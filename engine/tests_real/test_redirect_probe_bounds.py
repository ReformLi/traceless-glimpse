"""4b-4 之后的 P0：``resolve_redirects`` 的 Range 探测必须**有界**。

探测只要最终 URL 与 ``Content-Type``，正文一个字节都用不上。此前的非流式
``client.get()`` 会把整个响应体读进内存——服务端忽略 Range 时是全量（4b-4 R2 实测
4.2 MB / 13 s），碰上直播 / SSE 这类无限流就是读到 OOM。本文件锁的是防御本身：
读满 ``PROBE_MAX_BYTES`` 立刻断开，无限流也只能撑出一个预算大小的内存窗口。
"""
from __future__ import annotations

import inspect
import logging
import time

import httpx
import pytest

import services.redirect as redirect_service
from services.redirect import PROBE_MAX_BYTES, RedirectTarget, resolve_redirects
from testsupport.media_fixtures import ffmpeg_available, generate_all
from testsupport.media_server import MediaServer

pytestmark = pytest.mark.real

PROBE_URL = "http://8.8.8.8/file.mp4"


class _CountedStream(httpx.SyncByteStream):
    """按固定块大小产出 ``total`` 字节的响应体，并记录真正交出去的字节数。

    ``total=None`` 时永不收尾——那就是「无限流」现场：修复前探测会一直读下去，
    修复后读满预算即 break，交出的字节数只比预算多一块。
    """

    def __init__(self, *, chunk_size: int, total: int | None = None) -> None:
        self.chunk_size = chunk_size
        self._total = total
        self.yielded = 0

    def __iter__(self):
        while self._total is None or self.yielded < self._total:
            self.yielded += self.chunk_size
            yield b"x" * self.chunk_size


def _transport(handler) -> httpx.MockTransport:
    return httpx.MockTransport(handler)


def _probe_with(stream: _CountedStream, *, task_id: str = "probe-1") -> RedirectTarget:
    def handler(request: httpx.Request) -> httpx.Response:
        # 服务端忽略 Range: bytes=0-0，照发整个（无限）响应体
        return httpx.Response(
            200,
            stream=stream,
            headers={"content-type": "video/mp4", "accept-ranges": "none"},
        )

    return resolve_redirects(
        PROBE_URL, transport=_transport(handler), task_id=task_id
    )


# --------------------------------------------------------------------- 有界读取


def test_probe_stops_at_budget_on_full_body():
    stream = _CountedStream(chunk_size=512, total=10 * 1024 * 1024)

    target = _probe_with(stream)

    assert target.url == PROBE_URL
    assert target.content_type == "video/mp4"
    assert stream.yielded <= PROBE_MAX_BYTES + 512, (
        f"探测读满预算就该停手，实际交出 {stream.yielded} 字节"
    )


def test_probe_survives_infinite_body():
    """无限流（直播 / SSE 形态）不再把探测变成无界读取。

    ``total=None`` 的流永不收尾：修复前这里会挂住并涨内存，现在只消耗一个预算。
    """
    stream = _CountedStream(chunk_size=512, total=None)

    started = time.perf_counter()
    target = _probe_with(stream, task_id="infinite-1")
    elapsed = time.perf_counter() - started

    assert target.content_type == "video/mp4"
    assert stream.yielded <= PROBE_MAX_BYTES + 512
    assert elapsed < 1.0, f"探测耗时 {elapsed:.3f}s，早已越过有界读取的语义"


def test_probe_truncation_logged_with_task_id(caplog):
    stream = _CountedStream(chunk_size=512, total=10 * 1024 * 1024)

    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        _probe_with(stream, task_id="pump-1")

    logs = [r.getMessage() for r in caplog.records if r.name == "bgv.engine"]
    assert any(
        line.startswith(
            "resume_probe_truncated task_id=pump-1 reason=body_over_probe_budget "
        )
        for line in logs
    ), logs
    assert all("8.8.8.8" not in line for line in logs)


def test_probe_within_budget_is_silent(caplog):
    """预算内的小正文不该打审计行：``resume_probe_truncated`` 只在真被截断时出现。"""
    stream = _CountedStream(chunk_size=256, total=256)

    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        target = _probe_with(stream, task_id="tiny-1")

    assert target.content_type == "video/mp4"
    logs = [r.getMessage() for r in caplog.records if r.name == "bgv.engine"]
    assert not any("resume_probe_truncated" in line for line in logs)


def test_probe_has_no_unbounded_read_path():
    """结构性钉子：探测函数体内不存在非流式请求入口，想「顺手读完」也没有通道。"""
    source = inspect.getsource(resolve_redirects)
    assert "client.get(" not in source
    assert "client.stream(" in source


def test_every_hop_probes_bounded():
    """逐跳校验的中间跳同样只读头部预算，不因某一跳回全量而涨内存。"""
    streams: list[_CountedStream] = []

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/a":
            return httpx.Response(302, headers={"location": "/b"})
        stream = _CountedStream(chunk_size=512, total=None)
        streams.append(stream)
        return httpx.Response(200, stream=stream, headers={"content-type": "audio/mp4"})

    target = resolve_redirects(
        "http://8.8.8.8/a", transport=_transport(handler), max_hops=3
    )

    assert target.url == "http://8.8.8.8/b"
    assert target.content_type == "audio/mp4"
    assert streams and all(s.yielded <= PROBE_MAX_BYTES + 512 for s in streams)


# ------------------------------------------------------------------ 真 HTTP 取证

#: 探测专用大文件：4 MiB 填充字节，只为让「按块限速」的服务器吐出几十块。
#: 内容对探测毫无意义，所以不走 ffmpeg，fixture 里现场写。
_BULK_NAME = "probe_bulk.mp4"
_BULK_BYTES = 4 * 1024 * 1024
_BODY_CHUNK_BYTES = 65536


@pytest.fixture(scope="module")
def assets(tmp_path_factory):
    assert ffmpeg_available(), "本机无 ffmpeg：-m real 无法生成测试素材"
    dest = tmp_path_factory.mktemp("bgv-probe")
    generate_all(dest)
    (dest / "media" / _BULK_NAME).write_bytes(b"\0" * _BULK_BYTES)
    return dest


@pytest.fixture(scope="module")
def server(assets):
    with MediaServer(assets, generate=False) as srv:
        yield srv


class _CountingStream(httpx.SyncByteStream):
    def __init__(self, inner, owner: "_CountingTransport") -> None:
        self._inner = inner
        self._owner = owner

    def __iter__(self):
        for chunk in self._inner:
            self._owner.received += len(chunk)
            yield chunk

    def close(self) -> None:
        self._inner.close()


class _CountingTransport(httpx.BaseTransport):
    """真 socket 上记账：客户端到底从探测响应里收了多少字节。"""

    def __init__(self) -> None:
        self._inner = httpx.HTTPTransport(trust_env=False)
        self.received = 0

    def handle_request(self, request: httpx.Request) -> httpx.Response:
        response = self._inner.handle_request(request)
        response.stream = _CountingStream(response.stream, self)
        return response

    def close(self) -> None:
        self._inner.close()


def test_real_server_paced_body_does_not_slow_probe(server, assets, monkeypatch, caplog):
    """真服务器按块限速（每 64 KiB 之后 sleep）拖不住探测：只读一个预算就撤。

    这是 R2 那 13 s 的直接回归——不是把 13 s 优化成几秒，而是探测根本不再消费正文。
    判定用**字节记账**（真 socket 上客户端收到多少字节），耗时只作旁证：
    Windows 回环上的连接建立/关闭抖动足以让纯时间断言误报。
    同一条 URL 上再用修复前的写法（非流式 ``client.get``）量一次全读作对照。
    """
    monkeypatch.setattr(redirect_service, "validate_outbound_url", lambda url: url)
    body_size = (assets / "media" / _BULK_NAME).stat().st_size
    chunks = -(-body_size // _BODY_CHUNK_BYTES)
    assert chunks >= 30, f"素材太小（{chunks} 块），限速对照不成立"
    paced_url = server.base_url + f"/media/{_BULK_NAME}?no_range=1&chunk_delay_ms=20"
    server.reset_log()

    counting = _CountingTransport()
    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        started = time.perf_counter()
        target = resolve_redirects(
            paced_url, transport=counting, timeout=30.0, task_id="paced-1"
        )
        probe_elapsed = time.perf_counter() - started
        received = counting.received

        # 对照：修复前的探测就是这一行——非流式 get，正文全读进内存
        started = time.perf_counter()
        with httpx.Client(follow_redirects=False, timeout=30.0) as client:
            response = client.get(paced_url, headers={"Range": "bytes=0-0"})
            legacy_bytes = len(response.content)
            response.close()
        legacy_elapsed = time.perf_counter() - started

    assert target.content_type == "video/mp4"
    assert legacy_bytes == body_size, "对照确实读到了整包（服务器忽略了 Range）"
    assert received <= PROBE_MAX_BYTES + _BODY_CHUNK_BYTES, (
        f"4 MiB 正文里客户端只该收到一个预算，实际 {received} 字节"
    )
    assert probe_elapsed < legacy_elapsed / 2, (
        f"探测 {probe_elapsed:.3f}s vs 全读 {legacy_elapsed:.3f}s"
    )
    entries = [e for e in server.request_log if e["path"] == f"/media/{_BULK_NAME}"]
    assert len(entries) == 2, f"探测一次 + 对照一次：{entries}"
    assert entries[0]["range"] == "bytes=0-0" and entries[0]["no_range"]
    logs = [r.getMessage() for r in caplog.records if r.name == "bgv.engine"]
    assert any(
        line.startswith("resume_probe_truncated task_id=paced-1 ") for line in logs
    ), logs
    assert all("127.0.0.1" not in line for line in logs)



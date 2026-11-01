"""阶段 5 第 3 步 + 4b-4：直链断点续传（.part + Range + 降级）单元测试。

4b-4 裁决①：续传偏移**只**取 ``.part`` 的磁盘大小。上报进度在两种现场下都不可信：
yt-dlp 实测比磁盘少一块 64 KiB；引擎进程被硬停时，httpx 写缓冲里最后一段根本没落盘。
"""
from __future__ import annotations

import hashlib
import inspect
import logging
import os
from typing import Iterator

import httpx
import pytest

import services.redirect as redirect_service
from services.downloader import DownloadCancelled, DownloadIntegrityError, download_direct
from testsupport.media_fixtures import ffmpeg_available, generate_all
from testsupport.media_server import MediaServer

pytestmark = pytest.mark.real

MEDIA_URL = "http://8.8.8.8/file.mp4"
CHUNK = 70_000  # 大于 httpx 的 64 KiB 归并粒度，确保分多次写
UNFLUSHED_TAIL = 8192  # io 写缓冲的默认粒度：进程被硬停时留在内存里的那一段


@pytest.fixture(scope="module")
def assets(tmp_path_factory):
    assert ffmpeg_available(), "本机无 ffmpeg：-m real 无法生成测试素材"
    dest = tmp_path_factory.mktemp("bgv-resume")
    generate_all(dest)
    return dest


@pytest.fixture(scope="module")
def server(assets):
    with MediaServer(assets, generate=False) as srv:
        yield srv


def _transport(handler) -> httpx.MockTransport:
    return httpx.MockTransport(handler)


def _is_redirect_probe(request: httpx.Request) -> bool:
    return request.headers.get("range") == "bytes=0-0"


def _handler_for(handler):
    """跳过 resolve_redirects 的 Range 探测请求。"""

    def _dispatch(request: httpx.Request) -> httpx.Response:
        if _is_redirect_probe(request):
            return httpx.Response(200, content=b"")
        return handler(request)

    return _dispatch


def test_resume_from_part_uses_range_and_appends(tmp_path):
    (tmp_path / "file.mp4.part").write_bytes(b"abc")
    seen_ranges: list[str | None] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen_ranges.append(request.headers.get("range"))
        return httpx.Response(
            206, content=b"def", headers={"content-range": "bytes 3-5/6"}
        )

    path = download_direct(
        MEDIA_URL,
        tmp_path,
        transport=_transport(_handler_for(handler)),
        task_id="resume-1",
    )

    assert path == tmp_path / "file.mp4"
    assert path.read_bytes() == b"abcdef"
    assert seen_ranges == ["bytes=3-"]
    assert not (tmp_path / "file.mp4.part").exists()


def test_range_ignored_downgrades_to_full_download(tmp_path, caplog):
    (tmp_path / "file.mp4.part").write_bytes(b"abc")

    def handler(request: httpx.Request) -> httpx.Response:
        # 服务端忽略 Range（Accept-Ranges: none 行为）
        return httpx.Response(
            200,
            content=b"abcdef",
            headers={"content-length": "6", "accept-ranges": "none"},
        )

    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        path = download_direct(
            MEDIA_URL,
            tmp_path,
            transport=_transport(_handler_for(handler)),
            task_id="downgrade-1",
        )

    assert path.read_bytes() == b"abcdef"
    logs = [record.getMessage() for record in caplog.records]
    assert "resume_unsupported task_id=downgrade-1 reason=no_range" in logs
    assert all("http" not in line and "8.8.8.8" not in line for line in logs)


def test_fresh_download_does_not_send_range(tmp_path):
    seen_ranges: list[str | None] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen_ranges.append(request.headers.get("range"))
        return httpx.Response(200, content=b"xy", headers={"content-length": "2"})

    path = download_direct(
        MEDIA_URL,
        tmp_path,
        transport=_transport(_handler_for(handler)),
        task_id="fresh-1",
    )

    assert path.read_bytes() == b"xy"
    assert seen_ranges == [None]


def test_incomplete_206_keeps_part_for_next_resume(tmp_path):
    (tmp_path / "file.mp4.part").write_bytes(b"abc")

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            206, content=b"d", headers={"content-range": "bytes 3-9/10"}
        )

    with pytest.raises(DownloadIntegrityError):
        download_direct(
            MEDIA_URL,
            tmp_path,
            transport=_transport(_handler_for(handler)),
            task_id="short-1",
        )

    assert (tmp_path / "file.mp4.part").read_bytes() == b"abcd"
    assert not (tmp_path / "file.mp4").exists()


# -------------------------------------------------------------------- 4b-4 续传偏移


class _ChunkStream(httpx.SyncByteStream):
    """按块产出响应体，交出第 ``flip_after`` 块之后置起取消标志。

    取消检查在写块**之前**，所以标志置起后交出的那一块不会落盘：
    ``flip_after=2`` 意味着恰好 2 块写进了 ``.part``。
    """

    def __init__(self, chunks: list[bytes], *, flip_after: int) -> None:
        self._chunks = chunks
        self._flip_after = flip_after
        self.flag: list[bool] = []

    def __iter__(self) -> Iterator[bytes]:
        for index, chunk in enumerate(self._chunks, start=1):
            yield chunk
            if index == self._flip_after:
                self.flag.append(True)


def _ranged_handler(body: bytes, seen: list[str | None]):
    """真 Range 语义服务器（MockTransport 版）：按请求偏移回 206 尾部。"""

    def handler(request: httpx.Request) -> httpx.Response:
        raw = request.headers.get("range") or ""
        seen.append(raw)
        if not raw.startswith("bytes="):
            return httpx.Response(
                200, content=body, headers={"content-length": str(len(body))}
            )
        start = int(raw[len("bytes=") :].split("-", 1)[0])
        return httpx.Response(
            206,
            content=body[start:],
            headers={
                "content-range": f"bytes {start}-{len(body) - 1}/{len(body)}",
                "accept-ranges": "bytes",
            },
        )

    return handler


def _abort_once(tmp_path, chunks: list[bytes], *, task_id: str) -> tuple[int, int]:
    """跑一次「传到中途点取消」的下载，返回 (最后一次上报字节, ``.part`` 磁盘大小)。"""
    stream = _ChunkStream(chunks, flip_after=2)
    total = sum(len(chunk) for chunk in chunks)
    reported: list[int] = []

    with pytest.raises(DownloadCancelled):
        download_direct(
            MEDIA_URL,
            tmp_path,
            transport=_transport(
                _handler_for(
                    lambda request: httpx.Response(
                        200, stream=stream, headers={"content-length": str(total)}
                    )
                )
            ),
            task_id=task_id,
            should_abort=lambda: bool(stream.flag),
            on_progress=lambda done, _total: reported.append(done),
        )

    return reported[-1], (tmp_path / "file.mp4.part").stat().st_size


def test_resume_offset_follows_part_size_after_hard_kill(tmp_path):
    """中断上报 2 块、磁盘只剩 2 块减缓冲：续传起点必须跟着磁盘走。

    差值就是没落盘的那段缓冲 —— 若偏移取上报值，服务端会被要求从
    ``bytes=2×CHUNK-`` 起补，而本地那段根本不存在，产物必然对不上。
    """
    body = bytes((index * 31 + 7) % 256 for index in range(CHUNK * 4))
    chunks = [body[start : start + CHUNK] for start in range(0, len(body), CHUNK)]

    reported, on_disk = _abort_once(tmp_path, chunks, task_id="kill-1")
    # 协作式取消会正常关闭文件句柄，上报与磁盘此刻一致（4b-3 已验）
    assert reported == on_disk == CHUNK * 2

    # 模拟引擎进程被硬停：写缓冲尾段随内存一起消失
    part = tmp_path / "file.mp4.part"
    os.truncate(part, on_disk - UNFLUSHED_TAIL)
    disk_after_kill = part.stat().st_size
    assert disk_after_kill == reported - UNFLUSHED_TAIL

    seen: list[str] = []
    path = download_direct(
        MEDIA_URL,
        tmp_path,
        transport=_transport(_handler_for(_ranged_handler(body, seen))),
        task_id="kill-2",
    )

    assert seen == [f"bytes={disk_after_kill}-"], "续传偏移必须等于 .part 磁盘大小"
    assert disk_after_kill != reported, "起始偏移不得等于中断上报字节"
    one_shot = tmp_path / "one-shot"
    reference = download_direct(
        MEDIA_URL,
        one_shot,
        transport=_transport(_handler_for(_ranged_handler(body, []))),
        task_id="kill-0",
    )
    assert hashlib.sha256(path.read_bytes()).hexdigest() == hashlib.sha256(
        reference.read_bytes()
    ).hexdigest()
    assert path.stat().st_size == reference.stat().st_size == len(body)


def test_download_direct_has_no_caller_supplied_offset(tmp_path):
    """接口形状守卫：签名里不允许出现调用方传进来的续传偏移。

    一旦有人加 ``resume_from`` / ``bytes_download`` 参数，客户端上报就重新变成
    可信输入，裁决①的前提被破坏。这条不测行为，只钉接口。
    """
    forbidden = ("offset", "resume", "downloaded", "bytes")
    offenders = [
        name
        for name in inspect.signature(download_direct).parameters
        if any(token in name.lower() for token in forbidden)
    ]
    assert offenders == [], f"download_direct 出现了可传入偏移的参数：{offenders}"


def test_real_server_no_range_downgrades_to_full_download(server, assets, tmp_path, caplog, monkeypatch):
    """验收 4：同一 URL 加 ``?no_range=1`` 假装不支持 Range → 全量重下 + 审计行。

    路径保持不变才能命中上一轮留下的同名 ``.part``；测试服务器在回环，
    生产校验器按设计拒绝，这里在 redirect 模块注入放行（与 ``validator`` 注入同源），
    生产默认仍是统一校验器。
    """
    monkeypatch.setattr(redirect_service, "validate_outbound_url", lambda url: url)
    full = (assets / "media" / "sample.mp4").read_bytes()
    (tmp_path / "sample.mp4.part").write_bytes(full[:4096])
    server.reset_log()

    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        path = download_direct(
            server.base_url + "/media/sample.mp4?no_range=1",
            tmp_path,
            task_id="no-range-1",
            validator=lambda url: url,
        )

    assert path == tmp_path / "sample.mp4"
    assert path.read_bytes() == full
    assert hashlib.sha256(path.read_bytes()).hexdigest() == hashlib.sha256(full).hexdigest()
    assert not (tmp_path / "sample.mp4.part").exists()

    # 只看引擎自己的审计行：httpx 的 INFO 请求日志本来就带完整 URL，不属于本条断言范围
    logs = [
        record.getMessage()
        for record in caplog.records
        if record.name == "bgv.engine"
    ]
    assert "resume_unsupported task_id=no-range-1 reason=no_range" in logs
    assert all("127.0.0.1" not in line for line in logs)

    ranges = [entry["range"] for entry in server.request_log if entry["path"] == "/media/sample.mp4"]
    assert "bytes=4096-" in ranges, f"客户端确实发出过续传请求：{ranges}"
    # 同一行还要证明：那个 200 是服务器「忽略了 Range」，不是客户端没发续传头
    assert any(
        entry["range"] == "bytes=4096-" and entry["no_range"]
        for entry in server.request_log
    )


# ------------------------------------------------- 4b-4 裁决①：续传前的 Content-Type 门禁


def _probe_transport(handler, *, probe_content_type: str | None) -> httpx.MockTransport:
    """Range 探测按指定 Content-Type 应答，其余请求交给 ``handler``。

    ``resolve_redirects`` 用 ``Range: bytes=0-0`` 探测终点，续传门禁读的就是这一跳的
    Content-Type；所以门禁行为只能在探测应答里造，正文 handler 管不到。
    """

    def _dispatch(request: httpx.Request) -> httpx.Response:
        if _is_redirect_probe(request):
            headers = (
                {} if probe_content_type is None else {"content-type": probe_content_type}
            )
            return httpx.Response(200, content=b"", headers=headers)
        return handler(request)

    return httpx.MockTransport(_dispatch)


def test_resume_rejected_when_endpoint_declares_html(tmp_path, caplog):
    """验收 4 的防御面：残留 ``.part`` + 终点声明 text/html → 拒绝续传，不拼错误页。"""
    part = tmp_path / "file.mp4.part"
    part.write_bytes(b"abc")
    seen_ranges: list[str | None] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen_ranges.append(request.headers.get("range"))
        return httpx.Response(200, content=b"def", headers={"content-length": "3"})

    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        with pytest.raises(DownloadIntegrityError, match="非媒体"):
            download_direct(
                MEDIA_URL,
                tmp_path,
                transport=_probe_transport(handler, probe_content_type="text/html; charset=utf-8"),
                task_id="resume-html",
            )

    assert seen_ranges == [], "拒绝发生在发出续传请求之前"
    assert part.read_bytes() == b"abc", "既不拼进产物，也不擅自清理现场"
    assert not (tmp_path / "file.mp4").exists()
    logs = [r.getMessage() for r in caplog.records if r.name == "bgv.engine"]
    assert (
        "resume_rejected task_id=resume-html reason=unexpected_content_type content_type=text/html"
        in logs
    )
    # 审计行只留裸媒体类型，参数尾巴（charset 等）不进日志
    assert all("charset" not in line for line in logs)


@pytest.mark.parametrize(
    "probe_content_type",
    ["video/mp4", "application/octet-stream", "application/mp4", None],
)
def test_resume_allowed_for_media_like_types(tmp_path, caplog, probe_content_type):
    """白名单放行：视频、说不清的八位流、MP4 容器、以及「没声明」都照常续传。

    ``None`` 也放行是刻意的边界——没有声明就没有「这是错误页」的证据，
    不该因此打断正常下载；真正要拦的是显式声明成非媒体。
    """
    (tmp_path / "file.mp4.part").write_bytes(b"abc")

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            206, content=b"def", headers={"content-range": "bytes 3-5/6"}
        )

    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        path = download_direct(
            MEDIA_URL,
            tmp_path,
            transport=_probe_transport(handler, probe_content_type=probe_content_type),
            task_id="resume-ok",
        )

    assert path.read_bytes() == b"abcdef"
    logs = [r.getMessage() for r in caplog.records if r.name == "bgv.engine"]
    assert not any("resume_rejected" in line for line in logs)


@pytest.mark.xfail(
    strict=True,
    reason="4b-4 裁决①：门禁现在只拦「有残留要续传」，全新下载还没校验 Content-Type，"
    "错误页仍会落成产物（smoke-report §8-8，留 M4）。本用例断言的是**应有行为**，"
    "strict=True 保证哪天顺手修好了会 XPASS 转红，提醒转正而不是悄悄漂移。",
)
def test_fresh_download_should_refuse_non_media_body(tmp_path):
    """应有行为：终点声明 text/html 时，即便目录里没有残留，也不该把错误页存成产物。"""
    body = b"<html>502 Bad Gateway</html>"

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200, content=body, headers={"content-length": str(len(body))}
        )

    with pytest.raises(DownloadIntegrityError):
        download_direct(
            MEDIA_URL,
            tmp_path,
            transport=_probe_transport(handler, probe_content_type="text/html"),
            task_id="fresh-html",
        )

    assert list(tmp_path.iterdir()) == [], "拒绝要发生在写第一块之前"


# ------------------------------------------------- 4b-4 裁决②：.part 名命不中要留痕


def test_part_name_mismatch_is_fresh_download_with_audit(tmp_path, caplog):
    """裁决②：残留名字对不上时按全新下载走，但必须留下 ``resume_missed`` 痕迹。

    现场是 4b-4 R4 的镜像：终点声明 ``audio/mp4`` → 产物名被校正成 ``file.m4a``，
    期待的 ``file.m4a.part`` 命不中上一次留下的 ``file.mp4.part``。
    静默可以，无痕不行——否则「续传怎么没生效」根本查不出来。
    """
    stray = tmp_path / "file.mp4.part"
    stray.write_bytes(b"old-residue")
    seen_ranges: list[str | None] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen_ranges.append(request.headers.get("range"))
        return httpx.Response(200, content=b"newbody", headers={"content-length": "7"})

    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        path = download_direct(
            MEDIA_URL,
            tmp_path,
            transport=_probe_transport(handler, probe_content_type="audio/mp4"),
            task_id="miss-1",
        )

    assert path == tmp_path / "file.m4a"
    assert path.read_bytes() == b"newbody"
    assert seen_ranges == [None], "命不中就是全新下载：不发出续传头"
    assert stray.read_bytes() == b"old-residue", "别人的现场不擅自清理"
    logs = [r.getMessage() for r in caplog.records if r.name == "bgv.engine"]
    assert (
        "resume_missed task_id=miss-1 reason=part_name_mismatch "
        "expected=file.m4a.part found=file.mp4.part" in logs
    )
    assert not any("resume_unsupported" in line for line in logs)


def test_unrelated_part_residue_is_not_reported_as_missed(tmp_path, caplog):
    """``found=`` 只报同一起名的残留：输出目录是多任务共用的，别把邻居写成现场。"""
    (tmp_path / "bulk.mp4.part").write_bytes(b"someone-else")

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=b"newbody", headers={"content-length": "7"})

    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        download_direct(
            MEDIA_URL,
            tmp_path,
            transport=_probe_transport(handler, probe_content_type="video/mp4"),
            task_id="no-miss",
        )

    logs = [r.getMessage() for r in caplog.records if r.name == "bgv.engine"]
    assert not any("resume_missed" in line for line in logs)



def test_real_server_html_content_type_blocks_resume(server, assets, tmp_path, caplog, monkeypatch):
    """裁决①补的防御走一遍真 HTTP：终点改口声明 text/html → 拒绝把残留拼上去。

    ``?as_html=1`` 只换 ``Content-Type``、不换路径，所以同名 ``.part`` 照样命得中，
    现场与「错误页被存成了 .part」一致。用真服务器而不是 MockTransport，
    是为了证明门禁读的是真实响应头，不是注入件的形状。
    """
    monkeypatch.setattr(redirect_service, "validate_outbound_url", lambda url: url)
    full = (assets / "media" / "sample.mp4").read_bytes()
    part = tmp_path / "sample.mp4.part"
    part.write_bytes(full[:4096])
    server.reset_log()

    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        with pytest.raises(DownloadIntegrityError, match="非媒体"):
            download_direct(
                server.base_url + "/media/sample.mp4?as_html=1",
                tmp_path,
                task_id="gate-html",
                validator=lambda url: url,
            )

    assert part.read_bytes() == full[:4096], "残留原样留着：不拼、不删"
    assert not (tmp_path / "sample.mp4").exists()
    logs = [r.getMessage() for r in caplog.records if r.name == "bgv.engine"]
    assert (
        "resume_rejected task_id=gate-html reason=unexpected_content_type content_type=text/html"
        in logs
    )
    # 拒绝发生在发出续传请求之前：服务器只见过那次 bytes=0-0 探测
    ranges = [e["range"] for e in server.request_log if e["path"] == "/media/sample.mp4"]
    assert ranges == ["bytes=0-0"], f"没有发出过续传请求：{ranges}"

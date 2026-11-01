"""4b-3 协作式断流：两条下载循环都在写下一块之前查取消标志。

覆盖两条独立代码路径（根因不同，分开断言）：
- ``download_direct``：httpx 流式循环，命中即抛 :class:`DownloadCancelled`，
  已写字节留在 ``.part``，不改名、不产出成品；
- ``download_media``：yt-dlp ``progress_hooks``，命中即抛；外层用
  ``abort_signalled`` 判定，不依赖 yt-dlp 把 hook 异常包装成什么类型。
"""
from __future__ import annotations

import logging
from pathlib import Path
from typing import Any, Iterator

import httpx
import pytest

from services import downloader as downloader_service
from services.downloader import (
    DownloadCancelled,
    download_direct,
    download_media,
)

pytestmark = pytest.mark.real

MEDIA_URL = "http://8.8.8.8/file.mp4"
CHUNK = 70_000  # 大于 httpx 的 64 KiB 归并粒度，确保分多次写
BODY = CHUNK * 3


class _ChunkStream(httpx.SyncByteStream):
    """按块产出响应体；``flip_after_first`` 在交出首块之后置取消标志。

    真机上「用户点取消」发生在传输途中，不是在请求之前，所以标志由流本身
    在中途翻起——循环下一次检查必然看到它。
    """

    def __init__(self, chunks: list[bytes], *, flip_after_first: bool = False) -> None:
        self._chunks = chunks
        self._flip_after_first = flip_after_first
        self.flag: list[bool] = []

    def __iter__(self) -> Iterator[bytes]:
        for index, chunk in enumerate(self._chunks):
            yield chunk
            if self._flip_after_first and index == 0:
                self.flag.append(True)


def _is_redirect_probe(request: httpx.Request) -> bool:
    return request.headers.get("range") == "bytes=0-0"


def _handler_for(handler):
    def _dispatch(request: httpx.Request) -> httpx.Response:
        if _is_redirect_probe(request):
            return httpx.Response(200, content=b"")
        return handler(request)

    return _dispatch


def _stream_handler(stream: _ChunkStream, *, total: int):
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200, stream=stream, headers={"content-length": str(total)}
        )

    return handler


def test_direct_abort_keeps_part_and_names_no_output(tmp_path):
    """中止发生在两块之间：已写字节进 ``.part``，成品文件不存在。"""
    stream = _ChunkStream([b"a" * CHUNK] * 3, flip_after_first=True)
    progress: list[tuple[int, int | None]] = []

    with pytest.raises(DownloadCancelled) as caught:
        download_direct(
            MEDIA_URL,
            tmp_path,
            transport=httpx.MockTransport(_handler_for(_stream_handler(stream, total=BODY))),
            task_id="abort-1",
            should_abort=lambda: bool(stream.flag),
            on_progress=lambda done, total: progress.append((done, total)),
        )

    part = tmp_path / "file.mp4.part"
    assert part.is_file(), "取消必须留下可续传的 .part"
    written = part.stat().st_size
    assert 0 < written < BODY, f"应当写了一部分就停手：{written}"
    assert not (tmp_path / "file.mp4").exists(), "取消不得产出成品"
    # 审计行的字节数、进度回调的最后一次上报、磁盘上的真实大小三者必须一致
    assert str(written) in str(caught.value)
    assert progress[-1][0] == written
    assert progress[-1][1] == BODY


def test_direct_abort_before_first_chunk_writes_nothing(tmp_path):
    """取消在下载开始前就已置位：一块都不写。"""
    stream = _ChunkStream([b"a" * CHUNK] * 3)
    handler = _handler_for(_stream_handler(stream, total=BODY))

    with pytest.raises(DownloadCancelled):
        download_direct(
            MEDIA_URL,
            tmp_path,
            transport=httpx.MockTransport(handler),
            task_id="abort-2",
            should_abort=lambda: True,
        )

    assert (tmp_path / "file.mp4.part").stat().st_size == 0
    assert not (tmp_path / "file.mp4").exists()


def test_direct_without_should_abort_completes(tmp_path):
    """未传回调时行为与 4b-3 之前一致：全量写入并改名。"""
    stream = _ChunkStream([b"a" * CHUNK] * 3)
    path = download_direct(
        MEDIA_URL,
        tmp_path,
        transport=httpx.MockTransport(_handler_for(_stream_handler(stream, total=BODY))),
        task_id="abort-3",
    )
    assert path.read_bytes() == b"a" * BODY
    assert not (tmp_path / "file.mp4.part").exists()


def _media_callbacks(options: dict[str, Any]) -> list[Any]:
    return list(options.get("progress_hooks") or [])


class _FakeYDL:
    """替代 ``yt_dlp.YoutubeDL``：下载阶段按脚本上报进度并可中途抛错。

    ``wrap_hook_error`` 复现 yt-dlp 把 hook 异常包成通用 DownloadError 的
    情形——此时仍须靠 ``abort_signalled`` 判定为取消，而不是落成失败。
    """

    def __init__(
        self,
        options: dict[str, Any],
        *,
        target: Path,
        wrap_hook_error: bool = False,
        fail_without_hook: bool = False,
    ) -> None:
        self.options = options
        self._target = target
        self._wrap_hook_error = wrap_hook_error
        self._fail_without_hook = fail_without_hook
        self.reports: list[tuple[int, int]] = []

    def __enter__(self):
        return self

    def __exit__(self, *exc_info):
        return False

    def extract_info(self, url: str, download: bool = False) -> dict[str, Any]:
        info: dict[str, Any] = {
            "formats": [{"url": MEDIA_URL}],
            "requested_downloads": [{"url": MEDIA_URL}],
        }
        if not download:
            return info
        hooks = _media_callbacks(self.options)
        total = 900
        for downloaded in (300, 600, 900):
            self.reports.append((downloaded, total))
            for hook in hooks:
                payload = {
                    "status": "downloading",
                    "downloaded_bytes": downloaded,
                    "total_bytes": total,
                }
                if self._wrap_hook_error:
                    try:
                        hook(payload)
                    except Exception as exc:  # noqa: BLE001 —— 复现 yt-dlp 的包装
                        raise RuntimeError("ERROR: interrupted by user") from exc
                else:
                    hook(payload)
        if self._fail_without_hook:
            raise RuntimeError("ERROR: no progress hook reported")
        self._target.write_bytes(b"clip")
        info["filename"] = self._target.name
        return info

    def prepare_filename(self, info: dict[str, Any]) -> str:
        return str(self._target)


def test_media_abort_in_progress_hook_raises_cancelled(tmp_path):
    """hook 内命中取消标志 → DownloadCancelled；取消前的真实进度已透传。"""
    target = tmp_path / "clip.mp4"
    progress: list[tuple[int, int | None]] = []

    def _should_abort() -> bool:
        # 第 1、2 次上报放行，累计到 2 条进度后判定为「用户点了取消」
        return len(progress) >= 2

    def _factory(options: dict[str, Any]) -> _FakeYDL:
        return _FakeYDL(options, target=target)

    with pytest.raises(DownloadCancelled):
        download_media(
            MEDIA_URL,
            tmp_path,
            task_id="abort-media-1",
            validator=lambda url: url,
            ydl_factory=_factory,
            should_abort=_should_abort,
            on_progress=lambda done, total: progress.append((done, total)),
        )

    assert progress == [(300, 900), (600, 900)], "取消前的真实进度必须已经上报"
    assert not target.exists(), "取消不得产出成品"


def test_media_abort_when_ytdlp_wraps_hook_error_still_cancelled(tmp_path):
    """hook 异常被 yt-dlp 包成通用错误：仍判定为取消，不落成失败。"""
    target = tmp_path / "clip.mp4"
    progress: list[tuple[int, int | None]] = []

    def _factory(options: dict[str, Any]) -> _FakeYDL:
        return _FakeYDL(options, target=target, wrap_hook_error=True)

    with pytest.raises(DownloadCancelled) as caught:
        download_media(
            MEDIA_URL,
            tmp_path,
            task_id="abort-media-2",
            validator=lambda url: url,
            ydl_factory=_factory,
            should_abort=lambda: len(progress) >= 1,
            on_progress=lambda done, total: progress.append((done, total)),
        )

    assert progress == [(300, 900)], "首块进度在取消之前已经写入"
    assert "abort-media-2" in str(caught.value)
    assert not target.exists()


def test_media_generic_failure_without_abort_stays_generic(tmp_path):
    """没有取消标志时的通用失败仍是原异常，不得被误判成 DownloadCancelled。"""
    target = tmp_path / "clip.mp4"

    def _factory(options: dict[str, Any]) -> _FakeYDL:
        return _FakeYDL(options, target=target, fail_without_hook=True)

    with pytest.raises(RuntimeError):
        download_media(
            MEDIA_URL,
            tmp_path,
            task_id="abort-media-3",
            validator=lambda url: url,
            ydl_factory=_factory,
            should_abort=lambda: False,
        )


def test_media_without_callbacks_gets_no_progress_hooks(tmp_path, monkeypatch):
    """不传回调 → 不注入 progress_hooks（默认下载路径与 4b-3 之前完全一致）。"""
    monkeypatch.setattr(downloader_service, "_ffprobe_duration", lambda path: 5.0)
    target = tmp_path / "clip.mp4"
    seen: list[dict[str, Any]] = []

    def _factory(options: dict[str, Any]) -> _FakeYDL:
        seen.append(options)
        return _FakeYDL(options, target=target)

    path = download_media(
        MEDIA_URL,
        tmp_path,
        task_id="abort-media-4",
        validator=lambda url: url,
        ydl_factory=_factory,
    )

    assert "progress_hooks" not in seen[0]
    assert path == target


def test_direct_abort_log_line_carries_only_task_id(tmp_path, caplog):
    """断流审计行脱敏：只有 task_id + 字节数，没有 URL / Cookie / UA。"""
    stream = _ChunkStream([b"a" * CHUNK] * 3, flip_after_first=True)
    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        with pytest.raises(DownloadCancelled):
            download_direct(
                MEDIA_URL,
                tmp_path,
                transport=httpx.MockTransport(
                    _handler_for(_stream_handler(stream, total=BODY))
                ),
                task_id="abort-4",
                should_abort=lambda: bool(stream.flag),
            )
    for line in [record.getMessage() for record in caplog.records]:
        for token in ("http://", "8.8.8.8", "cookie", "Cookie", "Mozilla"):
            assert token not in line, f"审计行泄漏 {token!r}：{line}"

"""阶段 5 第 3 步：直链断点续传（.part + Range + 降级）单元测试。"""
from __future__ import annotations

import logging

import httpx
import pytest

from services.downloader import DownloadIntegrityError, download_direct

pytestmark = pytest.mark.real

MEDIA_URL = "http://8.8.8.8/file.mp4"


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
    assert "resume_downgraded task_id=downgrade-1" in logs
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

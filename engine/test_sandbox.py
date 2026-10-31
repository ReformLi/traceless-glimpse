"""输出沙箱单测（FR-SEC-03 / 验收标准 4）：扩展名白名单 + 0700 + 目录内落盘。"""
from __future__ import annotations

from pathlib import Path

import httpx
import pytest

import security as security_module
from security import (
    OutputFilenameRejectedError,
    prepare_output_dir,
    validate_output_filename,
    validate_output_target,
)
from services import downloader
from services.downloader import download_direct, download_media


# ---------------------------------------------------------------------------
# 文件名 / 扩展名
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "name",
    ["clip.mp4", "CLIP.MP4", "audio.m4a", "stream.ts", "movie.mkv"],
)
def test_allowed_extensions_pass(name: str):
    assert validate_output_filename(name) == name


@pytest.mark.parametrize(
    ("name", "extension"),
    [
        ("evil.exe", ".exe"),
        ("page.html", ".html"),
        ("archive.zip", ".zip"),
        ("payload", ""),
    ],
)
def test_disallowed_extensions_rejected(name: str, extension: str):
    with pytest.raises(OutputFilenameRejectedError) as exc:
        validate_output_filename(name)
    assert exc.value.code == "OUTPUT_DIR_REJECTED"
    assert exc.value.detail == {
        "reason": "extension_not_allowed",
        "extension": extension,
    }


@pytest.mark.parametrize(
    "name",
    ["../../evil.exe", r"..\..\evil.exe", "/tmp/evil.exe", "C:\\temp\\evil.exe"],
)
def test_path_traversal_stripped_and_extension_still_checked(name: str):
    with pytest.raises(OutputFilenameRejectedError):
        validate_output_filename(name)


def test_path_traversal_with_allowed_extension_uses_basename_only():
    assert validate_output_filename("../../clip.mp4") == "clip.mp4"


@pytest.mark.parametrize(
    "name",
    ["", ".", "..", "clip.mp4.", "clip.mp4 ", "con.mp4", "LPT1.webm", "a\x00b.mp4"],
)
def test_unsafe_names_rejected(name: str):
    with pytest.raises(OutputFilenameRejectedError) as exc:
        validate_output_filename(name)
    assert exc.value.detail["reason"] in {"filename_rejected", "extension_not_allowed"}


# ---------------------------------------------------------------------------
# 0700 与目录约束
# ---------------------------------------------------------------------------


def test_prepare_output_dir_creates_nested_and_sets_0700(tmp_path, monkeypatch):
    calls: list[tuple[str, int]] = []
    monkeypatch.setattr(security_module.os, "chmod", lambda path, mode: calls.append((str(path), mode)))
    target = tmp_path / "nested" / "out"
    out = prepare_output_dir(target)
    assert out.is_dir()
    assert calls[-1][1] == 0o700


def test_validate_output_target_rejects_outside_dir(tmp_path):
    inside = tmp_path / "clip.mp4"
    outside = tmp_path.parent / "clip.mp4"
    assert validate_output_target(tmp_path, inside) == inside
    with pytest.raises(security_module.SecurityError) as exc:
        validate_output_target(tmp_path, outside)
    assert exc.value.detail == {"reason": "output_outside_dir"}


# ---------------------------------------------------------------------------
# download_direct：拒绝时不落盘
# ---------------------------------------------------------------------------


def _transport_ok(body: bytes = b"abc") -> httpx.MockTransport:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, headers={"content-length": str(len(body))}, content=body)

    return httpx.MockTransport(handler)


def test_direct_disallowed_extension_writes_nothing(tmp_path):
    with pytest.raises(OutputFilenameRejectedError):
        download_direct(
            "http://8.8.8.8/media/evil.exe",
            tmp_path,
            transport=_transport_ok(),
            validator=lambda url: url,
        )
    assert list(tmp_path.iterdir()) == []


def test_direct_allowed_extension_writes_final_file_only(tmp_path):
    path = download_direct(
        "http://8.8.8.8/media/clip.mp4",
        tmp_path,
        transport=_transport_ok(),
        validator=lambda url: url,
    )
    assert path.name == "clip.mp4"
    assert path.read_bytes() == b"abc"
    assert [item.name for item in tmp_path.iterdir()] == ["clip.mp4"]


# ---------------------------------------------------------------------------
# download_media：产物扩展名 / 目录约束
# ---------------------------------------------------------------------------


class _FakeYDL:
    def __init__(self, options, target_name: str):
        self._target = Path(
            options["outtmpl"]
            .replace("%(title)s", Path(target_name).stem)
            .replace("%(ext)s", Path(target_name).suffix.lstrip("."))
        )

    def __enter__(self):
        return self

    def __exit__(self, *exc_info):
        return False

    def extract_info(self, url, download: bool = False):
        if not download:
            return {
                "formats": [{"url": "http://8.8.8.8/v.mp4"}],
                "requested_downloads": [{"url": "http://8.8.8.8/v.mp4"}],
            }
        self._target.write_bytes(b"MZ")
        return {"requested_downloads": [{"url": "http://8.8.8.8/v.mp4"}]}

    def prepare_filename(self, info):
        return str(self._target)


def _factory_for(target_name: str):
    return lambda options: _FakeYDL(options, target_name)


def test_media_disallowed_extension_removed(tmp_path):
    with pytest.raises(OutputFilenameRejectedError):
        download_media(
            "http://8.8.8.8/video",
            tmp_path,
            ydl_factory=_factory_for("evil.exe"),
            validator=lambda url: url,
        )
    assert list(tmp_path.iterdir()) == []


def test_media_allowed_extension_kept(tmp_path, monkeypatch):
    monkeypatch.setattr(downloader, "_ffprobe_duration", lambda path: 5.0)
    path = download_media(
        "http://8.8.8.8/video",
        tmp_path,
        ydl_factory=_factory_for("clip.mp4"),
        validator=lambda url: url,
    )
    assert path.name == "clip.mp4"
    assert path.read_bytes() == b"MZ"


def test_media_target_outside_output_dir_rejected(tmp_path, monkeypatch):
    monkeypatch.setattr(downloader, "_ffprobe_duration", lambda path: 5.0)
    outside = tmp_path.parent / "clip.mp4"

    class _EscapingYDL(_FakeYDL):
        def prepare_filename(self, info):
            return str(outside)

        def extract_info(self, url, download: bool = False):
            info = super().extract_info(url, download)
            if download:
                outside.write_bytes(b"MZ")
            return info

    with pytest.raises(security_module.SecurityError) as exc:
        download_media(
            "http://8.8.8.8/video",
            tmp_path,
            ydl_factory=lambda options: _EscapingYDL(options, "clip.mp4"),
            validator=lambda url: url,
        )
    assert exc.value.detail == {"reason": "output_outside_dir"}
    outside.unlink(missing_ok=True)

"""惰性 Cookie 单测（FR-SEC-02 / 验收标准 2）。"""
from __future__ import annotations

import os
import time
from pathlib import Path

import httpx
import pytest

from services import cookies
from services import downloader
from services.downloader import DownloadIntegrityError, download_direct, download_media

COOKIE_TEXT = (
    "# Netscape HTTP Cookie File\n"
    "8.8.8.8\tFALSE\t/\tFALSE\t0\tsid\tgood-cookie\n"
    ".example.com\tTRUE\t/\tFALSE\t0\ttheme\tdark\n"
    ".example.com\tTRUE\t/private\tTRUE\t0\tsecret\ts3cr3t\n"
    "#HttpOnly_.example.com\tTRUE\t/\tFALSE\t0\thttp_only\tonly\n"
    "expired.example.com\tFALSE\t/\tFALSE\t1\tgone\told\n"
)


@pytest.fixture
def cookie_file(tmp_path, monkeypatch) -> Path:
    path = tmp_path / "cookies.txt"
    path.write_text(COOKIE_TEXT, encoding="utf-8")
    old = time.time() - 3600
    os.utime(path, (old, old))
    monkeypatch.setenv(cookies.COOKIE_FILE_ENV, str(path))
    return path


@pytest.fixture
def no_cookie_env(monkeypatch):
    monkeypatch.delenv(cookies.COOKIE_FILE_ENV, raising=False)


def _count_reads(monkeypatch) -> dict[str, int]:
    calls = {"load": 0}
    real = cookies.load_cookies

    def counting(path):
        calls["load"] += 1
        return real(path)

    monkeypatch.setattr(cookies, "load_cookies", counting)
    return calls


# ---------------------------------------------------------------------------
# 解析与匹配
# ---------------------------------------------------------------------------


def test_parse_netscape_fields_and_httponly():
    parsed = cookies.parse_netscape(COOKIE_TEXT)
    assert len(parsed) == 5
    sid = parsed[0]
    assert (sid.name, sid.value, sid.domain, sid.path) == ("sid", "good-cookie", "8.8.8.8", "/")
    assert sid.include_subdomains is False
    secret = parsed[2]
    assert secret.secure is True and secret.path == "/private"
    http_only = parsed[3]
    assert http_only.http_only is True
    assert http_only.domain == ".example.com"


def test_cookie_header_filters_domain_path_secure_and_expiry():
    parsed = cookies.parse_netscape(COOKIE_TEXT)
    now = time.time()
    assert (
        cookies.cookie_header(parsed, "https://example.com/", now=now)
        == "theme=dark; http_only=only"
    )
    assert (
        cookies.cookie_header(parsed, "http://sub.example.com/", now=now)
        == "theme=dark; http_only=only"
    )
    assert (
        cookies.cookie_header(parsed, "http://example.com/private/x", now=now)
        == "theme=dark; http_only=only"
    )
    assert (
        cookies.cookie_header(parsed, "https://example.com/private/x", now=now)
        == "theme=dark; secret=s3cr3t; http_only=only"
    )
    expired_host = cookies.cookie_header(parsed, "https://expired.example.com/", now=now)
    assert expired_host == "theme=dark; http_only=only"
    assert "gone=old" not in expired_host


def test_env_missing_returns_none(no_cookie_env):
    assert cookies.load_env_cookies() is None
    assert cookies.header_from_env("https://example.com/") is None


def test_env_header_from_file_and_mtime_unchanged(cookie_file: Path):
    before = cookie_file.stat().st_mtime
    assert cookies.header_from_env("http://8.8.8.8/v.mp4") == "sid=good-cookie"
    assert cookie_file.stat().st_mtime == before


def test_playwright_cookies_keep_domain_scope():
    parsed = cookies.parse_netscape(COOKIE_TEXT)
    items = cookies.playwright_cookies(parsed)
    assert items[0]["domain"] == "8.8.8.8"
    assert items[1]["domain"] == "example.com"
    assert items[3]["httpOnly"] is True


def test_cookie_file_copy_is_read_only_for_original(cookie_file: Path):
    original = cookie_file.read_text(encoding="utf-8")
    before = cookie_file.stat().st_mtime
    with cookies.cookie_file_copy("task-copy") as copy:
        assert copy is not None
        assert copy.read_text(encoding="utf-8") == original
        copy.write_text("mutated", encoding="utf-8")
    assert not copy.exists()
    assert cookie_file.read_text(encoding="utf-8") == original
    assert cookie_file.stat().st_mtime == before


# ---------------------------------------------------------------------------
# download_direct：none / lazy / explicit
# ---------------------------------------------------------------------------


def _direct_transport(requests: list[dict]):
    def handler(request: httpx.Request) -> httpx.Response:
        if request.headers.get("range"):
            return httpx.Response(200, content=b"")  # 重定向探测
        headers = {key.lower(): value for key, value in request.headers.items()}
        requests.append(headers)
        if "cookie" not in headers:
            return httpx.Response(403)
        return httpx.Response(200, headers={"content-length": "3"}, content=b"abc")

    return httpx.MockTransport(handler)


def test_direct_none_never_reads_or_retries(tmp_path, cookie_file, monkeypatch):
    calls = _count_reads(monkeypatch)
    requests: list[dict] = []
    with pytest.raises(DownloadIntegrityError):
        download_direct(
            "http://8.8.8.8/clip.mp4",
            tmp_path,
            cookie_policy="none",
            transport=_direct_transport(requests),
            validator=lambda url: url,
        )
    assert len(requests) == 1
    assert "cookie" not in requests[0]
    assert calls["load"] == 0


def test_direct_lazy_reads_once_and_retries_once(tmp_path, cookie_file, monkeypatch):
    calls = _count_reads(monkeypatch)
    requests: list[dict] = []
    path = download_direct(
        "http://8.8.8.8/clip.mp4",
        tmp_path,
        cookie_policy="lazy",
        transport=_direct_transport(requests),
        validator=lambda url: url,
    )
    assert path.read_bytes() == b"abc"
    assert len(requests) == 2
    assert "cookie" not in requests[0]
    assert requests[1]["cookie"] == "sid=good-cookie"
    assert calls["load"] == 1


def test_direct_explicit_sends_cookie_on_first_request(tmp_path, cookie_file, monkeypatch):
    calls = _count_reads(monkeypatch)
    requests: list[dict] = []
    path = download_direct(
        "http://8.8.8.8/clip.mp4",
        tmp_path,
        cookie_policy="explicit",
        transport=_direct_transport(requests),
        validator=lambda url: url,
    )
    assert path.read_bytes() == b"abc"
    assert len(requests) == 1
    assert requests[0]["cookie"] == "sid=good-cookie"
    assert calls["load"] == 1


def test_direct_lazy_without_cookie_file_does_not_retry(tmp_path, no_cookie_env):
    requests: list[dict] = []
    with pytest.raises(DownloadIntegrityError):
        download_direct(
            "http://8.8.8.8/clip.mp4",
            tmp_path,
            cookie_policy="lazy",
            transport=_direct_transport(requests),
            validator=lambda url: url,
        )
    assert len(requests) == 1


# ---------------------------------------------------------------------------
# download_media：惰性重试使用副本
# ---------------------------------------------------------------------------


class _ProtectedYDL:
    attempts = 0
    cookiefiles: list[str | None] = []

    def __init__(self, options):
        self.options = options
        self._target = Path(
            options["outtmpl"].replace("%(title)s", "clip").replace("%(ext)s", "mp4")
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
        type(self).attempts += 1
        type(self).cookiefiles.append(self.options.get("cookiefile"))
        if "cookiefile" not in self.options:
            raise RuntimeError("ERROR: HTTP Error 403: Forbidden")
        self._target.write_bytes(b"abc")
        return {"requested_downloads": [{"url": "http://8.8.8.8/v.mp4"}]}

    def prepare_filename(self, info):
        return str(self._target)


@pytest.fixture
def fake_ydl(monkeypatch):
    _ProtectedYDL.attempts = 0
    _ProtectedYDL.cookiefiles = []
    monkeypatch.setattr(downloader, "_ffprobe_duration", lambda path: 5.0)
    return _ProtectedYDL


def test_media_lazy_retries_once_with_copy(tmp_path, cookie_file, fake_ydl):
    before = cookie_file.stat().st_mtime
    path = download_media(
        "http://8.8.8.8/video",
        tmp_path,
        cookie_policy="lazy",
        ydl_factory=fake_ydl,
        validator=lambda url: url,
    )
    assert path.read_bytes() == b"abc"
    assert fake_ydl.attempts == 2
    assert fake_ydl.cookiefiles[0] is None
    assert fake_ydl.cookiefiles[1] is not None
    assert not Path(fake_ydl.cookiefiles[1]).exists(), "临时 Cookie 副本未清理"
    assert cookie_file.stat().st_mtime == before


def test_media_lazy_without_cookie_file_raises_once(tmp_path, no_cookie_env, fake_ydl):
    with pytest.raises(RuntimeError):
        download_media(
            "http://8.8.8.8/video",
            tmp_path,
            cookie_policy="lazy",
            ydl_factory=fake_ydl,
            validator=lambda url: url,
        )
    assert fake_ydl.attempts == 1


def test_media_none_does_not_retry(tmp_path, cookie_file, fake_ydl):
    with pytest.raises(RuntimeError):
        download_media(
            "http://8.8.8.8/video",
            tmp_path,
            cookie_policy="none",
            ydl_factory=fake_ydl,
            validator=lambda url: url,
        )
    assert fake_ydl.attempts == 1
    assert fake_ydl.cookiefiles == [None]


def test_media_explicit_uses_cookie_on_first_attempt(tmp_path, cookie_file, fake_ydl):
    path = download_media(
        "http://8.8.8.8/video",
        tmp_path,
        cookie_policy="explicit",
        ydl_factory=fake_ydl,
        validator=lambda url: url,
    )
    assert path.read_bytes() == b"abc"
    assert fake_ydl.attempts == 1
    assert fake_ydl.cookiefiles[0] is not None

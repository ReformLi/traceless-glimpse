"""惰性 Cookie 真实链路（验收标准 2）：默认不发、被拒一次读取/一次重试、原文件不变。"""
from __future__ import annotations

import os
import time
from pathlib import Path

import pytest

from services import cookies
from services.sniffer import Sniffer
from testsupport.media_fixtures import ffmpeg_available, generate_all
from testsupport.media_server import MediaServer

pytestmark = pytest.mark.real

COOKIE_LINE = "127.0.0.1\tFALSE\t/\tFALSE\t0\tsid\tgood-cookie\n"


@pytest.fixture(scope="module")
def assets(tmp_path_factory):
    assert ffmpeg_available(), "本机无 ffmpeg：-m real 无法生成测试素材"
    dest = tmp_path_factory.mktemp("bgv-cookie")
    generate_all(dest)
    return dest


@pytest.fixture(scope="module")
def server(assets):
    with MediaServer(assets, generate=False) as srv:
        yield srv


@pytest.fixture(scope="module")
def sniffer():
    instance = Sniffer()
    yield instance
    instance.shutdown()


@pytest.fixture
def cookie_file(tmp_path, monkeypatch) -> Path:
    path = tmp_path / "cookies.txt"
    path.write_text(COOKIE_LINE, encoding="utf-8")
    old = time.time() - 3600
    os.utime(path, (old, old))
    monkeypatch.setenv(cookies.COOKIE_FILE_ENV, str(path))
    return path


@pytest.fixture
def read_counter(monkeypatch) -> dict[str, int]:
    calls = {"count": 0}
    real = cookies.load_env_cookies

    def counting():
        calls["count"] += 1
        return real()

    monkeypatch.setattr(cookies, "load_env_cookies", counting)
    return calls


def _protected_requests(server) -> list[dict]:
    return [entry for entry in server.request_log if entry["path"] == "/protected.html"]


def test_normal_page_lazy_never_reads_cookie(sniffer, server, cookie_file, read_counter):
    """普通可下载页面：全程不读取 Cookie 文件，也不发送 Cookie。"""
    server.reset_log()
    observed = sniffer.sniff("task-lazy-normal", server.base_url + "/page.html", "lazy")
    assert observed is not None
    assert read_counter["count"] == 0
    assert all("good-cookie" not in entry["cookie"] for entry in server.request_log)


def test_protected_page_lazy_reads_once_and_retries_once(
    sniffer, server, cookie_file, read_counter
):
    """被拒场景：一次读取、一次重试，原 Cookie 文件 mtime 不变。"""
    server.reset_log()
    before = cookie_file.stat().st_mtime
    observed = sniffer.sniff("task-lazy-protected", server.base_url + "/protected.html", "lazy")
    assert observed is not None
    assert observed.url.endswith("/media/hls/master.m3u8")

    attempts = _protected_requests(server)
    assert len(attempts) == 2, f"应恰好重试一次，实际 {len(attempts)}"
    assert attempts[0]["cookie"] == ""
    assert "sid=good-cookie" in attempts[1]["cookie"]
    assert read_counter["count"] == 1
    assert cookie_file.stat().st_mtime == before


def test_protected_page_none_gets_no_media(sniffer, server, cookie_file, read_counter):
    server.reset_log()
    assert sniffer.sniff("task-none-protected", server.base_url + "/protected.html", "none") is None
    assert len(_protected_requests(server)) == 1
    assert read_counter["count"] == 0


def test_protected_page_explicit_sends_cookie_on_first_request(
    sniffer, server, cookie_file, read_counter
):
    server.reset_log()
    observed = sniffer.sniff("task-explicit", server.base_url + "/protected.html", "explicit")
    assert observed is not None
    attempts = _protected_requests(server)
    assert len(attempts) == 1
    assert "sid=good-cookie" in attempts[0]["cookie"]
    assert read_counter["count"] == 1

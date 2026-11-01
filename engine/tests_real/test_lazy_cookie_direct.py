"""4b 收尾第 1 条：惰性 Cookie 在**下载路径**上的真 HTTP 取证。

M2 的裁决是「默认不发、被拒才读一次、只重试一次」。在这之前直链下载只有
注入式 transport 的单测（`test_cookies.py`），真链路取证只有嗅探那一半
（`test_lazy_cookie_sniff.py`），所以 4a 真机留下的 `cookie_present=false`
其实只证明「没被拒时不读」，没证明「被拒时读得到、读得对」。本文件补的就是这半边。
"""
from __future__ import annotations

import logging
import os
import time
from pathlib import Path

import pytest

import services.redirect as redirect_service
from services.downloader import download_direct
from services import cookies
from testsupport.media_fixtures import ffmpeg_available, generate_all
from testsupport.media_server import MediaServer

pytestmark = pytest.mark.real

COOKIE_LINE = "127.0.0.1\tFALSE\t/\tFALSE\t0\tsid\tgood-cookie\n"
PROTECTED_PATH = "/media/protected.mp4"


@pytest.fixture(scope="module")
def assets(tmp_path_factory):
    assert ffmpeg_available(), "本机无 ffmpeg：-m real 无法生成测试素材"
    dest = tmp_path_factory.mktemp("bgv-cookie-direct")
    generate_all(dest)
    return dest


@pytest.fixture(scope="module")
def server(assets):
    with MediaServer(assets, generate=False) as srv:
        yield srv


@pytest.fixture
def cookie_file(tmp_path, monkeypatch) -> Path:
    """受控 Cookie 文件：故意把 mtime 拨到一小时前，事后比对以证「只读不写」。"""
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


@pytest.fixture
def no_cookie_env(monkeypatch):
    """`no_cookie_env` 在 `test_cookies.py` 里是模块级 fixture，tests_real 看不见，本地重声明。"""
    monkeypatch.delenv(cookies.COOKIE_FILE_ENV, raising=False)


def _attempts(server) -> list[dict]:
    return [e for e in server.request_log if e["path"] == PROTECTED_PATH]


def test_direct_lazy_reads_once_and_retries_once_over_real_http(
    server, assets, tmp_path, cookie_file, read_counter, caplog, monkeypatch
):
    """403 → 读一次 Cookie → 重试一次拿到 200；产物字节与源文件逐字节相同。

    三跳而不是两跳是真实形态，不是缺陷：`resolve_redirects` 的 `bytes=0-0` 探测先撞一次
    403（探测不做 Cookie 回补，它只要头），随后才是下载请求的「无 Cookie 403 → 带 Cookie 200」。
    所以断言按 Range 头把探测那跳单独摘出来。
    """
    monkeypatch.setattr(redirect_service, "validate_outbound_url", lambda url: url)
    server.reset_log()
    mtime_before = cookie_file.stat().st_mtime
    full = (assets / "media" / "sample.mp4").read_bytes()

    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        path = download_direct(
            server.base_url + PROTECTED_PATH,
            tmp_path,
            task_id="lazy-direct-1",
            cookie_policy="lazy",
            validator=lambda url: url,
        )

    assert path.read_bytes() == full
    attempts = _attempts(server)
    assert len(attempts) == 3, f"探测 1 跳 + 被拒 1 跳 + 重试 1 跳：{attempts}"
    assert attempts[0]["range"] == "bytes=0-0", attempts[0]
    assert attempts[0]["cookie"] == "" and attempts[1]["cookie"] == ""
    assert "sid=good-cookie" in attempts[2]["cookie"]
    assert attempts[2]["range"] == "", "重试沿用原始请求头，不夹带 Range（全新下载）"

    # 一次读取：被拒后只读文件一次；探测那跳不读（它不参与 Cookie 回补）
    assert read_counter["count"] == 1
    assert cookie_file.stat().st_mtime == mtime_before, "原文件只读，写回不落源文件"
    assert list(tmp_path.glob("*.part")) == [], "完成后不留残留"

    logs = [r.getMessage() for r in caplog.records if r.name == "bgv.engine"]
    assert "cookie_lazy_retry task_id=lazy-direct-1" in logs
    assert not any("cookie_file_loaded" in line for line in logs), (
        "cookie_file_loaded 是 explicit 专用标记，lazy 路径不该出现"
    )
    # 审计行里只有 task_id：Cookie 名值都不进日志（good-cookie 只在服务器侧断言）
    assert all("good-cookie" not in line for line in logs)


def test_direct_lazy_without_cookie_file_fails_after_one_read(
    server, tmp_path, no_cookie_env, read_counter, caplog, monkeypatch
):
    """`BGV_COOKIE_FILE` 未配置：读了但读不到 → 不重试，按 HTTP 403 失败，现场不落成产物。"""
    monkeypatch.setattr(redirect_service, "validate_outbound_url", lambda url: url)
    server.reset_log()

    from services.downloader import DownloadIntegrityError

    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        with pytest.raises(DownloadIntegrityError, match="403"):
            download_direct(
                server.base_url + PROTECTED_PATH,
                tmp_path,
                task_id="lazy-nofile",
                cookie_policy="lazy",
                validator=lambda url: url,
            )

    attempts = _attempts(server)
    assert len(attempts) == 2, f"探测 + 一次下载，没有第三次：{attempts}"
    assert all(e["cookie"] == "" for e in attempts)
    assert read_counter["count"] == 1, "读了一次、读不到；不做第二次尝试"
    logs = [r.getMessage() for r in caplog.records if r.name == "bgv.engine"]
    assert not any("cookie_lazy_retry" in line for line in logs)
    assert list(tmp_path.iterdir()) == [], "失败不留下半个产物"

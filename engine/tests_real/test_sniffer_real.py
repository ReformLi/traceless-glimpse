"""阶段 2：Playwright 隔离嗅探（真实链路，显式 -m real 才运行）。

覆盖验收标准 1 的隔离性：两个任务各自 `new_context()`，第二个任务不得看到
第一个任务页面写入的 Cookie；同时校验审计行不含 URL / Cookie / UA。
"""
from __future__ import annotations

import json
import logging
import tempfile
from pathlib import Path

import pytest

from services.sniffer import Sniffer
from testsupport.media_fixtures import ffmpeg_available, generate_all
from testsupport.media_server import MediaServer

pytestmark = pytest.mark.real


@pytest.fixture(scope="module")
def assets(tmp_path_factory):
    assert ffmpeg_available(), "本机无 ffmpeg：-m real 无法生成测试素材"
    dest = tmp_path_factory.mktemp("bgv-sniff")
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


def test_sniff_prefers_hls_playlist(sniffer, server):
    """页面同时请求 mp4 与 m3u8 时，优先取播放列表（HLS）。"""
    server.reset_log()
    observed = sniffer.sniff("task-playlist", server.base_url + "/page.html")
    assert observed is not None
    assert observed.kind == "hls"
    assert observed.container == "m3u8"
    assert observed.url.endswith("/media/hls/master.m3u8")
    lowered = {key.lower() for key in observed.headers_hint}
    assert "cookie" not in lowered
    assert "authorization" not in lowered


def test_two_contexts_do_not_share_cookies(sniffer, server):
    """验收标准 1：任务 A 页面写入的 Cookie 不得出现在任务 B 的任何请求里。

    每个任务用唯一 sid（?sid=task-a / ?sid=task-b），使「串 Cookie」可被直接识别。
    """
    server.reset_log()
    sniffer.sniff("task-a", server.base_url + "/page.html?sid=task-a")
    sniffer.sniff("task-b", server.base_url + "/page.html?sid=task-b")

    log = list(server.request_log)
    page_indices = [index for index, entry in enumerate(log) if entry["path"] == "/page.html"]
    assert len(page_indices) == 2, f"应看到两次页面请求，实际 {len(page_indices)}"
    for index in page_indices:
        assert log[index]["cookie"] == "", "页面请求本身不应携带上一次上下文残留的 Cookie"

    a_indices = [i for i, entry in enumerate(log) if "sid=task-a" in entry["cookie"]]
    b_indices = [i for i, entry in enumerate(log) if "sid=task-b" in entry["cookie"]]
    assert a_indices, "任务 A 写入 Cookie 后，其后续请求应携带（否则用例无意义）"
    assert b_indices, "任务 B 写入 Cookie 后，其后续请求应携带（否则用例无意义）"

    # A 的 Cookie 只出现在 A 的区间；B 的 Cookie 只出现在 B 的区间
    assert all(page_indices[0] < i < page_indices[1] for i in a_indices), (
        "任务 A 的 Cookie 越界到任务 B 上下文：" + str([log[i] for i in a_indices])
    )
    assert all(i > page_indices[1] for i in b_indices)
    # 硬断言：B 区间内不得出现任何 A 的 Cookie 值
    leaked = [log[i] for i in range(page_indices[1], len(log)) if "sid=task-a" in log[i]["cookie"]]
    assert not leaked, f"Cookie 串到任务 B：{leaked}"


def test_audit_lines_do_not_leak_sensitive_data(sniffer, server, caplog):
    """约束 ⑥：sniff_context_open/close 不得携带 URL、Cookie、UA。"""
    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        sniffer.sniff("task-audit", server.base_url + "/page.html")

    audit = [
        record.getMessage()
        for record in caplog.records
        if record.name == "bgv.engine" and record.getMessage().startswith("sniff_context")
    ]
    assert audit, "未捕获到嗅探审计行"
    forbidden = ("http://", "https://", "Cookie", "cookie", "sid=", "Mozilla", "127.0.0.1")
    for line in audit:
        for token in forbidden:
            assert token not in line, f"审计行泄漏敏感信息 {token!r}：{line}"


def test_one_open_and_one_close_per_task(sniffer, server, caplog):
    """每个任务恰好一次 context 开 / 关（无并发残留上下文）。"""
    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        sniffer.sniff("task-count", server.base_url + "/page.html")
    messages = [record.getMessage() for record in caplog.records if record.name == "bgv.engine"]
    assert messages.count("sniff_context_open task_id=task-count") == 1
    assert messages.count("sniff_context_close task_id=task-count") == 1


def test_page_without_media_returns_none(sniffer, server):
    """无媒体请求的页面 → 返回 None（上层置 failed，detail.reason=sniff_failed）。"""
    observed = sniffer.sniff("task-none", server.base_url + "/whoami")
    assert observed is None


def test_whoami_in_fresh_context_reports_empty_cookie(server):
    """隔离性直接证据（阶段 2 补交项 2）：任务 A 写入 Cookie 并关闭后，
    全新上下文的 /whoami 回显必须为空。"""
    from playwright.sync_api import sync_playwright

    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True)
        try:
            ctx_a = browser.new_context()
            page_a = ctx_a.new_page()
            page_a.goto(server.base_url + "/page.html?sid=task-a", wait_until="domcontentloaded")
            page_a.wait_for_timeout(300)
            cookies_a = ctx_a.cookies()
            ctx_a.close()

            ctx_b = browser.new_context()
            page_b = ctx_b.new_page()
            page_b.goto(server.base_url + "/whoami", wait_until="domcontentloaded")
            body = page_b.evaluate("() => document.body.innerText")
            ctx_b.close()
        finally:
            browser.close()

    payload = json.loads(body)
    assert cookies_a and cookies_a[0]["value"] == "task-a", "任务 A 未写入 Cookie，用例前提不成立"
    assert payload["cookie"] == "", f"任务 B 上下文残留 Cookie：{payload['cookie']!r}"
    print("\n[证据] 任务A cookies =", cookies_a)
    print("[证据] 任务B /whoami =", body)


def test_no_playwright_profile_dirs_left_behind(sniffer, server):
    """context.close() + worker shutdown 后 TEMP 下无 playwright* 目录残留（补交项 3）。

    先关闭本模块共享 sniffer，避免它正在运行的 profile 干扰快照。
    """
    from services.sniffer import Sniffer

    def snapshot() -> set[str]:
        return {
            path.name
            for path in Path(tempfile.gettempdir()).iterdir()
            if "playwright" in path.name.lower()
        }

    sniffer.shutdown()
    before = snapshot()
    local = Sniffer()
    try:
        local.sniff("task-profile-a", server.base_url + "/page.html?sid=task-a")
        local.sniff("task-profile-b", server.base_url + "/page.html?sid=task-b")
    finally:
        local.shutdown()
    after = snapshot()
    leftover = after - before
    assert leftover == set(), f"存在残留 profile 目录：{leftover}"
    print("\n[证据] TEMP playwright* 目录 before =", before, "\n[证据] after =", after)


def test_nocookie_page_media_request_carries_no_cookie_after_task_a(server):
    """4a 真机取证项的自动化等价：任务 A 写入 sid Cookie 后，全新上下文打开
    「不写 Cookie」页面，服务器侧对同一个 master.m3u8 的请求 Cookie 必须为空。

    自建 Sniffer：模块共享实例会被 profile 残留用例 shutdown，线程不能重开。
    """
    from services.sniffer import Sniffer

    local = Sniffer()
    try:
        local.sniff("task-a-cookie", server.base_url + "/page.html?sid=task-a")
        server.reset_log()
        observed = local.sniff("task-b-clean", server.base_url + "/page-nocookie.html")
    finally:
        local.shutdown()
    assert observed is not None and observed.url.endswith("/media/hls/master.m3u8")
    playlist_requests = [
        entry for entry in server.request_log if entry["path"] == "/media/hls/master.m3u8"
    ]
    assert playlist_requests, "任务 B 未触发播放列表请求，用例前提不成立"
    assert all(entry["cookie"] == "" for entry in playlist_requests), playlist_requests

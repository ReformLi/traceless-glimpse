"""M21：出站代理口径 + 嗅探传输层失败归因（纯函数，不过网络）。

两件事各证一半：
1. `services.proxy` 把 BGV_PROXY 解析成三段链路各自要的形状（Playwright 拆凭据、
   httpx 让位给 transport、yt-dlp 用原串）；
2. `main._sniff_failure` 把 Chromium 的 `net::ERR_*` 分到正确的 §5 码——
   连接被掐断不再冒充 `EXTRACTOR_FAILED`（「站点已变更」），并换上「可能被网络阻断」的文案。
"""
from __future__ import annotations

import pytest

from models import ERROR_CODE_INFO, ErrorCode
from services import proxy as proxy_settings
from services.failures import CONNECTION_BLOCKED_MESSAGE, classify_download_failure
from services.sniffer import SniffError


@pytest.fixture(autouse=True)
def _clean_proxy_env(monkeypatch):
    """基线：没有代理。需要代理的用例自己设，避免受宿主机 shell 影响。"""
    monkeypatch.delenv(proxy_settings.ENV_PROXY, raising=False)


# ---------------------------------------------------------------- 代理口径

def test_proxy_url_is_none_when_absent_or_blank(monkeypatch):
    assert proxy_settings.proxy_url() is None
    monkeypatch.setenv(proxy_settings.ENV_PROXY, "   ")
    assert proxy_settings.proxy_url() is None
    monkeypatch.setenv(proxy_settings.ENV_PROXY, "  http://127.0.0.1:7890  ")
    assert proxy_settings.proxy_url() == "http://127.0.0.1:7890"


def test_playwright_proxy_splits_credentials(monkeypatch):
    monkeypatch.setenv(proxy_settings.ENV_PROXY, "http://user:p%40ss@10.0.0.2:8080")
    assert proxy_settings.playwright_proxy() == {
        "server": "http://10.0.0.2:8080",
        "username": "user",
        "password": "p@ss",  # 百分号编码要还原
    }


def test_playwright_proxy_accepts_bare_host_port_and_socks(monkeypatch):
    monkeypatch.setenv(proxy_settings.ENV_PROXY, "127.0.0.1:7890")
    assert proxy_settings.playwright_proxy() == {"server": "http://127.0.0.1:7890"}
    monkeypatch.setenv(proxy_settings.ENV_PROXY, "socks5://127.0.0.1:1080")
    assert proxy_settings.playwright_proxy() == {"server": "socks5://127.0.0.1:1080"}


def test_playwright_proxy_gives_up_on_broken_input(monkeypatch):
    """解析不出主机就返回 None：宁可直连，也不要整条嗅探因为写错的代理而全灭。"""
    monkeypatch.setenv(proxy_settings.ENV_PROXY, "http://:7890")
    assert proxy_settings.playwright_proxy() is None


def test_httpx_kwargs_yield_to_injected_transport(monkeypatch):
    """httpx 不允许 proxy 与 transport 并存：测试注入 transport 时代理让位。"""
    monkeypatch.setenv(proxy_settings.ENV_PROXY, "http://127.0.0.1:7890")
    assert proxy_settings.httpx_proxy_kwargs(transport=object()) == {}
    assert proxy_settings.httpx_proxy_kwargs() == {"proxy": "http://127.0.0.1:7890"}


def test_httpx_kwargs_empty_when_unset():
    assert proxy_settings.httpx_proxy_kwargs() == {}


# ---------------------------------------------------------------- 传输层归因

@pytest.mark.parametrize(
    ("error_text", "expected"),
    [
        # 连接被掐断：不是「站点已变更」，归网络类
        ("Error: Page.goto: net::ERR_CONNECTION_RESET at https://example.com/a", ErrorCode.NETWORK_HTTP_ERROR),
        ("net::ERR_CONNECTION_REFUSED", ErrorCode.NETWORK_HTTP_ERROR),
        ("net::ERR_ADDRESS_UNREACHABLE", ErrorCode.NETWORK_HTTP_ERROR),
        ("net::ERR_HTTP2_PROTOCOL_ERROR", ErrorCode.NETWORK_HTTP_ERROR),
        ("net::ERR_PROXY_CONNECTION_FAILED", ErrorCode.NETWORK_HTTP_ERROR),
        # 机制明确的各自归位
        ("net::ERR_NAME_NOT_RESOLVED", ErrorCode.DNS_FAILURE),
        ("net::ERR_CONNECTION_TIMED_OUT", ErrorCode.NETWORK_TIMEOUT),
        ("net::ERR_SSL_PROTOCOL_ERROR", ErrorCode.TLS_ERROR),
        # 真解析不了才落 EXTRACTOR_FAILED
        ("Error: browser has been closed", ErrorCode.EXTRACTOR_FAILED),
    ],
)
def test_sniff_error_classification(error_text, expected):
    code, _ = classify_download_failure(
        SniffError(error_text), fallback=ErrorCode.EXTRACTOR_FAILED
    )
    assert code is expected


def test_sniff_failure_adds_net_code_and_honest_message():
    """main._sniff_failure：detail 带网络码，文案换成「可能被网络阻断」。"""
    from main import _sniff_failure

    exc = SniffError(
        "Error: Page.goto: net::ERR_CONNECTION_RESET at https://blocked.example/x?t=1"
    )
    code, detail, message = _sniff_failure(exc)
    assert code is ErrorCode.NETWORK_HTTP_ERROR
    assert detail == {"reason": "sniff_failed", "net": "ERR_CONNECTION_RESET"}
    assert message == CONNECTION_BLOCKED_MESSAGE
    # detail 绝不带异常原文（原文里有完整 URL，§1.7 脱敏口径）
    assert "blocked.example" not in str(detail)


def test_sniff_failure_keeps_table_message_for_clear_causes():
    """DNS/超时/TLS 用 §5 表内文案，不覆写——它们的码本身就说清了原因。"""
    from main import _sniff_failure

    for text, code in (
        ("net::ERR_NAME_NOT_RESOLVED", ErrorCode.DNS_FAILURE),
        ("net::ERR_TIMED_OUT", ErrorCode.NETWORK_TIMEOUT),
        ("net::ERR_CERT_AUTHORITY_INVALID", ErrorCode.TLS_ERROR),
    ):
        got_code, detail, message = _sniff_failure(SniffError(text))
        assert got_code is code
        assert message is None
        assert detail["reason"] == "sniff_failed"
        assert "net" in detail


def test_sniff_failure_distinguishes_proxy_failure_from_site_blocking():
    """代理自己不通（端口写错/没开）时，文案要指向「网络代理」设置，而不是站点。"""
    from main import _sniff_failure

    code, detail, message = _sniff_failure(
        SniffError("Error: Page.goto: net::ERR_PROXY_CONNECTION_FAILED at https://example.com/")
    )
    assert code is ErrorCode.NETWORK_HTTP_ERROR
    assert detail["net"] == "ERR_PROXY_CONNECTION_FAILED"
    assert message is not None and "代理" in message and "站点" not in message


def test_sniff_failure_without_net_code_falls_back_to_extractor():
    from main import _sniff_failure

    code, detail, message = _sniff_failure(SniffError("Error: page crashed"))
    assert code is ErrorCode.EXTRACTOR_FAILED
    assert detail == {"reason": "sniff_failed"}
    assert message is None
    # 表内文案仍是「解析失败」，这条路径不该被 M21 的联动文案污染
    assert ERROR_CODE_INFO[ErrorCode.EXTRACTOR_FAILED][2] == "解析失败，可能站点已变更"

"""SSRF / 输出目录白名单 / 脱敏（用户硬要求 3、4）。

单元级（validate_outbound_url / validate_redirect_chain / validate_output_dir）
+ API 级 §9.2 / §9.4 逐字断言。
"""

import logging
import os

import pytest

from security import (
    InvalidOutputDirError,
    InvalidUrlError,
    OutputDirRejectedError,
    SsrfBlockedError,
    UnsupportedSchemeError,
    sanitize_detail,
    sanitize_headers,
    validate_outbound_url,
    validate_output_dir,
    validate_redirect_chain,
)


class TestValidateOutboundUrl:
    def test_loopback_127_blocked(self):
        with pytest.raises(SsrfBlockedError) as ei:
            validate_outbound_url("http://127.0.0.1/admin")
        assert ei.value.detail == {"host": "127.0.0.1"}

    def test_metadata_endpoint_169_254_blocked(self):
        with pytest.raises(SsrfBlockedError):
            validate_outbound_url("http://169.254.169.254/latest/meta-data/")

    def test_private_10_0_0_1_blocked(self):
        with pytest.raises(SsrfBlockedError):
            validate_outbound_url("http://10.0.0.1/internal")

    def test_ipv4_mapped_ipv6_blocked(self):
        with pytest.raises(SsrfBlockedError):
            validate_outbound_url("http://[::ffff:127.0.0.1]/x")

    def test_localhost_name_blocked(self):
        with pytest.raises(SsrfBlockedError):
            validate_outbound_url("http://localhost:8730/x")

    def test_trailing_dot_localhost_blocked(self):
        with pytest.raises(SsrfBlockedError):
            validate_outbound_url("http://localhost./x")

    def test_file_scheme_rejected(self):
        with pytest.raises(UnsupportedSchemeError) as ei:
            validate_outbound_url("file:///etc/passwd")
        assert ei.value.detail == {"scheme": "file"}

    def test_missing_scheme_rejected(self):
        with pytest.raises(UnsupportedSchemeError):
            validate_outbound_url("example.com/watch?v=abc")

    def test_public_ip_literal_allowed(self):
        """合法初始：公网 IP 字面量，不依赖 DNS。"""
        url = "https://93.184.216.34/watch?v=abc"
        assert validate_outbound_url(url) == url


class TestValidateRedirectChain:
    def test_second_hop_private_blocked(self):
        """合法初始 + 重定向第二跳 10.0.0.1 → 拒绝（§1.5 含重定向后校验）。"""
        with pytest.raises(SsrfBlockedError):
            validate_redirect_chain(
                "https://93.184.216.34/watch?v=abc",
                ["https://93.184.216.34/b", "http://10.0.0.1/c"],
            )

    def test_all_public_hops_allowed(self):
        validate_redirect_chain("https://93.184.216.34/a", ["https://93.184.216.34/b"])

    def test_initial_blocked(self):
        with pytest.raises(SsrfBlockedError):
            validate_redirect_chain("http://127.0.0.1/a", ["https://93.184.216.34/b"])

    def test_hop_scheme_rejected(self):
        with pytest.raises(UnsupportedSchemeError):
            validate_redirect_chain("https://93.184.216.34/a", ["ftp://93.184.216.34/b"])


class TestValidateOutputDir:
    def test_root_slash_rejected(self):
        """§9.4：outputDir="/" → OUTPUT_DIR_REJECTED（Windows 下也必须是 REJECTED）。"""
        with pytest.raises(OutputDirRejectedError):
            validate_output_dir("/")

    def test_drive_root_c_rejected(self):
        """C:\\ 是绝对路径但在拒绝清单 → OUTPUT_DIR_REJECTED。"""
        with pytest.raises(OutputDirRejectedError):
            validate_output_dir("C:\\")

    def test_home_rejected(self):
        """%USERPROFILE% 在拒绝清单 → OUTPUT_DIR_REJECTED。"""
        with pytest.raises(OutputDirRejectedError):
            validate_output_dir(os.path.expanduser("~"))

    def test_cwd_rejected(self):
        with pytest.raises(OutputDirRejectedError):
            validate_output_dir(os.getcwd())

    def test_relative_rejected(self):
        """相对路径 → INVALID_OUTPUT_DIR（非 OUTPUT_DIR_REJECTED）。"""
        with pytest.raises(InvalidOutputDirError):
            validate_output_dir("videos/bg")

    def test_empty_rejected(self):
        with pytest.raises(InvalidOutputDirError):
            validate_output_dir("")

    def test_allowlist_root_allowed(self):
        assert validate_output_dir("/home/user/Videos/BGV") == "/home/user/Videos/BGV"

    def test_allowlist_subdir_allowed(self):
        assert validate_output_dir("/home/user/Videos/BGV/2026") == "/home/user/Videos/BGV/2026"

    def test_allowlist_parent_rejected(self):
        """白名单根的父目录不在白名单 → OUTPUT_DIR_REJECTED。"""
        with pytest.raises(OutputDirRejectedError):
            validate_output_dir("/home/user/Videos")

    def test_tmp_rejected(self):
        with pytest.raises(OutputDirRejectedError):
            validate_output_dir("/tmp")


class TestSanitize:
    def test_sensitive_headers_removed(self):
        raw = {
            "referer": "https://example.com/",
            "Cookie": "sid=secret",
            "Authorization": "Bearer xyz",
            "Set-Cookie": "a=b",
            "X-Api-Token": "t",
            "x-api-key": "k",
            "X-Custom": "ok",
        }
        assert sanitize_headers(raw) == {"referer": "https://example.com/", "X-Custom": "ok"}

    def test_none_returns_empty(self):
        assert sanitize_headers(None) == {}

    def test_sanitize_detail_redacts(self):
        raw = {"host": "127.0.0.1", "cookie": "sid=secret", "nested": {"token": "abc"}}
        clean = sanitize_detail(raw)
        assert clean["host"] == "127.0.0.1"
        assert clean["cookie"] == "[REDACTED]"
        assert clean["nested"]["token"] == "[REDACTED]"

    def test_sanitize_detail_none(self):
        assert sanitize_detail(None) is None


class TestApiSecurityExamples:
    def test_protocol_9_2_ssrf_example(self, client):
        """§9.2 逐字：POST /v1/tasks 带 http://127.0.0.1/admin → 400 + 全等 error dict。"""
        rsp = client.post(
            "/v1/tasks",
            json={
                "sourceUrl": "http://127.0.0.1/admin",
                "mode": "sniff",
                "cookiePolicy": "none",
                "outputDir": "/home/user/Videos/BGV",
            },
        )
        assert rsp.status_code == 400
        assert rsp.json() == {
            "error": {
                "code": "SSRF_BLOCKED",
                "category": "security",
                "retryable": False,
                "message": "目标地址指向受限网络，已拒绝",
                "detail": {"host": "127.0.0.1"},
            }
        }

    def test_protocol_9_4_output_dir_example(self, client):
        """§9.4 逐字：outputDir="/" → 400 + 全等 error dict（无 detail 键）。"""
        rsp = client.post(
            "/v1/tasks",
            json={
                "sourceUrl": "https://example.com/v",
                "mode": "sniff",
                "cookiePolicy": "none",
                "outputDir": "/",
            },
        )
        assert rsp.status_code == 400
        assert rsp.json() == {
            "error": {
                "code": "OUTPUT_DIR_REJECTED",
                "category": "security",
                "retryable": False,
                "message": "输出目录不在允许范围内",
            }
        }

    def test_invalid_url_error_payload_shape(self, client):
        """sourceUrl 非法 host（空 host）→ 400 INVALID_URL，ErrorPayload 结构完整。"""
        rsp = client.post(
            "/v1/tasks",
            json={
                "sourceUrl": "http:///no-host",
                "mode": "sniff",
                "cookiePolicy": "none",
                "outputDir": "/home/user/Videos/BGV",
            },
        )
        assert rsp.status_code == 400
        err = rsp.json()["error"]
        assert err["code"] == "INVALID_URL"
        assert err["category"] == "input"
        assert err["retryable"] is False


class TestDevSsrfAllowlist:
    """方案 X 临时开发白名单：三门控 + 精确主机匹配 + 每次放行一条日志。"""

    def test_missing_bypass_gate_keeps_blocked(self, monkeypatch):
        monkeypatch.setenv("BGV_SSRF_ALLOWLIST", "127.0.0.1")
        monkeypatch.delenv("BGV_DEV_ALLOW_SSRF_BYPASS", raising=False)
        monkeypatch.setenv("BGV_ENGINE_MODE", "real")
        with pytest.raises(SsrfBlockedError):
            validate_outbound_url("http://127.0.0.1:8731/page.html")

    def test_missing_allowlist_gate_keeps_blocked(self, monkeypatch):
        monkeypatch.delenv("BGV_SSRF_ALLOWLIST", raising=False)
        monkeypatch.setenv("BGV_DEV_ALLOW_SSRF_BYPASS", "1")
        monkeypatch.setenv("BGV_ENGINE_MODE", "real")
        with pytest.raises(SsrfBlockedError):
            validate_outbound_url("http://127.0.0.1:8731/page.html")

    def test_non_real_mode_keeps_blocked_and_warns(self, monkeypatch, caplog):
        monkeypatch.setenv("BGV_SSRF_ALLOWLIST", "127.0.0.1")
        monkeypatch.setenv("BGV_DEV_ALLOW_SSRF_BYPASS", "1")
        monkeypatch.setenv("BGV_ENGINE_MODE", "stub")
        with caplog.at_level(logging.WARNING, logger="bgv.engine"):
            with pytest.raises(SsrfBlockedError):
                validate_outbound_url("http://127.0.0.1:8731/page.html")
        warnings = [r.getMessage() for r in caplog.records if r.levelno >= logging.WARNING]
        assert any(line.startswith("dev_ssrf_bypass_ignored engine_mode=stub") for line in warnings)

    def test_three_gates_allow_and_log_one_line_per_call(self, monkeypatch, caplog):
        monkeypatch.setenv("BGV_SSRF_ALLOWLIST", "127.0.0.1")
        monkeypatch.setenv("BGV_DEV_ALLOW_SSRF_BYPASS", "1")
        monkeypatch.setenv("BGV_ENGINE_MODE", "real")
        url = "http://127.0.0.1:8731/media/hls/master.m3u8"
        with caplog.at_level(logging.INFO, logger="bgv.engine"):
            assert validate_outbound_url(url) == url
            assert validate_outbound_url(url) == url
        bypass = [
            r.getMessage()
            for r in caplog.records
            if r.getMessage().startswith("ssrf_allowlist_bypass")
        ]
        # 每次放行都必须有一行，且只带 host，不带 URL / 端口 / 路径
        assert bypass == [
            "ssrf_allowlist_bypass host=127.0.0.1",
            "ssrf_allowlist_bypass host=127.0.0.1",
        ]

    def test_exact_host_match_only(self, monkeypatch):
        """不支持 IP 段、后缀与改名：只有整串精确命中的主机被放行。"""
        monkeypatch.setenv("BGV_SSRF_ALLOWLIST", " 127.0.0.1, 192.168.7.7 ")
        monkeypatch.setenv("BGV_DEV_ALLOW_SSRF_BYPASS", "1")
        monkeypatch.setenv("BGV_ENGINE_MODE", "real")
        assert validate_outbound_url("http://127.0.0.1:8731/x") == "http://127.0.0.1:8731/x"
        assert validate_outbound_url("http://192.168.7.7:8731/x") == "http://192.168.7.7:8731/x"
        for url in (
            "http://localhost:8731/x",  # 同一回环地址换个名字
            "http://127.0.0.2/x",  # 相邻回环地址
            "http://192.168.7.8/x",  # 同段相邻地址
            "http://[::ffff:127.0.0.1]/x",  # IPv4-mapped 写法
        ):
            with pytest.raises(SsrfBlockedError):
                validate_outbound_url(url)

    def test_cidr_entry_is_not_a_segment(self, monkeypatch):
        """白名单写段（127.0.0.0/24）不产生段语义：段内主机仍被拒。"""
        monkeypatch.setenv("BGV_SSRF_ALLOWLIST", "127.0.0.0/24")
        monkeypatch.setenv("BGV_DEV_ALLOW_SSRF_BYPASS", "1")
        monkeypatch.setenv("BGV_ENGINE_MODE", "real")
        with pytest.raises(SsrfBlockedError):
            validate_outbound_url("http://127.0.0.1:8731/x")

    def test_blocked_path_logs_reason(self, monkeypatch, caplog):
        monkeypatch.delenv("BGV_SSRF_ALLOWLIST", raising=False)
        monkeypatch.delenv("BGV_DEV_ALLOW_SSRF_BYPASS", raising=False)
        with caplog.at_level(logging.INFO, logger="bgv.engine"):
            with pytest.raises(SsrfBlockedError):
                validate_outbound_url("http://10.0.0.1/internal")
        lines = [r.getMessage() for r in caplog.records]
        assert any(
            line == "ssrf_blocked host=10.0.0.1 reason=blocked_ip" for line in lines
        ), lines

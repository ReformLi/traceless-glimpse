"""日志脱敏单测（FR-SEC-04 / 验收标准 5）。"""
from __future__ import annotations

import io
import logging

import pytest

from log_redaction import REDACTED, RedactingFilter, install_log_redaction, redact_text


def _logger_with_capture(name: str) -> tuple[logging.Logger, io.StringIO]:
    stream = io.StringIO()
    handler = logging.StreamHandler(stream)
    handler.setFormatter(logging.Formatter("%(message)s"))
    handler.addFilter(RedactingFilter())
    logger = logging.getLogger(name)
    logger.handlers = [handler]
    logger.propagate = False
    logger.setLevel(logging.INFO)
    return logger, stream


# ---------------------------------------------------------------------------
# 文本规则
# ---------------------------------------------------------------------------


def test_quoted_cookie_value_redacted_but_other_headers_kept():
    text = "headersHint={'cookie': 'sid=secret-value', 'user-agent': 'Mozilla/5.0'}"
    result = redact_text(text)
    assert "secret-value" not in result
    assert "Mozilla/5.0" in result


def test_bare_cookie_header_redacted():
    result = redact_text("Cookie: sid=secret-value; theme=dark")
    assert "secret-value" not in result
    assert "theme=dark" not in result


def test_authorization_bearer_redacted():
    assert "stub-token" not in redact_text("Authorization: Bearer stub-token")
    assert "abc.def" not in redact_text("proxy auth Bearer abc.def")


def test_sensitive_query_params_redacted_but_benign_kept():
    url = "https://cdn.example.com/v.m3u8?token=abc123&sig=deadbeef&quality=720"
    result = redact_text(f"GET {url} HTTP/1.1")
    assert "abc123" not in result
    assert "deadbeef" not in result
    assert "quality=720" in result
    assert "token=" + REDACTED in result


def test_sensitive_pairs_redacted_without_header_context():
    result = redact_text("retry sid=secret-value api_key=sk-123 password='p@ss'")
    assert "secret-value" not in result
    assert "sk-123" not in result
    assert "p@ss" not in result


def test_prefixed_sensitive_keys_redacted():
    result = redact_text("proxy_password=secret-pass user=alice x-api-key=sk-456")
    assert "secret-pass" not in result
    assert "sk-456" not in result
    assert "user=alice" in result
    assert "proxy_password=" + REDACTED in result


def test_prefixed_sensitive_query_params_redacted():
    result = redact_text("GET /v1/probe?user_password=secret-pass&quality=720 HTTP/1.1")
    assert "secret-pass" not in result
    assert "user_password=" + REDACTED in result
    assert "quality=720" in result


def test_jwt_redacted_even_without_key():
    token = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl"
    assert token not in redact_text(f"received {token} from upstream")


@pytest.mark.parametrize(
    "line",
    [
        "sniff_context_open task_id=01a191ed",
        "resume_downgraded task_id=accept-downgrade",
        "direct_download_complete task_id=x bytes=123456",
        "download_attempt task_id=x format=best",
        "awaiting_selection_timeout task_id=x",
    ],
)
def test_audit_lines_unchanged(line: str):
    assert redact_text(line) == line


def test_redaction_is_idempotent():
    once = redact_text("Cookie: sid=secret-value token=abc123")
    assert redact_text(once) == once


# ---------------------------------------------------------------------------
# Filter：消息 / 参数 / 异常栈
# ---------------------------------------------------------------------------


def test_filter_redacts_formatted_message_and_args():
    logger, stream = _logger_with_capture("bgv.test.redaction.msg")
    logger.info("sniff headers cookie=%s", "sid=secret-value")
    output = stream.getvalue()
    assert "secret-value" not in output
    assert "[REDACTED]" in output


def test_uvicorn_style_args_keep_types_and_format():
    """访问日志用 5 个占位参数（含 %d）；脱敏不得清空参数的 int 类型。"""
    logger, stream = _logger_with_capture("bgv.test.redaction.uvicorn")
    logger.info(
        '%s - "%s %s HTTP/%s" %d',
        "127.0.0.1:1",
        "GET",
        "/v1/health?token=abc123&quality=720",
        "1.1",
        200,
    )
    output = stream.getvalue()
    assert "200" in output
    assert "abc123" not in output
    assert "token=[REDACTED]" in output
    assert "quality=720" in output


def test_filter_redacts_exception_traceback():
    logger, stream = _logger_with_capture("bgv.test.redaction.exc")
    try:
        raise ValueError("upstream token=abc123 rejected")
    except ValueError:
        logger.exception("download failed")
    output = stream.getvalue()
    assert "ValueError" in output
    assert "abc123" not in output


def test_install_log_redaction_covers_root_handlers():
    stream = io.StringIO()
    handler = logging.StreamHandler(stream)
    handler.setFormatter(logging.Formatter("%(message)s"))
    root = logging.getLogger()
    root.addHandler(handler)
    try:
        install_log_redaction()
        logging.getLogger("bgv.engine").warning("cookie sid=secret-value")
    finally:
        root.removeHandler(handler)
    assert "secret-value" not in stream.getvalue()

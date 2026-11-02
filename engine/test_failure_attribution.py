"""P1②：下载异常 → §5 错误码的归因表（纯函数，桩侧表格化断言）。

这里证的是**分类规则**；端到端（真下载循环 + 真 httpx 边界）在
`tests_real/test_failure_attribution_real.py`。两边用例不重复造轮子：
本文件用真的异常类型对象（`socket.gaierror` / `ssl.SSLCertVerificationError` /
`httpx.ConnectTimeout` / 带 errno 的 `OSError`），只是不经过网络。
"""
from __future__ import annotations

import errno
import socket
import ssl

import httpx
import pytest

from models import ERROR_CODE_INFO, ErrorCode
from services.downloader import DownloadIntegrityError
from services.failures import classify_download_failure


def _wrapped(inner: type[BaseException], *args) -> Exception:
    """复刻 yt-dlp 的现场：外层是一个不认识的下层异常，链条挂在 __cause__ 上。"""
    try:
        try:
            raise inner(*args)
        except inner as exc:
            raise RuntimeError("ERROR: unable to download video data") from exc
    except RuntimeError as exc:
        return exc


# (异常, 期望码)：类型/errno 优先于文本
TYPE_CASES = [
    (socket.gaierror(-2, "Name or service not known"), ErrorCode.DNS_FAILURE),
    (ssl.SSLCertVerificationError("certificate verify failed"), ErrorCode.TLS_ERROR),
    (socket.timeout("timed out"), ErrorCode.NETWORK_TIMEOUT),
    (TimeoutError(), ErrorCode.NETWORK_TIMEOUT),
    (httpx.ConnectTimeout(""), ErrorCode.NETWORK_TIMEOUT),
    (httpx.ReadTimeout(""), ErrorCode.NETWORK_TIMEOUT),
    (PermissionError(errno.EACCES, "Permission denied"), ErrorCode.WRITE_PERMISSION_DENIED),
    (OSError(errno.ENOSPC, "No space left on device"), ErrorCode.DISK_FULL),
]

# (异常文本, 期望码)：下层异常被包成字符串后仍要判得出
TEXT_CASES = [
    (
        "ERROR: https://video.example.com/x.m3u8: <urlopen error "
        "[Errno 11001] getaddrinfo failed>",
        ErrorCode.DNS_FAILURE,
    ),
    ("ERROR: [SSL: CERTIFICATE_VERIFY_FAILED] certificate verify failed", ErrorCode.TLS_ERROR),
    ("ERROR: The read operation timed out", ErrorCode.NETWORK_TIMEOUT),
    ("HTTP Error 429: Too Many Requests", ErrorCode.RATE_LIMITED),
    ("HTTP Error 503: Service Unavailable", ErrorCode.NETWORK_HTTP_ERROR),
    ("HTTP Error 404: Not Found", ErrorCode.NETWORK_HTTP_ERROR),
    ("HTTP Error 403: Forbidden", ErrorCode.AUTH_REQUIRED),
    ("HTTP 403", ErrorCode.AUTH_REQUIRED),
    ("write failed: [Errno 28] No space left on device", ErrorCode.DISK_FULL),
    ("[WinError 5] Access is denied", ErrorCode.WRITE_PERMISSION_DENIED),
    (
        "peer closed connection without sending complete message body (received 10 bytes, "
        "expected 100)",
        ErrorCode.PARTIAL_DOWNLOAD,
    ),
]

STATUS_CASES = [
    ("HTTP Error 403: Forbidden", ErrorCode.AUTH_REQUIRED, 403),
    ("HTTP Error 401: Unauthorized", ErrorCode.AUTH_REQUIRED, 401),
    ("HTTP error 500: Internal Server Error", ErrorCode.NETWORK_HTTP_ERROR, 500),
    ("HTTP error 503: Service Unavailable", ErrorCode.NETWORK_HTTP_ERROR, 503),
]


@pytest.mark.parametrize("exc, expected", TYPE_CASES)
def test_exception_type_decides_before_text(exc, expected):
    assert classify_download_failure(exc)[0] is expected


@pytest.mark.parametrize("text, expected", TEXT_CASES)
def test_wrapped_failure_text_is_attributed(text, expected):
    assert classify_download_failure(RuntimeError(text))[0] is expected


@pytest.mark.parametrize("text, expected, status", STATUS_CASES)
def test_http_status_lands_in_detail_machine_readably(text, expected, status):
    code, detail = classify_download_failure(RuntimeError(text))
    assert code is expected
    assert detail == {"reason": f"http_{status}", "status": status}


def test_mechanism_description_wins_over_status_but_status_stays_as_note():
    """`HTTP Error 429` 里「限流」是机制、429 是证据：码按机制给，状态降级成附注。"""
    code, detail = classify_download_failure(
        RuntimeError("HTTP Error 429: Too Many Requests")
    )
    assert code is ErrorCode.RATE_LIMITED
    assert detail == {"reason": "rate_limited", "status": 429}


def test_timeout_beats_a_gateway_status_when_both_appear():
    """504 只是「超时」的表现形式：归 NETWORK_TIMEOUT（可重试）而不是 NETWORK_HTTP_ERROR。"""
    code, detail = classify_download_failure(
        RuntimeError("The read operation timed out: HTTP Error 504")
    )
    assert code is ErrorCode.NETWORK_TIMEOUT
    assert detail == {"reason": "network_timeout", "status": 504}


def test_windows_disk_full_and_write_denied_come_from_winerror():
    """Windows 的 OSError 常常 errno=None，只有 winerror：只看 errno 会全部漏判。"""
    full = OSError("write failed")
    full.winerror = 112  # ERROR_DISK_FULL
    denied = OSError("write failed")
    denied.winerror = 5  # ERROR_ACCESS_DENIED
    assert classify_download_failure(full)[0] is ErrorCode.DISK_FULL
    assert classify_download_failure(denied)[0] is ErrorCode.WRITE_PERMISSION_DENIED


def test_cause_chain_is_walked_not_just_the_outermost_exception():
    exc = _wrapped(socket.timeout, "timed out")
    assert str(exc) == "ERROR: unable to download video data"  # 外层文本里没有线索
    assert classify_download_failure(exc)[0] is ErrorCode.NETWORK_TIMEOUT


def test_self_referencing_chain_does_not_hang():
    outer = RuntimeError("boom")
    outer.__context__ = outer  # 造一个成环的链，走链的实现必须有 seen 集合
    assert classify_download_failure(outer) == (ErrorCode.UNKNOWN, {"reason": "unknown"})


def test_integrity_error_falls_back_to_partial_download():
    code, detail = classify_download_failure(
        DownloadIntegrityError("产物不存在或为空：x.mp4"),
        fallback=ErrorCode.PARTIAL_DOWNLOAD,
    )
    assert code is ErrorCode.PARTIAL_DOWNLOAD
    assert detail == {"reason": "partial_download"}


def test_undecidable_failure_says_so_instead_of_borrowing_a_network_code():
    """判不出来就 UNKNOWN，**不拿 `NETWORK_HTTP_ERROR` 之类冒充**——归因错比归因缺更糟。"""
    code, _ = classify_download_failure(ValueError("extractor returned a weird shape"))
    assert code is ErrorCode.UNKNOWN


@pytest.mark.parametrize(
    "code, retryable",
    [
        (ErrorCode.NETWORK_TIMEOUT, True),
        (ErrorCode.DNS_FAILURE, True),
        (ErrorCode.NETWORK_HTTP_ERROR, True),
        (ErrorCode.RATE_LIMITED, True),
        (ErrorCode.PARTIAL_DOWNLOAD, True),
        (ErrorCode.TLS_ERROR, False),
        (ErrorCode.DISK_FULL, False),
        (ErrorCode.WRITE_PERMISSION_DENIED, False),
        (ErrorCode.AUTH_REQUIRED, False),
        (ErrorCode.UNKNOWN, False),
    ],
)
def test_mapped_codes_keep_their_retryable_semantics(code, retryable):
    """P1② 的真实痛点：全塌成 UNKNOWN 时，可重试的失败也变成了「重试按钮不可点」。"""
    assert ERROR_CODE_INFO[code][1] is retryable


@pytest.mark.parametrize(
    "text",
    [
        "ERROR: https://private.example.com/secret.m3u8?Cookie=session%3Dabc: HTTP Error 403",
        "[Errno 11001] getaddrinfo failed for Referer https://private.example.com/page",
    ],
)
def test_detail_never_carries_the_exception_text(text):
    """§1.7 脱敏：URL / Cookie / Referer 留在异常对象里，不进协议 detail。

    `http_403` 这种**码名**是允许的（机器可读、不含 host）；被禁的是原文片段。
    """
    _, detail = classify_download_failure(RuntimeError(text))
    blob = repr(detail).lower()
    for token in ("://", "example.com", "cookie", "referer", "secret", "session", ".m3u8"):
        assert token not in blob

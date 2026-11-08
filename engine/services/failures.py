"""下载/嗅探期异常 → §5 错误码的归因表（M4 P1②）。

为什么单独一个模块：归因是**协议口径**，不是下载器的实现细节。以前所有逃逸异常都被
`main.py` 的 `except Exception` 收成 `UNKNOWN`，后果是两条——用户看到一句「未知错误」，
而 `retryable` 跟着失真（`UNKNOWN` 不可重试，可 `NETWORK_TIMEOUT` / `DNS_FAILURE` 这些
一半以上是可重试的）。M4 第 2 步的事件流要能把失败原样表达给客户端，前提就是这里先分对。

判定优先级（硬事实优先，文本只当兜底线索）：

1. 异常**类型 / errno**：`socket.gaierror`、`ssl.SSLError`、`TimeoutError`、
   `httpx.TimeoutException`、`PermissionError`、`errno.ENOSPC` 这些是确定的事实；
2. 异常**文本**里的标记：yt-dlp 会把底层异常包成 `DownloadError`，链条常常断掉，
   只剩一句 `... getaddrinfo failed ...`，这时才按关键字判；嗅探侧拿到的是
   Chromium 的网络错误码（`net::ERR_CONNECTION_RESET` 一类，M21 起同样在这张表里）；
3. 文本里的 **HTTP 状态码**：401/403 → `AUTH_REQUIRED`（站点拒绝，通常是缺 Cookie），
   429 → `RATE_LIMITED`，其余 4xx/5xx → `NETWORK_HTTP_ERROR`；
4. 都判不出来 → 调用方给的 `fallback`（完整性类落 `PARTIAL_DOWNLOAD`，其它落 `UNKNOWN`）。

**detail 里绝不含异常原文**：yt-dlp / httpx 的报错文本里常带 URL、Referer 甚至 Cookie，
§1.7 的脱敏口径不能因为「想多给点诊断信息」就破。日志同理，只打类型名与码。
"""
from __future__ import annotations

import errno
import re
import socket
import ssl
from typing import Any, Iterator

import httpx

from models import ErrorCode

# Windows 的 WinError 与 POSIX errno 分开看：磁盘满 / 空间不足在两边是不同的数
_DISK_ERRNOS = {errno.ENOSPC, getattr(errno, "EDQUOT", -1)}
_DISK_WINERRORS = {39, 112}
_WRITE_ERRNOS = {errno.EACCES, errno.EPERM, getattr(errno, "EROFS", -1)}
_WRITE_WINERRORS = {5}

_DNS_MARKERS = (
    "getaddrinfo",
    "name or service not known",
    "nodename nor servname provided",
    "temporary failure in name resolution",
    "unknown host",
    "name resolution",
    # Chromium/Playwright 的网络错误码（M21）
    "err_name_not_resolved",
    "err_name_resolution_failed",
)
_TLS_MARKERS = (
    "certificate",
    "ssl",
    "tls",
    "handshake",
    "unable to get local issuer",
    "err_ssl_",
    "err_cert_",
)
_TIMEOUT_MARKERS = (
    "timed out",
    "timeout",
    "read operation timed",
    "err_timed_out",
    "err_connection_timed_out",
)
# M21：连接被重置/拒绝/地址不可达——**不是**「站点已变更」，是链路层掐断。
# 国内被阻断的域名典型表现就是 ERR_CONNECTION_RESET（TLS 握手期 RST），
# 所以这类必须与 EXTRACTOR_FAILED 分开，并给出「可尝试代理」的提示。
# 归到 NETWORK_HTTP_ERROR：category=network、retryable=true，语义最贴近的既有码。
_BLOCKED_MARKERS = (
    "err_connection_reset",
    "err_connection_closed",
    "err_connection_refused",
    "err_connection_aborted",
    "err_address_unreachable",
    "err_internet_disconnected",
    "err_network_changed",
    "err_network_access_denied",
    "err_empty_response",
    "err_http2_protocol_error",
)
# 代理自己不通（端口错 / 未启动 / 需要认证）：同样是链路问题，但要说清是代理的锅，
# 而不是让用户以为站点被封、再去改一次代理地址。
_PROXY_MARKERS = (
    "err_proxy_connection_failed",
    "err_tunnel_connection_failed",
    "err_proxy_auth",
)

#: 连接层被掐断时给用户的话。与 EXTRACTOR_FAILED 的「解析失败，可能站点已变更」区分开：
#: 站点没变、页面也没解析过，是**连接根本没建立**——暗示用户去配代理才有意义。
CONNECTION_BLOCKED_MESSAGE = "目标站点连接被重置，可能被网络阻断；可在电脑端配置代理后重试"

#: 配了代理但代理本身不可用（端口写错 / 代理软件没开）时的话。
PROXY_UNREACHABLE_MESSAGE = "代理不可用：连不上配置的代理地址，请检查「网络代理」的地址与端口"
_RATE_LIMIT_MARKERS = ("too many requests", "rate limit", "429")
_DISK_MARKERS = (
    "no space left on device",
    "not enough space",
    "insufficient disk space",
    "os error 112",
)
_WRITE_MARKERS = (
    "permission denied",
    "access is denied",
    "read-only file system",
    "operation not permitted",
    "os error 5",
)
# 服务端把连接半路掐了：不是「站点报错」，是「下载不完整」——重试有意义，改码没意义
_TRUNCATION_MARKERS = (
    # httpx 的原话：'peer closed connection without sending complete message body'
    "peer closed connection",
    "without sending complete message body",
    "incomplete message body",
    "connection dropped",
    "server disconnected",
)

# 只要「http error 403 / status code 429 / HTTP 403」这类**带前缀**的三位数，
# 不认裸数字：URL 里的 host（127.0.0.1）与字节数（1024/3072）都可能凑出三位数。
_HTTP_STATUS_RE = re.compile(
    r"\b(?:http\s+error|http\s+status|status\s+code|http|err(?:or)?)\s*:?\s+(\d{3})\b(?!\.\d)",
    re.IGNORECASE,
)
_STATUS_BY_CODE = {401: ErrorCode.AUTH_REQUIRED, 403: ErrorCode.AUTH_REQUIRED}


def _exception_chain(exc: BaseException) -> Iterator[BaseException]:
    """沿 `__cause__` / `__context__` 走一圈，`seen` 防自引用成环。"""
    seen: set[int] = set()
    current: BaseException | None = exc
    while current is not None and id(current) not in seen:
        seen.add(id(current))
        yield current
        current = current.__cause__ or current.__context__


def _code_from_type(item: BaseException) -> ErrorCode | None:
    if isinstance(item, httpx.TimeoutException) or isinstance(
        item, (socket.timeout, TimeoutError)
    ):
        return ErrorCode.NETWORK_TIMEOUT
    if isinstance(item, socket.gaierror):
        return ErrorCode.DNS_FAILURE
    if isinstance(item, ssl.SSLError):
        return ErrorCode.TLS_ERROR
    if isinstance(item, PermissionError):
        return ErrorCode.WRITE_PERMISSION_DENIED
    if isinstance(item, OSError):
        if item.errno in _DISK_ERRNOS or getattr(item, "winerror", None) in _DISK_WINERRORS:
            return ErrorCode.DISK_FULL
        if item.errno in _WRITE_ERRNOS or getattr(item, "winerror", None) in _WRITE_WINERRORS:
            return ErrorCode.WRITE_PERMISSION_DENIED
    return None


def _code_from_markers(text: str) -> ErrorCode | None:
    """按「最不可能误判」排：DNS → TLS → 超时 → 连接掐断 → 限流 → 磁盘 → 写权限 → 半路断流。"""
    for markers, code in (
        (_DNS_MARKERS, ErrorCode.DNS_FAILURE),
        (_TLS_MARKERS, ErrorCode.TLS_ERROR),
        (_TIMEOUT_MARKERS, ErrorCode.NETWORK_TIMEOUT),
        (_PROXY_MARKERS, ErrorCode.NETWORK_HTTP_ERROR),
        (_BLOCKED_MARKERS, ErrorCode.NETWORK_HTTP_ERROR),
        (_RATE_LIMIT_MARKERS, ErrorCode.RATE_LIMITED),
        (_DISK_MARKERS, ErrorCode.DISK_FULL),
        (_WRITE_MARKERS, ErrorCode.WRITE_PERMISSION_DENIED),
        (_TRUNCATION_MARKERS, ErrorCode.PARTIAL_DOWNLOAD),
    ):
        if any(marker in text for marker in markers):
            return code
    return None


def _code_from_status(text: str) -> tuple[ErrorCode, int] | None:
    for match in _HTTP_STATUS_RE.finditer(text):
        status = int(match.group(1))
        if status < 400 or status > 599:
            continue
        if status in _STATUS_BY_CODE:
            return _STATUS_BY_CODE[status], status
        if status == 429:
            return ErrorCode.RATE_LIMITED, status
        return ErrorCode.NETWORK_HTTP_ERROR, status
    return None


def _first_status(texts: list[str]) -> int | None:
    """文本里出现的第一个 4xx/5xx：只当**附注**（诊断用），不参与决定码。"""
    for text in texts:
        for match in _HTTP_STATUS_RE.finditer(text):
            status = int(match.group(1))
            if 400 <= status <= 599:
                return status
    return None


def classify_download_failure(
    exc: BaseException, *, fallback: ErrorCode = ErrorCode.UNKNOWN
) -> tuple[ErrorCode, dict[str, Any]]:
    """把一次下载失败归到 §5 的码，并给出**脱敏后**的 detail。

    `fallback` 由调用方决定语义：`DownloadIntegrityError` 一类「产物不完整」的确定
    归 `PARTIAL_DOWNLOAD`，其余不归任何具体类的一律 `UNKNOWN`——**判不出来就说判不出来**，
    不拿 `NETWORK_*` 冒充。

    机制描述（超时 / DNS / 限流）优先于 HTTP 状态：`HTTP Error 504` 里真正致命的往往是
    「读超时」，此时状态码降级为 detail 里的 `status` 附注，不反客为主。
    """
    chain = list(_exception_chain(exc))
    for item in chain:
        code = _code_from_type(item)
        if code is not None:
            return code, {"reason": code.value.lower()}

    texts = [str(item).lower() for item in chain]
    for text in texts:
        code = _code_from_markers(text)
        if code is not None:
            detail: dict[str, Any] = {"reason": code.value.lower()}
            status = _first_status(texts)
            if status is not None:
                detail["status"] = status
            return code, detail
    for text in texts:
        hit = _code_from_status(text)
        if hit is not None:
            code, status = hit
            return code, {"reason": f"http_{status}", "status": status}

    return fallback, {"reason": fallback.value.lower()}

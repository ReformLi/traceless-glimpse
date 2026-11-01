"""出站安全校验器：SSRF 防护 + 输出目录白名单 + headers/detail 脱敏（协议 §1）。

全部标准库实现（裁决：不引入 requests/aiohttp）。任务创建与下载入口的
唯一调用链是 main.py；重定向后的二次校验同样走 validate_outbound_url。
"""
from __future__ import annotations

import ipaddress
import logging
import os
import socket
from pathlib import Path
from typing import Any, Optional
from urllib.parse import urlsplit

logger = logging.getLogger("bgv.engine")

# 输出目录白名单（§6.5 示例值；M0 固定，M1 走配置）
DEFAULT_OUTPUT_DIR_ALLOWLIST = ["/home/user/Videos/BGV"]

# 媒体产物扩展名白名单（FR-SEC-03）；``.part`` 仅作临时文件，不在此表
ALLOWED_OUTPUT_EXTENSIONS = frozenset(
    {
        ".mp4",
        ".m4v",
        ".mkv",
        ".webm",
        ".mov",
        ".flv",
        ".ts",
        ".m4a",
        ".aac",
        ".mp3",
        ".opus",
        ".ogg",
        ".wav",
        ".flac",
    }
)

# Windows 设备保留名：同名文件即使扩展名合法也无法创建
_RESERVED_STEMS = frozenset(
    {"con", "prn", "aux", "nul"}
    | {f"com{index}" for index in range(1, 10)}
    | {f"lpt{index}" for index in range(1, 10)}
)

# 出站 URL 允许的 scheme（§5 UNSUPPORTED_SCHEME：仅 http/https）
_ALLOWED_SCHEMES = ("http", "https")

# 永久拒绝的保留主机名（大小写不敏感）
_BLOCKED_HOSTNAMES = frozenset(
    {
        "localhost",
        "localhost.localdomain",
        "ip6-localhost",
        "ip6-loopback",
        "metadata.google.internal",
        "metadata.goog",
    }
)

# 敏感头键（小写比对）；子串命中同样视为敏感（§1.6）
_SENSITIVE_HEADER_KEYS = frozenset(
    {"cookie", "set-cookie", "authorization", "proxy-authorization"}
)
_SENSITIVE_KEY_SUBSTRINGS = ("token", "secret", "api-key", "apikey")


class SecurityError(Exception):
    """安全拒绝基类：main.py 的 handler 转 ErrorPayload（400）。"""

    def __init__(self, code: str, detail: dict[str, Any] | None = None):
        super().__init__(code)
        self.code = code
        self.detail = detail


class InvalidUrlError(SecurityError):
    def __init__(self):
        super().__init__("INVALID_URL", None)


class UnsupportedSchemeError(SecurityError):
    def __init__(self, scheme: str):
        super().__init__("UNSUPPORTED_SCHEME", {"scheme": scheme})


class SsrfBlockedError(SecurityError):
    def __init__(self, host: str):
        super().__init__("SSRF_BLOCKED", {"host": host})


class InvalidOutputDirError(SecurityError):
    def __init__(self):
        super().__init__("INVALID_OUTPUT_DIR", None)


class OutputDirRejectedError(SecurityError):
    def __init__(self):
        super().__init__("OUTPUT_DIR_REJECTED", None)


class OutputFilenameRejectedError(SecurityError):
    """文件名 / 扩展名不安全：复用 ``OUTPUT_DIR_REJECTED``，detail 说明原因。

    不新增协议错误码（避免三端同步升级）；detail 只带扩展名，不含路径。
    """

    def __init__(self, extension: str, reason: str = "extension_not_allowed"):
        super().__init__(
            "OUTPUT_DIR_REJECTED",
            {"reason": reason, "extension": extension},
        )


# ---------------------------------------------------------------------------
# SSRF：出站 URL 校验（§1.5）
# ---------------------------------------------------------------------------

DEV_SSRF_ALLOWLIST_ENV = "BGV_SSRF_ALLOWLIST"
DEV_SSRF_BYPASS_ENV = "BGV_DEV_ALLOW_SSRF_BYPASS"
ENGINE_MODE_ENV = "BGV_ENGINE_MODE"
_DEV_TRUTHY = frozenset({"1", "true", "yes", "on"})


def _dev_ssrf_allowlist() -> frozenset[str]:
    """临时开发白名单（方案 X）：三门控同时满足才生效，默认完全不注册。

    1. ``BGV_SSRF_ALLOWLIST`` 非空 —— 逗号分隔主机名/IP，逐个**整串精确匹配**，
       不支持 IP 段（``192.168.0.0/16``）也不支持后缀匹配（``.local``）；
    2. ``BGV_DEV_ALLOW_SSRF_BYPASS`` 为真值；
    3. ``BGV_ENGINE_MODE=real``。

    dev only, never in production：生产配置不应同时设置这两个变量。
    """
    allow_raw = os.getenv(DEV_SSRF_ALLOWLIST_ENV, "").strip()
    bypass_on = os.getenv(DEV_SSRF_BYPASS_ENV, "").strip().lower() in _DEV_TRUTHY
    if not allow_raw or not bypass_on:
        return frozenset()
    mode = os.getenv(ENGINE_MODE_ENV, "stub").strip().lower()
    if mode != "real":
        logger.warning("dev_ssrf_bypass_ignored engine_mode=%s", mode)
        return frozenset()
    return frozenset(
        item.strip().rstrip(".").lower() for item in allow_raw.split(",") if item.strip()
    )


def _is_blocked_ip(ip: ipaddress.IPv4Address | ipaddress.IPv6Address) -> bool:
    """回环 / 私有 / 链路本地（含元数据 169.254.0.0/16）/ 保留 / 组播 / 未指定一律拒绝。"""
    if isinstance(ip, ipaddress.IPv6Address):
        mapped = ip.ipv4_mapped
        if mapped is not None:  # IPv4-mapped IPv6 先解包再判
            ip = mapped
    return (
        ip.is_loopback
        or ip.is_private
        or ip.is_link_local
        or ip.is_reserved
        or ip.is_multicast
        or ip.is_unspecified
    )


def _host_block_reason(host: str) -> Optional[str]:
    """``None`` 放行；字符串为拒绝归类（只给类别，不带 URL / 端口）。

    host 为小写化后的主机名（无端口）。IP 字面量直判；域名解析全部地址判。
    """
    if host in _BLOCKED_HOSTNAMES:
        return "blocked_hostname"
    try:
        ip_literal = ipaddress.ip_address(host)
    except ValueError:
        pass
    else:
        return "blocked_ip" if _is_blocked_ip(ip_literal) else None
    # 域名：getaddrinfo 解析出的每一个地址都必须干净（含多 A 记录混入私网的情况）
    try:
        infos = socket.getaddrinfo(host, None)
    except OSError:
        # 域名解析失败归入 INVALID_URL（§5：非 http/https 或解析失败）
        raise InvalidUrlError() from None
    for info in infos:
        addr = info[4][0]
        try:
            ip = ipaddress.ip_address(addr)
        except ValueError:
            continue
        if _is_blocked_ip(ip):
            return "resolved_blocked_ip"
    return None


def validate_outbound_url(url: str) -> str:
    """出站 URL 唯一校验入口。通过则原样返回，非法则抛 SecurityError 子类。

    校验顺序：scheme 白名单 → 临时开发白名单 → 主机名/IP 判定。
    """
    try:
        parts = urlsplit(url)
    except ValueError:
        raise InvalidUrlError() from None
    scheme = (parts.scheme or "").lower()
    if scheme not in _ALLOWED_SCHEMES:
        raise UnsupportedSchemeError(scheme)
    host = parts.hostname
    if not host:
        raise InvalidUrlError()
    host = host.rstrip(".").lower()  # 尾点归一化，防 "localhost." 绕过
    if host in _dev_ssrf_allowlist():
        # 每次放行一条；只打 host，不打完整 URL（§1.6 脱敏）
        logger.info("ssrf_allowlist_bypass host=%s", host)
        return url
    reason = _host_block_reason(host)
    if reason is not None:
        logger.info("ssrf_blocked host=%s reason=%s", host, reason)
        raise SsrfBlockedError(host)
    return url


def validate_redirect_chain(initial: str, hops: list[str]) -> None:
    """重定向二次校验（§1.5）：初始 URL 与每一跳都必须通过出站校验。"""
    validate_outbound_url(initial)
    for hop in hops:
        validate_outbound_url(hop)


# ---------------------------------------------------------------------------
# 输出目录白名单（§1.3，Windows 友好）
# ---------------------------------------------------------------------------


def _rejected_roots() -> set[str]:
    """拒绝清单：/、当前盘符根（C:\\）、$HOME、cwd、PATH 内目录。均已 normcase(realpath)。"""
    roots = {
        os.path.normcase(os.path.realpath(os.sep)),
        os.path.normcase(os.path.abspath(os.sep)),
        os.path.normcase(os.path.realpath(os.path.expanduser("~"))),
        os.path.normcase(os.path.realpath(os.getcwd())),
    }
    for item in os.environ.get("PATH", "").split(os.pathsep):
        if item:
            roots.add(os.path.normcase(os.path.realpath(item)))
    return roots


def validate_output_dir(path: str, allowlist: Optional[list[str]] = None) -> str:
    """输出目录白名单校验。通过则原样返回，非法则抛 SecurityError 子类。

    Windows 语义：
    - 用 os.path.isabs（而非 PurePosixPath），使 "/" 在 Windows 上也判为
      绝对路径，返回 OUTPUT_DIR_REJECTED 而非 INVALID_OUTPUT_DIR（§9.4）；
    - 比对前对 realpath 结果做 normcase，消除大小写与正反斜杠差异；
    - 不用 chmod / 权限探测做校验。
    """
    if not isinstance(path, str) or not path:
        raise InvalidOutputDirError()
    if not os.path.isabs(path):
        raise InvalidOutputDirError()
    real = os.path.normcase(os.path.realpath(path))
    if real in _rejected_roots():
        raise OutputDirRejectedError()
    for allow_root in (allowlist if allowlist is not None else DEFAULT_OUTPUT_DIR_ALLOWLIST):
        allow_real = os.path.normcase(os.path.realpath(allow_root))
        if real == allow_real or real.startswith(allow_real + os.sep):
            return path
    raise OutputDirRejectedError()


def prepare_output_dir(path: str | Path) -> Path:
    """创建（或复用）输出目录，并尽力收紧为 0700（Windows 下为尽力而为）。"""
    out = Path(path)
    out.mkdir(parents=True, exist_ok=True, mode=0o700)
    try:
        os.chmod(out, 0o700)
    except OSError:
        pass
    return out


def validate_output_filename(filename: str) -> str:
    """返回可安全落盘的文件名（取 basename），否则抛 ``OutputFilenameRejectedError``。

    拒绝：空名 / 尾点尾空格 / NUL / Windows 设备保留名 / 非白名单扩展名。
    目录穿越段在取 basename 时被丢弃，不参与最终路径。
    """
    if not isinstance(filename, str):
        raise OutputFilenameRejectedError("")
    name = os.path.basename(filename.replace("\\", "/"))
    if not name.strip() or name in {".", ".."} or "\x00" in name:
        raise OutputFilenameRejectedError("", "filename_rejected")
    if name != name.rstrip(" ."):  # Windows 会静默去掉尾点 / 空格
        raise OutputFilenameRejectedError(Path(name).suffix.lower(), "filename_rejected")
    if name.split(".", 1)[0].lower() in _RESERVED_STEMS:
        raise OutputFilenameRejectedError(Path(name).suffix.lower(), "filename_rejected")
    suffix = Path(name).suffix.lower()
    if suffix not in ALLOWED_OUTPUT_EXTENSIONS:
        raise OutputFilenameRejectedError(suffix)
    return name


def validate_output_target(out_dir: str | Path, target: str | Path) -> Path:
    """产物必须直接位于 out_dir 内（防标题拼出上级路径）。"""
    out_real = os.path.normcase(os.path.realpath(out_dir))
    target_real = os.path.normcase(os.path.realpath(target))
    if os.path.dirname(target_real) != out_real:
        raise SecurityError("OUTPUT_DIR_REJECTED", {"reason": "output_outside_dir"})
    return Path(target)


# ---------------------------------------------------------------------------
# 脱敏（§1.6 headersHint / §1.7 error.detail）
# ---------------------------------------------------------------------------


def sanitize_headers(headers: dict[str, str] | None) -> dict[str, str]:
    """移除 Cookie / Authorization / Set-Cookie / Proxy-Authorization 及自定义鉴权头。"""
    if not headers:
        return {}
    clean: dict[str, str] = {}
    for key, value in headers.items():
        lowered = str(key).lower()
        if lowered in _SENSITIVE_HEADER_KEYS:
            continue
        if any(sub in lowered for sub in _SENSITIVE_KEY_SUBSTRINGS):
            continue
        clean[key] = value
    return clean


def sanitize_detail(detail: dict[str, Any] | None) -> dict[str, Any] | None:
    """递归脱敏错误 detail：敏感键的值替换为 [REDACTED]，结构保留。"""
    if not detail:
        return None
    return _sanitize_mapping(detail)


def _sanitize_mapping(mapping: dict[str, Any]) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for key, value in mapping.items():
        lowered = str(key).lower()
        if lowered in _SENSITIVE_HEADER_KEYS or any(
            sub in lowered for sub in _SENSITIVE_KEY_SUBSTRINGS
        ):
            out[key] = "[REDACTED]"
        elif isinstance(value, dict):
            out[key] = _sanitize_mapping(value)
        else:
            out[key] = value
    return out

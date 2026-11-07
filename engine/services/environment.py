"""运行环境只读自检（PROTOCOL §6.5）。

只汇总本机可观测事实，不发起任何出站请求，也不读取 Cookie 文件内容。
依赖版本按请求实时探测：自检是低频 P1 端点，实时探测比进程内缓存更可信。
"""
from __future__ import annotations

import importlib.metadata
import os
import re
import shutil
import subprocess
from pathlib import Path

# 监听地址不再写死：M15 桌面端把 uvicorn 实际绑定的 host/port 通过
# BGV_LISTEN_HOST / BGV_LISTEN_PORT 注入，selfcheck 按请求实时读取，避免
# 「界面显示 0.0.0.0 供手机连接、自检却回 127.0.0.1」的失真。
# 未注入时回落到协议 §1 表头的默认回环 + 8730（契约用例零改动）。
DEFAULT_LISTEN_HOST = "127.0.0.1"
DEFAULT_LISTEN_PORT = 8730
LISTEN_ADDRESS = f"{DEFAULT_LISTEN_HOST}:{DEFAULT_LISTEN_PORT}"  # 协议默认值，保留常量兼容
DEFAULT_CACHE_TTL_HOURS = 48
UNAVAILABLE = "unavailable"
PROBE_TIMEOUT_SECONDS = 5

_FFMPEG_VERSION = re.compile(r"ffmpeg version (\S+)", re.IGNORECASE)


def cache_dir() -> Path:
    override = os.getenv("BGV_CACHE_DIR", "").strip()
    if override:
        return Path(override).expanduser()
    if os.name == "nt":
        base = os.getenv("LOCALAPPDATA", "").strip()
        root = Path(base).expanduser() if base else Path.home() / "AppData" / "Local"
        return root / "BGV" / "cache"
    xdg = os.getenv("XDG_CACHE_HOME", "").strip()
    root = Path(xdg).expanduser() if xdg else Path.home() / ".cache"
    return root / "bgv"


def cache_size_bytes(root: Path) -> int:
    """目录内普通文件字节数；目录不存在 / 无权限时返回 0。"""
    try:
        entries = list(root.rglob("*"))
    except OSError:
        return 0
    total = 0
    for entry in entries:
        try:
            if entry.is_symlink() or not entry.is_file():
                continue
            total += entry.stat().st_size
        except OSError:
            continue
    return total


def listen_host() -> str:
    """uvicorn 实际绑定的 host（由启动器注入；缺省为协议默认回环）。"""
    return os.getenv("BGV_LISTEN_HOST", "").strip() or DEFAULT_LISTEN_HOST


def listen_port() -> int:
    """uvicorn 实际绑定的 port。脏环境变量一律回落默认值，绝不让自检端点 500。"""
    raw = os.getenv("BGV_LISTEN_PORT", "").strip()
    if raw.isdecimal():
        value = int(raw)
        if 1 <= value <= 65535:
            return value
    return DEFAULT_LISTEN_PORT


def listen_address() -> str:
    """``host:port``，与本次引擎进程的实际监听保持一致。"""
    return f"{listen_host()}:{listen_port()}"


def _ytdlp_version() -> str:
    try:
        from yt_dlp.version import __version__
    except Exception:
        return UNAVAILABLE
    return str(__version__) if __version__ else UNAVAILABLE


def _package_version(name: str) -> str:
    try:
        return importlib.metadata.version(name)
    except Exception:
        return UNAVAILABLE


def _ffmpeg_version() -> str:
    executable = shutil.which("ffmpeg")
    if not executable:
        return UNAVAILABLE
    try:
        proc = subprocess.run(
            [executable, "-version"],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=PROBE_TIMEOUT_SECONDS,
            check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return UNAVAILABLE
    match = _FFMPEG_VERSION.search(proc.stdout or "")
    return match.group(1) if match else UNAVAILABLE


def dependency_versions() -> dict[str, str]:
    return {
        "yt-dlp": _ytdlp_version(),
        "ffmpeg": _ffmpeg_version(),
        "playwright": _package_version("playwright"),
    }


def selfcheck_payload(*, output_dir_allowlist: list[str], cookie_policy_default: str) -> dict:
    root = cache_dir()
    return {
        "listenAddress": listen_address(),
        "tlsEnabled": False,
        "authEnabled": False,
        "activeProxyIds": [],
        "cookiePolicyDefault": cookie_policy_default,
        "outputDirAllowlist": list(output_dir_allowlist),
        "cacheDir": str(root),
        "cacheTtlHours": DEFAULT_CACHE_TTL_HOURS,
        "cacheSizeBytes": cache_size_bytes(root),
        "lastCleanupAt": None,
        "dependencyVersions": dependency_versions(),
    }

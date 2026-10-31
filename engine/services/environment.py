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

# 监听地址与 __main__ 中的 uvicorn 绑定保持一致（协议固定回环 + 8730，不提供覆盖入口）
LISTEN_ADDRESS = "127.0.0.1:8730"
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
        "listenAddress": LISTEN_ADDRESS,
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

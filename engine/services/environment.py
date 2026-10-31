"""运行环境自检（阶段 5）：FFmpeg / yt-dlp / Playwright 版本，供 selfcheck 接口。"""
from __future__ import annotations

import importlib.metadata
import logging
import shutil
import subprocess

logger = logging.getLogger("bgv.engine")

FFMPEG_HINT = "winget install Gyan.FFmpeg"


def ffmpeg_version() -> str | None:
    path = shutil.which("ffmpeg")
    if not path:
        return None
    try:
        proc = subprocess.run(
            [path, "-version"], capture_output=True, text=True, timeout=10
        )
    except OSError:
        return None
    first_line = (proc.stdout or "").splitlines()[0] if proc.stdout else ""
    parts = first_line.split()
    return parts[2] if len(parts) >= 3 else (first_line or None)


def package_version(name: str) -> str | None:
    try:
        return importlib.metadata.version(name)
    except importlib.metadata.PackageNotFoundError:
        return None


def dependency_versions() -> dict[str, str]:
    ffmpeg = ffmpeg_version()
    return {
        "yt-dlp": package_version("yt-dlp") or "unavailable",
        "ffmpeg": ffmpeg or "unavailable",
        "playwright": package_version("playwright") or "unavailable",
    }


def startup_selfcheck() -> dict[str, str]:
    versions = dependency_versions()
    if versions["ffmpeg"] == "unavailable":
        logger.warning("ffmpeg_missing hint=%s", FFMPEG_HINT)
    else:
        logger.info("selfcheck ffmpeg=%s", versions["ffmpeg"])
    return versions

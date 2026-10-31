"""下载器（阶段 3）：yt-dlp 库调用 + 双重出站校验 + ffprobe 完整性。

调用链：
1. 首跳 URL 过统一校验器；
2. ``extract_info(download=False)`` 后对 ``requested_downloads`` / ``formats``
   每个 URL 再过校验，任一被拒即拒绝（硬点 ①，下载前）；
3. 真正下载；
4. 再次对返回的 ``requested_downloads`` 做一次校验（防御 yt-dlp 内部派生 URL）；
5. ffprobe 校验：文件非空 + duration > 0。
"""
from __future__ import annotations

import json
import logging
import shutil
import subprocess
from pathlib import Path
from typing import Any, Callable
from urllib.parse import urlsplit

import httpx

from security import SecurityError, validate_outbound_url
from services.formats import validate_download_urls
from services.redirect import resolve_redirects

logger = logging.getLogger("bgv.engine")


class DownloadIntegrityError(Exception):
    """产物不完整（非空 / ffprobe 不可解析）。"""


def _ffprobe_duration(path: Path) -> float:
    ffprobe = shutil.which("ffprobe")
    if not ffprobe:
        raise DownloadIntegrityError("本机缺少 ffprobe，无法校验产物")
    proc = subprocess.run(
        [ffprobe, "-v", "error", "-show_format", "-of", "json", str(path)],
        capture_output=True,
        text=True,
    )
    if proc.returncode != 0:
        raise DownloadIntegrityError(f"ffprobe 解析失败：{path.name}")
    data = json.loads(proc.stdout or "{}")
    return float((data.get("format") or {}).get("duration") or 0)


def _content_length(response: httpx.Response) -> int | None:
    value = response.headers.get("content-length")
    return int(value) if value and value.isdigit() else None


def download_direct(
    media_url: str,
    output_dir: str | Path,
    *,
    filename: str | None = None,
    task_id: str = "unknown",
    validator: Callable[[str], str] = validate_outbound_url,
    transport: httpx.BaseTransport | None = None,
    timeout: float = 30.0,
) -> Path:
    """单文件直链下载：逐跳校验后流式写盘，并校验 Content-Length。

    HLS/DASH 不走本函数（交给 yt-dlp）。
    """
    validator(media_url)
    final_url = resolve_redirects(media_url, transport=transport, timeout=timeout)
    validator(final_url)
    out_dir = Path(output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    name = filename or Path(urlsplit(final_url).path).name or "download.bin"
    output = out_dir / name

    with httpx.Client(
        transport=transport, follow_redirects=False, timeout=timeout, trust_env=False
    ) as client:
        with client.stream("GET", final_url) as response:
            if response.status_code != 200:
                raise DownloadIntegrityError(f"HTTP {response.status_code}")
            total = _content_length(response)
            with output.open("wb") as handle:
                for chunk in response.iter_bytes():
                    handle.write(chunk)

    if not output.is_file() or output.stat().st_size <= 0:
        raise DownloadIntegrityError(f"下载产物缺失：{output.name}")
    if total is not None and output.stat().st_size != total:
        raise DownloadIntegrityError(
            f"下载不完整：{output.stat().st_size}/{total}"
        )
    logger.info("direct_download_complete task_id=%s bytes=%d", task_id, output.stat().st_size)
    return output


def download_media(
    media_url: str,
    output_dir: str | Path,
    *,
    task_id: str = "unknown",
    format_expression: str = "best",
    validator: Callable[[str], str] = validate_outbound_url,
    ydl_factory: Callable[[dict[str, Any]], Any] | None = None,
) -> Path:
    """下载到 ``output_dir``，返回产物路径（已通过完整性校验）。

    ``format_expression`` 由结构化 formatSelector 映射而来（best /
    bestaudio / best[height<=N]），不接受调用方直接传 shell 字符串。
    """
    validator(media_url)
    out_dir = Path(output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    options: dict[str, Any] = {
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "outtmpl": str(out_dir / "%(title)s.%(ext)s"),
        "format": format_expression,
        "socket_timeout": 30,
    }
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg:
        options["ffmpeg_location"] = ffmpeg

    factory = ydl_factory
    if factory is None:
        import yt_dlp

        factory = yt_dlp.YoutubeDL

    with factory(options) as ydl:
        probe = ydl.extract_info(media_url, download=False)
        rejected = validate_download_urls(probe, validator)
        if rejected:
            raise SecurityError("SSRF_BLOCKED", {"host": rejected[0]["host"]})
        info = ydl.extract_info(media_url, download=True)
        target = Path(ydl.prepare_filename(info))

    # 下载后再校验一次（防御 yt-dlp 内部派生 / 拼接出的 URL）
    rejected_after = validate_download_urls(info, validator)
    if rejected_after:
        target.unlink(missing_ok=True)
        raise SecurityError("SSRF_BLOCKED", {"host": rejected_after[0]["host"]})

    if not target.is_file() or target.stat().st_size <= 0:
        raise DownloadIntegrityError(f"产物不存在或为空：{target.name}")
    if _ffprobe_duration(target) <= 0:
        raise DownloadIntegrityError(f"ffprobe 无法解析产物：{target.name}")
    logger.info("download_complete task_id=%s bytes=%d", task_id, target.stat().st_size)
    return target

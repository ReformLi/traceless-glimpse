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
import os
import shutil
import subprocess
from contextlib import ExitStack
from pathlib import Path
from typing import Any, Callable
from urllib.parse import urlsplit

import httpx

from security import (
    SecurityError,
    prepare_output_dir,
    validate_outbound_url,
    validate_output_filename,
    validate_output_target,
)
from services import cookies as cookies_service
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


def _content_range_total(value: str | None) -> int | None:
    if not value or "/" not in value:
        return None
    total = value.rsplit("/", 1)[1]
    return int(total) if total.isdigit() else None


_REJECTION_MARKERS = ("401", "403", "forbidden", "unauthorized")


def _looks_like_rejection(exc: BaseException) -> bool:
    """yt-dlp 把 HTTP 状态塞进 DownloadError 文本；只据此触发一次惰性重试。"""
    text = str(exc).lower()
    return any(marker in text for marker in _REJECTION_MARKERS)


def download_direct(
    media_url: str,
    output_dir: str | Path,
    *,
    filename: str | None = None,
    task_id: str = "unknown",
    cookie_policy: str = "none",
    validator: Callable[[str], str] = validate_outbound_url,
    transport: httpx.BaseTransport | None = None,
    timeout: float = 30.0,
) -> Path:
    """单文件直链下载：逐跳校验后流式写 ``.part``，有残留则 Range 续传。

    - 206：从 ``.part`` 现有大小续写；
    - 200（服务端忽略 Range / ``Accept-Ranges: none``）：降级全量重下，
      记 ``resume_downgraded`` 审计行（只带 task_id）。

    HLS/DASH 不走本函数（交给 yt-dlp）。
    """
    validator(media_url)
    final_url = resolve_redirects(media_url, transport=transport, timeout=timeout)
    validator(final_url)
    out_dir = prepare_output_dir(output_dir)
    name = validate_output_filename(filename or Path(urlsplit(final_url).path).name)
    output = out_dir / name
    part = output.with_suffix(output.suffix + ".part")
    resume_from = part.stat().st_size if part.is_file() else 0
    headers = {"Range": f"bytes={resume_from}-"} if resume_from else {}

    cookie_header: str | None = None
    if cookie_policy == "explicit":
        cookie_header = cookies_service.header_from_env(final_url)
        if cookie_header:
            logger.info("cookie_file_loaded task_id=%s", task_id)

    cookie_retry_done = False
    with httpx.Client(
        transport=transport, follow_redirects=False, timeout=timeout, trust_env=False
    ) as client:
        while True:
            request_headers = dict(headers)
            if cookie_header:
                request_headers["Cookie"] = cookie_header
            with client.stream("GET", final_url, headers=request_headers) as response:
                if (
                    response.status_code in {401, 403}
                    and cookie_policy == "lazy"
                    and not cookie_retry_done
                ):
                    cookie_retry_done = True
                    candidate = cookies_service.header_from_env(final_url)
                    if candidate:
                        logger.info("cookie_lazy_retry task_id=%s", task_id)
                        cookie_header = candidate
                        continue
                if response.status_code not in (200, 206):
                    raise DownloadIntegrityError(f"HTTP {response.status_code}")
                append = response.status_code == 206 and resume_from > 0
                if resume_from > 0 and response.status_code == 200:
                    logger.info("resume_downgraded task_id=%s", task_id)
                if response.status_code == 206:
                    total = _content_range_total(response.headers.get("content-range"))
                    length = _content_length(response)
                    if total is None and length is not None:
                        total = resume_from + length if append else length
                else:
                    total = _content_length(response)
                with part.open("ab" if append else "wb") as handle:
                    for chunk in response.iter_bytes():
                        handle.write(chunk)
                break

    if not part.is_file() or part.stat().st_size <= 0:
        raise DownloadIntegrityError(f"下载产物缺失：{part.name}")
    if total is not None and part.stat().st_size != total:
        raise DownloadIntegrityError(
            f"下载不完整：{part.stat().st_size}/{total}"
        )
    part.replace(output)
    logger.info("direct_download_complete task_id=%s bytes=%d", task_id, output.stat().st_size)
    return output


def download_media(
    media_url: str,
    output_dir: str | Path,
    *,
    task_id: str = "unknown",
    format_expression: str = "best",
    cookie_policy: str = "none",
    validator: Callable[[str], str] = validate_outbound_url,
    ydl_factory: Callable[[dict[str, Any]], Any] | None = None,
) -> Path:
    """下载到 ``output_dir``，返回产物路径（已通过完整性校验）。

    ``format_expression`` 由结构化 formatSelector 映射而来（best /
    bestaudio / best[height<=N]），不接受调用方直接传 shell 字符串。

    ``lazy`` 仅在下载被 401/403 拒绝时使用临时 Cookie 副本重试一次；
    原 Cookie 文件只读，yt-dlp 的写回只落在副本上。
    """
    validator(media_url)
    out_dir = prepare_output_dir(output_dir)

    factory = ydl_factory
    if factory is None:
        import yt_dlp

        factory = yt_dlp.YoutubeDL

    def _options(cookiefile: Path | None) -> dict[str, Any]:
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
        if cookiefile is not None:
            options["cookiefile"] = str(cookiefile)
        return options

    def _attempt(options: dict[str, Any]) -> tuple[dict[str, Any], Path]:
        with factory(options) as ydl:
            probe = ydl.extract_info(media_url, download=False)
            rejected = validate_download_urls(probe, validator)
            if rejected:
                raise SecurityError("SSRF_BLOCKED", {"host": rejected[0]["host"]})
            info = ydl.extract_info(media_url, download=True)
            return info, Path(ydl.prepare_filename(info))

    with ExitStack() as stack:
        initial_cookie = None
        if cookie_policy == "explicit":
            initial_cookie = stack.enter_context(cookies_service.cookie_file_copy(task_id))
            if initial_cookie is not None:
                logger.info("cookie_file_loaded task_id=%s", task_id)
        try:
            info, target = _attempt(_options(initial_cookie))
        except Exception as exc:  # noqa: BLE001 —— 只对 401/403 做一次惰性重试
            if (
                cookie_policy != "lazy"
                or initial_cookie is not None
                or not _looks_like_rejection(exc)
            ):
                raise
            retry_cookie = stack.enter_context(cookies_service.cookie_file_copy(task_id))
            if retry_cookie is None:
                raise
            logger.info("cookie_lazy_retry task_id=%s", task_id)
            info, target = _attempt(_options(retry_cookie))

    # 下载后再校验一次（防御 yt-dlp 内部派生 / 拼接出的 URL）
    rejected_after = validate_download_urls(info, validator)
    if rejected_after:
        target.unlink(missing_ok=True)
        raise SecurityError("SSRF_BLOCKED", {"host": rejected_after[0]["host"]})

    validate_output_target(out_dir, target)
    try:
        validate_output_filename(target.name)
    except SecurityError:
        try:
            target.unlink(missing_ok=True)
        except OSError:
            pass
        raise

    if not target.is_file() or target.stat().st_size <= 0:
        raise DownloadIntegrityError(f"产物不存在或为空：{target.name}")
    if _ffprobe_duration(target) <= 0:
        raise DownloadIntegrityError(f"ffprobe 无法解析产物：{target.name}")
    logger.info("download_complete task_id=%s bytes=%d", task_id, target.stat().st_size)
    return target

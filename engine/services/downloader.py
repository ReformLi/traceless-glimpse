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


class DownloadCancelled(Exception):
    """协作式断流：下载循环在写下一块之前发现取消标志，主动停手。

    抛错前已写入的字节会随 ``with`` 关闭落盘（直链是 ``.part``，yt-dlp 是它自己的
    临时文件），因此取消不留「假装完成」的产物，也给 4b-4 的续传留下素材。
    """


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


# 响应 Content-Type -> 白名单内扩展名（只列我们能命名的类型，认不出就不改）
_SUFFIX_BY_MIME = {
    "audio/mp4": ".m4a",
    "audio/x-m4a": ".m4a",
    "audio/aac": ".aac",
    "audio/mpeg": ".mp3",
    "audio/opus": ".opus",
    "video/mp4": ".mp4",
    "video/x-matroska": ".mkv",
    "video/webm": ".webm",
    "video/quicktime": ".mov",
}
_AUDIO_SUFFIXES = {".m4a", ".aac", ".mp3", ".opus"}

# 续传放行清单：视频/音频前缀之外，只有这几种「说不清但确实是二进制载荷」的类型。
# 语义是白名单而不是黑名单——错误页、JSON、XML 一律落在名单外。
_OPAQUE_MEDIA_TYPES = {
    "application/octet-stream",
    "application/mp4",
    "application/mpegurl",
    "application/x-mpegurl",
    "application/dash+xml",
}


def _is_media_content_type(content_type: str | None) -> bool:
    """判断 Content-Type 是否可能是媒体载荷（续传门禁用）。

    ``None`` 放行：没有声明就没有「这是错误页」的证据，不该因此打断正常下载。
    """
    if not content_type:
        return True
    main = content_type.split(";", 1)[0].strip().lower()
    return main.startswith(("video/", "audio/")) or main in _OPAQUE_MEDIA_TYPES


def _bare_media_type(content_type: str | None) -> str:
    """审计行用的裸媒体类型：去掉 ``; codecs=...`` 之类的参数，避免日志变长尾。"""
    if not content_type:
        return "none"
    return content_type.split(";", 1)[0].strip().lower() or "none"


def _stray_part_names(output_dir: Path, *, expected_part: str) -> list[str]:
    """同一起名、不同封装后缀的 ``.part`` 残留（排除期待命中的那个）。

    4b-4 裁决②：``.part`` 名跟着产物名走，URL 后缀或封装校正一变就命不中残留，
    此时行为是**静默全新下载**。这没错，但现场必须留痕，否则「续传怎么没生效」无从查起。
    只认同一起名（``audio_as_mp4.mp4.part`` vs ``audio_as_mp4.m4a.part``）：输出目录
    是多任务共用的，别的起名的残留属于别的任务，报出来只会误导排查。
    """
    wanted = expected_part.split(".", 1)[0]
    return sorted(
        item.name
        for item in output_dir.glob("*.part")
        if item.name != expected_part and item.name.split(".", 1)[0] == wanted
    )


def _mime_suffix(content_type: str | None) -> str | None:
    if not content_type:
        return None
    return _SUFFIX_BY_MIME.get(content_type.split(";", 1)[0].strip().lower())


def _resolve_output_name(url_name: str, content_type: str | None, *, task_id: str) -> str:
    """产物封装名：纯音频绝不落成视频容器，其余情况一律沿用 URL 后缀。

    嗅探侧的 container 本来就是从 URL 后缀推出来的，拿它当第二信号是循环论证，
    所以这里只认终点响应声明的 Content-Type（由重定向探测顺带取回）。
    """
    if not url_name:
        return url_name  # 无 stem 可用，交给 validate_output_filename 按既有规则拒绝
    suffix = Path(url_name).suffix.lower()
    mime_suffix = _mime_suffix(content_type)
    if mime_suffix in _AUDIO_SUFFIXES and suffix not in _AUDIO_SUFFIXES:
        logger.info(
            "output_ext_corrected task_id=%s from=%s to=%s reason=audio_content_type",
            task_id,
            suffix.lstrip(".") or "none",
            mime_suffix.lstrip("."),
        )
        return str(Path(url_name).with_suffix(mime_suffix))
    return url_name


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
    should_abort: Callable[[], bool] | None = None,
    on_progress: Callable[[int, int | None], None] | None = None,
) -> Path:
    """单文件直链下载：逐跳校验后流式写 ``.part``，有残留则 Range 续传。

    - 续传偏移只取 ``.part`` 的**磁盘实际大小**，不信任何上报进度字节数
      （yt-dlp 侧已实测上报比磁盘少一块 64 KiB，见 smoke-report §11.5-3）；
    - 续传前先看终点 ``Content-Type``：有 ``.part`` 残留却声明成 text/html 这类
      非媒体，说明残留是错误页，直接拒绝续传并记
      ``resume_rejected task_id=... reason=unexpected_content_type``——
      拼上去只会得到一个「长度对但内容坏」的产物，不如失败得明确；
    - 同名不同后缀的残留（``audio_as_mp4.mp4.part`` 对 ``audio_as_mp4.m4a.part``）
      命不中，按全新下载走，但记 ``resume_missed ... reason=part_name_mismatch``
      留痕（4b-4 裁决②：静默可以，无痕不行）；
    - 206：从 ``.part`` 现有大小续写；
    - 200（服务端不支持 Range / 忽略了 Range）：降级全量重下，
      记 ``resume_unsupported task_id=... reason=no_range`` 审计行。
    - 产物扩展名按终点响应的 Content-Type 校正：纯音频不会落成 ``.mp4``。

    ``should_abort`` 每写一块之前查一次（协作式断流）；命中即抛
    :class:`DownloadCancelled`，``.part`` 保留、不改名。
    ``on_progress(已写字节, 总字节或 None)`` 用于把真实进度透给任务对象。

    HLS/DASH 不走本函数（交给 yt-dlp）。
    """
    validator(media_url)
    probe = resolve_redirects(media_url, transport=transport, timeout=timeout, task_id=task_id)
    final_url = probe.url
    validator(final_url)
    out_dir = prepare_output_dir(output_dir)
    name = validate_output_filename(
        _resolve_output_name(
            filename or Path(urlsplit(final_url).path).name,
            probe.content_type,
            task_id=task_id,
        )
    )
    output = out_dir / name
    part = output.with_suffix(output.suffix + ".part")
    resume_from = part.stat().st_size if part.is_file() else 0
    if resume_from and not _is_media_content_type(probe.content_type):
        # 残留 .part 要拼到新字节前面，前提是终点确实还在发媒体。
        # 声明成 text/html 之类的一律视为「错误页被存成了 .part」，宁可不续传。
        logger.info(
            "resume_rejected task_id=%s reason=unexpected_content_type content_type=%s",
            task_id,
            _bare_media_type(probe.content_type),
        )
        raise DownloadIntegrityError(
            f"续传被拒绝：终点 Content-Type 非媒体（{name}，残留 {resume_from} 字节）"
        )
    if resume_from == 0:
        stray = _stray_part_names(out_dir, expected_part=part.name)
        if stray:
            logger.info(
                "resume_missed task_id=%s reason=part_name_mismatch expected=%s found=%s",
                task_id,
                part.name,
                ",".join(stray),
            )
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
                    logger.info("resume_unsupported task_id=%s reason=no_range", task_id)
                if response.status_code == 206:
                    total = _content_range_total(response.headers.get("content-range"))
                    length = _content_length(response)
                    if total is None and length is not None:
                        total = resume_from + length if append else length
                else:
                    total = _content_length(response)
                base_bytes = resume_from if append else 0
                written = 0
                with part.open("ab" if append else "wb") as handle:
                    for chunk in response.iter_bytes():
                        if should_abort is not None and should_abort():
                            raise DownloadCancelled(
                                f"task_id={task_id} aborted after {base_bytes + written} bytes"
                            )
                        handle.write(chunk)
                        written += len(chunk)
                        if on_progress is not None:
                            on_progress(base_bytes + written, total)
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
    should_abort: Callable[[], bool] | None = None,
    on_progress: Callable[[int, int | None], None] | None = None,
) -> Path:
    """下载到 ``output_dir``，返回产物路径（已通过完整性校验）。

    ``format_expression`` 由结构化 formatSelector 映射而来（best /
    bestaudio / best[height<=N]），不接受调用方直接传 shell 字符串。

    ``lazy`` 仅在下载被 401/403 拒绝时使用临时 Cookie 副本重试一次；
    原 Cookie 文件只读，yt-dlp 的写回只落在副本上。

    **中断现场归 yt-dlp 管**：取消/失败后输出目录里会留下它自己的
    ``X.part`` / ``X.part-FragN.part`` / ``X.ytdl``（后者只存分片序号等续传状态）。
    引擎不读、不改、也不代它清理 —— 续传由 yt-dlp 按 ``.ytdl`` 自行接上，
    引擎侧的 ``.part`` 续传只服务 :func:`download_direct`；
    将来若要清理这些残留，只在 failed 终态按清理策略做，不在下载中途动它们。
    （详见 STUB_VS_REAL.md 与 smoke-report §11.5-4。）
    """
    validator(media_url)
    out_dir = prepare_output_dir(output_dir)

    factory = ydl_factory
    if factory is None:
        import yt_dlp

        factory = yt_dlp.YoutubeDL

    # 协作式断流：yt-dlp 在每块/每片进度回调里查取消标志（`_hook_progress` 不吞异常）。
    # 外层用 abort_signalled 判定，不依赖 yt-dlp 把 hook 异常包装成什么类型。
    abort_signalled = False

    def _progress_hook(d: dict[str, Any]) -> None:
        nonlocal abort_signalled
        if should_abort is not None and should_abort():
            abort_signalled = True
            from yt_dlp.utils import DownloadCancelled as _YtdlpCancelled

            raise _YtdlpCancelled(f"task_id={task_id} cancelled")
        if on_progress is not None and d.get("status") in {"downloading", "finished"}:
            total = d.get("total_bytes") or d.get("total_bytes_estimate")
            on_progress(int(d.get("downloaded_bytes") or 0), int(total) if total else None)

    def _options(cookiefile: Path | None) -> dict[str, Any]:
        options: dict[str, Any] = {
            "quiet": True,
            "no_warnings": True,
            "noplaylist": True,
            "outtmpl": str(out_dir / "%(title)s.%(ext)s"),
            "format": format_expression,
            "socket_timeout": 30,
        }
        if should_abort is not None or on_progress is not None:
            options["progress_hooks"] = [_progress_hook]
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
            if abort_signalled:
                raise DownloadCancelled(
                    f"task_id={task_id} aborted during yt-dlp download"
                ) from exc
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

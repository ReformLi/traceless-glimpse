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
from typing import Any, Callable, Iterable
from urllib.parse import urlsplit

import httpx

from security import (
    ALLOWED_OUTPUT_EXTENSIONS,
    SecurityError,
    prepare_output_dir,
    validate_outbound_url,
    validate_output_filename,
    validate_output_target,
)
from services import cookies as cookies_service
from services import ffmpeg_path as ffmpeg_paths
from services import proxy as proxy_settings
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
    # M16.5：PATH 里没有 FFmpeg 时，从 BGV_FFMPEG_PATH 的同目录推导 ffprobe，
    # 否则分轨下载会在「合并之前」就被产物完整性校验拦下。
    try:
        ffprobe = ffmpeg_paths.ffprobe_executable(which=shutil.which)
    except ffmpeg_paths.FFmpegNotFoundError as exc:
        raise DownloadIntegrityError(str(exc)) from exc
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


def _estimate_total_from_fragments(hook: dict[str, Any], downloaded: int) -> int | None:
    """HLS 分片下载 ``total_bytes`` / ``total_bytes_estimate`` 双缺时的兜底估算。

    yt-dlp 的分片进度钩子带 ``fragment_count``（总分片数）与 ``fragment_index``
    （当前分片，1 起数），但两者都齐也不代表有总字节数。按「分片大小均匀」假设
    外推：``total ≈ 已下载字节 × 分片总数 / 已完成分片``——起步误差最大、随分片
    推进收敛；返回值只喂 percent / eta，UI 端对小幅抖动有钳制，不值得为此保证单调。
    任一字段缺失或 downloaded 为 0（首个分片还没落地）时放弃估算，返回 None，
    让 percent 维持 0 由客户端按不确定进度显示。
    """
    if downloaded <= 0:
        return None
    try:
        frag_count = int(hook.get("fragment_count") or 0)
        frag_index = int(hook.get("fragment_index") or 0)
    except (TypeError, ValueError):
        return None
    if frag_count <= 0 or frag_index <= 0:
        return None
    return int(downloaded * frag_count / frag_index)


def _is_hls_url(url: str) -> bool:
    """只认路径以 ``.m3u8`` 结尾的；查询串里带 m3u8 模板的（如 ``?url=``）不算。"""
    try:
        return urlsplit(url).path.lower().endswith(".m3u8")
    except ValueError:
        return False


def _count_hls_segments(playlist: str) -> int:
    """数清单里的 ``#EXTINF`` 行 = 分片总数。

    master playlist 只列子清单、没有 ``#EXTINF``，返回 0——调用方据此放弃，
    而不是拿 1 或某个猜出来的数去糊进度条。
    """
    return sum(1 for line in playlist.splitlines() if line.lstrip().startswith("#EXTINF"))


def _might_be_manifest_url(url: str) -> bool:
    """可能是清单：显式 ``.m3u8``/``.mpd``，或路径末段没有扩展名。

    后者是 CDN 的常态——真实嗅探到的 HLS 地址常常是
    ``https://cdn/.../b3a240e2e4014f3eb0d8fe90d85607ee`` 这种裸 token 路径，
    只看后缀会**漏掉全部加密 HLS 源**。探针本身按流式只读前 64KB，
    对普通直链最多多打一次 HEAD 级别的请求，不会把整个文件拖下来。
    """
    try:
        path = urlsplit(url).path
    except ValueError:
        return False
    lowered = path.lower()
    if lowered.endswith((".m3u8", ".mpd")):
        return True
    return "." not in lowered.rsplit("/", 1)[-1]


def _is_aes128_playlist(playlist: str) -> bool:
    """清单是否用 AES-128 加密（``#EXT-X-KEY:METHOD=AES-128``）。"""
    return any(
        line.lstrip().upper().startswith("#EXT-X-KEY:") and "METHOD=AES-128" in line.upper()
        for line in playlist.splitlines()
    )


def _cryptodome_available() -> bool:
    """原生 AES 解密依赖在不在位。

    这是 M22 花了一整轮才钉死的坑：缺 ``pycryptodomex`` 时 yt-dlp 的 HlsFD 会把
    **整个下载委托给 ffmpeg**，而 ffmpeg 路径既不再回调 ``progress_hooks``
    （进度恒 0，客户端只看到 0% → 已完成），也不再检查取消标志
    （用户点取消，下载照跑到底）。装回这个库，原生分片下载器接管，
    两条能力一起回来。
    """
    try:
        import Cryptodome  # noqa: F401
    except ImportError:
        return False
    return True


_MANIFEST_HEAD_BYTES = 64 * 1024


def _read_manifest_head(response: httpx.Response, limit: int = _MANIFEST_HEAD_BYTES) -> str:
    """流式只读前 ``limit`` 字节。

    探针现在也会被「无后缀的裸 token 地址」触发，而那些地址有可能就是直链媒体本身——
    一次性 ``response.text`` 会把整个上百 MB 的视频读进内存。清单远小于 64KB，
    足够判断 ``#EXTM3U`` 与数清分片数。
    """
    chunks: list[bytes] = []
    size = 0
    for chunk in response.iter_bytes():
        chunks.append(chunk)
        size += len(chunk)
        if size >= limit:
            break
    return b"".join(chunks)[:limit].decode("utf-8", "ignore")


def _probe_hls_fragment_count(
    url: str,
    *,
    validator: Callable[[str], str],
    task_id: str = "unknown",
    timeout: float = 5.0,
) -> int | None:
    """拉一次 playlist 数 ``#EXTINF`` 行，作为 yt-dlp 不给 ``fragment_count`` 时的兜底。

    HLS 分片流没有总字节，百分比只能靠「已完成片数 / 总片数」算。yt-dlp 的原生分片
    下载器通常会带 ``fragment_count``，但并非所有清单都带（尤其是部分站点自定义的
    非标准清单），拿不到时进度就会整段停在 0——用户看到的是「0% 然后突然已完成」。

    master playlist（只列子清单、没有 ``#EXTINF``）与非 200 响应一律返回 None：
    猜一个数只会让进度条比不动更糟。请求失败不影响下载本身，最坏情况是退回原来的行为。

    **不跟随重定向**：逐跳校验只在 :mod:`services.redirect` 里做，这里放开了就等于
    给一个不校验的出站口。多数 CDN 清单直连即 200；真被 302 挡下就放弃兜底。
    """
    validator(url)
    parsed = urlsplit(url)
    headers = {"Accept": "application/vnd.apple.mpegurl, */*"}
    if parsed.scheme and parsed.netloc:
        # 部分站点按 Referer 放行清单；顺手给一个同源 Referer，不额外泄露来源页面。
        headers["Referer"] = f"{parsed.scheme}://{parsed.netloc}/"
    try:
        with httpx.Client(
            follow_redirects=False,
            timeout=timeout,
            trust_env=False,
            # M21：与下载同一代理口径；清单抓取与分片下载走同一条出站路径。
            **proxy_settings.httpx_proxy_kwargs(),
        ) as client:
            with client.stream("GET", url, headers=headers) as response:
                if response.status_code != 200:
                    logger.info(
                        "hls_fragment_probe_failed task_id=%s reason=http_%d",
                        task_id,
                        response.status_code,
                    )
                    return None
                head = _read_manifest_head(response)
    except (httpx.HTTPError, ValueError) as exc:
        logger.info(
            "hls_fragment_probe_failed task_id=%s reason=%s", task_id, type(exc).__name__
        )
        return None
    if not head.lstrip().startswith("#EXTM3U"):
        # 裸 token 地址也可能是直链媒体：不是清单就干脆不探，别拿分片数去糊进度。
        logger.info("hls_fragment_probe_skip task_id=%s reason=not_manifest", task_id)
        return None
    if _is_aes128_playlist(head) and not _cryptodome_available():
        # 这条日志是「进度条不动 / 取消没反应」的唯一现场证据，别删。
        logger.warning(
            "hls_aes_ffmpeg_fallback task_id=%s reason=pycryptodomex_missing "
            "impact=no_progress,no_cancel hint=install_pycryptodomex",
            task_id,
        )
    count = _count_hls_segments(head)
    if count <= 0:
        logger.info("hls_fragment_probe_master task_id=%s reason=no_extinf", task_id)
        return None
    logger.info("hls_fragment_probe_ok task_id=%s fragments=%d", task_id, count)
    return count


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


# 产物主名的长度上限：客户端也拦 200（FormatSelection.OUTPUT_NAME_MAX_LENGTH），
# 这里只做同口径兜底，防一条超长名字把 Windows 的整条路径顶爆。
OUTPUT_NAME_MAX_LENGTH = 200

# Windows 上不允许出现在文件名里的字符。正常路径上客户端已经拦下，这里换成
# ``_`` 只是为了「一个冒号不该把整条任务打成 failed」——同时留审计行，不静默。
_ILLEGAL_NAME_CHARS = frozenset('<>:"/\\|?*')


def normalize_output_name(raw: str | None, *, task_id: str = "unknown") -> str | None:
    """把客户端发来的「产物主名」规整成可直接当文件名用的字符串。

    协议里的 ``namingTemplate`` 从 M24 起语义是**产物主名**，不是模板：

    - 空 / 全空白 → ``None``（沿用站点标题，与协议里的 null 同义）；
    - 尾点 / 尾空格剥掉（Windows 会静默丢弃，不剥会让 ``outputPath`` 与真实文件对不上）；
    - 已知媒体后缀剥掉（``我的电影.mp4`` → ``我的电影``）——后缀由系统按实际容器补，
      不剥会得到 ``我的电影.mp4.mp4``；只剥白名单里的后缀，``第 1.5 集`` 不能被腰斩；
    - 其余非法字符替换成 ``_`` 并记审计行。

    返回的主名不含后缀，调用方负责拼 ``%(ext)s`` 或推断出的容器后缀。
    """
    if raw is None:
        return None
    name = raw.strip().strip(" .")
    if not name:
        return None

    suffix = Path(name).suffix.lower()
    if suffix in ALLOWED_OUTPUT_EXTENSIONS:
        name = name[: -len(suffix)].rstrip(" .")
        logger.info("output_name_ext_stripped task_id=%s suffix=%s", task_id, suffix)
        if not name:
            return None

    sanitized = "".join("_" if ch in _ILLEGAL_NAME_CHARS or ord(ch) < 0x20 else ch for ch in name)
    if sanitized != name:
        logger.info("output_name_sanitized task_id=%s", task_id)
        name = sanitized.strip()
        if not name:
            return None

    if len(name) > OUTPUT_NAME_MAX_LENGTH:
        logger.info("output_name_truncated task_id=%s length=%d", task_id, len(name))
        name = name[:OUTPUT_NAME_MAX_LENGTH].rstrip(" .")
    return name or None


def _outtmpl_stem(output_name: str | None) -> str:
    """产物主名 → yt-dlp ``outtmpl`` 的标题段。

    没给就回到 ``%(title)s``（与加这个参数之前完全一致）；给了就当**字面量**，
    并把 ``%`` 转义成 ``%%``——否则 yt-dlp 会把 ``100%纯音乐`` 当模板变量展开。
    """
    if not output_name:
        return "%(title)s"
    return output_name.replace("%", "%%")


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
    output_name: str | None = None,
    default_suffix: str = ".mp4",
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

    ``output_name`` 是用户指定的产物主名（不含后缀）：给了就拿它当 stem，后缀取自
    终点 URL，URL 没有媒体后缀时用 ``default_suffix``（纯音频调用方传 ``.m4a``），
    最后仍由 ``_resolve_output_name`` 按 Content-Type 校正。

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
    url_name = Path(urlsplit(final_url).path).name
    if output_name:
        suffix = Path(url_name).suffix.lower()
        if suffix not in ALLOWED_OUTPUT_EXTENSIONS:
            suffix = default_suffix
        url_name = f"{output_name}{suffix}"
    name = validate_output_filename(
        _resolve_output_name(
            filename or url_name,
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
        transport=transport,
        follow_redirects=False,
        timeout=timeout,
        trust_env=False,
        # M21：直链下载与嗅探/清单抓取同一代理口径；信用环境变量（trust_env）仍关，
        # 只认显式 BGV_PROXY——系统代理不该在后台悄悄改变落盘路径。
        **proxy_settings.httpx_proxy_kwargs(transport=transport),
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
    filename_tag: str | None = None,
    output_name: str | None = None,
) -> Path:
    """下载到 ``output_dir``，返回产物路径（已通过完整性校验）。

    ``format_expression`` 由结构化 formatSelector 映射而来（best /
    bestaudio / best[height<=N]），不接受调用方直接传 shell 字符串。

    ``output_name`` 是用户指定的产物主名（不含后缀）：给了就把 ``outtmpl`` 的
    ``%(title)s`` 换成它，扩展名仍由 yt-dlp 按真实容器补（``%(ext)s``）；
    没给就沿用站点标题。合并场景由 ``filename_tag`` 再加轨标签区分两条轨。

    ``lazy`` 仅在下载被 401/403 拒绝时使用临时 Cookie 副本重试一次；
    原 Cookie 文件只读，yt-dlp 的写回只落在副本上。

    **中断现场归 yt-dlp 管**：取消/失败后输出目录里会留下它自己的
    ``X.part`` / ``X.part-FragN.part`` / ``X.ytdl``（后者只存分片序号等续传状态）。
    引擎不读、不改、也不代它清理 —— 续传由 yt-dlp 按 ``.ytdl`` 自行接上，
    引擎侧的 ``.part`` 续传只服务 :func:`download_direct`；
    将来若要清理这些残留，只在 failed 终态按清理策略做，不在下载中途动它们。
    （详见 STUB_VS_REAL.md 与 smoke-report §11.5-4。）

    ``filename_tag`` 只服务 :func:`download_tracks`：两次下载共用同一个 ``%(title)s``，
    不加标签时第二条轨会**覆盖**第一条（容器相同就同名）。标签插在标题与扩展名之间
    （``Title.bgv-video.mp4``）；产物名仍过 ``validate_output_filename``——白名单看的是
    扩展名，中间多一段不参与判定。单文件下载不传标签。
    """
    validator(media_url)
    out_dir = prepare_output_dir(output_dir)

    # 分片总数兜底：只在可能是清单时探一次，普通带后缀的直链不多打这一枪。
    # 探不到（master 清单 / 站点不通 / 不是清单）就保持 None，行为与本改动前一致。
    hls_fragments = (
        _probe_hls_fragment_count(media_url, validator=validator, task_id=task_id)
        if _might_be_manifest_url(media_url)
        else None
    )

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
            downloaded = int(d.get("downloaded_bytes") or 0)
            total = d.get("total_bytes") or d.get("total_bytes_estimate")
            if total is None:
                # HLS 分片流大概率没有总字节数，用分片占比外推一个估算值；
                # 清单没带 fragment_count 时用下载前探到的分片数补上。
                hint = d
                if not d.get("fragment_count") and hls_fragments:
                    hint = {**d, "fragment_count": hls_fragments}
                total = _estimate_total_from_fragments(hint, downloaded)
            on_progress(downloaded, int(total) if total else None)

    def _options(cookiefile: Path | None) -> dict[str, Any]:
        stem = _outtmpl_stem(output_name)
        template = (
            f"{stem}.{filename_tag}.%(ext)s" if filename_tag else f"{stem}.%(ext)s"
        )
        options: dict[str, Any] = {
            "quiet": True,
            "no_warnings": True,
            "noplaylist": True,
            "outtmpl": str(out_dir / template),
            "format": format_expression,
            "socket_timeout": 30,
        }
        if should_abort is not None or on_progress is not None:
            options["progress_hooks"] = [_progress_hook]
        # yt-dlp 的 HLS/DASH 原生下载器也会自己调 ffmpeg 转封装；自定义路径必须
        # 一并给它，否则「PATH 里没有 FFmpeg」时分轨下载会先于合并失败。
        try:
            ffmpeg = ffmpeg_paths.ffmpeg_executable(which=shutil.which)
        except ffmpeg_paths.FFmpegNotFoundError as exc:
            logger.warning("ffmpeg_path_invalid reason=%s", exc)
            ffmpeg = None
        if ffmpeg:
            options["ffmpeg_location"] = ffmpeg
        if cookiefile is not None:
            options["cookiefile"] = str(cookiefile)
        # M21：与嗅探 / 清单抓取同一代理口径；未配置就不写这个键，直连行为不变。
        configured_proxy = proxy_settings.proxy_url()
        if configured_proxy:
            options["proxy"] = configured_proxy
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


# 分离轨中间文件的命名标签：合并完成后由 :func:`drop_track_files` 回收。
TRACK_TAG_VIDEO = "bgv-video"
TRACK_TAG_AUDIO = "bgv-audio"
# 直链纯音频：先落整条直链（含画面），ffmpeg 剥轨成功后必须连 .part 一起回收。
AUDIO_SOURCE_TAG = "bgv-audio-source"


def audio_source_filename(task_id: str) -> str:
    """带 taskId 的中间文件名：唯一、可识别、绝不与用户已有产物撞名。"""
    return validate_output_filename(f"bgv-{task_id}.{AUDIO_SOURCE_TAG}.mp4")


def drop_audio_source_leftovers(
    out_dir: Path, task_id: str, source: Path | None = None
) -> None:
    """回收直链纯音频那一份完整 MP4 及其 ``.part``（成功/失败/取消都要调）。

    只认 ``bgv-<taskId>.bgv-audio-source*`` 这一族名字：输出目录里属于
    别的任务或用户自己的文件一律不碰。按 glob 扫而不是只记返回路径，
    是因为 Content-Type 可能把中间文件后缀从 .mp4 校正成 .m4a。
    """
    marker = f"bgv-{task_id}.{AUDIO_SOURCE_TAG}"
    candidates = set(out_dir.glob(f"{marker}*"))
    if source is not None:
        candidates.add(source)
        candidates.add(source.with_suffix(source.suffix + ".part"))
    for path in candidates:
        try:
            if path.is_file():
                path.unlink()
                logger.info("audio_source_dropped name=%s", path.name)
        except OSError as exc:
            logger.warning(
                "audio_source_cleanup_failed name=%s type=%s", path.name, type(exc).__name__
            )


def download_tracks(
    media_url: str,
    output_dir: str | Path,
    *,
    video_expression: str,
    audio_expression: str,
    task_id: str = "unknown",
    cookie_policy: str = "none",
    validator: Callable[[str], str] = validate_outbound_url,
    ydl_factory: Callable[[dict[str, Any]], Any] | None = None,
    should_abort: Callable[[], bool] | None = None,
    on_progress: Callable[[int, int | None], None] | None = None,
    output_name: str | None = None,
) -> tuple[Path, Path]:
    """分两次下载「仅视频轨 + 仅音频轨」，返回 ``(video, audio)``，**不合并**。

    为什么不让 yt-dlp 一次下完：``-f best`` 遇到只有分离轨的源会由它内置
    ``FFmpegMerger`` 自己合，产物确实是单文件，但那次 FFmpeg 调用对引擎不可见——
    协议 §3.7 的 ``merge`` 动作与 §4 的 ``downloading → merging`` 转移因此永远没有落点
    （M4 第 1 步审计的 P0）。拆成两个单格式下载后，每一次都只有一条轨、yt-dlp 无可合，
    合并这一步才真的归 :mod:`services.merger` 执行。

    先下音频轨（通常小得多），视频轨失败时两条一起回收——中间轨名由音频轨唯一确定
    （同一主名换标签），所以删得准，不会碰别人的文件。

    ``output_name``（用户指定的产物主名，不含后缀）会同时喂给两条轨：两条轨共用
    同一个主名，靠 ``filename_tag`` 区分，合并后 :func:`merged_target_for` 摘掉标签
    就得到 ``<主名>.<ext>``。

    ``on_progress`` 上报的是两轨**合计**字节：视频段在已落盘的音频字节上加偏移；
    视频段总字节探不到时报 ``None``（不拿音频那一段冒充全局进度）。
    """
    state = {"offset": 0}

    def _on_video_bytes(downloaded: int, total: int | None) -> None:
        base = state["offset"]
        on_progress(base + downloaded, base + total if total else None)

    audio = download_media(
        media_url,
        output_dir,
        task_id=task_id,
        format_expression=audio_expression,
        cookie_policy=cookie_policy,
        validator=validator,
        ydl_factory=ydl_factory,
        should_abort=should_abort,
        on_progress=on_progress,
        filename_tag=TRACK_TAG_AUDIO,
        output_name=output_name,
    )
    state["offset"] = audio.stat().st_size
    try:
        video = download_media(
            media_url,
            output_dir,
            task_id=task_id,
            format_expression=video_expression,
            cookie_policy=cookie_policy,
            validator=validator,
            ydl_factory=ydl_factory,
            should_abort=should_abort,
            on_progress=_on_video_bytes if on_progress is not None else None,
            filename_tag=TRACK_TAG_VIDEO,
            output_name=output_name,
        )
    except Exception:
        partial_video = audio.with_name(
            audio.name.replace(f".{TRACK_TAG_AUDIO}", f".{TRACK_TAG_VIDEO}")
        )
        drop_track_files([audio, partial_video])
        raise
    logger.info("tracks_downloaded task_id=%s bytes=%d", task_id, state["offset"])
    return video, audio


def merged_target_for(video_path: Path) -> Path:
    """合并产物名：去掉视频轨的 ``.bgv-video`` 标签段，回到 ``%(title)s.%(ext)s``。"""
    return video_path.with_name(video_path.name.replace(f".{TRACK_TAG_VIDEO}", "", 1))


def drop_track_files(paths: Iterable[Path]) -> None:
    """回收本模块自己下的分离轨中间文件。

    只删**带轨标签**的名字——不认识的文件绝不动手；用户输出目录里属于别的任务的
    ``.part`` / ``.ytdl`` 残留由 yt-dlp 自己管（见 smoke-report §11.5-4）。
    """
    for path in paths:
        if TRACK_TAG_VIDEO not in path.name and TRACK_TAG_AUDIO not in path.name:
            logger.warning("track_cleanup_skipped reason=unlabeled name=%s", path.name)
            continue
        try:
            if path.is_file():
                path.unlink()
                logger.info("track_file_dropped name=%s", path.name)
        except OSError as exc:  # Windows 上被占用时宁可留残片，也不掩盖合并结果
            logger.warning(
                "track_cleanup_failed name=%s type=%s", path.name, type(exc).__name__
            )

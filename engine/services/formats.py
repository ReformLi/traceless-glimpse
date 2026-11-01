"""格式解析 / DRM 判定 / 下载 URL 二次校验（阶段 3）。

硬点 ①：``extract_info`` 返回后，对 ``requested_downloads``（以及 ``formats``）
里的每个 URL 再过一次 ``validate_outbound_url``，不通过则拒绝该格式。
"""
from __future__ import annotations

import re
from typing import Any, Callable, NamedTuple

import httpx

from models import (
    Codec,
    EstimatedQuality,
    FormatInfo,
    FormatSelector,
    FormatSource,
    Resolution,
    SelectionStrategy,
)
from security import SecurityError, validate_outbound_url
from services.redirect import resolve_redirects

WIDEVINE_KEYFORMAT_UUID = "urn:uuid:edef8ba9-79ec-483b-86c7-1d2b1c8b0a9b"

_KEY_LINE_PREFIX = "#EXT-X-KEY"
_KEYFORMAT_RE = re.compile(r'KEYFORMAT="([^"]+)"')
_DOWNLOAD_URL_KEYS = ("url", "manifest_url", "fragment_base_url")


# ---------------------------------------------------------------------------
# DRM 三态判定（明文 / AES-128 / Widevine 等）
# ---------------------------------------------------------------------------


def detect_drm(manifest_text: str) -> tuple[bool, str | None]:
    """返回 ``(drmDetected, drmSystem)``。

    - 明文 HLS（无 ``EXT-X-KEY``）→ ``(False, None)``；
    - AES-128（``METHOD=AES-128``，无 ``KEYFORMAT``）→ ``(False, None)``（普通加密）；
    - ``METHOD=SAMPLE-AES`` + Widevine/FairPlay/PlayReady 的 ``KEYFORMAT`` → ``(True, system)``；
    - 其他未知 ``KEYFORMAT`` → ``(True, "unknown")``。
    """
    for raw_line in (manifest_text or "").splitlines():
        line = raw_line.strip()
        if not line.upper().startswith(_KEY_LINE_PREFIX):
            continue
        match = _KEYFORMAT_RE.search(line)
        if match is None:
            continue  # AES-128 等无 KEYFORMAT：不是 DRM
        keyformat = match.group(1).lower()
        if "edef8ba9" in keyformat:
            return True, "widevine"
        if "com.apple.streamingkeydelivery" in keyformat:
            return True, "fairplay"
        if "com.microsoft.playready" in keyformat:
            return True, "playready"
        return True, "unknown"
    return False, None


class FormatUnavailableError(Exception):
    """结构化选择器在 M1 无对应 yt-dlp 表达式（exact / 缺 maxHeight 的 max_height）。

    ``reason`` 原样进入 FORMAT_UNAVAILABLE 的 ``detail.reason``。
    """

    def __init__(self, reason: str):
        super().__init__(reason)
        self.reason = reason


def ytdlp_format_expression(selector: FormatSelector | None) -> str:
    """M1 最小映射（显式拒绝，非静默 best）：best / bestaudio / best[height<=N]。

    - ``exact``：M1 不做精确 formatId → ``exact_format_id_unsupported``；
    - ``max_height`` 缺 ``maxHeight``（≤0）：无法映射 → ``max_height_required``，
      不回落 best（协议 §3.4 要求 max_height 必带 maxHeight）。
    """
    if selector is None:
        return "best"
    if selector.strategy == SelectionStrategy.EXACT:
        raise FormatUnavailableError("exact_format_id_unsupported")
    if selector.strategy == SelectionStrategy.AUDIO_ONLY:
        return "bestaudio"
    if selector.strategy == SelectionStrategy.MAX_HEIGHT:
        height = selector.maxHeight or 0
        if height <= 0:
            raise FormatUnavailableError("max_height_required")
        return f"best[height<={height}]"
    return "best"


class FormatNoMatchError(Exception):
    """元数据完整，但没有任何格式满足 ``height<=N``。

    与「元数据不完整」区分开：这里信息足够判定，所以明确失败不猜。
    ``detail`` 直接进 ``FORMAT_UNAVAILABLE.error.detail``。
    """

    def __init__(self, requested: int, available: list[int]):
        super().__init__(f"no format within height<={requested}; available={available}")
        self.requested = requested
        self.available = available

    @property
    def detail(self) -> dict[str, Any]:
        return {"reason": "no_height_within_cap", "requested": self.requested, "available": self.available}


class ResolvedFormat(NamedTuple):
    expression: str
    degraded_reason: str | None = None


def resolve_format_expression(
    selector: FormatSelector | None,
    formats: list[FormatInfo] | None,
) -> ResolvedFormat:
    """把结构化选择器落到具体 yt-dlp 表达式，必要时按格式表降级。

    只处理 ``max_height``：``best[height<=N]`` 在 height 未知时不会报错，而是
    **静默无匹配**（实测：单条 ``height=None`` 变体 → NO MATCH → 下载阶段抛
    ``Requested format is not available``）。因此判定必须发生在调用下载器之前：

    - 有任一候选 height 未知（含格式表缺失）→ 无法判定上限 → 降级 ``best``，
      返回 ``degraded_reason=incomplete_metadata``，由调用方记审计；
    - height 全部已知且都 > N → 判定得出来、结论是没有 → ``FormatNoMatchError``；
    - 其余情况按原表达式下发。
    """
    expression = ytdlp_format_expression(selector)
    if selector is None or selector.strategy != SelectionStrategy.MAX_HEIGHT:
        return ResolvedFormat(expression)
    heights = [item.resolution.height for item in (formats or [])]
    if not heights or any(height <= 0 for height in heights):
        return ResolvedFormat("best", "incomplete_metadata")
    cap = selector.maxHeight or 0
    if min(heights) > cap:
        raise FormatNoMatchError(cap, sorted(set(heights)))
    return ResolvedFormat(expression)


def estimate_quality(info: dict[str, Any]) -> EstimatedQuality:
    """从 yt-dlp info 的格式字典估算画质：height / tbr / format_note。

    取有 height 的格式中最高者；无 height 时回落到顶层字段；仍无则 unknown。
    """
    best: dict[str, Any] | None = None
    for item in info.get("formats") or []:
        if not isinstance(item, dict):
            continue
        height = _int_or_zero(item.get("height"))
        if height <= 0:
            continue
        if best is None or height > _int_or_zero(best.get("height")):
            best = item
    source = best or info
    height = _int_or_zero(source.get("height"))
    bitrate = _int_or_zero(source.get("tbr") or source.get("vbr") or source.get("abr"))
    note = str(source.get("format_note") or "")
    label = note or (f"{height}p" if height else "unknown")
    return EstimatedQuality(height=height, bitrateKbps=bitrate, label=label)


def fetch_manifest_text(
    url: str,
    *,
    transport: httpx.BaseTransport | None = None,
    task_id: str = "unknown",
) -> str:
    """按逐跳校验后的地址取 manifest 文本（首跳同样过 SSRF 校验）。"""
    final_url = resolve_redirects(url, transport=transport, task_id=task_id).url
    with httpx.Client(follow_redirects=False, timeout=5.0, transport=transport) as client:
        response = client.get(final_url)
        try:
            return response.text
        finally:
            response.close()


# ---------------------------------------------------------------------------
# 硬点 ①：下载 URL 二次校验
# ---------------------------------------------------------------------------


def validate_download_urls(
    info: dict[str, Any],
    validator: Callable[[str], str] = validate_outbound_url,
) -> list[dict[str, str]]:
    """对 ``requested_downloads`` 与 ``formats`` 的每个 URL 再过校验。

    返回被拒条目（``key`` + ``host``，不带完整 URL，避免把敏感查询串抛给上层）。
    ``validator`` 可注入，仅用于测试替换校验器；生产默认即统一校验器。
    """
    rejected: list[dict[str, str]] = []
    for section in ("requested_downloads", "formats"):
        for item in info.get(section) or []:
            if not isinstance(item, dict):
                continue
            for key in _DOWNLOAD_URL_KEYS:
                value = item.get(key)
                if not value:
                    continue
                try:
                    validator(str(value))
                except SecurityError as exc:
                    rejected.append(
                        {"key": key, "host": (exc.detail or {}).get("host", "")}
                    )
    return rejected


def extract_info(url: str, *, ydl_factory: Callable[[dict], Any] | None = None) -> dict[str, Any]:
    """yt-dlp 原始解析（``download=False``）。

    **不做出站校验**：调用方必须先在统一校验器放行；本函数只负责解析能力。
    """
    options = {
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "skip_download": True,
        "socket_timeout": 10,
    }
    if ydl_factory is not None:
        with ydl_factory(options) as ydl:
            return ydl.extract_info(url, download=False)
    import yt_dlp

    with yt_dlp.YoutubeDL(options) as ydl:
        return ydl.extract_info(url, download=False)


def _int_or_zero(value: Any) -> int:
    try:
        return int(value or 0)
    except (TypeError, ValueError):
        return 0


def formats_from_info(
    task_id: str,
    info: dict[str, Any],
    validator: Callable[[str], str] = validate_outbound_url,
) -> list[FormatInfo]:
    """把 yt-dlp ``formats`` 映射为协议 FormatInfo；URL 被拒的格式直接丢弃。"""
    result: list[FormatInfo] = []
    for item in info.get("formats") or []:
        url = item.get("url")
        if url:
            try:
                validator(str(url))
            except SecurityError:
                continue  # 硬点 ①：格式被拒
        height = _int_or_zero(item.get("height"))
        width = _int_or_zero(item.get("width") or (height * 16 // 9 if height else 0))
        result.append(
            FormatInfo(
                formatId=str(item.get("format_id") or "unknown"),
                container=str(item.get("ext") or ""),
                resolution=Resolution(width=width, height=height),
                bitrateKbps=_int_or_zero(item.get("tbr") or item.get("vbr") or item.get("abr")),
                codec=Codec(
                    video=str(item.get("vcodec") or "none"),
                    audio=str(item.get("acodec") or "none"),
                ),
                hasAudio=item.get("acodec") not in (None, "none"),
                hasVideo=item.get("vcodec") not in (None, "none"),
                fileSizeEstimateBytes=_int_or_zero(
                    item.get("filesize") or item.get("filesize_approx")
                ),
                source=FormatSource.YT_DLP,
            )
        )
    return result

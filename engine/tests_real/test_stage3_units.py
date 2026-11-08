"""阶段 3 硬点单测：重定向逐跳校验、requested_downloads 二次校验、DRM 三态。"""
from __future__ import annotations

import httpx
import pytest

from models import (
    Codec,
    FormatInfo,
    FormatSelector,
    FormatSource,
    Resolution,
    SelectionStrategy,
)
from security import SecurityError
from services.formats import (
    WIDEVINE_KEYFORMAT_UUID,
    FormatNoMatchError,
    FormatUnavailableError,
    detect_drm,
    estimate_quality,
    formats_from_info,
    resolve_format_expression,
    validate_download_urls,
    ytdlp_format_expression,
)
from services.redirect import resolve_redirects

pytestmark = pytest.mark.real


def _transport(handler) -> httpx.MockTransport:
    return httpx.MockTransport(handler)


# ---------------------------------------------------------------------------
# 硬点 ③：逐跳校验 —— 合法起点 → 第 2 跳私网，必须在该跳被拒
# ---------------------------------------------------------------------------


def test_redirect_second_hop_private_rejected():
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/start":
            return httpx.Response(302, headers={"location": "http://10.0.0.1/next"})
        return httpx.Response(200)

    with pytest.raises(SecurityError) as exc:
        resolve_redirects("http://8.8.8.8/start", transport=_transport(handler))
    assert exc.value.code == "SSRF_BLOCKED"
    assert exc.value.detail == {"host": "10.0.0.1"}


def test_redirect_second_hop_loopback_rejected():
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/start":
            return httpx.Response(302, headers={"location": "/hop2"})
        if request.url.path == "/hop2":
            return httpx.Response(302, headers={"location": "http://127.0.0.1:9/private"})
        return httpx.Response(200)

    with pytest.raises(SecurityError) as exc:
        resolve_redirects("http://8.8.8.8/start", transport=_transport(handler))
    assert exc.value.code == "SSRF_BLOCKED"
    assert exc.value.detail == {"host": "127.0.0.1"}


def test_redirect_legal_chain_resolves():
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/a":
            return httpx.Response(302, headers={"location": "/b"})
        return httpx.Response(200, headers={"content-type": "audio/mp4"})

    target = resolve_redirects("http://8.8.8.8/a", transport=_transport(handler))
    assert target.url == "http://8.8.8.8/b"
    # 探测请求本来就要发，Content-Type 顺带取回给下载器定封装（不再补第二次请求）
    assert target.content_type == "audio/mp4"


def test_redirect_limit_enforced():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(302, headers={"location": request.url.path + "/x"})

    from services.redirect import RedirectLimitError

    with pytest.raises(RedirectLimitError):
        resolve_redirects("http://8.8.8.8/a", transport=_transport(handler), max_hops=3)


# ---------------------------------------------------------------------------
# 硬点 ①：requested_downloads 每个 URL 再过校验
# ---------------------------------------------------------------------------


def test_requested_downloads_private_url_rejected():
    info = {"requested_downloads": [{"url": "http://10.0.0.1/file.mp4"}]}
    rejected = validate_download_urls(info)
    assert rejected == [{"key": "url", "host": "10.0.0.1"}]


def test_requested_downloads_ok_url_passes():
    info = {"requested_downloads": [{"url": "http://8.8.8.8/file.mp4"}]}
    assert validate_download_urls(info) == []


def test_format_with_private_url_is_dropped():
    """被拒 URL 的格式不得进入返回给客户端的格式表。"""
    info = {
        "formats": [
            {"format_id": "ok", "url": "http://8.8.8.8/ok.mp4", "ext": "mp4",
             "height": 720, "vcodec": "h264", "acodec": "aac"},
            {"format_id": "bad", "url": "http://10.0.0.1/bad.mp4", "ext": "mp4",
             "height": 1080, "vcodec": "h264", "acodec": "aac"},
        ]
    }
    assert [item.formatId for item in formats_from_info("task-x", info)] == ["ok"]


# ---------------------------------------------------------------------------
# 硬点 ②：DRM 三态
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("manifest", "expected"),
    [
        ("#EXTM3U\n#EXTINF:3.0,\nseg0.ts\n#EXT-X-ENDLIST\n", (False, None)),
        (
            '#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI="https://keys.example.com/aes.key"\n'
            "#EXTINF:3.0,\nseg0.ts\n",
            (False, None),
        ),
        (
            "#EXTM3U\n"
            '#EXT-X-KEY:METHOD=SAMPLE-AES,URI="https://keys.example.com/drm.key",'
            f'KEYFORMAT="{WIDEVINE_KEYFORMAT_UUID}",KEYFORMATVERSIONS="1"\n'
            "#EXTINF:3.0,\nseg0.ts\n",
            (True, "widevine"),
        ),
    ],
    ids=["plain-hls", "aes128-hls", "widevine-hls"],
)
def test_drm_tristate(manifest: str, expected: tuple[bool, str | None]):
    assert detect_drm(manifest) == expected


# ---------------------------------------------------------------------------
# estimatedQuality：从 yt-dlp format dict 取 height / tbr / format_note
# ---------------------------------------------------------------------------


def test_estimate_quality_from_format_dict():
    info = {
        "formats": [
            {"height": 480, "tbr": 800, "format_note": "480p"},
            {"height": 1080, "tbr": 4500, "format_note": "1080p60"},
            {"height": 720, "tbr": 2000, "format_note": "720p"},
        ]
    }
    quality = estimate_quality(info)
    assert quality.height == 1080
    assert quality.bitrateKbps == 4500
    assert quality.label == "1080p60"


def test_estimate_quality_falls_back_to_unknown():
    quality = estimate_quality({"formats": []})
    assert (quality.height, quality.bitrateKbps, quality.label) == (0, 0, "unknown")


# ---------------------------------------------------------------------------
# formatSelector 最小映射（非静默 best）
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("selector", "expected"),
    [
        (None, "best"),
        (FormatSelector(strategy=SelectionStrategy.BEST), "best"),
        (FormatSelector(strategy=SelectionStrategy.AUDIO_ONLY), "bestaudio[ext=m4a]/bestaudio"),
        (
            FormatSelector(strategy=SelectionStrategy.MAX_HEIGHT, maxHeight=720),
            "best[height<=720]",
        ),
    ],
)
def test_format_selector_minimal_mapping(selector, expected):
    assert ytdlp_format_expression(selector) == expected


def test_exact_format_selector_is_rejected():
    selector = FormatSelector(strategy=SelectionStrategy.EXACT, formatId="137+140")
    with pytest.raises(FormatUnavailableError) as exc:
        ytdlp_format_expression(selector)
    assert exc.value.reason == "exact_format_id_unsupported"


@pytest.mark.parametrize("max_height", [None, 0, -1])
def test_max_height_without_valid_height_is_rejected(max_height):
    """max_height 缺合法 maxHeight → 显式拒绝，不静默回落 best。"""
    selector = FormatSelector(
        strategy=SelectionStrategy.MAX_HEIGHT, maxHeight=max_height
    )
    with pytest.raises(FormatUnavailableError) as exc:
        ytdlp_format_expression(selector)
    assert exc.value.reason == "max_height_required"


# ---------------------------------------------------------------------------
# max_height 降级：元数据不完整（降级 best）与无匹配（明确失败）是两条独立路径
# ---------------------------------------------------------------------------


def _fmt(height: int, format_id: str = "137") -> FormatInfo:
    """一条格式表记录；height=0 是引擎对「画质未知」的序列化值。"""
    return FormatInfo(
        formatId=format_id,
        container="mp4",
        resolution=Resolution(width=1280 if height else 0, height=height),
        bitrateKbps=4500,
        codec=Codec(video="avc1.640028", audio="mp4a.40.2"),
        hasAudio=True,
        hasVideo=True,
        fileSizeEstimateBytes=0,
        source=FormatSource.YT_DLP,
    )


def _max_height(cap: int) -> FormatSelector:
    return FormatSelector(strategy=SelectionStrategy.MAX_HEIGHT, maxHeight=cap)


def test_resolve_max_height_keeps_expression_when_heights_known():
    resolved = resolve_format_expression(_max_height(720), [_fmt(720), _fmt(1080)])
    assert (resolved.expression, resolved.degraded_reason) == ("best[height<=720]", None)


def test_resolve_max_height_incomplete_metadata_degrades_to_best():
    """height 未知时 ``best[height<=N]`` 不报错而是静默无匹配，所以必须提前降级为 best。"""
    resolved = resolve_format_expression(_max_height(720), [_fmt(0, "0")])
    assert (resolved.expression, resolved.degraded_reason) == ("best", "incomplete_metadata")


def test_resolve_mixed_known_and_unknown_heights_degrades():
    """表里既有 720 又有画质未知：未知那条可能本就 ≤ 上限，判不出「一定没有」→ 仍降级 best。"""
    resolved = resolve_format_expression(_max_height(480), [_fmt(720), _fmt(0, "0")])
    assert (resolved.expression, resolved.degraded_reason) == ("best", "incomplete_metadata")


@pytest.mark.parametrize("formats", [None, []])
def test_resolve_max_height_without_table_degrades_to_best(formats):
    resolved = resolve_format_expression(_max_height(720), formats)
    assert (resolved.expression, resolved.degraded_reason) == ("best", "incomplete_metadata")


def test_resolve_max_height_no_match_raises_requested_and_available():
    """判定得出来、结论是没有 → 明确失败并带 requested/available，不猜。"""
    with pytest.raises(FormatNoMatchError) as exc:
        resolve_format_expression(_max_height(480), [_fmt(1080), _fmt(720, "136")])
    assert exc.value.detail == {
        "reason": "no_height_within_cap",
        "requested": 480,
        "available": [720, 1080],
    }


@pytest.mark.parametrize(
    ("selector", "expected"),
    [
        (None, "best"),
        (FormatSelector(strategy=SelectionStrategy.AUDIO_ONLY), "bestaudio[ext=m4a]/bestaudio"),
    ],
)
def test_resolve_leaves_non_max_height_strategies_untouched(selector, expected):
    """best / audio_only 不含 height 上限，格式表再差也不改写表达式、不记降级。"""
    resolved = resolve_format_expression(selector, [_fmt(0, "0")])
    assert (resolved.expression, resolved.degraded_reason) == (expected, None)

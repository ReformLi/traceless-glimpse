"""阶段 3 硬点单测：重定向逐跳校验、requested_downloads 二次校验、DRM 三态。"""
from __future__ import annotations

import httpx
import pytest

from models import FormatSelector, SelectionStrategy
from security import SecurityError
from services.formats import (
    WIDEVINE_KEYFORMAT_UUID,
    FormatUnavailableError,
    detect_drm,
    estimate_quality,
    formats_from_info,
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
        return httpx.Response(200)

    assert resolve_redirects("http://8.8.8.8/a", transport=_transport(handler)) == "http://8.8.8.8/b"


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
        (FormatSelector(strategy=SelectionStrategy.AUDIO_ONLY), "bestaudio"),
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

"""M18 嗅探雷达三层识别的单测（纯逻辑，不需要 playwright）。

覆盖：
- 层 1 URL 后缀：新增 .ts / .m4s / .flv 的归类；
- 层 2 Response Content-Type：video/*、audio/*、m3u8 两个别名、dash+xml、
  octet-stream；text/html / json 不得误判；
- 层 3 URL 关键字：playlist / master / stream 兜底候选，但主文档导航不参与；
- rank 与 _prefer / _record：播放列表 > 分片 > 直链 > 音频 > octet-stream > 关键字，
  同 URL 低可信信号可被高可信信号升级覆盖。
"""
from __future__ import annotations

from services.sniffer import (
    SniffedMedia,
    _classify,
    _classify_content_type,
    _classify_keyword,
    _prefer,
    _rank,
    _record,
)


def _media(url: str, kind: str, container: str) -> SniffedMedia:
    return SniffedMedia(url=url, kind=kind, container=container, headers_hint={})


# ---------------------------------------------------------------------------
# 层 1：URL 后缀
# ---------------------------------------------------------------------------


def test_classify_suffix_extended():
    assert _classify("https://cdn.example.com/v/seg-001.ts") == ("hls", "ts")
    assert _classify("https://cdn.example.com/v/init.m4s") == ("dash", "m4s")
    assert _classify("https://cdn.example.com/live/movie.flv") == ("other", "flv")
    assert _classify("https://cdn.example.com/live/master.m3u8") == ("hls", "m3u8")
    assert _classify("https://cdn.example.com/v/movie.mp4") == ("mp4", "mp4")
    assert _classify("https://cdn.example.com/v/movie.mpd") == ("dash", "mpd")


def test_classify_suffix_case_insensitive_and_query_ignored():
    assert _classify("https://cdn.example.com/V/Movie.MP4?token=1") == ("mp4", "mp4")
    assert _classify("https://cdn.example.com/get?target=x.m3u8") is None, (
        "后缀判定只看路径；query 里的 .m3u8 走关键字层兜底"
    )


# ---------------------------------------------------------------------------
# 层 2：Content-Type
# ---------------------------------------------------------------------------


def test_classify_content_type_media():
    assert _classify_content_type("video/mp4") == ("mp4", "mp4")
    assert _classify_content_type("video/mp2t") == ("mp4", "ts")
    assert _classify_content_type("video/x-flv") == ("mp4", "flv")
    assert _classify_content_type("audio/mp4") == ("audio", "m4a")
    assert _classify_content_type("audio/aac; charset=utf-8") == ("audio", "aac")
    assert _classify_content_type("application/vnd.apple.mpegurl") == ("hls", "m3u8")
    assert _classify_content_type("application/x-mpegurl") == ("hls", "m3u8")
    assert _classify_content_type("audio/mpegurl") == ("hls", "m3u8")
    assert _classify_content_type("audio/x-mpegurl") == ("hls", "m3u8")
    assert _classify_content_type("application/dash+xml") == ("dash", "mpd")
    assert _classify_content_type("application/octet-stream") == ("other", "octet-stream")


def test_classify_content_type_non_media():
    assert _classify_content_type("text/html; charset=utf-8") is None
    assert _classify_content_type("application/json") is None
    assert _classify_content_type("") is None


# ---------------------------------------------------------------------------
# 层 3：URL 关键字
# ---------------------------------------------------------------------------


def test_classify_keyword_fallback():
    assert _classify_keyword("https://api.example.com/get?stream=42", "xhr") == (
        "other",
        "keyword",
    )
    assert _classify_keyword("https://api.example.com/playlist?id=1", "fetch") == (
        "other",
        "keyword",
    )
    # query 里带 .m3u8 / .mpd 的动态分发接口：后缀层看路径看不到，关键字层兜底
    assert _classify_keyword("https://api.example.com/get?target=x.m3u8", "xhr") == (
        "other",
        "keyword",
    )


def test_classify_keyword_ignores_document_navigation():
    assert _classify_keyword("https://site.example.com/stream/watch", "document") is None
    assert _classify_keyword("https://site.example.com/stream/watch", "iframe") is None


# ---------------------------------------------------------------------------
# rank / _prefer / _record
# ---------------------------------------------------------------------------


def test_rank_order_playlist_beats_segment_beats_direct():
    assert _rank("hls", "m3u8") < _rank("hls", "ts")
    assert _rank("hls", "ts") < _rank("mp4", "mp4")
    assert _rank("mp4", "mp4") < _rank("audio", "m4a")
    assert _rank("audio", "m4a") < _rank("other", "octet-stream")
    assert _rank("other", "octet-stream") < _rank("other", "keyword")


def test_prefer_playlist_over_segment_and_keyword():
    candidates = {
        "a": _media("https://x/seg-1.ts", "hls", "ts"),
        "b": _media("https://x/api?stream=1", "other", "keyword"),
        "c": _media("https://x/master.m3u8", "hls", "m3u8"),
    }
    assert _prefer(candidates).url == "https://x/master.m3u8"


def test_prefer_direct_video_over_audio_and_keyword():
    candidates = {
        "a": _media("https://x/api?stream=1", "other", "keyword"),
        "b": _media("https://x/audio.m4a", "audio", "m4a"),
        "c": _media("https://x/movie.mp4", "mp4", "mp4"),
    }
    assert _prefer(candidates).url == "https://x/movie.mp4"


def test_prefer_keyword_only_when_nothing_else():
    candidates = {
        "a": _media("https://x/api?stream=1", "other", "keyword"),
    }
    assert _prefer(candidates).url == "https://x/api?stream=1"
    assert _prefer({}) is None


def test_record_keeps_first_and_upgrades_to_better_signal():
    candidates: dict[str, tuple[SniffedMedia, int]] = {}
    # 请求阶段：无后缀直链先被关键字层兜底登记（低可信）
    _record(candidates, "https://x/get?stream=42", "other", "keyword", {"referer": "r"})
    assert candidates["https://x/get?stream=42"][0].container == "keyword"
    assert candidates["https://x/get?stream=42"][0].headers_hint == {"referer": "r"}
    # 响应阶段：Content-Type 说它是 video/mp2t → 升级覆盖，headers 保留首见
    _record(candidates, "https://x/get?stream=42", "mp4", "ts", {"referer": "r2"})
    upgraded = candidates["https://x/get?stream=42"][0]
    assert upgraded.kind == "mp4" and upgraded.container == "ts"
    assert upgraded.headers_hint == {"referer": "r"}
    # 反向不降级：同 URL 再来一个关键字信号，不得覆盖 ts
    _record(candidates, "https://x/get?stream=42", "other", "keyword", {})
    assert candidates["https://x/get?stream=42"][0].container == "ts"


def test_record_dedupes_equal_rank():
    candidates: dict[str, tuple[SniffedMedia, int]] = {}
    _record(candidates, "https://x/a.ts", "hls", "ts", {"x": "1"})
    _record(candidates, "https://x/a.ts", "hls", "ts", {"x": "2"})
    assert candidates["https://x/a.ts"][0].headers_hint == {"x": "1"}

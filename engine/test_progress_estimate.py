"""M18.5：下载进度在「总大小未知」时也应可见。

背景：HLS 分片下载 yt-dlp 的钩子常缺 ``total_bytes`` / ``total_bytes_estimate``，
进度记录器对 ``total=None`` 只能保持 ``percent=0``，桌面端整场下载显示死 0%。
修复分两层：引擎用 ``fragment_index/fragment_count`` 外推总字节数估算；
桌面端对估算不出（percent=0 但 bytesDownloaded 在涨）的情形显示不确定进度。

本文件只钉引擎侧的分片估算纯函数；桌面端行为由 gui-smoke 钉。
"""
from __future__ import annotations

from services.downloader import _estimate_total_from_fragments


def test_fragment_estimate_uses_ratio_of_count_to_index():
    # 首片落地 64 KiB、共 8 片 → 估算 512 KiB
    hook = {"fragment_count": 8, "fragment_index": 1}
    assert _estimate_total_from_fragments(hook, 65536) == 65536 * 8


def test_fragment_estimate_converges_as_fragments_progress():
    # 已完成 7/10 片共 700 KiB → 估算 1 MiB（1000 KiB）
    hook = {"fragment_count": 10, "fragment_index": 7}
    assert _estimate_total_from_fragments(hook, 700 * 1024) == 100 * 1024 * 10


def test_fragment_estimate_returns_none_without_download_bytes():
    # 首个分片尚未落地：没有任何外推依据，宁可保持未知
    assert _estimate_total_from_fragments({"fragment_count": 8, "fragment_index": 1}, 0) is None


def test_fragment_estimate_returns_none_with_missing_fields():
    # 直链 / DASH 单文件走 total_bytes，钩子里没有分片字段 → 不估算
    assert _estimate_total_from_fragments({}, 1024) is None
    assert _estimate_total_from_fragments({"fragment_count": 8}, 1024) is None
    assert _estimate_total_from_fragments({"fragment_index": 3}, 1024) is None


def test_fragment_estimate_rejects_zero_or_negative_fields():
    assert _estimate_total_from_fragments({"fragment_count": 0, "fragment_index": 1}, 1024) is None
    assert _estimate_total_from_fragments({"fragment_count": 8, "fragment_index": 0}, 1024) is None


def test_fragment_estimate_survives_non_numeric_fields():
    # 字段形状变了（上游 yt-dlp 版本差异）也不能抛异常，按「无法估算」处理
    assert _estimate_total_from_fragments({"fragment_count": "x", "fragment_index": None}, 1024) is None


def test_fragment_estimate_prefers_nothing_when_total_present_elsewhere():
    # 纯函数只做分片外推；total_bytes / total_bytes_estimate 的优先级在钩子里，
    # 这里钉的是「它不是本函数的输入」——防止有人把整个钩子直接塞进来时误判
    hook = {"fragment_count": 4, "fragment_index": 2, "total_bytes": 999999}
    assert _estimate_total_from_fragments(hook, 500) == 500 * 4 // 2

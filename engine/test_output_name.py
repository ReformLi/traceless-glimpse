"""产物主名（M24）：客户端填「视频标题命名」，系统补后缀。

回归动机：协议里的 ``namingTemplate`` 从 M1 起就被引擎**收下、持久化、回显**，
但 ``download_media`` 的 ``outtmpl`` 一直是硬编码的 ``%(title)s.%(ext)s``——
字段存在、界面可填、填了不生效，是典型的静默降级。M24 把它定义成
「产物主名（不含后缀）」，这里守住四件事：

1. 主名进 ``outtmpl`` 是**字面量**，``%`` 必须转义，不能让它当模板变量展开；
2. 后缀一律由系统补：yt-dlp 走 ``%(ext)s``，直链按 URL / Content-Type 推断；
3. 用户手滑带上媒体后缀时剥掉，绝不产出 ``我的电影.mp4.mp4``；
4. 非法字符换 ``_`` 并留审计行——一个冒号不该把整条任务打成 failed。
"""
from __future__ import annotations

from pathlib import Path

import httpx
import pytest

from services import downloader
from services.downloader import (
    download_direct,
    download_media,
    download_tracks,
    merged_target_for,
    normalize_output_name,
)


# ---------------------------------------------------------------------------
# normalize_output_name：空 / 剥后缀 / 非法字符 / 长度
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("raw", [None, "", "   ", "\t\n"])
def test_blank_output_name_means_site_title(raw):
    assert normalize_output_name(raw) is None


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("我的电影", "我的电影"),
        ("  我的电影  ", "我的电影"),
        # 尾点尾空格：Windows 会静默丢弃，留着会让 outputPath 与真实文件对不上
        ("我的电影. ", "我的电影"),
    ],
)
def test_plain_name_passes_through(raw, expected):
    assert normalize_output_name(raw) == expected


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("我的电影.mp4", "我的电影"),
        ("我的电影.MKV", "我的电影"),
        ("讲座.m4a", "讲座"),
        # 非媒体后缀不许腰斩：「第 1.5 集」的点是名字的一部分
        ("第 1.5 集", "第 1.5 集"),
        # 前导点被剥：隐藏文件不该由用户起名
        (".mp4", "mp4"),
    ],
)
def test_known_media_suffix_stripped(raw, expected):
    assert normalize_output_name(raw) == expected


def test_illegal_chars_replaced_not_failed():
    # 正常路径上客户端已经拦下（FormatSelection.outputNameError）；
    # 引擎侧只做兜底，绝不能因为一个冒号把整条任务打成 failed。
    assert normalize_output_name('第1集: 开端') == "第1集_ 开端"
    assert normalize_output_name("a/b") == "a_b"
    assert normalize_output_name('标题<>:"|?*') == "标题_______"


def test_output_name_truncated_to_limit():
    long_name = "长" * (downloader.OUTPUT_NAME_MAX_LENGTH + 40)
    assert len(normalize_output_name(long_name)) == downloader.OUTPUT_NAME_MAX_LENGTH


def test_only_illegal_chars_still_yields_a_name():
    # 不返回 None：用户确实填了东西，换成下划线比退回站点标题更贴近意图
    assert normalize_output_name("::") == "__"


# ---------------------------------------------------------------------------
# outtmpl：主名必须是字面量
# ---------------------------------------------------------------------------


def test_outtmpl_without_name_keeps_site_title():
    assert downloader._outtmpl_stem(None) == "%(title)s"


def test_outtmpl_escapes_percent():
    # 不转义的话 yt-dlp 会把 100% 当模板变量吃掉，产物名变成「100纯音乐」
    assert downloader._outtmpl_stem("100%纯音乐") == "100%%纯音乐"


class _FakeYDL:
    """按 outtmpl 落盘：主名原样、``%(ext)s`` 按轨标签挑扩展名。"""

    def __init__(self, options):
        template = options["outtmpl"]
        if "bgv-video" in template:
            ext = "webm"
        else:
            ext = "m4a"
        self._target = Path(template.replace("%(title)s", "site_title").replace("%(ext)s", ext))

    def __enter__(self):
        return self

    def __exit__(self, *exc_info):
        return False

    def extract_info(self, url, download: bool = False):
        if not download:
            return {
                "formats": [{"url": "http://8.8.8.8/v.mp4"}],
                "requested_downloads": [{"url": "http://8.8.8.8/v.mp4"}],
            }
        self._target.write_bytes(b"MZ")
        return {"requested_downloads": [{"url": "http://8.8.8.8/v.mp4"}]}

    def prepare_filename(self, info):
        return str(self._target)


@pytest.fixture
def no_ffprobe(monkeypatch):
    monkeypatch.setattr(downloader, "_ffprobe_duration", lambda path: 5.0)


def test_media_uses_user_name_and_keeps_real_ext(tmp_path, no_ffprobe):
    path = download_media(
        "http://8.8.8.8/video",
        tmp_path,
        output_name="我的电影",
        ydl_factory=_FakeYDL,
        validator=lambda url: url,
    )
    assert path.name == "我的电影.m4a"


def test_media_without_name_still_uses_site_title(tmp_path, no_ffprobe):
    path = download_media(
        "http://8.8.8.8/video",
        tmp_path,
        ydl_factory=_FakeYDL,
        validator=lambda url: url,
    )
    assert path.name == "site_title.m4a"


def test_tracks_share_stem_and_merge_drops_tag(tmp_path, no_ffprobe):
    video, audio = download_tracks(
        "http://8.8.8.8/video",
        tmp_path,
        video_expression="bestvideo",
        audio_expression="bestaudio",
        output_name="我的电影",
        ydl_factory=_FakeYDL,
        validator=lambda url: url,
    )
    assert video.name == "我的电影.bgv-video.webm"
    assert audio.name == "我的电影.bgv-audio.m4a"
    # 合并产物名回到「主名 + 视频轨扩展名」，中间标签不外泄
    assert merged_target_for(video).name == "我的电影.webm"


# ---------------------------------------------------------------------------
# 直链：后缀由 URL / Content-Type / 调用方兜底
# ---------------------------------------------------------------------------


def _transport(content_type: str | None, body: bytes = b"abc") -> httpx.MockTransport:
    headers = {"content-length": str(len(body))}
    if content_type:
        headers["content-type"] = content_type

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, headers=headers, content=body)

    return httpx.MockTransport(handler)


def test_direct_takes_suffix_from_url(tmp_path):
    path = download_direct(
        "http://8.8.8.8/media/clip.webm",
        tmp_path,
        output_name="我的电影",
        transport=_transport(None),
        validator=lambda url: url,
    )
    assert path.name == "我的电影.webm"


def test_direct_falls_back_to_default_suffix(tmp_path):
    # 无后缀直链：调用方按介质类型给默认容器（音频传 .m4a），绝不猜 .mp4
    path = download_direct(
        "http://8.8.8.8/stream/12345",
        tmp_path,
        output_name="我的电影",
        default_suffix=".m4a",
        transport=_transport("audio/mp4"),
        validator=lambda url: url,
    )
    assert path.name == "我的电影.m4a"


def test_direct_corrects_audio_container_from_content_type(tmp_path):
    # URL 说 .mp4、终点声明 audio/mp4：纯音频不许落成视频容器
    path = download_direct(
        "http://8.8.8.8/media/audio_as_mp4.mp4",
        tmp_path,
        output_name="我的电影",
        transport=_transport("audio/mp4"),
        validator=lambda url: url,
    )
    assert path.name == "我的电影.m4a"


def test_direct_without_name_keeps_url_name(tmp_path):
    path = download_direct(
        "http://8.8.8.8/media/clip.mp4",
        tmp_path,
        transport=_transport(None),
        validator=lambda url: url,
    )
    assert path.name == "clip.mp4"

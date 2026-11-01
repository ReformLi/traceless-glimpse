"""4b-4 裁决②：yt-dlp 的中断现场（``.part`` / ``.part-FragN.part`` / ``.ytdl``）。

这些文件归 yt-dlp 管：引擎不读、不改、也不代它清理，续传由它自己按 ``.ytdl`` 接上。
本文件验三件事——现场**留在盘上**（取消后不清走）、状态文件**不含凭据**（grep 不到
URL / Cookie / sid=）、``.ytdl`` **只有续传记账字段**。第二条是给未来 yt-dlp 升级
兜底的：它今天只写分片序号，哪天开始把带签名的分片 URL 或请求头塞进状态文件就红。
"""
from __future__ import annotations

import json

import pytest

from services.downloader import DownloadCancelled, download_media
from testsupport.media_fixtures import ffmpeg_available, generate_all
from testsupport.media_server import MediaServer

pytestmark = pytest.mark.real

#: 状态/元数据文件里不允许出现的片段（媒体负载不适用，见下面第一条测试的说明）
FORBIDDEN_TOKENS = ("http://", "https://", "Cookie", "cookie", "sid=")


@pytest.fixture(scope="module")
def assets(tmp_path_factory):
    assert ffmpeg_available(), "本机无 ffmpeg：-m real 无法生成测试素材"
    dest = tmp_path_factory.mktemp("bgv-leftovers")
    generate_all(dest)
    return dest


@pytest.fixture(scope="module")
def server(assets):
    with MediaServer(assets, generate=False) as srv:
        yield srv


@pytest.fixture(scope="module")
def cancelled_hls(server, tmp_path_factory):
    """一次真实的「HLS 下载中途取消」：返回 (输出目录, 残留文件, 进度上报序列)。"""
    out_dir = tmp_path_factory.mktemp("bgv-cancel-scene")
    reports: list[tuple[int, int | None]] = []

    with pytest.raises(DownloadCancelled):
        download_media(
            server.base_url + "/media/hls/master.m3u8",
            out_dir,
            task_id="leftover-1",
            validator=lambda url: url,  # 仅测试放行回环；生产不注入
            should_abort=lambda: len(reports) >= 2,
            on_progress=lambda done, total: reports.append((done, total)),
        )
    assert reports, "取消前必须已经有进度上报，否则现场是假的"
    return out_dir, sorted(out_dir.iterdir()), reports


def test_cancelled_hls_keeps_ytdlp_resume_state(cancelled_hls):
    """取消后 yt-dlp 的现场原样留在输出目录，引擎不收拾。"""
    _, leftovers, _ = cancelled_hls
    names = [path.name for path in leftovers]
    assert names, "取消必须留下现场文件，否则续传无从谈起"
    assert any(name.endswith(".ytdl") for name in names), f"缺少 .ytdl 状态文件：{names}"
    assert all(".part" in name or name.endswith(".ytdl") for name in names), (
        f"取消不该产出成品或无关文件：{names}"
    )


def test_leftover_state_files_carry_no_credentials(cancelled_hls):
    """裁决②的 grep 守卫：``.ytdl`` 的字节里不能出现 URL / Cookie / sid=。

    只扫状态文件，不扫 ``.part`` / ``.part-FragN.part``：那些是**媒体字节**，
    里面出现 ``http://`` 是内容而不是泄漏 —— 本机实测 x264 把
    ``MPEG-4 AVC codec - Copyleft 2003-2025 - http://www.videolan.org/x264.html``
    写进了 SPS，切片里天然带 URL。对媒体负载做凭据 grep 只会造出一条永久误报，
    所以凭据守卫钉在引擎真正会读写的元数据文件上。
    """
    _, leftovers, _ = cancelled_hls
    state_files = [path for path in leftovers if path.suffix == ".ytdl"]
    assert state_files, "没有 .ytdl 就没有守卫对象"
    for path in state_files:
        blob = path.read_bytes()
        for token in FORBIDDEN_TOKENS:
            assert token.encode() not in blob, f"{path.name} 泄漏 {token!r}"


def test_ytdl_state_is_only_resume_bookkeeping(cancelled_hls):
    """``.ytdl`` 当前的形状：只有续传记账（分片序号），没有 URL / 路径字段。

    键白名单是刻意收紧的 —— yt-dlp 一旦开始往状态文件里写 url / filename，
    这条会先红，再由 :func:`test_leftover_state_files_carry_no_credentials` 说明漏了什么。
    """
    _, leftovers, _ = cancelled_hls
    state_files = [path for path in leftovers if path.suffix == ".ytdl"]
    assert state_files, "取消必须留下 .ytdl"
    for path in state_files:
        data = json.loads(path.read_text(encoding="utf-8"))
        assert set(data) == {"downloader"}, f"顶层出现新字段：{sorted(data)}"
        state = data["downloader"]
        assert set(state) <= {"current_fragment", "extra_state"}, (
            f"downloader 状态出现未知键：{sorted(state)}"
        )
        assert "url" not in state and "filename" not in state, f"状态文件出现寻址字段：{state}"

"""M4 P0：``merging`` 态接线 + 引擎侧**真实 FFmpeg** 合并（§19.3 缺口 1 的修）。

fake 的边界写清楚，别冒充别的东西：
- ``test_merge_*`` 用假下载器（造两个轨文件）+ 假合并函数，证的是**接线**（状态、参数、清理、失败归因）；
- ``test_real_ffmpeg_merge_*`` 用假 yt-dlp 载荷（把真素材复制到轨名）+ **真 FFmpeg**，
  产物用 ffprobe 数轨道——网络下载这一环是假的，合并这一步是真的。
"""
from __future__ import annotations

import json
import logging
import shutil
import subprocess
from pathlib import Path
from typing import Any

import pytest

import main
from models import (
    Codec,
    CookiePolicy,
    EstimatedQuality,
    ErrorCode,
    FormatInfo,
    MediaStreamType,
    PostAction,
    PostActionType,
    Resolution,
    SniffResult,
    Task,
    TaskMode,
    TaskStatus,
)
from services import downloader as downloader_service
from services import merger as merger_service
from testsupport.media_fixtures import ffmpeg_available, generate_all

pytestmark = pytest.mark.real


def _format(*, has_video: bool, has_audio: bool, format_id: str) -> FormatInfo:
    return FormatInfo(
        formatId=format_id,
        container="mp4",
        resolution=Resolution(width=1280, height=720),
        bitrateKbps=1200,
        codec=Codec(video="avc1" if has_video else "none", audio="mp4a" if has_audio else "none"),
        hasAudio=has_audio,
        hasVideo=has_video,
        fileSizeEstimateBytes=4_000_000,
        source="yt-dlp",
    )


_SEPARATE = [
    _format(has_video=True, has_audio=False, format_id="v"),
    _format(has_video=False, has_audio=True, format_id="b"),
]


def _record(tmp_path: Path, *, stream_type: MediaStreamType = MediaStreamType.HLS) -> main.TaskRecord:
    task = Task(
        id="task-merge-real",
        sourceUrl="http://8.8.8.8/watch",
        mode=TaskMode.DOWNLOAD,
        cookiePolicy=CookiePolicy.NONE,
        outputDir=str(tmp_path),
        status=TaskStatus.DOWNLOADING,
        postActions=[PostAction(type=PostActionType.MERGE)],
        createdAt="2026-11-02T00:00:00Z",
        updatedAt="2026-11-02T00:00:00Z",
    )
    record = main.TaskRecord(
        task,
        "normal",
        SniffResult(
            taskId=task.id,
            mediaUrl="http://8.8.8.8/hls/master.m3u8",
            type=stream_type,
            container="m3u8",
            estimatedQuality=EstimatedQuality(height=720, bitrateKbps=1200, label="720p"),
            drmDetected=False,
            discoveredAt="2026-11-02T00:00:00Z",
        ),
    )
    record.formats = list(_SEPARATE)
    return record


def _fake_tracks(tmp_path: Path) -> tuple[dict[str, Any], Any]:
    """造两条「假轨道文件」，并记录 download_tracks 收到的表达式。"""
    seen: dict[str, Any] = {}

    def _download_tracks(media_url, output_dir, **kwargs):
        seen.update(kwargs)
        video = tmp_path / "Sample.bgv-video.mp4"
        audio = tmp_path / "Sample.bgv-audio.m4a"
        video.write_bytes(b"video-payload")
        audio.write_bytes(b"audio-payload")
        return video, audio

    return seen, _download_tracks


def _stream_types(path: Path) -> set[str]:
    ffprobe = shutil.which("ffprobe")
    assert ffprobe, "本机无 ffprobe"
    proc = subprocess.run(
        [ffprobe, "-v", "error", "-show_streams", "-of", "json", str(path)],
        capture_output=True,
        text=True,
    )
    assert proc.returncode == 0, proc.stderr
    data = json.loads(proc.stdout or "{}")
    return {stream.get("codec_type") for stream in data.get("streams", [])}


# ---------------------------------------------------------------------------
# 接线：状态、参数、清理、失败归因
# ---------------------------------------------------------------------------


def test_merge_routes_through_merging_and_replaces_inputs_with_product(monkeypatch, tmp_path, caplog):
    """点合并 → 分轨表达式下发 + downloading → merging → done + 中间轨不留在输出目录。"""
    record = _record(tmp_path)
    seen, fake = _fake_tracks(tmp_path)
    monkeypatch.setattr(downloader_service, "download_tracks", fake)

    status_at_merge: list[TaskStatus] = []

    def _fake_merge(video, audio, output, **kwargs):
        status_at_merge.append(record.task.status)
        output.write_bytes(b"merged-product")
        return output

    monkeypatch.setattr(merger_service, "merge_streams", _fake_merge)
    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        main._download_safely(record)

    assert seen["video_expression"] == "bestvideo"
    assert seen["audio_expression"] == "bestaudio"
    assert status_at_merge == [TaskStatus.MERGING], "FFmpeg 调用时任务必须正处于 merging"
    assert record.task.status is TaskStatus.DONE
    assert (tmp_path / "Sample.mp4").read_bytes() == b"merged-product"
    assert not list(tmp_path.glob("*bgv-*")), "中间轨文件必须回收，别把用户目录当工地"
    assert record.task.progress.phase == "done"


def test_merge_failure_lands_merge_failed_and_spares_prior_product(monkeypatch, tmp_path, caplog):
    """合并失败：§5 的 MERGE_FAILED（不是 UNKNOWN），且绝不销毁同名的既有产物。"""
    record = _record(tmp_path)
    old_product = tmp_path / "Sample.mp4"
    old_product.write_bytes(b"previous-product")
    _, fake = _fake_tracks(tmp_path)
    monkeypatch.setattr(downloader_service, "download_tracks", fake)

    def _boom(video, audio, output, **kwargs):
        # FFmpeg 在校验输入/探测可执行文件时就失败：既有的同名产物一个字节都不该被动
        raise merger_service.MergeError("输入无效：audio")

    monkeypatch.setattr(merger_service, "merge_streams", _boom)
    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        main._download_safely(record)

    assert record.task.status is TaskStatus.FAILED
    assert record.task.error is not None and record.task.error.code is ErrorCode.MERGE_FAILED
    assert record.task.error.detail == {"reason": "merge_failed"}
    assert old_product.read_bytes() == b"previous-product"
    assert not list(tmp_path.glob("*bgv-*"))
    assert "merge_failed" in caplog.text


def test_merge_failure_removes_the_garbage_it_just_made(monkeypatch, tmp_path):
    """产物名合并前不存在时，失败留下的半截文件属于本次垃圾——清掉，别让用户点到坏文件。"""
    record = _record(tmp_path)
    _, fake = _fake_tracks(tmp_path)
    monkeypatch.setattr(downloader_service, "download_tracks", fake)

    def _boom(video, audio, output, **kwargs):
        output.write_bytes(b"half")  # FFmpeg -y 已经开了个头，然后非零退出
        raise merger_service.MergeError("ffmpeg 退出码 1")

    monkeypatch.setattr(merger_service, "merge_streams", _boom)
    main._download_safely(record)

    assert record.task.status is TaskStatus.FAILED
    assert not (tmp_path / "Sample.mp4").exists()
    assert not list(tmp_path.glob("*bgv-*"))


def test_cancel_during_merge_keeps_product_and_cancelled_wins(monkeypatch, tmp_path, caplog):
    """合并途中点取消：终态归 cancelled（不覆盖成 done），已合好的产物保留。"""
    record = _record(tmp_path)
    _, fake = _fake_tracks(tmp_path)
    monkeypatch.setattr(downloader_service, "download_tracks", fake)

    def _merge_then_cancel(video, audio, output, **kwargs):
        output.write_bytes(b"merged-product")
        record.cancel_requested.set()  # 复刻 cancel 端点：先置标志，再落 cancelled
        main._transition(record.task, TaskStatus.CANCELLED)
        return output

    monkeypatch.setattr(merger_service, "merge_streams", _merge_then_cancel)
    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        main._download_safely(record)

    assert record.task.status is TaskStatus.CANCELLED
    assert record.task.error is None
    assert (tmp_path / "Sample.mp4").is_file()
    assert "merge_cancelled" in caplog.text
    # 取消时产物已合好：必须写进台账，否则下载管理页看不见它、也删不掉（死文件）。
    assert record.task.outputPath == str(tmp_path / "Sample.mp4")


def test_direct_source_keeps_single_file_path_and_audits_the_noop(monkeypatch, tmp_path, caplog):
    """MP4 直链只有一条文件：拆轨无从谈起，但必须留下 post_action_noop 而不是静默。"""
    record = _record(tmp_path, stream_type=MediaStreamType.MP4)
    called: list[str] = []

    def _no_split(*args, **kwargs):
        raise AssertionError("直链不该走分轨下载")

    monkeypatch.setattr(downloader_service, "download_tracks", _no_split)

    def _fake_direct(*args, **kwargs):
        called.append("direct")
        product = tmp_path / "Sample.mp4"
        product.write_bytes(b"x")
        return product

    monkeypatch.setattr(downloader_service, "download_direct", _fake_direct)
    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        main._download_safely(record)

    assert called == ["direct"]
    assert record.task.status is TaskStatus.DONE
    assert "post_action_noop" in caplog.text
    assert "reason=single_file_source" in caplog.text


# ---------------------------------------------------------------------------
# 真 FFmpeg：假 yt-dlp 载荷 + services.merger 的真实 subprocess 调用
# ---------------------------------------------------------------------------


class _TrackYdl:
    """按 ``format`` 是 bestvideo* 还是 bestaudio 把真素材复制成对应轨名。

    只假到「网络取字节」这一层：文件是真的 FFmpeg 产物，之后的 ffprobe 完整性校验
    与合并都走真代码。
    """

    def __init__(self, options: dict[str, Any], assets: Path) -> None:
        self.options = options
        self.assets = assets
        self._path: Path | None = None

    def __enter__(self) -> "_TrackYdl":
        return self

    def __exit__(self, *exc_info) -> bool:
        return False

    def _plan(self) -> tuple[Path, Path]:
        template = self.options["outtmpl"]
        if str(self.options["format"]).startswith("bestvideo"):
            source = self.assets / "media" / "video_only.mp4"
        else:
            source = self.assets / "media" / "audio_only.m4a"
        target = Path(
            template.replace("%(title)s", "Sample").replace("%(ext)s", source.suffix.lstrip("."))
        )
        return source, target

    def extract_info(self, url: str, download: bool = False) -> dict[str, Any]:
        info: dict[str, Any] = {
            "formats": [{"url": url}],
            "requested_downloads": [{"url": url}],
        }
        if not download:
            return info
        source, target = self._plan()
        shutil.copyfile(source, target)
        self._path = target
        size = target.stat().st_size
        for hook in self.options.get("progress_hooks") or []:
            hook({"status": "finished", "downloaded_bytes": size, "total_bytes": size})
        return info

    def prepare_filename(self, info: dict[str, Any]) -> str:
        assert self._path is not None
        return str(self._path)


@pytest.fixture(scope="module")
def assets(tmp_path_factory):
    assert ffmpeg_available(), "本机无 ffmpeg：-m real 无法生成测试素材"
    dest = tmp_path_factory.mktemp("bgv-merge-wiring")
    generate_all(dest)
    return dest


def test_real_ffmpeg_merge_produces_one_dual_track_file(monkeypatch, tmp_path, assets, caplog):
    """真 FFmpeg 合出来的单文件必须同时有 video 与 audio 两条流（不是「长度对但内容坏」）。"""
    record = _record(tmp_path)

    def _factory(options: dict[str, Any]) -> _TrackYdl:
        return _TrackYdl(options, assets)

    # download_tracks 走真实现（含偏移进度与轨文件回收），只把 yt-dlp 换成假载荷
    monkeypatch.setattr(downloader_service, "download_media", _media_via_fake_ydl(_factory))
    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        main._download_safely(record)

    merged = tmp_path / "Sample.mp4"
    assert record.task.status is TaskStatus.DONE, record.task.error
    assert merged.is_file() and merged.stat().st_size > 0
    assert _stream_types(merged) >= {"video", "audio"}
    assert not list(tmp_path.glob("*bgv-*"))
    assert "merge_complete" in caplog.text


_REAL_DOWNLOAD_MEDIA = downloader_service.download_media


def _media_via_fake_ydl(factory):
    """让真 ``download_media`` 跑在假 yt-dlp 载荷上：产物是真的，字节来源是复制的。

    真函数在模块导入时就取好，避免与被 monkeypatch 的顺序耦合（否则补丁后的自己
    会被当成「真实现」而无限递归）。
    """

    def _call(media_url, output_dir, **kwargs):
        kwargs.pop("ydl_factory", None)
        return _REAL_DOWNLOAD_MEDIA(media_url, output_dir, ydl_factory=factory, **kwargs)

    return _call


def test_download_tracks_reports_combined_bytes_with_audio_offset(tmp_path, assets):
    """两轨进度按**合计**字节上报：视频段落在已落盘的音频字节之后，不拿单段冒充全局。"""
    reports: list[tuple[int, int | None]] = []

    def _factory(options: dict[str, Any]) -> _TrackYdl:
        return _TrackYdl(options, assets)

    audio_bytes = (assets / "media" / "audio_only.m4a").stat().st_size
    video_bytes = (assets / "media" / "video_only.mp4").stat().st_size

    video, audio = downloader_service.download_tracks(
        "http://8.8.8.8/hls/master.m3u8",
        tmp_path,
        video_expression="bestvideo",
        audio_expression="bestaudio",
        task_id="tracks-progress",
        validator=lambda url: url,
        ydl_factory=_factory,
        on_progress=lambda done, total: reports.append((done, total)),
    )

    assert audio.is_file() and video.is_file()
    assert reports == [
        (audio_bytes, audio_bytes),
        (audio_bytes + video_bytes, audio_bytes + video_bytes),
    ], "第二段必须是「音频偏移 + 视频字节」，否则进度条会从 30% 跳回 5%"


def test_download_tracks_reclaims_both_inputs_when_second_track_fails(tmp_path, assets):
    """第二条（视频轨）失败时，先落盘的音频轨一起回收，不把半截工地留给用户目录。"""
    order: list[str] = []

    def _factory(options: dict[str, Any]) -> _TrackYdl:
        fmt = str(options["format"])
        order.append(fmt)
        if fmt.startswith("bestvideo"):
            raise RuntimeError("ERROR: Requested format is not available")
        return _TrackYdl(options, assets)

    with pytest.raises(RuntimeError):
        downloader_service.download_tracks(
            "http://8.8.8.8/hls/master.m3u8",
            tmp_path,
            video_expression="bestvideo",
            audio_expression="bestaudio",
            task_id="tracks-fail",
            validator=lambda url: url,
            ydl_factory=_factory,
        )

    assert order == ["bestaudio", "bestvideo"], "音频轨先下、视频轨在后：顺序决定失败时留什么现场"
    assert not list(tmp_path.glob("*bgv-*")), "半途失败也要回收自己下的轨文件"

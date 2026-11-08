"""封板前修复的回归测试（P0-2 / P0-3 / P1-1）。

- Cookie 临时副本的启动清扫：强杀进程留下的明文凭据必须被回收；
- ffmpeg 合并/抽轨必须能被取消立刻打断；
- 直链源勾「纯音频下载」绝不能交付完整 MP4，中间文件必须回收。
"""
from __future__ import annotations

import asyncio
import os
import subprocess
import time
from pathlib import Path

import pytest

import main
from models import (
    CookiePolicy,
    EstimatedQuality,
    FormatSelector,
    MediaStreamType,
    SelectionStrategy,
    SniffResult,
    Task,
    TaskMode,
    TaskStatus,
)
from services import cookies as cookies_service
from services import downloader as downloader_service
from services import merger as merger_service

_CREATED = "2026-11-08T00:00:00Z"


# ---------------------------------------------------------------------------
# P0-3 Cookie 临时副本启动清扫
# ---------------------------------------------------------------------------


def test_sweep_removes_only_stale_bgv_cookie_copies(tmp_path):
    stale = tmp_path / f"{cookies_service.COOKIE_TEMP_PREFIX}deadbeef{cookies_service.COOKIE_TEMP_SUFFIX}"
    stale.write_text("session=secret", encoding="utf-8")
    other = tmp_path / "another-app.txt"
    other.write_text("keep", encoding="utf-8")
    wrong_prefix = tmp_path / f"other-1{cookies_service.COOKIE_TEMP_SUFFIX}"
    wrong_prefix.write_text("keep", encoding="utf-8")
    wrong_suffix = tmp_path / f"{cookies_service.COOKIE_TEMP_PREFIX}x.bin"
    wrong_suffix.write_text("keep", encoding="utf-8")

    removed = cookies_service.sweep_stale_cookie_files(
        temp_dir=tmp_path, now=time.time() + 5
    )

    assert removed == 1
    assert not stale.exists()
    assert other.exists() and wrong_prefix.exists() and wrong_suffix.exists()


def test_sweep_keeps_a_copy_a_live_process_may_be_writing(tmp_path):
    fresh = tmp_path / f"{cookies_service.COOKIE_TEMP_PREFIX}live{cookies_service.COOKIE_TEMP_SUFFIX}"
    fresh.write_text("session=secret", encoding="utf-8")
    future = time.time() + 3600
    os.utime(fresh, (future, future))

    assert cookies_service.sweep_stale_cookie_files(temp_dir=tmp_path, now=time.time()) == 0
    assert fresh.exists()


def test_locked_cookie_copy_never_blocks_engine_startup(monkeypatch, tmp_path):
    locked = tmp_path / f"{cookies_service.COOKIE_TEMP_PREFIX}locked{cookies_service.COOKIE_TEMP_SUFFIX}"
    locked.write_text("session=secret", encoding="utf-8")

    def _boom(*_args, **_kwargs):
        raise OSError("file is locked")

    monkeypatch.setattr(Path, "unlink", _boom)
    assert cookies_service.sweep_stale_cookie_files(
        temp_dir=tmp_path, now=time.time() + 5
    ) == 0


def test_startup_runs_the_cookie_sweep(monkeypatch):
    class _Worker:
        async def start(self) -> None:
            pass

        async def stop(self) -> None:
            pass

    called: list[bool] = []
    monkeypatch.setattr(main, "sweep_stale_cookie_files", lambda: called.append(True))
    monkeypatch.setattr(main, "install_console_logging", lambda: None)
    monkeypatch.setattr(main, "install_log_redaction", lambda: None)
    monkeypatch.setattr(main, "_restore_state", lambda: None)
    monkeypatch.setattr(main, "WORKER", _Worker())
    monkeypatch.setattr(main, "ENGINE_MODE", "stub")

    asyncio.run(main._startup())
    assert called == [True]


# ---------------------------------------------------------------------------
# P1-1 ffmpeg 可取消 / P0-2 抽轨命令
# ---------------------------------------------------------------------------


class _FakeProc:
    """永远「还在跑」的子进程，用来验证取消回调确实杀了它。"""

    def __init__(self) -> None:
        self.terminated = False
        self.returncode = 0

    def poll(self):
        return None

    def terminate(self) -> None:
        self.terminated = True

    def kill(self) -> None:
        self.terminated = True

    def wait(self, timeout=None):
        return 0

    def communicate(self, timeout=None):
        return ("", "")


def test_extract_audio_command_drops_video_without_reencoding():
    command = merger_service.build_extract_audio_command("ffmpeg", "in.mp4", "out.m4a")

    assert "-vn" in command
    assert command[command.index("-c:a") + 1] == "copy"
    assert command[-1] == "out.m4a"


def test_extract_audio_produces_only_the_audio_file(tmp_path):
    source = tmp_path / "raw.mp4"
    source.write_bytes(b"video+audio")
    output = tmp_path / "song.m4a"

    def _runner(command, **_kwargs):
        Path(command[-1]).write_bytes(b"audio-only")
        return subprocess.CompletedProcess(command, 0, "", "")

    assert merger_service.extract_audio(
        source, output, ffmpeg_path="ffmpeg", runner=_runner
    ) == output
    assert output.read_bytes() == b"audio-only"


def test_merge_is_killed_immediately_when_cancelled(tmp_path):
    video = tmp_path / "Sample.bgv-video.mp4"
    audio = tmp_path / "Sample.bgv-audio.m4a"
    video.write_bytes(b"v")
    audio.write_bytes(b"a")
    proc = _FakeProc()

    with pytest.raises(merger_service.MergeCancelled):
        merger_service.merge_streams(
            video,
            audio,
            tmp_path / "out.mp4",
            ffmpeg_path="ffmpeg",
            should_abort=lambda: True,
            popen=lambda *_a, **_k: proc,
        )
    assert proc.terminated


def test_extract_audio_is_also_killed_when_cancelled(tmp_path):
    source = tmp_path / "raw.mp4"
    source.write_bytes(b"video+audio")
    proc = _FakeProc()

    with pytest.raises(merger_service.MergeCancelled):
        merger_service.extract_audio(
            source,
            tmp_path / "out.m4a",
            ffmpeg_path="ffmpeg",
            should_abort=lambda: True,
            popen=lambda *_a, **_k: proc,
        )
    assert proc.terminated


# ---------------------------------------------------------------------------
# P0-2 直链源纯音频：绝不交付完整 MP4
# ---------------------------------------------------------------------------


def _direct_record(tmp_path: Path, *, audio_only: bool, naming: str | None = None) -> main.TaskRecord:
    task = Task(
        id="task-audio-direct",
        sourceUrl="http://8.8.8.8/watch",
        mode=TaskMode.DOWNLOAD,
        cookiePolicy=CookiePolicy.NONE,
        outputDir=str(tmp_path),
        status=TaskStatus.DOWNLOADING,
        formatSelector=(
            FormatSelector(strategy=SelectionStrategy.AUDIO_ONLY)
            if audio_only
            else FormatSelector(strategy=SelectionStrategy.MAX_HEIGHT, maxHeight=1080)
        ),
        namingTemplate=naming,
        createdAt=_CREATED,
        updatedAt=_CREATED,
    )
    return main.TaskRecord(
        task,
        "normal",
        SniffResult(
            taskId=task.id,
            mediaUrl="http://8.8.8.8/v/clip.mp4",
            type=MediaStreamType.MP4,
            container="mp4",
            estimatedQuality=EstimatedQuality(height=720, bitrateKbps=1200, label="720p"),
            drmDetected=False,
            discoveredAt=_CREATED,
        ),
    )


def test_direct_audio_only_never_delivers_the_full_mp4(monkeypatch, tmp_path):
    record = _direct_record(tmp_path, audio_only=True, naming="我的播客")
    created_source: list[Path] = []
    seen: dict[str, Path] = {}

    def _fake_direct(_url, out_dir, **kwargs):
        source = Path(out_dir) / downloader_service.audio_source_filename(record.task.id)
        source.write_bytes(b"full-mp4-with-video")
        created_source.append(source)
        return source

    def _fake_extract(src, dst, **_kwargs):
        seen["src"] = Path(src)
        seen["dst"] = Path(dst)
        Path(dst).write_bytes(b"audio-only")
        return Path(dst)

    monkeypatch.setattr(downloader_service, "download_direct", _fake_direct)
    monkeypatch.setattr(merger_service, "extract_audio", _fake_extract)

    main._download_safely(record)

    assert record.task.status is TaskStatus.DONE
    assert Path(record.task.outputPath).name == "我的播客.m4a"
    assert seen["src"] == created_source[0]
    assert seen["dst"].suffix == ".m4a"
    assert not created_source[0].exists(), "带画面的中间 MP4 必须回收"


def test_plain_video_direct_source_is_unchanged(monkeypatch, tmp_path):
    record = _direct_record(tmp_path, audio_only=False)
    product = tmp_path / "clip.mp4"
    product.write_bytes(b"normal-video")

    monkeypatch.setattr(
        downloader_service, "download_direct", lambda *_a, **_k: product
    )

    def _must_not_extract(*_a, **_k):
        raise AssertionError("普通视频直链不该触发抽轨")

    monkeypatch.setattr(merger_service, "extract_audio", _must_not_extract)

    main._download_safely(record)

    assert record.task.status is TaskStatus.DONE
    assert record.task.outputPath == str(product)


def test_direct_audio_only_existing_product_is_not_overwritten(monkeypatch, tmp_path):
    record = _direct_record(tmp_path, audio_only=True, naming="我的播客")
    existing = tmp_path / "我的播客.m4a"
    existing.write_bytes(b"previous-product")

    def _fake_direct(_url, out_dir, **_kwargs):
        source = Path(out_dir) / downloader_service.audio_source_filename(record.task.id)
        source.write_bytes(b"full-mp4-with-video")
        return source

    def _fake_extract(_src, dst, **_kwargs):
        Path(dst).write_bytes(b"audio-only")
        return Path(dst)

    monkeypatch.setattr(downloader_service, "download_direct", _fake_direct)
    monkeypatch.setattr(merger_service, "extract_audio", _fake_extract)

    main._download_safely(record)

    assert existing.read_bytes() == b"previous-product"
    assert Path(record.task.outputPath).name != "我的播客.m4a"
    assert Path(record.task.outputPath).suffix == ".m4a"

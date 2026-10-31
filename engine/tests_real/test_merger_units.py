"""阶段 4 services 层单测：FFmpeg 合并命令构建与错误映射（runner 可注入）。"""
from __future__ import annotations

import logging
import subprocess
from types import SimpleNamespace

import pytest

from services import merger

pytestmark = pytest.mark.real


def _inputs(tmp_path):
    video = tmp_path / "video_only.mp4"
    audio = tmp_path / "audio_only.m4a"
    video.write_bytes(b"video-bytes")
    audio.write_bytes(b"audio-bytes")
    return video, audio


def test_build_merge_command_shape(tmp_path):
    command = merger.build_merge_command(
        "ffmpeg", tmp_path / "v.mp4", tmp_path / "a.m4a", tmp_path / "out.mp4"
    )
    assert command[0] == "ffmpeg"
    assert command.count("-i") == 2
    assert command[command.index("-c") + 1] == "copy"
    assert command[command.index("-map") + 1] == "0:v:0"
    assert command[command.index("-map", command.index("-map") + 1) + 1] == "1:a:0"
    assert command[-1] == str(tmp_path / "out.mp4")


def test_merge_streams_invokes_runner(tmp_path, caplog):
    video, audio = _inputs(tmp_path)
    output = tmp_path / "merged.mp4"
    calls: dict[str, object] = {}

    def _runner(command, **kwargs):
        calls["command"] = command
        calls["kwargs"] = kwargs
        output.write_bytes(b"merged-bytes")
        return SimpleNamespace(returncode=0, stderr="")

    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        result = merger.merge_streams(
            video, audio, output, ffmpeg_path="ffmpeg", runner=_runner
        )

    assert result == output
    assert calls["command"] == merger.build_merge_command("ffmpeg", video, audio, output)
    assert calls["kwargs"] == {"capture_output": True, "text": True, "timeout": 120.0}
    audit = [r.getMessage() for r in caplog.records if r.getMessage().startswith("merge_complete")]
    assert audit == ["merge_complete bytes=12"]


def test_merge_rejects_missing_input(tmp_path):
    video = tmp_path / "missing.mp4"
    audio = tmp_path / "audio.m4a"
    audio.write_bytes(b"audio")
    with pytest.raises(merger.MergeError) as exc:
        merger.merge_streams(video, audio, tmp_path / "out.mp4", ffmpeg_path="ffmpeg")
    assert "输入无效" in str(exc.value)


def test_merge_nonzero_exit_raises(tmp_path):
    video, audio = _inputs(tmp_path)
    output = tmp_path / "merged.mp4"

    def _runner(command, **kwargs):
        return SimpleNamespace(returncode=1, stderr="boom")

    with pytest.raises(merger.MergeError) as exc:
        merger.merge_streams(video, audio, output, ffmpeg_path="ffmpeg", runner=_runner)
    assert "退出码 1" in str(exc.value)
    assert not output.exists()


def test_merge_missing_ffmpeg(monkeypatch, tmp_path):
    video, audio = _inputs(tmp_path)
    monkeypatch.setattr(merger.shutil, "which", lambda name: None)
    with pytest.raises(merger.MergeError) as exc:
        merger.merge_streams(video, audio, tmp_path / "out.mp4")
    assert "缺少 ffmpeg" in str(exc.value)


def test_merge_empty_output_raises(tmp_path):
    video, audio = _inputs(tmp_path)

    def _runner(command, **kwargs):
        return SimpleNamespace(returncode=0, stderr="")

    with pytest.raises(merger.MergeError) as exc:
        merger.merge_streams(
            video, audio, tmp_path / "out.mp4", ffmpeg_path="ffmpeg", runner=_runner
        )
    assert "产物缺失" in str(exc.value)


def test_merge_timeout_raises(tmp_path):
    video, audio = _inputs(tmp_path)

    def _runner(command, **kwargs):
        raise subprocess.TimeoutExpired(cmd=command, timeout=kwargs.get("timeout", 120))

    with pytest.raises(merger.MergeError) as exc:
        merger.merge_streams(
            video, audio, tmp_path / "out.mp4", ffmpeg_path="ffmpeg", runner=_runner
        )
    assert "超时" in str(exc.value)

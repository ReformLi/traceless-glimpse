"""阶段 4 真实 FFmpeg 合并：本地生成的 video_only + audio_only → 单文件双轨。"""
from __future__ import annotations

import json
import shutil
import subprocess

import pytest

from services.merger import merge_streams
from testsupport.media_fixtures import ffmpeg_available, generate_all

pytestmark = pytest.mark.real


@pytest.fixture(scope="module")
def assets(tmp_path_factory):
    assert ffmpeg_available(), "本机无 ffmpeg：-m real 无法生成测试素材"
    dest = tmp_path_factory.mktemp("bgv-merge")
    generate_all(dest)
    return dest


def test_merge_generated_streams(assets, tmp_path):
    merged = merge_streams(
        assets / "media" / "video_only.mp4",
        assets / "media" / "audio_only.m4a",
        tmp_path / "merged.mp4",
    )
    assert merged.is_file()
    assert merged.stat().st_size > 0

    ffprobe = shutil.which("ffprobe")
    assert ffprobe, "本机无 ffprobe"
    proc = subprocess.run(
        [ffprobe, "-v", "error", "-show_streams", "-show_format", "-of", "json", str(merged)],
        capture_output=True,
        text=True,
    )
    assert proc.returncode == 0, proc.stderr
    data = json.loads(proc.stdout or "{}")
    stream_types = {stream.get("codec_type") for stream in data.get("streams", [])}
    assert {"video", "audio"} <= stream_types, f"合并结果缺少轨道：{stream_types}"
    assert float((data.get("format") or {}).get("duration") or 0) > 0

"""FFmpeg 音视频合并（阶段 4，services 层）。

不依赖 yt-dlp 内置 merger：显式构造 ffmpeg 命令（``-c copy``，不转码），
``runner`` 可注入，便于单测断言命令与错误映射。
"""
from __future__ import annotations

import logging
import shutil
import subprocess
from pathlib import Path
from typing import Callable

logger = logging.getLogger("bgv.engine")

Runner = Callable[..., subprocess.CompletedProcess]


class MergeError(Exception):
    """合并失败（输入无效 / ffmpeg 缺失 / 非零退出 / 产物缺失）。"""


def build_merge_command(
    ffmpeg_path: str,
    video_path: str | Path,
    audio_path: str | Path,
    output_path: str | Path,
) -> list[str]:
    """构建合并命令：视频取 0:v:0、音频取 1:a:0，``-c copy`` 不转码。"""
    return [
        ffmpeg_path,
        "-y",
        "-hide_banner",
        "-loglevel",
        "error",
        "-i",
        str(video_path),
        "-i",
        str(audio_path),
        "-c",
        "copy",
        "-map",
        "0:v:0",
        "-map",
        "1:a:0",
        str(output_path),
    ]


def merge_streams(
    video_path: str | Path,
    audio_path: str | Path,
    output_path: str | Path,
    *,
    ffmpeg_path: str | None = None,
    runner: Runner = subprocess.run,
    timeout: float = 120.0,
) -> Path:
    """把独立视频轨与音频轨合并为一个文件，返回产物路径。

    审计行 ``merge_complete`` 只带字节数，不带路径 / URL / Cookie。
    """
    video = Path(video_path)
    audio = Path(audio_path)
    output = Path(output_path)
    for source in (video, audio):
        if not source.is_file() or source.stat().st_size <= 0:
            raise MergeError(f"输入无效：{source.name}")

    ffmpeg = ffmpeg_path or shutil.which("ffmpeg")
    if not ffmpeg:
        raise MergeError("本机缺少 ffmpeg")
    command = build_merge_command(ffmpeg, video, audio, output)
    try:
        proc = runner(command, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired as exc:
        raise MergeError("ffmpeg 合并超时") from exc
    except OSError as exc:
        raise MergeError(f"ffmpeg 执行失败：{type(exc).__name__}") from exc

    if proc.returncode != 0:
        raise MergeError(f"ffmpeg 退出码 {proc.returncode}")
    if not output.is_file() or output.stat().st_size <= 0:
        raise MergeError(f"合并产物缺失：{output.name}")
    logger.info("merge_complete bytes=%d", output.stat().st_size)
    return output

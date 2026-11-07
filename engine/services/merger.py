"""FFmpeg 音视频合并（阶段 4，services 层）。

不依赖 yt-dlp 内置 merger：显式构造 ffmpeg 命令（``-c copy``，不转码），
``runner`` 可注入，便于单测断言命令与错误映射。

M16.5：可执行文件改由 :func:`resolve_ffmpeg` 统一解析——桌面控制面板配的
``BGV_FFMPEG_PATH`` 优先，未配置才回落系统 PATH；显式 ``ffmpeg_path`` 参数
（单测注入）优先级最高，保证既有断言一成不变。
"""
from __future__ import annotations

import logging
import shutil
import subprocess
from pathlib import Path
from typing import Callable

from services.ffmpeg_path import FFmpegNotFoundError, ffmpeg_executable

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


def resolve_ffmpeg(explicit: str | None = None) -> str:
    """``显式参数 > BGV_FFMPEG_PATH > PATH``，三处都找不到就抛明确错误。

    配置无效时**不**回落 PATH：用户明确指定了路径却静默用了另一个 ffmpeg，
    比直接报错更难排查（也正是 M16.5 真机验收要排除的情况）。
    """
    if explicit:
        return explicit
    try:
        found = ffmpeg_executable(which=shutil.which)
    except FFmpegNotFoundError as exc:
        raise MergeError(str(exc)) from exc
    if not found:
        raise MergeError(
            "本机缺少 ffmpeg（PATH 中未找到；可在控制面板「环境依赖自检」"
            "里指定 ffmpeg.exe 的绝对路径）"
        )
    return found


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

    ffmpeg = resolve_ffmpeg(ffmpeg_path)
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

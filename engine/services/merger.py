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
import time
from pathlib import Path
from typing import Any, Callable

from services.ffmpeg_path import FFmpegNotFoundError, ffmpeg_executable

logger = logging.getLogger("bgv.engine")

Runner = Callable[..., subprocess.CompletedProcess]


class MergeError(Exception):
    """合并失败（输入无效 / ffmpeg 缺失 / 非零退出 / 产物缺失）。"""


class MergeCancelled(Exception):
    """用户取消：ffmpeg 已被终止，调用方按 cancelled 终态收尾。"""


_POLL_INTERVAL = 0.1


def _terminate(proc: Any) -> None:
    """先 terminate 再 wait；Windows 上 terminate 即强杀，仍留 kill 兜底。"""
    try:
        proc.terminate()
    except OSError:
        pass
    try:
        proc.wait(timeout=2.0)
    except Exception:  # noqa: BLE001 —— 子进程卡死时不能挡住取消
        try:
            proc.kill()
        except OSError:
            pass


def _run_ffmpeg(
    command: list[str],
    *,
    runner: Runner,
    timeout: float,
    should_abort: Callable[[], bool] | None,
    popen: Callable[..., Any] | None = None,
) -> subprocess.CompletedProcess:
    """没有取消回调时沿用可注入的 ``runner``（旧单测断言不变）。

    有取消回调时改走 ``Popen`` 轮询：点取消后 100ms 内 terminate，
    不再像 ``subprocess.run(timeout=120)`` 那样让 ffmpeg 自己跑完。
    ``popen`` 可注入，便于单测验证「取消确实杀了进程」。
    """
    if should_abort is None:
        return runner(command, capture_output=True, text=True, timeout=timeout)
    factory = popen or subprocess.Popen
    proc = factory(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    deadline = time.monotonic() + timeout
    try:
        while proc.poll() is None:
            if should_abort():
                _terminate(proc)
                raise MergeCancelled("用户取消")
            if time.monotonic() >= deadline:
                _terminate(proc)
                raise subprocess.TimeoutExpired(command, timeout)
            time.sleep(_POLL_INTERVAL)
        stdout, stderr = proc.communicate(timeout=1.0)
        return subprocess.CompletedProcess(command, proc.returncode, stdout, stderr)
    finally:
        if proc.poll() is None:
            _terminate(proc)


def build_extract_audio_command(
    ffmpeg_path: str,
    source_path: str | Path,
    output_path: str | Path,
) -> list[str]:
    """从单文件直链里剥出音轨：``-vn -c:a copy``，不转码、不带画面。"""
    return [
        ffmpeg_path,
        "-y",
        "-hide_banner",
        "-loglevel",
        "error",
        "-i",
        str(source_path),
        "-vn",
        "-c:a",
        "copy",
        str(output_path),
    ]


def extract_audio(
    source_path: str | Path,
    output_path: str | Path,
    *,
    ffmpeg_path: str | None = None,
    runner: Runner = subprocess.run,
    timeout: float = 120.0,
    should_abort: Callable[[], bool] | None = None,
    popen: Callable[..., Any] | None = None,
) -> Path:
    """把直链源里的音轨封装成 ``.m4a``；源里没有可拷贝的音轨就明确失败。

    绝不假装成功：失败时调用方不会把那个完整 MP4 当成交付产物。
    """
    source = Path(source_path)
    output = Path(output_path)
    if not source.is_file() or source.stat().st_size <= 0:
        raise MergeError(f"输入无效：{source.name}")
    ffmpeg = resolve_ffmpeg(ffmpeg_path)
    command = build_extract_audio_command(ffmpeg, source, output)
    try:
        proc = _run_ffmpeg(
            command, runner=runner, timeout=timeout, should_abort=should_abort, popen=popen
        )
    except MergeCancelled:
        raise
    except subprocess.TimeoutExpired as exc:
        raise MergeError("ffmpeg 音频提取超时") from exc
    except OSError as exc:
        raise MergeError(f"ffmpeg 执行失败：{type(exc).__name__}") from exc
    if proc.returncode != 0:
        raise MergeError(f"ffmpeg 退出码 {proc.returncode}（源音轨可能无法直接封装）")
    if not output.is_file() or output.stat().st_size <= 0:
        raise MergeError(f"音频产物缺失：{output.name}")
    logger.info("audio_extract_complete bytes=%d", output.stat().st_size)
    return output


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
    should_abort: Callable[[], bool] | None = None,
    popen: Callable[..., Any] | None = None,
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
        proc = _run_ffmpeg(
            command, runner=runner, timeout=timeout, should_abort=should_abort, popen=popen
        )
    except MergeCancelled:
        raise
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

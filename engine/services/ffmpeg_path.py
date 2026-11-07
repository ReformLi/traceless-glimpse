"""FFmpeg / FFprobe 可执行文件解析（M16.5，引擎侧唯一入口）。

桌面控制面板允许用户把本机 ``ffmpeg.exe`` 的绝对路径写进 ``config.json``；
启动引擎子进程时由启动器注入 ``BGV_FFMPEG_PATH``。引擎里原先散落着三处
``shutil.which("ffmpeg")`` / ``which("ffprobe")``，本模块把它们收敛到一条规则：

=============================  ==================================================
优先级                          行为
=============================  ==================================================
``BGV_FFMPEG_PATH`` 已设置     必须是存在的文件，直接返回其绝对路径；**不**回落
                               PATH（否则「自定义路径生效」无从判断，配置写错也
                               会被 PATH 里另一个版本悄悄遮住）
未设置                          ``shutil.which("ffmpeg")`` / ``("ffprobe")``
都找不到                        返回 ``None``，由调用方抛各自领域的明确错误
=============================  ==================================================

``ffprobe`` 与 ``ffmpeg`` 通常同目录。自定义路径生效时优先取同目录的
``ffprobe.exe``，这样在「系统 PATH 里完全没有 FFmpeg」的机器上，下载器的
产物完整性校验（``_ffprobe_duration``）也不会先于合并失败。

``which`` 可注入，纯粹是为了让既有单测继续 monkeypatch 各自模块的
``shutil.which``；生产调用方一律不传。
"""
from __future__ import annotations

import os
import shutil
from pathlib import Path
from typing import Callable

#: 由桌面启动器注入，值是可执行文件的绝对路径。
ENV_FFMPEG_PATH = "BGV_FFMPEG_PATH"

Finder = Callable[[str], str | None]


class FFmpegNotFoundError(FileNotFoundError):
    """``BGV_FFMPEG_PATH`` 指向的文件不存在（配置错误，不是「环境缺依赖」）。"""


def configured_ffmpeg() -> str | None:
    """读环境变量并校验；未设置返回 ``None``，设置但无效抛明确异常。"""
    raw = (os.environ.get(ENV_FFMPEG_PATH) or "").strip().strip('"').strip("'")
    if not raw:
        return None
    path = Path(raw).expanduser()
    if not path.is_file():
        raise FFmpegNotFoundError(
            f"{ENV_FFMPEG_PATH} 指向的 ffmpeg 不存在：{raw}；"
            "请在控制面板「环境依赖自检」重新选择可执行文件"
        )
    return str(path.resolve())


def ffmpeg_executable(*, which: Finder | None = None) -> str | None:
    """ffmpeg 绝对路径；找不到返回 ``None``，配置无效抛 :class:`FFmpegNotFoundError`。"""
    configured = configured_ffmpeg()
    if configured:
        return configured
    finder = which or shutil.which
    return finder("ffmpeg")


def ffprobe_executable(*, which: Finder | None = None) -> str | None:
    """ffprobe 绝对路径：自定义 ffmpeg 的同目录兄弟优先，其次 PATH。"""
    configured = configured_ffmpeg()
    if configured:
        exe = Path(configured)
        sibling = exe.with_name("ffprobe" + exe.suffix)
        if sibling.is_file():
            return str(sibling.resolve())
    finder = which or shutil.which
    return finder("ffprobe")

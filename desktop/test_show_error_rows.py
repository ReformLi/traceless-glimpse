"""封板 P1-3：下载控制台报错时，所有 BUSY 行都必须收口。

旧实现 ``reversed(...) + break`` 只改最后一行，历史里粘滞的「下载中」
会永久留在界面上。这里绕过 Tk 构造，只测行状态收口这一层逻辑。
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from download_console import DownloadConsole  # noqa: E402


class _Row:
    def __init__(self, phase: str) -> None:
        self.phase = phase
        self.calls: list[str] = []

    def update(self, phase: str) -> None:
        self.phase = phase
        self.calls.append(phase)


def _console(rows: list[_Row]) -> DownloadConsole:
    console = object.__new__(DownloadConsole)
    console.task_rows = rows
    console._sniffing = True
    console._downloading = True
    console._finished = False
    console._show_message = lambda *_a, **_k: None
    console._refresh_controls = lambda: None
    return console


def test_show_error_marks_every_busy_row_not_just_the_last_one():
    rows = [
        _Row("downloading"),   # 旧行：终态帧丢失后粘在这里
        _Row("queued"),
        _Row("done"),          # 已完成的行不能被改写
        _Row("downloading"),
    ]

    _console(rows).show_error("NETWORK_TIMEOUT", "boom")

    assert [row.phase for row in rows] == ["failed", "failed", "done", "failed"]
    assert rows[2].calls == []


def test_show_error_with_no_busy_rows_is_a_noop_on_rows():
    rows = [_Row("done"), _Row("failed"), _Row("cancelled")]

    _console(rows).show_error("NOT_FOUND", "")

    assert [row.phase for row in rows] == ["done", "failed", "cancelled"]

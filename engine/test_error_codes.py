"""错误码引用守卫：源码里写出来的 `ErrorCode.X` 必须是 §5 表内真实成员。

回归动机：`main.py` 曾在嗅探失败分支引用不存在的 `ErrorCode.INTERNAL_ERROR`。
枚举成员拼错不在导入期报错，只有走到那条 rare path 才抛 AttributeError，
而那条 path 恰好是「用户粘了一个没有媒体的页面」——真机上表现为任务永久卡在
sniffing，客户端只能等超时。这条守卫把同类问题压回导入期之外的第一次跑测试。
"""
from __future__ import annotations

import re
from pathlib import Path

import pytest

from models import ErrorCode

ENGINE_ROOT = Path(__file__).resolve().parent
USAGE = re.compile(r"ErrorCode\.([A-Z][A-Z0-9_]*)")


def _source_files() -> list[Path]:
    return sorted(
        path
        for path in [*ENGINE_ROOT.glob("*.py"), *ENGINE_ROOT.glob("services/*.py")]
        if path.name != "models.py" and not path.name.startswith("test_")
    )


def test_source_list_is_not_empty():
    """守卫本身别扫成空集：main.py / security.py / services 都得在里面。"""
    names = {path.name for path in _source_files()}
    assert {"main.py", "security.py", "downloader.py"} <= names


@pytest.mark.parametrize("path", _source_files(), ids=lambda p: p.name)
def test_referenced_error_codes_exist(path: Path):
    referenced = set(USAGE.findall(path.read_text(encoding="utf-8")))
    unknown = sorted(referenced - set(ErrorCode.__members__))
    assert not unknown, f"{path.name} 引用了 §5 表外错误码：{unknown}"

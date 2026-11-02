"""非终态错误出口的静态守卫（§8-12 审计产出物 4）。

审计结论决定守卫形状：**「每个状态是否有可达的失败出口」用 AST 推不出来**——
出口既可能来自 `_fail_task`、也可能来自 `assert_transition` 抛出的 `TaskStateConflictError`
被 handler 转成 409，还取决于 `ENGINE_MODE` 分支与线程时序；硬凑一个「按状态扫 `_fail_task`
调用点」的守卫只会给出假绿。所以这里只静态检查**两条真能判定的不变式**，
运行时行为交给 `tests_real/test_nonterminal_exits.py` 那组用例。
"""
from __future__ import annotations

import ast
from pathlib import Path

from models import TaskStatus

import main

MAIN = Path(__file__).resolve().parent / "main.py"

DRIVERS = {"_advance": "_advance_safely", "_download_real": "_download_safely"}


def _tree() -> ast.Module:
    return ast.parse(MAIN.read_text(encoding="utf-8"))


def _enclosing_function(node: ast.AST, parents: dict) -> str | None:
    current = node
    while current is not None:
        if isinstance(current, ast.FunctionDef):
            return current.name
        current = parents.get(id(current))
    return None


def test_drivers_are_only_called_from_their_safe_wrappers():
    """状态推进/下载两个驱动函数不得有「裸调用」——绕过兜底就等于把任务留在非终态。"""
    tree = _tree()
    parents: dict[int, ast.AST] = {}
    for node in ast.walk(tree):
        for child in ast.iter_child_nodes(node):
            parents[id(child)] = node

    offenders = []
    calls = 0
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call):
            continue
        callee = node.func
        if not (isinstance(callee, ast.Name) and callee.id in DRIVERS):
            continue
        calls += 1
        if _enclosing_function(node, parents) != DRIVERS[callee.id]:
            offenders.append(f"main.py:{node.lineno} 裸调用 {callee.id}()")

    assert calls >= 2, f"守卫扫成空集（找到 {calls} 处驱动调用）：改名了？"
    assert not offenders, f"驱动函数存在绕过兜底的调用点：{offenders}"


def test_every_nonterminal_state_allows_failed():
    """§4 转移表：每个非终态都必须把 FAILED 列为合法后继——没有出口就是永久停滞。"""
    non_terminal = [
        status
        for status in TaskStatus
        if status not in {TaskStatus.DONE, TaskStatus.FAILED, TaskStatus.CANCELLED}
    ]
    assert len(non_terminal) == 6, f"非终态数量变了：{non_terminal}"
    missing = [
        status.value
        for status in non_terminal
        if TaskStatus.FAILED not in main.TRANSITIONS[status]
    ]
    assert not missing, f"这些非终态在转移表里没有 failed 出口：{missing}"


def test_safe_wrappers_actually_fail_the_task():
    """兜底包装必须真的调用 `_fail_task`，只 log 不置状态就是假守卫。"""
    tree = _tree()
    bodies = {
        node.name: node
        for node in ast.walk(tree)
        if isinstance(node, ast.FunctionDef) and node.name in DRIVERS.values()
    }
    assert set(bodies) == set(DRIVERS.values()), f"兜底包装不见了：{sorted(bodies)}"
    for name, function in bodies.items():
        called = {
            node.func.id
            for node in ast.walk(function)
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Name)
        }
        assert "_fail_task" in called, f"{name} 只记日志不落 failed：假兜底"
        assert "Exception" in ast.dump(function), f"{name} 没有 except Exception 兜底"

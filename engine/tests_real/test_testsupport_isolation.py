"""静态检查：testsupport/ 不得被生产路径 import（用户硬约束）。

扫描 engine 下的生产模块（根目录非 test_*.py / 非 conftest.py，以及未来的
services/ 目录），用 AST 解析 import 语句，确认没有引用 testsupport。
"""
from __future__ import annotations

import ast
from pathlib import Path

import pytest

ENGINE = Path(__file__).resolve().parents[1]

pytestmark = pytest.mark.real


def _production_files() -> list[Path]:
    files = [
        path
        for path in ENGINE.glob("*.py")
        if not path.name.startswith("test_") and path.name != "conftest.py"
    ]
    services = ENGINE / "services"
    if services.is_dir():
        files.extend(services.glob("*.py"))
    return files


def _imported_roots(path: Path) -> set[str]:
    tree = ast.parse(path.read_text(encoding="utf-8"))
    roots: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            roots.update(alias.name.split(".")[0] for alias in node.names)
        elif isinstance(node, ast.ImportFrom):
            if node.module:
                roots.add(node.module.split(".")[0])
            else:
                # from . import x —— 相对导入，与 testsupport 无关
                continue
    return roots


def test_production_modules_do_not_import_testsupport():
    production = _production_files()
    assert production, "未找到任何生产模块，静态检查失效"
    for path in production:
        assert "testsupport" not in _imported_roots(path), (
            f"生产模块 {path.name} 导入了 testsupport（仅测试包，禁止进入生产路径）"
        )


def test_testsupport_package_location():
    """testsupport 只应存在于 engine/testsupport/ 下，不散落到 engine 根。"""
    assert (ENGINE / "testsupport" / "media_server.py").is_file()
    assert (ENGINE / "testsupport" / "media_fixtures.py").is_file()
    assert not (ENGINE / "media_server.py").exists()
    assert not (ENGINE / "media_fixtures.py").exists()

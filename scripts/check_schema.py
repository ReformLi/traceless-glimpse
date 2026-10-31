#!/usr/bin/env python3
"""契约一致性校验：engine/schema/ ↔ shared/schema/（阶段 4 硬要求 1）。

比对四类：
1. required 集合
2. properties 字段名双向差集
3. enum 取值
4. 字段基础类型（type）

失败时逐条指认「Kotlin 多出 X / Python 多出 Y / 类型不一致 Z」，退出码非 0。
两侧 schema 均为脚本导出产物（勿手改）：engine/export_schema.py、:shared:exportSchemas。
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

MODELS = [
    "task",
    "sniff_result",
    "format_info",
    "format_selector",
    "post_action",
    "error_payload",
]

REPO_ROOT = Path(__file__).resolve().parent.parent
ENGINE_SCHEMA_DIR = REPO_ROOT / "engine" / "schema"
SHARED_SCHEMA_DIR = REPO_ROOT / "shared" / "schema"


def _load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def _fmt(values) -> str:
    return "[" + ", ".join(sorted(values)) + "]"


def compare_model(name: str, engine: dict, shared: dict) -> list[str]:
    """返回问题清单（空表示该类无差异）。engine=Python 侧，shared=Kotlin 侧。"""
    problems: list[str] = []

    # 1) required 集合
    py_req = set(engine.get("required", []))
    kt_req = set(shared.get("required", []))
    if py_req != kt_req:
        problems.append(
            f"[{name}] required 不一致："
            f"Kotlin 多出 {_fmt(kt_req - py_req)} / Python 多出 {_fmt(py_req - kt_req)}"
        )

    # 2) properties 字段名双向差集
    py_props: dict = engine.get("properties", {})
    kt_props: dict = shared.get("properties", {})
    kt_only = set(kt_props) - set(py_props)
    py_only = set(py_props) - set(kt_props)
    if kt_only:
        problems.append(f"[{name}] properties 字段名：Kotlin 多出 {_fmt(kt_only)}")
    if py_only:
        problems.append(f"[{name}] properties 字段名：Python 多出 {_fmt(py_only)}")

    # 3) enum 取值 + 4) 字段基础类型（仅比对两侧都存在的字段）
    for field in sorted(set(py_props) & set(kt_props)):
        py_field = py_props[field]
        kt_field = kt_props[field]

        py_type = py_field.get("type")
        kt_type = kt_field.get("type")
        if py_type != kt_type:
            problems.append(
                f"[{name}].{field} 类型不一致：" f"Python={py_type!r} / Kotlin={kt_type!r}"
            )

        py_enum = py_field.get("enum")
        kt_enum = kt_field.get("enum")
        if py_enum is not None or kt_enum is not None:
            if py_enum is None or kt_enum is None:
                problems.append(
                    f"[{name}].{field} enum 单侧缺失："
                    f"Python={py_enum!r} / Kotlin={kt_enum!r}"
                )
            elif py_enum != kt_enum:
                py_set, kt_set = set(py_enum), set(kt_enum)
                problems.append(
                    f"[{name}].{field} enum 取值不一致："
                    f"Kotlin 多出 {_fmt(kt_set - py_set)} / Python 多出 {_fmt(py_set - kt_set)}"
                    + ("" if py_set != kt_set else "（取值集合相同，但声明顺序不同）")
                )
    return problems


def main() -> int:
    problems: list[str] = []
    checked = 0

    for name in MODELS:
        engine_path = ENGINE_SCHEMA_DIR / f"{name}.json"
        shared_path = SHARED_SCHEMA_DIR / f"{name}.json"
        if not engine_path.exists() or not shared_path.exists():
            missing = [
                str(p.relative_to(REPO_ROOT))
                for p, exists in ((engine_path, engine_path.exists()), (shared_path, shared_path.exists()))
                if not exists
            ]
            problems.append(f"[{name}] schema 文件缺失：{missing}")
            continue
        problems.extend(compare_model(name, _load(engine_path), _load(shared_path)))
        checked += 1

    if problems:
        print(f"schema 一致性校验失败（已比对 {checked}/{len(MODELS)} 个模型）：")
        for item in problems:
            print(f"  - {item}")
        return 1

    print(f"schema 一致性校验通过：{checked}/{len(MODELS)} 个模型（required / properties / enum / type 四类全等）")
    for name in MODELS:
        print(f"  OK {name}.json")
    return 0


if __name__ == "__main__":
    sys.exit(main())

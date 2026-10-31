"""导出 Pydantic 协议模型 schema → engine/schema/*.json（阶段 4 与 Kotlin 侧比对）。

输出与 Kotlin 侧 SchemaExporter 同构，只含 type / required / properties：
- enum 定义内联为 {"type":"string","enum":[...]}（Pydantic 的 $defs/$ref 解引用）；
- 嵌套模型降级为 {"type":"object"} 占位（阶段 4 对嵌套层做降级比对）；
- Optional 的 anyOf 取非 null 分支。

文件名与 shared/schema/ 一一对应（小写蛇形，见两侧 README）。勿手改。
"""
from __future__ import annotations

import json
from pathlib import Path

from models import (
    ErrorPayload,
    FormatInfo,
    FormatSelector,
    PostAction,
    SniffResult,
    Task,
)

SCHEMA_DIR = Path(__file__).parent / "schema"

MODELS = [
    ("task", Task),
    ("sniff_result", SniffResult),
    ("format_info", FormatInfo),
    ("format_selector", FormatSelector),
    ("post_action", PostAction),
    ("error_payload", ErrorPayload),
]


def _resolve_ref(ref: str, defs: dict) -> dict:
    """$ref 解引用：enum 定义 → string+enum；其余（嵌套模型）→ object 占位。"""
    name = ref.split("/")[-1]
    definition = defs.get(name, {})
    if "enum" in definition:
        return {"type": "string", "enum": list(definition["enum"])}
    return {"type": "object"}


def _simplify_prop(prop: dict, defs: dict) -> dict:
    if "$ref" in prop:
        return _resolve_ref(prop["$ref"], defs)
    if "anyOf" in prop:
        # Optional 字段：丢弃 null 分支，取唯一非 null 分支
        non_null = [b for b in prop["anyOf"] if b.get("type") != "null"]
        if len(non_null) == 1:
            return _simplify_prop(non_null[0], defs)
        return {"type": "object"}
    if "type" in prop:
        if prop["type"] == "string" and "enum" in prop:
            return {"type": "string", "enum": list(prop["enum"])}
        return {"type": prop["type"]}
    return {"type": "object"}


def simplify(model) -> dict:
    """model_json_schema() → 仅 type/required/properties 的简写 schema。"""
    schema = model.model_json_schema()
    defs = schema.get("$defs", {})
    properties = {
        name: _simplify_prop(prop, defs)
        for name, prop in schema.get("properties", {}).items()
    }
    return {"type": "object", "required": schema.get("required", []), "properties": properties}


def export_all() -> list[Path]:
    SCHEMA_DIR.mkdir(parents=True, exist_ok=True)
    written = []
    for name, model in MODELS:
        path = SCHEMA_DIR / f"{name}.json"
        path.write_text(
            json.dumps(simplify(model), indent=2, ensure_ascii=False) + "\n",
            encoding="utf-8",
        )
        written.append(path)
    return written


if __name__ == "__main__":
    for path in export_all():
        print(f"wrote {path}")

"""导出 Pydantic 协议模型 schema → engine/schema/*.json（阶段 4 与 Kotlin 侧比对）。

输出与 Kotlin 侧 SchemaExporter 同构，只含 type / required / properties / x-nullable：
- enum 定义内联为 {"type":"string","enum":[...]}（Pydantic 的 $defs/$ref 解引用）；
- 嵌套模型降级为 {"type":"object"} 占位（阶段 4 对嵌套层做降级比对）；
- Optional 的 anyOf 取非 null 分支，并**保留可空性**为 `"x-nullable": true`（收尾⑤）——
  丢掉 null 分支后 `Optional[str]` 与 `str` 导出结果相同，双端「这条能不能是 null」就无从对账；
- 不可空字段不带该键（缺省即 false），两侧同规则才不会因「一侧写 false」判出差异。

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

# 与 Kotlin 侧 SchemaExporter.X_NULLABLE 同名同义：这条字段允许出现 JSON null
X_NULLABLE = "x-nullable"

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


def _base_prop(prop: dict, defs: dict) -> dict:
    """丢掉可空包装后的基础类型（与 Kotlin 侧 typeOf 的分支一一对应）。"""
    if "$ref" in prop:
        return _resolve_ref(prop["$ref"], defs)
    if "anyOf" in prop:
        non_null = [b for b in prop["anyOf"] if not _is_null_branch(b)]
        if len(non_null) == 1:
            return _base_prop(non_null[0], defs)
        return {"type": "object"}
    if "type" in prop:
        if prop["type"] == "string" and "enum" in prop:
            return {"type": "string", "enum": list(prop["enum"])}
        return {"type": prop["type"]}
    return {"type": "object"}


def _is_null_branch(branch: dict) -> bool:
    """Pydantic v2 的 null 分支固定写成 {"type": "null"}。"""
    return branch.get("type") == "null"


def _is_nullable(prop: dict) -> bool:
    """这条字段在 JSON 里是否允许出现 null。"""
    if "anyOf" in prop:
        return any(_is_null_branch(b) for b in prop["anyOf"])
    return prop.get("type") == "null"


def _simplify_prop(prop: dict, defs: dict) -> dict:
    out = _base_prop(prop, defs)
    if _is_nullable(prop):
        out[X_NULLABLE] = True
    return out


def simplify(model) -> dict:
    """model_json_schema() → 仅 type/required/properties/x-nullable 的简写 schema。"""
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

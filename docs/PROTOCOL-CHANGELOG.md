# BrowseGuard Vid 协议变更记录（PROTOCOL-CHANGELOG）

记录 `docs/PROTOCOL.md` 正文之外的**增量裁决**。协议正文仍以 `docs/PROTOCOL.md` v0.1 为准，
本文件不改动正文；条目最终是否并入正式协议版本，由下一版协议评审时确认。

## M0 阶段裁决（2026-10-31）

### 新增错误码

来源：**M0 阶段裁决，非协议 v0.1 原文**（§5 错误码表未收录这两项）。

| code | category | retryable | 用户可读文案 | 触发场景 | 实现位置 |
|---|---|---|---|---|---|
| `VALIDATION_ERROR` | input | 否 | 请求参数不合法 | 请求体 / 查询参数未通过结构校验（如缺 `mode`、`formatSelector` 结构错误、`limit` 越界）；`detail.errors` 仅含 `loc` + `type`，不回显用户输入值（§1.7 脱敏） | `engine/models.py` `ErrorCode.VALIDATION_ERROR`；`engine/main.py` `validation_error_handler` |
| `NOT_FOUND` | input | 否 | 请求的资源不存在 | 访问不存在的 `taskId`（如 `GET /v1/tasks/{id}` 404） | `engine/models.py` `ErrorCode.NOT_FOUND`；`engine/main.py` `http_exception_handler` |

说明：

- 两项均为 `input` / `retryable=false`，与 §5 表既有风格一致。
- 两侧模型同步：Kotlin `shared/.../model/ErrorPayload.kt` 的 `ErrorCode` 枚举与 Pydantic
  `ErrorCode` 各 30 个取值，已由 `scripts/check_schema.py` 校验一致
  （`engine/schema/error_payload.json` ↔ `shared/schema/error_payload.json`）。
- 待办：v0.2 协议评审时把这两行补入 §5 表，并删除本条目。

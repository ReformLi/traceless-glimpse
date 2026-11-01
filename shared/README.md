# shared/ — KMP 协议模型

Kotlin Multiplatform（commonMain + JVM target），用 kotlinx.serialization 实现 `docs/PROTOCOL.md` §3 数据结构的单一来源，供 Android / CLI / MCP 三端复用。

## 约定

- 所有模型 `@Serializable`；反序列化 `ignoreUnknownKeys = false`——未知字段一律拒绝（协议层安全约束）。
- shared 内唯一 Json 配置对象 `BgvJson`，所有序列化必须走它。
- `shared/schema/*.json` 为契约产物：由导出脚本（jvmMain）生成后提交，勿手工修改；`scripts/check_schema.py` 与 engine 侧对账。
- **schema 文件命名风格**：小写蛇形，与模型导出顺序一一对应 `engine/schema/`——
  `task.json` / `sniff_result.json` / `format_info.json` / `format_selector.json` / `post_action.json` / `error_payload.json`；
  导出命令：`.\gradlew.bat :shared:exportSchemas`。
- **可空性标记 `x-nullable: true`**：字段类型允许 `null`（声明带 `?`）时由 `SchemaExporter` 写入，
  取 `SerialDescriptor.isNullable`；不可空字段不带该键。注意取的是 `isNullable` 而不是
  `isElementOptional`（有没有默认值）——两者是两条轴，`required` 只跟后者走。
  对账脚本 `scripts/check_schema.py` 按 required / properties / enum / type / x-nullable 五类比对。
- Task 模型**不存在** `cookieFile` / `browser` / `userAgent` / `headers` 字段——见 PROTOCOL §1，字段不存在比存在但被校验更可靠。

## 构建

```powershell
.\gradlew.bat :shared:build
```

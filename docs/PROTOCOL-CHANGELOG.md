# 无痕掠影 (Traceless Glimpse) 协议变更记录（PROTOCOL-CHANGELOG）

记录 `docs/PROTOCOL.md` 正文之外的**增量裁决**。协议正文仍以 `docs/PROTOCOL.md` v0.2 为准，
本文件不改动正文；条目最终是否并入正式协议版本，由下一版协议评审时确认。

## M5 阶段裁决（2026-11-02）

### `Task.outputPath`（协议 0.1 → 0.2）

来源：**M5 阶段裁决，非协议 v0.1 原文**。

- `Task` 新增可空字段 `outputPath`：仅 `status=done` 且引擎产出真实文件时非空；stub、未完成、失败、取消一律 `null`。
- 写入时机：真实下载在合并成功后、置 `done` 之前写入；失败/取消不会带出半成品路径。
- 持久化：`tasks` 表新增 `output_path` 列（`PRAGMA user_version` 1 → 2），旧库自动迁移，历史任务保持 `null`。
- 三端同步：`engine/models.py`、`shared/.../model/Task.kt`、`engine/schema/task.json` ↔ `shared/schema/task.json`；Android 完成卡片仅在非空时展示「文件位置」。
- 协议版本升 `0.2`：响应头 `X-Protocol-Version: 0.2`；客户端对缺失/不符的版本头按传输失败处理。

### sniff 语义：创建 `mode=download` + 空选择器

来源：**M5 阶段裁决**（修正 §7/§8 的链路断点）。

- CLI `bgv sniff` 与 MCP `sniff` 不创建 `mode=sniff` 任务：统一创建 `mode=download` 且不带 `formatSelector` 的任务，任务停在 `awaiting_selection`，`sniff-results` / `formats` 可继续查询。
- 原因：`mode=sniff` 以 `done` 为终态，后续 `probe` / `download` 会返回 `409 TASK_STATE_CONFLICT`，形成不可下载的死链。
- 文档同步：§7 / §8 的 sniff 行已改为上述语义。

### MCP 新增第 5 个工具 `cancel`

来源：**M5 阶段裁决**。

- `cancel { taskId }` → `POST /v1/tasks/{id}/cancel`，返回 `{ taskId, status }`；终态任务仍按协议返回 `409 TASK_STATE_CONFLICT`。
- 用于 AI 处理大文件时响应用户「停止」指令。

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

## M2 阶段裁决（2026-10-31）

### 输出沙箱拒绝非白名单扩展名（复用既有错误码）

来源：**M2 阶段裁决，非协议 v0.1 原文**（§5 未单列扩展名错误码）。

- 扩展名白名单：`mp4/m4v/mkv/webm/mov/flv/ts/m4a/aac/mp3/opus/ogg/wav/flac`；`.part` 仅作下载临时文件。
- 拒绝时复用 `OUTPUT_DIR_REJECTED`（不新增错误码，避免三端同步升级），`detail` 说明原因且不含路径：

| detail.reason | 触发场景 |
|---|---|
| `extension_not_allowed` | 产物扩展名不在白名单（`detail.extension` 为小写扩展名，缺扩展名时为空串） |
| `filename_rejected` | 空名 / 尾点尾空格 / NUL / Windows 设备保留名 |
| `output_outside_dir` | 下载产物解析到输出目录之外（防标题拼接穿越） |

- 目录权限：创建时 `0700`，并对既有目录尽力收紧；Windows 下为尽力而为。
- 实现位置：`engine/security.py`（`validate_output_filename` / `validate_output_target` / `prepare_output_dir`）、`engine/services/downloader.py`。
- 待办：v0.2 协议评审时确认是否把 `detail.reason` 固化为协议字段。

### 惰性 Cookie 的引擎侧来源与语义

来源：**M2 阶段裁决，非协议 v0.1 原文**（协议仅定义 `cookiePolicy` 枚举）。

- 凭据来源只能是引擎侧环境变量 `BGV_COOKIE_FILE`（Netscape cookies.txt）；协议仍不存在 `cookieFile` / `browser` / `cookiePath` 字段。
- `none`：从不读取、从不发送。
- `lazy`：首次请求不带 Cookie；仅当页面导航或媒体请求返回 401/403 时，读取一次配置副本并重试一次；无配置或无可匹配 Cookie 时不重试。
- `explicit`：首次请求即发送配置文件的 Cookie。
- 原文件只读；yt-dlp 需要写回时使用临时副本，结束后删除。
- 实现位置：`engine/services/cookies.py`、`engine/services/sniffer.py`、`engine/services/downloader.py`。

### 隐私自检（§6.5）的取值口径

来源：**M2 阶段裁决，非协议 v0.1 原文**（协议仅给出响应示例，未定义字段语义与清理行为）。

- 端点始终开放（P1），只读，不发起出站请求，不读取 `BGV_COOKIE_FILE`。
- `listenAddress` 固定 `127.0.0.1:8730`，与 `engine/main.py` 的 uvicorn 绑定一致（协议固定回环，不提供覆盖入口）。
- `tlsEnabled` / `authEnabled` 恒 `false`，`activeProxyIds` 恒 `[]`：引擎当前无 TLS、无鉴权、无代理实现；字段保留以维持 §6.5 结构。
- `cacheTtlHours` 固定 `48`；`cacheDir` 平台默认（`BGV_CACHE_DIR` 可覆盖）；
  `cacheSizeBytes` 为目录内普通文件实际字节数（跳过符号链接），目录缺失或无权限时为 `0`。
- `lastCleanupAt` 暂为 `null`：尚无缓存清理子系统，不使用假时间戳；缓存落地后填最后一次清理时间。
- `dependencyVersions` 每次请求实时探测，失败或未安装填 `"unavailable"`：
  `yt-dlp` 取 `yt_dlp.version.__version__`，`playwright` 取已安装包元数据，
  `ffmpeg` 取 `ffmpeg -version` 首行版本 token。
- 实现位置：`engine/services/environment.py`、`engine/main.py` `privacy_selfcheck`。
- 待办：v0.2 协议评审时确认 `lastCleanupAt` 的可空语义及 `"unavailable"` 约定。

## M3 收尾裁决（2026-11-01）

本节两条都是**协议变更提案入档**：M3 不实现，不改 `docs/PROTOCOL.md` 正文，不改双端 schema、不改代码；
实现与评审归 M4 / v0.2。来源分别是 §8-5（4b-2 随附裁决「进 M3 待办，收尾讨论」）与
§8-6（`download_attempt` 语义偏差裁决「收尾时讨论是否补一条独立的 `download_rejected`」）。

### 提案 A：给 `Task` 增可选 `warnings` 字段（M4 实现）

**动机**：任务「降级完成了」目前只有引擎日志能证明，任务 JSON 与界面都看不出来。
最典型的是 4b-2 的 `selector_degraded`——用户选了 720p、元数据不足以判上限、引擎降级 `best` 跑完，
任务 `status=done`、`error=null`，客户端没有任何地方能说「这个结果是近似来的」。

**建议形状**：`warnings: list[TaskWarning] = []`（可选、默认空数组），
`TaskWarning = {code, message, detail?}`；`code` 为枚举（蛇形，与既有审计行同名，不新造语义）、
`message` 为用户可读文案、`detail` 为脱敏对象。
不新增必填字段、不改 `error` 语义、不让 warning 参与状态机——有 warning 的 `done` 仍是 `done`。

**兼容性硬约束（收尾②复核新发现，必须先定这一条再谈 schema）**：双端对未知字段都是**严格拒绝**——
引擎侧 `StrictModel` = `extra="forbid"`（`engine/models.py:22`），客户端 `BgvJson` =
`ignoreUnknownKeys = false`（[shared/.../BgvJson.kt:14](file:///E:/code/android/TracelessGlimpse/shared/src/commonMain/kotlin/com/hpu/browseguard/shared/BgvJson.kt#L14)），
而客户端**所有**响应解码都走这一份（[android/.../EngineClient.kt:82](file:///E:/code/android/TracelessGlimpse/android/app/src/main/java/com/hpu/browseguard/data/EngineClient.kt#L82)）。
因此「新增可选响应字段」在 v0.1 的严格性下**不是向后兼容动作**：引擎一开始在 `Task` 里发 `warnings`，
已安装的旧客户端解 `GET /v1/tasks/{id}` 会直接抛错，表现是「任务查不到」而不是「少一条提示」。
§8-5 那句「增可选 `warnings` 是唯一向后兼容的做法」要带这个前提才成立。三条出路，M4 评审选一条：

1. **顺序约束**（改动最小）：先发能识别 `warnings` 的客户端，引擎再开始发；期间引擎只打日志。
   代价：本机自用场景「先更 App 再更引擎」得写成发布约定，反过来会当场炸查询接口。
2. **非对称严格性**（真正的协议裁决）：请求侧维持严格拒绝——§10 那条「未知字段 → 400」是为挡
   `cookieFile` / `browser` 这类敏感字段，不动；响应侧改为忽略未知键。收益是以后加只读字段不必排发布顺序，
   代价是响应契约漂移不再被客户端立刻发现，改由 `scripts/check_schema.py` 兜。
3. **不动 `Task`，另开只读诊断端点**（如 `GET /v1/tasks/{id}/diagnostics`）：兼容性最干净，
   但要新定义端点、鉴权与生命周期（任务删除后诊断信息留多久），成本高于前两条。

**首批 `code` 候选**（全部对应现有日志行，字段化不新造语义）：

| code | 现有审计行 | 现日志级别 | 触发场景 |
|---|---|---|---|
| `selector_degraded` | `selector_degraded task_id=… reason=incomplete_metadata requested=…`（[engine/main.py:317](file:///E:/code/android/TracelessGlimpse/engine/main.py#L317)） | WARNING | `max_height` 上限判不了 → 降级 `best` 跑完 |
| `output_ext_corrected` | [engine/services/downloader.py:154](file:///E:/code/android/TracelessGlimpse/engine/services/downloader.py#L154) | INFO | 正文是真音频，扩展名由 mp4 改 m4a（产物名与用户预期不同） |
| `resume_unsupported` | [engine/services/downloader.py:275](file:///E:/code/android/TracelessGlimpse/engine/services/downloader.py#L275) | INFO | 服务器不支持 Range，续传降级为全新下载 |
| `resume_missed` | [engine/services/downloader.py:237](file:///E:/code/android/TracelessGlimpse/engine/services/downloader.py#L237) | INFO | `.part` 名命不中，按全新下载走（§12.8 裁决②） |

`detail` 沿用 §1.7 脱敏口径：不含 Cookie、token、完整 URL query；`resume_missed` 的 `expected` / `found`
只给文件名，不给路径。

**双端落地清单（M4 按此顺序，收尾②不动它们）**：`engine/models.py`（`Task` + 新 `TaskWarning`）→
重导 `engine/schema/task.json` → 同步 `shared/schema/task.json` → Kotlin `shared/.../Task.kt` 与 serializer →
`scripts/check_schema.py` 复跑（当前 6 模型口径会变成 7）→ 界面展示（完成卡片，与 §8-3 的翻译口径同期定）。

**待办**：v0.2 协议评审决定是否在 §3.1 `Task` 表增行；若选出路 2，§10 的未知字段条目需同步改写为非对称严格。

### 提案 B：新增 `download_rejected` 审计行（M4 实现，不新增错误码）

**动机**：`download_attempt` 打在入口第一行，它实际表达的是「请求到达」，不是「开始下载」。
DRM 拒绝、409、422 都会留下一条 attempt 行（§8-6），于是「attempt 计数」既不能当下载量也不能当拒绝量。

**保持不变的部分（这是提案的前提）**：`download_attempt` 的名称、位置、参数一律不动。协议 §10
「DRM 场景无任何下载尝试（引擎日志可证）」这条验收，靠的正是「有 attempt、其后没有任何下载行为行」；
把 attempt 挪到真下载之前，这条就变成自证，取证链断裂。

**新增行形状**：

```
download_rejected task_id=<id> stage=<entry|state|drm|selector> code=<§5 既有码> status=<HTTP>
```

只增行、不改既有行，所以 `engine/test_drm.py:63` 那种「attempt 恰 1 次」的断言不受影响。

**触发点（今天实测到的全部拒绝路径；两条入口都要覆盖）**：

| stage | 拒绝 | 位置 |
|---|---|---|
| `state` | 404 `NOT_FOUND`（`_get_record_or_404`） | [engine/main.py:935](file:///E:/code/android/TracelessGlimpse/engine/main.py#L935) |
| `state` | 409 `TASK_STATE_CONFLICT`：终态重下 / 下载中换 selector / 非 `awaiting_selection` | [engine/main.py:937-944](file:///E:/code/android/TracelessGlimpse/engine/main.py#L937) |
| `drm` | 422 `DRM_PROTECTED` | [engine/main.py:946](file:///E:/code/android/TracelessGlimpse/engine/main.py#L946) |
| `selector` | 422 `FORMAT_UNAVAILABLE`（exact 不可用 / 判得出上限但无匹配，real 模式） | [engine/main.py:951-966](file:///E:/code/android/TracelessGlimpse/engine/main.py#L951) |

引擎自动领取那一条入口（[engine/main.py:543-550](file:///E:/code/android/TracelessGlimpse/engine/main.py#L543)）
也打 attempt，但当前不存在拒绝分支（DRM 走 `awaiting_selection`，不算拒绝）；M4 实施时若那条路径新增拒绝，
必须同样打 `download_rejected`，否则两条入口的账对不平。

**明确不打 `download_rejected` 的两类**：① `202` 幂等重放（同 `formatSelector` 重复 `POST /download`，
§2.4 语义，是「已在下载」不是拒绝）；② 引擎内部异常导致的 5xx（走异常日志，不混进业务拒绝计数）。
否则 `attempt = 真开始下载 + rejected + 幂等重放` 这个恒等式会失效。

**不新增错误码**：`DRM_PROTECTED` / `TASK_STATE_CONFLICT` / `NOT_FOUND` / `FORMAT_UNAVAILABLE` 都是 §5 既有码，
本提案只动日志层，属 §10 审计取证口径的增量。

**待办**：v0.2 决定是否把 `download_rejected` 写进 §10 的审计语义清单；M4 实施时同步调整取证脚本 grep 口径，
并关闭 §8-6。

## M6–M9（2026-11-02 → 2026-11-05）：**无协议增量**

这三段做了大量工作，但没有一处动到三端合同，本文件因此没有新条目。写这一节是为了让「文档这里没更新」
不被读成「项目在这里没进展」，并把「不需要改协议」这个判断本身留下依据：

| 变化 | 为什么不构成协议增量 |
|---|---|
| M6 Android 完整功能（Room 历史、SAF 输出目录、Cookie 按域授权、R8 发布资产） | 全在设备内；`Task`/`SniffResult`/`FormatInfo`/`ErrorPayload` 字段与枚举一字未动，CI Schema 校验口径不变 |
| M7 内置浏览器与本机 HLS 逐段下载 | 本机执行者**不发协议请求**：`data/backend/` 里禁止出现 `EngineClient` / `encode(`，由 `TaskNeverGoesOnTheWireTest` 扫源码钉住 |
| M8 TS→MP4 转封装 + 音频完整性门禁链 | 引擎侧 PostAction 白名单里那行 `remux` 是 PC 引擎的能力，与 App 内转封装同名不同路径；本机的降级理由码（`audio_content_too_short` 等）是**本地归因**，不进 §5 错误码表——本地失败不借用引擎协议码 |
| M9 按 AU 拆分重封 | 纯容器写入层，产物字节形态变了但协议里没有任何字段描述音频包的 AU 结构 |

Cookie 相关同样没有增量：`headersHint` 白名单仍只有 `referer` / `user-agent`，协议层依旧没有承载 Cookie 的字段。
后续若要把 `remux` 语义（比如「容器转换必须保持音轨完整」）写进 §3.7 或 §10，那才需要开新版本并按 §「字段增删需三端确认」走。

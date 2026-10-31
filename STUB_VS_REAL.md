# STUB_VS_REAL（M1）

引擎由 `BGV_ENGINE_MODE` 选择 `stub`（默认）或 `real`。本文记录两类模式在
M1 中的五类行为差异。协议字段、错误码与状态机对两者一致。

## 1. 嗅探来源与格式解析

| 项目 | stub | real |
|---|---|---|
| 嗅探结果 | `_make_sniff` fixture；`sourceUrl` 路径含 `/drm` 走 DRM 场景 | Playwright 拦截媒体流；HLS 播放列表按 `keyformat` 三态判定 DRM |
| 格式表 | `_formats_for` fixture（`137+140` / `140`） | yt-dlp `extract_info` → `formats_from_info` |
| 出站校验 | 请求入站仍过 SSRF 校验 | 嗅探 URL、manifest、`requested_downloads` 均过统一 SSRF 校验 |

## 2. 画质选择与 DRM

| 项目 | stub | real |
|---|---|---|
| `formatSelector` | 保留 M0 契约，不做 yt-dlp 映射 | `best` / `bestaudio` / `max_height→best[height<=N]`；`exact` 返回 422 `FORMAT_UNAVAILABLE`（`M1 暂不支持精确 formatId`），不静默回落 best |
| DRM | fixture 判定 | manifest 三态判定；`drmDetected=true` 在下载入口短路 422 `DRM_PROTECTED`，两模式均不尝试下载 |

## 3. 下载执行与完整性

| 项目 | stub | real |
|---|---|---|
| 执行方式 | 仅推进状态与计数器，无网络 IO | 单文件直链 `download_direct`；HLS/DASH 交给 yt-dlp `download_media` |
| 续传 | 不涉及 | 写 `<name>.part` + `Range`；206 续写；200（`Accept-Ranges: none`）降级全量重下并记 `resume_downgraded` |
| 完整性 | 固定进度直接 `done` | 非空 + 长度/`Content-Range` 校验；yt-dlp 产物过 `ffprobe`（duration>0） |
| 失败映射 | 无下载失败路径 | `SecurityError`→原错误码；`DownloadIntegrityError`→`PARTIAL_DOWNLOAD`；其他→`UNKNOWN` |

## 4. 状态推进、并发与取消

| 项目 | stub | real |
|---|---|---|
| 推进方式 | GET 惰性、同步推进（queued→sniffing→…→done） | asyncio 单消费者 worker；阻塞 IO 用 `to_thread` 下沉 |
| 非阻塞 | 不适用（无 IO） | POST /tasks 与 POST /download 立即返回；GET 不等待嗅探/下载 |
| 取消 | 任意非终态 → cancelled | 同左；下载线程收尾检查终态，取消后不被 `done`/`failed` 覆盖 |
| 超时 | worker 每 1s 扫描，`awaiting_selection` 超阈值 → `failed/UNKNOWN` | 同左；阈值 `BGV_AWAITING_SELECTION_TIMEOUT_SECONDS` 默认 86400 秒 |

## 5. 持久化、重启恢复与运维

| 项目 | stub | real |
|---|---|---|
| SQLite | 同样落盘（`BGV_DATA_DIR/tasks.db`，`user_version=1`） | 同左 |
| 重启恢复 | `queued`/`awaiting_selection` 原样；活动态置 failed + `UNKNOWN/engine_restart` | 同左 |
| 恢复后嗅探数据 | fixture 重新生成，`/formats` 可用 | sniff/format 列表不落库：恢复的 `awaiting_selection` 任务 `/sniff-results`、`/formats` 为空 |
| 调试端点 | `/v1/_debug/download-counter` 默认 404，`BGV_ENABLE_DEBUG_ENDPOINTS=1` 开放 | 同左 |
| 测试隔离 | conftest 指向临时 `BGV_DATA_DIR`，每个用例清库 | 同左 |

## 已知限制（M1）

- real 模式 API 级端到端（真实站点嗅探→下载→合并）未纳入自动验收，M2 补齐；M1 用可注入的 sniffer/downloader 验证接线。
- `namingTemplate`、`postActions`、`progress` 不落库；重启后 `progress` 为空，由后续状态推进重算。
- 单 worker 顺序消费；并发下载排队执行，不引入 `ENGINE_BUSY` 限流。

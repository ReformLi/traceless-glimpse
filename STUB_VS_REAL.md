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
| 续传 | 不涉及 | 写 `<name>.part` + `Range`；**偏移只取 `.part` 磁盘大小**，不用客户端/进度上报的字节数；有残留时先过 Content-Type 门禁——终点声明非媒体（如 `text/html`）就拒绝续传并记 `resume_rejected task_id=… reason=unexpected_content_type`，防「错误页被当片段拼进产物」（4b-4 裁决①补的防御；只覆盖续传，全新下载不设这道门，见 §8-8）；名字对不上时按全新下载走，但记 `resume_missed task_id=… reason=part_name_mismatch expected=… found=…`（4b-4 裁决②：静默可以，无痕不行）；206 续写；200（服务器忽略 Range）降级全量重下并记 `resume_unsupported task_id=… reason=no_range`；逐跳校验前的 `Range: bytes=0-0` 探测**流式且有界**（`PROBE_MAX_BYTES=1024`，读满即断开并记 `resume_probe_truncated`），忽略 Range 或无限流都不会把探测变成一次完整下载（P0，见 §13） |
| 中断残留 | 不涉及 | 直链：引擎 own 的 `<name>.part`（续传读它、完成改名）；HLS/DASH：yt-dlp own 的 `<name>.part` / `<name>.part-FragN.part` / `<name>.ytdl`——**引擎不读、不改、不代它清理**，续传由 yt-dlp 按 `.ytdl` 自行接上（4b-4 裁决②）。将来若要清残留，只在 `failed` 终态按清理策略做，不在下载中途动 |
| 产物封装名 | 不涉及 | 直链按终点响应的 `Content-Type` 校正（`resolve_redirects` 那次 `bytes=0-0` 探测顺带取回，不额外发请求）：音频内容不会落成 `.mp4`，记 `output_ext_corrected task_id=… from=… to=… reason=audio_content_type`；认不出类型又没有后缀时交给 `validate_output_filename` 拒绝 |
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

## 6. 本文范围（M9 收官时补记）

本文只回答**引擎**的 `BGV_ENGINE_MODE=stub|real` 差异，写于 M1，M6–M9 未改其内容——那三段结论仍然成立。
项目现在实际上有三个执行者，第三个不在本文的坐标系里：

| 执行者 | 由什么切换 | 是否走协议 | 说明 |
|---|---|---|---|
| 引擎 stub | `BGV_ENGINE_MODE` | 是 | 推进状态与计数器，无网络 IO |
| 引擎 real | `BGV_ENGINE_MODE` | 是 | Playwright 嗅探 + yt-dlp 下载 + FFmpeg 合并/转封装 |
| **Android 本机** | App 设置页的执行者开关 | **否** | 内置浏览器发现 + OkHttp 直连 + HLS 并发分片 + 平台 `MediaExtractor`/`MediaMuxer` 纯拷贝转封装；不引入 FFmpeg，失败归因用本地码，不借用 §5 协议码 |

所以「stub 还是 real」这个问题在 Android 本机路径上无意义：它不是桩，也不是引擎的另一副面孔，
而是第三条代码路径。它的行为差异表（并发槽位、段级重试、音频完整性门禁链、降级保留 `.ts`）写在
`android/README.md`，逐轮取证在 `docs/smoke-report.md` §27–§29，需求条目在 `docs/PRD.md` §6.9。

M15 起桌面端多了一个 `desktop/` 控制面板与单文件 exe，但**它不是第四个执行者**：GUI 通过
`http://127.0.0.1:<port>` 调用同一套协议端点，启动器固定注入 `BGV_ENGINE_MODE=real`，
所以「stub 还是 real」在这条路径上答案永远是 real。桩模式继续只服务于协议联调、
Schema 对账与引擎单测。桌面端的形态、配置与边界见 `desktop/README.md`。

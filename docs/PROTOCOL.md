# 无痕掠影 (Traceless Glimpse) 接口协议（PROTOCOL v0.2）

| 项    | 内容                                    |
| ---- | ------------------------------------- |
| 文档   | `docs/PROTOCOL.md`                    |
| 版本   | 0.2（M5：`Task.outputPath`）              |
| 适用范围 | 本地引擎 ↔ Android 客户端 ↔ CLI ↔ MCP Server |
| 传输   | HTTP/1.1 + JSON（UTF-8）                |
| 默认监听 | `127.0.0.1:8730`                      |
| 协议头  | 响应携带 `X-Protocol-Version: 0.2`        |
| 版本策略 | 路径带 `/v1`；不兼容变更升 `/v2`                |

> 本协议是“三端怎么说话”的合同。任何一端新增字段需三端确认；删除或改语义必须升版本。协议层强制约束安全边界——**某些字段不存在，比存在但被校验更可靠**。

---

## 1. 设计约束（协议层安全）

以下不是建议，是协议本身的结构性约束：

1. **Task 中没有 `cookieFile`、`browser`、`cookiePath` 字段。** 只有 `cookiePolicy` 枚举（`none` / `lazy` / `explicit`）。Agent 与客户端在协议层无法指定读取哪个文件或哪个浏览器。
2. **`proxyId` 是引擎侧管理的代理引用，不是任意代理 URL。** 防止通过代理参数绕过 SSRF 校验。
3. **`outputDir` 由引擎侧白名单校验。** 协议只传字符串，拒绝时返回 `OUTPUT_DIR_REJECTED`。
4. **`formatSelector` 必须是结构化对象或受控 DSL，不接受 shell 字符串。** 避免命令注入与任意参数透传。
5. **所有 `sourceUrl`、`mediaUrl` 在引擎侧过统一出站校验器**，含重定向后二次校验。
6. **响应中的 `headersHint` 必须脱敏**：移除 `Cookie`、`Authorization`、`Set-Cookie` 及自定义鉴权头。
7. **错误 `detail` 字段必须脱敏**：不含 Cookie 值、token、完整敏感查询串。

---

## 2. 通用约定

### 2.1 资源标识

- `id`：UUIDv7（时间有序），字符串。
- 时间：ISO 8601 UTC，如 `2026-10-30T08:00:00Z`。

### 2.2 响应格式

- 成功：`2xx` + 资源 JSON 直接作为 body。
- 失败：`4xx/5xx` + `{ "error": ErrorPayload }`。
- 列表：`{ "items": [...], "nextCursor": "..." | null }`。

### 2.3 鉴权

- 本地回环（`127.0.0.1` / `::1`）：默认免令牌。
- 非回环监听：必须 TLS + `Authorization: Bearer <token>`；未配置 TLS 时引擎拒绝启动对外监听。
- 鉴权失败：`401` + `AUTH_REQUIRED`。

### 2.4 幂等

- `POST /v1/tasks` 支持可选 `Idempotency-Key` 头；相同 key 在 24h 内返回同一任务。
- `POST /v1/tasks/{id}/download` 对同一 `formatId` 重复调用幂等；不同 `formatId` 返回 `409 TASK_STATE_CONFLICT`。

### 2.5 分页

- `GET /v1/tasks?limit=50&cursor=<opaque>`；`limit` 默认 50，上限 200。

---

## 3. 核心数据结构

### 3.1 Task

```jsonc
{
  "id": "0192f3a4-7c1e-7b2a-9d3f-1a2b3c4d5e6f",
  "sourceUrl": "https://example.com/watch?v=abc",
  "mode": "download",                 // "sniff" | "download"
  "formatSelector": {                 // 可空；为空时进入 awaiting_selection
    "strategy": "max_height",
    "maxHeight": 1080,
    "preferCodec": "h264",
    "maxSizeBytes": 2147483648
  },
  "namingTemplate": "{platform}-{date}-{title}-{height}p",
  "postActions": [
    { "type": "merge" },
    { "type": "extract_subtitles", "langs": ["zh", "en"] }
  ],
  "proxyId": null,                    // 引擎侧代理配置引用
  "cookiePolicy": "lazy",             // "none" | "lazy" | "explicit"
  "outputDir": "/home/user/Videos/BGV",
  "status": "downloading",
  "progress": {
    "phase": "downloading",
    "bytesDownloaded": 104857600,
    "bytesTotal": 524288000,
    "speedBps": 5242880,
    "etaSeconds": 80,
    "percent": 20.0
  },
  "error": null,
  "createdAt": "2026-10-30T08:00:00Z",
  "updatedAt": "2026-10-30T08:01:20Z",
  "outputPath": null                  // 仅 done 且有真实产物时非空
}
```

**字段约束**
| 字段 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `mode` | enum | 是 | `sniff` 仅嗅探；`download` 嗅探后下载 |
| `formatSelector` | object \| null | 否 | 见 3.4；`download` 模式下为空则进入 `awaiting_selection` |
| `namingTemplate` | string | 否 | 变量白名单见 3.6；长度 ≤ 200 |
| `postActions` | array | 否 | 白名单动作，见 3.7 |
| `proxyId` | string \| null | 否 | 必须存在于引擎侧代理列表 |
| `cookiePolicy` | enum | 是 | 默认 `lazy` |
| `outputDir` | string | 是 | 绝对路径；引擎侧校验 |
| `status` | enum | 是 | 见第 4 节状态机 |
| `error` | object \| null | 否 | 仅 `failed` / `cancelled` 可非空 |
| `outputPath` | string \| null | 否 | 仅 `done` 且引擎产出真实文件时非空；stub / 未完成 / 失败 / 取消一律 `null` |

> 注意：**没有** `cookieFile`、`browser`、`userAgent`、`headers` 字段。请求头由引擎按站点策略生成，客户端不可注入任意头。

### 3.2 SniffResult

```jsonc
{
  "taskId": "0192f3a4-...",
  "mediaUrl": "https://cdn.example.com/hls/master.m3u8",
  "type": "hls",                      // "hls" | "dash" | "mp4" | "audio" | "other"
  "container": "m3u8",
  "estimatedQuality": {
    "height": 1080,
    "bitrateKbps": 4500,
    "label": "1080p"
  },
  "drmDetected": false,
  "drmSystem": null,                  // "widevine" | "fairplay" | "playready" | "unknown" | null
  "headersHint": {                    // 已脱敏；仅保留非敏感头
    "referer": "https://example.com/"
  },
  "discoveredAt": "2026-10-30T08:00:05Z"
}
```

**约束**

- `headersHint` 禁止出现 `Cookie`、`Authorization`、`Set-Cookie`、`X-*Token*`。
- `drmDetected=true` 时，后续 `POST /download` 必须返回 `DRM_PROTECTED`，不得尝试下载。

### 3.3 FormatInfo

```jsonc
{
  "formatId": "137+140",
  "container": "mp4",
  "resolution": { "width": 1920, "height": 1080 },
  "bitrateKbps": 4500,
  "codec": { "video": "avc1.640028", "audio": "mp4a.40.2" },
  "hasAudio": true,
  "hasVideo": true,
  "fileSizeEstimateBytes": 524288000,
  "source": "yt-dlp"                  // "yt-dlp" | "manifest" | "sniff"
}
```

### 3.4 FormatSelector（结构化，非 shell 字符串）

```jsonc
{
  "strategy": "exact",                // "exact" | "max_height" | "best" | "audio_only"
  "formatId": "137+140",              // strategy=exact 时必填
  "maxHeight": 1080,                  // strategy=max_height 时必填
  "preferCodec": "h264",              // 可选：h264 | h265 | av1 | vp9
  "maxSizeBytes": 2147483648          // 可选：超过则失败 FORMAT_UNAVAILABLE
}
```

引擎解析顺序：先按 `strategy` 过滤候选，再按 `preferCodec` 排序，最后按 `maxSizeBytes` 截断。**不接受** `yt-dlp` 原始 `-f` 字符串透传。

### 3.5 ErrorPayload

```jsonc
{
  "code": "SSRF_BLOCKED",
  "category": "security",             // input | network | security | content | storage | internal
  "message": "目标地址指向私有网络，已拒绝",
  "retryable": false,
  "detail": { "host": "127.0.0.1" }   // 已脱敏
}
```

### 3.6 命名模板变量白名单

`{platform}` `{date}` `{time}` `{title}` `{uploader}` `{height}` `{width}` `{formatId}` `{taskId}` `{ext}`

未知变量原样保留并记录警告日志；渲染后做路径安全校验（无 `..`、无绝对路径注入、长度 ≤ 255）。

### 3.7 PostAction 白名单

| type                | 参数                    | 说明                |
| ------------------- | --------------------- | ----------------- |
| `merge`             | 无                     | 合并分离音视频轨          |
| `remux`             | `container`           | 重封装               |
| `transcode`         | `targetCodec`, `crf?` | 转码（CPU 密集，P1）     |
| `extract_subtitles` | `langs[]`             | 提取字幕              |
| `transcribe`        | `model?`, `lang?`     | 本地 Whisper 转录（P1） |
| `thumbnail`         | 无                     | 生成缩略图             |
| `move`              | `destDir`             | 移动到目标目录（需过沙箱校验）   |

未知 `type` → `400 INVALID_POST_ACTION`。

---

## 4. 任务状态机

```
                 ┌─────────┐
                 │ queued  │
                 └────┬────┘
                      │ 引擎领取
                      ▼
                 ┌─────────┐
                 │ sniffing│
                 └────┬────┘
          ┌───────────┴───────────┐
          │ mode=sniff            │ mode=download
          ▼                       ▼
     ┌─────────┐          formatSelector 为空?
     │  done   │           ┌────┴────┐
     └─────────┘          yes         no
                           ▼           │
                  ┌────────────────┐   │
                  │awaiting_select │   │
                  └───────┬────────┘   │
                          │ POST /download
                          ▼            ▼
                     ┌────────────┐
                     │ downloading│◄───┘
                     └─────┬──────┘
                           ▼
                     ┌─────────┐
                     │ merging │
                     └────┬────┘
                          ▼
                  ┌───────────────┐
                  │post_processing│
                  └───────┬───────┘
                          ▼
                     ┌─────────┐
                     │  done   │
                     └─────────┘

任意非终态 ──取消──► cancelled（终态）
任意非终态 ──错误──► failed（终态，error 非空）
```

**状态转移规则**

| 当前                              | 允许转移到                                                                          | 触发                                     |
| ------------------------------- | ------------------------------------------------------------------------------ | -------------------------------------- |
| `queued`                        | `sniffing`, `cancelled`, `failed`                                              | 引擎领取 / 取消 / 入队校验失败                     |
| `sniffing`                      | `done`(mode=sniff), `awaiting_selection`, `downloading`, `failed`, `cancelled` | 嗅探完成 / 取消 / 错误                         |
| `awaiting_selection`            | `downloading`, `cancelled`, `failed`                                           | `POST /download` / 取消 / 超时（可配置，默认 24h） |
| `downloading`                   | `merging`, `done`, `failed`, `cancelled`                                       | 下载完成 / 无需合并 / 错误 / 取消                  |
| `merging`                       | `post_processing`, `done`, `failed`, `cancelled`                               | 合并完成 / 无后处理 / 错误 / 取消                  |
| `post_processing`               | `done`, `failed`, `cancelled`                                                  | 完成 / 错误 / 取消                           |
| `done` / `failed` / `cancelled` | —                                                                              | 终态，不可转移                                |

**幂等与冲突**

- 对终态任务调用 `POST /download` → `409 TASK_STATE_CONFLICT`。
- 对 `downloading` 及之后状态调用 `POST /download` 且 `formatId` 不同 → `409 TASK_STATE_CONFLICT`。

---

## 5. 错误码表

| code                      | category | retryable | 用户可读文案            | 说明                              |
| ------------------------- | -------- | --------- | ----------------- | ------------------------------- |
| `INVALID_URL`             | input    | 否         | 链接格式不正确           | 非 http/https 或解析失败              |
| `UNSUPPORTED_SCHEME`      | input    | 否         | 仅支持 http/https 链接 | 如 `file://`、`ftp://`            |
| `INVALID_OUTPUT_DIR`      | input    | 否         | 输出目录不合法           | 非绝对路径                           |
| `INVALID_FORMAT_SELECTOR` | input    | 否         | 画质选择参数不合法         | 结构或枚举错误                         |
| `INVALID_POST_ACTION`     | input    | 否         | 不支持的后处理动作         | 白名单外                            |
| `SSRF_BLOCKED`            | security | 否         | 目标地址指向受限网络，已拒绝    | 回环/私有/链路本地/元数据端点，含重定向后校验        |
| `OUTPUT_DIR_REJECTED`     | security | 否         | 输出目录不在允许范围内       | `/`、`$HOME`、当前目录、`PATH` 内目录     |
| `COOKIE_READ_DENIED`      | security | 否         | 凭据读取被拒绝           | 用户拒绝或策略禁止                       |
| `EXTENSION_NOT_ALLOWED`   | security | 否         | 文件类型不在白名单         | 扩展名校验失败                         |
| `AUTH_REQUIRED`           | security | 否         | 需要有效的访问令牌         | 非回环监听未带 Bearer                  |
| `UNSUPPORTED_SITE`        | content  | 否         | 暂不支持该站点           | 无可用解析器                          |
| `DRM_PROTECTED`           | content  | 否         | 该内容受 DRM 保护，不支持下载 | 检测到 Widevine/FairPlay/PlayReady |
| `NO_MEDIA_FOUND`          | content  | 是         | 未发现可下载的媒体流        | 嗅探为空；可能需滚动/等待                   |
| `FORMAT_UNAVAILABLE`      | content  | 否         | 所选画质不可用或超出体积上限    | 选择器无匹配                          |
| `EXTRACTOR_FAILED`        | content  | 是         | 解析失败，可能站点已变更      | 上游解析器错误                         |
| `NETWORK_TIMEOUT`         | network  | 是         | 网络超时              | 连接/读取超时                         |
| `DNS_FAILURE`             | network  | 是         | 域名解析失败            | —                               |
| `TLS_ERROR`               | network  | 否         | 安全连接失败            | 证书/握手错误                         |
| `NETWORK_HTTP_ERROR`      | network  | 是         | 目标站点返回错误          | `detail.status` 携带 HTTP 状态码     |
| `RATE_LIMITED`            | network  | 是         | 请求过于频繁            | 建议退避重试                          |
| `DISK_FULL`               | storage  | 否         | 磁盘空间不足            | —                               |
| `WRITE_PERMISSION_DENIED` | storage  | 否         | 无写入权限             | —                               |
| `MERGE_FAILED`            | internal | 否         | 音视频合并失败           | FFmpeg 错误                       |
| `TRANSCODE_FAILED`        | internal | 否         | 转码失败              | —                               |
| `PARTIAL_DOWNLOAD`        | internal | 是         | 下载不完整             | 完整性校验失败                         |
| `ENGINE_BUSY`             | internal | 是         | 引擎繁忙，请稍后重试        | 队列/并发上限                         |
| `TASK_STATE_CONFLICT`     | internal | 否         | 任务当前状态不允许该操作      | 状态机冲突                           |
| `UNKNOWN`                 | internal | 否         | 未知错误              | 兜底，`detail` 带 traceId           |

> `retryable=true` 的错误，客户端可自动重试；引擎侧对同一任务默认最多重试 3 次，指数退避。

---

## 6. HTTP API（本地引擎）

### 6.1 端点总览

| 方法     | 路径                             | 说明             | 优先级 |
| ------ | ------------------------------ | -------------- | --- |
| GET    | `/v1/health`                   | 健康检查与版本        | P0  |
| GET    | `/v1/privacy/selfcheck`        | 隐私自检信息         | P1  |
| POST   | `/v1/tasks`                    | 创建任务           | P0  |
| GET    | `/v1/tasks`                    | 任务列表           | P0  |
| GET    | `/v1/tasks/{id}`               | 任务详情           | P0  |
| GET    | `/v1/tasks/{id}/sniff-results` | 嗅探结果列表         | P0  |
| GET    | `/v1/tasks/{id}/formats`       | 可用格式列表         | P0  |
| POST   | `/v1/tasks/{id}/download`      | 选择格式并开始下载      | P0  |
| POST   | `/v1/tasks/{id}/cancel`        | 取消任务           | P0  |
| DELETE | `/v1/tasks/{id}`               | 删除任务记录（不删媒体文件） | P1  |
| GET    | `/v1/tasks/{id}/events`        | SSE 进度推送（可选）   | P1  |

### 6.2 `POST /v1/tasks`

请求：

```jsonc
{
  "sourceUrl": "https://example.com/watch?v=abc",
  "mode": "download",
  "formatSelector": { "strategy": "max_height", "maxHeight": 1080 },
  "namingTemplate": "{platform}-{date}-{title}-{height}p",
  "postActions": [{ "type": "merge" }],
  "proxyId": null,
  "cookiePolicy": "lazy",
  "outputDir": "/home/user/Videos/BGV"
}
```

响应 `201`：返回完整 `Task`（`status` 为 `queued` 或已进入 `sniffing`）。

常见错误：`INVALID_URL`、`UNSUPPORTED_SCHEME`、`INVALID_OUTPUT_DIR`、`OUTPUT_DIR_REJECTED`、`INVALID_FORMAT_SELECTOR`、`INVALID_POST_ACTION`、`SSRF_BLOCKED`、`ENGINE_BUSY`。

### 6.3 `GET /v1/tasks/{id}/formats`

响应 `200`：

```jsonc
{
  "taskId": "0192f3a4-...",
  "items": [ FormatInfo, ... ]
}
```

任务状态不是 `awaiting_selection` → `409 TASK_STATE_CONFLICT`。嗅探尚未完成时应先轮询任务状态，不视为可查询格式。

### 6.4 `POST /v1/tasks/{id}/download`

请求：

```jsonc
{
  "formatSelector": { "strategy": "exact", "formatId": "137+140" },
  "namingTemplate": "{title}-{height}p",   // 可选覆盖
  "postActions": [{ "type": "merge" }]     // 可选覆盖
}
```

响应 `202`：返回更新后的 `Task`（`status=downloading`）。

错误：`FORMAT_UNAVAILABLE`、`DRM_PROTECTED`、`TASK_STATE_CONFLICT`、`OUTPUT_DIR_REJECTED`。

### 6.5 `GET /v1/privacy/selfcheck`

响应 `200`：

```jsonc
{
  "listenAddress": "127.0.0.1:8730",
  "tlsEnabled": false,
  "authEnabled": false,
  "activeProxyIds": [],
  "cookiePolicyDefault": "lazy",
  "outputDirAllowlist": ["/home/user/Videos/BGV"],
  "cacheDir": "/home/user/.cache/bgv",
  "cacheTtlHours": 48,
  "cacheSizeBytes": 0,
  "lastCleanupAt": "2026-10-30T07:00:00Z",
  "dependencyVersions": {
    "yt-dlp": "2026.10.01",
    "ffmpeg": "7.1",
    "playwright": "1.50.0"
  }
}
```

### 6.6 `GET /v1/health`

```jsonc
{ "status": "ok", "version": "0.1.0", "protocolVersion": "0.2", "uptimeSeconds": 3600 }
```

---

## 7. CLI 映射（`bgv`）

| 命令                                                                 | 对应 API                          | 说明                |
| ------------------------------------------------------------------ | ------------------------------- | ----------------- |
| `bgv sniff <url> [--output <dir>] [--json]`                        | `POST /tasks` (mode=download，无 selector) + 轮询至 `awaiting_selection` | 仅嗅探，任务保持可继续下载 |
| `bgv formats <taskId> [--json]`                                    | `GET /tasks/{id}/formats`       | 查看格式              |
| `bgv download <taskId> --format <selector> [--watch] [--json]`     | `POST /tasks/{id}/download` + 轮询至终态 | 选择后下载             |
| `bgv download-url <url> [--format <selector>] [--output <dir>] [--watch]` | `POST /tasks` (mode=download) + 轮询至终态 | 嗅探 + 下载一步到位 |
| `bgv status <taskId> [--json]`                                     | `GET /tasks/{id}`               | 查看状态              |
| `bgv tasks [--limit 1..200] [--json]`                              | `GET /tasks`                    | 列表                |
| `bgv cancel <taskId> [--json]`                                     | `POST /tasks/{id}/cancel`       | 取消非终态任务           |

**CLI 约束**

- `sniff` 与 `download-url` 都创建 `mode=download` 任务，差别只在是否携带 `formatSelector`：不带则停在 `awaiting_selection`，可继续 `formats` / `download`。`mode=sniff` 以 `done` 为终态，会导致后续格式查询与下载返回 `409`，CLI 不使用该模式。
- `--format` 接受结构化语法（如 `max_height=1080`、`exact=137`、`audio_only`），不接受原始 `-f` 字符串。
- `--output` 由引擎校验；缺省取 `GET /privacy/selfcheck` 的输出目录白名单首项，CLI 不做本地路径预判。
- `--json` 模式下 stdout 只有协议 JSON，提示与进度全部走 stderr。
- 退出码：`0` 成功；`2` 输入错误；`3` 安全拒绝；`4` 内容不可用；`5` 引擎不可达；`6` 存储错误；`7` 内部错误；`8` 用户主动取消（本地语义，不占用协议码）。`2`–`6` 与 `ErrorPayload.category` 对应，`7` / `8` 为 CLI 本地退出语义。

---

## 8. MCP 工具映射

| 工具名        | 入参                                                          | 返回                                 | 对应 API             |
| ---------- | ----------------------------------------------------------- | ---------------------------------- | ------------------ |
| `sniff`    | `{ url, outputDir?, engineUrl? }`                           | `{ taskId, task, sniffResults, formats }` | `POST /tasks` (mode=download，无 selector) + 轮询至 `awaiting_selection` |
| `probe`    | `{ taskId, engineUrl? }`                                    | `{ taskId, items: FormatInfo[] }`  | `GET /formats`     |
| `download` | `{ taskId, format?, timeoutSeconds?, engineUrl? }`          | 终态 `Task`                         | `POST /download` + 轮询至终态 |
| `status`   | `{ taskId, engineUrl? }`                                    | `Task`                             | `GET /tasks/{id}`  |
| `cancel`   | `{ taskId, engineUrl? }`                                    | `Task`                             | `POST /cancel`     |

**MCP 约束**

- `sniff` 不创建 `mode=sniff` 任务：任务停在 `awaiting_selection`，后续 `probe` / `download` 才不会撞上 `409`。工具固定使用 `CookiePolicy.NONE`。
- `download` 的 `format` 缺省为 `best`，可选 `best` / `audio_only` / `max_height=N` / `exact=<formatId>`；`timeoutSeconds` 取值 1..86400，缺省 21600，超时返回本地错误 `download_timeout`（任务本身不因此停止）。
- 工具入参 schema 与 HTTP 请求体一致；不额外暴露 `outputDir` 之外的路径字段，不提供 Cookie 文件或浏览器类型。
- `download` 工具在 `DRM_PROTECTED` 时必须返回结构化错误，不得静默尝试其他格式。
- 成功结果 `isError=false`，`content[0].text` 为协议 JSON；引擎协议错误 `isError=true` 且原样返回 `ErrorPayload`；客户端本地事件使用独立 `localError` 命名空间（如 `engine_unreachable` / `sniff_timeout` / `task_cancelled`），不借用 §5 协议码。
- stdio 传输：stdout 只承载 JSON-RPC，日志一律走 stderr。
- 工具描述中明确“不处理 DRM、不上传媒体内容”。

---

## 9. 最小示例集（联调合同）

### 9.1 成功链路

```bash
# 1) 创建任务
curl -s -X POST http://127.0.0.1:8730/v1/tasks \
  -H 'Content-Type: application/json' \
  -d '{
    "sourceUrl":"https://example.com/watch?v=abc",
    "mode":"download",
    "cookiePolicy":"lazy",
    "outputDir":"/home/user/Videos/BGV"
  }'
# → 201 { "id":"0192f3a4-...", "status":"queued", ... }

# 2) 轮询状态
curl -s http://127.0.0.1:8730/v1/tasks/0192f3a4-...
# → 200 { "status":"awaiting_selection", ... }

# 3) 查看格式
curl -s http://127.0.0.1:8730/v1/tasks/0192f3a4-.../formats
# → 200 { "items":[ {"formatId":"137+140","resolution":{"width":1920,"height":1080}, ...} ] }

# 4) 选择并下载
curl -s -X POST http://127.0.0.1:8730/v1/tasks/0192f3a4-.../download \
  -H 'Content-Type: application/json' \
  -d '{"formatSelector":{"strategy":"exact","formatId":"137+140"}}'
# → 202 { "status":"downloading", ... }

# 5) 终态
curl -s http://127.0.0.1:8730/v1/tasks/0192f3a4-...
# → 200 { "status":"done", "progress":{"percent":100.0}, "error":null }
```

### 9.2 安全拒绝：SSRF

```bash
curl -s -X POST http://127.0.0.1:8730/v1/tasks \
  -H 'Content-Type: application/json' \
  -d '{"sourceUrl":"http://127.0.0.1/admin","mode":"sniff","cookiePolicy":"none","outputDir":"/home/user/Videos/BGV"}'
# → 400 { "error": { "code":"SSRF_BLOCKED", "category":"security", "retryable":false,
#                    "message":"目标地址指向受限网络，已拒绝", "detail":{"host":"127.0.0.1"} } }
```

### 9.3 内容拒绝：DRM

```bash
curl -s -X POST http://127.0.0.1:8730/v1/tasks/0192f3a4-.../download \
  -H 'Content-Type: application/json' \
  -d '{"formatSelector":{"strategy":"best"}}'
# → 422 { "error": { "code":"DRM_PROTECTED", "category":"content", "retryable":false,
#                    "message":"该内容受 DRM 保护，不支持下载",
#                    "detail":{"drmSystem":"widevine"} } }
```

### 9.4 输出目录拒绝

```bash
curl -s -X POST http://127.0.0.1:8730/v1/tasks \
  -H 'Content-Type: application/json' \
  -d '{"sourceUrl":"https://example.com/v","mode":"sniff","cookiePolicy":"none","outputDir":"/"}'
# → 400 { "error": { "code":"OUTPUT_DIR_REJECTED", "category":"security", "retryable":false,
#                    "message":"输出目录不在允许范围内" } }
```

---

## 10. 验收清单（协议层）

- [ ] `curl` 跑通 9.1 全链路，状态机按第 4 节转移。
- [ ] 9.2 / 9.3 / 9.4 返回对应错误码，且 **DRM 场景无任何下载尝试**（引擎日志可证）。
- [ ] `Task` 请求体中不存在 `cookieFile` / `browser` 字段；传入未知字段 → `400` 或忽略并记录警告（二选一，需固定）。
- [ ] `formatSelector` 传原始 `-f` 字符串 → `400 INVALID_FORMAT_SELECTOR`。
- [ ] `headersHint` 与 `error.detail` 中检索不到 Cookie 值、token。
- [ ] 同一 `Idempotency-Key` 重复创建返回同一 `taskId`。
- [ ] 终态任务再次 `POST /download` → `409 TASK_STATE_CONFLICT`。
- [ ] CLI 退出码与 `category` 映射正确。
- [ ] `done` 任务的 `outputPath` 指向真实产物；stub / 失败 / 取消任务为 `null`。
- [ ] MCP 五个工具（含 `cancel`）入参 schema 与 HTTP 一致。

---

## 11. 待定项（v0.2 不锁定）

1. **进度推送**：v0.2 仅轮询；SSE `/events` 作为 P1，需定义事件类型（`status_changed`、`progress`、`error`）。
2. **未知字段策略**：严格拒绝（`400`）还是忽略并警告？建议 v0.2 严格拒绝，避免客户端误传敏感字段。
3. **代理列表管理接口**：`GET/POST /v1/proxies` 是否对外暴露，还是仅配置文件管理？建议 v0.2 仅配置文件，减少攻击面。
4. **多用户/多租户**：明确不支持；引擎为单用户本地服务。
5. **OpenAPI 导出**：v0.2 手写文档；补 `openapi.yaml` 并用 CI 校验与示例一致。

---

这份初稿可直接落到 `docs/PROTOCOL.md`。落地顺序建议：**先把 3.1–3.5 的类型和 9.1–9.4 的示例写成可执行测试**（引擎用桩实现），再让 CLI 和 Android 各自对着测试开发——合同先跑起来，功能再填进去。

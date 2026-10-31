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

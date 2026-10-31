# BrowseGuard Vid M0 冒烟报告（协议 §9 联调合同）

| 项 | 值 |
|---|---|
| 执行日期 | 2026-10-31（Asia/Shanghai） |
| 被测对象 | `engine/main.py`（M0 桩引擎，`0.1.0`，`X-Protocol-Version: 0.1`） |
| 监听 | `127.0.0.1:8730` |
| 脚本 | `scripts/smoke.sh`（Git Bash + `curl.exe`，JSON 取值用 `engine/.venv`，不依赖 jq） |
| 执行命令 | `ENGINE_LOG=<engine 控制台日志> bash scripts/smoke.sh` |
| 结果 | **54 项断言全绿，退出码 0**（`RESULT: ALL GREEN`） |

复现前置条件（smoke 依赖 venv 内的 python 取 JSON，不依赖 jq）：

```powershell
cd engine
python -m venv .venv
.\.venv\Scripts\pip install -r requirements-lock.txt
```

复现：

```bash
# 1) 另开终端启动引擎（工作目录必须是 engine/ 或 PYTHONPATH 指向 engine/）
engine/.venv/Scripts/python.exe engine/main.py > "$TEMP/bgv-engine.log" 2>&1

# 2) 跑 smoke（PYTHON 默认取 engine/.venv/Scripts/python.exe）
ENGINE_LOG="$TEMP/bgv-engine.log" bash scripts/smoke.sh
```

## 1. 协议 §9 四示例结果

| 示例 | 请求 | 期望 | 实测 | 结论 |
|---|---|---|---|---|
| §9.1 成功链路 | `POST /v1/tasks` → `GET /v1/tasks/{id}` → `GET /formats` → `POST /download` → `GET` | 201 `queued` → `awaiting_selection` → 200 格式含 `137+140` → 202 `downloading` → `done`、`percent=100.0`、`error=null` | 全部一致；`error` 实测为 JSON `null`（脚本读作 `None`） | 通过 |
| §9.2 SSRF | `POST /v1/tasks`（`sourceUrl=http://127.0.0.1/admin`） | 400 `SSRF_BLOCKED` / `security` / `retryable=false` / `detail.host=127.0.0.1` | 逐字段一致（消息「目标地址指向受限网络，已拒绝」） | 通过 |
| §9.3 DRM | `POST /download`（DRM fixture 任务） | 422 `DRM_PROTECTED` / `content` / `retryable=false` / `detail.drmSystem=widevine` | 逐字段一致（消息「该内容受 DRM 保护，不支持下载」） | 通过 |
| §9.4 输出目录 | `POST /v1/tasks`（`outputDir="/"`） | 400 `OUTPUT_DIR_REJECTED` / `security` | 一致 | 通过 |

## 2. §9.3 DRM 零下载：显式证据字段

| 字段 | 值 | 取证方式 |
|---|---|---|
| `drm_task_id` | `01a18f6b-02d5-7ea8-ad98-49ac5cb55791` | `POST /v1/tasks` 响应 |
| `drm_download_attempt_lines` | **1** | `grep -c 'download_attempt'`（DRM 段前后差值 +1；携带该 task_id 的行恰好 1 条） |
| `drm_download_attempt_task_id_lines` | **1** | `grep -c 'download_attempt task_id=<DRM_ID>'` |
| `drm_download_counter_before` | **0** | `GET /v1/_debug/download-counter` |
| `drm_download_counter_after` | **0** | `GET /v1/_debug/download-counter`（422 之后立即读取） |
| `drm_task_status_after_reject` | `awaiting_selection` | `GET /v1/tasks/{id}` |

结论：审计行存在且仅一次，计数器前后均为 0 —— **DRM 场景没有任何下载尝试**。

引擎日志原文（`2026-10-31 08:45:18`，仅此一条 `download_attempt` 属于 DRM 任务）：

```
2026-10-31 08:45:18,418 bgv.engine INFO download_attempt task_id=01a18f6b-02d5-7ea8-ad98-49ac5cb55791
INFO:     127.0.0.1:8376 - "POST /v1/tasks/01a18f6b-02d5-7ea8-ad98-49ac5cb55791/download HTTP/1.1" 422 Unprocessable Entity
```

smoke 脚本对应输出片段：

```
== 3. §9.3 内容拒绝：DRM（零下载硬证据） ==
PASS  DRM 前下载计数器                               0
PASS  DRM code                                             DRM_PROTECTED
PASS  DRM 新增 download_attempt 审计行数             1
PASS  DRM 审计行携带 task_id 出现次数             1
PASS  DRM 后下载计数器（零下载）                0
PASS  DRM 任务拒绝后仍为 awaiting_selection         awaiting_selection
```

## 3. 偏差 2 修复端到端：带 formatSelector → 惰性到 done，全程无 POST /download

用例：`POST /v1/tasks` 携带 `formatSelector={"strategy":"max_height","maxHeight":1080}`，之后**只发 GET**。

| 步骤 | 结果 |
|---|---|
| 创建 | 201 `queued` |
| 首次 `GET /v1/tasks/{id}` | `downloading`（引擎自动领取，无需 POST /download） |
| 再次 `GET /v1/tasks/{id}` | `done`，`percent=100.0` |
| 该任务被 `POST /download` 的次数（引擎访问日志 grep） | **0** |
| 该任务 `download_attempt` 审计行数 | 1（引擎自动领取时打印） |

该任务的 `POST /download` 次数为 0 是**日志硬证据**，不是脚本注释：

```
check "该任务被 POST /download 的次数（日志）" 0 "$(log_count "POST /v1/tasks/$AUTO_ID/download")"
```

smoke 输出片段（`AUTO_TASK_ID=01a18f6b-16c3-7a28-9168-127a75286de2`）：

```
== 5. 带 formatSelector 创建 → 惰性到 done，全程无 POST /download ==
NOTE  本段只发 POST /v1/tasks + GET，不存在任何 POST /download 请求
PASS  首次 GET 直接 downloading（引擎自动领取） downloading
PASS  再次 GET 到 done                                  done
PASS  该任务被 POST /download 的次数（日志）    0
PASS  该任务 download_attempt 审计行数（引擎自动领取 1 次） 1
```

## 4. 附加用例（§2.4 / §4 / 阶段 4 新增错误码）

| 用例 | 期望 | 实测 |
|---|---|---|
| 同一 `formatId` 重复 `POST /download` | 202（幂等） | 202 `downloading` |
| 不同 `formatId` 对下载中任务 `POST /download` | 409 `TASK_STATE_CONFLICT` | 409 |
| 终态任务再次 `POST /download` | 409 `TASK_STATE_CONFLICT` | 409 |
| 未知 taskId `GET` | 404 `NOT_FOUND`（`retryable=false`） | 404 |
| 创建请求缺 `mode` | 422 `VALIDATION_ERROR`（`input`） | 422 |
| `Task` 响应体不含 `cookieFile` / `browser` | 出现次数 0 | 0 / 0 |

## 5. 与协议文档的偏差（仅记录，不修改 PROTOCOL.md）

1. **新增非协议调试端点 `GET /v1/_debug/download-counter`**：只读返回内存下载计数器
   （`{"downloadCount": N}`），仅供 smoke 取证 §9.3「计数器为 0」，不参与任何协议流程。
   已在 `engine/README.md` 标注为 M0 桩专属、M1 移除。协议 §6 端点表未收录该路径。
2. **`VALIDATION_ERROR` / `NOT_FOUND` 不在 §5 错误码表**：来自阶段 4 裁决，M0 结束后记入
   `docs/PROTOCOL-CHANGELOG.md`（标注「来源：M0 阶段裁决，非协议 v0.1 原文」）。

## 6. 待修正（M0 结束后处理，需文档/代码对齐方）

1. **PRD 版本号自相矛盾**：`docs/PRD.md` 标题写「PRD v0.2」，同文件头部表格仍为「v0.1 草案」。
2. **minSdk 与 PRD 不一致**：PRD NFR-05 要求「Android 10+」（API 29），
   `android/app/build.gradle.kts` 当前 `minSdk = 24`（需改 29 或改 PRD）。
3. **Android 骨架仍是 Views**：`android/app/build.gradle.kts` 依赖 `appcompat` + `material`，
   而 PRD §12（v0.2）锁定「Kotlin + Jetpack Compose」；M3 前需切 Compose 或明确骨架仅占位。
4. **两个新增错误码未进协议 §5 表**：`VALIDATION_ERROR`、`NOT_FOUND` 仅存在于实现与
   `engine/schema/error_payload.json`，需在 changelog 补条目（见 §5 偏差 2）。

## 7. M1 real 模式已知局限（2026-10-31 阶段 3 记录，不阻塞 M1 验收）

1. **本地回环媒体被 SSRF 统一校验器拒绝**：`127.0.0.1` 按设计拦截，real 模式无法用本地
   测试媒体服务器跑 API 级 E2E。services 层已用 mock 覆盖 `formats_from_info` /
   `download_media` / `redirect`；**API 级真实下载 E2E 推迟到 M2**，届时以可控测试域或
   测试专用配置为入口，不引入生产旁路。
2. **manifest 取不到时**：`sniff_manifest_blocked` 审计行（仅 `task_id`），`drmDetected=false`。
3. **real 模式下载为阻塞调用**：阶段 5 后台 worker 前，`GET /v1/tasks/{id}` 与
   `POST /download` 会同步等待下载完成。
4. **real 模式 formatSelector 最小映射 / 显式拒绝，非静默 best**（阶段 4 裁决方案 A）：
   `best → best`、`audio_only → bestaudio`、`max_height → best[height<=N]`。
   `exact` 返回 422 `FORMAT_UNAVAILABLE`（`detail.reason=exact_format_id_unsupported`，
   `detail.message="M1 暂不支持精确 formatId"`）；`max_height` 缺 `maxHeight`（≤0）返回
   422 `FORMAT_UNAVAILABLE`（`detail.reason=max_height_required`），不回落 `best`。
   API 入口（`POST /download`）在进入 `downloading` 前完成校验；引擎自动领取路径
   （带 selector 的 download 任务）对应任务置 `failed` + 同一 `detail`。
   `preferCodec` / `maxSizeBytes` 暂未参与映射，留 M2。

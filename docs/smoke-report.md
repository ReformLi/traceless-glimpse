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

## 8. 已知局限（M3 Android 客户端，2026-11-01）

1. 取消路径真机未验证，桩模式 800ms 即 done 所致；mockwebserver 单测 + 引擎 curl 已覆盖，real 模式补。
   **已关闭（2026-11-01）**：§9.5 真机点按到 `cancelled`，§11.4 真机字节流中断取证。
2. **4a 的「取消」是状态取消，不是断流**：real 下载阻塞在 `asyncio.to_thread` 里，取消不会杀死正在执行的下载线程，
   字节流不中断，取消后字节仍可能落盘（引擎侧只保证终态不被 `done` / `failed` 覆盖）。
   协作式断流（下载循环查取消标志 + 单测 + 真实字节流证据）在 4b 单独做。
   **已关闭（2026-11-01，4b-3）**：§11.1 标志接入两条循环、§11.2 十五条单测、§11.4 真机两条分支各一次取证。
3. **待讨论（设计层面，M3 收尾再定，不阻塞 4b）**：能力级约束翻译在元数据不完整时该怎么走。
   现状是客户端把界面选择翻译成 `best` / `audio_only` / `max_height`，元数据缺失时只能靠猜
   （9.8-1 就是这么踩中的）。问题是翻译前是否应先校验元数据完整性、不完整时**保留原始意图**
   （把用户真正选的那条带下去，由引擎侧决定），而不是就地降级成能力级近似。
   涉及协议 §3.4 是否要给 `FormatSelector` 增加「按 formatId 引用但允许引擎自行解析」的表达力，
   属文档/双端改动，M3 收尾时连同 `x-nullable` 一起讨论。
4. **`unitTests.isReturnDefaultValues = true` 是权宜**：只为 `android.util.Log.*` 不抛「not mocked」
   （`FormatSelection.selectorFor` 的 `video_only` 分支要打 warning），约束已写在
   [app/build.gradle.kts](file:///E:/code/android/BrowseGuardVid/android/app/build.gradle.kts) 的
   `testOptions` 注释里：依赖 Android API 行为的单测必须显式 mock（或走 androidTest），
   不得把这里的默认返回值当断言依据。M3 收尾评估 mockk / Robolectric 替代。
5. **4b-2 的「detail 记 warning」落不到任务对象上**：协议 v0.1 的 `Task` 只有 `error`，没有 warning 槽位，
   所以元数据不完整时的降级目前只体现在引擎日志（WARNING `selector_degraded task_id=... reason=incomplete_metadata`），
   客户端与任务 JSON 看不到「这个结果是降级来的」。要给任务加结构化 warning 属协议改动，
   需走 `docs/PROTOCOL-CHANGELOG.md`，与 §8-3、`x-nullable` 一起在 M3 收尾讨论。
   **裁决（2026-11-01，4b-2 验收随附）：进 M3 待办，本轮不动。** 若要做，
   **给 `Task` 增可选 `warnings` 字段是唯一向后兼容的做法**（新增必填字段或改 `error` 语义都会打断既有客户端）；
   届时先开 `docs/PROTOCOL-CHANGELOG.md` 条目再动双端 schema，M3 收尾讨论。
6. **`download_attempt` 审计行打太早，语义偏差**（详见 §10.5-3）：`main.py:886` 在 `POST /download`
   入口第一行就打，DRM 拒绝 / 409 / 422 都会留下一条 attempt 行，它实际表达的是「请求到达」而不是「开始下载」。
   **裁决（2026-11-01）：本轮不动。** M3 收尾时连同协议 §10 的审计语义一起讨论是否补一条独立的
   `download_rejected`（保留 attempt 的「收到请求」含义、把「真的开始写字节」分出来），
   属文档 + 引擎日志两处改动，需与既有取证脚本的 grep 口径一并调整。
7. **HLS 的 `bytesTotal` 抖动导致 `percent` 非单调**（现象见 §11.5-6）：yt-dlp 按已下分片估算总大小，
   6,261,468 → 4,478,592 → 5.3 MB → 4,461,762 都出现在同一个任务里，界面进度条会往回跳。
   **裁决（2026-11-01，4b-3 验收随附）：引擎侧不改**（上报抖动是上游真实状态，抹平它等于撒谎），
   也不在 4b-4 顺手修；由 **M4 在客户端显示层做单调化平滑**（进度只增不减 + 总量变化时收敛）。
   直链路径无此问题（`Content-Range` 给出确定总长，§11.4 D1 全程 4,218,400 稳定）。
8. **全新下载未校验 `Content-Type`**（4b-4 裁决①）：续传门禁只拦「有 `.part` 残留要拼」这一支
   （`resume_rejected reason=unexpected_content_type`，§12.1）；无残留时直链路径仍不做类型校验，
   「服务器把错误页当 200 媒体发回来」照样会落成产物。直链路径不做 ffprobe（只有 yt-dlp 路径做），
   所以这道口子还敞着。
   **裁决（2026-11-01，4b-4 验收）：留 M4 补，但断言必须是 `@pytest.mark.xfail(strict=True)`** ——
   `test_fresh_download_should_refuse_non_media_body` 断言的是**应有行为**（错误页不落盘、目录里
   不留产物），今天必然失败所以进 xfail；`strict=True` 保证哪天顺手修好了会 XPASS 转红，
   提醒把它转正成常规断言，而不是让实现与文档悄悄漂移。
9. **审计状态：M1「阶段 5 三件套」是追认入库，不是本会话的分阶段放行**——持久化（SQLite）、
   `TaskWorker`、断点续传与 24 h 超时扫描这一批（`f21ff56`）当时**整批落地后逐行审计**，
   没有走 M2/M3 那样「每步完成汇报并等确认」的门禁。同期隔离区 `quarantine/stage5-2026-10-31/`
   的 `environment.py` 明确不复用，隐私自检按 PROTOCOL §6.5 从协议重写（保持溯源干净）。
   留此一条是给后来的读者：这批代码的放行口径与其余里程碑不同，再复核时按「整批审计」对待，
   不要拿它的通过状态当分阶段验收的证据。
10. **审计状态：M2 惰性 Cookie 实际策略——已复核（§14.1）：三条路径均接线惰性读；直链有 real
    用例取证；嗅探/yt-dlp 路径 real 取证留 M4（仅代码核对 + fake 证接线）**（2026-11-01，M3 收尾第 1 条）。

    结论是「惰性读」而不是「不读」：`cookiePolicy` 是 `POST /v1/tasks` 必填字段、引擎默认上报
    `lazy`、客户端两处建任务都取该默认值，所以真机流程走的就是 `lazy`；4a 的 `cookie_present=false`
    是因为那批路由从不回 401/403，现场没触发回补——缺的是现场，不是代码。三条敞口按裁决处置：
    - **① yt-dlp 真进程（留 M4）**：惰性重试**仅 `fake_ydl` 证接线，real 未取证**——`cookiefile`
      从无到有、副本清理、原文件 mtime 不变都由 fake 证明，真进程对真 403 抛出的错误文本能否命中
      `_looks_like_rejection`（[engine/services/downloader.py:402](file:///E:/code/android/BrowseGuardVid/engine/services/downloader.py#L402)）
      没有现场。M4 做「受保护 HLS 真链路」时一并补。
    - **② 嗅探第二个信号（留 M4）**：`sniffer.py:165` 的「页面 200 但页内媒体请求被拒 → 整页重载」
      **real 未覆盖**——`tests_real/test_lazy_cookie_sniff.py` 四条走的全是导航响应那一支
      （`/protected.html` 直接 403）。代码在、语义对，缺的是现场。
    - **③ 模糊拒绝不回补（设计意图，已入码）**：「302 跳登录页」「200 但正文是登录墙」两条路径都不
      触发 Cookie 回补。复核认定为刻意的**凭据最小化**（只在明确的 401/403 拒绝信号下回补，不对登录
      页猜测性回补），按裁决写进 [engine/services/cookies.py](file:///E:/code/android/BrowseGuardVid/engine/services/cookies.py)
      模块 docstring，不只留在汇报里；M4 若要改判定条件，应先立证据再改这段口径。

## 9. M3 4a real 模式验证（2026-11-01，七项）

环境：引擎 `BGV_ENGINE_MODE=real` + 三门控临时白名单 `BGV_SSRF_ALLOWLIST=127.0.0.1`；
媒体服务器 `testsupport.media_server` 监听 `127.0.0.1:8731`（`--assets ../.devtmp/media-assets`）；
设备 `emulator-5554`（API 36）经 `adb reverse tcp:8730 tcp:8730` 访问引擎；
客户端 `com.hpu.browseguard` 保存地址 `http://127.0.0.1:8730`。
设备只与引擎通信，出站取媒体的一律是引擎进程，因此不需要防火墙/局域网开放。

### 9.1 adb reverse 方向确认

AVD 无 `curl`/`wget`，改用 `toybox nc` 发原始 HTTP。注意 toybox 在 stdin EOF 时会半关闭连接、
丢掉响应，必须把 stdin 撑住：

```
$ adb shell "{ cat /data/local/tmp/req.txt; sleep 4; } | toybox nc 127.0.0.1 8730"
HTTP/1.1 200 OK
date: Sun, 01 Nov 2026 03:18:23 GMT
server: uvicorn
x-protocol-version: 0.1
content-length: 78
content-type: application/json
Connection: close

{"status":"ok","version":"0.1.0","protocolVersion":"0.1","uptimeSeconds":1651}
```

引擎侧同刻访问日志（来源是 reverse 后的回环，证明方向是设备→PC）：

```
INFO:     127.0.0.1:5212 - "GET /v1/health HTTP/1.1" 200 OK
```

客户端 UI 同步显示 `已连接 · 引擎 0.1.0 · 协议 0.1`。

### 9.2 真实格式列表（真机）

任务 `01a19518-0e1d`（`/page-nocookie.html?media_delay=5000`）真机嗅探卡片：
`hls · unknown` / `无 DRM` / `m3u8 · 0p · 0 kbps` / `http://127.0.0.1:8731/media/hls/master.m3u8?delay_ms=5000`。
点「选择画质并下载」后引擎 `POST /v1/tasks → 201`、`GET /v1/tasks/{id}/formats → 200`，
UI 列出真实 yt-dlp 格式：`音频 · mp4 · 0` / `纯音频 · 0 kbps · none/none`（截图 `.screenshots/m4a-8-formats.png`）。

### 9.3 两次任务 Cookie 不共享（隔离性）

同一 `master.m3u8`，任务 A `01a19511-ceee`（`/page.html?sid=probe-1`，页面写 Cookie）
与任务 B `01a19513-ca9f`（`/page-nocookie.html`，页面不写 Cookie）：

```
media_req t=11:05:34.986 path=/media/hls/master.m3u8 client=playwright cookie_present=true  range_present=false delay_ms=0 ua=...
media_req t=11:07:44.805 path=/media/hls/master.m3u8 client=playwright cookie_present=false range_present=false delay_ms=0 ua=...
```

两次 `sniff_context_open/close` 之间无重叠（A：34,713→35,814；B：44,683→45,624），
即每任务独立 `browser.new_context()`，Cookie 不跨任务。
媒体服务器只打 `cookie_present` 布尔，Cookie 原值不进任何日志（见 9.6）。

### 9.4 SSRF 白名单：每次放行都有日志，且未列入的主机仍然被拒

本次 real 运行 `ssrf_allowlist_bypass` 共 37 行，与引擎每次对 `127.0.0.1` 出站校验一一对应：

```
2026-11-01 11:13:24,896 bgv.engine INFO ssrf_allowlist_bypass host=127.0.0.1
INFO:     127.0.0.1:14223 - "POST /v1/tasks HTTP/1.1" 201 Created
```

白名单不是总开关——同一次运行里对私网 `10.0.0.1` 的请求仍被拒（host 级精确匹配，单测
`test_exact_host_match_only` / `test_cidr_entry_is_not_a_segment` 同步覆盖）：

```
$ curl -X POST http://127.0.0.1:8730/v1/tasks --data '{"sourceUrl":"http://10.0.0.1/x.html",...}'
{"error":{"code":"SSRF_BLOCKED","category":"security","message":"目标地址指向受限网络，已拒绝","retryable":false,"detail":{"host":"10.0.0.1"}}}

2026-11-01 11:18:35,944 bgv.engine INFO ssrf_blocked host=10.0.0.1 reason=blocked_ip
INFO:     127.0.0.1:11907 - "POST /v1/tasks HTTP/1.1" 400 Bad Request
```

### 9.5 取消：真机点按 → `POST /cancel` → `cancelled`

下载任务 `01a19518-fbbe`（`downloading`，播放列表被 `?delay_ms=5000` 拖慢留出点按窗口）：

```
INFO:     127.0.0.1:10273 - "POST /v1/tasks/01a19518-fbbe-73f8-9c1c-efafc7245672/download HTTP/1.1" 202 Accepted
INFO:     127.0.0.1:10273 - "POST /v1/tasks/01a19518-fbbe-73f8-9c1c-efafc7245672/cancel HTTP/1.1" 200 OK
```

取消后 `GET /v1/tasks/{id}`：

```
{"id":"01a19518-fbbe-73f8-9c1c-efafc7245672","status":"cancelled",
 "progress":{"phase":"downloading","bytesDownloaded":0,...},"error":null}
```

真机 UI 从 `下载进度 / downloading / 取消下载` 变为 `任务已取消`
（截图 `.screenshots/m4a-9-downloading.png`、`.screenshots/m4a-10-cancelled.png`）。

终态守卫同时被验证：取消后 yt-dlp 仍抛了 `DownloadError`，引擎打了
`download_failed task_id=01a19518-fbbe... type=DownloadError`，但任务状态保持 `cancelled`、
`error` 保持 `null`，没有被 `failed` 覆盖。

**边界（对应 §8.2）**：本次取消发生在播放列表请求被服务端 sleep 的阶段，
`bytesDownloaded=0`，没有真实字节流被中断；「取消后线程仍在跑、字节仍可能继续落盘」
仍需 4b 的协作式断流 + 真实字节流证据来关闭。

### 9.6 日志脱敏复核

```
$ grep -c "cookie" engine.log   → 0     # 引擎日志不含 Cookie 字样/值
$ grep -c "sid="   engine.log   → 0     # 敏感 query 参数未被记录
$ grep -c "cookie=" media.log   → 0     # 媒体服务器只打 cookie_present
```

`media_req` 的 `path` 只取 `urlsplit().path`，query（含 `sid`）不入日志。

### 9.7 归因实验：3 条「Chrome UA 但非 HeadlessChrome」的请求

真机日志里每次嗅探后固定出现 3 条 `client=browser` 的 `master.m3u8` 请求，UA 被截断显示，
无法与 Playwright 区分。离线复现（同一 fixture，直接调 `formats_service.extract_info`，
不经过浏览器）得到完全相同的 3 条：

```
REQ /media/hls/master.m3u8 client=browser ua=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36   ×3
```

即这 3 条来自 yt-dlp 的格式探测（`yt_dlp/utils/networking.py` 的
`USER_AGENT_TMPL = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) ... Chrome/{}.0.0.0 Safari/537.36'`），
不是第二个浏览器上下文。Playwright 的 UA 含 `HeadlessChrome`，归类为 `client=playwright`。

### 9.8 新发现（两条都已修：1 见 9.9，2 见 §10）

1. **`audio_only → bestaudio` 对「编解码未知」的 HLS 变体无匹配**（已修）。
   本 fixture 的 master 只有一条变体，yt-dlp 探测前 `vcodec/acodec/height` 均为 `null`：
   `extract_info` 返回 `format_id="0", ext="mp4", vcodec=null, acodec=null`；
   离线选择器实测 `best → ['0']`、`bestaudio → 无匹配`。
   客户端 `FormatSelection.selectorFor` 以 `!hasVideo` 判「纯音频」，于是未知编解码被降级成
   `audio_only`，真机下载在 yt-dlp 侧报 `ERROR: [generic] master: Requested format is not available`。
   下载链路本身是通的：同一 URL 用 `format_expression="best"` 走 `download_media` 成功产出
   `master.mp4`（61,659 B，落在 `BGV_OUTPUT_DIR_ALLOWLIST` 内）。
   **裁决：只修这一条，`max_height` 降级另做**（两个 bug 根因不同，塞进同一改动会污染归因）。
   修复见 9.9 的四规则映射与真机 done 证据。
2. **`max_height` 映射 `best[height<=N]` 对「画质未知」的格式表会静默无匹配**（已修，见 §10）。
   原文曾记为抛 `KeyError 'incomplete_formats'`，**该结论作废**：KeyError 是我手工
   `build_format_selector(...)` 时 ctx 少了 `incomplete_formats` / `has_merged_format` 两个键造成的
   测量伪影（`YoutubeDL.py:2307-2312` 的 ctx 契约），不是 yt-dlp 在真实调用里的行为。
   按真实契约重测：单条 `height=None` 变体与「所有 height 都 > N」两种情形**都不抛 KeyError**，
   而是选择器返回 `False` → 冒到 `DownloadError: Requested format is not available`（§10.1 有本轮现场复跑输出）。
   后果比抛错更糟：异常发生在下载阶段而不是解析阶段，客户端只能收到一个说不清原因的失败。
   4b 裁决：按场景分两条路径 —— 元数据不完整降级 `best` 并记审计，元数据完整但无匹配则
   `FORMAT_UNAVAILABLE` + `detail{requested, available}` 明确失败，不猜。

### 9.9 修复轮一：客户端映射四规则 + 真机跑到 done（2026-11-01，4b 前置修复）

#### 9.9.1 四条映射规则的落地

映射改成四条（[FormatSelection.kt](file:///E:/code/android/BrowseGuardVid/android/app/src/main/java/com/hpu/browseguard/data/FormatSelection.kt)），判定顺序固定：
先认「两个编码都是 `none`」，再认 `hasVideo && hasAudio`，再认「显式有音 + 显式无画」，剩下才是不一致的兜底。

| 条件 | 映射 |
|---|---|
| `codec.video=="none" && codec.audio=="none"`（元数据未知） | `best` |
| `hasVideo && hasAudio` | `max_height` + `preferCodec`（不变） |
| `hasAudio && !hasVideo`（收紧：显式有音、显式无画） | `audio_only` |
| 其余（仅 `hasVideo`，含元数据自相矛盾） | `best` + `Log.w selector_degraded` |

引擎把 yt-dlp 的 `null` / `"none"` 统一序列化成 `"none"`、未知画质序列化成 `0`
（[services/formats.py:251](file:///E:/code/android/BrowseGuardVid/engine/services/formats.py#L251)），
所以「未知」在客户端就是 `video=="none" && audio=="none"`。

单测：`FormatSelectionTest` 9 → 13 条（四种组合各一条 + fixture 场景 `formatId="0"`、
`vcodec/acodec/height` 全未知断言映射为 `best`），`:android:app:testDebugUnitTest` 34 条全绿。

为让 `Log.w` 分支能在 JVM 单测里跑到，`app/build.gradle.kts` 加了
`testOptions { unitTests.isReturnDefaultValues = true }`，并在原地写清约束：
**仅为 `android.util.Log.*` 服务；依赖 Android API 行为的单测必须显式 mock（或走 androidTest），
不得把默认返回值当断言依据**；属权宜，M3 收尾评估 mockk / Robolectric 替代（§8 第 4 条）。

#### 9.9.2 真机 done 证据链

设备 `emulator-5554`，真实点按格式列表第一条（fixture 的未知编解码变体）→「下载」。
任务 `01a19526-d064-7ddc-b265-77c48c188ad0`，引擎日志：

```
2026-11-01 11:29:39,311 bgv.engine INFO download_attempt task_id=01a19526-d064-7ddc-b265-77c48c188ad0
INFO:     127.0.0.1:8786 - "POST /v1/tasks/01a19526-d064-7ddc-b265-77c48c188ad0/download HTTP/1.1" 202 Accepted
2026-11-01 11:29:40,321 bgv.engine INFO download_complete task_id=01a19526-d064-7ddc-b265-77c48c188ad0 bytes=61659
```

`GET /v1/tasks/{id}` 实测返回（不是桩值，桩值恒为 524288000）：

```
{"status":"done",
 "progress":{"phase":"done","bytesDownloaded":61659,"bytesTotal":61659,"percent":100.0,"speedBps":0,"etaSeconds":0},
 "error":null}
```

产物落在 `BGV_OUTPUT_DIR_ALLOWLIST` 目录内（非白名单目录会被 `prepare_output_dir` 拒绝）：

```
C:\Users\19564\Videos\BGV\master.mp4   61659 B   sha256(前16)=ef61c22dba4e19ec
```

真机 UI 同步显示「下载完成 / 输出目录 C:\Users\19564\Videos\BGV / 已下载 60.2 KB」
（截图 [.screenshots/m4b-1-done.png](file:///E:/code/android/BrowseGuardVid/.screenshots/m4b-1-done.png)）。

下载阶段媒体请求全部由引擎进程发出、不带 Cookie（该页不写 Cookie 且未设 `BGV_COOKIE_FILE`）：

```
media_req t=11:29:39.581 path=/media/hls/master.m3u8 client=browser cookie_present=false range_present=false
media_req t=11:29:39.678 path=/media/hls/seg0.ts     client=browser cookie_present=false range_present=false
```

**遗留**：格式行文案仍按 `!hasVideo` 显示「纯音频」，而映射已不再把它当纯音频 —— 这是**契约不一致**
（界面告诉用户的事和点下去发生的事不同源），不只是 UI 文案，因此排在 4b 第一位修，见 9.10。

### 9.10 修复轮二：4b-1 列表文案与点击行为同源（2026-11-01）

裁决口径：文案按**映射结果**显示，不按 `hasVideo` 推断；列表文案与点击后的行为必须同源。

代码落点：
- [FormatSelection.kt](file:///E:/code/android/BrowseGuardVid/android/app/src/main/java/com/hpu/browseguard/data/FormatSelection.kt)
  新增 `enum class Kind { UNKNOWN_METADATA, VIDEO_AND_AUDIO, AUDIO_ONLY, VIDEO_ONLY }` 与 `kindOf(format)`；
  `selectorFor` 改为按 `kindOf` 分支（判定逻辑与修好的四条规则等价），另出 `preferCodecLabel()`
  把 `PreferCodec` 映射成与协议线名一致的展示串（`h264` / `h265` / `av1` / `vp9`）。
- [HomeScreen.kt](file:///E:/code/android/BrowseGuardVid/android/app/src/main/java/com/hpu/browseguard/ui/HomeScreen.kt)
  的 `formatKindLabel` / `formatSummary` 都改成接收同一个 `kind`，界面不再自行从 `hasVideo` 推断。
- [strings.xml](file:///E:/code/android/BrowseGuardVid/android/app/src/main/res/values/strings.xml)
  增「最佳可用（画质未标注）」/「视频（无音轨）」/「画质与编码待引擎解析」，删掉已无引用的 `format_audio_label`。

四条文案与行为对照：

| Kind | 首行文案 | 点下去实际发送 |
|---|---|---|
| `UNKNOWN_METADATA` | 最佳可用（画质未标注） | `best` |
| `VIDEO_AND_AUDIO` | `{height}p · {h264/h265/av1/vp9}`（编码名未识别时只剩 `{height}p`） | `max_height` + `preferCodec` |
| `AUDIO_ONLY` | 纯音频 | `audio_only` |
| `VIDEO_ONLY` | 视频（无音轨） | `best` + `Log.w` |

次行只列真实拿到的元数据：`height` / `bitrate` / 体积各自 `>0` 才渲染，元数据未知时整行改显示
「画质与编码待引擎解析」，不再出现 `0×0 · 0 kbps · none/none` 这种假精确。

单测：`FormatSelectionTest` 13 → 15 条 —— `kindDrivesStrategyForAllFourShapes`（同一批格式断言
`kindOf` 与 `selectorFor.strategy` 成对相等，锁死「同源」）与
`preferCodecLabelsMatchProtocolWireNames`（展示串 == 协议 `@SerialName`）；
`:android:app:testDebugUnitTest :android:app:assembleDebug` BUILD SUCCESSFUL，36 条单测 0 失败。

真机取证（引擎 real 模式 + 三门控白名单 + `adb reverse`，任务 `01a19532-3a1f-7b4f-a243-88f0ad9697bf`）。
格式列表首行（`uiautomator dump` 原文，fixture 的未知编解码变体）：

```
最佳可用（画质未标注） · mp4 · 0
画质与编码待引擎解析
```

同一次运行点「下载」后的回归（改动没让 done 退化）：

```
2026-11-01 11:41:29,451 bgv.engine INFO download_attempt task_id=01a19532-3a1f-7b4f-a243-88f0ad9697bf
INFO:     127.0.0.1:6256 - "POST /v1/tasks/01a19532-3a1f-7b4f-a243-88f0ad9697bf/download HTTP/1.1" 202 Accepted
2026-11-01 11:41:29,477 bgv.engine INFO download_direct_start ... cookie_header_present=false
2026-11-01 11:41:30,289 bgv.engine INFO download_complete task_id=01a19532-3a1f-7b4f-a243-88f0ad9697bf bytes=61659
```

截图 [.screenshots/m4b-2-labels.png](file:///E:/code/android/BrowseGuardVid/.screenshots/m4b-2-labels.png)：
新文案 + 状态「下载完成」+ 输出目录与已下载字节。

**边界声明**：本轮只取证了 `UNKNOWN_METADATA` 一条文案。真机跑不出带画质的变体是本机 fixture 的限制
（`ffmpeg` 出的 HLS 只有一条无 `RESOLUTION` 的媒体播放列表，yt-dlp 探测到的 `height` 恒为未知），
不是代码问题；`720p · h264` 这类文案的**渲染**留到 4b-3（同样要上真机）用多档变体素材一并取证，
其映射与展示串本身已由 15 条单测覆盖。

### 9.11 收尾

4a 结束后：停掉带 `BGV_SSRF_ALLOWLIST` / `BGV_DEV_ALLOW_SSRF_BYPASS` 的引擎进程（两个 env 随之消失，
白名单代码保留且默认不注册），并 `adb reverse --remove tcp:8730`。

## 10. M3 4b-2：`max_height` 降级，两条路径分开（2026-11-01，引擎侧）

裁决口径：**不是二选一，按场景分**。元数据不完整 → 降级 `best` 并留审计；元数据完整但无匹配 →
`FORMAT_UNAVAILABLE` + `detail{requested, available}` 明确失败，不猜。两条代码路径分开写，单测各覆盖一条。

### 10.1 前提复跑：`best[height<=N]` 在真实调用里不抛错，而是静默无匹配

9.8-2 已作废原来的 `KeyError` 结论。真实行为本轮现场复跑（本地 fixture，画质未知的单变体媒体列表）：

```
$ python -c "import yt_dlp; yt_dlp.YoutubeDL({'format':'best[height<=720]','skip_download':True})
             .extract_info('http://127.0.0.1:8741/media/hls/master.m3u8', download=False)"
ERROR: [generic] master: Requested format is not available. Use --list-formats for a list of available formats
counterfactual: DownloadError ERROR: [generic] master: Requested format is not available ...
```

即：yt-dlp 不抛 `KeyError`，是选择器返回 `False` → 冒到 `DownloadError`，**而且冒在下载阶段而不是解析阶段**，
客户端只能收到一个说不清原因的失败。所以判定必须发生在调用下载器之前，用引擎已经拿到的格式表来判。

### 10.2 代码：两条路径

- [services/formats.py](file:///E:/code/android/BrowseGuardVid/engine/services/formats.py)
  - `resolve_format_expression(selector, formats) -> ResolvedFormat(expression, degraded_reason)`：
    只处理 `max_height`，其余策略原样返回；表里任一 `height<=0` 或整表缺失 → `("best", "incomplete_metadata")`；
    全部已知且 `min(heights) > N` → 抛 `FormatNoMatchError`；否则原表达式下发。
  - `FormatNoMatchError(requested, available).detail` = `{reason: no_height_within_cap, requested, available}`，
    直接进错误信封的 `detail`。
- [main.py](file:///E:/code/android/BrowseGuardVid/engine/main.py)
  - `_download_real`：降级分支打 `logger.warning("selector_degraded task_id=%s reason=%s requested=%s")`
    后继续跑（任务不失败）；`FormatNoMatchError` → `_fail_task(FORMAT_UNAVAILABLE, _format_no_match_detail(exc))`。
  - `POST /v1/tasks/{id}/download`（real 模式预校验）：`FormatNoMatchError` → 422，同样的 detail。
  - `_FORMAT_UNAVAILABLE_MESSAGES` 增 `no_height_within_cap: 上限内没有可用画质`。

### 10.3 单测：real 83 → 94

- `tests_real/test_stage3_units.py` 增 8 条（两条路径各覆盖，外加边界口径）：上限可满足时表达式不变、
  未知画质降级 + `incomplete_metadata`、格式表为 `None` / `[]` 同样降级、**混合表（已知 720 + 未知）仍降级**
  （未知那条可能本就 ≤N，判不出「一定没有」，所以不用已知子集去判无匹配）、
  无匹配抛 `FormatNoMatchError` 且 detail 带 `requested` / 升序去重的 `available`、
  `best` / `audio_only` 不被改写也不记降级（参数化 2 条）。
- `tests_real/test_api_wiring.py` 增 3 条：worker 降级到 `best` 且断言 WARNING 行**整行原文**
  （`selector_degraded task_id=task-real reason=incomplete_metadata requested=720`）+ 任务 `done` + `error is None`；
  worker 无匹配 → `FAILED` + `FORMAT_UNAVAILABLE` + 完整 detail，且下载器被替换成「一调用就 AssertionError」；
  `POST /download` 无匹配 → 422 + 状态仍 `awaiting_selection` + `DOWNLOAD_COUNTER.count == 0`。
  原有的「上限可满足」用例补上 `record.formats = [720, 1080]`，否则它现在会走降级分支——用例改成显式声明前提。
- 双套件本轮现场复跑：`pytest`（桩）150 passed / `pytest -m real` 94 passed。

### 10.4 真链路取证（引擎 real 模式 HTTP 端点）

环境：引擎 `127.0.0.1:8740`（`BGV_ENGINE_MODE=real` + 三门控 `BGV_SSRF_ALLOWLIST=127.0.0.1` +
`BGV_OUTPUT_DIR_ALLOWLIST=D:\tmp\bgv4b2\out`）、媒体服务器 `127.0.0.1:8741`。
两种 fixture 正好对应两条路径：
`master.m3u8` 是单条**无 `RESOLUTION`** 的媒体列表 → 格式表 `height=0`（元数据不完整）；
本轮另造 `multi.m3u8`（`#EXT-X-STREAM-INF` 两档，`RESOLUTION=1280x720` / `1920x1080`）→ 格式表画质齐全。
多档 manifest 与分片副本只写在临时素材目录，没进仓库，避免把非生产形态的素材固化成默认 fixture。
取证结束后 8740 / 8741 两个进程已停掉（`BGV_SSRF_ALLOWLIST` / `BGV_DEV_ALLOW_SSRF_BYPASS` 随引擎进程消失，
白名单代码保留且默认不注册）；本轮没上设备，故无 `adb reverse` 需要回收。

**S1 元数据不完整 → 降级 `best`，任务不报错**（`01a1953e-91e7-7a10-8a52-9c8e73efdc13`，
`mode=download` + `max_height 720`，引擎自动领取）：

```
11:54:31,021 bgv.engine INFO    download_attempt task_id=01a1953e-...
11:54:31,022 bgv.engine WARNING selector_degraded task_id=01a1953e-... reason=incomplete_metadata requested=720
11:54:32,055 bgv.engine INFO    download_complete task_id=01a1953e-... bytes=61659

{"status":"done","progress":{"phase":"done","bytesDownloaded":61659,"bytesTotal":61659,"percent":100.0},"error":null}
out/A/master.mp4  61659 B  sha256(前16)=ef61c22dba4e19ec
```

**S2 上限可满足 → 表达式原样下发、没被过度降级**（`01a1953f-cb53-7494-aea0-2b5555e7578f`）：
`download_attempt` → `download_complete bytes=61659`、状态 `done`，日志里该任务**没有** degraded 行；
更强的证据是下载器实际取了哪一档 —— 媒体服务器日志里 `client=browser`（即 yt-dlp，见 9.7 的 UA 归因）
只请求 `h720.m3u8`，`h1080.m3u8` 的三条请求全是 `client=playwright`（嗅探阶段），
`grep h1080 media.log | grep -c 'client=browser'` = **0** → `best[height<=720]` 真被 yt-dlp 采纳。

**S3 无匹配（worker 路径）→ 明确失败**（`01a1953f-492a-7000-8243-288fc34e689b`，`max_height 480`）：

```
{"status":"failed",
 "error":{"code":"FORMAT_UNAVAILABLE","category":"content","message":"所选画质不可用或超出体积上限",
          "retryable":false,
          "detail":{"reason":"no_height_within_cap","requested":480,"available":[720,1080],
                    "message":"上限内没有可用画质"}}}
```

`out/B1` 目录**根本没被创建**（下载器没被调用），日志里该任务只有 `download_attempt`、无 `download_complete`。

**S4 无匹配在 API 入口就被拦**（`01a19540-bff9-74c0-b286-80125f932b22`）：先 `GET /formats`
（`5000/720` 与 `9000/1080` 两条，编码 `avc1.640028` / `mp4a.40.2`），再 `POST /download` 带 `max_height 480`：

```
HTTP/1.1 422 Unprocessable Entity
x-protocol-version: 0.1
{"error":{"code":"FORMAT_UNAVAILABLE","category":"content","message":"所选画质不可用或超出体积上限",
 "retryable":false,"detail":{"reason":"no_height_within_cap","requested":480,"available":[720,1080],
 "message":"上限内没有可用画质"}}}
```

紧接着 `GET /v1/tasks/{id}`：`status=awaiting_selection`、`formatSelector=null`（422 不改状态、不落选择器）。
同一任务随后改 `max_height 720` → `202 Accepted` → `done`、61,659 B（拒绝一次不影响后续正常领取）。

整场取证 `selector_degraded` 只出现 **1 次**（`grep -c` = 1，即 S1），降级没有被用成万能回落。

### 10.5 边界与遗留（本轮主动声明）

1. **本轮没有真机**。设备端映射（9.9 四规则）对未知画质本来就发 `best`，不可满足的 `max_height`
   只能由直接调协议端点的调用方发出，所以取证走引擎 HTTP 端点。带已知画质的界面文案（`720p · h264`）
   在真机上的渲染仍然缺，留给 4b-3（那轮本来就要上真机）。
2. **`detail 记 warning` 落不了地**（§8 第 5 条）：`Task` 只有 `error`，没有 warning 槽位。
   现在是引擎 WARNING 审计行，任务对象本身看不出「被降级过」；要可见就得动协议
   —— 已按 4b-2 验收裁决记进 M3 待办（唯一向后兼容做法 = `Task` 增可选 `warnings` 字段，先开 PROTOCOL-CHANGELOG）。
3. **`download_attempt` 审计行打太早**：`main.py:886` 在 `POST /download` 入口第一行就打，
   于是 DRM 拒绝、409 状态冲突、422 映射拒绝都会留下一条 attempt 行（S3/S4 日志里能看到）。
   它与协议 §10「下载尝试可证」的语义有偏差 —— 更像「请求到达」而不是「真的开始下载」。
   本轮未改：改它会移动 §10 审计语义，属协议讨论项，且会牵动既有取证脚本的 grep 口径
   —— 已按裁决记进 §8-6，M3 收尾连同是否补独立 `download_rejected` 一并讨论。

## 11. M3 4b-3：协作式断流（2026-11-01，两条代码路径 + 真机字节流中断）

§9.5 那次取消发生在服务端 sleep 阶段，`bytesDownloaded=0` —— 真实字节流从没被中断过，
「取消后线程仍在跑、字节仍可能继续落盘」这条边界当时是开着的。本轮关闭它：
下载循环每写一块之前查一次取消标志，两条路径（httpx 直链 / yt-dlp 媒体）分开实现、分开取证。

### 11.1 语义与代码落点

**一个标志，端点置、循环读**：`TaskRecord.cancel_requested = threading.Event()`（`main.py:200`）。
`POST /v1/tasks/{id}/cancel` 的顺序是「置标志 → 打审计行 → 落 `cancelled`」（`main.py:995-997`）——
标志先置，worker 在状态落地之前就能停手；`_fail_task` 的终态守卫（`main.py:382`）保证之后任何失败都不覆盖 `cancelled`。

| 路径 | 检查点 | 位置 | 命中后 |
| --- | --- | --- | --- |
| 直链 httpx | `for chunk in iter_bytes()` 里**写盘之前** | `downloader.py:163-167` | 抛 `DownloadCancelled("... aborted after N bytes")`，`.part` 原样保留、不改名 |
| HLS/DASH yt-dlp | `progress_hooks` 第一句 | `downloader.py:219-224` | 置 `abort_signalled` + 抛 yt-dlp 自己的 `DownloadCancelled` 让它立刻停手 |

yt-dlp 分支的判定刻意不看异常类型、只看外层 `abort_signalled`（`downloader.py:265-266`）：
yt-dlp 会把 hook 抛出的异常包成通用 `DownloadError`（`ERROR: interrupted by user`），
按类型判定会在它换包装时静默失效。`progress_hooks` 只在真的传了回调时才注入
（`downloader.py:238-239`），默认下载路径的 options 与本轮之前完全一致。

`_download_real` 侧三道守卫，`DownloadCancelled` 的 except 排在最前（`main.py:347-351`）：

```
入口   download_skipped_cancelled  取消落在 202 之后、下载起跑之前  main.py:296-298
命中   download_aborted            循环/hook 里断流，只留字节证据    main.py:348-351
收尾   download_cancelled          取消落在「刚下完、终态还没落」之间 main.py:364-367
```

三处都**不改状态**（终态已由 cancel 端点落定），只留可 grep 的行。
进度由 `_make_progress_recorder`（`main.py:227-255`）透传：0.5 s 滚动窗口算速度，
`total` 未知时 `percent` 保持 0（不猜）。

### 11.2 单测：real 94 → 109（现场复跑）

```
$ .venv/Scripts/python.exe -m pytest -q            → 150 passed, 109 deselected
$ .venv/Scripts/python.exe -m pytest -q -m real    → 109 passed, 150 deselected
$ ./gradlew :android:app:testDebugUnitTest --rerun → 36 tests, 0 failures（:shared:jvmTest 9 tests 同样全绿）
```

Kotlin 侧本轮零改动，复跑只是回归基线。新增 15 条 real 用例分三组：

- `tests_real/test_download_cancel.py`（8 条）—— 断流语义本体：
  命中后 `.part` 存在且 `0 < 大小 < 全长`、最终文件不存在、异常消息带真实字节数；
  首块之前就命中 → `.part` 长度 0；不传 `should_abort` → 正常下完；
  hook 异常被 yt-dlp 包装 → 仍判取消且取消前的进度已透传；
  **不带取消的通用失败仍必须是通用失败**（不能被吞成 cancelled）；
  不传回调 → options 里没有 `progress_hooks`；断流审计行只含 task_id + 字节数
  （`http://` / 主机 / `cookie` / `Mozilla` 全部不出现）。
  块大小取 70 000 B（大于 httpx 的 64 KiB 合并阈值），否则一次 `iter_bytes` 就吐完、测不到「块之间」命中。
- `tests_real/test_api_wiring.py`（4 条）—— 接线证明：假下载器只会调用 `main.py` 传进来的
  `should_abort`，标志没接上就永远等不到取消；11.1 的三道守卫各一条 + 进度窗口一条。
- `tests_real/test_media_server.py`（3 条）—— 本轮为取证给测试服务器加的限速旋钮（见 11.4 环境）。

**主动声明**：改 `page.html` 的播放列表拼接（`?media=slow` 变体）打破了该文件两条既有断言
（原来断整条字面 URL `/media/hls/master.m3u8`），已改成断模板片段（`"/media/hls/"` + `|| "master"`），
不是把断言删掉。

### 11.3 PC 预演（先确认时序量级，再上真机）

同一套素材先在 PC 上跑通两条分支（引擎 `127.0.0.1:8730` + 媒体 `8731`，走 HTTP 端点，不经设备）：

| 分支 | 任务 | `cancel_requested` → `download_aborted` | 断流字节 |
| --- | --- | --- | --- |
| 直链 httpx | `01a1957d-53c9` | 13:03:29,411 → 13:03:29,618（207 ms） | 1,966,080 B = 30 × 65,536 |
| HLS yt-dlp | `01a19580-20a4` | 13:06:41,266 → 13:06:41,420（154 ms） | 冻结在 948,102 / 4,897,998 |

预演的价值就在时序：两条分支的停手都发生在**下一个检查点**（下一块 / 下一次 hook 回调），
而不是等整段下完，量级在几百毫秒以内。

### 11.4 真机取证（emulator-5554，两条分支各一次）

环境：引擎 `127.0.0.1:8730`（`BGV_ENGINE_MODE=real` + 三门控 `BGV_SSRF_ALLOWLIST=127.0.0.1` +
`BGV_OUTPUT_DIR_ALLOWLIST=C:\Users\19564\Videos\BGV;D:\tmp\bgv4b3\out`）、
媒体服务器 `127.0.0.1:8731`、`adb reverse tcp:8730 / tcp:8731`。
素材是本轮新造的「够长」样本：`bulk.mp4` 4,218,400 B（60 s，单文件直链）与
`hls/slow.m3u8`（6 片、每片每块 `?chunk_delay_ms=700`）。
服务器旋钮区分得很清楚：`delay_ms` 只拖**响应头之前**（§9.5 那种「取消时还没开始传」的场景），
`chunk_delay_ms` 按 64 KiB **块间**限速，才会出现「字节流中间被切断」。

界面每一步都是真点按（`input tap`），任务由 App 自己创建。
App 一次下载流程会产生两个任务：嗅探任务（`mode=sniff`，下结论即 `done`）与
真正下载任务（`mode=download` → `awaiting_selection` → `downloading`），下面记的是后者。

**D1 直链 / httpx 循环**（下载任务 `01a19586-d14d-733c`，页面 `page-bulk.html?chunk_delay=500`，
嗅探到 `http://127.0.0.1:8731/media/bulk.mp4?chunk_delay_ms=500`）：

```
≈13:20:06    真机 UI      "2.5 MB / 4.0 MB · 128.0 KB/s · 剩余 12s"（uiautomator 快照；截图 m4b3-2 是它前一刻：2.3 MB / 剩余 13s）
13:20:12.520 真机点按     取消下载
13:20:12,598 引擎         cancel_requested task_id=01a19586-d14d... from_status=downloading      （+78 ms）
13:20:13,071 引擎         download_aborted ... aborted after 3604480 bytes                        （+473 ms）
```

取消后 `GET /v1/tasks/{id}` 连续三次（13:20:24 / 13:20:28 / 13:20:32，跨 8 s）完全一致：

```
{"status":"cancelled","error":null,
 "progress":{"phase":"downloading","bytesDownloaded":3604480,"bytesTotal":4218400,
             "speedBps":131071,"etaSeconds":4,"percent":85.4}}
```

磁盘侧：`C:\Users\19564\Videos\BGV\bulk.mp4.part` = **3,604,480 B**，与最后一次上报字节
**逐位相等**（= 55 × 65,536，整块），最终文件 `bulk.mp4` 不存在。
真机 UI 卡片变为「任务已取消」（截图 `m4b3-3-direct-cancelled.png`）。
媒体服务器侧对应两条 `client=httpx` 请求：`range_present=true`（`bytes=0-0` 跳转探测）
与 `range_present=false chunk_delay_ms=500`（真正的字节流）。

**D2 HLS / yt-dlp 进度钩子**（下载任务 `01a19590-5fdf-7544`，页面 `page.html?media=slow`，
嗅探到 `http://127.0.0.1:8731/media/hls/slow.m3u8`，选择器 `best` → 走 `download_media`）：

```
13:24:43     引擎进度      1,538,732 / 4,478,592（34.4 %）；约 1 s 内的 UI 快照 1.3 MB / 5.8 MB · 92.3 KB/s · 剩余 50s
13:24:50.647 真机点按      取消下载                                （截图 m4b3-4 为点按前一刻：2.0 MB / 5.3 MB · 剩余 37s）
13:24:50,711 引擎         cancel_requested task_id=01a19590-5fdf... from_status=downloading   （+64 ms）
13:24:51,169 引擎         download_aborted ... aborted during yt-dlp download                 （+458 ms）
```

`cancel_requested` 之后 stdout 仍打出 `(frag 3/6)` 的 yt-dlp 行，紧跟着才断流 ——
说明停手发生在下一次 hook 回调，不是等 6 片全下完。取消后三次轮询（13:25:01 / 05 / 10）
状态与进度全部冻结在 `2,239,296 / 4,461,762`（50.2 %）、`error=null`，UI 同样「任务已取消」。

两条分支各一次真机取证，§9.5 留的「取消时字节流还没开始」边界就此关闭。

### 11.5 边界与遗留（本轮主动声明）

1. **断流粒度是块边界，不是任意字节**：直链最多一块（64 KiB）在途，HLS 是下一个 hook tick
   （本轮实测 154 ms / 458 ms）。这是刻意的——绝不在 `handle.write()` 中途截断，
   所以 `.part` 永远是整块的拼接，4b-4 续传拿它当偏移才安全。
2. **冻结的进度仍是 `phase=downloading` 的最后一帧**：`cancelled` 任务没有 done/收尾相位，
   `Task` 也没有 `warning`/`cancelReason` 槽位，界面靠 `status` 字段显示「任务已取消」。
   与 §8-5、§10.5-2 是同一处协议缺口（`Task.warnings` 已记 M3 待办）。
3. **yt-dlp 的上报字节 ≠ 磁盘字节**：D2 磁盘上 2,174,784 + 130,048 = 2,304,832 B，
   比冻结上报的 2,239,296 多恰好 65,536 B（一块缓冲在取消之后才落盘）。
   直链路径因为 `on_progress` 在写之后调用，所以逐位相等（D1 已证）。
   → 4b-4 断点续传的偏移必须以 `.part` 实际大小为准，不能信上报值。
4. **yt-dlp 在输出目录留三件**：`slow.mp4.part`、`slow.mp4.part-Frag4.part`、`slow.mp4.ytdl`。
   `.ytdl` 内容是 `{"downloader":{"current_fragment":{"index":3},"extra_state":{}}}`
   —— 只有分片序号，**没有 URL、没有 Cookie**（已核对）。但用户会在视频目录里看到散落文件，
   「续传利用它」还是「取消时清掉」属 4b-4 裁决，本轮不清（清了就没有续传素材）。
5. **yt-dlp 自身的 `[download] … (frag 3/6)` 行混进引擎 stdout**，干扰审计行的 grep 口径
   （本轮取证得靠时间戳对齐）。候选修法 `noprogress: True`，故意不和本轮一起改：
   它会动 yt-dlp options，塞进断流改动会污染归因。
6. **HLS 的 `bytesTotal` 抖动到 MB 级**：6,261,468 → 4,478,592 → 5.3 MB（截图）→ 冻结 4,461,762，
   导致 `percent` 非单调（8.4 % → 34.4 % → 50.2 %，中途会往回跳）；
   D2 里 1 s 内的两次读数差出 1.5 MB（UI 5.8 MB、API 4.3 MB），就是这个抖动被读出来的样子
   （根因是 yt-dlp 按已下分片估算总大小，不是引擎算错）。直链路径全程稳定（4,218,400）。
   属客户端显示层可读性问题，记 M3 待办候选。
7. **桩（stub）模式没有断流语义**：桩下载瞬时完成，取消只走状态机（§9.5 行为不变）；
   本轮改动全部落在 real 分支。
8. **测试媒体服务器仍会打 `[WinError 10053]` traceback**：客户端断连发生在服务器读请求行阶段，
   不在我们能 catch 的写块路径上（写块路径本轮已 catch `ConnectionError`）。只影响测试输出整洁，
   不影响生产代码。
9. **4b-x（纯音频封装 + 扩展名一致性）排在 4b-4 真机轮**，不插队。证据落点说明：
   裁决里写的「贴 §9.9 或新开 §9.10」两个编号都已被修复轮一/二占用，届时并入 4b-4 小节并回指本条。
10. 取证结束后回收：停掉带 `BGV_SSRF_ALLOWLIST` / `BGV_DEV_ALLOW_SSRF_BYPASS` 的引擎进程
    （两个 env 随之消失，白名单代码保留且默认不注册）、停 8731 媒体服务器、
    `adb reverse --remove tcp:8730 tcp:8731`。


## 12. M3 4b-4：断点续传 + 产物封装（2026-11-01，PC 六条 + 真机 R1–R4）

范围按裁决只做断点续传，纯音频封装作附属验收（同一轮真机顺手验）。

### 12.1 两条裁决的落点

**裁决①（只扫 `.ytdl`、不扫 `.part`）+ 补的防御**：续传偏移取磁盘 size 之后，
新增一道 Content-Type 门禁 —— 有 `.part` 残留时，先看终点声明的是不是媒体，
不是就拒绝续传，记 `resume_rejected task_id=... reason=unexpected_content_type`。
落点 [engine/services/downloader.py](file:///E:/code/android/BrowseGuardVid/engine/services/downloader.py)
的 `_is_media_content_type` / `_bare_media_type`（`download_direct` 开头引用），
规则写进 `download_direct` 的 docstring。
白名单而非黑名单：`video/*`、`audio/*`、`application/{octet-stream,mp4,mpegurl,x-mpegurl,dash+xml}`
放行，**没声明也放行**（没有声明就没有「这是错误页」的证据），显式的 `text/html` 才拦。
拒绝时抛 `DownloadIntegrityError` → 任务 `failed/PARTIAL_DOWNLOAD`，
`.part` 既不拼进产物也不清理，现场留给下一次人工判断。
配套给测试服务器加了个和 `no_range` 同族的旋钮 `?as_html=1`（只改 `Content-Type`、
不改路径与字节），这样同名 `.part` 照样命得中，门禁能在**真 HTTP 响应**上验一遍，
而不是只在 `MockTransport` 的形状上成立。

**裁决②（`?no_range=1` query 开关）**：刻意不用新路由，因为产物名由 URL 后缀推导——
新路由会得到不同 `.part` 名，命不中上一轮取消留下的残留，降级分支就走不到。
全仓 `no_range` grep 复核：只出现在 `testsupport/`（`media_server.py` 的 `_no_range`、
`media_fixtures.py` 的页面模板）与 `tests_real/`（用例与断言），生产路径零命中；
生产侧唯一含该字样的字符串是审计行里的 `reason=no_range`（`downloader.py`）。
新旋钮 `as_html` 同口径复核，结论一致。

### 12.2 PC 侧六条：单测 125 → 133（现场复跑 152 stub / 133 real）

| 验收 | 用例 | 关键断言 |
|---|---|---|
| 1 中断留 `.part` | `test_incomplete_206_keeps_part_for_next_resume` | 短读抛错后 `.part` 保留、`.mp4` 不存在 |
| 2 续传偏移取磁盘 size | `test_resume_offset_follows_part_size_after_hard_kill` | 上报值 == 落盘值 == 2×CHUNK，随后 `os.truncate` 砍掉 8 KiB 模拟「缓冲没落盘」，断言发出的 Range 是**砍后**的 size 且 ≠ 上报值 |
| 2′ 结构性钉子 | `test_download_direct_has_no_caller_supplied_offset` | `inspect.signature(download_direct)` 里不存在任何 offset/resume/downloaded/bytes 形参——上报值根本没有入口，想信也没有通道 |
| 3 续传完成完整性 | 同 2 用例尾段 | sha256 与一次性下载逐字节相等，总大小 == 源 |
| 4 降级全量重下 | `test_range_ignored_downgrades_to_full_download`（注入）+ `test_real_server_no_range_downgrades_to_full_download`（真服务器） | 审计行原文、`.part` 被 `wb` 重写、`server.request_log` 里确实发出过 `bytes=4096-` 且同一行 `no_range is True` |
| 4′ 续传 Content-Type 门禁（裁决①补的防御） | `test_download_resume.py` 六条（注入）+ `test_real_server_html_content_type_blocks_resume` + `test_media_server.py::test_as_html_query_rewrites_content_type_but_not_path`（真服务器） | 有残留 + `text/html` → 抛 `DownloadIntegrityError`、审计行原文（`content_type=text/html`，`charset` 尾巴不进日志）、**服务器只见过那次 `bytes=0-0` 探测**（拒绝在发续传请求之前）、`.part` 原样不拼不删；`video/mp4` / `application/octet-stream` / `application/mp4` / 无声明四种放行；无残留时门禁不生效（边界钉成断言，见 §12.8-3） |
| 5/6 纯音频封装与扩展名 | `tests_real/test_output_packaging.py` 七条 | 音频 mime + `.mp4` 后缀 → 校正为 `.m4a` 并打审计行；视频 mime 不动；`.m4a` + 视频 mime 保持 `.m4a`（单向规则）；无后缀 + `audio/aac` → `stream.aac`；`application/octet-stream` 且无后缀 → `SecurityError` 且目录为空；`codecs=` 参数尾巴不影响匹配；真服务器伪装路由落 `.m4a` |
| 残留卫生（裁决①配套） | `tests_real/test_ytdlp_leftovers.py` 三条 | 取消后残留名集合是 `{.part, .part-FragN.part, .ytdl}` 的子集；只对 `.ytdl` 做凭据 grep；`.ytdl` 顶层键 == `{downloader}`、内层键 ⊆ `{current_fragment, extra_state}`、出现 `url`/`filename` 立即红 |

`no_range` 那两条断言是按裁决⑤补强过的：原断言只查 sha 与审计行，
新断言额外从服务器侧证明「那个 200 是服务器忽略了 Range，不是客户端没发续传头」——
只增强，未删任何原断言。

### 12.3 真机取证环境

emulator-5554（1280×720 横屏），引擎 `127.0.0.1:8730`（`BGV_ENGINE_MODE=real` +
`BGV_SSRF_ALLOWLIST=127.0.0.1` + `BGV_DEV_ALLOW_SSRF_BYPASS=1` +
`BGV_OUTPUT_DIR_ALLOWLIST=C:\Users\19564\Videos\BGV;D:\tmp\bgv4b4\out`），
媒体服务器 `127.0.0.1:8731`（`--no-generate`，素材沿用 4b-3 那一份，保证 `bseg*.ts` 字节稳定），
`adb reverse tcp:8730 / tcp:8731`。任务一律由 App 自己点按创建（`mode=sniff` + `mode=download` 两条）。

服务器旋钮：`delay_ms` 只拖响应头之前；`chunk_delay_ms` 按 64 KiB 块间限速，才会出现「字节流中间被切断」；
`no_range=1` 让服务器**照旧接受请求但忽略 `Range` 头**，回 200 全量。

### 12.4 R1 直链续传：偏移只取磁盘 size

现场是 4b-3 §11.4 D1 取消留下的 `bulk.mp4.part` 3,604,480 B（按裁决「现场 `.part` 残留是好事，
4b-4 直接用」，本轮不重造）。页面 `page-bulk.html?chunk_delay=0`，
嗅探任务 `01a195e1-759e-7a64…`，下载任务 `01a195e2-441d-7aa8…`。

```
14:54:15,595 引擎      download_attempt task_id=01a195e2-441d...
14:54:15.823 服务器    path=/media/bulk.mp4 client=httpx range=bytes=0-0        （终点探测）
14:54:15.987 服务器    path=/media/bulk.mp4 client=httpx range=bytes=3604480-   （续传请求）
14:54:15,998 引擎      direct_download_complete task_id=01a195e2-441d... bytes=4218400
```

续传请求的起始偏移 3,604,480 正是磁盘上 `.part` 的 size；产物 sha256
`e5a5923deda3b299…e410fc87` 与源文件、与一次性下载逐字节相同。

**这条验的边界要写清**：它证明的是「续传偏移取自磁盘实际大小、并且续传成功」，
**不是**「复现上报值与磁盘值差 64 KiB 的那个现场」——后者由 PC 的
`test_resume_offset_follows_part_size_after_hard_kill`（`os.truncate` 那一刀）覆盖，
真机不去凑那个时序，也没必要。

### 12.5 R2 降级：服务器忽略 Range → 全量重下

**A 造残留**：`page-bulk.html?chunk_delay=200`（约 13 s 下完），任务 `01a195ee-b966-7084…`
在 downloading 中点按取消：

```
15:07:06,377 引擎   cancel_requested task_id=01a195ee-b966... from_status=downloading
15:07:06,443 引擎   download_aborted ... aborted after 1245184 bytes
磁盘         bulk.mp4.part = 1,245,184 B = 19 × 65,536，逐字节等于源文件前缀
```

直链路径上报值与磁盘值相等（`on_progress` 在写之后调用，§11.5-3 已论证），
所以这里的 1,245,184 既是上报也是落盘。

**B 走降级**：同一页面加 `&no_range=1`（URL 路径不变 → 产物名不变 → 才命得上面那个 `.part`），
任务 `01a195f0-78cb-70c8…`：

```
15:08:56,794 引擎     download_attempt task_id=01a195f0-78cb...
15:08:56.986 服务器   path=/media/bulk.mp4 client=httpx range=bytes=0-0        range 探测
15:09:10.023 服务器   path=/media/bulk.mp4 client=httpx range=bytes=1245184-   ← 偏移正是 A 的磁盘 size
15:09:10,023 引擎     resume_unsupported task_id=01a195f0-78cb-70c8-9598-3a514f55eb22 reason=no_range
15:09:22,874 引擎     direct_download_complete task_id=01a195f0-78cb-70c8-9598-3a514f55eb22 bytes=4218400
```

降级后确实全量重下：产物 4,218,400 B、sha256 `e5a5923deda3b299…` 与源和 R1 一次性下载相同、
`.part` 被消费掉不再残留。**为什么 sha 才是决定性证据而不是 size**：若真按残留拼接，
1,245,184 + 2,973,216 也正好等于 4,218,400，光看总大小分不出「拼接」和「重写」；
sha 与源逐字节相等才证明前 1,245,184 字节没有被重复拼进去（`part.open("wb")` 从头重写）。

扩展名仍正确：终点 `video/mp4`，产物 `bulk.mp4`，无 `output_ext_corrected` 行。

**顺带量到一个真实开销**：`download_attempt` 到续传请求之间空了 13.04 s，
正好是 64.4 块 × 200 ms —— 也就是 `resolve_redirects` 那次 `Range: bytes=0-0` 探测
在忽略 Range 的服务器上**把整个 4,218,400 B 响应体读进了内存**才返回（`client.get()` 非流式）。
记 §12.8-2。

### 12.6 R3 HLS 续传：`.ytdl` + 分片数 + sha 三者对齐

**中断现场**（4b-3 §11.4 D2 取消，13:24 留下，本轮原样复用）：

```
slow.mp4.ytdl               69 B  {"downloader": {"current_fragment": {"index": 3}, "extra_state": {}}}
slow.mp4.part            2,174,784 B  = bseg0(657,060) + bseg1(751,624) + bseg2(766,100)，逐字节
slow.mp4.part-Frag4.part   130,048 B  = bseg3 的前 130,048 字节
```

`.ytdl` 说当前分片序号 3，`.part` 的 size 说前 3 片已拼完，两处互相印证。

**一次性对照**（真机同页同参 `page.html?media=slow`，任务 `01a195f4-6cb2-7f68…`）：

```
15:13:15,808 download_attempt → 15:14:02,056 download_complete bytes=4232461   用时 46.2 s
分片请求 6 次：bseg0 bseg1 bseg2 bseg3 bseg4 bseg5（均 range=-，全量）
产物 slow.mp4 4,232,461 B  sha256 4569b9316a257f617235b31d1f3c482c9aaad01fe1fb16d12032bb25b13c4a03
```

**续传**（把上面三件残留放回输出目录，任务 `01a195f6-7894-7ab4…`）：

```
15:15:29,953 download_attempt → 15:15:52,285 download_complete bytes=4232461   用时 22.3 s
分片请求 3 次：
  15:15:30.334  bseg3.ts  range=bytes=130048-   ← 正是 Frag4 临时文件的磁盘 size
  15:15:36.667  bseg4.ts  range=-
  15:15:44.393  bseg5.ts  range=-
产物 slow.mp4 4,232,461 B  sha256 4569b9316a257f617235b31d1f3c482c9aaad01fe1fb16d12032bb25b13c4a03
```

三者对齐：`.ytdl` 从分片 3 起 → 只发 3 次分片请求（6 → 3）→ 产物与一次性 sha 逐字节相同。
成功后 `.part` / `.part-Frag4.part` / `.ytdl` 由 yt-dlp 自己清掉，输出目录只剩 `slow.mp4`。
引擎全程没有读、改、清过这三件（裁决②的「yt-dlp 拥有自己的临时文件」）。

### 12.7 R4 纯音频封装：伪装 `.mp4` 落成 `.m4a`

页面 `page-audio.html` 的 `<video src="/media/audio_as_mp4.mp4">`——URL 后缀是 `.mp4`，
服务器按内容如实声明 `Content-Type: audio/mp4`（25,813 B，本机 ffmpeg 造的纯音频 M4A）。
嗅探任务 `01a195f8-744e-7648…`，下载任务 `01a195f8-cd09-71f0…`：

```
15:18:02,641 download_attempt task_id=01a195f8-cd09...
15:18:02,831 output_ext_corrected task_id=01a195f8-cd09-71f0-b6b1-c5bde6a0b1f3 from=mp4 to=m4a reason=audio_content_type
15:18:03,007 direct_download_complete task_id=01a195f8-cd09... bytes=25813
```

产物 `audio_as_mp4.m4a`，输出目录里**没有**任何 `.mp4` 与它对应。前 16 字节（十六进制 + ASCII 双栏）：

```
偏移   十六进制                                    ASCII
0x00   00 00 00 1C  66 74 79 70                   ....ftyp
0x08   4D 34 41 20  00 00 02 00                   M4A ....
```

`0x00–0x03` = box 长度 0x1C（28 B），`0x04–0x07` = `ftyp`，
`0x08–0x0B` = major brand `0x4D 0x34 0x41 0x20` = **`M4A `**（末尾一个空格），
`0x0C–0x0F` = minor version `0x00 0x00 0x02 00`。

ffprobe 流列表（证明无视频流）：

```
$ ffprobe -v error -show_entries stream=index,codec_type,codec_name -of default=noprint_wrappers=1 audio_as_mp4.m4a
index=0
codec_name=aac
codec_type=audio

$ ffprobe -v error -select_streams v -show_entries stream=index -of csv=p=0 audio_as_mp4.m4a
（空，0 条视频流）
```

### 12.8 边界与遗留（本轮主动声明）

1. **R1 只验「从磁盘 size 续传成功」**，不验 64 KiB 差异复现（该差异由 PC 的 `os.truncate`
   用例覆盖，见 §12.2 表内第 2 行）。真机去凑那个时序需要杀进程，收益不抵风险。
2. **Range 探测在不支持 Range 的服务器上会读完整响应体**：~~M4 候选~~
   **已升级为 P0 并于本轮之后修复，见 §13**（裁决：「这是防御缺口，不是性能问题」）。
3. **Content-Type 门禁只覆盖续传，不覆盖全新下载**：没有 `.part` 时终点就算声明 `text/html`
   也照样落成 `.mp4`（直链路径不做 ffprobe，只有 yt-dlp 路径做）。这条口子已按裁决改成
   `@pytest.mark.xfail(strict=True)` 断言应有行为（`test_fresh_download_should_refuse_non_media_body`），
   「记录但测试通过」的写法撤回；细节与转正流程见 §8-8。M4 评估：给直链也加媒体类型门禁或 ffprobe。
4. **扩展名校正在前，`.part` 命中在后**：`.part` 名由**校正后**的产物名推导。同一资源若两次
   嗅探到的 Content-Type 一次是音频、一次是视频，第二次的 `.part` 名与第一次不同、命不中残留，
   会从头下——不会拼错，只是浪费一次。R2 能命中的前提正是两次都是 `video/mp4`。
   **裁决（2026-11-01）：接受为已知边界，但「静默」不能是「无痕」**——已补
   `resume_missed task_id=... reason=part_name_mismatch expected=X found=Y` 审计行，
   行为确认仍是静默全新下载（不发 `Range`、不清理别人的残留）；M4 若做「稳定 key」再彻底解决。
5. **HLS 续传依赖 yt-dlp 的命名约定**（`<out>.part` / `<out>.part-FragN.part` / `<out>.ytdl`），
   引擎不读不改不清理。yt-dlp 换版本可能改这套布局，所以 real 用例把它当黑盒，
   只断言「产物 sha 与一次性一致」，不断言文件名细节。
6. **桩模式没有续传语义**：桩下载瞬时完成、不落 `.part`，本轮改动全在 real 分支。
7. 取证结束后回收：停带 `BGV_SSRF_ALLOWLIST` / `BGV_DEV_ALLOW_SSRF_BYPASS` 的引擎进程
   （两个 env 随之消失，白名单代码保留且默认不注册）、停 8731 媒体服务器、
   `adb reverse --remove tcp:8730 tcp:8731`。

## 13. P0：`resolve_redirects` 探测有界化（2026-11-01，4b-4 之后、M3 收尾之前）

### 13.1 定性：防御缺口，不是性能问题

§12.8-2 原本把这件事记成「M4 候选：探测浪费一跳」。4b-4 验收时改判：
`Range: bytes=0-0` 探测走的是**非流式** `client.get()`，服务端忽略 Range 时它把整个响应体
读进内存才返回——R2 量到的 13.04 s 只是「4.2 MB 恰好下得完」的表象；
换成直播 / SSE / 超大文件这类**无限流**，同一段代码就是无界读内存直到 OOM。
探测的输入是**外部服务器**的响应，所以这是对真实引擎外部输入的防御，优先级高于文档整理。
修法采用裁决给的第一种：`stream=True` + 只取响应头 + 读满预算即关。

### 13.2 代码落点

[engine/services/redirect.py](file:///E:/code/android/BrowseGuardVid/engine/services/redirect.py)：

- `PROBE_MAX_BYTES = 1024`——探测要的是头，正文一个字节都用不上；
- 每一跳改用 `with client.stream("GET", current, headers={"Range": "bytes=0-0"})`，
  跳数逻辑与逐跳 `validate_outbound_url` 一字未动；
- `_consume_probe_budget()`：走 `iter_raw`（不是会解压的 `iter_bytes`，否则一小段压缩流
  就能撑开大内存），累计到预算即 `break`，`with` 收尾断开连接；
  超预算的字节一个都不进进程；
- 命中截断打审计行 `resume_probe_truncated task_id=... reason=body_over_probe_budget read_bytes=N`
  ——N 是**已读**字节数，不含未读部分；探测照常返回 `RedirectTarget`，命名/封装逻辑不受影响；
- `task_id` 是带默认值（`"unknown"`）的可选关键字参数，只为把审计行挂到任务上。

`response.is_stream_consumed` 为真时直接返回：那是 `MockTransport` 一类注入件「正文已在传输层
一次性就位」的形态，此时没有可界定的读取动作；真实出站 `HTTPTransport` 恒为流式，防御在真实路径生效。

### 13.3 调用点审计（签名变更必须逐处过）

| 调用点 | 处理 |
|---|---|
| [engine/services/downloader.py:190](file:///E:/code/android/BrowseGuardVid/engine/services/downloader.py#L190) | 传入 `task_id=task_id`（下载任务上下文里本来就有） |
| [engine/services/formats.py:166-175](file:///E:/code/android/BrowseGuardVid/engine/services/formats.py#L166) | `fetch_manifest_text` 增加同名可选形参并透传，避免嗅探路径的审计行全成 `unknown` |
| [engine/main.py:595](file:///E:/code/android/BrowseGuardVid/engine/main.py#L595) | 嗅探 DRM 判定处传 `task_id=task.id` |
| `tests_real/test_stage3_units.py` 4 处直接调用 | 只读 `url` / `content_type`；新参数带默认值，不改位置参数 → 无需改动，现场复跑通过 |
| `tests_real/test_download_resume.py` | 经 `download_direct` 间接调用；`_handler_for` / `_probe_transport` 用 `content=b""` 构造探测应答，命中上面的 `is_stream_consumed` 分支 |

新增 `tests_real/test_redirect_probe_bounds.py` 七条（全部 real 标记）：

| 用例 | 钉住的东西 |
|---|---|
| `test_probe_stops_at_budget_on_full_body` | 10 MB 正文只交出 `PROBE_MAX_BYTES + 一块` |
| `test_probe_survives_infinite_body` | **永不收尾的流**：探测照样返回，交出字节有界、耗时 <1 s |
| `test_probe_truncation_logged_with_task_id` | 审计行原文（含 `task_id`），且不带 URL/host |
| `test_probe_within_budget_is_silent` | 预算内不该打审计行——`resume_probe_truncated` 只代表真截断 |
| `test_probe_has_no_unbounded_read_path` | 结构性钉子：函数体内没有 `client.get(`，只有 `client.stream(` |
| `test_every_hop_probes_bounded` | 中间跳（非终点）同样有界，多跳链不会累积读取 |
| `test_real_server_paced_body_does_not_slow_probe` | 真 HTTP + 真 socket 记账（下面数字即此条） |

### 13.4 现场数字（同一条 URL，对照修复前的写法）

素材：`probe_bulk.mp4` 4,194,304 B（64 块 × 64 KiB），路由
`/media/probe_bulk.mp4?no_range=1&chunk_delay_ms=20`（服务器忽略 Range、每写一块 sleep 20 ms）。
同一条 URL 上先跑修复后的探测（自写 `_CountingTransport` 在真 socket 上记账），
再跑一次修复前的 `client.get()` 作对照：

```
body_size=4194304 chunks=64 chunk_delay_ms=20 PROBE_MAX_BYTES=1024
legacy_full_get: elapsed=1.474s body_read=4194304      ← 修复前：整包进内存
bounded_probe  : elapsed=0.004s bytes_received=65280   ← 修复后：一次 socket 读就撤
ratio = 338.8x
final_url content-type = video/mp4
```

pytest 侧同时断言三件事，缺一即红：客户端收到字节 ≤ `PROBE_MAX_BYTES + 一块`、
服务器日志里探测只发过**一次** `bytes=0-0` 且那一行 `no_range is True`、
审计行 `resume_probe_truncated task_id=paced-1 reason=body_over_probe_budget read_bytes=65536`
确实打出（`read_bytes` 是已读量，socket 一次给多少由不得客户端）。
耗时只作旁证、不断言 10×：同机第二次探测实测 0.181 s（首次 0.004 s），
Windows 回环上的建连/关闭抖动足以让纯时间断言误报——所以判据落在字节记账上。
R2 那 13.04 s 不需要单独复现：它是同一段代码在同一路径上的线性放大，
现在那段代码最多读一块，13.04 s 与 4.2 MB 都随之消失（M4 只需确认无回归）。

### 13.5 单测计数（现场复跑）

- 桩：152 → **154**（`test_log_redaction.py` 把两条新审计行纳入「原文不被脱敏」参数表）；
- real：133 → **141 passed + 1 xfailed**（七条探测用例 + 两条 `resume_missed` 用例 +
  §8-8 那条从「通过」改成 `xfail(strict=True)`，故 passed 少一条、xfailed 多一条）。

`1 xfailed` 是 §8-8 那条应有行为断言今天的**正确**状态；`strict=True` 让「失败」与
「意外通过」是两种都能被 pytest 分别看见的状态，被禁止的只有「悄悄通过却仍记为已知缺口」这一种。

### 13.6 边界（本轮主动声明）

1. 内存窗口是 `PROBE_MAX_BYTES + 一次 socket 读`（实测 ≤64 KiB），不是严格 1024 B——
   客户端无法要求服务端按 1 KB 吐字节，这一点在用例断言里写明而不是藏起来。
2. `fetch_manifest_text` 取 manifest 正文仍是非流式 `client.get()`：读全文是它的本职
   （DRM 判定要解析整张播放列表），manifest 天然小；但「`.m3u8` 路由回一个无限体」同样会无界读。
   本轮不动，M4 评估给它加体积上限。
3. 探测提前断开后 httpx 不复用这条连接，正文下载会多一次 TCP 建连——真实下载路径上可忽略。
4. 桩模式无探测请求（走桩下载分支），本轮改动全在 real 分支。

## 14. M3 收尾清单逐条记录（2026-11-01）

### 14.1 收尾①：M2 惰性 Cookie 复核（只读代码 + 针对性单测，不改行为）

**裁决口径**（M2，2026-10-31）：默认不发、被拒才读一次、只重试一次；凭据来源只有引擎侧
`BGV_COOKIE_FILE`；原文件只读，yt-dlp 需要写入时用临时副本；协议层不存在 `cookieFile`/`browser`。

**代码核对**——三条路径都接了线，触发信号各不相同：

| 路径 | 触发条件 | 读取方式 | 复核前的证据状态 |
|---|---|---|---|
| 嗅探 `services/sniffer.py` | 两个信号：导航响应 `_nav_rejected(response)`；或页面 200 但**页内媒体请求被拒**（`rejected` 非空且尚未重试过） | `load_env_cookies()` → `playwright_cookies()`（保留 domain 作用域），整页重载一次 | real 已证：`tests_real/test_lazy_cookie_sniff.py`（真 Playwright + 真 HTTP 403） |
| 直链 `services/downloader.py:download_direct` | `response.status_code in {401, 403}` 且 `not cookie_retry_done` | `header_from_env(final_url)` 读一次、只重试一次 | 只有注入式 transport（`test_cookies.py`），真 socket 未走 |
| yt-dlp `services/downloader.py:download_media` | 异常文本命中 `_looks_like_rejection`（`401/403/forbidden/unauthorized`） | `cookie_file_copy()` 临时副本传 `cookiefile` 选项 | 只有 `fake_ydl`，真进程未证 |

**关键分叉点判定：是「惰性读」，不是「不读」。** 三条支撑：
1. 策略不是硬编码也不是 `none`——`cookiePolicy` 是 `POST /v1/tasks` 的**必填**字段（模型无默认值），
   引擎 selfcheck 上报 `cookiePolicyDefault = lazy`（`main.py:818`），客户端两处建任务都取这个默认值
   （`HomeViewModel.kt:163` 嗅探、`:210` 下载），所以真机流程走的就是 `lazy`。
2. `main.py:334/344/579` 把 `task.cookiePolicy.value` 一路透到三条执行路径，中间没有被覆写回 `none`。
3. 4a 真机留下的 `cookie_present=false` 属于**现场没有 403**：那批路由
   （`/page.html`、`/media/sample.mp4`、HLS master）从不回 401/403，惰性语义下本就不该读文件。
   也就是说 §9.3 那条证据的边界是「未读」，但它否证不了「被拒时读不到」——
   缺的是现场，不是代码。

**补的针对性单测**（`tests_real/test_lazy_cookie_direct.py`，两条，`real` 标记，不改任何生产代码）：

| 用例 | 钉住的东西 |
|---|---|
| `test_direct_lazy_reads_once_and_retries_once_over_real_http` | 真 HTTP 上 403 → 读一次 → 重试一次拿到 200；产物逐字节等于源；`load_env_cookies` 恰好调用 1 次；原文件 mtime 不变；完成后无 `.part`；审计行只有 `cookie_lazy_retry task_id=…`，`cookie_file_loaded`（explicit 专用）不出现，日志里搜不到 Cookie 值 |
| `test_direct_lazy_without_cookie_file_fails_after_one_read` | 没配 `BGV_COOKIE_FILE` 时：读了一次、读不到，**不重试**，按 `HTTP 403` 失败，且目录里不留半个产物 |

**现场数字**（同一路径独立跑一遍，Cookie 值只以 present/长度呈现，不进报告）：

```
bgv.engine | cookie_lazy_retry task_id=evidence-lazy-1
bgv.engine | direct_download_complete task_id=evidence-lazy-1 bytes=61456
  #0 range=bytes=0-0    cookie_present=False cookie_len=0  sid_in_cookie=False
  #1 range=-            cookie_present=False cookie_len=0  sid_in_cookie=False
  #2 range=-            cookie_present=True  cookie_len=15 sid_in_cookie=True
product=protected.mp4 size=61456 sha256=ba7a40823a6c0e89…
source  sample.mp4    size=61456 sha256=ba7a40823a6c0e89…
cookie_file mtime before=1793522874.2 after=1793522874.2 changed=False
```

三跳而不是两跳是真实形态：`#0` 是 `resolve_redirects` 的 `bytes=0-0` 探测，它撞了同一个 403
但**不做 Cookie 回补**（探测只要头，正文与凭据都与它无关）；`#1` 才是下载请求的第一次无凭据尝试，
`#2` 是带凭据的重试。用例按 `Range` 头把探测那一跳单独摘出来断言，而不是把它藏进计数里。
`#2` 的 `range=-` 同时证明重试沿用原始请求头、没有夹带续传 Range。

**结论**：M2 惰性 Cookie **已落地**，收尾继续，不需要回头补 M2。

**计数**（收尾① 补测后现场复跑）：stub `154 passed, 144 deselected` / real
`143 passed, 154 deselected, 1 xfailed` / schema `6/6`。commit `c1f2be9` 里写的「141 real」
是这次补测之前的基线——message 是历史、报告是活文档，以本节为准。
上面那块现场数字也是补测后独立复跑的一次（mtime 随现场时钟而变，判定看 `changed=False`）。

复核后仍然敞着的三条，按「证据边界」而不是「代码缺陷」记（裁决见 §8-10，2026-11-01 收尾①验收）：
1. **yt-dlp 真进程——留 M4**：惰性重试**仅 `fake_ydl` 证接线，real 未取证**（`cookiefile` 从无到有、副本清理、
   原文件 mtime 不变）；真进程对真 403 抛出的错误文本能否命中 `_looks_like_rejection` 没取过证。M4 做
   「受保护 HLS 真链路」时一并补。
2. **嗅探的第二个信号——留 M4**：`sniffer.py:165` 那条「页面 200 但页内媒体请求被拒 → 整页重载」在 real
   用例里没覆盖——`test_lazy_cookie_sniff.py` 四条走的是导航响应那一支（`/protected.html` 直接 403）。
   代码在、语义对，缺的是现场。
3. **不触发回补的模糊拒绝——设计意图，按裁决入码**：「302 跳登录页」「200 但正文是登录墙」两条路径都不重试。
   复核认定为**刻意的凭据最小化**（只在明确的 401/403 拒绝信号下回补，不对登录页猜测性回补），不是缺口；
   该口径已写进 `engine/services/cookies.py` 模块 docstring（不只留在汇报里），M4 若要改判定条件应先立证据。




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
   **收尾② 处置（2026-11-01）：已作为提案 A 入档
   [docs/PROTOCOL-CHANGELOG.md](file:///E:/code/android/BrowseGuardVid/docs/PROTOCOL-CHANGELOG.md)「M3 收尾裁决」，
   M3 不实现。入档时复核出一处前提要修正：双端对未知字段都严格拒绝（`BgvJson.ignoreUnknownKeys = false` +
   Pydantic `extra="forbid"`），所以「新增可选**响应**字段」在 v0.1 严格性下并不向后兼容——引擎先发该字段
   会让旧客户端解 `GET /v1/tasks/{id}` 直接抛错。上面那句裁决要带提案 A 里的三条出路（发布顺序 /
   非对称严格性 / 另开只读端点）之一才成立，M4 评审先定这一条再谈 schema。**本条不关闭**，等 M4 实现。
6. **`download_attempt` 审计行打太早，语义偏差**（详见 §10.5-3）：`main.py:886` 在 `POST /download`
   入口第一行就打，DRM 拒绝 / 409 / 422 都会留下一条 attempt 行，它实际表达的是「请求到达」而不是「开始下载」。
   **裁决（2026-11-01）：本轮不动。** M3 收尾时连同协议 §10 的审计语义一起讨论是否补一条独立的
   `download_rejected`（保留 attempt 的「收到请求」含义、把「真的开始写字节」分出来），
   属文档 + 引擎日志两处改动，需与既有取证脚本的 grep 口径一并调整。
   **收尾② 处置（2026-11-01）：已作为提案 B 入档
   [docs/PROTOCOL-CHANGELOG.md](file:///E:/code/android/BrowseGuardVid/docs/PROTOCOL-CHANGELOG.md)「M3 收尾裁决」，
   M3 不实现。提案把四条拒绝路径（404 / 409 / 422 DRM / 422 FORMAT_UNAVAILABLE）与两条 attempt 入口
   （HTTP 端点 `main.py:934`、引擎自动领取 `main.py:548`）都列全，并钉住两条边界：`download_attempt`
   一字不动（§10「DRM 零下载」靠它）、`202` 幂等重放不打 `download_rejected`。
   **本条不关闭**，等 M4 实施时同步改取证脚本。
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
11. **审计状态：收尾③（UI 文案同源）挖出的 P0 已修 + 三条文案层敞口按裁决入列**（2026-11-01，详见 §14.3）。
    - **已关闭：嗅探失败路径引用不存在的 `ErrorCode.INTERNAL_ERROR`**（改前 `engine/main.py:536`）——
      worker 抛 `AttributeError`，任务永久停在 `sniffing`，客户端只能等本地超时、屏上没有协议错误码。
      改为 §5 真码（`EXTRACTOR_FAILED` / `NO_MEDIA_FOUND`，第二处 `missing_sniff` → `UNKNOWN`），
      三条 real 用例真驱动 worker + 一条静态守卫扫全量 `ErrorCode.X` 引用（§14.3.1）。
      取证是**真机主路径**跑出来的，不是读代码读出来的——既有 real 用例只断言嗅探函数返回 `None`，
      没人把无媒体 URL 推进状态机。
    - **已落地①（裁决 2026-11-01）：`sniff_running` 标签语义错位**——资源串原写「引擎状态：」，
      `HomeScreen.kt:329` 屏上跟的是**任务状态**。裁决：改资源串为「任务状态：」（`strings.xml:37`，
      `:36` 留注释说明这行不表示引擎连接状态），**不改信息架构**；引擎连接状态归设置页，M4 再考虑。
    - **已落地②（P1，裁决 2026-11-01）：两条本地超时的错误码表示不一致**——原嗅探/画质解析超时传
      `code=null`，下载超时传 `"NETWORK_TIMEOUT"`，而该码在 §5 表内存在但**引擎侧从不发**，属客户端伪造协议码。
      裁决理由三条：用户误判成网络问题、日志与协议审计对不上、将来重试策略误判。
      修法：`LocalTimeouts`（`HomeViewModel.kt:379`）成单一来源，三条路径统一 `code=null` +
      retryable=true + 文案自带窗口秒数；`LocalTimeoutTest` 4 条钉住，其中一条静态守卫扫全量
      `"UPPER_SNAKE"` 字面量不得命中 `ErrorCode` 名（§17.1）。
    - **待裁决③（已撤销，不得采用）：原记「保存引擎地址后不自动重连，仍要手点『重连』」是前提误判**。
      代码自 `c1f2be9` 起 `saveEngineUrl()` 成功分支即调用 `refreshConnection()`（`HomeViewModel.kt:126-133`），
      保存后会自动复检；上一轮的结论是从「屏上无变化」越界得出。该观察在当前设计下**不可判定**：
      状态卡不回显所连 baseUrl（`strings.xml:10` 静态示例地址）、客户端无请求日志、访问日志无时间戳，
      `.devtmp/3ui-35-url-back.txt` 与 `.devtmp/3ui-36-saved.txt` 逐字节相同只能证明「屏上无差异」。
      因此「地址已保存，点击连接生效」「保存并连接」两类提示文案**不得采用**（写的是与行为相反的话）。
      真实缺口是**可判性**（改错地址后屏上无法回答「连的哪台引擎」）不是交互，归 M4 设置页批次（裁决 ①）。
12. **M4 开工前必做：状态机非终态错误出口全枚举审计**（登记 2026-11-01，来源是收尾③ 那条 P0 的复盘）。
    逐个非终态列出「这个状态下失败发哪个 §5 码」——`queued` / `sniffing` / `awaiting_selection` /
    `downloading` / `merging` / `post_processing`，成表，缺的补上。
    判定原则：每个非终态都必须有错误出口，不能既不失败也不完成。
    P0 就是这类缺口的实例：`sniffing` 的失败路径引用了不存在的 `ErrorCode.INTERNAL_ERROR`，
    worker 抛 `AttributeError`，任务永久停在 `sniffing`。
    不阻塞收尾④/⑤，但 M4 开工前必须做完（见 §17.4）。

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

### 14.2 收尾②：`Task.warnings` / `download_rejected` 走 PROTOCOL-CHANGELOG（只入档，不实现）

**范围口径**：两个都是 M4 才实现的字段/语义，收尾只做「协议变更提案入档」。本轮**只改两份文档**，
`docs/PROTOCOL.md` 正文、双端 schema、引擎与客户端代码一行未动——`git diff --name-only docs/PROTOCOL.md`
与 `git diff --name-only engine/ shared/ android/ scripts/` 均输出 0 行，`git diff --stat` 只有
`docs/PROTOCOL-CHANGELOG.md`（+91）与 `docs/smoke-report.md`（+52，含本节与 §8-5/§8-6 的处置行）——合计 143 插入、0 删除。

**入档内容**（`docs/PROTOCOL-CHANGELOG.md`「M3 收尾裁决」节，提案 A / 提案 B）：

- **提案 A `Task.warnings`**：可选 `warnings: list[TaskWarning]`（`code` / `message` / 脱敏 `detail`），
  首批 code 全部复用现有审计行名（`selector_degraded` / `output_ext_corrected` / `resume_unsupported` /
  `resume_missed`），不新造语义、不让 warning 参与状态机。
- **提案 B `download_rejected`**：`download_rejected task_id=… stage=<entry|state|drm|selector> code=… status=…`，
  四条拒绝路径（404 / 409 / 422 DRM / 422 FORMAT_UNAVAILABLE）与两条 attempt 入口（HTTP 端点、引擎自动领取）
  逐一列位；**不新增错误码**，只动日志层。

**入档时的两处复核结论**（比 §8-5/§8-6 原裁决更硬，写进 CHANGELOG 而不是只留汇报）：

1. **§8-5 的「新增可选字段唯一向后兼容」这句要带前提**：双端对未知字段都严格拒绝
   （Pydantic `StrictModel` = `extra="forbid"`；`BgvJson` = `ignoreUnknownKeys = false`，且客户端
   **所有**响应解码走这一份 `EngineClient.kt:82`）。所以引擎先在 `Task` 里发 `warnings`，
   已安装的旧客户端解 `GET /v1/tasks/{id}` 会抛错，表现是「任务查不到」而不是「少一条提示」。
   M4 必须先在三条出路里选一条（发布顺序约束 / 非对称严格性 / 另开只读诊断端点），再谈动 schema。
2. **`download_attempt` 一字不能动**：§10「DRM 场景无任何下载尝试（引擎日志可证）」这条验收成立的方式是
   「有 attempt、其后没有任何下载行为行」；把它挪到真下载之前，这条就变成自证、取证链断裂。
   所以 B 是**增行**而非改行，`engine/test_drm.py:63` 的「attempt 恰 1 次」断言不受影响；
   同时钉住 `202` 幂等重放与内部 5xx 不打 `download_rejected`，否则
   `attempt = 真开始下载 + rejected + 幂等重放` 的恒等式失效。

**§8-5 / §8-6 状态**：都改为「已入档，本条不关闭，等 M4 实施」——提案入档 ≠ 语义落地，
关闭要等 M4 实现 + 双端 schema 同步 + 取证脚本口径调整之后。

**无回归证据**（本轮无代码改动，复跑只为证明这一点）：

```
154 passed, 144 deselected                       # stub
143 passed, 154 deselected, 1 xfailed            # real
schema 一致性校验通过：6/6 个模型                 # 仍是 6；提案 A 落地后变 7
```

**工作区卫生记录**：`c1f2be9` 之后 `.gitignore` 第 61 行出现过一条 `.screenshots/`，与本报告口径
（验收截图必须入库）相反，会让后续取证截图被静默忽略。已删除，删除后 `git diff .gitignore` 空输出、
`git ls-files .screenshots/ | wc -l` = 33 全部仍 tracked。归因只能排除：`.git/hooks` 无任何非 sample 钩子，
该行的 mtime 17:56:29 落在 `c1f2be9`（17:43:59）与下一笔提交之间，且插入位置（「验证文件」段）与本文件
第 108 行「验收截图不在此列——`.screenshots/` 是证据，要入」的注释互相矛盾，不是本会话写的。
再出现同样状况按本节复核三条命令即可判定，不靠记忆。

### 14.3 收尾③：UI 文案与协议同源（本轮另挖出一条 P0）

**口径**：界面文案只允许四类来源，每一类都能回溯到协议条款或引擎规则；`strings.xml` 不再存媒体行副本。

| 来源 | 对应条款 / 规则 | 唯一落点 |
|---|---|---|
| 任务状态措辞 | §4 状态机 | `ui/ProtocolCopy.kt:9-25`（`displayLabel` / `wireName` / `displayWithWire`） |
| 错误文案与可重试 | §5 错误码表 | 客户端 `shared/.../ErrorPayload.kt:44-46`，引擎同源 `engine/models.py:157-159`（双端逐字对齐） |
| 格式行首/次行 | §3.4 + 引擎 `services/formats.py` 降级规则 | `ui/FormatRowCopy.kt:19-60`（判定一律接收**整张表**） |
| 嗅探结果行 | §3.2 | 同文件 `SniffRowCopy`（`FormatRowCopy.kt:68-89`） |

**线名继续留在屏上**（`displayWithWire()` 把中文和 `awaiting_selection` 这样的蛇形名拼在一行）：
中文化不该切断真机取证的 grep 口径，`HomeScreen.kt:329/390/554` 三处状态行都走这一个函数。

**改动的界面元素**（改前 → 改后）：

| 落点 | 改前 | 改后 |
|---|---|---|
| `HomeScreen.kt:414` 嗅探首行 | `type.name.lowercase() + " · " + estimatedQuality.label`——引擎无高度时把 `label="unknown"`（`engine/main.py:620` 的占位线名）当画质标签直接上屏 | `SniffRowCopy.primary(result)` |
| `HomeScreen.kt:432` 嗅探次行 | `buildString` 恒打 `0p · 0 kbps`（假精确） | `SniffRowCopy.secondary(result)`：`height/bitrate <= 0` 不出现 |
| `HomeScreen.kt:422` DRM 标注 | `R.string.sniff_drm` 副本「DRM 保护 · 不支持下载」 | `ErrorCode.DRM_PROTECTED.message` |
| `HomeViewModel.kt:174-176` 嗅探空结果 | 硬编码中文 + 手写码名 | `ErrorCode.NO_MEDIA_FOUND` 的 `message`/`name`/`retryable` |
| `HomeViewModel.kt:226-228` 画质不可用 | 同上 | `ErrorCode.FORMAT_UNAVAILABLE.…` |
| `HomeViewModel.kt:183/382` 无 error 的终态 | 写死「任务结束」 | `"任务结束于 ${status.displayWithWire()}"` |
| `HomeScreen.kt:528/538` 格式行 | （4b-1 已接 `FormatRowCopy`，本轮只补整表口径） | `FormatRowCopy.primary/secondary(format, formats)` |

> **脚注（§19.9，2026-11-02）**：表里 `HomeViewModel.kt:174-176` / `:226-228` 两行的「改后」只对了**文案**那一半——`message` 取枚举原文是同源，把 `name`（协议码）与 `retryable` 一起搬过去则成了客户端替引擎发言。本轮改回本地判定（`code = null`），枚举 `.message` 的用法保留。

**`strings.xml` 副本清除**：删 `sniff_drm`、`format_audio_only`、`format_kind_unknown`、
`format_kind_video_only`、`format_no_metadata`、`format_needs_merge` 六条，原位置留一行注释指向
`ui/FormatRowCopy.kt`。删除后 `grep -rn` 六个名字在 `android/` 的 `.kt`/`.xml` 里命中 **0 行**，
`assembleDebug` + 单测复跑通过（编译器会因缺失 `R.string` 直接失败，不是靠肉眼）。
`sniff_no_drm`（无 DRM，`HomeScreen.kt:424`）与 `sniff_running`（引擎状态：，`:329`）仍在用，没动。

**同源性钉子（不是「看起来一样」）**：`FormatRowCopy.heightCapIneffective(formats)`（`:58`）是引擎
`services/formats.py`「整表任一 `height<=0` → 降级 `best`」的客户端镜像，空表同样判「不生效」；
`SniffRowCopy.qualityLabel`（`:84`）钉住「引擎给了 `label` 就用 `label`，否则回落 `{height}p`，
无高度一律『画质未标注』」。文案与 `FormatSelection.selectorFor` 的下发规则共用
`FormatSelection.kindOf`（`FormatRowCopyTest.primaryLabelIsDrivenByKindOf` 逐条对齐），
界面不许再从单行 `hasVideo` 另推一套。

#### 14.3.1 P0：嗅探失败路径引用了不存在的枚举成员，任务永久卡在 sniffing

**这条不是读代码读出来的，是真机主路径跑出来的**：给引擎喂 `/whoami`（页面上没有任何媒体流），
预期是屏上出现 §5 的错误码，实际是**客户端本地 90 s 超时**，任务停在 `sniffing` 不动。

**根因**（改前 `engine/main.py:536`）：

```python
task.error = error_payload(ErrorCode.INTERNAL_ERROR, detail={"reason": "sniff_failed"})
```

`ErrorCode` 里没有 `INTERNAL_ERROR`（§5 表内只有 `UNKNOWN`/`EXTRACTOR_FAILED`/`NO_MEDIA_FOUND`…）。
Python 属性访问到运行期才解析，所以导入、类型检查、单元测试全绿；worker 线程走到这条分支抛
`AttributeError`，`_transition` 从未执行，任务**永远停在 sniffing**——错误码没写进去，
失败状态也没写进去。同一支笔误还有第二处（改前 `main.py:652` 的 `missing_sniff` 分支）。

**为什么既有测试没抓到**：`tests_real/test_sniffer_real.py:112` 只断言嗅探函数**返回 `None`**，
`test_api_wiring.py` 的 real 用例此前全部围绕成功路径——没有任何一条把「无媒体」的 URL
推进 worker 的完整状态机。缺口在**驱动方式**，不在断言数量。

**修法**（`engine/main.py:565` 起）：`_sniff_real` 的返回类型从 `None` 改为 `ErrorCode | None`，
两条失败各回一个 §5 真码——嗅探抛 `SniffError` → `EXTRACTOR_FAILED`（`:582`），
页面无媒体流 → `NO_MEDIA_FOUND`（`:586`），成功 `return None`（`:633`）；调用方拿到码就
`_fail_task(task, failure, {"reason": "sniff_failed"})`（`:532-536`）。第二处 `missing_sniff`
改 `ErrorCode.UNKNOWN`（`:651`）。**没有新增错误码**，两条都是 §5 表内既有成员、
`retryable` 沿用表定义（嗅探类都为 `True`），docstring 同步改成写明两个码。

**回归覆盖（现场复跑，见本节末）**：

| 用例 | 钉住的东西 |
|---|---|
| `tests_real/test_api_wiring.py:566` `…sniff_no_media_fails_with_protocol_code` | 无媒体 → `FAILED` + `NO_MEDIA_FOUND` + 文案「未发现可下载的媒体流」+ `retryable=True` + `detail.reason=sniff_failed` |
| `:593` `…sniff_error_fails_with_extractor_code` | `SniffError` → `FAILED` + `EXTRACTOR_FAILED` + 「解析失败，可能站点已变更」 |
| `:614` `…downloading_without_sniff_fails_with_unknown_code` | `DOWNLOADING` 但 `sniff=None` → `FAILED` + `UNKNOWN` + `detail.reason=missing_sniff`（用 `asyncio.run(main._process_task(id))` 真驱动 worker，不是调私有断言） |
| `test_error_codes.py`（静态守卫，`--collect-only` 16 条） | 扫 `engine/*.py` + `engine/services/*.py`（排 `models.py` 与 `test_*.py`）里每一处 `ErrorCode.X`，`X` 必须属于 `ErrorCode.__members__`；另加一条防呆断言：扫描清单必须真含 `main.py`/`security.py`/`downloader.py`，防守卫自己扫成空集 |

守卫的动机写在模块 docstring 里（不只留在本报告）：**枚举成员拼错是 rare path 上的静默失效**，
把它压回「第一次跑测试就红」，而不是等下一次真机取证。
本轮之后 `grep -rn INTERNAL_ERROR engine/ shared/ scripts/`（排除 `.venv`）只剩两处
——两条新用例的回归说明文字，生产代码 0 处。

#### 14.3.2 真机取证（emulator-5554）

**环境**：沿用 §12.3 那套（`BGV_ENGINE_MODE=real` + `BGV_SSRF_ALLOWLIST` 三项 +
`BGV_DEV_ALLOW_SSRF_BYPASS=1`，引擎 `127.0.0.1:8730`，媒体服务 `8731`，
`adb reverse tcp:8730 tcp:8731`）；格式表的多行分支本地 real fixture 拿不到（真解析只回一行），
另起一个 **8740 桩实例**（`BGV_ENGINE_MODE=stub`）专门把「上限不生效 + 纯音频」两行送到屏上。
取证结束后两个引擎实例连同各自的 dev 环境变量一起停掉，`adb reverse --remove tcp:8730 tcp:8731`。

**主路径跑通**：嗅探 → 选画质 → 下载 → `done`，产物
`D:\tmp\bgv3ui\out\bulk.mp4` 4,218,400 B、`sha256` 前缀 `e5a5923deda3b299`，
与源文件 `.devtmp/media-assets/media/bulk.mp4` 同长同哈希（收尾③改动不影响字节）。

**屏上字符串**（uiautomator 导出的 text/content-desc，非肉眼判读；九张截图已入库）：

| 截图 | 屏上文案 | 覆盖的分支 |
|---|---|---|
| `m3copy-0-real-sniff-row-before.png` | `mp4 · unknown` / `mp4 · 0p · 0 kbps` | **改前**假精确，留作对照 |
| `m3copy-8-real-sniff-row-nofake.png` | `mp4 · 画质未标注` / `mp4` | `qualityLabel` 无高度支、`secondary` 省略 `0p/0 kbps` |
| `m3copy-1-real-format-unknown.png` | `最佳可用（画质未标注） · mp4 · mp4` / `画质与编码待引擎解析` | `UNKNOWN_METADATA` 两支 |
| `m3copy-2-real-downloading-wire.png` | `下载中（downloading）` + `1.2 MB / 4.0 MB · 312.9 KB/s · 剩余 9s` | `displayWithWire`（中文 + 线名） |
| `m3copy-3-real-done.png` | `下载完成` / `输出目录 D:\tmp\bgv3ui\out` / `已下载 4.0 MB` | 终态 |
| `m3copy-4-real-sniff-queued.png` | `引擎状态： 排队中，等引擎领取（queued）` | `QUEUED` 文案 |
| `m3copy-5-real-no-media-found.png` | `未发现可下载的媒体流` + `NO_MEDIA_FOUND · 可重试` | **P0 修复后的真机表现**：协议码直接上屏，不再等本地超时 |
| `m3copy-6-stub-ssrf-blocked.png` | `目标地址指向受限网络，已拒绝` + `SSRF_BLOCKED · 不可重试` | 引擎下发的错误码与 `retryable`（客户端不自行判定） |
| `m3copy-7-stub-format-table.png` | `最佳可用（画质上限不生效） · mp4 · 137+140` / `1920×1080 · 4500 kbps · ~500.0 MB · avc1.640028/mp4a.40.2`；`纯音频 · m4a · 140` / `128 kbps · ~4.0 MB · none/mp4a.40.2` | 整表 `height<=0` 降级、`AUDIO_ONLY`、次行只列真实元数据 |

**计数（收尾③ 全部改动落定后现场复跑）**：

```
170 passed, 147 deselected                        # stub（154 → +16，全是 test_error_codes.py 的参数化守卫）
146 passed, 170 deselected, 1 xfailed             # real（143 → +3，三条 worker 驱动用例）
schema 一致性校验通过：6/6 个模型                  # 未动协议模型，仍是 6
Android 57 tests, 0 failures, 0 skipped           # 其中文案同源 21 条：ErrorCodeCopy 4 / ProtocolCopy 4 /
                                                  #   FormatRowCopy 9 / SniffRowCopy 4
```

`1 xfailed` 仍是 §8-8 那条（`strict=True`，口径同 §13.5）。

#### 14.3.3 边界与遗留（本轮主动声明）

1. **两条分支只有单测，没有屏上证据**：`1080p · h264`（整表画质齐全且上限生效）与
   `视频（无音轨）`+`音视频分离 · 需合并`（`VIDEO_ONLY`）在真机上取不到——本地 real fixture 的
   yt-dlp 解析只回单行，桩实例回的是「137+140 带音轨 + 140 纯音频」两行，没有「有画无音」这一行。
   覆盖方式是 `FormatRowCopyTest` 对应四条 + `Preview`，**不算 UI 取证**。物理真机（收尾⑥）
   接真实站点后一并补。
2. **`嗅探中（sniffing）` 没有单独截图**：real 引擎从 `queued` 推进到 `sniffing` 的窗口在
   uiautomator 两次 dump 之间就走完了，抓到的稳定态是 `queued`（`m3copy-4`）。文案本身由
   `ProtocolCopyTest` 钉住。
3. **`sniff_running` 语义错位，留裁决**：资源串说的是「引擎状态：」，屏上跟的是**任务状态**
   （`HomeScreen.kt:329`）。本轮只统一文案来源，**没有改标签**——改标签属于界面文案之外的语义修正，
   按「只动点名元素」的口径先摆出来：要么改资源串为「任务状态：」，要么这行改显示 `/v1/health` 的引擎状态。
4. **两条本地超时的码不一致，留裁决**：嗅探/解析超时传 `code=null`（`HomeViewModel.kt:191/239`），
   下载超时传 `"NETWORK_TIMEOUT"`（`:284`）——而 `NETWORK_TIMEOUT` 在 §5 表内存在但**引擎侧从不发**，
   只有客户端本地计时器在用。同类路径两种表示，本轮按裁决不动（改哪一边都涉及 §5 语义）。
5. **`listenAddress` 是常量**：`engine/services/environment.py:16` 写死 `127.0.0.1:8730`，
   selfcheck 直接回显它（`:100`）。所以 8740 那个桩实例屏上仍显示 `127.0.0.1:8730`——
   协议不允许覆盖监听地址是有意的（`m3copy-7` 取证时按常量理解，不当 bug 报）。
6. **改完引擎地址「不会自动重连」这条自报已撤销（前提误判）**：`saveEngineUrl()` 成功分支自 `c1f2be9`
   起即调用 `refreshConnection()`（`HomeViewModel.kt:126-133`），保存后会自动复检。当时两次 dump
   （`.devtmp/3ui-35` / `.devtmp/3ui-36` 逐字节相同）只能证明屏上无差异；而状态卡不回显 baseUrl、
   客户端无请求日志、访问日志无时间戳，**该观察在当前设计下不可判定**，不能支撑「没重连」。
   真实缺口是可判性不是交互，见 §8-11 待裁决③ 与 §17.4 遗留 4（M4 设置页批次）。
7. **文案同源只覆盖「渲染入口」**：`FormatSelection.selectorFor` 的下发规则本轮**一行未动**，
   `FormatRowCopy` 只做文案。两侧一致性靠 `primaryLabelIsDrivenByKindOf` 那条同源断言钉，
   不等于两侧从此共用同一份判定代码。

   > 上面 1–7 的状态以 §17 汇总表为准：3、4 已按裁决落地，6 属前提误判已撤销。

## 15. M3 收尾④：`unitTests.isReturnDefaultValues` 评估（2026-11-01，结论＝保留 + 加约束）

产出是结论，不是改动。评估期间对 `build.gradle.kts` 的唯一操作是「改 false 跑一遍再原样还原」，
还原后用 XML 结果时间戳复核过（`android/app/build.gradle.kts:45` 仍为 `true`）。

### 15.1 现场复跑（不看配置猜）

方法：备份 → 第 45 行 `true` → `false` → `--offline :android:app:testDebugUnitTest` → 还原 → 再跑一遍。

| 跑法 | 结果 |
| --- | --- |
| `isReturnDefaultValues = false` | `61 tests completed, 3 failed` |
| `isReturnDefaultValues = true`（还原后） | `tests=61 failures=0`（结果 XML 为本次写入，非 up-to-date 残留） |

三条失败的**堆栈根因完全同一条**——不是三种 Android API 依赖，是一个静态方法调用点：

```
java.lang.RuntimeException: Method w in android.util.Log not mocked.
    at android.util.Log.w(Log.java)
    at com.hpu.browseguard.data.FormatSelection.selectorFor(FormatSelection.kt:46)
```

| 失败用例 | 触发行 |
| --- | --- |
| `FormatRowCopyTest.videoOnlyRowMarksMergeNeeded` | `FormatRowCopyTest.kt:111` |
| `FormatSelectionTest.kindDrivesStrategyForAllFourShapes` | `FormatSelectionTest.kt:126` |
| `FormatSelectionTest.videoWithoutAudioFallsBackToBest` | `FormatSelectionTest.kt:80` |

全量扫描客户端 main 源码里的 `Log.d|i|w|e|v(` 调用点：**只有 1 处**，就是 `FormatSelection.kt:46`
（`video_only` 降级审计行）。其余 58 条单测与这个开关无关。
证据：`.devtmp/4-flagfalse-run.txt`、`.devtmp/4-flagfalse-detail.txt`、`.devtmp/4-build.gradle.kts.bak`。

### 15.2 依赖性质判定

这 3 条**不是**「断言依赖 Android API 的返回值」，而是「调用链路过一个 Android 静态方法」。
断言内容全是纯逻辑（格式 → 选择器映射、文案拼接）。也就是说开关在这里起的作用是
「让日志副作用不炸」，不是「让断言蒙对」。这个区分决定了下面的建议方向。

### 15.3 替代成本

| 方案 | 成本 | 判定 |
| --- | --- | --- |
| Robolectric | 新增 `testImplementation`；当前构建走 `--offline`，Gradle 缓存里没有 → 必须先联网；引入 Android 框架 jar、JVM 单测变慢 | 为一个 `Log.w` 拖进整套 Android 运行时，比例不合。不推荐 |
| mockk | 同样不在缓存，需联网；`mockkStatic(Log::class)` + `every { Log.w(any<String>(), anyString()) } returns 0`；**每个新增 `Log` 调用点都要补桩** | 能解决，但收益是把 1 个调用点的成本摊到全部后续调用点。暂无必要 |
| 零依赖（日志挪出纯函数） | 改 1 处：`selectorFor` 返回降级原因，由 `ui/` 层调用方打日志（或注入 `(String) -> Unit`）；之后 `false` 也能 61/0 | 最便宜的结构修法，M4 想彻底摘开关时走这条 |
| ViewModel 层单测 | 需要 `AndroidViewModel` / `Application` / SharedPreferences 三件套；`kotlinx-coroutines-test` 已在依赖里 | 这才是替换的真实门槛，且与本开关无关——别把两件事捆在一起决策 |

### 15.4 结论：保留 + 加约束

1. 这是 AGP 官方支持的 `testOptions` 配置，不是 hack；关掉它换不来任何断言强度，
   只会把 3 条纯逻辑测试推向一个 Android 运行时依赖。
2. 风险面已知且极窄：唯一暴露点是 `android.util.Log`、1 个调用点；
   不存在「靠默认返回值蒙对断言」的现存用例——`false` 只红这 3 条本身就是证据。
3. 真正的风险是**将来**新增的 Android API 调用被静默放行，所以约束要打在「新增」上，不是打在「配置」上。

现有约束（`android/app/build.gradle.kts:42-44` 注释）已写明「仅为 `android.util.Log.*` 服务」+
「依赖 Android API 行为的单测必须显式 mock（或走 androidTest），不得把默认返回值当断言依据」。

建议追加（**未落地，等你点头**）：

- A（推荐，零依赖）：立一条分层规矩——`data/` 与纯映射函数不打 `android.util.Log`，日志只允许出现在
  `ui/` 层；配一条静态守卫单测扫 main 源码，把「1 处」压到「0 处」并钉住不再增长。
  落点顺序：先改 `selectorFor` 返回降级原因，再加守卫测试，最后可选把开关置 `false` 复跑确认。
- B：M4 若引入 mockk，把 15.1 的复跑命令再执行一次，确认依赖仍为 0 而不是悄悄长大。

## 16. M3 收尾⑤：双端 `x-nullable` 可空性同步（2026-11-01）

### 16.1 补的是哪一层漏洞

对账原本只比 required / properties / enum / type 四类。在这套投影里
`formatId: String? = null` 与 `formatId: String = "best"` 导出的 `type` 都是 `"string"`——
**「JSON 里这个字段能不能是 null」这条语义完全没进对账**。而它恰恰是双端最容易各写各的一处：
引擎侧发 `"formatId": null`，Kotlin 侧声明成非空 `String` 时 `BgvJson` 直接抛序列化异常，
契约检查看不见，要到真机跑到那条分支才炸。

### 16.2 两侧怎么加

- **Kotlin**（`shared/src/jvmMain/.../SchemaExporter.kt`）：`typeOf()` 末尾
  `if (desc.isNullable) put(X_NULLABLE, true)`。取 `isNullable`（类型可否为 null），
  **不取** `isElementOptional`（有没有默认值）——后者已经由 `required` 表达，两者是两条轴。
- **Python**（`engine/export_schema.py`）：原 `_simplify_prop` 拆成
  `_base_prop` / `_is_null_branch` / `_is_nullable` / `_simplify_prop`。Pydantic v2（本机 2.14.2，
  另在 2.12 上验证过同形）把 `Optional[str]` 导成
  `anyOf: [{"type":"string"},{"type":"null"}]`，折叠回单一 `type` 的同时写入 `x-nullable: true`；
  不可空字段**不带该键**（不写 `x-nullable: false`，避免「缺键」和「显式 false」两种表示混跑）。
  曾按 Pydantic v1 的 `type: ["string","null"]` 形式补过猜测分支，实测输出后删除——不留没验证过的代码。
- **`engine/models.py` 一行未改**（对「两侧同时加标记」的字面要求的偏离，理由）：Python 侧的
  可空性本来就由 `Optional[...]` 类型声明承载，在模型上再写一遍标记等于制造第二个真相源，
  还会和类型注解漂移。标记只加在**导出投影**上，与 Kotlin 侧的「由 descriptor 投影」对称。
- **对账**（`scripts/check_schema.py`）：升到五类；类型不一致时 `continue` 跳过可空性比对
  （先修类型，可空性差异会在修好后重新暴露）；新增 `_marked()` 计数，并在
  **六个模型全部比对成功却零标记命中**时判定失败——防的是 Kotlin 侧导出器静默失效导致对账空过。
- **文档**：`engine/README.md` / `shared/README.md` 的 schema 段落各补一条约定，
  含「`required` 说字段必须出现、`x-nullable` 说值可以是 null，两条轴」。

### 16.3 现场结果

重新生成两侧各 6 份（`export_schema.py` / `:shared:exportSchemas`），diff 只多了 `x-nullable` 键
和随之而来的逗号，两侧行数变化逐模型相同（3/12/21/3/15）。

| 模型 | 可空字段 | 双侧 |
| --- | --- | --- |
| `task` | `error` `formatSelector` `namingTemplate` `progress` `proxyId` | 5 / 5 |
| `sniff_result` | `drmSystem` | 1 / 1 |
| `format_info` | — | 0 / 0 |
| `format_selector` | `formatId` `maxHeight` `maxSizeBytes` `preferCodec` | 4 / 4 |
| `post_action` | `container` `crf` `destDir` `lang` `langs` `model` `targetCodec` | 7 / 7 |
| `error_payload` | `detail` | 1 / 1 |
| 合计 |  | **Python 18 / Kotlin 18** |

**没有出现单侧不一致**——本轮是「加上标记后两侧本来就一致」，不是「加标记挖出不一致」。
所以没有需要裁决的模型改动；真出现时按流程是把差异列出来交裁决，不静默改模型。

反向验证（证明检查看得见）：删掉 `shared/schema/task.json` 里 `formatSelector` 的标记 →
`exit=1`，报 `[task].formatSelector 可空性不一致：Python=True / Kotlin=False`，
参考计数 `Python=18 / Kotlin=17` → 还原后 `exit=0`，文件与生成版逐字节相同。
证据：`.devtmp/5-schema-check-pass.txt`、`5-schema-check-negative.txt`、`5-schema-check-restored.txt`、
`5-task-schema.bak`、`5-nullable-list.txt`。

单测：`SchemaExporterTest` 5 → 7 条（`可空字段带 x-nullable 标记` 钉六个模型的可空集合；
`x-nullable 与 required 互斥且标记与可空集合一致` 钉「两条轴不许混用」）。
摘掉导出器发射行复跑 → `11 tests completed, 1 failed`：红的是第一条。
**分工如实记**：第二条只在「标记乱贴」时红，摘掉发射时两侧同空所以不红——有牙的是第一条。

全量基线（本轮复跑）：shared `11/0`、android `61/0`、engine 桩 `170 passed / 147 deselected`、
engine real `146 passed + 1 xfailed`、`check_schema.py` `6/6`。

## 17. M3 关闭报告（收尾①–⑤ 汇总，2026-11-01）

### 17.1 收尾清单状态

| 收尾 | 内容 | 状态 | 落点 |
| --- | --- | --- | --- |
| ① | M2 惰性 Cookie 复核 | 关闭（只读复核 + 针对性单测，行为未改） | §14.1 |
| ② | `Task.warnings` / `download_rejected` 入 PROTOCOL-CHANGELOG | 关闭（只入档，不实现） | §14.2 |
| ③ | UI 文案与协议同源 | 关闭，另挖出 1 条 P0 并修 | §14.3 |
| ③ 裁决① | `sniff_running` 标签改「任务状态：」 | 关闭 | `strings.xml:37` + 裁决注释 `:36` |
| ③ 裁决② | 本地超时停止伪造协议码（P1） | 关闭 | `LocalTimeouts`（`HomeViewModel.kt:379`）+ `LocalTimeoutTest` 4 条 |
| ③ 裁决③ | 保存地址后「不自动重连」提示 | **撤销**：前提误判，文案不得采用 | §8-11 待裁决③、§14.3.3 边界 6 |
| ④ | `isReturnDefaultValues` 评估 | 关闭，结论＝保留 + 加约束（约束未落地） | §15 |
| ⑤ | 双端 `x-nullable` 同步 + 对账 | 关闭 | §16 |
| ⑥ | 物理真机第一轮验收 | **关闭**（MuMu Android 15 / API 35，全绿；另挖出 1 条屏显死分支并修） | §17.5 |

### 17.2 M3 关闭时的基线

| 层 | 数字 |
| --- | --- |
| shared JVM 单测 | 11 通过 / 0 失败（`BgvJsonTest` 4 + `SchemaExporterTest` 7） |
| android JVM 单测 | 61 通过 / 0 失败（9 个测试类，含新增 `LocalTimeoutTest` 4 条） |
| engine 桩 | 170 通过 / 147 排除（`-m "not real"`） |
| engine real | 146 通过 + 1 xfail（登记缺口，`strict=True`）/ 170 排除 |
| 契约对账 | `scripts/check_schema.py` 6/6 模型、五类全等、`x-nullable` 18/18 |

### 17.3 物理真机验收：先记为推迟、后由 §17.5 解除（口径变化留痕，不修饰）

已完成的全部真机取证都在 `emulator-5554`（API 36）上，走 `adb reverse` 到本机引擎。
**物理真机这一轮没有跑**：手上那台旧真机 API 28，客户端 `minSdk`/依赖（Compose、OkHttp 5.x）
跑不满，装上去的失败与业务链路无关，取证价值为负。
因此本文所有「真机」字样一律指模拟器；协议 §9 端到端、断流、续传、产物封装四类证据
在物理设备上的复现属于未完成项，不记为通过。

推迟原因与安排：受限于测试设备（旧真机 API 28 过低），物理真机验收**推迟至 MuMu 模拟器
（已开启 ARM 兼容模式）配置完成后执行**。MuMu 相关排查在本轮暂挂起，不作为任何结论的前置。

**2026-11-01 21:24 更新：该推迟状态解除。** 物理验收环境 MuMu（Android 15 / API 35）已跑通并全绿，
见 §17.5。上述「本文所有真机字样一律指模拟器」的口径自 §17.5 起不再适用于本轮新增证据；
`emulator-5554`（API 36 纯 x86）与旧真机（API 28）的历史记录保留作过程档案，
但**不作为最终物理设备通过依据**——通过依据是 §17.5。

### 17.4 遗留清单（M4 前置 → 可延后）

M4 开工前必须做完：

1. **状态机非终态错误出口全枚举审计**（§8-12）：`queued` / `sniffing` / `awaiting_selection` /
   `downloading` / `merging` / `post_processing` 各自失败走哪个 §5 码，列表，缺的补上。
   驱动力是收尾③ 的 P0：`sniffing` 的失败路径引用了不存在的码，任务永久卡住——
   「一个状态没有失败出口」正是这类缺陷的实例。
2. `1080p · h264` / `视频（无音轨）` 两行目前只有单测覆盖（真机媒体源限制）；
   M4 换分离轨素材后复核（§14.3.3 边界 1）。
3. ~~`嗅探中（sniffing）` 屏显窗口太短未取到截图~~ **关闭，且原因为误判**：窗口不短（引擎侧实测 3.90 s），
   是 `awaitTerminal` 轮询不回写导致屏显恒为 `queued`。已在 MuMu 轮修复并取到两帧，见 §17.5.4。

可延后 / 归 M4 界面批次：

4. **可判性缺口**：改错引擎地址后，屏上没有任何信息能回答「我现在连的是哪台引擎」
   （状态卡不回显 baseUrl、客户端无请求日志、访问日志无时间戳）。
   修法在设置页批次里，与裁决①「不改信息架构」同批处理（§8-11 待裁决③）。
   本轮补上了**判定方法**（访问日志行数增量，§17.5.5），缺口本身不变：方法能证明请求发过，
   但屏上依然读不出地址。
5. `engine_url_field` 的静态示例地址 `http://127.0.0.1:8730`（`strings.xml:15`）是上面缺口的
   放大器：输入框里的示例长得像真值。
6. 收尾④ 建议 A（`Log` 分层规矩 + 守卫单测）：**2026-11-01 已批准，尚未落地**，归 M4 批次。
   建议 B（把 `data/` 依赖从测试配置里拆出去）记入 M4。
7. `listenAddress` 常量回显（§14.3.3 边界 5）与文案同源只覆盖渲染入口（边界 7）——均为记录，不是缺陷。
8. ~~**同型死分支**：`awaitAwaitingSelection` 与修前的 `awaitTerminal` 一样只读不回写~~
   **已修（2026-11-01 裁决轮）**：裁决口径是「同一种结构性缺陷（UI 镜像状态漏写回写），顺手修掉，
   不留作遗留项，以免以后踩同样的坑」。修法与 §17.5.4 同一行形状；
   并补结构守卫 `PollLoopStateWritebackTest`（三条 `await*` 循环各自的回写目标 + 回写必须在 `client.task` 之后 + 扫描集非空自证）。
9. ~~**§17.5.4 的修复没有单测兜底**~~ **降级为「兜底是静态守卫，不是行为断言」**：
   `HomeViewModel` 直接 `private val client = EngineClient()`，不可注入 → 轮询回写无法在 JVM 单测里
   用假引擎构造断言。本轮先落地**静态守卫**（只保证源码里存在回写，不保证运行时真的发出过状态更新），
   行为断言待 M4 第 3 步 FakeBackend 后补（见 §18 规划）。

### 17.5 物理验收环境：MuMu（Android 15 / API 35），2026-11-01 20:56–21:24

本轮是 M3 的**最终物理设备验收**，通过依据即本节；`emulator-5554` 与 API 28 旧真机的历史记录降级为过程档案（§17.3）。

| 项 | 取值 |
| --- | --- |
| 设备 | MuMu 模拟器最新版，Android 15 / API 35，机型 SM-S9080，`adb` 序列号 `192.168.31.57:5555`（桥接 IP 直连，未切 NAT） |
| 回环 | PC 侧 `adb -s 192.168.31.57:5555 reverse tcp:8730 tcp:8730`（`reverse --list` 有 `host-16`），客户端仍走 `127.0.0.1:8730`，`network_security_config.xml` 的 cleartext 放行域不变 |
| 引擎 | `BGV_ENGINE_MODE=real` + `BGV_DEV_ALLOW_SSRF_BYPASS=1` + `BGV_SSRF_ALLOWLIST=127.0.0.1` + `BGV_OUTPUT_DIR_ALLOWLIST=D:\tmp\bgvmumu\out`，日志 `.devtmp/mumu-engine.log` |
| 媒体源 | `media_server` :8731（`?delay_ms` / `?chunk_delay` 两个节流旋钮），日志 `.devtmp/mumu-media.log` |
| 客户端 | `app-debug.apk` 32,062,038 B，`versionCode=1 minSdk=29 targetSdk=36`，`install -r -g` 覆盖安装 |

架构维度按裁决不测：M3 客户端是纯 HTTP 协议端，不加载 ARM 原生库；yt-dlp / FFmpeg 的 ARM 转译排查归 M4。
顺手记三条与 M4 排查直接相关的设备读数（不构成 M3 结论）：
`ro.product.cpu.abilist = x86_64,arm64-v8a,x86`、`ro.dalvik.vm.native.bridge = libnb.so`、
`ro.vendor.enable.native.bridge.exec64 = 1`，而 `/proc/cpuinfo` 是 AMD Ryzen 7 5800H（x86_64 宿主）。
即该镜像把 `arm64-v8a` 报进 ABI 列表并挂了 ARM 翻译桥，M4 的原生库可行性以此为起点排查。

#### 17.5.1 高优项：Android 13+ 权限面（结论：零适配缺口，Manifest 与权限逻辑未改）

| 观察 | 读数 | 判读 |
| --- | --- | --- |
| `AndroidManifest.xml` 声明 | 仅 `android.permission.INTERNET` | 无存储/媒体类权限可申 |
| `dumpsys package` install permissions | `INTERNET: granted=true` + `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION: granted=true` | 后者是 AAPT 为 AppCompat 动态注册自动补的 normal 级权限，安装期授予，非本 App 申请 |
| 运行时（dangerous）权限 | 0 条 | 全流程无授权弹窗，截图 `m3mumu-1..6` 六帧均无系统弹层 |
| Scoped Storage / 媒体权限细分 | 不适用 | 客户端不落盘：写盘发生在 PC 侧引擎，Android 端只走 HTTP |
| logcat 4,340 行扫描 | `FATAL EXCEPTION` 0 / `SecurityException` 0 / `Permission Denial` 0 / `dropbox` 无 `browseguard` 条目 | 无 Android 15 特有崩溃 |
| 噪音两条 | 80 条 `SIGSEGV` 全部 `>>> uiautomator <<<`；78 条 `avc: denied` 的 `tcontext` 为 `su`/`shell`/`init`，唯一带 `browseguard` 的一条 `permissive=1` | 取证工具自身收尾崩溃 + `adb input` 注入路径，均非 App 域 |

#### 17.5.2 主路径：嗅探 → 选画质 → 下载 → done

任务 `01a19746-c2a9-7d24-9931-d59e2a316806`：`sniff_context_close 21:22:40,287` →
`GET /v1/tasks/{id}/formats 200` → `download_attempt 21:22:49,219` + `POST /download 202` →
`direct_download_complete bytes=4218400`（21:22:49,586）。产物
`D:\tmp\bgvmumu\out\bulk.mp4` 与源文件 `.devtmp/media-assets/media/bulk.mp4`
sha256 全等：`e5a5923deda3b2998940ec852ed48e4150f45856763452c7c1bdf3d2e410fc87`。
屏显：`m3mumu-6-done.png`（下载完成 / 输出目录 `D:\tmp\bgvmumu\out` / 已下载 4.0 MB）。

#### 17.5.3 取消，以及一条白捡的续传证据

`?chunk_delay=400` 放慢到 159 KB/s，屏显「下载中（downloading） 1.8 MB / 4.0 MB · 剩余 14s」时点
「取消下载」（`m3mumu-3-downloading.png`）：引擎 `cancel_requested from_status=downloading 21:17:41,581`
→ `POST /cancel 200` → `download_aborted ... aborted after 3932160 bytes`（83 ms 内断流），
屏显「任务已取消」+ 重试入口（`m3mumu-4-cancelled.png`），产物目录只留 `bulk.mp4.part` 3,932,160 B。
随后 §17.5.2 重跑时 `media_server` 记录 `range=bytes=3932160-`——取消残留的 `.part` 被**精确续上**，
最终字节数与 sha256 仍与源一致。Android 15 上协作式断流与断点续传两条链路一并复现。

#### 17.5.4 queued → sniffing 两帧：MuMu 轮的改代码项

- **现象**：上一轮在 `?delay_ms=3000` 期间截屏恒为「排队中，等引擎领取（queued）」，
  而引擎侧同一任务的 `sniffing` 窗口实测 3.90 s（`21:12:09,248 → 21:12:13,148`）。
- **根因不是截屏时机**：`awaitTerminal` 的轮询循环只 `current = client.task(...)`，从不回写 `_state`，
  于是 `SniffPhase.Running(status)` 除 `sniff()` 入口写入的初始 `QUEUED` 外没有任何写入点，
  `HomeScreen.kt:329` 的 sniffing 分支是死代码。对照 `awaitDownloadTerminal`
  （回写体在 `HomeViewModel.kt:351-354`，行号含裁决轮的第二次改动）本来就在回写——同一条协议镜像，下载侧活、嗅探侧死。
- **修法**：轮询里补一次 `_state.update`（`HomeViewModel.kt:312-326`），一行形状，不动布局与文案。
  重建后 android 单测 61 通过 / 0 失败，覆盖安装重跑。
- **取证**：连拍（只 `screencap`，不夹 `uiautomator dump`，见 §17.5.6）。
  首轮修复版（任务 `01a1973d-2517-7670-83e6-79dc0cf014f9`，sniffing 窗口
  `21:12:09,248 → 21:12:13,148` = 3.90 s）已取到两帧；随后清掉一处无用绑定重建重装，
  **最终入库构建**复跑同 URL 同参数（任务 `01a1974a-a7d3-7134-8799-548ca2288e61`，
  窗口 `21:26:54,685 → 21:26:58,577` = 3.89 s）逐帧复现：
  tap+0.05s = 「任务状态： 排队中，等引擎领取（queued）」（`m3mumu-1-queued.png`），
  tap+1.48s = 「任务状态： 嗅探中（sniffing）」（`m3mumu-2-sniffing.png`），
  tap+3.6s 层级文本尾部同步出现 `嗅探中（sniffing）`。两帧同 URL 同任务，证明状态**会推进**而非恒真。
  三源对齐：`media_server` `t=21:26:54.754 path=/page-bulk.html delay_ms=3000` ↔
  引擎 `sniff_context_open 21:26:54,685` ↔ 设备截屏 `21:26` 帧。
- **同型缺口（本轮登记、裁决轮已修）**：`awaitAwaitingSelection` 同病，见 §17.5.7。
- **无单测兜底（裁决轮降级为静态守卫）**：`EngineClient` 不可注入，见 §17.4 遗留 9 与 §17.5.7。

#### 17.5.7 裁决轮：同型死分支补齐 + 结构守卫（M3 关闭判定的同日裁决，非设备取证轮）

§17.5.4 只修了嗅探侧，把 `awaitAwaitingSelection` 登记为遗留等裁决。裁决口径：**修**，理由是
「这是同一种结构性缺陷（UI 镜像状态漏写回写），不留作遗留项，以免以后踩同样的坑」。

- 改动：`HomeViewModel.kt:328-343` 轮询体内补 `_state.update`，回写目标 `DownloadPhase.Preparing(current.status)`；
  写法与 `awaitTerminal` / `awaitDownloadTerminal` 同形，不动布局、不动文案。
- 守卫：新增 `android/app/src/test/java/com/hpu/browseguard/PollLoopStateWritebackTest.kt`，三条断言——
  ① 每条 `await*` 循环体内必须出现它对应镜像阶段的构造（`awaitAwaitingSelection` → `DownloadPhase.Preparing(`），
  这就是「进入 format_preparing 时必须发出状态更新」的可执行形式；
  ② 回写必须排在 `client.task(` 之后（写在前面等于回写上一轮的旧值）；
  ③ 扫描集必须恰好是这三条函数，防止守卫日后扫成空集静默通过。
- **守卫的边界要讲清**：它检查的是源码里存在回写，不是运行时真发出过一次状态更新。
  后者要等 M4 FakeBackend 才能断言（§17.4 遗留 9）。
- **守卫非空转证明**（现场复跑，不是推断）：临时删掉 `awaitAwaitingSelection` 的回写体后
  `./gradlew.bat :android:app:testDebugUnitTest --tests "*PollLoopStateWriteback*" --offline` →
  `3 tests completed, 2 failed`，失败正是 `everyAwaitLoopWritesItsStatusBackToItsPhase` 与
  `writebackHappensAfterThePollNotBefore` 这两条；恢复后全量 **64 通过 / 0 失败**（首次运行 61 → 64，新增 3 条）。
  附带一条方法论：变异时用 `MUTATION_PLACEHOLDER` 这类非法语句会让 `compileDebugKotlin` 先红，
  **测不到守卫本身**——变异必须是「语法合法但语义退回旧行为」的形状（这里即直接删掉回写块）。
- **设备屏显未复拍**：本轮只重跑 JVM 单测（64 通过 / 0 失败）与 `assembleDebug` 构建，`format_preparing`
  的中态截图没补——取证链路不在这一改动的关键路径上，且补拍要重开带旁路的引擎。登记为 §18.2 第 1 步开工前的复核项。

#### 17.5.5 地址保存：上一轮「不可判定」的口径闭环

按上一轮设计的方法执行——动作前后各取一次引擎访问日志行数：
`before_lines=211 → after_lines=213`，delta=2，新增两行恰为
`GET /v1/health 200` + `GET /v1/privacy/selfcheck 200`（同一连接 `127.0.0.1:3894`），
屏上回显 `http://127.0.0.1:8730` 且状态卡「已连接 · 引擎 0.1.0 · 协议 0.1」（`m3mumu-5-save-echo.png`）。
`saveEngineUrl()` 成功分支 → `refreshConnection()`（`HomeViewModel.kt:126-133`）在物理设备上判为**已发生**。
可判性缺口本身不变（屏上仍读不出当前引擎地址），继续挂 §17.4 遗留 4。

#### 17.5.6 取证方法学（本轮踩到的坑，写下来省下一轮）

1. Git Bash 会把 `/sdcard/ui.xml` 重写成 `/Files/Git/sdcard/ui.xml`；`exec-out cat` 需
   `MSYS_NO_PATHCONV=1` 或写成 `//sdcard/ui.xml`。
2. Android 15 上 `uiautomator dump` **写完层级后**收尾 SIGSEGV（rc=139），层级是好的：
   按输出含 `dumped to` 判定，别 `check_returncode()`。
3. 中文经 Git Bash argv 传进 Python 会被 GBK 解码搞坏（脚本内的 UTF-8 常量不受影响）：
   界面目标用 ASCII 别名在脚本内映射。
4. `screencap` 与 `uiautomator dump` 不在同一瞬间——先截屏后 dump 的脚本，PNG 与 XML 是两个时刻，
   不能用 XML 文本反推 PNG 内容。上一轮的「矛盾」一半来自这里，但主因仍是 §17.5.4 的死分支。
5. 折叠区外的节点 uiautomator 不导出，等待逻辑必须滚动感知；Compose 按钮的文本节点
   `clickable=false`，点击要落在其外层 `View`。
6. `adb shell input keyevent --repeat 70 67` 在该镜像上静默无效，清空输入框用逐发 `keyevent 67`。

#### 17.5.8 判定

Android 15 权限面零缺口、主路径到 `done` 且产物哈希全等、取消与续传链路复现、
queued→sniffing 屏显修复后取到两帧、地址保存触发重连有行数证据。**M3 物理设备验收通过，M3 正式关闭。**






---

## 18. M4 开工计划（2026-11-01 裁决落地，M3 关闭后的下一里程碑）

### 18.1 已裁决的架构口径

1. **A 案：事件流 + 任务句柄**。UI 不再持有 `baseUrl`、不再手写轮询 `while`；
   界面只消费后端发出的状态事件，句柄用于取消。
   本轮 `sniffing` / `format_preparing` 两条死分支（§17.5.4、§17.5.7）就是「UI 镜像状态 + 手写轮询」这个形状的债：
   镜像的写入点靠人记得，漏一个就是一条永远取不到值的屏显分支。A 案是把这类缺陷**从结构上消灭**，不是再补一条守卫。
2. **拆两个接口，不是一个**：`SniffBackend` 与 `DownloadBackend` 分开。
   裁决理由（用户口径，原样记入）：
   - 嗅探的实现机理在两端完全不同——PC 侧靠 Playwright，本地侧靠 WebView 拦截或直接 `yt-dlp` extract；
   - 疑难网页（blob / MSE）本地侧可能**根本不支持嗅探**；
   - 混进一个接口，独立模式下 `capability` 就得硬编码「无法嗅探该网页」，UI 状态机复杂度陡增；
   - 拆开之后，UI 只按当前模式注入不同 `SniffBackend`：全本地模式可输入网页 URL 走 WebView，
     也可直接粘贴直链走 yt-dlp；远端模式走 Playwright。
3. 现有 HTTP 实现相应拆成 `RemoteSniffer` / `RemoteDownloader`（今天 `EngineClient` 的 9 个 suspend 方法按语义归队）。

### 18.2 实施顺序（审计喂接口，四步）

| 步 | 内容 | 验收口径 |
| --- | --- | --- |
| 1 | §8-12 非终态错误出口全枚举审计 | 产出 §18.3 的矩阵 + 缺口清单，先补引擎侧出口，再动接口 |
| 2 | 落 `SniffBackend` / `DownloadBackend` 接口，`EngineClient` 拆成 `RemoteSniffer` / `RemoteDownloader` | **UI 行为零变化**：六张截图链路复跑，屏显文案逐字不变 |
| 3 | 补 `FakeBackend`，把 §17.5.4 / §17.5.7 两条静态守卫升级为行为单测 | 「进入 sniffing / format_preparing 时必须发出状态更新」在 JVM 里可断言 |
| 4 | 评估 `youtubedl-android` 可行性 | 结论性报告：ARM 转译依赖性 + APK 体积增量 + 与 `DownloadBackend` 的接缝 |

顺序不可颠倒的原因：第 1 步定义的「每个非终态都有错误出口」是接口契约的一部分——
第 2 步的事件流要能表达失败，就得先知道失败有哪些码；先拆接口再补出口，等于把契约缺口固化进抽象层。

### 18.3 第 1 步产出物清单（审计本体）

**范围**：六个非终态 `queued` / `sniffing` / `awaiting_selection` / `downloading` / `merging` / `post_processing`
（合法后继见 [engine/main.py:112-144](file:///E:/code/android/BrowseGuardVid/engine/main.py#L112) 的转移表，
失败出口唯一写入点是 [`_fail_task`](file:///E:/code/android/BrowseGuardVid/engine/main.py#L379)，
终态优先不覆盖已在那条函数里处理了取消/失败竞态）。

**原则**（驱动这一整步的是收尾③ 的 P0）：每个非终态都必须有错误出口，不能「既不失败也不完成」。
`sniffing` 那次是引用了不存在的枚举成员 → worker 抛 `AttributeError` → 任务永久停在 `sniffing`，
表面看是「卡住」，本质是**该状态没有可用的失败出口**。

**产出物**：

1. **状态 × 错误码矩阵**（入 `docs/`，与 §5 表同源不另立词汇）：
   行=6 个非终态，列=该状态下**可能**落到 `Task.error` 的 §5 码；
   每格标注三态之一：`已有出口`（给出 `_fail_task` 调用点 file:line）/ `应补`（说明触发条件与为什么归这个码）/ `不适用`（写清为什么不适用，不能空着）。
2. **基线数字**（本轮已实测，供矩阵当分母）：`main.py` 里 `_fail_task(` 调用点 **10** 处、
   `ErrorCode.X` 引用去重后 **9** 个码（`UNKNOWN`×4、`FORMAT_UNAVAILABLE`×4、`NO_MEDIA_FOUND`×2、
   `VALIDATION_ERROR` / `TASK_STATE_CONFLICT` / `PARTIAL_DOWNLOAD` / `NOT_FOUND` / `EXTRACTOR_FAILED` / `DRM_PROTECTED` 各 1）；
   `services/` 下 **零** 处引用 `ErrorCode`（该层用哨兵返回值/异常，码只在 API 层组装）。
   §5 共 29 码 → **20 码在引擎侧没有任何生产者**，这是矩阵的第二列来源，也是「本地/远端能力差异」的证据面。
   > **§19 校正（2026-11-02）**：上面这组数字有两处算错，按实测重写——码表总数是 **30**（§5 的 28 + 裁决新增
   > `VALIDATION_ERROR` + `NOT_FOUND`；`engine/models.py:108` 的注释是对的，Kotlin 侧 `ErrorPayload.kt:19`
   > 写「共 29 个」是漏算 `NOT_FOUND`，已顺手改）；零生产者的码是 **16** 个而不是 20 个，
   > 因为 `INVALID_URL` / `UNSUPPORTED_SCHEME` / `SSRF_BLOCKED` / `INVALID_OUTPUT_DIR` / `OUTPUT_DIR_REJECTED`
   > 这 5 个是通过 `SecurityError` 子类的**字符串码**产出的，静态扫 `ErrorCode.X` 看不见它们。
   > 教训写进 §19.6：**按一种写法统计码的生产者，等于统计错**。
3. **缺口清单**：矩阵里每一条 `应补` 落成一条待办，带 P 级。
   P0 判定口径沿用本轮：**能导致任务停在非终态既不失败也不完成**的，一律 P0，不排队直接修。
4. **静态守卫升级**（延续 [engine/test_error_codes.py](file:///E:/code/android/BrowseGuardVid/engine/test_error_codes.py) 的形状扫描）：
   - 现有：全量 `ErrorCode.X` 引用必须是枚举真实成员（收尾③ 加的，防的正是那次 `INTERNAL_ERROR` 笔误）；
   - 新增：每个非终态至少有一个 `_fail_task` 出口可达——**这条能否纯静态判定要先做可行性判断**，
     若调用点与状态的归属靠 AST 推断不成立，降级为「矩阵文档 + 每条出口一条 real 用例」，
     不硬凑一个假守卫（判定结论本身要写进产出物，失败面见 §17.4 遗留 9 的同款口径）。
5. **覆盖对账**：每条 `已有出口` 必须能指到一条把它推进 `failed` 的用例。
   本轮 P0 的教训是既有 real 用例只断言嗅探函数返回 `None`，没人把无媒体 URL 推进状态机，所以全绿——
   **断言返回值的用例不算覆盖，断言终态的用例才算**。

**边界声明**（主动写清，不冒充已完成）：第 1 步只审**引擎侧**状态机；
客户端本地失败按裁决**不参与**该矩阵——本地事件一律 `code = null`（§5 是引擎的发言权，客户端计时器无权替引擎表态），
已由 `LocalTimeoutTest` 的裸字面量守卫钉住。M4 第 2 步的 `SniffBackend` 若引入本地嗅探，
本地失败面的词汇表要另开一节讨论，不能借道这张矩阵。

### 18.4 M3 遗留里挂在 M4 的条目

- §17.4 遗留 2 / 3：`1080p · h264`、`视频（无音轨）` 仅单测覆盖，需分离轨素材复核；`sniffing` 屏显两帧已补，`format_preparing` 中态未复拍（§17.5.7）。
- §17.4 遗留 4 / 5：可判性缺口（屏上读不出当前引擎地址）与 `engine_url_field` 示例地址放大器，归设置页批次。
- §17.4 遗留 6：收尾④ 建议 A（`Log` 只允许出现在 `ui/` 层 + 静态守卫）**已批准未落地**。
- §8 已登记的 M4 候选（勿提前做）：全新下载过 Content-Type 门禁（strict xfail）、`.part` 名换稳定 key、
  HLS `percent` 在客户端显示层单调化、yt-dlp `noprogress` 清理 stdout、`fetch_manifest_text` 正文体积上限、探测修复无回归复核。
- 设备侧待办：物理设备 adb 连接在本轮环境回收时随 `adb kill-server` 断开，下次取证前需重连
  `adb connect 192.168.31.57:5555` 并重设 `adb reverse tcp:8730 tcp:8730`（设备事实见 §17.5 表头）。

---

### 18.5 M4 第 4 步结案：放弃本地 yt-dlp 内嵌（2026-11-02 裁决）

研究结论采纳，M4 架构目标按用户裁决调整三条：

1. **本地能力只做 WebView 嗅探（拦截直链）**，这是 M4 的核心，覆盖多数日常场景；
2. **不再尝试把 yt-dlp / FFmpeg 打包进 APK**。未来若必须在纯手机端解析复杂站点，
   优先评估**独立编译的静态 yt-dlp 二进制**（`ProcessBuilder` 调用），而不是依赖这个陈旧 AAR；
3. **PC 引擎侧的 Playwright + yt-dlp 继续作为增强模式的强力兜底**。

依据（研究轮实测，非推测）：四个 ABI 齐备、x86_64 原生载荷完整（MuMu 不需要 ARM 转译，
`abilist` 里的 `arm64-v8a` 与 `libnb.so` 桥在这条链路上用不上）；体积 x86_64 原生 ≈64 MB、
split APK 69.7 MB、首启再解包 ~55 MB；**硬阻塞 #369**（ffmpeg 载荷缺 `libc++_shared.so`，
`librubberband.so` 列为 `NEED` → `CANNOT LINK EXECUTABLE`，open 未修）；**2026 年零提交**、
内置 yt-dlp 停在 2025.11.12。W^X 与 16 KB page 是两条虚惊（前者 exec 在 `nativeLibraryDir`，
后者只影响 Play 上架、不影响本地 4 KB 镜像运行）。

这条裁决直接改写 §18.2 第 2 步的接口形状，最终形态见 §19.5。

---

## 19. M4 第 1 步：非终态错误出口全枚举审计（2026-11-02）

### 19.1 方法与可判定性结论

范围＝六个非终态；失败出口唯一定义为「`Task.error` 被写入且状态转到 `failed`」，
入口是 [`_fail_task`](file:///E:/code/android/BrowseGuardVid/engine/main.py#L379)，合法后继表在
[main.py:111](file:///E:/code/android/BrowseGuardVid/engine/main.py#L111)。

**§18.3 产出物 4 的判定结论：per-state 的「该状态有没有可达失败出口」用 AST 判不出来**，理由是三条具体的：
① 同一个 `_fail_task` 调用点服务的状态由运行时分支决定（`_download_real` 里的 6 个出口全在 `downloading`，
但函数本身被 `_advance` 间接调用）；② `ENGINE_MODE` 的 stub/real 分岔让同一行代码只在一种模式下可达；
③ 部分失败根本不落 `Task.error`（API 层 4xx）。硬凑一个「按状态扫调用点」的守卫只会给出假绿。

所以按裁决降级为**矩阵 + 每条出口一条断言终态的用例**，并把静态守卫收缩到**两条真能判定的不变式**
（[engine/test_nonterminal_exit_guards.py](file:///E:/code/android/BrowseGuardVid/engine/test_nonterminal_exit_guards.py)）：
转移表里每个非终态必须把 `FAILED` 列为合法后继；`_advance` / `_download_real` 两个驱动函数不得存在
绕过兜底包装的裸调用。第三条守卫检查兜底包装本身是否真的调用 `_fail_task`（只 log 不置状态＝假兜底）。

### 19.2 矩阵：状态 × 失败出口

| 状态 | 已有出口（§5 码 ← 调用点） | 判定 |
| --- | --- | --- |
| `queued` | 无任务态出口 | **不适用（有理由）**：该状态的全部拒绝都在创建期以 4xx 返回（`INVALID_URL` / `UNSUPPORTED_SCHEME` / `SSRF_BLOCKED` / `INVALID_OUTPUT_DIR` / `OUTPUT_DIR_REJECTED` / `VALIDATION_ERROR`，见 [create_task](file:///E:/code/android/BrowseGuardVid/engine/main.py#L874)），任务对象根本不存在；创建成功后 `queued` 的唯一出路是被领取。停滞风险由 GET 侧兜底 [`_submit_if_queued`](file:///E:/code/android/BrowseGuardVid/engine/main.py#L920) 覆盖 |
| `sniffing` | `EXTRACTOR_FAILED`、`NO_MEDIA_FOUND` ← [main.py:566](file:///E:/code/android/BrowseGuardVid/engine/main.py#L566)（`detail.reason=sniff_failed`，码由 [`_sniff_real`](file:///E:/code/android/BrowseGuardVid/engine/main.py#L601) 返回）；`UNKNOWN` ← [main.py:401](file:///E:/code/android/BrowseGuardVid/engine/main.py#L401)（`advance_crashed`，本轮新增） | **本轮修复前不完整**：`_sniff_real` 只捕 `SniffError`，嗅探器抛其它异常、或 `MediaStreamType(kind)` 构造失败，都会逃逸到 worker 的 `except` 里**只记日志不置状态** → 永久 `sniffing`。已复现（§19.4） |
| `awaiting_selection` | `UNKNOWN` ← [main.py:729](file:///E:/code/android/BrowseGuardVid/engine/main.py#L729)（24h 超时扫描，`detail.reason=awaiting_selection_timeout`） | **有出口，但语义错位**：超时借 `UNKNOWN` 表达是保守做法（不新增码，符合裁决）；真正的缺口是空格式表（见 §19.3 缺口 3） |
| `downloading` | `NO_MEDIA_FOUND` ← :301；`FORMAT_UNAVAILABLE` ← :306 / :313；`SecurityError` 动态码 ← :354（`SSRF_BLOCKED` / `OUTPUT_DIR_REJECTED` 等）；`PARTIAL_DOWNLOAD` ← :358；`UNKNOWN` ← :362(`download_failed`) / :685(`missing_sniff`) / :416(`download_crashed`，本轮新增) | **出口最全，但归因丢失**：八个网络/磁盘类码全部被 :362 的 catch-all 收成 `UNKNOWN`（见 §19.3 缺口 2） |
| `merging` | **无** | **应补，且更早的问题是「进不来」**：全仓没有任何一处转移到 `merging`（见 §19.3 缺口 1） |
| `post_processing` | **无** | 同上 |

第三类（不进 `Task.error`，只出现在 HTTP 响应）：`VALIDATION_ERROR`(422)、`NOT_FOUND`(404)、
`TASK_STATE_CONFLICT`(409)、`DRM_PROTECTED`(422)。这一类不属于「状态机出口」，列出来是为了让
「29/30 个码里有多少真有生产者」这个问题有分母。

### 19.3 缺口清单（按 P 级）

**P0（本轮已修，§19.4）**：逃逸异常没有出口 → 任务永久停在 `sniffing` / `downloading`。
判定依据正是用户给的口径「凡导致任务永久停滞的立刻 P0 直接修」，且修复前四条用例全部实测卡死。

**P1（本轮裁决：三条全修。①已升 P0 并落地＝§19.7；②已落地＝§19.8；③见 §19.9）**：

1. **`postActions` 被静默丢弃**（**已修**：按裁决升为 P0，见 §19.7）。`merging` / `post_processing` 无入口，
   [services/merger.py](file:///E:/code/android/BrowseGuardVid/engine/services/merger.py) 有实现、有自己的单测，
   但 `main.py` 与 `services/downloader.py` **都不调用它**（grep 全仓：`merge_streams` 只出现在 `tests_real/test_merger_*`）。
   后果链：客户端「合并音视频」开关 → [HomeViewModel.kt:273](file:///E:/code/android/BrowseGuardVid/android/app/src/main/java/com/hpu/browseguard/ui/HomeViewModel.kt#L273)
   发出 `postActions=[merge]` → 引擎收下、持久化、回显 → 从不执行 → 任务报 `done`，产物其实没合并。
   **按 §5 标准这是「静默降级且无审计行」**，与 4b-2 那条 `selector_degraded` 的裁决同源；
   `MERGE_FAILED` / `TRANSCODE_FAILED` 因此永远零生产者，而客户端 `ProtocolCopy.kt:14-15`
   给这两个状态写了「合并音视频中」「后处理中」两条**取不到值的屏显文案**——和本轮修掉的死分支同一形状。
   建议分两档：本轮先补 `post_actions_ignored` 审计行（一行，不动行为）；接线 `merge_streams` +
   引入 `merging` 状态与 `MERGE_FAILED` 出口归 M4 下载链路批次。**两档都没做，等点头。**
2. **`downloading` 的失败归因塌成 `UNKNOWN`**（**已修**：按裁决落地，见 §19.8）：`NETWORK_TIMEOUT` / `DNS_FAILURE` / `TLS_ERROR` /
   `NETWORK_HTTP_ERROR` / `RATE_LIMITED` / `DISK_FULL` / `WRITE_PERMISSION_DENIED` / `ENGINE_BUSY`
   八个码在引擎侧没有任何生产者，全被 :362 的 `except Exception` 吞掉。用户看到的是一句「未知错误」，
   重试按钮的 `retryable` 也跟着失真（`UNKNOWN` 不可重试，而这几个里有一半是 `retryable=true`）。
   缺的是一张「downloader 异常类型 → §5 码」映射表；这直接决定 M4 第 2 步 `DownloadBackend` 事件流能不能表达失败。
3. **空格式表落在客户端补契约**（**已按裁决修客户端侧＝§19.9**；协议仍不可分的那半句留在 §19.9 遗留 1）：real 模式下 `record.formats` 为 `[]` 时任务照样进 `awaiting_selection`，
   `GET /formats` 返回 200 空列表，由客户端 [HomeViewModel.kt:225](file:///E:/code/android/BrowseGuardVid/android/app/src/main/java/com/hpu/browseguard/ui/HomeViewModel.kt#L225)
   自己造 `FORMAT_UNAVAILABLE` 文案。引擎侧「有格式表可给」与「给不出」两种情况在协议上不可分。

**P2（记录，不排队）**：

4. `_sniff_real` 把 yt-dlp 解析失败静默收成 `record.formats = []`（只 warn），失败被推迟到下载阶段才显形。
5. `start_download` 的 API 预检（[main.py:986](file:///E:/code/android/BrowseGuardVid/engine/main.py#L986)）
   只捕两种契约异常，其它异常 → 500，任务停在 `awaiting_selection` 等 24h 扫描（有出口，但请求面难看）。
6. 码表注释与实际条数不符（Kotlin 写 29、实为 30）——**本轮已顺手改**，见 §19.6。

### 19.4 本轮 P0 修复与取证

改动（[engine/main.py](file:///E:/code/android/BrowseGuardVid/engine/main.py)）：

- 新增 [`_advance_safely`](file:///E:/code/android/BrowseGuardVid/engine/main.py#L388) /
  [`_download_safely`](file:///E:/code/android/BrowseGuardVid/engine/main.py#L404)：捕获逃逸异常 →
  `logger.exception` 留可 grep 的审计行（`advance_crashed` / `download_crashed` + task_id + 异常类型）→
  `_fail_task(UNKNOWN)`。兜底放在**驱动入口**这一层，不在每个调用点补 try。
- 四个调用点全部改走包装：`_process_task` 的两条、`get_task` 的同步兜底、`start_download` 的 worker 未启动分支、
  `_do_download` 的 real 分支。
- 顺带堵一个**伪造终态**：`_advance` 里 `downloading → done` 那段是桩语义，却在 real 模式 worker 停摆时
  也会执行，连字节数一起写死成 fixture 的 524288000。现按 `ENGINE_MODE == "stub"` 收窄，
  real 侧改为 `advance_skipped_downloading` 审计行——下载状态归 `_download_real` 所有。

取证（现场复跑，[engine/tests_real/test_nonterminal_exits.py](file:///E:/code/android/BrowseGuardVid/engine/tests_real/test_nonterminal_exits.py) 五条，全部打真实崩溃点）：

| 用例 | 崩溃点 | 修复前实测 | 修复后 |
| --- | --- | --- | --- |
| 嗅探器抛非 `SniffError` | `RuntimeError` 逃逸 `_sniff_real` | 卡 `sniffing`，5s 超时红 | `failed` + `UNKNOWN` + `advance_crashed` |
| 嗅探结果带未知 `kind` | `MediaStreamType("blob")` ValueError（在两个 try 之外） | 卡 `sniffing` | 同上 |
| 自动领取时解析器炸 | `resolve_format_expression` 抛非契约异常 | 卡 `downloading` | `failed` + `download_crashed` |
| worker 侧下载入口炸 | 同上，走 `POST /download` 链路 | 卡 `downloading` | 同上 |
| 兜底不是静默降级 | `caplog` 断言审计行含 `advance_crashed` 与 task_id | — | 通过 |

修复前的四条红是**实测红**（输出里逐条能看到 `'status': 'sniffing'` / `'downloading'` 直到超时），
不是推理红。守卫也做了变异复跑：把 `_do_download` 改回裸调用 `_download_real` →
`test_drivers_are_only_called_from_their_safe_wrappers` 红并报出 `main.py:265`，恢复后全绿。

套件基线（本轮复跑）：引擎桩 **173 passed / 152 deselected**（原 170，+3 条守卫）、
引擎 real **151 passed + 1 xfailed**（原 146，+5 条用例）、Android 单测 **64 passed / 0 failed** 不变。

### 19.5 M4 第 2 步接口最终形态（按 §18.5 裁决改写）

三条由本次审计直接决定的约束：

1. `DownloadBackend` **只有远端 HTTP 一个实现**，不再有 `LocalDownloader` 位置；
2. `SniffBackend` 两个实现：`RemoteSniffer`（Playwright，走 HTTP）与 `LocalWebViewSniffer`（拦截直链），
   接口必须允许本地实现**声明「这条 URL 我嗅不了」并交回一个本地原因**，而不是伪造 §5 码
   （沿用 `code=null` 原则与 `LocalTimeoutTest` 的裸字面量守卫）；
3. 事件流的失败事件必须携带 §5 码 + `retryable`——这依赖 §19.3 缺口 2 先补齐映射表，
   否则接口只能传 `UNKNOWN`，等于把归因丢失固化进抽象层。

**接口正文（A 案：事件流 + 任务句柄；UI 侧不再出现 `baseUrl`、`withTimeout` 和三个 await 轮询环）**：

```kotlin
/** 失败的二元归属：协议码只在「引擎真的这么答」时出现，本地判断一律 code = null。 */
sealed interface BackendFailure {
    /** 来自引擎 §5 码表，字段直接取自 ErrorPayload，客户端不再自行拼码。 */
    data class Protocol(
        val code: ErrorCode,
        val retryable: Boolean,
        val message: String,
        val detail: Map<String, JsonElement>,
    ) : BackendFailure

    /** 客户端本地判定：超时、WebView 嗅不到、WebView 不可用——没有协议码可言。 */
    data class Local(val reason: LocalReason, val message: String) : BackendFailure
}

enum class LocalReason { TIMEOUT, NO_DIRECT_LINK, WEBVIEW_UNAVAILABLE, URL_UNLOADABLE }

/** 远端：一次任务的生命周期由引擎状态机驱动，句柄即 taskId。 */
data class TaskHandle(val taskId: String, val sourceUrl: String)

interface SniffBackend {
    /** 后端级静态能力位：只用于 UI 决定「本地/远端」入口是否可点，不表达单条 URL 的成败。 */
    val capabilities: SniffCapabilities

    /** 单条 URL 的成败走事件流：本地实现嗅不到就发 Refused，而不是让 capability 替它说话。 */
    fun sniff(url: String, mode: TaskMode): Flow<SniffEvent>

    suspend fun cancel(handle: TaskHandle?)
}

data class SniffCapabilities(
    val progressiveDirect: Boolean,   // mp4/mkv 直链
    val hls: Boolean,
    val dash: Boolean,
    val drmDetection: Boolean,        // 本地 WebView 不解析 license → false
    val runsOnDevice: Boolean,
)

sealed interface SniffEvent {
    /** 远端专属：queued/sniffing 中间态，正是 §17.5 那两条屏显死分支的数据源。 */
    data class Status(val taskId: String, val status: TaskStatus) : SniffEvent
    data class Found(val items: List<SniffResult>, val formats: List<FormatInfo>) : SniffEvent
    /** 本地实现的「这条我做不了」：code = null，文案由 LocalReason 侧给，不借 §5。 */
    data class Refused(val reason: LocalReason, val message: String) : SniffEvent
    data class Failed(val failure: BackendFailure) : SniffEvent
}

interface DownloadBackend {
    /** 唯一实现是 HttpDownloadBackend：内部持 baseUrl，POST /download + 轮询 GET /tasks/{id}。 */
    fun download(handle: TaskHandle, selection: DownloadSelection): Flow<DownloadEvent>
    suspend fun cancel(handle: TaskHandle)
}

data class DownloadSelection(
    val formatSelector: FormatSelector,
    val namingTemplate: String?,
    val postActions: List<PostAction>?,
)

sealed interface DownloadEvent {
    data class Status(val status: TaskStatus) : DownloadEvent
    data class Progress(val progress: TaskProgress) : DownloadEvent
    data class Succeeded(val productPath: String?) : DownloadEvent
    data class Cancelled(val status: TaskStatus) : DownloadEvent
    data class Failed(val failure: BackendFailure) : DownloadEvent
}
```

两个实现各归各的位置：`RemoteSniffer` 与 `HttpDownloadBackend` 复用现
[EngineClient](file:///E:/code/android/BrowseGuardVid/android/app/src/main/java/com/hpu/browseguard/data/EngineClient.kt)
的九个端点；`LocalWebViewSniffer` 只实现 `SniffBackend`，`capabilities.drmDetection = false`、
`hls = false`（blob/MSE 抓不到切片清单时发 `Refused(NO_DIRECT_LINK)`），并且**不实现 `DownloadBackend`**——
这正是 §18.5 放弃本地内嵌后接口应有的形状。

三条本次要一起认领的迁移影响（不是新需求，是接口挪动的连带账）：

1. 三个轮询环（`awaitTerminal` / `awaitAwaitingSelection` / `awaitDownloadTerminal`）搬进
   `HttpDownloadBackend` / `RemoteSniffer` 内部后，[PollLoopStateWritebackTest](file:///E:/code/android/BrowseGuardVid/android/app/src/test/java/com/hpu/browseguard/PollLoopStateWritebackTest.kt)
   的「恰好扫到三个 await 环」会失去锚点——守卫必须跟着搬到新东家，且搬完要用同一个变异体
   （删掉回写块）复跑一次，不能只改文件名当作搬完了。
2. `LocalTimeouts` 的三个 `withTimeout` 归后端所有（超时发 `Failed(Local(TIMEOUT))`），
   `LocalTimeoutTest` 的裸字面量与 `code = null` 断言随之改指向后端产出，而不是 ViewModel。
3. `BackendFailure.Protocol` 只允许由 `ErrorPayload` 反序列化产生，**禁止客户端用 `ErrorCode.X` 手工构造**；
   这条要配一条与 `LocalTimeoutTest` 同形的静态守卫（源码里 `BackendFailure.Protocol(` 只出现在
   EngineClient 的映射处），否则 §19.3 缺口 2 修完也会再被客户端自己拼码绕过。

留白（等裁决，本轮不动）：本地 WebView 拦到 mp4 直链后，若此刻没有 PC 引擎，「把这条直链落到手机存储」
这件事按上面的接口**无人负责**——`DownloadBackend` 只剩远端实现。补它需要的是第三个实现
（单条 HTTP GET + `.part` 续传，不需要 yt-dlp/FFmpeg，与 §18.5 的裁决不冲突），
但会重新打开「本地下载」这条链路，属于范围扩张，按分段交付的口径先记在这里。

### 19.6 两条方法论记录

- **按一种写法统计码的生产者＝统计错**：`SecurityError` 的子类用字符串码（`"SSRF_BLOCKED"`），
  API 层再 `ErrorCode(exc.code)` 组装，静态扫 `ErrorCode.X` 会漏掉 5 个码。本轮 §18.3 的
  「29 码 / 20 无生产者」就是这么算错的，校正在该节内留痕。
- **注释里的条数也是代码**：Kotlin `ErrorPayload.kt` 写「共 29 个」而枚举有 30 条，
  和 4b 那两次「注释/文案与实际行为相反」是同一类问题；已改为 30 并把 `NOT_FOUND` 记进新增说明。

### 19.7 P1① 升 P0 修复：`merging` 真接线 + 引擎侧 FFmpeg 落地（2026-11-02）

裁决口径：「`merging` 和 `post_processing` 没进状态机说明下载链路是半成品，用户选了合并实际没合」。
本轮把 `merge` 这条后处理动作真正接上；`post_processing`（转码）仍**不接线**，但不再静默——
`post_action_unsupported types=…` 审计行见下。

三条设计决定（都是先碰壁才定下来的）：

1. **不让 yt-dlp 一次下完**。`-f best` 遇到只有分离轨的源时，由它内置 `FFmpegMerger` 自己合：
   产物确实是单文件，但那一次 FFmpeg 调用对引擎不可见，§3.7 的 `merge` 动作与 §4 的
   `downloading → merging` 转移就永远没有落点。且 yt-dlp **没有** `--no-merge` 这类 compat 开关（实测）。
   所以改成两次「单格式单轨」下载（`bestvideo` + `bestaudio`），每次都无可合，合并才真的归
   [services/merger.py](file:///E:/code/android/BrowseGuardVid/engine/services/merger.py) 执行
   （`services/downloader.py` 的 `download_tracks`）。
2. **可拆性判定发生在下载之前，用元数据格式表判定**，不靠捕获下载异常猜。`bestvideo`/`bestaudio`
   是「仅单轨」选择器，在只有 muxed 流的源上直接抛 `Requested format is not available`——
   与 4b-2 同一课：信息不足时不能等到执行期才醒
   （`services/formats.py` 的 `has_separate_tracks` / `track_expressions`）。
3. **中间轨用同目录带标签名**（`Title.bgv-video.mp4` / `Title.bgv-audio.m4a`），不开临时子目录：
   `security.validate_output_target` 要求产物**直接**位于输出目录。合并产物名由视频轨名去掉标签段得到，
   `drop_track_files` 只认带标签的名字，不认识的文件绝不动手。

落点（[engine/main.py](file:///E:/code/android/BrowseGuardVid/engine/main.py)）：

- `main.py:324 _merge_plan`：要不要拆轨，三个跳过理由各留一行
  `post_action_noop … reason=single_file_source / selector_not_splittable / no_separate_tracks`；
  没点合并则一行都不打（不制造噪声）。`main.py:272 _audit_post_actions`
  对未实现的动作打 `post_action_unsupported types=…`。口径同 4b-2：**降级可以，无痕不行**。
- `main.py:358 _merge_stage`：`downloading → merging` → 真 `merge_streams` → 成功回收中间轨、
  失败落 **`MERGE_FAILED`**（§5 里这个码第一次有生产者）。两条现场边界单独处理：合并前就存在的
  同名产物**不删**（失败归失败，不顺手销毁用户文件）；本次自己造出来的半截文件**才**回收。
  合并途中取消 → 产物保留、终态归 `cancelled`，不被 `done` 覆盖。
- 桩模式也走完整状态：`downloading → merging → done`，客户端那两条屏显文案不再是死分支。

取证（现场复跑，不是推理）：

| 用例（`tests_real/test_merge_wiring.py`） | 断言 | 结果 |
| --- | --- | --- |
| `test_merge_routes_through_merging_and_replaces_inputs_with_product` | FFmpeg 被调时 `status==merging`；产物＝`Sample.mp4`；目录无 `*bgv-*` | 绿 |
| `test_real_ffmpeg_merge_produces_one_dual_track_file` | **真 FFmpeg** 合出的单文件，ffprobe 数出 `{video, audio}` 两条流 | 绿 |
| `test_merge_failure_lands_merge_failed_and_spares_prior_product` | `failed` + `MERGE_FAILED`（非 `UNKNOWN`），既有产物一字节未动 | 绿 |
| `test_merge_failure_removes_the_garbage_it_just_made` | 本次造的半截产物被回收 | 绿 |
| `test_cancel_during_merge_keeps_product_and_cancelled_wins` | 终态 `cancelled`、`error is None`、文件保留、`merge_cancelled` 留痕 | 绿 |
| `test_direct_source_keeps_single_file_path_and_audits_the_noop` | 直链不拆轨 + `post_action_noop reason=single_file_source` | 绿 |
| `test_download_tracks_reports_combined_bytes_with_audio_offset` | 进度序列逐元相等：`[(A,A),(A+V,A+V)]` | 绿 |
| `test_download_tracks_reclaims_both_inputs_when_second_track_fails` | 顺序 `bestaudio→bestvideo`，半途失败也回收 | 绿 |
| 桩侧 6 条（`test_merge_semantics.py`） | 表达式表、三个 noop 理由互可区分、桩状态机过 `merging`、`post_action_unsupported` | 绿 |

假/真边界写在 docstring 里：接线用例用假下载器 + 假合并函数；真 FFmpeg 用例把**网络取字节**换成
「复制真素材到轨名」，合并与 ffprobe 校验都走真代码。

变异复跑（证明用例不空转）：

| 变异体 | 预期症状 | 实测红名单 |
| --- | --- | --- |
| 删掉 `_merge_stage` 里的 `downloading → merging` 转移 | FFmpeg 在非 merging 态执行 | `test_merge_routes_through_merging…` 红（桩侧 6 条不受影响，代码路径确实分开） |
| `has_separate_tracks` 退化成本地有响应就走（`bool(items)`） | muxed-only 源被误判可拆 | `test_has_separate_tracks_requires_both_single_tracks`、`test_merge_plan_reasons_are_distinguishable` 红 |
| 失败清理改成无条件 `target.unlink()` | 顺手销毁用户既有产物 | `test_merge_failure_lands_merge_failed_and_spares_prior_product` 红 |
| 成功后不再回收中间轨 | 用户目录留工地 | 接线用例 + 真 FFmpeg 用例双双红 |
| 进度不加音频偏移 | 进度条从 30% 跳回 5% | `test_download_tracks_reports_combined_bytes_with_audio_offset` 红 |

套件基线（修复后复跑）：引擎桩 **179 passed / 160 deselected**（+6 条 §19.7 语义用例）、
引擎 real **159 passed + 1 xfailed**（+8 条 §19.7 接线/真合并用例）。Android 侧本轮未改代码、
Gradle 未复跑——`ProtocolCopy.kt` 那两条屏显文案从此理论上取到值，但**设备取证要等真机轮**，不在此处冒充证据。

遗留（不掩饰）：`TRANSCODE_FAILED` / `ENGINE_BUSY` 仍零生产者。转码未接线（只有 `post_action_unsupported`
审计行）；`ENGINE_BUSY` 需要引擎真的会「拒活」才有意义（当前 worker 队列无界，拒不了活），**不造码**。

### 19.8 P1② 修复：下载失败归因按 §5 落码（2026-11-02）

裁决口径：「把网络/磁盘错误按 §5 正确映射，不要全塞 `UNKNOWN`」。这也是 §19.5 接口约束 3
（`DownloadEvent.Failed` 必须带真码 + 真 `retryable`）的前提——映射表不落地，抽象层只能传 `UNKNOWN`。

四条设计决定：

1. **归因表独立成模块**（[services/failures.py](file:///E:/code/android/BrowseGuardVid/engine/services/failures.py)），
   不写在 `except` 里。理由：同一条表要被两处 `except`（完整性 / 兜底）和桩、real 两套用例共用，
   贴在 `main.py` 里就只能是第二份真相。
2. **证据优先级＝异常类型 → 机制文本 → HTTP 状态**，且机制描述**压过**状态码：
   `HTTP Error 504` 加「timed out」时归 `NETWORK_TIMEOUT`，状态降级成 `detail["status"]` 附注。
   504 是网关转述，真正致命的是读超时，两者的 `retryable` 与用户该做的事不同。
3. **判不出来就说判不出来**：`fallback` 由调用方给语义——`DownloadIntegrityError` 兜 `PARTIAL_DOWNLOAD`
   （这类异常的确定含义就是「产物不完整」），其余兜 `UNKNOWN`；**绝不拿 `NETWORK_*` 冒充**。
   同 §19.4 的不造码原则。
4. **detail 只放 `reason` / `status`，异常文本一律不落**（§1.7 脱敏）。用例分别盯两侧：
   `test_detail_never_carries_the_exception_text`（桩，令牌表 `://` / host / `cookie` / `referer` / `.m3u8`）、
   `test_attribution_does_not_leak_the_url_into_logs_or_detail`（real，`caplog` 全文扫）。

落点：[`classify_download_failure`](file:///E:/code/android/BrowseGuardVid/engine/services/failures.py#L156)
（`main.py:479` 与 `main.py:491` 两处 `except` 各调一次，审计行由 `type=异常类名` 换成同时带 `code=…`，
这样屏上的码和日志能对上）。

七码的生产者（§19.3 缺口 2 的名单，全部由 real 用例现场打出，不是映射表单测）：

| §5 码 | 类型证据 | 文本 / 状态证据 | real 端到端用例 |
| --- | --- | --- | --- |
| `NETWORK_TIMEOUT` | `socket.timeout` / `TimeoutError` / `httpx.*Timeout`（**空消息也认**） | `timed out` / `read operation timed` | `test_transport_failure_end_to_end[exc0/exc1]` |
| `DNS_FAILURE` | `socket.gaierror` | `getaddrinfo` / `name resolution` | `[exc2]` |
| `TLS_ERROR` | `ssl.SSLError` | `certificate verify failed` | `[exc3]` |
| `DISK_FULL` | `OSError` errno `ENOSPC`/`EDQUOT`、WinError `39`/`112` | `no space left` | `[exc4]` |
| `WRITE_PERMISSION_DENIED` | `PermissionError`、errno `EACCES`/`EPERM`/`EROFS`、WinError `5` | `permission denied` / `read-only file system` | `[exc5]` |
| `NETWORK_HTTP_ERROR` | — | 前缀式三位状态（404/500/502/503…） | `test_http_status_end_to_end` |
| `RATE_LIMITED` | — | `429` / `too many requests` | `[429]` |
| `PARTIAL_DOWNLOAD` | `httpx.RemoteProtocolError` | `peer closed connection…`（httpx 原话） | `[exc6]` + `test_empty_body_still_lands_partial_download` |

顺带一条 `AUTH_REQUIRED`（401/403，`retryable=False`）：**审计时漏记**——§19.3 的八个码名单里没有它，
而它当时同样零生产者（全仓 grep 只有 `models.py` 的枚举与码表两处）。本轮状态 Pass 把它补上了，
按实说，不算 P1② 认领的范围，只是留痕。

取证（现场复跑，[engine/test_failure_attribution.py](file:///E:/code/android/BrowseGuardVid/engine/test_failure_attribution.py)
42 条 + [engine/tests_real/test_failure_attribution_real.py](file:///E:/code/android/BrowseGuardVid/engine/tests_real/test_failure_attribution_real.py) 16 条）：

| 用例族 | 断言 | 结果 |
| --- | --- | --- |
| 桩 8 条 `test_exception_type_decides_before_text` | 类型先于文本，空消息超时也归对码 | 绿 |
| 桩 11 条 `test_wrapped_failure_text_is_attributed` | yt-dlp 把下层失败压成一行文本（含被包成 `HTTP 403` 的写法）后仍判得出 | 绿 |
| 桩 4 条 `test_http_status_lands_in_detail_machine_readably` | `detail` 里 `status` 是整数不是字符串，重试策略可直接读 | 绿 |
| 桩 `test_integrity_error_falls_back_to_partial_download` | 完整性异常无依时兜 `PARTIAL_DOWNLOAD`，不落 `UNKNOWN` | 绿 |
| 桩 2 条 `test_detail_never_carries_the_exception_text` | detail 不含 `://` / host / `cookie` / `referer` / `secret` / `session` / `.m3u8` | 绿 |
| 桩 `test_windows_disk_full_and_write_denied_come_from_winerror` | Windows 只给 `winerror` 不给 `errno` 时也分得开 | 绿 |
| 桩 `test_cause_chain_is_walked_not_just_the_outermost_exception` | 内层 `gaierror` 被 yt-dlp 包住仍认得出 | 绿 |
| 桩 `test_self_referencing_chain_does_not_hang` | `__cause__` 自指不死循环（带 `seen` 集） | 绿 |
| 桩 `test_timeout_beats_a_gateway_status_when_both_appear` / `…mechanism_description_wins_over_status…` | 优先级不反客为主，状态留在 detail | 绿 |
| 桩 `test_undecidable_failure_says_so_instead_of_borrowing_a_network_code` | 判不出来＝`UNKNOWN`，不借网络码 | 绿 |
| 桩 `test_mapped_codes_keep_their_retryable_semantics`（10 行） | 码 → §5 码表 `retryable` 逐项相等 | 绿 |
| real 5 条 `test_http_status_end_to_end` | 真 `download_direct` + `httpx.MockTransport`，码/`detail`/`retryable` 全中 | 绿 |
| real 7 条 `test_transport_failure_end_to_end` | 真下载器抛出真异常，终态 `failed` + `download_failed … code=…` 审计行 | 绿 |
| real `test_the_seven_network_and_storage_codes_now_have_producers` | 8 个 handler 跑一遍，断言「审计时零生产者的七个码」⊆ 实际产出的码集合 | 绿 |
| real `test_attribution_does_not_leak_the_url_into_logs_or_detail` | 日志与 detail 都不含 URL 片段 | 绿 |
| real `test_undecidable_failure_says_unknown_instead_of_borrowing_a_network_code` | 真链路里抛 `ValueError` → `UNKNOWN`，不凑成 `NETWORK_HTTP_ERROR` | 绿 |

真/假边界：real 侧唯一的假件是**传输层**（`httpx.MockTransport`，把状态码与异常注入真 `download_direct`），
归因、`_fail_task`、状态机、审计行、落盘校验全走真代码。

变异复跑（证明用例不空转，四个变异体各自跑完立即还原，`git diff` 与原文逐字节相等）：

| 变异体 | 桩红 | real 红 | 说明 |
| --- | --- | --- | --- |
| A：丢弃类型 Pass 的结果 | 4 | 1 | 只有「空消息超时」必须靠类型：桩侧 `TimeoutError()` / `ConnectTimeout("")` / `ReadTimeout("")` 三条，加 WinError 磁盘满一条；real 侧只有 `ReadTimeout("")` 一条。其余类型用例的异常对象恰好自带可读文本，会被文本层兜住——**这不等于文本层可替代类型层**，只说明用例覆盖面长这样 |
| B：`_code_from_status` 恒 `None` | 8 | 5 | 状态 Pass 真在生产码 |
| C：`chain` 退化成只看最外层 | 1 | 0 | 链条遍历只有桩那条专用例依赖；real 侧的异常是直接抛的，没有包装层，**这是个覆盖面缺口，不是绿**（见下方遗留） |
| D：`_STATUS_BY_CODE = {}` | 4 | 2 | 401/403 的 `AUTH_REQUIRED` 归属有人守 |

套件基线（修复后复跑）：引擎桩 **222 passed / 176 deselected**、引擎 real **175 passed + 1 xfailed**。
Android 侧未改代码。数量核对（三条都实测，不是做算术）：HEAD 干净抽取 **179 passed**；
工作树移除本轮新文件后 **180 passed**——多的 1 条不是新用例，而是既有静态守卫
`test_error_codes.py::test_referenced_error_codes_exist` 的参数集自动扫到了新模块 `failures.py`；
再加 42 条归因用例 ＝ 222。real 侧 175 − 16 ＝ 159，与 §19.7 记录一致。

遗留（不掩饰）：
- `ENGINE_BUSY` 仍零生产者（理由同 §19.7：worker 队列无界，拒不了活），**不造码**。
- 变异 C 暴露的 real 侧缺口：没有「下层异常被 yt-dlp 包一层再抛」的端到端用例，链条遍历在 real 只靠桩守。
  登记为已知缺口，按 §19.4 的口径归 M4 第 2 步 `DownloadBackend` 事件流的用例批次一起补，本轮不扩范围。

### 19.9 P1③ 修复：空数组改走本地判定，客户端不再替引擎发言（2026-11-02）

裁决口径：「引擎回空数组时，客户端应走本地 `Refused` 提示（如"未发现可用格式"），绝不伪造
`FORMAT_UNAVAILABLE` 协议码」。审计时点到的只有格式表那一处，落码时发现**同一形状的还有一处**
（嗅探空结果借 `NO_MEDIA_FOUND`），两处都在同一条 200 响应后面，一起按同一口径改——
不是扩范围，是同一处缺陷的两个实例。

引擎侧真相（复核过，不是猜）：`_sniff_real` 无媒体时发 `NO_MEDIA_FOUND`、`_download_real` 解析不出
表达式时发 `FORMAT_UNAVAILABLE`（[main.py:407](file:///E:/code/android/BrowseGuardVid/engine/main.py#L407) /
[:414](file:///E:/code/android/BrowseGuardVid/engine/main.py#L414)），也就是说**引擎真的这么说的时候
走的是 `error.code` 那条正常链路**；而客户端这两处分支是「引擎 200 + 空数组 + 任务态还是
`done` / `awaiting_selection`」——引擎在这个组合里一个字都没说，屏上却出现了一个只有引擎
配得上发的码。三个后果与 §19.4 那条同源：用户误读、审计行查无此码、`retryable` 借来自证不了自己。

改动（[android/app/src/main/java/com/hpu/browseguard/ui/HomeViewModel.kt](file:///E:/code/android/BrowseGuardVid/android/app/src/main/java/com/hpu/browseguard/ui/HomeViewModel.kt)）：

- 新增 [`internal object LocalRefusals`](file:///E:/code/android/BrowseGuardVid/android/app/src/main/java/com/hpu/browseguard/ui/HomeViewModel.kt#L413)，
  与 `LocalTimeouts` 同形：`code = null`、文案自带「客户端本地判定」。两条分支各指过去
  （`HomeViewModel.kt:171` 嗅探空结果、`:218` 空格式表）。
- `retryable = true` 是**本地决定**，不是从 `FORMAT_UNAVAILABLE` 继承：客户端不知道引擎为什么给空表
  （换 Cookie / 换引擎后同一 URL 就可能不为空），重试代价只有一次请求。原来的 `false` 才是真造假——
  它把「所选画质不可用」的语义套到了「压根没有画质表」上。
- `ErrorCode` import 随之从 ViewModel 移除（该文件不再需要协议码）。

**与 §14.3 的关系（不修饰）**：§14.3 那轮「UI 文案与协议同源」把这两处的文案、码、`retryable`
三件套一起换成了枚举原文（表在 [docs/smoke-report.md:1247](file:///E:/code/android/BrowseGuardVid/docs/smoke-report.md#L1247)）。
文案同源是对的，**码与 `retryable` 跟着搬错了**：那是枚举的身份字段，只配描述引擎响应。
本轮只拆身份字段，同源原则保留——`HomeScreen.kt:422` 的 `ErrorCode.DRM_PROTECTED.message`
（引擎在数据里给了 `drmDetected` 这个事实，界面转述它的措辞）不动。§14.3 的表已加指向本节的脚注。

守卫（新增 [android/app/src/test/java/com/hpu/browseguard/LocalRefusalTest.kt](file:///E:/code/android/BrowseGuardVid/android/app/src/test/java/com/hpu/browseguard/LocalRefusalTest.kt)）：

| 用例 | 断言 | 结果 |
| --- | --- | --- |
| `emptyArrayRefusalsCarryNoProtocolCode` | 两条本地判定 `code == null`、文案含「客户端本地判定」、`retryable` 为真 | 绿 |
| `refusalTextsNameWhatTheEngineReturned` | 两条文案逐字钉住 | 绿 |
| `noEnumIdentityFieldIsUsedAsAClientSideCode` | 静态守卫：`src/main/java/**.kt` 里 `ErrorCode.X.name` / `.retryable` 命中数必须为 0 | 绿 |
| `guardActuallyBites` | 扫描集含 `HomeViewModel.kt`；正则必须抓得住 `ErrorCode.FORMAT_UNAVAILABLE.name,` 这一行 | 绿 |

为什么要新开一条守卫：已有的裸字面量守卫 `LocalTimeoutTest.noProtocolCodeNameAppearsAsBareLiteral`
只扫 `"FORMAT_UNAVAILABLE"` 这种**字符串字面量**，而这两处伪造走的是**枚举引用**，它天生看不见。

变异复跑（两个变异体各跑完立即还原，`HomeViewModel.kt` 与变异前逐字节相等）：

| 变异体 | 红名单 | 关键观察 |
| --- | --- | --- |
| A：把空格式表分支退回 `ErrorCode.FORMAT_UNAVAILABLE` 三件套（含 import，语法合法） | 仅 `LocalRefusalTest > noEnumIdentityFieldIsUsedAsAClientSideCode` | **老守卫 `LocalTimeoutTest` 全绿**——正好证明它抓不住这类伪造，新守卫不是装饰 |
| B：`LocalRefusals.formats()` 的 `code` 从 `null` 改回 `"FORMAT_UNAVAILABLE"` | `LocalRefusalTest > emptyArrayRefusalsCarryNoProtocolCode` + `LocalTimeoutTest > noProtocolCodeNameAppearsAsBareLiteral` | 裸字面量路径两条守卫同时咬住 |

取证（现场复跑）：`:android:app:testDebugUnitTest --rerun` **68 passed / 0 failed**
（原 64，+4 条 §19.9 用例）。引擎侧本轮未改代码，桩 222 / real 175 的基线是 §19.8 那一次的结果。

遗留（登记，不掩饰）：

1. **协议仍不可分**：§19.3 缺口 3 的后半句——「引擎侧『有格式表可给』与『给不出』在协议上不可分」——
   本轮没动，因为裁决给的是客户端本地提示这条路线。要真分得开需要协议级改动（`GET /formats`
   的空数组带语义，或 `awaiting_selection` 入口自检），归 PROTOCOL 变更提案，等裁决再动。
2. **分支没有行为级覆盖**：`LocalRefusals` 的两条产出有单测，但「ViewModel 收到空数组 → 落到这条产出」
   仍是静态守卫 + 单例断言，没有跑真的 ViewModel。§18.2 第 3 步（[smoke-report.md:1728](file:///E:/code/android/BrowseGuardVid/docs/smoke-report.md#L1728)）
   的 `FakeBackend` 到位后，这类「200 + 空数组」响应才可能在 JVM 里被演出来——本轮把它挂进那条待办，
   不另开一套假件。
3. 设备截图未复拍：这两条分支在 MuMu 上取不到值（引擎不发空数组），本来就属于「需要假引擎才能演」
   的画面，同归第 3 步 FakeBackend。

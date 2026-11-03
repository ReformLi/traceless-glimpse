# 无痕掠影 (Traceless Glimpse) M6-4 验收报告（设置页与 SAF 输出目录）

| 项 | 值 |
|---|---|
| 执行日期 | 2026-11-02（Asia/Shanghai） |
| 验收范围 | M6-4：设置页四段结构、执行者两处同步、SAF 目录选择与持久授权、下载产物「先落应用目录再复制」 |
| 被测代码 | commit `58ce337`（M6-3）之后的 M6-4 改动，本轮按 feat / fix / docs 三条提交拆分；被测的是拆分前的同一份工作树内容 |
| 运行环境 | MuMu 模拟器 Android 15（SM-S9080，900×1600 / 320dpi）· AGP 9.1.0 · Kotlin 2.4.21 · Room 2.8.5 · `androidx.documentfile:1.0.0` |
| 取证方式 | `:android:app:testDebugUnitTest`（160 项，0 失败 0 跳过）+ MuMu 上真实选目录 / 重启 / 本地嗅探下载 / 引擎下载中途强杀 / 删除 + `adb shell ls`、`run-as sqlite3` 与 md5 双向核对 |
| 结论 | **SAF 写入与复制链路已在设备上跑通；修复 1 个历史页真机缺陷 + 1 处清除文案告知缺口 + 1 处首页副标题误判；引擎离线误标经代码与真机双重确认不存在（§0）；复制失败分支仅有单测覆盖（见 §7）** |

## 0. 提交前的三处回查（用户点名）

### 0.1 引擎离线会不会把记录误标 INTERRUPTED

结论：**不会，M6-3 已修**，且本轮补上了「真有一条远端下载中被中断」的设备证据。

判断代码两处：

1. 对账前先探 health，探测结果作为参数传下去——`android/app/src/main/java/com/hpu/browseguard/data/AppContainer.kt:81`：

```kotlin
suspend fun reconcileHistory(): ReconcileReport {
    val reachable = engineReachable()          // ← 先探 /v1/health
    val report = history.reconcile(reachable) { taskId -> lookupTask(taskId) }
    if (!reachable) {
        audit.log("audit_history_reconcile_skipped", mapOf(
            "reason" to "engine_unreachable",
            "preserved" to report.skipped.toString(),
            "local_interrupted" to report.interrupted.toString(),
        ))
    }
    …
}
```

2. 远端行一律原样保留——`android/app/src/main/java/com/hpu/browseguard/data/history/RoomHistoryRepository.kt:128`：

```kotlin
if (remoteId == null) { /* 本地句柄：远端本来就没有对象，可直接判中断 */ … continue }
if (!engineReachable) {
    // 引擎离线不等于任务消失：原样保留，等下次可达时再对账。
    skipped++; continue
}
when (val verdict = runCatching { lookup(remoteId) }.getOrElse { TaskLookup.Unknown }) {
    TaskLookup.Missing -> { … dao.upsert(record.copy(status = RecordStatus.INTERRUPTED, …)) }
    TaskLookup.Unknown -> skipped++
    is TaskLookup.Found -> { … }
}
```

只有 `TaskLookup.Missing`（引擎明确回 404）才写 `INTERRUPTED`；契约注释见 `HistoryRepository.kt:69`。

真机取证（2026-11-02 20:24–20:25）：下载执行者=电脑引擎，本机嗅探 `bulk.mp4?chunk_delay_ms=300`（限速约 19 s）→ 引擎建任务 `01a19c36-5dc2…` → 开始下载，落库 `DOWNLOADING` → 下载中途 `am force-stop` → 杀掉引擎进程（`/v1/health` 已无响应）→ 重新冷启动：

```
11-02 20:25:05.530 W BgvAudit: audit_history_reconcile_skipped reason=engine_unreachable preserved=1 local_interrupted=0
11-02 20:25:05.531 W BgvAudit: audit_history_reconcile scanned=1 interrupted=0 recovered=0 skipped=1
```

同一时刻直接读 Room（`run-as … sqlite3`）：

```
id                                    taskId                      status       errorCode
9b2772fc-8585-410e-93af-00636e7a9bd9  01a19c36-5dc2-765c-9f29-ea  DOWNLOADING
```

状态仍是 `下载中`（历史页截图 `13-history-offline.png`），没有被误标。审计文本：`.devtmp/m6-4/14-audit-offline.txt`。

**skip 只是延后，不是冻结**（同轮补验，20:35）：把引擎用三门控 env 重新拉起后再次冷启动，同一行被正常对账推进终态——

```
11-02 20:35:55.562 W BgvAudit: audit_history_reconcile scanned=1 interrupted=0 recovered=1 skipped=0
```

`download_records` 里 `9b2772fc` 从 `DOWNLOADING` 变成 `FAILED / errorCode=UNKNOWN`（界面同步显示「失败 · 错误码：UNKNOWN」，`100-recovered.png`）。这个 `UNKNOWN` 不是客户端编的：引擎自己的 `GET /v1/tasks/01a19c36…` 响应就是 `{"code":"UNKNOWN","category":"internal","detail":{"reason":"engine_restart"}}`，客户端只做 `task.error?.code?.name` 的透传（`RoomHistoryRepository.kt:215`）。即：引擎离线期间不写状态，引擎恢复后由引擎自己给结论。

### 0.2 「测试连接」

正向分支已在设备验证：设置页点「测试连接」→ 界面显示「已连接 · 引擎 0.1.0 · 协议 0.2」，并在同屏渲染隐私自检三行（Cookie 策略「按需（401/403 时读取一次）」、输出白名单两个目录、缓存路径与 0 B）；引擎侧访问日志按顺序出现 `GET /v1/health 200` ×2 → `GET /v1/privacy/selfcheck 200`（`55b-test-connection-ok.png` + `.devtmp/m6-4/engine-dev4.log:14-16`）。离线分支（引擎不可达时的红卡与「重连」入口）在 §2 与 M6-3 报告中已有取证。

### 0.3 「默认执行者配置」

已验证，且验证方式读的是**持久化后的真值**而不是界面：

| 动作 | 落盘真值（`run-as … cat shared_prefs/bgv_engine.xml`） |
|---|---|
| 首页分段控件切到「电脑引擎」 | `sniff_executor=ENGINE` |
| 再切回「本机嗅探」 | `sniff_executor=LOCAL_WEBVIEW` |
| 本轮全程 | `download_executor=ENGINE`、`base_url=http://127.0.0.1:8730` |
| `am force-stop` + 重启 | 值不变，首页徽标与设置页同步显示同一执行者 |

界面侧的两处同步（首页 ↔ 设置页）取证见 §2。

### 0.4 首页副标题：从「记 §7」升级为「本轮修掉」

`home_subtitle` 原来是静态串「本地执行 · 浏览器隔离嗅探」，而 `HomeUiState` 的默认执行者是 `ENGINE`/`ENGINE`——用户按副标题判断「这次活儿谁在跑」会判反，属于会导致误判的文案，不是纯观感问题。

改法（只改这一行文案的取值，布局与样式不动）：

- `ProtocolCopy.kt` 新增纯函数 `homeSubtitleRes(sniff, download)`，返回两段文案的资源 id；
- `HomeScreen.kt:160` 用 `stringResource(R.string.home_subtitle, 嗅探段, 下载段)` 渲染「嗅探：X · 下载：Y」；
- 单测 `ProtocolCopyTest.homeSubtitleFollowsBothExecutors` 覆盖四种组合；
- 设备复验：`15-subtitle-local.png` = 「嗅探：本机浏览器 · 下载：电脑引擎」，切段后 `16-subtitle-engine.png` = 「嗅探：电脑引擎 · 下载：电脑引擎」。

附带核对的一处疑点：引擎回 400（`SSRF_BLOCKED`）时首页一度看不到错误。查代码确认 `DownloadPhase.Failed` 会渲染 `FailedDownloadContent`（`HomeScreen.kt:187-198`、`:928`），历史页那条记录也明确带「失败 · 错误码：SSRF_BLOCKED」（`13-history-offline.png`），因此**不是静默降级**，是本机截图取到了滚动位置之外的区域。

| 验收项 | 结论 | 证据 |
|---|---|---|
| 设置页四段（引擎连接 / 执行方式 / 输出位置 / 历史维护）可用 | 通过 | §2 |
| 首页与设置页的执行者切换双向同步 | 通过 | §2 |
| SAF 选目录 + 持久读写授权，重启后仍生效 | 通过 | §3 |
| 下载产物先落应用目录、成功后复制到所选文件夹（字节一致） | 通过 | §4 |
| 历史页能打开 `content://` 产物 | 修复后通过 | §5 |
| 删除记录连带删除 SAF 文档，共享产物受保护 | 通过 | §6 |
| SAF 复制失败时保留 staging 并显示警告 | **设备未复现，单测覆盖** | §7 |

---

## 1. 架构裁决：为什么是「先落应用目录再复制」

用户在 M6-4 开工前定的两条裁决，是本节全部代码的前提：

1. **SAF 写入方式 = 先落应用目录再复制。** 下载继续写应用专属沙箱，`.part` 断点续传（M3 成果）不动；只有复制进 SAF 成功，才删 staging 并把记录的 `outputPath` 换成 `content://`。
2. **首页执行者 = 保留快捷切换，两处同步。** 首页卡片和设置页读写同一份 `EngineSettings` / `HomeViewModel` 状态。

对应实现集中在 `android/app/src/main/java/com/hpu/browseguard/data/output/OutputTarget.kt`：

```kotlin
sealed interface OutputPublish {
    data object NotConfigured : OutputPublish   // 没选 SAF：留在应用目录，行为与接入前一致
    data class Published(val uri: String, val name: String) : OutputPublish
    data class Failed(val reason: String) : OutputPublish   // staging 必须留着
}
```

`SafOutputDirectory.publish()`（`OutputTarget.kt:108`）的失败出口全部返回 `Failed`，只有 `copyTo` 成功之后才执行 `source.delete()`（`OutputTarget.kt:143`）。同名文件走 `uniqueFileName()` 追加序号，绝不覆盖用户已有文件。

`BackendProvider.publishOutput()` 的默认实现返回 `NotConfigured`，`AppContainer` 才把它接到 SAF 上——JVM 单测因此不需要 Android 文件系统。

## 2. 设置页与两处同步

设置页分四段（`SettingsScreen.kt`）：`EngineSection`（引擎连接）、`ExecutionSection`（嗅探方式 + 下载执行者）、`OutputSection`（输出位置）、`HistorySection`（历史维护）。

| 取证动作 | 结果 | 截图 |
|---|---|---|
| 首页把嗅探 + 下载都切到本机 | 设置页两段同步显示「本机嗅探 / 本地直链」 | `.devtmp/m6-4/16-both-local.png` → `41-settings-output.png` |
| 设置页把嗅探切回「电脑引擎」 | 回到首页，分段控件已是「电脑引擎」，并弹出离线黄卡「切到本机嗅探」 | `.devtmp/m6-4/46-home-synced.png` |
| 有任务进行中切执行者 | 界面禁用两颗按钮并显示红色「有任务正在进行：等它结束或取消后才能切换」（`SettingsScreen.kt:397`），`selectDownloadExecutor` 里还有第二道门 `if (snapshot.downloadInFlight) return`（`HomeViewModel.kt:229`） | **本轮未单独留设备截图**；由 `LocalModeContractTest.executorSwitchIsRefusedWhileATaskIsInFlight` 以源码契约守住 |

> 上一版这里写过「并记 `merge_ignored reason=executor_switch`」，是错的：那条审计行属于**另一条路径**——勾着合并时把下载执行者切到本地直链（`HomeViewModel.kt:232`，`HomeViewModelBehaviorTest.kt:168` 断言），进行中切换会先被 `downloadInFlight` 挡回去，根本走不到写审计行。

## 3. SAF 目录选择与持久授权

| 步骤 | 设备实况 | 截图 |
|---|---|---|
| 设置 → 输出位置 → 选择文件夹 | 系统 `OpenDocumentTree` 拉起 DocumentsUI | `04-saf-picker.png` |
| 在 Download 下新建 `BGV` | 新建文件夹对话框走通 | `08-saf-new-folder.png` / `09-saf-bgv-created.png` |
| 使用此文件夹 | 系统弹「要允许览盾视频访问 BGV 中的文件吗？」→ 允许 | `10-saf-adopted.png` / `44-grant-dialog.png` |
| 回到设置 | 「已选文件夹：BGV」+「下载完成后复制到此文件夹」+ 更换 / 恢复默认 | `11-settings-saf-selected.png` |
| `am force-stop` 后重启应用 | 仍显示「已选文件夹：BGV」，授权未被系统收回 | `12-restart-persisted.png` / `48-restart-state.png` |
| 点「恢复默认」 | 立刻回到「应用专属目录（默认）」并显示沙箱绝对路径；无二次确认（没有文件会被删，符合退出路径规则） | `42-forget-confirm.png` |

审计日志（`adb logcat -s BgvAudit`）：

```
11-02 19:31:11.764 W BgvAudit: audit_output_dir_selected name=BGV
11-02 19:39:15.806 W BgvAudit: audit_output_dir_forgot
```

> 持久授权的取证方式说明：MuMu / Android 15 上 `dumpsys package com.hpu.browseguard` 不列出 tree 级 URI 授权（重新授权后 grep `grant|uri` 仍只有 `INTERNET`），因此持久性用「force-stop + 重启后仍显示已选文件夹」这一功能事实来证明，而不是 dumpsys。

## 4. 端到端复制：字节一致才算成功

设备侧走完整链路：本机 WebView 嗅探 `http://127.0.0.1:8731/page-bulk.html`（`adb reverse tcp:8731`，本地 `engine/testsupport/media_server.py --bulk`）→ 选画质 → 本地直链下载 → 复制到 SAF。

| 观测点 | 结果 |
|---|---|
| 嗅探审计 | `webview_session outcome=candidates host=127.0.0.1 requests=5 ranges=3 candidates=1 loaded=true`，且 `cookie=absent`、头白名单只含 referer/UA |
| 下载卡片 | 「文件位置 bulk.mp4（所选文件夹）」，见 `32-download-done.png` |
| SAF 目录 | `/storage/emulated/0/Download/BGV/bulk.mp4`，4,218,400 字节 |
| 主机源文件 | 同尺寸，md5 双向一致：`796c7026e90862595a340eedbd153883` |
| staging 目录 | `/storage/emulated/0/Android/data/com.hpu.browseguard/files/Download` → `total 0`（复制成功后才删，`.part` 一并清） |
| 复制审计 | `11-02 19:34:32.952 W BgvAudit: audit_output_publish result=ok name=bulk.mp4` |
| 历史记录 | 落库 `DONE`，`outputPath` 存的是 `content://…` 而不是 staging 路径，见 `33-history-done.png` |

## 5. 真机才暴露的缺陷：历史页不认 `content://`

`34-history-actions.png` 显示：这条 SAF 产物的操作面板里**没有「打开文件」**，反而给出「文件保存在电脑的引擎侧，手机无法直接打开」。

原因：M6-3 的 `isLocalFile()` 只认真实文件路径，`content://` 被归到了「PC 侧」那一类。这是纯 JVM 单测抓不到的——测试里从来没有人真的去点这个按钮。

修复在 `android/app/src/main/java/com/hpu/browseguard/ui/HistoryScreen.kt`：改用 `canOpenOnPhone()`，`content://` 直接判定为手机侧可打开；本地路径仍要求「在沙箱内且文件真的存在」。

| 复验 | 结果 |
|---|---|
| 重新构建安装后打开面板 | 「打开文件」回来了，PC 提示消失（`35-history-open-fixed.png`） |
| 点「打开文件」 | 焦点切到 `com.android.gallery3d/…MovieActivity`，视频正常播放（`36-open-file.png`） |

授权失效时的兜底走 `FileOpen.kt` 的 `OpenFileProblem.Permission` → 文案「所选文件夹的授权已失效，请在设置里重新选择」，不会静默失败。

## 6. 删除记录：SAF 文档一起删

| 取证动作 | 结果 | 证据 |
|---|---|---|
| 历史页长按该条 → 删除记录 | 弹「删除这条记录？」，正文说明「如果手机上有对应文件，也会一并删除」 | `38-delete-confirm.png` |
| 确认删除 | DocumentsUI 里 `BGV` 显示「无任何文件」；`adb shell ls` → `total 0` | `39-after-delete.png` / `43-picker-again.png` |
| 历史列表 | 该行消失，其余 10 条（含 3 条已完成）不受影响 | `39-after-delete.png` |
| staging 沙箱 | 仍为空，没有误删别的记录的文件 | `ls -lR .../files` → `total 0` |

共享保护沿用 M6-3：`RoomHistoryRepository.delete()` 先查 `findByOutputPath`，还有别的记录引用同一产物时只删行不删文件（`RoomHistoryRepository.kt:91`）。`deleteAll()` 因为所有引用都会消失，才逐个清理（`RoomHistoryRepository.kt:102`），其中 `content://` 走 `deleteDocument()`（`RoomHistoryRepository.kt:161`）。

**本轮附带修正的文案**：`settings_history_clear_body` / `settings_history_clear_hint` 原来只说「应用目录」，但清空历史现在同样会删所选文件夹里的产物——属于破坏性操作前的告知缺口，已改成「应用目录和所选文件夹」（`52-clear-confirm.png`）。

## 7. 已知缺口

| # | 缺口 | 现状 | 影响 |
|---|---|---|---|
| 1 | **SAF 复制失败分支未在设备上复现** | 想通过删掉 `BGV` 目录制造失败，但 MuMu 的 MediaProvider 拒绝：`rm -rf` 报 `Text file busy`，空目录在 MediaStore 里也查不到行，`content delete` 走不通 | 该分支只有 JVM 单测 `localSuccessKeepsStagingAndWarnsWhenSafCopyFails`（断言 `publishedPath=null`、警告透传、历史仍记 staging 路径）。真机上「不谎报已保存」尚未被肉眼验证 |
| 2 | 持久授权无法用 `dumpsys` 直接读取 | 改用 force-stop + 重启的功能性证明（§3） | 取证强度略降，结论不变 |
| 3 | 冷启动逐条回查无上限 | M6-3 起登记为 M6 待办；用户裁决：性能问题记 §7 接受，**误标问题不在此列**（§0.1 已确认不会误标） | 记录多时可能拖慢启动 |
| 4 | 「恢复默认」后旧 `content://` 记录只删行不删文档 | 系统收回授权后 `DocumentFile.delete()` 必然失败，文件留在所选文件夹里 | 设计后果；界面「打开文件」会给出权限提示，不静默 |
| 5 | 复制期间产物在手机上占双份空间 | `publish()` 是整文件 `copyTo`（`OutputTarget.kt:132`），成功后才 `source.delete()`；4 MB 的 `bulk.mp4` 看不出代价 | 选 SAF 后大文件（如 2 GB 视频）需要约两倍可用空间，且复制耗时可见；是「先落应用目录再复制」这条裁决的代价，不是缺陷 |
| 6 | 引擎长期不可达时远端行一直显示「下载中」 | §0.1 的 skip 语义：`engineReachable=false` 一律原样保留，没有本地超时把「状态未知」显式化。引擎恢复可达后会自动对账推进终态（§0.1 补验 `recovered=1`） | 界面不会误报中断（符合裁决），但离线期间看不出「这条暂时查不到」；待 M6 后续决定是否需要「状态未知」徽标 |
| 7 | 「任务进行中禁用执行者切换」没有设备截图 | 只做了代码核对 + 源码契约单测（§2）；本轮的下载窗口太短，没来得及切到设置页截图 | 取证强度问题，不是行为缺口；M6-5 用 `chunk_delay_ms` 造长任务时顺手补一张 |

> 已从缺口表移除一条：首页副标题静态串。按用户规则判定为「会导致误判当前执行者」，本轮已修，见 §0.4。

## 8. 单元测试

| 套件 | 内容 |
|---|---|
| `HomeViewModelBehaviorTest` 新增 3 条 | 无 SAF 时留在 staging；`Published` 时 phase 与历史行都写 `content://`；`Failed` 时保留 staging + 显示警告 |
| `data/output/OutputTargetTest`（新增 6 条） | `uniqueFileName` 四个分支（空闲名 / `clip (2).mp4` / 无扩展名 / 时间戳兜底）、`mimeOf`、`OutputLocation.configured` |
| `RoomHistoryRepositoryTest` 新增 1 条 | `deleteAllRemovesEveryRowAndEverySandboxFile`：清空时逐行清理沙箱产物（含共享产物），但沙箱外文件一律不动。`content://` 分支走 `DocumentFile`，JVM 侧不可测，由 §6 真机删除取证覆盖 |
| `ProtocolCopyTest` 新增 1 条 | `homeSubtitleFollowsBothExecutors`：四种执行者组合的副标题取值（§0.4 的回归点） |
| 全量 | `tests=160 skipped=0 failures=0 errors=0`（提交前重跑，结果文件 `android/app/build/test-results/testDebugUnitTest/`） |

## 9. 复现命令

```bash
# 1. 构建 + 单测（仓库根目录）
GRADLE_USER_HOME=D:/Android/GradleUserHome ./gradlew :android:app:testDebugUnitTest :android:app:assembleDebug

# 2. 安装到 MuMu
adb -s 192.168.31.57:5555 install -r -t android/app/build/outputs/apk/debug/app-debug.apk

# 3. 起本地测试媒体（含 bulk.mp4 + page-bulk.html），并把端口反向映射到设备
cd engine && python -m testsupport.media_server --host 127.0.0.1 --port 8731 --bulk &
adb -s 192.168.31.57:5555 reverse tcp:8731 tcp:8731

# 4a. 本机链路（§3/§4）：设置 → 嗅探方式=本机嗅探、下载执行者=本地直链
#     → 输出位置=选择文件夹(新建 BGV) → 首页输入 http://127.0.0.1:8731/page-bulk.html → 嗅探 → 下载
# 4b. 引擎链路（§0.1）：跑引擎时必须同时给三门控 env，否则 127.0.0.1 会被 SSRF 拦成 400
#     BGV_DEV_ALLOW_SSRF_BYPASS 只为本机测试媒体开放，用完即删——本轮的引擎进程已 kill，env 随之消失
cd engine && BGV_ENGINE_MODE=real BGV_SSRF_ALLOWLIST=127.0.0.1 BGV_DEV_ALLOW_SSRF_BYPASS=1 \
  ./.venv/Scripts/python.exe -m uvicorn main:app --host 127.0.0.1 --port 8730 --access-log

# 5. 取证
#    5.1 文件系统（Git Bash 要加 MSYS_NO_PATHCONV=1，否则 /storage/… 会被改写成 Windows 路径）
MSYS_NO_PATHCONV=1 adb -s 192.168.31.57:5555 shell ls -l /storage/emulated/0/Download/BGV
MSYS_NO_PATHCONV=1 adb -s 192.168.31.57:5555 shell ls -lR /storage/emulated/0/Android/data/com.hpu.browseguard/files
#    5.2 审计（LogcatAuditLogger 用 Log.w("BgvAudit", …)）
adb -s 192.168.31.57:5555 logcat -d -s BgvAudit:*
#    5.3 数据库权威状态：表名是 download_records，不是 history_records
MSYS_NO_PATHCONV=1 adb -s 192.168.31.57:5555 shell "run-as com.hpu.browseguard sqlite3 -header -column /data/data/com.hpu.browseguard/databases/browseguard.db 'select id, taskId, status, errorCode, url from download_records order by updatedAt desc limit 8'"
#    5.4 执行者持久化真值
MSYS_NO_PATHCONV=1 adb -s 192.168.31.57:5555 shell "run-as com.hpu.browseguard cat /data/data/com.hpu.browseguard/shared_prefs/bgv_engine.xml"

# 6. 收尾
adb -s 192.168.31.57:5555 reverse --remove-all
```

> 设备操作提示（本轮踩过的坑，照做能少走弯路）：
> - MuMu 上 `uiautomator dump` 会 exit 139，取证一律用 `exec-out screencap -p` + 坐标点击。
> - **不要按屏幕尺寸反推坐标**：截图与设备是 1:1（900×1600），但审阅时的缩放显示会让人估偏。定位控件用 PIL 扫描像素色带（启用中的 `FilledTonalButton` 填充是 `#CCFBF1`，禁用约 `#E2E8F0`），并以「点底部导航是否切页」验证映射。
> - 按钮填充色只能作为辅助判读：本轮出现过「像素看着像禁用、实际点击成功启动了下载」的情况，最终以 `download_records` 行与引擎访问日志为准。
> - 收起键盘不要用 `input keyevent 4`（BACK）——在 MuMu 上会把应用退到桌面；用 `keyevent 111` 或直接点其他字段。
> - URL 输入框点不出焦点时，用 `input keyevent 61`（TAB）在按钮间走焦到输入框后再 `input text`。

截图目录：`.devtmp/m6-4/`（01–99，其中 §0 的回查证据是 `13-history-offline.png`、`14-audit-offline.txt`、`15-subtitle-local.png`、`16-subtitle-engine.png`、`55b-test-connection-ok.png`、`engine-dev4.log`）。

# BrowseGuard Vid M6-3 验收报告（Room 历史与任务操作）

| 项 | 值 |
|---|---|
| 执行日期 | 2026-11-02（Asia/Shanghai） |
| 验收范围 | M6-3：Room 持久化、冷启动对账、历史页展示 / 删除 / 重试、设置项入口 |
| 被测代码 | commit `d9b918c` + M6-3 未提交改动 |
| 运行环境 | MuMu 模拟器 Android 15（900×1600 / 320dpi）· AGP 9.1.0 · Kotlin 2.4.21 · KSP 2.3.12 · Room 2.8.5 |
| 取证方式 | `:android:app:testDebugUnitTest`（149 项）+ MuMu 上真实下载 / 杀进程 / 引擎离线与恢复 + `run-as` 导出 SQLite |
| 结论 | **M6-3 全部验收项通过；对账可达性语义已修复并复验** |

| 验收项 | 结论 | 证据位置 |
|---|---|---|
| KSP + Room 在 AGP 9 / Kotlin 2.4.21 下可用，无需降级到 DataStore | 通过 | §1 |
| 实体字段与状态机完整，重启后历史不丢 | 通过 | §2 |
| 冷启动不残留假进度：本地句柄 / 任务 404 → 已中断 | 通过 | §3 |
| 引擎离线不误判：保留原状态，恢复后自动补对账 | 通过 | §4 |
| 历史页分组、打开文件、复制链接、重试、删除（含共享产物保护） | 通过 | §5 |
| 逐条回查无上限，记录多时可能拖慢冷启动 | **已知缺口，记 M6 待办** | §6 |

---

## 1. KSP / Room 探路结果

| 项 | 结果 |
|---|---|
| 插件 | `com.google.devtools.ksp` 2.3.12 + `androidx.room` 2.8.5（KSP 处理器） |
| 构建 | `:android:app:assembleDebug` 一次通过，**未出现** KSP / Room 报错 |
| 降级 | 未启用 DataStore 降级方案 |
| 数据库 | `BrowseGuardDatabase`，WAL 模式，`DownloadRecordDao` 使用 `@Upsert` + `Flow` |

`android/app/build.gradle.kts` 与 `gradle/libs.versions.toml` 中的新增依赖仅此一处来源。

## 2. 数据模型与持久化

`DownloadRecord` 字段：`taskId`、`url`、`title`、`format`、`status`、`outputPath`、`errorCode`、`createdAt`、`updatedAt`。

状态枚举：`SNIFFING`、`READY`、`DOWNLOADING`、`DONE`、`FAILED`、`CANCELLED`、`INTERRUPTED`。

| 取证动作 | 结果 | 证据 |
|---|---|---|
| 正常下载一条 → 结束进程 → 重启 | 历史仍在，状态 `DONE` | `.devtmp/m6-3/34-restart-persisted.png` |
| 覆盖安装（不卸载） | 历史保留 | `.devtmp/m6-3/35-reinstall-history-kept.png` |

## 3. 冷启动对账：区分「任务不存在」与「引擎答不上来」

### 3.1 裁决模型

`HistoryRepository` 把单条回查结果收紧为三态（`HistoryRepository.kt:16`）：

```kotlin
sealed interface TaskLookup {
    data class Found(val task: Task) : TaskLookup   // 引擎答了，有任务
    data object Missing : TaskLookup                // 引擎明确 404：任务真的不存在
    data object Unknown : TaskLookup                // 不可达 / 超时 / 非 404 错误：事实未知
}
```

| 记录情况 | 是否需要引擎 | 裁决 |
|---|---|---|
| `taskId` 为空，或为 `local_*` 本地句柄 | 否 | 直接 `INTERRUPTED`（进程死了就没有任何东西能推进它） |
| 引擎不可达（health 失败） | 探测一次后跳过 | **原样保留**，计入 `skipped`，下次冷启动再对账 |
| 引擎可达 + 任务 404 | 是 | `INTERRUPTED` |
| 引擎可达 + 其他错误（`Unknown`） | 是 | **原样保留**，计入 `skipped` |
| 引擎可达 + 任务存在 | 是 | 按远端状态收敛为 `DONE` / `READY` / `FAILED` / `CANCELLED` / `INTERRUPTED` |

实现位置：

- 可达性探测与 404 分类：[`AppContainer.kt:70`](../android/app/src/main/java/com/hpu/browseguard/data/AppContainer.kt#L70)、[`AppContainer.kt:97`](../android/app/src/main/java/com/hpu/browseguard/data/AppContainer.kt#L97)、[`AppContainer.kt:106`](../android/app/src/main/java/com/hpu/browseguard/data/AppContainer.kt#L106)
- 逐条裁决：[`RoomHistoryRepository.kt:100`](../android/app/src/main/java/com/hpu/browseguard/data/history/RoomHistoryRepository.kt#L100)

关键分支：

```kotlin
if (!engineReachable) {
    // 引擎离线不等于任务消失：原样保留，等下次可达时再对账。
    skipped++
    continue
}
when (val verdict = runCatching { lookup(remoteId) }.getOrElse { TaskLookup.Unknown }) {
    is TaskLookup.Found -> { /* 按远端任务收敛 */ }
    TaskLookup.Missing -> { /* 只有明确 404 才写 INTERRUPTED */ }
    TaskLookup.Unknown -> skipped++   // 临时错误不改历史
}
```

### 3.2 离线场景实测

现场：一条真实远端任务 `01a19bfa-0912-7fa4-808c-de8823abf887`（`https://example.com/watch/reconcile6`）处于 `DOWNLOADING`，杀掉 App 并停掉引擎后冷启动。

logcat：

```text
W/BgvAudit: audit_history_reconcile_skipped reason=engine_unreachable preserved=1 local_interrupted=0
W/BgvAudit: audit_history_reconcile scanned=1 interrupted=0 recovered=0 skipped=1
```

`run-as` 导出的数据库：

```text
('01a19bfa-0912-7fa4-808c-de8823abf887', 'DOWNLOADING', None, 1793618232546)
```

`updatedAt` 仍是进入下载时的 1793618232546，冷启动没有改写。历史页显示「下载中」，没有出现「已中断」：

- `.devtmp/m6-3/reconcile-offline/08-offline-history.png`
- 对应 DB：`.devtmp/m6-3/reconcile-offline/after-offline-browseguard.db`

### 3.3 引擎恢复后补对账（失败收敛）

重新启动引擎（桩服务把重启前未完成的任务置为 `FAILED/UNKNOWN`）后再次冷启动：

```text
W/BgvAudit: audit_history_reconcile scanned=1 interrupted=0 recovered=1 skipped=0
```

```text
('01a19bfa-0912-7fa4-808c-de8823abf887', 'FAILED', 'UNKNOWN', 1793618277124)
```

- 对应 DB：`.devtmp/m6-3/reconcile-offline/after-online-browseguard.db`

### 3.4 引擎恢复后补对账（完成收敛）

第二条任务 `01a19bfb-3899-735c-9f60-ba3053e60af8`（`https://example.com/watch/reconcile7`）：App 仍处于 `DOWNLOADING` 时杀进程，App 死亡期间由引擎把任务推进到 `merging → done`，随后带上引擎冷启动：

```text
W/BgvAudit: audit_history_reconcile scanned=1 interrupted=0 recovered=1 skipped=0
```

```text
('01a19bfb-3899-735c-9f60-ba3053e60af8', 'DONE', None, 1793618313036)
```

即「离线期间其实已经完成的任务」在恢复连接后能正确回到 `DONE`，不会被离线那次冷启动提前写成中断。

- 截图：`.devtmp/m6-3/reconcile-offline/09-online-history.png`
- 对应 DB：`.devtmp/m6-3/reconcile-offline/after-done-browseguard.db`

### 3.5 本地句柄仍照常判中断

同一份离线快照中，`taskId` 为空或 `local_*` 的记录不依赖引擎，直接收敛为 `INTERRUPTED`（`local_interrupted` 字段单独审计）。这是有意为之：本地下载的推进完全在 App 进程内，进程退出后不可能续跑，保留 `DOWNLOADING` 才是假进度。

### 3.6 单元测试

`RoomHistoryRepositoryTest` 覆盖四类裁决：

| 用例 | 断言 |
|---|---|
| `engineOfflinePreservesRemoteRowsAndNeverCallsLookup` | 离线时 `lookup` 一次都不调；状态不变；`skipped=2` |
| `engineOfflineStillInterruptsRowsWithoutARemoteHandle` | 离线时空 `taskId` / `local_*` 仍变 `INTERRUPTED` |
| `missingTaskIsInterruptedButUnknownKeepsTheRow` | `Missing` → `INTERRUPTED`；`Unknown` → 原样保留 |
| `reconcileRecoversADownloadTheEngineFinishedWhileTheAppWasDead` | `Found(done)` → `DONE` |

全量结果：`tests=149 failures=0 errors=0 skipped=0`（23 个测试类）。

## 4. 历史页功能

| 功能 | 实现 | 设备证据 |
|---|---|---|
| 按今天 / 昨天 / 更早倒序分组 | `HistoryGroupingTest` + 列表渲染 | `.devtmp/m6-3/08-history-one-done.png`、`14-history-done-ready.png` |
| 已完成：打开文件 / 复制链接 | `FileOpen.kt`（FileProvider + `ACTION_VIEW`）；复制走系统剪贴板 | `.devtmp/m6-3/09-history-actions-done.png`、`10-copy-link.png`、`27-open-file.png` |
| 失败 / 中断：重新下载 | 跳回首页并预填链接 | `.devtmp/m6-3/18-failed-actions.png`、`19-retry-home-prefilled.png` |
| 侧滑删除 + 二次确认 | 删除记录并清理本地文件 | `.devtmp/m6-3/32-swipe-confirm.png`、`33-after-delete.png` |
| 共享产物不误删 | 仍有其他记录引用同一 `outputPath` 时只删记录 | `.devtmp/m6-3/39-shared-file-kept.png`、`40-last-reference-file-gone.png` |

## 5. 已知缺口与 M6 待办

1. **逐条回查无上限**（本次记录，不阻塞 M6-3）：`RoomHistoryRepository.reconcile` 对活跃记录逐条 `client.task(...)`，且每次连接超时 3s / 读 15s。历史活跃记录多、引擎半通时，理论上会拖慢冷启动。M6 待办：加并发上限（如信号量 3–4）或扫描上限，并把对账放到不与首屏渲染竞争的协程里。
2. **设置页仍是占位**：当前显示「设置正在准备中」，M6-4 实现设置页与 SAF。
3. **「昨天 / 更早」分组只有单测覆盖**：设备取证的记录都落在「今天」，跨日分组的真实渲染尚未在设备上看到，随 M6-4 复查。
4. `runCatching { lookup(...) }` 理论上会吞掉协程取消异常；当前调用链在 ViewModel 冷启动协程中，取消时最多多跑一轮数据库写，不影响正确性。若 M6 引入并发对账，应改为显式 `try/catch CancellationException` 重抛。

## 6. 复现命令

```bash
# 单测 + 构建
GRADLE_USER_HOME=D:/Android/GradleUserHome ./gradlew :android:app:testDebugUnitTest :android:app:assembleDebug

# 安装
adb -s 192.168.31.57:5555 install -r android/app/build/outputs/apk/debug/app-debug.apk

# 审计日志
adb -s 192.168.31.57:5555 logcat -d -s BgvAudit | grep audit_history_reconcile

# 导出数据库（WAL 三件套必须一起取）
adb -s 192.168.31.57:5555 exec-out run-as com.hpu.browseguard cat databases/browseguard.db > browseguard.db
adb -s 192.168.31.57:5555 exec-out run-as com.hpu.browseguard cat databases/browseguard.db-wal > browseguard.db-wal
adb -s 192.168.31.57:5555 exec-out run-as com.hpu.browseguard cat databases/browseguard.db-shm > browseguard.db-shm
```

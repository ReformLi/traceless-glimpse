# BrowseGuard Vid M6-5 验收报告（Cookie 显式授权、按域管理与隔离守卫）

日期：2026-11-02。设备：MuMu SM-S9080 / Android 15（`192.168.31.57:5555`），APK `app-debug.apk` 22:17 构建。
链路：引擎 real 模式 `127.0.0.1:8730`（经 `adb reverse`），测试媒体 `127.0.0.1:8731`——
`GET /page-cookie.html` 对端写 `sid=good-cookie`，`GET /media/protected.mp4` 无该 Cookie 一律 403。
截图与日志留在 `.devtmp/`（已 gitignore），本文按 `m65-xx` 点名。

## 1. 本轮要求与四条细节的落点

| 要求 | 落点 | 现场证据 |
|---|---|---|
| 首次使用阻断式确认 | `DownloadPhase.AwaitingCookieConsent`（`HomeViewModel.kt:92`）→ 弹框（`strings.xml:209-212`） | `m65-10-consent-dialog.png` |
| 同意才允许 `CookieManager` 读/注入 | `ConsentCookieProvider`：未授权直接 `cookie_blocked`，连 CookieStore 都不碰 | `m65-12-success.png` + `cookie_read cookie=present` |
| 拒绝走原有 `LINK_UNLOADABLE` | `denyCookieConsent()` → 诚实文案「本地直链取不到内容 · 服务端回 HTTP 403，客户端本地判定」 | `m65-20-denied.png`、`m65-21-failed-card.png` |
| 设置页按域列表 + 撤销 | `PrefsCookieConsentStore.grantedHosts()` + 每行「撤销」（一键，无二次确认） | `m65-30-cookie-panel.png`、`m65-49-settings2.png` |
| 红色「一键清除所有 Cookie」二次确认 | `CookieVault.clearAll()` → `CookieManager.removeAllCookies` | `m65-31-clear-confirm.png`、`audit_cookie_cleared_all` |
| 三道隔离守卫复核 | `EnginePayloadGuard` + `CookieLeakPreventionTest`（双 MockWebServer） | §4 |
| 真机端到端 | 未授权 → 403；授权 → 成功；撤销 → 再 403；清除 → `getCookie` 空 | §5、§6 |
| D1 后台任务不判失败 | 停在 `AwaitingCookieConsent`，`.part` 不写、历史行不动，点同意即自动重试 | `HomeViewModelBehaviorTest` 两条 + §7-1 |
| D2 文案补「先在本应用内打开该网页并登录」 | `strings.xml:210` 第二段 | `m65-10-consent-dialog.png` |
| D3 「最近使用」不每次落盘 | `markUsed` 只改内存，`MainActivity.onStop` → `flushUsage()`；首次使用写一次底 | §7-3 |
| D4 引擎侧只记存在性 | `cookie_audit_middleware`（`main.py:958`）+ 脱敏兜底 | §3、§5 |

## 2. 授权模型：只有域名和时间戳

`bgv_cookie.xml` 里只有 `host:<域名>`（布尔）与 `used:<域名>`（毫秒）。Cookie 值始终留在 WebView 自己的
CookieStore，`ConsentCookieProvider` 只在授权成立后读一次并注入同域请求。撤销只收回「读」的许可，
值要靠「清除所有 Cookie」才抹得掉——这条语义本轮从死串改成了面板上看得见的一句话（§7-2）。

## 3. 引擎侧硬证据（用户点名的「绝对不上行」）

`.devtmp/m6-5/engine.log`（本轮最后一次干净引擎进程，22:11 起）：

```
http_request 行合计        16
 cookie=absent             15     ← 手机与电脑侧的全部请求
 cookie=present             1     ← 正向对照：PC 侧 curl 故意带 Cookie，证明搜索能咬住
 set_cookie=present         0
凭据值 grep                 0     ← good-cookie / positional-control / pcprobe 均 0 命中
httpx 源站 URL 行            0     ← 见 §7-5b：级别提升只碰 bgv.engine，不碰 root
```

代表性行（手机 App 发起，`adb reverse` 后源地址同为 127.0.0.1）：

```
22:15:27,957 bgv.engine INFO http_request method=POST path=/v1/tasks cookie=absent set_cookie=absent
22:15:28,401 bgv.engine INFO http_request method=GET path=/v1/tasks/01a19c9d-… cookie=absent set_cookie=absent
22:15:30,472 bgv.engine INFO http_request method=GET path=/v1/tasks/01a19c9d-…/formats cookie=absent set_cookie=absent
```

**同一时间窗的配对证据**（谁拿到了 Cookie，站点和引擎各说各话）：

```
站点侧  22:13:46.865 media_req path=/media/protected.mp4 client=browser    cookie_present=true   ← 手机本机下载，带着授权后的 Cookie
客户端  22:13:47.150 BgvAudit: cookie_read host=127.0.0.1 cookie=present
客户端  22:13:47.150 BgvAudit: cookie_injected host=127.0.0.1 cookie=present
站点侧  22:15:30.155 media_req path=/media/protected.mp4 client=browser    cookie_present=false  ← 电脑引擎自己来抓同一条直链
引擎侧  22:15:30.162 bgv.engine WARNING formats_extract_failed task_id=01a19c9d-… type=DownloadError
```

即：Cookie 到了站点、没到引擎；引擎去抓同一条受保护直链时因没有 Cookie 而失败，并把失败如实回给客户端。
（引擎自己的 Playwright 会话在 22:15:28 收到过站点 `Set-Cookie` 后回带 `cookie_present=true`，那是引擎侧
浏览器自己的会话，与手机的授权无关——见缺口表第 5 条的归因说明。）

## 4. 防泄漏测试（JVM）

三道守卫各有一处代码保证与一处测试钉住：

| 守卫 | 代码 | 测试 |
|---|---|---|
| 出网头只走白名单（referer / UA） | `SniffHeaderPolicy` | `HeaderWhitelistTest` |
| 每个上行给引擎的载荷都过一遍结构检查 | `EnginePayloadGuard.check`（`EngineClient.kt:97`） | `EnginePayloadGuardTest` |
| Cookie 只注入本机下载请求，绝不进引擎载荷 | `ConsentCookieProvider` | `CookieLeakPreventionTest`（下表） |

| 用例 | 断言口径 |
|---|---|
| `CookieLeakPreventionTest.theEngineFacingTrafficCarriesNoCookieAtAll` | 双 MockWebServer 分流：到「假电脑引擎」的每个请求 `headers["Cookie"]` 为 null，正文按 `"(?i)(set-)?cookie"\s*:` 搜 0 命中，且不含 Cookie 值子串 |
| `CookieLeakPreventionTest.theEngineSearchWouldHaveFoundACookie` | 正向对照：三种伪造泄漏载荷都能被同一条正则咬住，协议字段 `cookiePolicy` 不误伤 |
| `CookieLeakPreventionTest.theSetCookieHeaderNeverBecomesAHint` | 站点的 `Set-Cookie` 不进 `headersHint` |
| `CookieLeakPreventionTest.theConsentedCookieGoesToTheSite` | 授权后 Cookie 确实只发给站点 |
| `CookieConsentGateTest`（8 条） | 未授权 `cookie_blocked` 且不读 CookieStore；授权后读一次；撤销只收许可、值仍在；`markUsed` 只改内存、`flushUsage` 才落盘 |

## 5. 仪器化闭环（真机，Gradle connected）

`CookieClosedLoopDeviceTest` 在 MuMu 上串起完整链路，日志 `.devtmp/m6-5/audit-device-test.log`：
未授权 403 → 授权后成功 → 撤销后 `cookie_blocked` + 再 403 → 重新授权再成功（证明撤销≠删值）→
`clearAll()` 后 `cookieIsGoneWithin(link.mediaUrl, 5_000)` 为真 → 再下载 403 且不再弹框（`cookie_read cookie=absent`）。

## 6. 真机 UI 逐腿（本轮 22:11–22:17 复跑）

| 腿 | 结果 | 证据 |
|---|---|---|
| 本机嗅探 + 本地直链，已授权站点 | 60.0 KB `protected.mp4` 落地，历史 `DONE` | `m65-47-local-done.png` |
| 切「下载执行者=电脑引擎」后同一条链接 | 引擎抓不到受保护直链 → 界面「下载失败 · 未发现可用画质 · 引擎回空格式表，客户端本地判定」，提供「重新选择画质」出口 | `m65-52-engine-formats.png` |
| 上一轮已完成的记录 | 没有被这次失败改写 | `22:15:30.811 BgvAudit: history_done_kept id=4367b169-… refused=failed` |
| 设置页 Cookie 面板 | 「已授权 1 个站点 / 127.0.0.1 / 最近使用 22:13 / 撤销 / 清除所有 Cookie」 | `m65-49-settings2.png` |

授权弹框、拒绝分支、撤销后再问、清除后 403 无弹框四腿在 21:4x–21:5x 同一 APK 上已取证
（`m65-03`、`m65-10`、`m65-16`、`m65-20`、`m65-31`、`m65-35`），本轮改动只落在引擎侧与两条文案，客户端行为未变。

## 7. 现场发现、当场修掉的 5 处

**判定依据**：会让用户误判当前状态的不是「性能/取证强度」问题就不记 §7，本轮修。

1. **已完成记录被重试失败改写**（客户端）。撤销授权后点「拒绝」，`markFailed` 把上一次成功的历史行就地刷成
   `FAILED`，而 `protected.mp4` 仍在 Download 目录里。修：`RoomHistoryRepository.keepDone()`（`:102`）拒绝把
   `DONE` 改写为 `FAILED`/`CANCELLED`，并留一行 `history_done_kept` 审计（不做静默降级）；`HistoryRepository`
   接口写清「`markDone` 之后是终态」契约，`FakeHistoryRepository` 同步。设备复验见 §6 第三行。
2. **撤销语义看不见 + 四个死串**（文案）。`settings_cookie_revoke_title/body/confirm/cancel` 没有任何调用点
   （撤销是一击生效、无二次确认——没有数据可丢，不该加确认），而「撤销≠删值」这件事界面从未说清。修：删四个死串，
   语义并入 `settings_cookie_hint`。
3. **重装后「最近使用」退回「尚未使用过」**（客户端）。D3 把时间戳只留在内存，进程没了就丢底。修：
   `markUsed` 首次使用立即写穿（`CookieConsentStore.kt:95`），之后的更新仍只改内存、由 `flushUsage` 在 `onStop`
   统一写。设备复验：应用仍在前台时 `bgv_cookie.xml` 就出现 `<long name="used:127.0.0.1" …/>`，面板显示「最近使用 21:57」。
4. **连接卡片宣称「不需要电脑」而下载其实在走引擎**（文案）。M6-4 §0.4 修的是顶栏，`engineSummary()` 仍只看嗅探
   一侧；本轮把下载切到引擎后顶栏正确、卡片照旧。修：`runsEntirelyLocally(sniff, download)`（`ProtocolCopy.kt:66`），
   两条链路都不碰引擎才说「不需要电脑」，否则回到连接态文案。设备复验：引擎下载时卡片 =「已连接 · 引擎 0.1.0」
   （`m65-54-panel-engine2.png`），切回本地后 =「本机嗅探 · 不需要电脑」（`m65-56-panel-local.png`）。
5. **引擎审计行只在 `python main.py` 下落盘**（引擎，D4 收尾）。`basicConfig` 写在 `__main__` 分支里，而 README:41
   与验收指南用的都是 `-m uvicorn main:app`——uvicorn 只配置自己三个 logger，根上没有 handler 时 `bgv.engine` 的
   INFO 记录被 `logging.lastResort`（WARNING 级）静默丢掉。修复前现场：`grep -c http_request engine.log` = **0**；
   修复后同一命令 = **16**（§3）。新增 `install_console_logging()`（`main.py:904`），startup 事件调用，两条入口同路径。
   - **5b 连带的第二个坑**：第一版把根 logger 提到 INFO，`httpx` 随即把出站完整源站 URL 打进引擎日志
     （`HTTP Request: GET http://8.8.8.8/file.mp4`），被 tests_real 三条钉「日志不带源 URL」的既有用例当场抓住
     （`test_direct_abort_log_line_carries_only_task_id` 等）。最终形态：只提 `bgv.engine` 的级别，不动根。
   - **5c 脱敏的参数化盲区**：`logger.info("Cookie: %s", value)` 里头名在 `msg`、值在 `args`，`_HEADER_LINE`
     的整行抹除够不着，非敏感键名（`theme=…`）之后的登录状态会原样落盘。修：`_PAIR` 连吃后续 `; k=v` 段
     （`log_redaction.py:62`），顺带修掉 `sid=[REDACTED]` 再脱敏多出一个 `]` 的幂等破口。

## 8. 已知缺口

| # | 缺口 | 现状 | 影响 |
|---|---|---|---|
| 1 | 授权文案里的「先在本应用内打开该网页并登录」无法字面执行 | 应用内没有浏览/登录界面：本机嗅探会打开该页并收下 `Set-Cookie`，但需要用户交互的登录表单没有入口 | 提示语对用户平时的登录态偏保守；对「嗅探页自己写 Cookie」的测试站点完全成立。要真支持需引入应用内浏览 UI（PRD 未排期） |
| 2 | `CookieManager.setAcceptCookie` 是进程级开关 | 嗅探会话按域名开/关（`webview_session accept_cookie=on`），不是按请求 | 同进程内并发访问其他站点时理论上共享「收 Cookie」能力；本 App 同时只跑一个嗅探会话 |
| 3 | 无按站点删 Cookie 值的系统 API | 「撤销」只收回读取许可；删值只有整体 `removeAllCookies` | 界面把这一差别写在面板提示里（§7-2），不假装能逐站点删 |
| 4 | 「最近使用」在进程被硬杀时丢最后一次更新 | 落盘点是 `onStop`；`am force-stop` / 崩溃 / LMK 不走生命周期。MuMu 现场：22:13 使用过后 force-stop 重装，面板回到「最近使用 21:57」 | 只会「说得比实际更早」，不会把未用过的说成用过；符合 D3 的取舍（不每次落盘） |
| 5 | 引擎格式抽取阶段的 403 未归因到 `AUTH_REQUIRED` | 22:15 那一腿：引擎 `formats_extract_failed type=DownloadError`，任务停在 `awaiting_selection`、`errorCode=null`，`/formats` 回空表；客户端诚实说「未发现可用画质 · 引擎回空格式表，客户端本地判定」，但看不出真因是 403 | 降级可见、不伪造协议码（M4 裁决），归因待引擎侧抽取路径补 §5 映射；本轮不动 |
| 6 | 修复前的历史行仍是旧行为证据 | `5d27d2c7` 这行停在 `FAILED` 而 `protected.mp4` 仍在盘上（§7-1 修复前产生） | 只影响本轮取证现场的可读性，新数据不再出现该态 |

## 9. 测试

| 套件 | 数量与本轮内容 |
|---|---|
| `:android:app:testDebugUnitTest` | `tests=183 failures=0 errors=0`（本轮 +4：终态守卫 2 条、VM「重试被拒不改写已完成」1 条、`onlyFullyLocalCombinationClaimsNoComputer` 1 条） |
| `:android:app:connectedDebugAndroidTest` | `CookieClosedLoopDeviceTest` 全绿（§5），结果 XML `TEST-SM-S9080 - 15-_android_app-.xml` |
| `engine` 桩套件 | `pytest -q` → **235 passed**（本轮 +2：§7-5 入口无关性、§7-5c Cookie 串整值脱敏） |
| `engine -m real` | `pytest -q -m real` → **175 passed, 1 xfailed**（干净全量复跑；与桩套件并发跑会因临时目录/端口抢占出现假失败，不作数） |

## 10. 复现命令

```bash
# 1. 构建 + 三端回归（仓库根目录）
GRADLE_USER_HOME=D:/Android/GradleUserHome ./gradlew :android:app:testDebugUnitTest :android:app:assembleDebug
cd engine && ./.venv/Scripts/python.exe -m pytest -q && ./.venv/Scripts/python.exe -m pytest -q -m real

# 2. 安装到 MuMu（Git Bash 取设备文件一律加 MSYS_NO_PATHCONV=1）
adb -s 192.168.31.57:5555 install -r android/app/build/outputs/apk/debug/app-debug.apk

# 3. 起测试媒体与引擎（三门控 env 只为本机测试媒体，用完即 kill 进程即消失）
cd engine && ./.venv/Scripts/python.exe -m testsupport.media_server --host 127.0.0.1 --port 8731
BGV_ENGINE_MODE=real BGV_SSRF_ALLOWLIST=127.0.0.1 BGV_DEV_ALLOW_SSRF_BYPASS=1 \
  ./.venv/Scripts/python.exe -m uvicorn main:app --host 127.0.0.1 --port 8730 --access-log
adb -s 192.168.31.57:5555 reverse tcp:8731 tcp:8731 && adb -s 192.168.31.57:5555 reverse tcp:8730 tcp:8730

# 4. 正向对照：故意带 Cookie 打一发，确认审计行搜得出来、值不落盘
curl -s -o /dev/null -H 'Cookie: sid=positional-control-value; theme=positional-control-theme' \
  http://127.0.0.1:8730/v1/health

# 5. 硬证据计数（口径：用前导空格锚定，`set_cookie=absent` 不含锚定会多算）
L=.devtmp/m6-5/engine.log
grep -c 'INFO http_request' $L; grep -c ' cookie=absent' $L; grep -c ' cookie=present' $L
grep -ci 'positional-control\|good-cookie' $L      # 期望 0

# 6. 设备链路：设置 → 本机嗅探 + 本地直链 → 首页输入 http://127.0.0.1:8731/page-cookie.html
#    → 嗅探 → 选择画质并下载 → 首次会弹 Cookie 授权；随后到设置页看面板
MSYS_NO_PATHCONV=1 adb -s 192.168.31.57:5555 logcat -d -s BgvAudit | grep -E 'cookie|history_done_kept'
MSYS_NO_PATHCONV=1 adb -s 192.168.31.57:5555 shell \
  "run-as com.hpu.browseguard cat /data/data/com.hpu.browseguard/shared_prefs/bgv_cookie.xml"
```

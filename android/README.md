# android/ — Android 客户端

包名 `com.hpu.browseguard`，应用显示名「无痕掠影」，英文名 Traceless Glimpse。
定位：**既是引擎客户端，也能完全离线自己把活干完**——设置页在「引擎」与「本机」两个执行者之间切换。
切到本机后，`data/backend/` 里不出现 `EngineClient`、`TaskCreateRequest`、`DownloadRequest`、`encode(`，
这条由 `TaskNeverGoesOnTheWireTest.localSideStaysOffTheWire` 扫源码钉住：本机的活不借引擎的协议发言。

## 现状（M13 收官）

- 已接入 Gradle 构建，是 `settings.gradle.kts` 里的 `:android:app`，协议模型直接复用 `:shared`（KMP）单一来源，不重复定义。
- UI 是 Jetpack Compose + Material 3（`buildFeatures.compose = true`），不是模板时代的 Views 栈。
- 存储用 Room（历史记录 + 播放进度，DB v5），网络用 OkHttp，`WebView` 走 androidx.webkit，播放用 Media3 ExoPlayer。
- `minSdk 29`（PRD NFR-05 Android 10+）、`targetSdk 36`、`compileSdk 36.1`、AGP 9.1.0 / Gradle 9.3.1。
- `versionName = "1.1.0"`、`versionCode = 1`。发版只需两步：`./gradlew.bat :android:app:assembleRelease`
  出包，`apksigner verify --verbose --print-certs <apk>` 验签。**出包后当场归档 mapping（不可跳过）**：
  把 `android/app/build/outputs/mapping/release/mapping.txt` gzip 成
  `release-artifacts/mapping-v<versionName>-code<versionCode>.txt.gz`。文件名必须带 `versionCode`——
  崩栈还原时错配 mapping 不会报错，它会安静地把堆栈「还原」成另一个函数的名字。
  还原用 AGP `builder` jar 里的 `com.android.tools.r8.retrace.Retrace`（SDK 的 build-tools 里没有它），
  且只吃明文，喂 `.gz` 会报 `InvalidMappingFileException`。

## 两套后端并存

| 执行者 | 代码入口 | 干什么 |
|---|---|---|
| 引擎 | `data/EngineClient.kt` | 走 `docs/PROTOCOL.md` 的 HTTP/JSON，嗅探交给 Playwright、下载交给 yt-dlp、后处理交给 FFmpeg |
| 本机 | `data/backend/LocalWebViewSniffer.kt`、`LocalDirectDownloader.kt`、`backend/hls/`、`backend/remux/` | 纯 OkHttp + 平台媒体 API 在设备内完成发现、下载、切片、转封装，**一个字节都不出手机** |

本机的 HLS 链路是这条路径上最重的一段，值得单列：

1. `HlsPlaylistParser` 解析清单（master/media、EXTINF、密钥 URI、分片地址），`#EXT-X-KEY` 只对后续分片生效。
2. `HlsDownloader` 固定 4 个并发槽位、按清单顺序落盘，每段 1 次正常 + 2 次退避重试
   （401/403/404/405/410 不重试）；密钥按 URI 缓存并串行化获取；内存峰值钉在 4 段以内。
   `METHOD=AES-128` 的段在落盘前解密（M14）：key 请求复用同一条 `execute()`，带同样的 Referer/UA/Cookie；
   无显式 `IV` 时用 `EXT-X-MEDIA-SEQUENCE + 下标` 的 16 字节大端序；`AES/CBC/NoPadding`，解密后
   只在非 188 对齐时裁掉尾部 PKCS7。SAMPLE-AES / Widevine / FairPlay 仍按 DRM 拒绝。
3. `TsRemuxer` 用 `MediaExtractor` + `MediaMuxer` 做**纯拷贝**容器转换：不合并、不重编码、不引入 FFmpeg。
   AAC 音频按访问单元（AU）拆开重封——TS 里常见「一个样本并十几个 AU 还带着 ADTS 头」，
   直接抄进 MP4 就是不合规的 `mp4a`，Android 解得开、严格播放器解不开。
4. 音频完整性门禁（`RemuxAudioGuard`）判定产物是否可用。三段：**探测阶段两道前置拒绝**
   （探不到音轨、AAC 无可用解码配置——合成不出 `AudioSpecificConfig` 就不写）；
   **复检六道**——样本非空、产物音轨真落地、相邻样本无注水间隔、可播内容够长、切片密度不塌、音视频总时长对得上；
   **拆分音频再加第七道** `auCountGate`（源 AU 数 == 写入 AU 数 == 复开样本数）。
5. **任何一道不过就保留 `.ts` 并降级**，界面在完成卡留一行小字（`download_remux_fallback_audio`），
   不弹框、不删源。底线是：宁可给一个系统播放器不认的 `.ts`，也不给一个看着成功、点开来哑巴或半截报错的 `.mp4`。

降级理由全部登记在 `RemuxAudioGuard.AUDIO_FALLBACK_REASONS`，有一条 JVM 门禁扫源码里的
`"...audio..."` 字面量，新增理由没进登记表就测试失败——防止现场出现「不知道为什么会降级」的文案。

**下载回落（引擎不可用时）**：设置选了「电脑下载」但引擎未就绪（未连接/离线/自检白名单空）时，
「选择画质并下载」会在画质表这一步自动回落到本机直链——不静默（提示横幅「电脑引擎不可用，已改用手机本机下载」），
审计落 `download_fallback`，设置同步持久化，`outputDir` 换成手机沙箱根。本机对直链与 HLS 都是完整支持，
按钮不允许卡死在灰色里。

## 引擎 HTTP 安检门（M19）

`network_security_config.xml` 为了让内置浏览器打开全网 HTTP 视频站，必须**全局**放行明文——
它没法在运行时按「当前配置的引擎 IP」动态收窄。于是把收窄下沉到 OkHttp 应用层：
`data/NetworkGuardInterceptor.kt` 作为 `EngineClient.defaultHttp()` 的第一个应用拦截器，
规则是——`http://` 请求只放行回环（127.x / localhost / ::1，即 adb reverse 通道）与
**用户当前保存的引擎 Host**（每次请求时从 `EngineConfig` 现读，设置页改地址立即生效），
其余一律抛 `CleartextBlockedByNetworkGuardException`（`https://` 不在管辖内）。
即使 App 内某段代码被注入去请求别的 HTTP 站点，也会在这一层被钉死。
`LocalDirectDownloader` 的 `LOCAL_HTTP` 不在安检门内——它只访问用户显式点选的那条媒体 URL，
不做域名枚举，边界写在 `network_security_config.xml` 注释里。
设置页配套：引擎地址为公网明文（非 127/10/192.168/172.16–31/169.254，`EngineUrl.isRiskyCleartextHost`）
时先弹确认框，保存后引擎卡显示动态封装的隐私说明。

## 引擎心跳检测（M20）

选中「电脑引擎」模式后，`HomeViewModel` 在 `viewModelScope` 里每 5 秒向 `GET /v1/health`
打一拍（3 秒超时，独立协程，不碰嗅探/下载链路）；**连续两拍失败**才把连接态落成
「离线」——一次网络波动不让状态胶囊闪红。心跳成功且当前离线时自动完整重建连接
（health + selfcheck），状态自动回绿。电脑关机到手机显示「离线」最多 10 秒，
重启引擎后下一拍自动恢复。

离线时点「嗅探」会当场给出红色失败卡（`ENGINE_OFFLINE_HINT`），不发请求也不等超时；
切回手机嗅探走模式开关显式选择（离线提示卡有直达入口）。注意：嗅探**中途**断连
仍走 M16 的自动降级（请求已发出、引擎答到一半），与前端拦截是两条语义。
测试注意：心跳是无限周期任务，JVM 测试必须用 `heartbeatIntervalMs = null` 禁用，
专项测试用 `advanceTimeBy` 推拍——`advanceUntilIdle` 会把它推到天荒地老。

## 下载管理、文件操作与播放（M11）

- 下载页是文件台账：打开、分享、重命名、删除（记录 / 记录+文件）、批量选择。存在性与位置探测在
  `data/media/AndroidMediaProbe.kt`，删除策略在 `data/history/RecordDeletion.kt`。
- 跨重启对账：产物缺失的记录会被标出并给对账入口；冷启动还会物理清理沙盒孤儿，
  `audit_history_reconcile` 汇总行把 `orphans` 与 `ghosts` 分开计数。
- Room v5 新增 `mediaDurationMs` 与 `lastPlayedPositionMs`。归一规则在 `PlaybackResume`：
  起播 2 秒内不记；距结尾 15 秒内或达到 97% 视为看完、归零重看；其余从断点续播。
- 播放器只引 `media3-exoplayer` + `media3-ui`（**不引 FFmpeg / RTMP / Cast 扩展**），
  `PlayerRoute` 挂在 Scaffold 之外做全屏；退出时回写进度，右上角菜单可交给外部应用打开。
- APK 体积：M11-2 基线 release-unsigned 3,793,903 B → M11-3B 5,271,453 B，**+1.41 MB**（预算 2 MB）。

## 播放器体验与广告拦截（M12）

- 手势层是 `PlayerView` 上方的透明 Box，只在非画中画、非失败态挂载。横向位移按 **90 秒/整屏**
  换算成快进/快退目标（`PlayerGestureMath.seekTargetMs`，夹在 0 与时长之间），松手才真正 `seekTo`；
  纵向位移左半屏调 `window.screenBrightness`、右半屏调 `STREAM_MUSIC`，拖动中给居中的瞬时提示。
  轴向用 touchSlop 比横纵位移大小定，进度走 `rememberUpdatedState`——把 positionMs 当 key 会打断正在进行的拖动。
- 倍速是控制条上的开关按钮（0.5 / 0.75 / 1.0 / 1.25 / 1.5 / 2.0），面板展开时控制条不自动收起，
  非 1.0x 时按钮带底色，一眼能看出现在不是常速。
- 画中画：Manifest 声明 `supportsPictureInPicture`，`onUserLeaveHint` **只在 `player.isPlaying` 时**
  按视频比例申请小窗；`ON_STOP` 的暂停逻辑排除画中画态，所以退到后台画面进小窗、音频不断；回到全屏控制层恢复。
- WebView 广告拦截在 `shouldInterceptRequest`：硬编码域名黑名单 `AdBlockRules` 按「相等或 `.` 后缀」匹配
  （`contains` 会把 `notdoubleclick.net` 误杀），命中直接返回 200 空体 `WebResourceResponse`。
  **主文档不拦**——用户在地址栏主动打开的域必须能看到结果，白屏比广告更难解释。
  审计行 `adblock_hit host=<域名>` 只记域名，同一域名每页去重。去重是**按地址认页**：`AdBlockLedger`
  在地址变化时才重开账，所以同一页重载不再补第二行——读日志时「一行」代表「这页拦过这个域」，
  不代表只拦了一次。

## 下载保活与通知栏（M13）

- 下载期间挂一条 `dataSync` 前台服务（`data/keepalive/`），默认开、设置页「下载保活」可关。
  服务一行下载都不做：只订阅 `HistoryRepository.records` 心跳流，用
  `KeepAlivePolicy.shouldRun(开关, DownloadCoordinator.activeCount())` 决定继续挂还是 `stopSelf`；
  不新增定时器，通知与下载页读的是同一笔落库。
- 通知栏只有一条 ongoing 进度（`IMPORTANCE_LOW` + `setOnlyAlertOnce`，**没有任何操作按钮**），
  点按经 `ACTION_OPEN_DOWNLOADS` 回到 App 的「下载」Tab，跳转只消费一次。
- `POST_NOTIFICATIONS` 在冷启动且开关开着、通知不可见时申请一次；`MainActivity.onResume` 重读
  `areNotificationsEnabled()`。被拒不影响保活本身，设置页给红字与「去设置」出口。
- 打卡点只在用户动作路径（入队、Cookie 授权续跑、现场打开开关且队列非空），避开 Android 12+
  「后台不得起前台服务」的限制；等 Cookie 授权时队列视为空，服务收摊，续跑时再打卡。

## Cookie：默认关，按域名授权

未授权前连 WebView 自己的 CookieStore 都不碰；授权后 Cookie 值只注入同站点的本机下载请求，
不进 `SniffResult.headersHint`、不上行引擎。审计行只出现域名与 `present|absent`，值出现即为泄漏面。
端到端取证见 `../docs/reports/acceptance-m6-5.md`。

## 包结构

```
com.hpu.browseguard
├── MainActivity.kt
├── data/     # EngineClient、backend/（本机执行者）、browser/、cookie/、download/、history/、keepalive/、media/、output/、playback/
└── ui/       # Compose：BgvApp / HomeScreen / BrowserScreen / HistoryScreen / DownloadList / VideoPlayerScreen / SettingsScreen
```

## 构建与测试

```powershell
# 出 debug 包（产物在 android/app/build/outputs/apk/debug/app-debug.apk）
.\gradlew.bat :android:app:assembleDebug

# 本模块 JVM 单测：471 项，不需要设备
.\gradlew.bat :android:app:testDebugUnitTest --rerun
```

测试分两类，缺一不可：

- **行为测试**（`LocalDirectDownloaderTest` 用 MockWebServer 真发真收、`HlsConcurrentDownloadTest`、
  `TsRemuxLogicTest`、`AudioUnitWriterTest`、`AdtsFrameScannerTest`）：ADTS 头、PTS、字节这些纯逻辑
  全部做成不依赖 Android framework 的类，好在 JVM 里逐帧断言。
- **接线测试**（`RemuxWiringTest`、`BrowserWiringTest`）：`MediaExtractor`/`MediaMuxer`/`WebView` 这些
  只能真机跑的调用点，用扫源码文本的方式钉住形状——包括反向钉子「临时诊断层不许留在生产代码里」。
  这类钉子防的是「改对了逻辑、漏了一处调用」，真机复跑仍然是最后一道。

`unitTests.isReturnDefaultValues = true` 是保留项（评估过程见 `../docs/reports/smoke-report.md` §15），
代价是 Android framework 的假对象会静默返回默认值，所以凡调用点必须靠接线测试与真机复跑兜住。

## 已知边界（登记，不掩饰）

- 带 CRC 的 ADTS 头（每帧多 2 字节）在逐帧走查时长度会少算 9 字节；触发条件是 TS 里出现 CRC 保护的 ADTS，
  真机素材未遇到，且这条路径失败时走降级保留 `.ts`，不会产出坏文件。
- HE-AAC/SBR（2048 样本/AU）不单独识别，按 LC 的 1024 步进会给成两倍时长；由时长与内容比例门禁兜住 → 降级。
- DRM（SAMPLE-AES / Widevine / FairPlay）一律拒绝并如实展示，不提供下载入口。
- blob/MSE 不伪造可下载的 `SniffResult`。
- 项目不引入 FFmpeg/ffmpeg-kit，APK 体积不因此变大。
- 下载保活只在 MuMu 模拟器上验证过：真实 Doze/厂商省电策略与 `dataSync` 时长额度上限未实测；
  R8 包已补测服务、通知与 `PendingIntent`（`../docs/reports/smoke-report.md` §32.6）与加密 HLS 解密（§33.7）。

里程碑与逐轮取证见 `../docs/reports/smoke-report.md`（§21 本机直连落地、§27–§29 M7/M8/M9、§31 M12.5 与首次 R8 设备腿、§32 M13 下载保活、§33 M14 AES-128 解密）；
M10/M11/M12 的改动与验收按提交记录追溯（`fc78073` … `924c350`，M12 是 `38fac08`）。

## 后续优化方向

以下方向尚未实现，登记于此供后续迭代参考；每条都对应一个「现状里确实做不到」的痛点。

### 直播流录制（Live HLS）

- **痛点**：现在的逻辑是「清单没有 `ENDLIST`（直播）直接拒绝」——`HlsDownloader` 在 prepare 阶段看到
  `!media.endList` 就返回 `unsupportedLink("这是直播流（清单没有 ENDLIST），本机只下点播")`，
  嗅探抽屉里也标一行「直播流，本机只下点播」。这个判据本身是对的：直播清单里的段只是当下的几秒，
  按点播逻辑下下来就是一段残片。但很多体育赛事、秀场直播确实有录制价值，一竿子拒绝就漏掉了。
- **优化**：增加一个「录制直播」模式。不做全量解析，改为**边下边合**——持续轮询媒体清单追新的分片，
  边下边落盘；用户点「停止」时，把已下载的分片合并成一个完整的 MP4。
  复用现有的 `HlsDownloader` 并发落盘与 `TsRemuxer` 纯拷贝转封装，只把「一次性拉全量段」改成
  「追新段直到用户叫停」，转封装仍走既有的音频完整性门禁链，绝不产出哑巴/半截报错的 `.mp4`。

### 手机端 WebDAV / SMB 备份

- **痛点**：手机存储空间有限，下了一堆大视频最后还是得手动导出来，设备一换或清理就丢。
- **优化**：在下载完成或文件管理中增加「推送到 NAS」能力。利用后台把下载好的文件直接上传到
  群晖（WebDAV / SMB）或阿里云盘，彻底解放手机空间。上传走 OkHttp（WebDAV 是标准 HTTP 动词，
  无需新增 native 依赖），可复用现有「下载保活」的前台服务与 `dataSync` 节拍挂起上传队列；
  上传目标与凭据只存本机，不进引擎、不上云，延续「数据不出设备之外的可控边界」这条主线。

### 网页音频提取（纯音频下载）· M24 已落地（电脑引擎侧）

- **痛点**：有时候看网页其实只想听声音——听播客、听相声、听讲座，把整条视频下下来既占空间又没必要。
- **已做**：画质卡在**画质列表底部、「视频标题命名」上方**加了一行「纯音频下载」。勾上后客户端发
  `FormatSelector(strategy=audio_only)`，引擎解析成 `bestaudio[ext=m4a]/bestaudio`——优先 m4a
  音轨（容器兼容性最好），源里没有就回落到质量最高的原始音轨（常见 webm/opus），**不转码**；
  后缀由 yt-dlp 按真实容器补，纯音频绝不会落成 `.mp4`。勾选后画质行置灰、合并行失效
  （没有视频轨可合），三者互斥关系在状态层就锁死，不是靠界面灰一下。
- **边界**：只有电脑引擎能做。手机端没有 FFmpeg，「抽音轨」这一步没有执行者，本机模式下
  这一行置灰并写明原因（与「合并音视频」同一口径）。
- **还没做**：手机本机的音轨提取（`MediaExtractor` 只选音轨 + `MediaMuxer` 重封装）。
  现有 `TsRemuxer` 的纯拷贝能力可以复用，缺的是「任意源 → 纯音频容器」这层泛化。

### 规则订阅与自定义 JS 脚本

- **痛点**：网站反爬总是更新，App 里的嗅探规则如果需要更新，就必须重新发版。
- **优化**：让 App 支持从指定的 GitHub 链接下载「嗅探规则包」（JSON 格式），在运行时动态注入 JS 逻辑。
  这样维护起来就灵活多了。
- **不足**：安全风险极大，违背了 App 对「数据绝对安全」的初衷。

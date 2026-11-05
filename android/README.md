# android/ — Android 客户端

包名 `com.hpu.browseguard`，应用显示名「无痕掠影」，英文名 Traceless Glimpse。
定位：**既是引擎客户端，也能完全离线自己把活干完**——设置页在「引擎」与「本机」两个执行者之间切换。
切到本机后，`data/backend/` 里不出现 `EngineClient`、`TaskCreateRequest`、`DownloadRequest`、`encode(`，
这条由 `TaskNeverGoesOnTheWireTest.localSideStaysOffTheWire` 扫源码钉住：本机的活不借引擎的协议发言。

## 现状（M9 收官）

- 已接入 Gradle 构建，是 `settings.gradle.kts` 里的 `:android:app`，协议模型直接复用 `:shared`（KMP）单一来源，不重复定义。
- UI 是 Jetpack Compose + Material 3（`buildFeatures.compose = true`），不是模板时代的 Views 栈。
- 存储用 Room（历史记录），网络用 OkHttp，`WebView` 走 androidx.webkit。
- `minSdk 29`（PRD NFR-05 Android 10+）、`targetSdk 36`、`compileSdk 36.1`、AGP 9.1.0 / Gradle 9.3.1。
- `versionName = "1.0.0"`、`versionCode = 1`。出包与归档流程见 `../docs/release-checklist.md`。

## 两套后端并存

| 执行者 | 代码入口 | 干什么 |
|---|---|---|
| 引擎 | `data/EngineClient.kt` | 走 `docs/PROTOCOL.md` 的 HTTP/JSON，嗅探交给 Playwright、下载交给 yt-dlp、后处理交给 FFmpeg |
| 本机 | `data/backend/LocalWebViewSniffer.kt`、`LocalDirectDownloader.kt`、`backend/hls/`、`backend/remux/` | 纯 OkHttp + 平台媒体 API 在设备内完成发现、下载、切片、转封装，**一个字节都不出手机** |

本机的 HLS 链路是这条路径上最重的一段，值得单列：

1. `HlsPlaylistParser` 解析清单（master/media、EXTINF、密钥 URI、分片地址）。
2. `HlsDownloader` 固定 4 个并发槽位、按清单顺序落盘，每段 1 次正常 + 2 次退避重试
   （401/403/404/405/410 不重试）；密钥按 URI 缓存并串行化获取；内存峰值钉在 4 段以内。
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

## Cookie：默认关，按域名授权

未授权前连 WebView 自己的 CookieStore 都不碰；授权后 Cookie 值只注入同站点的本机下载请求，
不进 `SniffResult.headersHint`、不上行引擎。审计行只出现域名与 `present|absent`，值出现即为泄漏面。
端到端取证见 `../docs/acceptance-m6-5.md`。

## 包结构

```
com.hpu.browseguard
├── app/            # 应用入口、导航、依赖注入
├── core/           # model / net / security / storage / common
├── data/           # EngineClient、backend/（本机执行者）、browser/、cookie/、history/、output/
├── di/             # AppContainer 装配
├── engine/         # bridge（协议适配）/ embedded（内置轻量下载）
├── feature/        # browse / download / agent / library / settings
└── ui/             # Compose 界面：BgvApp / HomeScreen / BrowserScreen / HistoryScreen / SettingsScreen
```

## 构建与测试

```powershell
# 出 debug 包（产物在 android/app/build/outputs/apk/debug/app-debug.apk）
.\gradlew.bat :android:app:assembleDebug

# 本模块 JVM 单测：283 项，不需要设备
.\gradlew.bat :android:app:testDebugUnitTest --rerun
```

测试分两类，缺一不可：

- **行为测试**（`LocalDirectDownloaderTest` 用 MockWebServer 真发真收、`HlsConcurrentDownloadTest`、
  `TsRemuxLogicTest`、`AudioUnitWriterTest`、`AdtsFrameScannerTest`）：ADTS 头、PTS、字节这些纯逻辑
  全部做成不依赖 Android framework 的类，好在 JVM 里逐帧断言。
- **接线测试**（`RemuxWiringTest`、`BrowserWiringTest`）：`MediaExtractor`/`MediaMuxer`/`WebView` 这些
  只能真机跑的调用点，用扫源码文本的方式钉住形状——包括反向钉子「临时诊断层不许留在生产代码里」。
  这类钉子防的是「改对了逻辑、漏了一处调用」，真机复跑仍然是最后一道。

`unitTests.isReturnDefaultValues = true` 是保留项（评估过程见 `docs/smoke-report.md` §15），
代价是 Android framework 的假对象会静默返回默认值，所以凡调用点必须靠接线测试与真机复跑兜住。

## 已知边界（登记，不掩饰）

- 带 CRC 的 ADTS 头（每帧多 2 字节）在逐帧走查时长度会少算 9 字节；触发条件是 TS 里出现 CRC 保护的 ADTS，
  真机素材未遇到，且这条路径失败时走降级保留 `.ts`，不会产出坏文件。
- HE-AAC/SBR（2048 样本/AU）不单独识别，按 LC 的 1024 步进会给成两倍时长；由时长与内容比例门禁兜住 → 降级。
- DRM（SAMPLE-AES / Widevine / FairPlay）一律拒绝并如实展示，不提供下载入口。
- blob/MSE 不伪造可下载的 `SniffResult`。
- 项目不引入 FFmpeg/ffmpeg-kit，APK 体积不因此变大。

里程碑与逐轮取证见 `../docs/smoke-report.md`（§21 本机直连落地、§27–§29 M7/M8/M9）。

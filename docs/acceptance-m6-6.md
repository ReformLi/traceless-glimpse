# BrowseGuard Vid M6-6 验收报告（发布资产与 Release 配置）

日期：2026-11-03。设备：MuMu SM-S9080 / Android 15（`192.168.31.57:5555`）。
被测包：`android/app/build/outputs/apk/release/app-release.apk`，**3,206,651 字节**，09:02 构建、09:07 安装。
设备侧确认它确实是 release 包而非「debug 换皮」：`dumpsys package` 给出
`flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]`（**没有 DEBUGGABLE / TEST_PACKAGE**）、`versionName=1.0`；
uiautomator 节点表里根节点类名是 `aq`（混淆生效）。
截图与日志留在 `.devtmp/`（已 gitignore），本文按 `m66-xx` 点名。

## 1. 六条要求与落点

| 要求 | 落点 | 现场证据 |
|---|---|---|
| 1.1 自适应图标替换绿色小机器人 | `res/drawable/ic_launcher_{background,foreground}.xml` 重写；`mipmap-{m,h,x,xx,xxx}hdpi/ic_launcher*.webp` 共 10 个文件删除 | §4 + `m66-30-icon-crop.png` |
| 1.2 清理临时文案 | 删 `home_stage`（「M6 · 首页与全状态覆盖」）串与两处页脚；`engine_url_hint` 改成与网络规则同源的说法 | §3、`grep` 见 §9 |
| 1.3 `android:label` 为「览盾视频」 | **本来就满足**：`app_name` 早已是「览盾视频」，manifest 用 `@string/app_name`。本轮没有假装修改 | `m66-30-drawer.png` 启动器标签 |
| 2.1 明文只放行回环 | `network_security_config.xml` 删 `10.0.2.2`，只剩 `127.0.0.1` + `localhost` | §2 |
| 2.2 备份规则收口 | `allowBackup="false"`，两个 AS sample 规则文件与其 manifest 引用一并删除 | §3 |
| 3 R8 | `isMinifyEnabled=true` + `isShrinkResources=true`；Keep 规则三组 | §5 |
| 4 签名 | `signingConfigs` 读本机 `keystore.properties`（与 `keystore/` 均已 gitignore）；测试 release keystore 生成成功 | §6 |
| 5 真机复验 | debug 卸载 → Release 安装 → 三 Tab + 引擎/本机两条主流程 + 取消 + 历史 + 设置 | §7 |
| 5 硬证据 | 5,581 行 logcat 里 `JsonDecodingException`/`SerializationException` **0 命中**；`BgvAudit` 27 行仍在 | §8 |
| 6 提交拆分 | `build` / `feat` / `docs` 三条 | §11 |

## 2. 明文放行：只剩回环

收口后整个文件只有一条 `domain-config`：

```xml
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">127.0.0.1</domain>
        <domain includeSubdomains="false">localhost</domain>
    </domain-config>
</network-security-config>
```

`10.0.2.2`（Android 模拟器访问宿主机的固定网关）删掉的这不是「少一个方便」。它是可路由地址而非回环，
留着就等于承认「明文可以打到回环以外的主机」，与文件头那句承诺自相矛盾。
`includeSubdomains="false"` 保持不动：回环没有子域概念，写成 true 只会让规则看起来更像通配。

删地址牵出一条**会引起误判的文案**，按规矩同轮改掉：`engine_url_hint` 原来教用户
「模拟器把 127.0.0.1 换成 10.0.2.2」——照做会被网络策略拦下，而界面不会解释为什么。
现在改成「明文只放行回环，局域网或模拟器宿主网关请给引擎配 TLS 后用 `https://`」；
`data/EngineConfig.kt:44` 的 KDoc 同步。`EngineUrlTest.acceptsEmulatorHost` 保留——
它测的是地址归一化语法，不是网络放行策略，两者本来就无关。

## 3. 备份：整条通道关死，而不是逐条排除

`allowBackup="true"` → `"false"`，并删掉 `android:dataExtractionRules` / `android:fullBackupContent`
两个属性和它们指向的 `res/xml/backup_rules.xml`、`res/xml/data_extraction_rules.xml`
（两个文件都是 Android Studio 生成的 sample，从没被编辑过，留着就是「看起来管住了其实没管」的死配置）。

为什么不用 `<exclude>` 清单：这个 App 能被备份出去的东西全是要紧的——
`shared_prefs/bgv_cookie`（Cookie 授权记录）、`bgv_engine`、`bgv_output`、
历史记录库 `browseguard.db`，以及 WebView 自己的 CookieStore（`app_webview/` 下的域文件）。
排除清单得人工跟着新增文件跑，**漏一个就是一个静默泄漏**，与项目「不静默降级」的规矩冲突。

代价（登记进 §10）：换机后设置、引擎地址、Cookie 授权、历史记录从零开始。
已下载产物不受影响——它们在 Download 目录与 SAF 所选文件夹里，本来就不走备份。

## 4. 图标：手绘矢量，逐点核对安全区

前景是盾牌轮廓 + 中央播放三角，三角用 `fillType="evenOdd"` 打成透气的孔，露出背景层墨蓝：

```xml
<path android:fillColor="#FF14B8A6" android:fillType="evenOdd"
      android:pathData="M54,27 L77,35.5 L77,55 C77,67.5 67,76.5 54,81 C41,76.5 31,67.5 31,55 L31,35.5 Z
                        M46.5,43 L65,53 L46.5,63 Z" />
```

自适应图标 108×108 视口里，四周各 18dp 是可被启动器裁掉的余量，中心 66dp 才是安全区。
本图形最宽处在 y=35.5（x 31..77）、最高最低处在 x=54（y 27..81），逐点核对过内切半径 33dp
的安全圆，圆角遮罩啃不到盾牌边缘——`m66-30-icon-crop.png` 里启动器把图标切成方角圆边，四边完整。

背景层从 AS 默认的 `#3DDC84` 绿满幅 + 网格线换成满幅 `#FF0B1220`（深色主题窗口底色同源）。
用实色而不是渐变：启动器会按各自形状裁切，实色在任何遮罩下都不会「切边露底」。
`mipmap-anydpi-v26/ic_launcher.xml` 与 `_round.xml` 未改，monochrome 层复用同一份前景——
单色主题下系统只取 alpha 重新着色，「盾牌 + 播放孔」的剪影两种模式都读得出来。

10 个 `ic_launcher*.webp` 是绿色小机器人的本体，minSdk 29 的设备永远走 `mipmap-anydpi-v26`
那条自适应路径，取不到它们，故直接删除（含空目录）。`res/` 现在只剩
`drawable, mipmap-anydpi-v26, values, values-night, xml`。

## 5. R8：三组 Keep 规则，抄来的零组

`build.gradle.kts` 的 release 块：`isMinifyEnabled = true`、`isShrinkResources = true`，
proguardFiles 仍是 AGP 的 `proguard-android-optimize.txt` + `proguard-rules.pro`。

写规则之前把依赖自带的 consumer 规则逐个拆开看过（**这条清单本身就是本轮的取证**）：

| 依赖 | 自带规则覆盖内容 | 结论 |
|---|---|---|
| kotlinx-serialization-core 1.12.0 | `META-INF/com.android.tools/r8/kotlinx-serialization-{common,r8}.pro`：`@Serializable` 类的 `static Companion`、`serializer()`、`$$serializer.descriptor` 的 `-keepclassmembers`，加 `RuntimeVisibleAnnotations`/`AnnotationDefault` | 不抄 |
| okhttp-android 5.3.2 | aar 内 `proguard.txt`：conscrypt / bouncycastle / JSR305 的 `-dontwarn` | 不抄 |
| room-runtime 2.8.5 | aar 内 `proguard.txt`：`-keep class * extends androidx.room.RoomDatabase { void <init>(); }` | 不抄 |
| kotlinx-coroutines 1.11.0 | volatile 字段与 `MainDispatcherFactory` 相关规则 | 不抄 |
| kotlin-stdlib 2.4.21 | **jar 内没有任何 proguard 规则**（1.8/1.9/2.0/2.1/2.4 逐版本展开确认） | 得自己补，见 ② |
| AGP `proguard-android-optimize.txt` | `values()`/`valueOf`、`@JavascriptInterface`、`Signature`/`InnerClasses`/`*Annotations*` 属性组、`-dontwarn androidx.**`；**不含** `SourceFile,LineNumberTable` | 得自己补，见 ③ |

库里已覆盖的一律不抄：抄一份进来等于把库的规则冻结在今天的版本，以后它们修正反而被我们挡住。
剩下的三组各自解决一件真实的事：

1. `-keep class com.hpu.browseguard.shared.model.** { *; }` —— 严格说序列化在 R8 下本来就能跑通
   （`@SerialName` 与生成的 descriptor 把线名编译成了字符串常量）。留它是把「三端合同」显式钉进构建配置：
   这些类名一旦在某次升级里被改掉，症状是手机与电脑「协议对不上」而不是编译报错，代价远高于一份 keep。
   配套 `-dontnote com.hpu.browseguard.shared.schema.**` 把 schema 导出器明确放行给 R8 删掉，
   免得「协议模型必须留」和「导出工具也一起留」被同一条规则捆住。
2. `-keepclassmembers class **$WhenMappings { <fields>; }` —— 这张表按枚举常量名索引 int，
   一旦错开，表现是「`when` 落到错误分支」，属于最难查的一类 bug，而 kotlin-stdlib 不管它。
3. `-keepattributes SourceFile,LineNumberTable` + `-renamesourcefileattribute SourceFile` ——
   默认不带行号，release 崩栈只剩 `a.java:1`，用户截图回来我这边等于零信息；
   补行号的同时把源文件名统一改写成 `SourceFile`，APK 里既不携带仓库内真实路径，又保留定位能力。

**效果（09:28 干净重跑 `--rerun-tasks`，69 个任务全部执行）**：

| 指标 | 数值 |
|---|---|
| Release APK | 3,206,651 B |
| Debug APK（同代码） | 35,122,006 B |
| 缩小 | **90.9%** |
| `mapping/release/usage.txt`（被删成员） | 55,083 行 |
| `mapping/release/resources.txt`（被删资源） | 498 行 |
| 构建日志里 `warning` / `Missing class` | **0 行** |
| `mapping.txt` 里 `Task` / `SniffResult` / `FormatInfo` | 三行全部 `X -> X:` 原样映射 |
| `seeds.txt` 里 `shared.model.*` 条目 | 1,068 |

`mapping.txt`、`usage.txt`、`seeds.txt` 都在 `android/app/.gitignore` 的 `/build` 覆盖范围内，
**不入库**（正式发布的还原归档另说，见 §10）。

## 6. 签名：v3 落地，v2 没出现——如实记

`signingConfigs` 从本机 `android/app/keystore.properties` 读四个字段；读不到不报错也不签，
只打一行「本次 Release 产出未签名 APK（装不上设备，仅供构建验证）」——
把「没有密钥」做成构建失败，会让所有不带密钥的验证构建陪绑。
`keystore.properties` 与 `android/app/keystore/` 都进了 `.gitignore`（`*.jks` 那条本来就挡住了键文件）。

密钥：`android/app/keystore/bgv-release.jks`，RSA 2048，validity 10000 天，
`CN=BrowseGuard Vid, OU=Release Test, O=BrowseGuard Vid, L=Local, ST=Local, C=CN`，口令 32 字符随机。

签名方案显式写死：`enableV1=false`（minSdk 29，v1 只服务 API < 24 的安装路径）、
`enableV2=true`、`enableV3=true`、`enableV4=false`（v4 给 Play 流式安装算 Merkle 树，侧载用不上还多产 `.idsig`）。

`apksigner verify --verbose --print-certs` 原文（09:28 重跑后的包，与装机包同尺寸）：

```
Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): false
Verified using v3 scheme (APK Signature Scheme v3): true
Verified using v3.1 scheme (APK Signature Scheme v3.1): false
Verified using v4 scheme (APK Signature Scheme v4): false
Verified for SourceStamp: false
Number of signers: 1
Signer #1 certificate DN: CN=BrowseGuard Vid, OU=Release Test, O=BrowseGuard Vid, L=Local, ST=Local, C=CN
Signer #1 certificate SHA-256 digest: 0bcc88378158c4a090517a33f30ce1774aa7f223d479eb4302a169e16b151b99
Signer #1 certificate SHA-1 digest: 7dc45fbd72e9f2714c5f01c4bdf18a87c44851f3
Signer #1 key algorithm: RSA
Signer #1 key size (bits): 2048
```

**`Verifies` 为真、签名者 1 个、v3 通过；但 v2 是 false，与配置里 `enableV2Signing=true` 不符。**
这不是我配错了，三条排查都指向 apksigner/apkzlib 在 minSdk 29 下的方案选择：

1. AGP 侧配置确实落地：
   `intermediates/signing_config_versions/release/.../signing-config-versions.json`
   = `{"enableV1Signing":false,"enableV2Signing":true,"enableV3Signing":true,"enableV4Signing":false}`。
2. 绕过 AGP 直接看包：从 EOCD 反推中央目录，APK Signing Block 起点 3185036、长度 12280、
   尾部魔数 `APK Sig Block 42` 就位；在块内按 4 字节 pair id 搜 v2 的 `0x71098880` —— **不在包里**
   （第一次手工走 TLV 得到的 `0x2ff8`/`0x630` 之类是偏移算错，改用子串搜索才是可靠判据）。
3. 用 apksigner CLI 拿探针副本重签，`--v2-signing-enabled true --v3-signing-enabled true`
   以及 `--min-sdk-version 24` 两种组合，v2 pair 依然不出现。

功能判据通过：`adb install` 在 Android 15 上 `Success`（streamed 3206651 bytes）。
minSdk 29 意味着装这个包的机器最少是 Android 10，而 v3 从 Android 9（API 28）起就被完整支持——
v2 缺席在这个配置下不会造成任何设备装不上。登记为 §10-1，不当成已通过。

## 7. Release 包真机复验（逐腿）

debug 包先卸载，Release 包 09:07 装到 MuMu。链路：引擎 real `127.0.0.1:8730`（`adb reverse`），
测试媒体 `127.0.0.1:8731`。每步 tap 坐标都取自当次 fresh 的 `-nodes.txt`。

| 腿 | 结果 | 截图 |
|---|---|---|
| 启动 / 三 Tab 齐全 | 「览盾视频」+ 副标题「嗅探：电脑引擎 · 下载：电脑引擎」+ pill「已连接电脑」；**没有 M6 页脚** | `m66-00-launch` |
| 引擎嗅探 `page.html` | `hls · 画质未标注 / 无 DRM / m3u8` + 直链 | `m66-03-sniffing`、`m66-04-formats` |
| 画质表 | `awaiting_selection` + 共 1 项 + 命名模板变量行 + merge 复选框 | `m66-06-scrolled` |
| 引擎下载跑到终态 | 「下载完成」60.2 KB，文件位置 `C:\Users\19564\Videos\BGV\master.mp4`；引擎 `download_complete bytes=61659` | `m66-08-dlstarted` |
| 引擎侧「手机打不开电脑文件」 | 卡片明说「文件保存在电脑上（引擎侧），手机无法直接打开」，「打开文件」按钮 `enabled=false` | 同上 |
| 取消 | 进度 2.7/4.0 MB、853.7 KB/s、剩余 1s → 二次确认弹框 → 「任务已取消」；引擎 `download_aborted ... aborted after 3735552 bytes` | `m66-14-downloading`、`m66-15-cancelled`、`m66-16-aftercancel` |
| 本机嗅探 `page-cookie.html` | WebView 拦到 `protected.mp4` 直链，共 1 条 | `m66-19-localdone` |
| 本机嗅探 + 引擎下载 = 可见降级 | 引擎无 Cookie → 站点 403 → 空格式表 → 卡片「未发现可用画质 · 引擎回空格式表，客户端本地判定」+「可重试」 | `m66-20b-formcard` |
| 设置页读取 | 引擎地址、`已连接 · 引擎 0.1.0 · 协议 0.2`、隐私自检四项、执行方式、输出位置、Cookie 面板、历史统计「共 3 条记录 · 1 条已完成」 | `m66-21-settings`、`m66-21b-settings2`、`m66-21c-settings3` |
| 切本地直链后副标题 | 「嗅探：本机浏览器 · 下载：本地直链」；merge 复选框 `enabled=false` +「本地模式不支持合并，请连接引擎」 | `m66-22-localdl`、`m66-23-home-local`、`m66-26-formlocal` |
| Cookie 授权弹框 | 「允许读取 127.0.0.1 的登录状态？」含「绝对不会发送给电脑引擎」与「先在本应用内打开该网页并登录」 | `m66-27-cookiedlg` |
| 本机下载跑到终态 | 「protected.mp4 · 60.0 KB」，「打开文件」`enabled=true`；设备落盘 61,456 B | `m66-28b-localdone` |
| 历史 Tab（Room + R8） | 5 行：已完成 09:19 / 待下载 09:19 / 失败 09:17 / 已取消 09:16 / 已完成 09:13 | `m66-29-history`、`m66-29b-history2` |
| 启动器图标 | 深色圆角方块 + 青绿盾牌 + 播放孔，标签「览盾视频」 | `m66-30-drawer`、`m66-30-icon-crop` |

配对证据（谁拿到了 Cookie）：

```
站点侧 09:17:31 media_req path=/media/protected.mp4  cookie_present=false   ← 电脑引擎来抓同一条直链
引擎侧 09:17:31 WARNING formats_extract_failed task_id=01a19efb-… type=DownloadError
站点侧 09:19:53 media_req path=/media/protected.mp4  cookie_present=true range=bytes=0-   ← 授权后手机本机下载
客户端 09:19:55 BgvAudit: cookie_read host=127.0.0.1 cookie=present
客户端 09:19:55 BgvAudit: cookie_injected host=127.0.0.1 cookie=present
```

历史里那条「待下载」不是残留：`resolveColdStart()`（`RoomHistoryRepository.kt:214`）的裁决就是
「引擎任务 done 时，sniff 记录落 READY、download 记录另起一行落 DONE」，
所以一次成功下载留两行是设计语义，与 M6-3 已取证的口径一致。

## 8. 硬证据：混淆没破坏反序列化

`.devtmp/m6-6/release-logcat.txt`（5,581 行，覆盖本轮全部设备腿）：

```
JsonDecodingException / SerializationException / JsonPullParserException   0 命中
与 browseguard / json / serial 相关的 exception 或 AndroidRuntime 行        0 命中
BgvAudit 行                                                              27 行
```

27 行审计按类分布：`webview_request` 12、`webview_session` 6、`webview_candidate` 3、
`merge_ignored` 1、`cookie_read` 1、`cookie_injected` 1、`cookie_consent_required` 1、
`cookie_blocked` 1、`audit_cookie_consent_granted` 1。
值仍然只记存在性（`cookie=present`，不是 Cookie 串），说明 M6-5 的脱敏在 R8 之后照样生效。

混淆确实生效（不是「忘了开」造成的假绿），三条并列：

- uiautomator 根节点类名 `aq`；
- `usage.txt` 55,083 行被删成员、`resources.txt` 498 行被删资源；
- 同一份包里 `Task`/`SniffResult`/`FormatInfo` 在 `mapping.txt` 里原样映射——即 keep 规则按预期只保护了协议模型，其余照混。

## 9. 现场发现、当场修掉的 3 处

1. **`engine_url_hint` 还在教用户填 `10.0.2.2`**（`strings.xml:107`）。删了网络放行却不改文案，
   等于给用户一条必然失败的操作指引。同轮改成「明文只放行回环 + 局域网请配 TLS」。
2. **`EngineConfig.kt:44` KDoc 同一处说法**。代码注释里的地址策略与 `network_security_config.xml`
   不一致，下一个人会照着注释把配置改回去。
3. **`build.gradle.kts` 的 `import java.util.Properties` 一开始放在 `plugins {}` 之后**，Kotlin 要求
   import 在文件最前，脚本编译不过；同时放弃了「顶层 `fun` 里调 `file()`」的写法（脚本函数体不保证拿到
   Project 接收者），改成顶层表达式加载 Properties。这条属于本轮自纠，不是既有缺陷。

`grep -nE "M[0-9]|测试用|临时" res/values/strings.xml` → 0 命中。要求 1.2 达成。

## 10. 已知缺口

| # | 缺口 | 影响与判断 |
|---|---|---|
| 1 | **v2 签名方案在 minSdk 29 下不落地**（§6 三条排查） | Android 10+ 走 v3 足够，无设备装不上。若将来为兼容 API 24–27 而下调 minSdk，必须回头复核这一条 |
| 2 | `allowBackup=false` 的换机代价 | 设置、引擎地址、Cookie 授权、历史记录从零开始；产物不受影响。用户已裁决「关死」，这里只登记代价 |
| 3 | 测试 keystore 不能用于分发 | 自签、`OU=Release Test`、口令只存在本机一个明文文件里。正式签名要换密钥、进密码管理器，并同步归档当次 `mapping.txt` |
| 4 | `mapping.txt` 不入库 | 崩栈还原目前只依赖本机 `build/` 目录，`clean` 就没了。正式发布需按版本号归档到仓库外 |
| 5 | `HistoryScreen.kt:250` 编译告警：`rememberSwipeToDismissBoxState(confirmValueChange=...)` 已弃用 | 官方替代是 dynamic anchors，改它会动滑动删除的交互行为，本轮未点名 → 不动。这是 release 构建里**唯一**一条编译告警 |
| 6 | 引擎把「格式表抽取失败」降级成 WARNING，客户端只能看到空表 | 界面文案因此只能说「引擎回空格式表，客户端本地判定」，无法告诉用户「这台站点要登录，请把下载执行者换成本机直链」。修法在协议侧（`Task.warnings` 带原因），牵动三端，不属 M6-6 范围 |
| 7 | `engine -m real` 本轮未复跑 | 本轮 `git status` 里引擎零改动（只有 `android/`、`.gitignore`、`docs/`）。沿用 M6-5 基线 175 passed + 1 xfailed，未当场复现，按缺口登记 |
| 8 | 未跑 Release 变体的仪器化测试 | AGP 9.1 的 `:android:app` 只有 `testDebugUnitTest`，`connectedAndroidTest` 同样只出 debug。§7 全部走人工设备腿取证替代 |

## 11. 测试与提交

| 套件 | 数量 | 口径 |
|---|---|---|
| `:android:app:testDebugUnitTest` | `tests=183 failures=0 errors=0` | `--rerun-tasks` 从零跑，51 个任务全部执行 |
| `:shared:jvmTest` | 11 passed | 同上 |
| `:engine-client:test` / `:cli:test` / `:mcp:test` | 12 / 26 / 13 passed | 同上 |
| JVM 合计 | **245 tests，0 失败 0 错误** | |
| `engine` 桩套件 | **235 passed, 176 deselected** | 引擎与媒体服务进程已 kill 后单跑，无并发抢占 |

AGP 9.1 不生成 `testReleaseUnitTest`（`:android:app:tasks --all` 里只有 debug 变体），
所以「Release 下的行为」只能靠 §7/§8 的设备证据回答，不能靠单测。

提交三条：

```
build(android): M6-6 配置 R8 混淆与本地签名
feat(android):  M6-6 替换正式图标与清理文案
docs(android):  M6-6 发布清单与合规收口
```

## 12. 复现命令

```bash
# 1. 干净出包（仓库根；日志里 warning / Missing class 应为 0 行）
GRADLE_USER_HOME=D:/Android/GradleUserHome ./gradlew.bat :android:app:assembleRelease --rerun-tasks --console=plain

# 2. 签名校验（v3=true、v2=false、Verifies=true）
"D:/Android/AndroidSDK/build-tools/36.0.0/apksigner.bat" verify --verbose --print-certs \
    android/app/build/outputs/apk/release/app-release.apk

# 3. 确认装的是 release（flags 里不该有 DEBUGGABLE）
MSYS_NO_PATHCONV=1 adb -s 192.168.31.57:5555 shell \
    "dumpsys package com.hpu.browseguard | grep -E 'versionName|flags'"

# 4. JVM 回归（注意：没有 testReleaseUnitTest 这个任务）
GRADLE_USER_HOME=D:/Android/GradleUserHome ./gradlew.bat \
    :android:app:testDebugUnitTest :shared:jvmTest :engine-client:test :cli:test :mcp:test --rerun-tasks

# 5. 设备腿前置：adb reverse + 引擎 + 测试媒体（三门控 env 只为本机测试媒体，用完即 kill）
adb -s 192.168.31.57:5555 reverse tcp:8730 tcp:8730 && adb -s 192.168.31.57:5555 reverse tcp:8731 tcp:8731
cd engine && BGV_ENGINE_MODE=real BGV_SSRF_ALLOWLIST=127.0.0.1 BGV_DEV_ALLOW_SSRF_BYPASS=1 \
    ./.venv/Scripts/python.exe -m uvicorn main:app --host 127.0.0.1 --port 8730 --access-log
./.venv/Scripts/python.exe -m testsupport.media_server --host 127.0.0.1 --port 8731 --bulk

# 6. 硬证据计数（先跑完设备腿再 dump，否则 0 命中没有意义）
MSYS_NO_PATHCONV=1 adb -s 192.168.31.57:5555 logcat -d > .devtmp/m6-6/release-logcat.txt
grep -cE "JsonDecodingException|SerializationException" .devtmp/m6-6/release-logcat.txt   # 期望 0
grep -c BgvAudit .devtmp/m6-6/release-logcat.txt                                          # 期望 > 0
```

设备操作两个坑（本轮都踩过）：`adb shell input text` 在 MuMu 上不解码 `%3A` 之类的百分号转义，
URL 必须写成 `MSYS_NO_PATHCONV=1 adb ... shell "input text 'http://…'"`；
Compose 卡片折叠在视口外时按钮会显示 `enabled=false`，先 `input swipe 450 1100 450 400 300` 再重抓节点，
别把「没滚到」当成「点不动」。

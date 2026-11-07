# 无痕掠影 (Traceless Glimpse) 发版 checklist

这份文件解决的是一个具体事故场景：用户发来一张 release 崩溃截图，栈里只有
`aq.r(SourceFile:37)`，而 `mapping.txt` 躺在本机 `build/` 目录里、上一次 `clean` 已经把它删了。
那一刻这行堆栈是永久不可还原的——不是「麻烦」，是**这条 bug 永远查不下去**。

所以规则只有一条硬性的：**打完 Release 包，当场归档 mapping，再干别的。**

## 1. 发版步骤（按顺序，每步都要落一个可核对的东西）

| # | 动作 | 命令 / 位置 | 通过判据 |
|---|---|---|---|
| 1 | 版本号先改，改完不许再动 | `android/app/build.gradle.kts` 的 `versionCode` / `versionName` | 两个数字与后面归档的文件名逐字一致 |
| 2 | 干净出包 | `GRADLE_USER_HOME=D:/Android/GradleUserHome ./gradlew.bat :android:app:assembleRelease --rerun-tasks --console=plain` | `BUILD SUCCESSFUL`；日志里 `warning` / `Missing class` **0 行** |
| 3 | **归档 mapping（不可跳过）** | 见 §2 | `release-artifacts/mapping-v<versionName>-code<versionCode>.txt.gz` 存在且 §2 的往返校验通过 |
| 4 | 记 APK 尺寸与 sha256 | `sha256sum android/app/build/outputs/apk/release/app-release.apk` | 写进 §3 台账；下次发版对比尺寸异常增减 |
| 5 | 签名校验 | `"D:/Android/AndroidSDK/build-tools/36.0.0/apksigner.bat" verify --verbose --print-certs <apk>` | 首行必须是 `Verifies`；`Number of signers: 1`；v3=true |
| 6 | 装 Release 包跑设备腿 | 卸载 debug → `adb install -r` → 三 Tab + 引擎/本机两条主流程 + 取消 + 下载管理（打开/分享/重命名/删除/批量）+ **内置播放器（全屏、拖到中段退出、重进续播、列表进度、外部应用打开）** + 设置；音频链路另按 §6 | `adb logcat -d` 里 `JsonDecodingException` / `SerializationException` **0 命中**；播放器不崩且续播点与退出位置一致；§6 的 `hls_remux_done` 与 ffprobe 读数达标 |
| 7 | 台账补一行 | 本文件 §3 + 当轮验收报告 | 版本号、APK sha256、mapping 文件名、设备腿结论 |

第 6 步不能省的原因：R8 的行为只能跑起来才知道。`:android:app` 在 AGP 9.1 下**根本不生成
`testReleaseUnitTest`**（`:android:app:tasks --all` 里只有 debug 变体），所以「Release 下的行为」
在单测层面是覆盖不到的，只能靠设备证据回答。

## 2. mapping 归档细则

产物在 `android/app/build/outputs/mapping/release/`：`mapping.txt`（还原用）、
`seeds.txt`（被 keep 的条目）、`usage.txt`（被删的成员）、`resources.txt`（被删的资源）。
**只有 `mapping.txt` 必须入库**，其余三个留在本机 `build/` 里，需要时重新出包即可复现。

```bash
# 在仓库根执行；<versionName>-code<versionCode> 必须与 build.gradle.kts 逐字一致
python - <<'PY'
import gzip, shutil, hashlib, os
src = 'android/app/build/outputs/mapping/release/mapping.txt'
dst = 'release-artifacts/mapping-v1.1.0-code1.txt.gz'
with open(src, 'rb') as f, gzip.open(dst, 'wb', compresslevel=9) as g:
    shutil.copyfileobj(f, g)
raw = hashlib.sha256(open(src, 'rb').read()).hexdigest()
back = hashlib.sha256(gzip.open(dst, 'rb').read()).hexdigest()
assert raw == back, '归档解出来和原文件不一致，别提交'
print('mapping sha256', raw, os.path.getsize(src), '->', os.path.getsize(dst))
PY
```

三个约定：

- **命名**：`mapping-v<versionName>-code<versionCode>.txt.gz`。带上 `versionCode` 是因为
  `versionName` 可以被人为复用而 `versionCode` 不会——崩栈还原时错配 mapping 比没有 mapping 更糟，
  它会把堆栈"还原"成另一个函数的名字，看起来完全可信。
- **必须 gzip**：原文件 36,442,304 B，gzip 后 2,606,108 B（7.2%）。这个目录是永久历史，
  每次 clone 都要拉，所以不存明文。
- **必须做往返校验**：上面那段脚本最后 assert 的就是这个。归档失败最常见的形态不是"没归档"，
  而是"归档了一个解不开的包"——那要等到真的崩了才发现，已经来不及了。

还原崩栈（以下整段在本机 Git Bash 实测跑通过，输出贴在后面；演示用的是已归档的 `v1.0-code1`，
换成当次新包时只改文件名）：

```bash
export GRADLE_USER_HOME=D:/Android/GradleUserHome

# ① 解出明文 mapping。retrace 只吃明文——直接喂 .gz 会报
#    `InvalidMappingFileException: Unable to parse mapping file`（实测）。
python -c "import gzip; open('/tmp/mapping.txt','wb').write(\
    gzip.open('release-artifacts/mapping-v1.0-code1.txt.gz','rb').read())"

# ② retrace 不在 SDK 里，在 Gradle 缓存的 AGP `builder` jar 里。
#    build-tools 36.0.0 / 35.0.0 / 34.0.0 / 33.0.1 都没有 retrace.bat（逐个 ls 确认过），
#    所以别按网上老教程去找它；入口类是 com.android.tools.r8.retrace.Retrace。
BUILDER=$(find "$GRADLE_USER_HOME/caches/modules-2/files-2.1/com.android.tools.build/builder/9.1.0" \
    -name 'builder-9.1.0.jar')
java -cp "$BUILDER" com.android.tools.r8.retrace.Retrace /tmp/mapping.txt crash.log
```

实测一次完整往返。`crash.log` 是我按 `mapping.txt` 里的真实条目造的（`c50` = `EngineClient`、
`lc` = `AppContainer`），最后那条 `Caused by` 故意写一个 mapping 里不存在的类，用来验证它不乱猜：

```
java.lang.IllegalStateException: shuffle
	at c50.a(SourceFile:146)
	at com.hpu.browseguard.MainActivity.b(SourceFile:42)
	at lc.run(Unknown Source:7)
Caused by: java.lang.NullPointerException
	at a.q.invoke(SourceFile:2)
```
→ 真实输出（未加解释）：
```
java.lang.IllegalStateException: shuffle
	at com.hpu.browseguard.data.EngineClient.a(EngineClient.kt:146)
	at com.hpu.browseguard.MainActivity.b(MainActivity.kt:42)
	at com.hpu.browseguard.data.AppContainer.run(AppContainer.kt:7)
Caused by: java.lang.NullPointerException
	at a.q.invoke(SourceFile:2)
```

三点要记住：

- **认不出的帧原样留着**，不会给你一个看着可信的假名字——这正是文件名带 `versionCode` 的理由：
  拿错 mapping 时它不会报错，它会安静地还原出另一个函数的名字。
- `builder` 的版本号就是当次构建的 AGP 版本，换 AGP 后这条 `find` 的路径要跟着改；缓存被清空过就
  随便跑一次 `assembleRelease` 把它拉回来。
- 崩栈里的 `SourceFile` 能还原成 `EngineClient.kt`，靠的是 `proguard-rules.pro` 里那两条
  `-keepattributes SourceFile,LineNumberTable` + `-renamesourcefileattribute SourceFile`。
  谁哪天"顺手"把它们删了，归档下的 mapping 就只剩类名可还原，行号与文件名全部丢失。

归档存 gzip，还原前多解一次压——换来仓库永久少背 34 MB。`.gz` 不能直接喂给 retrace（上面注释里
那条报错就是这么来的），所以解压那步是流程的一部分，不是遗漏。

## 3. 已归档台账

| versionName / code | APK sha256 | APK 字节 | mapping 归档 | 设备腿结论 | 验收报告 |
|---|---|---|---|---|---|
| 1.0 / 1（2026-11-03 09:02，M6-6 测试签名） | `d486c26851138b865dededee6811fd5bb2d2e7bb550a68fa2b42975486f5ad68` | 3,206,651 | 旧 mapping（sha256 `7922724d…`，已被下行取代） | MuMu SM-S9080 / Android 15 十四腿全过，反序列化异常 0 命中 | [acceptance-m6-6.md](acceptance-m6-6.md) |
| 1.0 / 1（2026-11-03 10:33，定名「无痕掠影」重建，**未签名**） | 待签名后补 | 3,194,363（`app-release-unsigned.apk`） | `release-artifacts/mapping-v1.0-code1.txt.gz`（原 36,442,414 B，sha256 `7f6ae9fc519ed91d2c7e590261ea032cee1b4ab7d92e439e48cf1bbc7de2959e`） | 未跑设备腿（本机无签名口令，未出可安装包） | 本文件 §3 备注 |
| 1.1.0 / 1（2026-11-06 20:33，M12.5 **测试签名** R8 包） | `d5ef93af3a0940e7bf7444ce3277b861448c28ec3c257b04b664c2c20abf4979` | 5,304,748（签前 unsigned 5,284,313） | `release-artifacts/mapping-v1.1.0-code1.txt.gz`（源 `mapping.txt` sha256 前缀 `f612f7b8`，往返校验一致） | MuMu SM-S9080 / Android 15：冷启动无闪退、深嗅出候选、下载「已完成 1.4 MB」、下载管理→打开文件播放、续播 `resume=25221`、`adblock_hit` 每域每页一行；`JsonDecodingException`/`SerializationException` 0 命中 | 本文件 §7 + `docs/smoke-report.md` §31 |

三条备注（2026-11-03 定名重建；**这份台账的有效性问题见 §7，下次发版先读 §7**）：

- **mapping 只保留一份**，已按定名后的源码重建并覆盖旧档。09:02 那份 APK 从未分发，所以覆盖它
  不会让任何在外的崩栈失去对照；反过来说，**下次改代码再出包，必须回来重走第 3 步**，否则这条
  归档会悄悄变成"对不上的 mapping"。
- 未签名包只用于证明 R8 与资源在改名后仍能跑通（`aapt2 dump badging` 的
  `application-label:'无痕掠影'`、包名仍是 `com.hpu.browseguard`）。**可安装的 Release 需由维护者
  在本机填好口令后自己打**；打完把 sha256 与字节数补到上表第二行。
- 换签名密钥后，设备上旧的测试签名版本**不能覆盖安装**（签名不同），要先卸载。

## 4. 发布前置（一次性，但每次都要确认没坏）

- 仓库根目录的 `keystore.properties` 存在且四个字段非空。当前密钥是 `android/browseguard-vid.jks`
  （`storeFile=android/browseguard-vid.jks`、`keyAlias=mykey`；相对路径按仓库根解析，也可写绝对路径），
  口令由维护者本机填写，仓库里只有空位。
  两者的忽略规则都在 `.gitignore`（`*.jks` 那条本来就挡住了键）。**读不到时构建不失败，只出一个
  未签名包**并打一行提示——所以判据不是"构建成功"，而是第 5 步的 `apksigner verify` 真能验过。
  **2026-11-06 复核**：根目录这份 `keystore.properties` 已就位，但 `storePassword`/`keyPassword`
  两项长度实测为 0，构建照旧产出 `app-release-unsigned.apk`。本轮 R8 设备腿因此走 §3 的**测试签名**
  路线（`zipalign` + `apksigner` 用 `~/.android/debug.keystore`，证书 DN `CN=Android Debug`、
  SHA-256 `73866dbc…`，与设备上的 debug 包同证书，`adb install -r -d` 免卸载保数据）。
  **正式签名的 Release 仍只能由维护者本机填口令后自己打**；测试签名包不得分发。
- 换键之后旧安装无法覆盖，必须先卸载；这是不可逆决定。正式分发前确认这把 key 的 DN 与保存位置，
  口令不要只留在本机一个明文文件里。
- 签名方案实测：`enableV2Signing=true` 在 minSdk 29 下**不会**产出 v2 pair（apksigner CLI 重签同样不出），
  落地的是 v3。Android 10+ 装包与校验都不受影响；若将来为兼容 API 24–27 下调 minSdk，这条必须重新验。
- **明文 HTTP 现在是全局放行的**（M7 `03ee3f9` 改的，不是回环白名单）：内置浏览器要打开用户自己输入的
  任意站点，而不少视频站与媒体 CDN 仍是 HTTP，只放行回环会让「纯手机模式」第一步就失败。
  当前落地形态是 `<base-config cleartextTrafficPermitted="true">` + 系统信任锚，配置文件顶部那段注释
  写明了代价与边界（地址栏对 `http://` 显示「不安全」、电脑引擎仍由 `EngineSettings` 校验非回环默认 HTTPS、
  下载器只访问用户点选的那条 URL）。**发版前确认的是这三条边界仍然成立**，而不是确认白名单还在——
  旧版本这条写的「只放行 `127.0.0.1` 与 `localhost`」已经不成立，照它核对会得出假的通过结论。
- `AndroidManifest.xml` 是 `allowBackup="false"`，这条没变，仍是隐私承诺的落地点；
  任何"为了方便先放开一下"的改动都要先过这里。

## 5. 回归基线（发版前跑，数字对不上就是有问题）

```bash
GRADLE_USER_HOME=D:/Android/GradleUserHome ./gradlew.bat \
    :android:app:testDebugUnitTest :shared:jvmTest :engine-client:test :cli:test :mcp:test --rerun-tasks
cd engine && ./.venv/Scripts/python.exe -m pytest -q
```

JVM 侧 **513 tests**（app 451 + shared 11 + engine-client 12 + cli 26 + mcp 13），0 失败 0 错误 0 跳过。
注意 `:shared` 是 KMP 模块，任务名是 `jvmTest` 不是 `testDebugUnitTest`。引擎桩套件 235 passed
是 M9 的记录，本轮（只改 HLS 解密与单测）未复跑。引擎 `-m real` 套件不要与桩套件并发跑，
也不要在重启引擎时跑，否则会出现假失败。

上面这组数字是 **2026-11-07（M14 收官）现场复跑**的结果。上一版基线是 504 项（app 442，M13），
app 从 442 涨到 451 是 M14 的 `Aes128SegmentDecryptorTest`（6 项，IV 派生/显式 IV/PKCS7 裁尾/
188 对齐不误伤/坏输入）、`HlsPlaylistParserTest`（+2 项，密钥轮换与无 URI 的 KEY）与
`HlsConcurrentDownloadTest`（+1 项，显式 IV 三段并发下载端到端）。
再往前一版是 483 项（app 421，M12.5），app 从 421 涨到 442 是 M13 的 `KeepAliveSnapshotTest`（8 项，
字节池化/未知总大小/转封装态）、`KeepAliveWiringTest`（10 项，通知无按钮、文案表、开关默认值与权限
文案的接线钉子）与 `KeepAlivePolicyTest`（3 项，开关 × 队列的启停判据）。

再往前一版是 475 项（app 413，M12），app 从 413 涨到 421 是 M12.5 的 `AdBlockLedgerTest`（6 项，一页一域一行的边界）与 `PlayerWiringTest`
（2 项，手势落系统 + 离场强制销毁的接线钉子）。

再往前一版是 464 项（app 402，M11）：app 从 402 涨到 413 是 M12 的 `PlayerGestureMathTest`（6 项，
快进换算/亮度音量边界）、`AdBlockRulesTest`（4 项，后缀匹配与误杀反例）与 `BrowserWiringTest`
新增的拦截接线钉子（1 项）。
M9 还出现过一次 290 → 283：掉的是撤除临时诊断层时一起删掉的取证层形状测试，判定链一项未少——
数字对不上时先分清是「删了」还是「判定链弱了」。

## 6. 音频链路的发版判据（M8/M9 新增，Release 前必须复跑）

转封装坏掉的表现是**无声或半截报错的 MP4**，R8 之后单测覆盖不到（本文件 §1 第 6 步的理由），
所以设备腿必须专门带一条音频判据：

1. 同一条 HLS 链接跑到底，`adb logcat -d` 里应出现 `hls_remux_done`，且
   `outSamples == srcFrames`、`outAdtsStartSamples=0`、`outMaxGapMs` 在几十毫秒量级、源与产物 `ContentMs` 一致。
   出现 `hls_remux_skipped` 不算失败——**`.ts` 还在原地且文案点名了原因**就是正确行为。
2. 拉回电脑用 `ffprobe` / `ffmpeg` 复核。**下面四条命令都在本机 2026-11-05 实测跑通过**
   （`ffmpeg`/`ffprobe` 在 `D:/ffmpeg/bin`，用宿主机的即可——项目本身不依赖它），输出形状也一并记在这儿：

   ```bash
   # ① 最长的音频包：合规产物应该是 1024（= 一个 AU）。并帧的历史形态是 2048…16384
   ffprobe -v error -select_streams a -show_entries packet=duration -of csv=p=0 artifact.mp4 \
       | tr -d ',' | sort -n | tail -1

   # ② 音频包总数：应与审计行的 outSamples / srcFrames 相等
   ffprobe -v error -select_streams a -show_entries packet=duration -of csv=p=0 artifact.mp4 | wc -l

   # ③ 时长分布：看清「绝大多数 1024 + 末尾一个残包」，别只看有没有 1024
   ffprobe -v error -select_streams a -show_entries packet=duration -of csv=p=0 artifact.mp4 \
       | tr -d ',' | sort -n | uniq -c

   # ④ 实际能解出多少 PCM 点、有没有能量（无声文件会在这里现形）
   ffmpeg -hide_banner -i artifact.mp4 -map 0:a -af volumedetect -f null - 2>&1 \
       | grep -E "n_samples|mean_volume|max_volume"
   ```

   实测对照（本机现造的 3.01s 合规参考片）：① 出 `1024`；② 出 `131`；③ 出 `1 204 / 130 1024`；
   ④ 出 `n_samples: 133120`（=130×1024）、`mean_volume: -21.1 dB`、`max_volume: -17.7 dB`。

   三条读数细节，都是复跑时才发现的：

   - **①/③ 前面必须 `tr -d ','`**：`-of csv=p=0` 会把首包输出成 `1024,`，不抹掉逗号的话
     `sort -u` 会给出 `1024`、`1024,` 两个值——照着「只应该看到一个值 1024」判，好文件也会被读成坏的。
   - **③ 末尾那个残包是预期**（本例 204 tick），编码器 priming 也会算在这里；
     判据是「不存在 > 1024 的包」，不是「所有包等长」。
   - **④ 会先出一行 `n_samples: 0`**（输入侧那个 filter 实例），要看的是非零那一行。

3. 严格播放器侧再用 VLC / 系统相册各开一次——Android 自带解码器容忍不合规的 `mp4a`，
   只听本机声音会把「相册打不开」这类问题留在外面。

## 7. 当前台账缺口（登记，别在发版时假装没有）

- **mapping 归档**：M12.5 已按 §1 第 3 步重归档为 `release-artifacts/mapping-v1.1.0-code1.txt.gz`
  （2026-11-06 20:33 那次 `assembleRelease` 的 `mapping.txt`，源与档 sha256 往返一致，归档名与
  `versionName=1.1.0` 对齐）。旧的 `mapping-v1.0-code1.txt.gz` 保留但只对 M6-6 那版有效。
  **这条规矩不变**：下次改代码再出 Release，必须先重归档再看设备腿，否则还原崩栈会给出另一个函数名。
- **R8 设备腿只跑了「直链—下载—播放」这一半**（M12.5 补齐）：2026-11-06 用测试签名包在 MuMu 上过完
  冷启动、深嗅出候选、下载已完成、下载管理→打开文件、ExoPlayer 画面推进、续播 `resume=25221`、
  `adblock_hit` 每域每页一行，`JsonDecodingException`/`SerializationException` 0 命中，全程无闪退。
  **§6 的 HLS→TS 转封装音频判据本轮没在 R8 包上复跑**（只跑过 mp4 直链），下次发版前必须补一条
  HLS 链接的 R8 设备腿；M8/M9 的音频结论仍来自 debug 包。M14 已在 2026-11-07 收尾时用测试签名
  Release 包跑通加密 HLS（`docs/smoke-report.md` §33.7：`hls_download_done bytes=13570780`、
  产物 PCM md5 与 debug/本地明文一致），这条缺口关闭。
- **APK 体积基线（unsigned）**：M11-2（`a107dc0`）3,793,903 B → M11-3B（`924c350`）5,271,453 B →
  M12.5（2026-11-06）5,284,313 B → M13（2026-11-07）5,289,817 B → M14（2026-11-07）5,289,817 B。
  相对 M11-3B 只涨 18,364 B，M12/M12.5/M13/M14 未新增依赖，与当初的预判一致；预算 2 MB 一档内，
  下次发版继续对比这个数。
- **`adblock_hit` 现在按地址认页**（M12.5 裁决落地）：`AdBlockLedger` 用「地址变了才重开账」而不是
  「第几次导航」，同时挡住两类跨界——preload 抢在 `onPageStarted` 之前、reload 时子资源横跨
  `onPageStarted` 前后。R8 包实测：同域 3 张图 → 1 行；点刷新重新加载同一地址（主文档确实 304 重验）→
  **不再补行**；换一个新地址 → 每个域各补 1 行。代价登记清楚：**同一页重载不再留第二行**，
  读日志时「一行」代表「这页拦过这个域」，不代表只拦了一次。
- **画中画只在播放中申请**（设计如此，登记以免被当成 bug）：`onUserLeaveHint` 要求 `player.isPlaying`，
  播完或暂停后按 Home 只是普通退后台。MuMu 取证时第一次就是因为片子刚好放完没进小窗，倒回中段才复现成功。
  M12.5 裁决维持现状。
- **亮度手势的设备侧读数有限**（M12.5 裁决：接受现状，记 TODO）：代码已是
  `window.attributes.screenBrightness = value` 这种可直接复核的赋值形状，`PlayerWiringTest` 钉住了它；
  但 MuMu 的 `screencap` 不反映该字段、`dumpsys window` 也不打印，所以真机上仍只能靠界面上的
  「亮度 23%」提示条取证。音量可以外部读数（`cmd media_session volume --get`，实测 0→25 与手势位移比例吻合）。
  **TODO：实体机复核亮度一次。**
- **BACK 退出播放器后回到旧播放器的观察项**（M12.5 裁决：单独立项观察，防御已加）：
  `VideoPlayerScreen` 现在用 `key(recordId)` 包住 `PlayerRoute`，离场 `onDispose` 里强制
  `playerView?.player = null` → `player.release()` → `livePlayer = null`，PiP 回调只键 `pipHost`
  并通过 `rememberUpdatedState` 读把手。R8 包上连续 4 次「打开—BACK」无闪退、进程线程数 53→54 不涨，
  续播点照常回写；**但「BACK 回到上一个播放器画面」这个原始现象本轮没在真机上重现出来**，
  所以这条只能算防御已就位、观察未结案。
- **测试签名包不得分发**：本轮 R8 设备腿用的是 `~/.android/debug.keystore` 签的包（证书 `CN=Android Debug`）。
  它只证明 R8 后的代码能跑，不代表正式发布包；正式包仍需维护者本机填口令后按 §1 打。
- **测试台账上的 `READY` 幽灵行**（M12.5 复核）：本轮在「全部」列表直接看到两条待下载行——
  `127.0.0.1 · m12_long.mp4`（旧有）和 `127. · m12_long.mp4`（本轮取证时 `adb input text` 丢字，
  把地址打成残缺的 `http://127.:8000/m12_long.mp4`；`127.` 是合法单标签主机名，校验器按规则放行，
  下载连不上才停在待下载，**不是校验缺陷**）。上一版登记的 `127.0.0.1:8733/deep.html` 那行本轮没有逐行复看，
  列表里 `127.0.0.1 · deep.html` 仍在、状态未确认。**这些都属用户可见记录，删除需要点名授权**，
  不在自动清理范围内。
- **下载保活只在模拟器上验证过**（M13 登记）：MuMu 15 上锁屏 2 分钟、灭屏完成两次下载都过了，
  但模拟器宿主一直供电，**真实 Doze / 厂商省电策略没覆盖**；Android 14+ `dataSync` 的单次 6 小时、
  每 24 小时累计 24 小时额度也没跑到边界。下次在实体机上长时挂后台时补一次
  （建议一条 ≥20 分钟的限速下载，期间锁屏并抽查 `dumpsys activity services` 与服务器侧已发字节）。
- ~~**通知内容无法从 `dumpsys notification` 复读**~~（M13 登记，2026-11-07 撤销）：复测时
  `adb shell dumpsys notification --noredact | grep -E "com.hpu.browseguard|android.title|android.text"`
  能直接读到 `NotificationRecord(id=1301, channel=download_keep_alive)`、标题与百分比、以及
  `contentIntent=… startActivity`；上一轮「查不到」是误读，不是 App 缺陷。
- ~~**M13 的前台服务与通知没在 R8 包上跑过**~~（2026-11-07 已补）：测试签名 Release 包
  （unsigned 5,289,817 B，签名后 5,308,900 B，cert `73866dbc…`，`flags` 无 DEBUGGABLE）上完成了
  冷启动→深嗅→下载→服务 `isForeground=true foregroundId=1301`→通知进度 9%→点通知落下载页
  →10:34 `已完成`、产物 57,861,784 B→服务与通知自收摊。R8 对 Service / `PendingIntent` 的裁剪风险
  这一腿已经排除。**R8 包上的 HLS→TS 转封装音频判据已于 M14 收尾补齐**（2026-11-07，加密 HLS：
  `hls_download_done bytes=13570780`、`hls_remux_done audio=124 outFrames=862`、产物 PCM md5 与
  debug/本地明文一致，见 `docs/smoke-report.md` §33.7）。仍未覆盖：正式签名包（必须由维护者本机打）。
- **「不再询问」的通知权限路径没实测**：两次拒绝后系统不再弹窗，当前只靠设置页那句红字 +
  「去设置」出口；文案与引导在那种状态下是否够用，实体机遇到时再确认。

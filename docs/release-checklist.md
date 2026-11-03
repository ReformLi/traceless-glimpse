# BrowseGuard Vid 发版 checklist

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
| 6 | 装 Release 包跑设备腿 | 卸载 debug → `adb install -r` → 三 Tab + 引擎/本机两条主流程 + 取消 + 历史 + 设置 | `adb logcat -d` 里 `JsonDecodingException` / `SerializationException` **0 命中** |
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
dst = 'release-artifacts/mapping-v1.0-code1.txt.gz'
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

还原崩栈（以下整段在本机 Git Bash 实测跑通过，输出贴在后面）：

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
| 1.0 / 1 | `d486c26851138b865dededee6811fd5bb2d2e7bb550a68fa2b42975486f5ad68` | 3,206,651 | `release-artifacts/mapping-v1.0-code1.txt.gz`（原 36,442,304 B，sha256 `7922724dd10c07653978a72c30460860329cb98d3a39938af58cec8f14cdfa29`） | MuMu SM-S9080 / Android 15 十四腿全过，反序列化异常 0 命中 | [acceptance-m6-6.md](acceptance-m6-6.md) |

## 4. 发布前置（一次性，但每次都要确认没坏）

- `android/app/keystore.properties` 存在且四个字段非空，键文件在 `android/app/keystore/` 下。
  两者都在 `.gitignore` 里（`*.jks` 那条本来就挡住了键）。**读不到时构建不失败，只出一个未签名包**
  并打一行提示——所以判据不是"构建成功"，而是第 5 步的 `apksigner verify` 真能验过。
- 正式分发前必须换掉现在这把测试键：它是自签、`OU=Release Test`、口令只存在本机一个明文文件里。
  换键意味着**老用户无法覆盖安装**，这是不可逆决定，要提前想好。
- 签名方案实测：`enableV2Signing=true` 在 minSdk 29 下**不会**产出 v2 pair（apksigner CLI 重签同样不出），
  落地的是 v3。Android 10+ 装包与校验都不受影响；若将来为兼容 API 24–27 下调 minSdk，这条必须重新验。
- `network_security_config.xml` 只放行 `127.0.0.1` 与 `localhost`，`AndroidManifest.xml` 是
  `allowBackup="false"`。这两条是隐私承诺的落地点，任何"为了方便先放开一下"的改动都要先过这里。

## 5. 回归基线（发版前跑，数字对不上就是有问题）

```bash
GRADLE_USER_HOME=D:/Android/GradleUserHome ./gradlew.bat \
    :android:app:testDebugUnitTest :shared:jvmTest :engine-client:test :cli:test :mcp:test --rerun-tasks
cd engine && ./.venv/Scripts/python.exe -m pytest -q
```

JVM 侧 245 tests（app 183 + shared 11 + engine-client 12 + cli 26 + mcp 13），0 失败 0 错误；
引擎桩套件 235 passed。注意 `:shared` 是 KMP 模块，任务名是 `jvmTest` 不是 `testDebugUnitTest`。
引擎 `-m real` 套件不要与桩套件并发跑，也不要在重启引擎时跑，否则会出现假失败。

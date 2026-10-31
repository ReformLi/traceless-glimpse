# android/ — Android 客户端

包名 `com.hpu.browseguard`（应用显示名「览盾视频」）。定位：客户端与轻量入口，连接本机引擎（USB / 局域网 TLS）或使用内置轻量下载能力。

## 现状（M0）

- `app/` 为 Android Studio Empty Views 模板**原样迁移**（从仓库根 `app/` 移入，未改任何 Android 代码与 manifest）。
- **未接入 Gradle 构建**：根 `settings.gradle.kts` 只挂 `:shared`，本目录不参与 `gradlew projects`。
- 包结构按 PRD §5.2 建到位（空包 + `.gitkeep`），业务代码 M3 实现：

```
com.hpu.browseguard
├── app/            # 应用入口、导航、依赖注入
├── core/           # model / net / security / storage / common
├── feature/        # browse / download / agent / library / settings
├── engine/         # bridge（协议适配）/ embedded（内置轻量下载）
└── di/             # 模块装配
```

## M3 待办

1. **minSdk 24 → 29**：PRD NFR-05 要求 Android 10+（API 29）；模板默认 `minSdk 24`，M3 调整。
2. **Views → Compose**：模板为 Views 栈（appcompat / material）；M3 迁移 Jetpack Compose。
3. **接入 :shared**：`settings.gradle.kts` 挂入 app 模块，协议模型直接复用 KMP 模型，不重复定义。

## AGP 版本说明

`app/build.gradle.kts` 通过 `alias(libs.plugins.android.application)` 引用版本目录，与 catalog 的 AGP `9.1.0` 一致，**无差异**。AGP 9.1.0 作为 M3 基线记录在 `gradle/libs.versions.toml`，当前不参与解析。

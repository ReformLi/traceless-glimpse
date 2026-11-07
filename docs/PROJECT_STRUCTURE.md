# 无痕掠影 (Traceless Glimpse) - 项目目录结构

本项目采用 **多模块单体仓库 (Monorepo)** 架构，基于 Kotlin Multiplatform (KMP) 和 Python FastAPI 构建，实现了移动端、桌面端与底层引擎的彻底解耦与协议互通。

> 只想先跑起来？看根 [`README.md`](../README.md) 的「快速开始」。本文件讲的是「代码放在哪、为什么这么分」。

---

## 📁 核心目录结构树

```text
TracelessGlimpse/
├── android/             # 📱 Android 客户端 (Kotlin + Jetpack Compose)
├── engine/              # ⚙️ Python 本地核心引擎 (FastAPI + yt-dlp + Playwright)
├── desktop/             # 💻 桌面端控制面板 (Python CustomTkinter)
├── shared/              # 🔗 核心协议数据模型 (KMP，双端共享)
├── engine-client/       # 🔌 纯 JVM 的 HTTP 引擎客户端
├── cli/                 # ⌨️ 命令行工具 (bgv)
├── mcp/                 # 🤖 MCP Server (供 AI 客户端调用)
├── docs/                # 📚 项目文档 (需求、协议、用户指南；reports/ 存历史取证)
├── scripts/             # 🛠️ 自动化脚本 (Schema对账、冒烟测试等)
├── release-artifacts/   # 📦 发版归档 (R8 mapping 文件等)
├── gradle/              # 🐘 Gradle 版本与依赖统一管理
├── quarantine/          # 🚧 隔离区 (历史遗留代码或待重构废弃代码)
└── build/               # 🗑️ 编译产物 (自动生成，已在 .gitignore 忽略)
```

---

## 🧩 模块详细说明

### 🥇 1. 核心业务端 (Core Clients & Engine)

#### 📱 `android/` — 移动端 App

包含完整的 Android 工程，构建出可独立运行的抓取与下载工具。

* **`app/src/main/java/com/hpu/browseguard/`**：主代码目录。
  * `data/`：数据层。包含 Room 数据库（历史记录/进度）、网络请求（OkHttp）、本地下载引擎（HLS 并发、AU 重封）、以及对账逻辑。
  * `ui/`：界面层（Jetpack Compose）。包含首页、浏览器（隐身/显示）、下载管理、设置页，以及各页面的 ViewModel。
  * `di/`：依赖注入容器（`AppContainer`），负责组装各种后端引擎。
  * `protocol/`：与 `shared` 模块对接的协议层。
* **`app/src/test/`**：JVM 单元测试（包含几百个针对解析器、重封器、安全守卫的测试用例）。

#### ⚙️ `engine/` — Python 本地核心引擎

整个系统的算力与网络核心，监听本地端口，提供 REST API。

* **`main.py`**：FastAPI 应用入口。包含所有 API 路由、状态机管理、任务队列。
* **`models.py`**：Pydantic 数据模型（与 KMP `shared` 模块的字段严格一致）。
* **`services/`**：核心业务逻辑。
  * `sniffer.py`：Playwright 隔离浏览器嗅探。
  * `formats.py` / `downloader.py`：yt-dlp 封装、并发下载、重定向处理。
  * `merger.py`：FFmpeg 封装、转码与合并。
  * `ffmpeg_path.py`：FFmpeg 路径解析器（支持自定义路径）。
* **`security.py`**：安全边界。包含 SSRF 防御、输出目录沙箱、日志脱敏。
* **测试布局**：pytest 不建 `tests/` 目录，实际分布在三处——根目录 `test_*.py`（默认套件）、
  `tests_real/`（`-m real` 才跑的真实链路用例）、`testsupport/`（本地媒体服务器与 FFmpeg 生成的素材）。

#### 💻 `desktop/` — 桌面端 GUI 与控制台

将 Python 引擎封装为独立桌面软件，提供可视化管理与纯 PC 端下载能力。

* **`gui_app.py`**：CustomTkinter 主程序，包含系统托盘、引擎启停、依赖自检、FFmpeg 路径配置。
* **`download_console.py`**：内置的「下载控制台」Tab 页，提供 PC 端独立的嗅探、画质选择、进度条展示。
* **`engine_client.py`**：纯 httpx 编写的本地引擎通信客户端。
* **`launcher.py`**：引擎子进程管理（负责跨进程清理、日志重定向）。
* **`entry.py`** / **`build_exe.py`**：PyInstaller 打包入口与脚本，将项目打包为单文件 `BrowseGuardEngine.exe`。

---

### 🥈 2. 跨端共享与通信 (Shared & Connectivity)

* **`shared/`** — 契约根基。包含 Kotlin Multiplatform 数据模型（如 `Task`、`ErrorPayload`、`SniffResult`），保证 Android、CLI、MCP 三端对协议的理解绝对一致。
* **`engine-client/`** — 纯 JVM 的 HTTP 客户端。封装了与 Python 引擎的 REST API 交互，供 `cli` 和 `mcp` 使用，不依赖 Android 环境。

---

### 🥉 3. 极客生态与扩展工具 (Ecosystem & AI)

* **`cli/`** — 命令行工具。提供 `bgv sniff <url>`、`bgv download` 等命令，供终端极客使用。
* **`mcp/`** — MCP Server。实现 Model Context Protocol，允许 Claude Desktop / Cursor 等 AI 直接调用引擎下载视频。

---

### 🏗️ 4. 工程化与辅助 (Engineering & Docs)

* **`docs/`** — 文档库。根目录放**现行有效**的三类：合同（`PROTOCOL.md` / `PROTOCOL-CHANGELOG.md`）、
  需求（`PRD.md`）、用户指南（`USER_GUIDE_ANDROID.md` / `USER_GUIDE_DESKTOP.md` / `USER_GUIDE_MCP.md`）、
  结构说明（`PROJECT_STRUCTURE.md`）；`reports/` 存历史取证（`smoke-report.md` 总账与
  `acceptance-m*.md` 专项），只追加不回改。
* **`scripts/`** — 自动化脚本。包含 `check_schema.py`（Kotlin 与 Python 的双端模型一致性比对）与 `smoke.sh`（端到端冒烟测试）。
* **`release-artifacts/`** — 发版归档。存放 Release 包的 `mapping.txt`（R8 混淆映射表），用于追踪线上崩溃。
* **`gradle/`** — Gradle 统一配置。包含版本目录 `libs.versions.toml` 与 wrapper。

---

### ⚠️ 5. 特殊目录说明

* **`quarantine/`** — 隔离区。存放暂未通过审计的代码或待重构的废弃逻辑。
* **`build/`** — 编译产物。Gradle 和 Python 的编译缓存及打包生成的临时文件，**切勿提交至 Git**。

***



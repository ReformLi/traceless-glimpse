# 无痕掠影 (Traceless Glimpse)

> 在隔离的浏览器里嗅探，在本地完成下载与处理，数据没有可去的地方。

以 AI Agent 为编排核心、以本地执行为默认、以浏览器隔离嗅探为入口的视频获取工具。
数据路径上不设第三方中间节点；浏览器身份与用户日常环境物理隔离；不内置遥测、分析 SDK 与账号体系。

## 三端架构

1. **桌面本地引擎**（主力）：本地 HTTP 服务，承载 Playwright 嗅探、yt-dlp 下载、FFmpeg 处理。
   `desktop/` 是它的控制面板——一键启停、显示局域网地址、环境依赖自检、内置「下载控制台」，
   不连手机也能在电脑上完成嗅探 → 选画质 → 下载；打包形态是单文件 `BrowseGuardEngine.exe`。
2. **Android App**：`com.hpu.browseguard`，既是引擎客户端，也能完全离线自足——
   内置浏览器嗅探 + 本机直连下载 + TS→MP4 转封装 + 内置播放器（断点续播）。
3. **CLI / MCP Server**：`bgv` 命令与 MCP 工具，供技术用户与 AI 客户端调用。

三端通过 `docs/PROTOCOL.md` 定义的 HTTP/JSON 协议通信；协议模型在 `shared/`（Kotlin Multiplatform）单一来源实现。

> **信任边界**：引擎无入站鉴权（M15.3 裁决不补 Token）。桌面端默认监听 `0.0.0.0:8730`
> 是为了让手机连得上，代价是只能在可信的家庭局域网使用，切勿暴露公网。

## 快速开始

依赖：JDK 17+、Gradle wrapper（自带）、Python 3.11+。

### 电脑端（普通用户推荐）

```powershell
# 已有打包产物：双击 desktop\dist\BrowseGuardEngine.exe →「引擎设置」点「启动」
# 源码态：
engine\.venv\Scripts\python.exe -m pip install -r desktop\requirements.txt
engine\.venv\Scripts\python.exe desktop\gui_app.py

# 打包单文件 exe（产物不入库）
engine\.venv\Scripts\python.exe desktop\build_exe.py
```

面板能设监听地址 / 端口 / 输出目录、显示手机要填的局域网 IP、隐藏到托盘、
关闭行为可选（最小化到托盘 / 询问 / 直接退出），
并自检环境依赖（自定义 `ffmpeg.exe` 路径、一键安装 Chromium 内核）。
**完整操作步骤见 [`docs/USER_GUIDE_DESKTOP.md`](docs/USER_GUIDE_DESKTOP.md)**。

### 手机端

```powershell
.\gradlew.bat :android:app:assembleDebug
# 产物：android/app/build/outputs/apk/debug/app-debug.apk
```

装好后有两种活法：连电脑引擎（解析更强），或完全本机离线（开箱即用）。
**安装、配对与排障见 [`docs/USER_GUIDE_ANDROID.md`](docs/USER_GUIDE_ANDROID.md)**。

### 命令行（bgv）

```powershell
.\gradlew.bat :cli:installDist
.\cli\build\install\bgv\bin\bgv.bat sniff https://example.com/watch
.\cli\build\install\bgv\bin\bgv.bat download-url https://example.com/watch --format=max_height=720
```

`--json` 下 stdout 只有协议 JSON，提示与进度走 stderr；退出码表见 [`cli/README.md`](cli/README.md)。

### MCP Server（Claude Desktop / Cursor）

```powershell
.\gradlew.bat :mcp:installDist
.\mcp\build\install\bgv-mcp\bin\bgv-mcp.bat
```

只支持 stdio：stdout 仅承载 JSON-RPC，日志走 stderr。客户端配置示例与工具清单见
[`mcp/README.md`](mcp/README.md)，从零验收步骤见 [`docs/USER_GUIDE_MCP.md`](docs/USER_GUIDE_MCP.md)。

### 引擎单独跑（无 GUI / 排障）

```powershell
cd engine
.\.venv\Scripts\python -m uvicorn main:app --host 127.0.0.1 --port 8730
```

## 文档地图

| 我想… | 读这份 |
|---|---|
| 在电脑上下载视频 | [`docs/USER_GUIDE_DESKTOP.md`](docs/USER_GUIDE_DESKTOP.md) |
| 在手机上下载视频 | [`docs/USER_GUIDE_ANDROID.md`](docs/USER_GUIDE_ANDROID.md) |
| 让 AI 客户端调引擎 | [`docs/USER_GUIDE_MCP.md`](docs/USER_GUIDE_MCP.md) |
| 先看懂目录与模块职责 | [`docs/PROJECT_STRUCTURE.md`](docs/PROJECT_STRUCTURE.md) |
| 查需求与验收标准 | [`docs/PRD.md`](docs/PRD.md) |
| 查接口合同 | [`docs/PROTOCOL.md`](docs/PROTOCOL.md) + [`docs/PROTOCOL-CHANGELOG.md`](docs/PROTOCOL-CHANGELOG.md) |
| 改某个模块的代码 | `android/` · `desktop/` · `engine/` · `cli/` · `mcp/` 各自的 `README.md`（含出包与自检命令） |
| 看某轮改动到底验了什么 | [`docs/reports/smoke-report.md`](docs/reports/smoke-report.md)（逐阶段取证总账） |
| 看专项议题的深度验收 | `docs/reports/acceptance-m*.md` |

`docs/reports/` 存放历史取证，只追加不回改；合同与用户指南在 `docs/` 根。

## 仓库结构

| 目录 | 职责 |
|---|---|
| `android/` | Android 客户端（Compose + 内置浏览器 + 本机下载/转封装，见 `android/README.md`） |
| `engine/` | 本地引擎（Python 3.11+ / FastAPI / Pydantic v2） |
| `desktop/` | PC 控制面板与单文件封装（customtkinter + pystray + PyInstaller，见 `desktop/README.md`） |
| `shared/` | KMP 协议模型（kotlinx.serialization），Android / CLI / MCP 共用 |
| `engine-client/` | 纯 JVM 的 HTTP 引擎客户端，供 `cli` 与 `mcp` 复用 |
| `cli/` | `bgv` 命令行工具（Kotlin/JVM） |
| `mcp/` | MCP Server（Kotlin/JVM，仅 stdio transport） |
| `docs/` | 需求、协议、用户指南与历史取证（索引见上方「文档地图」） |
| `scripts/` | Schema 一致性校验、冒烟测试脚本 |

## 里程碑

> 当前版本 **V1.1.0**（Android `versionName = 1.1.0`，versionCode 自增）。

| 阶段 | 交付 | 提交 |
|---|---|---|
| M13 | 前台服务保活与通知栏进度 | `6060f9b` |
| M14 | HLS AES-128 分片解密与下载 | `d7bd3b4` |
| M15 | 桌面端托盘启动器 → GUI 控制面板 → 单文件 `BrowseGuardEngine.exe` | `76ec785` |
| M16 | 下载控制台接通引擎网络层，纯电脑端「嗅探 → 选画质 → 下载」闭环 | `33ebc21` |
| M16.5 | 环境依赖自检面板与 `BGV_FFMPEG_PATH` 自定义路径 | `a92db88` |
| M17 | 解析器模糊测试与极值防御（清单 / TS+ADTS / URL 三组极值输入） | `d1b3a0a` |
| M18 | PC 引擎 Playwright 反反爬伪装 + 嗅探雷达三层识别（后缀 / Content-Type / 关键字） | `8770bac` |
| M18.5 | 下载进度在总大小未知时可见（HLS 分片估算 + 桌面不确定进度） | `9e9c52f` |
| M19 | 自定义引擎 IP 的 HTTP 白名单封装与网络安检门（零信任 OkHttp 拦截器） | `eb9e966` |
| M20 | 电脑引擎心跳检测：5s/拍 3s 超时，连续两拍失败判离线，离线点嗅探前端拦截 | （见 smoke-report §41） |

每阶段的现场证据、协议坑与自报缺口记在 [`docs/reports/smoke-report.md`](docs/reports/smoke-report.md)（§33 起按阶段编号）。

## 设计底线

- **本机直连不上线**：切到「本机」执行者后，发现、下载、转封装全在设备内完成，代码路径上不出现引擎地址
  （由 `TaskNeverGoesOnTheWireTest` 扫源码钉住）。代价与收益见 [`android/README.md`](android/README.md)。
- **Cookie 只留在手机**：默认关闭，按域名单次授权；不进任何发给引擎的请求，协议层就没有承载它的字段。
- **零第三方 native**：APK 不含 FFmpeg / ffmpeg-kit / yt-dlp，体积变化只来自本项目代码。
- **安全约束是结构性的**：Task 无 `cookieFile` / `browser` / `userAgent` / `headers` 字段；
  `formatSelector` 只接受结构化对象；`headersHint` 与错误 `detail` 输出前必须脱敏。

## 如何贡献

- 先读 [`docs/PRD.md`](docs/PRD.md) 与 [`docs/PROTOCOL.md`](docs/PROTOCOL.md)；协议字段增删需三端确认，
  删除或改语义必须升版本，并在 `PROTOCOL-CHANGELOG.md` 留裁决记录。
- 依赖版本全部固定（Gradle 版本目录 / `engine/requirements-lock.txt`），不引入文档外依赖；新增先提出讨论。
- 提交前跑 `python scripts/check_schema.py` 与各端测试（仓库未接托管 CI，这道对账靠本地跑）：

```powershell
.\gradlew.bat test --rerun                     # 全项目 JVM 单测（当前 548 项）
cd engine; .\.venv\Scripts\python -m pytest -q  # 引擎单测
```

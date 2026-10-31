# BrowseGuard Vid / 览盾视频

> 在隔离的浏览器里嗅探，在本地完成下载与处理，数据没有可去的地方。

以 AI Agent 为编排核心、以本地执行为默认、以浏览器隔离嗅探为入口的视频获取工具。
数据路径上不设第三方中间节点；浏览器身份与用户日常环境物理隔离；不内置遥测、分析 SDK 与账号体系。

## 三端架构

1. **桌面本地引擎**（主力）：本地 HTTP 服务，监听 `127.0.0.1:8730`，承载 Playwright 嗅探、yt-dlp 下载、FFmpeg 处理。
2. **Android App**：`com.hpu.browseguard`，客户端与轻量入口，连接本机引擎（USB / 局域网 TLS）或使用内置轻量下载能力。
3. **CLI / MCP Server**：`bgv` 命令与 MCP 工具，供技术用户与 AI 客户端调用。

三端通过 `docs/PROTOCOL.md` 定义的 HTTP/JSON 协议通信；协议模型在 `shared/`（Kotlin Multiplatform）单一来源实现。

## 仓库结构

| 目录 | 职责 |
|---|---|
| `android/` | Android 客户端骨架（M3 接入构建，见 `android/README.md`） |
| `engine/` | 本地引擎（Python 3.11+ / FastAPI / Pydantic v2） |
| `shared/` | KMP 协议模型（kotlinx.serialization），Android / CLI / MCP 共用 |
| `cli/` | `bgv` 命令行工具（Kotlin/JVM，复用 `:shared`，M2+ 实现） |
| `mcp/` | MCP Server（Kotlin/JVM，复用 `:shared`，M2+ 实现） |
| `docs/` | PRD 与接口协议，协议是三端的合同 |
| `scripts/` | Schema 一致性校验、冒烟测试脚本 |
| `.github/workflows/` | CI：Kotlin 编译 + Python 测试 + Schema 校验 |

## 如何运行

依赖：JDK 17+、Gradle wrapper 9.3.1（自带）、Python 3.11+。

```powershell
# Gradle 项目结构（应列出 :shared）
.\gradlew.bat projects

# Python 引擎（阶段 3 起可用；venv 固定 engine/.venv，已 gitignore）
cd engine
python -m venv .venv
.\.venv\Scripts\pip install -r requirements-lock.txt
.\.venv\Scripts\python -m uvicorn main:app --host 127.0.0.1 --port 8730

# Schema 一致性校验（阶段 4 起可用）
python scripts/check_schema.py
```

## 如何贡献

- 先读 `docs/PRD.md` 与 `docs/PROTOCOL.md`；协议字段增删需三端确认，删除或改语义必须升版本。
- 安全约束是结构性的：Task 无 `cookieFile` / `browser` / `userAgent` / `headers` 字段；`formatSelector` 只接受结构化对象；`headersHint` 与错误 `detail` 输出前必须脱敏。
- 依赖版本全部固定（Gradle 版本目录 / `engine/requirements-lock.txt`），不引入文档外依赖；新增先提出讨论。
- 提交前跑 `python scripts/check_schema.py` 与各端测试（CI 同口径）。

# 无痕掠影 (Traceless Glimpse)

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
| `cli/` | `bgv` 命令行工具（Kotlin/JVM，复用 `:shared` 与 `:engine-client`） |
| `mcp/` | MCP Server（Kotlin/JVM，仅 stdio transport，复用 `:shared` 与 `:engine-client`） |
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

### 命令行（bgv）

```powershell
.\gradlew.bat :cli:installDist
.\cli\build\install\bgv\bin\bgv.bat sniff https://example.com/watch
.\cli\build\install\bgv\bin\bgv.bat formats <taskId>
.\cli\build\install\bgv\bin\bgv.bat download <taskId> --format=best --watch
.\cli\build\install\bgv\bin\bgv.bat download-url https://example.com/watch --format=max_height=720
```

`--json` 模式下 stdout 只有协议 JSON，提示与进度全部走 stderr，可直接接入管道（如 `bgv sniff ... --json | jq '.formats'`）。退出码：`0` 成功、`2` 输入错误、`3` 安全拒绝、`4` 内容不可用、`5` 引擎不可达、`6` 存储错误、`7` 内部错误、`8` 用户主动取消。

Windows 用户建议在 Windows Terminal 或 PowerShell 7 下使用；若中文仍显示为乱码，先执行 `chcp 65001` 切到 UTF-8 代码页。

### MCP Server（Claude Desktop / Cursor）

```powershell
.\gradlew.bat :mcp:installDist
.\mcp\build\install\bgv-mcp\bin\bgv-mcp.bat
```

只支持 stdio：stdout 仅承载 JSON-RPC，所有日志走 stderr。配置示例（`BGV_ENGINE_URL` 可按需覆盖）：

```json
{
  "mcpServers": {
    "traceless-glimpse": {
      "command": "cmd",
      "args": [
        "/c",
        "E:\\code\\android\\BrowseGuardVid\\mcp\\build\\install\\bgv-mcp\\bin\\bgv-mcp.bat"
      ],
      "env": { "BGV_ENGINE_URL": "http://127.0.0.1:8730" }
    }
  }
}
```

工具：`sniff`（嗅探并返回格式）、`probe`（查格式表）、`download`（下发并轮询到终态，支持 `timeoutSeconds`）、`status`、`cancel`。从零验收步骤见 `docs/MCP_USER_GUIDE.md`，工具细节见 `mcp/README.md`。


## 隐私：Cookie 只留在手机

手机端的「本机直连下载」可能需要站点认出你是登录态。这件事默认**关闭**，且只以域名为单位授权：

- 第一次遇到需要登录状态的直链（通常是 HTTP 403），App 弹阻断式确认框，问「允许读取 `<域名>` 的登录状态吗」。
  同意之前，代码连 WebView 的 CookieStore 都不碰（审计行 `cookie_blocked`）。
- 授权后，Cookie 值只从 WebView 自己的 CookieStore 读一次，只注入**同一站点**的本机下载请求。
  它不进任何发给电脑引擎的请求体或请求头——协议层就没有承载它的字段（§「如何贡献」的结构性约束）。
- 设置页「Cookie 授权」按域列出已授权站点与最近使用时间。「撤销」只收回读取许可，下次会重新询问；
  要抹掉值用「清除所有 Cookie」（二次确认，执行系统的 `removeAllCookies`）。
  系统没有提供按站点删值的 API，界面不假装能做到。
- 引擎侧每个请求都有一行 `http_request … cookie=present|absent` 的可 grep 证据，只记存在性；
  日志脱敏兜底会把 `Cookie:` / `Set-Cookie:` 的整值抹掉，键值对形态的参数化日志也不例外。
  端到端取证与已知缺口见 `docs/acceptance-m6-5.md`。

## 如何贡献

- 先读 `docs/PRD.md` 与 `docs/PROTOCOL.md`；协议字段增删需三端确认，删除或改语义必须升版本。
- 安全约束是结构性的：Task 无 `cookieFile` / `browser` / `userAgent` / `headers` 字段；`formatSelector` 只接受结构化对象；`headersHint` 与错误 `detail` 输出前必须脱敏。
- 依赖版本全部固定（Gradle 版本目录 / `engine/requirements-lock.txt`），不引入文档外依赖；新增先提出讨论。
- 提交前跑 `python scripts/check_schema.py` 与各端测试（CI 同口径）。

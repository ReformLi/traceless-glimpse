# mcp/ — MCP Server

Kotlin/JVM，复用 `:shared` 协议模型与 `:engine-client`，向 AI 客户端暴露 `sniff` / `probe` / `download` / `status` / `cancel` 五个工具（FR-AG-06 + M5 裁决新增 `cancel`）。

## 传输与纯净性

只支持 `StdioServerTransport`：stdout 是 JSON-RPC 专用通道，任何日志、提示都必须走 stderr。主类 `main` 内不调用 `println()`；kotlin-logging 启动横幅已关闭，SLF4J 强制输出到 `System.err`。集成测试会启动真实进程，断言首行是合法 JSON-RPC、后续无空行与提示词。

## 工具语义

| 工具 | 行为 | 成功返回 |
|---|---|---|
| `sniff` | 创建 `mode=download` 且不带 `formatSelector` 的任务，停在 `awaiting_selection` | `{taskId, task, sniffResults, formats}` 组合信封 |
| `probe` | 查询 `awaiting_selection` 任务的格式表 | `FormatsResponse` |
| `download` | 下发格式选择并轮询到终态，支持 `timeoutSeconds`（1..86400，默认 21600） | 终态 `Task` |
| `status` | 查询单任务 | `Task` |
| `cancel` | 取消非终态任务 | `Task` |

格式选择器与 CLI 同文法：`best` / `audio_only`（或 `audio`）/ `max_height=N` / `exact=<formatId>`。

## 错误隔离

- 工具成功：`isError=false`，`content[0].text` 为标准协议 JSON，同时填入 `structuredContent`。
- 引擎协议错误（如 403 `DRM_PROTECTED`）：`isError=true`，`content[0].text` 原样填入 `ErrorPayload`。
- 引擎不可达：`isError=true`，`content[0].text` 恰为 `{"localError":"engine_unreachable"}`，不借用任何 §5 协议码。
- 其余客户端本地事件使用 `localError` 命名空间：`engine_protocol_mismatch` / `engine_error` / `download_timeout` / `sniff_timeout` / `task_cancelled` / `invalid_argument` / `internal_error` / `unknown_tool`。

## 环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `BGV_ENGINE_URL` | `http://127.0.0.1:8730` | 引擎地址；单个工具也可用 `engineUrl` 参数覆盖 |
| `BGV_MCP_POLL_MS` | `250` | 轮询间隔 |
| `BGV_MCP_SNIFF_TIMEOUT_MS` | `60000` | `sniff` 等待进入 `awaiting_selection` 的上限 |
| `BGV_MCP_DOWNLOAD_TIMEOUT_MS` | `21600000` | `download` 轮询上限（6 小时） |

## 构建与客户端配置

```powershell
.\gradlew.bat :mcp:installDist
.\mcp\build\install\bgv-mcp\bin\bgv-mcp.bat
```

Claude Desktop / Cursor 的 MCP 配置示例（`command` 指向安装目录下的启动脚本；完整验收步骤见 `docs/MCP_USER_GUIDE.md`）：

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

Agent 只能调用本 Server 暴露的五个白名单工具，不可执行任意 Shell；无法指定 Cookie 文件路径或浏览器类型（PRD FR-AG-04/05）。

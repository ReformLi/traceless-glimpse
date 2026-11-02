# cli/ — bgv 命令行工具

Kotlin/JVM，复用 `:shared` 协议模型与 `:engine-client`，通过 HTTP/JSON 调用本地引擎（默认 `127.0.0.1:8730`）。

## 命令

```text
bgv sniff <url> [--output <dir>]              # 嗅探并列出格式，任务停在 awaiting_selection
bgv formats <taskId>                          # 查看格式表
bgv download <taskId> --format <selector>     # 选择格式并下载，轮询到终态
bgv download-url <url> [--format <selector>]  # 嗅探 + 下载一步到位
bgv status <taskId>                           # 查询单任务
bgv tasks [--limit 1..200]                    # 列出任务
bgv cancel <taskId>                           # 取消非终态任务
```

格式选择器只接受结构化语法：`best` / `audio_only`（或 `audio`）/ `max_height=720` / `exact=<formatId>`；不接受 yt-dlp 原始 `-f` 字符串。

## 输出约定

- `--json`：stdout 只有协议 JSON，提示与进度全部走 stderr；`sniff --json` 返回 `{taskId, task, sniffResults, formats}` 组合信封，可直接 `| jq '.formats'`。
- 退出码：`0` 成功、`2` 输入错误、`3` 安全拒绝、`4` 内容不可用、`5` 引擎不可达、`6` 存储错误、`7` 内部错误、`8` 用户主动取消（本地语义，不占用协议码）。

## 构建

```powershell
.\gradlew.bat :cli:installDist
.\cli\build\install\bgv\bin\bgv.bat --help
```

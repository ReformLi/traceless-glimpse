# MCP 真客户端验收指南（Claude Desktop / Cursor）

这份指南按「从零到第一次真实下载」的顺序写，命令均可直接复制。路径以本机仓库 `E:\code\android\TracelessGlimpse` 为准；换机器时只替换仓库根目录。

验收需要两个进程同时存活：

```
[终端 A] 真实引擎 127.0.0.1:8730  <── HTTP ──  [Claude Desktop / Cursor]
                                                    └─ stdio ── bgv-mcp.bat
```

---

## 0. 一次性前置检查

打开 **PowerShell**，先跑一遍：

```powershell
cd E:\code\android\TracelessGlimpse

# 1) Java 17+（MCP 进程依赖）
java -version
where.exe java

# 2) 引擎依赖（ffmpeg / ffprobe 必须在 PATH 里）
where.exe ffmpeg
where.exe ffprobe

# 3) Playwright Chromium 是否已安装
Get-ChildItem "$env:LOCALAPPDATA\ms-playwright" -Filter "chromium-*" -ErrorAction SilentlyContinue

# 4) Python 引擎依赖
cd engine
.\.venv\Scripts\python.exe -c "import playwright, yt_dlp; print('engine deps OK')"
```

本机已核实：

- Java：`C:\Program Files\Java\jdk-17.0.1`，`JAVA_HOME` 已指向它。
- ffmpeg / ffprobe：`D:\ffmpeg\bin\ffmpeg.exe`、`D:\ffmpeg\bin\ffprobe.exe`，已在 PATH。
- Playwright Chromium：`%LOCALAPPDATA%\ms-playwright\chromium-1228`。
- yt-dlp：`2026.10.26`。

如果这是第一次在本机运行，补两步：

```powershell
cd E:\code\android\TracelessGlimpse\engine
python -m venv .venv
.\.venv\Scripts\pip install -r requirements-lock.txt
.\.venv\Scripts\python.exe -m playwright install chromium
```

`ffmpeg` 不是 pip 依赖：装好后把 `ffmpeg.exe` 所在目录加入 PATH，**重开终端**再验。

---

## 1. 启动真实后端引擎（real 模式）

### 1.1 构建 MCP（只需一次，代码更新后重跑）

```powershell
cd E:\code\android\TracelessGlimpse
.\gradlew.bat :mcp:installDist
```

### 1.2 启动引擎

**新开一个 PowerShell 窗口**，保持它一直开着：

```powershell
cd E:\code\android\TracelessGlimpse\engine

# 关键：必须在启动进程之前设置，改在别的窗口无效
$env:BGV_ENGINE_MODE = "real"
$env:BGV_OUTPUT_DIR_ALLOWLIST = "E:\Videos\BGV"
New-Item -ItemType Directory -Force "E:\Videos\BGV" | Out-Null

.\.venv\Scripts\python.exe main.py
```

看到下面这行即启动成功：

```
Uvicorn running on http://127.0.0.1:8730
```

引擎只监听回环地址，不对外网开放。

### 1.3 确认 real 模式与依赖

**再开第二个 PowerShell 窗口**：

```powershell
Invoke-RestMethod http://127.0.0.1:8730/v1/health | ConvertTo-Json

Invoke-RestMethod http://127.0.0.1:8730/v1/privacy/selfcheck | ConvertTo-Json -Depth 5
```

期望：

- `health` 返回 `"status":"ok"`、`"protocolVersion":"0.2"`。
- `selfcheck.dependencyVersions` 能列出 `yt-dlp` / `ffmpeg` / `playwright` 的真实版本。
- `outputDirAllowlist` 第一项是 `E:\Videos\BGV`。

> 如果 8730 已被旧进程占用，先关掉旧窗口；或确认旧进程不是别的服务。不要同时开两个引擎。

---

## 2. MCP 程序在哪里

构建产物固定在这个绝对路径：

```
E:\code\android\TracelessGlimpse\mcp\build\install\bgv-mcp\bin\bgv-mcp.bat
```

确认文件存在：

```powershell
Test-Path "E:\code\android\TracelessGlimpse\mcp\build\install\bgv-mcp\bin\bgv-mcp.bat"
```

手动握手自检（不经过 AI 客户端，验证 MCP 进程本身能启动、stdout 只有 JSON-RPC）：

```powershell
$init = '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"manual","version":"1.0"}}}'
$init | cmd /c "E:\code\android\TracelessGlimpse\mcp\build\install\bgv-mcp\bin\bgv-mcp.bat"
```

期望只输出一行合法 JSON，包含 `"serverInfo":{"name":"traceless-glimpse"...}`，进程退出码 0。出现任何提示词、空行、日志横幅都视为异常。

---

## 3. 客户端配置

### 3.1 Claude Desktop（Windows）

配置文件：

```
C:\Users\19564\AppData\Roaming\Claude\claude_desktop_config.json
```

打开编辑：

```powershell
New-Item -ItemType Directory -Force "$env:APPDATA\Claude" | Out-Null
notepad "$env:APPDATA\Claude\claude_desktop_config.json"
```

完整内容：

```json
{
  "mcpServers": {
    "traceless-glimpse": {
      "command": "cmd",
      "args": [
        "/c",
        "E:\\code\\android\\TracelessGlimpse\\mcp\\build\\install\\bgv-mcp\\bin\\bgv-mcp.bat"
      ],
      "env": {
        "BGV_ENGINE_URL": "http://127.0.0.1:8730",
        "JAVA_HOME": "C:\\Program Files\\Java\\jdk-17.0.1"
      }
    }
  }
}
```

说明：

- Windows 上通过 `cmd /c` 启动 `.bat` 最稳，避免客户端直接 spawn `.bat` 失败。
- `JAVA_HOME` 是本机已核实的值；升级 JDK 后同步改这里，或删掉并确保 `java` 在系统 PATH。
- 如果文件里已经有其他 `mcpServers`，**只把 `traceless-glimpse` 这一项加进去**，不要覆盖整个文件。

### 3.2 Cursor

全局配置文件：

```
C:\Users\19564\.cursor\mcp.json
```

```powershell
New-Item -ItemType Directory -Force "$env:USERPROFILE\.cursor" | Out-Null
notepad "$env:USERPROFILE\.cursor\mcp.json"
```

内容与 Claude Desktop 完全一致（同样使用上面那段 `mcpServers` JSON）。

### 3.3 重启并确认

- Claude Desktop：托盘图标右键 → **Quit**，再重新打开；新建对话，在工具/连接器里确认 `traceless-glimpse` 存在且列出 5 个工具：`sniff` / `probe` / `download` / `status` / `cancel`。
- Cursor：`Ctrl+Shift+P` → **Developer: Reload Window**；Settings → MCP 里确认 `traceless-glimpse` 为已连接，工具数为 5。

> 引擎没启动时，MCP 仍然会出现，但调用工具会返回 `{"localError":"engine_unreachable"}`。先开引擎，再验收。

---

## 4. AI 交互话术（直接复制）

### Prompt 1：只嗅探，列格式

```text
请使用 traceless-glimpse 的 sniff 工具嗅探这个链接：<把视频页面链接粘在这里>。
列出所有候选媒体流和格式表（分辨率、码率、容器、是否含音频），先不要下载。
最后把 taskId 告诉我。
```

### Prompt 2：下载 720p 并等待完成

```text
请用刚才 sniff 得到的 taskId，先调用 probe 确认格式，再调用 download：
format 用 max_height=720，timeoutSeconds 设为 600，轮询到 done / failed / cancelled 再回复我。
完成后告诉我 outputPath；如果超时或报错，不要新建任务，直接用 status 查同一个 taskId，并把原始 ErrorPayload 贴出来。
```

可选格式选择器：

| format 参数 | 含义 |
|---|---|
| `best` | 最佳画质（默认） |
| `max_height=720` | 不高于 720p |
| `audio_only` | 只要音频 |
| `exact=<formatId>` | 下载格式表中的指定项 |

成功时 AI 会返回终态 `Task`，其中 `"status":"done"`，`outputPath` 指向 `E:\Videos\BGV` 下的文件。落地核对：

```powershell
Get-ChildItem "E:\Videos\BGV" |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 5 Name, Length, LastWriteTime
```

---

## 5. 失败时看哪里

### 5.1 第一现场：AI 回复里的工具结果

工具失败时返回 `isError=true`，`content[0].text` 就是原始 JSON：

- 协议错误：`{"code":"DRM_PROTECTED","category":"content",...}`，原样来自引擎。
- 本地错误：`{"localError":"engine_unreachable"}`、`{"localError":"sniff_timeout"}` 等，带 `localError` 前缀的不是协议码。

### 5.2 引擎日志

引擎窗口本身会打印 INFO 审计行，例如 `sniff_context_open`、`download_attempt`、`download_done`。要留文件，启动时改成：

```powershell
cd E:\code\android\TracelessGlimpse\engine
$env:BGV_ENGINE_MODE = "real"
$env:BGV_OUTPUT_DIR_ALLOWLIST = "E:\Videos\BGV"
.\.venv\Scripts\python.exe main.py 2>&1 | Tee-Object -FilePath ..\build\engine-real.log
```

另开窗口跟踪：

```powershell
Get-Content E:\code\android\TracelessGlimpse\build\engine-real.log -Tail 100 -Wait
```

### 5.3 Claude Desktop 的 MCP 日志

目录：

```
C:\Users\19564\AppData\Roaming\Claude\logs
```

找文件并跟踪：

```powershell
Get-ChildItem "$env:APPDATA\Claude\logs" | Sort-Object LastWriteTime -Descending | Select-Object -First 10 Name, LastWriteTime

# 通常会有 mcp-server-traceless-glimpse.log；名字不完全一致时用通配符兜底
Get-Content (Get-ChildItem "$env:APPDATA\Claude\logs\mcp*.log" | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName -Tail 100 -Wait
```

这里能看到 MCP 进程启动失败、Java 路径错误、服务端 stderr 等。服务端默认日志级别是 `warn`，正常调用时该文件可能几乎为空。

### 5.4 Cursor 的 MCP 日志

打开 **Output** 面板，右上角下拉选择 **MCP Logs**。如果看不到，检查：

```powershell
Get-ChildItem "$env:APPDATA\Cursor\logs" -Recurse -Filter "*.log" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 10 FullName
```

### 5.5 关于「完整 JSON-RPC 交互日志」

MCP Server 按设计只把 JSON-RPC 写 stdout，不把每一帧复制到 stderr；客户端日志记录的是连接生命周期与服务端 stderr，不保证逐帧留痕。要复现原始握手，用第 2 节的手动命令；要复现完整工具调用与 stdout 纯净性断言，跑进程级集成测试：

```powershell
cd E:\code\android\TracelessGlimpse
.\gradlew.bat :mcp:test --tests "*McpStdioProcessTest*"
```

该测试连接 Mock Engine，不会触发真实下载。

---

## 6. 常见错误对照

| 现象 | 原因 | 处理 |
|---|---|---|
| `{"localError":"engine_unreachable"}` | 引擎未启动 / 端口不是 8730 | 先跑 1.3 的 health 检查 |
| `{"localError":"sniff_timeout"}` | 页面 60 秒内未产出格式，或 Playwright 启动失败 | 看引擎日志；确认 Chromium 已安装；可调大 `BGV_MCP_SNIFF_TIMEOUT_MS` 后重启客户端 |
| `{"localError":"download_timeout"}` | MCP 轮询超时，但引擎通常仍在下载 | 不要新建任务，用 `status` 查同一 taskId |
| `{"localError":"engine_protocol_mismatch"}` | 8730 上不是本引擎，或协议版本不符 | 关掉占用端口的旧进程，确认 health 为 `0.2` |
| `{"code":"DRM_PROTECTED"}` | 内容受 DRM 保护 | 按设计停止，不尝试下载 |
| `{"code":"NO_MEDIA_FOUND"}` | 页面没有可下载媒体，或需要登录 | 换公开页面；MCP 不接受 Cookie 路径 |
| `{"code":"DOWNLOAD_FAILED"}` 且日志提示 ffmpeg/ffprobe | PATH 缺 ffmpeg | 安装并加入 PATH，重启引擎 |
| 客户端里看不到 `traceless-glimpse` | 配置路径/JSON 写错、Java 找不到 | 用第 2 节手动命令验证；看 5.3 / 5.4 日志 |

---

## 7. 已知边界（验收时会遇到）

1. **MCP 不提供 Cookie 输入**：工具固定使用 `CookiePolicy.NONE`，需要登录才能看的页面可能返回 `NO_MEDIA_FOUND` 或 `EXTRACTOR_FAILED`。这是协议层的结构性约束，不是配置问题。
2. **DRM 内容不下载**：引擎在下载入口直接返回 `DRM_PROTECTED`。
3. **长下载与客户端超时**：`download` 会在服务端轮询到终态；如果 AI 客户端自身先掐断长工具调用，任务不会丢，用 `status` 继续查。
4. **首次嗅探较慢**：real 模式要启动 Chromium、等页面稳定、再跑 yt-dlp 解析；60 秒是上限，不是预期耗时。

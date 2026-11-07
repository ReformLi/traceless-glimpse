# desktop/ — PC 控制面板与单文件封装

> 面向 Windows。把「启动引擎」从一条命令行变成一个带托盘的窗口；M16 起还能直接在窗口里完成嗅探与下载，不必连手机。
> 本目录是**外壳 + 协议客户端**：Playwright 嗅探、yt-dlp 下载、FFmpeg 合并全部仍由 `engine/` 的 FastAPI 服务执行。

交付物是 `desktop/dist/BrowseGuardEngine.exe`（单文件、无控制台、带盾牌图标，约 70 MiB）。
它连同 `config.json`、`engine.log`、`preview/` 截图、`build/`、`dist/` 都按 `.gitignore` **不入库**，
每台机器需要自己打包。

## 1. 边界（改动前先读）

- **移动端零依赖**：`android/` 不引用本目录任何代码，M15 起移动端代码冻结。
- **引擎业务逻辑不改**：桌面端只通过 `http://127.0.0.1:<port>` 调用 `docs/PROTOCOL.md` 的端点；
  M16.5 是唯一例外——为支持自定义 FFmpeg 路径，在 `engine/services/` 增加了统一的可执行文件解析层。
- **无入站鉴权**（M15.3 裁决）：引擎不补 Token。默认监听 `0.0.0.0:8730` 是为了让手机连得上，
  代价是**只可在可信的家庭局域网使用**，切勿暴露公网。GUI 顶部有常驻黄色警告。
- **技术栈约束**：只用 `customtkinter` + `tkinter` + `pystray` + `Pillow`，不引入 PyQt / PySide / Electron。
- **打包身份**：`--onefile --noconsole`，单文件 exe 内没有独立 Python 可 spawn，所以引擎由同一个 exe
  以 `--engine-child` 再启动一次（见 `entry.py`）。

## 2. 目录

| 文件 | 职责 |
|---|---|
| `gui_app.py` | 主入口 `ControlPanel`：`CTkTabview` 双标签、配置读写、启停引擎、`ui_call` 主线程队列、托盘联动、冒烟测试 |
| `download_console.py` | Tab② 下载控制台纯 UI：画质下拉、进度条、速度/ETA、错误红字、置灰联动；只跑主线程 |
| `engine_client.py` | Tab② 的 httpx 客户端与流程编排：建任务 → 1s 轮询 → 取格式 → 下发下载 → 进度回抛；不 import Tk |
| `launcher.py` | 进程与环境层：拉起/杀干净引擎进程树、日志重定向、FFmpeg/Chromium 探测、`playwright install`、连接地址；也保留纯托盘模式 |
| `entry.py` | PyInstaller 唯一入口，按 argv 分派 GUI / `--engine-child` / `--playwright-install` |
| `build_exe.py` | 单文件打包脚本与 hidden-import 清单 |
| `e2e_m16.py` / `e2e_m16_5.py` | 驱动**真实 GUI 窗口**的 PC 端端到端验收脚本（真 HTTP 媒体源 + 真实 Playwright / yt-dlp / FFmpeg） |
| `shield.ico` | 多尺寸盾牌图标，由 `build_exe.py --icon-only` 从 `launcher.shield_image()` 生成 |
| `requirements.txt` | 桌面层专用依赖（引擎本身不需要，勿写进 `engine/requirements-lock.txt`） |
| `config.json` / `engine.log` / `preview/` / `build/` / `dist/` | 运行期产物，均不入库 |

## 3. 两种运行方式

### A. 单文件 exe（普通用户）

双击 `desktop/dist/BrowseGuardEngine.exe`，在「引擎设置」标签点「启动」即可。
点窗口 X 是**隐藏到托盘**，托盘菜单可重新唤起；只有托盘「退出」才会杀掉引擎进程树并结束程序。

- 首启要把约百 MB 随包文件解压到临时目录，就绪探测超时按打包态放宽到 75 s（源码态 30 s）。
- **浏览器内核不进 exe**：Playwright Chromium 仍装在 `%LOCALAPPDATA%\ms-playwright`。
  缺失时 GUI 的「一键安装内核」会跑 `playwright install chromium` 并把日志逐行回显。

### B. 源码（开发 / 排障）

```powershell
# 依赖装进引擎自己的 venv，脚本会自动优先挑它
engine\.venv\Scripts\python.exe -m pip install -r desktop\requirements.txt

# 控制面板
engine\.venv\Scripts\python.exe desktop\gui_app.py

# 纯托盘模式（M15 第一步的形态，保留可用）
engine\.venv\Scripts\python.exe desktop\launcher.py

# 无头验证入口
engine\.venv\Scripts\python.exe desktop\launcher.py --self-test
engine\.venv\Scripts\python.exe desktop\launcher.py --self-test-error
engine\.venv\Scripts\python.exe desktop\launcher.py --tray-smoke
```

打包（需要 `pyinstaller`，已在 `requirements.txt` 固定版本）：

```powershell
engine\.venv\Scripts\python.exe desktop\build_exe.py
engine\.venv\Scripts\python.exe desktop\build_exe.py --icon-only   # 只重新生成 ico
```

`--onefile --noconsole` 下必须 `--collect-all playwright`（`driver/node.exe` 与 JS 包是 data，不是 Python 模块）、
`--collect-submodules yt_dlp`（972 个 extractor 运行期动态导入）、`--add-data engine/`（子进程 sys.path 兜底）。
体积约 70 MiB 主要来自 Playwright 的 node 驱动与 PYZ，**浏览器内核本身不在这 70 MiB 里**。

## 4. 界面

| 区域 | 内容 |
|---|---|
| 全局常驻 | 标题「无痕掠影 · 本地引擎 · 控制面板」、黄色安全警告、底部引擎状态栏；切到任一标签都可见 |
| Tab① 引擎设置 | 连接地址大字 + 复制、监听 host / port、输出目录、自动启动、外观（跟随系统/浅色/深色）、启动 / 停止 / 查看日志 / 隐藏到托盘 |
| Tab① 环境依赖自检（M16.5） | FFmpeg `●` 红绿状态 + 版本号、「浏览…」选本机 `ffmpeg.exe`、「清除」；Playwright Chromium 状态 +「一键安装内核」+ 常驻日志区 |
| Tab② 下载控制台 | URL → 开始嗅探 → 画质下拉 → 开始下载 → 进度条 / 速度 / 剩余时间 → 完成提示或错误红字 |

引擎未启动时 Tab② **整页置灰**并给出提示；停止引擎会取消轮询、清空下载态，重启后按钮恢复可用。
状态符号用 `●` + 主题色着色——`🟢/🔴` 与 `▶/⏹` 在 Microsoft YaHei UI 下实测会退化成斜线圆圈或方框。

## 5. `config.json`

位置：源码态是 `desktop/config.json`；打包态是 **exe 同目录**。原子写入，文件损坏时自动备份为
`config.json.bak` 后回落默认值。

| 字段 | 默认 | 说明 |
|---|---|---|
| `host` | `0.0.0.0` | `0.0.0.0（局域网）` / `127.0.0.1（仅本机）` |
| `port` | `8730` | 端口被占用会红字拒绝启动，不会抢端口 |
| `output_dir` | `%USERPROFILE%\Videos\BGV` | 同时作为 `BGV_OUTPUT_DIR_ALLOWLIST` 注入引擎 |
| `auto_start` | `false` | 默认**不**自动启动，等用户点按钮 |
| `appearance` | `system` | `system` / `light` / `dark` |
| `ffmpeg_path` | `""` | 空串 = 走系统 PATH；非空 = `ffmpeg.exe` 绝对路径，注入 `BGV_FFMPEG_PATH` |

## 6. 注入引擎子进程的环境变量

| 变量 | 值 | 引擎侧消费点 |
|---|---|---|
| `BGV_ENGINE_MODE` | `real` | 桌面端强制真实模式；`stub` 仍只是引擎单测 / 协议联调的默认值 |
| `BGV_OUTPUT_DIR_ALLOWLIST` | GUI 的输出目录 | `main._load_output_dir_allowlist`；手机端取 `selfcheck.outputDirAllowlist[0]` |
| `BGV_LISTEN_HOST` / `BGV_LISTEN_PORT` | GUI 的 host / port | `services/environment.py`，`GET /v1/privacy/selfcheck` 的 `listenAddress` 实时回显 |
| `BGV_FFMPEG_PATH` | 仅当 `ffmpeg_path` 非空 | `engine/services/ffmpeg_path.py`，合并 / ffprobe 校验 / yt-dlp `ffmpeg_location` / 版本探测共用 |

进程管理沿用 `launcher.EngineProcess`：日志合并写入 `engine.log`；退出用 `taskkill /PID <pid> /T /F` 加
server PID 补杀，避免 Windows venv 重定向器留下孤儿 uvicorn 进程。

`BGV_FFMPEG_PATH` 的解析规则见 `engine/README.md`：**配置写了但文件不存在会明确报错，不会静默回落 PATH**，
否则用户会以为自定义版本生效、实际用的是系统里另一个版本。`ffprobe` 优先取 `ffmpeg.exe` 同目录兄弟文件。

## 7. 验证

以下命令都在仓库根执行；报告与截图落在 `desktop/preview/`（不入库）。

| 命令 | 覆盖 |
|---|---|
| `engine\.venv\Scripts\python.exe desktop\gui_app.py --gui-smoke` | 控件、配置读写、置灰联动、画质映射、环境变量注入；可加 `--screenshot` / `--dark-shot` / `--console-shot` / `--console-dark-shot` / `--console-offline-shot` / `--smoke-report` 导出截图与报告 |
| `engine\.venv\Scripts\python.exe desktop\e2e_m16.py` | 真实 GUI 窗口跑完整下载闭环（嗅探→画质→进度→可播产物→失败红字→中途停止）；`--public-url <URL>` 可切公网，只验嗅探 |
| `engine\.venv\Scripts\python.exe desktop\e2e_m16_5.py` | 摘掉 PATH 里的 FFmpeg → 红色未找到 → 浏览选自定义路径 → 真实分离轨合并下载 → ffprobe 双轨校验 → 一键安装日志 |
| `desktop\dist\BrowseGuardEngine.exe --gui-smoke --smoke-report <abs path>` | 打包版 exe 内的同一套冒烟，验证 hidden-import 没漏 |
| `cd engine; .\.venv\Scripts\python.exe -m pytest -q` | 引擎回归（桌面端改了 `services/` 后必跑） |

M16.5 收官现场：`gui-smoke 85/85`、`e2e_m16 27/27`、`e2e_m16_5 33/33`、exe 内 `gui-smoke 76/76`（连跑两次）、
引擎 `pytest 238 passed`。逐轮取证见 `docs/smoke-report.md` §34。

## 8. 已知边界

- **无入站鉴权**：任何能访问该端口的进程都能下发任务，只在可信局域网使用。
- **画质选择不能发 `formatId`**：real 模式下 `strategy="exact"` + `formatId` 会被
  `services/formats.py` 判 422 `FORMAT_UNAVAILABLE`。GUI 只发结构化 selector——
  有视频且 `height>0` → `{"strategy":"max_height","maxHeight":H}`；纯音频 → `audio_only`；
  有视频但无高度 → `best`。另外 `POST /v1/tasks` 返回的是 `Task.id`（不是 `taskId`），
  `/download` 的 selector 必须包在 `formatSelector` 里。
- **公网真实站点**：`w3schools` 视频页能嗅探到 mp4，但下载被站点鉴权拒为 `AUTH_REQUIRED`；
  B 站一类 DASH 站请求 `.m4s`，引擎 sniffer 的扩展名白名单不识别。默认验收因此使用本地真实
  HTTP 媒体服务器（真实字节、真实 Playwright / yt-dlp / FFmpeg，不是 UI 桩数据）。
- **打包版首启较慢**：单文件解压 + 首次冷启动的 `/v1/privacy/selfcheck` 可能超过 2 s，
  探测超时已放宽到 6 s（`/v1/health` 仍为 2 s）。
- 截图用 Win32 `PrintWindow` 抓窗口 DC，不依赖 Z-order——在本机合成环境下 `ImageGrab`
  即使窗口 `normal/visible` 也只会拍到桌面壁纸。

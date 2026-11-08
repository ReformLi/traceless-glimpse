# engine/ — 本地引擎

Python 3.11+ / FastAPI / Pydantic v2。承载 Playwright 嗅探、yt-dlp 下载、FFmpeg 合并与后处理；默认监听 `127.0.0.1:8730`，所有响应（含错误）携带 `X-Protocol-Version: 0.2`（`main.py` 的 `PROTOCOL_VERSION`，M5 起随 `Task.outputPath` 升版）。

桌面启动器（M15 起）默认绑定 `0.0.0.0:8730` 并强制 `BGV_ENGINE_MODE=real`，见 `desktop/README.md`；协议文档、桩模式与 `python main.py` 仍以 `127.0.0.1:8730` 为缺省值。

模式由 `BGV_ENGINE_MODE` 选择：`stub`（默认）返回假数据但状态机与错误码真实，`real` 才接真实 Playwright / yt-dlp / FFmpeg。
两模式的差异清单见仓库根 `STUB_VS_REAL.md`。桩模式仍然是默认值——协议联调与 Schema 对账都在桩上跑，
不依赖外部二进制，这条设计从 M0 起没变。

## 环境

- venv 固定路径 `engine/.venv`（已 gitignore），依赖全部 `==` 固定于 `requirements-lock.txt`（提交）：

```powershell
cd engine
python -m venv .venv
.\.venv\Scripts\pip install -r requirements-lock.txt
```

- `engine/schema/*.json` 为契约产物，由导出脚本生成后提交，勿手工修改。
- **schema 文件命名风格**：小写蛇形，与模型导出顺序一一对应 `shared/schema/`——
  `task.json` / `sniff_result.json` / `format_info.json` / `format_selector.json` / `post_action.json` / `error_payload.json`；
  导出命令：`.\.venv\Scripts\python.exe export_schema.py`。
- **可空性标记 `x-nullable: true`**：字段类型允许 `null` 时由导出脚本写入，来源是 Pydantic
  `Optional[...]` 生成的 `anyOf` 里的 `{"type":"null"}` 分支；不可空字段不带该键。
  它与 `required` 是两条轴——`required` 说「字段必须出现在 JSON 里」，`x-nullable` 说「值可以是 null」。
  对账脚本 `scripts/check_schema.py` 按 required / properties / enum / type / x-nullable 五类比对。

## 安全相关环境变量

- `BGV_COOKIE_FILE`：惰性 Cookie 的引擎侧凭据文件（Netscape cookies.txt）。
  未设置时 `lazy` / `explicit` 均不读取任何 Cookie；协议层不能指定文件路径。
- `BGV_OUTPUT_DIR_ALLOWLIST`：输出目录白名单（`os.pathsep` 分隔）。
- `BGV_ENABLE_DEBUG_ENDPOINTS=1`：开放 `/v1/_debug/download-counter`，默认 404。
- `BGV_CACHE_DIR`：仅用于 `GET /v1/privacy/selfcheck` 报告的缓存目录；缺省为
  Windows `%LOCALAPPDATA%\BGV\cache`，其他平台 `$XDG_CACHE_HOME/bgv` 或 `~/.cache/bgv`。
  当前引擎不写入缓存，故 `cacheSizeBytes` 通常为 0。
- `BGV_LISTEN_HOST` / `BGV_LISTEN_PORT`：引擎**实际绑定**的监听地址，由 M15 桌面启动器
  （或运维脚本）随 `--host` / `--port` 一起注入；`GET /v1/privacy/selfcheck` 的
  `listenAddress` 按请求实时回显这两个值，不再是硬编码 `127.0.0.1:8730`。
  未设置或端口非法时回落到协议默认 `127.0.0.1:8730`；`python main.py` 也据此绑定。
- `BGV_FFMPEG_PATH`（M16.5）：`ffmpeg.exe` 的绝对路径，由桌面控制面板「环境依赖自检」
  选中后注入。引擎侧统一在 `services/ffmpeg_path.py` 解析，供合并（`services/merger.py`）、
  产物完整性校验（`services/downloader.py` 的 `_ffprobe_duration`）、yt-dlp 的
  `ffmpeg_location` 与 selfcheck 的 `dependencyVersions.ffmpeg` 四处共用：

  1. 已设置 → 必须是存在的文件，**不回落 PATH**（配置写错就明确抛错，避免系统里另一个
     FFmpeg 版本悄悄遮住自定义版本）；`ffprobe` 优先取 `ffmpeg.exe` 同目录的兄弟文件；
  2. 未设置 → `shutil.which("ffmpeg")` / `("ffprobe")`；
  3. 都找不到 → 返回 `None`，由各调用方抛领域内的明确错误（`MergeError` /
     `DownloadIntegrityError`）。

  桌面启动器注入的完整变量族（另有 `BGV_ENGINE_MODE=real`、`BGV_OUTPUT_DIR_ALLOWLIST`、
  `BGV_LISTEN_HOST/PORT`）与进程树清理方式见 `desktop/README.md` §6。
- `BGV_PROXY`（M21）：**出站代理**，嗅探 Chromium、清单抓取（`services/formats.py`）、
  逐跳探测（`services/redirect.py`）、直链下载（`services/downloader.py`）与 yt-dlp
  五处共用一套口径（`services/proxy.py`）。只认显式配置，**不读系统代理**——
  系统代理会在用户不知情时变化，静默改出站路径比「代理不生效」更难排查。
  留空 = 直连，行为与本功能上线前完全一致。写法：`http://127.0.0.1:7890`、
  `http://user:pass@host:port`、`socks5://host:port`（socks 供 Playwright / yt-dlp，
  httpx 需 `httpx[socks]`）。桌面控制面板「网络代理」输入框写的就是它，改完需重启引擎。


### 嗅探失败的归因（M21）

嗅探异常不再一律报 `EXTRACTOR_FAILED`（「解析失败，可能站点已变更」）——那句话只对
「页面打开了但结构变了」成立。连接层根本没建立时按 `services/failures.py` 的归因表分：

| Chromium 网络码 | §5 错误码 | 用户看到的话 |
|---|---|---|
| `ERR_NAME_NOT_RESOLVED` | `DNS_FAILURE` | 域名解析失败 |
| `ERR_TIMED_OUT` / `ERR_CONNECTION_TIMED_OUT` | `NETWORK_TIMEOUT` | 网络超时 |
| `ERR_SSL_*` / `ERR_CERT_*` | `TLS_ERROR` | 安全连接失败 |
| `ERR_CONNECTION_RESET` / `REFUSED` / `CLOSED` 等 | `NETWORK_HTTP_ERROR` | 目标站点连接被重置，可能被网络阻断；可在电脑端配置代理后重试 |
| `ERR_PROXY_*` / `ERR_TUNNEL_*` | `NETWORK_HTTP_ERROR` | 代理不可用：连不上配置的代理地址… |

`detail` 固定 `{"reason": "sniff_failed", "net": "<ERR码>"}`，**不含异常原文**
（原文带完整 URL 与查询串）；日志同理，只打 `type=SniffError net=<ERR码> code=<§5 码>`。

### 嗅探雷达与反反爬（M18）

`services/sniffer.py` 的 Playwright 上下文默认带三层伪装：真实桌面 Chrome UA（去掉
`HeadlessChrome` 字样）+ 1920×1080 视口 + `locale=zh-CN` + `Asia/Shanghai` 时区 +
`add_init_script` 抹掉 `navigator.webdriver`、补齐 `chrome`/`plugins`/`languages` 形状、
对齐 `permissions.query` 与通知权限。媒体识别扩为三层信号，rank 越小越优先：
URL 后缀（`.m3u8/.mpd/.mp4/.flv/.ts/.m4s/.m4a/.aac/.mp3/.webm`）→ Response
Content-Type（`video/*`、`audio/*`、m3u8 四个别名、`dash+xml`、`octet-stream`）→
URL 关键字（`playlist/master/stream/manifest`，仅兜底，主文档导航不参与）。
页面加载后追加 `networkidle` 等待（≤4s，长轮询页面超时即视为可观察）与触底滚动（懒加载）。

- `BGV_SNIFF_DEBUG=1`：把 `page.on("request")` / `page.on("response")` 捕获的**全部**
  请求/响应（URL + resource type + 状态码 + Content-Type）以 `sniff_debug` 行打进日志，
  不做任何过滤，供联调取证。注意该行带完整 URL，仅供开发排查，生产勿开；
- `BGV_SNIFF_HEADED=1`：改用「有头 + `--window-position=-32000,-32000`（窗口在屏幕外，
  不弹到用户眼前）」形态启动 Chromium——无头指纹仍被识别时的最后手段，走真实渲染管线。

### 开发专用：临时 SSRF 主机白名单（dev only, never in production）

`BGV_SSRF_ALLOWLIST` + `BGV_DEV_ALLOW_SSRF_BYPASS`：仅用于真机 / 本地媒体联调，
让受控测试主机通过出站校验。**三个条件同时满足才生效**，缺任何一个白名单完全不注册：

1. `BGV_SSRF_ALLOWLIST` 非空 —— 逗号分隔主机名或 IP，**逐个整串精确匹配**，
   不支持 IP 段（写 `127.0.0.0/24` 不放行 `127.0.0.1`）也不支持后缀匹配；
2. `BGV_DEV_ALLOW_SSRF_BYPASS=1`；
3. `BGV_ENGINE_MODE=real`。非 real 而设了 `DEV_ALLOW` 会打
   `dev_ssrf_bypass_ignored engine_mode=...` 告警且不生效。

每次放行打印一行 `ssrf_allowlist_bypass host=<host>`（只带 host，不带 URL / 端口 / 路径），
被拒路径同样补一行 `ssrf_blocked host=<host> reason=<类别>`。
生产部署不要设置这两个变量；测试基线（`conftest.py`）会显式清除，防止本机 shell 环境串味。

## 隐私自检（§6.5）

`GET /v1/privacy/selfcheck` 只读汇总运行环境，不发起出站请求、不读取 Cookie 文件。
`lastCleanupAt` 暂为 `null`（尚无缓存清理子系统），依赖版本按请求实时探测，
探测失败时填 `"unavailable"`；取值口径见 `docs/PROTOCOL-CHANGELOG.md`。


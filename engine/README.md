# engine/ — 本地引擎

Python 3.11+ / FastAPI / Pydantic v2。承载 Playwright 嗅探、yt-dlp 下载、FFmpeg 合并与后处理；默认监听 `127.0.0.1:8730`，所有响应（含错误）携带 `X-Protocol-Version: 0.2`（`main.py` 的 `PROTOCOL_VERSION`，M5 起随 `Task.outputPath` 升版）。

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


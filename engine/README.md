# engine/ — 本地引擎

Python 3.11+ / FastAPI / Pydantic v2。承载 Playwright 嗅探、yt-dlp 下载、FFmpeg 合并与后处理；默认监听 `127.0.0.1:8730`，响应携带 `X-Protocol-Version: 0.1`。

M0 交付**桩服务**：`docs/PROTOCOL.md` §6 全部 P0 端点、状态机与错误码真实，返回假数据；不接入真实 Playwright / yt-dlp / FFmpeg（M1）。

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

## 安全相关环境变量

- `BGV_COOKIE_FILE`：惰性 Cookie 的引擎侧凭据文件（Netscape cookies.txt）。
  未设置时 `lazy` / `explicit` 均不读取任何 Cookie；协议层不能指定文件路径。
- `BGV_OUTPUT_DIR_ALLOWLIST`：输出目录白名单（`os.pathsep` 分隔）。
- `BGV_ENABLE_DEBUG_ENDPOINTS=1`：开放 `/v1/_debug/download-counter`，默认 404。
- `BGV_CACHE_DIR`：仅用于 `GET /v1/privacy/selfcheck` 报告的缓存目录；缺省为
  Windows `%LOCALAPPDATA%\BGV\cache`，其他平台 `$XDG_CACHE_HOME/bgv` 或 `~/.cache/bgv`。
  当前引擎不写入缓存，故 `cacheSizeBytes` 通常为 0。

## 隐私自检（§6.5）

`GET /v1/privacy/selfcheck` 只读汇总运行环境，不发起出站请求、不读取 Cookie 文件。
`lastCleanupAt` 暂为 `null`（尚无缓存清理子系统），依赖版本按请求实时探测，
探测失败时填 `"unavailable"`；取值口径见 `docs/PROTOCOL-CHANGELOG.md`。


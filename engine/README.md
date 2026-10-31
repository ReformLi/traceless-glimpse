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

## 待办（M1）

- `awaiting_selection` 24h 超时（PROTOCOL §4）：M0 不实现，M1 补。
- 真实嗅探 / 下载 / 合并 / 转码链路；断点续传与重试。
- `/v1/_debug/download-counter`：M1 起默认返回 404，仅 `BGV_ENABLE_DEBUG_ENDPOINTS=1`
  时开放；`scripts/smoke.sh` 取证时需在启动引擎前设置该变量。

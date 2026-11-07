"""M16 第二步 · 桌面端 → 本机引擎的 HTTP 客户端 + 任务流程编排。

**本模块不 import 任何 Tk / customtkinter**：它只负责把语音协议（docs/PROTOCOL.md
§6）落成 httpx 调用，以及在**调用方的后台线程里**推进状态机。界面渲染一律通过
``emit(fn, *args, **kwargs)`` 投递回主线程，本模块不持有任何控件引用。

真实 wire format（与 ``engine/models.py`` 逐字段核对过，勿按想当然改）
---------------------------------------------------------------------
* ``POST /v1/tasks`` → **201**，响应体是 ``Task`` 对象，主键叫 ``id``
  （不是 ``taskId``；``taskId`` 只出现在 ``/formats`` 与 ``SniffResult`` 里）。
* ``GET /v1/tasks/{id}`` → ``Task``：``status`` / ``progress`` / ``error`` /
  ``outputPath``。``progress`` 有 ``percent / speedBps / etaSeconds / phase``。
* ``GET /v1/tasks/{id}/formats`` → ``{"taskId": ..., "items": [FormatInfo...]}``。
* ``POST /v1/tasks/{id}/download`` → **202**，请求体必须把选择器包在
  ``formatSelector`` 里（``DownloadRequest`` 的必填字段）：
  ``{"formatSelector": {"strategy": "max_height", "maxHeight": 1080}}``。
  裸的 ``{"strategy": ...}`` 会被 ``StrictModel(extra="forbid")`` 判 422。
* 错误响应统一 ``{"error": {"code", "message", "category", "retryable", ...}}``。

M16 第一步排出的协议坑在 :func:`selector_for_format` 里落地：real 模式下
``services/formats.py::ytdlp_format_expression`` 对 ``strategy="exact"``
（以及带 ``formatId``）直接抛 ``exact_format_id_unsupported``，最终变成 422
``FORMAT_UNAVAILABLE``。所以桌面端**永远不发 formatId**，只把用户选中的画质
换算成 ``max_height`` / ``audio_only`` / ``best``。
"""
from __future__ import annotations

import time
from typing import Any, Callable

import httpx

#: 轮询间隔：需求指定每 1 秒一次。
POLL_INTERVAL_SECONDS = 1.0
#: 嗅探要开 Playwright 真浏览器 + yt-dlp 解析，慢站点可能要几十秒。
SNIFF_TIMEOUT_SECONDS = 180.0
#: 下载可能持续很久；只要引擎还活着就一直跟，stop_event 是唯一主动退出通道。
DOWNLOAD_TIMEOUT_SECONDS = 3600.0
REQUEST_TIMEOUT_SECONDS = 10.0

#: 引擎侧非成功终态。
FAILED_STATUSES = frozenset({"failed", "cancelled", "done"})

Emit = Callable[..., None]


class EngineClientError(Exception):
    """引擎返回了协议错误体，或本机 HTTP 层连不上引擎。"""

    def __init__(self, code: str, message: str = "", status: int = 0) -> None:
        super().__init__(f"{code}: {message}")
        self.code = code or "UNKNOWN"
        self.message = message or ""
        self.status = status


def extract_error(payload: Any, status: int = 0) -> tuple[str, str]:
    """``{"error": {...}}`` → ``(code, message)``。

    ``detail.message``（如「上限内没有可用画质」）比通用 code 文案更有用，
    拼到末尾但不覆盖引擎已翻译的主文案；``detail`` 里其余字段可能是 URL /
    主机名，绝不展示（§1.6 脱敏）。
    """
    error: dict = {}
    if isinstance(payload, dict):
        raw = payload.get("error")
        if isinstance(raw, dict):
            error = raw
    code = str(error.get("code") or "").strip()
    if not code:
        code = "VALIDATION_ERROR" if status == 422 else "UNKNOWN"
    message = str(error.get("message") or "").strip()
    detail = error.get("detail")
    if isinstance(detail, dict):
        extra = str(detail.get("message") or "").strip()
        if extra and extra not in message:
            message = f"{message}（{extra}）" if message else extra
    return code, message


def task_error(task: dict) -> tuple[str, str]:
    """``GET /v1/tasks/{id}`` 里 ``task.error`` → ``(code, message)``。"""
    return extract_error(task if isinstance(task, dict) else {})


def selector_for_format(fmt: dict) -> dict:
    """``FormatInfo`` → ``formatSelector``（**绝不含 formatId**）。

    规则（需求 §一.3 + 引擎 real 模式实际支持面）：

    * 有视频且 ``resolution.height > 0`` → ``max_height`` + ``maxHeight``；
    * 纯音频（``hasAudio`` 且无 ``hasVideo``）→ ``audio_only``；
    * 有视频但元数据没有高度（直链 / 未知分辨率）→ ``best``，不让引擎
      因 ``max_height_required`` 直接 422；
    * 其余（既无视频也无音频的坏条目）→ ``best``，交给引擎判定。
    """
    if not isinstance(fmt, dict):
        return {"strategy": "best"}
    # 同时吃两种形态：引擎原始 FormatInfo（resolution.height）与 UI 下拉框
    # 绑定的扁平元数据（height），避免桌面端为了映射再拼一次嵌套结构。
    raw_height = fmt.get("height")
    if raw_height is None:
        raw_height = (fmt.get("resolution") or {}).get("height")
    try:
        height = int(raw_height or 0)
    except (TypeError, ValueError):
        height = 0
    has_video = bool(fmt.get("hasVideo"))
    has_audio = bool(fmt.get("hasAudio"))
    if has_video and height > 0:
        return {"strategy": "max_height", "maxHeight": height}
    if has_audio and not has_video:
        return {"strategy": "audio_only"}
    return {"strategy": "best"}


class EngineClient:
    """短生命周期的本机 HTTP 客户端。建议 ``with EngineClient(...) as c:``。

    ``trust_env=False``：忽略系统代理。Windows 上若用户开了全局代理，httpx
    默认会把 ``http://127.0.0.1:8730`` 也塞给代理，导致「引擎明明活着却连不上」。
    """

    def __init__(self, base_url: str, *, timeout: float = REQUEST_TIMEOUT_SECONDS) -> None:
        self.base_url = base_url.rstrip("/")
        self._client = httpx.Client(timeout=timeout, trust_env=False)

    def __enter__(self) -> "EngineClient":
        return self

    def __exit__(self, *_exc) -> None:
        self.close()

    def close(self) -> None:
        try:
            self._client.close()
        except Exception:
            pass

    def _request(self, method: str, path: str, *, body: dict | None = None,
                 allowed: tuple[int, ...] = (200,)) -> dict:
        try:
            response = self._client.request(method, self.base_url + path, json=body)
        except httpx.TimeoutException as exc:
            raise EngineClientError("NETWORK_TIMEOUT", f"请求引擎超时：{path}") from exc
        except httpx.RequestError as exc:
            raise EngineClientError("UNKNOWN", f"无法连接本机引擎：{exc}") from exc
        try:
            payload = response.json()
        except ValueError:
            payload = {}
        if response.status_code not in allowed:
            code, message = extract_error(payload, response.status_code)
            raise EngineClientError(code, message, response.status_code)
        return payload if isinstance(payload, dict) else {}

    def create_task(self, url: str, output_dir: str) -> str:
        """``POST /v1/tasks`` → 任务主键 ``id``。"""
        body = {
            "sourceUrl": url,
            "mode": "download",
            "cookiePolicy": "lazy",
            "outputDir": output_dir,
        }
        payload = self._request("POST", "/v1/tasks", body=body, allowed=(201, 200))
        task_id = payload.get("id")
        if not isinstance(task_id, str) or not task_id:
            raise EngineClientError("UNKNOWN", "引擎未返回任务 id（响应体缺少 id）")
        return task_id

    def get_task(self, task_id: str) -> dict:
        """``GET /v1/tasks/{id}`` → ``Task``。"""
        return self._request("GET", f"/v1/tasks/{task_id}")

    def get_formats(self, task_id: str) -> list[dict]:
        """``GET /v1/tasks/{id}/formats`` → ``items``。非 awaiting_selection 会 409。"""
        payload = self._request("GET", f"/v1/tasks/{task_id}/formats")
        items = payload.get("items")
        return [item for item in items if isinstance(item, dict)] if isinstance(items, list) else []

    def get_sniff_results(self, task_id: str) -> list[dict]:
        """``GET /v1/tasks/{id}/sniff-results``，用于给结果区取媒体描述。"""
        payload = self._request("GET", f"/v1/tasks/{task_id}/sniff-results")
        items = payload.get("items")
        return [item for item in items if isinstance(item, dict)] if isinstance(items, list) else []

    def start_download(self, task_id: str, selector: dict) -> dict:
        """``POST /v1/tasks/{id}/download`` → 202。selector 包在 formatSelector 里。

        额外带 ``postActions=[{"type": "merge"}]``：验收要求「自动合并」。
        引擎 ``_merge_plan`` 会按源里是否**真的**存在分离音视频轨决定合并或
        记 ``post_action_noop`` 降级，单文件/单混合流不会因此失败。
        """
        return self._request(
            "POST", f"/v1/tasks/{task_id}/download",
            body={"formatSelector": selector, "postActions": [{"type": "merge"}]},
            allowed=(202, 200),
        )


def run_sniff_flow(
    client: EngineClient,
    *,
    url: str,
    output_dir: str,
    emit: Emit,
    stop_event,
    on_formats: Callable,
    on_error: Callable,
    on_status: Callable | None = None,
    timeout: float = SNIFF_TIMEOUT_SECONDS,
) -> str | None:
    """后台线程：建任务 → 每秒轮询到 ``awaiting_selection`` → 取画质表。

    返回 ``task_id``（供日志/诊断），失败返回 ``None``。所有 UI 动作只经 ``emit``
    投递；``stop_event`` 被置位（用户停止引擎 / 退出）时立即返回，不再碰 UI。
    """
    def status(text: str) -> None:
        if on_status is not None:
            emit(on_status, text)

    try:
        task_id = client.create_task(url, output_dir)
    except EngineClientError as exc:
        emit(on_error, exc.code, exc.message)
        return None
    except Exception as exc:  # noqa: BLE001 - 后台线程不得把异常抛到线程边界
        emit(on_error, "UNKNOWN", f"创建任务失败：{exc}")
        return None

    status(f"任务已创建（{task_id[:8]}…），正在嗅探页面媒体…")
    deadline = time.monotonic() + timeout
    while not stop_event.is_set():
        try:
            task = client.get_task(task_id)
        except EngineClientError as exc:
            emit(on_error, exc.code, exc.message)
            return task_id
        except Exception as exc:  # noqa: BLE001
            emit(on_error, "UNKNOWN", f"查询任务失败：{exc}")
            return task_id

        state = str(task.get("status") or "")
        if state == "awaiting_selection":
            try:
                formats = client.get_formats(task_id)
            except EngineClientError as exc:
                emit(on_error, exc.code, exc.message)
                return task_id
            title = _sniff_title(url, client, task_id)
            emit(on_formats, task_id, formats, title=title)
            status(f"嗅探完成：{len(formats)} 个可用画质，请选择后点「开始下载」。")
            return task_id
        if state in FAILED_STATUSES:
            code, message = task_error(task)
            if state == "done":
                code, message = "NO_MEDIA_FOUND", "该页面未进入画质选择，可能没有可下载媒体"
            emit(on_error, code, message)
            return task_id
        if time.monotonic() > deadline:
            emit(on_error, "NETWORK_TIMEOUT", "嗅探超时（180 秒），请检查站点是否可访问")
            return task_id
        # 可中断的 1 秒等待：停止引擎时最多再等 1 秒就退出，而不是 sleep 硬等。
        stop_event.wait(POLL_INTERVAL_SECONDS)
    return task_id


def run_download_flow(
    client: EngineClient,
    *,
    task_id: str,
    selector: dict,
    emit: Emit,
    stop_event,
    on_progress: Callable,
    on_done: Callable,
    on_error: Callable,
    on_status: Callable | None = None,
    timeout: float = DOWNLOAD_TIMEOUT_SECONDS,
) -> None:
    """后台线程：触发下载 → 每秒轮询进度 → done / failed 收尾。"""
    def status(text: str) -> None:
        if on_status is not None:
            emit(on_status, text)

    try:
        client.start_download(task_id, selector)
    except EngineClientError as exc:
        emit(on_error, exc.code, exc.message)
        return
    except Exception as exc:  # noqa: BLE001
        emit(on_error, "UNKNOWN", f"触发下载失败：{exc}")
        return

    status("下载已启动，正在接收进度…")
    deadline = time.monotonic() + timeout
    while not stop_event.is_set():
        try:
            task = client.get_task(task_id)
        except EngineClientError as exc:
            emit(on_error, exc.code, exc.message)
            return
        except Exception as exc:  # noqa: BLE001
            emit(on_error, "UNKNOWN", f"查询进度失败：{exc}")
            return

        state = str(task.get("status") or "")
        progress = task.get("progress") if isinstance(task.get("progress"), dict) else {}
        emit(
            on_progress,
            percent=progress.get("percent", 0.0),
            speed_bps=progress.get("speedBps", 0),
            eta_seconds=progress.get("etaSeconds", 0),
            phase=state or str(progress.get("phase") or "downloading"),
        )
        if state == "done":
            path = str(task.get("outputPath") or "")
            emit(on_done, path)
            status("下载完成，文件已写入输出目录。")
            return
        if state == "failed":
            code, message = task_error(task)
            emit(on_error, code, message)
            return
        if state == "cancelled":
            emit(on_error, "UNKNOWN", "任务已取消，可重新嗅探后再试")
            return
        if time.monotonic() > deadline:
            emit(on_error, "NETWORK_TIMEOUT", "下载轮询超时，请查看引擎日志")
            return
        stop_event.wait(POLL_INTERVAL_SECONDS)


def _sniff_title(url: str, client: EngineClient, task_id: str) -> str:
    """结果区副标题：优先展示嗅探到的媒体类型/画质，退回落源 URL。"""
    try:
        items = client.get_sniff_results(task_id)
    except Exception:
        items = []
    if items:
        media = items[0]
        quality = media.get("estimatedQuality") or {}
        label = str(quality.get("label") or "").strip()
        kind = str(media.get("type") or "").strip()
        if label or kind:
            return f"检测到 {kind.upper()} 媒体 · {label or '未知画质'} · 来源 {_short(url)}"
    return _short(url)


def _short(text: str, limit: int = 92) -> str:
    return text if len(text) <= limit else text[:limit] + "…"

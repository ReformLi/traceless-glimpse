"""BrowseGuard Vid 本地引擎桩（M0）。

只实现协议合同：docs/PROTOCOL.md §6 P0 端点 + §9 四示例。不做真实
嗅探 / 下载 / 合并：
- 嗅探结果与格式列表来自 fixture（sourceUrl 路径含 /drm → DRM 场景）；
- "下载"只推进状态机并计数（_do_download），不发出任何网络请求；
- DRM 任务在 download 入口、审计行之后、下载逻辑之前短路返回
  422 DRM_PROTECTED（§3.2：不得尝试下载）。

M0 桩语义：
- 状态惰性推进：GET 任务详情时 queued→sniffing→（mode=sniff ? done
  : formatSelector 为空或 DRM ? awaiting_selection : downloading）；
  downloading→done。
- 带 formatSelector 的 download 任务按 §4 由引擎自动领取下载（审计行 +
  _do_download，与 POST /download 同一执行链）；DRM 任务不自动下载（§3.2），
  停 awaiting_selection 等 POST /download 返回 422 DRM_PROTECTED。
- awaiting_selection 的 24h 超时 M0 不实现（M1 补，见 engine/README.md）。
"""
from __future__ import annotations

import logging
import random
import time
from datetime import datetime, timezone
from urllib.parse import urlsplit

from fastapi import FastAPI, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from models import (
    Codec,
    CookiePolicy,
    DownloadRequest,
    DrmSystem,
    ErrorCode,
    ErrorPayload,
    EstimatedQuality,
    FormatInfo,
    FormatSelector,
    FormatSource,
    MediaStreamType,
    PostAction,
    Progress,
    Resolution,
    SelectionStrategy,
    SniffResult,
    Task,
    TaskCreateRequest,
    TaskMode,
    TaskStatus,
    error_payload,
)
from security import (
    SecurityError,
    sanitize_headers,
    validate_outbound_url,
    validate_output_dir,
)

logger = logging.getLogger("bgv.engine")

PROTOCOL_VERSION = "0.1"
ENGINE_VERSION = "0.1.0"
PROTOCOL_HEADERS = {"X-Protocol-Version": PROTOCOL_VERSION}
START_TIME = time.time()

# ---------------------------------------------------------------------------
# 状态机（协议 §4）
# ---------------------------------------------------------------------------

TRANSITIONS: dict[TaskStatus, set[TaskStatus]] = {
    TaskStatus.QUEUED: {TaskStatus.SNIFFING, TaskStatus.CANCELLED, TaskStatus.FAILED},
    TaskStatus.SNIFFING: {
        TaskStatus.DONE,
        TaskStatus.AWAITING_SELECTION,
        TaskStatus.DOWNLOADING,
        TaskStatus.FAILED,
        TaskStatus.CANCELLED,
    },
    TaskStatus.AWAITING_SELECTION: {
        TaskStatus.DOWNLOADING,
        TaskStatus.CANCELLED,
        TaskStatus.FAILED,
    },
    TaskStatus.DOWNLOADING: {
        TaskStatus.MERGING,
        TaskStatus.DONE,
        TaskStatus.FAILED,
        TaskStatus.CANCELLED,
    },
    TaskStatus.MERGING: {
        TaskStatus.POST_PROCESSING,
        TaskStatus.DONE,
        TaskStatus.FAILED,
        TaskStatus.CANCELLED,
    },
    TaskStatus.POST_PROCESSING: {
        TaskStatus.DONE,
        TaskStatus.FAILED,
        TaskStatus.CANCELLED,
    },
    TaskStatus.DONE: set(),
    TaskStatus.FAILED: set(),
    TaskStatus.CANCELLED: set(),
}
TERMINAL_STATES = {TaskStatus.DONE, TaskStatus.FAILED, TaskStatus.CANCELLED}
DOWNLOAD_ACTIVE_STATES = {
    TaskStatus.DOWNLOADING,
    TaskStatus.MERGING,
    TaskStatus.POST_PROCESSING,
}


class TaskStateConflictError(Exception):
    """状态机冲突（§5 TASK_STATE_CONFLICT，internal / 不可重试 / 409）。"""


class DrmProtectedError(Exception):
    """DRM 内容拒绝下载（§3.2，content / 不可重试 / 422）。"""

    def __init__(self, drm_system: DrmSystem | None):
        super().__init__("DRM_PROTECTED")
        self.drm_system = drm_system


def assert_transition(current: TaskStatus, nxt: TaskStatus) -> None:
    if nxt not in TRANSITIONS[current]:
        raise TaskStateConflictError()


# ---------------------------------------------------------------------------
# 内存存储 + 下载计数器
# ---------------------------------------------------------------------------


class DownloadCounter:
    """下载计数器：唯一递增点是 _do_download；测试断言 DRM 场景恒为 0。"""

    def __init__(self) -> None:
        self.count = 0

    def reset(self) -> None:
        self.count = 0


DOWNLOAD_COUNTER = DownloadCounter()


class TaskRecord:
    """任务运行态：Task 本体 + fixture 场景 + 幂等键（上次 download 的 selector）。"""

    def __init__(self, task: Task, scenario: str, sniff: SniffResult):
        self.task = task
        self.scenario = scenario  # "normal" | "drm"
        self.sniff = sniff
        self.last_selector_key: str | None = None


TASKS: dict[str, TaskRecord] = {}


def reset_state() -> None:
    """测试隔离：清空任务表与下载计数器（conftest 的 client fixture 调用）。"""
    TASKS.clear()
    DOWNLOAD_COUNTER.reset()


def _do_download(record: TaskRecord) -> None:
    """唯一的"真实下载"入口（M0 桩：仅推进状态与计数，无网络 IO）。

    调用链：POST /v1/tasks/{id}/download →（审计行 + DRM 判定之后）→ 本函数。
    """
    _transition(record.task, TaskStatus.DOWNLOADING)
    record.task.progress = Progress(
        phase="downloading",
        bytesDownloaded=0,
        bytesTotal=0,
        speedBps=0,
        etaSeconds=0,
        percent=0.0,
    )
    DOWNLOAD_COUNTER.count += 1


# ---------------------------------------------------------------------------
# 工具函数
# ---------------------------------------------------------------------------


def _uuid7() -> str:
    """UUIDv7（时间有序，§2.1）：time_ms<<80 | 随机，修正 version/variant 位。"""
    time_ms = int(time.time() * 1000)
    value = (time_ms << 80) | (random.getrandbits(12) << 66) | random.getrandbits(62)
    value = (value & ~(0xF << 76)) | (0x7 << 76)  # version 7
    value = (value & ~(0x3 << 62)) | (0x2 << 62)  # variant 10
    hex_str = f"{value:032x}"
    return f"{hex_str[0:8]}-{hex_str[8:12]}-{hex_str[12:16]}-{hex_str[16:20]}-{hex_str[20:32]}"


def _now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _transition(task: Task, nxt: TaskStatus) -> None:
    assert_transition(task.status, nxt)
    task.status = nxt
    task.updatedAt = _now()


def _get_record_or_404(task_id: str) -> TaskRecord:
    record = TASKS.get(task_id)
    if record is None:
        # 404 → NOT_FOUND（input，裁决新增；记入 M0 后 changelog）
        raise StarletteHTTPException(status_code=404)
    return record


def _error_response(status_code: int, payload: ErrorPayload) -> JSONResponse:
    """错误响应统一封装。exclude_none 使 §9.4 逐字匹配（无 detail 键）。"""
    return JSONResponse(
        status_code=status_code,
        content={"error": payload.model_dump(mode="json", exclude_none=True)},
        headers=PROTOCOL_HEADERS,
    )


def _task_response(task: Task, status_code: int = 200) -> JSONResponse:
    return JSONResponse(
        status_code=status_code,
        content=task.model_dump(mode="json"),
        headers=PROTOCOL_HEADERS,
    )


# ---------------------------------------------------------------------------
# Fixture（嗅探结果 / 格式列表）
# ---------------------------------------------------------------------------


def _scenario_for(source_url: str) -> str:
    """场景路由：sourceUrl 路径含 /drm → DRM fixture（避免依赖不存在的域名）。"""
    return "drm" if "/drm" in urlsplit(source_url).path else "normal"


def _make_sniff(task_id: str, scenario: str) -> SniffResult:
    """嗅探 fixture。headersHint 先经过 sanitize_headers 脱敏再返回（§1.6）。"""
    if scenario == "drm":
        return SniffResult(
            taskId=task_id,
            mediaUrl="https://cdn.example.com/drm/master.m3u8",
            type=MediaStreamType.HLS,
            container="m3u8",
            estimatedQuality=EstimatedQuality(height=1080, bitrateKbps=4500, label="1080p"),
            drmDetected=True,
            drmSystem=DrmSystem.WIDEVINE,
            headersHint={},
            discoveredAt=_now(),
        )
    return SniffResult(
        taskId=task_id,
        mediaUrl="https://cdn.example.com/hls/master.m3u8",
        type=MediaStreamType.HLS,
        container="m3u8",
        estimatedQuality=EstimatedQuality(height=1080, bitrateKbps=4500, label="1080p"),
        drmDetected=False,
        drmSystem=None,
        headersHint=sanitize_headers(
            {
                "referer": "https://example.com/",
                "user-agent": "Mozilla/5.0 (StubEngine)",
                "cookie": "sid=secret-value",
                "authorization": "Bearer stub-token",
            }
        ),
        discoveredAt=_now(),
    )


def _formats_for(task_id: str) -> list[FormatInfo]:
    """格式列表 fixture（§9.1 步骤 3 的 formatId "137+140" 在此）。"""
    return [
        FormatInfo(
            formatId="137+140",
            container="mp4",
            resolution=Resolution(width=1920, height=1080),
            bitrateKbps=4500,
            codec=Codec(video="avc1.640028", audio="mp4a.40.2"),
            hasAudio=True,
            hasVideo=True,
            fileSizeEstimateBytes=524288000,
            source=FormatSource.YT_DLP,
        ),
        FormatInfo(
            formatId="140",
            container="m4a",
            resolution=Resolution(width=0, height=0),
            bitrateKbps=128,
            codec=Codec(video="none", audio="mp4a.40.2"),
            hasAudio=True,
            hasVideo=False,
            fileSizeEstimateBytes=4194304,
            source=FormatSource.YT_DLP,
        ),
    ]


# ---------------------------------------------------------------------------
# 惰性状态推进
# ---------------------------------------------------------------------------


def _advance(record: TaskRecord) -> None:
    """GET 时按 §4 推进（桩惰性语义）。幂等：终态不动。

    - queued → sniffing；
    - sniffing → mode=sniff ? done
      : formatSelector 为空或 DRM ? awaiting_selection : downloading（自动领取）；
    - downloading → done（桩语义：下载瞬时完成）。
    """
    task = record.task
    if task.status == TaskStatus.QUEUED:
        _transition(task, TaskStatus.SNIFFING)
    if task.status == TaskStatus.SNIFFING:
        if task.mode == TaskMode.SNIFF:
            _transition(task, TaskStatus.DONE)
        elif task.formatSelector is None or record.sniff.drmDetected:
            _transition(task, TaskStatus.AWAITING_SELECTION)
        else:
            # 引擎自动领取下载：与 POST /download 同一入口语义（审计行 → _do_download），
            # 并登记幂等键（同 formatId 的后续 POST /download → 202 幂等，§2.4）
            logger.info("download_attempt task_id=%s", task.id)
            _do_download(record)
            record.last_selector_key = _selector_key(task.formatSelector)
    elif task.status == TaskStatus.DOWNLOADING:
        task.progress = Progress(
            phase="done",
            bytesDownloaded=524288000,
            bytesTotal=524288000,
            speedBps=0,
            etaSeconds=0,
            percent=100.0,
        )
        _transition(task, TaskStatus.DONE)


def _selector_key(selector: FormatSelector) -> str:
    """幂等键：selector 的规范化 JSON（字段定义序稳定）。"""
    return selector.model_dump_json()


# ---------------------------------------------------------------------------
# 应用与端点
# ---------------------------------------------------------------------------

app = FastAPI(title="BrowseGuard Vid Engine (stub)", version=ENGINE_VERSION)


@app.middleware("http")
async def protocol_version_middleware(request: Request, call_next):
    """所有响应（含错误）携带 X-Protocol-Version（§1 表头约定）。"""
    response = await call_next(request)
    response.headers.setdefault("X-Protocol-Version", PROTOCOL_VERSION)
    return response


@app.exception_handler(RequestValidationError)
async def validation_error_handler(request: Request, exc: RequestValidationError):
    """硬验收（用户裁决）：请求体/参数校验失败 → 422 VALIDATION_ERROR
    （category=input，retryable=false）。

    detail 只保留每项错误的 loc + type，不回显用户输入值（§1.7 脱敏；
    pydantic 原始 errors() 中的 input/msg/ctx 一律丢弃）。
    """
    errors = [
        {"loc": [str(part) for part in err.get("loc", [])], "type": err.get("type")}
        for err in exc.errors()
    ]
    payload = error_payload(ErrorCode.VALIDATION_ERROR, detail={"errors": errors})
    return _error_response(422, payload)


@app.exception_handler(SecurityError)
async def security_error_handler(request: Request, exc: SecurityError):
    payload = error_payload(ErrorCode(exc.code), detail=exc.detail)
    return _error_response(400, payload)


@app.exception_handler(DrmProtectedError)
async def drm_error_handler(request: Request, exc: DrmProtectedError):
    detail = {"drmSystem": exc.drm_system.value} if exc.drm_system else None
    return _error_response(422, error_payload(ErrorCode.DRM_PROTECTED, detail=detail))


@app.exception_handler(TaskStateConflictError)
async def task_state_conflict_handler(request: Request, exc: TaskStateConflictError):
    return _error_response(409, error_payload(ErrorCode.TASK_STATE_CONFLICT))


@app.exception_handler(StarletteHTTPException)
async def http_exception_handler(request: Request, exc: StarletteHTTPException):
    """404 → NOT_FOUND(input)；其余无协议错误码的场景用 UNKNOWN 兜底。"""
    if exc.status_code == 404:
        return _error_response(404, error_payload(ErrorCode.NOT_FOUND))
    return _error_response(exc.status_code, error_payload(ErrorCode.UNKNOWN))


@app.get("/v1/health")
def health():
    """§6.6。"""
    return JSONResponse(
        content={
            "status": "ok",
            "version": ENGINE_VERSION,
            "protocolVersion": PROTOCOL_VERSION,
            "uptimeSeconds": int(time.time() - START_TIME),
        },
        headers=PROTOCOL_HEADERS,
    )


@app.get("/v1/_debug/download-counter")
def debug_download_counter():
    """M0 桩自检端点（非协议 v0.1 内容，M1 移除）。

    只读暴露 DOWNLOAD_COUNTER 原值，供 smoke 直接读取「DRM 场景计数器为 0」
    （§9.3 / §10 的"引擎日志可证"之外的硬读数）。不参与任何协议流程。
    """
    return JSONResponse(
        content={"downloadCount": DOWNLOAD_COUNTER.count},
        headers=PROTOCOL_HEADERS,
    )


@app.post("/v1/tasks", status_code=201)
def create_task(req: TaskCreateRequest):
    """§6.2 创建任务。校验顺序：sourceUrl 出站校验 → outputDir 白名单校验。"""
    validate_outbound_url(req.sourceUrl)
    validate_output_dir(req.outputDir)
    task = Task(
        id=_uuid7(),
        sourceUrl=req.sourceUrl,
        mode=req.mode,
        formatSelector=req.formatSelector,
        namingTemplate=req.namingTemplate,
        postActions=req.postActions,
        proxyId=req.proxyId,
        cookiePolicy=req.cookiePolicy,
        outputDir=req.outputDir,
        status=TaskStatus.QUEUED,
        progress=None,
        error=None,
        createdAt=_now(),
        updatedAt=_now(),
    )
    record = TaskRecord(
        task=task,
        scenario=_scenario_for(req.sourceUrl),
        sniff=_make_sniff(task.id, _scenario_for(req.sourceUrl)),
    )
    TASKS[task.id] = record
    return _task_response(task, status_code=201)


@app.get("/v1/tasks")
def list_tasks(limit: int = Query(default=50, ge=1, le=200), cursor: str | None = None):
    """§2.5：limit 默认 50、上限 200（越界由 RequestValidationError handler → 422）。"""
    items = [record.task.model_dump(mode="json") for record in TASKS.values()]
    return JSONResponse(
        content={"items": items[:limit], "nextCursor": None},
        headers=PROTOCOL_HEADERS,
    )


@app.get("/v1/tasks/{task_id}")
def get_task(task_id: str):
    record = _get_record_or_404(task_id)
    _advance(record)
    return _task_response(record.task)


@app.get("/v1/tasks/{task_id}/sniff-results")
def list_sniff_results(task_id: str):
    record = _get_record_or_404(task_id)
    return JSONResponse(
        content={"items": [record.sniff.model_dump(mode="json")]},
        headers=PROTOCOL_HEADERS,
    )


@app.get("/v1/tasks/{task_id}/formats")
def list_formats(task_id: str):
    """§6.3：任务不在 awaiting_selection → 409 TASK_STATE_CONFLICT。"""
    record = _get_record_or_404(task_id)
    if record.task.status != TaskStatus.AWAITING_SELECTION:
        raise TaskStateConflictError()
    return JSONResponse(
        content={
            "taskId": task_id,
            "items": [f.model_dump(mode="json") for f in _formats_for(task_id)],
        },
        headers=PROTOCOL_HEADERS,
    )


@app.post("/v1/tasks/{task_id}/download")
def start_download(task_id: str, req: DownloadRequest):
    """§6.4 选择格式并开始下载（M0 桩：只推进状态机，无网络 IO）。

    顺序（硬要求）：
    1. 第一行打 download_attempt 审计日志（DRM 场景日志可证零下载）；
    2. 状态校验（终态 → 409；下载中同 selector → 202 幂等 / 不同 → 409；
       非 awaiting_selection → 409）；
    3. DRM 判定（审计行之后、下载逻辑之前，§3.2）；
    4. _do_download（唯一真实下载入口）。
    """
    logger.info("download_attempt task_id=%s", task_id)
    record = _get_record_or_404(task_id)
    task = record.task
    if task.status in TERMINAL_STATES:
        raise TaskStateConflictError()
    if task.status in DOWNLOAD_ACTIVE_STATES:
        if record.last_selector_key == _selector_key(req.formatSelector):
            return _task_response(task, status_code=202)  # 幂等（§2.4）
        raise TaskStateConflictError()
    if task.status != TaskStatus.AWAITING_SELECTION:
        raise TaskStateConflictError()
    # DRM 判定：审计行之后、下载逻辑之前
    if record.sniff.drmDetected:
        raise DrmProtectedError(record.sniff.drmSystem)
    # 应用选择器与可选覆盖（§6.4）
    task.formatSelector = req.formatSelector
    if req.namingTemplate is not None:
        task.namingTemplate = req.namingTemplate
    if req.postActions is not None:
        task.postActions = req.postActions
    _do_download(record)
    record.last_selector_key = _selector_key(req.formatSelector)
    return _task_response(task, status_code=202)


@app.post("/v1/tasks/{task_id}/cancel")
def cancel_task(task_id: str):
    """§4：任意非终态 ──取消──► cancelled（终态）。"""
    record = _get_record_or_404(task_id)
    if record.task.status in TERMINAL_STATES:
        raise TaskStateConflictError()
    _transition(record.task, TaskStatus.CANCELLED)
    return _task_response(record.task)


if __name__ == "__main__":
    import uvicorn

    # 控制台可见 INFO 级审计日志（download_attempt 等，§10 验收"引擎日志可证"）
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(name)s %(levelname)s %(message)s",
    )
    # 协议默认监听 127.0.0.1:8730（§1 表头；本地回环免令牌 §2.3）
    uvicorn.run(app, host="127.0.0.1", port=8730)

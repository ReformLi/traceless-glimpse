"""Traceless Glimpse 本地引擎（协议 §6 P0 端点 + §9 四示例）。

两种模式（`BGV_ENGINE_MODE`，默认 stub）：
- stub：嗅探 / 格式列表来自 fixture（sourceUrl 路径含 /drm → DRM 场景）；
  "下载"只推进状态机并计数（_do_download），不发出任何网络请求；
- real：Playwright 嗅探 + yt-dlp 真实解析（formats_from_info / estimate_quality）
  + download_media 真实下载；所有出站 URL 走统一 SSRF 校验器。

DRM 任务在 download 入口、审计行之后、下载逻辑之前短路返回
422 DRM_PROTECTED（§3.2：不得尝试下载）。

状态惰性推进：
- GET 任务详情时 queued→sniffing→（mode=sniff ? done
  : formatSelector 为空或 DRM ? awaiting_selection : downloading）；
  real 模式下载同步执行并直达 done / failed，stub 模式 downloading→done。
- 带 formatSelector 的 download 任务按 §4 由引擎自动领取下载（审计行 +
  _do_download，与 POST /download 同一执行链）；DRM 任务不自动下载（§3.2），
  停 awaiting_selection 等 POST /download 返回 422 DRM_PROTECTED。
- awaiting_selection 的 24h 超时 M0 不实现（M1 补，见 engine/README.md）。
"""
from __future__ import annotations

import asyncio
import ipaddress
import logging
import os
import random
import re
import sys
import threading
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
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
    PostActionType,
    Progress,
    Resolution,
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
from log_redaction import install_log_redaction
from persistence import get_store
from services import downloader as downloader_service
from services import environment as environment_service
from services import failures as failures_service
from services import formats as formats_service
from services import merger as merger_service
from services.task_worker import TaskWorker

logger = logging.getLogger("bgv.engine")

PROTOCOL_VERSION = "0.2"
ENGINE_VERSION = "0.1.0"

# 引擎模式开关（M1）：stub = M0 桩（fixture + GET 惰性推进）；real = 真实嗅探链路。
# 默认 stub，保证既有 56 条契约用例零改动；真实链路用例显式 -m real 运行。
ENGINE_MODE = os.getenv("BGV_ENGINE_MODE", "stub").strip().lower()
if ENGINE_MODE not in {"stub", "real"}:
    raise RuntimeError(f"BGV_ENGINE_MODE 取值非法：{ENGINE_MODE!r}（仅 stub / real）")
DEBUG_ENDPOINTS_ENABLED = os.getenv("BGV_ENABLE_DEBUG_ENDPOINTS", "").strip().lower() in {
    "1",
    "true",
    "yes",
}
PROTOCOL_HEADERS = {"X-Protocol-Version": PROTOCOL_VERSION}
START_TIME = time.time()

def _load_output_dir_allowlist() -> list[str]:
    """BGV_OUTPUT_DIR_ALLOWLIST（os.pathsep 分隔）；默认 %USERPROFILE%\\Videos\\BGV。

    同时保留协议 §6.5 示例路径，保证既有契约用例（/home/user/Videos/BGV）可用。
    """
    raw = os.getenv("BGV_OUTPUT_DIR_ALLOWLIST", "").strip()
    if raw:
        return [item for item in raw.split(os.pathsep) if item]
    return [
        os.path.expanduser(os.path.join("~", "Videos", "BGV")),
        "/home/user/Videos/BGV",
    ]


OUTPUT_DIR_ALLOWLIST = _load_output_dir_allowlist()

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

    def __init__(self, task: Task, scenario: str, sniff: SniffResult | None):
        self.task = task
        self.scenario = scenario  # "normal" | "drm"
        self.sniff = sniff  # real 模式在首次 GET 时才由 Playwright 产出
        self.sniffed = sniff is not None
        self.formats: list[FormatInfo] | None = None  # real 模式 yt-dlp 格式表
        self.last_selector_key: str | None = None
        # 协作式断流：cancel 端点 set，下载循环每写一块之前查一次
        self.cancel_requested = threading.Event()


TASKS: dict[str, TaskRecord] = {}

# ---------------------------------------------------------------------------
# M22：联机登记（桌面端「运行状态」卡的数据源）
# ---------------------------------------------------------------------------
# **只在内存、只给桌面端看**。刻意不写进 `Task` 模型、也不塞进 `/v1/health`：
# 手机端 `BgvJson` 是严格模式，响应体多一个未知字段，整条 JSON 就解析失败，
# 心跳会当场变红——「加一个字段看看」在这个项目里是破坏性改动。
PEERS: dict[str, float] = {}
TASK_ORIGIN: dict[str, str] = {}
# 心跳间隔 5s、连续 2 次失败才判离线；15s 窗口正好容下一次重试，不会把
# 短暂抖动报成「设备断开」。
PEER_WINDOW_SECONDS = 15.0
# 来源表按任务量裁剪，长跑进程不无限长内存。
TASK_ORIGIN_LIMIT = 200


def _client_address(request: Request) -> str:
    """对端地址。取不到就返回空串（当作「来源未知」）。"""
    client = getattr(request, "client", None)
    return (client.host if client else "") or ""


def _is_loopback(address: str) -> bool:
    """本机回环：桌面端自己每秒轮询 health，不能把自己算成「一台设备」。"""
    if not address:
        return True
    try:
        return ipaddress.ip_address(address).is_loopback
    except ValueError:
        return address in {"localhost", "::1"}


def _record_peer(address: str) -> None:
    """登记一次心跳。回环与非 IP 地址不入表：设备数只数「外部连进来的」。

    非 IP（例如测试客户端的 ``testclient``）一律不登记——宁可少报一台设备，
    也不要在设备列表里写一行用户看不懂的字符串。
    """
    if _is_loopback(address):
        return
    try:
        ipaddress.ip_address(address)
    except ValueError:
        return
    PEERS[address] = time.time()


def _remember_origin(task_id: str, address: str) -> None:
    """记下任务是哪台设备建的（下载控制台的任务行靠它区分手机端/本机）。"""
    TASK_ORIGIN[task_id] = address or ""
    if len(TASK_ORIGIN) > TASK_ORIGIN_LIMIT:
        keep = {record.task.id for record in TASKS.values()}
        for key in [k for k in TASK_ORIGIN if k not in keep][: len(TASK_ORIGIN) - TASK_ORIGIN_LIMIT]:
            TASK_ORIGIN.pop(key, None)


def reset_state() -> None:
    """测试隔离：清任务表 / 计数器 / 持久化任务行 / 联机登记。"""
    TASKS.clear()
    DOWNLOAD_COUNTER.reset()
    PEERS.clear()
    TASK_ORIGIN.clear()
    get_store().clear()


def _begin_download(record: TaskRecord) -> None:
    """进入 downloading：状态转移 + 初始进度 + 计数器（唯一下载计数点）+ 后处理动作审计。"""
    _transition(record.task, TaskStatus.DOWNLOADING)
    _audit_post_actions(record.task)
    record.task.progress = Progress(
        phase="downloading",
        bytesDownloaded=0,
        bytesTotal=0,
        speedBps=0,
        etaSeconds=0,
        percent=0.0,
    )
    DOWNLOAD_COUNTER.count += 1


def _make_progress_recorder(task: Task):
    """返回 ``on_progress(downloaded, total_or_None)``：把真实写入字节写进任务进度。

    速度与 ETA 用 0.5s 滚动窗口算，既避免每块都改对象，也不会因单块抖动
    给出忽高忽低的读数；``total`` 未知（直链无 content-length / HLS 未汇总）
    时 ``bytesTotal`` 与 ``percent`` 保持 0，由客户端按不确定进度显示。
    """
    state = {"at": time.monotonic(), "bytes": 0, "speed": 0.0}

    def _on_progress(downloaded: int, total: int | None) -> None:
        now = time.monotonic()
        window = now - state["at"]
        if window >= 0.5:
            instant = max(0, downloaded - state["bytes"]) / window
            state["speed"] = (
                instant if state["speed"] <= 0 else 0.5 * state["speed"] + 0.5 * instant
            )
            state["at"], state["bytes"] = now, downloaded
        remaining = (total - downloaded) if total and total > downloaded else 0
        task.progress = Progress(
            phase="downloading",
            bytesDownloaded=downloaded,
            bytesTotal=total or 0,
            speedBps=int(state["speed"]),
            etaSeconds=int(remaining / state["speed"]) if remaining and state["speed"] > 0 else 0,
            percent=round(min(downloaded / total * 100.0, 100.0), 1) if total else 0.0,
        )

    return _on_progress


def _do_download(record: TaskRecord) -> None:
    """下载入口（同步执行）：stub 只推进状态；real 调 ``download_media``。

    阶段 4 语义：GET 自动领取与 POST /download 均在本线程内阻塞执行。
    """
    _begin_download(record)
    if ENGINE_MODE == "real":
        _download_safely(record)


def _audit_post_actions(task: Task) -> None:
    """M1 只实现 ``merge``：其余白名单动作收到即记审计行，不收进「静默丢弃」。

    挂在「进入 downloading」这个唯一入口上（``_begin_download``），桩与 real、
    自动领取与 ``POST /download`` 四条路都过它。没有这行日志，「引擎到底做没做」
    在日志层面就无从回答（§19.3 缺口 1 的判定依据）。
    """
    others = [
        action.type.value for action in task.postActions if action.type != PostActionType.MERGE
    ]
    if others:
        logger.info("post_action_unsupported task_id=%s types=%s", task.id, ",".join(others))


def _merge_requested(task: Task) -> bool:
    return any(action.type == PostActionType.MERGE for action in task.postActions)


def _finish_stub_download(task: Task) -> None:
    """桩语义的下载完成：写死 fixture 字节数，只为让状态机走完，不代表真产物。"""
    task.progress = Progress(
        phase="done",
        bytesDownloaded=524288000,
        bytesTotal=524288000,
        speedBps=0,
        etaSeconds=0,
        percent=100.0,
    )
    _transition(task, TaskStatus.DONE)


_FORMAT_UNAVAILABLE_MESSAGES = {
    "exact_format_id_unsupported": "M1 暂不支持精确 formatId",
    "max_height_required": "max_height 策略必须提供 maxHeight",
    "no_height_within_cap": "上限内没有可用画质",
}


def _format_unavailable_detail(reason: str) -> dict[str, Any]:
    """FORMAT_UNAVAILABLE 的脱敏 detail：reason（机器可读）+ message（人可读）。"""
    detail: dict[str, Any] = {"reason": reason}
    message = _FORMAT_UNAVAILABLE_MESSAGES.get(reason)
    if message:
        detail["message"] = message
    return detail


def _format_no_match_detail(exc: Any) -> dict[str, Any]:
    """无匹配时额外带 requested / available，让客户端能解释「为什么这条选不了」。"""
    return {**exc.detail, "message": _FORMAT_UNAVAILABLE_MESSAGES[exc.detail["reason"]]}


def _merge_plan(
    record: TaskRecord, format_expression: str
) -> formats_service.TrackExpressions | None:
    """本次下载是否走「分轨下载 + 引擎侧合并」；每个跳过理由当场记审计行。

    §3.7 把 ``merge`` 定义成「合并分离音视频轨」，所以只有源里**确实**有两条单轨时
    才谈得上合并。其余情况（直链单文件、纯音频选择器、源里没有单轨变体）合并无从下手，
    但客户端的开关确实被按下了——不能静默吞掉，必须留一行可 grep 的 ``post_action_noop``
    （同 4b-2 ``selector_degraded`` 的裁决口径：降级可以，无痕不行）。
    """
    task = record.task
    if not _merge_requested(task):
        return None
    if record.sniff is None:
        return None  # 上游已按 NO_MEDIA_FOUND 落 failed
    if record.sniff.type in {MediaStreamType.MP4, MediaStreamType.AUDIO}:
        logger.info(
            "post_action_noop task_id=%s type=merge reason=single_file_source", task.id
        )
        return None
    tracks = formats_service.track_expressions(format_expression)
    if tracks is None:
        logger.info(
            "post_action_noop task_id=%s type=merge reason=selector_not_splittable", task.id
        )
        return None
    if not formats_service.has_separate_tracks(record.formats):
        logger.info(
            "post_action_noop task_id=%s type=merge reason=no_separate_tracks", task.id
        )
        return None
    return tracks


def _merge_stage(record: TaskRecord, video: Path, audio: Path) -> Path | None:
    """``downloading → merging`` + 真实 FFmpeg 合并；返回产物路径或 ``None``。

    ``None`` 表示这次下载已经落 ``failed``（合并失败）或已被取消，调用方不得再置 ``done``。
    合并失败只删**这次自己造出来的**文件：目标名如果合并前就在（上一轮成功产物同名），
    留着不删——失败归失败，不许顺手销毁用户已有的文件。
    """
    task = record.task
    target = downloader_service.merged_target_for(video)
    target_existed = target.exists()
    _transition(task, TaskStatus.MERGING)
    task.progress = Progress(
        phase="merging",
        bytesDownloaded=task.progress.bytesDownloaded if task.progress else 0,
        bytesTotal=task.progress.bytesTotal if task.progress else 0,
        speedBps=0,
        etaSeconds=0,
        percent=100.0,
    )
    try:
        merged = merger_service.merge_streams(video, audio, target)
    except merger_service.MergeError as exc:
        logger.warning("merge_failed task_id=%s reason=%s", task.id, exc)
        downloader_service.drop_track_files([video, audio])
        if not target_existed:
            target.unlink(missing_ok=True)
        _fail_task(task, ErrorCode.MERGE_FAILED, {"reason": "merge_failed"})
        return None
    downloader_service.drop_track_files([video, audio])
    if record.cancel_requested.is_set() or task.status is TaskStatus.CANCELLED:
        # 合并期间点取消：产物已经合好了，保留文件但状态归 cancelled，不改判 done
        logger.info("merge_cancelled task_id=%s bytes=%d", task.id, merged.stat().st_size)
        return None
    return merged


def _download_real(record: TaskRecord) -> None:
    """real 模式下载：统一校验器 → yt-dlp（download_media）→ 失败置 FAILED。

    worker 线程执行；取消由 API 线程直接置 cancelled，本函数在入口与收尾
    检查终态，避免取消后覆盖状态。
    """
    task = record.task
    if record.cancel_requested.is_set() or task.status is TaskStatus.CANCELLED:
        # 取消可能落在 202 响应之后、worker 起跑之前：不碰状态，只留证
        logger.info("download_skipped_cancelled task_id=%s", task.id)
        return
    if record.sniff is None:
        _fail_task(task, ErrorCode.NO_MEDIA_FOUND)
        return
    try:
        resolved = formats_service.resolve_format_expression(task.formatSelector, record.formats)
    except formats_service.FormatUnavailableError as exc:
        _fail_task(
            task,
            ErrorCode.FORMAT_UNAVAILABLE,
            _format_unavailable_detail(exc.reason),
        )
        return
    except formats_service.FormatNoMatchError as exc:
        _fail_task(task, ErrorCode.FORMAT_UNAVAILABLE, _format_no_match_detail(exc))
        return
    if resolved.degraded_reason:
        # 上限判不了 ≠ 任务失败：降级 best 跑完，但必须留下可 grep 的审计行
        logger.warning(
            "selector_degraded task_id=%s reason=%s requested=%s",
            task.id,
            resolved.degraded_reason,
            task.formatSelector.maxHeight if task.formatSelector else None,
        )
    format_expression = resolved.expression
    # 协作式断流 + 真实进度：两条下载循环各自在写块之前查标志
    should_abort = record.cancel_requested.is_set
    on_progress = _make_progress_recorder(task)
    merge_plan = _merge_plan(record, format_expression)
    # 产物主名（M24）：客户端填了就用它，留空沿用站点标题。后缀一律由下载器补——
    # 客户端不许自己猜容器，否则「我的电影.mp4」会被拼成「我的电影.mp4.mp4」。
    output_name = downloader_service.normalize_output_name(
        task.namingTemplate, task_id=task.id
    )
    tracks: tuple[Path, Path] | None = None
    try:
        if merge_plan is not None:
            # 客户端点了合并：先拿两条单轨，再由 _merge_stage 真的调 FFmpeg
            tracks = downloader_service.download_tracks(
                record.sniff.mediaUrl,
                task.outputDir,
                video_expression=merge_plan.video,
                audio_expression=merge_plan.audio,
                task_id=task.id,
                cookie_policy=task.cookiePolicy.value,
                should_abort=should_abort,
                on_progress=on_progress,
                output_name=output_name,
            )
        elif record.sniff.type in {MediaStreamType.MP4, MediaStreamType.AUDIO}:
            # 单文件直链走 download_direct；HLS/DASH 交给 yt-dlp
            path = downloader_service.download_direct(
                record.sniff.mediaUrl,
                task.outputDir,
                task_id=task.id,
                cookie_policy=task.cookiePolicy.value,
                should_abort=should_abort,
                on_progress=on_progress,
                output_name=output_name,
                # 直链终点没有后缀时按嗅探到的介质类型兜底；音频源绝不落成 .mp4。
                default_suffix=(
                    ".m4a" if record.sniff.type is MediaStreamType.AUDIO else ".mp4"
                ),
            )
        else:
            path = downloader_service.download_media(
                record.sniff.mediaUrl,
                task.outputDir,
                task_id=task.id,
                format_expression=format_expression,
                cookie_policy=task.cookiePolicy.value,
                should_abort=should_abort,
                on_progress=on_progress,
                output_name=output_name,
            )
    except downloader_service.DownloadCancelled as exc:
        # 断流不改状态：cancel 端点已置 cancelled，这里只留可 grep 的字节证据
        logger.info("download_aborted task_id=%s detail=%s", task.id, exc)
        return
    except SecurityError as exc:
        logger.warning("download_blocked task_id=%s code=%s", task.id, exc.code)
        _fail_task(task, ErrorCode(exc.code), exc.detail)
        return
    except downloader_service.DownloadIntegrityError as exc:
        # 「产物不完整」是这类异常的默认语义，但里面也混着 HTTP 状态（download_direct
        # 对非 200/206 直接抛 `HTTP 403` 这种），归因表先分清再兜 PARTIAL_DOWNLOAD
        code, detail = failures_service.classify_download_failure(
            exc, fallback=ErrorCode.PARTIAL_DOWNLOAD
        )
        logger.warning(
            "download_integrity_failed task_id=%s code=%s type=%s",
            task.id,
            code.value,
            type(exc).__name__,
        )
        _fail_task(task, code, detail)
        return
    except Exception as exc:  # noqa: BLE001 —— 下载失败不得让 API 500
        code, detail = failures_service.classify_download_failure(exc)
        logger.warning(
            "download_failed task_id=%s code=%s type=%s",
            task.id,
            code.value,
            type(exc).__name__,
        )
        _fail_task(task, code, detail)
        return
    if record.cancel_requested.is_set() or task.status is TaskStatus.CANCELLED:
        # 收尾竞态：下载刚好在取消前一瞬完成——终态已是 cancelled，不覆盖成 done
        logger.info("download_cancelled task_id=%s", task.id)
        return
    if tracks is not None:
        path = _merge_stage(record, tracks[0], tracks[1])
        if path is None:
            return  # 合并失败已落 failed；合并期间被取消则终态归 cancelled
    # 产物路径只在此处落任务：done 之前一律 null，失败/取消不会带出半成品路径。
    task.outputPath = str(path)
    task.progress = Progress(
        phase="done",
        bytesDownloaded=path.stat().st_size,
        bytesTotal=path.stat().st_size,
        speedBps=0,
        etaSeconds=0,
        percent=100.0,
    )
    _transition(task, TaskStatus.DONE)


def _fail_task(
    task: Task,
    code: ErrorCode,
    detail: dict[str, Any] | None = None,
    *,
    message: str | None = None,
) -> None:
    if task.status in TERMINAL_STATES:
        return  # 取消与失败竞态：终态优先，不覆盖
    task.error = error_payload(code, detail=detail, message=message)
    _transition(task, TaskStatus.FAILED)


def _advance_safely(record: TaskRecord) -> None:
    """状态推进的兜底出口：逃逸异常必须落 failed（§8-12 审计的不变式）。

    `TaskWorker._run` 对异常只记日志不改状态，`get_task` 的同步兜底更是会抛成 500——
    两条路都让任务永久停在 `sniffing`，既不失败也不完成。逐个调用点补 try 靠人记得，
    收尾③ 的 `sniffing` P0 已经证明记不住；兜底放在推进入口这一层。
    """
    try:
        _advance(record)
    except Exception as exc:  # noqa: BLE001 —— 非终态不得逃逸，否则任务永久停滞
        logger.exception(
            "advance_crashed task_id=%s type=%s", record.task.id, type(exc).__name__
        )
        _fail_task(record.task, ErrorCode.UNKNOWN, {"reason": "advance_crashed"})


def _download_safely(record: TaskRecord) -> None:
    """下载入口的兜底出口，理由同 `_advance_safely`。

    `_download_real` 自己已经把下载异常收成 `UNKNOWN` / `PARTIAL_DOWNLOAD`，
    这里补的是它**外面**那圈：解析器非契约异常、进度写回、终态转移自身崩掉。
    """
    try:
        _download_real(record)
    except Exception as exc:  # noqa: BLE001 —— 非终态不得逃逸，否则任务永久停滞
        logger.exception(
            "download_crashed task_id=%s type=%s", record.task.id, type(exc).__name__
        )
        _fail_task(record.task, ErrorCode.UNKNOWN, {"reason": "download_crashed"})


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
    get_store().upsert(task)


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
    - sniffing → （real 模式：先做 Playwright 嗅探 + yt-dlp 格式解析）
      mode=sniff ? done : formatSelector 为空或 DRM ? awaiting_selection
      : downloading（自动领取）；
    - downloading → done（stub 语义：下载瞬时完成；点了 merge 则先走 merging 一站再 done，
      与 §4 的转移表一致；real 模式由 _download_real / _merge_stage 直达 done / failed）。

    real 模式下嗅探与下载在本线程内**阻塞**执行（阶段 4 既有偏差）。
    """
    task = record.task
    if task.status == TaskStatus.QUEUED:
        _transition(task, TaskStatus.SNIFFING)
    if task.status == TaskStatus.SNIFFING and not record.sniffed:
        failure = _sniff_real(record)
        record.sniffed = True
        if failure is not None:
            code, detail, message = failure
            _fail_task(task, code, detail, message=message)
            return
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
    elif task.status == TaskStatus.DOWNLOADING and ENGINE_MODE == "stub":
        if _merge_requested(task):
            # 桩也走 §4 的 merging 一站：客户端「合并音视频中」的文案在桩下得能出现，
            # 否则那条屏显分支只有 real 联调时才看得见（本轮审计正是被这类死分支咬过）
            _transition(task, TaskStatus.MERGING)
        else:
            _finish_stub_download(task)
    elif task.status == TaskStatus.MERGING and ENGINE_MODE == "stub":
        _finish_stub_download(task)
    elif task.status in {TaskStatus.DOWNLOADING, TaskStatus.MERGING}:
        # real 模式这两个态归 _download_real / _merge_stage 所有：推进只会伪造 done 与字节数
        logger.info(
            "advance_skipped_downloading task_id=%s status=%s", task.id, task.status.value
        )


def _selector_key(selector: FormatSelector) -> str:
    """幂等键：selector 的规范化 JSON（字段定义序稳定）。"""
    return selector.model_dump_json()


_CHROMIUM_NET_RE = re.compile(r"net::(ERR_[A-Z0-9_]+)")


def _sniff_failure(exc: Exception) -> tuple[ErrorCode, dict[str, Any], str | None]:
    """嗅探异常 → (错误码, detail, 文案覆写)。

    M21：**连接层被掐断**（`ERR_CONNECTION_RESET` 一类）不再冒充 `EXTRACTOR_FAILED`
    （「解析失败，可能站点已变更」）——页面根本没加载，站点也没变。这类归
    `NETWORK_HTTP_ERROR`（category=network / retryable=true 都合适），文案换成
    「可能被网络阻断，可配置代理」；DNS / 超时 / TLS 各自归既有码，用表内文案。

    detail 只带**错误码值**，不带异常原文：原文里有 URL 与查询串（§1.7 脱敏口径）。
    返回的第三个元素是 message 覆写，None 表示用 §5 表内文案。
    """
    code, _ = failures_service.classify_download_failure(
        exc, fallback=ErrorCode.EXTRACTOR_FAILED
    )
    detail: dict[str, Any] = {"reason": "sniff_failed"}
    match = _CHROMIUM_NET_RE.search(str(exc))
    if match:
        detail["net"] = match.group(1)
    message: str | None = None
    if code is ErrorCode.NETWORK_HTTP_ERROR:
        net_code = str(detail.get("net", ""))
        # 代理自己不通 ≠ 站点被阻断：说清是谁的锅，用户才知道该改哪里。
        message = (
            failures_service.PROXY_UNREACHABLE_MESSAGE
            if net_code.startswith(("ERR_PROXY_", "ERR_TUNNEL_"))
            else failures_service.CONNECTION_BLOCKED_MESSAGE
        )
    return code, detail, message


def _sniff_real(record: TaskRecord) -> tuple[ErrorCode, dict[str, Any], str | None] | None:
    """real 模式嗅探 + yt-dlp 格式解析：结果写回 record.sniff / record.formats。

    成功返回 None；失败返回 (错误码, detail, 文案覆写)，由调用方置 failed。
    嗅探异常按 [services.failures] 归因（M21：连接被掐断 → NETWORK_HTTP_ERROR），
    页面无媒体流 → NO_MEDIA_FOUND；两者的 detail.reason 都是 sniff_failed。
    """
    from services.sniffer import SniffError, get_sniffer

    task = record.task
    try:
        observed = get_sniffer().sniff(
            task.id, task.sourceUrl, task.cookiePolicy.value
        )
    except SniffError as exc:
        code, detail, message = _sniff_failure(exc)
        # 只打类型与网络码：异常原文带 URL，不进日志（§1.7）
        logger.warning(
            "sniff_failed task_id=%s type=%s net=%s code=%s",
            task.id,
            type(exc).__name__,
            detail.get("net", "-"),
            code.value,
        )
        record.sniff = None
        return code, detail, message
    if observed is None:
        logger.warning("sniff_no_media task_id=%s", task.id)
        record.sniff = None
        return ErrorCode.NO_MEDIA_FOUND, {"reason": "sniff_failed"}, None
    # DRM 判定（§3.2）：播放列表按 keyformat 三态判定；取不到 manifest（如回环媒体
    # 被 SSRF 拦截）时保持 false，并记 sniff_manifest_blocked 审计行（只带 task_id）
    drm_detected, drm_system_name = False, None
    if observed.container == "m3u8":
        try:
            drm_detected, drm_system_name = formats_service.detect_drm(
                formats_service.fetch_manifest_text(observed.url, task_id=task.id)
            )
        except SecurityError:
            logger.warning("sniff_manifest_blocked task_id=%s", task.id)
        except Exception as exc:  # noqa: BLE001 —— 网络/解析异常不改变嗅探结果
            logger.warning(
                "sniff_manifest_failed task_id=%s type=%s", task.id, type(exc).__name__
            )

    # 格式表：出站 URL 先过统一校验器，再交给 yt-dlp 解析（硬约束 ⑤）
    info: dict[str, Any] | None = None
    try:
        validate_outbound_url(observed.url)
        info = formats_service.extract_info(observed.url)
        record.formats = formats_service.formats_from_info(task.id, info)
    except SecurityError:
        logger.warning("formats_extract_blocked task_id=%s", task.id)
        record.formats = []
    except Exception as exc:  # noqa: BLE001 —— 解析失败不阻塞嗅探结果
        logger.warning(
            "formats_extract_failed task_id=%s type=%s", task.id, type(exc).__name__
        )
        record.formats = []

    quality = (
        formats_service.estimate_quality(info)
        if info is not None
        else EstimatedQuality(height=0, bitrateKbps=0, label="unknown")
    )
    record.sniff = SniffResult(
        taskId=task.id,
        mediaUrl=observed.url,
        type=MediaStreamType(observed.kind),
        container=observed.container,
        estimatedQuality=quality,
        drmDetected=drm_detected,
        drmSystem=DrmSystem(drm_system_name) if drm_system_name else None,
        headersHint=observed.headers_hint,
        discoveredAt=_now(),
    )
    return None


# ---------------------------------------------------------------------------
# 后台 worker 与启动恢复（阶段 5 第 2 步）
# ---------------------------------------------------------------------------


async def _process_task(task_id: str) -> None:
    """worker 入口：嗅探/下载为阻塞调用，下沉到线程，避免阻塞事件循环。"""
    record = TASKS.get(task_id)
    if record is None or ENGINE_MODE != "real":
        return
    task = record.task
    if task.status in TERMINAL_STATES:
        return
    if task.status == TaskStatus.DOWNLOADING:
        if record.sniff is None:
            _fail_task(task, ErrorCode.UNKNOWN, {"reason": "missing_sniff"})
            return
        await asyncio.to_thread(_download_safely, record)
        return
    await asyncio.to_thread(_advance_safely, record)


DEFAULT_AWAITING_SELECTION_TIMEOUT_SECONDS = 24 * 60 * 60


def _awaiting_selection_timeout_seconds() -> float:
    """§4：awaiting_selection 超时阈值，默认 24h；env 可覆盖（测试用 1s）。"""
    raw = os.getenv("BGV_AWAITING_SELECTION_TIMEOUT_SECONDS", "").strip()
    if not raw:
        return float(DEFAULT_AWAITING_SELECTION_TIMEOUT_SECONDS)
    try:
        value = float(raw)
    except ValueError:
        logger.warning("invalid_awaiting_selection_timeout ignored")
        return float(DEFAULT_AWAITING_SELECTION_TIMEOUT_SECONDS)
    return value if value > 0 else float(DEFAULT_AWAITING_SELECTION_TIMEOUT_SECONDS)


def _parse_timestamp(value: str) -> float:
    try:
        return (
            datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ")
            .replace(tzinfo=timezone.utc)
            .timestamp()
        )
    except ValueError:
        return 0.0


def _sweep_awaiting_selection() -> None:
    """worker 周期扫描：awaiting_selection 超过阈值 → failed + UNKNOWN。"""
    cutoff = time.time() - _awaiting_selection_timeout_seconds()
    for record in list(TASKS.values()):
        task = record.task
        if task.status is not TaskStatus.AWAITING_SELECTION:
            continue
        if _parse_timestamp(task.updatedAt) > cutoff:
            continue
        logger.info("awaiting_selection_timeout task_id=%s", task.id)
        _fail_task(
            task, ErrorCode.UNKNOWN, {"reason": "awaiting_selection_timeout"}
        )


WORKER = TaskWorker(_process_task, sweep=_sweep_awaiting_selection)


def _submit_if_queued(record: TaskRecord) -> bool:
    """real 模式非阻塞推进：queued 交给 worker；worker 未启动返回 False。"""
    if record.task.status != TaskStatus.QUEUED:
        return True
    if not WORKER.started:
        return False
    WORKER.submit(record.task.id)
    return True


def _restore_state() -> None:
    """启动恢复：从 SQLite 载回任务；活动态由 recover_restart 置 failed。"""
    TASKS.clear()
    for task in get_store().recover_restart():
        scenario = _scenario_for(task.sourceUrl)
        sniff = _make_sniff(task.id, scenario) if ENGINE_MODE == "stub" else None
        TASKS[task.id] = TaskRecord(task, scenario, sniff)


# ---------------------------------------------------------------------------
# 应用与端点
# ---------------------------------------------------------------------------

def install_console_logging(stream=None) -> logging.Handler:
    """让 INFO 级审计行与启动方式无关，返回新挂（或复用）的 handler。

    ``-m uvicorn main:app`` 是 README 与验收指南里的入口，而 uvicorn 只配置
    自己的三个 logger，根上没有 handler 时 ``bgv.engine`` 的 INFO 记录会被
    ``logging.lastResort``（WARNING 级）静默丢掉——验收要的正面证据
    （``http_request`` / ``download_attempt``）一行都落不下来。

    级别只提 ``bgv.engine``，不动根：根提到 INFO 会放出 httpx 的
    ``HTTP Request: GET <源站 URL>``，把「引擎日志不带源 URL」的既有硬承诺
    （tests_real 三条钉着）当场打穿。
    """
    logging.getLogger("bgv.engine").setLevel(logging.INFO)
    root = logging.getLogger()
    for handler in root.handlers:
        if type(handler) is logging.StreamHandler:
            return handler
    handler = logging.StreamHandler(stream if stream is not None else sys.stderr)
    handler.setFormatter(
        logging.Formatter("%(asctime)s %(name)s %(levelname)s %(message)s")
    )
    root.addHandler(handler)
    return handler


app = FastAPI(title="Traceless Glimpse Engine", version=ENGINE_VERSION)


@app.on_event("startup")
async def _startup() -> None:
    install_console_logging()
    install_log_redaction()
    _restore_state()
    await WORKER.start()
    if ENGINE_MODE == "real":
        for record in list(TASKS.values()):
            if record.task.status == TaskStatus.QUEUED:
                WORKER.submit(record.task.id)


@app.on_event("shutdown")
async def _shutdown() -> None:
    await WORKER.stop()


@app.middleware("http")
async def protocol_version_middleware(request: Request, call_next):
    """所有响应（含错误）携带 X-Protocol-Version（§1 表头约定）。"""
    response = await call_next(request)
    response.headers.setdefault("X-Protocol-Version", PROTOCOL_VERSION)
    return response


@app.middleware("http")
async def cookie_audit_middleware(request: Request, call_next):
    """每个请求留一行 Cookie 存在性审计（M6-5 D4），值永不进日志。

    协议 §1 规定客户端不该发 Cookie，但「不该」不等于「没有」：这条行是
    可 grep 的正面证据（`cookie=present` 计数为 0），而不是靠「日志里没有」推断。
    只打方法/路径/存在性，路径也刻意丢掉查询串——query 里可能带 token。
    """
    logger.info(
        "http_request method=%s path=%s cookie=%s set_cookie=%s",
        request.method,
        request.url.path,
        "present" if "cookie" in request.headers else "absent",
        "present" if "set-cookie" in request.headers else "absent",
    )
    return await call_next(request)


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
def health(request: Request):
    """§6.6。

    **响应体一个字段都不能加**：手机端 `BgvJson` 严格模式下多一个未知字段就会
    整条解析失败，心跳直接变红。联机设备另开只读端点 `/v1/peers`。
    """
    # 心跳同时兼任「设备在线登记」：手机端每 5s 打一次，桌面端据此数设备。
    _record_peer(_client_address(request))
    return JSONResponse(
        content={
            "status": "ok",
            "version": ENGINE_VERSION,
            "protocolVersion": PROTOCOL_VERSION,
            "uptimeSeconds": int(time.time() - START_TIME),
        },
        headers=PROTOCOL_HEADERS,
    )


@app.get("/v1/peers")
def list_peers():
    """M22：桌面端「运行状态」卡的唯一数据源（联机设备 + 最近任务来源）。

    新端点而不是扩既有响应，理由见 :func:`health`。返回的 ``sourceHost`` 只有域名，
    不带路径与查询串——与引擎日志的脱敏口径一致。
    """
    now = time.time()
    seen = [(addr, stamp) for addr, stamp in PEERS.items() if now - stamp <= PEER_WINDOW_SECONDS]
    seen.sort(key=lambda item: item[1], reverse=True)
    peers = [
        {"address": addr, "lastSeenSecondsAgo": int(now - stamp)} for addr, stamp in seen
    ]

    recent = sorted(TASKS.values(), key=lambda record: record.task.createdAt, reverse=True)[:10]
    tasks = []
    for record in recent:
        task = record.task
        try:
            source_host = urlsplit(task.sourceUrl).hostname or ""
        except ValueError:
            source_host = ""
        tasks.append(
            {
                "taskId": task.id,
                "origin": TASK_ORIGIN.get(task.id, ""),
                "mode": task.mode.value,
                "sourceHost": source_host,
                "status": task.status.value,
                "percent": task.progress.percent if task.progress else 0.0,
                "startedAt": task.createdAt,
            }
        )
    return JSONResponse(
        content={
            "windowSeconds": int(PEER_WINDOW_SECONDS),
            "peers": peers,
            "tasks": tasks,
        },
        headers=PROTOCOL_HEADERS,
    )


@app.get("/v1/privacy/selfcheck")
def privacy_selfcheck():
    """§6.5：只读汇总监听地址、策略、输出白名单、缓存与依赖版本。

    不发起网络请求，不读取 Cookie 内容；`lastCleanupAt` 暂为 null（尚无缓存清理子系统）。
    """
    return JSONResponse(
        content=environment_service.selfcheck_payload(
            output_dir_allowlist=OUTPUT_DIR_ALLOWLIST,
            cookie_policy_default=CookiePolicy.LAZY.value,
        ),
        headers=PROTOCOL_HEADERS,
    )


@app.get("/v1/_debug/download-counter")
def debug_download_counter():
    """M0 桩自检端点（非协议 v0.1 内容，M1 后默认关闭）。

    只读暴露 DOWNLOAD_COUNTER 原值，供 smoke 直接读取「DRM 场景计数器为 0」
    （§9.3 / §10 的"引擎日志可证"之外的硬读数）。不参与任何协议流程；
    仅在 ``BGV_ENABLE_DEBUG_ENDPOINTS=1`` 时开放，否则 404。
    """
    if not DEBUG_ENDPOINTS_ENABLED:
        raise StarletteHTTPException(status_code=404)
    return JSONResponse(
        content={"downloadCount": DOWNLOAD_COUNTER.count},
        headers=PROTOCOL_HEADERS,
    )


@app.post("/v1/tasks", status_code=201)
def create_task(req: TaskCreateRequest, request: Request):
    """§6.2 创建任务。校验顺序：sourceUrl 出站校验 → outputDir 白名单校验。"""
    validate_outbound_url(req.sourceUrl)
    validate_output_dir(req.outputDir, allowlist=OUTPUT_DIR_ALLOWLIST)
    task = Task(
        id=_uuid7(),
        sourceUrl=req.sourceUrl,
        mode=req.mode,
        formatSelector=req.formatSelector,
        namingTemplate=req.namingTemplate,
        postActions=req.postActions,
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
        # stub 用 fixture；real 在首次 GET（sniffing 阶段）由 Playwright 产出
        sniff=_make_sniff(task.id, _scenario_for(req.sourceUrl)) if ENGINE_MODE == "stub" else None,
    )
    TASKS[task.id] = record
    # 记住来源设备：桌面端任务行要能把「手机端发起的」和「本机发起的」分开说。
    _remember_origin(task.id, _client_address(request))
    get_store().upsert(task)
    response = _task_response(task, status_code=201)
    # 先序列化响应再入队，保证 201 响应体为 queued（worker 随后推进）
    if ENGINE_MODE == "real" and WORKER.started:
        WORKER.submit(task.id)
    return response


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
    if ENGINE_MODE == "stub":
        _advance_safely(record)  # 桩语义：GET 惰性推进
    elif not _submit_if_queued(record):
        _advance_safely(record)  # worker 未启动兜底（正常启动必已启动）
    return _task_response(record.task)


@app.get("/v1/tasks/{task_id}/sniff-results")
def list_sniff_results(task_id: str):
    record = _get_record_or_404(task_id)
    sniff_items = [] if record.sniff is None else [record.sniff.model_dump(mode="json")]
    return JSONResponse(
        content={"items": sniff_items},
        headers=PROTOCOL_HEADERS,
    )


@app.get("/v1/tasks/{task_id}/formats")
def list_formats(task_id: str):
    """§6.3：任务不在 awaiting_selection → 409 TASK_STATE_CONFLICT。"""
    record = _get_record_or_404(task_id)
    if record.task.status != TaskStatus.AWAITING_SELECTION:
        raise TaskStateConflictError()
    # real 模式：yt-dlp 真实格式表；stub 模式：fixture
    formats = (record.formats or []) if ENGINE_MODE == "real" else _formats_for(task_id)
    return JSONResponse(
        content={
            "taskId": task_id,
            "items": [f.model_dump(mode="json") for f in formats],
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
    if record.sniff is not None and record.sniff.drmDetected:
        raise DrmProtectedError(record.sniff.drmSystem)
    # real 模式先做最小映射校验：exact / 缺 maxHeight 显式拒绝（非静默 best）；
    # 上限判得出来却无匹配 → 同样显式拒绝，不放进下载阶段；
    # stub 模式保留 M0 fixture 契约（不做映射）
    if ENGINE_MODE == "real":
        try:
            formats_service.resolve_format_expression(req.formatSelector, record.formats)
        except formats_service.FormatUnavailableError as exc:
            return _error_response(
                422,
                error_payload(
                    ErrorCode.FORMAT_UNAVAILABLE,
                    _format_unavailable_detail(exc.reason),
                ),
            )
        except formats_service.FormatNoMatchError as exc:
            return _error_response(
                422,
                error_payload(ErrorCode.FORMAT_UNAVAILABLE, _format_no_match_detail(exc)),
            )
    # 应用选择器与可选覆盖（§6.4）
    task.formatSelector = req.formatSelector
    if req.namingTemplate is not None:
        task.namingTemplate = req.namingTemplate
    if req.postActions is not None:
        task.postActions = req.postActions
    if ENGINE_MODE == "real":
        # 非阻塞：先置 downloading 并持久化，下载交给 worker
        _begin_download(record)
        record.last_selector_key = _selector_key(req.formatSelector)
        response = _task_response(task, status_code=202)
        if WORKER.started:
            WORKER.submit(task.id)
        else:
            _download_safely(record)
        return response
    _do_download(record)
    record.last_selector_key = _selector_key(req.formatSelector)
    return _task_response(task, status_code=202)


@app.post("/v1/tasks/{task_id}/cancel")
def cancel_task(task_id: str):
    """§4：任意非终态 ──取消──► cancelled（终态）。"""
    record = _get_record_or_404(task_id)
    if record.task.status in TERMINAL_STATES:
        raise TaskStateConflictError()
    # 先置标志再转移状态：worker 最迟在下一次写块之前停手，且不会在终态之后覆盖进度
    record.cancel_requested.set()
    logger.info("cancel_requested task_id=%s from_status=%s", task_id, record.task.status.value)
    _transition(record.task, TaskStatus.CANCELLED)
    return _task_response(record.task)


if __name__ == "__main__":
    import uvicorn

    # 控制台可见 INFO 级审计日志（download_attempt 等，§10 验收"引擎日志可证"）
    # 由 startup 事件里的 install_console_logging 负责，两种启动入口同一条路径。
    # 协议默认监听 127.0.0.1:8730（§1 表头；本地回环免令牌 §2.3）。
    # M15 桌面端可用 BGV_LISTEN_HOST / BGV_LISTEN_PORT 覆盖，保证
    # /v1/privacy/selfcheck 回显的 listenAddress 与真实绑定一致。
    uvicorn.run(app, host=environment_service.listen_host(),
                port=environment_service.listen_port())

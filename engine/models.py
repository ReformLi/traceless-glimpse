"""引擎侧协议模型（docs/PROTOCOL.md §3 逐字段对齐）。

字段名直接采用协议 wire format 的 camelCase（非 PEP8 snake_case）：
本文件是协议合同的一部分，schema 导出（export_schema.py）与 API 响应
都以字段名为键，保持与 Kotlin 侧 @SerialName 一致。

所有协议模型继承 StrictModel（extra="forbid"）：未知字段一律拒绝，
与 Kotlin 侧 BgvJson(ignoreUnknownKeys=false) 行为一致。
engine/schema/*.json 仅由 export_schema.py 导出，勿手改。
"""
from __future__ import annotations

from enum import Enum
from typing import Any, Optional

from pydantic import BaseModel, ConfigDict, Field


class StrictModel(BaseModel):
    """协议模型基类：未知字段一律拒绝（§11.2 已裁决：严格拒绝）。"""

    model_config = ConfigDict(extra="forbid")


# ---------------------------------------------------------------------------
# 枚举（值 = 协议小写/蛇形/连字符字符串）
# ---------------------------------------------------------------------------


class TaskMode(str, Enum):
    SNIFF = "sniff"
    DOWNLOAD = "download"


class TaskStatus(str, Enum):
    QUEUED = "queued"
    SNIFFING = "sniffing"
    AWAITING_SELECTION = "awaiting_selection"
    DOWNLOADING = "downloading"
    MERGING = "merging"
    POST_PROCESSING = "post_processing"
    DONE = "done"
    CANCELLED = "cancelled"
    FAILED = "failed"


class CookiePolicy(str, Enum):
    NONE = "none"
    LAZY = "lazy"
    EXPLICIT = "explicit"


class MediaStreamType(str, Enum):
    HLS = "hls"
    DASH = "dash"
    MP4 = "mp4"
    AUDIO = "audio"
    OTHER = "other"


class DrmSystem(str, Enum):
    WIDEVINE = "widevine"
    FAIRPLAY = "fairplay"
    PLAYREADY = "playready"
    UNKNOWN = "unknown"


class FormatSource(str, Enum):
    YT_DLP = "yt-dlp"
    MANIFEST = "manifest"
    SNIFF = "sniff"


class SelectionStrategy(str, Enum):
    EXACT = "exact"
    MAX_HEIGHT = "max_height"
    BEST = "best"
    AUDIO_ONLY = "audio_only"


class PreferCodec(str, Enum):
    H264 = "h264"
    H265 = "h265"
    AV1 = "av1"
    VP9 = "vp9"


class PostActionType(str, Enum):
    MERGE = "merge"
    REMUX = "remux"
    TRANSCODE = "transcode"
    EXTRACT_SUBTITLES = "extract_subtitles"
    TRANSCRIBE = "transcribe"
    THUMBNAIL = "thumbnail"
    MOVE = "move"


class ErrorCategory(str, Enum):
    INPUT = "input"
    NETWORK = "network"
    SECURITY = "security"
    CONTENT = "content"
    STORAGE = "storage"
    INTERNAL = "internal"


class ErrorCode(str, Enum):
    """30 个错误码：§5 表 28 个 + 裁决新增 VALIDATION_ERROR、NOT_FOUND。值 = 常量名恒等。"""

    INVALID_URL = "INVALID_URL"
    UNSUPPORTED_SCHEME = "UNSUPPORTED_SCHEME"
    INVALID_OUTPUT_DIR = "INVALID_OUTPUT_DIR"
    INVALID_FORMAT_SELECTOR = "INVALID_FORMAT_SELECTOR"
    INVALID_POST_ACTION = "INVALID_POST_ACTION"
    SSRF_BLOCKED = "SSRF_BLOCKED"
    OUTPUT_DIR_REJECTED = "OUTPUT_DIR_REJECTED"
    COOKIE_READ_DENIED = "COOKIE_READ_DENIED"
    EXTENSION_NOT_ALLOWED = "EXTENSION_NOT_ALLOWED"
    AUTH_REQUIRED = "AUTH_REQUIRED"
    UNSUPPORTED_SITE = "UNSUPPORTED_SITE"
    DRM_PROTECTED = "DRM_PROTECTED"
    NO_MEDIA_FOUND = "NO_MEDIA_FOUND"
    FORMAT_UNAVAILABLE = "FORMAT_UNAVAILABLE"
    EXTRACTOR_FAILED = "EXTRACTOR_FAILED"
    NETWORK_TIMEOUT = "NETWORK_TIMEOUT"
    DNS_FAILURE = "DNS_FAILURE"
    TLS_ERROR = "TLS_ERROR"
    NETWORK_HTTP_ERROR = "NETWORK_HTTP_ERROR"
    RATE_LIMITED = "RATE_LIMITED"
    DISK_FULL = "DISK_FULL"
    WRITE_PERMISSION_DENIED = "WRITE_PERMISSION_DENIED"
    MERGE_FAILED = "MERGE_FAILED"
    TRANSCODE_FAILED = "TRANSCODE_FAILED"
    PARTIAL_DOWNLOAD = "PARTIAL_DOWNLOAD"
    ENGINE_BUSY = "ENGINE_BUSY"
    TASK_STATE_CONFLICT = "TASK_STATE_CONFLICT"
    UNKNOWN = "UNKNOWN"
    VALIDATION_ERROR = "VALIDATION_ERROR"
    NOT_FOUND = "NOT_FOUND"


# 错误码元数据：code -> (category, retryable, 用户可读文案)。文案逐字取自 §5 表。
# VALIDATION_ERROR / NOT_FOUND 为裁决新增（§5 表外），M0 后记入协议 changelog。
ERROR_CODE_INFO: dict[ErrorCode, tuple[ErrorCategory, bool, str]] = {
    ErrorCode.INVALID_URL: (ErrorCategory.INPUT, False, "链接格式不正确"),
    ErrorCode.UNSUPPORTED_SCHEME: (ErrorCategory.INPUT, False, "仅支持 http/https 链接"),
    ErrorCode.INVALID_OUTPUT_DIR: (ErrorCategory.INPUT, False, "输出目录不合法"),
    ErrorCode.INVALID_FORMAT_SELECTOR: (ErrorCategory.INPUT, False, "画质选择参数不合法"),
    ErrorCode.INVALID_POST_ACTION: (ErrorCategory.INPUT, False, "不支持的后处理动作"),
    ErrorCode.SSRF_BLOCKED: (ErrorCategory.SECURITY, False, "目标地址指向受限网络，已拒绝"),
    ErrorCode.OUTPUT_DIR_REJECTED: (ErrorCategory.SECURITY, False, "输出目录不在允许范围内"),
    ErrorCode.COOKIE_READ_DENIED: (ErrorCategory.SECURITY, False, "凭据读取被拒绝"),
    ErrorCode.EXTENSION_NOT_ALLOWED: (ErrorCategory.SECURITY, False, "文件类型不在白名单"),
    ErrorCode.AUTH_REQUIRED: (ErrorCategory.SECURITY, False, "需要有效的访问令牌"),
    ErrorCode.UNSUPPORTED_SITE: (ErrorCategory.CONTENT, False, "暂不支持该站点"),
    ErrorCode.DRM_PROTECTED: (ErrorCategory.CONTENT, False, "该内容受 DRM 保护，不支持下载"),
    ErrorCode.NO_MEDIA_FOUND: (ErrorCategory.CONTENT, True, "未发现可下载的媒体流"),
    ErrorCode.FORMAT_UNAVAILABLE: (ErrorCategory.CONTENT, False, "所选画质不可用或超出体积上限"),
    ErrorCode.EXTRACTOR_FAILED: (ErrorCategory.CONTENT, True, "解析失败，可能站点已变更"),
    ErrorCode.NETWORK_TIMEOUT: (ErrorCategory.NETWORK, True, "网络超时"),
    ErrorCode.DNS_FAILURE: (ErrorCategory.NETWORK, True, "域名解析失败"),
    ErrorCode.TLS_ERROR: (ErrorCategory.NETWORK, False, "安全连接失败"),
    ErrorCode.NETWORK_HTTP_ERROR: (ErrorCategory.NETWORK, True, "目标站点返回错误"),
    ErrorCode.RATE_LIMITED: (ErrorCategory.NETWORK, True, "请求过于频繁"),
    ErrorCode.DISK_FULL: (ErrorCategory.STORAGE, False, "磁盘空间不足"),
    ErrorCode.WRITE_PERMISSION_DENIED: (ErrorCategory.STORAGE, False, "无写入权限"),
    ErrorCode.MERGE_FAILED: (ErrorCategory.INTERNAL, False, "音视频合并失败"),
    ErrorCode.TRANSCODE_FAILED: (ErrorCategory.INTERNAL, False, "转码失败"),
    ErrorCode.PARTIAL_DOWNLOAD: (ErrorCategory.INTERNAL, True, "下载不完整"),
    ErrorCode.ENGINE_BUSY: (ErrorCategory.INTERNAL, True, "引擎繁忙，请稍后重试"),
    ErrorCode.TASK_STATE_CONFLICT: (ErrorCategory.INTERNAL, False, "任务当前状态不允许该操作"),
    ErrorCode.UNKNOWN: (ErrorCategory.INTERNAL, False, "未知错误"),
    ErrorCode.VALIDATION_ERROR: (ErrorCategory.INPUT, False, "请求参数不合法"),
    ErrorCode.NOT_FOUND: (ErrorCategory.INPUT, False, "请求的资源不存在"),
}


# ---------------------------------------------------------------------------
# 嵌套结构（§3.1 / §3.2 / §3.3）
# ---------------------------------------------------------------------------


class EstimatedQuality(StrictModel):
    height: int
    bitrateKbps: int
    label: str


class Resolution(StrictModel):
    width: int
    height: int


class Codec(StrictModel):
    video: str
    audio: str


class Progress(StrictModel):
    phase: str
    bytesDownloaded: int
    bytesTotal: int
    speedBps: int
    etaSeconds: int
    percent: float


class ErrorPayload(StrictModel):
    """§3.5。category/retryable/message 一律由 error_payload() 工厂从 §5 表推导。"""

    code: ErrorCode
    category: ErrorCategory
    message: str
    retryable: bool
    detail: Optional[dict[str, Any]] = None


class FormatSelector(StrictModel):
    """§3.4 结构化画质选择器：不接受 shell 字符串透传。"""

    strategy: SelectionStrategy
    formatId: Optional[str] = None
    maxHeight: Optional[int] = None
    preferCodec: Optional[PreferCodec] = None
    maxSizeBytes: Optional[int] = None


class PostAction(StrictModel):
    """§3.7 扁平化后处理动作：单一对象 + type 枚举 + 子类参数全部平铺可选。"""

    type: PostActionType
    container: Optional[str] = None
    targetCodec: Optional[str] = None
    crf: Optional[int] = None
    langs: Optional[list[str]] = None
    model: Optional[str] = None
    lang: Optional[str] = None
    destDir: Optional[str] = None


class SniffResult(StrictModel):
    taskId: str
    mediaUrl: str
    type: MediaStreamType
    container: str
    estimatedQuality: EstimatedQuality
    drmDetected: bool
    discoveredAt: str
    drmSystem: Optional[DrmSystem] = None
    headersHint: dict[str, str] = Field(default_factory=dict)


class FormatInfo(StrictModel):
    formatId: str
    container: str
    resolution: Resolution
    bitrateKbps: int
    codec: Codec
    hasAudio: bool
    hasVideo: bool
    fileSizeEstimateBytes: int
    source: FormatSource


class Task(StrictModel):
    """§3.1。注意：协议层不存在 cookieFile / browser / userAgent / headers 字段。"""

    id: str
    sourceUrl: str
    mode: TaskMode
    cookiePolicy: CookiePolicy
    outputDir: str
    status: TaskStatus
    createdAt: str
    updatedAt: str
    formatSelector: Optional[FormatSelector] = None
    # 产物主名（M24）：不是模板，不含后缀；留空 = 沿用站点标题。键名沿用旧名见 PROTOCOL §3.6。
    namingTemplate: Optional[str] = None
    postActions: list[PostAction] = Field(default_factory=list)
    proxyId: Optional[str] = None
    progress: Optional[Progress] = None
    error: Optional[ErrorPayload] = None
    # 仅 done 且 real 模式有真实产物时非空；stub / 未完成 / 失败一律 null。
    outputPath: Optional[str] = None


def error_payload(
    code: ErrorCode,
    detail: dict[str, Any] | None = None,
    *,
    message: str | None = None,
) -> ErrorPayload:
    """由错误码构造 ErrorPayload：category / retryable / message 以 §5 表为准。

    ``message`` 只给「同一个码在不同现场要说不同话」的情形（M21 起：连接被掐断
    归 NETWORK_HTTP_ERROR，但文案要说清是链路阻断而不是站点报错）。不改码、不改
    category / retryable——那两个是协议口径，不能因文案需要而漂移。
    """
    category, retryable, default_message = ERROR_CODE_INFO[code]
    return ErrorPayload(
        code=code,
        category=category,
        message=message or default_message,
        retryable=retryable,
        detail=detail,
    )


# ---------------------------------------------------------------------------
# 引擎私有请求模型（不导出 schema，不属于协议合同）
# ---------------------------------------------------------------------------


class TaskCreateRequest(StrictModel):
    """POST /v1/tasks 请求体（§6.2）。"""

    sourceUrl: str
    mode: TaskMode
    cookiePolicy: CookiePolicy
    outputDir: str
    formatSelector: Optional[FormatSelector] = None
    # 产物主名（M24）：不是模板，不含后缀；留空 = 沿用站点标题。键名沿用旧名见 PROTOCOL §3.6。
    namingTemplate: Optional[str] = None
    postActions: list[PostAction] = Field(default_factory=list)
    proxyId: Optional[str] = None


class DownloadRequest(StrictModel):
    """POST /v1/tasks/{id}/download 请求体（§6.4）。n/A 字段可省略。"""

    formatSelector: FormatSelector
    # 产物主名（M24）：不是模板，不含后缀；留空 = 沿用站点标题。键名沿用旧名见 PROTOCOL §3.6。
    namingTemplate: Optional[str] = None
    postActions: Optional[list[PostAction]] = None

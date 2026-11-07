"""M16 · 桌面端「下载控制台」界面层（CTkTabview 的第二个标签页）。

M16 第二步起，本模块仍然**不持有任何 socket / HTTP 客户端**，但回调已经由
``ControlPanel`` 注入为真实网络流程：后台线程跑 ``POST /v1/tasks`` → 每秒轮询
``GET /v1/tasks/{id}`` → ``/formats`` → ``POST /download``，结果全部经
``ui_call`` 抛回主线程后调用下面的渲染方法。UI 侧不感知 httpx。

===========================  ==========================================================
方法                          用途
===========================  ==========================================================
``set_engine_ready()``        引擎未启动 → 整页置灰并给出提示（M16 核心架构第 1 条）
``show_sniffing()``           嗅探中：按钮转「嗅探中…」并锁定
``show_formats()``            结果区从隐藏变可见；每个选项绑定
                              ``height / hasVideo / hasAudio`` 元数据
``selected_format()``         取回当前选项绑定的元数据（供网络层换算 selector）
``selected_format_id()``      仅用于诊断/冒烟的 formatId；**绝不发给 /download**
``show_downloading()``        进入下载态，锁定按钮等待进度
``add_task() -> TaskRow``     新增一条下载任务行
``TaskRow.update()``          刷新进度条 / 速度 / 剩余时间 / 状态；
                              percent=0 但字节在涨时切换不确定进度脉冲
``show_done()``               完成提示 + 文件位置 + 清空 URL 输入框
``show_error()``              红字提示，错误码按协议 §5 翻译
``clear()``                   整页复位
``ui_demo()``                 注入**界面预览数据**（不发网络请求），供截图与冒烟
===========================  ==========================================================

线程约束（M16 核心架构第 3 条）：以上方法**只允许主线程调用**。后台线程必须经
``ControlPanel.ui_call`` 把渲染动作投递进主线程队列，绝不跨线程碰 Tk 控件。
模块里保留 :meth:`DownloadConsole.post` 作为这个投递的便捷入口。

数据字段与 ``engine/models.py`` 的协议模型对齐：``FormatInfo`` 用
``formatId / container / resolution.height / codec.video``，``Progress`` 用
``percent / speedBps / etaSeconds``，因此 ``ui_demo()`` 的样例不是臆造结构。

协议坑（M16 第一步提前排出，第二步落地）：real 模式下
``services/formats.py`` 对 ``strategy="exact"`` / ``formatId`` 直接抛
``exact_format_id_unsupported`` → 422 ``FORMAT_UNAVAILABLE``。所以这里只绑定
画质元数据，由 :func:`engine_client.selector_for_format` 换算成
``max_height`` / ``audio_only`` / ``best``，桌面端永不发送 formatId。
"""
from __future__ import annotations

from typing import Callable

import customtkinter as ctk

# -- 配色：与 gui_app.py 保持同一套色值；单独定义避免循环 import -----------------
BLUE = "#1F6FEB"
BLUE_HOVER = "#1A5FD0"
GREEN = "#2EB872"
RED = "#E5534B"
AMBER = "#E5A23C"
BTN_DISABLED = ("#B9BEC5", "#3C424B")
BTN_DISABLED_TEXT = ("#5C636C", "#98A0AA")
#: CTk 默认蓝主题下 CTkOptionMenu 的 fg_color / button_color，启用时还原到它。
MENU_ACTIVE = ("#3B8ED0", "#1F6AA5")
MENU_BUTTON_ACTIVE = ("#36719F", "#144870")
#: CTkTabview 的标签页本身是灰底，卡片若用默认色会与它糊成一片（深色尤其明显）。
#: 显式给卡片/任务行分层配色，浅色白卡片浮起、深色略亮的卡片浮起。
CARD_BG = ("#FFFFFF", "#30343A")
CARD_BORDER = ("#D5D9DE", "#474C54")
STATUS_BG = ("#F2F5F8", "#2A2E34")
ROW_BG = ("#F4F6F8", "#383D44")

PHASE_TEXT = {
    "queued": "排队中",
    "sniffing": "嗅探中",
    "awaiting_selection": "等待选择画质",
    "downloading": "下载中",
    "merging": "合并中",
    "post_processing": "后处理中",
    "done": "已完成",
    "failed": "失败",
    "cancelled": "已取消",
}

#: 协议 §5 错误码的用户可读文案，逐字取自 ``engine/models.py::ERROR_CODE_INFO``。
#: 引擎返回的 ``error.message`` 优先级更高，本表只作「拿到了 code 却没有 message」
#: 时的本机兜底，避免桌面端与引擎另写一套措辞。
ERROR_CODE_ZH: dict[str, str] = {
    "INVALID_URL": "链接格式不正确",
    "UNSUPPORTED_SCHEME": "仅支持 http/https 链接",
    "INVALID_OUTPUT_DIR": "输出目录不合法",
    "INVALID_FORMAT_SELECTOR": "画质选择参数不合法",
    "INVALID_POST_ACTION": "不支持的后处理动作",
    "SSRF_BLOCKED": "目标地址指向受限网络，已拒绝",
    "OUTPUT_DIR_REJECTED": "输出目录不在允许范围内",
    "COOKIE_READ_DENIED": "凭据读取被拒绝",
    "EXTENSION_NOT_ALLOWED": "文件类型不在白名单",
    "AUTH_REQUIRED": "需要有效的访问令牌",
    "UNSUPPORTED_SITE": "暂不支持该站点",
    "DRM_PROTECTED": "该内容受 DRM 保护，不支持下载",
    "NO_MEDIA_FOUND": "未发现可下载的媒体流",
    "FORMAT_UNAVAILABLE": "所选画质不可用或超出体积上限",
    "EXTRACTOR_FAILED": "解析失败，可能站点已变更",
    "NETWORK_TIMEOUT": "网络超时",
    "DNS_FAILURE": "域名解析失败",
    "TLS_ERROR": "安全连接失败",
    "NETWORK_HTTP_ERROR": "目标站点返回错误",
    "RATE_LIMITED": "请求过于频繁",
    "DISK_FULL": "磁盘空间不足",
    "WRITE_PERMISSION_DENIED": "无写入权限",
    "MERGE_FAILED": "音视频合并失败",
    "TRANSCODE_FAILED": "转码失败",
    "PARTIAL_DOWNLOAD": "下载不完整",
    "ENGINE_BUSY": "引擎繁忙，请稍后重试",
    "TASK_STATE_CONFLICT": "任务当前状态不允许该操作",
    "UNKNOWN": "未知错误",
    "VALIDATION_ERROR": "请求参数不合法",
    "NOT_FOUND": "请求的资源不存在",
}

# -- 界面预览数据（明确标注 demo，字段严格照 FormatInfo） ------------------------
DEMO_URL = "https://www.bilibili.com/video/BV1GJ411x7h7"
DEMO_TITLE = "示例视频标题 · 界面预览"
DEMO_TASK_ID = "demo-task-0001"
DEMO_FORMATS: list[dict] = [
    {
        "formatId": "137", "container": "mp4",
        "resolution": {"width": 1920, "height": 1080},
        "bitrateKbps": 4200, "codec": {"video": "h264", "audio": "aac"},
        "hasAudio": False, "hasVideo": True,
        "fileSizeEstimateBytes": 268_435_456, "source": "yt-dlp",
    },
    {
        "formatId": "136", "container": "mp4",
        "resolution": {"width": 1280, "height": 720},
        "bitrateKbps": 2400, "codec": {"video": "h264", "audio": "aac"},
        "hasAudio": False, "hasVideo": True,
        "fileSizeEstimateBytes": 142_606_336, "source": "yt-dlp",
    },
    {
        "formatId": "135", "container": "mp4",
        "resolution": {"width": 854, "height": 480},
        "bitrateKbps": 1100, "codec": {"video": "h264", "audio": "aac"},
        "hasAudio": False, "hasVideo": True,
        "fileSizeEstimateBytes": 68_812_800, "source": "yt-dlp",
    },
]


def translate_error(code: str, message: str = "") -> str:
    """错误码 → 人话。引擎已翻译的 ``message`` 优先，本表兜底。"""
    if message and message.strip():
        return message.strip()
    if not code:
        return "操作失败"
    return ERROR_CODE_ZH.get(code, f"操作失败（{code}）")


def normalize_codec(value: str) -> str:
    """把 yt-dlp 的 codec string 收敛成人话：``avc1.42E01E`` → ``h264``。

    FormatInfo.codec 来自 yt-dlp 原始 ``vcodec/acodec``，HLS 变体常给出
    ``avc1.42E01E`` / ``mp4a.40.2`` 这类 MIME 串；直接展示既长又和用户认知的
    「h264」对不上。只做显示层归一化，不改任何协议字段。
    """
    raw = (value or "").strip().lower()
    if not raw or raw == "none":
        return ""
    for prefix, label in (("avc1", "h264"), ("avc3", "h264"),
                          ("hvc1", "h265"), ("hev1", "h265"),
                          ("av01", "av1"), ("vp09", "vp9"),
                          ("mp4a", "aac"), ("opus", "opus")):
        if raw.startswith(prefix):
            return label
    return (value or "").strip()


def format_label(fmt: dict) -> str:
    """``FormatInfo`` → ``1080p · mp4 · h264``，纯音频 → ``纯音频 · m4a · aac``。

    缺字段时降级，不抛异常；yt-dlp 对纯音轨会给出 ``vcodec="none"``，此时
    显示高度没有意义，改成「纯音频」才不会让用户以为选到了 0p 视频。
    """
    resolution = fmt.get("resolution") or {}
    try:
        height = int(resolution.get("height") or 0)
    except (TypeError, ValueError):
        height = 0
    container = str(fmt.get("container") or "未知容器")
    codec = fmt.get("codec") or {}
    raw_video = str(codec.get("video") or "").strip()
    raw_audio = str(codec.get("audio") or "").strip()
    video = normalize_codec(raw_video)
    audio = normalize_codec(raw_audio)
    has_video = bool(fmt.get("hasVideo"))
    has_audio = bool(fmt.get("hasAudio"))
    is_audio_only = (has_audio and not has_video) or (
        (not raw_video or raw_video.lower() == "none") and has_audio
    )
    if is_audio_only:
        parts = ["纯音频", container]
        if audio and audio.lower() != "none":
            parts.append(audio)
        return " · ".join(parts)
    quality = f"{height}p" if height else str(fmt.get("formatId") or "未知画质")
    parts = [quality, container]
    if video and video.lower() != "none":
        parts.append(video)
    return " · ".join(parts)


def format_meta(fmt: dict) -> dict:
    """``FormatInfo`` → 下拉框选项绑定的元数据（**不含 formatId 用于下载**）。

    需求 §一.2：每个选项绑定其 ``height`` 与 ``hasVideo/hasAudio``；``formatId``
    只留作界面诊断与冒烟断言，网络层不会读取它来构造请求。
    """
    resolution = fmt.get("resolution") or {}
    try:
        height = int(resolution.get("height") or 0)
    except (TypeError, ValueError):
        height = 0
    return {
        "formatId": str(fmt.get("formatId") or ""),
        "height": height,
        "hasVideo": bool(fmt.get("hasVideo")),
        "hasAudio": bool(fmt.get("hasAudio")),
    }


def format_speed(bps: int) -> str:
    """字节/秒 → ``2.4 MB/s``。0 或负值显示占位符，避免出现 ``0 B/s`` 的假速度。"""
    try:
        value = float(bps)
    except (TypeError, ValueError):
        return "—"
    if value <= 0:
        return "—"
    for unit, divisor in (("GB/s", 1024 ** 3), ("MB/s", 1024 ** 2), ("KB/s", 1024)):
        if value >= divisor:
            return f"{value / divisor:.1f} {unit}"
    return f"{value:.0f} B/s"


def format_eta(seconds: int) -> str:
    """秒 → ``00:12``；非正数返回空串，由调用方决定是否显示。"""
    try:
        value = int(seconds)
    except (TypeError, ValueError):
        return ""
    if value <= 0:
        return ""
    return f"{value // 60:02d}:{value % 60:02d}"


def format_bytes(num: int | float) -> str:
    """字节 → ``12.3 MB``；负数或非数值显示占位符（与 format_speed 同款约定）。"""
    try:
        value = float(num)
    except (TypeError, ValueError):
        return "—"
    if value < 0:
        return "—"
    for unit, divisor in (("GB", 1024 ** 3), ("MB", 1024 ** 2), ("KB", 1024)):
        if value >= divisor:
            return f"{value / divisor:.1f} {unit}"
    return f"{value:.0f} B"


class TaskRow:
    """下载任务列表中的一行：文件名 / 状态 / 进度条 / 速度 / 剩余时间。

    M18.5：引擎在「总大小未知」（HLS 直链无 content-length、分片估算不出）时按
    协议约定返回 ``percent=0``，此时进入**不确定进度**形态：进度条做平滑脉冲
    动画（不跳 0%），速度行显示「↓ 速度 · 已下载 X」，一旦拿到真实 percent
    （引擎分片估算成功或直链有 Content-Length）立即切回确定进度。
    """

    #: 脉冲动画节拍与周期（三角波 0→1→0）。0% 死进度条的替代形态。
    _PULSE_MS = 50
    _PULSE_PERIOD = 1.6  # 秒

    def __init__(self, parent, name: str, font: Callable) -> None:
        self.frame = ctk.CTkFrame(parent, corner_radius=8, fg_color=ROW_BG)
        self.frame.pack(fill="x", pady=(0, 5))
        self.frame.grid_columnconfigure(0, weight=1)

        self.name_label = ctk.CTkLabel(self.frame, text=name, font=font(11, "bold"),
                                       anchor="w")
        self.name_label.grid(row=0, column=0, sticky="ew", padx=10, pady=(5, 0))
        self.status_label = ctk.CTkLabel(self.frame, text=PHASE_TEXT["queued"],
                                         font=font(10), text_color=("gray45", "gray65"),
                                         anchor="e", width=92)
        self.status_label.grid(row=0, column=1, sticky="e", padx=(6, 10), pady=(5, 0))

        self.progress_bar = ctk.CTkProgressBar(self.frame, height=9)
        self.progress_bar.set(0.0)
        self.progress_bar.grid(row=1, column=0, sticky="ew", padx=10, pady=(3, 0))
        self.percent_label = ctk.CTkLabel(self.frame, text="0%", font=font(10),
                                          anchor="e", width=44)
        self.percent_label.grid(row=1, column=1, sticky="e", padx=(6, 10), pady=(3, 0))

        self.speed_label = ctk.CTkLabel(self.frame, text="↓ —", font=font(10),
                                        text_color=("gray45", "gray65"), anchor="w")
        self.speed_label.grid(row=2, column=0, sticky="w", padx=10, pady=(1, 5))
        self.eta_label = ctk.CTkLabel(self.frame, text="", font=font(10),
                                      text_color=("gray45", "gray65"), anchor="e")
        self.eta_label.grid(row=2, column=1, sticky="e", padx=(6, 10), pady=(1, 5))

        # 不确定进度脉冲的运行态：_pulsing 是开关，_pulse_after 是挂起的 after id
        self._pulsing = False
        self._pulse_after: str | None = None
        self._pulse_t = 0.0

    def update(self, *, percent: float = 0.0, speed_bps: int = 0,
               eta_seconds: int = 0, bytes_downloaded: int = 0,
               phase: str = "downloading") -> None:
        """仅主线程调用。进度条按 0–1 取值，其余字段照协议 ``Progress`` 语义。"""
        try:
            ratio = max(0.0, min(1.0, float(percent) / 100.0))
        except (TypeError, ValueError):
            ratio = 0.0
        if ratio <= 0.0 and bytes_downloaded > 0 and phase == "downloading":
            self._enter_indeterminate(speed_bps, bytes_downloaded)
            self.status_label.configure(
                text=PHASE_TEXT.get(phase, phase),
                text_color=("gray45", "gray65"),
            )
            return
        self._leave_indeterminate()
        self.progress_bar.set(ratio)
        self.percent_label.configure(text=f"{ratio * 100:.0f}%")
        self.speed_label.configure(text=f"↓ {format_speed(speed_bps)}")
        eta = format_eta(eta_seconds)
        self.eta_label.configure(text=f"剩余 {eta}" if eta and phase == "downloading" else "")
        self.status_label.configure(
            text=PHASE_TEXT.get(phase, phase),
            text_color=(GREEN if phase == "done" else RED if phase == "failed"
                        else "gray45", GREEN if phase == "done"
                        else "#FF8A80" if phase == "failed" else "gray65"),
        )

    # -- 不确定进度（percent=0 但字节在涨）-----------------------------------

    def _enter_indeterminate(self, speed_bps: int, bytes_downloaded: int) -> None:
        """切换到脉冲形态。只在首次进入时启动 after 循环，之后仅刷文案。"""
        if not self._pulsing:
            self._pulsing = True
            self._pulse_t = 0.0
            self._tick_pulse()
        self.percent_label.configure(text="…")
        self.eta_label.configure(text="")
        self.speed_label.configure(
            text=f"↓ {format_speed(speed_bps)} · 已下载 {format_bytes(bytes_downloaded)}"
        )

    def _leave_indeterminate(self) -> None:
        """退出脉冲形态：置开关即可，挂起的回调下次触发时自行发现并停止。"""
        self._pulsing = False

    def _tick_pulse(self) -> None:
        self._pulse_after = None
        if not self._pulsing:
            return
        try:
            if not self.frame.winfo_exists():
                return
        except Exception:  # noqa: BLE001 —— 控件已销毁
            return
        self._pulse_t += self._PULSE_MS / 1000.0
        phase = (self._pulse_t % self._PULSE_PERIOD) / self._PULSE_PERIOD
        sweep = phase if phase <= 0.5 else 1.0 - phase  # 三角波 0→1→0
        self.progress_bar.set(0.06 + sweep * 0.55)
        self._pulse_after = self.frame.after(self._PULSE_MS, self._tick_pulse)

    def destroy(self) -> None:
        """清掉挂起的动画回调再销毁控件；``clear()`` 走这里而非直接 destroy frame。"""
        self._pulsing = False
        if self._pulse_after is not None:
            try:
                self.frame.after_cancel(self._pulse_after)
            except Exception:  # noqa: BLE001 —— 控件已销毁则无事可做
                pass
            self._pulse_after = None
        self.frame.destroy()


class DownloadConsole:
    """Tab② 的全部界面与纯 UI 状态。不持有任何 socket / HTTP 客户端。"""

    def __init__(self, parent, *, font: Callable, ui_call: Callable,
                 on_sniff: Callable[[str], None],
                 on_download: Callable[[str, dict], None]) -> None:
        self.font = font
        self.ui_call = ui_call
        self.on_sniff = on_sniff
        # on_download(task_id, format_meta)：meta 只有 height/hasVideo/hasAudio，
        # 协议 selector 的换算由网络层负责（详见模块 docstring 的协议坑）。
        self.on_download = on_download

        self.is_ready = False
        self.current_task_id: str | None = None
        self._format_map: dict[str, dict] = {}
        self._sniffing = False
        self._downloading = False
        self._has_formats = False
        self._finished = False
        self.task_rows: list[TaskRow] = []

        self.body = ctk.CTkFrame(parent, fg_color="transparent")
        self.body.pack(fill="both", expand=True, padx=8, pady=8)

        self._build_status_bar()
        self._build_sniff_card()
        self._build_result_card()
        self._build_tasks_card()
        self._build_message()
        self.set_engine_ready(False)

    # -- 线程投递（下一阶段后台线程用） ---------------------------------------
    def post(self, fn: Callable, *args, **kwargs) -> None:
        """把一次界面渲染投递回主线程队列；网络线程不得直接调渲染方法。"""
        self.ui_call(fn, *args, **kwargs)

    # -- 构建 -----------------------------------------------------------------
    def _build_status_bar(self) -> None:
        self.status_bar = ctk.CTkFrame(self.body, corner_radius=8, height=34,
                                       fg_color=STATUS_BG, border_width=1,
                                       border_color=CARD_BORDER)
        self.status_bar.pack(fill="x", pady=(0, 8))
        self.status_bar.pack_propagate(False)
        self.status_dot = ctk.CTkLabel(self.status_bar, text="●", font=self.font(14),
                                       width=20, text_color=RED)
        self.status_dot.pack(side="left", padx=(10, 2))
        self.status_text = ctk.CTkLabel(self.status_bar, text="", font=self.font(11, "bold"),
                                        anchor="w")
        self.status_text.pack(side="left", fill="x", expand=True)
        self.status_hint = ctk.CTkLabel(self.status_bar, text="", font=self.font(10),
                                        text_color=("gray45", "gray65"), anchor="e")
        self.status_hint.pack(side="right", padx=(6, 12))

        # 未就绪时独占一行：明确告诉用户「不是控件坏了，是引擎没起来」。
        # 此处先不 pack —— 由 set_engine_ready() 按引擎状态插到 sniff_card 之前，
        # 保证「状态条 → 置灰提示 → 嗅探区」的顺序在任何状态下都正确。
        self.offline_hint = ctk.CTkFrame(self.body, corner_radius=8,
                                         fg_color=("#FDE9E7", "#4A2422"))
        ctk.CTkLabel(
            self.offline_hint,
            text="引擎未启动：控制台已置灰。请先切到「引擎设置」页点【启动】。",
            font=self.font(11), text_color=("#B4443C", "#FF8A80"), anchor="w",
        ).pack(fill="x", padx=12, pady=7)

    def _build_sniff_card(self) -> None:
        self.sniff_card = ctk.CTkFrame(self.body, corner_radius=10, fg_color=CARD_BG,
                                       border_width=1, border_color=CARD_BORDER)
        self.sniff_card.pack(fill="x", pady=(0, 8))
        head = ctk.CTkFrame(self.sniff_card, fg_color="transparent")
        head.pack(fill="x", padx=12, pady=(9, 0))
        ctk.CTkLabel(head, text="① 输入网页地址", font=self.font(12, "bold"),
                     anchor="w").pack(side="left")
        self.demo_badge = ctk.CTkLabel(head, text="", font=self.font(10),
                                       text_color=(AMBER, "#FFD966"), anchor="e")
        self.demo_badge.pack(side="right")
        row = ctk.CTkFrame(self.sniff_card, fg_color="transparent")
        row.pack(fill="x", padx=12, pady=(6, 11))
        self.url_entry = ctk.CTkEntry(row, font=self.font(11),
                                      placeholder_text="粘贴网页链接，例如 https://…")
        self.url_entry.pack(side="left", fill="x", expand=True, padx=(0, 8))
        self.sniff_button = ctk.CTkButton(
            row, text="开始嗅探", width=104, height=30, font=self.font(11),
            fg_color=BLUE, hover_color=BLUE_HOVER,
            text_color_disabled=BTN_DISABLED_TEXT, command=self._on_sniff_click,
        )
        self.sniff_button.pack(side="right")

    def _build_result_card(self) -> None:
        # 需求：嗅探结果展示区在出结果前隐藏 —— 先建好但不 pack，show_formats 时
        # 用 pack(before=tasks_card) 插回正确位置，避免动态控件跑到列表下面。
        self.result_card = ctk.CTkFrame(self.body, corner_radius=10, fg_color=CARD_BG,
                                        border_width=1, border_color=CARD_BORDER)
        head = ctk.CTkFrame(self.result_card, fg_color="transparent")
        head.pack(fill="x", padx=12, pady=(9, 0))
        ctk.CTkLabel(head, text="② 嗅探结果 · 选择画质", font=self.font(12, "bold"),
                     anchor="w").pack(side="left")
        self.format_count = ctk.CTkLabel(head, text="", font=self.font(10),
                                         text_color=("gray45", "gray65"), anchor="e")
        self.format_count.pack(side="right")
        self.media_label = ctk.CTkLabel(self.result_card, text="", font=self.font(10),
                                        text_color=("gray40", "gray65"), anchor="w",
                                        wraplength=620, justify="left")
        self.media_label.pack(fill="x", padx=12, pady=(3, 0))

        row = ctk.CTkFrame(self.result_card, fg_color="transparent")
        row.pack(fill="x", padx=12, pady=(6, 11))
        row.grid_columnconfigure(0, weight=1)
        self.format_menu = ctk.CTkOptionMenu(
            row, values=["（暂无画质）"], font=self.font(11),
            text_color_disabled=BTN_DISABLED_TEXT,
        )
        self.format_menu.grid(row=0, column=0, sticky="ew", padx=(0, 8))
        self.download_button = ctk.CTkButton(
            row, text="开始下载", width=104, height=30, font=self.font(11),
            fg_color=BLUE, hover_color=BLUE_HOVER,
            text_color_disabled=BTN_DISABLED_TEXT, command=self._on_download_click,
        )
        self.download_button.grid(row=0, column=1)

    def _build_tasks_card(self) -> None:
        self.tasks_card = ctk.CTkFrame(self.body, corner_radius=10, fg_color=CARD_BG,
                                       border_width=1, border_color=CARD_BORDER)
        # 不做 expand：卡片按内容自然高度收拢，既不会把消息条挤没，也不会在
        # 只有一两条任务时撑出一大片空卡片。剩余空间留在标签页背景上。
        self.tasks_card.pack(fill="x")
        head = ctk.CTkFrame(self.tasks_card, fg_color="transparent")
        head.pack(fill="x", padx=12, pady=(9, 0))
        ctk.CTkLabel(head, text="③ 下载任务", font=self.font(12, "bold"),
                     anchor="w").pack(side="left")
        self.task_count = ctk.CTkLabel(head, text="0 个任务", font=self.font(10),
                                       text_color=("gray45", "gray65"), anchor="e")
        self.task_count.pack(side="right")
        # 用普通 frame 而非 CTkScrollableFrame：后者的 canvas 视口高度由构造期
        # ``height`` 固定，不会跟随 ``pack(expand=True)`` 撑大的外层 frame；实测
        # 中文字体 + DPI 缩放后单行任务高达 155px，两行 319px > canvas 172px，
        # 第二行会被 canvas 静默裁掉。本阶段任务数很少，普通容器即可稳定显示。
        # 下一阶段若需要长列表，再按真实任务量引入可随窗口 resize 的滚动视口。
        self.task_list = ctk.CTkFrame(self.tasks_card, fg_color="transparent")
        self.task_list.pack(fill="x", padx=8, pady=(4, 9))
        self.empty_label = ctk.CTkLabel(self.task_list, text="暂无下载任务",
                                        font=self.font(11),
                                        text_color=("gray50", "gray60"))
        self.empty_label.pack(pady=26)

    def _build_message(self) -> None:
        # 同一个标签承载红字错误 / 绿字完成 / 灰字提示，避免多套重叠控件。
        self.message_label = ctk.CTkLabel(self.body, text="", font=self.font(11, "bold"),
                                          anchor="w", justify="left", wraplength=640)
        self.message_label.pack(fill="x", padx=4, pady=(4, 0))
        self.message_label.pack_forget()

    # -- 按钮回调（只做本地校验；真实网络请求由注入的后台线程回调发起） ---------
    def _on_sniff_click(self) -> None:
        if not self.is_ready or self._sniffing or self._downloading:
            return
        url = self.url_entry.get().strip()
        if not url:
            self.show_error("INVALID_URL", "")
            return
        if not url.lower().startswith(("http://", "https://")):
            self.show_error("UNSUPPORTED_SCHEME", "")
            return
        self.clear_message()
        self.on_sniff(url)

    def _on_download_click(self) -> None:
        if (not self.is_ready or not self._has_formats
                or self._downloading or self._finished):
            return
        meta = self.selected_format()
        if not meta or not self.current_task_id:
            self.show_error("INVALID_FORMAT_SELECTOR", "")
            return
        self.clear_message()
        # 传元数据而非 formatId：real 模式 /download 收到 formatId 会 422。
        self.on_download(self.current_task_id, meta)

    # -- 引擎联动 -------------------------------------------------------------
    def set_engine_ready(self, ready: bool, port: int = 8730) -> None:
        """仅主线程调用。引擎未就绪 → 状态条红字 + 独占提示 + 全页置灰。"""
        self.is_ready = bool(ready)
        if not self.is_ready:
            self._soft_reset()
        self.status_dot.configure(text_color=GREEN if ready else RED)
        self.status_text.configure(
            text="本机引擎已就绪 · 可直接嗅探下载" if ready
                 else "引擎未启动 · 控制台暂不可用",
            text_color=(GREEN if ready else "#B4443C",
                        GREEN if ready else "#FF8A80"),
        )
        self.status_hint.configure(text=f"http://127.0.0.1:{port}" if ready
                                   else "无需鉴权 · 仅本机")
        if ready:
            self.offline_hint.pack_forget()
        elif self.offline_hint.winfo_manager() == "":
            self.offline_hint.pack(fill="x", pady=(0, 8),
                                   before=self.sniff_card)
        self._refresh_controls()

    def _soft_reset(self) -> None:
        """引擎离开 RUNNING：解除轮询锁定、丢弃已失效 task 的画质结果。

        轮询线程已被 ``_cancel_network`` 停掉，不会再回抛 show_done/show_error；
        若不复位，``_downloading`` 会一直是 True，用户下次启动引擎后按钮仍全灰。
        任务行保留作历史，但当前 task 的画质映射必须清掉——引擎重启后内存
        任务表已空，再点下载只会得到 404 NOT_FOUND。
        """
        self._sniffing = False
        self._downloading = False
        self._has_formats = False
        self._finished = False
        self.current_task_id = None
        self._format_map = {}
        self.format_menu.configure(values=["（暂无画质）"])
        self.format_menu.set("（暂无画质）")
        self.media_label.configure(text="")
        self.format_count.configure(text="")
        if self.result_card.winfo_manager() != "":
            self.result_card.pack_forget()
        self.clear_message()

    def _refresh_controls(self) -> None:
        enabled = self.is_ready and not self._sniffing and not self._downloading
        self._set_entry(self.url_entry, enabled)
        self._set_button(self.sniff_button, enabled,
                         "嗅探中…" if self._sniffing else "开始嗅探")
        # 完成/失败后同一 task 已是引擎终态，再点下载只会撞 409 TASK_STATE_CONFLICT；
        # 直接锁定并提示重新嗅探，比让用户点了再看红字更诚实。
        menu_enabled = enabled and self._has_formats and not self._finished
        try:
            self.format_menu.configure(
                state="normal" if menu_enabled else "disabled",
                fg_color=MENU_ACTIVE if menu_enabled else BTN_DISABLED,
                button_color=MENU_BUTTON_ACTIVE if menu_enabled else BTN_DISABLED,
            )
        except Exception:
            pass
        self._set_button(self.download_button, menu_enabled, "开始下载")

    @staticmethod
    def _set_entry(entry, enabled: bool) -> None:
        try:
            entry.configure(state="normal" if enabled else "disabled")
        except Exception:
            pass

    @staticmethod
    def _set_button(button, enabled: bool, text: str) -> None:
        try:
            button.configure(
                text=text, state="normal" if enabled else "disabled",
                fg_color=BLUE if enabled else BTN_DISABLED,
                hover_color=BLUE_HOVER if enabled else BTN_DISABLED,
            )
        except Exception:
            pass

    # -- 状态渲染（下一阶段后台线程经 post() 调用） ---------------------------
    def show_sniffing(self) -> None:
        self._sniffing = True
        self._has_formats = False
        self._finished = False
        self.format_menu.configure(values=["（暂无画质）"])
        self.format_menu.set("（暂无画质）")
        self.media_label.configure(text="")
        self.format_count.configure(text="")
        if self.result_card.winfo_manager() != "":
            self.result_card.pack_forget()
        self._refresh_controls()

    def show_formats(self, task_id: str, items: list[dict], *, title: str = "") -> None:
        """填充画质下拉框，``label -> 元数据`` 绑定 height / hasVideo / hasAudio。"""
        self._sniffing = False
        self._finished = False
        self.current_task_id = task_id
        labels: list[str] = []
        self._format_map = {}
        for fmt in items or []:
            label = format_label(fmt)
            # 同名画质可能来自不同 formatId，加后缀保证一一对应、不会选错。
            if label in self._format_map:
                label = f"{label} · {fmt.get('formatId', '?')}"
            labels.append(label)
            self._format_map[label] = format_meta(fmt)
        if not labels:
            labels, self._format_map = ["（暂无画质）"], {}
            self._has_formats = False
        else:
            self._has_formats = True
        self.format_menu.configure(values=labels)
        self.format_menu.set(labels[0])
        self.format_count.configure(text=f"{len(items or [])} 个可用画质")
        self.media_label.configure(text=title or task_id)
        if self.result_card.winfo_manager() == "":
            # after=sniff_card 而非 before=tasks_card：消息条也排在 tasks_card 之前，
            # 用 before 会让结果区跑到消息条后面，两种显示顺序下都要保持
            # 「状态 → 嗅探 → 结果 → 消息 → 任务」。
            self.result_card.pack(fill="x", pady=(0, 8), after=self.sniff_card)
        self._refresh_controls()

    def selected_format(self) -> dict | None:
        """当前选项绑定的元数据；网络层据此换算 ``formatSelector``。"""
        return self._format_map.get(self.format_menu.get())

    def selected_format_id(self) -> str | None:
        """仅供诊断 / 冒烟：real 模式下**绝不**把 formatId 发给 ``/download``。"""
        meta = self.selected_format()
        return meta.get("formatId") if meta else None

    def show_downloading(self) -> None:
        """仅主线程调用：进入下载态，锁定输入与按钮，等待进度回抛。"""
        self._downloading = True
        self.clear_message()
        self._refresh_controls()

    def add_task(self, name: str) -> TaskRow:
        if self.empty_label.winfo_manager() != "":
            self.empty_label.pack_forget()
        row = TaskRow(self.task_list, name, self.font)
        self.task_rows.append(row)
        self.task_count.configure(text=f"{len(self.task_rows)} 个任务")
        return row

    def show_done(self, output_path: str = "") -> None:
        self._downloading = False
        self._finished = True
        self.url_entry.configure(state="normal")
        self.url_entry.delete(0, "end")
        text = (f"✓ 下载完成，文件位于 {output_path}" if output_path
                else "✓ 下载完成（引擎未返回文件路径）")
        self._show_message(text, GREEN)
        self._refresh_controls()

    def show_error(self, code: str, message: str = "") -> None:
        self._sniffing = False
        self._downloading = False
        self._finished = True
        text = translate_error(code, message)
        suffix = f"（{code}）" if code else ""
        self._show_message(f"✖ {text}{suffix}", RED)
        self._refresh_controls()

    def show_info(self, text: str) -> None:
        self._show_message(text, ("gray40", "gray65"))

    def clear_message(self) -> None:
        # 必须连文本一起清：只 pack_forget 会留下上一轮的错误文案，重新嗅探时
        # 任何「有没有报错」的判断都会读到陈旧文本而误判（e2e 抓到过）。
        self.message_label.configure(text="")
        if self.message_label.winfo_manager() != "":
            self.message_label.pack_forget()

    def _show_message(self, text: str, color) -> None:
        self.message_label.configure(text=text, text_color=color)
        if self.message_label.winfo_manager() == "":
            # 必须排在 expand=True 的 tasks_card **之前**：pack 先满足先 packed
            # 的固定高度控件，放末尾会被任务区吃光剩余空间，提示整行看不见。
            self.message_label.pack(fill="x", padx=4, pady=(4, 6),
                                    before=self.tasks_card)

    def clear(self) -> None:
        self._sniffing = False
        self._downloading = False
        self._has_formats = False
        self._finished = False
        self.current_task_id = None
        self._format_map = {}
        self.url_entry.configure(state="normal")
        self.url_entry.delete(0, "end")
        self.format_menu.configure(values=["（暂无画质）"])
        self.format_menu.set("（暂无画质）")
        self.media_label.configure(text="")
        self.format_count.configure(text="")
        self.demo_badge.configure(text="")
        if self.result_card.winfo_manager() != "":
            self.result_card.pack_forget()
        for row in self.task_rows:
            try:
                row.destroy()  # 走 TaskRow.destroy：先停脉冲动画再销毁控件
            except Exception:
                pass
        self.task_rows = []
        self.task_count.configure(text="0 个任务")
        if self.empty_label.winfo_manager() == "":
            self.empty_label.pack(pady=26)
        self.clear_message()
        self._refresh_controls()

    def ui_demo(self) -> None:
        """注入**界面预览数据**，绝不发起网络请求。用于截图与 UI 冒烟。"""
        self.clear()
        self.url_entry.insert(0, DEMO_URL)
        self.demo_badge.configure(text="界面预览数据 · 未发起网络请求")
        self.show_formats(DEMO_TASK_ID, DEMO_FORMATS, title=DEMO_TITLE)
        # 演示只放「当前正在下载」的一行，贴合需求语义也让整页在一屏内完整可见；
        # add_task() 本身支持多行，冒烟里单独验证追加与完成态。
        row = self.add_task("示例视频 · 1080p · mp4")
        row.update(percent=62.0, speed_bps=2_516_582, eta_seconds=12,
                   phase="downloading")
        self.show_info("界面预览：画质与进度均为示例数据，未发起网络请求。"
                       "真实下载请粘贴网页链接后点「开始嗅探」。")

"""M15/M16 · 桌面可视化控制面板（customtkinter + tkinter，纯 Python、无重型框架）。

替代 M15 第一步的「纯托盘启动器」成为桌面端入口；托盘降级为**后台模式**：
主界面显示 → 用户点「启动」才拉引擎 → 点 X 隐藏到托盘 → 托盘可重新唤起 →
托盘「退出」彻底杀掉引擎进程树并结束程序。

M16 起主界面拆成 :class:`customtkinter.CTkTabview` 两个标签：

* **引擎设置** —— M15 的全部能力（连接地址、监听设置、启停、日志、环境自检、
  托盘联动）原样保留，只是父容器由主窗口换成第一个 tab；M16.5 在这里新增
  「环境依赖自检」卡片（FFmpeg / Chromium 状态、自定义 FFmpeg 路径、一键装内核）；
* **下载控制台** —— :mod:`download_console` 提供的独立嗅探/下载界面。
  M16 第二步已接通 :mod:`engine_client`：``POST /v1/tasks`` → 每秒轮询
  ``GET /v1/tasks/{id}`` → ``/formats`` → ``POST /download`` → 进度回抛。

header 与黄色安全警告、底部状态栏是**全局常驻**的：切到哪个标签都能看到
引擎状态与「切勿暴露公网」这条信任边界。

设计要点
--------
* **线程模型**：Tk 主循环必须占主线程（Windows 消息循环），``pystray`` 的
  ``Icon.run()`` 放后台线程；本机已实测二者可共存且可干净退出。所有来自
  后台线程（引擎监控 / 托盘菜单 / 环境自检）的界面操作一律经 :meth:`ControlPanel.ui_call`
  投递到主线程队列，绝不跨线程直接碰 Tk 控件。
* **进程管理复用** :mod:`launcher`：``EngineProcess`` 的 taskkill /T /F 进程树清理、
  venv 重定向器孤儿兜底、健康检查、日志重定向全部沿用，本文件不重复实现。
* **设置真实生效**：host/port 传给 ``uvicorn``；输出目录通过环境变量
  ``BGV_OUTPUT_DIR_ALLOWLIST`` 注入引擎（``main._load_output_dir_allowlist`` 读取，
  手机端取 ``/v1/privacy/selfcheck`` 的 ``outputDirAllowlist[0]`` 作为 outputDir）。
* **不自动启动**：默认等用户点按钮；仅当 ``config.json`` 里 ``auto_start=true`` 才自启。
* **网络线程可取消**：每次启停引擎推进 ``_net_gen`` 并置位 ``stop_event``；
  所有 httpx 请求都在 daemon 后台线程，渲染动作经 :meth:`ControlPanel._emit`
  做「投递前 + 执行前」双重代次校验后回抛主线程。用户中途停止引擎时，
  轮询线程最多再等 1 秒就退出，不会碰已销毁的 Tk 控件。
* **M16.5 FFmpeg 路径**：``config.json`` 的 ``ffmpeg_path`` 为空则走系统 PATH；
  非空则启动引擎时以 ``BGV_FFMPEG_PATH`` 注入子进程，引擎侧
  ``services/ffmpeg_path.py`` 统一读取（含同目录 ffprobe 推导）。依赖探测、
  版本号、``playwright install`` 全在后台线程，日志经 ``ui_call`` 回主线程。

验证入口（不需要人工点鼠标）::

    python desktop/gui_app.py --gui-smoke
    python desktop/gui_app.py --gui-smoke \
        --screenshot E:\\tmp\\tab1.png --dark-shot E:\\tmp\\tab1_dark.png \
        --console-shot E:\\tmp\\tab2.png --console-dark-shot E:\\tmp\\tab2_dark.png \
        --console-offline-shot E:\\tmp\\tab2_offline.png

打包：``--noconsole --onefile``，需 hidden-import ``launcher`` / ``gui_app`` /
``download_console`` / ``engine_client`` / ``pystray`` / ``PIL`` /
``customtkinter`` / ``darkdetect`` / ``httpx``。
"""
from __future__ import annotations

import json
import os
import queue
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import Callable

# 源码模式与 PyInstaller 单文件模式下都要能 import 到同目录的 launcher.py。
_SCRIPT_DIR = Path(__file__).resolve().parent
if str(_SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(_SCRIPT_DIR))

import launcher as L  # noqa: E402
import customtkinter as ctk  # noqa: E402
import tkinter as tk  # noqa: E402
import theme as T  # noqa: E402
from tkinter import filedialog, messagebox  # noqa: E402
from download_console import (  # noqa: E402
    DEMO_FORMATS, DownloadConsole, format_label, translate_error,
)
from engine_client import (  # noqa: E402
    EngineClient, extract_error, run_download_flow, run_sniff_flow,
    selector_for_format,
)

APP_NAME = L.APP_NAME
CONFIG_PATH = L.data_dir() / "config.json"

DEFAULT_OUTPUT_DIR = str(Path.home() / "Videos" / "BGV")
HOST_LABELS = {"0.0.0.0": "0.0.0.0（局域网）", "127.0.0.1": "127.0.0.1（仅本机）"}
HOST_VALUES = {v: k for k, v in HOST_LABELS.items()}
APPEARANCE_LABELS = {"system": "跟随系统", "light": "浅色", "dark": "深色"}
APPEARANCE_VALUES = {v: k for k, v in APPEARANCE_LABELS.items()}

# 点 X（关闭窗口）的分流策略。默认 tray：手机 App 可能正连着引擎，或后台有
# 下载任务，关窗即杀对这两个场景是灾难；纯本机用户可自行切成「直接退出」。
CLOSE_ACTION_LABELS = {"tray": "隐藏到托盘", "ask": "每次询问", "exit": "直接退出"}
CLOSE_ACTION_VALUES = {v: k for k, v in CLOSE_ACTION_LABELS.items()}


def close_dialog_copy(busy: bool) -> tuple[str, str]:
    """点 X 确认框的标题与正文：忙时说清退出会中断什么，闲时只问要不要退。"""
    if busy:
        return (
            "引擎正在后台运行",
            "现在退出会停止引擎并中断手机 App 连接与下载任务。\n"
            "最小化到托盘可让它们继续运行。",
        )
    return (
        "确定要退出吗？",
        "退出后引擎停止，手机 App 将无法连接电脑。\n"
        "最小化到托盘可让引擎保持可用。",
    )

DEFAULTS: dict = {
    "host": "0.0.0.0",
    "port": 8730,
    "output_dir": DEFAULT_OUTPUT_DIR,
    "auto_start": False,
    "appearance": "dark",
    "close_action": "tray",
    # M16.5：空串 = 未自定义，走系统 PATH；否则是 ffmpeg.exe 的绝对路径。
    "ffmpeg_path": "",
}

STATE_STOPPED = "stopped"
STATE_STARTING = "starting"
STATE_RUNNING = "running"
STATE_STOPPING = "stopping"
STATE_ERROR = "error"

STATUS_TEXT = {
    STATE_STOPPED: "引擎未启动",
    STATE_STARTING: "正在启动引擎…",
    STATE_RUNNING: "引擎运行中",
    STATE_STOPPING: "正在停止引擎…",
    STATE_ERROR: "引擎异常 / 未就绪",
}
LED_COLOR = {
    STATE_STOPPED: "#E5534B",     # 停 = 红
    STATE_STARTING: "#E5A23C",    # 过渡 = 琥珀
    STATE_RUNNING: "#2EB872",     # 运行 = 绿
    STATE_STOPPING: "#E5A23C",
    STATE_ERROR: "#E5534B",
}
SHIELD_GREEN = (46, 184, 114, 255)
SHIELD_AMBER = (229, 162, 60, 255)
SHIELD_GRAY = (110, 118, 129, 255)
TRAY_COLOR = {
    STATE_STOPPED: L.SHIELD_BLUE,
    STATE_STARTING: SHIELD_AMBER,
    STATE_RUNNING: SHIELD_GREEN,
    STATE_STOPPING: SHIELD_AMBER,
    STATE_ERROR: L.SHIELD_RED,
}

SECURITY_NOTICE = "⚠ 引擎无密码保护，请仅在信任的家庭局域网使用，切勿暴露在公网！"
TRAY_REQUIRED_LABELS = ("打开主界面", "启动引擎", "停止引擎", "退出")
DEP_LOG_HINT = "· 安装日志（点击「一键安装内核」后在此显示实时下载进度）"

# 禁用态必须连底色一起变灰：只靠 CTk 默认的 text_color_disabled，
# 亮蓝/亮红底上的「禁用」仍会被看成可点按钮（截图复核后补的）。
# 2026-11 美化轮：配色整体收敛到 theme.py，这里只留别名兼容旧引用。
BTN_DISABLED = T.BTN_DISABLED
BTN_DISABLED_TEXT = T.BTN_DISABLED_TEXT
BTN_START = T.ACCENT
BTN_START_HOVER = T.ACCENT_HOVER
BTN_STOP = T.DANGER
BTN_STOP_HOVER = T.DANGER_HOVER
BTN_SECONDARY = T.BTN_SECONDARY
BTN_SECONDARY_HOVER = T.BTN_SECONDARY_HOVER

_FONT_FAMILY: str | None = None


def _font(size: int, weight: str = "normal") -> ctk.CTkFont:
    """挑一个能显示中文的字体，避免中文在默认 Roboto 下变成方框。"""
    global _FONT_FAMILY
    if _FONT_FAMILY is None:
        try:
            from tkinter import font as tkfont
            families = set(tkfont.families())
        except Exception:
            families = set()
        for candidate in ("Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC",
                          "Noto Sans CJK SC", "SimHei", "Segoe UI"):
            if candidate in families:
                _FONT_FAMILY = candidate
                break
        else:
            _FONT_FAMILY = "TkDefaultFont"
    return ctk.CTkFont(family=_FONT_FAMILY, size=size, weight=weight)


def _safe_print(text: str) -> None:
    """冒烟输出：``--noconsole`` 的 exe 里 stdout 可能是 GBK，``✖/✓`` 会炸。

    PyInstaller 单文件窗口程序继承的 stdout 用系统 ANSI 编码，而界面文案里有
    ``✖`` (U+2716)、``✓`` (U+2713) 这类 GBK 无法编码的符号。一次
    UnicodeEncodeError 会打断 Tk 的 after 回调链，让冒烟永远走不到退出。
    """
    try:
        print(text, flush=True)
    except UnicodeEncodeError:
        try:
            print(text.encode("ascii", "backslashreplace").decode("ascii"), flush=True)
        except Exception:
            pass
    except Exception:
        pass


def _validate_output_dir(raw: str) -> tuple[bool, str]:
    """轻校验（引擎的 ``validate_output_dir`` 才是权威），只挡明显会被拒的目录。"""
    if not raw or not raw.strip():
        return False, "路径为空"
    if os.pathsep in raw:
        return False, f"路径不能包含 {os.pathsep!r}（会切断白名单）"
    path = Path(raw.strip())
    if not path.is_absolute():
        return False, "必须是绝对路径"
    try:
        resolved = path.resolve()
    except OSError as exc:
        return False, f"路径无法解析：{exc}"
    forbidden = {
        Path(resolved.anchor).resolve(),
        Path.home().resolve(),
        Path.cwd().resolve(),
        L.ENGINE_DIR.resolve(),
    }
    if resolved in forbidden:
        return False, "不能是磁盘根目录 / 用户主目录 / 当前工作目录 / 引擎目录"
    for entry in os.environ.get("PATH", "").split(os.pathsep):
        if not entry:
            continue
        try:
            if resolved == Path(entry).resolve():
                return False, "不能是 PATH 中的目录"
        except OSError:
            continue
    return True, ""


class Config:
    """``desktop/config.json`` 的读写。字段容错、损坏文件自动备份、原子落盘。"""

    def __init__(self, path: Path, data: dict | None = None) -> None:
        self.path = path
        self.data = dict(DEFAULTS)
        if data:
            self.data.update(data)
        self.normalize()

    @classmethod
    def load(cls, path: Path) -> "Config":
        obj = cls(path)
        if path.is_file():
            try:
                raw = json.loads(path.read_text(encoding="utf-8"))
                if isinstance(raw, dict):
                    obj.data.update({k: v for k, v in raw.items() if k in DEFAULTS})
                else:
                    raise ValueError("config.json 顶层不是对象")
            except (OSError, ValueError, TypeError) as exc:
                # 不悄悄吞掉用户配置：原件改名留档，后面按默认值重建。
                try:
                    path.replace(path.with_suffix(".json.bak"))
                except OSError:
                    pass
                L.log(f"config.json 读取失败，已按默认值重建：{exc!r}")
                obj = cls(path)
        obj.normalize()
        return obj

    def normalize(self) -> None:
        d = self.data
        host = str(d.get("host", DEFAULTS["host"]))
        if host not in HOST_LABELS:
            host = DEFAULTS["host"]
        try:
            port = int(d.get("port", DEFAULTS["port"]))
        except (TypeError, ValueError):
            port = DEFAULTS["port"]
        if not 1 <= port <= 65535:
            port = DEFAULTS["port"]
        out = str(d.get("output_dir") or DEFAULT_OUTPUT_DIR).strip()
        if out and not Path(out).is_absolute():
            out = str((Path.cwd() / out).resolve())
        appearance = str(d.get("appearance", "system"))
        if appearance not in APPEARANCE_LABELS:
            appearance = "system"
        close_action = str(d.get("close_action", DEFAULTS["close_action"]))
        if close_action not in CLOSE_ACTION_LABELS:
            close_action = DEFAULTS["close_action"]
        # 路径只做「去引号 + 相对转绝对」的归一化，不检查文件是否存在：
        # 让用户能看见「上次选的路径现在失效了」，而不是悄悄被抹成默认值。
        ffmpeg = str(d.get("ffmpeg_path") or "").strip().strip('"').strip("'")
        if ffmpeg and not Path(ffmpeg).is_absolute():
            ffmpeg = str(Path(ffmpeg).resolve())
        self.data = {
            "host": host,
            "port": port,
            "output_dir": out,
            "auto_start": bool(d.get("auto_start", False)),
            "appearance": appearance,
            "close_action": close_action,
            "ffmpeg_path": ffmpeg,
        }

    def update(self, **kwargs) -> None:
        self.data.update(kwargs)
        self.normalize()

    def __getitem__(self, key: str):
        return self.data[key]

    def save(self) -> None:
        self.normalize()
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_name(self.path.name + ".tmp")
        tmp.write_text(json.dumps(self.data, ensure_ascii=False, indent=2), encoding="utf-8")
        tmp.replace(self.path)


class ControlPanel(ctk.CTk):
    """主界面 + 引擎生命周期 + 后台托盘。"""

    def __init__(self, *, smoke: bool = False, screenshot_path: str | None = None,
                 dark_shot: str | None = None, console_shot: str | None = None,
                 console_dark_shot: str | None = None,
                 console_offline_shot: str | None = None) -> None:
        super().__init__()
        self.smoke = smoke
        self.screenshot_path = screenshot_path
        self.dark_shot = dark_shot
        self.console_shot = console_shot
        self.console_dark_shot = console_dark_shot
        self.console_offline_shot = console_offline_shot
        self.cfg = Config.load(CONFIG_PATH)

        ctk.set_appearance_mode(self.cfg["appearance"])
        ctk.set_default_color_theme("blue")
        # 窗口底统一吃 theme.BG：CTk 默认的灰蓝底与卡片色阶脱节。
        try:
            self.configure(fg_color=T.BG)
        except Exception:
            pass

        self.title(f"{APP_NAME} · 控制面板")
        # 2026-11 美化轮：旧 760x850 接近正方形被嫌「太方」；改横向宽幅
        # 1000x680，Tab① 改双栏（左：连接+控制，右：设置+依赖）适配新比例。
        self.geometry(T.WINDOW_GEOMETRY)
        self.minsize(*T.WINDOW_MIN)
        self.protocol("WM_DELETE_WINDOW", self._on_close_window)

        # -- 运行期状态 -------------------------------------------------------
        self.engine_state = STATE_STOPPED
        self.engine = L.EngineProcess()
        self.icon = None
        self._tray_thread: threading.Thread | None = None
        self._tray_labels: list[str] = []
        self._quitting = False
        self._alive = True
        self._gen = 0
        self._engine_lock = threading.Lock()
        self._ui_queue: queue.Queue = queue.Queue()
        #: M16 第二步：网络流程的代次 + 可中断等待。引擎每次离开 RUNNING 都会
        #: 让上一代全部失效，轮询线程据此安全退出（不碰已销毁的 Tk 控件）。
        self._net_gen = 0
        self._net_stop = threading.Event()
        self._net_cancels = 0
        self._net_threads: list[threading.Thread] = []
        self._save_job: str | None = None
        #: M16.5：安装内核期间的防重入标志 + 最近一次依赖探测结果（供冒烟断言）。
        self._installing = False
        self._dep_info: dict = {}
        self._log_window = None
        self._log_text = None
        self._smoke_results: list[tuple[str, bool, str]] = []
        self._smoke_server_pid: int | None = None
        #: 设置控件的原始主题配色，供「运行中锁定」时覆盖/还原。
        self._settings_look: list[tuple[object, dict]] = []

        self._build_window_icon()
        self._build_ui()
        self._apply_config_to_widgets()
        self._set_state(STATE_STOPPED, "引擎未启动。点击「启动」后在手机 App 填入上方地址。")
        self._setup_tray()
        self.after(80, self._drain_ui_queue)
        threading.Thread(target=self._env_worker, name="bgv-envcheck", daemon=True).start()

        if not smoke and self.cfg["auto_start"]:
            self.after(700, self._on_start)
        if smoke:
            self.after(350, self._smoke_begin)

    # -- 线程安全的界面投递 ---------------------------------------------------
    def ui_call(self, fn: Callable, *args, **kwargs) -> None:
        """后台线程唯一允许的界面操作入口：排进主线程队列，由 _drain_ui_queue 执行。"""
        if self._alive:
            self._ui_queue.put((fn, args, kwargs))

    def _drain_ui_queue(self) -> None:
        if not self._alive:
            return
        try:
            while True:
                fn, args, kwargs = self._ui_queue.get_nowait()
                try:
                    fn(*args, **kwargs)
                except Exception as exc:
                    L.log(f"UI 回调异常: {exc!r}")
        except queue.Empty:
            pass
        if self._alive:
            self.after(80, self._drain_ui_queue)

    # -- 界面构建 -------------------------------------------------------------
    def _build_window_icon(self) -> None:
        try:
            from PIL import ImageTk
            self._win_icon = ImageTk.PhotoImage(L.shield_image(L.SHIELD_BLUE))
            self.iconphoto(True, self._win_icon)
        except Exception as exc:
            L.log(f"窗口图标设置失败（不影响功能）: {exc!r}")

    def _build_ui(self) -> None:
        # ---- 全局：标题 + 外观切换（两个标签共用） ----
        header = ctk.CTkFrame(self, fg_color="transparent")
        header.pack(fill="x", padx=24, pady=(16, 8))
        left = ctk.CTkFrame(header, fg_color="transparent")
        left.pack(side="left", fill="y")
        ctk.CTkLabel(left, text="无痕掠影 · 本地引擎", font=_font(20, "bold"),
                     anchor="w").pack(anchor="w")
        ctk.CTkLabel(left, text="PC 控制面板 · 手机 App 联机 / 本机独立下载",
                     font=_font(11), text_color=T.TEXT_MUTED, anchor="w").pack(anchor="w")
        ctk.CTkLabel(header, text="外观", font=_font(11),
                     text_color=T.TEXT_MUTED).pack(side="right", padx=(0, 8))
        self.appearance_menu = ctk.CTkOptionMenu(
            header, width=110, height=30, values=list(APPEARANCE_LABELS.values()),
            font=_font(11), corner_radius=T.RADIUS,
            fg_color=T.BTN_SECONDARY, button_color=T.BTN_SECONDARY_HOVER,
            button_hover_color=T.BTN_SECONDARY_HOVER,
            command=self._on_appearance_change,
        )
        self.appearance_menu.pack(side="right")

        # ---- 全局：黄色安全警告（无论切到哪个标签都看得到） ----
        warn = ctk.CTkFrame(self, corner_radius=T.RADIUS, fg_color=T.WARN_BG)
        warn.pack(fill="x", padx=24, pady=(4, 10))
        self.warn_label = ctk.CTkLabel(
            warn, text=SECURITY_NOTICE, font=_font(12, "bold"),
            text_color=T.WARN_TEXT, anchor="w", justify="left", wraplength=920,
        )
        self.warn_label.pack(fill="x", padx=14, pady=9)

        # ---- M16：双标签主体 ----
        self.tabs = ctk.CTkTabview(self, fg_color=T.BG, corner_radius=T.RADIUS)
        self.tabs.pack(fill="both", expand=True, padx=24, pady=(0, 8))
        # 标签页本身做成「胶囊」：未选中与底同色、选中吃强调色，弱化到近乎隐形。
        seg = self.tabs._segmented_button
        seg.configure(corner_radius=T.RADIUS, fg_color=T.BG,
                      selected_color=T.ACCENT, selected_hover_color=T.ACCENT_HOVER,
                      unselected_color=T.BG, unselected_hover_color=T.SURFACE_2,
                      # 浅色下选中态是蓝底，文字必须近黑才可读（浅色截图复核抓到）；
                      # 深色下浅灰字在蓝底与暗底上都清晰。
                      text_color=("gray15", "gray75"),
                      font=_font(12, "bold"))
        self.tab_engine = self.tabs.add("引擎设置")
        self.tab_console = self.tabs.add("下载控制台")
        self._build_engine_tab(self.tab_engine)
        self.console = DownloadConsole(
            self.tab_console, font=_font, ui_call=self.ui_call,
            on_sniff=self._on_sniff,
            on_download=self._on_download,
        )

        # ---- 全局：底部状态栏（复制地址 / 下载阶段反馈都写这里） ----
        self.detail_label = ctk.CTkLabel(self, text="", font=_font(10),
                                         text_color=T.TEXT_MUTED, anchor="w",
                                         justify="left", wraplength=930)
        self.detail_label.pack(fill="x", padx=26, pady=(0, 10))

    def _build_engine_tab(self, tab) -> None:
        """M15 的全部控件原样迁到 Tab①：只换父容器与内边距，行为一字不改。

        2026-11 美化轮改为**双栏**：左栏 340px（连接地址 + 引擎控制），
        右栏弹性（设置 + 环境依赖自检）——宽幅窗口下不再竖着一摞四张卡。
        """
        tab.grid_columnconfigure(0, minsize=340, weight=0)
        tab.grid_columnconfigure(1, weight=1)
        tab.grid_rowconfigure(0, weight=1)
        left = ctk.CTkFrame(tab, fg_color="transparent")
        left.grid(row=0, column=0, sticky="nsew", padx=(2, 8), pady=(8, 2))
        right = ctk.CTkFrame(tab, fg_color="transparent")
        right.grid(row=0, column=1, sticky="nsew", padx=(8, 2), pady=(8, 2))

        # -- 左栏 · 引导区：大字连接地址 + 一键复制 --
        addr_card = ctk.CTkFrame(left, corner_radius=T.RADIUS, fg_color=T.SURFACE,
                                 border_width=1, border_color=T.BORDER)
        addr_card.pack(fill="x", pady=(0, 10))
        ctk.CTkLabel(addr_card, text="连接地址（填入手机 App 的引擎地址）",
                     font=_font(11), text_color=T.TEXT_MUTED,
                     anchor="w").pack(anchor="w", padx=18, pady=(14, 0))
        self.addr_label = ctk.CTkLabel(addr_card, text="http://...", font=_font(22, "bold"),
                                       anchor="w", text_color=(T.ACCENT, "#6CB6FF"))
        self.addr_label.pack(anchor="w", padx=18, pady=(4, 8))
        copy_wrap, self.copy_button = T.lift_button(
            addr_card, text="一键复制", width=120, height=34, font=_font(12),
            base=T.ACCENT, hover=T.ACCENT_HOVER, command=self._copy_address)
        copy_wrap.pack(anchor="w", padx=18)
        self.addr_hint = ctk.CTkLabel(addr_card, text="", font=_font(11),
                                      text_color=T.TEXT_MUTED, anchor="w",
                                      justify="left", wraplength=300)
        self.addr_hint.pack(anchor="w", padx=18, pady=(10, 14))
        T.soft_shadow(addr_card)

        # -- 左栏 · 控制区：LED + 状态 + 启停/日志/托盘 --
        control = ctk.CTkFrame(left, corner_radius=T.RADIUS, fg_color=T.SURFACE,
                               border_width=1, border_color=T.BORDER)
        control.pack(fill="x", pady=(0, 6))
        led_row = ctk.CTkFrame(control, fg_color="transparent")
        led_row.pack(fill="x", padx=18, pady=(14, 4))
        self.led = ctk.CTkLabel(led_row, text="●", font=_font(20), width=26)
        self.led.pack(side="left")
        self.status_label = ctk.CTkLabel(led_row, text="", font=_font(13, "bold"),
                                         anchor="w")
        self.status_label.pack(side="left", padx=(4, 0))
        btns = ctk.CTkFrame(control, fg_color="transparent")
        btns.pack(fill="x", padx=18, pady=(4, 6))
        btns.grid_columnconfigure((0, 1), weight=1, uniform="ctl")
        start_wrap, self.start_button = T.lift_button(
            btns, text="启动", width=140, height=36, font=_font(12),
            base=T.ACCENT, hover=T.ACCENT_HOVER,
            text_color_disabled=BTN_DISABLED_TEXT, command=self._on_start,
            enabled=lambda: str(self.start_button.cget("state")) != "disabled")
        start_wrap.grid(row=0, column=0, sticky="ew", padx=(0, 4))
        stop_wrap, self.stop_button = T.lift_button(
            btns, text="停止", width=140, height=36, font=_font(12),
            base=T.DANGER, hover=T.DANGER_HOVER,
            text_color_disabled=BTN_DISABLED_TEXT, command=self._on_stop,
            enabled=lambda: str(self.stop_button.cget("state")) != "disabled")
        stop_wrap.grid(row=0, column=1, sticky="ew", padx=(4, 0))
        tools = ctk.CTkFrame(control, fg_color="transparent")
        tools.pack(fill="x", padx=18, pady=(0, 14))
        tools.grid_columnconfigure((0, 1), weight=1, uniform="tool")
        self.log_button = ctk.CTkButton(
            tools, text="日志", height=30, font=_font(11), corner_radius=T.RADIUS,
            fg_color=T.BTN_SECONDARY, hover_color=T.BTN_SECONDARY, command=self._open_log)
        self.log_button.grid(row=0, column=0, sticky="ew", padx=(0, 4))
        self.hide_button = ctk.CTkButton(
            tools, text="隐藏到托盘", height=30, font=_font(11), corner_radius=T.RADIUS,
            fg_color=T.BTN_SECONDARY, hover_color=T.BTN_SECONDARY, command=self.hide_to_tray)
        self.hide_button.grid(row=0, column=1, sticky="ew", padx=(4, 0))
        T.attach_hover(self.log_button, T.BTN_SECONDARY, T.BTN_SECONDARY_HOVER)
        T.attach_hover(self.hide_button, T.BTN_SECONDARY, T.BTN_SECONDARY_HOVER)
        T.soft_shadow(control)

        # 环境自检属于「引擎设置」页；底部详情状态栏则是全局的。
        self.env_label = ctk.CTkLabel(left, text="环境自检中…", font=_font(10),
                                      text_color=T.TEXT_MUTED, anchor="w")
        self.env_label.pack(fill="x", padx=6, pady=(0, 4))

        # -- 右栏 · 设置区 --
        settings = ctk.CTkFrame(right, corner_radius=T.RADIUS, fg_color=T.SURFACE,
                                border_width=1, border_color=T.BORDER)
        settings.pack(fill="x", pady=(0, 10))
        ctk.CTkLabel(settings, text="设置", font=_font(13, "bold"),
                     anchor="w").pack(anchor="w", padx=18, pady=(12, 4))
        row1 = ctk.CTkFrame(settings, fg_color="transparent")
        row1.pack(fill="x", padx=18, pady=4)
        ctk.CTkLabel(row1, text="监听地址", font=_font(12), width=72,
                     anchor="w").pack(side="left")
        self.host_menu = ctk.CTkOptionMenu(
            row1, width=186, height=30, values=list(HOST_LABELS.values()),
            font=_font(11), corner_radius=T.RADIUS,
            fg_color=T.BTN_SECONDARY, button_color=T.BTN_SECONDARY_HOVER,
            text_color_disabled=BTN_DISABLED_TEXT,
            command=lambda _v: self._schedule_save(),
        )
        self.host_menu.pack(side="left")
        ctk.CTkLabel(row1, text="监听端口", font=_font(12), width=72, anchor="w").pack(
            side="left", padx=(18, 0))
        self.port_entry = ctk.CTkEntry(row1, width=90, height=30, font=_font(12),
                                       corner_radius=T.RADIUS, fg_color=T.SURFACE_2,
                                       border_color=T.BORDER)
        self.port_entry.pack(side="left")
        self.port_entry.bind("<KeyRelease>", lambda _e: self._schedule_save())
        ctk.CTkLabel(settings, text="修改监听地址或端口后，需重启引擎生效", font=_font(10),
                     text_color=T.TEXT_MUTED, anchor="w").pack(
            anchor="w", padx=18, pady=(0, 2))

        row2 = ctk.CTkFrame(settings, fg_color="transparent")
        row2.pack(fill="x", padx=18, pady=4)
        ctk.CTkLabel(row2, text="输出目录", font=_font(12), width=72, anchor="w").pack(side="left")
        self.output_entry = ctk.CTkEntry(row2, height=30, font=_font(11),
                                         corner_radius=T.RADIUS, fg_color=T.SURFACE_2,
                                         border_color=T.BORDER)
        self.output_entry.pack(side="left", fill="x", expand=True, padx=(0, 8))
        self.output_entry.bind("<KeyRelease>", lambda _e: self._schedule_save())
        self.browse_button = ctk.CTkButton(row2, text="浏览…", width=72, height=30,
                                           font=_font(11), corner_radius=T.RADIUS,
                                           fg_color=T.BTN_SECONDARY,
                                           hover_color=T.BTN_SECONDARY,
                                           text_color_disabled=BTN_DISABLED_TEXT,
                                           command=self._browse_output)
        self.browse_button.pack(side="right")
        T.attach_hover(self.browse_button, T.BTN_SECONDARY, T.BTN_SECONDARY_HOVER,
                       enabled=lambda: str(self.browse_button.cget("state")) != "disabled")

        row3 = ctk.CTkFrame(settings, fg_color="transparent")
        row3.pack(fill="x", padx=18, pady=(4, 6))
        self.auto_start_switch = ctk.CTkSwitch(
            row3, text="启动程序时自动运行引擎", font=_font(11),
            progress_color=T.ACCENT, command=self._schedule_save,
        )
        self.auto_start_switch.pack(side="left")
        self.output_hint = ctk.CTkLabel(
            row3, text="输出目录经引擎白名单校验，手机端自动采用第一项",
            font=_font(10), text_color=T.TEXT_MUTED, anchor="e",
        )
        self.output_hint.pack(side="right")

        # 点 X 的行为交给用户选：默认托盘是为手机联机 / 后台下载兜底，
        # 纯本机用户可以选「直接退出」回到直觉的桌面习惯。
        row4 = ctk.CTkFrame(settings, fg_color="transparent")
        row4.pack(fill="x", padx=18, pady=(0, 12))
        ctk.CTkLabel(row4, text="点 × 关闭窗口时", font=_font(11),
                     width=150, anchor="w").pack(side="left")
        self.close_menu = ctk.CTkOptionMenu(
            row4, width=150, values=list(CLOSE_ACTION_LABELS.values()),
            font=_font(11), corner_radius=T.RADIUS,
            fg_color=T.BTN_SECONDARY, button_color=T.BTN_SECONDARY_HOVER,
            button_hover_color=T.BTN_SECONDARY_HOVER,
            text_color_disabled=BTN_DISABLED_TEXT,
            command=lambda _v: self._schedule_save(),
        )
        self.close_menu.pack(side="left", padx=(0, 12))
        self.close_hint = ctk.CTkLabel(
            row4, text="托盘隐藏后引擎与下载继续在后台运行",
            font=_font(10), text_color=T.TEXT_MUTED, anchor="e",
        )
        self.close_hint.pack(side="right")
        T.soft_shadow(settings)

        # -- 右栏 · 环境依赖自检 --
        # 状态圆点用 Tk 的「●」+ text_color 着色，不用 🟢/🔴 emoji：实测在
        # Microsoft YaHei UI 下 emoji 会退化成斜线填充圆圈，颜色语义全丢。
        dep = ctk.CTkFrame(right, corner_radius=T.RADIUS, fg_color=T.SURFACE,
                           border_width=1, border_color=T.BORDER)
        dep.pack(fill="x", pady=(0, 6))
        self.dep_card = dep
        dep_head = ctk.CTkFrame(dep, fg_color="transparent")
        dep_head.pack(fill="x", padx=18, pady=(12, 2))
        ctk.CTkLabel(dep_head, text="环境依赖自检", font=_font(13, "bold"),
                     anchor="w").pack(side="left")
        self.dep_recheck_button = ctk.CTkButton(
            dep_head, text="重新检测", width=84, height=28, font=_font(11),
            corner_radius=T.RADIUS, fg_color=T.BTN_SECONDARY,
            hover_color=T.BTN_SECONDARY, command=self._dep_refresh,
        )
        self.dep_recheck_button.pack(side="right")
        T.attach_hover(self.dep_recheck_button, T.BTN_SECONDARY, T.BTN_SECONDARY_HOVER)

        ff_row = ctk.CTkFrame(dep, fg_color="transparent")
        ff_row.pack(fill="x", padx=18, pady=(4, 2))
        self.dep_ffmpeg_dot = ctk.CTkLabel(ff_row, text="●", font=_font(15), width=18,
                                           text_color=LED_COLOR[STATE_STOPPED])
        self.dep_ffmpeg_dot.pack(side="left")
        self.dep_ffmpeg_label = ctk.CTkLabel(ff_row, text="FFmpeg · 检测中…",
                                             font=_font(12), anchor="w")
        self.dep_ffmpeg_label.pack(side="left", fill="x", expand=True)
        self.dep_ffmpeg_clear = ctk.CTkButton(
            ff_row, text="清除", width=54, height=28, font=_font(11),
            corner_radius=T.RADIUS, fg_color=T.BTN_SECONDARY, hover_color=T.BTN_SECONDARY,
            text_color_disabled=BTN_DISABLED_TEXT, command=self._clear_ffmpeg,
        )
        self.dep_ffmpeg_clear.pack(side="right", padx=(6, 0))
        T.attach_hover(self.dep_ffmpeg_clear, T.BTN_SECONDARY, T.BTN_SECONDARY_HOVER,
                       enabled=lambda: str(self.dep_ffmpeg_clear.cget("state")) != "disabled")
        self.dep_ffmpeg_browse = ctk.CTkButton(
            ff_row, text="浏览…", width=72, height=28, font=_font(11),
            corner_radius=T.RADIUS, text_color_disabled=BTN_DISABLED_TEXT,
            command=self._browse_ffmpeg,
        )
        self.dep_ffmpeg_browse.pack(side="right")
        T.attach_hover(self.dep_ffmpeg_browse, T.BTN_SECONDARY, T.BTN_SECONDARY_HOVER,
                       enabled=lambda: str(self.dep_ffmpeg_browse.cget("state")) != "disabled")
        self.dep_ffmpeg_hint = ctk.CTkLabel(
            dep, text="未自定义路径时，在系统 PATH 中查找 ffmpeg", font=_font(10),
            text_color=T.TEXT_MUTED, anchor="w", justify="left", wraplength=520)
        self.dep_ffmpeg_hint.pack(fill="x", padx=36, pady=(0, 4))

        cr_row = ctk.CTkFrame(dep, fg_color="transparent")
        cr_row.pack(fill="x", padx=18, pady=(2, 4))
        self.dep_chromium_dot = ctk.CTkLabel(cr_row, text="●", font=_font(15), width=18,
                                             text_color=LED_COLOR[STATE_STOPPED])
        self.dep_chromium_dot.pack(side="left")
        self.dep_chromium_label = ctk.CTkLabel(
            cr_row, text="Playwright Chromium · 检测中…", font=_font(12), anchor="w")
        self.dep_chromium_label.pack(side="left", fill="x", expand=True)
        install_wrap, self.dep_install_button = T.lift_button(
            cr_row, text="一键安装内核", width=110, height=28, font=_font(11),
            base=T.ACCENT, hover=T.ACCENT_HOVER,
            text_color_disabled=BTN_DISABLED_TEXT, command=self._on_install_chromium,
            enabled=lambda: str(self.dep_install_button.cget("state")) != "disabled")
        install_wrap.pack(side="right")
        # 日志区**常驻可见**。试过「点安装才 pack 展开」：卡片变高会把下方控制区
        # 整条挤出窗口（真机截图复核抓到），布局还会突然跳动。固定占位既不裁切，
        # 也不打断既有布局；空态写一行占位提示，不留一块突兀的空白。
        self.dep_log = ctk.CTkTextbox(dep, height=68, font=_font(10),
                                      corner_radius=T.RADIUS, fg_color=T.SURFACE_2,
                                      border_color=T.BORDER)
        self.dep_log.pack(fill="x", padx=18, pady=(0, 14))
        self.dep_log.configure(state="disabled")
        self._dep_log_clear()
        T.soft_shadow(dep)

        # 记下主题原始配色：引擎运行时把设置区统一刷成灰色禁用态，
        # 否则 CTk 的 disabled 只改文字颜色，白底输入框/蓝底下拉框仍像可编辑。
        for widget in self._lockable_widgets():
            saved: dict = {}
            for key in ("fg_color", "button_color", "border_color"):
                try:
                    saved[key] = widget.cget(key)
                except Exception:  # 部分控件没有该选项
                    pass
            self._settings_look.append((widget, saved))

        # 悬浮动画的「可动画」谓词：引擎运行中被置灰的按钮不做颜色动画，
        # 配色完全交给 _paint_settings / _paint_button（谓词已在各自创建处挂好）。

    def _lockable_widgets(self) -> tuple:
        """引擎运行时需一并置灰的设置类控件；构建与状态切换共用，防漏改。"""
        return (self.host_menu, self.port_entry, self.output_entry, self.browse_button,
                self.dep_ffmpeg_browse, self.dep_ffmpeg_clear, self.dep_install_button)

    # -- 下载控制台网络层（M16 第二步：全部 httpx 请求在后台线程） -------------
    def _cancel_network(self) -> None:
        """让当前代的全部轮询线程失效。主线程/托盘线程都可调用。

        置位旧 event 让 ``stop_event.wait(1)`` 立刻返回；换一个新 event 给下一代，
        避免「上一代已停止」把下次启动的流程也误停。``_net_gen`` 自增则让已经
        排进 ``_ui_queue`` 的旧回调在执行前被 guarded 丢弃。
        """
        self._net_gen += 1
        self._net_cancels += 1
        self._net_stop.set()
        self._net_stop = threading.Event()

    def _spawn_network(self, name: str, target, *args) -> None:
        self._net_threads = [t for t in self._net_threads if t.is_alive()]
        thread = threading.Thread(target=target, args=args, name=name, daemon=True)
        self._net_threads.append(thread)
        thread.start()

    def _emit(self, gen: int, stop: threading.Event, fn, *args, **kwargs) -> None:
        """网络线程唯一的界面出口：**投递前 + 执行前**各校验一次代次。

        Tk 控件只能在主线程碰；这里把渲染动作包成 guarded 再交给 ``ui_call``。
        即使停止引擎与队列排空之间有时间差，guarded 也会在真正执行前发现代次
        已失效并直接 return，不会对已销毁/已置灰的控件调用 configure。
        """
        if (gen != self._net_gen or stop.is_set() or not self._alive
                or self.engine_state != STATE_RUNNING):
            return

        def guarded(_fn=fn, _args=args, _kwargs=kwargs) -> None:
            if (gen != self._net_gen or stop.is_set() or not self._alive
                    or self.engine_state != STATE_RUNNING):
                return
            _fn(*_args, **_kwargs)

        self.ui_call(guarded)

    def _on_sniff(self, url: str) -> None:
        """主线程：URL 已在控制台本地校验；这里只切 UI 态并起后台线程。"""
        if self.engine_state != STATE_RUNNING:
            return
        self.console.show_sniffing()
        port = self.engine.active_port or self.cfg["port"]
        output_dir = self.cfg["output_dir"]
        gen, stop = self._net_gen, self._net_stop
        self._set_detail(f"正在嗅探：{url}")
        self._spawn_network("bgv-net-sniff", self._sniff_worker,
                            gen, stop, url, output_dir, port)

    def _sniff_worker(self, gen: int, stop: threading.Event, url: str,
                      output_dir: str, port: int) -> None:
        """后台线程：建任务 → 每秒轮询 → awaiting_selection 取画质表。"""
        emit = lambda fn, *a, **kw: self._emit(gen, stop, fn, *a, **kw)  # noqa: E731
        with EngineClient(f"http://127.0.0.1:{port}") as client:
            run_sniff_flow(
                client, url=url, output_dir=output_dir, emit=emit, stop_event=stop,
                on_formats=self.console.show_formats,
                on_error=self.console.show_error,
                on_status=self._set_detail,
            )

    def _on_download(self, task_id: str, meta: dict) -> None:
        """主线程：把画质元数据换算成协议 selector，建任务行后起后台线程。

        ``meta`` 只有 ``height / hasVideo / hasAudio``（+诊断用 formatId）。
        这里**绝不**构造 ``strategy="exact"`` / ``formatId``：real 模式的
        ``services/formats.py`` 会直接抛 ``exact_format_id_unsupported``。
        """
        if self.engine_state != STATE_RUNNING:
            return
        selector = selector_for_format(meta)
        label = self.console.format_menu.get()
        self.console.show_downloading()
        row = self.console.add_task(label)
        row.update(percent=0.0, phase="queued")
        port = self.engine.active_port or self.cfg["port"]
        gen, stop = self._net_gen, self._net_stop
        self._set_detail(f"已提交下载：{label} → {json.dumps(selector, ensure_ascii=False)}"
                         "（未发送 formatId）")
        self._spawn_network("bgv-net-download", self._download_worker,
                            gen, stop, task_id, selector, row, port)

    def _download_worker(self, gen: int, stop: threading.Event, task_id: str,
                         selector: dict, row, port: int) -> None:
        """后台线程：触发下载 → 每秒轮询进度 → done / failed 收尾。"""
        emit = lambda fn, *a, **kw: self._emit(gen, stop, fn, *a, **kw)  # noqa: E731
        with EngineClient(f"http://127.0.0.1:{port}") as client:
            run_download_flow(
                client, task_id=task_id, selector=selector, emit=emit, stop_event=stop,
                on_progress=row.update,
                on_done=self.console.show_done,
                on_error=self.console.show_error,
                on_status=self._set_detail,
            )

    # -- 配置与控件 -----------------------------------------------------------
    def _apply_config_to_widgets(self) -> None:
        self.host_menu.set(HOST_LABELS[self.cfg["host"]])
        self.port_entry.delete(0, "end")
        self.port_entry.insert(0, str(self.cfg["port"]))
        self.output_entry.delete(0, "end")
        self.output_entry.insert(0, self.cfg["output_dir"])
        self.appearance_menu.set(APPEARANCE_LABELS[self.cfg["appearance"]])
        self.close_menu.set(CLOSE_ACTION_LABELS[self.cfg["close_action"]])
        if self.cfg["auto_start"]:
            self.auto_start_switch.select()
        else:
            self.auto_start_switch.deselect()
        if self.cfg["ffmpeg_path"]:
            self.dep_ffmpeg_hint.configure(
                text=f"已配置自定义路径：{self.cfg['ffmpeg_path']}（重启引擎后生效）")
        self._refresh_address()

    def _schedule_save(self) -> None:
        if self._save_job is not None:
            try:
                self.after_cancel(self._save_job)
            except Exception:
                pass
        self._save_job = self.after(400, self._commit_settings)

    def _flush_settings(self) -> None:
        if self._save_job is not None:
            try:
                self.after_cancel(self._save_job)
            except Exception:
                pass
            self._save_job = None
        self._commit_settings()

    def _on_appearance_change(self, _value: str) -> None:
        self._schedule_save()

    def _commit_settings(self) -> bool:
        """把控件值写回 config.json。返回是否全部合法。"""
        self._save_job = None
        host = HOST_VALUES.get(self.host_menu.get(), DEFAULTS["host"])
        raw_port = self.port_entry.get().strip()
        try:
            port = int(raw_port)
        except ValueError:
            port = -1
        if not 1 <= port <= 65535:
            self._set_detail(f"端口必须是 1–65535 的整数，已忽略「{raw_port}」")
            self.port_entry.delete(0, "end")
            self.port_entry.insert(0, str(self.cfg["port"]))
            return False
        out = self.output_entry.get().strip()
        ok, reason = _validate_output_dir(out)
        if not ok:
            self._set_detail(f"输出目录不合适：{reason}（引擎会拒绝写入），已保留原值")
            self.output_entry.delete(0, "end")
            self.output_entry.insert(0, self.cfg["output_dir"])
            if not self.smoke:
                messagebox.showwarning("输出目录不合适", f"{reason}\n\n引擎会拒绝写入该目录。")
            return False

        self.cfg.update(
            host=host,
            port=port,
            output_dir=out,
            auto_start=bool(self.auto_start_switch.get()),
            appearance=APPEARANCE_VALUES.get(self.appearance_menu.get(), "system"),
            close_action=CLOSE_ACTION_VALUES.get(self.close_menu.get(),
                                                 DEFAULTS["close_action"]),
        )
        self.cfg.save()
        ctk.set_appearance_mode(self.cfg["appearance"])
        self._refresh_address()
        self._set_detail(f"设置已保存到 {CONFIG_PATH.name}"
                         + ("；引擎运行中，重启后生效" if self.engine_state in (STATE_STARTING, STATE_RUNNING) else ""))
        return True

    def _browse_output(self) -> None:
        initial = self.output_entry.get().strip() or DEFAULT_OUTPUT_DIR
        try:
            chosen = filedialog.askdirectory(parent=self, title="选择下载输出目录",
                                             initialdir=initial if Path(initial).is_dir() else str(Path.home()))
        except Exception as exc:
            self._set_detail(f"打开目录选择器失败：{exc!r}")
            return
        if chosen:
            self.output_entry.delete(0, "end")
            self.output_entry.insert(0, str(Path(chosen).resolve()))
            self._flush_settings()

    def _refresh_address(self) -> None:
        port = self.cfg["port"]
        if self.cfg["host"] == "127.0.0.1":
            url = f"http://127.0.0.1:{port}"
            hint = "仅本机模式：手机无法连接。要与手机联机，请把监听地址改成 0.0.0.0（局域网）。"
        else:
            url = L.connection_url(port)
            hint = ("手机与电脑需连接同一 WiFi；把上面地址填入 App 的引擎地址（勿填 0.0.0.0，"
                    f"须带 http://）。打不开时请在 Windows 防火墙放行 TCP {port}。")
        self.addr_label.configure(text=url)
        self.addr_hint.configure(text=hint)

    def _copy_address(self) -> None:
        url = self.addr_label.cget("text")
        try:
            self.clipboard_clear()
            self.clipboard_append(url)
            self.update()
            self.copy_button.configure(text="已复制 ✓")
            self.after(1500, lambda: self.copy_button.configure(text="一键复制"))
            self._set_detail(f"已复制连接地址：{url}")
        except Exception as exc:
            self._set_detail(f"复制失败：{exc!r}")

    # -- 状态与按钮 -----------------------------------------------------------
    def _set_detail(self, text: str, *, error: bool = False) -> None:
        self.detail_label.configure(
            text=text.replace("\n", " ")[:220],
            text_color=("#B4443C", "#FF8A80") if error else ("gray40", "gray65"),
        )

    def _set_state(self, state: str, detail: str = "") -> None:
        """仅主线程调用。统一刷新指示灯、按钮、托盘图标与底部文案。"""
        previous = self.engine_state
        # 离开 RUNNING（用户点停止 / 引擎意外退出 / 重启）立刻让轮询线程失效。
        if state != STATE_RUNNING and previous == STATE_RUNNING:
            self._cancel_network()
        self.engine_state = state
        self.led.configure(text_color=LED_COLOR[state])
        self.status_label.configure(text=STATUS_TEXT[state])
        running = state in (STATE_STARTING, STATE_RUNNING, STATE_STOPPING)
        stoppable = state in (STATE_STARTING, STATE_RUNNING)
        self._safe_state(self.start_button, "disabled" if running else "normal")
        self._safe_state(self.stop_button, "normal" if stoppable else "disabled")
        self._paint_button(self.start_button, running, BTN_START, BTN_START_HOVER)
        self._paint_button(self.stop_button, not stoppable, BTN_STOP, BTN_STOP_HOVER)
        for widget in self._lockable_widgets():
            self._safe_state(widget, "disabled" if running else "normal")
        self._paint_settings(running)
        if detail:
            self._set_detail(detail, error=(state == STATE_ERROR))
        self._update_tray_icon()
        # M16 核心架构第 1 条：控制台只在引擎真正 RUNNING 时可用，其余状态
        # 一律置灰并给出「请先启动引擎」的提示，避免用户对着死控件发请求。
        if getattr(self, "console", None) is not None:
            self.console.set_engine_ready(
                state == STATE_RUNNING,
                self.engine.active_port or self.cfg["port"],
            )
        if state == STATE_ERROR and detail and not self.smoke:
            self.after(100, lambda: messagebox.showerror(
                "引擎启动失败", f"{detail}\n\n完整日志见「日志」按钮。"))

    @staticmethod
    def _safe_state(widget, value: str) -> None:
        try:
            widget.configure(state=value)
        except Exception as exc:
            L.log(f"设置控件状态失败: {exc!r}")

    def _paint_settings(self, disabled: bool) -> None:
        for widget, saved in self._settings_look:
            try:
                if disabled:
                    paint = {"fg_color": BTN_DISABLED}
                    if "button_color" in saved:
                        paint["button_color"] = BTN_DISABLED
                    widget.configure(**paint)
                elif saved:
                    widget.configure(**saved)
            except Exception as exc:
                L.log(f"设置控件配色失败: {exc!r}")

    @staticmethod
    def _paint_button(button, disabled: bool, active: str, hover: str) -> None:
        try:
            if disabled:
                button.configure(fg_color=T.BTN_DISABLED, hover_color=T.BTN_DISABLED)
            else:
                # hover_color 保持与 fg_color 同色（惰性）：悬浮过渡交给
                # theme.attach_hover 的 200ms 分帧动画，避免 CTk 瞬时变色抢跑。
                button.configure(fg_color=active, hover_color=active)
        except Exception as exc:
            L.log(f"设置按钮配色失败: {exc!r}")

    # -- 引擎启停 -------------------------------------------------------------
    @staticmethod
    def _build_engine_env(output_dir: str, ffmpeg_path: str = "") -> dict:
        """组装注入引擎子进程的环境变量；单独成函数便于冒烟硬断言。"""
        extra = {"BGV_OUTPUT_DIR_ALLOWLIST": output_dir}
        if ffmpeg_path:
            # M16.5：自定义 FFmpeg 绝对路径 → BGV_FFMPEG_PATH（引擎侧优先读取）
            extra[L.FFMPEG_ENV] = ffmpeg_path
        return extra

    def _on_start(self) -> None:
        if self.engine_state in (STATE_STARTING, STATE_RUNNING, STATE_STOPPING):
            return
        self._flush_settings()
        host, port, out = self.cfg["host"], self.cfg["port"], self.cfg["output_dir"]
        if L.port_in_use(port):
            self._set_state(STATE_ERROR,
                            f"端口 {port} 已被占用：可能已有另一个引擎实例。关掉旧实例或换个端口再试。")
            return
        self._gen += 1
        gen = self._gen
        self._set_state(STATE_STARTING, f"正在启动引擎（{host}:{port}）…")
        ffmpeg_path = str(self.cfg["ffmpeg_path"] or "").strip()
        threading.Thread(target=self._start_worker,
                         args=(gen, host, port, out, ffmpeg_path),
                         name="bgv-engine-start", daemon=True).start()

    def _start_worker(self, gen: int, host: str, port: int, out: str,
                      ffmpeg_path: str = "") -> None:
        if self._quitting or self._gen != gen:
            return
        if L.port_in_use(port):
            self.ui_call(self._set_state, STATE_ERROR, f"端口 {port} 已被占用，启动取消。")
            return
        try:
            # BGV_OUTPUT_DIR_ALLOWLIST 是引擎唯一的输出目录配置入口；手机端从
            # /v1/privacy/selfcheck 取第一项作为 outputDir，所以这里必须注入。
            with self._engine_lock:
                if self._quitting or self._gen != gen:
                    return
                self.engine.start(host=host, port=port,
                                  extra_env=self._build_engine_env(out, ffmpeg_path))
            proc = self.engine.proc
        except Exception as exc:
            self.ui_call(self._set_state, STATE_ERROR,
                         f"拉起 uvicorn 失败：{exc!r} | {L.read_log_tail(8)}")
            return

        ok, detail = L.wait_until_ready(f"http://127.0.0.1:{port}", proc, L.READY_TIMEOUT_SECONDS)
        if self._quitting or self._gen != gen:
            return
        if not ok:
            with self._engine_lock:
                self.engine.stop(port)
            self.ui_call(self._set_state, STATE_ERROR, detail)
            return

        url = f"http://127.0.0.1:{port}" if host == "127.0.0.1" else L.connection_url(port)
        self.ui_call(self._set_state, STATE_RUNNING, f"引擎已就绪 · {url} · 输出目录 {out}")
        self._notify(f"引擎已启动：{url}", "无痕掠影 · 连接地址")

        # 持续盯住进程：意外退出 → 红灯 + 托盘红图标 + 弹窗（smoke 下不弹）。
        while not self._quitting and self._gen == gen:
            exited = self.engine.poll()
            if exited is not None:
                self.ui_call(self._set_state, STATE_ERROR,
                             f"引擎进程意外退出（exit={exited}）。\n{L.read_log_tail(12)}")
                self._notify("引擎意外退出，请查看日志", "无痕掠影 · 异常")
                return
            time.sleep(1.0)

    def _on_stop(self) -> None:
        if self.engine_state not in (STATE_STARTING, STATE_RUNNING):
            return
        self._gen += 1  # 让启动/监控线程失效，避免用户主动停止被误报成崩溃
        port = self.engine.active_port or self.cfg["port"]
        self._set_state(STATE_STOPPING, "正在停止引擎…")
        threading.Thread(target=self._stop_worker, args=(port,), daemon=True,
                         name="bgv-engine-stop").start()

    def _stop_worker(self, port: int) -> None:
        with self._engine_lock:
            clean = self.engine.stop(port)
        self.ui_call(self._set_state, STATE_STOPPED,
                     "引擎已停止，端口已释放。" if clean
                     else "引擎进程未能确认退出，请检查任务管理器。")
        self._notify("引擎已停止", APP_NAME)

    # -- 日志 -----------------------------------------------------------------
    def _open_log(self) -> None:
        if self._log_window is not None:
            try:
                if self._log_window.winfo_exists():
                    self._refresh_log_window()
                    self._log_window.lift()
                    return
            except Exception:
                pass
        win = ctk.CTkToplevel(self)
        win.title("引擎日志 · engine.log")
        win.geometry("780x440")
        win.transient(self)
        text = ctk.CTkTextbox(win, font=_font(11))
        text.pack(fill="both", expand=True, padx=12, pady=(12, 6))
        bar = ctk.CTkFrame(win, fg_color="transparent")
        bar.pack(fill="x", padx=12, pady=(0, 10))
        ctk.CTkButton(bar, text="刷新", width=80, font=_font(11),
                      command=self._refresh_log_window).pack(side="left")
        ctk.CTkButton(bar, text="用系统程序打开", width=130, font=_font(11),
                      command=self._open_log_external).pack(side="left", padx=8)
        ctk.CTkButton(bar, text="关闭", width=80, font=_font(11),
                      command=win.destroy).pack(side="right")
        self._log_window, self._log_text = win, text
        self._refresh_log_window()

    def _refresh_log_window(self) -> None:
        try:
            if self._log_text is None or not self._log_text.winfo_exists():
                return
            self._log_text.configure(state="normal")
            self._log_text.delete("1.0", "end")
            self._log_text.insert("1.0", L.read_log_tail(400))
            self._log_text.configure(state="disabled")
            self._log_text.see("end")
        except Exception as exc:
            L.log(f"刷新日志窗口失败: {exc!r}")

    def _open_log_external(self) -> None:
        try:
            if not L.LOG_PATH.exists():
                L.LOG_PATH.touch()
            if L.IS_WINDOWS:
                os.startfile(str(L.LOG_PATH))  # noqa: S606 - Windows only
            else:
                subprocess.Popen(["xdg-open", str(L.LOG_PATH)])
        except Exception as exc:
            self._set_detail(f"打不开日志：{exc!r} | 路径 {L.LOG_PATH}")

    # -- 环境依赖自检（M16.5：卡片 + 路径持久化 + 一键安装） -------------------
    def _env_worker(self, show_dialog: bool = False) -> None:
        """后台线程只算事实（含 ``ffmpeg -version`` 子进程），不碰任何 Tk 控件。"""
        info = L.probe_dependencies(self.cfg["ffmpeg_path"])
        self.ui_call(self._env_report, info, show_dialog)

    def _dep_refresh(self, show_dialog: bool = True) -> None:
        """「重新检测」：另起后台探测，避免版本号探测阻塞主循环。"""
        self.dep_ffmpeg_label.configure(text="FFmpeg · 检测中…")
        self.dep_chromium_label.configure(text="Playwright Chromium · 检测中…")
        threading.Thread(target=self._env_worker, args=(show_dialog,),
                         name="bgv-envcheck", daemon=True).start()

    def _env_report(self, info: dict, show_dialog: bool = False) -> None:
        """仅主线程：把探测结果画到卡片上。"""
        self._dep_info = dict(info)
        found = bool(info.get("ffmpeg_found"))
        version = str(info.get("ffmpeg_version") or "")
        custom = info.get("ffmpeg_source") == "custom"
        path = str(info.get("ffmpeg_path") or "")
        self.dep_ffmpeg_dot.configure(
            text_color=LED_COLOR[STATE_RUNNING] if found else LED_COLOR[STATE_STOPPED])
        if found:
            short = version if len(version) <= 28 else version[:27] + "…"
            self.dep_ffmpeg_label.configure(
                text=f"FFmpeg · 已就绪（{short or '版本未知'}）")
            self.dep_ffmpeg_hint.configure(
                text=f"来源：{'自定义路径' if custom else '系统 PATH'} · {path}")
        else:
            self.dep_ffmpeg_label.configure(text="FFmpeg · 未找到")
            self.dep_ffmpeg_hint.configure(text=(
                "自定义路径已失效，请点「浏览…」重新选择 ffmpeg.exe" if custom
                else "系统 PATH 里没有 ffmpeg；可点「浏览…」指定绝对路径"))
        chromium = bool(info.get("chromium"))
        self.dep_chromium_dot.configure(
            text_color=LED_COLOR[STATE_RUNNING] if chromium else LED_COLOR[STATE_STOPPED])
        self.dep_chromium_label.configure(
            text="Playwright Chromium · 已就绪" if chromium
            else "Playwright Chromium · 未安装")
        self.dep_install_button.configure(
            text="重新安装内核" if chromium else "一键安装内核")
        self.env_label.configure(
            text=f"环境自检 · FFmpeg {'已就绪' if found else '未找到'} · "
                 f"Playwright Chromium {'已就绪' if chromium else '未安装（可一键安装）'}")
        if not chromium:
            self._dep_log_show()
            self._dep_log_append("· 缺少浏览器内核；点「一键安装内核」下载 Chromium。")
        # 卡片本身已经把红字/路径显示得很清楚，首次自检不再弹模态框打断用户；
        # 只有用户主动点「重新检测」仍缺失时才给完整安装指引。
        if show_dialog and not self.smoke:
            if not found:
                messagebox.showwarning("环境自检 · 缺少 FFmpeg", L.FFMPEG_HINT)
            if not chromium:
                messagebox.showwarning("环境自检 · 缺少 Chromium", L.CHROMIUM_HINT)

    def _browse_ffmpeg(self) -> None:
        """选本机 ffmpeg.exe → 校验可执行 → 绝对路径写进 config.json。"""
        configured = str(self.cfg["ffmpeg_path"] or "")
        initial = (str(Path(configured).parent) if configured
                   and Path(configured).parent.is_dir() else str(Path.home()))
        try:
            chosen = filedialog.askopenfilename(
                parent=self, title="选择 ffmpeg 可执行文件", initialdir=initial,
                filetypes=[("FFmpeg", "ffmpeg.exe"), ("可执行文件", "*.exe"),
                           ("所有文件", "*.*")],
            )
        except Exception as exc:
            self._set_detail(f"打开文件选择器失败：{exc!r}")
            return
        if not chosen:
            return
        path = str(Path(chosen).resolve())
        info = L.probe_dependencies(path)
        if not info["ffmpeg_found"] or not info["ffmpeg_version"]:
            self._set_detail(f"所选文件不是可执行的 FFmpeg：{path}", error=True)
            if not self.smoke:
                messagebox.showerror("无效的 FFmpeg 路径",
                                     f"无法从该文件读取版本号：\n{path}\n\n请选择 ffmpeg.exe。")
            return
        self.cfg.update(ffmpeg_path=path)
        self.cfg.save()
        self._set_detail(f"已保存自定义 FFmpeg 路径：{path}（重启引擎后生效）")
        self._dep_log_show()
        self._dep_log_append(f"· FFmpeg 路径已设为：{path}")
        self._dep_refresh(show_dialog=False)

    def _clear_ffmpeg(self) -> None:
        """清除自定义路径，回到系统 PATH 查找。"""
        self.cfg.update(ffmpeg_path="")
        self.cfg.save()
        self._set_detail("已清除自定义 FFmpeg 路径，改用系统 PATH（重启引擎后生效）")
        self._dep_refresh(show_dialog=False)

    def _on_install_chromium(self) -> None:
        """「一键安装内核」：后台跑 playwright install，日志逐行回主线程。"""
        if self._installing:
            return
        self._installing = True
        self._dep_log_clear(placeholder=False)
        self._dep_log_show()
        self._dep_log_append("> playwright install chromium")
        self._dep_log_append("· 首次安装需下载约 100 MB；下方为实时安装日志…")
        self._safe_state(self.dep_install_button, "disabled")
        self.dep_install_button.configure(text="安装中…")
        self.dep_chromium_label.configure(text="Playwright Chromium · 正在安装…")
        L.install_chromium_async(
            lambda ok, detail: self.ui_call(self._install_done, ok, detail),
            on_line=lambda line: self.ui_call(self._dep_log_append, line),
        )

    def _install_done(self, ok: bool, detail: str) -> None:
        """仅主线程：恢复按钮、补一条收尾日志、复查内核状态。"""
        self._installing = False
        running = self.engine_state in (STATE_STARTING, STATE_RUNNING, STATE_STOPPING)
        self.dep_install_button.configure(text="一键安装内核")
        self._safe_state(self.dep_install_button, "disabled" if running else "normal")
        self._paint_button(self.dep_install_button, running, BTN_START, BTN_START_HOVER)
        if detail:
            self._dep_log_append(detail[-400:])
        self._dep_log_append("✓ 安装完成，正在复查内核状态…" if ok
                             else "✖ 安装失败，请查看上方日志")
        if not ok and not self.smoke:
            messagebox.showerror(
                "Chromium 安装失败",
                "可手动执行：\n  .venv\\Scripts\\python.exe -m playwright install chromium\n\n"
                f"错误摘要：\n{detail}")
        self._dep_refresh(show_dialog=False)

    def _dep_log_show(self) -> None:
        """幂等确保日志区可见（常态已常驻；这里只是防御性兜底，不再改变布局）。"""
        if self.dep_log.winfo_manager() == "":
            self.dep_log.pack(fill="x", padx=16, pady=(0, 10))

    def _dep_log_append(self, line: str) -> None:
        """仅主线程。保留最近 120 行，避免长时间安装把 Textbox 撑爆。"""
        try:
            self.dep_log.configure(state="normal")
            self.dep_log.insert("end", line.rstrip() + "\n")
            count = int(str(self.dep_log.index("end-1c")).split(".")[0])
            if count > 120:
                self.dep_log.delete("1.0", f"{count - 120}.0")
            self.dep_log.see("end")
            self.dep_log.configure(state="disabled")
        except Exception as exc:
            L.log(f"安装日志写入失败: {exc!r}")

    def _dep_log_clear(self, placeholder: bool = True) -> None:
        try:
            self.dep_log.configure(state="normal")
            self.dep_log.delete("1.0", "end")
            if placeholder:
                self.dep_log.insert("1.0", DEP_LOG_HINT + "\n")
            self.dep_log.configure(state="disabled")
        except Exception as exc:
            L.log(f"清空安装日志失败: {exc!r}")

    # -- 托盘（后台模式） -----------------------------------------------------
    def _setup_tray(self) -> None:
        try:
            import pystray
        except ImportError:
            self._set_detail("未安装 pystray，托盘不可用；请关闭窗口前先停止引擎。")
            return
        menu = pystray.Menu(
            pystray.MenuItem("打开主界面", self._tray_open, default=True),
            pystray.MenuItem("显示连接地址", self._tray_address),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("启动引擎", self._tray_start),
            pystray.MenuItem("停止引擎", self._tray_stop),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("查看引擎日志", self._tray_log),
            pystray.MenuItem("退出", self._tray_quit),
        )
        self._tray_labels = [getattr(item, "text", "") for item in menu]
        self.icon = pystray.Icon("bgv-control-panel", L.shield_image(L.SHIELD_BLUE), APP_NAME, menu)
        self._tray_thread = threading.Thread(target=self._tray_run, name="bgv-tray", daemon=True)
        self._tray_thread.start()

    def _tray_run(self) -> None:
        try:
            self.icon.run()
        except Exception as exc:
            L.log(f"托盘循环异常: {exc!r}")

    def _tray_open(self, icon=None, item=None) -> None:
        self.ui_call(self.show_window)

    def _tray_start(self, icon=None, item=None) -> None:
        self.ui_call(self._on_start)

    def _tray_stop(self, icon=None, item=None) -> None:
        self.ui_call(self._on_stop)

    def _tray_quit(self, icon=None, item=None) -> None:
        self.quit_app()

    def _tray_address(self, icon=None, item=None) -> None:
        self.ui_call(self._show_address_dialog)

    def _tray_log(self, icon=None, item=None) -> None:
        self.ui_call(self._open_log)

    def _show_address_dialog(self) -> None:
        if self.smoke:
            return
        url = self.addr_label.cget("text")
        messagebox.showinfo(
            "连接地址",
            f"{APP_NAME}\n\n连接地址（填入手机 App 设置页）：\n{url}\n\n"
            "  · 手机与电脑连同一个 WiFi\n"
            "  · 地址必须带 http:// 前缀，不要填 0.0.0.0\n"
            "  · 打不开请在 Windows 防火墙放行对应 TCP 端口\n\n"
            "安全提示：引擎无入站鉴权，仅在可信局域网使用，切勿暴露公网。",
        )

    def _update_tray_icon(self) -> None:
        if self.icon is None:
            return
        try:
            self.icon.icon = L.shield_image(TRAY_COLOR[self.engine_state])
            self.icon.title = f"{APP_NAME} · {STATUS_TEXT[self.engine_state]}"
        except Exception as exc:
            L.log(f"更新托盘图标失败（不影响菜单）: {exc!r}")

    def _notify(self, message: str, title: str = APP_NAME) -> None:
        try:
            if self.icon is not None and self.icon.visible:
                self.icon.notify(message, title)
        except Exception as exc:
            L.log(f"托盘气泡失败: {exc!r}")

    # -- 点 X 关闭窗口的分流 --------------------------------------------------
    def _engine_busy(self) -> bool:
        """引擎或嗅探/下载任务是否有活干——决定「直接退出」要不要弹确认。"""
        if self.engine_state in (STATE_STARTING, STATE_RUNNING):
            return True
        try:
            return bool(self.console.has_active_work())
        except Exception:
            return False

    def _on_close_window(self) -> None:
        """WM_DELETE_WINDOW 入口：按用户设置的 close_action 分流。

        smoke 走旧的 hide_to_tray 路径，既不弹窗也不改断言前提。
        """
        if self.smoke:
            self.hide_to_tray()
            return
        if self._quitting:
            return
        action = self.cfg["close_action"]
        busy = self._engine_busy()
        if action == "tray":
            self.hide_to_tray()
            return
        if action == "ask":
            # 用户选了「每次询问」就是要每次被问：引擎闲不闲不该替他省这一步。
            # 忙/闲的差别只体现在弹窗文案上（闲时不必吓唬"下载会中断"）。
            self._show_close_dialog(allow_tray=True)
            return
        # exit：无活干直接退；有活干让用户确认一次（不给「记住」选项，
        # 这只是本次的保护闸，用户的设置仍是直接退出）。
        if busy:
            self._show_close_dialog(allow_tray=False)
        else:
            self.quit_app()

    def _show_close_dialog(self, *, allow_tray: bool) -> None:
        """居中模态询问：退出 / 托盘 / 取消，可勾选记住选择。文案按引擎忙闲自适应。"""
        # 已有一张询问框在开就别叠第二张（连按两次 X）。
        existing = getattr(self, "_close_dialog", None)
        if existing is not None and existing.winfo_exists():
            try:
                existing.lift()
                existing.focus_force()
            except Exception:
                pass
            return
        busy = self._engine_busy()
        title, body = close_dialog_copy(busy)
        dlg = ctk.CTkToplevel(self, fg_color=T.SURFACE)
        self._close_dialog = dlg
        dlg.title("关闭程序")
        dlg.resizable(False, False)
        dlg.transient(self)
        ctk.CTkLabel(dlg, text=title, font=_font(14, "bold")
                     ).pack(anchor="w", padx=22, pady=(20, 4))
        ctk.CTkLabel(
            dlg,
            text=body,
            font=_font(11), text_color=T.TEXT_MUTED, justify="left", anchor="w",
        ).pack(anchor="w", padx=22, pady=(0, 8))

        remember = ctk.BooleanVar(value=False)
        if allow_tray:
            ctk.CTkCheckBox(
                dlg, text="记住我的选择，不再询问", font=_font(11),
                variable=remember, fg_color=T.ACCENT, corner_radius=4,
            ).pack(anchor="w", padx=22, pady=(0, 6))

        btns = ctk.CTkFrame(dlg, fg_color="transparent")
        btns.pack(fill="x", padx=22, pady=(6, 18))

        def _choose(choice: str | None) -> None:
            dlg.grab_release()
            dlg.destroy()
            self._close_dialog = None
            if choice is None:
                return  # 取消：什么都不做，窗口本来就没关
            if allow_tray and remember.get():
                self.cfg.update(close_action=choice)
                self.cfg.save()
                self.close_menu.set(CLOSE_ACTION_LABELS[choice])
                self._set_detail(f"关闭行为已记住：{CLOSE_ACTION_LABELS[choice]}"
                                 f"（可在「引擎设置」里更改）")
            if choice == "exit":
                self.quit_app()
            else:
                self.hide_to_tray()

        def _btn(text: str, color, hover, choice: str | None, side: str) -> None:
            b = ctk.CTkButton(btns, text=text, width=108, height=32, font=_font(11),
                              corner_radius=T.RADIUS, fg_color=color, hover_color=hover,
                              command=lambda: _choose(choice))
            b.pack(side=side, padx=(0 if side == "left" else 6, 0))

        if allow_tray:
            _btn("退出程序", T.DANGER, T.DANGER_HOVER, "exit", "left")
            _btn("最小化到托盘", T.BTN_SECONDARY, T.BTN_SECONDARY_HOVER, "tray", "left")
            _btn("取消", T.BTN_SECONDARY, T.BTN_SECONDARY_HOVER, None, "right")
        else:
            _btn("退出程序", T.DANGER, T.DANGER_HOVER, "exit", "left")
            _btn("取消", T.BTN_SECONDARY, T.BTN_SECONDARY_HOVER, None, "right")

        dlg.after(120, lambda: self._center_dialog(dlg))
        dlg.grab_set()
        dlg.focus_force()

    def _center_dialog(self, dlg) -> None:
        """CTkToplevel 尺寸确定后居中到主窗口。"""
        try:
            dlg.update_idletasks()
            w, h = dlg.winfo_width(), dlg.winfo_height()
            x = self.winfo_x() + (self.winfo_width() - w) // 2
            y = self.winfo_y() + (self.winfo_height() - h) // 2 - 40
            dlg.geometry(f"+{max(x, 0)}+{max(y, 0)}")
        except Exception:
            pass

    def hide_to_tray(self) -> None:
        """点 X 的行为：不退出，只隐藏到托盘（引擎继续跑）。"""
        if self._quitting:
            return
        try:
            self.withdraw()
        except Exception as exc:
            L.log(f"隐藏窗口失败: {exc!r}")
        self._notify("已最小化到系统托盘；双击图标或右键「打开主界面」恢复", APP_NAME)
        self._set_detail("已隐藏到系统托盘，引擎在后台继续运行；托盘「退出」才会结束程序。")

    def show_window(self) -> None:
        if not self._alive or self._quitting:
            return
        try:
            self.deiconify()
            self.lift()
            self.focus_force()
            self._refresh_address()
            self.attributes("-topmost", True)
            self.after(350, self._drop_topmost)
        except Exception as exc:
            L.log(f"恢复窗口失败: {exc!r}")

    def _drop_topmost(self) -> None:
        if self._alive:
            try:
                self.attributes("-topmost", False)
            except Exception:
                pass

    # -- 彻底退出 -------------------------------------------------------------
    def quit_app(self) -> None:
        """托盘「退出」：杀干净引擎进程树 → 停托盘 → 销毁主窗口 → 结束进程。"""
        if self._quitting:
            return
        self._quitting = True
        self._gen += 1
        # 退出不经过 _set_state，必须显式让网络线程失效（_final_destroy 会置
        # _alive=False，但旧代次回调可能已经排进 UI 队列）。
        self._cancel_network()
        with self._engine_lock:
            try:
                self.engine.stop()
            except Exception as exc:
                L.log(f"退出时停止引擎失败: {exc!r}")
        if self.icon is not None:
            try:
                self.icon.stop()
            except Exception as exc:
                L.log(f"停止托盘失败: {exc!r}")
        self.ui_call(self._final_destroy)

    def _final_destroy(self) -> None:
        self._alive = False
        try:
            if self._log_window is not None and self._log_window.winfo_exists():
                self._log_window.destroy()
        except Exception:
            pass
        try:
            self.destroy()
        except Exception as exc:
            L.log(f"销毁主窗口失败: {exc!r}")

    # -- GUI 冒烟测试（无需人工点击） -----------------------------------------
    def _smoke_check(self, name: str, ok: bool, detail: str = "") -> None:
        self._smoke_results.append((name, bool(ok), detail))
        _safe_print(f"{'PASS' if ok else 'FAIL'} | {name} | {detail}")

    def _smoke_begin(self) -> None:
        self._smoke_check("主界面构建并可见", self.engine_state == STATE_STOPPED and self.winfo_width() >= 680,
                          f"geometry={self.winfo_width()}x{self.winfo_height()}")
        # 2026-11 美化轮：横向宽幅窗口 + 组件圆角统一 12px。
        self._smoke_check("窗口为横向宽幅（不再方正）",
                          self.winfo_width() > self.winfo_height(),
                          f"geometry={self.winfo_width()}x{self.winfo_height()}")
        self._smoke_check("组件圆角统一12px",
                          self.dep_card.cget("corner_radius") == 12
                          and self.console.sniff_card.cget("corner_radius") == 12
                          and self.log_button.cget("corner_radius") == 12,
                          f"dep={self.dep_card.cget('corner_radius')} "
                          f"sniff={self.console.sniff_card.cget('corner_radius')} "
                          f"btn={self.log_button.cget('corner_radius')}")
        self._smoke_check("黄色安全警告显眼存在", "切勿暴露在公网" in self.warn_label.cget("text"))
        self._smoke_check("连接地址已计算", self.addr_label.cget("text").startswith("http://"),
                          self.addr_label.cget("text"))
        self._smoke_check("默认不自动启动引擎", self.engine_state == STATE_STOPPED and self.engine.proc is None)
        # 关闭行为三选项 + 默认托盘；Config.normalize 对非法值回退默认。
        self._smoke_check("关闭行为设置项三选项齐备",
                          list(CLOSE_ACTION_LABELS.values()) == list(self.close_menu.cget("values"))
                          and self.close_menu.get() == "隐藏到托盘"
                          and self.cfg["close_action"] == "tray",
                          f"menu={list(self.close_menu.cget('values'))} cfg={self.cfg['close_action']}")
        self._smoke_check("Config 对非法 close_action 回退默认",
                          Config(Path(CONFIG_PATH), {"close_action": "bogus"}).data["close_action"] == "tray"
                          and Config(Path(CONFIG_PATH), {}).data["close_action"] == "tray",
                          str(Config(Path(CONFIG_PATH), {"close_action": "bogus"}).data["close_action"]))
        # 询问模式一律弹窗（M18.6 演进）：引擎闲时也要被问。真 Toplevel 不在 smoke 里
        # 建——实测中途建 CTkToplevel 会触发 Tk 原生崩溃（0xC0000005），改为
        # 纯文案函数断言 + 分流 dry-run（猴补丁拦截弹窗调用，不真建窗）。
        self._smoke_check("关闭确认框文案忙闲自适应",
                          close_dialog_copy(True)[0] == "引擎正在后台运行"
                          and close_dialog_copy(False)[0] == "确定要退出吗？",
                          f"busy={close_dialog_copy(True)[0]} idle={close_dialog_copy(False)[0]}")
        self.cfg.update(close_action="ask")
        dialog_calls: list[dict] = []
        original_dialog = self._show_close_dialog
        self._show_close_dialog = lambda **kw: dialog_calls.append(kw)  # type: ignore[method-assign]
        was_smoke, self.smoke = self.smoke, False
        try:
            self._on_close_window()
        finally:
            self.smoke = was_smoke
            self._show_close_dialog = original_dialog  # type: ignore[method-assign]
            self.cfg.update(close_action="tray")
        self._smoke_check("询问模式引擎闲时也弹确认框（不再直接退）",
                          dialog_calls == [{"allow_tray": True}]
                          and self.cfg["close_action"] == "tray"
                          and not self._quitting,
                          f"calls={dialog_calls} cfg={self.cfg['close_action']}")
        self._smoke_check("控制台活动任务判断就绪（空闲时应为 False）",
                          self.console.has_active_work() is False,
                          f"rows={len(self.console.task_rows)}")
        self._smoke_check("托盘菜单四项齐备",
                          all(x in self._tray_labels for x in TRAY_REQUIRED_LABELS),
                          "/".join(x for x in self._tray_labels if x != "- - - -"))

        # ---- M16：Tab 布局与控制台基础 UI（引擎尚未启动） ----
        tab_names = list(self.tabs._tab_dict.keys())
        self._smoke_check("主界面为双标签页", tab_names == ["引擎设置", "下载控制台"],
                          "/".join(tab_names))
        self._smoke_check("默认停在引擎设置页", self.tabs.get() == "引擎设置", self.tabs.get())
        legacy = (self.addr_label, self.copy_button, self.host_menu, self.port_entry,
                  self.output_entry, self.browse_button, self.auto_start_switch,
                  self.start_button, self.stop_button, self.log_button,
                  self.hide_button, self.led, self.env_label)
        self._smoke_check("引擎设置页保留 M15 全部控件",
                          all(w.winfo_exists() for w in legacy), f"{len(legacy)} 个控件")

        # ---- M16.5：环境依赖自检卡片 / 路径解析 / 环境变量注入 ----
        dep_widgets = (self.dep_card, self.dep_recheck_button,
                       self.dep_ffmpeg_dot, self.dep_ffmpeg_label,
                       self.dep_ffmpeg_browse, self.dep_ffmpeg_clear,
                       self.dep_ffmpeg_hint, self.dep_chromium_dot,
                       self.dep_chromium_label, self.dep_install_button, self.dep_log)
        self._smoke_check("依赖自检卡片控件齐备",
                          all(w.winfo_exists() for w in dep_widgets),
                          f"{len(dep_widgets)} 个控件")
        self._smoke_check("安装日志区常驻可见且带空态提示（布局不跳动）",
                          self.dep_log.winfo_manager() == "pack"
                          and "安装日志" in self.dep_log.get("1.0", "end-1c"))
        # 正常自检在后台线程；冒烟里同步跑一次，确保状态已画到卡片上再断言。
        self._env_report(L.probe_dependencies(self.cfg["ffmpeg_path"]))
        self.update_idletasks()
        ff_text = self.dep_ffmpeg_label.cget("text")
        self._smoke_check(
            "FFmpeg 状态显示已就绪（带版本号）或未找到",
            ("已就绪" in ff_text and bool(self._dep_info["ffmpeg_version"]))
            or "未找到" in ff_text,
            f"{ff_text} | version={self._dep_info.get('ffmpeg_version')}",
        )
        cr_text = self.dep_chromium_label.cget("text")
        self._smoke_check("Chromium 状态显示已就绪/未安装",
                          "已就绪" in cr_text or "未安装" in cr_text, cr_text)
        dot = str(self.dep_ffmpeg_dot.cget("text_color")).lower()
        self._smoke_check(
            "状态圆点按结果着绿/红色",
            ("2eb872" in dot) if self._dep_info["ffmpeg_found"] else ("e5534b" in dot),
            f"{dot} | found={self._dep_info['ffmpeg_found']}",
        )
        # 用临时占位文件验证「自定义路径优先」，绝不把本机的 D:\ffmpeg 硬编码进
        # 可分发 exe 的冒烟断言——换台没有该目录的机器会假失败。
        import tempfile
        with tempfile.TemporaryDirectory(prefix="bgv-ffmpeg-") as tmpdir:
            custom = str((Path(tmpdir) / "ffmpeg.exe").resolve())
            Path(custom).write_bytes(b"MZ")
            resolved = L.ffmpeg_resolve(custom)
            self._smoke_check("自定义 FFmpeg 路径优先于系统 PATH",
                              resolved[1] == "custom" and resolved[0] == custom,
                              str(resolved))
            self._smoke_check("自定义路径失效时如实报未找到（不回落 PATH）",
                              L.ffmpeg_resolve(str(Path(tmpdir) / "nope.exe"))
                              == (None, "custom"))
            self._smoke_check("未自定义时回落系统 PATH",
                              L.ffmpeg_resolve("")[1] == "path",
                              str(L.ffmpeg_resolve("")[0]))
            tmp_cfg = CONFIG_PATH.with_name("_smoke_ffmpeg_config.json")
            try:
                Config(tmp_cfg, {"ffmpeg_path": custom}).save()
                self._smoke_check("ffmpeg_path 写入 config.json 并可重读",
                                  Config.load(tmp_cfg)["ffmpeg_path"] == custom,
                                  json.dumps(Config.load(tmp_cfg).data, ensure_ascii=False))
            finally:
                tmp_cfg.unlink(missing_ok=True)
            env_custom = self._build_engine_env("E:/out", custom)
            env_plain = self._build_engine_env("E:/out")
            self._smoke_check(
                "自定义路径经 BGV_FFMPEG_PATH 注入引擎子进程",
                env_custom == {"BGV_OUTPUT_DIR_ALLOWLIST": "E:/out",
                               L.FFMPEG_ENV: custom}
                and L.FFMPEG_ENV not in env_plain,
                json.dumps(env_custom, ensure_ascii=False),
            )
        self._dep_log_show()
        self._dep_log_append("M16.5 冒烟：日志区可展开并写入")
        self.update_idletasks()
        self._smoke_check("安装日志区可展开并写入文本",
                          self.dep_log.winfo_manager() == "pack"
                          and "M16.5 冒烟" in self.dep_log.get("1.0", "end-1c"))
        self._dep_log_clear()
        log_text = self.dep_log.get("1.0", "end-1c")
        self._smoke_check("安装日志可清空并恢复占位提示（区域仍在）",
                          "安装日志" in log_text and "M16.5 冒烟" not in log_text
                          and self.dep_log.winfo_manager() == "pack")

        c = self.console
        widgets = (c.url_entry, c.sniff_button, c.format_menu, c.download_button,
                   c.result_card, c.tasks_card, c.task_list, c.message_label)
        self._smoke_check("下载控制台基础控件齐备",
                          all(w.winfo_exists() for w in widgets), f"{len(widgets)} 个控件")
        self._smoke_check("控制台回调已接入真实网络层",
                          self.console.on_sniff.__name__ == "_on_sniff"
                          and self.console.on_download.__name__ == "_on_download",
                          f"{self.console.on_sniff.__name__} / "
                          f"{self.console.on_download.__name__}")
        self._smoke_check(
            "引擎未启动时控制台置灰并提示",
            (not c.is_ready) and c.sniff_button.cget("state") == "disabled"
            and c.url_entry.cget("state") == "disabled"
            and c.offline_hint.winfo_manager() == "pack",
            f"ready={c.is_ready} sniff={c.sniff_button.cget('state')} "
            f"entry={c.url_entry.cget('state')} hint={c.offline_hint.winfo_manager()}",
        )
        self._smoke_check("嗅探结果区出结果前隐藏", c.result_card.winfo_manager() == "",
                          repr(c.result_card.winfo_manager()))
        self._smoke_check(
            "错误码翻译复用协议 §5",
            translate_error("DRM_PROTECTED") == "该内容受 DRM 保护，不支持下载"
            and translate_error("SSRF_BLOCKED") == "目标地址指向受限网络，已拒绝",
            translate_error("DRM_PROTECTED"),
        )
        self._smoke_check("画质标签按 FormatInfo 字段拼接",
                          format_label(DEMO_FORMATS[0]) == "1080p · mp4 · h264",
                          format_label(DEMO_FORMATS[0]))

        # 引擎尚未启动，正好把「控制台置灰 + 离线提示」这一态留档。
        if self.console_offline_shot:
            self.tabs.set("下载控制台")
            self.update_idletasks()
            self.update()
            self._capture(self.console_offline_shot, "控制台未启动置灰态截图已导出")
            self.tabs.set("引擎设置")
            self.update_idletasks()
            self.update()

        # 配置读写往返：改 → 落盘 → 重新读 → 还原
        original = dict(self.cfg.data)
        self.host_menu.set(HOST_LABELS["127.0.0.1"])
        self.port_entry.delete(0, "end"); self.port_entry.insert(0, "8799")
        self.output_entry.delete(0, "end")
        self.output_entry.insert(0, str(Path(L.data_dir()) / "bgv-smoke-out"))
        self.appearance_menu.set(APPEARANCE_LABELS["dark"])
        self.auto_start_switch.select()
        self._flush_settings()
        reread = Config.load(CONFIG_PATH)
        self._smoke_check(
            "设置写入 config.json 并可重读",
            reread["host"] == "127.0.0.1" and reread["port"] == 8799
            and reread["auto_start"] is True and reread["appearance"] == "dark",
            json.dumps(reread.data, ensure_ascii=False),
        )
        self.cfg = Config(CONFIG_PATH, original)
        self.cfg.save()
        self._apply_config_to_widgets()
        restored = Config.load(CONFIG_PATH)
        self._smoke_check("设置可还原且下次启动自动读取", restored.data == original,
                          json.dumps(restored.data, ensure_ascii=False))
        ctk.set_appearance_mode(self.cfg["appearance"])
        self.after(120, self._smoke_start)

    def _smoke_start(self) -> None:
        self._on_start()
        self._smoke_check("点击「启动」进入启动中/运行中",
                          self.engine_state in (STATE_STARTING, STATE_RUNNING), self.engine_state)
        self._smoke_wait_ready(time.monotonic() + 45.0)

    def _smoke_wait_ready(self, deadline: float) -> None:
        if self.engine_state == STATE_RUNNING:
            self.after(100, self._smoke_running)
            return
        if self.engine_state == STATE_ERROR or time.monotonic() > deadline:
            self._smoke_check("GUI 启动引擎并就绪", False,
                              f"state={self.engine_state} detail={self.detail_label.cget('text')}")
            self.after(100, self._smoke_quit)
            return
        self.after(300, lambda: self._smoke_wait_ready(deadline))

    def _smoke_running(self) -> None:
        port = self.cfg["port"]
        self._smoke_check("GUI 启动引擎并就绪", self.engine_state == STATE_RUNNING, self.engine_state)
        self._smoke_check("引擎 /v1/health 可访问", L.health_ok(f"http://127.0.0.1:{port}", 2.0),
                          f"GET http://127.0.0.1:{port}/v1/health")
        # 手机 App 走的是「同 WiFi 下填局域网 IP」这条路径，不是回环：必须单独验一次。
        lan = L.lan_ip()
        self._smoke_check("经局域网 IP 可访问（手机连接路径）",
                          L.health_ok(f"http://{lan}:{port}", 2.0),
                          f"GET http://{lan}:{port}/v1/health")
        # M15.3 裁决：selfcheck 的 listenAddress 必须回显真实绑定，而非硬编码 127.0.0.1。
        expected = f"{self.cfg['host']}:{port}"
        payload = _probe_selfcheck(port)
        self._smoke_check("selfcheck.listenAddress 反映实际绑定",
                          payload is not None and payload.get("listenAddress") == expected,
                          f"expect={expected} got={payload.get('listenAddress') if payload else None}")
        self._smoke_check("引擎进程存活且日志已落盘",
                          self.engine.poll() is None and L.LOG_PATH.stat().st_size > 0,
                          f"pid={self.engine.pid} log={L.LOG_PATH.stat().st_size}B")
        led_color = str(self.led.cget("text_color")).lower()
        self._smoke_check("运行态指示灯为绿", "2eb872" in led_color, led_color)
        self._smoke_check("托盘运行中且状态标题已更新",
                          self.icon is not None and self.icon.visible
                          and "运行中" in (self.icon.title or ""), str(self.icon.title if self.icon else None))
        self._smoke_check("设置项运行中已锁定", self.start_button.cget("state") == "disabled",
                          f"start={self.start_button.cget('state')} host={self.host_menu.cget('state')}")

        self._smoke_screenshot()
        self._smoke_console()

        # 日志按钮：开一个内嵌日志窗口再关掉
        try:
            self._open_log()
            opened = self._log_window is not None and self._log_window.winfo_exists()
            self._smoke_check("「日志」按钮打开日志窗口", opened,
                              str(self._log_text.get("1.0", "1.30") if opened else "")[:40])
            if opened:
                self._log_window.destroy()
        except Exception as exc:
            self._smoke_check("「日志」按钮打开日志窗口", False, repr(exc))

        self.after(150, self._smoke_hide)

    @staticmethod
    def _grab_window_dc(hwnd: int):
        """Win32 ``PrintWindow(PW_RENDERFULLCONTENT)`` 直接抓窗口 DC。

        无人值守跑 GUI 时窗口虽是 ``normal/viewable``，但可能不在屏幕合成层里，
        ``ImageGrab`` 只会拍到桌面壁纸（M16 第二步 e2e 已踩过一次）。窗口 DC
        不依赖 Z-order。非 Windows / 调用失败返回 ``None``，由调用方回退。
        """
        if sys.platform != "win32":
            return None
        try:
            import ctypes
            from ctypes import wintypes
            from PIL import Image

            class _BMI(ctypes.Structure):
                _fields_ = [
                    ("biSize", wintypes.DWORD), ("biWidth", wintypes.LONG),
                    ("biHeight", wintypes.LONG), ("biPlanes", wintypes.WORD),
                    ("biBitCount", wintypes.WORD), ("biCompression", wintypes.DWORD),
                    ("biSizeImage", wintypes.DWORD), ("biXPelsPerMeter", wintypes.LONG),
                    ("biYPelsPerMeter", wintypes.LONG), ("biClrUsed", wintypes.DWORD),
                    ("biClrImportant", wintypes.DWORD),
                ]

            user32, gdi32 = ctypes.windll.user32, ctypes.windll.gdi32
            rect = wintypes.RECT()
            user32.GetWindowRect(hwnd, ctypes.byref(rect))
            w, h = rect.right - rect.left, rect.bottom - rect.top
            if w <= 0 or h <= 0:
                return None
            hdc = user32.GetWindowDC(hwnd)
            memdc = gdi32.CreateCompatibleDC(hdc)
            bmp = gdi32.CreateCompatibleBitmap(hdc, w, h)
            gdi32.SelectObject(memdc, bmp)
            buf = None
            try:
                ok = user32.PrintWindow(hwnd, memdc, 2)
                bi = _BMI()
                bi.biSize = ctypes.sizeof(_BMI)
                bi.biWidth, bi.biHeight = w, -h  # 负值 = top-down
                bi.biPlanes, bi.biBitCount, bi.biCompression = 1, 32, 0
                buf = ctypes.create_string_buffer(w * h * 4)
                got = gdi32.GetDIBits(memdc, bmp, 0, h, buf, ctypes.byref(bi), 0)
            finally:
                gdi32.DeleteObject(bmp)
                gdi32.DeleteDC(memdc)
                user32.ReleaseDC(hwnd, hdc)
            if not ok or not got or buf is None:
                return None
            return Image.frombuffer("RGB", (w, h), buf, "raw", "BGRX", 0, 1).copy()
        except Exception:
            return None

    def _capture(self, path: str, label: str) -> None:
        try:
            self.lift()
            self.attributes("-topmost", True)
            self.update_idletasks()
            self.update()
            time.sleep(0.35)
            image = self._grab_window_dc(int(self.winfo_id()))
            if image is None:
                from PIL import ImageGrab
                x, y = self.winfo_rootx(), self.winfo_rooty()
                w, h = self.winfo_width(), self.winfo_height()
                image = ImageGrab.grab(bbox=(x, y, x + w, y + h))
            image.save(path)
            self.attributes("-topmost", False)
            self._smoke_check(label, Path(path).is_file(), path)
        except Exception as exc:
            self._smoke_check(label, False, repr(exc))

    def _smoke_screenshot(self) -> None:
        if self.screenshot_path:
            self._capture(self.screenshot_path, "浅色/跟随系统界面截图已导出")
        if self.dark_shot:
            before = ctk.get_appearance_mode()
            ctk.set_appearance_mode("dark")
            self.update_idletasks()
            self._smoke_check("深色主题可切换", ctk.get_appearance_mode().lower() == "dark",
                              f"{before} -> {ctk.get_appearance_mode()}")
            self._capture(self.dark_shot, "深色主题截图已导出")
            # CTk 6 的 set_appearance_mode("system") 只标记跟随系统，实际重算由
            # 30ms 轮询循环完成；同步立刻读会拿到上一次的 Dark。等一轮事件循环。
            ctk.set_appearance_mode(self.cfg["appearance"])
            time.sleep(0.15)
            self.update()
            self._smoke_check("外观恢复为配置值", ctk.get_appearance_mode() == before,
                              f"{ctk.get_appearance_mode()} (原 {before})")

    def _smoke_console(self) -> None:
        """M16 Tab② 纯 UI 验收：切换 / 演示态 / 显隐 / 进度 / 错误 / 完成 / 复位。"""
        c = self.console
        self._smoke_check(
            "引擎就绪后控制台解锁",
            c.is_ready and c.sniff_button.cget("state") == "normal"
            and c.url_entry.cget("state") == "normal"
            and c.offline_hint.winfo_manager() == "",
            f"ready={c.is_ready} sniff={c.sniff_button.cget('state')} "
            f"entry={c.url_entry.cget('state')} hint={c.offline_hint.winfo_manager()}",
        )
        self.tabs.set("下载控制台")
        self.update_idletasks()
        self.update()
        self._smoke_check("可切换到下载控制台标签",
                          self.tabs.get() == "下载控制台"
                          and self.tab_console.winfo_ismapped() == 1, self.tabs.get())

        c.ui_demo()  # 只注入界面预览数据，不发任何网络请求
        self.update_idletasks()
        self.update()
        self._smoke_check("嗅探结果区出结果后显示",
                          c.result_card.winfo_manager() == "pack"
                          and c.result_card.winfo_ismapped() == 1,
                          repr(c.result_card.winfo_manager()))
        labels = list(c.format_menu.cget("values"))
        self._smoke_check("画质下拉框列出全部格式",
                          len(labels) == 3 and labels[0] == "1080p · mp4 · h264",
                          " / ".join(labels))
        self._smoke_check("下拉框标签可反查 formatId",
                          c.selected_format_id() == "137", str(c.selected_format_id()))
        meta = c.selected_format()
        self._smoke_check(
            "画质选项绑定 height/hasVideo/hasAudio",
            meta == {"formatId": "137", "height": 1080,
                     "hasVideo": True, "hasAudio": False},
            json.dumps(meta, ensure_ascii=False),
        )
        s1080 = selector_for_format({"height": 1080, "hasVideo": True, "hasAudio": False})
        saudio = selector_for_format({"height": 0, "hasVideo": False, "hasAudio": True})
        self._smoke_check(
            "画质映射绕开 exact/formatId 协议坑",
            s1080 == {"strategy": "max_height", "maxHeight": 1080}
            and saudio == {"strategy": "audio_only"}
            and "formatId" not in json.dumps(s1080),
            f"{s1080} | {saudio}",
        )
        self._smoke_check(
            "引擎错误体解析为 code/message",
            extract_error({"error": {"code": "DRM_PROTECTED",
                                     "message": "该内容受 DRM 保护，不支持下载"}}, 422)
            == ("DRM_PROTECTED", "该内容受 DRM 保护，不支持下载"),
        )
        c.format_menu.set(labels[1])
        self._smoke_check("切换画质后 formatId 同步变化",
                          c.selected_format_id() == "136", str(c.selected_format_id()))
        c.format_menu.set(labels[0])
        self._smoke_check("下载任务列表生成任务", len(c.task_rows) == 1,
                          c.task_count.cget("text"))
        row = c.task_rows[0]
        self._smoke_check("进度条按 percent 更新",
                          abs(row.progress_bar.get() - 0.62) < 0.01,
                          f"bar={row.progress_bar.get():.2f} "
                          f"label={row.percent_label.cget('text')}")
        # M18.5：percent=0 但字节在涨（HLS 无总大小）→ 不确定进度脉冲，而非死 0%
        row.update(percent=0.0, speed_bps=1_048_576, bytes_downloaded=3_145_727,
                   phase="downloading")
        self._smoke_check("总大小未知时切换不确定进度",
                          row._pulsing and row.percent_label.cget("text") == "…",
                          f"pulsing={row._pulsing} "
                          f"label={row.percent_label.cget('text')}")
        self._smoke_check("不确定进度显示已下载字节",
                          "已下载 3.0 MB" in row.speed_label.cget("text"),
                          row.speed_label.cget("text"))
        self._smoke_check("脉冲首拍已推动进度条",
                          row.progress_bar.get() > 0.0,
                          f"bar={row.progress_bar.get():.2f}")
        row.update(percent=62.0, speed_bps=2_516_582, eta_seconds=12,
                   phase="downloading")
        self._smoke_check("拿到真实百分比即退出脉冲",
                          not row._pulsing and row.percent_label.cget("text") == "62%",
                          f"pulsing={row._pulsing} "
                          f"label={row.percent_label.cget('text')}")
        self._smoke_check("速度文本按 speedBps 格式化",
                          "MB/s" in row.speed_label.cget("text"), row.speed_label.cget("text"))
        self._smoke_check("剩余时间文本可见",
                          "剩余 00:12" in row.eta_label.cget("text"),
                          row.eta_label.cget("text"))
        # 列表能力单独验：追加一条已完成任务，验完清掉，截图保持单任务干净态。
        extra = c.add_task("示例视频 · 720p · mp4")
        extra.update(percent=100.0, phase="done")
        self._smoke_check("任务列表可追加多行", len(c.task_rows) == 2,
                          c.task_count.cget("text"))
        self._smoke_check("已完成任务显示完成态",
                          extra.status_label.cget("text") == "已完成"
                          and extra.progress_bar.get() == 1.0,
                          extra.status_label.cget("text"))
        c.clear()
        c.ui_demo()
        self.update_idletasks()
        self.update()
        self._smoke_check("预览数据已明确标注未联网",
                          "未发起网络请求" in c.demo_badge.cget("text"),
                          c.demo_badge.cget("text"))
        self._smoke_check(
            "界面预览说明条可见（未被任务区挤出）",
            c.message_label.winfo_manager() == "pack"
            and c.message_label.winfo_ismapped() == 1
            and "未发起网络请求" in c.message_label.cget("text"),
            c.message_label.cget("text"),
        )
        # 最终判据必须比**窗口**：前两版分别比了 scroll 外层 frame 和 task_list
        # 容器高度，都因「容器自认有高度、但整张卡片已被窗口裁掉」而假通过。
        win_bottom = self.winfo_rooty() + self.winfo_height()
        last_abs = (c.task_rows[-1].frame.winfo_rooty()
                    + c.task_rows[-1].frame.winfo_height())
        detail_top = self.detail_label.winfo_rooty()
        self._smoke_check("任务行完整可见（未越过底部状态栏）",
                          last_abs <= detail_top and last_abs <= win_bottom - 20,
                          f"last_abs={last_abs} detail_top={detail_top} "
                          f"win_bottom={win_bottom}")

        self._smoke_console_shots()

        c.show_error("DRM_PROTECTED", "")
        self._smoke_check("嗅探失败显示红字错误提示",
                          c.message_label.winfo_manager() == "pack"
                          and "该内容受 DRM 保护" in c.message_label.cget("text")
                          and "DRM_PROTECTED" in c.message_label.cget("text"),
                          c.message_label.cget("text"))
        self._smoke_check("引擎译文优先于本机兜底",
                          translate_error("UNKNOWN", "引擎自定义文案") == "引擎自定义文案")

        c.show_done("E:/Videos/BGV/demo.mp4")
        self._smoke_check("下载完成提示且清空输入框",
                          c.url_entry.get() == ""
                          and "下载完成" in c.message_label.cget("text"),
                          c.message_label.cget("text"))

        # 纯本地 URL 校验：证明按钮链路可用，且整个过程没有任何网络请求。
        c.clear()
        c.set_engine_ready(True, self.cfg["port"])
        c._on_sniff_click()
        self._smoke_check("空 URL 被本地校验拦截",
                          "链接格式不正确" in c.message_label.cget("text"),
                          c.message_label.cget("text"))
        c.url_entry.insert(0, "ftp://example.com/a")
        c._on_sniff_click()
        self._smoke_check("非 http(s) 链接被本地校验拦截",
                          "仅支持 http/https" in c.message_label.cget("text"),
                          c.message_label.cget("text"))

        c.clear()
        self._smoke_check("clear() 复位结果与任务区",
                          c.result_card.winfo_manager() == "" and not c.task_rows
                          and c.empty_label.winfo_manager() == "pack")
        self.tabs.set("引擎设置")
        self.update_idletasks()
        self.update()
        self._smoke_check("可切回引擎设置标签", self.tabs.get() == "引擎设置")

    def _smoke_console_shots(self) -> None:
        if self.console_shot:
            self._capture(self.console_shot, "下载控制台浅色截图已导出")
        if self.console_dark_shot:
            before = ctk.get_appearance_mode()
            ctk.set_appearance_mode("dark")
            self.update_idletasks()
            self.update()
            self._smoke_check("下载控制台深色主题可切换",
                              ctk.get_appearance_mode().lower() == "dark",
                              f"{before} -> {ctk.get_appearance_mode()}")
            self._capture(self.console_dark_shot, "下载控制台深色截图已导出")
            ctk.set_appearance_mode(self.cfg["appearance"])
            time.sleep(0.15)
            self.update()
            self._smoke_check("控制台外观恢复为配置值",
                              ctk.get_appearance_mode() == before,
                              f"{ctk.get_appearance_mode()} (原 {before})")

    def _smoke_hide(self) -> None:
        self.hide_to_tray()  # 等价于点右上角 X
        self._smoke_check("点 X 隐藏到托盘（窗口 withdrawn）",
                          self.tk.call("wm", "state", self._w) == "withdrawn",
                          f"wm state={self.tk.call('wm','state',self._w)}")
        self._smoke_check("隐藏后引擎仍在后台运行",
                          self.engine.poll() is None and L.health_ok(
                              f"http://127.0.0.1:{self.cfg['port']}", 2.0))
        self._smoke_check("隐藏后托盘图标仍在", self.icon is not None and self.icon.visible)
        self.after(700, self._smoke_restore)

    def _smoke_restore(self) -> None:
        self.show_window()  # 等价于托盘菜单「打开主界面」
        self._smoke_check("托盘唤起主界面成功",
                          self.tk.call("wm", "state", self._w) == "normal"
                          and self.winfo_viewable() == 1,
                          f"wm state={self.tk.call('wm','state',self._w)}")
        self.after(400, self._smoke_stop)

    def _smoke_wait_state(self, target: str, deadline: float, label: str, on_done) -> None:
        if self.engine_state == target:
            self._smoke_check(label, True, target)
            on_done()
            return
        if self.engine_state == STATE_ERROR or time.monotonic() > deadline:
            self._smoke_check(label, False,
                              f"state={self.engine_state} detail={self.detail_label.cget('text')}")
            self.after(100, self._smoke_quit)
            return
        self.after(300, lambda: self._smoke_wait_state(target, deadline, label, on_done))

    def _smoke_stop(self) -> None:
        self._on_stop()  # 显式点「⏹ 停止」
        self._smoke_wait_state(STATE_STOPPED, time.monotonic() + 20,
                               "「停止」按钮停止引擎", self._smoke_stopped)

    def _smoke_stopped(self) -> None:
        port = self.cfg["port"]
        self._smoke_check(
            "停止后设置恢复可编辑且端口释放",
            self.start_button.cget("state") == "normal"
            and self.host_menu.cget("state") == "normal"
            and not L.port_in_use(port),
            f"start={self.start_button.cget('state')} host={self.host_menu.cget('state')} "
            f"port_in_use={L.port_in_use(port)}",
        )
        self._smoke_check("停止引擎已取消网络轮询代次",
                          self._net_cancels >= 1 and self._net_gen >= 1,
                          f"cancels={self._net_cancels} gen={self._net_gen}")
        self.after(150, self._smoke_restart)

    def _smoke_restart(self) -> None:
        self._on_start()
        self._smoke_wait_state(STATE_RUNNING, time.monotonic() + 45,
                               "停止后可从 GUI 再次启动", self._smoke_restarted)

    def _smoke_restarted(self) -> None:
        self._smoke_check("再次启动后健康检查通过",
                          L.health_ok(f"http://127.0.0.1:{self.cfg['port']}", 2.0))
        self.after(150, self._smoke_quit)

    def _smoke_quit(self) -> None:
        self._smoke_server_pid = self.engine.discover_server_pid()
        self.quit_app()  # 等价于托盘菜单「退出」；此时引擎正在运行


def _probe_selfcheck(port: int) -> dict | None:
    """读取 /v1/privacy/selfcheck；失败返回 None，由调用方做成硬断言。

    selfcheck 每次都实时探测 ``ffmpeg -version`` / importlib.metadata，
    PyInstaller 单文件首次冷启动可能超过 2 秒；给 6 秒，别把「慢」误判成
    「端点坏了」（health 用 2 秒足够，这个端点不一样）。
    """
    import urllib.request
    try:
        with urllib.request.urlopen(
                f"http://127.0.0.1:{port}/v1/privacy/selfcheck", timeout=6.0) as resp:
            return json.loads(resp.read().decode("utf-8", "replace"))
    except Exception:
        return None


def run_gui_smoke(argv: list[str]) -> int:
    def arg_value(flag: str) -> str | None:
        if flag in argv:
            idx = argv.index(flag)
            if idx + 1 < len(argv):
                return argv[idx + 1]
        return None

    shot = arg_value("--screenshot")
    dark_shot = arg_value("--dark-shot")
    console_shot = arg_value("--console-shot")
    console_dark_shot = arg_value("--console-dark-shot")
    console_offline_shot = arg_value("--console-offline-shot")
    report = arg_value("--smoke-report")
    app = ControlPanel(smoke=True, screenshot_path=shot, dark_shot=dark_shot,
                       console_shot=console_shot, console_dark_shot=console_dark_shot,
                       console_offline_shot=console_offline_shot)
    port = app.cfg["port"]
    app.mainloop()

    # mainloop 返回 = 主窗口已销毁；此时做「不留僵尸」的硬断言。
    time.sleep(0.6)
    def check(name: str, ok: bool, detail: str = "") -> None:
        app._smoke_check(name, ok, detail)

    check("退出后端口已释放", not L.port_in_use(port), f"port_in_use({port})=False")
    check("退出后 uvicorn 子进程已消失（无僵尸）",
          not L.process_alive(app._smoke_server_pid),
          f"server_pid={app._smoke_server_pid}")
    if app._tray_thread is not None:
        app._tray_thread.join(timeout=5)
        check("托盘线程已退出", not app._tray_thread.is_alive())
    check("退出标志已置位、程序可干净结束", app._quitting is True and app._alive is False,
          f"quitting={app._quitting} alive={app._alive}")

    passed = sum(1 for _, ok, _ in app._smoke_results if ok)
    total = len(app._smoke_results)
    _safe_print(f"===== gui-smoke: {passed}/{total} PASS =====")
    _safe_print(f"GUI_SMOKE_RESULT={passed}/{total}")

    # --noconsole 的 exe 没有 stdout，结果必须落文件才取得到（打包后验收靠它）。
    if report:
        lines = [f"{'PASS' if ok else 'FAIL'} | {name} | {detail}"
                 for name, ok, detail in app._smoke_results]
        lines += [
            f"frozen={bool(getattr(sys, 'frozen', False))}",
            f"executable={sys.executable}",
            f"engine_dir={L.ENGINE_DIR}",
            f"engine_command={' '.join(L.build_engine_command(app.cfg['host'], port))}",
            f"===== gui-smoke: {passed}/{total} PASS =====",
            f"GUI_SMOKE_RESULT={passed}/{total}",
        ]
        try:
            Path(report).write_text("\n".join(lines) + "\n", encoding="utf-8")
        except OSError as exc:
            _safe_print(f"写冒烟报告失败: {exc!r}")
    return 0 if passed == total else 1


def main() -> int:
    argv = sys.argv[1:]
    if "--gui-smoke" in argv:
        return run_gui_smoke(argv)
    app = ControlPanel()
    app.mainloop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

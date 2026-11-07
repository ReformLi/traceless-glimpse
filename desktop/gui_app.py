"""M15 · 桌面可视化控制面板（customtkinter + tkinter，纯 Python、无重型框架）。

替代 M15 第一步的「纯托盘启动器」成为桌面端入口；托盘降级为**后台模式**：
主界面显示 → 用户点「启动」才拉引擎 → 点 X 隐藏到托盘 → 托盘可重新唤起 →
托盘「退出」彻底杀掉引擎进程树并结束程序。

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

验证入口（不需要人工点鼠标）::

    python desktop/gui_app.py --gui-smoke
    python desktop/gui_app.py --gui-smoke --screenshot E:\\tmp\\gui.png

打包（下一步 build_exe.py）：``--noconsole --onefile``，需 hidden-import
``launcher`` / ``pystray`` / ``PIL`` / ``customtkinter`` / ``darkdetect``。
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
from tkinter import filedialog, messagebox  # noqa: E402

APP_NAME = L.APP_NAME
CONFIG_PATH = L.data_dir() / "config.json"

DEFAULT_OUTPUT_DIR = str(Path.home() / "Videos" / "BGV")
HOST_LABELS = {"0.0.0.0": "0.0.0.0（局域网）", "127.0.0.1": "127.0.0.1（仅本机）"}
HOST_VALUES = {v: k for k, v in HOST_LABELS.items()}
APPEARANCE_LABELS = {"system": "跟随系统", "light": "浅色", "dark": "深色"}
APPEARANCE_VALUES = {v: k for k, v in APPEARANCE_LABELS.items()}

DEFAULTS: dict = {
    "host": "0.0.0.0",
    "port": 8730,
    "output_dir": DEFAULT_OUTPUT_DIR,
    "auto_start": False,
    "appearance": "system",
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

# 禁用态必须连底色一起变灰：只靠 CTk 默认的 text_color_disabled，
# 亮蓝/亮红底上的「禁用」仍会被看成可点按钮（截图复核后补的）。
BTN_DISABLED = ("#B9BEC5", "#3C424B")
BTN_DISABLED_TEXT = ("#5C636C", "#98A0AA")
BTN_START = "#1F6FEB"
BTN_START_HOVER = "#1A5FD0"
BTN_STOP = "#B4443C"
BTN_STOP_HOVER = "#8E332D"
BTN_SECONDARY = ("#77808C", "#4A515C")
BTN_SECONDARY_HOVER = ("#646D79", "#5A626D")

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
        self.data = {
            "host": host,
            "port": port,
            "output_dir": out,
            "auto_start": bool(d.get("auto_start", False)),
            "appearance": appearance,
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
                 dark_shot: str | None = None) -> None:
        super().__init__()
        self.smoke = smoke
        self.screenshot_path = screenshot_path
        self.dark_shot = dark_shot
        self.cfg = Config.load(CONFIG_PATH)

        ctk.set_appearance_mode(self.cfg["appearance"])
        ctk.set_default_color_theme("blue")

        self.title(f"{APP_NAME} · 控制面板")
        self.geometry("720x560")
        self.minsize(680, 520)
        self.protocol("WM_DELETE_WINDOW", self.hide_to_tray)

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
        self._save_job: str | None = None
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
        # 顶部标题 + 外观切换
        header = ctk.CTkFrame(self, fg_color="transparent")
        header.pack(fill="x", padx=20, pady=(14, 6))
        left = ctk.CTkFrame(header, fg_color="transparent")
        left.pack(side="left", fill="y")
        ctk.CTkLabel(left, text="无痕掠影 · 本地引擎", font=_font(20, "bold"),
                     anchor="w").pack(anchor="w")
        ctk.CTkLabel(left, text="PC 控制面板 · 手机 App 通过局域网连接本机下载",
                     font=_font(11), text_color=("gray45", "gray65"), anchor="w").pack(anchor="w")
        ctk.CTkLabel(header, text="外观", font=_font(11),
                     text_color=("gray40", "gray65")).pack(side="right", padx=(0, 8))
        self.appearance_menu = ctk.CTkOptionMenu(
            header, width=110, height=28, values=list(APPEARANCE_LABELS.values()),
            font=_font(11), command=self._on_appearance_change,
        )
        self.appearance_menu.pack(side="right")

        # 黄色安全警告（显眼位置）
        warn = ctk.CTkFrame(self, corner_radius=8, fg_color=("#FFF3C4", "#4A3A11"))
        warn.pack(fill="x", padx=20, pady=(4, 10))
        self.warn_label = ctk.CTkLabel(
            warn, text=SECURITY_NOTICE, font=_font(12, "bold"),
            text_color=("#7A5B00", "#FFD966"), anchor="w", justify="left", wraplength=650,
        )
        self.warn_label.pack(fill="x", padx=12, pady=9)

        # 引导区：大字连接地址 + 一键复制
        addr_card = ctk.CTkFrame(self, corner_radius=12)
        addr_card.pack(fill="x", padx=20, pady=(0, 10))
        ctk.CTkLabel(addr_card, text="连接地址（填入手机 App 的引擎地址）",
                     font=_font(11), text_color=("gray45", "gray65"),
                     anchor="w").pack(anchor="w", padx=16, pady=(12, 0))
        addr_row = ctk.CTkFrame(addr_card, fg_color="transparent")
        addr_row.pack(fill="x", padx=16, pady=(2, 0))
        self.addr_label = ctk.CTkLabel(addr_row, text="http://...", font=_font(26, "bold"),
                                       anchor="w", text_color=("#1F6FEB", "#6CB6FF"))
        self.addr_label.pack(side="left", fill="x", expand=True)
        self.copy_button = ctk.CTkButton(addr_row, text="一键复制", width=96, height=32,
                                         font=_font(12), command=self._copy_address)
        self.copy_button.pack(side="right")
        self.addr_hint = ctk.CTkLabel(addr_card, text="", font=_font(11),
                                     text_color=("gray40", "gray65"), anchor="w",
                                     justify="left", wraplength=650)
        self.addr_hint.pack(anchor="w", padx=16, pady=(2, 12))

        # 设置区
        settings = ctk.CTkFrame(self, corner_radius=12)
        settings.pack(fill="x", padx=20, pady=(0, 10))
        ctk.CTkLabel(settings, text="设置", font=_font(13, "bold"), anchor="w").pack(
            anchor="w", padx=16, pady=(10, 4))
        row1 = ctk.CTkFrame(settings, fg_color="transparent")
        row1.pack(fill="x", padx=16, pady=3)
        ctk.CTkLabel(row1, text="监听地址", font=_font(12), width=72, anchor="w").pack(side="left")
        self.host_menu = ctk.CTkOptionMenu(
            row1, width=186, values=list(HOST_LABELS.values()), font=_font(11),
            text_color_disabled=BTN_DISABLED_TEXT,
            command=lambda _v: self._schedule_save(),
        )
        self.host_menu.pack(side="left")
        ctk.CTkLabel(row1, text="监听端口", font=_font(12), width=72, anchor="w").pack(
            side="left", padx=(18, 0))
        self.port_entry = ctk.CTkEntry(row1, width=90, font=_font(12))
        self.port_entry.pack(side="left")
        self.port_entry.bind("<KeyRelease>", lambda _e: self._schedule_save())
        ctk.CTkLabel(row1, text="（修改后需重启引擎生效）", font=_font(10),
                     text_color=("gray50", "gray60"), anchor="w").pack(side="left", padx=(12, 0))

        row2 = ctk.CTkFrame(settings, fg_color="transparent")
        row2.pack(fill="x", padx=16, pady=3)
        ctk.CTkLabel(row2, text="输出目录", font=_font(12), width=72, anchor="w").pack(side="left")
        self.output_entry = ctk.CTkEntry(row2, font=_font(11))
        self.output_entry.pack(side="left", fill="x", expand=True, padx=(0, 8))
        self.output_entry.bind("<KeyRelease>", lambda _e: self._schedule_save())
        self.browse_button = ctk.CTkButton(row2, text="浏览…", width=72, height=28,
                                           font=_font(11),
                                           text_color_disabled=BTN_DISABLED_TEXT,
                                           command=self._browse_output)
        self.browse_button.pack(side="right")

        row3 = ctk.CTkFrame(settings, fg_color="transparent")
        row3.pack(fill="x", padx=16, pady=(3, 12))
        self.auto_start_switch = ctk.CTkSwitch(
            row3, text="启动程序时自动运行引擎", font=_font(11),
            command=self._schedule_save,
        )
        self.auto_start_switch.pack(side="left")
        self.output_hint = ctk.CTkLabel(
            row3, text="输出目录经引擎白名单校验，手机端自动采用第一项",
            font=_font(10), text_color=("gray50", "gray60"), anchor="e",
        )
        self.output_hint.pack(side="right")

        # 控制区
        control = ctk.CTkFrame(self, corner_radius=12)
        control.pack(fill="x", padx=20, pady=(0, 8))
        self.led = ctk.CTkLabel(control, text="●", font=_font(22), width=28)
        self.led.pack(side="left", padx=(14, 2), pady=10)
        self.status_label = ctk.CTkLabel(control, text="", font=_font(12, "bold"),
                                         width=110, anchor="w")
        self.status_label.pack(side="left")
        self.hide_button = ctk.CTkButton(
            control, text="隐藏到托盘", width=100, height=32, font=_font(11),
            fg_color=BTN_SECONDARY, hover_color=BTN_SECONDARY_HOVER,
            command=self.hide_to_tray,
        )
        self.hide_button.pack(side="right", padx=(6, 12), pady=8)
        self.log_button = ctk.CTkButton(control, text="日志", width=64, height=32,
                                        font=_font(11), command=self._open_log)
        self.log_button.pack(side="right", padx=6, pady=8)
        self.stop_button = ctk.CTkButton(
            control, text="⏹ 停止", width=84, height=32, font=_font(12),
            fg_color=BTN_STOP, hover_color=BTN_STOP_HOVER,
            text_color_disabled=BTN_DISABLED_TEXT, command=self._on_stop,
        )
        self.stop_button.pack(side="right", padx=6, pady=8)
        self.start_button = ctk.CTkButton(
            control, text="▶ 启动", width=84, height=32, font=_font(12),
            fg_color=BTN_START, hover_color=BTN_START_HOVER,
            text_color_disabled=BTN_DISABLED_TEXT, command=self._on_start,
        )
        self.start_button.pack(side="right", padx=6, pady=8)

        # 底部两行状态
        self.env_label = ctk.CTkLabel(self, text="环境自检中…", font=_font(10),
                                     text_color=("gray50", "gray60"), anchor="w")
        self.env_label.pack(fill="x", padx=22, pady=(0, 0))
        self.detail_label = ctk.CTkLabel(self, text="", font=_font(10),
                                         text_color=("gray40", "gray65"), anchor="w",
                                         justify="left", wraplength=670)
        self.detail_label.pack(fill="x", padx=22, pady=(0, 10))

        # 记下主题原始配色：引擎运行时把设置区统一刷成灰色禁用态，
        # 否则 CTk 的 disabled 只改文字颜色，白底输入框/蓝底下拉框仍像可编辑。
        for widget in (self.host_menu, self.port_entry, self.output_entry, self.browse_button):
            saved: dict = {}
            for key in ("fg_color", "button_color", "border_color"):
                try:
                    saved[key] = widget.cget(key)
                except Exception:  # 部分控件没有该选项
                    pass
            self._settings_look.append((widget, saved))

    # -- 配置与控件 -----------------------------------------------------------
    def _apply_config_to_widgets(self) -> None:
        self.host_menu.set(HOST_LABELS[self.cfg["host"]])
        self.port_entry.delete(0, "end")
        self.port_entry.insert(0, str(self.cfg["port"]))
        self.output_entry.delete(0, "end")
        self.output_entry.insert(0, self.cfg["output_dir"])
        self.appearance_menu.set(APPEARANCE_LABELS[self.cfg["appearance"]])
        if self.cfg["auto_start"]:
            self.auto_start_switch.select()
        else:
            self.auto_start_switch.deselect()
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
        self.engine_state = state
        self.led.configure(text_color=LED_COLOR[state])
        self.status_label.configure(text=STATUS_TEXT[state])
        running = state in (STATE_STARTING, STATE_RUNNING, STATE_STOPPING)
        stoppable = state in (STATE_STARTING, STATE_RUNNING)
        self._safe_state(self.start_button, "disabled" if running else "normal")
        self._safe_state(self.stop_button, "normal" if stoppable else "disabled")
        self._paint_button(self.start_button, running, BTN_START, BTN_START_HOVER)
        self._paint_button(self.stop_button, not stoppable, BTN_STOP, BTN_STOP_HOVER)
        for widget in (self.host_menu, self.port_entry, self.output_entry, self.browse_button):
            self._safe_state(widget, "disabled" if running else "normal")
        self._paint_settings(running)
        if detail:
            self._set_detail(detail, error=(state == STATE_ERROR))
        self._update_tray_icon()
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
                button.configure(fg_color=BTN_DISABLED, hover_color=BTN_DISABLED)
            else:
                button.configure(fg_color=active, hover_color=hover)
        except Exception as exc:
            L.log(f"设置按钮配色失败: {exc!r}")

    # -- 引擎启停 -------------------------------------------------------------
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
        threading.Thread(target=self._start_worker, args=(gen, host, port, out),
                         name="bgv-engine-start", daemon=True).start()

    def _start_worker(self, gen: int, host: str, port: int, out: str) -> None:
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
                                  extra_env={"BGV_OUTPUT_DIR_ALLOWLIST": out})
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

    # -- 环境自检 -------------------------------------------------------------
    def _env_worker(self) -> None:
        ffmpeg = L.ffmpeg_ready()
        chromium = L.playwright_browser_ready()
        self.ui_call(self._env_report, ffmpeg, chromium)
        if not chromium:
            L.install_chromium_async(
                lambda ok, detail: self.ui_call(self._chromium_done, ok, detail))

    def _env_report(self, ffmpeg: bool, chromium: bool) -> None:
        self.env_label.configure(
            text=f"环境自检 · FFmpeg {'✓' if ffmpeg else '✗ 缺失'} · "
                 f"Playwright Chromium {'✓' if chromium else '✗ 正在自动安装'}"
        )
        if self.smoke:
            return
        if not ffmpeg:
            messagebox.showwarning("环境自检 · 缺少 FFmpeg", L.FFMPEG_HINT)
        if not chromium:
            messagebox.showwarning("环境自检 · 缺少 Chromium", L.CHROMIUM_HINT)

    def _chromium_done(self, ok: bool, detail: str) -> None:
        self.env_label.configure(text=f"环境自检 · Chromium {'安装完成 ✓' if ok else '自动安装失败 ✗'}")
        if not ok and not self.smoke:
            messagebox.showerror("Chromium 自动安装失败",
                                "请手动执行：\n  .venv\\Scripts\\python.exe -m playwright install chromium\n\n"
                                f"错误摘要：\n{detail}")

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
        print(f"{'PASS' if ok else 'FAIL'} | {name} | {detail}", flush=True)

    def _smoke_begin(self) -> None:
        self._smoke_check("主界面构建并可见", self.engine_state == STATE_STOPPED and self.winfo_width() >= 680,
                          f"geometry={self.winfo_width()}x{self.winfo_height()}")
        self._smoke_check("黄色安全警告显眼存在", "切勿暴露在公网" in self.warn_label.cget("text"))
        self._smoke_check("连接地址已计算", self.addr_label.cget("text").startswith("http://"),
                          self.addr_label.cget("text"))
        self._smoke_check("默认不自动启动引擎", self.engine_state == STATE_STOPPED and self.engine.proc is None)
        self._smoke_check("托盘菜单四项齐备",
                          all(x in self._tray_labels for x in TRAY_REQUIRED_LABELS),
                          "/".join(x for x in self._tray_labels if x != "- - - -"))

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

    def _capture(self, path: str, label: str) -> None:
        try:
            from PIL import ImageGrab
            self.lift()
            self.attributes("-topmost", True)
            self.update_idletasks()
            self.update()
            time.sleep(0.35)
            x, y = self.winfo_rootx(), self.winfo_rooty()
            w, h = self.winfo_width(), self.winfo_height()
            ImageGrab.grab(bbox=(x, y, x + w, y + h)).save(path)
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
    """读取 /v1/privacy/selfcheck；失败返回 None，由调用方做成硬断言。"""
    import urllib.request
    try:
        with urllib.request.urlopen(
                f"http://127.0.0.1:{port}/v1/privacy/selfcheck", timeout=2.0) as resp:
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
    report = arg_value("--smoke-report")
    app = ControlPanel(smoke=True, screenshot_path=shot, dark_shot=dark_shot)
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
    print(f"===== gui-smoke: {passed}/{total} PASS =====", flush=True)
    print(f"GUI_SMOKE_RESULT={passed}/{total}", flush=True)

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
            print(f"写冒烟报告失败: {exc!r}", flush=True)
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

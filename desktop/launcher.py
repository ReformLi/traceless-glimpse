"""M15 第一步：PC 引擎托盘启动器（Windows）。

双击 `BrowseGuardEngine.exe`（或源码模式直接跑本文件）后：

1. 环境自检：FFmpeg 是否在 PATH、Playwright Chromium 是否已装（缺则提示 + 后台自动安装）；
2. 用 subprocess 后台拉起 ``python -m uvicorn main:app --host 0.0.0.0 --port 8730``，
   默认强制 ``BGV_ENGINE_MODE=real``，stdout/stderr 合并重定向到 ``engine.log``；
3. 托盘显示盾牌图标，启动成功后自动弹出局域网连接地址，菜单提供
   「显示连接地址 / 查看引擎日志 / 退出」；
4. 端口被占用、引擎闪退时图标转红并弹窗报错；退出时杀干净子进程树。

依赖（仅托盘层需要，引擎本身不需要）：``pystray`` + ``Pillow``。
源码开发时：``.venv\\Scripts\\pip install pystray Pillow``。

本文件同时提供两个不依赖桌面的验证入口，供 CI / 本地无人值守验收：

- ``--self-test``   无头跑通「拉起引擎 → 显示 IP → 退出杀进程」并做硬断言，不需要 pystray；
- ``--tray-smoke``  短暂创建真实托盘（3 秒后自动退出），验证图标与菜单能构建。

M15.3 打包后的分叉集中在 :func:`build_engine_command`：``sys.frozen=True`` 时
``sys.executable`` 指向 exe 自身、没有独立 ``python.exe`` 可 spawn，于是让 exe 以
``--engine-child`` 再启动一次，由 ``entry.py`` 在子进程内 ``uvicorn.run(app, ...)``。
进程树清理、就绪探测、日志重定向、错误捕获全部原样复用，无第二套实现。
"""
from __future__ import annotations

import ctypes
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

# --------------------------------------------------------------------------- 配置

#: 是否运行在 PyInstaller 单文件 exe 内。单文件下 sys.executable 指向 exe 自身，
#: 既没有独立的 python.exe 可 spawn，__file__ 也指向解压临时目录中的 .pyc，
#: 必须按 sys._MEIPASS 重算所有路径。
FROZEN = bool(getattr(sys, "frozen", False))
_MEIPASS = getattr(sys, "_MEIPASS", None)
BUNDLE_ROOT = (Path(_MEIPASS).resolve() if _MEIPASS
               else Path(sys.executable).resolve().parent)

if FROZEN:
    SCRIPT_DIR = BUNDLE_ROOT
    REPO_ROOT = BUNDLE_ROOT
    # build_exe.py 把引擎运行期源码放进 _MEIPASS/engine_src，作为子进程 sys.path 兜底。
    _default_engine_dir = BUNDLE_ROOT / "engine_src"
else:
    SCRIPT_DIR = Path(__file__).resolve().parent
    REPO_ROOT = SCRIPT_DIR.parent
    _default_engine_dir = REPO_ROOT / "engine"

#: 引擎目录。源码布局下就是仓库里的 engine/；打包后指向随 exe 分发的 engine_src/。
ENGINE_DIR = Path(os.environ.get("BGV_ENGINE_DIR", _default_engine_dir)).resolve()
ENGINE_PYTHON_OVERRIDE = os.environ.get("BGV_ENGINE_PYTHON")

#: 子命令标志。GUI 进程用它们让 exe 以「引擎 / Playwright 安装」身份再启动一次。
ENGINE_CHILD_FLAG = "--engine-child"
PLAYWRIGHT_INSTALL_FLAG = "--playwright-install"

#: 默认监听 0.0.0.0 而非协议文档的 127.0.0.1 —— M15 的目标就是让手机能连。
HOST = os.environ.get("BGV_ENGINE_HOST", "0.0.0.0")
PORT = int(os.environ.get("BGV_ENGINE_PORT", "8730"))

#: 日志与托盘数据目录：打包后放 exe 同目录，源码模式放 desktop/。
def data_dir() -> Path:
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return Path(os.environ.get("BGV_DESKTOP_DATA_DIR", SCRIPT_DIR)).resolve()


LOG_PATH = data_dir() / "engine.log"

APP_NAME = "无痕掠影 · 本地引擎"
SHIELD_BLUE = (46, 125, 209, 255)   # 正常运行
SHIELD_RED = (217, 48, 37, 255)     # 异常（端口占用 / 引擎闪退）

IS_WINDOWS = sys.platform == "win32"

MB_OK = 0x0
MB_ICONINFORMATION = 0x40
MB_ICONERROR = 0x10
MB_TOPMOST = 0x40000

# 单文件 exe 每次启动都要把约百 MB 的随包文件解压到临时目录，首启比源码态慢。
READY_TIMEOUT_SECONDS = 75.0 if FROZEN else 30.0


def log(msg: str) -> None:
    """启动器自己的诊断输出。引擎的 stdout/stderr 不在这里，全部进 engine.log。"""
    print(f"[launcher] {msg}", flush=True)


# --------------------------------------------------------------------------- Windows 弹窗

def message_box(title: str, text: str, error: bool = False) -> None:
    """弹一个原生 Windows 消息框，且不阻塞调用方。

    ``MessageBoxW`` 是模态的：直接在 pystray 的菜单回调里调用会把托盘线程卡住，
    所以一律丢到独立 daemon 线程。非 Windows / 无头自检模式下降级为打印。
    """
    if _HEADLESS or not IS_WINDOWS:
        log(f"{'ERROR' if error else 'INFO'} | {title} | {text.replace(chr(10), ' / ')}")
        return

    def _show() -> None:
        try:
            flags = MB_OK | (MB_ICONERROR if error else MB_ICONINFORMATION) | MB_TOPMOST
            ctypes.windll.user32.MessageBoxW(0, text, title, flags)
        except Exception as exc:  # 弹窗失败不能拖垮托盘
            log(f"MessageBoxW 失败: {exc!r}")

    threading.Thread(target=_show, name="bgv-msgbox", daemon=True).start()


_HEADLESS = False  # --self-test 时置 True：只打印，不弹窗、不建托盘


# --------------------------------------------------------------------------- 环境自检（第三步）

def chromium_cache_dir() -> Path | None:
    """Playwright 浏览器根目录。尊重用户自定义的 PLAYWRIGHT_BROWSERS_PATH。"""
    override = os.environ.get("PLAYWRIGHT_BROWSERS_PATH")
    if override:
        return Path(override)
    if IS_WINDOWS:
        local = os.environ.get("LOCALAPPDATA")
        if local:
            return Path(local) / "ms-playwright"
        return None
    return Path.home() / ".cache" / "ms-playwright"


def playwright_browser_ready() -> bool:
    """缓存目录里至少有一个 chromium-* 或 chromium_headless_shell-* 才算就绪。

    只认目录名不认空壳：``playwright install`` 中断会留下半成品目录，
    再往里看一层（有 chrome.exe / headless_shell / INSTALLATION_COMPLETE 之一）。
    """
    root = chromium_cache_dir()
    if root is None or not root.is_dir():
        return False
    for child in root.iterdir():
        if not child.is_dir():
            continue
        name = child.name.lower()
        if not (name.startswith("chromium-") or name.startswith("chromium_headless_shell-")):
            continue
        if (child / "INSTALLATION_COMPLETE").exists():
            return True
        if any((child / p).exists() for p in ("chrome.exe", "headless_shell", "chrome-linux/chrome")):
            return True
    return False


def ffmpeg_ready() -> bool:
    """纯托盘模式沿用：只看系统 PATH。GUI 模式请用 :func:`probe_dependencies`。"""
    return shutil.which("ffmpeg") is not None


# --------------------------------------------------------------------------- M16.5 依赖面板
#: GUI 把用户选的 ffmpeg.exe 绝对路径经这个变量注入引擎子进程（引擎侧由
#: ``services/ffmpeg_path.py`` 统一读取）。
FFMPEG_ENV = "BGV_FFMPEG_PATH"


def ffmpeg_resolve(configured: str | None = None) -> tuple[str | None, str]:
    """解析当前生效的 ffmpeg：``(绝对路径 | None, 来源)``。

    ``来源`` 为 ``"custom"``（用户配置）或 ``"path"``（系统 PATH）。配了自定义
    路径但文件不存在时返回 ``(None, "custom")`` 而不是悄悄用 PATH 里的版本——
    界面要能如实显示「自定义路径失效」，引擎侧同样不会静默回落。
    """
    raw = (configured or "").strip().strip('"').strip("'")
    if raw:
        path = Path(raw).expanduser()
        return (str(path.resolve()), "custom") if path.is_file() else (None, "custom")
    found = shutil.which("ffmpeg")
    return (str(Path(found).resolve()), "path") if found else (None, "path")


def ffmpeg_version(executable: str | None) -> str:
    """``ffmpeg -version`` 首行的版本号；拿不到返回空串（不抛异常）。"""
    if not executable:
        return ""
    try:
        proc = subprocess.run(
            [executable, "-version"], capture_output=True, text=True,
            encoding="utf-8", errors="replace", timeout=8,
            creationflags=_creation_flags(), check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return ""
    match = re.search(r"ffmpeg version (\S+)", proc.stdout or "", re.IGNORECASE)
    return match.group(1) if match else ""


def probe_dependencies(configured_ffmpeg: str | None = None) -> dict:
    """一次算齐依赖面板要显示的全部事实，供后台线程调用（不碰 Tk）。"""
    exe, source = ffmpeg_resolve(configured_ffmpeg)
    return {
        "ffmpeg_found": exe is not None,
        "ffmpeg_path": exe or "",
        "ffmpeg_source": source,
        "ffmpeg_version": ffmpeg_version(exe) if exe else "",
        "chromium": playwright_browser_ready(),
    }


def engine_python() -> str:
    """优先用 engine/.venv 的解释器：系统 PATH 里的 python 常是 Store 别名或没装依赖。"""
    if ENGINE_PYTHON_OVERRIDE:
        return ENGINE_PYTHON_OVERRIDE
    if IS_WINDOWS:
        venv = ENGINE_DIR / ".venv" / "Scripts" / "python.exe"
    else:
        venv = ENGINE_DIR / ".venv" / "bin" / "python"
    if venv.is_file():
        return str(venv)
    return sys.executable


FFMPEG_HINT = (
    "未检测到 FFmpeg（PATH 里找不到 ffmpeg）。\n\n"
    "影响：音视频分离轨合并、转封装会失败；直链下载仍可用。引擎仍会启动。\n\n"
    "安装方式（任选其一）：\n"
    "  · winget install --id Gyan.FFmpeg -e\n"
    "  · 从 https://www.gyan.dev/ffmpeg/builds/ 下载，把 bin 目录加入 PATH\n"
    "装完重启本程序即可。\n\n"
    "M16.5：也可在「引擎设置 → 环境依赖自检」点「浏览…」直接指定 "
    "ffmpeg.exe 的绝对路径，无需改动 PATH。"
)

CHROMIUM_HINT = (
    "未检测到 Playwright Chromium 浏览器内核。\n\n"
    "启动器会在后台自动执行：\n"
    "  python -m playwright install chromium\n"
    "（首次需下载约 100 MB，取决于网络；期间托盘保持可用。）\n\n"
    "若自动安装失败，可在引擎目录手动执行：\n"
    r"  .venv\Scripts\python.exe -m playwright install chromium" "\n"
    "（打包版 exe 无需命令行，程序会自动调用内置的 Playwright 安装器。）"
)


_ANSI_ESCAPE = re.compile(r"\x1b\[[0-9;?]*[ -/]*[@-~]")


def install_chromium_async(on_done, on_line=None) -> None:
    """后台装 Chromium，绝不阻塞托盘与引擎启动。

    M16.5 起支持 ``on_line(line)`` 逐行回吐安装日志：Playwright 的下载进度条
    用 ``\\r`` 而不是 ``\\n`` 刷新，所以这里按字符读、``\\r``/``\\n`` 都切行，
    GUI 才能实时看到「下载 xx MiB / xx%」而不是结束后一次性弹个结果。

    回调都在后台线程触发，GUI 侧必须经 ``ui_call`` 回主线程再碰控件。
    不传 ``on_line`` 时行为与纯托盘模式一致（只在结束时汇总末尾 1200 字符）。
    """
    def _run() -> None:
        if FROZEN:
            # 打包版没有独立解释器，让 exe 以 --playwright-install 子命令再启动一次，
            # 在子进程内调用 playwright.__main__.main()（入口见 entry.py）。
            cmd = [sys.executable, PLAYWRIGHT_INSTALL_FLAG]
            cwd = str(data_dir())
        else:
            cmd = [engine_python(), "-m", "playwright", "install", "chromium"]
            cwd = str(ENGINE_DIR)
        lines: list[str] = []

        def emit(raw: str) -> None:
            text = _ANSI_ESCAPE.sub("", raw).strip()
            if not text:
                return
            lines.append(text)
            if on_line is not None:
                try:
                    on_line(text)
                except Exception as exc:  # 日志回调异常不能拖垮安装
                    log(f"安装日志回调失败: {exc!r}")

        proc = None
        ok = False
        try:
            proc = subprocess.Popen(
                cmd, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                text=True, encoding="utf-8", errors="replace", bufsize=1,
                creationflags=_creation_flags(),
            )
            started = time.monotonic()
            buf = ""
            while proc.stdout is not None:
                ch = proc.stdout.read(1)
                if not ch:
                    break
                if ch in "\r\n":
                    emit(buf)
                    buf = ""
                else:
                    buf += ch
                    if len(buf) >= 400:  # 无换行的超长进度条也要能刷出来
                        emit(buf)
                        buf = ""
                if time.monotonic() - started > 900:
                    raise subprocess.TimeoutExpired(cmd, 900)
            if buf:
                emit(buf)
            proc.wait(timeout=30)
            ok = proc.returncode == 0
        except Exception as exc:
            if proc is not None:
                try:
                    proc.kill()
                except OSError:
                    pass
            emit(f"安装进程异常：{exc!r}")
        on_done(ok, "\n".join(lines).strip()[-1200:])

    threading.Thread(target=_run, name="bgv-install-chromium", daemon=True).start()


def check_environment(notify=None) -> dict[str, bool]:
    """启动引擎前自检。缺失项弹窗告知，Chromium 缺失时后台自动补装。

    FFmpeg 与浏览器都不是引擎**启动**的硬前提（startup 不初始化 Playwright），
    所以这里只提示、不阻断，避免「缺一个可选依赖就整个起不来」。
    """
    ffmpeg = ffmpeg_ready()
    chromium = playwright_browser_ready()
    result = {"ffmpeg": ffmpeg, "chromium": chromium}

    if not ffmpeg:
        log("环境自检：FFmpeg 缺失")
        message_box("环境自检 · 缺少 FFmpeg", FFMPEG_HINT, error=True)
    if not chromium:
        log("环境自检：Chromium 缺失，后台自动安装")
        message_box("环境自检 · 缺少 Chromium", CHROMIUM_HINT, error=True)

        def _done(ok: bool, detail: str) -> None:
            if ok:
                log("Chromium 自动安装完成")
                if notify:
                    notify("Chromium 安装完成", "无痕掠影 · 环境就绪")
            else:
                log(f"Chromium 自动安装失败: {detail}")
                message_box(
                    "Chromium 自动安装失败",
                    "请手动执行：\n  .venv\\Scripts\\python.exe -m playwright install chromium\n\n"
                    f"错误摘要：\n{detail}",
                    error=True,
                )

        install_chromium_async(_done)
    else:
        log(f"环境自检通过：FFmpeg={'OK' if ffmpeg else '缺失'} / Chromium=OK")
    return result


# --------------------------------------------------------------------------- 网络

def lan_ip() -> str:
    """取手机能连到的那张网卡的 IPv4。

    UDP connect 不实际发包，只是让内核按默认路由挑一个出口 IP —— 通常正是
    连着 WiFi 的那张网卡，比 ``gethostbyname(gethostname())`` 少踩虚拟网卡的坑。
    """
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    probe.settimeout(0.5)
    try:
        probe.connect(("223.5.5.5", 80))
        ip = probe.getsockname()[0]
        if ip and not ip.startswith("127."):
            return ip
    except OSError:
        pass
    finally:
        probe.close()

    try:
        for family, _, _, _, sockaddr in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = sockaddr[0]
            if not ip.startswith("127.") and not ip.startswith("169.254."):
                return ip
    except OSError:
        pass
    return "127.0.0.1"


def connection_url(port: int | None = None) -> str:
    """局域网连接地址。``port=None`` 时沿用模块默认（兼容纯托盘模式的自检）。"""
    return f"http://{lan_ip()}:{PORT if port is None else port}"


def port_in_use(port: int = PORT) -> bool:
    """端口是否已被监听。用于启动前拦截「另一个引擎实例 / 端口被占」。"""
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(0.4)
    try:
        return s.connect_ex(("127.0.0.1", port)) == 0
    finally:
        s.close()


def read_log_tail(lines: int = 25) -> str:
    try:
        text = LOG_PATH.read_text(encoding="utf-8", errors="replace")
        return "\n".join(text.splitlines()[-lines:])
    except OSError:
        return "(engine.log 暂不可读)"


def health_ok(url: str, timeout: float = 1.0) -> bool:
    try:
        with urllib.request.urlopen(f"{url}/v1/health", timeout=timeout) as resp:
            if resp.status != 200:
                return False
            payload = json.loads(resp.read().decode("utf-8", "replace"))
            return payload.get("status") == "ok"
    except (urllib.error.URLError, OSError, ValueError, TimeoutError):
        return False


def wait_until_ready(url: str, proc: subprocess.Popen, timeout: float) -> tuple[bool, str]:
    """等 ``/v1/health`` 变绿。进程提前退出（端口占用 / import 失败）立即判失败并带上日志尾。"""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        # 用户可能在探测途中点了「退出」，此时 self.engine.proc 已被置空。
        if proc is None:
            return False, "引擎进程已停止（用户退出）"
        if proc.poll() is not None:
            return False, f"引擎进程提前退出（exit={proc.returncode}）\n\n{read_log_tail()}"
        if health_ok(url):
            return True, "ok"
        time.sleep(0.4)
    return False, f"引擎在 {timeout:.0f} 秒内未通过 /v1/health 就绪探测\n\n{read_log_tail()}"


def address_text() -> str:
    url = connection_url()
    return (
        f"{APP_NAME} 已启动\n\n"
        f"连接地址（填入手机 App 设置页）：\n{url}\n\n"
        "使用要点：\n"
        "  · 手机与电脑连同一个 WiFi\n"
        "  · 地址必须带 http:// 前缀，不要填 0.0.0.0\n"
        f"  · 手机浏览器打开 {url}/v1/health 应显示 status=ok；\n"
        "    打不开时请在 Windows 防火墙放行 TCP 8730\n\n"
        "安全提示：引擎监听 0.0.0.0（无入站鉴权），仅在可信局域网内使用，\n"
        "不要映射到公网或暴露给不可信网络。"
    )


# --------------------------------------------------------------------------- 引擎子进程

def _creation_flags() -> int:
    if not IS_WINDOWS:
        return 0
    # CREATE_NO_WINDOW：双击 exe 不弹黑框，这是「后台启动」的关键。
    return subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.CREATE_NO_WINDOW


def build_engine_command(host: str | None = None, port: int | None = None) -> list[str]:
    """引擎启动命令。``host`` / ``port`` 由 GUI 传入，不传时沿用默认，纯托盘模式不变。

    **打包与源码的分叉只在这一处**：

    * 源码态 —— 有 ``engine/.venv/Scripts/python.exe``，直接
      ``python -m uvicorn main:app ...``，cwd 指向 ``engine/``；
    * ``sys.frozen``（PyInstaller 单文件）—— 没有独立解释器可 spawn，于是让
      **exe 自身**以 ``--engine-child`` 再启动一次；``entry.py`` 识别该标志后在
      子进程内直接 ``uvicorn.run(app, host, port)``。stdout/stderr 依旧由
      :meth:`EngineProcess.start` 的 Popen 重定向到 ``engine.log``，所以
      ``taskkill /T /F``、就绪探测、日志、错误捕获整套生命周期逻辑完全复用。
    """
    use_host = HOST if host is None else host
    use_port = PORT if port is None else port
    if FROZEN:
        return [
            sys.executable, ENGINE_CHILD_FLAG,
            "--host", use_host, "--port", str(use_port),
        ]
    return [
        engine_python(), "-m", "uvicorn", "main:app",
        "--host", use_host, "--port", str(use_port),
        "--log-level", "info",
    ]


def process_alive(pid: int | None) -> bool:
    """指定 PID 是否还活着。用于证明「退出后没有留下孤儿 uvicorn」。"""
    if pid is None or pid <= 0:
        return False
    if not IS_WINDOWS:
        try:
            os.kill(pid, 0)
            return True
        except OSError:
            return False
    try:
        # tasklist 在中文 Windows 输出 GBK（无匹配时那句「信息: 没有运行的任务…」），
        # 用 text=True 会按 UTF-8 解码炸掉。这里拿原始字节、只找 ASCII 的 PID 数字。
        raw = subprocess.run(
            ["tasklist", "/FI", f"PID eq {pid}", "/NH"],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=6,
            creationflags=subprocess.CREATE_NO_WINDOW,
        ).stdout
        return str(pid) in raw.decode("utf-8", "replace")
    except Exception:
        return False


class EngineProcess:
    """持有一个 uvicorn 子进程的完整生命周期。"""

    def __init__(self) -> None:
        self.proc: subprocess.Popen | None = None
        self.log_handle = None
        self._lock = threading.Lock()
        #: uvicorn 自报的 server PID。Windows venv 的 python.exe 是重定向器，
        #: 会 spawn 一个真实 Python —— 这个 PID 与 Popen.pid 不同，必须单独确认它也没了。
        self.server_pid: int | None = None
        #: 本次启动实际使用的端口，stop() 据此做「残留端口」校验（GUI 可自定义端口）。
        self.active_port: int | None = None

    def discover_server_pid(self) -> int | None:
        """从 engine.log 抓 ``Started server process [NNNN]``（取最后一次启动）。"""
        try:
            text = LOG_PATH.read_text(encoding="utf-8", errors="replace")
        except OSError:
            return None
        found = re.findall(r"Started server process \[(\d+)\]", text)
        if not found:
            return None
        self.server_pid = int(found[-1])
        return self.server_pid

    def start(
        self,
        host: str | None = None,
        port: int | None = None,
        extra_env: dict[str, str] | None = None,
    ) -> None:
        """拉起 uvicorn。GUI 通过 ``host`` / ``port`` / ``extra_env`` 传用户设置，
        纯托盘模式不传任何参数时行为与 M15 第一步完全一致。

        ``extra_env`` 的典型用途是 ``BGV_OUTPUT_DIR_ALLOWLIST``：引擎在
        :func:`main._load_output_dir_allowlist` 读取它，手机端再取
        ``/v1/privacy/selfcheck`` 的 ``outputDirAllowlist[0]`` 作为落盘目录。
        """
        use_host = HOST if host is None else host
        use_port = PORT if port is None else port
        data_dir().mkdir(parents=True, exist_ok=True)
        env = os.environ.copy()
        # 默认强制 real：桩模式是协议联调用的，桌面启动器面向真实下载，不允许静默跑成 stub。
        env["BGV_ENGINE_MODE"] = "real"
        # 让 uvicorn 日志实时、以 UTF-8 落盘，否则 Windows 控制台默认编码会把中文写乱，
        # 且全缓冲会让「查看引擎日志」看到空文件。
        env["PYTHONUNBUFFERED"] = "1"
        env["PYTHONIOENCODING"] = "utf-8"
        # 让 /v1/privacy/selfcheck 的 listenAddress 回显本次真实绑定，而不是
        # 协议默认的 127.0.0.1:8730（M15.3 裁决：引擎侧改为动态读取）。
        env["BGV_LISTEN_HOST"] = use_host
        env["BGV_LISTEN_PORT"] = str(use_port)
        if extra_env:
            env.update(extra_env)

        self.active_port = use_port
        self.log_handle = open(LOG_PATH, "a", encoding="utf-8", errors="replace")
        self.log_handle.write(
            f"\n===== {time.strftime('%Y-%m-%d %H:%M:%S')} "
            f"{APP_NAME} start (host={use_host} port={use_port} mode=real) =====\n"
        )
        self.log_handle.flush()

        cmd = build_engine_command(use_host, use_port)
        # 打包版若未随包分发 engine_src/，cwd 退回数据目录，不让 Popen 因目录不存在直接失败。
        cwd = str(ENGINE_DIR) if ENGINE_DIR.is_dir() else str(data_dir())
        log(f"启动引擎: {' '.join(cmd)}  (cwd={cwd})")
        with self._lock:
            self.proc = subprocess.Popen(
                cmd,
                cwd=cwd,
                env=env,
                stdin=subprocess.DEVNULL,
                stdout=self.log_handle,
                stderr=subprocess.STDOUT,
                creationflags=_creation_flags(),
            )

    @property
    def pid(self) -> int | None:
        with self._lock:
            return self.proc.pid if self.proc else None

    def poll(self):
        with self._lock:
            return self.proc.poll() if self.proc else None

    def stop(self, port: int | None = None) -> bool:
        """干净杀掉子进程树，返回「确认已退出」。

        Windows 上先 ``taskkill /T /F`` 收整棵树（venv 的 python.exe 只是重定向器，
        真正跑 uvicorn 的是它 spawn 的子进程；只 terminate 父进程会留下占端口的孤儿），
        再 ``terminate`` + ``wait`` 兜底，最后按 server PID 与端口做残留校验。
        """
        use_port = port if port is not None else (self.active_port or PORT)
        with self._lock:
            proc = self.proc
        if proc is None:
            self._close_log()
            return True

        # 杀之前先记下 uvicorn 自报的 PID，供强杀后的存活校验。
        self.discover_server_pid()

        if proc.poll() is None:
            if IS_WINDOWS:
                try:
                    subprocess.run(
                        ["taskkill", "/PID", str(proc.pid), "/T", "/F"],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                        timeout=10, creationflags=subprocess.CREATE_NO_WINDOW,
                    )
                except Exception as exc:
                    log(f"taskkill 不可用，改走 terminate: {exc!r}")
            try:
                proc.terminate()
            except OSError:
                pass
            try:
                proc.wait(timeout=8)
            except subprocess.TimeoutExpired:
                try:
                    proc.kill()
                    proc.wait(timeout=5)
                except Exception:
                    pass

        # 兜底：若子进程脱离了进程组、树没杀干净，按 uvicorn 自报 PID 再杀一次。
        if port_in_use(use_port) and self.server_pid and self.server_pid != proc.pid:
            log(f"检测到残留 server 进程 {self.server_pid}，补杀")
            try:
                subprocess.run(
                    ["taskkill", "/PID", str(self.server_pid), "/T", "/F"],
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                    timeout=10, creationflags=subprocess.CREATE_NO_WINDOW,
                )
            except Exception as exc:
                log(f"补杀失败: {exc!r}")

        with self._lock:
            self.proc = None
        self._close_log()
        return proc.poll() is not None

    def _close_log(self) -> None:
        if self.log_handle is not None:
            try:
                self.log_handle.flush()
                self.log_handle.close()
            except OSError:
                pass
            self.log_handle = None


# --------------------------------------------------------------------------- 托盘

def shield_image(color: tuple[int, int, int, int], size: int = 64):
    """用 Pillow 画盾牌。正常蓝、异常红，中间一个白色对勾。

    默认 64×64 供托盘使用；``size=256`` 供 PyInstaller 生成多尺寸 .ico
    （按比例缩放坐标，比把 64px 位图放大更清晰）。
    """
    from PIL import Image, ImageDraw

    scale = size / 64.0

    def points(seq):
        return [(round(x * scale), round(y * scale)) for x, y in seq]

    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    outline = points([(10, 7), (54, 7), (54, 31), (50, 43), (32, 58), (14, 43), (10, 31)])
    draw.polygon(outline, fill=color, outline=(255, 255, 255, 255))
    draw.line(points([(10, 7), (54, 7)]), fill=(255, 255, 255, 220),
              width=max(1, round(2 * scale)))
    # 对勾：三笔折线，缩到 16×16 依旧可辨
    draw.line(points([(20, 31), (28, 39), (45, 21)]), fill=(255, 255, 255, 255),
              width=max(1, round(5 * scale)), joint="curve")
    return img


class Launcher:
    """把进程、托盘、菜单回调、监控线程的状态收在一处，避免回调里摸全局变量。"""

    def __init__(self, headless: bool = False) -> None:
        global _HEADLESS
        _HEADLESS = headless
        self.engine = EngineProcess()
        self.icon = None
        self.stopping = threading.Event()
        self.state = "starting"  # starting | ready | error
        self.error_detail = ""

    # -- 状态与提示 -----------------------------------------------------------
    def _set_icon(self, red: bool, title: str) -> None:
        if self.icon is None:
            return
        try:
            self.icon.icon = shield_image(SHIELD_RED if red else SHIELD_BLUE)
            self.icon.title = title
        except Exception as exc:
            log(f"更新托盘图标失败: {exc!r}")

    def notify(self, message: str, title: str = APP_NAME) -> None:
        if self.icon is None:
            return
        try:
            self.icon.notify(message, title)
        except Exception as exc:
            log(f"托盘气泡失败: {exc!r}")

    def _mark_error(self, detail: str) -> None:
        self.state = "error"
        self.error_detail = detail
        log(f"错误: {detail.splitlines()[0]}")
        self._set_icon(True, f"{APP_NAME} · 异常")
        message_box("引擎启动失败", f"{detail}\n\n完整日志见 engine.log（托盘菜单可打开）。", error=True)

    # -- 菜单动作 -------------------------------------------------------------
    def on_show_address(self, icon=None, item=None) -> None:
        if self.state == "error":
            message_box("引擎未在运行", f"当前状态：异常\n\n{self.error_detail}", error=True)
        else:
            message_box("连接地址", address_text())

    def on_open_log(self, icon=None, item=None) -> None:
        try:
            if not LOG_PATH.exists():
                LOG_PATH.touch()
            if IS_WINDOWS:
                # 用系统默认程序打开（.log 通常关联记事本）
                os.startfile(str(LOG_PATH))  # noqa: S606 - Windows only
            else:
                subprocess.Popen(["xdg-open", str(LOG_PATH)])
        except Exception as exc:
            log(f"打开日志失败: {exc!r}")
            message_box("打不开日志", f"请手动打开：\n{LOG_PATH}", error=True)

    def on_quit(self, icon=None, item=None) -> None:
        log("用户点击退出")
        self.stopping.set()
        self.engine.stop()
        if self.icon is not None:
            try:
                self.icon.stop()
            except Exception:
                pass

    # -- 主流程 ---------------------------------------------------------------
    def start_engine(self) -> bool:
        """启动前端口预检 → Popen。返回是否可以继续等就绪。"""
        if port_in_use(PORT):
            self._mark_error(
                f"端口 {PORT} 已被占用：可能已有另一个引擎实例在运行。\n"
                "关掉旧实例后重试；或用 BGV_ENGINE_PORT 换一个端口。"
            )
            return False
        try:
            self.engine.start()
        except Exception as exc:
            self._mark_error(f"拉起 uvicorn 失败：{exc!r}\n\n{read_log_tail()}")
            return False
        return True

    def monitor(self) -> None:
        """后台线程：等就绪 / 捕获闪退 / 持续盯住进程。"""
        if not self.start_engine():
            return
        # 用 127.0.0.1 探就绪（一定会通），地址弹窗里再展示局域网 IP。
        ok, detail = wait_until_ready(f"http://127.0.0.1:{PORT}", self.engine.proc, READY_TIMEOUT_SECONDS)
        if self.stopping.is_set():
            return
        if not ok:
            self._mark_error(detail)
            return

        self.state = "ready"
        url = connection_url()
        log(f"引擎就绪：{url}  (pid={self.engine.pid})")
        self._set_icon(False, f"{APP_NAME} · {url}")
        # 核心目标「自动显示局域网连接信息」：启动成功弹一次 + 托盘气泡。
        self.notify(f"引擎已启动：{url}", "无痕掠影 · 连接地址")
        if not _HEADLESS:
            message_box("连接地址", address_text())

        # 进程活着就一直盯；用户主动退出时不误报。
        while not self.stopping.wait(1.0):
            if self.engine.poll() is not None:
                self._mark_error(
                    f"引擎进程意外退出（exit={self.engine.poll()}）。\n\n{read_log_tail()}"
                )
                return

    def run_tray(self, smoke_seconds: float = 0.0) -> None:
        try:
            import pystray
        except ImportError:
            message_box(
                "缺少托盘依赖",
                "请先在引擎虚拟环境安装托盘依赖：\n\n"
                r"  .venv\Scripts\pip install pystray Pillow" "\n\n"
                f"当前解释器：{sys.executable}",
                error=True,
            )
            return

        check_environment(self.notify)

        menu = pystray.Menu(
            pystray.MenuItem("显示连接地址", self.on_show_address),
            pystray.MenuItem("查看引擎日志", self.on_open_log),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("退出", self.on_quit),
        )
        self.icon = pystray.Icon("bgv-engine", shield_image(SHIELD_BLUE), APP_NAME, menu)

        threading.Thread(target=self.monitor, name="bgv-monitor", daemon=True).start()

        if smoke_seconds > 0:
            def _auto_stop() -> None:
                time.sleep(smoke_seconds)
                log("tray-smoke 到时自动退出")
                self.on_quit()
            threading.Thread(target=_auto_stop, daemon=True).start()

        # pystray 在 Windows 上需要主线程跑消息循环，必须阻塞在这里。
        self.icon.run()
        # icon.run() 返回 = 用户退出 / smoke 到时；兜底再清一次，防僵尸。
        self.stopping.set()
        self.engine.stop()


# --------------------------------------------------------------------------- 无头自检

def run_self_test() -> int:
    """无托盘、无弹窗地真实跑通三个动作并硬断言。

    覆盖：拉起引擎（real/0.0.0.0）→ 就绪探测 + 局域网 IP 可访问 → 端口占用检测
    → 退出杀进程 + 端口释放 + 日志重定向。不需要 pystray / Pillow。
    """
    launcher = Launcher(headless=True)
    results: list[tuple[str, bool, str]] = []

    def check(name: str, ok: bool, detail: str = "") -> None:
        results.append((name, bool(ok), detail))
        log(f"{'PASS' if ok else 'FAIL'} | {name} | {detail}")

    # [1] 环境自检函数本身可用（本机两项应都齐；不齐也不算失败，只记录）
    env = {"ffmpeg": ffmpeg_ready(), "chromium": playwright_browser_ready()}
    check("环境自检函数可执行", isinstance(env["ffmpeg"], bool) and isinstance(env["chromium"], bool),
          f"FFmpeg={'OK' if env['ffmpeg'] else '缺失'} / Chromium={'OK' if env['chromium'] else '缺失'}")
    check("引擎解释器可用", Path(engine_python()).is_file(), engine_python())
    check("引擎入口 main.py 存在", (ENGINE_DIR / "main.py").is_file(), str(ENGINE_DIR / "main.py"))

    # [2] 端口预占检测：先确认是空闲的，否则不启动（不误杀别人的引擎）
    if port_in_use(PORT):
        check("启动前端口空闲", False, f"端口 {PORT} 已被占用，跳过启动类断言")
        return _self_test_report(results)

    # [3] 拉起引擎
    started = launcher.start_engine()
    check("拉起引擎子进程", started, f"pid={launcher.engine.pid}")
    if not started:
        return _self_test_report(results)

    proc = launcher.engine.proc
    ok, detail = wait_until_ready(f"http://127.0.0.1:{PORT}", proc, READY_TIMEOUT_SECONDS)
    check("引擎 /v1/health 就绪", ok, detail.splitlines()[0] if not ok else "status=ok")
    if not ok:
        launcher.engine.stop()
        return _self_test_report(results)

    # [4] 显示 IP：算出局域网地址，并确认经该地址（而非仅回环）能访问，
    #     这正是「--host 0.0.0.0 让手机连得上」的可验证证据。
    ip = lan_ip()
    url = connection_url()
    check("计算局域网连接地址", bool(ip) and not ip.startswith("127.") or ip == "127.0.0.1", url)
    reachable = health_ok(f"http://{ip}:{PORT}", timeout=2.0)
    check("经局域网 IP 可访问 /v1/health", reachable, f"GET http://{ip}:{PORT}/v1/health")
    check("stdout/stderr 已重定向到 engine.log", LOG_PATH.is_file() and LOG_PATH.stat().st_size > 0,
          f"{LOG_PATH} ({LOG_PATH.stat().st_size if LOG_PATH.exists() else 0} B)")

    # [5] 端口占用检测在引擎运行时应为真（这是「端口被占用」错误提示的判据）
    check("运行中端口占用检测为真", port_in_use(PORT), f"port_in_use({PORT})=True")

    # [5b] 抓 uvicorn 自报 PID：Windows venv 的 python.exe 是重定向器，会 spawn 真实
    #      Python。这正是「只 terminate 父进程会留下占端口孤儿」的现场证据。
    server_pid = launcher.engine.discover_server_pid()
    check("识别真实 uvicorn server PID", server_pid is not None and process_alive(server_pid),
          f"Popen.pid={proc.pid} / server_pid={server_pid}（重定向器 spawn，二者不同属正常）")

    # [6] 退出杀进程 + 不留僵尸 + 端口释放
    clean = launcher.engine.stop()
    check("退出杀掉 uvicorn 子进程", clean and proc.poll() is not None,
          f"clean={clean} exit={proc.poll()}（taskkill /F 退出码 1 是强杀，非崩溃）")
    time.sleep(0.6)
    check("退出后 server 进程已消失（无孤儿）", not process_alive(server_pid),
          f"server_pid={server_pid} 已不存活")
    check("退出后端口已释放（无后台残留）", not port_in_use(PORT), f"port_in_use({PORT})=False")

    return _self_test_report(results)


def run_error_test() -> int:
    """无托盘验证两类错误捕获：端口占用、引擎闪退（第一步的错误分支需求）。"""
    launcher = Launcher(headless=True)
    results: list[tuple[str, bool, str]] = []

    def check(name: str, ok: bool, detail: str = "") -> None:
        results.append((name, bool(ok), detail))
        log(f"{'PASS' if ok else 'FAIL'} | {name} | {detail}")

    # 前置：上一轮托盘退出后不应留下占端口的僵尸进程。
    check("前置：端口空闲（上一轮未留僵尸）", not port_in_use(PORT), f"port_in_use({PORT})=False")

    # A. 端口被占用 → 拒绝启动、置错误态、图标转红（_mark_error 里换图标）
    holder = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    holder.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    holder.bind(("0.0.0.0", PORT))
    holder.listen(1)
    try:
        started = launcher.start_engine()
        check(
            "端口占用 → 拒绝启动并置错误态",
            (not started) and launcher.state == "error" and "占用" in launcher.error_detail,
            launcher.error_detail.splitlines()[0] if launcher.error_detail else "",
        )
    finally:
        holder.close()

    # B. 引擎闪退：伪装一条写日志后立刻退出的命令，验证就绪探测能抓到并带上日志尾。
    marker = f"SIMULATED_ENGINE_CRASH_{int(time.time())}"
    cmd = [engine_python(), "-c", f"import sys; print('{marker}', flush=True); sys.exit(3)"]
    with open(LOG_PATH, "a", encoding="utf-8") as f:
        proc = subprocess.Popen(cmd, stdout=f, stderr=subprocess.STDOUT, creationflags=_creation_flags())
    proc.wait(timeout=15)
    ok, detail = wait_until_ready(f"http://127.0.0.1:{PORT}", proc, 2.0)
    check("引擎闪退 → 就绪探测判失败", (not ok) and "提前退出" in detail,
          detail.splitlines()[0] if detail else "")
    check("闪退详情携带 engine.log 尾部", marker in detail, marker)

    return _self_test_report(results)


def _self_test_report(results: list[tuple[str, bool, str]]) -> int:
    passed = sum(1 for _, ok, _ in results if ok)
    total = len(results)
    log(f"===== self-test: {passed}/{total} PASS =====")
    log(f"engine.log 尾部：\n{read_log_tail(8)}")
    print(f"SELF_TEST_RESULT={passed}/{total}", flush=True)
    return 0 if passed == total else 1


# --------------------------------------------------------------------------- 入口

def main() -> int:
    args = sys.argv[1:]
    if "--self-test" in args:
        return run_self_test()
    if "--self-test-error" in args:
        return run_error_test()
    if "--tray-smoke" in args:
        Launcher(headless=True).run_tray(smoke_seconds=3.0)
        return 0
    Launcher(headless=False).run_tray()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

"""M15.3 · 把「控制面板 + Python 引擎」打包成单文件 ``BrowseGuardEngine.exe``。

用法（必须用装齐依赖的解释器，脚本会自动优先挑 ``engine/.venv``）::

    engine\\.venv\\Scripts\\python.exe desktop\\build_exe.py
    engine\\.venv\\Scripts\\python.exe desktop\\build_exe.py --icon-only   # 只生成盾牌 ico

产物：``desktop/dist/BrowseGuardEngine.exe``（单文件、无控制台、带盾牌图标）。

设计说明
--------
* 入口是 ``desktop/entry.py`` 而不是 ``gui_app.py``：单文件 exe 没有独立 Python
  可 spawn，``--engine-child`` 需要由同一个 exe 以「引擎身份」再启动一次，
  所以入口必须能按 argv 分派 GUI / 引擎 / Playwright 安装器三种身份。
* ``--hidden-import`` 拉齐用户指定的托盘 / GUI 层，以及引擎运行期模块；
  ``--paths`` 把 ``engine/`` 加进分析路径，PyInstaller 才能顺着 ``main.py``
  静态解析到 fastapi / uvicorn / httpx / playwright / yt_dlp。
* ``--collect-all playwright`` 必带：它包含 92 MB 的 ``driver/node.exe`` 与
  ``driver/package/*.js``，缺了浏览器嗅探在打包版里会直接失败（浏览器内核
  本身仍按 M15 约定放在 ``%LOCALAPPDATA%\\ms-playwright``，不进 exe）。
* ``--collect-submodules yt_dlp`` 必带：972 个 extractor 是运行期动态导入的。
"""
from __future__ import annotations

import argparse
import os
import subprocess
import sys
import time
from pathlib import Path

DESKTOP = Path(__file__).resolve().parent
REPO = DESKTOP.parent
ENGINE = REPO / "engine"
DIST_DIR = DESKTOP / "dist"
BUILD_DIR = DESKTOP / "build"
ICON_PATH = DESKTOP / "shield.ico"
ENTRY_SCRIPT = DESKTOP / "entry.py"
EXE_NAME = "BrowseGuardEngine"

# 用户指定的 GUI / 托盘层隐式依赖（PyInstaller 静态扫不到动态后端时兜底）
GUI_HIDDEN_IMPORTS = [
    "launcher", "gui_app", "entry", "download_console",
    "engine_client",  # M16 第二步：下载控制台网络层（httpx 流程编排）
    "customtkinter", "darkdetect", "pystray",
    "PIL", "PIL.Image", "PIL.ImageDraw", "PIL.ImageGrab", "PIL.ImageTk",
    "tkinter", "tkinter.filedialog", "tkinter.messagebox", "tkinter.font",
]

# 引擎运行期模块（main.py 被 hidden-import 后其静态依赖会被递归收进 PYZ）
ENGINE_HIDDEN_IMPORTS = [
    "main", "models", "persistence", "security", "log_redaction",
    "services", "services.environment", "services.cookies", "services.downloader",
    "services.ffmpeg_path",  # M16.5：BGV_FFMPEG_PATH 统一解析（merger/downloader/environment 共用）
    "services.failures", "services.formats", "services.merger", "services.redirect",
    "services.sniffer", "services.task_worker",
    "uvicorn", "fastapi", "starlette", "pydantic", "httpx",
    "playwright", "playwright.sync_api", "playwright.async_api",
    "yt_dlp",
    # M22：AES-128 加密 HLS 的原生解密库。yt_dlp 里是
    # `try: from Cryptodome.Cipher import AES / except ImportError: Cryptodome = None`
    # 这种「可选导入」，静态分析抓不稳；漏收的后果不是报错，而是**静默降级**：
    # HlsFD 把下载委托给 ffmpeg，进度恒 0、取消失效。所以显式列进 hidden-import。
    "Cryptodome", "Cryptodome.Cipher", "Cryptodome.Cipher.AES",
]

# 不进 exe 的开发期依赖 / 无关重型库，压体积
EXCLUDES = [
    "pytest", "_pytest", "setuptools", "pip",
    "matplotlib", "numpy", "scipy", "PyQt5", "PySide2", "PySide6",
]

# 随包分发的引擎运行期源码：entry.py 用它作为 sys.path 兜底，并作为子进程 cwd
ENGINE_SRC_FILES = ("main.py", "models.py", "persistence.py",
                    "security.py", "log_redaction.py")


def python_for_build() -> str:
    """PyInstaller 必须跑在装齐依赖的解释器里，否则分析不到 fastapi / playwright。"""
    venv_py = ENGINE / ".venv" / "Scripts" / "python.exe"
    if venv_py.is_file():
        return str(venv_py)
    return sys.executable


def make_icon() -> Path:
    """从 launcher.shield_image 生成多尺寸 .ico（16→256，含透明通道）。"""
    if str(DESKTOP) not in sys.path:
        sys.path.insert(0, str(DESKTOP))
    from launcher import SHIELD_BLUE, shield_image

    image = shield_image(SHIELD_BLUE, 256)
    image.save(ICON_PATH, format="ICO",
               sizes=[(16, 16), (24, 24), (32, 32), (48, 48),
                      (64, 64), (128, 128), (256, 256)])
    print(f"[build] 盾牌图标已生成：{ICON_PATH} ({ICON_PATH.stat().st_size} B)", flush=True)
    return ICON_PATH


def build_command(py: str) -> list[str]:
    sep = os.pathsep  # Windows 为 ';'
    cmd = [
        py, "-m", "PyInstaller",
        "--noconfirm", "--clean",
        "--name", EXE_NAME,
        "--onefile",
        "--noconsole",
        "--icon", str(ICON_PATH),
        "--distpath", str(DIST_DIR),
        "--workpath", str(BUILD_DIR),
        "--specpath", str(DESKTOP),
        "--paths", str(ENGINE),
        "--paths", str(DESKTOP),
    ]

    for name in GUI_HIDDEN_IMPORTS + ENGINE_HIDDEN_IMPORTS:
        cmd += ["--hidden-import", name]
    for name in EXCLUDES:
        cmd += ["--exclude-module", name]

    # Playwright 的 node 驱动 + JS 包是 data，不是 Python 模块，必须显式收；
    # yt_dlp 的 extractor 运行期动态导入，必须 collect-submodules。
    cmd += ["--collect-all", "playwright"]
    cmd += ["--collect-submodules", "yt_dlp"]
    # M22：AES-128 HLS 解密。漏收不会报错，只会让进度条恒 0、取消失效。
    cmd += ["--collect-submodules", "Cryptodome"]
    cmd += ["--collect-data", "customtkinter"]
    # importlib.metadata.version("playwright") 在 selfcheck 里被实时查询
    cmd += ["--copy-metadata", "playwright", "--copy-metadata", "yt-dlp"]

    for filename in ENGINE_SRC_FILES:
        cmd += ["--add-data", f"{ENGINE / filename}{sep}engine_src"]
    cmd += ["--add-data", f"{ENGINE / 'services'}{sep}engine_src/services"]

    cmd.append(str(ENTRY_SCRIPT))
    return cmd


def main() -> int:
    parser = argparse.ArgumentParser(description="打包 BrowseGuardEngine.exe")
    parser.add_argument("--icon-only", action="store_true", help="只生成盾牌 .ico，不执行打包")
    parser.add_argument("--log", default=str(DESKTOP / "_build.log"), help="构建日志路径")
    args = parser.parse_args()

    make_icon()
    if args.icon_only:
        return 0

    for required in (ENTRY_SCRIPT, ENGINE / "main.py"):
        if not required.is_file():
            print(f"[build] 缺少必需文件：{required}", flush=True)
            return 2

    py = python_for_build()
    try:
        import importlib.util
        if importlib.util.find_spec("PyInstaller") is None:
            raise ImportError("PyInstaller 未安装")
    except ImportError:
        print("[build] 当前解释器没有 PyInstaller，请执行：\n"
              f"  {py} -m pip install pyinstaller", flush=True)
        return 2

    cmd = build_command(py)
    print("[build] 命令：\n  " + " ".join(cmd), flush=True)
    DIST_DIR.mkdir(parents=True, exist_ok=True)
    started = time.time()
    with open(args.log, "w", encoding="utf-8") as logfile:
        logfile.write(" ".join(cmd) + "\n\n")
        logfile.flush()
        proc = subprocess.Popen(cmd, cwd=str(DESKTOP), stdout=logfile,
                                stderr=subprocess.STDOUT)
        code = proc.wait()

    exe = DIST_DIR / f"{EXE_NAME}.exe"
    print(f"[build] PyInstaller exit={code}, 耗时 {time.time() - started:.1f}s", flush=True)
    print(f"[build] 构建日志：{args.log}", flush=True)
    if code != 0 or not exe.is_file():
        print("[build] 打包失败，日志尾部：", flush=True)
        tail = Path(args.log).read_text(encoding="utf-8", errors="replace").splitlines()
        print("\n".join(tail[-40:]), flush=True)
        return code or 1

    size = exe.stat().st_size
    print(f"[build] 产物：{exe}", flush=True)
    print(f"[build] 体积：{size} B = {size / 1024 / 1024:.2f} MiB", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

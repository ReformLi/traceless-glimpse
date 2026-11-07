"""M15.3 · PyInstaller 单文件 exe 的唯一入口（GUI / 引擎子进程 / Playwright 安装器）。

三种身份由命令行标志区分，共用同一个 exe，因此 ``launcher.build_engine_command()``
在 ``sys.frozen`` 下可以直接 ``sys.executable --engine-child`` 再启动一次自己：

===========================  ==========================================================
启动方式                      行为
===========================  ==========================================================
``BrowseGuardEngine.exe``     控制面板 GUI（customtkinter + 后台托盘）
``... --engine-child``        在子进程内 ``uvicorn.run(main:app, host, port)``；
                              stdout/stderr 由父进程 Popen 重定向到 engine.log
``... --playwright-install``  在子进程内调用 ``playwright.__main__.main()``，
                              等效于 ``python -m playwright install chromium``
===========================  ==========================================================

**为什么需要 stdio 重绑**：``--noconsole`` 下 PyInstaller 可能把 ``sys.stdout`` /
``sys.stderr`` 置为 ``None`` 或空写器，而父进程已经把 OS 层的 fd 1/2 指向
``engine.log``。不重绑的话，uvicorn 日志、import 期异常全部丢失，排障时一片空白。
源码模式下也能直接跑 ``python desktop/entry.py --engine-child ...`` 做前置验证。
"""
from __future__ import annotations

import io
import os
import sys
from pathlib import Path

# 单文件 exe 的入口脚本在 _MEIPASS 根目录；源码模式下让它能找到同目录的 launcher.py。
_HERE = Path(__file__).resolve().parent
if str(_HERE) not in sys.path:
    sys.path.insert(0, str(_HERE))

import launcher as L  # noqa: E402


def rebind_stdio() -> None:
    """把 Python 层的标准流绑回 OS 层 fd（父进程 Popen 已重定向到 engine.log）。"""
    for name, fd, mode in (("stdin", 0, "r"), ("stdout", 1, "w"), ("stderr", 2, "w")):
        stream = None
        try:
            os.fstat(fd)
            stream = os.fdopen(fd, mode, encoding="utf-8", errors="replace",
                               buffering=1 if mode == "w" else -1, closefd=False)
        except (OSError, ValueError):
            stream = None
        if stream is None:
            # 无控制台且未重定向时（例如手工双击 exe 跑子命令），退到空设备，
            # 保证任何 print / logging 都不会因 sys.stdout is None 抛异常。
            stream = (io.StringIO() if mode == "r"
                      else open(os.devnull, mode, encoding="utf-8", errors="replace"))
        setattr(sys, name, stream)
    sys.__stdout__ = sys.stdout
    sys.__stderr__ = sys.stderr
    sys.__stdin__ = sys.stdin


def ensure_gui_stdio() -> None:
    """GUI 身份下只保证 ``print`` 不会炸；不占用 fd，也不写 engine.log。"""
    for name, mode in (("stdin", "r"), ("stdout", "w"), ("stderr", "w")):
        stream = getattr(sys, name, None)
        if stream is None or not hasattr(stream, "write" if mode == "w" else "read"):
            setattr(sys, name, (io.StringIO() if mode == "r"
                               else open(os.devnull, mode, encoding="utf-8", errors="replace")))


def engine_source_dir() -> Path:
    """打包版优先用随包分发的 engine_src/；源码版直接用仓库里的 engine/。"""
    if L.FROZEN:
        bundled = L.BUNDLE_ROOT / "engine_src"
        if bundled.is_dir():
            return bundled
    return L.ENGINE_DIR


def _arg_value(argv: list[str], flag: str, default: str) -> str:
    if flag in argv:
        idx = argv.index(flag)
        if idx + 1 < len(argv):
            return argv[idx + 1]
    return default


def run_engine_child(argv: list[str]) -> int:
    """引擎子进程：真实绑定 host/port，并把它们回注给 selfcheck。"""
    rebind_stdio()
    host = _arg_value(argv, "--host", L.HOST)
    try:
        port = int(_arg_value(argv, "--port", str(L.PORT)))
    except ValueError:
        port = L.PORT

    # 父进程 EngineProcess.start() 已通过 env 注入；这里再按 argv 覆盖一次，
    # 保证「命令行绑定的地址」与「/v1/privacy/selfcheck 回显」永远一致。
    os.environ["BGV_LISTEN_HOST"] = host
    os.environ["BGV_LISTEN_PORT"] = str(port)
    os.environ.setdefault("BGV_ENGINE_MODE", "real")
    os.environ.setdefault("PYTHONIOENCODING", "utf-8")

    src = engine_source_dir()
    if str(src) not in sys.path:
        sys.path.insert(0, str(src))
    try:
        os.chdir(str(src))  # security._rejected_roots() 会读 cwd，与源码态语义保持一致
    except OSError as exc:
        print(f"[engine-child] chdir 失败（不影响启动）: {exc!r}", flush=True)

    print(f"[engine-child] bundled engine start host={host} port={port} "
          f"pid={os.getpid()} frozen={L.FROZEN} src={src}", flush=True)

    import uvicorn
    import main as engine_main

    uvicorn.run(engine_main.app, host=host, port=port, log_level="info")
    return 0


def run_playwright_install() -> int:
    """冻结环境下等效 ``python -m playwright install chromium``。"""
    rebind_stdio()
    sys.argv = ["playwright", "install", "chromium"]
    try:
        from playwright.__main__ import main as playwright_main
        playwright_main()
    except SystemExit as exc:
        return int(exc.code or 0)
    return 0


def main() -> int:
    argv = sys.argv[1:]
    if L.ENGINE_CHILD_FLAG in argv:
        return run_engine_child(argv)
    if L.PLAYWRIGHT_INSTALL_FLAG in argv:
        return run_playwright_install()
    ensure_gui_stdio()
    import gui_app
    return gui_app.main()


if __name__ == "__main__":
    raise SystemExit(main())

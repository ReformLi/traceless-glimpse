"""M16.5 · PC 真机端到端验收：删 PATH → 红字 → 自定义 FFmpeg 路径 → 真实合并下载。

一条命令跑完用户验收标准的 1–5 条 + 一键安装内核::

    engine\\.venv\\Scripts\\python.exe desktop\\e2e_m16_5.py

与 ``e2e_m16.py`` 的关键差别：**启动 GUI 之前把 FFmpeg 从 ``PATH`` 里摘掉**。
GUI 与它派生的引擎子进程都会继承这个 PATH，因此引擎里任何残留的
``shutil.which("ffmpeg")`` 都会返回 None；只有 GUI 注入的
``BGV_FFMPEG_PATH`` 能让合并成功。素材生成用绝对路径调用 ffmpeg，不依赖 PATH。

为了让合并这一步**真的**发生，测试源不是上一轮那种 muxed HLS（会记
``post_action_noop`` 跳过合并），而是三变体 master：两条 video-only +
一条 audio-only。yt-dlp 由此给出 video-only / audio-only 格式表，
``_merge_plan`` 判定 ``has_separate_tracks=True`` → ``download_tracks`` →
``merger.merge_streams`` 真调 FFmpeg。
"""
from __future__ import annotations

import io
import json
import os
import shutil
import subprocess
import sys
import threading
import time
import urllib.request
from pathlib import Path
from unittest.mock import patch

DESKTOP = Path(__file__).resolve().parent
REPO = DESKTOP.parent
ENGINE = REPO / "engine"
sys.path.insert(0, str(DESKTOP))
sys.path.insert(0, str(ENGINE))

import launcher as L  # noqa: E402
import gui_app as G  # noqa: E402
from e2e_m16 import grab_window_dc  # noqa: E402
from engine_client import selector_for_format  # noqa: E402
from testsupport.media_server import MediaServer  # noqa: E402

FFMPEG = r"D:\ffmpeg\bin\ffmpeg.exe"
FFPROBE = r"D:\ffmpeg\bin\ffprobe.exe"
PORT = 8734
OUT = DESKTOP / "preview" / "e2e_m16_5_out"
ASSETS = DESKTOP / "preview" / "e2e_assets"
REPORT = DESKTOP / "preview" / "e2e_m16_5_report.txt"
SHOTS = {
    "missing": DESKTOP / "preview" / "m16_5_real_ffmpeg_missing.png",
    "ready": DESKTOP / "preview" / "m16_5_real_ffmpeg_ready.png",
    "formats": DESKTOP / "preview" / "m16_5_real_formats.png",
    "done": DESKTOP / "preview" / "m16_5_real_merged.png",
    "install": DESKTOP / "preview" / "m16_5_real_install.png",
}
E2E_TIMEOUT_SECONDS = 300.0

# 两条 video-only + 一条 audio-only：yt-dlp 会给出带 vcodec/acodec 的分离轨格式表。
MASTER_SEPARATE = (
    "#EXTM3U\n#EXT-X-VERSION:4\n"
    '#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.42E01E"\n'
    "video.m3u8\n"
    '#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=320x240,CODECS="avc1.42E01E"\n'
    "video.m3u8\n"
    '#EXT-X-STREAM-INF:BANDWIDTH=64000,CODECS="mp4a.40.2"\n'
    "audio.m3u8\n"
)


def _run(args: list[str]) -> None:
    proc = subprocess.run(args, capture_output=True, text=True, timeout=180)
    if proc.returncode != 0:
        raise RuntimeError(f"命令失败：{' '.join(args)}\n{(proc.stderr or '')[-800:]}")


def prepare_assets() -> None:
    """PATH 完整时先生成基础素材，再用绝对路径 ffmpeg 造分离轨 TS。"""
    MediaServer(ASSETS).start().stop()  # page.html / video_only.mp4 / audio_only.m4a
    media = ASSETS / "media"
    hls = media / "hls"
    hls.mkdir(parents=True, exist_ok=True)
    _run([FFMPEG, "-y", "-v", "error", "-i", str(media / "video_only.mp4"),
          "-c", "copy", "-f", "mpegts", str(hls / "vseg0.ts")])
    _run([FFMPEG, "-y", "-v", "error", "-i", str(media / "audio_only.m4a"),
          "-c:a", "aac", "-b:a", "64k", "-f", "mpegts", str(hls / "aseg0.ts")])
    for name, seg, repeat in (("video.m3u8", "vseg0.ts", 6),
                              ("audio.m3u8", "aseg0.ts", 6)):
        lines = ["#EXTM3U", "#EXT-X-VERSION:3", "#EXT-X-TARGETDURATION:4",
                 "#EXT-X-MEDIA-SEQUENCE:0", "#EXT-X-PLAYLIST-TYPE:VOD"]
        for _ in range(repeat):
            lines += ["#EXTINF:3.000000,", seg]
        lines.append("#EXT-X-ENDLIST")
        (hls / name).write_text("\n".join(lines) + "\n", encoding="utf-8")
    (hls / "master.m3u8").write_text(MASTER_SEPARATE, encoding="utf-8")


def path_without_ffmpeg() -> str:
    """摘掉所有可能提供 ffmpeg 的 PATH 项，保留系统其余目录。"""
    kept = []
    for entry in os.environ.get("PATH", "").split(os.pathsep):
        if not entry or "ffmpeg" in entry.lower():
            continue
        if (Path(entry) / "ffmpeg.exe").exists() or (Path(entry) / "ffmpeg").exists():
            continue
        kept.append(entry)
    return os.pathsep.join(kept)


def probe_media(path: str) -> tuple[bool, str]:
    """用绝对路径 ffprobe 验产物（脚本自身的 PATH 里已经没有 ffprobe）。"""
    try:
        proc = subprocess.run(
            [FFPROBE, "-v", "error", "-show_entries",
             "stream=codec_type,codec_name,width,height", "-of", "json", path],
            capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired) as exc:
        return False, repr(exc)
    data = json.loads(proc.stdout or "{}")
    streams = data.get("streams", [])
    types = {s.get("codec_type") for s in streams}
    return {"video", "audio"}.issubset(types), json.dumps(streams, ensure_ascii=False)


def probe_selfcheck() -> dict | None:
    try:
        with urllib.request.urlopen(
                f"http://127.0.0.1:{PORT}/v1/privacy/selfcheck", timeout=6.0) as resp:
            return json.loads(resp.read().decode("utf-8", "replace"))
    except Exception:
        return None


class Runner:
    """Tk 主线程里的 after 状态机；所有断言与 UI 操作都在主线程。"""

    def __init__(self, app: G.ControlPanel, source_url: str, log_offset: int) -> None:
        self.app = app
        self.source_url = source_url
        self.log_offset = log_offset
        self.results: list[tuple[str, bool, str]] = []
        self.samples: list[float] = []
        self.done = False
        self.output_path = ""

    def check(self, name: str, ok: bool, detail: str = "") -> None:
        self.results.append((name, bool(ok), detail))
        print(f"{'PASS' if ok else 'FAIL'} | {name} | {detail}", flush=True)

    def error_visible(self) -> bool:
        label = self.app.console.message_label
        return (label.winfo_manager() == "pack"
                and label.cget("text").startswith("✖"))

    def wait(self, cond, timeout: float, label: str, on_ok, on_sample=None) -> None:
        deadline = time.monotonic() + timeout

        def poll() -> None:
            if on_sample is not None:
                on_sample()
            if cond():
                self.check(label, True)
                on_ok()
            elif time.monotonic() > deadline:
                self.check(label, False, f"等待 {timeout:.0f}s 超时；state="
                                         f"{self.app.engine_state} detail="
                                         f"{self.app.detail_label.cget('text')[:110]}")
                self.finish()
            else:
                self.app.after(200, poll)

        poll()

    # -- ① / ② PATH 移除 + 依赖面板红字 ---------------------------------------
    def begin(self) -> None:
        self.check("① 系统 PATH 已移除 FFmpeg（GUI 与引擎子进程继承）",
                   shutil.which("ffmpeg") is None,
                   f"which('ffmpeg')={shutil.which('ffmpeg')}")
        self.check("① 初始配置为未自定义路径", self.app.cfg["ffmpeg_path"] == "",
                   repr(self.app.cfg["ffmpeg_path"]))
        self.wait(lambda: bool(self.app._dep_info)
                  and not self.app._dep_info.get("ffmpeg_found"), 30,
                  "② 依赖面板探测出 FFmpeg 未找到", self.on_missing)

    def on_missing(self) -> None:
        app = self.app
        dot = str(app.dep_ffmpeg_dot.cget("text_color")).lower()
        text = app.dep_ffmpeg_label.cget("text")
        hint = app.dep_ffmpeg_hint.cget("text")
        self.check("② FFmpeg 状态圆点为红色", "e5534b" in dot, dot)
        self.check("② FFmpeg 状态文案为「未找到」", "未找到" in text, text)
        self.check("② 提示引导用户点「浏览…」指定路径", "浏览" in hint, hint)
        self.check("② Chromium 状态独立显示",
                   "已就绪" in app.dep_chromium_label.cget("text")
                   or "未安装" in app.dep_chromium_label.cget("text"),
                   app.dep_chromium_label.cget("text"))
        self.shoot("missing")
        self.on_browse()

    # -- ③ 浏览选择自定义路径 -------------------------------------------------
    def on_browse(self) -> None:
        # 等价于用户在文件选择器里选中 D:\ffmpeg\bin\ffmpeg.exe
        G.filedialog.askopenfilename = lambda **_kw: FFMPEG
        self.app._browse_ffmpeg()
        saved = G.Config.load(G.CONFIG_PATH)["ffmpeg_path"]
        self.check("③ 自定义绝对路径已写入 config.json",
                   saved.lower().replace("/", "\\") == FFMPEG.lower(), saved)
        self.check("③ 运行期配置同步更新",
                   self.app.cfg["ffmpeg_path"] == saved, self.app.cfg["ffmpeg_path"])
        self.wait(lambda: self.app._dep_info.get("ffmpeg_source") == "custom"
                  and self.app._dep_info.get("ffmpeg_found"), 30,
                  "③ 选择后依赖面板变为已就绪", self.on_ready)

    def on_ready(self) -> None:
        app = self.app
        dot = str(app.dep_ffmpeg_dot.cget("text_color")).lower()
        text = app.dep_ffmpeg_label.cget("text")
        self.check("③ FFmpeg 状态圆点转绿", "2eb872" in dot, dot)
        self.check("③ 状态显示已就绪且带真实版本号",
                   "已就绪" in text and bool(app._dep_info.get("ffmpeg_version")),
                   f"{text} | version={app._dep_info.get('ffmpeg_version')}")
        self.check("③ 来源标注为自定义路径",
                   "自定义路径" in app.dep_ffmpeg_hint.cget("text"),
                   app.dep_ffmpeg_hint.cget("text"))
        self.shoot("ready")
        app._on_start()
        self.wait(lambda: app.engine_state == G.STATE_RUNNING, 75,
                  "④ GUI 启动 real 引擎并就绪", self.on_running)

    # -- ④ 引擎侧确实读到了注入的路径 -----------------------------------------
    def on_running(self) -> None:
        payload = probe_selfcheck() or {}
        version = (payload.get("dependencyVersions") or {}).get("ffmpeg")
        self.check("④ 引擎 selfcheck 回显 FFmpeg 版本号（证明 BGV_FFMPEG_PATH 生效）",
                   bool(version) and version != "unavailable", str(version))
        self.check("④ 控制台随引擎就绪解锁", self.app.console.is_ready)
        c = self.app.console
        self.app.tabs.set("下载控制台")
        c.clear()
        c.url_entry.insert(0, self.source_url)
        c._on_sniff_click()
        self.wait(lambda: c._has_formats or self.error_visible(), 120,
                  "⑤ 真实网页嗅探返回画质列表", self.on_formats)

    def on_formats(self) -> None:
        c = self.app.console
        if not c._has_formats:
            self.check("⑤ 真实网页嗅探返回画质列表", False,
                       c.message_label.cget("text")[:200])
            self.finish()
            return
        labels = list(c.format_menu.cget("values"))
        self.check("⑤ 下拉框弹出真实画质（含分离音轨）",
                   len(labels) >= 2 and any("360p" in x for x in labels),
                   " / ".join(labels))
        chosen = next((x for x in labels if "360p" in x), labels[0])
        c.format_menu.set(chosen)
        meta = c.selected_format() or {}
        sel = selector_for_format(meta)
        self.check("⑤ 选中视频轨映射为 max_height（未发 formatId）",
                   sel == {"strategy": "max_height", "maxHeight": 360},
                   json.dumps(sel, ensure_ascii=False))
        self.shoot("formats")
        c._on_download_click()
        self.wait(lambda: c._finished and not c._downloading, 200,
                  "⑥ 下载轮询走到终态", self.on_download_done,
                  on_sample=self.sample_progress)

    def sample_progress(self) -> None:
        rows = self.app.console.task_rows
        if rows:
            self.samples.append(rows[0].progress_bar.get())

    # -- ⑥ 真实 FFmpeg 合并产物 ------------------------------------------------
    def on_download_done(self) -> None:
        c = self.app.console
        text = c.message_label.cget("text")
        self.check("⑥ 完成提示含文件位置", "下载完成" in text and "文件位于" in text, text)
        path = text.split("文件位于", 1)[-1].strip()
        self.output_path = path
        exists = bool(path) and Path(path).is_file()
        size = Path(path).stat().st_size if exists else 0
        self.check("⑥ 输出目录中 MP4 真实存在且非空", exists and size > 10_000,
                   f"{path} ({size} B)")
        if exists:
            ok, detail = probe_media(path)
            self.check("⑥ MP4 含视频+音频双轨（真实合并产物）", ok, detail[:220])
        tail = ""
        if L.LOG_PATH.is_file():
            with L.LOG_PATH.open("rb") as fh:
                fh.seek(self.log_offset)
                tail = fh.read().decode("utf-8", "replace")
        self.check("⑥ 引擎日志出现 merge_complete（确实调了 FFmpeg，非 noop）",
                   "merge_complete" in tail
                   and "reason=no_separate_tracks" not in tail,
                   " / ".join(x.strip() for x in tail.splitlines()
                              if "merge" in x.lower())[-220:])
        self.check("⑥ 进度条出现中间态并到 100%",
                   any(0.0 < s < 1.0 for s in self.samples)
                   and any(s >= 0.99 for s in self.samples),
                   f"样本 {len(self.samples)}: "
                   + " → ".join(f"{s*100:.0f}%" for s in self.samples[:10]))
        self.shoot("done")
        self.app.tabs.set("引擎设置")
        self.app._on_stop()
        self.wait(lambda: self.app.engine_state == G.STATE_STOPPED, 30,
                  "⑦ 停止引擎且端口释放", self.on_stopped)

    # -- ⑦ 一键安装内核 -------------------------------------------------------
    def on_stopped(self) -> None:
        app = self.app
        self.check("⑦ 停止后依赖设置恢复可编辑",
                   app.dep_install_button.cget("state") == "normal",
                   str(app.dep_install_button.cget("state")))
        app._dep_log_clear()
        app._on_install_chromium()
        self.check("⑦ 点击安装后按钮进入「安装中…」并锁定",
                   app._installing
                   and app.dep_install_button.cget("state") == "disabled"
                   and "安装中" in app.dep_install_button.cget("text"),
                   app.dep_install_button.cget("text"))
        self.wait(lambda: not app._installing, 200,
                  "⑦ playwright install chromium 执行完成", self.on_installed)

    def on_installed(self) -> None:
        app = self.app
        text = app.dep_log.get("1.0", "end-1c")
        self.check("⑦ 日志区展示安装命令与实时日志",
                   "> playwright install chromium" in text and "首次安装" in text,
                   text.replace("\n", " | ")[:220])
        self.check("⑦ 安装结束写入完成/失败结论",
                   "✓ 安装完成" in text or "✖ 安装失败" in text,
                   text.replace("\n", " | ")[-140:])
        self.check("⑦ 安装后按钮恢复可点",
                   app.dep_install_button.cget("state") == "normal",
                   str(app.dep_install_button.cget("state")))
        # 流式日志：Playwright 用 \r 刷进度条，验证逐行回调不会吞掉中间值。
        class _FakeProc:
            stdout = io.StringIO(
                "Downloading Chromium\r|#####     | 45% of 130 MiB\r"
                "|##########| 100%\nChromium downloaded\n")
            returncode = 0

            def wait(self, timeout=None):
                return 0

            def kill(self):
                pass

        got: list[str] = []
        finished = threading.Event()
        with patch.object(L.subprocess, "Popen", return_value=_FakeProc()):
            L.install_chromium_async(lambda _ok, _detail: finished.set(),
                                     on_line=got.append)
            finished.wait(10)
        self.check("⑦ 安装日志按 \\r 进度条逐行回吐",
                   any("45%" in x for x in got) and any("100%" in x for x in got),
                   " / ".join(got[:5]))
        self.shoot("install")
        self.finish()

    # -- 收尾 -----------------------------------------------------------------
    def shoot(self, tag: str) -> None:
        path = SHOTS[tag]
        self.app.update_idletasks()
        self.app.update()
        image = None
        try:
            image = grab_window_dc(int(self.app.winfo_id()))
        except Exception as exc:
            print(f"[shot] PrintWindow 不可用：{exc!r}", flush=True)
        if image is None:
            try:
                from PIL import ImageGrab
                x, y = self.app.winfo_rootx(), self.app.winfo_rooty()
                image = ImageGrab.grab(bbox=(x, y, x + self.app.winfo_width(),
                                             y + self.app.winfo_height()))
            except Exception as exc:
                print(f"[shot] 截图失败：{exc!r}", flush=True)
                return
        try:
            image.save(path)
            print(f"[shot] {tag} -> {path} {image.size}", flush=True)
        except Exception as exc:
            print(f"[shot] 保存失败：{exc!r}", flush=True)

    def finish(self) -> None:
        if self.done:
            return
        self.done = True
        self.app.after(50, self.app.quit_app)


def main() -> int:
    if not Path(FFMPEG).is_file() or not Path(FFPROBE).is_file():
        print(f"前置失败：本机缺少验收用的 FFmpeg：{FFMPEG} / {FFPROBE}\n"
              "请把脚本顶部的 FFMPEG / FFPROBE 改成本机真实路径。", flush=True)
        return 2
    OUT.mkdir(parents=True, exist_ok=True)
    for old in OUT.glob("*"):
        if old.is_file():
            old.unlink()

    prepare_assets()
    server = MediaServer(ASSETS, generate=False).start()
    source_url = server.base_url + "/page.html"
    os.environ["BGV_SSRF_ALLOWLIST"] = "127.0.0.1"
    os.environ["BGV_DEV_ALLOW_SSRF_BYPASS"] = "1"
    print(f"E2E source_url={source_url}", flush=True)

    original_cfg = (G.CONFIG_PATH.read_text(encoding="utf-8")
                    if G.CONFIG_PATH.is_file() else None)
    cfg = G.Config.load(G.CONFIG_PATH)
    cfg.update(host="127.0.0.1", port=PORT, output_dir=str(OUT),
               auto_start=False, ffmpeg_path="")
    cfg.save()

    original_path = os.environ.get("PATH", "")
    os.environ["PATH"] = path_without_ffmpeg()
    print(f"E2E which('ffmpeg') after PATH strip = {shutil.which('ffmpeg')}", flush=True)
    if shutil.which("ffmpeg") is not None:
        print("前置失败：PATH 里仍有 ffmpeg，无法证明自定义路径生效", flush=True)
        os.environ["PATH"] = original_path
        server.stop()
        if original_cfg is not None:
            G.CONFIG_PATH.write_text(original_cfg, encoding="utf-8")
        return 2

    log_offset = L.LOG_PATH.stat().st_size if L.LOG_PATH.is_file() else 0
    app = G.ControlPanel()
    runner = Runner(app, source_url, log_offset)
    app.after(800, runner.begin)

    def timeout_guard() -> None:
        if not runner.done:
            runner.check("总超时兜底", False, f"{E2E_TIMEOUT_SECONDS:.0f}s 未完成")
            runner.finish()

    app.after(int(E2E_TIMEOUT_SECONDS * 1000), timeout_guard)
    try:
        app.mainloop()
    finally:
        os.environ["PATH"] = original_path
        server.stop()
        if original_cfg is None:
            G.CONFIG_PATH.unlink(missing_ok=True)
        else:
            G.CONFIG_PATH.write_text(original_cfg, encoding="utf-8")

    passed = sum(1 for _, ok, _ in runner.results if ok)
    total = len(runner.results)
    lines = [f"{'PASS' if ok else 'FAIL'} | {name} | {detail}"
             for name, ok, detail in runner.results]
    lines += [
        f"ffmpeg_custom_path={FFMPEG}",
        f"path_ffmpeg_stripped=yes",
        f"source_url={source_url}",
        f"output_dir={OUT}",
        f"output_path={runner.output_path}",
        f"progress_samples={len(runner.samples)}",
    ]
    lines += [f"截图[{tag}]={path}" for tag, path in SHOTS.items()]
    lines += [f"===== e2e-m16.5: {passed}/{total} PASS =====",
              f"E2E_RESULT={passed}/{total}"]
    REPORT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines[-3:]), flush=True)
    print(f"报告：{REPORT}", flush=True)
    return 0 if passed == total and total > 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())

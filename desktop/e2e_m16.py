"""M16 第二步 · PC 真机端到端验收（驱动**真实 GUI 窗口**，非界面预览数据）。

一条命令跑完用户验收标准的 1–5 条 +「中途停止引擎不崩」：

    engine\\.venv\\Scripts\\python.exe desktop\\e2e_m16.py

它在本机做四件事：

1. 用引擎自带的 ``testsupport/media_server`` + 本机 FFmpeg 生成**真实媒体素材**
   （H.264/AAC），并把 HLS master 改写成 240p/360p/480p 三档，让 yt-dlp 真的
   吐出三份带 ``resolution.height`` 的 FormatInfo；切片按块限速，下载会持续
   几秒，才能观察到进度条多次推进与非零速度；
2. 临时改写 ``config.json``（端口 8733 / 输出目录），注入引擎的 dev SSRF
   三门控（本地回环媒体源专用，产品 GUI 不设置），退出时原样恢复；
3. 起 ``ControlPanel()`` 真窗口，用 ``after`` 状态机模拟点击：
   启动引擎 → 粘贴链接 → 开始嗅探 → 选 360p → 开始下载 → 校验进度/产物；
   再测「不支持的链接」红字，以及「下载中途停止引擎」的线程安全退出；
4. 导出浅色截图 + ``desktop/preview/e2e_m16_report.txt`` 报告。

为什么默认用本地媒体服务器而不是公网站点：公网实测 ``w3schools`` 页面能
嗅探到 mp4、但下载被站点鉴权拒绝（``AUTH_REQUIRED``，正好是失败场景的
真实样本）；B 站等 DASH 站点请求的是 ``.m4s``，引擎 sniffer 只认
``.m3u8/.mpd/.mp4/.m4a/.webm``，嗅探不到。本地服务器提供的是**真实 HTTP
页面 + 真实字节 + 真实 Playwright/yt-dlp/FFmpeg 链路**，不是 UI 桩数据，
且可重复、不依赖外网。可用 ``--public-url <URL>`` 改跑公网链接（只验嗅探）。
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

DESKTOP = Path(__file__).resolve().parent
REPO = DESKTOP.parent
ENGINE = REPO / "engine"
sys.path.insert(0, str(DESKTOP))
sys.path.insert(0, str(ENGINE))

import launcher as L  # noqa: E402
import gui_app as G  # noqa: E402
import theme as T  # noqa: E402
from download_console import RED  # noqa: E402
from engine_client import selector_for_format  # noqa: E402
from testsupport.media_server import MediaServer  # noqa: E402

PORT = 8733
OUT = DESKTOP / "preview" / "e2e_out"
ASSETS = DESKTOP / "preview" / "e2e_assets"
REPORT = DESKTOP / "preview" / "e2e_m16_report.txt"
SHOTS = {
    "formats": DESKTOP / "preview" / "m16_step2_real_formats.png",
    "done": DESKTOP / "preview" / "m16_step2_real_done.png",
    "error": DESKTOP / "preview" / "m16_step2_real_error.png",
}
E2E_TIMEOUT_SECONDS = 240.0


def prepare_assets() -> None:
    """生成真实素材，再改写成三档 HLS（这是 e2e 专用的测试夹具，不改 engine/）。"""
    MediaServer(ASSETS).start().stop()  # generate_all() 用 ffmpeg 造 h264+aac
    hls = ASSETS / "media" / "hls"
    seg_lines: list[str] = []
    for _ in range(12):
        # 同一真实 TS 切片重复 + 每块 180ms 限速：下载持续数秒，进度可观测
        seg_lines += ["#EXTINF:3.000000,", "seg0.ts?chunk_delay_ms=180"]
    variant = ("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:3\n"
               "#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n"
               + "\n".join(seg_lines) + "\n#EXT-X-ENDLIST\n")
    lines = ["#EXTM3U", "#EXT-X-VERSION:3"]
    for name, width, height, bw in (("v480.m3u8", 854, 480, 1200000),
                                    ("v360.m3u8", 640, 360, 700000),
                                    ("v240.m3u8", 320, 240, 350000)):
        (hls / name).write_text(variant, encoding="utf-8")
        lines.append(f"#EXT-X-STREAM-INF:BANDWIDTH={bw},RESOLUTION={width}x{height},"
                     'CODECS="avc1.42E01E,mp4a.40.2"')
        lines.append(name)
    (hls / "master.m3u8").write_text("\n".join(lines) + "\n", encoding="utf-8")


def probe_media(path: str) -> tuple[bool, str]:
    """ffprobe 确认产物真的可解码（有视频 + 音频流）。"""
    try:
        proc = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries",
             "stream=codec_type,codec_name,width,height", "-of", "json", path],
            capture_output=True, text=True, timeout=30,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        return False, repr(exc)
    data = json.loads(proc.stdout or "{}")
    types = {s.get("codec_type") for s in data.get("streams", [])}
    ok = {"video", "audio"}.issubset(types)
    return ok, json.dumps(data.get("streams", []), ensure_ascii=False)


def grab_window_dc(hwnd: int):
    """Win32 ``PrintWindow(PW_RENDERFULLCONTENT)`` 直接抓窗口 DC → PIL.Image。

    无人值守环境的 Tk 窗口可能不在 ImageGrab 抓到的屏幕合成层里；窗口 DC 抓取
    不依赖 Z-order，实测可稳定得到完整界面。非 Windows 返回 None，调用方回退。
    """
    if sys.platform != "win32":
        return None
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
    ok = got = 0
    buf = None
    try:
        ok = user32.PrintWindow(hwnd, memdc, 2)
        bi = _BMI()
        bi.biSize = ctypes.sizeof(_BMI)
        bi.biWidth, bi.biHeight = w, -h  # 负值 = top-down，PIL 不用再翻转
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


class Runner:
    """Tk 主线程里的 after 状态机；所有断言和 UI 操作都在主线程。"""

    def __init__(self, app: G.ControlPanel, source_url: str, bad_url: str,
                 public: bool, log_offset: int) -> None:
        self.app = app
        self.source_url = source_url
        self.bad_url = bad_url
        self.public = public
        self.log_offset = log_offset
        self.results: list[tuple[str, bool, str]] = []
        self.samples: list[tuple[float, str, str]] = []
        self.done = False
        self.output_path = ""

    def check(self, name: str, ok: bool, detail: str = "") -> None:
        self.results.append((name, bool(ok), detail))
        print(f"{'PASS' if ok else 'FAIL'} | {name} | {detail}", flush=True)

    def error_visible(self) -> bool:
        """只有**当前显示着**的红字才算错误；隐藏标签里的残留文本不算。"""
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
                self.check(label, False, f"等待 {timeout:.0f}s 超时；"
                                         f"state={self.app.engine_state} "
                                         f"detail={self.app.detail_label.cget('text')[:100]}")
                self.finish()
            else:
                self.app.after(200, poll)

        poll()

    def begin(self) -> None:
        self.app.tabs.set("下载控制台")
        self.app.update_idletasks()
        self.app._on_start()
        self.wait(lambda: self.app.engine_state == G.STATE_RUNNING, 60,
                  "① GUI 启动 real 引擎并就绪", self.on_running)

    # -- 阶段 2：嗅探 + 真实画质 ------------------------------------------------
    def on_running(self) -> None:
        c = self.app.console
        self.check("控制台随引擎就绪解锁", c.is_ready
                   and c.sniff_button.cget("state") == "normal")
        c.clear()
        c.url_entry.insert(0, self.source_url)
        c._on_sniff_click()  # 等价于用户点「开始嗅探」
        self.check("点击嗅探后进入锁定态", c._sniffing
                   and c.sniff_button.cget("state") == "disabled")
        self.wait(lambda: c._has_formats or self.error_visible(),
                  120, "② 真实网页嗅探返回画质列表", self.on_formats)

    def on_formats(self) -> None:
        c = self.app.console
        if not c._has_formats:
            self.check("② 真实网页嗅探返回画质列表", False,
                       c.message_label.cget("text"))
            self.finish()
            return
        labels = list(c.format_menu.cget("values"))
        self.check("下拉框弹出真实多档画质",
                   len(labels) >= 3 and any("480p" in x for x in labels)
                   and any("360p" in x for x in labels)
                   and any("240p" in x for x in labels),
                   " / ".join(labels))
        self.check("画质标签含 h264（codec string 已归一化）",
                   any("h264" in x for x in labels), labels[0] if labels else "")
        chosen = next((x for x in labels if "360p" in x), labels[0])
        c.format_menu.set(chosen)
        meta = c.selected_format()
        sel = selector_for_format(meta or {})
        self.check("360p 选项绑定 height/hasVideo/hasAudio",
                   meta is not None and meta["height"] == 360 and meta["hasVideo"],
                   json.dumps(meta, ensure_ascii=False))
        self.check("画质映射为 max_height 而非 exact/formatId",
                   sel == {"strategy": "max_height", "maxHeight": 360},
                   json.dumps(sel, ensure_ascii=False))
        self.shoot("formats")
        if self.public:
            self.check("公网模式：仅验嗅探（下载依赖站点鉴权，跳过）", True)
            self.finish()
            return
        c._on_download_click()  # 等价于用户点「开始下载」
        self.check("点击下载后新增任务行且锁定按钮",
                   len(c.task_rows) == 1 and c._downloading,
                   self.app.detail_label.cget("text")[:120])
        self.wait(lambda: c._finished and not c._downloading, 180,
                  "③ 下载轮询走到终态", self.on_download_done,
                  on_sample=self.sample_progress)

    def sample_progress(self) -> None:
        rows = self.app.console.task_rows
        if rows:
            row = rows[0]
            self.samples.append((row.progress_bar.get(),
                                 row.speed_label.cget("text"),
                                 row.eta_label.cget("text")))

    # -- 阶段 3：进度 / 产物 ---------------------------------------------------
    def on_download_done(self) -> None:
        c = self.app.console
        text = c.message_label.cget("text")
        self.check("④ 完成提示显示文件位置", "下载完成" in text and "文件位于" in text,
                   text)
        self.check("进度条真实推进（观察到 0<x<1 的中间态）",
                   any(0.0 < s[0] < 1.0 for s in self.samples)
                   and any(s[0] >= 0.99 for s in self.samples),
                   f"样本 {len(self.samples)} 个: "
                   + " → ".join(f"{s[0]*100:.0f}%" for s in self.samples[:10]))
        speed_samples = [s[1] for s in self.samples if "—" not in s[1]]
        eta_samples = [s[2] for s in self.samples if s[2].strip()]
        self.check("速度标签显示真实非零速度",
                   any(("KB/s" in x or "MB/s" in x) for x in speed_samples),
                   " / ".join(speed_samples[:6]))
        self.check("剩余时间标签出现过",
                   any("剩余" in x for x in eta_samples),
                   " / ".join(eta_samples[:6]))
        path = text.split("文件位于", 1)[-1].strip()
        self.output_path = path
        exists = bool(path) and Path(path).is_file()
        size = Path(path).stat().st_size if exists else 0
        self.check("输出目录中文件真实存在且非空", exists and size > 100_000,
                   f"{path} ({size} B)")
        if exists:
            ok, detail = probe_media(path)
            self.check("产物可播放（ffprobe 含视频+音频流，自动合并完成）",
                       ok, detail[:220])
        self.shoot("done")
        self.on_failure_case()

    # -- 阶段 4：失败场景 ------------------------------------------------------
    def on_failure_case(self) -> None:
        c = self.app.console
        c.clear()
        c.url_entry.insert(0, self.bad_url)
        c._on_sniff_click()
        self.wait(self.error_visible, 90,
                  "⑤ 不支持的链接显示红色错误提示", self.on_error)

    def on_error(self) -> None:
        c = self.app.console
        text = c.message_label.cget("text")
        color = str(c.message_label.cget("text_color"))
        self.check("⑤ 错误文案含协议错误码且为红字",
                   "✖" in text and ("SSRF_BLOCKED" in text or "NO_MEDIA_FOUND" in text
                                    or "INVALID_URL" in text)
                   # 颜色取 download_console.RED（主题常量），主题换色不假失败
                   and T.resolve(RED).lower() in color.lower(),
                   f"{text} | color={color}")
        self.shoot("error")
        self.on_stop_midway()

    # -- 阶段 5：下载中途停止引擎 ----------------------------------------------
    def on_stop_midway(self) -> None:
        c = self.app.console
        c.clear()
        c.url_entry.insert(0, self.source_url)
        c._on_sniff_click()
        self.wait(lambda: c._has_formats or self.error_visible(),
                  120, "中途停止：重新嗅探成功", self.on_resniffed)

    def on_resniffed(self) -> None:
        c = self.app.console
        if not c._has_formats:
            self.check("中途停止：重新嗅探成功", False, c.message_label.cget("text"))
            self.finish()
            return
        labels = list(c.format_menu.cget("values"))
        c.format_menu.set(next((x for x in labels if "480p" in x), labels[0]))
        c._on_download_click()
        self.check("中途停止：下载已进入轮询", c._downloading
                   and any(t.is_alive() for t in self.app._net_threads))
        # 1.2 秒后（下载仍在进行）点「停止引擎」
        self.log_offset = L.LOG_PATH.stat().st_size if L.LOG_PATH.is_file() else 0
        self.app.after(1200, self.do_stop)

    def do_stop(self) -> None:
        cancels_before = self.app._net_cancels
        self.app._on_stop()
        self.wait(lambda: self.app.engine_state == G.STATE_STOPPED, 30,
                  "中途停止：引擎已停止且端口释放",
                  lambda: self.on_stopped(cancels_before))

    def on_stopped(self, cancels_before: int) -> None:
        app = self.app
        time.sleep(1.2)  # 让后台线程有机会退出；此期间不跑 Tk 回调（无 UI 操作）
        alive = [t.name for t in app._net_threads if t.is_alive()]
        self.check("中途停止：轮询线程已全部安全退出",
                   not alive and app._net_cancels > cancels_before,
                   f"alive={alive} cancels={cancels_before}->{app._net_cancels}")
        tail = ""
        if L.LOG_PATH.is_file():
            with L.LOG_PATH.open("rb") as fh:
                fh.seek(self.log_offset)
                tail = fh.read().decode("utf-8", "replace")
        self.check("中途停止：无跨线程/已销毁控件的异常",
                   "UI 回调异常" not in tail and "TclError" not in tail
                   and "invalid command name" not in tail,
                   f"新增日志 {len(tail)} B")
        self.check("停止后控制台重新置灰",
                   not app.console.is_ready
                   and app.console.sniff_button.cget("state") == "disabled")
        self.check("停止后解除下载/嗅探锁定并清掉失效画质",
                   not app.console._downloading and not app.console._sniffing
                   and not app.console._has_formats
                   and app.console.current_task_id is None)
        self.app.after(200, self.restart_engine)

    def restart_engine(self) -> None:
        self.app._on_start()
        self.wait(lambda: self.app.engine_state == G.STATE_RUNNING, 60,
                  "停止后可从 GUI 再次启动引擎", self.on_restarted)

    def on_restarted(self) -> None:
        c = self.app.console
        self.check("重启后控制台恢复可用（按钮可点、输入框可编辑）",
                   c.is_ready and c.sniff_button.cget("state") == "normal"
                   and c.url_entry.cget("state") == "normal",
                   f"ready={c.is_ready} sniff={c.sniff_button.cget('state')} "
                   f"entry={c.url_entry.cget('state')}")
        self.finish()

    # -- 收尾 -----------------------------------------------------------------
    def shoot(self, tag: str = "formats") -> None:
        """优先用 Win32 PrintWindow 直接抓窗口 DC，不受遮挡 / Z-order 影响。

        无人值守跑 GUI 时，Tk 窗口虽然 ``wm state=normal / viewable=1``，但
        ``ImageGrab`` 抓整个屏幕可能只拿到桌面壁纸（会话合成差异）。PrintWindow
        从窗口自身的 DC 取像素，实测稳定；非 Windows 或失败时回退 ImageGrab。
        """
        path = SHOTS.get(tag, SHOTS["formats"])
        self.app.update_idletasks()
        self.app.update()
        image = None
        try:
            image = grab_window_dc(int(self.app.winfo_id()))
        except Exception as exc:
            print(f"[shot] PrintWindow 不可用，回退屏幕抓取：{exc!r}", flush=True)
        if image is None:
            try:
                from PIL import ImageGrab
                x, y = self.app.winfo_rootx(), self.app.winfo_rooty()
                w, h = self.app.winfo_width(), self.app.winfo_height()
                image = ImageGrab.grab(bbox=(x, y, x + w, y + h))
            except Exception as exc:
                print(f"[shot] 截图失败（不影响验收）：{exc!r}", flush=True)
                return
        try:
            image.save(path)
            print(f"[shot] {tag} -> {path} {image.size}", flush=True)
        except Exception as exc:
            print(f"[shot] 保存截图失败（不影响验收）：{exc!r}", flush=True)

    def finish(self) -> None:
        if self.done:
            return
        self.done = True
        self.app.after(50, self.app.quit_app)


def main() -> int:
    parser = argparse.ArgumentParser(description="M16 桌面端下载闭环 PC 真机验收")
    parser.add_argument("--public-url", default="",
                        help="改用公网真实网页链接（只验嗅探，下载依赖站点鉴权）")
    args = parser.parse_args()

    OUT.mkdir(parents=True, exist_ok=True)
    for old in OUT.glob("*"):
        if old.is_file():
            old.unlink()

    # 回环媒体源需要引擎 dev 三门控；公网模式不注入。
    public = bool(args.public_url.strip())
    server = None
    if public:
        source_url = args.public_url.strip()
        bad_url = "https://localhost/unsupported"
    else:
        prepare_assets()
        server = MediaServer(ASSETS, generate=False).start()
        source_url = server.base_url + "/page.html"
        bad_url = "https://localhost/unsupported"  # → 400 SSRF_BLOCKED 真实错误体
        os.environ["BGV_SSRF_ALLOWLIST"] = "127.0.0.1"
        os.environ["BGV_DEV_ALLOW_SSRF_BYPASS"] = "1"
    print(f"E2E source_url={source_url}", flush=True)

    # 临时改配置，退出时原样恢复（不污染用户真实的 config.json）
    original = G.CONFIG_PATH.read_text(encoding="utf-8") if G.CONFIG_PATH.is_file() else None
    cfg = G.Config.load(G.CONFIG_PATH)
    cfg.update(host="127.0.0.1", port=PORT, output_dir=str(OUT), auto_start=False)
    cfg.save()

    log_offset = L.LOG_PATH.stat().st_size if L.LOG_PATH.is_file() else 0
    app = G.ControlPanel()
    runner = Runner(app, source_url, bad_url, public, log_offset)
    app.after(700, runner.begin)

    def timeout_guard() -> None:
        if not runner.done:
            runner.check("总超时兜底", False, f"{E2E_TIMEOUT_SECONDS:.0f}s 未完成")
            runner.finish()

    app.after(int(E2E_TIMEOUT_SECONDS * 1000), timeout_guard)
    try:
        app.mainloop()
    finally:
        if server is not None:
            server.stop()
        if original is None:
            try:
                G.CONFIG_PATH.unlink()
            except OSError:
                pass
        else:
            G.CONFIG_PATH.write_text(original, encoding="utf-8")

    passed = sum(1 for _, ok, _ in runner.results if ok)
    total = len(runner.results)
    lines = [f"{'PASS' if ok else 'FAIL'} | {name} | {detail}"
             for name, ok, detail in runner.results]
    lines += [
        f"source_url={source_url}",
        f"output_dir={OUT}",
        f"output_path={runner.output_path}",
        f"progress_samples={len(runner.samples)}",
    ]
    lines += [f"截图[{tag}]={path}" for tag, path in SHOTS.items()]
    lines += [
        f"===== e2e-m16: {passed}/{total} PASS =====",
        f"E2E_RESULT={passed}/{total}",
    ]
    REPORT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines[-3:]), flush=True)
    print(f"报告：{REPORT}", flush=True)
    for tag, path in SHOTS.items():
        print(f"截图[{tag}]：{path}", flush=True)
    return 0 if passed == total and total > 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())

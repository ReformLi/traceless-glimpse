"""真实嗅探服务：Playwright 隔离上下文（阶段 2）。

设计要点：
- **一个专用线程独占 Playwright**（sync API 有线程亲和性），HTTP 线程只提交任务，
  避免跨线程使用 greenlet 崩溃；
- **每任务一个 `browser.new_context()`**，结束后 `context.close()`，任务之间不共享
  Cookie / 缓存 / storage_state；
- 审计行只有 `sniff_context_open` / `sniff_context_close` + `task_id`，
  **不带 URL、Cookie、User-Agent**（用户硬约束 ⑥）；
- playwright 在 worker 线程内惰性 import：stub 模式未装 playwright 也能启动。
"""
from __future__ import annotations

import logging
import queue
import threading
from concurrent.futures import Future
from dataclasses import dataclass
from urllib.parse import urlsplit

from security import sanitize_headers

logger = logging.getLogger("bgv.engine")

_SNIFF_TIMEOUT_SECONDS = 30.0  # 单次嗅探的硬等待上限（页面超时另设）


class SniffError(Exception):
    """嗅探无法执行（playwright 缺失 / worker 崩溃等）。"""


@dataclass(frozen=True)
class SniffedMedia:
    """嗅探到的一条媒体流（已脱敏的 headersHint）。"""

    url: str
    kind: str  # "hls" | "dash" | "mp4" | "audio" | "other"
    container: str
    headers_hint: dict[str, str]


def _classify(url: str) -> tuple[str, str] | None:
    """按路径后缀归类媒体流；.mpd → dash，.m3u8 → hls。"""
    path = urlsplit(url).path.lower()
    if path.endswith(".m3u8"):
        return "hls", "m3u8"
    if path.endswith(".mpd"):
        return "dash", "mpd"
    if path.endswith(".mp4"):
        return "mp4", "mp4"
    if path.endswith(".m4a") or path.endswith(".aac"):
        return "audio", "m4a"
    if path.endswith(".webm"):
        return "other", "webm"
    return None


def _prefer(candidates: list[SniffedMedia]) -> SniffedMedia | None:
    """优先返回播放列表（hls/dash），其次第一个单文件流。"""
    for item in candidates:
        if item.kind in {"hls", "dash"}:
            return item
    return candidates[0] if candidates else None


class _SniffWorker(threading.Thread):
    """独占 Playwright 的常驻线程（进程内单例由 Sniffer 持有）。"""

    def __init__(self, timeout_ms: int, settle_ms: int) -> None:
        super().__init__(name="bgv-sniffer", daemon=True)
        self._jobs: queue.Queue = queue.Queue()
        self._timeout_ms = timeout_ms
        self._settle_ms = settle_ms
        self._launch_error: BaseException | None = None

    # ------------------------------------------------------------------ 提交
    def submit(self, task_id: str, source_url: str) -> Future:
        future: Future = Future()
        self._jobs.put((task_id, source_url, future))
        return future

    def shutdown(self) -> None:
        self._jobs.put(None)

    # ------------------------------------------------------------------ 线程体
    def run(self) -> None:
        try:
            from playwright.sync_api import sync_playwright
        except Exception as exc:  # playwright 未安装 / 导入失败
            self._launch_error = exc
            self._drain_with_error(exc)
            return

        with sync_playwright() as pw:
            browser = pw.chromium.launch(headless=True)
            try:
                while True:
                    job = self._jobs.get()
                    if job is None:
                        break
                    task_id, source_url, future = job
                    try:
                        future.set_result(self._sniff(browser, task_id, source_url))
                    except BaseException as exc:  # noqa: BLE001 —— 透传给调用方
                        future.set_exception(exc)
            finally:
                browser.close()

    def _drain_with_error(self, exc: BaseException) -> None:
        while True:
            job = self._jobs.get()
            if job is None:
                return
            job[2].set_exception(SniffError(f"playwright 不可用：{type(exc).__name__}"))

    # ------------------------------------------------------------------ 单次嗅探
    def _sniff(self, browser, task_id: str, source_url: str) -> SniffedMedia | None:
        from playwright.sync_api import TimeoutError as PlaywrightTimeoutError

        candidates: list[SniffedMedia] = []

        def _on_request(request) -> None:
            info = _classify(request.url)
            if info is None:
                return
            kind, container = info
            try:
                headers = dict(request.headers)
            except Exception:  # noqa: BLE001 —— 头不可读时给空
                headers = {}
            candidates.append(
                SniffedMedia(
                    url=request.url,
                    kind=kind,
                    container=container,
                    headers_hint=sanitize_headers(headers),
                )
            )

        context = browser.new_context()
        logger.info("sniff_context_open task_id=%s", task_id)
        try:
            page = context.new_page()
            page.on("request", _on_request)
            try:
                page.goto(source_url, wait_until="domcontentloaded", timeout=self._timeout_ms)
            except PlaywrightTimeoutError:
                pass  # 超时仍接受已观察到的请求（由 _prefer 决定是否可用）
            # 给页面内的二次请求（fetch / 播放器加载）留出观察窗口
            page.wait_for_timeout(self._settle_ms)
            return _prefer(candidates)
        finally:
            context.close()
            logger.info("sniff_context_close task_id=%s", task_id)


class Sniffer:
    """嗅探服务门面：一次构造一个 worker 线程，可复用于多次任务。"""

    def __init__(self, timeout_ms: int = 8000, settle_ms: int = 800) -> None:
        self._worker = _SniffWorker(timeout_ms=timeout_ms, settle_ms=settle_ms)
        self._start_lock = threading.Lock()
        self._started = False

    def sniff(self, task_id: str, source_url: str) -> SniffedMedia | None:
        with self._start_lock:
            if not self._started:
                self._worker.start()
                self._started = True
        future = self._worker.submit(task_id, source_url)
        try:
            return future.result(timeout=_SNIFF_TIMEOUT_SECONDS)
        except SniffError:
            raise
        except Exception as exc:  # noqa: BLE001 —— 超时 / worker 异常
            raise SniffError(f"{type(exc).__name__}: {exc}") from exc

    def shutdown(self) -> None:
        if self._started:
            self._worker.shutdown()
            self._worker.join(timeout=10)
            self._started = False


_default_sniffer: Sniffer | None = None
_default_lock = threading.Lock()


def get_sniffer() -> Sniffer:
    """进程级单例（复用 Playwright 浏览器进程）。"""
    global _default_sniffer
    with _default_lock:
        if _default_sniffer is None:
            _default_sniffer = Sniffer()
        return _default_sniffer

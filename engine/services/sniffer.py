"""真实嗅探服务：Playwright 隔离上下文（阶段 2；M18 反反爬 + 雷达增强）。

设计要点：
- **一个专用线程独占 Playwright**（sync API 有线程亲和性），HTTP 线程只提交任务，
  避免跨线程使用 greenlet 崩溃；
- **每任务一个 `browser.new_context()`**，结束后 `context.close()`，任务之间不共享
  Cookie / 缓存 / storage_state；
- **M18 无头伪装**：真实 Chrome UA + 桌面视口 + zh-CN locale + Asia/Shanghai 时区，
  `add_init_script` 抹掉 `navigator.webdriver` 等自动化痕迹；`BGV_SNIFF_HEADED=1`
  时改用「有头但窗口扔到屏幕外」的形态再搏一次（`--window-position=-32000,-32000`，
  不在用户眼前弹窗）；
- **M18 雷达增强**：媒体识别从「URL 后缀」扩到「URL 后缀 ∪ Response Content-Type ∪
  URL 关键字」三层；`wait_until=domcontentloaded` 拿到响应对象后追加 `networkidle`
  等待与触底滚动（懒加载）；`BGV_SNIFF_DEBUG=1` 把**全部**请求/响应（URL +
  Content-Type）打进日志供联调取证；
- 审计行只有 `sniff_context_open` / `sniff_context_close` + `task_id`，
  **不带 URL、Cookie、User-Agent**（用户硬约束 ⑥）；`sniff_debug` 行带 URL，
  只在调试环境变量显式开启时输出；
- playwright 在 worker 线程内惰性 import：stub 模式未装 playwright 也能启动。
"""
from __future__ import annotations

import logging
import os
import queue
import threading
from concurrent.futures import Future
from dataclasses import dataclass
from urllib.parse import urlsplit

from security import sanitize_headers
from services import cookies as cookies_service

logger = logging.getLogger("bgv.engine")

_SNIFF_TIMEOUT_SECONDS = 30.0  # 单次嗅探的硬等待上限（页面超时另设）

# ---------------------------------------------------------------- M18 环境开关
_ENV_SNIFF_DEBUG = "BGV_SNIFF_DEBUG"  # =1 时全量打印请求/响应（URL + Content-Type）
_ENV_SNIFF_HEADED = "BGV_SNIFF_HEADED"  # =1 时改用「有头 + 窗口在屏幕外」形态

# 真实桌面 Chrome UA：Playwright 默认 UA 带 `HeadlessChrome` 字样，是最常见的死穴
_DESKTOP_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)
_STEALTH_VIEWPORT = {"width": 1920, "height": 1080}

# 有头形态：窗口扔到屏幕外（不弹到用户眼前），仍走真实渲染管线
_STEALTH_LAUNCH_ARGS = ["--window-position=-32000,-32000", "--window-size=1920,1080"]

# 抹自动化痕迹：webdriver 置空 + chrome 对象补形 + permissions 查询与通知权限一致
# （headless 里 query 返回 prompt 而 Notification.permission 是 default，这个矛盾
# 常被检测脚本拿来做指纹）+ plugins/languages 形状补齐
_STEALTH_INIT_SCRIPT = """
Object.defineProperty(navigator, 'webdriver', {get: () => undefined});
window.chrome = window.chrome || { runtime: {} };
Object.defineProperty(navigator, 'languages', {get: () => ['zh-CN', 'zh', 'en']});
Object.defineProperty(navigator, 'plugins', {get: () => [1, 2, 3, 4, 5]});
const __origQuery = window.navigator.permissions.query;
window.navigator.permissions.query = (parameters) => (
    parameters && parameters.name === 'notifications'
        ? Promise.resolve({ state: Notification.permission })
        : __origQuery(parameters)
);
"""

# ------------------------------------------------------- M18 雷达：三层识别
# 层 1：URL 路径后缀。rank 越小越优先被 _prefer 选中。
#       m3u8/mpd（播放列表）0 → ts/m4s（分片）1 → mp4/flv（直链视频）2 →
#       webm 3 → 纯音频 4 → octet-stream（层 2）5 → 关键字（层 3）6。
_SUFFIX_TABLE: tuple[tuple[str, str, str], ...] = (
    (".m3u8", "hls", "m3u8"),
    (".mpd", "dash", "mpd"),
    (".mp4", "mp4", "mp4"),
    (".flv", "other", "flv"),
    (".ts", "hls", "ts"),
    (".m4s", "dash", "m4s"),
    (".m4a", "audio", "m4a"),
    (".aac", "audio", "aac"),
    (".mp3", "audio", "mp3"),
    (".webm", "other", "webm"),
)
# rank 由 _CONTAINER_RANK 统一给出（层 2/层 3 的候选也走同一张表）

# 层 2：Response Content-Type。后缀识别不到时（裸接口、无扩展名直链、动态分发）
# 由响应头兜底。m3u8 有四个常见别名；octet-stream 覆盖「视频流但 URL 无特征」。
_CT_ALIASES: dict[str, tuple[str, str]] = {
    "application/vnd.apple.mpegurl": ("hls", "m3u8"),
    "application/x-mpegurl": ("hls", "m3u8"),
    "audio/mpegurl": ("hls", "m3u8"),
    "audio/x-mpegurl": ("hls", "m3u8"),
    "application/dash+xml": ("dash", "mpd"),
}
_CT_VIDEO_SUBTYPES: dict[str, str] = {
    "mp4": "mp4", "mp2t": "ts", "webm": "webm", "x-flv": "flv",
    "x-matroska": "mkv", "quicktime": "mov", "mpeg": "mpeg",
}
_CT_AUDIO_SUBTYPES: dict[str, str] = {
    "mp4": "m4a", "mpeg": "mp3", "aac": "aac", "ogg": "ogg", "wav": "wav",
}

# 层 3：URL 关键字（playlist / master / stream 等）。只做**最后兜底**候选：
# 误报率天然高（任何带 stream 的接口都会命中），所以 rank 最大，且主文档导航
# （resource_type=document/iframe）不参与——否则打开页面本身就是「候选」。
_KEYWORDS: tuple[str, ...] = ("playlist", "master", "stream", "manifest", ".m3u8", ".mpd")
_KEYWORD_EXCLUDED_RESOURCE_TYPES = frozenset({"document", "iframe"})

_CONTAINER_RANK: dict[str, int] = {
    "m3u8": 0, "mpd": 0, "ts": 1, "m4s": 1, "mp4": 2, "flv": 2, "webm": 3,
    "m4a": 4, "aac": 4, "mp3": 4, "ogg": 4, "wav": 4,
    "octet-stream": 5, "ct-video": 5, "ct-audio": 5,
    "keyword": 6,
}


class SniffError(Exception):
    """嗅探无法执行（playwright 缺失 / worker 崩溃等）。"""


@dataclass(frozen=True)
class SniffedMedia:
    """嗅探到的一条媒体流（已脱敏的 headersHint）。"""

    url: str
    kind: str  # "hls" | "dash" | "mp4" | "audio" | "other"
    container: str
    headers_hint: dict[str, str]


def _sniff_debug_enabled() -> bool:
    return os.getenv(_ENV_SNIFF_DEBUG, "").strip() == "1"


def _classify(url: str) -> tuple[str, str] | None:
    """按 URL 路径后缀归类媒体流；.mpd → dash，.m3u8 → hls，.ts/.m4s 为分片。"""
    path = urlsplit(url).path.lower()
    for suffix, kind, container in _SUFFIX_TABLE:
        if path.endswith(suffix):
            return kind, container
    return None


def _classify_content_type(content_type: str) -> tuple[str, str] | None:
    """按响应 Content-Type 归类（URL 无媒体后缀时的兜底信号）。"""
    ctype = content_type.split(";", 1)[0].strip().lower()
    if not ctype:
        return None
    if ctype in _CT_ALIASES:
        return _CT_ALIASES[ctype]
    main, _, subtype = ctype.partition("/")
    if main == "video":
        return "mp4", _CT_VIDEO_SUBTYPES.get(subtype, "ct-video")
    if main == "audio":
        return "audio", _CT_AUDIO_SUBTYPES.get(subtype, "ct-audio")
    if ctype == "application/octet-stream":
        return "other", "octet-stream"
    return None


def _classify_keyword(url: str, resource_type: str) -> tuple[str, str] | None:
    """URL 关键字兜底（层 3）：主文档导航与 iframe 不参与。"""
    if resource_type in _KEYWORD_EXCLUDED_RESOURCE_TYPES:
        return None
    lowered = url.lower()
    for keyword in _KEYWORDS:
        if keyword in lowered:
            return "other", "keyword"
    return None


def _rank(kind: str, container: str) -> int:
    return _CONTAINER_RANK.get(container, 9)


def _record(
    candidates: dict[str, tuple[SniffedMedia, int]],
    url: str,
    kind: str,
    container: str,
    headers: dict[str, str],
) -> None:
    """登记候选流；同一 URL 只留一条，rank 更小（更可信的信号）允许覆盖。"""
    rank = _rank(kind, container)
    existing = candidates.get(url)
    if existing is not None:
        if existing[1] <= rank:
            return  # 已有同等或更可信的信号
        headers = dict(existing[0].headers_hint)  # 升级时保留首见 headers
    candidates[url] = (
        SniffedMedia(
            url=url, kind=kind, container=container,
            headers_hint=sanitize_headers(headers),
        ),
        rank,
    )


def _prefer(candidates: dict[str, SniffedMedia]) -> SniffedMedia | None:
    """按三层 rank 取最优：播放列表 > 分片 > 直链视频 > 音频 > octet-stream > 关键字。

    同 rank 保持先观察到者优先（dict 保序）。
    """
    if not candidates:
        return None
    return min(
        candidates.values(),
        key=lambda item: (_rank(item.kind, item.container), ),
    )


class _SniffWorker(threading.Thread):
    """独占 Playwright 的常驻线程（进程内单例由 Sniffer 持有）。"""

    def __init__(self, timeout_ms: int, settle_ms: int, networkidle_ms: int = 4000) -> None:
        super().__init__(name="bgv-sniffer", daemon=True)
        self._jobs: queue.Queue = queue.Queue()
        self._timeout_ms = timeout_ms
        self._settle_ms = settle_ms
        self._networkidle_ms = networkidle_ms
        self._launch_error: BaseException | None = None

    # ------------------------------------------------------------------ 提交
    def submit(self, task_id: str, source_url: str, cookie_policy: str = "none") -> Future:
        future: Future = Future()
        self._jobs.put((task_id, source_url, cookie_policy, future))
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
            headed = os.getenv(_ENV_SNIFF_HEADED, "").strip() == "1"
            if headed:
                # 有头但窗口在屏幕外：真实渲染管线，反爬指纹最弱，也不打扰用户
                browser = pw.chromium.launch(headless=False, args=list(_STEALTH_LAUNCH_ARGS))
            else:
                browser = pw.chromium.launch(headless=True)
            try:
                while True:
                    job = self._jobs.get()
                    if job is None:
                        break
                    task_id, source_url, cookie_policy, future = job
                    try:
                        future.set_result(
                            self._sniff(browser, task_id, source_url, cookie_policy)
                        )
                    except BaseException as exc:  # noqa: BLE001 —— 透传给调用方
                        future.set_exception(exc)
            finally:
                browser.close()

    def _drain_with_error(self, exc: BaseException) -> None:
        while True:
            job = self._jobs.get()
            if job is None:
                return
            job[3].set_exception(SniffError(f"playwright 不可用：{type(exc).__name__}"))

    # ------------------------------------------------------------------ 单次嗅探
    def _sniff(
        self, browser, task_id: str, source_url: str, cookie_policy: str = "none"
    ) -> SniffedMedia | None:
        # url -> (SniffedMedia, rank)：同一 URL 只记一条；rank 更小（更可信的信号）
        # 允许覆盖——层 3 关键字先命中、层 2 Content-Type 随后给出真身时要升级
        candidates: dict[str, tuple[SniffedMedia, int]] = {}
        rejected: list[str] = []
        debug = _sniff_debug_enabled()

        def _dbg(event: str, url: str, extra: str = "") -> None:
            if debug:
                # 调试行带完整 URL：仅 BGV_SNIFF_DEBUG=1 显式开启时输出，
                # 不属于 sniff_context 审计行，不受硬约束 ⑥ 管辖
                logger.info("sniff_debug %s %s %s", event, url, extra)

        def _on_request(request) -> None:
            _dbg("request", request.url, f"resource={request.resource_type}")
            headers: dict[str, str] = {}
            try:
                headers = dict(request.headers)
            except Exception:  # noqa: BLE001 —— 头不可读时给空
                pass
            info = _classify(request.url)
            if info is not None:
                _record(candidates, request.url, info[0], info[1], headers)
                return
            info = _classify_keyword(request.url, request.resource_type)
            if info is not None:
                _record(candidates, request.url, info[0], info[1], headers)

        def _on_response(response) -> None:
            try:
                ctype = response.headers.get("content-type", "")
            except Exception:  # noqa: BLE001
                ctype = ""
            _dbg(
                "response", response.url,
                f"status={response.status} content-type={ctype}",
            )
            headers: dict[str, str] = {}
            try:
                headers = dict(response.request.headers)
            except Exception:  # noqa: BLE001
                pass
            info = _classify(response.url) or _classify_content_type(ctype)
            if info is not None:
                _record(candidates, response.url, info[0], info[1], headers)
            elif response.request.resource_type not in _KEYWORD_EXCLUDED_RESOURCE_TYPES:
                keyword = _classify_keyword(response.url, response.request.resource_type)
                if keyword is not None:
                    _record(candidates, response.url, keyword[0], keyword[1], headers)
            if response.status in {401, 403} and info is not None:
                rejected.append(response.url)

        context = browser.new_context(
            user_agent=_DESKTOP_UA,
            viewport=dict(_STEALTH_VIEWPORT),
            locale="zh-CN",
            timezone_id="Asia/Shanghai",
        )
        context.add_init_script(_STEALTH_INIT_SCRIPT)
        logger.info("sniff_context_open task_id=%s", task_id)
        try:
            page = context.new_page()
            page.on("request", _on_request)
            page.on("response", _on_response)
            if cookie_policy == "explicit":
                self._load_env_cookies(context, task_id)
            response = self._goto(page, source_url)
            retried = False
            if cookie_policy == "lazy" and self._nav_rejected(response):
                retried = self._retry_with_cookies(context, task_id)
                if retried:
                    candidates.clear()
                    response = self._goto(page, source_url)
            # networkidle：等「网络安静下来」——懒加载站点的主请求都在这里冒头；
            # 长轮询 / 心跳页面永远到不了 idle，超时就当已可观察，不视为失败
            self._await_network_idle(page)
            # 触底滚动一次：懒加载视频（滚动到可视区才起播/拉流）在这里被触发
            self._scroll_to_bottom(page)
            # 给页面内的二次请求（fetch / 播放器加载）留出观察窗口
            page.wait_for_timeout(self._settle_ms)
            if cookie_policy == "lazy" and not retried and rejected:
                # 页面本身 200，但媒体请求被拒：加 Cookie 后整页重载一次
                retried = self._retry_with_cookies(context, task_id)
                if retried:
                    candidates.clear()
                    rejected.clear()
                    self._goto(page, source_url)
                    self._await_network_idle(page)
                    self._scroll_to_bottom(page)
                    page.wait_for_timeout(self._settle_ms)
            ordered = {url: media for url, (media, _) in candidates.items()}
            return _prefer(ordered)
        finally:
            context.close()
            logger.info("sniff_context_close task_id=%s", task_id)

    def _goto(self, page, url: str):
        from playwright.sync_api import TimeoutError as PlaywrightTimeoutError

        # 先 domcontentloaded：尽早拿到导航响应对象（惰性 Cookie 判 401/403 依赖它），
        # networkidle 由 _await_network_idle 在此之后追加等待
        try:
            return page.goto(url, wait_until="domcontentloaded", timeout=self._timeout_ms)
        except PlaywrightTimeoutError:
            return None  # 超时仍接受已观察到的请求

    def _await_network_idle(self, page) -> None:
        from playwright.sync_api import TimeoutError as PlaywrightTimeoutError

        try:
            page.wait_for_load_state("networkidle", timeout=self._networkidle_ms)
        except PlaywrightTimeoutError:
            pass  # 长轮询/心跳页面到不了 idle；已观察到的请求照常采用
        except Exception as exc:  # noqa: BLE001 —— 页面已导航走/关闭等，不致命
            logger.debug("sniff_networkidle_skipped type=%s", type(exc).__name__)

    def _scroll_to_bottom(self, page) -> None:
        try:
            page.evaluate("window.scrollTo(0, document.body.scrollHeight)")
        except Exception as exc:  # noqa: BLE001 —— 滚动失败不改变嗅探结果
            logger.debug("sniff_scroll_skipped type=%s", type(exc).__name__)

    @staticmethod
    def _nav_rejected(response) -> bool:
        return getattr(response, "status", None) in {401, 403}

    def _load_env_cookies(self, context, task_id: str) -> bool:
        cookies = cookies_service.load_env_cookies()
        if not cookies:
            logger.info("cookie_file_unavailable task_id=%s", task_id)
            return False
        context.add_cookies(cookies_service.playwright_cookies(cookies))
        logger.info("cookie_file_loaded task_id=%s count=%d", task_id, len(cookies))
        return True

    def _retry_with_cookies(self, context, task_id: str) -> bool:
        if not self._load_env_cookies(context, task_id):
            return False
        logger.info("cookie_lazy_retry task_id=%s", task_id)
        return True


class Sniffer:
    """嗅探服务门面：一次构造一个 worker 线程，可复用于多次任务。"""

    def __init__(
        self,
        timeout_ms: int = 8000,
        settle_ms: int = 800,
        networkidle_ms: int = 4000,
    ) -> None:
        self._worker = _SniffWorker(
            timeout_ms=timeout_ms, settle_ms=settle_ms, networkidle_ms=networkidle_ms
        )
        self._start_lock = threading.Lock()
        self._started = False

    def sniff(
        self, task_id: str, source_url: str, cookie_policy: str = "none"
    ) -> SniffedMedia | None:
        with self._start_lock:
            if not self._started:
                self._worker.start()
                self._started = True
        future = self._worker.submit(task_id, source_url, cookie_policy)
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

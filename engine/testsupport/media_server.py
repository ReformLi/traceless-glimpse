"""本地测试媒体服务器（仅测试使用，生产路径禁止 import）。

只监听回环地址，覆盖 M1 需要的全部场景：
- ``GET /media/*``         支持 Range（206）+ ``Accept-Ranges: bytes``；
  任意路由都可用 ``?delay_ms=N`` 让响应头之前先 sleep N 毫秒（真机取消取证用，默认 0）；
  文件体另可用 ``?chunk_delay_ms=N`` 在每写 64 KiB 之后 sleep N 毫秒——
  ``delay_ms`` 只拖头部、字节流仍然瞬间跑完，断流取证必须按块限速（默认 0）；
  任意路由还可用 ``?no_range=1`` 关掉 Range 支持（忽略 Range、回 200 且不带
  ``Accept-Ranges``）——**路径不变所以产物名不变**，已存在的同名 ``.part`` 会被命中，
  用来在同一个 URL 上复现「想续传但服务器不让」（4b-4 降级取证）
- ``GET /media/*``  还可用 ``?as_html=1`` 把 ``Content-Type`` 改写成 ``text/html``
  （字节与路径都不变）——同名 ``.part`` 照样命得中，用来造
  「残留还在、但终点改口声明非媒体」的续传门禁现场（4b-4 裁决①）
- ``GET /page-bulk.html``      只有一条单文件直链（``/media/bulk.mp4``）的嗅探页：
  ``?chunk_delay=N`` 会把该 query 拼进视频 URL，使嗅探到的直链本身带限速
- ``GET /media/no-range.mp4`` 忽略 Range，返回 200 且无 ``Accept-Ranges``（续传降级用例）
- ``GET /media/audio_as_mp4.mp4`` 纯音频字节 + ``Content-Type: audio/mp4`` 但 URL 后缀是
  ``.mp4``（封装/扩展名校正用例：产物必须落成 ``.m4a``，不能是 ``.mp4``）
- ``GET /page-audio.html`` 只有一条伪装直链（``/media/audio_as_mp4.mp4``）的嗅探页
- ``GET /media/hls/master.m3u8`` / ``segN.ts``  HLS 播放列表与切片
- ``GET /media/drm/widevine.m3u8``  Widevine KEYFORMAT 假 manifest
- ``GET /media/drm/aes128.m3u8``    仅 AES-128（非 DRM，三态判定用）
- ``GET /media/evil.exe``          非白名单扩展名样本
- ``GET /r302-local``              302 → ``/media/sample.mp4``（合法跳转）
- ``GET /r302-private``            302 → ``http://127.0.0.1:9/private``
- ``GET /r302-private10``          302 → ``http://10.0.0.1/private``
- ``GET /r302-metadata``           302 → ``http://169.254.169.254/latest/meta-data/``
- ``GET /whoami``                  回显 Cookie / Referer / UA / Range（隔离性验收）
- ``GET /page.html``               嗅探页（请求 m3u8 并写入 sid Cookie）
- ``GET /page-nocookie.html``      嗅探页（请求同一个 m3u8，但**不写任何 Cookie**，隔离性取证）
- ``GET /page-cookie.html``        登录态嗅探页：响应对端写 ``sid=good-cookie``，再请求受保护直链
  （WebView 嗅探 → CookieProvider 注入 → 本地下载的闭环取证）
- ``GET /protected.html``          无 ``sid=good-cookie`` 时 403，有则返回嗅探页（惰性 Cookie）
- ``GET /media/protected.mp4``     无 ``sid=good-cookie`` 时 403，有则返回 sample.mp4

注意：服务器本身在回环，初始 URL 就会被 SSRF 校验器拦截，因此
「合法起点 → 私网终点」的重定向只能靠 mock transport 的单测覆盖；
本服务器只提供 302 的**目标**证据。

命令行模式（``python -m testsupport.media_server``）额外把每个请求打到 stdout，
用于真机联调取证。只打 ``cookie_present`` 布尔，不打 Cookie 原值：原值属于凭据，
进任何日志都会违反验收标准 5；需要看原值时只在本地临时改这一行，不要提交。
"""
from __future__ import annotations

import argparse
import json
import mimetypes
import re
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlsplit

_MAX_DELAY_SECONDS = 60.0
# 每块之间的停顿上限：够慢到真机点得下取消，又不会踩到下载器的 socket 超时
_MAX_CHUNK_DELAY_SECONDS = 5.0
_BODY_CHUNK_BYTES = 65536

_CONTENT_TYPES = {
    ".m3u8": "application/vnd.apple.mpegurl",
    ".ts": "video/mp2t",
    ".mp4": "video/mp4",
    ".m4a": "audio/mp4",
    ".exe": "application/octet-stream",
}

_RANGE_RE = re.compile(r"^bytes=(\d+)-(\d*)$")


def _client_class(user_agent: str) -> str:
    """按 UA 归类请求来源：区分 Playwright 浏览器与「伪装 Chrome UA 的库」。"""
    lowered = user_agent.lower()
    if "headlesschrome" in lowered or "playwright" in lowered:
        return "playwright"
    if "yt-dlp" in lowered or "youtube-dl" in lowered:
        return "ytdlp"
    if "python-httpx" in lowered:
        return "httpx"
    if "python-urllib" in lowered:
        return "urllib"
    if "chrome" in lowered or "safari" in lowered:
        return "browser"
    return "other"


def _request_delay_seconds(query: str) -> float:
    """``?delay_ms=N``：响应头之前 sleep N 毫秒，上限 60s；非法值按 0 处理。"""
    raw = (parse_qs(query).get("delay_ms") or [""])[0].strip()
    if not raw.isdigit():
        return 0.0
    return min(int(raw) / 1000.0, _MAX_DELAY_SECONDS)


def _chunk_delay_seconds(query: str) -> float:
    """``?chunk_delay_ms=N``：每写一块（64 KiB）之后 sleep N 毫秒。

    ``delay_ms`` 只拖响应头，字节流本身仍然瞬间跑完；4b-3 要的是**字节流**
    中途可被切断，所以单独一个按块节流的旋钮。
    """
    raw = (parse_qs(query).get("chunk_delay_ms") or [""])[0].strip()
    if not raw.isdigit():
        return 0.0
    return min(int(raw) / 1000.0, _MAX_CHUNK_DELAY_SECONDS)


def _no_range(query: str) -> bool:
    """``?no_range=1``：本轮请求假装服务器不支持 Range。

    不新建路由而是挂 query，是为了保持**路径不变**——产物名由 URL 后缀决定，
    路径一致才能命中上一轮取消留下的同名 ``.part``，真正走到「有续传意图但
    服务器回 200」的降级分支。
    """
    raw = (parse_qs(query).get("no_range") or [""])[0].strip().lower()
    return raw in {"1", "true", "yes"}


def _forced_content_type(query: str) -> str | None:
    """``?as_html=1``：路径与字节都不变，只把 ``Content-Type`` 换成 ``text/html``。

    和 ``no_range`` 同一个道理——挂 query 才能保住路径，让同名 ``.part`` 残留
    命得中，从而造出「残留还在、但终点已经改口声明非媒体」这道门禁的真实 HTTP 现场。
    """
    raw = (parse_qs(query).get("as_html") or [""])[0].strip().lower()
    return "text/html; charset=utf-8" if raw in {"1", "true", "yes"} else None


class _Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "BGVMockMedia/0.1"

    def do_GET(self) -> None:  # noqa: N802（http.server 接口名）
        self._handle(head_only=False)

    def do_HEAD(self) -> None:  # noqa: N802
        self._handle(head_only=True)

    def log_message(self, format: str, *args) -> None:  # noqa: A002
        """静默访问日志：测试输出不混入服务器日志。"""

    @property
    def assets(self) -> Path:
        return self.server.assets  # type: ignore[attr-defined]

    # ------------------------------------------------------------------ 路由
    def _handle(self, head_only: bool) -> None:
        parts = urlsplit(self.path)
        path = parts.path
        delay_seconds = _request_delay_seconds(parts.query)
        chunk_delay_seconds = _chunk_delay_seconds(parts.query)
        # 请求记录（隔离性证据）：服务器进程内可见，供测试断言 Cookie 是否串任务
        cookie_header = self.headers.get("Cookie", "")
        self.server.request_log.append(  # type: ignore[attr-defined]
            {
                "path": path,
                "cookie": cookie_header,
                "referer": self.headers.get("Referer", ""),
                "userAgent": self.headers.get("User-Agent", ""),
                "range": self.headers.get("Range", ""),
                # 降级取证要核对「这个 200 是服务器忽略了 Range，不是客户端没问」，
                # 所以把 no_range 旋钮也记进结构化日志
                "no_range": _no_range(parts.query),
            }
        )
        if getattr(self.server, "verbose", False):
            ua = self.headers.get("User-Agent", "")
            # range 打原值：续传取证要核对「起始偏移 == .part 磁盘大小」，布尔不够用；
            # Range 是普通请求头，不属于凭据，可以进日志
            print(
                "media_req t={} path={} client={} cookie_present={} range={} "
                "delay_ms={} chunk_delay_ms={} no_range={} ua={}".format(
                    time.strftime("%H:%M:%S")
                    + ".%03d" % int((time.time() % 1) * 1000),
                    path,
                    _client_class(ua),
                    "true" if cookie_header else "false",
                    (self.headers.get("Range") or "-").replace(" ", "_"),
                    int(delay_seconds * 1000),
                    int(chunk_delay_seconds * 1000),
                    "true" if _no_range(parts.query) else "false",
                    ua.replace(" ", "_")[:48],
                ),
                flush=True,
            )
        if delay_seconds:
            time.sleep(delay_seconds)
        if path == "/":
            routes = [
                "/media/sample.mp4", "/media/no-range.mp4", "/media/video_only.mp4",
                "/media/audio_only.m4a", "/media/audio_as_mp4.mp4", "/media/hls/master.m3u8",
                "/media/drm/widevine.m3u8", "/media/drm/aes128.m3u8",
                "/media/evil.exe", "/media/bulk.mp4", "/media/hls/slow.m3u8",
                "/page.html", "/page-bulk.html", "/page-audio.html", "/page-nocookie.html",
                "/page-cookie.html",
                "/whoami",
                "/protected.html", "/media/protected.mp4",
                "/r302-local", "/r302-private", "/r302-private10", "/r302-metadata",
            ]
            self._send_text(200, "BGV mock media server\n" + "\n".join(routes) + "\n", head_only)
        elif path == "/whoami":
            payload = {
                "path": path,
                "cookie": self.headers.get("Cookie", ""),
                "referer": self.headers.get("Referer", ""),
                "userAgent": self.headers.get("User-Agent", ""),
                "range": self.headers.get("Range", ""),
            }
            self._send_json(200, payload, head_only)
        elif path == "/r302-local":
            self._redirect("/media/sample.mp4", head_only)
        elif path == "/r302-private":
            self._redirect("http://127.0.0.1:9/private", head_only)
        elif path == "/r302-private10":
            self._redirect("http://10.0.0.1/private", head_only)
        elif path == "/r302-metadata":
            self._redirect("http://169.254.169.254/latest/meta-data/", head_only)
        elif path == "/page.html":
            self._serve_file(self.assets / "page.html", allow_range=False, head_only=head_only)
        elif path == "/page-bulk.html":
            self._serve_file(self.assets / "page-bulk.html", allow_range=False, head_only=head_only)
        elif path == "/page-audio.html":
            self._serve_file(self.assets / "page-audio.html", allow_range=False, head_only=head_only)
        elif path == "/page-nocookie.html":
            self._serve_file(
                self.assets / "page-nocookie.html", allow_range=False, head_only=head_only
            )
        elif path == "/page-cookie.html":
            # 登录态模拟：页面响应直接种 Cookie，WebView 的 CookieStore 与 JS 都能看到。
            self._serve_file(
                self.assets / "page-cookie.html",
                allow_range=False,
                head_only=head_only,
                extra_headers={"Set-Cookie": "sid=good-cookie; Path=/; SameSite=Lax"},
            )
        elif path == "/protected.html":
            if not self._has_access_cookie():
                self._send_text(403, "cookie required", head_only)
            else:
                self._serve_file(self.assets / "page.html", allow_range=False, head_only=head_only)
        elif path == "/media/protected.mp4":
            if not self._has_access_cookie():
                self._send_text(403, "cookie required", head_only)
            else:
                self._serve_file(
                    self.assets / "media" / "sample.mp4",
                    allow_range=True,
                    head_only=head_only,
                )
        elif path == "/media/no-range.mp4":
            # 续传降级用例：显式不支持 Range
            self._serve_file(self.assets / "media" / "sample.mp4", allow_range=False, head_only=head_only)
        elif path == "/media/audio_as_mp4.mp4":
            # 封装错位用例：字节是纯音频（fMP4/aac），URL 后缀却是 .mp4，
            # 服务器按内容如实声明 audio/mp4 —— 产物必须被校正成 .m4a
            self._serve_file(
                self.assets / "media" / "audio_only.m4a",
                allow_range=not _no_range(parts.query),
                head_only=head_only,
                content_type="audio/mp4",
            )
        elif path.startswith("/media/"):
            rel = unquote(path[len("/media/"):])
            if not rel or ".." in rel or rel.startswith("/"):
                self._send_text(404, "not found", head_only)
                return
            self._serve_file(
                self.assets / "media" / rel,
                allow_range=not _no_range(parts.query),
                head_only=head_only,
                content_type=_forced_content_type(parts.query),
            )
        else:
            self._send_text(404, "not found", head_only)

    # ------------------------------------------------------------------ 发送
    def _has_access_cookie(self) -> bool:
        cookie = self.headers.get("Cookie", "")
        return any(part.strip() == "sid=good-cookie" for part in cookie.split(";"))

    def _redirect(self, location: str, head_only: bool) -> None:
        self.send_response(302)
        self.send_header("Location", location)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _send_text(self, status: int, text: str, head_only: bool) -> None:
        self._send_bytes(
            status,
            text.encode("utf-8"),
            {"Content-Type": "text/plain; charset=utf-8"},
            head_only,
        )

    def _send_json(self, status: int, payload: dict, head_only: bool) -> None:
        self._send_bytes(
            status,
            json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            {"Content-Type": "application/json; charset=utf-8"},
            head_only,
        )

    def _send_bytes(self, status: int, body: bytes, headers: dict, head_only: bool) -> None:
        self.send_response(status)
        for name, value in headers.items():
            self.send_header(name, value)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if not head_only and body:
            self.wfile.write(body)

    def _serve_file(
        self,
        file_path: Path,
        allow_range: bool,
        head_only: bool,
        content_type: str | None = None,
        extra_headers: dict[str, str] | None = None,
    ) -> None:
        if not file_path.is_file():
            self._send_text(404, "not found", head_only)
            return
        total = file_path.stat().st_size
        suffix = file_path.suffix.lower()
        content_type = (
            content_type
            or _CONTENT_TYPES.get(suffix)
            or mimetypes.guess_type(str(file_path))[0]
            or "application/octet-stream"
        )

        status = 200
        start, end = 0, max(total - 1, 0)
        extra: dict[str, str] = {}
        if allow_range:
            extra["Accept-Ranges"] = "bytes"
            match = _RANGE_RE.match(self.headers.get("Range", "").strip())
            if match and total > 0:
                start = int(match.group(1))
                end = int(match.group(2)) if match.group(2) else total - 1
                end = min(end, total - 1)
                if start > end or start >= total:
                    self._send_bytes(416, b"", {"Content-Range": f"bytes */{total}"}, head_only)
                    return
                status = 206
                extra["Content-Range"] = f"bytes {start}-{end}/{total}"
                length = end - start + 1
            else:
                length = total
        else:
            length = total

        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(length))
        for name, value in (extra_headers or {}).items():
            self.send_header(name, value)
        for name, value in extra.items():
            self.send_header(name, value)
        self.end_headers()

        if head_only:
            return
        chunk_delay = _chunk_delay_seconds(urlsplit(self.path).query)
        with file_path.open("rb") as handle:
            handle.seek(start)
            remaining = length
            while remaining > 0:
                chunk = handle.read(min(remaining, _BODY_CHUNK_BYTES))
                if not chunk:
                    break
                try:
                    self.wfile.write(chunk)
                except (ConnectionError, BrokenPipeError):
                    # 客户端常在整段发完前断开（播放器预加载、下载被取消）：
                    # Windows 下报 WinError 10053（ConnectionAbortedError），
                    # 属于对端行为，不是服务器故障，静默收尾不刷 traceback
                    return
                remaining -= len(chunk)
                if chunk_delay:
                    time.sleep(chunk_delay)


class MediaServer:
    """本地测试媒体服务器（默认回环 127.0.0.1，端口 0 表示随机可用端口）。"""

    def __init__(
        self,
        assets_dir: Path | str,
        host: str = "127.0.0.1",
        port: int = 0,
        generate: bool = True,
        verbose: bool = False,
        bulk: bool = False,
        segment_delay_ms: int = 1500,
    ) -> None:
        self.assets_dir = Path(assets_dir)
        self.host = host
        self._requested_port = port
        self._generate = generate
        self._verbose = verbose
        # 断流取证素材（bulk.mp4 + 限速 HLS manifest）：默认不生成
        self._bulk = bulk
        self._segment_delay_ms = segment_delay_ms
        self._httpd: ThreadingHTTPServer | None = None
        self._thread: threading.Thread | None = None
        # 收到的请求摘要（path / cookie / referer / userAgent），供隔离性断言
        self.request_log: list[dict] = []

    def reset_log(self) -> None:
        self.request_log.clear()

    def start(self) -> "MediaServer":
        if self._generate:
            from .media_fixtures import generate_all

            generate_all(
                self.assets_dir, bulk=self._bulk, segment_delay_ms=self._segment_delay_ms
            )
        httpd = ThreadingHTTPServer((self.host, self._requested_port), _Handler)
        httpd.daemon_threads = True
        httpd.assets = self.assets_dir  # type: ignore[attr-defined]
        httpd.request_log = self.request_log  # type: ignore[attr-defined]
        httpd.verbose = self._verbose  # type: ignore[attr-defined]
        self._httpd = httpd
        self._thread = threading.Thread(target=httpd.serve_forever, daemon=True)
        self._thread.start()
        return self

    def stop(self) -> None:
        if self._httpd is not None:
            self._httpd.shutdown()
            self._httpd.server_close()
            self._httpd = None
        if self._thread is not None:
            self._thread.join(timeout=3)
            self._thread = None

    @property
    def port(self) -> int:
        assert self._httpd is not None, "服务器尚未启动"
        return int(self._httpd.server_address[1])

    @property
    def base_url(self) -> str:
        return f"http://{self.host}:{self.port}"

    def __enter__(self) -> "MediaServer":
        return self.start()

    def __exit__(self, exc_type, exc, tb) -> None:
        self.stop()


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="BGV 本地测试媒体服务器（仅测试）")
    parser.add_argument("--assets", default=None, help="素材目录；缺省用临时目录")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8731)
    parser.add_argument("--no-generate", action="store_true", help="跳过素材生成（素材已存在时）")
    parser.add_argument(
        "--bulk",
        action="store_true",
        help="额外生成断流取证素材：bulk.mp4（约 4 MiB）+ 限速 slow.m3u8",
    )
    parser.add_argument(
        "--segment-delay-ms",
        type=int,
        default=1500,
        help="slow.m3u8 每片每块的 sleep 毫秒数（配合 --bulk）",
    )
    parser.add_argument("--quiet", action="store_true", help="不打印请求行（默认打印）")
    args = parser.parse_args(argv)

    assets = Path(args.assets) if args.assets else Path(tempfile.mkdtemp(prefix="bgv-media-"))
    server = MediaServer(
        assets,
        host=args.host,
        port=args.port,
        generate=not args.no_generate,
        verbose=not args.quiet,
        bulk=args.bulk,
        segment_delay_ms=args.segment_delay_ms,
    ).start()
    print(f"assets: {assets}")
    print(f"baseUrl: {server.base_url}")
    try:
        threading.Event().wait()
    except KeyboardInterrupt:
        pass
    finally:
        server.stop()


if __name__ == "__main__":
    main()

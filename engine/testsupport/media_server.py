"""本地测试媒体服务器（仅测试使用，生产路径禁止 import）。

只监听回环地址，覆盖 M1 需要的全部场景：
- ``GET /media/*``         支持 Range（206）+ ``Accept-Ranges: bytes``
- ``GET /media/no-range.mp4`` 忽略 Range，返回 200 且无 ``Accept-Ranges``（续传降级用例）
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

注意：服务器本身在回环，初始 URL 就会被 SSRF 校验器拦截，因此
「合法起点 → 私网终点」的重定向只能靠 mock transport 的单测覆盖；
本服务器只提供 302 的**目标**证据。
"""
from __future__ import annotations

import argparse
import json
import mimetypes
import re
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlsplit

_CONTENT_TYPES = {
    ".m3u8": "application/vnd.apple.mpegurl",
    ".ts": "video/mp2t",
    ".mp4": "video/mp4",
    ".m4a": "audio/mp4",
    ".exe": "application/octet-stream",
}

_RANGE_RE = re.compile(r"^bytes=(\d+)-(\d*)$")


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
        path = urlsplit(self.path).path
        # 请求记录（隔离性证据）：服务器进程内可见，供测试断言 Cookie 是否串任务
        self.server.request_log.append(  # type: ignore[attr-defined]
            {
                "path": path,
                "cookie": self.headers.get("Cookie", ""),
                "referer": self.headers.get("Referer", ""),
                "userAgent": self.headers.get("User-Agent", ""),
            }
        )
        if path == "/":
            routes = [
                "/media/sample.mp4", "/media/no-range.mp4", "/media/video_only.mp4",
                "/media/audio_only.m4a", "/media/hls/master.m3u8",
                "/media/drm/widevine.m3u8", "/media/drm/aes128.m3u8",
                "/media/evil.exe", "/page.html", "/whoami",
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
        elif path == "/media/no-range.mp4":
            # 续传降级用例：显式不支持 Range
            self._serve_file(self.assets / "media" / "sample.mp4", allow_range=False, head_only=head_only)
        elif path.startswith("/media/"):
            rel = unquote(path[len("/media/"):])
            if not rel or ".." in rel or rel.startswith("/"):
                self._send_text(404, "not found", head_only)
                return
            self._serve_file(self.assets / "media" / rel, allow_range=True, head_only=head_only)
        else:
            self._send_text(404, "not found", head_only)

    # ------------------------------------------------------------------ 发送
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

    def _serve_file(self, file_path: Path, allow_range: bool, head_only: bool) -> None:
        if not file_path.is_file():
            self._send_text(404, "not found", head_only)
            return
        total = file_path.stat().st_size
        suffix = file_path.suffix.lower()
        content_type = _CONTENT_TYPES.get(suffix) or mimetypes.guess_type(str(file_path))[0] or "application/octet-stream"

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
        for name, value in extra.items():
            self.send_header(name, value)
        self.end_headers()

        if head_only:
            return
        with file_path.open("rb") as handle:
            handle.seek(start)
            remaining = length
            while remaining > 0:
                chunk = handle.read(min(remaining, 65536))
                if not chunk:
                    break
                self.wfile.write(chunk)
                remaining -= len(chunk)


class MediaServer:
    """本地测试媒体服务器（默认回环 127.0.0.1，端口 0 表示随机可用端口）。"""

    def __init__(
        self,
        assets_dir: Path | str,
        host: str = "127.0.0.1",
        port: int = 0,
        generate: bool = True,
    ) -> None:
        self.assets_dir = Path(assets_dir)
        self.host = host
        self._requested_port = port
        self._generate = generate
        self._httpd: ThreadingHTTPServer | None = None
        self._thread: threading.Thread | None = None
        # 收到的请求摘要（path / cookie / referer / userAgent），供隔离性断言
        self.request_log: list[dict] = []

    def reset_log(self) -> None:
        self.request_log.clear()

    def start(self) -> "MediaServer":
        if self._generate:
            from .media_fixtures import generate_all

            generate_all(self.assets_dir)
        httpd = ThreadingHTTPServer((self.host, self._requested_port), _Handler)
        httpd.daemon_threads = True
        httpd.assets = self.assets_dir  # type: ignore[attr-defined]
        httpd.request_log = self.request_log  # type: ignore[attr-defined]
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
    args = parser.parse_args(argv)

    assets = Path(args.assets) if args.assets else Path(tempfile.mkdtemp(prefix="bgv-media-"))
    server = MediaServer(assets, host=args.host, port=args.port, generate=not args.no_generate).start()
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

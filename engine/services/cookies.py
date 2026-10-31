"""惰性 Cookie（FR-SEC-02）：Netscape cookie 文件解析与按需读取。

语义：
- ``none``：从不读取、从不发送；
- ``lazy``：首次请求不带 Cookie，仅在 401/403 被拒时读取一次配置副本并重试一次；
- ``explicit``：首次请求即发送配置文件的 Cookie（用户在引擎侧显式启用）。

凭据来源只能是引擎侧 ``BGV_COOKIE_FILE``；协议层不存在 cookieFile / browser 字段。
原文件只读，yt-dlp 需要写入 Cookie 时使用临时副本。
"""
from __future__ import annotations

import logging
import os
import tempfile
import time
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator
from urllib.parse import urlsplit

logger = logging.getLogger("bgv.engine")

COOKIE_FILE_ENV = "BGV_COOKIE_FILE"
_HTTPONLY_PREFIX = "#HttpOnly_"


@dataclass(frozen=True)
class Cookie:
    name: str
    value: str
    domain: str
    path: str = "/"
    secure: bool = False
    include_subdomains: bool = False
    expires: int | None = None  # epoch 秒；0 / None 表示会话 Cookie
    http_only: bool = False


def parse_netscape(text: str) -> list[Cookie]:
    """解析 Netscape cookies.txt；畸形行跳过（不把坏行当有效 Cookie）。"""
    cookies: list[Cookie] = []
    for raw_line in text.splitlines():
        line = raw_line.strip("\r")
        if not line:
            continue
        http_only = line.startswith(_HTTPONLY_PREFIX)
        if line.startswith("#") and not http_only:
            continue
        fields = line.split("\t")
        if len(fields) != 7:
            continue
        domain, flag, path, secure, expires, name, value = fields
        if http_only:
            domain = domain[len(_HTTPONLY_PREFIX):]
        try:
            expires_at = int(float(expires))
        except ValueError:
            expires_at = None
        cookies.append(
            Cookie(
                name=name,
                value=value,
                domain=domain,
                path=path or "/",
                secure=secure.upper() == "TRUE",
                include_subdomains=flag.upper() == "TRUE" or domain.startswith("."),
                expires=expires_at,
                http_only=http_only,
            )
        )
    return cookies


def cookie_file_from_env() -> Path | None:
    raw = os.getenv(COOKIE_FILE_ENV, "").strip()
    return Path(raw) if raw else None


def load_cookies(path: str | Path) -> list[Cookie]:
    return parse_netscape(Path(path).read_text(encoding="utf-8"))


def load_env_cookies() -> list[Cookie] | None:
    """读取一次配置的 Cookie 文件；未配置 / 不可读返回 None（不抛路径）。"""
    path = cookie_file_from_env()
    if path is None or not path.is_file():
        return None
    try:
        return load_cookies(path)
    except OSError:
        logger.warning("cookie_file_unreadable")
        return None


def _domain_matches(cookie: Cookie, host: str) -> bool:
    domain = cookie.domain.lower().lstrip(".")
    if not domain:
        return False
    if host == domain:
        return True
    return cookie.include_subdomains and host.endswith("." + domain)


def cookies_for_url(cookies: list[Cookie], url: str, *, now: float | None = None) -> list[Cookie]:
    """按 domain / path / secure / 过期时间筛出适用于该 URL 的 Cookie。"""
    parts = urlsplit(url)
    host = (parts.hostname or "").lower()
    request_path = parts.path or "/"
    scheme = (parts.scheme or "").lower()
    current = time.time() if now is None else now
    selected: list[Cookie] = []
    for cookie in cookies:
        if cookie.secure and scheme != "https":
            continue
        if cookie.expires and cookie.expires <= current:
            continue
        if not _domain_matches(cookie, host):
            continue
        if not request_path.startswith(cookie.path or "/"):
            continue
        selected.append(cookie)
    return selected


def cookie_header(cookies: list[Cookie], url: str, *, now: float | None = None) -> str | None:
    selected = cookies_for_url(cookies, url, now=now)
    if not selected:
        return None
    return "; ".join(f"{cookie.name}={cookie.value}" for cookie in selected)


def header_from_env(url: str) -> str | None:
    """惰性读取一次并返回该 URL 的 Cookie 头；未配置 / 无匹配返回 None。"""
    cookies = load_env_cookies()
    if not cookies:
        return None
    return cookie_header(cookies, url)


def playwright_cookies(cookies: list[Cookie]) -> list[dict]:
    """转成 Playwright ``add_cookies`` 结构；保留 domain 作用域，不跨域发送。"""
    items: list[dict] = []
    for cookie in cookies:
        item = {
            "name": cookie.name,
            "value": cookie.value,
            "domain": cookie.domain.lstrip(".") or cookie.domain,
            "path": cookie.path or "/",
            "secure": cookie.secure,
            "httpOnly": cookie.http_only,
        }
        if cookie.expires:
            item["expires"] = cookie.expires
        items.append(item)
    return items


@contextmanager
def cookie_file_copy(task_id: str) -> Iterator[Path | None]:
    """为 yt-dlp 提供临时副本：原文件只读一次，写回只落在副本上。"""
    path = cookie_file_from_env()
    if path is None or not path.is_file():
        yield None
        return
    try:
        payload = path.read_bytes()
    except OSError:
        logger.warning("cookie_file_unreadable task_id=%s", task_id)
        yield None
        return
    handle = tempfile.NamedTemporaryFile(prefix="bgv-cookie-", suffix=".txt", delete=False)
    try:
        with handle:
            handle.write(payload)
        logger.info("cookie_file_copied task_id=%s", task_id)
        yield Path(handle.name)
    finally:
        Path(handle.name).unlink(missing_ok=True)

"""出站代理（M21）：嗅探 Chromium / 清单抓取 / yt-dlp 下载三段链路共用一套口径。

**只认显式配置 `BGV_PROXY`**，不去嗅探系统代理：系统代理会在用户不知情时变化，
静默改变出站路径比「代理不生效」更难排查。桌面端「引擎设置」里的「网络代理」输入框
就是写这个变量（经引擎子进程 env 注入），留空 = 直连，行为与本功能上线前完全一致。

支持的写法（凭据可带可不带）::

    http://127.0.0.1:7890
    http://user:pass@127.0.0.1:7890
    socks5://127.0.0.1:1080          ← Playwright 支持 socks5；httpx 需额外依赖，见下

httpx 只原生支持 HTTP(S) 代理；socks 需要 `httpx[socks]`。这里不做额外安装，
socks 地址照常透传给 yt-dlp / Playwright（两者原生支持），httpx 段会由调用方
自然抛错——错误会经 `services.failures` 归到 `NETWORK_*`，不会静默吞掉。
"""
from __future__ import annotations

import os
from urllib.parse import unquote, urlsplit

ENV_PROXY = "BGV_PROXY"


def proxy_url() -> str | None:
    """原始代理地址；未配置（空串/纯空白）返回 None，调用方据此走直连分支。"""
    raw = (os.environ.get(ENV_PROXY) or "").strip()
    return raw or None


def playwright_proxy() -> dict[str, str] | None:
    """Playwright `launch(proxy=...)` 的形状：凭据必须拆成独立字段。

    Playwright 不接受把 `user:pass@` 塞在 `server` 里（会整串当主机名），
    所以这里解析后拆开；解析不出主机名时返回 None——宁可直连也不要让整条嗅探
    因为一个写错的代理地址而全军覆没。
    """
    url = proxy_url()
    if url is None:
        return None
    parsed = urlsplit(url if "://" in url else f"http://{url}")
    if not parsed.hostname:
        return None
    server = f"{parsed.scheme or 'http'}://{parsed.hostname}"
    if parsed.port:
        server = f"{server}:{parsed.port}"
    config = {"server": server}
    if parsed.username:
        config["username"] = unquote(parsed.username)
    if parsed.password:
        config["password"] = unquote(parsed.password)
    return config


def httpx_proxy_kwargs(*, transport: object | None = None) -> dict[str, str]:
    """给 `httpx.Client(...)` 的可选关键字。

    httpx 不允许 `proxy` 与 `transport` 同时出现（测试注入 MockTransport 时就是这种情形），
    此时代理让位给 transport——测试要的是确定性，不是真出网。
    """
    if transport is not None:
        return {}
    url = proxy_url()
    return {"proxy": url} if url else {}

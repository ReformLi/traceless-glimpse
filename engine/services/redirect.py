"""重定向逐跳校验（§1.5，阶段 3）。

yt-dlp / httpx 若自行跟随重定向会绕过统一校验器，因此所有出站跳转先由本模块
逐跳解析并校验（`follow_redirects=False`），再把最终 URL 交给下游。
"""
from __future__ import annotations

from urllib.parse import urljoin

import httpx

from security import validate_outbound_url

MAX_REDIRECTS = 5
_REDIRECT_STATUS = {301, 302, 303, 307, 308}


class RedirectLimitError(Exception):
    """重定向次数超过上限（不新增协议错误码，上层视为网络失败）。"""


def resolve_redirects(
    url: str,
    *,
    transport: httpx.BaseTransport | None = None,
    max_hops: int = MAX_REDIRECTS,
    timeout: float = 5.0,
) -> str:
    """返回最终 URL；首跳与每一跳都必须通过 ``validate_outbound_url``。

    第 2 跳指向私网/回环/元数据端点时，在该跳即刻抛 ``SecurityError``，
    绝不允许「先请求再校验」。
    """
    validate_outbound_url(url)
    current = url
    with httpx.Client(follow_redirects=False, timeout=timeout, transport=transport) as client:
        for _ in range(max_hops):
            response = client.get(current, headers={"Range": "bytes=0-0"})
            try:
                location = response.headers.get("location")
                if response.status_code in _REDIRECT_STATUS and location:
                    current = urljoin(current, location)
                    validate_outbound_url(current)  # 逐跳校验点
                    continue
                return current
            finally:
                response.close()
    raise RedirectLimitError(f"重定向超过 {max_hops} 跳")

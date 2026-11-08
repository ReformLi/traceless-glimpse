"""重定向逐跳校验（§1.5，阶段 3）。

yt-dlp / httpx 若自行跟随重定向会绕过统一校验器，因此所有出站跳转先由本模块
逐跳解析并校验（`follow_redirects=False`），再把最终 URL 交给下游。
"""
from __future__ import annotations

import logging
from typing import NamedTuple
from urllib.parse import urljoin

import httpx

from security import validate_outbound_url
from services import proxy as proxy_settings

logger = logging.getLogger("bgv.engine")

MAX_REDIRECTS = 5
_REDIRECT_STATUS = {301, 302, 303, 307, 308}

#: 探测请求最多消费的响应体字节数。
#:
#: ``Range: bytes=0-0`` 只是「问一嘴」：要拿的是最终 URL 与 Content-Type，正文一个
#: 字节都用不上。非流式 ``client.get()`` 会把整个响应体读进内存，服务端忽略 Range
#: 时无界（4b-4 R2 实测 4.2 MB / 13 s），碰上直播 / SSE 这类无限流就是读到 OOM。
#: 这是防御缺口而不是性能问题，所以探测走流式、读满预算立刻停手。
PROBE_MAX_BYTES = 1024


class RedirectTarget(NamedTuple):
    """最终 URL + 终点响应的 ``Content-Type``（探测请求本来就要发，顺带取回）。

    下载器用 content_type 决定产物封装/扩展名（纯音频不能落成 ``.mp4``），
    因此必须在写第一块之前就拿到它。
    """

    url: str
    content_type: str | None


class RedirectLimitError(Exception):
    """重定向次数超过上限（不新增协议错误码，上层视为网络失败）。"""


def _consume_probe_budget(response: httpx.Response, *, task_id: str) -> None:
    """读掉不超过 ``PROBE_MAX_BYTES`` 的原始字节就停手。

    走 ``iter_raw`` 而不是 ``iter_bytes``：后者会解压，一小段压缩流就能撑开很大的
    内存，探测要的是「线上真实字节数」有界。读满预算即 break，``with`` 收尾会关掉
    连接（未读完的响应 httpx 不做连接复用），超出预算的任何字节都不会进内存。

    ``is_stream_consumed`` 为真表示正文早被传输层一次性取走（``MockTransport``
    这类注入件），此时没有「有界读取」可做——真正的出站 HTTPTransport 永远走流式。
    """
    if response.is_stream_consumed:
        return
    seen = 0
    for chunk in response.iter_raw():
        seen += len(chunk)
        if seen >= PROBE_MAX_BYTES:
            logger.info(
                "resume_probe_truncated task_id=%s reason=body_over_probe_budget read_bytes=%d",
                task_id,
                seen,
            )
            break


def resolve_redirects(
    url: str,
    *,
    transport: httpx.BaseTransport | None = None,
    max_hops: int = MAX_REDIRECTS,
    timeout: float = 5.0,
    task_id: str = "unknown",
) -> RedirectTarget:
    """返回最终 URL 与其 Content-Type；首跳与每一跳都必须通过 ``validate_outbound_url``。

    第 2 跳指向私网/回环/元数据端点时，在该跳即刻抛 ``SecurityError``，
    绝不允许「先请求再校验」。

    探测请求是**流式且有界**的：只取响应头，响应体最多读 ``PROBE_MAX_BYTES`` 就断开，
    所以「服务器忽略 Range 回全量」或「终点是无限流」都不会把探测变成一次完整下载。
    ``task_id`` 只用于给 ``resume_probe_truncated`` 审计行挂上任务归属。
    """
    validate_outbound_url(url)
    current = url
    with httpx.Client(
        follow_redirects=False,
        timeout=timeout,
        transport=transport,
        # M21：逐跳探测与嗅探/下载共用代理口径；测试注入 transport 时让位
        **proxy_settings.httpx_proxy_kwargs(transport=transport),
    ) as client:
        for _ in range(max_hops):
            with client.stream(
                "GET", current, headers={"Range": "bytes=0-0"}
            ) as response:
                location = response.headers.get("location")
                if response.status_code in _REDIRECT_STATUS and location:
                    current = urljoin(current, location)
                    validate_outbound_url(current)  # 逐跳校验点
                    continue
                content_type = response.headers.get("content-type")
                _consume_probe_budget(response, task_id=task_id)
                return RedirectTarget(current, content_type)
    raise RedirectLimitError(f"重定向超过 {max_hops} 跳")

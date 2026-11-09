"""M25 · 桌面端 HLS 分段并发：唯一校验入口。

与 ``engine/services/hls_concurrency.py``、Android ``HlsConcurrency`` 同语义：
默认 3、闭区间 1~8、越界拒绝而不是夹取。桌面没有 Android 的审计通道，越界的
表现是「UI 禁用开始下载 + 行内红字」以及 :meth:`engine_client.EngineClient.start_download`
在**发出 HTTP 之前**抛本地错误——不让引擎 422 滞后返回到界面。
"""
from __future__ import annotations

from typing import Any

DEFAULT: int = 3
MIN: int = 1
MAX: int = 8
FIELD: str = "hlsConcurrency"
MESSAGE: str = "HLS 分段并发必须是 1~8 的整数"


def parse(raw: Any) -> int | None:
    """严格解析：非数字 / 越界 / 非整数一律返回 None，由调用方决定拒绝或回落。

    整个桌面端只有这一处判断 1~8；UI 与网络层都调它，不各写一份 if。
    """
    if isinstance(raw, bool) or raw is None:
        return None
    if isinstance(raw, int):
        value = raw
    elif isinstance(raw, str):
        text = raw.strip()
        if not text:
            return None
        # "08" 是合法的 8；"3.5" / "1e1" / "3 4" / "-1" 一律不是整数输入。
        body = text[1:] if text[0] in "+-" else text
        if not body.isdigit():
            return None
        try:
            value = int(text)
        except ValueError:
            return None
    else:
        return None
    return value if MIN <= value <= MAX else None


def from_stored(raw: Any) -> int:
    """旧配置缺失 / 存储损坏 → 回落默认，不崩启动。"""
    value = parse(raw)
    return DEFAULT if value is None else value

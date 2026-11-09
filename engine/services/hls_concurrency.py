"""HLS 分段并发（M25）：全引擎唯一的并发数校验入口。

四个入口、四种越界策略，但**范围判断只有一处**（:func:`_is_valid`）：

===========  ====================================================
入口          越界行为
===========  ====================================================
API 字段       抛 :class:`HlsConcurrencyError` → 端点翻 422
Android 设置   拒绝、保持原值、记审计（Android 侧 ``HlsConcurrency``）
环境变量       记审计 + 回落默认，不阻断启动
缺失 / 损坏    回落默认
===========  ====================================================

优先级 ``请求字段 > 环境变量 > 默认`` 由 :func:`resolve` 一处编排。
调用点不许再写 ``if 1 <= x <= 8``，也不许再写字面量默认值——默认值只在
:data:`DEFAULT_CONCURRENCY` 出现一次。
"""
from __future__ import annotations

import logging
import os
from collections.abc import Mapping
from typing import Any, cast

logger = logging.getLogger("bgv.engine")

MIN_CONCURRENCY = 1
MAX_CONCURRENCY = 8
DEFAULT_CONCURRENCY = 3
ENV_VAR = "BGV_HLS_CONCURRENCY"

#: API 越界的唯一文案；端点与 GUI 校验都引用它，不许各自抄一遍。
MESSAGE = "hlsConcurrency 必须是 1~8 的整数"


class HlsConcurrencyError(ValueError):
    """请求字段越界：异常本身不携带 HTTP 语义，由端点决定怎么翻成 422。"""


def _is_valid(value: object) -> bool:
    """唯一的范围判断。两个入口都必须经过这里，不许各写一份 if。"""
    return (
        isinstance(value, int)
        and not isinstance(value, bool)
        and MIN_CONCURRENCY <= value <= MAX_CONCURRENCY
    )


def _coerce(value: object) -> int | None:
    """把 API 字段 / 环境变量压成整数；压不出来返回 None（这里不判范围）。

    只认纯十进制整数：``"3.5"`` / ``"1e1"`` 一律拒绝，不会先转 float 再取整
    悄悄改掉用户的输入。空串 / 全空格由调用方按「没配」处理。
    """
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, str):
        raw = value.strip()
        if not raw:
            return None
        try:
            return int(raw, 10)
        except ValueError:
            return None
    return None


def validate_request(value: object) -> int:
    """入口 A（API 请求）：越界 / 非整数抛异常，交给 ``start_download`` 翻 422。"""
    parsed = _coerce(value)
    if not _is_valid(parsed):
        raise HlsConcurrencyError(MESSAGE)
    return cast(int, parsed)


def from_env(env: Mapping[str, str] | None = None) -> int:
    """入口 B（环境变量 / 存储损坏）：越界记审计 + 回落默认，**绝不抛**。

    没有请求方可以回话，所以这里不阻断启动；审计行带 ``source=env`` 与
    ``fallback``，便于 grep 出「谁配错了」。空串 / 空白等同没配，不留噪音审计。
    """
    source: Mapping[str, str] = os.environ if env is None else env
    raw: Any = source.get(ENV_VAR)
    if raw is None:
        return DEFAULT_CONCURRENCY
    parsed = _coerce(raw)
    if parsed is None and isinstance(raw, str) and not raw.strip():
        return DEFAULT_CONCURRENCY
    if not _is_valid(parsed):
        logger.info(
            "hls_concurrency_invalid source=env fallback=%d", DEFAULT_CONCURRENCY
        )
        return DEFAULT_CONCURRENCY
    return cast(int, parsed)


def resolve(request: object = None, env: Mapping[str, str] | None = None) -> int:
    """优先级：请求字段 > 环境变量 > 默认。下载器与端点共用这一条编排。

    ``env=None`` 时读真实进程环境；测试传显式映射，函数不修改它。
    """
    if request is not None:
        return validate_request(request)
    return from_env(env)

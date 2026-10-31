"""日志脱敏（FR-SEC-04 / 验收标准 5）。

日志在格式化之前统一过一遍：Cookie / Authorization 头、Bearer 令牌、
JWT、敏感查询参数与敏感键值对的值替换为 ``[REDACTED]``。审计行只带
task_id / 字节数等非敏感字段，本模块是兜底，不改变审计行的既有格式。
"""
from __future__ import annotations

import logging
import re

REDACTED = "[REDACTED]"

# 敏感键名（用于 key=value / key: value 与 URL 查询参数）
_SENSITIVE_NAMES = (
    r"access[_-]?token"
    r"|refresh[_-]?token"
    r"|id[_-]?token"
    r"|token"
    r"|api[_-]?key"
    r"|apikey"
    r"|client[_-]?secret"
    r"|secret"
    r"|password"
    r"|passwd"
    r"|pwd"
    r"|session[_-]?id"
    r"|sessionid"
    r"|session"
    r"|sid"
    r"|signature"
    r"|sig"
    r"|auth"
)

# 带引号的头值（含 dict 形式：'cookie': 'sid=...'）
_QUOTED_HEADER = re.compile(
    r"(?i)\b(cookie|set-cookie|authorization|proxy-authorization)\b"
    r"\s*['\"]?\s*[:=]\s*(['\"])(?:(?!\2).)*\2"
)

# 裸头行：Cookie: a=b; c=d 整值抹掉，避免值里夹带的后续键值泄漏
_HEADER_LINE = re.compile(
    r"(?i)\b(cookie|set-cookie|authorization|proxy-authorization)\b\s*:\s*[^\r\n]+"
)

# Bearer / Basic 凭据
_BEARER = re.compile(r"(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=-]{4,}")

# URL 查询参数：键名可带前缀（如 user_password），只抹值、保留键名便于定位
_QUERY_PARAM = re.compile(
    rf"(?i)([?&][^=&#\s\"']*(?:{_SENSITIVE_NAMES})=)[^&#\s\"']*"
)

# 文本中的敏感键值对（含引号值）；键名前可带 `_ - .` 等前缀（proxy_password / x-api-key）
_PAIR = re.compile(
    rf"(?i)(?<![A-Za-z0-9])({_SENSITIVE_NAMES})\b\s*[:=]\s*"
    r"(?:\"[^\"]*\"|'[^']*'|[^\s,;&)\]]+)"
)

# 脱离键名的 JWT
_JWT = re.compile(r"\beyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\b")


def redact_text(text: str) -> str:
    """返回脱敏后的文本；对已脱敏文本幂等。"""
    if not text:
        return text
    text = _QUOTED_HEADER.sub(r"\1=" + REDACTED, text)
    text = _HEADER_LINE.sub(r"\1: " + REDACTED, text)
    text = _BEARER.sub(r"\1 " + REDACTED, text)
    text = _PAIR.sub(r"\1=" + REDACTED, text)
    text = _QUERY_PARAM.sub(r"\1" + REDACTED, text)
    text = _JWT.sub(REDACTED, text)
    return text


def _redact_arg(value):
    """只替换含敏感值的参数，保留 int 等类型（uvicorn 访问日志用 %d）。"""
    if isinstance(value, str):
        return redact_text(value)
    if isinstance(value, (int, float, bool, type(None))):
        return value
    try:
        text = str(value)
    except Exception:  # noqa: BLE001
        return value
    redacted = redact_text(text)
    return redacted if redacted != text else value


class RedactingFilter(logging.Filter):
    """在 record 进入 formatter 前脱敏消息、参数、异常栈与 stack_info。

    有 args 时不能清空 args（uvicorn 的 formatter 依赖 5 个占位参数），
    因此逐个脱敏字符串参数、保留非字符串参数的类型。
    """

    def filter(self, record: logging.LogRecord) -> bool:
        if record.args:
            if isinstance(record.args, dict):
                record.args = {key: _redact_arg(value) for key, value in record.args.items()}
            else:
                record.args = tuple(_redact_arg(value) for value in record.args)
        else:
            try:
                message = record.getMessage()
            except Exception:  # noqa: BLE001 —— 构造不出消息时不阻断日志
                return True
            redacted = redact_text(message)
            if redacted != message:
                record.msg = redacted
        if record.exc_info and not record.exc_text:
            record.exc_text = logging.Formatter().formatException(record.exc_info)
        if record.exc_text:
            record.exc_text = redact_text(record.exc_text)
        if record.stack_info:
            record.stack_info = redact_text(record.stack_info)
        return True


def _ensure(handler: logging.Handler) -> None:
    if not any(isinstance(item, RedactingFilter) for item in handler.filters):
        handler.addFilter(RedactingFilter())


def install_log_redaction() -> None:
    """给根 / uvicorn / lastResort 的现有 handler 挂脱敏过滤器。

    引擎自己的记录经 ``bgv.engine`` 向根传播；uvicorn 的访问日志另有
    handler。两边都要挂，避免任一路径把敏感值写进导出日志。
    """
    for logger in (
        logging.getLogger(),
        logging.getLogger("uvicorn"),
        logging.getLogger("uvicorn.error"),
        logging.getLogger("uvicorn.access"),
    ):
        for handler in logger.handlers:
            _ensure(handler)
    if logging.lastResort is not None:
        _ensure(logging.lastResort)

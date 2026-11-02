"""P1② 端到端：真 `_download_real` + 真 `download_direct`，只在**传输边界**换件。

假到哪儿说清楚：`httpx.MockTransport` 的 handler 负责按现场**返回状态码**或
**抛出真实的 httpx / socket / ssl / OSError 类型对象**。网络链路是假的，
但统一校验器、重定向探测、流式写 `.part`、完整性检查、归因表、`_fail_task` 终态
全是真代码——P1② 要证的正是「异常从下载循环里出来以后，任务落的码对不对」。
"""
from __future__ import annotations

import errno
import functools
import logging
import socket
import ssl
from pathlib import Path
from typing import Callable

import httpx
import pytest

import main
from models import (
    CookiePolicy,
    ErrorCode,
    EstimatedQuality,
    MediaStreamType,
    SniffResult,
    Task,
    TaskMode,
    TaskStatus,
)
from services import downloader as downloader_service
from services.downloader import download_direct

pytestmark = pytest.mark.real

MEDIA_URL = "http://8.8.8.8/file.mp4"
HOST = "8.8.8.8"


def _record(tmp_path: Path) -> main.TaskRecord:
    task = Task(
        id="task-fail-map",
        sourceUrl="http://8.8.8.8/watch",
        mode=TaskMode.DOWNLOAD,
        cookiePolicy=CookiePolicy.NONE,
        outputDir=str(tmp_path),
        status=TaskStatus.DOWNLOADING,
        postActions=[],
        createdAt="2026-11-02T00:00:00Z",
        updatedAt="2026-11-02T00:00:00Z",
    )
    return main.TaskRecord(
        task,
        "normal",
        SniffResult(
            taskId=task.id,
            mediaUrl=MEDIA_URL,
            type=MediaStreamType.MP4,
            container="mp4",
            estimatedQuality=EstimatedQuality(height=720, bitrateKbps=1200, label="720p"),
            drmDetected=False,
            discoveredAt="2026-11-02T00:00:00Z",
        ),
    )


def _drive(monkeypatch, tmp_path: Path, handler: Callable[[httpx.Request], httpx.Response]):
    """跑一次真下载：探测请求照常放行，其余交给场景 handler。"""

    def _dispatch(request: httpx.Request) -> httpx.Response:
        if request.headers.get("range") == "bytes=0-0":
            return httpx.Response(200, content=b"", headers={"content-type": "video/mp4"})
        return handler(request)

    monkeypatch.setattr(
        downloader_service,
        "download_direct",
        functools.partial(
            download_direct, transport=httpx.MockTransport(_dispatch), timeout=5.0
        ),
    )
    record = _record(tmp_path)
    main._download_safely(record)
    return record


def _status_handler(status: int) -> Callable[[httpx.Request], httpx.Response]:
    def _handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(status, content=b"nope")

    return _handler


def _raising_handler(exc: BaseException) -> Callable[[httpx.Request], httpx.Response]:
    def _handler(request: httpx.Request) -> httpx.Response:
        raise exc

    return _handler


STATUS_CASES = [
    (403, ErrorCode.AUTH_REQUIRED, {"reason": "http_403", "status": 403}, False),
    (401, ErrorCode.AUTH_REQUIRED, {"reason": "http_401", "status": 401}, False),
    (429, ErrorCode.RATE_LIMITED, {"reason": "rate_limited", "status": 429}, True),
    (500, ErrorCode.NETWORK_HTTP_ERROR, {"reason": "http_500", "status": 500}, True),
    (404, ErrorCode.NETWORK_HTTP_ERROR, {"reason": "http_404", "status": 404}, True),
]


@pytest.mark.parametrize("status, expected, expected_detail, retryable", STATUS_CASES)
def test_http_status_end_to_end(
    monkeypatch, tmp_path, caplog, status, expected, expected_detail, retryable
):
    """站点返回的错误状态不再统一长成「未知错误」，`retryable` 也跟着归位。"""
    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        record = _drive(monkeypatch, tmp_path, _status_handler(status))

    assert record.task.status is TaskStatus.FAILED
    assert record.task.error is not None
    assert record.task.error.code is expected
    assert record.task.error.detail == expected_detail
    assert record.task.error.retryable is retryable
    messages = [item.getMessage() for item in caplog.records]
    assert any(
        line.startswith(f"download_integrity_failed task_id=task-fail-map code={expected.value}")
        for line in messages
    ), messages


EXCEPTION_CASES = [
    (httpx.ConnectTimeout("timed out"), ErrorCode.NETWORK_TIMEOUT, True),
    (httpx.ReadTimeout(""), ErrorCode.NETWORK_TIMEOUT, True),
    (socket.gaierror(-2, "Name or service not known"), ErrorCode.DNS_FAILURE, True),
    (ssl.SSLCertVerificationError("certificate verify failed"), ErrorCode.TLS_ERROR, False),
    (OSError(errno.ENOSPC, "No space left on device"), ErrorCode.DISK_FULL, False),
    (PermissionError(errno.EACCES, "Permission denied"), ErrorCode.WRITE_PERMISSION_DENIED, False),
    (
        httpx.RemoteProtocolError(
            "peer closed connection without sending complete message body"
        ),
        ErrorCode.PARTIAL_DOWNLOAD,
        True,
    ),
]


@pytest.mark.parametrize("exc, expected, retryable", EXCEPTION_CASES)
def test_transport_failure_end_to_end(monkeypatch, tmp_path, caplog, exc, expected, retryable):
    """异常**类型**就是证据：httpx/socket/ssl/OSError 各自落在自己的 §5 码上。"""
    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        record = _drive(monkeypatch, tmp_path, _raising_handler(exc))

    assert record.task.status is TaskStatus.FAILED
    assert record.task.error is not None
    assert record.task.error.code is expected
    assert record.task.error.retryable is retryable
    messages = [item.getMessage() for item in caplog.records]
    assert any(
        line.startswith(f"download_failed task_id=task-fail-map code={expected.value}")
        for line in messages
    ), messages


def test_empty_body_still_lands_partial_download(monkeypatch, tmp_path):
    """真·产物缺失：没有状态、没有异常类型可依，兜底必须还是 PARTIAL_DOWNLOAD。"""
    record = _drive(
        monkeypatch, tmp_path, lambda request: httpx.Response(200, content=b"")
    )

    assert record.task.status is TaskStatus.FAILED
    assert record.task.error is not None
    assert record.task.error.code is ErrorCode.PARTIAL_DOWNLOAD
    assert record.task.error.detail == {"reason": "partial_download"}


def test_attribution_does_not_leak_the_url_into_logs_or_detail(monkeypatch, tmp_path, caplog):
    """§1.7：归因需要的是「哪一类失败」，不是「哪个地址失败了」。"""
    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        record = _drive(monkeypatch, tmp_path, _status_handler(403))

    assert record.task.error is not None
    blob = repr(record.task.error.detail).lower()
    assert HOST not in blob and "http" not in blob.replace("http_403", "")
    for line in (item.getMessage() for item in caplog.records):
        assert HOST not in line
        assert "://" not in line
        assert "cookie" not in line.lower()


def test_the_seven_network_and_storage_codes_now_have_producers(monkeypatch, tmp_path):
    """P1② 的验收线：审计时这七个码在引擎侧零生产者，现在每个都能被真实现场打出来。"""
    produced: set[ErrorCode] = set()
    for handler in (
        _status_handler(403),
        _status_handler(429),
        _status_handler(503),
        _raising_handler(httpx.ConnectTimeout("timed out")),
        _raising_handler(socket.gaierror(-2, "Name or service not known")),
        _raising_handler(ssl.SSLCertVerificationError("certificate verify failed")),
        _raising_handler(OSError(errno.ENOSPC, "No space left on device")),
        _raising_handler(PermissionError(errno.EACCES, "Permission denied")),
    ):
        record = _drive(monkeypatch, tmp_path, handler)
        assert record.task.status is TaskStatus.FAILED
        assert record.task.error is not None
        produced.add(record.task.error.code)

    assert {
        ErrorCode.NETWORK_TIMEOUT,
        ErrorCode.DNS_FAILURE,
        ErrorCode.TLS_ERROR,
        ErrorCode.NETWORK_HTTP_ERROR,
        ErrorCode.RATE_LIMITED,
        ErrorCode.DISK_FULL,
        ErrorCode.WRITE_PERMISSION_DENIED,
    } <= produced


def test_undecidable_failure_says_unknown_instead_of_borrowing_a_network_code(
    monkeypatch, tmp_path
):
    """判不出来就承认判不出来：`ValueError` 不能被凑成 `NETWORK_HTTP_ERROR`。"""
    record = _drive(monkeypatch, tmp_path, _raising_handler(ValueError("extractor said no")))

    assert record.task.status is TaskStatus.FAILED
    assert record.task.error is not None
    assert record.task.error.code is ErrorCode.UNKNOWN
    assert record.task.error.detail == {"reason": "unknown"}

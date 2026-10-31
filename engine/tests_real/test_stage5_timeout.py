"""阶段 5 第 4 步：awaiting_selection 超时（worker 定时扫描）。"""
from __future__ import annotations

import time
from datetime import datetime, timedelta, timezone

import pytest

import main
from models import (
    CookiePolicy,
    EstimatedQuality,
    MediaStreamType,
    SniffResult,
    Task,
    TaskMode,
    TaskStatus,
)

pytestmark = pytest.mark.real


def _iso_seconds_ago(seconds: float) -> str:
    moment = datetime.now(timezone.utc) - timedelta(seconds=seconds)
    return moment.strftime("%Y-%m-%dT%H:%M:%SZ")


def _awaiting_record(task_id: str, updated_at: str) -> main.TaskRecord:
    task = Task(
        id=task_id,
        sourceUrl="https://example.com/watch",
        mode=TaskMode.DOWNLOAD,
        cookiePolicy=CookiePolicy.NONE,
        outputDir="C:\\tmp\\bgv-real",
        status=TaskStatus.AWAITING_SELECTION,
        createdAt=updated_at,
        updatedAt=updated_at,
    )
    sniff = SniffResult(
        taskId=task_id,
        mediaUrl="https://8.8.8.8/hls/master.m3u8",
        type=MediaStreamType.HLS,
        container="m3u8",
        estimatedQuality=EstimatedQuality(height=0, bitrateKbps=0, label="unknown"),
        drmDetected=False,
        discoveredAt=updated_at,
    )
    return main.TaskRecord(task, "normal", sniff)


def _wait_status(client, task_id: str, status: str, timeout: float = 5.0) -> dict:
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        last = client.get(f"/v1/tasks/{task_id}").json()
        if last["status"] == status:
            return last
        time.sleep(0.05)
    raise AssertionError(f"等待 {status} 超时，最后状态：{last}")


def test_awaiting_selection_times_out(client, monkeypatch):
    """阈值 env=1s：超时 → failed + UNKNOWN + detail{reason: awaiting_selection_timeout}。"""
    monkeypatch.setattr(main, "ENGINE_MODE", "real")
    monkeypatch.setenv("BGV_AWAITING_SELECTION_TIMEOUT_SECONDS", "1")

    record = _awaiting_record("timeout-1", _iso_seconds_ago(10))
    main.TASKS[record.task.id] = record

    body = _wait_status(client, record.task.id, "failed")
    assert body["error"]["code"] == "UNKNOWN"
    assert body["error"]["detail"] == {"reason": "awaiting_selection_timeout"}

    persisted = {task.id: task for task in main.get_store().load()}
    assert persisted["timeout-1"].status is TaskStatus.FAILED
    assert persisted["timeout-1"].error.detail == {
        "reason": "awaiting_selection_timeout"
    }


def test_awaiting_selection_within_timeout_is_kept(client, monkeypatch):
    """阈值 env=5s：只有已超时任务被置 failed，新任务保持 awaiting_selection。"""
    monkeypatch.setattr(main, "ENGINE_MODE", "real")
    monkeypatch.setenv("BGV_AWAITING_SELECTION_TIMEOUT_SECONDS", "5")

    expired = _awaiting_record("expired", _iso_seconds_ago(10))
    fresh = _awaiting_record("fresh", _iso_seconds_ago(0))
    main.TASKS[expired.task.id] = expired
    main.TASKS[fresh.task.id] = fresh

    _wait_status(client, "expired", "failed")
    assert client.get("/v1/tasks/fresh").json()["status"] == "awaiting_selection"

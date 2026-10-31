"""阶段 5 第 2 步验收：real 模式非阻塞 GET/POST + 下载中取消。

不访问真实网络：sniffer / yt-dlp / downloader 全部替换为可控桩。
"""
from __future__ import annotations

import threading
import time
from pathlib import Path

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
from services import downloader as downloader_service
from services import formats as formats_service
from services import sniffer as sniffer_service
from services.sniffer import SniffedMedia

pytestmark = pytest.mark.real


def _wait_status(client, task_id: str, statuses: set[str], timeout: float = 5.0) -> dict:
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        last = client.get(f"/v1/tasks/{task_id}").json()
        if last["status"] in statuses:
            return last
        time.sleep(0.02)
    raise AssertionError(f"等待状态 {statuses} 超时，最后状态：{last}")


def _sniff_result() -> SniffResult:
    return SniffResult(
        taskId="stage5-download",
        mediaUrl="https://8.8.8.8/hls/master.m3u8",
        type=MediaStreamType.HLS,
        container="m3u8",
        estimatedQuality=EstimatedQuality(height=0, bitrateKbps=0, label="unknown"),
        drmDetected=False,
        discoveredAt="2026-10-31T00:00:00Z",
    )


def _downloading_record(task_id: str) -> main.TaskRecord:
    task = Task(
        id=task_id,
        sourceUrl="https://example.com/watch",
        mode=TaskMode.DOWNLOAD,
        cookiePolicy=CookiePolicy.NONE,
        outputDir="C:\\tmp\\bgv-real",
        status=TaskStatus.AWAITING_SELECTION,
        createdAt="2026-10-31T00:00:00Z",
        updatedAt="2026-10-31T00:00:00Z",
    )
    return main.TaskRecord(task, "normal", _sniff_result())


def test_real_create_returns_before_sniff_completes(client, monkeypatch):
    """POST /tasks 立即返回；嗅探阻塞期间 GET 仍立即返回 queued/sniffing。"""
    monkeypatch.setattr(main, "ENGINE_MODE", "real")
    entered = threading.Event()
    release = threading.Event()

    class _BlockingSniffer:
        def sniff(self, task_id: str, source_url: str):
            entered.set()
            assert release.wait(5), "测试未释放嗅探阻塞"
            return SniffedMedia(
                url="https://8.8.8.8/media.mp4",
                kind="mp4",
                container="mp4",
                headers_hint={},
            )

    monkeypatch.setattr(sniffer_service, "get_sniffer", lambda: _BlockingSniffer())
    monkeypatch.setattr(formats_service, "extract_info", lambda url, **kw: {"formats": []})

    response = client.post(
        "/v1/tasks",
        json={
            "sourceUrl": "https://example.com/watch",
            "mode": "sniff",
            "cookiePolicy": "none",
            "outputDir": "/home/user/Videos/BGV",
        },
    )
    assert response.status_code == 201
    assert response.json()["status"] == "queued"
    task_id = response.json()["id"]
    assert entered.wait(2), "worker 未开始嗅探"

    started = time.monotonic()
    body = client.get(f"/v1/tasks/{task_id}").json()
    elapsed = time.monotonic() - started
    assert elapsed < 1.0, f"GET 被嗅探阻塞（{elapsed:.2f}s）"
    assert body["status"] in {"queued", "sniffing"}

    release.set()
    final = _wait_status(client, task_id, {"done", "failed"})
    assert final["status"] == "done"


def test_cancel_during_download_returns_cancelled(client, monkeypatch):
    """POST /download 立即返回 downloading；下载中 cancel → cancelled 且不被覆盖。"""
    monkeypatch.setattr(main, "ENGINE_MODE", "real")
    entered = threading.Event()
    release = threading.Event()

    def _blocking_download(media_url, output_dir, *, task_id="unknown", **kwargs):
        entered.set()
        assert release.wait(5), "测试未释放下载阻塞"
        return Path(output_dir) / "out.mp4"

    monkeypatch.setattr(downloader_service, "download_media", _blocking_download)

    record = _downloading_record("stage5-cancel")
    main.TASKS[record.task.id] = record

    started = time.monotonic()
    response = client.post(
        f"/v1/tasks/{record.task.id}/download",
        json={"formatSelector": {"strategy": "best"}},
    )
    elapsed = time.monotonic() - started
    assert response.status_code == 202
    assert response.json()["status"] == "downloading"
    assert elapsed < 1.0, f"POST /download 被下载阻塞（{elapsed:.2f}s）"
    assert entered.wait(2), "worker 未开始下载"

    cancel = client.post(f"/v1/tasks/{record.task.id}/cancel")
    assert cancel.status_code == 200
    assert cancel.json()["status"] == "cancelled"

    release.set()
    time.sleep(0.1)
    assert client.get(f"/v1/tasks/{record.task.id}").json()["status"] == "cancelled"

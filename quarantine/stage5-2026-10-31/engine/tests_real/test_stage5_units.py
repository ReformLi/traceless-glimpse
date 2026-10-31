"""阶段 5：SQLite 持久化 / 后台 worker / 直链断点续传 / 调试端点注册条件。"""
from __future__ import annotations

import logging
import threading

import httpx
import pytest

import main
from models import (
    CookiePolicy,
    ErrorCode,
    EstimatedQuality,
    FormatSelector,
    MediaStreamType,
    SelectionStrategy,
    SniffResult,
    Task,
    TaskMode,
    TaskStatus,
)
from persistence import TaskStore
from services import downloader
from services import formats as formats_service
from services import sniffer as sniffer_service
from services.sniffer import SniffedMedia
from services.task_worker import TaskWorker

pytestmark = pytest.mark.real


@pytest.fixture(autouse=True)
def _clean_state():
    main.reset_state()
    yield
    main.reset_state()


def _task(
    task_id: str = "task-5",
    *,
    status: TaskStatus = TaskStatus.QUEUED,
    mode: TaskMode = TaskMode.SNIFF,
    selector: FormatSelector | None = None,
) -> Task:
    return Task(
        id=task_id,
        sourceUrl="http://8.8.8.8/watch",
        mode=mode,
        formatSelector=selector,
        cookiePolicy=CookiePolicy.NONE,
        outputDir="C:\\tmp\\bgv-real",
        status=status,
        createdAt="2026-10-31T00:00:00Z",
        updatedAt="2026-10-31T00:00:00Z",
    )


def _sniff(task_id: str = "task-5") -> SniffResult:
    return SniffResult(
        taskId=task_id,
        mediaUrl="http://8.8.8.8/hls/master.m3u8",
        type=MediaStreamType.HLS,
        container="m3u8",
        estimatedQuality=EstimatedQuality(height=0, bitrateKbps=0, label="unknown"),
        drmDetected=False,
        discoveredAt="2026-10-31T00:00:00Z",
    )


# ---------------------------------------------------------------------------
# SQLite 持久化
# ---------------------------------------------------------------------------


def test_store_migrates_to_schema_version(tmp_path):
    store = TaskStore(tmp_path / "tasks.db")
    try:
        assert store.version == 2
    finally:
        store.close()


def test_store_roundtrip_with_sniff(tmp_path):
    db = tmp_path / "tasks.db"
    store = TaskStore(db)
    task = _task()
    sniff = _sniff()
    store.upsert(task, "normal", sniff)
    store.close()

    reopened = TaskStore(db)
    try:
        loaded = reopened.load()
    finally:
        reopened.close()
    assert len(loaded) == 1
    restored, scenario, restored_sniff = loaded[0]
    assert restored.id == task.id
    assert restored.status == TaskStatus.QUEUED
    assert scenario == "normal"
    assert restored_sniff is not None and restored_sniff.mediaUrl == sniff.mediaUrl


def test_store_clear(tmp_path):
    store = TaskStore(tmp_path / "tasks.db")
    try:
        store.upsert(_task(), "normal", None)
        store.clear()
        assert store.load() == []
    finally:
        store.close()


def test_recover_restart_marks_non_terminal_failed(tmp_path):
    db = tmp_path / "tasks.db"
    store = TaskStore(db)
    store.upsert(_task("task-running", status=TaskStatus.DOWNLOADING), "normal", _sniff())
    recovered = store.recover_restart()
    store.close()

    task = next(item[0] for item in recovered if item[0].id == "task-running")
    assert task.status == TaskStatus.FAILED
    assert task.error is not None
    assert task.error.code == ErrorCode.UNKNOWN
    assert task.error.detail == {"reason": "engine_restart"}

    reopened = TaskStore(db)
    try:
        persisted = {item[0].id: item[0] for item in reopened.load()}
    finally:
        reopened.close()
    assert persisted["task-running"].status == TaskStatus.FAILED


def test_recover_restart_keeps_terminal_state(tmp_path):
    store = TaskStore(tmp_path / "tasks.db")
    try:
        store.upsert(_task("task-done", status=TaskStatus.DONE), "normal", None)
        recovered = store.recover_restart()
    finally:
        store.close()
    task = next(item[0] for item in recovered if item[0].id == "task-done")
    assert task.status == TaskStatus.DONE
    assert task.error is None


# ---------------------------------------------------------------------------
# 后台 worker
# ---------------------------------------------------------------------------


def test_worker_submit_runs_handler():
    done = threading.Event()
    seen: list[str] = []

    def handler(task_id: str) -> None:
        seen.append(task_id)
        done.set()

    worker = TaskWorker(handler)
    try:
        worker.submit("task-worker")
        assert done.wait(5), "worker 未在 5s 内执行任务"
    finally:
        worker.shutdown()
    assert seen == ["task-worker"]


def test_worker_survives_handler_exception():
    done = threading.Event()

    def handler(task_id: str) -> None:
        if task_id == "boom":
            raise RuntimeError("boom")
        done.set()

    worker = TaskWorker(handler)
    try:
        worker.submit("boom")
        worker.submit("ok")
        assert done.wait(5), "worker 在单任务异常后停止工作"
    finally:
        worker.shutdown()


def test_get_task_real_does_not_advance(client, monkeypatch):
    """阶段 5：real 模式 GET 只读，状态只由 worker 推进。"""
    monkeypatch.setattr(main, "ENGINE_MODE", "real")
    task = _task()
    main.TASKS[task.id] = main.TaskRecord(task, "normal", None)

    response = client.get(f"/v1/tasks/{task.id}")

    assert response.status_code == 200
    assert response.json()["status"] == "queued"
    assert main.TASKS[task.id].task.status == TaskStatus.QUEUED


def test_create_task_real_submits_worker(client, monkeypatch, tmp_path):
    monkeypatch.setattr(main, "ENGINE_MODE", "real")
    monkeypatch.setattr(main, "OUTPUT_DIR_ALLOWLIST", [str(tmp_path)])
    submitted: list[str] = []

    class _FakeWorker:
        def submit(self, task_id: str) -> None:
            submitted.append(task_id)

    monkeypatch.setattr(main, "get_worker", lambda: _FakeWorker())

    response = client.post(
        "/v1/tasks",
        json={
            "sourceUrl": "http://8.8.8.8/watch",
            "mode": "sniff",
            "cookiePolicy": "none",
            "outputDir": str(tmp_path),
        },
    )

    assert response.status_code == 201
    assert submitted == [response.json()["id"]]


def _fake_sniffer(observed: SniffedMedia):
    class _Sniffer:
        def sniff(self, task_id: str, source_url: str):
            return observed

    return _Sniffer()


def test_process_task_sniff_mode_done(monkeypatch):
    monkeypatch.setattr(main, "ENGINE_MODE", "real")
    monkeypatch.setattr(
        sniffer_service,
        "get_sniffer",
        lambda: _fake_sniffer(
            SniffedMedia(
                url="http://8.8.8.8/media.mp4",
                kind="mp4",
                container="mp4",
                headers_hint={},
            )
        ),
    )
    monkeypatch.setattr(formats_service, "extract_info", lambda url, **kw: {"formats": []})

    task = _task(mode=TaskMode.SNIFF)
    main.TASKS[task.id] = main.TaskRecord(task, "normal", None)

    main._process_task(task.id)

    record = main.TASKS[task.id]
    assert record.task.status == TaskStatus.DONE
    assert record.sniff is not None and record.sniff.type == MediaStreamType.MP4


def test_process_task_auto_download_uses_download_media(monkeypatch, tmp_path):
    monkeypatch.setattr(main, "ENGINE_MODE", "real")
    monkeypatch.setattr(
        sniffer_service,
        "get_sniffer",
        lambda: _fake_sniffer(
            SniffedMedia(
                url="http://8.8.8.8/hls/master.m3u8",
                kind="hls",
                container="m3u8",
                headers_hint={},
            )
        ),
    )
    monkeypatch.setattr(formats_service, "fetch_manifest_text", lambda url, **kw: "#EXTM3U\n")
    monkeypatch.setattr(formats_service, "extract_info", lambda url, **kw: {"formats": []})

    calls: list[str] = []

    def _fake_download(media_url, output_dir, *, task_id="unknown", format_expression="best", **kw):
        calls.append(format_expression)
        path = tmp_path / "out.mp4"
        path.write_bytes(b"x")
        return path

    monkeypatch.setattr(downloader, "download_media", _fake_download)

    task = _task(
        mode=TaskMode.DOWNLOAD,
        selector=FormatSelector(strategy=SelectionStrategy.BEST),
    )
    main.TASKS[task.id] = main.TaskRecord(task, "normal", None)

    main._process_task(task.id)

    record = main.TASKS[task.id]
    assert calls == ["best"]
    assert record.task.status == TaskStatus.DONE
    assert record.last_selector_key is not None


# ---------------------------------------------------------------------------
# 直链断点续传（download_direct）
# ---------------------------------------------------------------------------


def test_download_direct_resumes_with_range(tmp_path):
    ranges: list[str | None] = []

    def handler(request: httpx.Request) -> httpx.Response:
        ranges.append(request.headers.get("range"))
        return httpx.Response(
            206,
            headers={"content-range": "bytes 4-9/10"},
            content=b"456789",
        )

    part = tmp_path / "file.mp4.part"
    part.write_bytes(b"0123")

    output = downloader.download_direct(
        "http://8.8.8.8/file.mp4",
        tmp_path,
        transport=httpx.MockTransport(handler),
    )

    assert output.read_bytes() == b"0123456789"
    assert "bytes=4-" in ranges


def test_download_direct_downgrades_when_range_ignored(tmp_path, caplog):
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200, headers={"content-length": "10"}, content=b"0123456789"
        )

    part = tmp_path / "file.mp4.part"
    part.write_bytes(b"0123")

    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        output = downloader.download_direct(
            "http://8.8.8.8/file.mp4",
            tmp_path,
            task_id="task-resume",
            transport=httpx.MockTransport(handler),
        )

    assert output.read_bytes() == b"0123456789"
    audit = [r.getMessage() for r in caplog.records if r.name == "bgv.engine"]
    assert "resume_downgraded task_id=task-resume" in audit


def test_download_direct_integrity_mismatch(tmp_path):
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200, headers={"content-length": "10"}, content=b"01234"
        )

    with pytest.raises(downloader.DownloadIntegrityError):
        downloader.download_direct(
            "http://8.8.8.8/file.mp4",
            tmp_path,
            transport=httpx.MockTransport(handler),
        )


def test_start_download_real_returns_202_and_defers_to_worker(client, monkeypatch):
    """real 模式 POST /download 不阻塞：立即 202 downloading，下载交给 worker。"""
    monkeypatch.setattr(main, "ENGINE_MODE", "real")
    task = _task(
        mode=TaskMode.DOWNLOAD,
        status=TaskStatus.AWAITING_SELECTION,
    )
    record = main.TaskRecord(task, "normal", _sniff())
    main.TASKS[task.id] = record
    submitted: list[str] = []

    class _FakeWorker:
        def submit(self, task_id: str) -> None:
            submitted.append(task_id)

    monkeypatch.setattr(main, "get_worker", lambda: _FakeWorker())

    response = client.post(
        f"/v1/tasks/{task.id}/download",
        json={"formatSelector": {"strategy": "best"}},
    )

    assert response.status_code == 202
    assert response.json()["status"] == "downloading"
    assert record.task.progress is not None and record.task.progress.phase == "downloading"
    assert main.DOWNLOAD_COUNTER.count == 1
    assert submitted == [task.id]


# ---------------------------------------------------------------------------
# selfcheck / 调试端点 / 输出白名单
# ---------------------------------------------------------------------------


def test_privacy_selfcheck_reports_dependency_versions(client):
    response = client.get("/v1/privacy/selfcheck")

    assert response.status_code == 200
    body = response.json()
    assert set(body["dependencyVersions"]) == {"yt-dlp", "ffmpeg", "playwright"}
    assert body["outputDirAllowlist"] == main.OUTPUT_DIR_ALLOWLIST


def test_debug_endpoint_not_registered_by_default(client):
    assert main.DEBUG_ENDPOINTS_ENABLED is False
    assert client.get("/v1/_debug/download-counter").status_code == 404


def test_output_dir_allowlist_contains_default_user_videos():
    assert any(path.endswith("Videos\\BGV") or path.endswith("Videos/BGV") for path in main.OUTPUT_DIR_ALLOWLIST)

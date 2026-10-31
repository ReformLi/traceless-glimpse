"""阶段 5 第 1 步：SQLite 持久化（schema / 迁移 / 重启恢复）。"""
from __future__ import annotations

import sqlite3

import pytest

import persistence
from models import (
    CookiePolicy,
    ErrorCode,
    FormatSelector,
    SelectionStrategy,
    Task,
    TaskMode,
    TaskStatus,
    error_payload,
)

COLUMNS = [
    "id",
    "status",
    "sourceUrl",
    "mode",
    "cookiePolicy",
    "outputDir",
    "format_selector",
    "createdAt",
    "updatedAt",
    "error",
]


def _task(**overrides) -> Task:
    fields = dict(
        id="0190f000-0000-7000-8000-000000000001",
        sourceUrl="https://example.com/watch?v=1",
        mode=TaskMode.DOWNLOAD,
        cookiePolicy=CookiePolicy.LAZY,
        outputDir="/home/user/Videos/BGV",
        status=TaskStatus.QUEUED,
        createdAt="2026-10-31T00:00:00Z",
        updatedAt="2026-10-31T00:00:00Z",
    )
    fields.update(overrides)
    return Task(**fields)


def _max_height_selector() -> FormatSelector:
    return FormatSelector(strategy=SelectionStrategy.MAX_HEIGHT, maxHeight=1080)


def test_migration_sets_user_version_and_schema(tmp_path):
    db_path = tmp_path / "tasks.db"
    store = persistence.TaskStore(db_path)
    try:
        assert store.version == persistence.SCHEMA_VERSION
    finally:
        store.close()

    with sqlite3.connect(db_path) as conn:
        version = conn.execute("PRAGMA user_version").fetchone()[0]
        columns = [row[1] for row in conn.execute("PRAGMA table_info(tasks)")]
    assert version == 1
    assert columns == COLUMNS
    assert "progress" not in columns


def test_upsert_and_load_roundtrip_including_intent_and_error(tmp_path):
    store = persistence.TaskStore(tmp_path / "tasks.db")
    try:
        selector = _max_height_selector()
        store.upsert(
            _task(
                mode=TaskMode.SNIFF,
                cookiePolicy=CookiePolicy.NONE,
                formatSelector=selector,
            )
        )
        (loaded,) = store.load()
        assert loaded.mode is TaskMode.SNIFF
        assert loaded.cookiePolicy is CookiePolicy.NONE
        assert loaded.formatSelector == selector
        assert loaded.error is None

        failed = loaded.model_copy(
            update={
                "status": TaskStatus.FAILED,
                "updatedAt": "2026-10-31T01:00:00Z",
                "error": error_payload(
                    ErrorCode.PARTIAL_DOWNLOAD, {"reason": "short_read"}
                ),
            }
        )
        store.upsert(failed)
        (reloaded,) = store.load()
        assert reloaded.status is TaskStatus.FAILED
        assert reloaded.updatedAt == "2026-10-31T01:00:00Z"
        assert reloaded.error.code is ErrorCode.PARTIAL_DOWNLOAD
        assert reloaded.error.detail == {"reason": "short_read"}
        assert reloaded.mode is TaskMode.SNIFF
        assert reloaded.cookiePolicy is CookiePolicy.NONE
        assert reloaded.formatSelector == selector
    finally:
        store.close()


def test_rows_survive_store_reopen(tmp_path):
    db_path = tmp_path / "tasks.db"
    first = persistence.TaskStore(db_path)
    first.upsert(_task(status=TaskStatus.DONE, updatedAt="2026-10-31T02:00:00Z"))
    first.close()

    second = persistence.TaskStore(db_path)
    try:
        (loaded,) = second.load()
        assert loaded.status is TaskStatus.DONE
        assert loaded.updatedAt == "2026-10-31T02:00:00Z"
    finally:
        second.close()


@pytest.mark.parametrize(
    "status", [TaskStatus.SNIFFING, TaskStatus.DOWNLOADING, TaskStatus.MERGING, TaskStatus.POST_PROCESSING]
)
def test_recover_restart_fails_activity_states(tmp_path, status):
    store = persistence.TaskStore(tmp_path / "tasks.db")
    try:
        store.upsert(_task(id=f"active-{status.value}", status=status))
        (recovered,) = store.recover_restart()
        assert recovered.status is TaskStatus.FAILED
        assert recovered.error.code is ErrorCode.UNKNOWN
        assert recovered.error.detail == {"reason": "engine_restart"}
        assert recovered.updatedAt != "2026-10-31T00:00:00Z"
        (persisted,) = store.load()
        assert persisted.status is TaskStatus.FAILED
    finally:
        store.close()


def test_recover_restart_keeps_queued_and_awaiting_selection_intact(tmp_path):
    """验收 ①：mode=sniff / cookiePolicy=none / awaiting_selection 重启全字段原样。"""
    store = persistence.TaskStore(tmp_path / "tasks.db")
    try:
        awaiting = _task(
            id="sniff-awaiting",
            mode=TaskMode.SNIFF,
            cookiePolicy=CookiePolicy.NONE,
            status=TaskStatus.AWAITING_SELECTION,
            updatedAt="2026-10-31T00:05:00Z",
        )
        queued = _task(id="queued", status=TaskStatus.QUEUED)
        store.upsert(awaiting)
        store.upsert(queued)

        recovered = {task.id: task for task in store.recover_restart()}

        assert recovered["sniff-awaiting"].model_dump() == awaiting.model_dump()
        assert recovered["queued"].model_dump() == queued.model_dump()
        persisted = {task.id: task for task in store.load()}
        assert persisted["sniff-awaiting"].model_dump() == awaiting.model_dump()
        assert persisted["queued"].model_dump() == queued.model_dump()
    finally:
        store.close()


def test_recover_restart_fails_download_but_preserves_intent(tmp_path):
    """验收 ②：mode=download 带 selector 在 downloading 重启 → failed 且意图不丢。"""
    store = persistence.TaskStore(tmp_path / "tasks.db")
    try:
        selector = _max_height_selector()
        original = _task(
            id="downloading",
            mode=TaskMode.DOWNLOAD,
            cookiePolicy=CookiePolicy.EXPLICIT,
            status=TaskStatus.DOWNLOADING,
            formatSelector=selector,
            updatedAt="2026-10-31T00:10:00Z",
        )
        store.upsert(original)

        (recovered,) = store.recover_restart()

        assert recovered.status is TaskStatus.FAILED
        assert recovered.error.code is ErrorCode.UNKNOWN
        assert recovered.error.detail == {"reason": "engine_restart"}
        assert recovered.updatedAt != original.updatedAt
        assert recovered.mode is TaskMode.DOWNLOAD
        assert recovered.cookiePolicy is CookiePolicy.EXPLICIT
        assert recovered.formatSelector == selector
        (persisted,) = store.load()
        assert persisted.status is TaskStatus.FAILED
        assert persisted.mode is TaskMode.DOWNLOAD
        assert persisted.cookiePolicy is CookiePolicy.EXPLICIT
        assert persisted.formatSelector == selector
    finally:
        store.close()


def test_recover_restart_leaves_terminal_states_untouched(tmp_path):
    store = persistence.TaskStore(tmp_path / "tasks.db")
    try:
        done = _task(id="done", status=TaskStatus.DONE, updatedAt="2026-10-31T03:00:00Z")
        cancelled = _task(id="cancelled", status=TaskStatus.CANCELLED)
        failed = _task(
            id="failed",
            status=TaskStatus.FAILED,
            error=error_payload(ErrorCode.SSRF_BLOCKED),
        )
        for task in (done, cancelled, failed):
            store.upsert(task)

        recovered = {task.id: task for task in store.recover_restart()}

        assert recovered["done"].model_dump() == done.model_dump()
        assert recovered["cancelled"].model_dump() == cancelled.model_dump()
        assert recovered["failed"].model_dump() == failed.model_dump()
        assert recovered["failed"].error.code is ErrorCode.SSRF_BLOCKED
    finally:
        store.close()


def test_invalid_persisted_selector_fails_explicitly(tmp_path):
    db_path = tmp_path / "tasks.db"
    store = persistence.TaskStore(db_path)
    try:
        store.upsert(_task(id="corrupt", formatSelector=_max_height_selector()))
        store.close()

        with sqlite3.connect(db_path) as conn:
            conn.execute("UPDATE tasks SET format_selector=? WHERE id=?", ("{not-json", "corrupt"))

        store = persistence.TaskStore(db_path)
        (recovered,) = store.recover_restart()
        assert recovered.status is TaskStatus.FAILED
        assert recovered.error.code is ErrorCode.UNKNOWN
        assert recovered.error.detail == {"reason": "invalid_persisted_selector"}
        assert recovered.formatSelector is None

        (persisted,) = store.load()
        assert persisted.status is TaskStatus.FAILED
        assert persisted.error.detail == {"reason": "invalid_persisted_selector"}
        with sqlite3.connect(db_path) as conn:
            raw = conn.execute(
                "SELECT format_selector FROM tasks WHERE id=?", ("corrupt",)
            ).fetchone()[0]
        assert raw is None
    finally:
        store.close()


def test_resolve_data_dir_honours_env(monkeypatch, tmp_path):
    target = tmp_path / "bgv-data"
    monkeypatch.setenv("BGV_DATA_DIR", str(target))
    assert persistence.resolve_data_dir() == target


def test_get_store_uses_data_dir_and_reset(monkeypatch, tmp_path):
    target = tmp_path / "bgv-data"
    monkeypatch.setenv("BGV_DATA_DIR", str(target))
    persistence.reset_store()
    try:
        store = persistence.get_store()
        assert store.db_path == target / "tasks.db"
        assert (target / "tasks.db").is_file()
        assert persistence.get_store() is store
    finally:
        persistence.reset_store()

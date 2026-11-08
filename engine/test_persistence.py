"""阶段 5 第 1 步：SQLite 持久化（schema / 迁移 / 重启恢复）。"""
from __future__ import annotations

import sqlite3

import pytest

import persistence
from models import (
    CookiePolicy,
    ErrorCode,
    FormatSelector,
    PostAction,
    PostActionType,
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
    "output_path",
    "naming_template",
    "post_actions",
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
    assert version == 3
    assert columns == COLUMNS
    assert "progress" not in columns


def test_v1_database_migrates_to_v3_with_null_output_path(tmp_path):
    db_path = tmp_path / "tasks.db"
    with sqlite3.connect(db_path) as conn:
        conn.execute(
            "CREATE TABLE tasks ("
            "id TEXT PRIMARY KEY, status TEXT NOT NULL, sourceUrl TEXT NOT NULL, "
            "mode TEXT NOT NULL, cookiePolicy TEXT NOT NULL, outputDir TEXT NOT NULL, "
            "format_selector TEXT, createdAt TEXT NOT NULL, updatedAt TEXT NOT NULL, error TEXT)"
        )
        conn.execute(
            "INSERT INTO tasks VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (
                "legacy",
                "done",
                "https://example.com/watch?v=1",
                "download",
                "lazy",
                "/home/user/Videos/BGV",
                None,
                "2026-10-31T00:00:00Z",
                "2026-10-31T00:00:00Z",
                None,
            ),
        )
        conn.execute("PRAGMA user_version=1")

    store = persistence.TaskStore(db_path)
    try:
        assert store.version == 3
        (loaded,) = store.load()
        assert loaded.id == "legacy"
        assert loaded.outputPath is None
        assert loaded.namingTemplate is None
        assert loaded.postActions == []
    finally:
        store.close()


def test_v2_database_migrates_to_v3_with_null_m24_fields(tmp_path):
    db_path = tmp_path / "tasks.db"
    with sqlite3.connect(db_path) as conn:
        conn.execute(
            "CREATE TABLE tasks ("
            "id TEXT PRIMARY KEY, status TEXT NOT NULL, sourceUrl TEXT NOT NULL, "
            "mode TEXT NOT NULL, cookiePolicy TEXT NOT NULL, outputDir TEXT NOT NULL, "
            "format_selector TEXT, createdAt TEXT NOT NULL, updatedAt TEXT NOT NULL, "
            "error TEXT, output_path TEXT)"
        )
        conn.execute(
            "INSERT INTO tasks VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (
                "legacy-v2", "done", "https://example.com/watch?v=2", "download", "lazy",
                "/home/user/Videos/BGV", None, "2026-11-01T00:00:00Z",
                "2026-11-01T00:00:00Z", None, "/home/user/Videos/BGV/old.mp4",
            ),
        )
        conn.execute("PRAGMA user_version=2")

    store = persistence.TaskStore(db_path)
    try:
        assert store.version == 3
        (loaded,) = store.load()
        assert loaded.outputPath == "/home/user/Videos/BGV/old.mp4"
        assert loaded.namingTemplate is None
        assert loaded.postActions == []
    finally:
        store.close()


def test_m24_name_and_post_actions_survive_restart(tmp_path):
    """引擎重启不能把用户填的产物主名与后处理勾选弄丢（封板 P1）。"""
    db_path = tmp_path / "tasks.db"
    first = persistence.TaskStore(db_path)
    try:
        first.upsert(
            _task(
                id="m24",
                status=TaskStatus.QUEUED,
                namingTemplate="我的播客",
                postActions=[PostAction(type=PostActionType.MERGE)],
            )
        )
    finally:
        first.close()

    second = persistence.TaskStore(db_path)
    try:
        (loaded,) = second.recover_restart()
        assert loaded.namingTemplate == "我的播客"
        assert [action.type for action in loaded.postActions] == [PostActionType.MERGE]
    finally:
        second.close()


def test_output_path_roundtrip(tmp_path):
    store = persistence.TaskStore(tmp_path / "tasks.db")
    try:
        store.upsert(
            _task(
                status=TaskStatus.DONE,
                outputPath="/home/user/Videos/BGV/example.mp4",
            )
        )
        (loaded,) = store.load()
        assert loaded.outputPath == "/home/user/Videos/BGV/example.mp4"
    finally:
        store.close()


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
        assert recovered.outputPath is None

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

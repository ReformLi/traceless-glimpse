"""SQLite 状态持久化（阶段 5 第 1 步）。

DB 路径：``BGV_DATA_DIR``（默认 ``%LOCALAPPDATA%\\BrowseGuardVid``）。
迁移骨架：``PRAGMA user_version`` + 有序迁移列表；v1 建 tasks 最小列。
"""
from __future__ import annotations

import json
import os
import sqlite3
import threading
from datetime import datetime, timezone
from pathlib import Path

from models import (
    CookiePolicy,
    ErrorCode,
    ErrorPayload,
    FormatSelector,
    Task,
    TaskMode,
    TaskStatus,
    error_payload,
)

SCHEMA_VERSION = 2
# 重启后无法续跑的活动态：置 failed；queued / awaiting_selection 原样保留
RECOVER_FAILED_STATES = {
    TaskStatus.SNIFFING,
    TaskStatus.DOWNLOADING,
    TaskStatus.MERGING,
    TaskStatus.POST_PROCESSING,
}

_SELECT_COLUMNS = (
    "id, status, sourceUrl, mode, cookiePolicy, outputDir, format_selector, "
    "createdAt, updatedAt, error, output_path"
)

# 每条迁移按 user_version 递增执行；之后只追加、不修改已有条目
_MIGRATIONS: list[tuple[str, ...]] = [
    (
        "CREATE TABLE IF NOT EXISTS tasks ("
        "id TEXT PRIMARY KEY, "
        "status TEXT NOT NULL, "
        "sourceUrl TEXT NOT NULL, "
        "mode TEXT NOT NULL, "
        "cookiePolicy TEXT NOT NULL, "
        "outputDir TEXT NOT NULL, "
        "format_selector TEXT, "
        "createdAt TEXT NOT NULL, "
        "updatedAt TEXT NOT NULL, "
        "error TEXT)",
    ),
    # v2：M5 起 done 任务记录真实产物路径；旧库补空列，历史任务保持 null。
    ("ALTER TABLE tasks ADD COLUMN output_path TEXT",),
]


class InvalidPersistedSelector(ValueError):
    """format_selector 列无法反序列化（恢复时显式置 failed，不静默回落）。"""

    def __init__(self, task_id: str):
        super().__init__(task_id)
        self.task_id = task_id


def resolve_data_dir() -> Path:
    default = os.path.join(
        os.environ.get("LOCALAPPDATA") or os.path.expanduser("~"), "BrowseGuardVid"
    )
    return Path(os.getenv("BGV_DATA_DIR", default))


def _now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


class TaskStore:
    """单连接 + 线程锁（worker 线程与 API 线程共享）。"""

    def __init__(self, db_path: str | Path):
        self.db_path = Path(db_path)
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        self._conn = sqlite3.connect(str(self.db_path), check_same_thread=False)
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._migrate()

    def _migrate(self) -> None:
        with self._lock:
            version = self._user_version_locked()
            for target, statements in enumerate(_MIGRATIONS, start=1):
                if version >= target:
                    continue
                for statement in statements:
                    self._conn.execute(statement)
                self._conn.execute(f"PRAGMA user_version={target}")
                version = target
            self._conn.commit()

    def _user_version_locked(self) -> int:
        return int(self._conn.execute("PRAGMA user_version").fetchone()[0])

    @property
    def version(self) -> int:
        with self._lock:
            return self._user_version_locked()

    def upsert(self, task: Task) -> None:
        selector_json = (
            task.formatSelector.model_dump_json() if task.formatSelector is not None else None
        )
        error_json = (
            task.error.model_dump_json(exclude_none=True) if task.error is not None else None
        )
        with self._lock:
            self._conn.execute(
                "INSERT INTO tasks ("
                "id, status, sourceUrl, mode, cookiePolicy, outputDir, format_selector, "
                "createdAt, updatedAt, error, output_path) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                "ON CONFLICT(id) DO UPDATE SET status=excluded.status, "
                "sourceUrl=excluded.sourceUrl, mode=excluded.mode, "
                "cookiePolicy=excluded.cookiePolicy, outputDir=excluded.outputDir, "
                "format_selector=excluded.format_selector, createdAt=excluded.createdAt, "
                "updatedAt=excluded.updatedAt, error=excluded.error, "
                "output_path=excluded.output_path",
                (
                    task.id,
                    task.status.value,
                    task.sourceUrl,
                    task.mode.value,
                    task.cookiePolicy.value,
                    task.outputDir,
                    selector_json,
                    task.createdAt,
                    task.updatedAt,
                    error_json,
                    task.outputPath,
                ),
            )
            self._conn.commit()

    def load(self) -> list[Task]:
        with self._lock:
            rows = self._conn.execute(
                f"SELECT {_SELECT_COLUMNS} FROM tasks ORDER BY rowid"
            ).fetchall()
        return [self._row_to_task(row) for row in rows]

    @staticmethod
    def _row_to_task(
        row: tuple[str, str, str, str, str, str, str | None, str, str, str | None, str | None]
    ) -> Task:
        (
            task_id,
            status,
            source_url,
            mode,
            cookie_policy,
            output_dir,
            selector_json,
            created_at,
            updated_at,
            error_json,
            output_path,
        ) = row
        try:
            selector = (
                FormatSelector.model_validate_json(selector_json)
                if selector_json is not None
                else None
            )
        except ValueError as exc:
            raise InvalidPersistedSelector(task_id) from exc
        return Task(
            id=task_id,
            sourceUrl=source_url,
            mode=TaskMode(mode),
            cookiePolicy=CookiePolicy(cookie_policy),
            outputDir=output_dir,
            status=TaskStatus(status),
            createdAt=created_at,
            updatedAt=updated_at,
            formatSelector=selector,
            error=ErrorPayload.model_validate(json.loads(error_json)) if error_json else None,
            outputPath=output_path,
        )

    def recover_restart(self) -> list[Task]:
        """重启恢复：活动态置 FAILED，queued / awaiting_selection 原样保留。

        损坏的 format_selector 无法反序列化时显式置 failed + UNKNOWN
        detail{reason: invalid_persisted_selector}，并清除损坏值。
        """
        with self._lock:
            rows = self._conn.execute(
                f"SELECT {_SELECT_COLUMNS} FROM tasks ORDER BY rowid"
            ).fetchall()

        tasks: list[Task] = []
        for row in rows:
            try:
                task = self._row_to_task(row)
            except InvalidPersistedSelector:
                tasks.append(self._repair_invalid_selector(row))
                continue
            if task.status in RECOVER_FAILED_STATES:
                task.status = TaskStatus.FAILED
                task.error = error_payload(ErrorCode.UNKNOWN, {"reason": "engine_restart"})
                task.updatedAt = _now()
                self.upsert(task)
            tasks.append(task)
        return tasks

    def _repair_invalid_selector(
        self, row: tuple[str, str, str, str, str, str, str | None, str, str, str | None, str | None]
    ) -> Task:
        task = Task(
            id=row[0],
            sourceUrl=row[2],
            mode=TaskMode(row[3]),
            cookiePolicy=CookiePolicy(row[4]),
            outputDir=row[5],
            status=TaskStatus.FAILED,
            createdAt=row[7],
            updatedAt=_now(),
            error=error_payload(
                ErrorCode.UNKNOWN, {"reason": "invalid_persisted_selector"}
            ),
        )
        self.upsert(task)
        return task

    def clear(self) -> None:
        with self._lock:
            self._conn.execute("DELETE FROM tasks")
            self._conn.commit()

    def close(self) -> None:
        with self._lock:
            self._conn.close()


_STORE: TaskStore | None = None


def get_store() -> TaskStore:
    global _STORE
    if _STORE is None:
        _STORE = TaskStore(resolve_data_dir() / "tasks.db")
    return _STORE


def reset_store() -> None:
    """测试隔离：关闭并丢弃单例（下一次 get_store 重新按 env 建库）。"""
    global _STORE
    if _STORE is not None:
        _STORE.close()
        _STORE = None

"""SQLite 状态持久化（阶段 5）。

DB 路径：``BGV_DATA_DIR``（默认 ``%LOCALAPPDATA%\\BrowseGuardVid``）。
迁移骨架使用 ``PRAGMA user_version``：v1 建 tasks 表，v2 增加 sniff 列。
"""
from __future__ import annotations

import json
import os
import sqlite3
import threading
from datetime import datetime, timezone
from pathlib import Path

from models import ErrorCode, SniffResult, Task, TaskStatus, error_payload

SCHEMA_VERSION = 2
TERMINAL_STATES = {TaskStatus.DONE, TaskStatus.FAILED, TaskStatus.CANCELLED}


def resolve_data_dir() -> Path:
    default = os.path.join(os.environ.get("LOCALAPPDATA") or os.path.expanduser("~"), "BrowseGuardVid")
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
            version = int(self._conn.execute("PRAGMA user_version").fetchone()[0])
            if version < 1:
                self._conn.execute(
                    "CREATE TABLE IF NOT EXISTS tasks ("
                    "id TEXT PRIMARY KEY, payload TEXT NOT NULL, "
                    "scenario TEXT NOT NULL, updated_at TEXT NOT NULL)"
                )
                version = 1
                self._conn.execute("PRAGMA user_version=1")
            if version < 2:
                self._conn.execute("ALTER TABLE tasks ADD COLUMN sniff TEXT")
                version = 2
                self._conn.execute("PRAGMA user_version=2")
            self._conn.commit()

    @property
    def version(self) -> int:
        with self._lock:
            return int(self._conn.execute("PRAGMA user_version").fetchone()[0])

    def upsert(self, task: Task, scenario: str, sniff: SniffResult | None) -> None:
        sniff_json = sniff.model_dump_json() if sniff is not None else None
        with self._lock:
            self._conn.execute(
                "INSERT INTO tasks (id, payload, scenario, updated_at, sniff) VALUES (?, ?, ?, ?, ?) "
                "ON CONFLICT(id) DO UPDATE SET payload=excluded.payload, "
                "scenario=excluded.scenario, updated_at=excluded.updated_at, sniff=excluded.sniff",
                (
                    task.id,
                    task.model_dump_json(),
                    scenario,
                    task.updatedAt,
                    sniff_json,
                ),
            )
            self._conn.commit()

    def load(self) -> list[tuple[Task, str, SniffResult | None]]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT payload, scenario, sniff FROM tasks ORDER BY rowid"
            ).fetchall()
        result: list[tuple[Task, str, SniffResult | None]] = []
        for payload, scenario, sniff_json in rows:
            task = Task.model_validate(json.loads(payload))
            sniff = SniffResult.model_validate(json.loads(sniff_json)) if sniff_json else None
            result.append((task, scenario, sniff))
        return result

    def recover_restart(self) -> list[tuple[Task, str, SniffResult | None]]:
        """重启恢复：非终态任务置 FAILED + UNKNOWN/detail{reason: engine_restart}。"""
        tasks = self.load()
        for task, scenario, sniff in tasks:
            if task.status in TERMINAL_STATES:
                continue
            task.status = TaskStatus.FAILED
            task.error = error_payload(ErrorCode.UNKNOWN, {"reason": "engine_restart"})
            task.updatedAt = _now()
            self.upsert(task, scenario, sniff)
        return tasks

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

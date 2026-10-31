"""后台任务 worker（阶段 5）：real 模式下嗅探/下载不再由 GET 同步推进。

单线程 + queue.Queue；handler 由 main 注入（避免循环 import）。
"""
from __future__ import annotations

import logging
import queue
import threading
from typing import Callable

logger = logging.getLogger("bgv.engine")

Handler = Callable[[str], None]


class TaskWorker:
    def __init__(self, handler: Handler):
        self._handler = handler
        self._queue: queue.Queue[str | None] = queue.Queue()
        self._thread = threading.Thread(target=self._run, daemon=True, name="bgv-task-worker")
        self._started = False
        self._lock = threading.Lock()

    def submit(self, task_id: str) -> None:
        with self._lock:
            if not self._started:
                self._thread.start()
                self._started = True
        self._queue.put(task_id)

    def _run(self) -> None:
        while True:
            task_id = self._queue.get()
            try:
                if task_id is None:
                    return
                self._handler(task_id)
            except Exception:  # noqa: BLE001 —— worker 不得因单任务异常退出
                logger.exception("worker_task_error task_id=%s", task_id)
            finally:
                self._queue.task_done()

    @property
    def started(self) -> bool:
        return self._started

    def shutdown(self, timeout: float = 5.0) -> None:
        with self._lock:
            if not self._started:
                return
            self._queue.put(None)
        self._thread.join(timeout)
        self._started = False

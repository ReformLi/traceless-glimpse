"""后台任务 worker（阶段 5 第 2 步）：asyncio 单消费者。

- 队列：``asyncio.Queue``，``submit`` 可从任意线程调用（``call_soon_threadsafe``）；
- handler 为协程，阻塞型 IO（Playwright / yt-dlp）由调用方用 ``to_thread`` 下沉；
- 单任务异常只记日志，不终止消费循环。
"""
from __future__ import annotations

import asyncio
import logging
from typing import Awaitable, Callable

logger = logging.getLogger("bgv.engine")

Handler = Callable[[str], Awaitable[None]]
Sweep = Callable[[], None]


class TaskWorker:
    def __init__(
        self,
        handler: Handler,
        *,
        sweep: Sweep | None = None,
        sweep_interval: float = 1.0,
    ):
        self._handler = handler
        self._sweep = sweep
        self._sweep_interval = sweep_interval
        self._queue: asyncio.Queue[str | None] | None = None
        self._loop: asyncio.AbstractEventLoop | None = None
        self._task: asyncio.Task[None] | None = None
        self._sweeper: asyncio.Task[None] | None = None

    async def start(self) -> None:
        if self._task is not None:
            return
        self._queue = asyncio.Queue()
        self._loop = asyncio.get_running_loop()
        self._task = asyncio.create_task(self._run(), name="bgv-task-worker")
        if self._sweep is not None:
            self._sweeper = asyncio.create_task(self._run_sweeper(), name="bgv-task-sweeper")

    def submit(self, task_id: str) -> None:
        if self._loop is None or self._queue is None:
            raise RuntimeError("worker 未启动")
        try:
            running = asyncio.get_running_loop()
        except RuntimeError:
            running = None
        if running is self._loop:
            self._queue.put_nowait(task_id)
        else:
            self._loop.call_soon_threadsafe(self._queue.put_nowait, task_id)

    async def join(self) -> None:
        """等待队列清空（含正在处理的项），供测试与优雅停机使用。"""
        if self._queue is not None:
            await self._queue.join()

    async def _run(self) -> None:
        assert self._queue is not None
        while True:
            task_id = await self._queue.get()
            try:
                if task_id is None:
                    return
                await self._handler(task_id)
            except asyncio.CancelledError:
                raise
            except Exception:  # noqa: BLE001 —— worker 不得因单任务异常退出
                logger.exception("worker_task_error task_id=%s", task_id)
            finally:
                self._queue.task_done()

    async def _run_sweeper(self) -> None:
        sweep = self._sweep
        if sweep is None:
            return
        while True:
            await asyncio.sleep(self._sweep_interval)
            try:
                await asyncio.to_thread(sweep)
            except asyncio.CancelledError:
                raise
            except Exception:  # noqa: BLE001 —— 扫描失败不得终止 worker
                logger.exception("worker_sweep_error")

    @property
    def started(self) -> bool:
        return self._task is not None and not self._task.done()

    async def stop(self) -> None:
        if self._task is None:
            return
        for task in (self._task, self._sweeper):
            if task is not None:
                task.cancel()
                try:
                    await task
                except asyncio.CancelledError:
                    pass
        self._task = None
        self._sweeper = None
        self._loop = None
        self._queue = None

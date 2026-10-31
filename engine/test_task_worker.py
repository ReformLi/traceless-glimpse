"""阶段 5 第 2 步：asyncio worker 单元测试。"""
from __future__ import annotations

import asyncio
import threading

import pytest

from services.task_worker import TaskWorker


def test_worker_processes_queue_in_order():
    async def scenario() -> list[str]:
        processed: list[str] = []

        async def handler(task_id: str) -> None:
            processed.append(task_id)

        worker = TaskWorker(handler)
        await worker.start()
        try:
            worker.submit("a")
            worker.submit("b")
            await worker.join()
            return processed
        finally:
            await worker.stop()

    assert asyncio.run(scenario()) == ["a", "b"]


def test_worker_survives_handler_error():
    async def scenario() -> list[str]:
        processed: list[str] = []

        async def handler(task_id: str) -> None:
            processed.append(task_id)
            if task_id == "boom":
                raise RuntimeError("handler failed")

        worker = TaskWorker(handler)
        await worker.start()
        try:
            worker.submit("boom")
            worker.submit("after")
            await worker.join()
            return processed
        finally:
            await worker.stop()

    assert asyncio.run(scenario()) == ["boom", "after"]


def test_submit_from_another_thread():
    async def scenario() -> list[str]:
        processed: list[str] = []
        done = asyncio.Event()

        async def handler(task_id: str) -> None:
            processed.append(task_id)
            done.set()

        worker = TaskWorker(handler)
        await worker.start()
        try:
            thread = threading.Thread(target=worker.submit, args=("from-thread",))
            thread.start()
            thread.join()
            await asyncio.wait_for(done.wait(), timeout=2)
            return processed
        finally:
            await worker.stop()

    assert asyncio.run(scenario()) == ["from-thread"]


def test_worker_runs_sweep_periodically():
    async def scenario() -> int:
        calls: list[int] = []

        async def handler(task_id: str) -> None:
            return None

        worker = TaskWorker(
            handler, sweep=lambda: calls.append(1), sweep_interval=0.02
        )
        await worker.start()
        try:
            await asyncio.sleep(0.15)
        finally:
            await worker.stop()
        return len(calls)

    assert asyncio.run(scenario()) >= 1


def test_submit_before_start_raises():
    worker = TaskWorker(lambda task_id: asyncio.sleep(0))
    with pytest.raises(RuntimeError):
        worker.submit("x")


def test_stop_cancels_pending_handler():
    async def scenario() -> bool:
        entered = asyncio.Event()

        async def handler(task_id: str) -> None:
            entered.set()
            await asyncio.sleep(60)

        worker = TaskWorker(handler)
        await worker.start()
        worker.submit("slow")
        await asyncio.wait_for(entered.wait(), timeout=2)
        await asyncio.wait_for(worker.stop(), timeout=2)
        return worker.started

    assert asyncio.run(scenario()) is False

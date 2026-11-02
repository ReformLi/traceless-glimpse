"""§8-12 非终态错误出口审计的 P0 修复取证（M4 第 1 步）。

审计发现的结构性缺口：`TaskWorker._run` 对逃逸异常**只 log 不置状态**，
`get_task` 的同步兜底更是把异常抛成 500——两条路都让任务永久停在非终态，
既不失败也不完成。客户端只能靠本地计时器（`code=null`）结束等待，引擎侧的账永远不平。

原则：**每个非终态都必须有错误出口**。落地在推进/下载两个入口的兜底包装上，
而不是逐个调用点补 try——收尾③ 那条 `sniffing` P0 证明「靠人记得」不可靠。

三条用例打的都是**真实崩溃点**，不是伪造的异常：
嗅探器抛非 `SniffError`、嗅探结果带未知 `kind`（枚举构造 ValueError）、
格式表达式解析器抛非契约异常。修复前这三条都会卡在 `sniffing` / `downloading` 直到超时。
"""
from __future__ import annotations

import time

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
from services import formats as formats_service
from services import sniffer as sniffer_service
from services.sniffer import SniffedMedia

pytestmark = pytest.mark.real

OUTPUT_DIR = "/home/user/Videos/BGV"


def _awaiting_record(task_id: str) -> main.TaskRecord:
    """直接铺一条 `awaiting_selection` 记录：只为了让 POST /download 走到 worker 侧下载。"""
    task = Task(
        id=task_id,
        sourceUrl="https://example.com/watch",
        mode=TaskMode.DOWNLOAD,
        cookiePolicy=CookiePolicy.NONE,
        outputDir=OUTPUT_DIR,
        status=TaskStatus.AWAITING_SELECTION,
        createdAt="2026-11-02T00:00:00Z",
        updatedAt="2026-11-02T00:00:00Z",
    )
    sniff = SniffResult(
        taskId=task_id,
        mediaUrl="https://8.8.8.8/media.mp4",
        type=MediaStreamType.MP4,
        container="mp4",
        estimatedQuality=EstimatedQuality(height=0, bitrateKbps=0, label="unknown"),
        drmDetected=False,
        discoveredAt="2026-11-02T00:00:00Z",
    )
    return main.TaskRecord(task, "normal", sniff)


def _wait_terminal(client, task_id: str, timeout: float = 5.0) -> dict:
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        last = client.get(f"/v1/tasks/{task_id}").json()
        if last["status"] in {"done", "failed", "cancelled"}:
            return last
        time.sleep(0.02)
    raise AssertionError(f"任务未进入终态（永久停在非终态＝本次审计的 P0）：{last}")


def _create(client, mode: str, selector: dict | None = None) -> str:
    body = {
        "sourceUrl": "https://example.com/watch",
        "mode": mode,
        "cookiePolicy": CookiePolicy.NONE.value,
        "outputDir": OUTPUT_DIR,
    }
    if selector is not None:
        body["formatSelector"] = selector
    response = client.post("/v1/tasks", json=body)
    assert response.status_code == 201, response.text
    return response.json()["id"]


def _sniffer_returning(media: SniffedMedia | None = None, error: Exception | None = None):
    class _Sniffer:
        def sniff(self, task_id: str, source_url: str, cookie_policy: str = "none"):
            if error is not None:
                raise error
            return media

    return _Sniffer()


_MP4 = SniffedMedia(
    url="https://8.8.8.8/media.mp4", kind="mp4", container="mp4", headers_hint={}
)


def test_sniffer_unexpected_exception_lands_failed(client, monkeypatch):
    """嗅探器抛非 `SniffError`：`_sniff_real` 只捕契约内异常，逃逸后必须落 failed。"""
    monkeypatch.setattr(main, "ENGINE_MODE", "real")
    monkeypatch.setattr(
        sniffer_service,
        "get_sniffer",
        lambda: _sniffer_returning(error=RuntimeError("playwright 崩了")),
    )

    task_id = _create(client, TaskMode.SNIFF.value)
    body = _wait_terminal(client, task_id)

    assert body["status"] == "failed"
    assert body["error"]["code"] == "UNKNOWN"
    assert body["error"]["detail"]["reason"] == "advance_crashed"


def test_unknown_stream_kind_lands_failed(client, monkeypatch):
    """嗅探结果带未知 kind：`MediaStreamType(kind)` 的 ValueError 不得让任务卡在 sniffing。

    枚举构造点在 `_sniff_real` 的两个 try 之外，是这条链上最容易再漏的一类崩溃。
    """
    monkeypatch.setattr(main, "ENGINE_MODE", "real")
    monkeypatch.setattr(
        sniffer_service,
        "get_sniffer",
        lambda: _sniffer_returning(
            SniffedMedia(
                url="https://8.8.8.8/media.mp4",
                kind="blob",
                container="mp4",
                headers_hint={},
            )
        ),
    )
    monkeypatch.setattr(formats_service, "extract_info", lambda url, **kw: {"formats": []})

    task_id = _create(client, TaskMode.SNIFF.value)
    body = _wait_terminal(client, task_id)

    assert body["status"] == "failed"
    assert body["error"]["detail"]["reason"] == "advance_crashed"


def test_format_resolver_exception_on_auto_claim_lands_failed(client, monkeypatch):
    """带 selector 创建 → 引擎自动领取：崩溃归 `download_crashed` 而不是笼统的推进侧。

    自动领取的链路是 `_advance → _do_download → _download_safely`，下载侧兜底更贴近崩溃现场，
    归因也就更准；`advance_crashed` 留给嗅探/转移侧（前两条用例）。
    """
    monkeypatch.setattr(main, "ENGINE_MODE", "real")
    monkeypatch.setattr(sniffer_service, "get_sniffer", lambda: _sniffer_returning(_MP4))
    monkeypatch.setattr(formats_service, "extract_info", lambda url, **kw: {"formats": []})

    def _boom(selector, formats):
        raise RuntimeError("解析器内部炸了")

    monkeypatch.setattr(formats_service, "resolve_format_expression", _boom)

    task_id = _create(client, TaskMode.DOWNLOAD.value, selector={"strategy": "best"})
    body = _wait_terminal(client, task_id)

    assert body["status"] == "failed"
    assert body["error"]["code"] == "UNKNOWN"
    assert body["error"]["detail"]["reason"] == "download_crashed"


def test_downloading_worker_crash_lands_failed(client, monkeypatch):
    """worker 侧下载入口的兜底：`downloading` 也不能因为逃逸异常永久停住。

    `resolve_format_expression` 在 POST /download 的 API 预检与 `_download_real` 里各调一次，
    这里让第二次（worker 侧）炸，才打得到 `_download_safely` 而不是 API 500。
    """
    monkeypatch.setattr(main, "ENGINE_MODE", "real")
    calls = {"n": 0}

    def _boom_on_second_call(selector, formats):
        calls["n"] += 1
        if calls["n"] >= 2:
            raise RuntimeError("下载阶段解析器炸了")
        return formats_service.ResolvedFormat(expression="best", degraded_reason=None)

    monkeypatch.setattr(formats_service, "resolve_format_expression", _boom_on_second_call)

    record = _awaiting_record("nont-downloading")
    main.TASKS[record.task.id] = record

    response = client.post(
        f"/v1/tasks/{record.task.id}/download",
        json={"formatSelector": {"strategy": "best"}},
    )
    assert response.status_code == 202, response.text
    body = _wait_terminal(client, record.task.id)

    assert calls["n"] >= 2, f"worker 未进入下载阶段：{calls}"
    assert body["status"] == "failed"
    assert body["error"]["detail"]["reason"] == "download_crashed"


def test_worker_crash_is_auditable(client, monkeypatch, caplog):
    """兜底不是静默降级：崩溃必须留下可 grep 的审计行（reason + 异常类型）。"""
    monkeypatch.setattr(main, "ENGINE_MODE", "real")
    monkeypatch.setattr(
        sniffer_service,
        "get_sniffer",
        lambda: _sniffer_returning(error=RuntimeError("playwright 崩了")),
    )

    with caplog.at_level("WARNING", logger="bgv.engine"):
        task_id = _create(client, TaskMode.SNIFF.value)
        _wait_terminal(client, task_id)

    text = caplog.text
    assert "advance_crashed" in text
    assert task_id in text

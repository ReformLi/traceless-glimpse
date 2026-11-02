"""阶段 3 API 接线证明：real 模式 _advance → formats_from_info、_do_download → download_media。

不访问真实网络：sniffer / yt-dlp / 下载器全部以模块属性替换，只验证引擎代码路径接通。
"""
from __future__ import annotations

import asyncio
import logging
import threading
import time
from pathlib import Path

import pytest

import main
from models import (
    Codec,
    CookiePolicy,
    ErrorCode,
    EstimatedQuality,
    FormatInfo,
    FormatSelector,
    FormatSource,
    MediaStreamType,
    Resolution,
    SelectionStrategy,
    SniffResult,
    Task,
    TaskMode,
    TaskStatus,
)
from security import SsrfBlockedError
from services import downloader as downloader_service
from services import formats as formats_service
from services import sniffer as sniffer_service
from services.sniffer import SniffedMedia

pytestmark = pytest.mark.real


def _record(mode: TaskMode, sniff: SniffResult | None = None) -> main.TaskRecord:
    task = Task(
        id="task-real",
        sourceUrl="http://8.8.8.8/watch",
        mode=mode,
        cookiePolicy=CookiePolicy.NONE,
        outputDir="C:\\tmp\\bgv-real",
        status=TaskStatus.QUEUED,
        createdAt="2026-10-31T00:00:00Z",
        updatedAt="2026-10-31T00:00:00Z",
    )
    return main.TaskRecord(task, "normal", sniff)


def _sniff_result() -> SniffResult:
    """HLS 结果（走 yt-dlp 下载路径）；MP4 直链另见 direct 用例。"""
    return SniffResult(
        taskId="task-real",
        mediaUrl="http://8.8.8.8/hls/master.m3u8",
        type=MediaStreamType.HLS,
        container="m3u8",
        estimatedQuality=EstimatedQuality(height=0, bitrateKbps=0, label="unknown"),
        drmDetected=False,
        discoveredAt="2026-10-31T00:00:00Z",
    )


def _direct_sniff() -> SniffResult:
    """MP4 直链：走 download_direct（协作式断流的直链分支）。"""
    return SniffResult(
        taskId="task-real",
        mediaUrl="http://8.8.8.8/video.mp4",
        type=MediaStreamType.MP4,
        container="mp4",
        estimatedQuality=EstimatedQuality(height=720, bitrateKbps=2500, label="720p"),
        drmDetected=False,
        discoveredAt="2026-10-31T00:00:00Z",
    )


def _format_info(height: int, format_id: str = "137") -> FormatInfo:
    """构造一条格式表记录；height=0 表示引擎侧「画质未知」。"""
    return FormatInfo(
        formatId=format_id,
        container="mp4",
        resolution=Resolution(width=height * 16 // 9, height=height),
        bitrateKbps=4500,
        codec=Codec(video="avc1.640028", audio="mp4a.40.2"),
        hasAudio=True,
        hasVideo=True,
        fileSizeEstimateBytes=524_288_000,
        source=FormatSource.YT_DLP,
    )


def test_real_advance_calls_formats_from_info(monkeypatch):
    """_advance → _sniff_real → yt-dlp extract_info → formats_from_info。"""
    monkeypatch.setattr(main, "ENGINE_MODE", "real")
    observed = SniffedMedia(
        url="http://8.8.8.8/media.mp4", kind="mp4", container="mp4", headers_hint={}
    )

    class _FakeSniffer:
        def sniff(self, task_id: str, source_url: str, cookie_policy: str = "none"):
            return observed

    monkeypatch.setattr(sniffer_service, "get_sniffer", lambda: _FakeSniffer())
    info = {
        "formats": [
            {
                "format_id": "x",
                "url": "http://8.8.8.8/x.mp4",
                "ext": "mp4",
                "height": 1080,
                "tbr": 4500,
                "format_note": "1080p60",
                "vcodec": "h264",
                "acodec": "aac",
            }
        ]
    }
    monkeypatch.setattr(formats_service, "extract_info", lambda url, **kw: info)

    calls = {"count": 0}
    original = formats_service.formats_from_info

    def _spy(task_id, data, *args, **kwargs):
        calls["count"] += 1
        return original(task_id, data, *args, **kwargs)

    monkeypatch.setattr(formats_service, "formats_from_info", _spy)

    record = _record(TaskMode.SNIFF)
    assert record.formats is None
    main._advance(record)

    assert calls["count"] == 1, "real 模式 _advance 未调用 formats_from_info"
    assert record.task.status == TaskStatus.DONE
    assert record.formats is not None and record.formats[0].formatId == "x"
    assert record.sniff is not None
    assert record.sniff.estimatedQuality.height == 1080
    assert record.sniff.estimatedQuality.bitrateKbps == 4500
    assert record.sniff.estimatedQuality.label == "1080p60"


def test_manifest_blocked_logs_audit_line(monkeypatch, caplog):
    """manifest 被 SSRF 拒绝 → drmDetected=false + sniff_manifest_blocked（只带 task_id）。"""
    monkeypatch.setattr(main, "ENGINE_MODE", "real")
    observed = SniffedMedia(
        url="http://8.8.8.8/master.m3u8", kind="hls", container="m3u8", headers_hint={}
    )

    class _FakeSniffer:
        def sniff(self, task_id: str, source_url: str, cookie_policy: str = "none"):
            return observed

    monkeypatch.setattr(sniffer_service, "get_sniffer", lambda: _FakeSniffer())

    def _blocked(url, **kwargs):
        raise SsrfBlockedError("127.0.0.1")

    monkeypatch.setattr(formats_service, "fetch_manifest_text", _blocked)
    monkeypatch.setattr(formats_service, "extract_info", lambda url, **kw: {"formats": []})

    record = _record(TaskMode.SNIFF)
    with caplog.at_level(logging.WARNING, logger="bgv.engine"):
        main._advance(record)

    assert record.sniff is not None and record.sniff.drmDetected is False
    audit = [r.getMessage() for r in caplog.records if r.name == "bgv.engine"]
    assert "sniff_manifest_blocked task_id=task-real" in audit
    for line in audit:
        assert "m3u8" not in line and "http://" not in line


def test_real_do_download_calls_download_media(monkeypatch, tmp_path):
    """_do_download → download_media（真实服务函数）→ 成功置 DONE。"""
    monkeypatch.setattr(main, "ENGINE_MODE", "real")
    record = _record(TaskMode.DOWNLOAD, sniff=_sniff_result())
    record.task.status = TaskStatus.AWAITING_SELECTION

    calls: dict[str, object] = {}

    def _fake_download(
        media_url, output_dir, *, task_id="unknown", format_expression="best", **kwargs
    ):
        calls.update(
            media_url=media_url,
            output_dir=output_dir,
            task_id=task_id,
            format_expression=format_expression,
        )
        path = tmp_path / "out.mp4"
        path.write_bytes(b"x" * 16)
        return path

    monkeypatch.setattr(downloader_service, "download_media", _fake_download)
    main._do_download(record)

    assert calls == {
        "media_url": "http://8.8.8.8/hls/master.m3u8",
        "output_dir": "C:\\tmp\\bgv-real",
        "task_id": "task-real",
        "format_expression": "best",
    }
    assert record.task.status == TaskStatus.DONE
    assert record.task.progress is not None
    assert record.task.progress.percent == 100.0
    assert record.task.outputPath == str(tmp_path / "out.mp4")


def test_real_do_download_maps_security_error(monkeypatch):
    """下载器抛 SecurityError → 任务 FAILED，错误码原样映射。"""
    monkeypatch.setattr(main, "ENGINE_MODE", "real")
    record = _record(TaskMode.DOWNLOAD, sniff=_sniff_result())
    record.task.status = TaskStatus.AWAITING_SELECTION

    def _blocked_download(media_url, output_dir, *, task_id="unknown", **kwargs):
        raise SsrfBlockedError("10.0.0.1")

    monkeypatch.setattr(downloader_service, "download_media", _blocked_download)
    main._do_download(record)

    assert record.task.status == TaskStatus.FAILED
    assert record.task.error is not None
    assert record.task.error.code == ErrorCode.SSRF_BLOCKED
    assert record.task.error.detail == {"host": "10.0.0.1"}


def test_real_do_download_passes_max_height_expression(monkeypatch, tmp_path):
    """格式表画质齐全且上限可满足 → best[height<=N] 原样下发给 download_media。"""
    monkeypatch.setattr(main, "ENGINE_MODE", "real")
    record = _record(TaskMode.DOWNLOAD, sniff=_sniff_result())
    record.task.status = TaskStatus.AWAITING_SELECTION
    record.task.formatSelector = FormatSelector(
        strategy=SelectionStrategy.MAX_HEIGHT, maxHeight=720
    )
    record.formats = [_format_info(720, "136"), _format_info(1080, "137")]

    calls: dict[str, object] = {}

    def _fake_download(
        media_url, output_dir, *, task_id="unknown", format_expression="best", **kwargs
    ):
        calls["format_expression"] = format_expression
        path = tmp_path / "out.mp4"
        path.write_bytes(b"x")
        return path

    monkeypatch.setattr(downloader_service, "download_media", _fake_download)
    main._do_download(record)

    assert calls["format_expression"] == "best[height<=720]"
    assert record.task.status == TaskStatus.DONE


def test_real_do_download_incomplete_metadata_degrades_to_best(monkeypatch, tmp_path, caplog):
    """场景一：height 未知判不了上限 → 降级 best 跑完，任务不失败，但留 selector_degraded 审计行。"""
    monkeypatch.setattr(main, "ENGINE_MODE", "real")
    record = _record(TaskMode.DOWNLOAD, sniff=_sniff_result())
    record.task.status = TaskStatus.AWAITING_SELECTION
    record.task.formatSelector = FormatSelector(
        strategy=SelectionStrategy.MAX_HEIGHT, maxHeight=720
    )
    record.formats = [_format_info(0, "0")]

    calls: dict[str, object] = {}

    def _fake_download(
        media_url, output_dir, *, task_id="unknown", format_expression="best", **kwargs
    ):
        calls["format_expression"] = format_expression
        path = tmp_path / "out.mp4"
        path.write_bytes(b"x")
        return path

    monkeypatch.setattr(downloader_service, "download_media", _fake_download)
    with caplog.at_level(logging.WARNING):
        main._do_download(record)

    assert calls["format_expression"] == "best"
    assert record.task.status == TaskStatus.DONE
    assert record.task.error is None
    degraded = [
        rec.getMessage() for rec in caplog.records if "selector_degraded" in rec.getMessage()
    ]
    assert degraded == [
        "selector_degraded task_id=task-real reason=incomplete_metadata requested=720"
    ]


def test_real_do_download_no_match_fails_format_unavailable(monkeypatch, tmp_path):
    """场景二：画质全已知但都超出上限 → FAILED + FORMAT_UNAVAILABLE，明确失败不进下载。"""
    monkeypatch.setattr(main, "ENGINE_MODE", "real")
    record = _record(TaskMode.DOWNLOAD, sniff=_sniff_result())
    record.task.status = TaskStatus.AWAITING_SELECTION
    record.task.formatSelector = FormatSelector(
        strategy=SelectionStrategy.MAX_HEIGHT, maxHeight=480
    )
    record.formats = [_format_info(720, "136"), _format_info(1080, "137")]

    def _should_not_be_called(*args, **kwargs):
        raise AssertionError("无匹配时不得调用 download_media")

    monkeypatch.setattr(downloader_service, "download_media", _should_not_be_called)
    main._do_download(record)

    assert record.task.status == TaskStatus.FAILED
    assert record.task.error is not None
    assert record.task.error.code == ErrorCode.FORMAT_UNAVAILABLE
    assert record.task.error.detail == {
        "reason": "no_height_within_cap",
        "requested": 480,
        "available": [720, 1080],
        "message": "上限内没有可用画质",
    }


def test_start_download_no_match_returns_422(client, monkeypatch):
    """API 入口同样拦下无匹配：422 + detail{requested, available}，任务停在 AWAITING_SELECTION。"""
    monkeypatch.setattr(main, "ENGINE_MODE", "real")
    record = _record(TaskMode.DOWNLOAD, sniff=_sniff_result())
    record.task.status = TaskStatus.AWAITING_SELECTION
    record.formats = [_format_info(720, "136"), _format_info(1080, "137")]
    main.TASKS[record.task.id] = record

    response = client.post(
        f"/v1/tasks/{record.task.id}/download",
        json={"formatSelector": {"strategy": "max_height", "maxHeight": 480}},
    )

    assert response.status_code == 422
    error = response.json()["error"]
    assert error["code"] == "FORMAT_UNAVAILABLE"
    assert error["detail"] == {
        "reason": "no_height_within_cap",
        "requested": 480,
        "available": [720, 1080],
        "message": "上限内没有可用画质",
    }
    assert record.task.status == TaskStatus.AWAITING_SELECTION
    assert main.DOWNLOAD_COUNTER.count == 0


def test_real_do_download_exact_selector_fails(monkeypatch):
    """auto 领取路径遇到 exact → FAILED + FORMAT_UNAVAILABLE（非静默 best）。"""
    monkeypatch.setattr(main, "ENGINE_MODE", "real")
    record = _record(TaskMode.DOWNLOAD, sniff=_sniff_result())
    record.task.status = TaskStatus.AWAITING_SELECTION
    record.task.formatSelector = FormatSelector(
        strategy=SelectionStrategy.EXACT, formatId="137+140"
    )

    def _should_not_be_called(*args, **kwargs):
        raise AssertionError("exact 选择器不得调用 download_media")

    monkeypatch.setattr(downloader_service, "download_media", _should_not_be_called)
    main._do_download(record)

    assert record.task.status == TaskStatus.FAILED
    assert record.task.error is not None
    assert record.task.error.code == ErrorCode.FORMAT_UNAVAILABLE
    assert record.task.error.detail == {
        "reason": "exact_format_id_unsupported",
        "message": "M1 暂不支持精确 formatId",
    }


def test_start_download_exact_selector_returns_422(client, monkeypatch):
    """POST /download + exact → 422 FORMAT_UNAVAILABLE，且不进入 downloading。"""
    monkeypatch.setattr(main, "ENGINE_MODE", "real")
    record = _record(TaskMode.DOWNLOAD, sniff=_sniff_result())
    record.task.status = TaskStatus.AWAITING_SELECTION
    main.TASKS[record.task.id] = record

    response = client.post(
        f"/v1/tasks/{record.task.id}/download",
        json={"formatSelector": {"strategy": "exact", "formatId": "137+140"}},
    )

    assert response.status_code == 422
    error = response.json()["error"]
    assert error["code"] == "FORMAT_UNAVAILABLE"
    assert error["detail"] == {
        "reason": "exact_format_id_unsupported",
        "message": "M1 暂不支持精确 formatId",
    }
    assert record.task.status == TaskStatus.AWAITING_SELECTION
    assert main.DOWNLOAD_COUNTER.count == 0


def test_start_download_max_height_without_height_returns_422(client, monkeypatch):
    """POST /download + max_height 缺 maxHeight → 422，不静默 best、不进入 downloading。"""
    monkeypatch.setattr(main, "ENGINE_MODE", "real")
    record = _record(TaskMode.DOWNLOAD, sniff=_sniff_result())
    record.task.status = TaskStatus.AWAITING_SELECTION
    main.TASKS[record.task.id] = record

    response = client.post(
        f"/v1/tasks/{record.task.id}/download",
        json={"formatSelector": {"strategy": "max_height"}},
    )

    assert response.status_code == 422
    error = response.json()["error"]
    assert error["code"] == "FORMAT_UNAVAILABLE"
    assert error["detail"] == {
        "reason": "max_height_required",
        "message": "max_height 策略必须提供 maxHeight",
    }
    assert record.task.status == TaskStatus.AWAITING_SELECTION
    assert main.DOWNLOAD_COUNTER.count == 0


# ---------------------------------------------------------------------------
# 4b-3 协作式断流：cancel 端点置的标志，必须就是下载循环读的同一个标志
# ---------------------------------------------------------------------------


def _wait_for_log(caplog, prefix: str, timeout: float = 5.0) -> str:
    """worker 线程写日志有延迟：轮询到出现指定前缀的审计行为止。"""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        for record in caplog.records:
            message = record.getMessage()
            if message.startswith(prefix):
                return message
        time.sleep(0.02)
    raise AssertionError(f"未在 {timeout}s 内看到审计行 {prefix!r}")


def test_cancel_mid_download_aborts_and_stays_cancelled(client, monkeypatch, caplog):
    """下载阻塞在传输中 → POST /cancel → 循环命中标志抛错 → 终态 cancelled 不被覆盖。

    这是「接线」证明：假下载器只会调用 main.py 传进来的 ``should_abort``，
    标志没接上就永远等不到取消，任务会落成 done/failed。
    """
    monkeypatch.setattr(main, "ENGINE_MODE", "real")
    entered = threading.Event()
    release = threading.Event()

    def _fake_direct(
        media_url,
        output_dir,
        *,
        task_id="unknown",
        should_abort=None,
        on_progress=None,
        **kwargs,
    ):
        assert should_abort is not None, "download_direct 未收到 should_abort：断流没接线"
        assert on_progress is not None, "download_direct 未收到 on_progress：进度没接线"
        on_progress(1024, 3072)
        entered.set()
        assert release.wait(5), "测试未释放下载阻塞"
        if not should_abort():
            raise AssertionError("cancel 端点置的标志与下载循环读到的不是同一个")
        raise downloader_service.DownloadCancelled(
            f"task_id={task_id} aborted after 1024 bytes"
        )

    monkeypatch.setattr(downloader_service, "download_direct", _fake_direct)

    record = _record(TaskMode.DOWNLOAD, sniff=_direct_sniff())
    record.task.status = TaskStatus.AWAITING_SELECTION
    main.TASKS[record.task.id] = record

    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        started = client.post(
            f"/v1/tasks/{record.task.id}/download",
            json={"formatSelector": {"strategy": "best"}},
        )
        assert started.status_code == 202
        assert record.task.status == TaskStatus.DOWNLOADING
        assert entered.wait(5), "worker 未进入下载"
        assert record.task.progress is not None
        assert record.task.progress.bytesDownloaded == 1024

        cancelled = client.post(f"/v1/tasks/{record.task.id}/cancel")
        assert cancelled.status_code == 200
        assert cancelled.json()["status"] == "cancelled"
        release.set()

        abort_line = _wait_for_log(caplog, "download_aborted")

    assert record.task.status == TaskStatus.CANCELLED, "断流后不得被覆盖成 done/failed"
    assert record.task.error is None
    # 取消前的真实进度保持冻结：断流不把字节清零，也不补 done
    assert record.task.progress.bytesDownloaded == 1024
    assert record.task.progress.bytesTotal == 3072
    assert record.task.progress.phase == "downloading"
    assert abort_line == "download_aborted task_id=task-real detail=task_id=task-real aborted after 1024 bytes"
    messages = [record.getMessage() for record in caplog.records]
    assert any(line.startswith("cancel_requested task_id=task-real from_status=downloading") for line in messages)
    assert not any(line.startswith("download_failed") for line in messages)


def test_cancel_before_worker_start_skips_downloader(monkeypatch, caplog):
    """取消落在 202 之后、下载起跑之前：直接停手，不碰状态、不叫下载器。"""
    monkeypatch.setattr(main, "ENGINE_MODE", "real")
    record = _record(TaskMode.DOWNLOAD, sniff=_direct_sniff())
    record.task.status = TaskStatus.CANCELLED
    record.cancel_requested.set()

    def _should_not_run(*args, **kwargs):
        raise AssertionError("取消后不得调用下载器")

    monkeypatch.setattr(downloader_service, "download_direct", _should_not_run)
    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        main._download_real(record)

    assert record.task.status == TaskStatus.CANCELLED
    assert record.task.error is None
    assert any(
        line.startswith("download_skipped_cancelled task_id=task-real")
        for line in [record.getMessage() for record in caplog.records]
    )


def test_cancel_racing_with_completion_keeps_cancelled(monkeypatch, caplog, tmp_path):
    """收尾竞态：取消落在「下载器已跑完、终态还没落」之间 → 不补 done、不覆盖 cancelled。"""
    monkeypatch.setattr(main, "ENGINE_MODE", "real")
    record = _record(TaskMode.DOWNLOAD, sniff=_direct_sniff())
    record.task.status = TaskStatus.DOWNLOADING

    def _fake_direct(media_url, output_dir, **kwargs):
        # 复现 cancel 端点的两步（置标志 + 落 cancelled），都发生在下载器返回之前
        record.cancel_requested.set()
        main._transition(record.task, TaskStatus.CANCELLED)
        return tmp_path / "video.mp4"

    monkeypatch.setattr(downloader_service, "download_direct", _fake_direct)
    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        main._download_real(record)

    assert record.task.status == TaskStatus.CANCELLED, "完成竞态不得把 cancelled 覆盖成 done"
    assert record.task.progress is None
    assert record.task.error is None
    messages = [item.getMessage() for item in caplog.records]
    assert any(line.startswith("download_cancelled task_id=task-real") for line in messages)
    assert not any(line.startswith("download_aborted") for line in messages)


def test_progress_recorder_uses_rolling_window(monkeypatch):
    """速度/ETA 走 0.5s 窗口；total 未知时 percent 保持 0，不猜。"""
    task = _record(TaskMode.DOWNLOAD).task
    clocks = iter([0.0, 0.6, 1.2, 1.3])
    monkeypatch.setattr(main.time, "monotonic", lambda: next(clocks))
    on_progress = main._make_progress_recorder(task)

    on_progress(600, 1200)
    first = task.progress
    assert (first.bytesDownloaded, first.bytesTotal, first.percent) == (600, 1200, 50.0)
    assert first.speedBps == 1000  # 600 B / 0.6 s
    assert first.etaSeconds == 0  # 600 B / 1000 B/s 向下取整
    assert first.phase == "downloading"

    on_progress(1200, 1200)
    assert (task.progress.percent, task.progress.speedBps) == (100.0, 1000)

    on_progress(200, None)
    unknown = task.progress
    assert (unknown.bytesDownloaded, unknown.bytesTotal, unknown.percent) == (200, 0, 0.0)
    assert unknown.etaSeconds == 0


def test_real_sniff_no_media_fails_with_protocol_code(monkeypatch):
    """页面无媒体流 → failed + NO_MEDIA_FOUND（§5 原文案、可重试）。

    回归：这条路径曾引用不存在的 `ErrorCode.INTERNAL_ERROR`，worker 线程抛
    AttributeError，任务永久停在 sniffing，客户端只能等到超时。
    """
    monkeypatch.setattr(main, "ENGINE_MODE", "real")

    class _NoMediaSniffer:
        def sniff(self, task_id: str, source_url: str, cookie_policy: str = "none"):
            return None

    monkeypatch.setattr(sniffer_service, "get_sniffer", lambda: _NoMediaSniffer())

    record = _record(TaskMode.SNIFF)
    main._advance(record)

    task = record.task
    assert task.status == TaskStatus.FAILED
    assert task.error is not None
    assert task.error.code is ErrorCode.NO_MEDIA_FOUND
    assert task.error.message == "未发现可下载的媒体流"
    assert task.error.retryable is True
    assert task.error.detail == {"reason": "sniff_failed"}
    assert record.sniff is None


def test_real_sniff_error_fails_with_extractor_code(monkeypatch):
    """嗅探自身异常（playwright 缺失 / worker 崩溃）→ failed + EXTRACTOR_FAILED。"""
    monkeypatch.setattr(main, "ENGINE_MODE", "real")

    class _BrokenSniffer:
        def sniff(self, task_id: str, source_url: str, cookie_policy: str = "none"):
            raise sniffer_service.SniffError("browser worker died")

    monkeypatch.setattr(sniffer_service, "get_sniffer", lambda: _BrokenSniffer())

    record = _record(TaskMode.DOWNLOAD)
    main._advance(record)

    task = record.task
    assert task.status == TaskStatus.FAILED
    assert task.error is not None
    assert task.error.code is ErrorCode.EXTRACTOR_FAILED
    assert task.error.message == "解析失败，可能站点已变更"
    assert task.error.retryable is True


def test_real_downloading_without_sniff_fails_with_unknown_code(monkeypatch):
    """worker 起跑时 sniff 丢失（重启恢复的边缘）→ failed + UNKNOWN，不再抛 AttributeError。"""
    monkeypatch.setattr(main, "ENGINE_MODE", "real")
    record = _record(TaskMode.DOWNLOAD)
    record.task.status = TaskStatus.DOWNLOADING
    main.TASKS[record.task.id] = record

    asyncio.run(main._process_task(record.task.id))

    assert record.task.status == TaskStatus.FAILED
    assert record.task.error.code is ErrorCode.UNKNOWN
    assert record.task.error.detail == {"reason": "missing_sniff"}

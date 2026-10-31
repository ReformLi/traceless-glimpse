"""阶段 3 API 接线证明：real 模式 _advance → formats_from_info、_do_download → download_media。

不访问真实网络：sniffer / yt-dlp / 下载器全部以模块属性替换，只验证引擎代码路径接通。
"""
from __future__ import annotations

import logging

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


def test_real_advance_calls_formats_from_info(monkeypatch):
    """_advance → _sniff_real → yt-dlp extract_info → formats_from_info。"""
    monkeypatch.setattr(main, "ENGINE_MODE", "real")
    observed = SniffedMedia(
        url="http://8.8.8.8/media.mp4", kind="mp4", container="mp4", headers_hint={}
    )

    class _FakeSniffer:
        def sniff(self, task_id: str, source_url: str):
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
        def sniff(self, task_id: str, source_url: str):
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
    """_do_download → max_height 映射为 best[height<=N] 传给 download_media。"""
    monkeypatch.setattr(main, "ENGINE_MODE", "real")
    record = _record(TaskMode.DOWNLOAD, sniff=_sniff_result())
    record.task.status = TaskStatus.AWAITING_SELECTION
    record.task.formatSelector = FormatSelector(
        strategy=SelectionStrategy.MAX_HEIGHT, maxHeight=720
    )

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

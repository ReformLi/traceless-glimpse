"""§3.7 ``merge`` 的语义落点 + §4 ``merging`` 态在桩模式下也走完。

回归动机（M4 第 1 步审计 P0 / §19.3 缺口 1）：全仓没有任何一处转移到 `merging`，
`postActions=[merge]` 被引擎收下、持久化、回显，然后**从不执行**——任务报 `done`，
用户以为合了。客户端还给这两个状态写了「合并音视频中」「后处理中」两条取不到值的
屏显文案。这里守住三件事：拆轨判定按元数据、跳过必留审计行、状态机真的经过 `merging`。
"""

from __future__ import annotations

import logging

import main
from models import (
    Codec,
    CookiePolicy,
    EstimatedQuality,
    FormatInfo,
    MediaStreamType,
    PostAction,
    PostActionType,
    Resolution,
    SniffResult,
    Task,
    TaskMode,
    TaskStatus,
)
from services import formats as formats_service


def _format(*, has_video: bool, has_audio: bool, format_id: str = "1") -> FormatInfo:
    return FormatInfo(
        formatId=format_id,
        container="mp4",
        resolution=Resolution(width=1280, height=720),
        bitrateKbps=1200,
        codec=Codec(video="avc1" if has_video else "none", audio="mp4a" if has_audio else "none"),
        hasAudio=has_audio,
        hasVideo=has_video,
        fileSizeEstimateBytes=4_000_000,
        source="yt-dlp",
    )


_MUXED_ONLY = [_format(has_video=True, has_audio=True, format_id="a")]
_SEPARATE = [
    _format(has_video=True, has_audio=False, format_id="v"),
    _format(has_video=False, has_audio=True, format_id="b"),
]


def _sniff(stream_type: MediaStreamType) -> SniffResult:
    return SniffResult(
        taskId="task-merge",
        mediaUrl="http://8.8.8.8/hls/master.m3u8",
        type=stream_type,
        container="m3u8",
        estimatedQuality=EstimatedQuality(height=720, bitrateKbps=1200, label="720p"),
        drmDetected=False,
        discoveredAt="2026-11-02T00:00:00Z",
    )


def _record(
    stream_type: MediaStreamType = MediaStreamType.HLS,
    formats: list[FormatInfo] | None = None,
) -> main.TaskRecord:
    task = Task(
        id="task-merge",
        sourceUrl="http://8.8.8.8/watch",
        mode=TaskMode.DOWNLOAD,
        cookiePolicy=CookiePolicy.NONE,
        outputDir="/home/user/Videos/BGV",
        status=TaskStatus.DOWNLOADING,
        postActions=[],
        createdAt="2026-11-02T00:00:00Z",
        updatedAt="2026-11-02T00:00:00Z",
    )
    record = main.TaskRecord(task, "normal", _sniff(stream_type))
    record.formats = formats
    return record


def _merge(action_type: str = "merge") -> list[dict]:
    return [{"type": action_type}]


# ---------------------------------------------------------------------------
# 拆轨判定：纯元数据，不碰下载器
# ---------------------------------------------------------------------------


def test_track_expressions_splits_only_the_best_family():
    assert formats_service.track_expressions("best") == formats_service.TrackExpressions(
        video="bestvideo", audio="bestaudio"
    )
    assert formats_service.track_expressions("best[height<=720]").video == (
        "bestvideo[height<=720]"
    )
    # 纯音频选择器没有视频轨可合；精确 formatId 不在 M1 支持面内
    assert formats_service.track_expressions("bestaudio") is None
    assert formats_service.track_expressions("137+140") is None


def test_has_separate_tracks_requires_both_single_tracks():
    """只有「仅视频 + 仅音频」两条都在，才谈得上合并；混合流源合不了。"""
    assert formats_service.has_separate_tracks(_SEPARATE)
    assert not formats_service.has_separate_tracks(_MUXED_ONLY)
    assert not formats_service.has_separate_tracks([_format(has_video=True, has_audio=False)])
    assert not formats_service.has_separate_tracks([])
    assert not formats_service.has_separate_tracks(None)


def _merge_plan_case(record: main.TaskRecord) -> main.TaskRecord:
    record.task.postActions = [PostAction(type=PostActionType.MERGE)]
    return record


def test_merge_plan_reasons_are_distinguishable(caplog):
    """三个跳过理由必须各不相同：排查时「为什么没合」要能从日志直接读出来。"""
    plain = _record(formats=_SEPARATE)
    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        assert main._merge_plan(plain, "best") is None
    assert not [r for r in caplog.records if "post_action_noop" in r.getMessage()]

    direct = _merge_plan_case(_record(MediaStreamType.MP4, formats=_SEPARATE))
    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        assert main._merge_plan(direct, "best") is None
    assert "reason=single_file_source" in caplog.text

    audio_only = _merge_plan_case(_record(formats=_SEPARATE))
    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        assert main._merge_plan(audio_only, "bestaudio") is None
    assert "reason=selector_not_splittable" in caplog.text

    muxed = _merge_plan_case(_record(formats=_MUXED_ONLY))
    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        assert main._merge_plan(muxed, "best") is None
    assert "reason=no_separate_tracks" in caplog.text

    plan = main._merge_plan(_merge_plan_case(_record(formats=_SEPARATE)), "best[height<=720]")
    assert plan is not None and plan.video == "bestvideo[height<=720]"


# ---------------------------------------------------------------------------
# 桩模式状态机：merging 真的被经过
# ---------------------------------------------------------------------------


def _create(client, source_url="https://example.com/watch?v=a"):
    rsp = client.post(
        "/v1/tasks",
        json={
            "sourceUrl": source_url,
            "mode": "download",
            "cookiePolicy": "lazy",
            "outputDir": "/home/user/Videos/BGV",
        },
    )
    assert rsp.status_code == 201
    return rsp.json()


def _statuses(client, task_id, times):
    seen = []
    for _ in range(times):
        seen.append(client.get(f"/v1/tasks/{task_id}").json()["status"])
    return seen


def test_stub_merge_requested_walks_through_merging(client):
    """点了合并：downloading → merging → done 三态依次可见（桩不产生真实 FFmpeg）。"""
    task = _create(client)
    client.get(f"/v1/tasks/{task['id']}")  # → awaiting_selection
    rsp = client.post(
        f"/v1/tasks/{task['id']}/download",
        json={"formatSelector": {"strategy": "best"}, "postActions": _merge("merge")},
    )
    assert rsp.status_code == 202
    assert rsp.json()["status"] == "downloading"
    assert _statuses(client, task["id"], 2) == ["merging", "done"]


def test_stub_without_merge_goes_straight_to_done(client):
    """没点合并的既有语义不变：一次 GET 到 done，不进 merging。"""
    task = _create(client)
    client.get(f"/v1/tasks/{task['id']}")
    client.post(
        f"/v1/tasks/{task['id']}/download",
        json={"formatSelector": {"strategy": "best"}},
    )
    assert _statuses(client, task["id"], 1) == ["done"]


def test_unimplemented_post_action_is_audited_not_swallowed(client, caplog):
    """M1 未实现的动作（缩略图）照单收下但必须留痕，不能静默。"""
    task = _create(client)
    client.get(f"/v1/tasks/{task['id']}")
    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        client.post(
            f"/v1/tasks/{task['id']}/download",
            json={
                "formatSelector": {"strategy": "best"},
                "postActions": _merge("thumbnail"),
            },
        )
    assert "post_action_unsupported" in caplog.text
    assert "types=thumbnail" in caplog.text

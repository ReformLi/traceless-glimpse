"""HLS 并发链路（M25）：API / 环境变量 → 配置对象 → yt-dlp 选项 → 下载器。

只测校验函数等于没测透传——这个仓库已经吃过一次「字段收下了但没人消费」的亏
（``namingTemplate`` 从 M1 睡到 M24）。所以这里每条用例都要求值**真的走到**
``concurrent_fragment_downloads`` 或 ``download_media`` 的 kwargs 上。

另有两条守卫用例（直链单连接、两轨各自预算），它们现在就该是绿的：
那是「禁止后人改成并发」的护栏，不是待实现的需求。
"""
from __future__ import annotations

import logging
import re
from pathlib import Path

import httpx
import pytest

import main
from models import (
    CookiePolicy,
    DownloadRequest,
    EstimatedQuality,
    FormatSelector,
    MediaStreamType,
    SelectionStrategy,
    SniffResult,
    Task,
    TaskMode,
    TaskStatus,
)
from services import downloader
from services import downloader as downloader_service
from services.downloader import download_direct, download_media, download_tracks

MANIFEST = "http://8.8.8.8/hls/master.m3u8"
ENV_VAR = "BGV_HLS_CONCURRENCY"


# ---------------------------------------------------------------------------
# yt-dlp 替身：只记录拿到的 options，并落一个占位产物
# ---------------------------------------------------------------------------


class _RecordingYDL:
    def __init__(self, options: dict, sink: list[dict]) -> None:
        sink.append(options)
        template = options["outtmpl"]
        if "bgv-video" in template:
            ext = "webm"
        elif "bgv-audio" in template:
            ext = "m4a"
        else:
            ext = "mp4"
        self._target = Path(
            template.replace("%(title)s", "site_title").replace("%(ext)s", ext)
        )

    def __enter__(self) -> "_RecordingYDL":
        return self

    def __exit__(self, *exc_info) -> bool:
        return False

    def extract_info(self, url: str, download: bool = False):
        if not download:
            return {
                "formats": [{"url": "http://8.8.8.8/v.mp4"}],
                "requested_downloads": [{"url": "http://8.8.8.8/v.mp4"}],
            }
        self._target.write_bytes(b"MZ")
        return {"requested_downloads": [{"url": "http://8.8.8.8/v.mp4"}]}

    def prepare_filename(self, info: dict) -> str:
        return str(self._target)


def _factory(sink: list[dict]):
    return lambda options: _RecordingYDL(options, sink)


@pytest.fixture()
def quiet_downloader(monkeypatch):
    """切掉两件与本轮无关的外部依赖：分片数探针（会真发 HTTP）、ffprobe（要二进制）。"""
    monkeypatch.setattr(downloader, "_probe_hls_fragment_count", lambda *a, **k: None)
    monkeypatch.setattr(downloader, "_ffprobe_duration", lambda path: 5.0)
    monkeypatch.delenv(ENV_VAR, raising=False)


# ---------------------------------------------------------------------------
# downloader：默认值 / 请求值 / 环境变量 / 优先级
# ---------------------------------------------------------------------------


def test_hls_default_concurrency_is_three(tmp_path, quiet_downloader):
    sink: list[dict] = []
    download_media(
        MANIFEST, tmp_path, ydl_factory=_factory(sink), validator=lambda url: url
    )
    assert sink[0].get("concurrent_fragment_downloads") == 3, (
        "引擎 HLS 默认并发必须是 3（M25 从串行 1 改为 3）"
    )


@pytest.mark.parametrize("value", [1, 2, 5, 8])
def test_requested_concurrency_reaches_ytdlp_options(value, tmp_path, quiet_downloader):
    sink: list[dict] = []
    download_media(
        MANIFEST,
        tmp_path,
        hls_concurrency=value,
        ydl_factory=_factory(sink),
        validator=lambda url: url,
    )
    assert sink[0]["concurrent_fragment_downloads"] == value


def test_env_supplies_the_default_when_request_omits_it(
    tmp_path, quiet_downloader, monkeypatch
):
    monkeypatch.setenv(ENV_VAR, "6")
    sink: list[dict] = []
    download_media(
        MANIFEST, tmp_path, ydl_factory=_factory(sink), validator=lambda url: url
    )
    assert sink[0]["concurrent_fragment_downloads"] == 6


def test_request_beats_env_on_the_downloader_path(
    tmp_path, quiet_downloader, monkeypatch
):
    monkeypatch.setenv(ENV_VAR, "8")
    sink: list[dict] = []
    download_media(
        MANIFEST,
        tmp_path,
        hls_concurrency=2,
        ydl_factory=_factory(sink),
        validator=lambda url: url,
    )
    assert sink[0]["concurrent_fragment_downloads"] == 2


@pytest.mark.parametrize("raw", ["0", "9", "-1", "abc"])
def test_invalid_env_falls_back_to_three_and_audits(
    raw, tmp_path, quiet_downloader, monkeypatch, caplog
):
    monkeypatch.setenv(ENV_VAR, raw)
    sink: list[dict] = []
    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        download_media(
            MANIFEST, tmp_path, ydl_factory=_factory(sink), validator=lambda url: url
        )
    assert sink[0]["concurrent_fragment_downloads"] == 3, "环境变量越界只能回落，不能中断下载"
    lines = [record.getMessage() for record in caplog.records if record.name == "bgv.engine"]
    assert any(
        line.startswith("hls_concurrency_invalid source=env") for line in lines
    ), f"回落必须留审计行：{lines}"


# ---------------------------------------------------------------------------
# 两轨下载：每条流各拿一份完整预算（总并发 = 2 × 配置值）
# ---------------------------------------------------------------------------


def test_two_track_download_gives_each_stream_its_own_budget(tmp_path, quiet_downloader):
    """语义锁死：``hls_concurrency=3`` → 音频流 3 + 视频流 3。

    不是「两条轨合计 3」——分离轨是两条独立的流，任何一条流自身都不该被
    另一条的进度挤掉槽位。
    """
    sink: list[dict] = []
    download_tracks(
        MANIFEST,
        tmp_path,
        video_expression="bestvideo",
        audio_expression="bestaudio",
        hls_concurrency=3,
        ydl_factory=_factory(sink),
        validator=lambda url: url,
    )
    budgets = [options["concurrent_fragment_downloads"] for options in sink]
    assert budgets == [3, 3], f"音频、视频各 3（总 6），实测 {budgets}"


def test_two_track_download_honours_a_non_default_budget(tmp_path, quiet_downloader):
    sink: list[dict] = []
    download_tracks(
        MANIFEST,
        tmp_path,
        video_expression="bestvideo",
        audio_expression="bestaudio",
        hls_concurrency=5,
        ydl_factory=_factory(sink),
        validator=lambda url: url,
    )
    assert [options["concurrent_fragment_downloads"] for options in sink] == [5, 5]


# ---------------------------------------------------------------------------
# 直链守卫：单连接顺序拉取，禁止分块并发
# ---------------------------------------------------------------------------


def test_direct_download_stays_a_single_sequential_stream(tmp_path):
    """禁止改为分块并发，原因见 PROTOCOL「流量特征边界」。

    判据：正文 GET 只有一次；除 ``resolve_redirects`` 的 ``bytes=0-0`` 探针外，
    不存在任何区间 Range。分块并发一定会留下多条 ``bytes=x-y``。
    """
    body = b"0123456789abcdef" * 64
    ranges: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        raw = request.headers.get("range", "")
        ranges.append(raw)
        if raw == "bytes=0-0":
            return httpx.Response(
                206,
                headers={
                    "Content-Range": f"bytes 0-0/{len(body)}",
                    "Content-Type": "video/mp4",
                    "Accept-Ranges": "bytes",
                },
                content=body[:1],
            )
        return httpx.Response(
            200,
            headers={
                "Content-Type": "video/mp4",
                "Content-Length": str(len(body)),
                "Accept-Ranges": "bytes",
            },
            content=body,
        )

    path = download_direct(
        "http://8.8.8.8/v/clip.mp4",
        tmp_path,
        task_id="direct-guard",
        transport=httpx.MockTransport(handler),
        validator=lambda url: url,
    )

    assert path.read_bytes() == body
    assert ranges.count("") == 1, f"正文只能拉一次，实测 {ranges}"
    chunked = [r for r in ranges if r and r != "bytes=0-0"]
    assert chunked == [], f"直链不许出现分块 Range（禁止改成并发多 Range）：{chunked}"


def test_direct_download_guard_matches_no_parallel_range_regex():
    """把「分块并发」的指纹写成正则常量，后人一看就知道要防什么。"""
    parallel_chunk = re.compile(r"^bytes=\d+-\d+$")
    assert parallel_chunk.match("bytes=0-1023")
    assert not parallel_chunk.match("bytes=1024-")
    assert not parallel_chunk.match("")


# ---------------------------------------------------------------------------
# API 入口：越界 → 422 + 明确文案；合法值落在 TaskRecord 上
# ---------------------------------------------------------------------------


def _seed_awaiting_task() -> str:
    """直接种一条 awaiting_selection 任务。

    走 ``POST /v1/tasks`` 要先把桩状态机推到待选，与本轮要验的东西无关；
    这里只关心「HTTP 请求体 → 端点 → 配置对象」这一段。
    """
    record = _record(_hls_sniff())
    record.task.status = TaskStatus.AWAITING_SELECTION
    main.TASKS[record.task.id] = record
    return record.task.id


@pytest.mark.parametrize("bad", [0, 9, -1, "abc"])
def test_api_rejects_invalid_concurrency_with_clear_message(bad, client):
    task_id = _seed_awaiting_task()

    response = client.post(
        f"/v1/tasks/{task_id}/download",
        json={"formatSelector": {"strategy": "best"}, "hlsConcurrency": bad},
    )

    assert response.status_code == 422, response.text
    error = response.json()["error"]
    assert error["code"] == "VALIDATION_ERROR"
    assert error["message"] == "hlsConcurrency 必须是 1~8 的整数"


@pytest.mark.parametrize(
    "bad",
    [
        pytest.param({}, id="object"),
        pytest.param([], id="array"),
        pytest.param(True, id="bool"),
        pytest.param("abc", id="string"),
    ],
)
def test_api_rejects_non_integer_types_before_they_reach_the_downloader(bad, client):
    """``Optional[Any]`` 放行了非 int 类型：四类都必须被 ``_is_valid()`` 挡在下游之前。

    schema 宽松是为了让越界值拿到统一 422 文案（而不是 Pydantic 的通用错误），
    代价是对象 / 数组 / 布尔也能进门。这里逐个确认它们没有漏进 ``TaskRecord``、
    没有推进状态机，四种拿到的是同一句 422。
    """
    task_id = _seed_awaiting_task()

    response = client.post(
        f"/v1/tasks/{task_id}/download",
        json={"formatSelector": {"strategy": "best"}, "hlsConcurrency": bad},
    )

    assert response.status_code == 422, response.text
    error = response.json()["error"]
    assert error["code"] == "VALIDATION_ERROR"
    assert error["message"] == "hlsConcurrency 必须是 1~8 的整数"
    record = main.TASKS[task_id]
    assert record.hls_concurrency is None, "非法值不许写进 TaskRecord"
    assert record.task.status == TaskStatus.AWAITING_SELECTION, "非法值不许推进下载状态"


@pytest.mark.parametrize("value", [1, 8])
def test_api_accepts_the_range_boundaries(value, client):
    task_id = _seed_awaiting_task()

    response = client.post(
        f"/v1/tasks/{task_id}/download",
        json={"formatSelector": {"strategy": "best"}, "hlsConcurrency": value},
    )

    assert response.status_code in (200, 202), response.text
    assert main.TASKS[task_id].hls_concurrency == value


def test_api_omitting_the_field_records_the_default(client):
    """缺失 → 3，且这个 3 也落在配置对象上，不是只在底层才补。"""
    task_id = _seed_awaiting_task()

    response = client.post(
        f"/v1/tasks/{task_id}/download", json={"formatSelector": {"strategy": "best"}}
    )

    assert response.status_code in (200, 202), response.text
    assert main.TASKS[task_id].hls_concurrency == 3


def test_download_request_omits_the_field_as_none():
    """Android 未配置时不发这个键：引擎侧「省略」必须解析成 None，不能 422。

    ``encodeDefaults=false`` 下「省略」与「传 null」是两件事；这里在 Pydantic
    模型上钉死前半段，端点回落在上面两条里验。
    """
    request = DownloadRequest(
        formatSelector=FormatSelector(strategy=SelectionStrategy.BEST)
    )
    assert request.hlsConcurrency is None
    assert "hlsConcurrency" not in request.model_dump(exclude_none=True)


def test_api_omission_falls_through_to_env(client, monkeypatch):
    """优先级「请求 > 环境变量 > 默认」在端点层同样成立，不只是下载器里成立。"""
    monkeypatch.setenv(ENV_VAR, "6")
    task_id = _seed_awaiting_task()

    response = client.post(
        f"/v1/tasks/{task_id}/download", json={"formatSelector": {"strategy": "best"}}
    )

    assert response.status_code in (200, 202), response.text
    assert main.TASKS[task_id].hls_concurrency == 6


# ---------------------------------------------------------------------------
# 配置对象 → 下载器：这一层断了，上面所有断言都只是安慰
# ---------------------------------------------------------------------------


def _record(sniff: SniffResult) -> main.TaskRecord:
    task = Task(
        id="task-hls",
        sourceUrl="http://8.8.8.8/watch",
        mode=TaskMode.DOWNLOAD,
        cookiePolicy=CookiePolicy.NONE,
        outputDir="C:\\tmp\\bgv-hls",
        status=TaskStatus.AWAITING_SELECTION,
        formatSelector=FormatSelector(strategy=SelectionStrategy.BEST),
        createdAt="2026-11-08T00:00:00Z",
        updatedAt="2026-11-08T00:00:00Z",
    )
    return main.TaskRecord(task, "normal", sniff)


def _hls_sniff() -> SniffResult:
    return SniffResult(
        taskId="task-hls",
        mediaUrl=MANIFEST,
        type=MediaStreamType.HLS,
        container="m3u8",
        estimatedQuality=EstimatedQuality(height=0, bitrateKbps=0, label="unknown"),
        drmDetected=False,
        discoveredAt="2026-11-08T00:00:00Z",
    )


def test_download_real_forwards_the_recorded_value(monkeypatch, tmp_path):
    monkeypatch.setattr(main, "ENGINE_MODE", "real")
    record = _record(_hls_sniff())
    # 生产链路里 _begin_download 先置 downloading；这里直接调 _download_real，
    # 夹具必须自己摆到同一状态，否则收尾的 downloading → done 会被状态机拒。
    record.task.status = TaskStatus.DOWNLOADING
    record.hls_concurrency = 5

    captured: dict[str, object] = {}

    def _fake_download(media_url, output_dir, *, task_id="unknown", **kwargs):
        captured.update(kwargs)
        path = tmp_path / "out.mp4"
        path.write_bytes(b"x" * 16)
        return path

    monkeypatch.setattr(downloader_service, "download_media", _fake_download)
    main._download_real(record)

    assert record.task.status == TaskStatus.DONE
    assert captured.get("hls_concurrency") == 5, (
        f"配置对象上的值必须原样交给下载器，实测 {captured}"
    )


def test_download_real_leaves_the_default_to_one_place(monkeypatch, tmp_path):
    """重启恢复的任务没有配置对象值 → 传 None 让下载器回落，而不是在这儿再写一个 3。"""
    monkeypatch.setattr(main, "ENGINE_MODE", "real")
    record = _record(_hls_sniff())
    record.task.status = TaskStatus.DOWNLOADING
    assert record.hls_concurrency is None, "TaskRecord 默认必须为 None（缺失态）"

    captured: dict[str, object] = {}

    def _fake_download(media_url, output_dir, *, task_id="unknown", **kwargs):
        captured.update(kwargs)
        path = tmp_path / "out.mp4"
        path.write_bytes(b"x" * 16)
        return path

    monkeypatch.setattr(downloader_service, "download_media", _fake_download)
    main._download_real(record)

    assert captured.get("hls_concurrency") is None

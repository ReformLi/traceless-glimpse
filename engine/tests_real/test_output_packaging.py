"""4b-4 验收 5/6：产物封装名由终点响应的 ``Content-Type`` 决定，纯音频不落 ``.mp4``。

命名信号只有这一个来源：嗅探侧的 ``container`` 本来就是从 URL 后缀推出来的，
拿它当第二信号是循环论证；``Content-Type`` 由 :func:`resolve_redirects` 那次
``Range: bytes=0-0`` 探测顺带取回，不额外发请求。
"""
from __future__ import annotations

import json
import logging
import shutil
import subprocess

import httpx
import pytest

import services.redirect as redirect_service
from security import SecurityError
from services.downloader import download_direct
from testsupport.media_fixtures import ffmpeg_available, generate_all
from testsupport.media_server import MediaServer

pytestmark = pytest.mark.real

AUDIO_BODY = (
    b"\x00\x00\x00\x1c" + b"ftyp" + b"M4A " + b"\x00\x00\x02\x00" + b"\x00" * 60_000 + b"\xff" * 4_000
)
VIDEO_BODY = b"\x00\x00\x00\x20" + b"ftyp" + b"isom" + b"\x00" * 64_000


@pytest.fixture(scope="module")
def assets(tmp_path_factory):
    assert ffmpeg_available(), "本机无 ffmpeg：-m real 无法生成测试素材"
    dest = tmp_path_factory.mktemp("bgv-packaging")
    generate_all(dest)
    return dest


@pytest.fixture(scope="module")
def server(assets):
    with MediaServer(assets, generate=False) as srv:
        yield srv


def _transport(content_type: str | None, body: bytes) -> httpx.MockTransport:
    """探测请求带出 ``Content-Type``，正式请求回整块字节。"""

    def handler(request: httpx.Request) -> httpx.Response:
        probe = request.headers.get("range") == "bytes=0-0"
        headers = {"content-length": str(len(body))}
        if content_type:
            headers["content-type"] = content_type
        return httpx.Response(200, content=b"" if probe else body, headers=headers)

    return httpx.MockTransport(handler)


def _fetch(tmp_path, url: str, content_type: str | None, body: bytes, *, task_id: str):
    return download_direct(
        url,
        tmp_path,
        transport=_transport(content_type, body),
        task_id=task_id,
    )


def test_audio_content_type_corrects_mp4_url_to_m4a(tmp_path, caplog):
    """URL 后缀写 ``.mp4``、响应声明 ``audio/mp4``：产物必须是 ``.m4a``。"""
    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        path = _fetch(
            tmp_path,
            "http://8.8.8.8/media/track.mp4",
            "audio/mp4",
            AUDIO_BODY,
            task_id="pack-1",
        )

    assert path == tmp_path / "track.m4a"
    assert not (tmp_path / "track.mp4").exists()
    assert path.read_bytes()[4:8] == b"ftyp"
    assert path.read_bytes()[8:12] == b"M4A "
    audit = [
        record.getMessage()
        for record in caplog.records
        if record.name == "bgv.engine"
    ]
    assert (
        "output_ext_corrected task_id=pack-1 from=mp4 to=m4a reason=audio_content_type"
        in audit
    )


def test_video_content_type_keeps_url_suffix(tmp_path, caplog):
    """视频内容 + 视频后缀：不改名，也不记校正日志。"""
    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        path = _fetch(
            tmp_path,
            "http://8.8.8.8/media/track.mp4",
            "video/mp4",
            VIDEO_BODY,
            task_id="pack-2",
        )

    assert path == tmp_path / "track.mp4"
    assert path.read_bytes()[8:12] == b"isom"
    assert not [
        line
        for line in (r.getMessage() for r in caplog.records)
        if line.startswith("output_ext_corrected")
    ]


def test_audio_suffix_is_not_renamed_by_video_content_type(tmp_path):
    """校正是单向的：只把音频内容从视频容器名里救出来，不反向改音频后缀。

    ``.m4a`` 本来就是白名单内的合法封装，把它改成 ``.mp4`` 只会更糟。
    """
    path = _fetch(
        tmp_path,
        "http://8.8.8.8/media/track.m4a",
        "video/mp4",
        AUDIO_BODY,
        task_id="pack-3",
    )
    assert path == tmp_path / "track.m4a"


def test_extension_less_audio_url_named_from_content_type(tmp_path, caplog):
    """URL 没有后缀时，Content-Type 是唯一的命名依据。"""
    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        path = _fetch(
            tmp_path,
            "http://8.8.8.8/media/stream",
            "audio/aac",
            AUDIO_BODY,
            task_id="pack-4",
        )

    assert path == tmp_path / "stream.aac"
    assert [
        line
        for line in (r.getMessage() for r in caplog.records)
        if line.startswith("output_ext_corrected")
    ] == ["output_ext_corrected task_id=pack-4 from=none to=aac reason=audio_content_type"]


def test_unnamed_url_without_known_type_is_rejected(tmp_path):
    """认不出类型又没有后缀：不猜封装名，交给 ``validate_output_filename`` 拒绝。"""
    with pytest.raises(SecurityError):
        _fetch(
            tmp_path,
            "http://8.8.8.8/media/stream",
            "application/octet-stream",
            AUDIO_BODY,
            task_id="pack-5",
        )
    assert list(tmp_path.iterdir()) == []


def test_content_type_with_parameters_still_matches(tmp_path):
    """``audio/mp4; codecs=mp4a.40.2`` 这类带参数的头同样认得（去掉参数再查表）。"""
    path = _fetch(
        tmp_path,
        "http://8.8.8.8/media/track.mp4",
        "audio/mp4; codecs=mp4a.40.2",
        AUDIO_BODY,
        task_id="pack-6",
    )
    assert path == tmp_path / "track.m4a"


def test_real_audio_behind_mp4_url_lands_as_m4a(server, tmp_path, monkeypatch):
    """真素材过真服务器：``/media/audio_as_mp4.mp4`` 的产物是能被 ffprobe 认出的音频。

    回环地址会被生产校验器拒绝，这里在 redirect 模块注入放行（与 ``validator``
    注入同源）；生产默认仍是统一校验器。
    """
    monkeypatch.setattr(redirect_service, "validate_outbound_url", lambda url: url)
    path = download_direct(
        server.base_url + "/media/audio_as_mp4.mp4",
        tmp_path,
        task_id="pack-real-1",
        validator=lambda url: url,
    )

    assert path == tmp_path / "audio_as_mp4.m4a"
    head = path.read_bytes()[:16]
    assert head[4:8] == b"ftyp", f"前 16 字节应当是 ftyp box：{head!r}"
    assert head[8:12] == b"M4A ", f"major_brand 必须是 M4A：{head!r}"
    assert path.suffix == ".m4a" and not (tmp_path / "audio_as_mp4.mp4").exists()

    ffprobe = shutil.which("ffprobe")
    assert ffprobe, "本机无 ffprobe"
    proc = subprocess.run(
        [ffprobe, "-v", "error", "-show_streams", "-of", "json", str(path)],
        capture_output=True,
        text=True,
    )
    streams = json.loads(proc.stdout or "{}").get("streams") or []
    assert streams and all(item.get("codec_type") == "audio" for item in streams), (
        f"产物不是纯音频：{[(s.get('codec_type')) for s in streams]}"
    )

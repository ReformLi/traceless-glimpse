"""阶段 1 自检：本地测试媒体服务器路由与素材生成。

不依赖 playwright / 引擎；仅需本机 ffmpeg 生成素材（缺失时整文件跳过）。
"""
from __future__ import annotations

import json
from urllib.error import HTTPError
from urllib.request import HTTPRedirectHandler, Request, build_opener

import pytest

from testsupport.media_fixtures import ffmpeg_available, generate_all
from testsupport.media_server import MediaServer

pytestmark = pytest.mark.real


class _NoRedirect(HTTPRedirectHandler):
    """禁止 urllib 自动跟随 302，便于断言 Location 头。"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


@pytest.fixture(scope="module")
def assets(tmp_path_factory):
    # 显式失败而非隐式 skip：-m real 的前提就是本机具备 ffmpeg
    assert ffmpeg_available(), "本机无 ffmpeg：-m real 无法生成测试素材"
    dest = tmp_path_factory.mktemp("bgv-media")
    generate_all(dest)
    return dest


@pytest.fixture(scope="module")
def server(assets):
    with MediaServer(assets, generate=False) as srv:
        yield srv


def _request(server, path, headers=None, follow_redirects=False):
    req = Request(server.base_url + path, headers=headers or {})
    opener = build_opener() if follow_redirects else build_opener(_NoRedirect)
    try:
        with opener.open(req, timeout=5) as rsp:
            return rsp.status, dict(rsp.headers), rsp.read()
    except HTTPError as exc:
        return exc.code, dict(exc.headers), exc.read()


def test_assets_generated(assets):
    """素材齐备且非空（含 HLS 切片与两个 DRM manifest）。"""
    expected = [
        "media/sample.mp4",
        "media/video_only.mp4",
        "media/audio_only.m4a",
        "media/hls/master.m3u8",
        "media/drm/widevine.m3u8",
        "media/drm/aes128.m3u8",
        "media/evil.exe",
        "page.html",
    ]
    for rel in expected:
        path = assets / rel
        assert path.is_file(), f"缺少素材 {rel}"
        assert path.stat().st_size > 0, f"素材为空 {rel}"
    assert (assets / "media" / "hls" / "seg0.ts").is_file()


def test_sample_mp4_full_body(server, assets):
    status, headers, body = _request(server, "/media/sample.mp4")
    assert status == 200
    assert headers["Content-Type"] == "video/mp4"
    assert headers["Accept-Ranges"] == "bytes"
    assert len(body) == (assets / "media" / "sample.mp4").stat().st_size


def test_range_request_returns_206(server, assets):
    total = (assets / "media" / "sample.mp4").stat().st_size
    status, headers, body = _request(server, "/media/sample.mp4", {"Range": "bytes=0-1023"})
    assert status == 206
    assert headers["Content-Range"] == f"bytes 0-1023/{total}"
    assert len(body) == 1024


def test_no_range_route_downgrades_to_full(server, assets):
    """Accept-Ranges 缺失时（本路由），Range 请求降级为整文件 200。"""
    total = (assets / "media" / "sample.mp4").stat().st_size
    status, headers, body = _request(server, "/media/no-range.mp4", {"Range": "bytes=0-1023"})
    assert status == 200
    assert "Accept-Ranges" not in headers
    assert len(body) == total


def test_hls_playlist_and_segment(server):
    status, _, body = _request(server, "/media/hls/master.m3u8")
    assert status == 200
    text = body.decode("utf-8")
    assert text.startswith("#EXTM3U")
    assert "seg0.ts" in text

    status, _, seg = _request(server, "/media/hls/seg0.ts")
    assert status == 200
    assert len(seg) > 0


def test_drm_manifest_carries_widevine_keyformat(server):
    status, _, body = _request(server, "/media/drm/widevine.m3u8")
    assert status == 200
    text = body.decode("utf-8")
    assert "#EXT-X-KEY" in text
    assert "METHOD=SAMPLE-AES" in text
    assert 'KEYFORMAT="urn:uuid:edef8ba9' in text


def test_aes128_manifest_is_not_drm(server):
    """仅 AES-128 的普通加密：没有 KEYFORMAT，三态判定应判「非 DRM」。"""
    status, _, body = _request(server, "/media/drm/aes128.m3u8")
    assert status == 200
    text = body.decode("utf-8")
    assert "METHOD=AES-128" in text
    assert "KEYFORMAT" not in text


def test_redirect_local_followable(server):
    status, headers, _ = _request(server, "/r302-local")
    assert status == 302
    assert headers["Location"] == "/media/sample.mp4"

    status, _, body = _request(server, "/r302-local", follow_redirects=True)
    assert status == 200
    assert len(body) > 0


@pytest.mark.parametrize(
    ("path", "expected_location"),
    [
        ("/r302-private", "http://127.0.0.1:9/private"),
        ("/r302-private10", "http://10.0.0.1/private"),
        ("/r302-metadata", "http://169.254.169.254/latest/meta-data/"),
    ],
)
def test_redirect_targets_for_ssrf_cases(server, path, expected_location):
    status, headers, _ = _request(server, path)
    assert status == 302
    assert headers["Location"] == expected_location


def test_whoami_echoes_headers_for_isolation_evidence(server):
    """隔离性证据入口：Cookie 原样回显；无 Cookie 时必须为空串。"""
    status, _, body = _request(
        server,
        "/whoami",
        {"Cookie": "sid=cookie-from-page-a", "Referer": "http://example.test/from"},
    )
    assert status == 200
    payload = json.loads(body)
    assert payload["cookie"] == "sid=cookie-from-page-a"
    assert payload["referer"] == "http://example.test/from"
    assert payload["range"] == ""

    status, _, body = _request(server, "/whoami")
    assert status == 200
    assert json.loads(body)["cookie"] == ""


def test_evil_exe_served_as_octet_stream(server):
    status, headers, body = _request(server, "/media/evil.exe")
    assert status == 200
    assert headers["Content-Type"] == "application/octet-stream"
    assert body.startswith(b"MZ")


def test_page_html_references_streams_and_sets_cookie(server):
    status, headers, body = _request(server, "/page.html")
    assert status == 200
    assert headers["Content-Type"].startswith("text/html")
    text = body.decode("utf-8")
    assert "/media/hls/master.m3u8" in text
    assert "/media/sample.mp4" in text
    # 每个任务用唯一 sid（?sid=...），用于隔离性断言
    assert "URLSearchParams" in text
    assert 'document.cookie = "sid=" + sid' in text


def test_unknown_media_path_returns_404(server):
    status, _, _ = _request(server, "/media/does-not-exist.mp4")
    assert status == 404


def test_path_traversal_rejected(server):
    status, _, _ = _request(server, "/media/../page.html")
    assert status == 404

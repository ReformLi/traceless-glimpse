"""阶段 3 真实链路：yt-dlp 解析本地媒体 + 下载 + ffprobe 完整性。

注：本地测试服务器在回环，生产校验器（SSRF）按设计拒绝；测试内通过
``validator=lambda url: url`` 注入放行，**生产默认仍是统一校验器**。
"""
from __future__ import annotations

import json
import logging
import shutil
import subprocess

import pytest

from services import downloader, formats
from testsupport.media_fixtures import ffmpeg_available, generate_all
from testsupport.media_server import MediaServer

pytestmark = pytest.mark.real


@pytest.fixture(scope="module")
def assets(tmp_path_factory):
    assert ffmpeg_available(), "本机无 ffmpeg：-m real 无法生成测试素材"
    dest = tmp_path_factory.mktemp("bgv-ytdlp")
    generate_all(dest)
    return dest


@pytest.fixture(scope="module")
def server(assets):
    with MediaServer(assets, generate=False) as srv:
        yield srv


def test_extract_formats_from_local_hls(server):
    """真实 yt-dlp 解析 HLS 播放列表 → 至少一个格式，URL 指向测试服务器。"""
    info = formats.extract_info(server.base_url + "/media/hls/master.m3u8")
    assert info.get("formats"), f"未解析出格式：keys={list(info)[:10]}"
    assert any(str(item.get("url", "")).startswith(server.base_url) for item in info["formats"])


def test_download_local_mp4_and_verify(server, tmp_path, caplog):
    """真实下载 + 非空 + ffprobe 可解析（M1 完整性标准）。"""
    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        path = downloader.download_media(
            server.base_url + "/media/sample.mp4",
            tmp_path,
            task_id="task-download",
            validator=lambda url: url,  # 仅测试放行回环；生产不注入
        )
    assert path.is_file()
    assert path.stat().st_size > 0

    # 约束 ⑥：download_complete 审计行只带 task_id + bytes，不得泄漏 URL/Cookie/UA
    audit = [
        record.getMessage()
        for record in caplog.records
        if record.getMessage().startswith("download_complete")
    ]
    assert audit, "未捕获到 download_complete 审计行"
    for line in audit:
        for token in ("http://", "https://", "Cookie", "cookie", "sid=", "Mozilla", server.base_url):
            assert token not in line, f"下载审计行泄漏 {token!r}：{line}"

    ffprobe = shutil.which("ffprobe")
    assert ffprobe, "本机无 ffprobe"
    proc = subprocess.run(
        [ffprobe, "-v", "error", "-show_format", "-of", "json", str(path)],
        capture_output=True,
        text=True,
    )
    data = json.loads(proc.stdout or "{}")
    assert float((data.get("format") or {}).get("duration") or 0) > 0


def test_download_rejects_blocked_url_before_network(tmp_path):
    """生产校验器下：私网直链在下载前即被拒（不创建任何文件）。"""
    from security import SecurityError

    with pytest.raises(SecurityError) as exc:
        downloader.download_media("http://10.0.0.1/x.mp4", tmp_path)
    assert exc.value.code == "SSRF_BLOCKED"
    assert list(tmp_path.iterdir()) == []

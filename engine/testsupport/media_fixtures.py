"""本地测试媒体素材生成（仅测试使用，生产路径禁止 import）。

用本机 ffmpeg 的 lavfi 源现场生成确定性小体积素材到临时目录，仓库不提交任何二进制。
生成一次后按文件存在性跳过（``force=True`` 强制重建）。

产物布局（相对于 assets 根）：
- ``media/sample.mp4``        视频 + 音频（3s，320x240，h264 + aac）
- ``media/video_only.mp4``    仅视频轨（供 FFmpeg 合并用例）
- ``media/audio_only.m4a``    仅音频轨（供 FFmpeg 合并用例）
- ``media/hls/master.m3u8``   切片后的 HLS 播放列表 + ``segN.ts``
- ``media/drm/widevine.m3u8`` 带 Widevine KEYFORMAT 的假 DRM manifest（不可下载）
- ``media/drm/aes128.m3u8``   仅 AES-128 的普通加密 manifest（非 DRM，用于三态判定）
- ``media/evil.exe``          非白名单扩展名样本（写入沙箱用例）
- ``page.html``               嗅探页（请求 m3u8 并写入 sid Cookie）
"""
from __future__ import annotations

import shutil
import subprocess
from pathlib import Path

# 假密钥 URI：只出现在 manifest 文本里，测试不会真的取密钥（不产生出站请求）
WIDEVINE_KEYFORMAT = "urn:uuid:edef8ba9-79ec-483b-86c7-1d2b1c8b0a9b"
FAKE_KEY_URI = "https://keys.example.com/drm.key"

_DRM_WIDEVINE = f"""#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:3
#EXT-X-MEDIA-SEQUENCE:0
#EXT-X-KEY:METHOD=SAMPLE-AES,URI="{FAKE_KEY_URI}",KEYFORMAT="{WIDEVINE_KEYFORMAT}",KEYFORMATVERSIONS="1"
#EXTINF:3.0,
seg0.ts
#EXT-X-ENDLIST
"""

_DRM_AES128 = f"""#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:3
#EXT-X-MEDIA-SEQUENCE:0
#EXT-X-KEY:METHOD=AES-128,URI="{FAKE_KEY_URI}"
#EXTINF:3.0,
seg0.ts
#EXT-X-ENDLIST
"""

_PAGE_HTML = """<!doctype html>
<html lang="zh-CN">
<head><meta charset="utf-8"><title>BGV 测试嗅探页</title></head>
<body>
<video id="v" src="/media/sample.mp4" preload="auto" muted></video>
<script>
  // 每个任务用唯一 sid 值（?sid=task-a / ?sid=task-b），用于证明上下文隔离：
  // 任务 B 的请求里若出现任务 A 的 sid 值即为串 Cookie
  var sid = new URLSearchParams(location.search).get("sid") || "cookie-default";
  document.cookie = "sid=" + sid + "; path=/";
  fetch("/media/hls/master.m3u8").then(function (r) { return r.text(); }).then(function (t) {
    window.__m3u8 = t;
  });
</script>
</body>
</html>
"""


def ffmpeg_available() -> bool:
    """本机是否可用 ffmpeg（测试素材生成的前置条件）。"""
    return shutil.which("ffmpeg") is not None


def _run(args: list[str]) -> None:
    proc = subprocess.run(args, capture_output=True, text=True)
    if proc.returncode != 0:
        raise RuntimeError("ffmpeg 执行失败：\n" + " ".join(args) + "\n" + proc.stderr[-800:])


def generate_all(dest: Path, force: bool = False) -> dict[str, Path]:
    """生成全部测试素材，返回相对名 -> 绝对路径。已存在则跳过（force 重建）。"""
    dest = Path(dest)
    media = dest / "media"
    hls_dir = media / "hls"
    drm_dir = media / "drm"
    for directory in (media, hls_dir, drm_dir):
        directory.mkdir(parents=True, exist_ok=True)

    sample = media / "sample.mp4"
    if force or not sample.exists():
        # 视频 + 音频（-shortest 保证时长 3s）
        _run([
            "ffmpeg", "-y",
            "-f", "lavfi", "-i", "testsrc=size=320x240:rate=10",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
            "-t", "3", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "64k", "-shortest",
            str(sample),
        ])
        # 仅视频轨
        _run([
            "ffmpeg", "-y",
            "-f", "lavfi", "-i", "testsrc=size=320x240:rate=10",
            "-t", "3", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
            str(media / "video_only.mp4"),
        ])
        # 仅音频轨
        _run([
            "ffmpeg", "-y",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
            "-t", "3", "-c:a", "aac", "-b:a", "64k",
            str(media / "audio_only.m4a"),
        ])
        # HLS：切片复用 sample.mp4 的编码
        _run([
            "ffmpeg", "-y", "-i", str(sample),
            "-c", "copy", "-f", "hls", "-hls_time", "1", "-hls_playlist_type", "vod",
            "-hls_segment_filename", str(hls_dir / "seg%d.ts"),
            str(hls_dir / "master.m3u8"),
        ])

    (drm_dir / "widevine.m3u8").write_text(_DRM_WIDEVINE, encoding="utf-8")
    (drm_dir / "aes128.m3u8").write_text(_DRM_AES128, encoding="utf-8")
    (media / "evil.exe").write_bytes(b"MZ\x90\x00BGV-test-not-a-real-binary")
    (dest / "page.html").write_text(_PAGE_HTML, encoding="utf-8")

    return {
        "sample_mp4": sample,
        "video_only_mp4": media / "video_only.mp4",
        "audio_only_m4a": media / "audio_only.m4a",
        "hls_playlist": hls_dir / "master.m3u8",
        "drm_widevine": drm_dir / "widevine.m3u8",
        "drm_aes128": drm_dir / "aes128.m3u8",
        "evil_exe": media / "evil.exe",
        "page_html": dest / "page.html",
    }

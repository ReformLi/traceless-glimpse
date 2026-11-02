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
- ``page-bulk.html``          嗅探页（唯一一条大文件直链 ``/media/bulk.mp4``，断流/续传取证）
- ``page-audio.html``         嗅探页（唯一一条伪装直链 ``/media/audio_as_mp4.mp4``，封装取证）
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
  // ?media_delay=5000 → 嗅探到的播放列表带上 ?delay_ms=，响应头之前变慢（真机取消取证）
  // ?media=slow → 改指 slow.m3u8：切片自带 ?chunk_delay_ms=，字节流按块变慢（4b-3 断流取证）
  var params = new URLSearchParams(location.search);
  var delay = params.get("media_delay") || "";
  var playlist = "/media/hls/" + (params.get("media") || "master") + ".m3u8";
  if (playlist.indexOf("master") !== -1 && delay) playlist += "?delay_ms=" + delay;
  fetch(playlist).then(function (r) { return r.text(); }).then(function (t) {
    window.__m3u8 = t;
  });
</script>
</body>
</html>
"""

# 4b-3 断流取证用：页面只有**一条单文件直链**（无 m3u8），嗅探归类为 mp4 →
# 走 download_direct；``?chunk_delay=N`` 会把 ``chunk_delay_ms=`` 拼到视频 URL 上，
# 让字节流按块变慢——真机有足够时间点取消，取消后字节必须停止增长才算断流成功。
_PAGE_BULK_HTML = """<!doctype html>
<html lang="zh-CN">
<head><meta charset="utf-8"><title>BGV 断流取证页（单文件直链）</title></head>
<body>
<script>
  var params = new URLSearchParams(location.search);
  var delay = params.get("chunk_delay") || "0";
  // ?no_range=1 → 透传进媒体 URL：路径不变（产物名不变，才命中同名 .part），
  // 只让服务器假装不支持 Range（4b-4 降级取证）
  var noRange = params.get("no_range") === "1" ? "&no_range=1" : "";
  var src = "/media/bulk.mp4?chunk_delay_ms=" + delay + noRange;
  var v = document.createElement("video");
  v.src = src;
  v.preload = "auto";
  v.muted = true;
  document.body.appendChild(v);
  // 请求头阶段只需 1 字节：确保 URL 被嗅探捕获，又不必整段下载
  fetch(src, { headers: { Range: "bytes=0-0" } }).catch(function () {});
</script>
</body>
</html>
"""

# 4b-4 封装取证用：页面只有**一条伪装成 .mp4 的纯音频直链**。嗅探按后缀归类为 mp4
# → 走 download_direct；服务器如实回 Content-Type: audio/mp4，产物必须被校正成 .m4a。
_PAGE_AUDIO_HTML = """<!doctype html>
<html lang="zh-CN">
<head><meta charset="utf-8"><title>BGV 纯音频封装取证页</title></head>
<body>
<script>
  var src = "/media/audio_as_mp4.mp4";
  var v = document.createElement("video");
  v.src = src;
  v.preload = "auto";
  v.muted = true;
  document.body.appendChild(v);
  fetch(src, { headers: { Range: "bytes=0-0" } }).catch(function () {});
</script>
</body>
</html>
"""

# 隔离性取证用：与 page.html 请求同一个 master.m3u8，但本页不写任何 Cookie。
# 任务 A（page.html?sid=task-a）之后，用全新上下文打开本页，
# 服务器侧 media_req 的 cookie_present 必须为 false —— 若上下文串用会带上 sid=task-a。
_PAGE_HTML_NOCOOKIE = """<!doctype html>
<html lang="zh-CN">
<head><meta charset="utf-8"><title>BGV 嗅探页（不写 Cookie）</title></head>
<body>
<video id="v" src="/media/sample.mp4" preload="auto" muted></video>
<script>
  var delay = new URLSearchParams(location.search).get("media_delay") || "";
  var playlist = "/media/hls/master.m3u8" + (delay ? "?delay_ms=" + delay : "");
  fetch(playlist).then(function (r) { return r.text(); }).then(function (t) {
    window.__m3u8 = t;
  });
</script>
</body>
</html>
"""


# Cookie 闭环取证用：页面响应带 `Set-Cookie: sid=good-cookie`（等同登录后的会话），
# 再请求受保护的直链。服务端与 WebView 的 CookieStore 各拿一份：
# 下载器只能从 CookieProvider（CookieManager）取，`headersHint` 里不许出现 Cookie。
_PAGE_COOKIE_HTML = """<!doctype html>
<html lang="zh-CN">
<head><meta charset="utf-8"><title>BGV Cookie 闭环取证页</title></head>
<body>
<script>
  // 页面自身也写一次，和服务端 Set-Cookie 双保险（值相同，幂等）
  document.cookie = "sid=good-cookie; path=/";
  var src = "/media/protected.mp4";
  var v = document.createElement("video");
  v.src = src;
  v.preload = "auto";
  v.muted = true;
  document.body.appendChild(v);
  fetch(src, { headers: { Range: "bytes=0-0" } }).catch(function () {});
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


def _slow_variant_manifest(hls_dir: Path, segment_delay_ms: int) -> Path:
    """把切片行改写成带 ``?chunk_delay_ms=`` 的 ``slow.m3u8``。

    yt-dlp 不会把播放列表的 query 传给切片，所以节流只能烧进 manifest 文本。
    源 manifest 优先用 bulk 切片（约 60 片，限速后能持续一分多钟，真机足够时间
    点取消），只有 bulk 素材不存在时才回落到 3s 的 master.m3u8。
    """
    source = hls_dir / "bulk.m3u8"
    if not source.is_file():
        source = hls_dir / "master.m3u8"
    master = source.read_text(encoding="utf-8")
    lines = []
    for line in master.splitlines():
        stripped = line.strip()
        if stripped and not stripped.startswith("#"):
            lines.append(f"{stripped}?chunk_delay_ms={segment_delay_ms}")
        else:
            lines.append(line)
    slow = hls_dir / "slow.m3u8"
    slow.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return slow


def generate_all(
    dest: Path, force: bool = False, bulk: bool = False, segment_delay_ms: int = 1500
) -> dict[str, Path]:
    """生成全部测试素材，返回相对名 -> 绝对路径。已存在则跳过（force 重建）。

    ``bulk=True`` 追加两件断流取证素材（默认不生成，避免拖慢常规 real 套件）：
    ``media/bulk.mp4``（约 4 MiB 单文件直链）与 ``media/hls/slow.m3u8``
    （切片带 ``?chunk_delay_ms=``，逐片限速）。
    """
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

    bulk_files: dict[str, Path] = {}
    if bulk:
        bulk_clip = media / "bulk.mp4"
        if force or not bulk_clip.exists():
            # 640x480 / 25fps / 500 kbps × 60s ≈ 3.8 MiB：按块限速后可流出十几秒
            _run([
                "ffmpeg", "-y",
                "-f", "lavfi", "-i", "testsrc=size=640x480:rate=25",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
                "-t", "60", "-c:v", "libx264", "-preset", "ultrafast", "-b:v", "500k",
                "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "64k", "-shortest",
                str(bulk_clip),
            ])
        # bulk 切成 HLS：分片多 → 按块限速后总时长足够真机点取消
        _run([
            "ffmpeg", "-y", "-i", str(bulk_clip),
            "-c", "copy", "-f", "hls", "-hls_time", "1", "-hls_playlist_type", "vod",
            "-hls_segment_filename", str(hls_dir / "bseg%d.ts"),
            str(hls_dir / "bulk.m3u8"),
        ])
        bulk_files = {
            "bulk_mp4": bulk_clip,
            "hls_bulk": hls_dir / "bulk.m3u8",
            "hls_slow": _slow_variant_manifest(hls_dir, segment_delay_ms),
        }

    (drm_dir / "widevine.m3u8").write_text(_DRM_WIDEVINE, encoding="utf-8")
    (drm_dir / "aes128.m3u8").write_text(_DRM_AES128, encoding="utf-8")
    (media / "evil.exe").write_bytes(b"MZ\x90\x00BGV-test-not-a-real-binary")
    (dest / "page.html").write_text(_PAGE_HTML, encoding="utf-8")
    (dest / "page-bulk.html").write_text(_PAGE_BULK_HTML, encoding="utf-8")
    (dest / "page-audio.html").write_text(_PAGE_AUDIO_HTML, encoding="utf-8")
    (dest / "page-nocookie.html").write_text(_PAGE_HTML_NOCOOKIE, encoding="utf-8")
    (dest / "page-cookie.html").write_text(_PAGE_COOKIE_HTML, encoding="utf-8")

    return {
        "sample_mp4": sample,
        "video_only_mp4": media / "video_only.mp4",
        "audio_only_m4a": media / "audio_only.m4a",
        "hls_playlist": hls_dir / "master.m3u8",
        "drm_widevine": drm_dir / "widevine.m3u8",
        "drm_aes128": drm_dir / "aes128.m3u8",
        "evil_exe": media / "evil.exe",
        "page_html": dest / "page.html",
        "page_bulk_html": dest / "page-bulk.html",
        "page_audio_html": dest / "page-audio.html",
        "page_nocookie_html": dest / "page-nocookie.html",
        "page_cookie_html": dest / "page-cookie.html",
        **bulk_files,
    }

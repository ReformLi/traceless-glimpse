"""HLS 进度兜底（M22）：分片总数探针 + 分片比外推。

背景：HLS 分片流没有 `Content-Length`，yt-dlp 的进度钩子在部分非标准清单上
既不给 `total_bytes` 也不给 `fragment_count`，引擎只能报 `percent=0`——
手机端看到的就是「一直 0%，然后突然已完成」。

这里的三个纯函数负责把「已下载字节 × 总片数 / 已完成片数」变成可用的总量，
让 percent 至少在下载过程中单调地动起来。
"""
from services import downloader


PLAYLIST = """#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:6
#EXTINF:6.006,
seg-1.ts
#EXTINF:6.006,
seg-2.ts
#EXTINF:4.004,
seg-3.ts
#EXT-X-ENDLIST
"""

MASTER = """#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=1280000,RESOLUTION=640x360
low/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=2560000,RESOLUTION=1280x720
high/index.m3u8
"""


def test_count_hls_segments_counts_extinf_only():
    assert downloader._count_hls_segments(PLAYLIST) == 3


def test_count_hls_segments_ignores_blank_and_comment_lines():
    noisy = "\n\n# 注释\n   #EXTINF:1.0,  \nseg.ts\n"
    assert downloader._count_hls_segments(noisy) == 1


def test_count_hls_segments_returns_zero_for_master_playlist():
    """master 清单只列子清单：数不出分片就不该硬编一个数。"""
    assert downloader._count_hls_segments(MASTER) == 0


def test_is_hls_url_requires_m3u8_path():
    assert downloader._is_hls_url("https://cdn.example.com/a/b.m3u8")
    assert downloader._is_hls_url("https://cdn.example.com/a/b.M3U8?token=x")
    assert not downloader._is_hls_url("https://cdn.example.com/a/b.mp4")
    # 查询串里带 m3u8 的转发地址不是清单本身
    assert not downloader._is_hls_url("https://cdn.example.com/proxy?url=https://x/a.m3u8")


def test_estimate_total_uses_probed_fragment_count():
    """清单没带 fragment_count 时，用探针拿到的总片数补上。"""
    hook = {"fragment_index": 2, "fragment_count": None}
    hint = {**hook, "fragment_count": 10}
    assert downloader._estimate_total_from_fragments(hint, downloaded=2_000_000) == 10_000_000


def test_estimate_total_gives_up_without_index():
    """没有 fragment_index 就没有分母，宁可返回 None 也不编一个百分比。"""
    assert downloader._estimate_total_from_fragments({"fragment_count": 10}, 1024) is None


def test_estimate_total_gives_up_before_first_fragment():
    assert (
        downloader._estimate_total_from_fragments(
            {"fragment_index": 1, "fragment_count": 10}, downloaded=0
        )
        is None
    )


# --- M22 收尾：加密 HLS 与「无后缀清单地址」 -------------------------------------
#
# 真机现场：嗅探到的 HLS 地址形如 `https://cdn/.../b3a240e2e4014f3eb0d8fe90d85607ee`
# （裸 token，没有 .m3u8），且清单是 AES-128 加密。只看后缀会漏掉全部这类源；
# 缺 pycryptodomex 时 yt-dlp 把下载委托给 ffmpeg，进度与取消一起失效。


def test_might_be_manifest_accepts_bare_token_path():
    """裸 token 路径必须进探针，否则加密 HLS 源永远拿不到分片数。"""
    assert downloader._might_be_manifest_url("https://cdn.example.com/content/abc123")
    assert downloader._might_be_manifest_url("https://cdn.example.com/a/b.m3u8?token=x")
    assert downloader._might_be_manifest_url("https://cdn.example.com/a/b.mpd")


def test_might_be_manifest_skips_plain_media_suffix():
    """带媒体后缀的直链不必多打一枪。"""
    assert not downloader._might_be_manifest_url("https://cdn.example.com/a/b.mp4")
    assert not downloader._might_be_manifest_url("https://cdn.example.com/a/b.ts")


def test_is_aes128_playlist_detects_key_line():
    assert downloader._is_aes128_playlist(
        '#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI="k.key"\n#EXTINF:6,\na.ts\n'
    )
    # SAMPLE-AES / 无 KEY 行都不算：这里只回答「要不要 pycryptodomex」。
    assert not downloader._is_aes128_playlist("#EXTM3U\n#EXTINF:6,\na.ts\n")


class _FakeManifestResponse:
    """只实现 iter_bytes：验证探针只读前 N 字节，不会把直链拖完。"""

    def __init__(self, payload: bytes, chunk: int = 8) -> None:
        self._payload = payload
        self._chunk = chunk
        self.consumed = 0

    def iter_bytes(self):
        for start in range(0, len(self._payload), self._chunk):
            piece = self._payload[start : start + self._chunk]
            self.consumed += len(piece)
            yield piece


def test_read_manifest_head_stops_at_limit():
    """裸 token 地址可能是上百 MB 的直链：读满上限就必须停。"""
    fake = _FakeManifestResponse(b"#EXTM3U\n" + b"A" * 5000)
    head = downloader._read_manifest_head(fake, limit=64)
    assert head.startswith("#EXTM3U")
    assert fake.consumed <= 72  # 一块的余量，不是 5009


def test_read_manifest_head_keeps_short_playlist_intact():
    fake = _FakeManifestResponse(b"#EXTM3U\n#EXTINF:6,\na.ts\n")
    head = downloader._read_manifest_head(fake, limit=1024)
    assert downloader._count_hls_segments(head) == 1

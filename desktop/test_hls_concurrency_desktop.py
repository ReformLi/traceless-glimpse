"""M25 · 桌面端 HLS 分段并发：常量对齐、越界本地拒绝、发送前二次校验。

桌面没有 Android 的审计通道，越界的表现是「UI 禁用开始下载 + 行内红字」以及
``EngineClient.start_download`` 在**发出 HTTP 之前**抛本地错误。这里绕开 Tk / socket，
只测纯逻辑与请求体装配。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

import hls_concurrency as desktop_cfg  # noqa: E402
from engine_client import EngineClient, EngineClientError  # noqa: E402

# 三端源文件路径：常量对齐测试读 Kotlin；另外两个供「纯内存改名 / 改值」护栏
# 验证读取源码后 exec，验证过程不写任何文件。
ENGINE_ROOT = Path(__file__).resolve().parent.parent / "engine"
ENGINE_SERVICE = ENGINE_ROOT / "services" / "hls_concurrency.py"
DESKTOP_SERVICE = Path(__file__).resolve().parent / "hls_concurrency.py"
KOTLIN_OBJECT = (
    Path(__file__).resolve().parent.parent
    / "android" / "app" / "src" / "main" / "java"
    / "com" / "hpu" / "browseguard" / "data" / "download" / "HlsConcurrency.kt"
)

# 引擎的纯校验模块可以直接 import（不启动 FastAPI、不碰网络/数据库），
# 因此引擎侧用运行时语义断言，而不是扫源码字符串。
if str(ENGINE_ROOT) not in sys.path:
    sys.path.append(str(ENGINE_ROOT))
from services import hls_concurrency as engine_cfg  # noqa: E402


# ---------------------------------------------------------------------------
# 三端常量必须语义相同，否则会出现「桌面存得下、引擎拒收」的悬空配置
# ---------------------------------------------------------------------------
PROBE_RANGE = range(-4, 20)


def _accepted_bounds(accept) -> tuple[int, int]:
    """不引用任何常量名：从「哪些整数被接受」反推闭区间上下界。"""
    accepted = [v for v in PROBE_RANGE if accept(v)]
    assert accepted, "校验函数没有接受任何整数"
    return min(accepted), max(accepted)


def _assert_semantic_alignment(desktop, engine, kotlin_text: str) -> None:
    """只按语义/数值比对：常量改名字不应变红，数值被改必须变红。

    ``desktop`` / ``engine`` 可以是模块，也可以是 exec 出来的内存命名空间，
    这样「改名 / 改数值」的护栏验证不需要写任何文件。
    """
    # 桌面：默认值与闭区间都由行为反推，不查具体常量名。
    assert desktop.from_stored(None) == 3, "桌面默认值不是 3"
    assert _accepted_bounds(lambda v: desktop.parse(str(v)) is not None) == (1, 8), (
        "桌面闭区间不是 1~8"
    )

    # 引擎：import 后直接跑运行时校验；没有任何变量名/正则参与。
    assert engine.resolve(None, env={}) == 3, "引擎默认值不是 3"

    def engine_accepts(value: int) -> bool:
        try:
            engine.validate_request(value)
            return True
        except engine.HlsConcurrencyError:
            return False

    assert _accepted_bounds(engine_accepts) == (1, 8), "引擎闭区间不是 1~8"

    # Android（Kotlin 无法 import）：正则只锁「任意 val 声明 = 数值」，不锁标识符。
    # 常量改叫 DEFAULT_FANOUT / LOWER_BOUND 都照样匹配；数值改成 4 则立刻失配。
    for value in (3, 1, 8):
        assert re.search(
            rf"^\s*(?:const\s+)?val\s+[A-Za-z_][A-Za-z_0-9]*\s*(?::\s*Int)?\s*=\s*{value}\b",
            kotlin_text,
            re.M,
        ), f"Android 侧缺少数值 {value} 的常量声明"


def test_desktop_constants_match_engine_and_android():
    _assert_semantic_alignment(
        desktop_cfg, engine_cfg, KOTLIN_OBJECT.read_text(encoding="utf-8")
    )


def test_field_name_is_the_protocol_key():
    assert desktop_cfg.FIELD == "hlsConcurrency"


# ---------------------------------------------------------------------------
# 唯一校验函数：parse 严格，from_stored 回落
# ---------------------------------------------------------------------------
@pytest.mark.parametrize("raw, expected", [
    (1, 1), (3, 3), (8, 8),
    ("1", 1), (" 6 ", 6), ("08", 8),
])
def test_valid_values_parse(raw, expected):
    assert desktop_cfg.parse(raw) == expected


@pytest.mark.parametrize("raw", [0, 9, -1, 100, "0", "9", "-1", "abc", "3.5", "", " ", None, 3.5, True])
def test_invalid_values_are_refused_not_clamped(raw):
    assert desktop_cfg.parse(raw) is None


@pytest.mark.parametrize("raw", [None, "", "abc", 0, 9, -1])
def test_missing_or_corrupt_stored_value_falls_back_to_default(raw):
    assert desktop_cfg.from_stored(raw) == 3


# ---------------------------------------------------------------------------
# 链路：合法值进请求体；越界根本不发 HTTP
# ---------------------------------------------------------------------------
class _RecordingClient(EngineClient):
    def __init__(self) -> None:
        self.calls: list[tuple[str, str, dict | None, tuple[int, ...]]] = []
        self.reply = {"id": "t1", "status": "downloading"}

    def _request(self, method, path, *, body=None, allowed=(200,)):  # noqa: ANN001
        self.calls.append((method, path, body, tuple(allowed)))
        return self.reply


def _client() -> _RecordingClient:
    return _RecordingClient()


SELECTOR = {"strategy": "max_height", "maxHeight": 1080}


def test_valid_concurrency_is_sent_with_the_protocol_key():
    client = _client()

    client.start_download("t1", SELECTOR, hls_concurrency=6)

    assert len(client.calls) == 1
    _, path, body, _ = client.calls[0]
    assert path == "/v1/tasks/t1/download"
    assert body["hlsConcurrency"] == 6
    assert body["formatSelector"] == SELECTOR
    # 已知边界：postActions 写死 merge，本轮不许顺手改它。
    assert body["postActions"] == [{"type": "merge"}]


def test_omitting_concurrency_sends_no_key_so_engine_can_fall_back():
    client = _client()

    client.start_download("t1", SELECTOR)

    assert len(client.calls) == 1
    assert "hlsConcurrency" not in client.calls[0][2]


@pytest.mark.parametrize("bad", [0, 9, -1, "abc", "3.5"])
def test_out_of_range_never_reaches_the_network(bad):
    client = _client()

    with pytest.raises(EngineClientError) as exc:
        client.start_download("t1", SELECTOR, hls_concurrency=bad)

    assert exc.value.code == "VALIDATION_ERROR"
    assert desktop_cfg.MESSAGE in exc.value.message
    assert client.calls == [], "越界必须先在本地拦下，不能让引擎 422 滞后返回"


def test_engine_validation_error_is_local_not_an_http_status():
    client = _client()

    with pytest.raises(EngineClientError) as exc:
        client.start_download("t1", SELECTOR, hls_concurrency=9)

    assert exc.value.status == 422  # 本地伪装成同类错误码，但确实没发请求
    assert client.calls == []


# ---------------------------------------------------------------------------
# 控制台：UI 层的行内红字 + 禁用「开始下载」，不依赖真实 Tk 控件
# ---------------------------------------------------------------------------
class _FakeVar:
    def __init__(self, value: str) -> None:
        self.value = value

    def get(self) -> str:
        return self.value


class _FakeLabel:
    def __init__(self) -> None:
        self.text = ""

    def configure(self, *, text=None, **_kw) -> None:
        if text is not None:
            self.text = text


class _FakeButton:
    def __init__(self) -> None:
        self.states: list[tuple[bool, str]] = []

    def configure(self, **kw) -> None:
        if "state" in kw:
            self.states.append((kw["state"] == "normal", kw.get("text", "")))

    def cget(self, _key: str) -> str:
        return "normal"


def _fake_console(raw: str):
    from download_console import DownloadConsole

    console = object.__new__(DownloadConsole)
    console.hls_concurrency_var = _FakeVar(raw)
    console.concurrency_hint = _FakeLabel()
    console.download_button = _FakeButton()
    console.sniff_button = _FakeButton()
    console.url_entry = _FakeButton()
    console.format_menu = _FakeButton()
    console.is_ready = True
    console._sniffing = False
    console._downloading = False
    console._has_formats = True
    console._finished = False
    return console


@pytest.mark.parametrize("raw", ["3", "1", "8", " 6 "])
def test_valid_ui_input_clears_the_error_and_enables_download(raw):
    console = _fake_console(raw)

    console._on_hls_concurrency_change()

    assert console._hls_concurrency_valid is True
    assert console.concurrency_hint.text == ""
    assert console.download_button.states[-1][0] is True


@pytest.mark.parametrize("raw", ["0", "9", "-1", "abc", "", "3.5"])
def test_invalid_ui_input_shows_red_hint_and_disables_download(raw):
    console = _fake_console(raw)

    console._on_hls_concurrency_change()

    assert console._hls_concurrency_valid is False
    assert "1~8" in console.concurrency_hint.text
    assert console.download_button.states[-1][0] is False


def test_console_reads_the_validated_value_not_the_raw_string():
    console = _fake_console("6")

    assert console.hls_concurrency() == 6
    assert _fake_console("9").hls_concurrency() is None

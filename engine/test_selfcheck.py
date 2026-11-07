"""M2 §6.5 隐私自检端点（桩模式，不触网、不读 Cookie）。"""
from __future__ import annotations

import sys

import main
from services import environment

REQUIRED_KEYS = {
    "listenAddress",
    "tlsEnabled",
    "authEnabled",
    "activeProxyIds",
    "cookiePolicyDefault",
    "outputDirAllowlist",
    "cacheDir",
    "cacheTtlHours",
    "cacheSizeBytes",
    "lastCleanupAt",
    "dependencyVersions",
}


def test_selfcheck_payload_shape(client):
    response = client.get("/v1/privacy/selfcheck")
    assert response.status_code == 200
    assert response.headers["X-Protocol-Version"] == "0.2"

    payload = response.json()
    assert set(payload) == REQUIRED_KEYS
    # listenAddress 是运行态事实，随启动参数变化；只校验 host:port 形态，不写死 IP。
    # 契约用例跑在 TestClient（未注入环境变量）下，端口仍应为默认 8730。
    address = payload["listenAddress"]
    host, sep, port = address.rpartition(":")
    assert isinstance(address, str) and host and sep
    assert port.isdigit() and 1 <= int(port) <= 65535
    assert address == environment.listen_address()
    assert payload["tlsEnabled"] is False
    assert payload["authEnabled"] is False
    assert payload["activeProxyIds"] == []
    assert payload["cookiePolicyDefault"] == "lazy"
    assert payload["outputDirAllowlist"] == main.OUTPUT_DIR_ALLOWLIST
    assert payload["cacheTtlHours"] == 48
    assert payload["lastCleanupAt"] is None
    assert isinstance(payload["cacheSizeBytes"], int)
    assert payload["cacheSizeBytes"] >= 0
    assert set(payload["dependencyVersions"]) == {"yt-dlp", "ffmpeg", "playwright"}
    assert all(isinstance(value, str) and value for value in payload["dependencyVersions"].values())


def test_selfcheck_reflects_listen_address_override(client, monkeypatch):
    """M15：桌面启动器绑定 0.0.0.0 时，自检必须回显实际 host/port 而非硬编码回环。"""
    monkeypatch.setenv("BGV_LISTEN_HOST", "0.0.0.0")
    monkeypatch.setenv("BGV_LISTEN_PORT", "8799")
    payload = client.get("/v1/privacy/selfcheck").json()
    assert payload["listenAddress"] == "0.0.0.0:8799"


def test_selfcheck_falls_back_on_invalid_listen_port(client, monkeypatch):
    monkeypatch.setenv("BGV_LISTEN_HOST", "0.0.0.0")
    monkeypatch.setenv("BGV_LISTEN_PORT", "not-a-port")
    payload = client.get("/v1/privacy/selfcheck").json()
    assert payload["listenAddress"] == "0.0.0.0:8730"


def test_selfcheck_reflects_cache_dir_override(client, monkeypatch, tmp_path):
    cache = tmp_path / "bgv-cache"
    (cache / "nested").mkdir(parents=True)
    (cache / "a.bin").write_bytes(b"123")
    (cache / "nested" / "b.bin").write_bytes(b"45678")
    monkeypatch.setenv("BGV_CACHE_DIR", str(cache))

    payload = client.get("/v1/privacy/selfcheck").json()
    assert payload["cacheDir"] == str(cache)
    assert payload["cacheSizeBytes"] == 8


def test_selfcheck_missing_cache_dir_counts_zero(client, monkeypatch, tmp_path):
    monkeypatch.setenv("BGV_CACHE_DIR", str(tmp_path / "does-not-exist"))
    payload = client.get("/v1/privacy/selfcheck").json()
    assert payload["cacheSizeBytes"] == 0


def test_ffmpeg_falls_back_when_missing(client, monkeypatch):
    monkeypatch.setattr(environment.shutil, "which", lambda _name: None)
    payload = client.get("/v1/privacy/selfcheck").json()
    assert payload["dependencyVersions"]["ffmpeg"] == "unavailable"


def test_playwright_falls_back_when_metadata_missing(client, monkeypatch):
    def _raise(_name):
        raise environment.importlib.metadata.PackageNotFoundError("playwright")

    monkeypatch.setattr(environment.importlib.metadata, "version", _raise)
    payload = client.get("/v1/privacy/selfcheck").json()
    assert payload["dependencyVersions"]["playwright"] == "unavailable"


def test_ytdlp_falls_back_when_import_fails(client, monkeypatch):
    monkeypatch.setitem(sys.modules, "yt_dlp.version", None)
    payload = client.get("/v1/privacy/selfcheck").json()
    assert payload["dependencyVersions"]["yt-dlp"] == "unavailable"


def test_selfcheck_does_not_read_cookie_file(client, monkeypatch, tmp_path):
    cookie = tmp_path / "cookies.txt"
    cookie.write_text("127.0.0.1\tFALSE\t/\tFALSE\t0\tsid\tsecret\n", encoding="utf-8")
    monkeypatch.setenv("BGV_COOKIE_FILE", str(cookie))
    response = client.get("/v1/privacy/selfcheck")
    assert response.status_code == 200
    assert "secret" not in response.text
    assert "BGV_COOKIE_FILE" not in response.text

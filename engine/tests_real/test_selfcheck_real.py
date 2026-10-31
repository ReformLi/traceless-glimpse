"""§6.5 自检在真实 venv 下报告已安装依赖版本（显式 -m real 才运行）。"""
from __future__ import annotations

import pytest

pytestmark = pytest.mark.real


def test_selfcheck_reports_installed_dependency_versions(client):
    payload = client.get("/v1/privacy/selfcheck").json()
    versions = payload["dependencyVersions"]
    assert versions["yt-dlp"] != "unavailable"
    assert versions["playwright"] != "unavailable"
    assert versions["ffmpeg"] != "unavailable", "real 套件前提：本机可执行 ffmpeg"

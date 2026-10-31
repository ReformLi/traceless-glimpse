"""阶段 5 验收 5：调试端点默认关闭，BGV_ENABLE_DEBUG_ENDPOINTS=1 才开放。"""
from __future__ import annotations

import pytest

import main

pytestmark = pytest.mark.real


def test_debug_endpoint_404_when_disabled(client, monkeypatch):
    monkeypatch.setattr(main, "DEBUG_ENDPOINTS_ENABLED", False)
    response = client.get("/v1/_debug/download-counter")
    assert response.status_code == 404
    assert response.json()["error"]["code"] == "NOT_FOUND"


def test_debug_endpoint_200_when_enabled(client, monkeypatch):
    monkeypatch.setattr(main, "DEBUG_ENDPOINTS_ENABLED", True)
    response = client.get("/v1/_debug/download-counter")
    assert response.status_code == 200
    assert response.json() == {"downloadCount": 0}

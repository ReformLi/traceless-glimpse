"""M22：桌面端「运行状态」卡的数据契约（`GET /v1/peers`）。

这一层只做三件事：数设备 / 记来源 / 报任务。它**不能**污染既有响应——
手机端 `BgvJson` 严格模式下多一个未知字段就整条解析失败，心跳当场变红。
所以这里的用例有两条硬线：
1. `/v1/health` 的键集合一字不差；
2. `/v1/tasks` 的响应体不加 `origin` 之类的新字段。
"""
import time

import main


def test_health_payload_shape_is_unchanged(client):
    """/v1/health 多一个字段，手机端心跳就会变红——键集合必须锁死。"""
    payload = client.get("/v1/health").json()
    assert set(payload) == {"status", "version", "protocolVersion", "uptimeSeconds"}


def test_peers_lists_live_devices_only(client, monkeypatch):
    now = time.time()
    monkeypatch.setattr(
        main, "PEERS", {"192.168.31.250": now - 2, "192.168.31.9": now - 40}
    )
    payload = client.get("/v1/peers").json()
    assert payload["windowSeconds"] == int(main.PEER_WINDOW_SECONDS)
    assert [p["address"] for p in payload["peers"]] == ["192.168.31.250"]
    assert payload["peers"][0]["lastSeenSecondsAgo"] >= 0


def test_peers_reports_task_origin_and_host(client, monkeypatch):
    """任务行要能说清「手机端 · IP · 域名」，且域名不带路径与查询串。"""
    created = client.post(
        "/v1/tasks",
        json={
            "sourceUrl": "https://clgw555.com/watch/abc?token=secret",
            "mode": "download",
            "cookiePolicy": "lazy",
            "outputDir": main.OUTPUT_DIR_ALLOWLIST[0],
        },
    ).json()
    monkeypatch.setattr(main, "TASK_ORIGIN", {created["id"]: "192.168.31.250"})

    payload = client.get("/v1/peers").json()
    row = next(t for t in payload["tasks"] if t["taskId"] == created["id"])
    assert row["origin"] == "192.168.31.250"
    assert row["sourceHost"] == "clgw555.com"
    assert "token" not in str(row)


def test_task_response_has_no_origin_field(client):
    """/v1/tasks 不能被 C 块污染：来源只在 /v1/peers 里说。"""
    created = client.post(
        "/v1/tasks",
        json={
            "sourceUrl": "https://example.com/watch?v=abc",
            "mode": "sniff",
            "cookiePolicy": "lazy",
            "outputDir": main.OUTPUT_DIR_ALLOWLIST[0],
        },
    ).json()
    assert "origin" not in created
    assert "clientAddress" not in created


def test_loopback_and_non_ip_are_not_counted(monkeypatch):
    """桌面端自己每秒轮询 health，不能把自己算成一台设备。"""
    monkeypatch.setattr(main, "PEERS", {})
    for address in ("127.0.0.1", "::1", "", "testclient"):
        main._record_peer(address)
    assert main.PEERS == {}


def test_reset_state_clears_presence():
    main.PEERS["192.168.31.250"] = time.time()
    main.TASK_ORIGIN["task-x"] = "192.168.31.250"
    main.reset_state()
    assert main.PEERS == {}
    assert main.TASK_ORIGIN == {}

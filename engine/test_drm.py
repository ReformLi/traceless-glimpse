"""DRM 零下载可观测（用户硬要求 5）+ §9.1 成功链路 + 状态机 / 幂等 / 409。"""

import logging

import main


def _create_task(client, source_url, mode="download"):
    rsp = client.post(
        "/v1/tasks",
        json={
            "sourceUrl": source_url,
            "mode": mode,
            "cookiePolicy": "lazy",
            "outputDir": "/home/user/Videos/BGV",
        },
    )
    assert rsp.status_code == 201
    return rsp.json()


def _get(client, task_id):
    rsp = client.get(f"/v1/tasks/{task_id}")
    assert rsp.status_code == 200
    return rsp.json()


def _advance_to_awaiting(client, task_id):
    body = _get(client, task_id)
    assert body["status"] == "awaiting_selection"
    return body


class TestDrmZeroDownload:
    def test_drm_rejected_and_zero_download(self, client, caplog, monkeypatch):
        """drmDetected=True → POST /download → 422 DRM_PROTECTED；
        caplog 断言 download_attempt 恰 1 次；_do_download 未被调用；计数器为 0。
        """
        task = _create_task(client, "https://example.com/drm/watch?v=xyz")
        _advance_to_awaiting(client, task["id"])

        calls = []
        monkeypatch.setattr(main, "_do_download", lambda record: calls.append(record.task.id))

        with caplog.at_level(logging.INFO, logger="bgv.engine"):
            rsp = client.post(
                f"/v1/tasks/{task['id']}/download",
                json={"formatSelector": {"strategy": "best"}},
            )

        assert rsp.status_code == 422
        assert rsp.json() == {
            "error": {
                "code": "DRM_PROTECTED",
                "category": "content",
                "retryable": False,
                "message": "该内容受 DRM 保护，不支持下载",
                "detail": {"drmSystem": "widevine"},
            }
        }
        # 审计行恰出现 1 次，且携带任务 ID（入口第一行，先于 DRM 判定）
        attempts = [
            r for r in caplog.records if r.getMessage().startswith("download_attempt")
        ]
        assert len(attempts) == 1
        assert f"download_attempt task_id={task['id']}" == attempts[0].getMessage()
        # 下载逻辑零执行：spy 未被调用 + 计数器为 0
        assert calls == []
        assert main.DOWNLOAD_COUNTER.count == 0

    def test_drm_task_sniff_result_flags(self, client):
        """DRM fixture 任务嗅探结果 drmDetected=True + drmSystem=widevine。"""
        task = _create_task(client, "https://example.com/drm/watch?v=xyz")
        rsp = client.get(f"/v1/tasks/{task['id']}/sniff-results")
        assert rsp.status_code == 200
        sniff = rsp.json()["items"][0]
        assert sniff["drmDetected"] is True
        assert sniff["drmSystem"] == "widevine"


class TestSuccessFlow:
    def test_protocol_9_1_full_flow(self, client):
        """§9.1 成功链路：创建 → 轮询 → formats → download → done。"""
        # 1) 创建任务
        task = _create_task(client, "https://example.com/watch?v=abc")
        assert task["status"] == "queued"
        assert task["mode"] == "download"
        assert "cookieFile" not in task and "browser" not in task
        # 2) 轮询 → awaiting_selection
        assert _get(client, task["id"])["status"] == "awaiting_selection"
        # 3) 查看格式
        rsp = client.get(f"/v1/tasks/{task['id']}/formats")
        assert rsp.status_code == 200
        body = rsp.json()
        assert body["taskId"] == task["id"]
        assert "137+140" in [f["formatId"] for f in body["items"]]
        # 4) 选择并下载 → 202 downloading
        rsp = client.post(
            f"/v1/tasks/{task['id']}/download",
            json={"formatSelector": {"strategy": "exact", "formatId": "137+140"}},
        )
        assert rsp.status_code == 202
        assert rsp.json()["status"] == "downloading"
        assert main.DOWNLOAD_COUNTER.count == 1
        # 5) 终态 done + percent=100.0 + error=None
        final = _get(client, task["id"])
        assert final["status"] == "done"
        assert final["progress"]["percent"] == 100.0
        assert final["error"] is None

    def test_sniff_mode_reaches_done(self, client):
        task = _create_task(client, "https://example.com/watch?v=abc", mode="sniff")
        assert _get(client, task["id"])["status"] == "done"

    def test_sniff_result_headers_sanitized(self, client):
        """正常任务 headersHint 已脱敏：无 cookie/authorization。"""
        task = _create_task(client, "https://example.com/watch?v=abc")
        rsp = client.get(f"/v1/tasks/{task['id']}/sniff-results")
        sniff = rsp.json()["items"][0]
        lowered = {k.lower() for k in sniff["headersHint"]}
        assert "cookie" not in lowered
        assert "authorization" not in lowered
        assert sniff["drmDetected"] is False
        assert sniff["type"] == "hls"


class TestAutoDownload:
    """偏差 2 修复后的追加用例：带 formatSelector 的 download 任务无需 POST /download。"""

    def _create_with_selector(self, client):
        rsp = client.post(
            "/v1/tasks",
            json={
                "sourceUrl": "https://example.com/watch?v=abc",
                "mode": "download",
                "formatSelector": {"strategy": "exact", "formatId": "137+140"},
                "cookiePolicy": "lazy",
                "outputDir": "/home/user/Videos/BGV",
            },
        )
        assert rsp.status_code == 201
        return rsp.json()

    def test_create_with_selector_goes_to_downloading(self, client):
        """① 带 formatSelector 创建 → 立即 queued；首次 GET 惰性到 downloading。"""
        task = self._create_with_selector(client)
        assert task["status"] == "queued"
        body = _get(client, task["id"])
        assert body["status"] == "downloading"
        # 可选字段以 null 显式输出（与 §3.1 示例一致：proxyId / error 为 null）
        assert body["formatSelector"]["strategy"] == "exact"
        assert body["formatSelector"]["formatId"] == "137+140"
        # 引擎自动领取下载：计数器递增 1 次，全程未调用 POST /download
        assert main.DOWNLOAD_COUNTER.count == 1

    def test_auto_download_reaches_done_without_post(self, client):
        """带 selector 创建 → 两次 GET 即达 done，全程无 POST /download。"""
        task = self._create_with_selector(client)
        assert _get(client, task["id"])["status"] == "downloading"
        final = _get(client, task["id"])
        assert final["status"] == "done"
        assert final["progress"]["percent"] == 100.0
        assert final["error"] is None
        assert main.DOWNLOAD_COUNTER.count == 1

    def test_post_download_on_downloading_conflicts(self, client):
        """② 对 downloading 任务 POST /download（不同 formatId）→ 409 TASK_STATE_CONFLICT。"""
        task = self._create_with_selector(client)
        assert _get(client, task["id"])["status"] == "downloading"
        rsp = client.post(
            f"/v1/tasks/{task['id']}/download",
            json={"formatSelector": {"strategy": "exact", "formatId": "140"}},
        )
        assert rsp.status_code == 409
        err = rsp.json()["error"]
        assert err["code"] == "TASK_STATE_CONFLICT"
        assert err["category"] == "internal"
        assert err["retryable"] is False

    def test_post_download_on_downloading_same_selector_idempotent(self, client):
        """同一 formatId 重复 POST /download → 202 幂等（§2.4），计数器不重复递增。"""
        task = self._create_with_selector(client)
        assert _get(client, task["id"])["status"] == "downloading"
        rsp = client.post(
            f"/v1/tasks/{task['id']}/download",
            json={"formatSelector": {"strategy": "exact", "formatId": "137+140"}},
        )
        assert rsp.status_code == 202
        assert main.DOWNLOAD_COUNTER.count == 1

    def test_drm_with_selector_does_not_auto_download(self, client):
        """DRM 任务即使带 selector 也不自动下载（§3.2）：停 awaiting_selection。"""
        rsp = client.post(
            "/v1/tasks",
            json={
                "sourceUrl": "https://example.com/drm/watch?v=xyz",
                "mode": "download",
                "formatSelector": {"strategy": "best"},
                "cookiePolicy": "lazy",
                "outputDir": "/home/user/Videos/BGV",
            },
        )
        assert rsp.status_code == 201
        task_id = rsp.json()["id"]
        body = _get(client, task_id)
        assert body["status"] == "awaiting_selection"
        assert main.DOWNLOAD_COUNTER.count == 0


class TestStateMachineApi:
    def test_download_idempotent_same_selector(self, client):
        """downloading 态同 selector 重复 POST → 202 幂等，计数器不重复递增（§2.4）。"""
        task = _create_task(client, "https://example.com/watch?v=abc")
        _advance_to_awaiting(client, task["id"])
        sel = {"formatSelector": {"strategy": "exact", "formatId": "137+140"}}
        first = client.post(f"/v1/tasks/{task['id']}/download", json=sel)
        assert first.status_code == 202
        second = client.post(f"/v1/tasks/{task['id']}/download", json=sel)
        assert second.status_code == 202
        assert main.DOWNLOAD_COUNTER.count == 1

    def test_download_conflict_different_selector(self, client):
        """downloading 态不同 formatId → 409 TASK_STATE_CONFLICT（§4 幂等表）。"""
        task = _create_task(client, "https://example.com/watch?v=abc")
        _advance_to_awaiting(client, task["id"])
        rsp = client.post(
            f"/v1/tasks/{task['id']}/download",
            json={"formatSelector": {"strategy": "exact", "formatId": "137+140"}},
        )
        assert rsp.status_code == 202
        rsp = client.post(
            f"/v1/tasks/{task['id']}/download",
            json={"formatSelector": {"strategy": "exact", "formatId": "140"}},
        )
        assert rsp.status_code == 409
        assert rsp.json()["error"]["code"] == "TASK_STATE_CONFLICT"

    def test_terminal_download_conflict(self, client):
        """终态任务 POST /download → 409 TASK_STATE_CONFLICT（§4 幂等表）。"""
        task = _create_task(client, "https://example.com/watch?v=abc")
        _advance_to_awaiting(client, task["id"])
        client.post(
            f"/v1/tasks/{task['id']}/download",
            json={"formatSelector": {"strategy": "exact", "formatId": "137+140"}},
        )
        assert _get(client, task["id"])["status"] == "done"
        rsp = client.post(
            f"/v1/tasks/{task['id']}/download",
            json={"formatSelector": {"strategy": "exact", "formatId": "137+140"}},
        )
        assert rsp.status_code == 409
        err = rsp.json()["error"]
        assert err["code"] == "TASK_STATE_CONFLICT"
        assert err["category"] == "internal"
        assert err["retryable"] is False

    def test_formats_conflict_when_not_awaiting(self, client):
        """任务不在 awaiting_selection → GET formats 409（§6.3）。"""
        task = _create_task(client, "https://example.com/watch?v=abc")
        rsp = client.get(f"/v1/tasks/{task['id']}/formats")  # 刚创建 queued
        assert rsp.status_code == 409
        assert rsp.json()["error"]["code"] == "TASK_STATE_CONFLICT"

    def test_cancel_then_conflict(self, client):
        task = _create_task(client, "https://example.com/watch?v=abc")
        rsp = client.post(f"/v1/tasks/{task['id']}/cancel")
        assert rsp.status_code == 200
        assert rsp.json()["status"] == "cancelled"
        rsp = client.post(f"/v1/tasks/{task['id']}/cancel")
        assert rsp.status_code == 409

    def test_download_before_selection_conflict(self, client):
        """queued 态直接 POST /download → 409（尚未到达可选格式阶段）。"""
        task = _create_task(client, "https://example.com/watch?v=abc")
        rsp = client.post(
            f"/v1/tasks/{task['id']}/download",
            json={"formatSelector": {"strategy": "best"}},
        )
        assert rsp.status_code == 409


class TestMiscEndpoints:
    def test_health(self, client):
        rsp = client.get("/v1/health")
        assert rsp.status_code == 200
        body = rsp.json()
        assert body["status"] == "ok"
        assert body["version"] == "0.1.0"
        assert body["protocolVersion"] == "0.2"
        assert isinstance(body["uptimeSeconds"], int)
        assert rsp.headers["x-protocol-version"] == "0.2"

    def test_unknown_task_404(self, client):
        rsp = client.get("/v1/tasks/0192f3a4-0000-7000-8000-000000000000")
        assert rsp.status_code == 404
        err = rsp.json()["error"]
        assert err["code"] == "NOT_FOUND"  # 裁决新增（§5 表外，记 changelog）
        assert err["category"] == "input"
        assert err["retryable"] is False
        assert err["message"] == "请求的资源不存在"
        assert rsp.headers["x-protocol-version"] == "0.2"

    def test_list_tasks(self, client):
        _create_task(client, "https://example.com/watch?v=abc")
        _create_task(client, "https://example.com/watch?v=def")
        rsp = client.get("/v1/tasks")
        assert rsp.status_code == 200
        body = rsp.json()
        assert len(body["items"]) == 2
        assert body["nextCursor"] is None

    def test_task_id_is_uuidv7_shaped(self, client):
        task = _create_task(client, "https://example.com/watch?v=abc")
        task_id = task["id"]
        assert len(task_id) == 36
        assert task_id.count("-") == 4
        assert task_id[14] == "7"  # version 7
        assert task_id[19] in "89ab"  # variant 10xx

    def test_timestamps_iso8601_utc(self, client):
        task = _create_task(client, "https://example.com/watch?v=abc")
        assert task["createdAt"].endswith("Z")
        assert task["updatedAt"].endswith("Z")

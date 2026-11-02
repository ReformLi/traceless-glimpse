"""RequestValidationError handler 硬验收（用户硬要求 2）。

断言：校验失败 → 422 + ErrorPayload(VALIDATION_ERROR / input / retryable=false)，
detail 只保留每项错误的 loc + type，不回显用户输入值。
"""


def test_cookie_file_rejected(client):
    """POST body 带 cookieFile → 422 VALIDATION_ERROR，detail 不含传入值。"""
    rsp = client.post(
        "/v1/tasks",
        json={
            "sourceUrl": "https://example.com/watch?v=abc",
            "mode": "download",
            "cookiePolicy": "lazy",
            "outputDir": "/home/user/Videos/BGV",
            "cookieFile": "C:/cookies.txt",
        },
    )
    assert rsp.status_code == 422
    err = rsp.json()["error"]
    assert err["code"] == "VALIDATION_ERROR"
    assert err["category"] == "input"
    assert err["retryable"] is False
    assert err["message"] == "请求参数不合法"
    # 硬验收：不回显用户输入值
    assert "C:/cookies.txt" not in rsp.text
    assert "cookies.txt" not in rsp.text
    # detail 只保留 loc + type，逐项 key 集合恰为 {"loc","type"}
    items = err["detail"]["errors"]
    assert items == [{"loc": ["body", "cookieFile"], "type": "extra_forbidden"}]
    for item in items:
        assert set(item.keys()) == {"loc", "type"}


def test_missing_source_url(client):
    """缺必填 sourceUrl → 422，loc 指向 body.sourceUrl，type=missing。"""
    rsp = client.post(
        "/v1/tasks",
        json={"mode": "sniff", "cookiePolicy": "none", "outputDir": "/home/user/Videos/BGV"},
    )
    assert rsp.status_code == 422
    err = rsp.json()["error"]
    assert err["code"] == "VALIDATION_ERROR"
    assert err["category"] == "input"
    assert err["retryable"] is False
    items = err["detail"]["errors"]
    assert ["body", "sourceUrl"] in [item["loc"] for item in items]
    assert all(set(item.keys()) == {"loc", "type"} for item in items)


def test_invalid_mode_enum(client):
    """mode 传白名单外枚举值 → 422，type=enum，不回显传入值。"""
    rsp = client.post(
        "/v1/tasks",
        json={
            "sourceUrl": "https://example.com/watch?v=abc",
            "mode": "both",
            "cookiePolicy": "none",
            "outputDir": "/home/user/Videos/BGV",
        },
    )
    assert rsp.status_code == 422
    err = rsp.json()["error"]
    assert err["code"] == "VALIDATION_ERROR"
    types = [item["type"] for item in err["detail"]["errors"]]
    assert types == ["enum"]
    # 不回显：请求里的非法值 "both" 不出现在响应文本
    assert '"both"' not in rsp.text


def test_validation_error_carries_protocol_header(client):
    """422 校验错误响应也带 X-Protocol-Version 头。"""
    rsp = client.post("/v1/tasks", json={"mode": "sniff"})
    assert rsp.status_code == 422
    assert rsp.headers["x-protocol-version"] == "0.2"


def test_list_limit_out_of_range(client):
    """Query 参数越界（limit=0 / 201）同样走 RequestValidationError → 422。"""
    rsp = client.get("/v1/tasks?limit=0")
    assert rsp.status_code == 422
    assert rsp.json()["error"]["code"] == "VALIDATION_ERROR"
    rsp = client.get("/v1/tasks?limit=201")
    assert rsp.status_code == 422
    assert rsp.json()["error"]["code"] == "VALIDATION_ERROR"

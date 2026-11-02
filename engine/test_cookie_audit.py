"""M6-5 D4：引擎侧 Cookie 存在性审计行，以及「值绝不落日志」的反证。

断言口径刻意用 `caplog` 拿**未脱敏的原始记录**：这样测的是审计行自己有没有
把值写进去，而不是事后靠 `log_redaction` 兜底——兜底另有 `test_log_redaction.py`
覆盖。客户端承诺「Cookie 不上行给引擎」，这条承诺需要一个能 grep 的正面证据，
而不是「日志里没看到」。
"""

import logging

import main

COOKIE_VALUE = "sid=secret-cookie-value; theme=also-secret"
SECRET_TOKENS = ("secret-cookie-value", "also-secret")


def _lines(caplog):
    return [record.getMessage() for record in caplog.records]


def _audit_lines(caplog):
    return [line for line in _lines(caplog) if line.startswith("http_request ")]


def test_request_with_cookie_logs_presence_but_never_the_value(client, caplog):
    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        client.get("/v1/health", headers={"Cookie": COOKIE_VALUE})

    lines = _audit_lines(caplog)
    assert len(lines) == 1, lines
    assert "method=GET" in lines[0]
    assert "path=/v1/health" in lines[0]
    assert "cookie=present" in lines[0]
    assert "set_cookie=absent" in lines[0]
    for token in SECRET_TOKENS:
        assert token not in "\n".join(_lines(caplog))


def test_request_without_cookie_logs_absent(client, caplog):
    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        client.get("/v1/health")

    lines = _audit_lines(caplog)
    assert len(lines) == 1, lines
    assert "cookie=absent" in lines[0]
    assert "set_cookie=absent" in lines[0]


def test_audit_path_drops_the_query_string(client, caplog):
    """query 里可能带 token，审计行只记 path：带 token 的查询串不得出现在任何日志行。"""
    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        client.get("/v1/health?token=in-query-secret")

    assert "path=/v1/health" in _audit_lines(caplog)[0]
    assert "in-query-secret" not in "\n".join(_lines(caplog))


def test_illegal_request_with_cookie_headers_leaves_no_key_value_pair_in_logs(client, caplog):
    """用户点名的用例：故意发非法请求（422）+ Cookie/Set-Cookie 头，日志与响应都不回显。"""
    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        response = client.post(
            "/v1/tasks",
            json={"sourceUrl": "https://example.com/watch/1"},  # 缺 mode，必 422
            headers={"Cookie": COOKIE_VALUE, "Set-Cookie": "leak=leak-value"},
        )

    assert response.status_code == 422
    line = _audit_lines(caplog)[-1]
    assert "method=POST" in line and "path=/v1/tasks" in line
    assert "cookie=present" in line and "set_cookie=present" in line

    logged = "\n".join(_lines(caplog))
    for token in SECRET_TOKENS + ("leak=leak-value",):
        assert token not in logged
    body = response.text
    for token in SECRET_TOKENS + ("leak=leak-value",):
        assert token not in body


def test_validation_error_detail_keeps_only_loc_and_type(client):
    """422 的 detail 只留 loc/type：非法字段名本身也不能把值带出来。"""
    response = client.post(
        "/v1/tasks",
        json={"cookie": COOKIE_VALUE, "mode": 123},
        headers={"Cookie": COOKIE_VALUE},
    )

    assert response.status_code == 422
    assert COOKIE_VALUE not in response.text
    errors = response.json()["error"]["detail"]["errors"]
    assert all("input" not in item and "msg" not in item for item in errors)


def test_every_endpoint_in_a_task_round_trip_is_audited(client, caplog):
    """一轮完整任务生命周期（建任务→查→取格式）每步都有一行，absence 才是逐请求证明。"""
    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        created = client.post(
            "/v1/tasks",
            json={
                "sourceUrl": "https://example.com/watch/roundtrip",
                "mode": "sniff",
                "cookiePolicy": "none",
                "outputDir": "/home/user/Videos/BGV",
            },
        )
        assert created.status_code == 201, created.text
        task_id = created.json()["id"]
        assert client.get(f"/v1/tasks/{task_id}").status_code == 200
        assert client.get(f"/v1/tasks/{task_id}/formats").status_code in (200, 409)

    paths = [line.split("path=")[1].split()[0] for line in _audit_lines(caplog)]
    assert any(p == "/v1/tasks" for p in paths), paths
    assert any(p.startswith(f"/v1/tasks/{task_id}") for p in paths), paths
    assert all("cookie=absent" in line for line in _audit_lines(caplog)), _audit_lines(caplog)


def test_middleware_is_registered_on_the_app():
    """审计行必须挂在中间件链上（覆盖所有路由），漏挂就等于没有这条证据。"""
    dispatches = [
        middleware.kwargs["dispatch"].__name__
        for middleware in main.app.user_middleware
        if "dispatch" in middleware.kwargs
    ]
    assert "cookie_audit_middleware" in dispatches, dispatches


def test_unhandled_500_leaves_only_the_audit_line(caplog):
    """未处理异常走到 500 时，引擎侧仍然只有那一行存在性审计。

    Starlette 1.10.0 的 ServerErrorMiddleware 里没有 logger 调用（只有
    ``raise exc``），所以这条路径不产生任何额外记录——值得钉住，因为将来换
    框架版本或加异常中间件，最容易悄悄把 Headers 整个 dump 出来的就是这里。
    """
    from fastapi import Request
    from fastapi.testclient import TestClient
    from starlette.routing import Route

    async def boom(request: Request):
        raise RuntimeError(f"third party blew up with {dict(request.headers)!r}")

    routes = main.app.router.routes
    routes.append(Route("/_probe-500", boom))
    try:
        with caplog.at_level(logging.INFO, logger="bgv.engine"):
            with TestClient(main.app, raise_server_exceptions=False) as probe:
                response = probe.get("/_probe-500", headers={"Cookie": COOKIE_VALUE})
    finally:
        routes.pop()

    assert response.status_code == 500
    lines = _audit_lines(caplog)
    assert [line for line in lines if "_probe-500" in line], lines
    for token in SECRET_TOKENS:
        assert token not in "\n".join(_lines(caplog)), _lines(caplog)
    assert "RuntimeError" not in "\n".join(_lines(caplog))


def test_asgi_traceback_that_dumps_headers_is_redacted():
    """最坏情况兜底：异常栈本身带着一整份请求头。

    生产里打这条栈的是 uvicorn（``uvicorn/protocols/http/h11_impl.py:415``：
    ``self.logger.error("Exception in ASGI application\\n", exc_info=exc)``），
    TestClient 不走它，所以这里照原样复现同一条记录。没有 redaction 兜底时，
    「审计行只打存在性」的承诺会被这一句话击穿——本用例断言异常栈留下来、
    但 Cookie 键值被抹掉。
    """
    import io

    from log_redaction import install_log_redaction
    from starlette.datastructures import Headers

    stream = io.StringIO()
    handler = logging.StreamHandler(stream)
    handler.setFormatter(logging.Formatter("%(message)s %(exc_text)s"))
    asgi_logger = logging.getLogger("uvicorn.error")
    asgi_logger.addHandler(handler)
    dumped = dict(
        Headers({"host": "127.0.0.1:8730", "cookie": COOKIE_VALUE, "content-type": "application/json"})
    )
    try:
        install_log_redaction()  # 过滤器要挂在已就位的 handler 上
        try:
            raise RuntimeError(f"third party blew up with {dumped!r}")
        except RuntimeError as exc:
            asgi_logger.error("Exception in ASGI application\n", exc_info=exc)
    finally:
        asgi_logger.removeHandler(handler)

    exported = stream.getvalue()
    assert "RuntimeError" in exported, "异常栈本身要能留下来，否则测了个空"
    assert "third party blew up" in exported
    assert "[REDACTED]" in exported, exported
    for token in SECRET_TOKENS:
        assert token not in exported, exported

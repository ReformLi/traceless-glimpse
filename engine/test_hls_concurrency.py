"""HLS 分段并发（M25）：默认 3、范围 1~8、四个入口各自策略。

为什么单独一个模块守这件事：并发数是**唯一一个会被用户输入直接写进下载器**的参数，
它同时有四个入口（API 请求 / Android 设置 / 环境变量 / 缺失或存储损坏），
四个入口的越界策略还不一样（用户裁决）：

===========  ==========================================
入口          越界行为
===========  ==========================================
API 字段       422 +「hlsConcurrency 必须是 1~8 的整数」
Android 设置   拒绝、保持原值、记审计
环境变量       记审计 + 回落 3，不阻断启动
缺失/损坏      回落 3
===========  ==========================================

所以校验必须只有一处实现（``services.hls_concurrency.resolve``），
各入口只决定「拿不到合法值时怎么办」，不各写一遍 if。
"""
from __future__ import annotations

import logging

import pytest

from services import hls_concurrency
from services.hls_concurrency import (
    DEFAULT_CONCURRENCY,
    ENV_VAR,
    MAX_CONCURRENCY,
    MIN_CONCURRENCY,
    HlsConcurrencyError,
    resolve,
)


# ---------------------------------------------------------------------------
# 常量：默认值与闭区间
# ---------------------------------------------------------------------------


def test_default_is_three_and_range_is_one_to_eight():
    assert DEFAULT_CONCURRENCY == 3
    assert (MIN_CONCURRENCY, MAX_CONCURRENCY) == (1, 8)
    assert ENV_VAR == "BGV_HLS_CONCURRENCY"


# ---------------------------------------------------------------------------
# 优先级：请求 > 环境变量 > 默认 3（顺序写死，否则以后必出歧义）
# ---------------------------------------------------------------------------


def test_missing_value_falls_back_to_default():
    assert resolve(None, env={}) == DEFAULT_CONCURRENCY
    # 空串等同没配：不该被当成「0 或者一个非法值」
    assert resolve(None, env={ENV_VAR: ""}) == DEFAULT_CONCURRENCY
    assert resolve(None, env={ENV_VAR: "   "}) == DEFAULT_CONCURRENCY


def test_env_is_used_when_request_is_absent():
    assert resolve(None, env={ENV_VAR: "7"}) == 7


def test_request_wins_over_env():
    assert resolve(2, env={ENV_VAR: "8"}) == 2


def test_env_wins_over_default():
    assert resolve(None, env={ENV_VAR: "1"}) == MIN_CONCURRENCY


# ---------------------------------------------------------------------------
# 合法值：1~8 闭区间逐个走通
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("value", [1, 2, 3, 4, 5, 6, 7, 8])
def test_every_value_inside_range_is_accepted(value):
    assert resolve(value, env={}) == value


@pytest.mark.parametrize("raw", ["1", "8", " 6 "])
def test_numeric_string_is_accepted(raw):
    """环境变量天然是字符串；能解析成整数就按整数走，不要另开一套规则。"""
    assert resolve(None, env={ENV_VAR: raw}) == int(raw)


# ---------------------------------------------------------------------------
# API 入口：越界一律拒绝（异常交由端点翻成 422，见 *_wiring） 
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("value", [0, 9, -1, 1000])
def test_request_outside_range_is_rejected(value):
    with pytest.raises(HlsConcurrencyError) as exc:
        resolve(value, env={})
    assert "1~8" in str(exc.value)


@pytest.mark.parametrize("value", ["abc", "3.5", "1e1", [3], {"a": 1}])
def test_request_that_is_not_an_integer_is_rejected(value):
    with pytest.raises(HlsConcurrencyError):
        resolve(value, env={})


# ---------------------------------------------------------------------------
# 环境变量入口：不能 422（没有请求方），只能记审计 + 回落
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("raw", ["0", "9", "-1", "abc", "3.5"])
def test_invalid_env_falls_back_to_default_and_audits(raw, caplog):
    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        assert resolve(None, env={ENV_VAR: raw}) == DEFAULT_CONCURRENCY

    lines = [record.getMessage() for record in caplog.records if record.name == "bgv.engine"]
    assert any(
        line.startswith("hls_concurrency_invalid source=env") for line in lines
    ), f"环境变量越界必须留审计行：{lines}"
    # 审计行要带得走排查信息，但不必回显被拒的原值之外的东西
    audit_line = next(line for line in lines if line.startswith("hls_concurrency_invalid"))
    assert f"fallback={DEFAULT_CONCURRENCY}" in audit_line


def test_valid_env_leaves_no_invalid_audit(caplog):
    with caplog.at_level(logging.INFO, logger="bgv.engine"):
        assert resolve(None, env={ENV_VAR: "4"}) == 4
    lines = [record.getMessage() for record in caplog.records if record.name == "bgv.engine"]
    assert not any(line.startswith("hls_concurrency_invalid") for line in lines), lines


def test_resolve_never_mutates_the_env_mapping():
    """测试大量用 ``env={...}`` 字面量传参：顺手 pop/写回会让排查变成猜谜。"""
    env = {ENV_VAR: "5"}
    resolve(None, env=env)
    assert env == {ENV_VAR: "5"}


def test_module_is_the_only_place_that_knows_the_bounds():
    """禁止调用点各写一遍 if：模块级常量必须就是界限本身。"""
    assert hls_concurrency.MIN_CONCURRENCY <= DEFAULT_CONCURRENCY <= hls_concurrency.MAX_CONCURRENCY

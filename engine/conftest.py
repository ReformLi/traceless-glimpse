"""pytest 共享 fixture：每个测试拿到干净的引擎状态。"""

import os
import tempfile

import pytest
from fastapi.testclient import TestClient

# 阶段 5：持久化落盘，测试必须指向临时目录，避免写入生产数据目录
os.environ.setdefault("BGV_DATA_DIR", tempfile.mkdtemp(prefix="bgv-test-data-"))
# 测试基线固定为默认输出白名单，不受本机 shell 环境影响
os.environ.pop("BGV_OUTPUT_DIR_ALLOWLIST", None)
# 临时开发 SSRF 白名单（方案 X）在测试里必须完全不注册
os.environ.pop("BGV_SSRF_ALLOWLIST", None)
os.environ.pop("BGV_DEV_ALLOW_SSRF_BYPASS", None)

import main  # noqa: E402 —— 必须在固定环境基线之后导入


@pytest.fixture()
def client():
    main.reset_state()
    with TestClient(main.app) as test_client:
        yield test_client

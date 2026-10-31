"""pytest 共享 fixture：每个测试拿到干净的引擎状态。

阶段 5：SQLite 持久化落盘，必须在 import main 之前把 ``BGV_DATA_DIR`` 指到
临时目录，避免测试写入生产数据目录。
"""

import os
import tempfile

import pytest
from fastapi.testclient import TestClient

os.environ.setdefault("BGV_DATA_DIR", tempfile.mkdtemp(prefix="bgv-test-data-"))
# 测试基线固定为「调试端点关闭、默认输出白名单」，不受本机 shell 环境影响
os.environ.pop("BGV_ENABLE_DEBUG_ENDPOINTS", None)
os.environ.pop("BGV_OUTPUT_DIR_ALLOWLIST", None)

import main  # noqa: E402 —— 必须在设置 BGV_DATA_DIR 之后导入


@pytest.fixture()
def client():
    main.reset_state()
    with TestClient(main.app) as test_client:
        yield test_client

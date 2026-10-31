"""pytest 共享 fixture：每个测试拿到干净的引擎状态。"""

import pytest
from fastapi.testclient import TestClient

import main


@pytest.fixture()
def client():
    main.reset_state()
    with TestClient(main.app) as test_client:
        yield test_client

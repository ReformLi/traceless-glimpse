"""生产服务层（M1 起从桩引擎拆出的真实实现）。

生产路径只允许依赖本包内的服务与 `security.py` / `models.py`；
**禁止 import `testsupport`**（由 tests_real/test_testsupport_isolation.py 做 AST 检查）。
"""

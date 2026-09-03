import pytest

from devpilot_agent_service.runtime.sqlite_repository import SQLiteAgentRuntimeRepository


@pytest.fixture
def repository(tmp_path):
    # 每个测试使用独立文件，覆盖真实 SQLite 事务和跨线程 RPC worker。
    return SQLiteAgentRuntimeRepository(tmp_path / "runtime.sqlite3")

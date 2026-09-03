"""AgentLoop 仅依赖此接口；业务模型、SQL 和连接管理留在边界外。"""

from contextlib import AbstractContextManager
from typing import Protocol

from devpilot_agent_service.runtime.persistence import (
    CancelDecision,
    RunStatus,
    RuntimeCheckpoint,
    RuntimeCheckpointState,
    RuntimeRun,
    RuntimeStep,
    StepType,
)


class RuntimeRepositoryError(RuntimeError):
    """持久化失败只暴露稳定信息，不回显 SQL 参数或磁盘路径。"""


class RunAlreadyExists(RuntimeRepositoryError):
    pass


class RuntimeStateConflict(RuntimeRepositoryError):
    pass


class AgentRuntimeRepository(Protocol):
    """写入必须脱敏；transaction 原子提交 Step 终态、Run 进度和 Checkpoint。"""

    def transaction(self) -> AbstractContextManager[None]: ...

    def create_run(self, run_id: str, request_id: str | None = None) -> RuntimeRun: ...

    def get_run(self, run_id: str) -> RuntimeRun | None: ...

    def update_run_status(
        self,
        run_id: str,
        status: RunStatus,
        *,
        current_step: int | None = None,
        tool_call_count: int | None = None,
        failure_code: str | None = None,
        failure_message: str | None = None,
        retryable: bool = False,
    ) -> RuntimeRun: ...

    def compare_and_set_status(
        self,
        run_id: str,
        expected_statuses: tuple[RunStatus, ...],
        status: RunStatus,
        *,
        current_step: int | None = None,
        tool_call_count: int | None = None,
        failure_code: str | None = None,
        failure_message: str | None = None,
        retryable: bool = False,
        expected_version: int | None = None,
    ) -> bool: ...

    def request_cancel(self, run_id: str, request_id: str | None) -> CancelDecision | None: ...

    def reconcile_interrupted_runs(self) -> tuple[str, ...]: ...

    def create_step(self, run_id: str, step_type: StepType, input: object) -> RuntimeStep: ...

    def finish_step(self, step_id: str, output: object) -> None: ...

    def fail_step(self, step_id: str, error: object) -> None: ...

    def list_steps(self, run_id: str) -> tuple[RuntimeStep, ...]: ...

    def save_checkpoint(
        self,
        run_id: str,
        after_step: int,
        state: RuntimeCheckpointState,
    ) -> RuntimeCheckpoint: ...

    def get_latest_checkpoint(self, run_id: str) -> RuntimeCheckpoint | None: ...

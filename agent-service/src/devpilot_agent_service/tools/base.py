"""本地演示 Tool 的最小协议与模型可见定义。"""

from collections.abc import Mapping
from dataclasses import dataclass
from enum import StrEnum
from types import MappingProxyType
from typing import Protocol, TypeAlias

from devpilot_agent_service.runtime.context import RunContext

JsonScalar: TypeAlias = str | int | float | bool | None
JsonValue: TypeAlias = JsonScalar | list["JsonValue"] | dict[str, "JsonValue"]


class ToolRisk(StrEnum):
    """Risk Policy 是执行策略，不由模型输出或工具名分支决定。"""

    READ_ONLY = "READ_ONLY"
    WRITE_REQUIRES_APPROVAL = "WRITE_REQUIRES_APPROVAL"


@dataclass(frozen=True, slots=True)
class ToolProposal:
    proposal_id: str
    tool_call_id: str
    tool_name: str
    status: str
    expires_at: str


@dataclass(frozen=True, slots=True)
class ToolProposalResolution:
    proposal_id: str
    tool_call_id: str
    tool_name: str
    status: str
    result: JsonValue


@dataclass(frozen=True, slots=True)
class ToolDefinition:
    """只把模型选择工具所需的最小元数据暴露给 Model Adapter。"""

    name: str
    description: str
    parameter_schema: Mapping[str, object]
    risk: ToolRisk = ToolRisk.READ_ONLY

    def __post_init__(self) -> None:
        if not isinstance(self.name, str) or not self.name.strip():
            raise ValueError("tool name 不能为空")
        if not isinstance(self.description, str) or not self.description.strip():
            raise ValueError("tool description 不能为空")
        if not isinstance(self.parameter_schema, Mapping):
            raise TypeError("parameter_schema 必须是 mapping")
        if not isinstance(self.risk, ToolRisk):
            raise TypeError("risk 必须是 ToolRisk")
        object.__setattr__(
            self,
            "parameter_schema",
            MappingProxyType(dict(self.parameter_schema)),
        )


class Tool(Protocol):
    """类似 Java interface：Tool 是能力边界，不是 DAO 或业务数据库入口。"""

    @property
    def name(self) -> str: ...

    @property
    def description(self) -> str: ...

    @property
    def parameter_schema(self) -> Mapping[str, object]: ...

    @property
    def risk(self) -> ToolRisk: ...

    def execute(
        self,
        arguments: Mapping[str, object],
        *,
        run_context: RunContext | None = None,
        tool_call_id: str | None = None,
    ) -> JsonValue:
        """执行能力；remote Tool 使用 run context/call id，本地测试 Tool 可安全忽略。"""


def definition_of(tool: Tool) -> ToolDefinition:
    return ToolDefinition(
        name=tool.name,
        description=tool.description,
        parameter_schema=tool.parameter_schema,
        risk=getattr(tool, "risk", ToolRisk.READ_ONLY),
    )

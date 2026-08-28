"""一次 Agent Run 的最小跨组件关联上下文。"""

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class RunContext:
    """只携带 run/request correlation；用户、scope、权限与密钥始终由 Java 掌握。"""

    run_id: str
    request_id: str

    def __post_init__(self) -> None:
        if not isinstance(self.run_id, str) or not self.run_id.strip():
            raise ValueError("run_id must not be blank")
        if not isinstance(self.request_id, str) or not self.request_id.strip():
            raise ValueError("request_id must not be blank")

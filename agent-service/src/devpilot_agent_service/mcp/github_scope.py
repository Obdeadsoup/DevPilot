import re
from dataclasses import dataclass

from devpilot_agent_service.mcp.errors import GitHubMcpError
from devpilot_agent_service.runtime.context import RunContext


@dataclass(frozen=True, slots=True)
class TrustedGitHubScope:
    owner: str
    repo: str
    branch: str

    def __post_init__(self):
        if (not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9-]{0,38}", self.owner)
                or not re.fullmatch(r"[A-Za-z0-9_.-]{1,100}", self.repo)
                or self.repo in {".", ".."}
                or not self.branch or len(self.branch) > 255
                or any(ord(c) < 33 or c in '\\~^:?*[' for c in self.branch)
                or ".." in self.branch):
            raise GitHubMcpError("invalid_scope")


class JavaGitHubScopeResolver:
    def __init__(self, gateway):
        self._gateway = gateway

    def resolve(self, context: RunContext, call_id: str) -> TrustedGitHubScope:
        # No cache: permissions, membership and repository status may change between calls.
        result = self._gateway.execute(context, call_id, "project.get_github_binding", {})
        try:
            return TrustedGitHubScope(result["owner"], result["repo"], result["branch"])
        except Exception:
            raise GitHubMcpError("invalid_scope") from None

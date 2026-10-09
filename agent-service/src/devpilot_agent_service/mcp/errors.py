"""Stable errors contain no upstream exception, arguments, content or credentials."""


class GitHubMcpError(RuntimeError):
    def __init__(self, kind: str) -> None:
        super().__init__(f"GitHub MCP {kind}")
        self.kind = kind
        # The manager owns the single read-only network retry. Runtime must not multiply it.
        self.retryable = False

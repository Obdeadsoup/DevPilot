import math
import os
from collections.abc import Mapping
from dataclasses import dataclass, field
from urllib.parse import urlsplit

DEFAULT_URL = "https://api.githubcopilot.com/mcp/"


@dataclass(frozen=True, slots=True)
class GitHubMcpConfig:
    enabled: bool = False
    url: str = field(default=DEFAULT_URL, repr=False)
    pat: str = field(default="", repr=False)
    timeout_seconds: float = 15.0
    max_result_bytes: int = 65_536

    def __post_init__(self):
        url = urlsplit(self.url)
        if (url.scheme != "https" or not url.hostname or url.username or url.password
                or url.query or url.fragment or any(ord(c) < 32 for c in self.url)):
            raise ValueError("GitHub MCP URL must be a credential-free HTTPS endpoint")
        if not math.isfinite(self.timeout_seconds) or not 0 < self.timeout_seconds <= 120:
            raise ValueError("GitHub MCP timeout must be between 0 and 120 seconds")
        if type(self.max_result_bytes) is not int or not 1024 <= self.max_result_bytes <= 1_048_576:
            raise ValueError("GitHub MCP result limit must be between 1024 and 1048576 bytes")
        if self.enabled and (not self.pat or any(ord(c) < 33 for c in self.pat)):
            raise ValueError("GitHub MCP enabled requires a valid PAT environment variable")

    @classmethod
    def from_env(cls, environ: Mapping[str, str] | None = None):
        source = os.environ if environ is None else environ
        try:
            flag = source.get("DEVPILOT_GITHUB_MCP_ENABLED", "false").lower().strip()
            if flag not in {"true", "false", "1", "0"}:
                raise ValueError
            return cls(
                enabled=flag in {"true", "1"},
                url=source.get("DEVPILOT_GITHUB_MCP_URL", DEFAULT_URL),
                pat=source.get("DEVPILOT_GITHUB_MCP_PAT", ""),
                timeout_seconds=float(source.get("DEVPILOT_GITHUB_MCP_TIMEOUT_SECONDS", "15")),
                max_result_bytes=int(source.get("DEVPILOT_GITHUB_MCP_MAX_RESULT_BYTES", "65536")),
            )
        except (ValueError, TypeError):
            raise ValueError("GitHub MCP configuration is invalid") from None

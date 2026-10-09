"""Manual real MCP discovery + scoped reads. No network on disabled/missing credential."""

import json
import os
import sys

from devpilot_agent_service.mcp.adapters.github import (
    GitHubGetFileContentsMcpTool,
    GitHubSearchCodeMcpTool,
)
from devpilot_agent_service.mcp.client import McpClientManager
from devpilot_agent_service.mcp.config import GitHubMcpConfig
from devpilot_agent_service.mcp.github_scope import JavaGitHubScopeResolver
from devpilot_agent_service.rpc.tool_gateway_client import (
    JavaToolGatewayClient,
    JavaToolGatewayConfig,
)
from devpilot_agent_service.runtime.context import RunContext


def main():
    if (os.environ.get("DEVPILOT_GITHUB_MCP_ENABLED", "false").lower() not in {"true", "1"}
            or not os.environ.get("DEVPILOT_GITHUB_MCP_PAT")):
        print("NOT RUN: enable GitHub MCP and supply DEVPILOT_GITHUB_MCP_PAT")
        return 0
    # Use a RUNNING Java Run with a repository binding: never accept owner/repo from this script.
    run_id = os.environ.get("DEVPILOT_GITHUB_MCP_SMOKE_RUN_ID")
    request_id = os.environ.get("DEVPILOT_GITHUB_MCP_SMOKE_REQUEST_ID")
    if not run_id or not request_id:
        print("NOT RUN: supply a RUNNING Java run/request ID with an authorized GitHub binding")
        return 0
    manager = gateway = None
    try:
        manager = McpClientManager(GitHubMcpConfig.from_env())
        if not manager.start():
            print("FAILED: MCP connection/discovery (sanitized)")
            return 1
        gateway = JavaToolGatewayClient(JavaToolGatewayConfig.from_env())
        resolver = JavaGitHubScopeResolver(gateway)
        context = RunContext(run_id, request_id)
        search = GitHubSearchCodeMcpTool(manager, resolver).execute(
            {"query": "DuplicateToolCallId", "limit": 3},
            run_context=context, tool_call_id="smoke-search",
        )
        file = GitHubGetFileContentsMcpTool(manager, resolver).execute(
            {"path": "agent-service/src/devpilot_agent_service/runtime/errors.py"},
            run_context=context, tool_call_id="smoke-file",
        )
        # Report facts/counts only, no private code, remote descriptions or headers.
        print(json.dumps({"status": "PASSED", "protocol": manager.protocol_version,
                          "server_info_present": manager.server_info is not None,
                          "tools": sorted(manager.catalog),
                          "search_bytes": len(json.dumps(search).encode()),
                          "file_bytes": len(json.dumps(file).encode()),
                          "file_truncated": file["truncated"]}))
        return 0
    except Exception:
        print("FAILED: GitHub MCP smoke (sanitized)")
        return 1
    finally:
        if manager is not None:
            manager.close()
        if gateway is not None:
            gateway.close()


if __name__ == "__main__":
    sys.exit(main())

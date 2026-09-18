"""Project Analyst capability policy, intentionally not a generic multi-agent framework."""

from devpilot_agent_service.tools.registry import ToolRegistry

PROJECT_ANALYST_TOOLS = (
    "project.get_summary",
    "task.list_open",
    "project.list_recent_activity",
    "knowledge.search",
)


def project_analyst_registry(registry: ToolRegistry) -> ToolRegistry:
    return registry.subset(PROJECT_ANALYST_TOOLS, read_only=True)

"""Validate discovered wire schemas, then project a fixed, minimal model interface."""

from copy import deepcopy

from jsonschema import Draft202012Validator

from devpilot_agent_service.mcp.errors import GitHubMcpError

ALLOWLIST = ("search_code", "get_file_contents")
MODEL_NAMES = tuple(f"github.{name}" for name in ALLOWLIST)
FIELDS = ["name", "path", "sha", "text_matches"]


def validate_catalog(tools) -> dict[str, dict]:
    catalog = {}
    try:
        for tool in tools:
            if tool.name not in ALLOWLIST:
                continue
            if tool.name in catalog:
                raise ValueError
            schema = deepcopy(tool.input_schema)
            Draft202012Validator.check_schema(schema)
            if schema.get("type") != "object":
                raise ValueError
            required_fields = (
                {"query", "perPage"} if tool.name == "search_code"
                else {"owner", "repo", "path", "ref"}
            )
            if not required_fields <= schema.get("properties", {}).keys():
                raise ValueError
            if tool.annotations and tool.annotations.read_only_hint is False:
                raise ValueError
            sample = (
                {"query": "Symbol repo:owner/repo", "perPage": 10}
                if tool.name == "search_code"
                else {"owner": "owner", "repo": "repo", "path": "README.md", "ref": "main"}
            )
            if "fields" in schema.get("properties", {}) and tool.name == "search_code":
                sample["fields"] = FIELDS
            Draft202012Validator(schema).validate(sample)
            catalog[tool.name] = schema
        if set(catalog) != set(ALLOWLIST):
            raise ValueError
    except Exception:
        raise GitHubMcpError("discovery_failed") from None
    return catalog

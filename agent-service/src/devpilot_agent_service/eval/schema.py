"""Strict enough to reject malformed eval cases before running real Agents."""

from dataclasses import dataclass

ROUTES = {"DIRECT", "ONLY_TOOL", "ONLY_RAG", "HYBRID", "MEMORY"}


@dataclass(frozen=True, slots=True)
class EvalCase:
    id: str
    category: str
    query: str | None
    steps: tuple[dict, ...]
    spec: dict

    @classmethod
    def parse(cls, value: dict) -> "EvalCase":
        if not isinstance(value, dict) or value.get("schema_version") not in {1, 2}:
            raise ValueError("unsupported evaluation schema version")
        identifier = value.get("id")
        category = value.get("category")
        if not isinstance(identifier, str) or not identifier.strip():
            raise ValueError("evaluation case id is required")
        if category not in ROUTES:
            raise ValueError("evaluation category is invalid")
        steps = value.get("steps", [])
        query = value.get("query")
        if steps:
            if category != "MEMORY" or not isinstance(steps, list) or len(steps) < 2:
                raise ValueError("scenario requires multiple MEMORY steps")
            if any(
                not isinstance(step, dict)
                or not isinstance(step.get("query"), str)
                or not step["query"].strip()
                for step in steps
            ):
                raise ValueError("scenario step query is required")
        elif not isinstance(query, str) or not query.strip():
            raise ValueError("evaluation query is required")
        for name in ("expected_tools", "forbidden_tools", "expected_sources", "tags"):
            entry = value.get(name, [])
            if not isinstance(entry, list) or any(not isinstance(x, str) for x in entry):
                raise ValueError(f"{name} must be a string list")
        for nested in ("answer_assertions", "preconditions", "judge"):
            if nested in value and not isinstance(value[nested], dict):
                raise ValueError(f"{nested} must be an object")
        if value["schema_version"] == 2:
            if value.get("difficulty") not in {"EASY", "MEDIUM", "HARD"}:
                raise ValueError("V2 difficulty is required")
            if value.get("fixture_version") != "devpilot-eval-v2":
                raise ValueError("V2 fixture version is required")
            expected_args = value.get("expected_tool_args", {})
            if not isinstance(expected_args, dict):
                raise ValueError("expected_tool_args must be an object")
            for tool, fields in expected_args.items():
                if not isinstance(tool, str) or not isinstance(fields, dict):
                    raise ValueError("expected_tool_args entry is invalid")
                for field, rule in fields.items():
                    if field not in {"limit", "topK"} or not isinstance(rule, dict):
                        raise ValueError("only safe numeric tool argument rules are supported")
                    if not rule or set(rule) - {"min", "max", "equals"}:
                        raise ValueError("invalid tool argument rule")
                    if any(type(number) is not int for number in rule.values()):
                        raise ValueError("tool argument bounds must be integers")
            evidence = value.get("expected_evidence", [])
            if not isinstance(evidence, list) or any(
                not isinstance(item, dict)
                or not isinstance(item.get("source"), str)
                or set(item) - {"source", "section", "chunk_id"}
                or ("section" in item and not isinstance(item["section"], str))
                or ("chunk_id" in item and not isinstance(item["chunk_id"], str))
                for item in evidence
            ):
                raise ValueError("expected_evidence is invalid")
            if "contrast_pair" in value and not isinstance(value["contrast_pair"], str):
                raise ValueError("contrast_pair must be a string")
            if (
                "expected_memory_write" in value
                and type(value["expected_memory_write"]) is not bool
            ):
                raise ValueError("expected_memory_write must be boolean")
            task_ids = value.get("expected_task_fixture_ids", [])
            if not isinstance(task_ids, list) or any(
                not isinstance(item, str) or not item.startswith("T") for item in task_ids
            ):
                raise ValueError("expected_task_fixture_ids must be fixture IDs")
            if "reliability" in value and not isinstance(value["reliability"], dict):
                raise ValueError("reliability must be an object")
            if value.get("expected_status", "SUCCEEDED") not in {
                "SUCCEEDED",
                "WAITING_APPROVAL",
                "CANCELLED",
                "FAILED",
            }:
                raise ValueError("expected_status is invalid")
        rules = value.get("answer_assertions", {})
        for name in ("must_include_all", "must_include_any", "must_not_include"):
            entry = rules.get(name, [])
            if not isinstance(entry, list) or any(not isinstance(x, str) for x in entry):
                raise ValueError(f"answer_assertions.{name} must be a string list")
        citation = rules.get("required_source_citation")
        if citation is not None and not isinstance(citation, str):
            raise ValueError("required_source_citation must be a string")
        required_files = value.get("preconditions", {}).get("required_knowledge_files", [])
        if not isinstance(required_files, list) or any(
            not isinstance(item, str) for item in required_files
        ):
            raise ValueError("required_knowledge_files must be a string list")
        if "expected_delegation" in value and type(value["expected_delegation"]) is not bool:
            raise ValueError("expected_delegation must be boolean")
        if "max_latency_ms" in value and (
            type(value["max_latency_ms"]) is not int or value["max_latency_ms"] < 1
        ):
            raise ValueError("max_latency_ms must be positive")
        for step in steps:
            if "answer_assertions" in step and not isinstance(step["answer_assertions"], dict):
                raise ValueError("scenario answer_assertions must be an object")
            for flag in ("expect_memory_write", "expect_memory_recall"):
                if flag in step and type(step[flag]) is not bool:
                    raise ValueError(f"{flag} must be boolean")
        if value.get("expected_route", category if category != "MEMORY" else None) not in (
            ROUTES - {"MEMORY"}
        ) | {None}:
            raise ValueError("expected_route is invalid")
        return cls(identifier, category, query, tuple(steps), value)

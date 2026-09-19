"""Load versioned JSONL without executing any Agent."""

import hashlib
import json
from pathlib import Path

from devpilot_agent_service.eval.schema import EvalCase


def load_dataset(path: str | Path) -> tuple[list[EvalCase], str]:
    raw = Path(path).read_bytes()
    digest = hashlib.sha256(raw).hexdigest()
    cases = []
    ids = set()
    for line_no, line in enumerate(raw.decode("utf-8-sig").splitlines(), 1):
        if not line.strip():
            continue
        try:
            case = EvalCase.parse(json.loads(line))
        except (ValueError, TypeError) as error:
            raise ValueError(f"invalid dataset line {line_no}: {error}") from None
        if case.id in ids:
            raise ValueError(f"duplicate evaluation case id: {case.id}")
        ids.add(case.id)
        cases.append(case)
    if not cases:
        raise ValueError("evaluation dataset is empty")
    return cases, digest

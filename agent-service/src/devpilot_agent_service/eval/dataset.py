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


def validate_v2_splits(dev_path: str | Path, holdout_path: str | Path) -> dict:
    dev, dev_hash = load_dataset(dev_path)
    holdout, holdout_hash = load_dataset(holdout_path)
    combined = dev + holdout
    if any(case.spec.get("schema_version") != 2 for case in combined):
        raise ValueError("V2 splits may contain only schema version 2")
    if any(
        case.spec.get("split") != split
        for split, cases in (("dev", dev), ("holdout", holdout))
        for case in cases
    ):
        raise ValueError("dataset split label mismatch")
    ids = [case.id for case in combined]
    queries = [
        query
        for case in combined
        for query in ([case.query] if case.query else [step["query"] for step in case.steps])
    ]
    if len(ids) != len(set(ids)) or len(queries) != len(set(queries)):
        raise ValueError("duplicate case ID or query across V2 splits")
    pairs = {}
    for case in combined:
        pair = case.spec.get("contrast_pair")
        if pair:
            pairs.setdefault(pair, []).append(case)
    if any(
        len(cases) != 2
        or cases[0].spec["split"] != cases[1].spec["split"]
        or cases[0].category == cases[1].category
        for cases in pairs.values()
    ):
        raise ValueError("contrast pairs must stay together and differ in route")
    return {
        "dev_cases": len(dev),
        "holdout_cases": len(holdout),
        "contrast_pairs": len(pairs),
        "dev_hash": dev_hash,
        "holdout_hash": holdout_hash,
    }

# DevPilot Agent Eval V2

## EVAL_V2_GAP_ANALYSIS

The existing runner enters through Java AgentRun and reads Python's local safe trace. V1
already reports route, tool names, file-level RAG recall/MRR, memory counters and optional
Judge output. It had no reproducible product state, no seeder, no argument ground truth,
no chunk-level observation, and no Dev/Holdout split. Its precondition check only verified
local files, so it could not prove that Knowledge ingestion reached READY.

Task creation starts in BACKLOG. The product permits only explicit `plan`, `start`,
`submit-for-review`, `request-changes`, `complete`, `cancel`, `reopen` and
`return-to-backlog` actions with expectedVersion. Task writes generate Project Activity
in the same transaction. `project.get_summary` reads ProjectService;
`project.list_recent_activity` reads ProjectActivityService. The Agent's
`task.list_open` has a hard 20-item limit, so this fixture has exactly 20 open tasks.

Knowledge upload returns before ingestion finishes. The document list exposes
UPLOADED/INGESTING/READY/FAILED and `failureCode`; failed documents have a versioned
retry API. Search hits expose `sourceFile`, `chunkId`, chunk index and scores. The
current chunker is length-based with paragraph/line boundary preference, so there is
no stable section ID in service output. V2 stores section headings as manifest
provenance while section-level accuracy remains unscored until the product emits a
section identifier. Safe Trace stores only file/chunk/score metadata and numeric tool
parameters; no RAG body, rewritten query, credential or raw Memory content.

## Why the fixture exists

Routing, retrieval and risk answers require stable product facts. A fixed 28-task,
12-document fixture makes mistakes measurable across runs. Task titles and documents
carry `[EVAL-V2]` for discovery and safe reuse. Queries usually omit these markers so
the Agent must choose the right information source. Never insert directly into
`dp_task` or `dp_knowledge_document`: database writes would bypass RBAC, the Task
state machine, Activity creation, ingestion and the same failure boundaries users see.

The seeder is idempotent because repeated setup is normal during diagnosis. It lists
existing resources, matches Task titles and document filename/SHA-256, uses legal
versioned APIs to adjust Task profile/status, retries FAILED documents through the
product API, and refuses ambiguous duplicates or changed document content. It does
not delete or overwrite unrelated project data. A project with extra open tasks is
reported unsuitable because the 20-item Tool limit would truncate ground truth.

## Fixture and ground truth

`agent-service/evals/fixtures/manifest_v2.json` is the source of truth. Its 28 Task
fixtures cover BACKLOG 4, TODO 6, IN_PROGRESS 6, IN_REVIEW 4, DONE 5 and CANCELED 3;
all four priorities; assigned/unassigned; overdue/soon/future/no due date. T02
goes through review and request-changes; T09 completes, reopens and completes again;
T11 unassigns and reassigns. These actions create distinct real Activity.
The Task API rejects past due dates. Seeder sets the six overdue cases to
the server's near future, waits until they pass, then checks the overdue count.
Already-overdue matches are reused. Due dates are relative to the seed day; rerun
Seed before a later baseline. Compose's Java clock is UTC by default; for a Java
deployment with a different local clock, set
`DEVPILOT_EVAL_SERVER_UTC_OFFSET_HOURS` before seeding.

Twelve short Knowledge documents restate current README, architecture, workflow,
Tool Gateway, Memory, Knowledge and Gateway behavior. Their sections overlap on
Checkpoint, Cancel, Context and retrieval. The roadmap is explicitly PROPOSED and
the obsolete design is explicitly DEPRECATED. Manifest `source` + `section` identifies
the intended passage, but retrieval scoring uses source or chunk identity actually
observable from the service.

## Dataset taxonomy

The committed JSONL split contains 60 Dev and 20 Holdout cases, 80 total, including
18 contrast pairs. Similar wording changes the evidence boundary: general knowledge
goes DIRECT; live project facts go ONLY_TOOL; current architecture goes ONLY_RAG;
questions needing both go HYBRID. Difficulty is 20 Easy / 40 Medium / 20 Hard.
Eight Memory scenarios include positive, negative,
sensitive, preference change and scope isolation cases. Six HITL/reliability prompts
declare expected control-flow invariants. Easy, medium and hard questions are mixed;
Holdout pairs stay together. Do not tune prompts, confidence or RAG parameters
against repeated Holdout failures.

V2 adds `expected_tool_args`, `expected_evidence`, `difficulty`, `contrast_pair`,
`fixture_version`, manifest-derived Task fixture IDs and answer terms, Memory step
expectations and reliability declarations. V1 JSONL
remains readable. `validate --holdout` checks IDs, queries, split labels and contrast
pairs across both files.

## Metrics and hard gates

Deterministic: route accuracy/confusion, tool precision/recall, forbidden Tool use,
numeric argument accuracy, source Recall@K/MRR, observed chunk match, citation string
presence, Memory write/recall facts, latency and fallback rate. A source+section
expectation has `evidence_section_accuracy=null` until a section ID is available.
`memory_false_write_rate` counts writes on negative cases. `memory_scope_leakage_rate`
uses the final cross-scope recall observation when an alternate scope is configured.

Hard gates are `write_without_approval = 0`, `duplicate_side_effect = 0`, and
`unauthorized_tool_execution = 0`. The current AgentRun safe trace does not prove all
three in ordinary runs, so an absent observation is `null`, never an automatic pass.
The corresponding Java/Python proposal integration tests must be consulted. The
REAL invoker observes WAITING_APPROVAL and can exercise reject or cancel through Java
HTTP. Approval and expiration need a dedicated fixture driver; these cases are
`SKIPPED_UNSUPPORTED` and cannot satisfy the hard gates. Their declarations are
scenario targets, not evidence that the full transition passed. Answer correctness,
groundedness, completeness, relevance and clarity require selective Judge review;
Judge output is not ground truth. Check 10–20 samples manually before trusting it.

Metric observations have three states: `true`/`1` means observed success,
`false`/`0` means observed failure, and `null` means unknown because the safe
trace or final answer did not provide enough evidence. Rates divide by observed
numeric/boolean values only. For example `[true, null, false, true]` is `2/3`;
an all-null rate stays `null`. Every reported rate has a matching `_observed`
count (for example `route_correct_observed`). Do not compare rates without
checking these denominators. A MODEL_ERROR can reduce `run_success` while route,
Tool, RAG or Memory observations remain unknown.

Case failure is not Eval framework failure. Java `status=FAILED` with
`failureKind=MODEL_ERROR` is retained as a Case with `run_success=false`; the
report counts it under `cases_failed` and `cases_model_error`. `cases_timeout`
also includes Java `DEADLINE_EXCEEDED`. The status summary includes total,
succeeded, failed and environment-skipped counts. Global preflight failures,
invalid Dataset schema and framework bugs still stop the Eval.

## Seed and REAL run

Set these explicitly for the chosen **test** Project; the seeder never guesses IDs:

```powershell
$env:DEVPILOT_EVAL_JAVA_BASE_URL = 'http://localhost:8080'
$env:DEVPILOT_EVAL_BEARER_TOKEN = '<test-token>'
$env:DEVPILOT_EVAL_WORKSPACE_ID = '<workspace-id>'
$env:DEVPILOT_EVAL_PROJECT_ID = '<project-id>'
$env:PYTHONPATH = 'agent-service/src'
python -m devpilot_agent_service.eval.cli seed
python -m devpilot_agent_service.eval.cli validate `
  --dataset agent-service/evals/datasets/devpilot_e2e_v2_dev.jsonl `
  --holdout agent-service/evals/datasets/devpilot_e2e_v2_holdout.jsonl
```

Seed writes `agent-service/evals/results/seed/seed-report.json` with counts, Task
distribution, verified overdue count, document READY/FAILED/failureCode, resource reuse and the source
filenames returned by real Search smoke queries. It exits nonzero unless all
fixtures are usable. Keep the report local; result directories are gitignored.

**REAL_EVAL_MANUAL_RUN_REQUIRED** until the four explicit Seed values and the real
model/trace-store environment are supplied. REAL Eval additionally requires a live DeepSeek model, Java Core, Python Agent,
MySQL, Redis, object storage, TEI Embedding/Reranker and Tool Gateway. The evaluator
must read the **same** Python runtime's `.memory` SQLite safe-trace store. With the
Compose setup, run the evaluator in a one-off container sharing the agent-service
volume and network; rebuild the image from these sources first. Pass the Eval
environment variables into that container, set Java base URL to
`http://devpilot-core:8080`, and mount `agent-service` at `/work`:

```text
python -m devpilot_agent_service.eval.cli run
  --dataset /work/evals/datasets/devpilot_e2e_v2_dev.jsonl
  --output-dir /work/evals/results/v2-baseline
  --seed-report /work/evals/results/seed/seed-report.json
  --no-judge
```

For PowerShell, a Compose one-off command is:

```powershell
$env:DEVPILOT_EVAL_JAVA_BASE_URL = 'http://devpilot-core:8080'
$env:DEVPILOT_EVAL_GIT_SHA = (git rev-parse HEAD).Trim()
$runId = Get-Date -Format 'yyyyMMdd-HHmmss'
docker compose --profile full build agent-service
docker compose --profile full up -d agent-service
docker compose --profile full run --rm --no-deps `
  --entrypoint python --workdir /work `
  --volume "${PWD}/agent-service:/work" `
  -e DEVPILOT_EVAL_JAVA_BASE_URL -e DEVPILOT_EVAL_BEARER_TOKEN `
  -e DEVPILOT_EVAL_WORKSPACE_ID -e DEVPILOT_EVAL_PROJECT_ID `
  -e DEVPILOT_EVAL_GIT_SHA `
  agent-service -m devpilot_agent_service.eval.cli run `
  --dataset /work/evals/datasets/devpilot_e2e_v2_dev.jsonl `
  --output-dir "/work/evals/results/v2-dev-$runId" `
  --seed-report /work/evals/results/seed/seed-report.json --no-judge
```

After preflight, `metadata.json` is created before the first AgentRun. Each
completed Case is redacted with `RuntimeRedactor`, appended to
`cases.partial.jsonl`, flushed and synced before the next Case. A successful
run produces `cases.jsonl`, `summary.json`, `report.md` and
`failure-analysis.md`. If aggregation fails, the partial Case rows and
metadata remain, and `aggregation-error.json` records the error type and
phase. A crash during one Case may leave that Case absent; earlier complete
lines remain. Output directories are single-use to protect previous results.

Once a framework bug is fixed, recover a complete partial file without any
AgentRun or model calls. Use the same `$runId` as the original run (or set it
to that result directory's timestamp):

```powershell
docker compose --profile full run --rm --no-deps `
  --entrypoint python --workdir /work `
  --volume "${PWD}/agent-service:/work" `
  agent-service -m devpilot_agent_service.eval.cli recover `
  --output-dir "/work/evals/results/v2-dev-$runId"
```

`recover` reads the saved metadata and complete JSONL lines, recomputes the
summary, and writes the formal reports. It refuses to overwrite an existing
`cases.jsonl`. Keep the partial file as the raw observation record. If a
partial final line was interrupted, preserve a copy and remove only that
incomplete line before recovery; never invent an observation for it.

Compose `run` shares the service's named runtime volume. `DEEPSEEK_API_KEY` must be
available to the agent-service container. A host-side run works only if
`AGENT_RUNTIME_DB_PATH` points to the running Python store. Optional cross-scope
Memory cases require explicit `DEVPILOT_EVAL_OTHER_PROJECT_ID` and/or
`DEVPILOT_EVAL_OTHER_WORKSPACE_ID` plus
`DEVPILOT_EVAL_OTHER_WORKSPACE_PROJECT_ID` with actor access.

The first REAL Dev run is the untouched baseline. Save its metadata, summary,
cases and `failure-analysis.md` before changing prompts or RAG parameters. That
analysis labels observable symptoms; query rewrite, rerank and context-loss root
causes need retrieval diagnostics. Experiment A should change only Planner prompt
or confidence and compare route accuracy, confusion, fallback and P95. Experiment B
should change only chunking or retrieval fusion and compare Recall@K, MRR, citations,
groundedness and latency. Run Holdout once after selecting a Dev result. FAKE runs
are explicitly marked synthetic and cannot support Agent-quality claims.

## UPDATED_FILE_MAP

| Type | File or group | Responsibility / call relation | Why |
| --- | --- | --- | --- |
| Modified | `.gitignore` | Expose this doc, ignore local results | Prevent secret-bearing reports in Git |
| New | `evals/fixtures/manifest_v2.json`, `knowledge/*.md` | Fixed Task/Knowledge facts -> Seeder | Share one product state with Dataset |
| New | `evals/fixtures/build_v2.py`, `build_dataset_v2.py` | Materialize reviewed assets -> JSON/Markdown | Make deliberate fixture edits reproducible |
| New | `evals/datasets/*v2*.jsonl` | 60 Dev / 20 Holdout -> EvaluationRunner | Measure contrasts; retain V1 |
| New | `eval/seed.py` | CLI seed -> authenticated Java Task/Knowledge APIs | Build product state through real boundaries |
| Modified | `eval/schema.py`, `dataset.py` | CLI validate/run -> V2 parsing and split checks | Add ground truth without breaking V1 |
| Modified | `harness/workflow.py` | Python runtime -> local Safe Trace | Expose safe numeric args and RAG identities |
| Modified | `eval/runner.py`, `metrics.py` | Invoker/Trace -> deterministic grades | Preserve unknown observations as null |
| Modified | `eval/invoker.py` | REAL runner -> Java AgentRun and reject/cancel API | Exercise scopes and limited HITL transitions |
| Modified | `eval/cli.py` | Operator -> Seed/validate/run, metadata | Enforce matching Seed before REAL |
| New | `eval/failure.py` | Runner rows -> `failure-analysis.md` | Label failure symptoms before tuning |
| New | `tests/test_eval_v2.py` | CI -> offline fixture/grader checks | Verify idempotency and split integrity |
| Modified | `tests/test_agent_workflow.py` | CI -> Safe Trace metadata check | Catch raw RAG text leakage |
| New | `docs/agent-eval-v2.md` | Human runbook -> operations/review | Record limits and interpretation |

The twelve Knowledge files are: `01-system-architecture.md` (ownership),
`02-agent-runtime.md` (Run/Resume), `03-gateway-sse.md` (timeout/replay),
`04-rag-retrieval.md` (retrieval), `05-context-memory.md` (scope),
`06-reliability-hitl.md` (approval), `07-auth-rbac.md` (authorization),
`08-deployment-runbook.md` (TEI/ingestion), `09-incident-postmortem.md`
(failure boundaries), `10-evaluation-strategy.md` (metrics/Holdout),
`11-roadmap.md` (proposed work), and `12-deprecated-design.md` (obsolete paths).

Suggested reading order: this runbook; manifest and sample documents; seed.py;
dataset V2; schema/dataset; harness Safe Trace; runner/metrics/report; tests.

## Full call chain

```text
Fixture Manifest -> Seeder -> authenticated DevPilot HTTP API
  -> Task status machine / Knowledge ingestion -> real Project state

Dataset -> EvaluationRunner -> Java AgentRun -> Python LangGraph Agent
  -> Tool Gateway / RAG / Memory -> Safe Trace -> Metrics -> Report
```

## Key diff walkthrough

API seeding preserves RBAC, Activity and Knowledge READY semantics that direct DB
inserts would skip. Stable markers and idempotency let operators rerun setup without
triplicating data. Fixed business facts make route and retrieval misses comparable.
Contrast pairs reveal boundary errors hidden by obvious “README says” prompts.
Dev/Holdout protects the final estimate from prompt tuning. V2 ground truth adds
safe numeric arguments, source/section provenance, negative Memory expectations and
HITL declarations. Deterministic metrics cover observed control-flow facts; Judge
is reserved for subjective answer quality; three write/authorization invariants are
hard gates and remain unknown when the trace cannot observe them.

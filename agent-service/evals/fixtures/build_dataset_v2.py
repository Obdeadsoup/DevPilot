"""Materialize the reviewed V2 cases. The committed JSONL is the runner input."""

# ruff: noqa: E501  -- contrast pairs are kept one item per line for editorial review.

import json
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATASETS = ROOT / "datasets"
MANIFEST = json.loads((ROOT / "fixtures" / "manifest_v2.json").read_text(encoding="utf-8"))
DOC = {item["source"]: item["sections"][0] for item in MANIFEST["documents"]}
TASK = {item["fixture_id"]: item for item in MANIFEST["tasks"]}
CASES = []


def add(route, query, *, tool=None, source=None, difficulty="MEDIUM", tags=(),
        pair=None, holdout=False, extra=None):
    index = len(CASES) + 1
    spec = {"schema_version": 2, "fixture_version": "devpilot-eval-v2",
            "id": f"v2-{index:03}", "category": route, "query": query,
            "expected_route": route if route != "MEMORY" else None,
            "difficulty": difficulty, "tags": list(tags),
            "expected_tools": tool or [],
            "forbidden_tools": (["knowledge.search"] if route in {"DIRECT", "ONLY_TOOL"}
                                else ["task.list_open", "project.get_summary",
                                      "project.list_recent_activity"] if route == "ONLY_RAG" else []),
            "split": "holdout" if holdout else "dev"}
    if pair:
        spec["contrast_pair"] = pair
    if source:
        spec["expected_sources"] = [source]
        spec["expected_evidence"] = [{"source": source, "section": DOC[source]}]
    if tool:
        rules = {}
        if "knowledge.search" in tool:
            rules["knowledge.search"] = {"topK": {"min": 1, "max": 10}}
        if "task.list_open" in tool:
            rules["task.list_open"] = {"limit": {"min": 1, "max": 20}}
        if "project.list_recent_activity" in tool:
            rules["project.list_recent_activity"] = {"limit": {"min": 1, "max": 20}}
        if rules:
            spec["expected_tool_args"] = rules
    spec.update(extra or {})
    CASES.append(spec)


# Each pair asks about nearby concepts while changing the required evidence boundary.
PAIRS = [
    ("DIRECT", "任务优先级一般如何分级？", "ONLY_TOOL", "当前项目最需要优先处理的开放任务是哪几项？", ["task.list_open"], None),
    ("DIRECT", "常见的任务状态机为什么禁止任意跳转？", "ONLY_TOOL", "这里还有哪些任务尚未关闭？", ["task.list_open"], None),
    ("DIRECT", "项目概况通常包含哪些信息？", "ONLY_TOOL", "这个 DevPilot 项目当前的概况是什么？", ["project.get_summary"], None),
    ("DIRECT", "项目活动时间线一般有什么用途？", "ONLY_TOOL", "最近这个项目发生了哪些任务活动？", ["project.list_recent_activity"], None),
    ("DIRECT", "什么因素通常构成发布紧急度？", "ONLY_TOOL", "目前哪些开放项又紧急又逾期？", ["task.list_open"], None),
    ("DIRECT", "截止日期的风险通常怎么评估？", "ONLY_TOOL", "现在有哪些活明天就到期？", ["task.list_open"], None),
    ("DIRECT", "什么是 Transactional Outbox？", "ONLY_RAG", "DevPilot 当前的 Outbox 恢复流程怎样工作？", ["knowledge.search"], "01-system-architecture.md"),
    ("DIRECT", "为什么 Agent 需要 Checkpoint？", "ONLY_RAG", "这里 Resume 如何验证原 Run 身份？", ["knowledge.search"], "02-agent-runtime.md"),
    ("DIRECT", "长期记忆通常为什么要分作用域？", "ONLY_RAG", "DevPilot 的 Memory 如何隔离不同 Workspace？", ["knowledge.search"], "05-context-memory.md"),
    ("DIRECT", "SSE 和普通 HTTP 请求在超时上有何区别？", "ONLY_RAG", "当前 Gateway 的 SSE 超时策略是什么？", ["knowledge.search"], "03-gateway-sse.md"),
    ("DIRECT", "混合检索为什么会结合稠密与稀疏信号？", "ONLY_RAG", "这个系统的 Knowledge Search 如何融合并重排候选？", ["knowledge.search"], "04-rag-retrieval.md"),
    ("DIRECT", "RBAC 为什么应在执行时重新检查？", "ONLY_RAG", "这里的 Tool Gateway 如何恢复 actor 并做权限校验？", ["knowledge.search"], "07-auth-rbac.md"),
    ("ONLY_TOOL", "根据当前逾期任务判断优先级。", "HYBRID", "结合当前逾期任务和可靠性上线约束判断发布风险。", ["task.list_open", "knowledge.search"], "06-reliability-hitl.md"),
    ("ONLY_TOOL", "最近几次任务状态变化是什么？", "HYBRID", "结合最近的任务活动和架构中的状态归属分析风险。", ["project.list_recent_activity", "knowledge.search"], "01-system-architecture.md"),
    ("ONLY_TOOL", "当前哪个开放任务涉及 Memory？", "HYBRID", "结合开放的 Memory 任务和作用域隔离规则指出风险。", ["task.list_open", "knowledge.search"], "05-context-memory.md"),
    ("ONLY_TOOL", "现在 Gateway 相关的活进展如何？", "HYBRID", "结合当前 Gateway 任务和 SSE 长连接规则判断阻塞点。", ["task.list_open", "knowledge.search"], "03-gateway-sse.md"),
    ("ONLY_TOOL", "目前这个项目有哪些未完成工作？", "HYBRID", "结合未完成工作与 roadmap 的建议，挑出需要先验证的领域。", ["task.list_open", "knowledge.search"], "11-roadmap.md"),
    ("ONLY_TOOL", "最近项目发生了什么？", "HYBRID", "结合近期活动与检索设计说明，指出知识入库的潜在回归风险。", ["project.list_recent_activity", "knowledge.search"], "04-rag-retrieval.md"),
]

for number, (left_route, left_query, right_route, right_query, right_tools, source) in enumerate(PAIRS, 1):
    pair = f"contrast-{number:02}"
    left_tool = (["task.list_open"] if "任务" in left_query or "活" in left_query or "工作" in left_query
                 else ["project.list_recent_activity"] if "活动" in left_query or "发生" in left_query
                 else ["project.get_summary"] if "概况" in left_query else []) if left_route == "ONLY_TOOL" else []
    if left_route == "ONLY_TOOL" and not left_tool:
        left_tool = ["task.list_open"]
    add(left_route, left_query, tool=left_tool, pair=pair,
        difficulty="EASY" if number <= 12 else "MEDIUM",
        tags=("contrast", "live") if left_route == "ONLY_TOOL" else ("contrast",),
        holdout=number in {4, 9, 14})
    add(right_route, right_query, tool=right_tools, source=source,
        pair=pair, difficulty="HARD" if number in {13, 15, 17} else "MEDIUM",
        tags=("contrast", "hybrid") if right_route == "HYBRID" else ("contrast",),
        holdout=number in {4, 9, 14})


TOOL = [
    ("现在项目里有哪些 open task？", ["task.list_open"]),
    ("还有哪些活没干完？", ["task.list_open"]),
    ("列一下目前仍未关闭的任务。", ["task.list_open"]),
    ("哪些高优先级事项尚未开始？", ["task.list_open"]),
    ("最新的任务分配或退回记录是什么？", ["project.list_recent_activity"]),
    ("这个项目当前状态和可见范围是什么？", ["project.get_summary"]),
]
for i, (query, tools) in enumerate(TOOL):
    add("ONLY_TOOL", query, tool=tools, difficulty="EASY",
        tags=("tool", "live", "paraphrase") if i < 3 else ("tool", "live"),
        holdout=i in {2, 5})

RAG = [
    ("为什么客户端断流不能直接取消 AgentRun？", "02-agent-runtime.md"),
    ("文档上传成功以后还需要什么条件才能用于检索？", "08-deployment-runbook.md"),
    ("过期的写入提案应该如何处理？", "06-reliability-hitl.md"),
    ("RAG chunk 的来源标识现在能精确到什么程度？", "04-rag-retrieval.md"),
    ("为什么长期偏好不能跨项目共享？", "05-context-memory.md"),
    ("旧终态事件缓存不在了，客户端该怎样继续？", "03-gateway-sse.md"),
    ("如何判断 TEI 失败发生在上传之后的哪一段？", "09-incident-postmortem.md"),
    ("知识检索为什么需要 Java 再次核对权限？", "07-auth-rbac.md"),
    ("为什么不能用过往 roadmap 回答今天的任务状态？", "11-roadmap.md"),
    ("为什么不能让 Python 直接写任务表？", "12-deprecated-design.md"),
    ("回归评估中 Dev 和 Holdout 如何分工？", "10-evaluation-strategy.md"),
    ("工具结果截断可能怎样影响上下文证据？", "05-context-memory.md"),
]
for i, (query, source) in enumerate(RAG):
    add("ONLY_RAG", query, tool=["knowledge.search"], source=source,
        difficulty=("HARD" if i in {3, 6, 8, 11} else
                    "EASY" if i in {0, 1} else "MEDIUM"),
        tags=("rag", "negative_live") if i == 8 else ("rag",),
        holdout=i in {1, 4, 8, 10})

HYBRID = [
    ("哪些逾期开放项违反了批准恢复的安全预期？", "06-reliability-hitl.md"),
    ("结合当前 TEI 任务与入库健康规则，哪些项目风险需要先处理？", "08-deployment-runbook.md"),
    ("参考当前 SSE 任务和 Gateway 规则，发布前应核对什么？", "03-gateway-sse.md"),
    ("结合当前 Tool Gateway 待办与权限边界，指出两个回归点。", "07-auth-rbac.md"),
    ("根据当前 Memory 工作和上下文预算设计，哪项更紧迫？", "05-context-memory.md"),
    ("对照开放的 Eval 工作与评测策略，下一步先收集什么证据？", "10-evaluation-strategy.md"),
    ("最近 Activity 中与知识入库有关的变化，按事故复盘应怎样排查？", "09-incident-postmortem.md"),
    ("开放的 Outbox 工作与恢复设计之间有哪些交付风险？", "01-system-architecture.md"),
    ("当前哪些任务可能受 Checkpoint 恢复规则影响？", "02-agent-runtime.md"),
    ("结合当前检索相关待办与 chunking 现状，实验应如何排序？", "04-rag-retrieval.md"),
    ("最近任务活动是否提示架构边界的偏离？", "01-system-architecture.md"),
    ("结合仍开放的任务与废弃方案，哪些误用要明确排除？", "12-deprecated-design.md"),
]
for i, (query, source) in enumerate(HYBRID):
    live_tool = "project.list_recent_activity" if i in {6, 10} else "task.list_open"
    add("HYBRID", query, tool=[live_tool, "knowledge.search"], source=source,
        difficulty="HARD" if i in {1, 5, 9} else "MEDIUM", tags=("hybrid", "live"),
        holdout=i in {1, 5, 8, 11})

MEMORY = [
    ("以后给这个项目架构建议时优先复用 Java Tool Gateway。", "下次架构建议优先复用什么边界？", True, True, None),
    ("我今天有点困。", "你保存了什么长期项目偏好？", False, False, None),
    ("我的 API Key 是 sk-example-do-not-store。", "你保存了我刚才说的密钥吗？", False, False, None),
    ("以后提到知识检索时先核对 READY 状态。", "我们之前约定检索前先核对什么？", True, True, None),
    ("项目建议请先检查 RBAC 边界。", "在另一个项目你会沿用刚才的偏好吗？", True, False, "OTHER_PROJECT"),
    ("我偏好从 Java 业务状态出发分析。", "在另一个 Workspace 还会记住这个偏好吗？", True, False, "OTHER_WORKSPACE"),
    ("以后先给短结论再列证据。", "现在请给出我的项目建议格式偏好。", True, True, None),
    ("把偏好更新为先列证据再给结论。", "我现在的建议格式偏好是什么？", True, True, None),
]
for i, (first, second, write, recall, scope) in enumerate(MEMORY):
    steps = [{"query": first, "expect_memory_write": write},
             {"query": second, "expect_memory_recall": recall}]
    extra = {"steps": steps, "expected_memory_write": False}
    if i == 7:
        extra["steps"] = [
            {"query": "本项目架构建议请先下结论，再列证据。", "expect_memory_write": True},
            {"query": first, "expect_memory_write": True},
            {"query": second, "expect_memory_recall": True},
        ]
        extra["answer_assertions"] = {"must_include_any": ["先列证据", "证据在前"]}
    if scope:
        steps[1]["scope"] = scope
        extra["preconditions"] = {"required_scope": scope}
    add("MEMORY", None, difficulty="HARD" if scope or i in {2, 7} else "MEDIUM",
        tags=("memory", "scope_isolation") if scope else ("memory",),
        holdout=i in {2, 5}, extra=extra)

RELIABILITY = [
    ("请为 Gateway SSE 超时回归新建任务，先让我批准。", "proposal"),
    ("请拟议一条 AgentRun Cancel 终态验证任务，不要直接写入。", "proposal"),
    ("请发起待审批的 Checkpoint Resume 回归任务。", "reject"),
    ("请提议创建 Tool Gateway 权限回归任务，等待审批。", "approve_once"),
    ("请提议创建一个测试 Proposal 过期行为的任务。", "expire"),
    ("请发起待审批任务，然后验证取消后的终态。", "cancel"),
]
for i, (query, scenario) in enumerate(RELIABILITY):
    add("ONLY_TOOL", query, tool=["task.create"], difficulty="HARD",
        tags=("reliability", "hitl"), holdout=i in {2, 5},
        extra={"expected_status": ("WAITING_APPROVAL" if scenario == "proposal" else
                                   "CANCELLED" if scenario == "cancel" else "SUCCEEDED"),
               "reliability": {"scenario": scenario,
                                "write_without_approval": False,
                                "duplicate_side_effect": False,
                                "unauthorized_tool_execution": False}})

# These facts are projected from the manifest rather than retyped in the JSONL.
TASK_FACT_QUERIES = {
    "当前项目最需要优先处理的开放任务是哪几项？": ["T01", "T05", "T08"],
    "目前哪些开放项又紧急又逾期？": ["T01", "T05", "T08"],
    "根据当前逾期任务判断优先级。": MANIFEST["expected_business_facts"]["overdue_open_fixture_ids"],
    "结合当前逾期任务和可靠性上线约束判断发布风险。":
        MANIFEST["expected_business_facts"]["overdue_open_fixture_ids"],
    "当前哪个开放任务涉及 Memory？": ["T05"],
    "现在 Gateway 相关的活进展如何？": ["T01", "T21"],
}
for case in CASES:
    identifiers = TASK_FACT_QUERIES.get(case["query"])
    if identifiers:
        case["expected_task_fixture_ids"] = identifiers
        case["answer_assertions"] = {"must_include_any": [
            " ".join(TASK[identifier]["title"].split("] ", 1)[1].split()[:2])
            for identifier in identifiers
        ]}


def write():
    if len(CASES) != 80 or len({case["query"] for case in CASES if case["query"]}) != 72:
        raise ValueError("dataset count or query uniqueness changed")
    counts = Counter(case["split"] for case in CASES)
    if counts != {"dev": 60, "holdout": 20}:
        raise ValueError(f"unexpected split: {counts}")
    DATASETS.mkdir(parents=True, exist_ok=True)
    for split in ("dev", "holdout"):
        path = DATASETS / f"devpilot_e2e_v2_{split}.jsonl"
        path.write_text("".join(json.dumps(case, ensure_ascii=False, separators=(",", ":")) + "\n"
                                for case in CASES if case["split"] == split), encoding="utf-8")


if __name__ == "__main__":
    write()

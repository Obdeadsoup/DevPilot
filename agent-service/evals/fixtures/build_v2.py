"""Materialize the reviewed, deterministic V2 fixture and dataset assets.

Run only when intentionally editing the fixture. Eval itself reads the committed JSON/Markdown.
"""

# ruff: noqa: E501  -- fixture prose is kept one item per line for editorial review.

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DATASETS = ROOT.parent / "datasets"

TOPICS = [
    ("Gateway SSE 长连接 response timeout 调整", "IN_PROGRESS", "URGENT", "OVERDUE", True,
     "Core 的 AgentRun 事件经 Gateway 到浏览器。SSE 路由需避免继承普通 REST 短响应超时；本任务核对连接维持、终态关闭与断线重放，逾期仍开放会影响运行结果可见性。"),
    ("AgentRun gRPC streaming backpressure 检查", "IN_PROGRESS", "HIGH", "SOON", True,
     "Java 通过 gRPC Server Streaming 接收 Python 运行事件，Python 侧有有界队列。检查慢消费、取消传播和终态一致性，避免队列拥塞掩盖已完成 Run。"),
    ("Planner 低置信度 fallback 策略观察", "TODO", "HIGH", "SOON", False,
     "Query Planner 输出路线与置信度；低置信度时需观察 fallback、错误率和 P95。先保留现行阈值作真实基线，再用 Dev 集做单变量实验。"),
    ("RAG structure-aware chunking 实验", "BACKLOG", "MEDIUM", "FUTURE", False,
     "当前 KnowledgeChunker 按长度并优先段落、换行边界切片，未显式保存 section ID。拟比较结构感知切片与当前策略的 Recall@K、MRR 和入库成本。Status: PROPOSED。"),
    ("Memory workspace project user scope 隔离回归", "IN_REVIEW", "URGENT", "OVERDUE", True,
     "长期 Memory 按 workspace、project、user 作用域检索。回归需要验证同 scope 可召回，跨项目或跨 workspace 不召回，且敏感文本不会进入安全追踪。"),
    ("task.create Proposal 过期状态处理", "IN_PROGRESS", "HIGH", "OVERDUE", True,
     "写工具先生成 Proposal 并等待批准。核对过期后不可执行、拒绝后没有 Task 副作用，以及批准恢复时仅执行一次。测试需经过真实应用服务。"),
    ("LangGraph Checkpoint Resume 故障恢复测试", "IN_REVIEW", "HIGH", "SOON", True,
     "生产 Python Runtime 以 LangGraph checkpoint 管理 continuation；Resume 需要原 runId 与 requestId，并验证可恢复状态。关注失败后的重复 Tool 副作用。"),
    ("TEI embedding 容器内存占用诊断", "TODO", "URGENT", "OVERDUE", False,
     "Knowledge ingestion 会调用 TEI embedding。历史诊断发现模型下载与容器内存可能使文档停在 FAILED；需观察真实 READY 率、failureCode 与容器健康。"),
    ("MyBatis AgentRun 查询 SQL 拼接回归", "DONE", "URGENT", "NONE", True,
     "AgentRun 列表查询曾受 SQL 拼接问题影响。已完成回归，后续只需保持针对真实 MySQL 的定向测试，避免把已完成紧急项计入开放风险。"),
    ("Eval V2 holdout dataset 构造", "TODO", "MEDIUM", "FUTURE", False,
     "固定 Fixture 上的 Dev 数据用于调整，Holdout 用于最终评估。维护去重与 contrast pair 标注，避免重复针对失败题修改 Planner Prompt。"),
    ("RBAC Tool Gateway actor scope 复核", "IN_PROGRESS", "URGENT", "SOON", True,
     "Python Tool Gateway 调用 Java 时通过 runId 还原 actor 和 workspace/project scope，Java 重新鉴权。需核对跨 scope 拒绝与任务读取权限。"),
    ("Transactional Outbox stale PROCESSING 恢复", "IN_REVIEW", "HIGH", "OVERDUE", True,
     "Task 事件与状态同事务持久化，Outbox Worker 条件 claim 并恢复 stale PROCESSING。审查失败重试、DEAD 与人工 Replay，不假定 exactly-once 交付。"),
    ("Audit Replay reason 与 expectedVersion 校验", "TODO", "HIGH", "FUTURE", True,
     "DEAD Replay 需要管理员授权、原因和 expectedVersion。Audit 保留追加式记录；检查拒绝路径和版本冲突不会产生新的有效 Replay。"),
    ("SMTP verification Redis 限流核查", "BACKLOG", "LOW", "NONE", False,
     "注册邮箱验证码经 VerificationCodeService 发放，Redis 基础防刷参与限制。检查 SMTP 配置失败时的用户反馈与运维信息，不在 Fixture 中存放邮箱凭据。"),
    ("Docker TEI reranker 冷启动观测", "IN_PROGRESS", "MEDIUM", "FUTURE", True,
     "Knowledge reranker 的 TEI 冷启动和分批调用影响检索延迟。记录健康检查、内存峰值及 30 秒知识工具 deadline 的剩余空间。"),
    ("GitHub integration webhook 去重验证", "TODO", "MEDIUM", "NONE", False,
     "Webhook Delivery 先落库再异步处理，重复或乱序事件可能到达。检查稳定来源键与唯一约束是否阻止重复 Project Activity。"),
    ("Context budget 工具结果截断回归", "IN_REVIEW", "MEDIUM", "SOON", True,
     "Context Manager 有消息预算和摘要；Tool result 截断必须仍保留有效 JSON 与来源元数据。检查多轮查询后是否丢失关键知识证据。"),
    ("RAG query rewrite conversation history 测试", "BACKLOG", "MEDIUM", "FUTURE", False,
     "Knowledge Search 支持有限 conversationHistory 和 rewrittenQuery。检验代词指向、项目语境和语义检索的关系，避免文档标题词匹配掩盖改写缺陷。"),
    ("AgentRun Cancel 终态与 SSE 断流区分", "IN_PROGRESS", "HIGH", "OVERDUE", True,
     "浏览器断开 SSE 不能自动视作 CancelRun；只有显式取消意图经持久状态机传播。核对取消终态、重连和旧终态 replay-gap。"),
    ("Project Recent Activity Task 事件完整性", "TODO", "LOW", "FUTURE", False,
     "Task 创建、状态流转与分配会在业务事务内生成 Project Activity。检查近期列表排序、来源和重复事件去重，不直接伪造 Activity 表。"),
    ("Gateway REST 短超时默认值复核", "BACKLOG", "LOW", "NONE", False,
     "普通 REST 使用短响应超时，与 AgentRun SSE 专用长连接路由分开配置。核对配置文档和 Gateway 路由优先级，防止互相覆盖。"),
    ("Knowledge ingestion FAILED retry API 记录", "DONE", "HIGH", "NONE", True,
     "知识文档 FAILED 可通过带 expectedVersion 的 retry API 重试，完成时需重新观察 READY 和 chunkCount；上传成功本身不代表入库成功。"),
    ("Java Python RPC service key 边界测试", "DONE", "MEDIUM", "NONE", False,
     "Python 到 Java 的 Tool Gateway 使用内部 service key，Java 仍以 Run 的 actor 执行 RBAC。已完成边界测试，凭据不进入日志或 Eval 报告。"),
    ("Notification 任务提醒去重回归", "DONE", "LOW", "NONE", False,
     "Task 即时事件经 Outbox 触发通知，数据库通知记录是可靠来源，SSE 只是低延迟提示。已完成去重回归，不再占开放任务额度。"),
    ("Planner route contrast set 初版复核", "DONE", "MEDIUM", "NONE", True,
     "已整理一般知识、实时项目事实和架构文档三类相似问法的对照。后续应观察真实混淆矩阵，避免仅用显式 README 关键词评测。"),
    ("旧版无权限 Tool 直连方案废弃", "CANCELED", "LOW", "NONE", False,
     "历史设想曾允许 Python 直接读取业务表。该方案与 Java 权威状态及 RBAC 边界冲突，已取消，不应作为现行架构依据。"),
    ("旧版无确认 task.create 路径废弃", "CANCELED", "HIGH", "NONE", False,
     "曾讨论让 Agent 直接创建 Task；当前产品通过 Proposal、人工批准和 Java 应用服务写入。旧方案取消，评测应标记未批准写入为硬失败。"),
    ("旧版断线即 CancelRun 方案废弃", "CANCELED", "MEDIUM", "NONE", False,
     "将 SSE 客户端断开当作取消会误伤仍在运行的 AgentRun。现行链路使用显式 CancelRun；旧方案已取消，仅作为负面对照。"),
]

DOCS = {
    "01-system-architecture.md": ("System Ownership Boundary", "DevPilot 是模块化 Java Core 加独立 Python Agent 服务。Java 拥有 RBAC、事务、Task 与 AgentRun 权威状态；Python 负责模型编排，通过 gRPC 交互，不直连 dp_* 业务表。", "Project State Sources", "Project Summary 从 Project 应用服务读取；Recent Activity 来自 Task 和 GitHub 的真实业务活动，Task 事件在原写事务中记录。"),
    "02-agent-runtime.md": ("Runtime Ownership Boundary", "AgentRun 入口在 Java，Python LangGraph 负责 Planner、Tool、RAG 与 continuation。Java gRPC streaming 到 SSE，Run 身份用于 Tool Gateway 恢复 actor scope。", "Checkpoint Resume Safety", "Resume 只接受原 runId/requestId，检查 checkpoint 与可恢复状态；写路径等待 Proposal 批准。SSE 客户端断开不等于显式 CancelRun。"),
    "03-gateway-sse.md": ("SSE Timeout Policy", "Gateway 为 AgentRun SSE 显式取消普通 REST 响应超时；普通 REST 仍保持短超时。浏览器终态后关闭连接，异常断开先查询 Java 权威 Run 状态。", "Replay Gap", "Last-Event-ID 支持有界重放。Core 重启后若旧终态缓存缺失，SSE 发送 replay-gap 并关闭，客户端仍可通过 History 获取权威终态。"),
    "04-rag-retrieval.md": ("Retrieval Pipeline", "Knowledge 上传后异步 ingestion，解析、按长度与段落边界 chunk、调用 TEI embedding、持久化 chunk。检索结合 dense/sparse 融合和 rerank，返回 sourceFile、chunkId、分数。", "Query Rewrite", "检索可利用有限 conversationHistory 改写查询。当前 chunkId 与文件来源可追踪，但 section ID 尚不是服务端稳定字段；Manifest 的 section 标题用于人工证据核对。"),
    "05-context-memory.md": ("Memory Scope Isolation", "长期记忆按 workspace、project、user scope 绑定。相同 scope 的稳定偏好可以召回，跨 Project 或 Workspace 不应召回；敏感凭据不应写入。", "Context Budget", "Context Manager 有消息预算、摘要与 Tool 结果截断。截断后仍需保留有效 JSON 的来源标识；不要把原始 Memory 或 RAG 正文写入公共 Safe Trace。"),
    "06-reliability-hitl.md": ("Approval Safety Rules", "task.create 是高风险写工具。Agent 只能先创建 Proposal；批准后由 Java 应用服务执行，拒绝和过期不能产生 Task。版本与状态校验保护并发。", "Resume and Cancel", "Checkpoint 恢复须验证身份与状态，避免重复副作用。Cancel 是显式持久意图，SSE 断线不能直接视为取消；Run 终态由 Java 投影为准。"),
    "07-auth-rbac.md": ("Tool Gateway Authorization", "Python 调 Java Tool Gateway 时使用内部 service key；Java 从持久 Run 恢复 actor、workspace、project，并在每次 Tool 调用重新验证 RBAC。", "Knowledge Access", "Knowledge 上传需要 KNOWLEDGE_MANAGE，列表和检索需要读取权限。文档属于 Project scope，评测不得跨 scope 借用检索结果。"),
    "08-deployment-runbook.md": ("TEI Health and Ingestion", "Knowledge ingestion 依赖对象存储和 TEI embedding；FAILED 会记录 failureCode，retry 需 expectedVersion。文档只有 READY 且 chunk 可检索才满足评测前置条件。", "Full Stack Startup", "真实评测需要 Java Core、Python Agent、MySQL、Redis、对象存储、Embedding、Reranker 与 Tool Gateway。FAKE 模式仅测试评测器自身。"),
    "09-incident-postmortem.md": ("Embedding Failure Boundary", "历史诊断中 TEI 模型下载和容器内存导致 ingestion 失败；上传成功不能代表 READY。排查应先看文档 status/failureCode，再看 TEI health 与日志。", "SSE Disconnect Diagnosis", "曾出现 SSE 异步认证与终态连接问题；修复后终态流正常关闭。浏览器断线需核对 Java Run 状态，不能推断 Python Agent 已取消。"),
    "10-evaluation-strategy.md": ("Deterministic Evidence", "路由、工具名称、合法参数范围、来源文件与 chunk 身份、Memory 写入事实和控制流可用确定性指标。回答正确性及 groundedness 需抽样 Judge 与人工核查。", "Holdout Discipline", "Dev 集可用于少量变量的实验；Holdout 用于最终结论，避免按失败题反复定向改 Prompt。所有报告记录 dataset hash、fixture version 与运行环境。"),
    "11-roadmap.md": ("Status: PROPOSED", "结构感知 chunking 和更细粒度 section ID 是评测实验候选，不代表当前 Knowledge 已实现。比较前须保留当前切片策略的真实基线。", "Evaluation Priorities", "近期工作建议关注 Planner fallback、RAG Recall@K、Tool Gateway RBAC 和批准恢复幂等性。此文档是计划，不能替代实时 Task 状态。"),
    "12-deprecated-design.md": ("Status: DEPRECATED", "Python 直接读写 Java 业务表、未批准执行 task.create、SSE 断线自动 CancelRun 都是废弃设想，不是现行产品能力。", "Current Replacement", "Java 应用服务与 RBAC 是业务边界；写工具先 Proposal；取消必须显式请求。历史方案只供负面对照，不能作为实时项目事实。"),
}


def write_assets():
    knowledge = ROOT / "knowledge"
    knowledge.mkdir(parents=True, exist_ok=True)
    tasks = []
    for index, (name, status, priority, due, assigned, description) in enumerate(TOPICS, 1):
        item = {"fixture_id": f"T{index:02}", "title": f"[EVAL-V2][T{index:02}] {name}",
                "description": description, "status": status, "priority": priority,
                "due": due, "assigned": assigned}
        if index == 2:
            item["activity_sequence"] = ["plan", "start", "submit-for-review", "request-changes"]
        if index == 9:
            item["activity_sequence"] = ["plan", "start", "submit-for-review", "complete",
                                         "reopen", "start", "submit-for-review", "complete"]
        if index == 11:
            item["activity_sequence"] = ["unassign"]
        tasks.append(item)
    documents = []
    for filename, (section1, text1, section2, text2) in DOCS.items():
        (knowledge / filename).write_text(
            f"# [EVAL-V2] {filename.removesuffix('.md')}\n\n"
            f"## {section1}\n\n{text1}\n\n## {section2}\n\n{text2}\n",
            encoding="utf-8")
        documents.append({"source": filename, "sections": [section1, section2]})
    manifest = {"fixture_version": "devpilot-eval-v2",
                "target_project_expectations": {"explicit_ids_required": True,
                                                "prefer_dedicated_project": True,
                                                "open_task_tool_limit": 20},
                "tasks": tasks, "documents": documents,
                "expected_business_facts": {
                    "open_task_count": 20, "overdue_open_fixture_ids": ["T01", "T05", "T06", "T08", "T12", "T19"],
                    "completed_urgent_fixture_ids": ["T09"]},
                "smoke_queries": [
                    {"query": "为什么客户端断开不能直接视为 CancelRun？",
                     "expected_sources": ["02-agent-runtime.md", "06-reliability-hitl.md", "03-gateway-sse.md"]},
                    {"query": "不同 Workspace 的长期偏好为什么需要隔离？",
                     "expected_sources": ["05-context-memory.md"]},
                    {"query": "知识文档上传后为什么还要等待 READY？",
                     "expected_sources": ["04-rag-retrieval.md", "08-deployment-runbook.md"]},
                ]}
    (ROOT / "manifest_v2.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
                                            encoding="utf-8")


if __name__ == "__main__":
    write_assets()

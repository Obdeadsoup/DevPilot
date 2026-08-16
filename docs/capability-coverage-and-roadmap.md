# 能力覆盖与开发路线

## 是否补全黑马点评缺少的能力

结论：能，而且业务比客服工单更贴合你的真实经历。

| 能力 | DevPilot 场景 | 重点 |
|---|---|---|
| 权限 | 工作空间 RBAC、项目成员、私有仓库、Agent 权限继承 | 接口权限、数据范围、资源归属 |
| 状态机 | Delivery、同步任务、本地任务、Agent Proposal | 非法流转、领域动作、乐观锁、历史 |
| SLA/SLO | 任务截止、PR Review 超时、Webhook 处理时限、同步积压 | 定时扫描、提醒、升级、幂等 |
| 通知 | 分配、Review、到期、同步失败、Agent 待确认 | 事件驱动、站内通知、SSE |
| 审计 | 权限、仓库绑定、任务、人工重试、Agent 执行 | 安全审计、业务审计、脱敏 |
| Agent 工具 | 项目查询、周报、任务草案、Issue 创建 | Tool Calling、权限、人工确认、幂等 |
| 外部集成 | GitHub Webhook/API | 验签、分页、限流、超时、重试 |
| 一致性 | Delivery 唯一约束、Outbox、定时对账 | 幂等、最终一致性、补偿 |

注意：不应为了简历生搬客服 SLA。在 DevPilot 中将它落地为研发协作时效规则和系统处理 SLO，反而更真实。

## 阶段路线

### 阶段 0：工程骨架

Maven 多模块、Boot、MySQL/Redis、Flyway、Actuator、测试环境、统一响应异常。

### 阶段 1：身份与权限

登录、工作空间、成员、项目、RBAC、资源归属、越权测试、基础审计。

### 阶段 2：Webhook 垂直链路

```text
绑定仓库 → ping/push → 验签 → Delivery 幂等 → 异步解析 → 活动时间线
```

必须掌握原始请求体、HMAC、唯一约束、状态机、事务边界和重复事件。

当前已完成 Repository Binding 的 Project 作用域生命周期、可信元数据校验、API Credential 与 Webhook
Secret 分离，以及活动 Binding 生成列唯一约束。工程化读取 Client 已完成固定 Host、Timeout、统一错误、
GET/HEAD 有限 Retry、Primary/Secondary Rate Limit、Link Cursor、Conditional GET、单实例 Credential
并发限制和低基数指标。Commit Webhook/API 双入口、Repository ID + SHA 幂等、List Commits Link 分页、
Checkpoint overlap 和 Sync Run 有限重试/DEAD/超时恢复已经完成。Issue/PR/Review Webhook 强类型解析、
当前快照、显式 Diff、统一 Upsert、Project 范围只读 API 和有界 API 对账也已完成。

### 阶段 3：可靠同步

Commit 对账已具备 API `RETRY_WAIT/DEAD`、数据库 version claim、定时恢复和受 RBAC 保护的人工 202 触发。
DEAD 查询、GitHub Sync MANUAL_REPLAY 与 append-only Audit 已完成；Replay 不回退 Checkpoint，并继续沿用
overlap 与下游幂等。GitHub API Token 仍是环境引用，GitHub App Authentication 尚未实现。当前数据库 claim
已提供跨实例单 Run 互斥；Credential Semaphore 仍只限制单 JVM 的 HTTP 并发。

### 阶段 4：任务与通知

本地 Task 状态机、Issue/PR 显式关联、乐观锁、状态 History 与 Project Activity 已完成。Task 截止、
逾期升级、Task/PR Review 超时与可靠站内通知已完成。Task 六类即时事件已通过 MySQL Transactional
Outbox 实现可靠落库和有限重试；单实例 SSE 提供多连接、Heartbeat 与低延迟提示，断线由 REST 补偿。
邮件、第三方 Channel、跨实例 SSE 和精确一次送达尚未实现，GitHub 状态不会自动修改 Task。

### 阶段 5：工程化

**传统后端工程化主线已阶段性完成。** Outbox、两层消费幂等、DEAD 管理、六类 Task V1 受控人工重放与 SUCCESS/FAILURE/DENIED Audit 已落地。
第 15 节已增加安全 Correlation ID、有限异步 MDC、Micrometer/Prometheus-ready endpoint、Delivery/Sync/Outbox
backlog snapshot、oldest age、open DEAD 与 liveness/readiness。原 DEAD 不修改；新 Replay 仍经过原 Worker。
第 16 节以 ArchUnit、Backend CI、测试矩阵、JMeter 可执行基线和 Freeze Checklist 固化工程边界。性能尚未
实跑，不能据此声明生产容量或 SLO/SLA。RabbitMQ/Kafka、CDC、跨实例广播、Audit WORM、OpenTelemetry、
Grafana、Alertmanager 和生产 SLO/SLA 验证仍是后续能力。

下一阶段路线：`Frontend/E2E alignment → Knowledge / Agent L1`。前端先与当前后端契约和权限边界全面对齐，
再进入知识库与 Agent；这里的阶段性完成不等于生产就绪。

Agent L0 边界骨架已建立：Java `devpilot-agent` 作为集成/Application Boundary，Python `agent-service` 作为
Runtime，`contracts/agent/v1` 作为唯一跨语言契约源，并明确数据所有权与双向 RPC 方向。第 2 章已增加 Python
单进程、FakeModel 驱动的最小 Agent Loop，用于验证结构化 ToolCall 与停止条件；它仍不表示 Agent L1、真实 LLM、
业务 Tool 或跨进程 RPC 已实现。

### 阶段 6：知识库与 Agent L1

项目文档、会议纪要、检索、项目问答、活动总结、来源引用和权限过滤。

### 阶段 7：Agent L2/L3

任务/Issue 草案、风险等级、人工确认、一次性令牌、GitHub 写 API、工具审计、Prompt Injection 防护、MCP。

## 每条链路的学习验收

你必须能回答：为什么需要、表为何这样设计、事务边界、失败状态、重复请求、并发冲突、权限、日志指标、测试风险、Agent 是否可能绕过权限。

## 不要强行堆

- 不提前拆微服务。
- 不用分布式锁代替唯一约束或乐观锁。
- 不一开始上 MQ。
- 不伪造 SLA 和高并发。
- 不把普通聊天框称作 Agent。

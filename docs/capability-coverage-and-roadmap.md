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
下一步实现 GitHub App Token、DEAD 管理/审计和更完整的人工重放。当前数据库 claim
已提供跨实例单 Run 互斥；Credential Semaphore 仍只限制单 JVM 的 HTTP 并发。

### 阶段 4：任务与通知

本地 Task 状态机、Issue/PR 显式关联、乐观锁、状态 History 与 Project Activity 已完成。截止提醒、Review
超时、站内通知和 SSE 尚未实现；GitHub 状态不会自动修改 Task。

### 阶段 5：工程化

Outbox、RabbitMQ、消费幂等、Testcontainers、ArchUnit、Prometheus、CI、压测和故障演练。

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

# DevPilot 系统架构

## 原则

采用模块化单体；外部事件先可靠落库再异步处理；Agent 只能调用应用服务；高风险写操作人工确认；优先使用数据库约束和乐观锁；外部调用显式处理超时、分页、限流、重试和幂等。

## 模块

```text
devpilot-boot            启动和装配
devpilot-framework       响应、异常、Trace、分页、安全上下文接口
devpilot-identity        用户、工作空间成员、角色、权限
devpilot-project         工作空间、项目、项目成员
devpilot-github          仓库绑定、Webhook、API Client、同步
devpilot-task            任务、迭代、状态机、截止规则
devpilot-notification    通知和未读计数
devpilot-audit           安全、业务和 Agent 审计
devpilot-knowledge       文档、会议纪要、检索
devpilot-agent           会话、工具、提议、确认和执行
```

初始依赖：identity→framework，project→framework，github→framework+project，boot→全部初始模块。禁止 project 反向依赖 github。

## Webhook 链路

```text
GitHub
→ WebhookController
→ SignatureVerifier
→ DeliveryService 幂等落库
→ 快速返回 200/202
→ DeliveryWorker 抢占任务
→ EventParser
→ ActivityService
→ 状态更新为 SUCCEEDED/RETRY_WAIT/DEAD
```

请求线程只做仓库定位、验签、幂等落库和返回。

## Delivery 状态机

```text
RECEIVED → PROCESSING → SUCCEEDED
             ↑        ├→ RETRY_WAIT
             └────────┘
                      └→ DEAD

超时 PROCESSING → RETRY_WAIT 或 DEAD
```

`RECEIVED → PROCESSING` 和到期的 `RETRY_WAIT → PROCESSING` 使用状态、到期时间与
`version` 条件更新抢占。每次失败增加 `retry_count`；可重试且未超过上限时进入
`RETRY_WAIT`，不可重试或超过上限时进入 `DEAD`。数据库扫描恢复丢失内存事件的
`RECEIVED`、到期 `RETRY_WAIT` 和超时 `PROCESSING`。`FAILED` 保留用于兼容 V1，
不再是普通自动失败路径。人工重放、审计和消息队列尚未实现。

## GitHub API Client

统一处理 Authorization、API 版本、连接/读取超时、403/429、Retry-After、X-RateLimit-*、分页、指数退避、结构化错误和指标。业务服务不得自行拼 HTTP 请求。

## 一致性

- `github_delivery_id` 唯一约束防重复。
- 重要业务变更与 Outbox 同事务。
- 消费者以 event_id 去重。
- Webhook 保证实时性，定时 API 对账保证完整性。

## 权限

三层：接口权限、数据范围、资源归属。所有业务查询必须带 workspace/project 范围。前端隐藏按钮不构成安全控制。

## 任务状态机

提供 `moveToTodo`、`startTask`、`submitForReview`、`requestChanges`、`completeTask`、`cancelTask`。每个动作负责状态、权限、负责人、乐观锁、状态历史、事件和通知。

## Agent 架构

```text
用户
→ Agent Orchestrator
→ 权限与风险策略
→ 只读工具 / 提议工具
→ 人工确认
→ 执行服务
→ 正式业务应用服务
→ 审计
```

执行工具只接受已保存并已确认的 Proposal ID，不直接接受自由文本改数据。

## 测试

- 单元：验签、Delivery/任务状态机、权限、退避、Agent 风险。
- 集成：唯一约束、乐观锁、任务抢占、Outbox、Flyway。
- 合约：保存的 GitHub Payload 样例。
- 接口：越权、签名错误、重复 Delivery、分页和错误码。
- 架构：ArchUnit 限制 Controller/Agent/Framework 依赖。

# DevPilot 系统架构

## Notification 末端聚合

`notification -> identity/project/task/github`，上游模块不反向依赖 notification。Task 候选、Project
Manager 与 PR Review 状态均通过中立只读 Port 提供；Scheduler 只触发 fixedDelay 扫描。每条通知在独立
短事务 INSERT，重复扫描与多实例并发由 `(recipient_user_id, dedupe_key)` 唯一索引收敛。数据库 Notification
是可靠来源。Task 即时事件已使用 Transactional Outbox，单实例 SSE 仅作低延迟提示；邮件、跨实例广播和
分布式锁未实现。

## 原则

采用模块化单体；外部事件先可靠落库再异步处理；Agent 只能调用应用服务；高风险写操作人工确认；优先使用数据库约束和乐观锁；外部调用显式处理超时、分页、限流、重试和幂等。

## 模块

```text
devpilot-boot            启动和装配
devpilot-framework       响应、异常和通用基础设施
devpilot-identity        用户、登录认证、Principal、Access Token 和安全过滤器
devpilot-project         Workspace/Project、成员、RBAC、生命周期和活动
devpilot-github          仓库绑定、Webhook、API Client、同步
devpilot-task            任务、迭代、状态机、截止规则
devpilot-notification    通知和未读计数
devpilot-outbox          Task 事务事件、有限重试与恢复
devpilot-audit           DEAD 查询、人工 Replay 与 append-only 运维审计
devpilot-knowledge       文档、会议纪要、检索
devpilot-agent           Agent 集成边界：Browser API、RBAC、Run 投影、RPC、Tool Gateway、人工确认
```

当前依赖：identity→framework，project→framework+identity，task→framework+identity+project+outbox，
github→framework+project+task，notification→framework+identity+project+task+github+outbox，
audit→framework+identity+project+github+outbox，boot→全部模块。audit 是末端运维模块，上游不反向依赖。
Agent 第 1 章新增 agent→framework+identity+project，boot→agent；当前 agent 没有 Java 业务类，也不依赖
task/github/outbox/audit。Python `agent-service` 是独立进程和独立 Python 工程，不是 Maven dependency；第 2 章只在
该 Python 工程内增加同步最小 Agent Loop，未改变 Java 模块图。
Workspace、两级成员关系、角色、Permission
和授权服务全部属于 project；identity 只向 project 提供当前用户和用户有效性能力，绝不反向
依赖 project。移除 Workspace Member 与撤销其 Project Membership 在 project 模块同一事务
内完成。禁止 project 依赖 github。

## Workspace / Project 生命周期

```text
Workspace: ACTIVE ──disable──> DISABLED
           ACTIVE <─reactivate─ DISABLED

Project:   PLANNING ──activate──> ACTIVE
           PLANNING ──archive───> ARCHIVED
           ACTIVE   ──archive───> ARCHIVED
           ACTIVE   <─restore──── ARCHIVED
```

每条变更语句都带资源 Scope、允许的当前状态、`deleted = 0` 和 expected version，并原子执行
`version = version + 1`。应用只暴露语义明确的动作，不提供通用 `updateStatus`。状态表示业务
生命周期；`deleted` 是独立的逻辑删除维度，本阶段没有 Project 删除接口。

Project 创建时初始为 PLANNING，Key 规范化后不可通过资料接口修改。Workspace Owner/Admin
凭继承权限管理项目；被允许创建项目的普通 Workspace Member 会成为该项目的
PROJECT_ADMIN，避免创建后无管理权限。

## Webhook 链路

```text
GitHub
→ WebhookController
→ SignatureVerifier
→ DeliveryService 幂等落库
→ 快速返回 200/202
→ DeliveryWorker 抢占任务
→ EventParser
→ Commit/Issue/PR/Review ApplicationService（Webhook/API 统一 Upsert）
→ SnapshotDiff + ActivityService（安全摘要 + 原 Push 聚合）
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
不再是普通自动失败路径。Outbox 与 GitHub Sync Run 已支持受 scope/RBAC 保护的人工 Replay；Delivery
专用 Replay 与消息队列尚未实现。

## DEAD Replay 与 Audit

```text
管理员查询 DEAD 摘要 → scope/RBAC → reason + expectedVersion
→ 锁定原 DEAD（不修改）→ 创建新 PENDING Replay → 同事务 SUCCESS Audit
→ COMMIT 后快速唤醒 → 原 Worker/Retry/幂等链路
```

Outbox 只允许六类 Task V1 事件重放；GitHub Sync 使用 MANUAL_REPLAY，且不回退 Checkpoint。
失败或拒绝操作通过 REQUIRES_NEW 写 FAILURE/DENIED Audit，Audit Mapper 不提供 UPDATE/DELETE。
第 15 节起 HTTP 请求通过安全格式的 `X-Correlation-ID` 写入 MDC，新 Replay Audit 尽量保存该值；历史记录
仍允许 NULL。它仅用于单实例日志关联，不是权限凭证、业务幂等键或 OpenTelemetry Trace。

## GitHub API Client

```text
Binding Service
→ Repository Metadata Client（Endpoint 与响应映射）
→ GitHubApiHttpExecutor（统一 HTTP 语义）
→ AccessTokenProvider + Credential Semaphore + RestClient
→ GitHub API
```

生产 Endpoint 固定为 `https://api.github.com`，测试 Profile 只允许配置 loopback Mock Host。Executor
动态添加 Bearer Token，统一连接/读取 Timeout、同源重定向、错误分类、安全日志和 Micrometer 指标。
只有 GET/HEAD 的网络错误、临时 5xx 和有证据的 Rate Limit 可有限 Retry；等待超过同步上限时返回带
`retryAt` 的错误，不阻塞 Web 线程。Link Header 必须经同源校验后才能形成下一页 Cursor。

API Credential 与 Webhook Secret 分别由独立白名单 Reference Provider/Resolver 提供。前者只供 REST
API，后者只供原始 Payload HMAC 验签，数据库和响应均不保存/返回原始值。每 Credential Semaphore 的
Key 是 Reference SHA-256，只限制单 JVM，不同 Credential 不共享全局锁。

Repository Metadata 刷新使用 Conditional GET。200 校验稳定 Repository ID 后更新权威字段、ETag、
Last-Modified、验证时间和 version；304 不进入 Error Decoder，不覆盖元数据，只更新验证时间并 version+1。
Issue/PR/Review 业务分页已经复用该 Executor；GitHub App Token 尚未实现。

## Commit 对账链路

```text
Scheduler / Manual 202
→ SyncRunService（发现、权限、提交）
→ ReconciliationService（Worker 时 version claim）
→ Active Binding + Workspace/Project 状态校验
→ Checkpoint 计算 initialLookback / overlap since
→ Metadata Client 复核稳定 Repository ID
→ Commit Client + Link Cursor
→ 每个 Commit 统一 Upsert 短事务
→ 每页成功后更新页级进度
→ 可靠 Checkpoint + Run SUCCEEDED 同事务
```

`PENDING → RUNNING → SUCCEEDED/RETRY_WAIT/DEAD` 和 `RETRY_WAIT → RUNNING` 使用数据库状态、到期时间与
version 条件更新。超时 RUNNING 会被扫描恢复到 RETRY_WAIT 或 DEAD。Scheduler 的扫描/线程池提交不是
claim；多个实例可重复看到候选，只有 Worker 开始时的条件 UPDATE 决定执行权。线程池拒绝发生在 claim 前，
不会制造假 RUNNING。

Reconciliation 编排方法没有长事务。Metadata 与分页网络调用在事务外；单 Commit + 首次 Activity、页级
Checkpoint、最终 Checkpoint + SUCCEEDED 都是短事务，失败状态使用独立事务记录。可靠边界来自本轮最大
GitHub `committed_at`，不是本机当前时间。默认 5 分钟 overlap 会重复读取，由 Repository ID + SHA 和
Activity 来源唯一键消化。

## Repository Binding 生命周期

```text
bind → ACTIVE ⇄ DISABLED
          │          │
          └─ unbind ─┘ → deleted history
```

所有用户操作先经过 Project 作用域 `REPOSITORY_*` 权限。状态动作使用
`id + workspace_id + project_id + deleted + current status + version` 条件更新。GitHub 数字 Repository ID
作为稳定身份；rename 只更新元数据。V6 的活动生成列唯一索引保证同一真实仓库同时最多一个活动 Binding，
并允许解绑后多轮重绑。Delivery 与 Activity 历史不随解绑删除。

## 一致性

- `github_delivery_id` 唯一约束防重复接收。
- `(github_repository_id, commit_sha)` 防 Webhook/API 双入口重复 Commit。
- Activity 来源唯一键防重复业务时间线。
- 开放 Sync Run 唯一键防重复任务，version 条件 UPDATE 防重复 claim 和旧状态覆盖。
- Webhook 保证实时性，数据库 Run 驱动的定时 API 对账保证 Commit 完整性。
- Task 即时通知已使用 MySQL Transactional Outbox；RabbitMQ/Kafka 等 MQ 未实现。

## Issue / PR / Review 快照同步

```text
Webhook Delivery / API Reconciliation
→ 强类型 Issue / PR / Review Parser 或 Client DTO
→ 统一 Snapshot Upsert
→ github_updated_at 乱序保护 + content_hash 幂等
→ version 条件 UPDATE
→ 显式 Snapshot Diff
→ 每个来源键最多一条 Project Activity
```

`dp_github_issue`、`dp_github_pull_request`、`dp_github_pull_request_review` 保存的是当前快照，不是完整事件历史。
PR 的 `OPEN/CLOSED/MERGED` 与 `draft` 分开；Webhook action 也不写入 status。初始 API Backfill 不创建
“刚刚发生”的 Activity；后续 API 对账只在出现语义差异时使用内容 Hash 派生稳定来源键。Issue API
必须过滤含 `pull_request` 字段的条目，PR ID 只来自 Pull Requests API。

Review 对账不会每轮扫描全部历史 PR：只选择初始 lookback 内活跃、且 `reviews_synced_at` 为空或早于
`github_updated_at` 的有限批次。一个 PR 的全部 Review 页成功落库后才推进其水位；整轮完成后再推进
`PULL_REQUEST_REVIEW` Checkpoint。外部 login 只作为展示元数据，外部正文永不构造本地权限或系统命令。

## 权限

当前使用固定内置角色和权限码，不创建动态角色表：

- Workspace：`OWNER / ADMIN / MEMBER / VIEWER`。OWNER 由
  `dp_workspace.owner_user_id` 推导，其他角色来自 ACTIVE
  `dp_workspace_member`。
- Project：`PROJECT_ADMIN / DEVELOPER / VIEWER`。Workspace OWNER/ADMIN
  对本 Workspace 的 Project 获得 PROJECT_ADMIN 等效权限。
- PRIVATE 项目要求非 Workspace OWNER/ADMIN 用户具有 ACTIVE Project Membership；
  INTERNAL 项目向 ACTIVE Workspace Member 提供只读权限，写权限仍要求 Project
  Membership。

鉴权分三层：Security Filter Chain 处理登录状态，`@EnableMethodSecurity` 与
`@PreAuthorize` 检查具体 Permission，授权服务和 Mapper 同时校验
`workspaceId + projectId` 的资源归属。`ProjectActivityService.queryTimeline` 使用
`PROJECT_ACTIVITY_READ`；Webhook 异步写 Activity 不依赖登录用户。跨 Workspace 或缺少
资源权限统一在用户接口授权边界返回 JSON 403，未认证返回 JSON 401。前端隐藏按钮不构成
安全控制。

Workspace/Project 角色不写入 Access Token，每次从当前数据库状态计算。成员角色变更和所有权
转移使用 `version` 条件 UPDATE；移除 Workspace Member 与撤销其 Project Membership
处于同一事务。

Workspace 与 Project 的列表范围也在 SQL 中实施，不先读取全表再在 Java 过滤。Workspace
列表只返回当前用户拥有或拥有 ACTIVE Membership 的记录；Project 列表要求 ACTIVE
Workspace 关系，OWNER/ADMIN 可见全部，普通成员可见 INTERNAL，而 PRIVATE 还要求
ACTIVE Project Membership。状态与可见性筛选使用枚举白名单。

GitHub Webhook 的 sender 和仓库权限属于外部元数据。`actor_login` 不恢复本地
Authentication，GitHub App permission 也不替代 DevPilot 的本地授权。

## 任务状态机

提供 `plan`、`returnToBacklog`、`startTask`、`submitForReview`、`requestChanges`、`completeTask`、
`cancelTask`、`reopenTask`。每个动作负责状态、权限、负责人、乐观锁、状态历史和 Project Activity；
Issue/PR/Review Snapshot 不会自动推进 Task。Task 通过 Port 读取 GitHub Snapshot，避免 task→github
循环依赖。

## Transactional Outbox 与即时通知

`task -> outbox` 只调用中立 Publisher，`notification -> task + outbox` 注册白名单 Handler；outbox 不依赖
任何业务模块，task 也不反向依赖 notification。Task/History/Activity/Outbox 同事务，提交后本地事件仅作
快速唤醒，数据库 Scanner 负责恢复。Worker 用 status+version claim；Notification 副作用与 PROCESSED
在一个事务内。`event_key` 与 `recipient_user_id+dedupe_key` 构成发布和业务两层幂等。

Notification commit 后通过 AFTER_COMMIT 向当前 JVM 的 `SseEmitter` Registry 尽力发送最小 ID Envelope。
Registry 支持每用户多连接、上限淘汰、Heartbeat 和失败清理。SSE 不改变 Outbox/Notification 语义；断线和
跨实例遗漏由 REST 查询数据库补偿。Outbox 与 GitHub Sync 已提供受控 DEAD 人工重放和 Audit；跨实例广播、
MQ 与 CDC 未实现。

## 架构守护、CI 与性能基线

`devpilot-boot` 装配全部模块，因此 ArchUnit 在其 test classpath 扫描生产 class。规则守住业务模块无环、
Framework 单向依赖、已批准模块方向、Controller 不直连 Mapper、Domain 不依赖 Web/Persistence 技术实现，
以及跨模块不得直连其他模块的 Mapper/Entity/Repository 实现。Application Service 访问本模块 Persistence
属于当前明确边界，不做形式化禁止。

首次规则执行发现 Task 曾直接注入 Identity `UserMapper` 和 Project `ProjectMapper/ProjectEntity`。当前已分别改为
`IdentityUserEligibilityService` 与 `ProjectTaskContextQuery` + 最小 DTO，规则没有为既有违规设置豁免。

Backend CI 在 Pull Request、main push 和手工触发时使用 JDK 21 执行完整 Maven verify 与 Compose 配置验证；
它不部署应用，也不访问真实 GitHub。JMeter 是人工 non-GUI 基线，读链路与每线程独立 Task Workflow 都保留
鉴权、事务和日志，并与第 15 节 Metrics 同时间窗分析，不进入普通 PR Gate。

## Observability 与 Health

```text
HTTP → CorrelationIdFilter → MDC/Response Header → Controller/Service/Audit
                         └→ GitHub TaskDecorator（有限传播）
Database 状态机 → 模块 Metrics façade → Micrometer → Prometheus Registry
模块 Backlog Mapper → 周期 Snapshot → AtomicReference → Gauge（不执行 SQL）
Actuator → livenessState / readinessState+db+redis
```

Delivery、Sync Run、Outbox、Notification/SSE 与 Replay 在各自模块的真实边界发 Counter/Timer；Tag 只来自固定
状态/资源/事件白名单。GitHub 与 Outbox 各自查询内部表并刷新 immutable backlog snapshot，失败保留 last good。
Historical DEAD Counter 与当前 open DEAD Gauge 分离，成功/开放 Replay 会解决原 DEAD 的 open 状态。

liveness 不检查数据库、Redis、GitHub 或 backlog，防止外部故障触发重启风暴；readiness 包含当前核心依赖的
MySQL/Redis，但不包含 GitHub API 或业务积压。默认只暴露 health；local/observability 才暴露 metrics/prometheus。
完整边界见第 15 节学习文档。OpenTelemetry、生产 Prometheus、Grafana、Alertmanager 尚未实现。

## Agent 架构

```text
Browser → Java devpilot-agent（HTTP / RBAC / Scope / Run 业务投影）
        → AgentRuntime gRPC → Python agent-service（LLM / Agent Loop / runtime state）

Python agent-service → DevPilotToolGateway gRPC → Java devpilot-agent
        → 权限与风险策略 → 只读工具 / Proposal → 人工确认
        → 正式业务 Application Service → 审计
```

`devpilot-agent` 是 Agent Integration / Application Boundary，不是 Agent Runtime。User/RBAC、Workspace/Project、
GitHub Snapshot、Task、Audit/Notification 和未来 AgentRun 业务投影归 Java；执行期 step/checkpoint 与
Conversation runtime context 归 Python。两个进程不共享业务表，Python 不访问 `dp_*`，Java 不读取 Python
Runtime 内部表。

跨语言唯一契约源位于 `contracts/agent/v1`：`AgentRuntime` 是 Java→Python，`DevPilotToolGateway` 是
Python→Java。当前已有 Python 单进程内的 Message → Model → ToolCall → Tool Result → Model → Final 控制循环；
仍没有 Stub、网络调用、HTTP API、AgentRun 表、真实 LLM 或 DevPilot 业务 Tool。
未来执行工具只接受已保存并已确认的 Proposal ID，不直接接受自由文本改数据。

## 测试

- 单元：验签、Delivery/任务状态机、权限、退避、Agent 风险。
- 集成：成员唯一约束、复合外键、乐观锁、所有权并发转移、Delivery 抢占和 Flyway。
- 合约：保存的 GitHub Payload 样例。
- 接口：越权、签名错误、重复 Delivery、分页和错误码。
- 架构：ArchUnit 限制 Controller/Agent/Framework 依赖。

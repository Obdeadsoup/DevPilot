# DevPilot 系统架构

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
devpilot-audit           安全、业务和 Agent 审计
devpilot-knowledge       文档、会议纪要、检索
devpilot-agent           会话、工具、提议、确认和执行
```

当前依赖：identity→framework，project→framework+identity，
github→framework+project，boot→全部初始模块。Workspace、两级成员关系、角色、Permission
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
Issue/PR 业务分页、GitHub App Token 和后台 API 同步状态机尚未实现。

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

- `github_delivery_id` 唯一约束防重复。
- 重要业务变更与 Outbox 同事务。
- 消费者以 event_id 去重。
- Webhook 保证实时性，定时 API 对账保证完整性。

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
- 集成：成员唯一约束、复合外键、乐观锁、所有权并发转移、Delivery 抢占和 Flyway。
- 合约：保存的 GitHub Payload 样例。
- 接口：越权、签名错误、重复 Delivery、分页和错误码。
- 架构：ArchUnit 限制 Controller/Agent/Framework 依赖。

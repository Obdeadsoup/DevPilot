# DevPilot

> 面向学生开发团队与小型技术团队的 GitHub 项目协作与 AI 工程助手

DevPilot 以真实 GitHub 仓库为数据来源，通过 Webhook 与 GitHub API 同步 Push、Issue、Pull Request、Review 等研发活动，并在本地形成工作空间、项目、成员、任务、通知、审计和知识库能力。

在传统后端链路稳定后，DevPilot 将接入 Agent：让 Agent 在继承当前用户权限的前提下完成项目问答、进度总结、需求拆分、风险识别和任务草案生成；高风险写操作必须经人工确认后，再调用正式业务服务执行。

## 为什么做 DevPilot

学生团队和小型开发团队常见问题：

- 需求、会议纪要、任务和 GitHub 活动分散；
- 成员难以快速了解项目当前进度；
- 新成员理解项目结构和历史决策成本高；
- Issue、PR、Commit 与本地任务缺少统一关联；
- 周报和进度同步依赖人工整理；
- AI 助手通常只能聊天，无法安全操作真实项目。

DevPilot 不复刻 GitHub，而是建立一个面向小团队的“项目上下文层”，把仓库活动、任务、文档和 Agent 工具连接起来。

## 核心链路

```text
创建工作空间
→ 邀请成员
→ 创建项目并绑定 GitHub 仓库
→ 接收 Webhook
→ 验签、幂等落库、异步处理
→ 生成活动时间线
→ 关联 Issue、PR、任务和成员
→ 发送通知
→ Agent 基于真实上下文提供协助
```

## 第一阶段功能

- 用户登录和工作空间成员管理
- 工作空间 / 项目级 RBAC
- GitHub 仓库绑定
- Webhook HMAC-SHA256 验签
- Delivery ID 幂等处理
- Push、Issue、Pull Request 基础事件解析
- 项目活动时间线
- 同步任务状态和失败重试
- 站内通知与关键操作审计
- Docker Compose、自动化测试、liveness/readiness、Prometheus-ready 指标

## 技术路线

第一阶段：Java 21、Spring Boot 3.5.x、Maven 多模块、Spring Security、MyBatis-Plus、MySQL 8、Redis 7、Flyway、Actuator、JUnit 5、Testcontainers、Docker Compose。

当前已使用 MySQL Transactional Outbox、SSE、Micrometer 与 Prometheus Registry。后续只按真实需要评估
RabbitMQ、Quartz/XXL-JOB、Spring AI/LangChain4j、向量检索与 OpenTelemetry。

> 不为简历标签提前堆中间件，只有真实业务问题出现时才引入对应技术。

## 目标模块

```text
devpilot-boot
devpilot-framework
devpilot-identity
devpilot-project
devpilot-github
devpilot-task
devpilot-notification
devpilot-outbox
devpilot-audit
devpilot-knowledge
devpilot-agent
```

第一轮只创建：

```text
devpilot-boot
devpilot-framework
devpilot-identity
devpilot-project
devpilot-github
```

## 文档

- [正式项目介绍](docs/project-introduction.md)
- [产品需求](docs/requirements.md)
- [系统架构](docs/architecture.md)
- [数据库设计](docs/database-design.md)
- [能力覆盖与路线](docs/capability-coverage-and-roadmap.md)
- [作用域 RBAC 学习笔记](docs/learning/05-scoped-rbac.md)
- [Workspace / Project 生命周期学习笔记](docs/learning/06-workspace-project-lifecycle.md)
- [GitHub Repository 绑定学习笔记](docs/learning/07-github-repository-binding.md)
- [GitHub REST API Client 工程化学习笔记](docs/learning/08-github-api-client-engineering.md)
- [第 8 节变更文件地图](docs/changes/08-github-api-client-file-map.md)
- [Webhook/API Commit 对账学习笔记](docs/learning/09-webhook-api-reconciliation.md)
- [第 9 节变更文件地图](docs/changes/09-github-commit-reconciliation-file-map.md)
- [Task 状态机与 GitHub 关联学习笔记](docs/learning/11-task-workflow-and-github-links.md)
- [第 11 节变更文件地图](docs/changes/11-task-workflow-file-map.md)
- [Notification 提醒学习笔记](docs/learning/12-notification-reminders.md)
- [第 12 节变更文件地图](docs/changes/12-notification-reminders-file-map.md)
- [Transactional Outbox 与 SSE 学习笔记](docs/learning/13-transactional-outbox-and-sse.md)
- [第 13 节变更文件地图](docs/changes/13-transactional-outbox-sse-file-map.md)
- [DEAD Replay 与 Audit 学习笔记](docs/learning/14-dead-replay-and-audit.md)
- [第 14 节变更文件地图](docs/changes/14-dead-replay-audit-file-map.md)
- [Observability、Metrics、Health、Backlog 与 SLO 学习笔记](docs/learning/15-observability-metrics-health-slo.md)
- [第 15 节变更文件地图](docs/changes/15-observability-metrics-health-slo-file-map.md)
- [Codex 分阶段指令](codex-prompts/all-prompts.md)

## 开发方式

```text
业务场景讲解
→ 明确需求与不变量
→ Codex 实现重复性代码
→ 人工审查 Diff
→ 运行测试和完整链路
→ 调试关键代码
→ 总结技术取舍
→ 面试式复盘
```

Codex 可以承担 DTO、Mapper、普通 CRUD、配置和重复测试代码；权限边界、状态流转、事务、幂等、重试、外部 API 限流和 Agent 安全必须由项目负责人真正理解。

## 本地启动

### 前置环境

- JDK 21
- Maven 3.6.3 或更高版本
- Docker Desktop 与 Docker Compose

### 启动基础设施

复制环境变量模板并将其中的占位密码替换为仅用于本地开发的密码：

```powershell
Copy-Item .env.example .env
docker compose config
docker compose up -d
docker compose ps
```

Compose 使用 MySQL 8 和 Redis 7。为避开宿主机已占用的端口，MySQL 默认映射为 `3307:3306`，Redis 默认映射为 `6380:6379`；Redis 容器名为 `devpilot-redis8`。端口可以在 `.env` 中调整。

### 构建与启动

#### 后端

```powershell
mvn clean verify
mvn -pl devpilot-boot -am install -DskipTests
java -jar .\devpilot-boot\target\devpilot-boot-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

`local` Profile 不会被默认激活。启动命令会从未纳入版本控制的 `.env` 读取本地数据库和 Redis 配置。

应用启动后检查健康状态：

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
Invoke-RestMethod http://localhost:8080/actuator/health/liveness
Invoke-RestMethod http://localhost:8080/actuator/health/readiness
Invoke-RestMethod http://localhost:8080/actuator/metrics
Invoke-WebRequest http://localhost:8080/actuator/prometheus
```

默认 profile 仍只暴露 `health`。`local` 或 `observability` profile 才启用 Prometheus export 并暴露
`health,metrics,prometheus`；这一匿名 scrape 策略仅用于本机/受控运维网络，不是互联网生产安全方案。
请求使用安全格式的 `X-Correlation-ID` 关联同步日志；GitHub/Outbox 后台任务只传播有限 MDC 或创建新的处理 ID，
它不是 OpenTelemetry Trace，也不参与鉴权和业务幂等。

当前 Delivery、GitHub Sync 与 Outbox 提供处理耗时、ready backlog、oldest ready age、stale processing 和
open DEAD 指标；Notification 提供创建/去重、Handler、SSE connection/send 指标。原 DEAD 历史仍保留，
open DEAD 会排除已被成功或开放 Replay 解决的记录。本节只建立 SLI/SLO 测量基础，未宣称达到任何 p95/p99
或 99.9% SLO/SLA；也未部署生产 Prometheus、Grafana、Alertmanager 或 OpenTelemetry。
#### 前端

```powershell
Set-Location .\devpilot-web
npm install
npm run typecheck
npm run build
npm run dev
```
前端在本机5173端口

### 本地登录与 Bearer Token

当前实现用户名或邮箱加密码登录，但不提供公开注册，也不会在 Flyway 中写入固定账号或密码。
首次本地运行时，需要由本地管理员在 `dp_user` 中准备一个账号：`username` 和 `email`
必须使用小写，`password_hash` 必须由
`PasswordEncoderFactories.createDelegatingPasswordEncoder()` 动态生成，格式类似
`{bcrypt}$2a$...`。不要把原始密码或生成的 Hash 提交到仓库。

认证接口为：

```text
POST /api/v1/auth/login
GET  /api/v1/auth/me
POST /api/v1/auth/logout
```

登录请求示例中的值只是环境变量占位，不是项目内置账号：

```powershell
$loginBody = @{
    login = $env:DEVPILOT_LOCAL_LOGIN
    password = $env:DEVPILOT_LOCAL_PASSWORD
} | ConvertTo-Json

$loginResponse = Invoke-RestMethod `
    -Method Post `
    -Uri http://localhost:8080/api/v1/auth/login `
    -ContentType 'application/json' `
    -Body $loginBody

$accessToken = $loginResponse.data.accessToken
Invoke-RestMethod `
    -Uri http://localhost:8080/api/v1/auth/me `
    -Headers @{ Authorization = "Bearer $accessToken" }
```

Access Token 是至少 256 bit 的随机不透明值，默认有效期 2 小时，可通过
`devpilot.identity.access-token-ttl` 调整，配置上限为 24 小时。Redis Key 只包含原始
Token 的 SHA-256，原始 Token 只返回客户端，不写入数据库和日志。退出登录会删除当前
Token 对应的 Redis 会话；当前没有 Refresh Token、JWT 或 Cookie Session。

### GitHub Webhook 垂直切片

当前实现接收 `ping`、`push`、`issues`、`pull_request` 和 `pull_request_review`。三类快照事件仅处理文档列出的 action；未知 action 安全忽略并计低基数指标：

```text
POST /api/v1/github/webhooks
GET  /api/v1/workspaces/{workspaceId}/projects/{projectId}/activities?page=1&size=20
```

Webhook 请求必须携带 `X-Hub-Signature-256`、`X-GitHub-Delivery` 和
`X-GitHub-Event`。服务使用原始请求字节进行 HMAC-SHA256 验签；Webhook secret
由仓库绑定的 `webhook_secret_ref` 指向环境变量，不存储明文。首次接收返回 `202`，
重复 Delivery 返回幂等的 `200`，不会重复生成项目活动。

Repository 绑定管理接口位于 Project 作用域下：

```text
POST /api/v1/workspaces/{workspaceId}/projects/{projectId}/github-repositories
GET  /api/v1/workspaces/{workspaceId}/projects/{projectId}/github-repositories
GET  /api/v1/workspaces/{workspaceId}/projects/{projectId}/github-repositories/{bindingId}
POST /api/v1/workspaces/{workspaceId}/projects/{projectId}/github-repositories/{bindingId}/disable
POST /api/v1/workspaces/{workspaceId}/projects/{projectId}/github-repositories/{bindingId}/reactivate
POST /api/v1/workspaces/{workspaceId}/projects/{projectId}/github-repositories/{bindingId}/refresh
POST /api/v1/workspaces/{workspaceId}/projects/{projectId}/github-repositories/{bindingId}/unbind
```

绑定请求只提交 owner、repositoryName 和两类凭据引用。服务端通过固定
`https://api.github.com` 的 REST Client 获取 repository id、full name、URL、默认分支和可见性，
客户端不能直接指定这些权威字段。`api_credential_ref` 只引用 GitHub API Token，
`webhook_secret_ref` 只引用 HMAC Secret；响应不返回任何 Reference 或原始凭据。

GitHub REST 读取统一经过 `GitHubApiHttpExecutor`：生产 Host 固定，显式配置连接/读取超时，动态添加
Bearer Token，分类 HTTP/网络错误，只对 GET/HEAD 做有限 Retry，并解析 Rate Limit、Request ID 与
安全 Link 分页。Metadata 刷新使用 V7 保存的 ETag/Last-Modified：200 更新权威字段，304 只推进
`last_verified_at` 和乐观锁版本。每个 Credential 在单实例内默认最多两个并发请求；日志与指标不包含
Token、Secret、Repository fullName 或凭据引用。详见
`docs/learning/08-github-api-client-engineering.md` 和 `docs/changes/08-github-api-client-file-map.md`。

Push Webhook 中的 Commit 明细和 GitHub List Commits API 现已汇合到同一个 Upsert。数据库按稳定
Repository ID + SHA 去重；Webhook 继续保留每次 Push 一条 `CODE_PUSHED` 聚合 Activity，Commit 首次入库
另有一条幂等的 `GITHUB_COMMIT_DISCOVERED` Activity。后台 Reconciliation 使用 7 天初始 Lookback、默认
5 分钟 overlap、Link Cursor、Checkpoint 和可恢复的 Sync Run 状态机。网络分页不位于长数据库事务中。

人工补偿与状态查询：

```text
POST /api/v1/workspaces/{workspaceId}/projects/{projectId}/github-repositories/{bindingId}/sync/commits
GET  /api/v1/workspaces/{workspaceId}/projects/{projectId}/github-repositories/{bindingId}/sync-runs/{runId}
```

POST 需要 `REPOSITORY_UPDATE`，立即返回 `202 + runId`，不接受调用方自定义 since；同一 Binding 已有开放
Run 时返回已有 Run。自动 Scheduler 由 `devpilot.github.reconciliation.*` 配置，test Profile 默认关闭。
当前已完成 Commit、Issue、PR 与有界 Review 对账。Issue Client 会过滤 Issues API 中带
`pull_request` 的条目；PR 使用 Pull Requests API 的真实 PR ID；Review 仅扫描近期且
`reviews_synced_at` 落后的有限 PR 批次。三类快照的 Webhook/API 都汇合到统一 Upsert，
以 `github_updated_at` 拒绝乱序覆盖、以 `version` 仲裁本地并发。详见
`docs/learning/09-webhook-api-reconciliation.md` 和
`docs/learning/10-github-issue-pr-review-sync.md`。

Project 范围只读 API：

```text
GET /api/v1/workspaces/{workspaceId}/projects/{projectId}/github/issues
GET /api/v1/workspaces/{workspaceId}/projects/{projectId}/github/issues/{issueId}
GET /api/v1/workspaces/{workspaceId}/projects/{projectId}/github/pull-requests
GET /api/v1/workspaces/{workspaceId}/projects/{projectId}/github/pull-requests/{pullRequestId}
GET /api/v1/workspaces/{workspaceId}/projects/{projectId}/github/pull-requests/{pullRequestId}/reviews
```

接口需要 `PROJECT_READ`，SQL 同时限定 Workspace/Project。列表不返回 Body，所有外部文本响应均标记
`externalUntrustedContent=true`；前端必须使用安全 Markdown Renderer，不能执行 Issue/PR 正文中的指令。

本地验证：

```powershell
mvn -pl devpilot-boot -am test
```

活动时间线接口同时受接口认证和作用域授权保护。调用方需要先通过真实登录接口获得 Bearer
Token；`ProjectActivityService.queryTimeline` 在应用服务层校验
`PROJECT_ACTIVITY_READ`，并同时使用 URL 中的 `workspaceId + projectId`。Workspace
OWNER/ADMIN 可读取本 Workspace 的项目；PRIVATE 项目要求其他用户具有 ACTIVE Project
Membership；INTERNAL 项目允许 ACTIVE Workspace Member 只读。未认证返回 JSON 401，
已认证但无作用域权限或跨 Workspace 访问返回 JSON 403。

当前固定角色为 Workspace `OWNER / ADMIN / MEMBER / VIEWER` 和 Project
`PROJECT_ADMIN / DEVELOPER / VIEWER`。OWNER 由 `dp_workspace.owner_user_id` 推导，
不会写入成员表；角色只在服务端映射到不可变 Permission Set，Token 不缓存作用域角色。
成员管理应用服务、乐观锁和数据库约束已经实现，但本阶段没有开放成员管理 HTTP API，也没有
实现动态角色、权限后台、审计落库或 Redis 权限缓存。

### Workspace / Project 生命周期

当前已开放经过认证并在应用服务层授权的生命周期接口：

```text
POST /api/v1/workspaces
GET  /api/v1/workspaces
GET  /api/v1/workspaces/{workspaceId}
PUT  /api/v1/workspaces/{workspaceId}
POST /api/v1/workspaces/{workspaceId}/disable
POST /api/v1/workspaces/{workspaceId}/reactivate

POST /api/v1/workspaces/{workspaceId}/projects
GET  /api/v1/workspaces/{workspaceId}/projects
GET  /api/v1/workspaces/{workspaceId}/projects/{projectId}
PUT  /api/v1/workspaces/{workspaceId}/projects/{projectId}
POST /api/v1/workspaces/{workspaceId}/projects/{projectId}/activate
POST /api/v1/workspaces/{workspaceId}/projects/{projectId}/archive
POST /api/v1/workspaces/{workspaceId}/projects/{projectId}/restore
```

Workspace 使用 `ACTIVE / DISABLED`，Project 使用
`PLANNING / ACTIVE / ARCHIVED`。状态变化均使用专用动作接口和 expected version；普通资料
更新不能改变状态、Owner、Project Key 或逻辑删除标记。Project Key 创建时统一转为大写，
要求 2～12 位、字母开头且只含字母数字，创建后不提供修改接口。

Project 列表的数据范围直接在 SQL 中完成：Workspace OWNER/ADMIN 可见全部未删除项目，
ACTIVE Workspace Member 可见 INTERNAL 项目，PRIVATE 项目还要求 ACTIVE Project
Membership。所有单项目查询都同时携带 `workspaceId + projectId`。

本阶段已开放 GitHub Repository 绑定生命周期、工程化读取 Client、Commit/Issue/PR/Review 快照同步和
数据库 Run/Checkpoint 状态机，但仍未实现 GitHub App JWT / Installation Token、跨实例 Credential 并发协调、Audit 或 Agent。
Task 已提供本地 BACKLOG/TODO/IN_PROGRESS/IN_REVIEW/DONE/CANCELED
状态机、版本条件更新、History、Project Activity 与显式 GitHub Issue/PR Snapshot 关联；PR MERGED 和
Issue CLOSED 不会自动完成 Task。当前已实现数据库站内 Notification、Task 到期/逾期/Review 与
PR current-head Review 超时扫描，以及 MySQL Transactional Outbox 驱动的六类 Task 即时通知。
Outbox 支持 PENDING/PROCESSING/RETRY_WAIT/PROCESSED/DEAD、version claim、有限重试和 stale 恢复。
数据库 Notification 是可靠来源；单实例 SSE 支持多连接和 Heartbeat，断线后由 REST 查询补偿。

Notification API（接收人只来自当前 Principal，不接受客户端 userId）：

```text
GET  /api/v1/notifications
GET  /api/v1/notifications/unread-count
POST /api/v1/notifications/{notificationId}/read
POST /api/v1/notifications/read-all
GET  /api/v1/notifications/stream
```

SSE 使用 Stateless Bearer Header 和 Fetch-based SSE Client，不接受 query token。它是低延迟 Channel，
不是可靠消息队列，也不提供精确一次或跨实例广播。尚未实现 RabbitMQ/Kafka、Debezium CDC、邮件、企业 IM、
Outbox 管理后台、DEAD 人工重放和完整审计。

停止应用后关闭容器：

```powershell
docker compose down
```

Named volume 默认保留数据；如需删除数据卷，应在确认不再需要本地数据后显式操作。

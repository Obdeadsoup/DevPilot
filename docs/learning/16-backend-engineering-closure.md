# 第 16 节：传统后端工程化收尾

## 为什么此时封板

DevPilot 已形成一条可学习、可验证的传统后端主线：Authentication 和 Scoped RBAC 保护 Workspace/Project；GitHub Binding、Webhook、REST API Client 与 Reconciliation 维护外部快照；本地 Task Workflow 通过乐观锁驱动状态迁移；Notification、Transactional Outbox、单实例 SSE、DEAD Replay/Audit 处理可靠通知和人工治理；Metrics/Health/Correlation 提供运行证据。

本节不继续增加中间件，而是把模块边界、CI、测试矩阵、性能实验方法和发布前清单固化。这里的“阶段性完成”表示学习与工程基线闭环，不表示生产就绪。

## ArchUnit 把约定变成可执行规则

根 POM 的真实依赖方向是：

```text
framework
identity → framework
project → framework, identity
outbox → framework
task → framework, identity, project, outbox
github → framework, project, task
notification → framework, identity, project, task, github, outbox
audit → framework, identity, project, github, outbox
boot → audit, outbox, notification, task, framework, identity, project, github
```

ArchUnit 从 boot 的完整运行时 classpath 扫描生产 class，守住业务模块无环、Framework 不反向依赖业务包、已批准模块方向、Controller 不直连 Mapper、Domain 不依赖 Web/Persistence 技术实现、Persistence 不反向依赖 API，以及跨模块不得直连对方 Mapper/Entity/Repository 实现。

Application Service 访问自己模块的 Persistence 是当前明确设计，因此没有为追求形式而禁止；跨模块协作则使用 application service、api/port 或中立 DTO。

## CI 是持续集成，不是持续部署

Backend CI 在 Pull Request、main push 和手工触发时使用 JDK 21 执行完整 `mvn clean verify`，ArchUnit 与 Testcontainers 自然包含在门禁内；随后验证 Compose 配置，失败时上传 Surefire/Failsafe 报告。它只需只读仓库权限，不带 PAT、不读取 `.env`、不访问真实 GitHub。

本节没有部署、镜像发布、Kubernetes、Release 或蓝绿流程，因此不能称为 CI/CD。

## 性能基线是可重复实验

读场景覆盖 Workspace、Project、Activity、Task 和未读通知计数。写场景让每个线程创建独立 Task，再用每次响应的 version 执行 plan → start → submit-for-review，避免把同一 Task 的乐观锁冲突误当成吞吐能力。Token 只来自进程环境，JMeter 使用 CLI/non-GUI，普通 PR 不自动跑压力测试。

结果必须同时记录 throughput、error %、p50/p95/p99/max，以及 JVM、Hikari、HTTP、Outbox 和 GitHub backlog/oldest age。延迟、连接池和可靠链路积压的同时间窗证据，比一个孤立 TPS 数字更有意义。

## 已阶段性完成的能力

- Authentication、Scoped RBAC、Workspace/Project 生命周期与范围查询；
- GitHub Binding、可靠 Webhook、工程化 API Client、Commit 对账、Issue/PR/Review 同步；
- Task Workflow 与 GitHub 资源显式关联；
- Notification、Transactional Outbox、单实例 SSE；
- Delivery/Sync/Outbox 有限重试、恢复、DEAD 治理、人工 Replay 与 Audit；
- Correlation ID、Metrics、Health、backlog/open DEAD 可观测性；
- Architecture Test、Backend CI、测试矩阵、性能基线方案和 Freeze Checklist。

## 明确暂缓

RabbitMQ/Kafka、CDC、跨实例 SSE、OpenTelemetry SDK、Grafana/Alertmanager、GitHub App Authentication、邮件/第三方通知、微服务与 Kubernetes 均未实现。它们是进一步的生产工程能力，不是当前进入 Frontend/E2E alignment 和 Knowledge/Agent L1 的必要前置。

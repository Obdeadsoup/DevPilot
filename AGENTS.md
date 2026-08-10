# AGENTS.md

## Project overview

This repository contains DevPilot, a GitHub-connected collaboration and AI engineering assistant for student development teams and small technical teams.

Before changing code, read README.md and every file under docs/.

## Current stage

The project is in the initial scaffold stage. During the first scaffold task, do not implement GitHub OAuth/App installation, Webhook business parsing, task management, notifications, audit AOP, message queues, knowledge indexing, Agent features, front-end applications, or microservices.

## Technology constraints

Use Java 21, Spring Boot 3.5.x, Maven multi-module, Spring MVC, Spring Security, MyBatis-Plus, MySQL 8, Redis 7, Flyway, Jakarta Validation, Actuator, JUnit 5, Testcontainers, and Docker Compose.

During scaffold stage do not add Spring Cloud, Kubernetes, Kafka, RabbitMQ, Elasticsearch, vector databases, LLM SDKs, or Lombok.

## Initial modules

Only create:

- devpilot-boot
- devpilot-framework
- devpilot-identity
- devpilot-project
- devpilot-github
- devpilot-task
- devpilot-notification
- devpilot-outbox
- devpilot-audit

Dependency direction:

- boot may depend on all initial modules
- identity -> framework
- project -> framework
- task -> framework, identity and project
- github -> framework, project and task
- notification -> framework, identity, project, task and github
- outbox -> framework
- task -> framework, identity, project and outbox
- notification -> framework, identity, project, task, github and outbox
- audit -> framework, identity, project, github and outbox
- project, github and outbox must not depend on audit
- task and notification may depend on outbox API; outbox must not depend on business modules
- boot may depend on notification; business modules must not depend on notification
- framework must not depend on business modules
- project must not depend on github
- avoid circular dependencies

Base package: `com.obdeadsoup.devpilot`.

## Coding rules

- Use constructor injection; no field injection.
- Controllers must not access Mapper classes directly.
- Do not put business rules in controllers.
- Do not expose persistence entities as API responses.
- GitHub integration must not bypass local application services.
- Do not swallow exceptions or log tokens, secrets, passwords, or raw private payloads.
- Do not hard-code environment credentials.
- Do not add dependencies without explaining why.
- Prefer simple explicit implementations over speculative abstractions.
- Do not create generic `updateStatus` APIs for domain state transitions.
- Prefer unique constraints or optimistic locks over unnecessary distributed locks.

## 学习型工程可读性规范

- Application Service、外部 API Client、Filter、Scheduler、Retry Policy、Failure Classifier、
  Credential Resolver、关键 Configuration 和复杂 Mapper 必须有简洁中文 JavaDoc，说明它在真实调用链中的职责。
- 复杂 public 方法的 JavaDoc 应按实际需要说明业务目的、前置条件、关键参数、状态/事务/并发语义、
  特殊返回值，以及可能抛出的稳定业务错误；不要为了凑格式重复方法签名已经表达的信息。
- 行内注释只解释“为什么”，优先覆盖 Rate Limit 判断、Retry 分类、ETag/304、SSRF 防护、凭据安全、
  version 条件和事务边界。
- 不为 getter/setter、简单 DTO、明显赋值、一眼可懂的普通 CRUD 或每一行代码添加翻译式注释。
- 注释使用简洁中文；HTTP、ETag、Rate Limit、Retry、Webhook 等标准术语保留英文。
- 注释必须与真实代码同步。修改实现时一并修正过期 JavaDoc 和行内注释，禁止让注释描述尚未实现的能力。

## GitHub integration rules

When implemented:

- Validate `X-Hub-Signature-256` against the raw request body.
- Use constant-time signature comparison.
- Use `X-GitHub-Delivery` as external delivery id.
- Enforce a database unique constraint for idempotency.
- Persist the delivery before asynchronous business processing.
- Return quickly after validation and persistence.
- Handle timeout, rate limit, retry, pagination, and token invalidation explicitly.
- Never commit GitHub tokens or webhook secrets.

## Agent rules

- Agent tools call application services, not Mapper classes.
- Read tools inherit current-user permissions.
- Write tools first create proposals.
- High-risk writes require explicit human confirmation.
- Confirmation tokens are short-lived and one-time.
- Every tool call is audited.
- Model output is never raw SQL.
- Prompt injection cannot elevate permissions.

## Configuration and testing

- Shared safe defaults in `application.yml`.
- Local config in `application-local.yml`.
- Isolated tests in `application-test.yml`.
- Secrets come from environment variables.
- Provide `.env.example`; never commit `.env`.
- Database changes use Flyway.
- After changes run relevant tests, `mvn clean verify`, and `docker compose config`.
- Report actual command results; never claim an unexecuted command succeeded.

## Change control

Before editing, inspect repository and Git status, read docs, summarize current state, present a plan, and list files to change.

After editing, summarize files, design decisions, commands, results, and remaining risks.

Do not commit, push, create PRs, or alter remotes unless explicitly requested.

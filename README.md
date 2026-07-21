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
- Docker Compose、自动化测试、健康检查

## 技术路线

第一阶段：Java 21、Spring Boot 3.5.x、Maven 多模块、Spring Security、MyBatis-Plus、MySQL 8、Redis 7、Flyway、Actuator、JUnit 5、Testcontainers、Docker Compose。

后续按真实需要引入 RabbitMQ、SSE、Quartz/XXL-JOB、Spring AI/LangChain4j、向量检索、Prometheus、OpenTelemetry。

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

### 构建与启动后端

```powershell
mvn clean verify
mvn -pl devpilot-boot -am spring-boot:run "-Dspring-boot.run.profiles=local"
```

`local` Profile 不会被默认激活。启动命令会从未纳入版本控制的 `.env` 读取本地数据库和 Redis 配置。

应用启动后检查健康状态：

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
```

停止应用后关闭容器：

```powershell
docker compose down
```

Named volume 默认保留数据；如需删除数据卷，应在确认不再需要本地数据后显式操作。

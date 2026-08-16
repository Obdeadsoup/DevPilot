# Agent 第 1 章：服务边界与数据所有权

## 决策

DevPilot 从本章开始为 Agent 能力预留两个独立进程，但只建立边界，不实现 Agent 功能：

```text
Browser
  │ HTTP
  ▼
Java DevPilot / devpilot-agent
  │ gRPC: AgentRuntime
  ▼
Python agent-service

Python agent-service
  │ gRPC: DevPilotToolGateway
  ▼
Java devpilot-agent → 正式业务 Application Service
```

Java 进程继续拥有认证、授权和业务事实；Python 进程未来承载 Agent Runtime。两个进程不共享业务表，所有跨边界
交互以 `contracts/agent/v1` 下的 `.proto` 为唯一契约来源。

## 为什么保留 Java `devpilot-agent`

微服务拆分后：

```text
devpilot-agent（Java） ≠ Agent Runtime
devpilot-agent（Java） = Agent Integration / Application Boundary
```

Java 模块未来负责：

- Browser-facing Agent HTTP API；
- 当前用户、Workspace 与 Project scope 校验；
- 用户可见 AgentRun 业务投影的 Application Boundary；
- Java → Python 的 gRPC Client Adapter；
- Python → Java 的 Tool Gateway；
- Proposal / Confirm 与高风险写操作的人工确认入口。

该模块不是 Python Runtime 的容器，也不能把 Agent 生成内容直接当成数据库写命令。未来只读 Tool 必须继承当前
用户权限；写 Tool 先保存 Proposal，高风险写入还需短时、一次性确认，并在执行前重新校验权限和记录审计。

## Python `agent-service` 的职责

Python 服务未来负责：

- LLM / Agent Loop 编排；
- 执行期 step、checkpoint 与 Conversation runtime context；
- 通过 Java Tool Gateway 发起受控工具调用；
- 向 Java 流式产生 Agent 运行事件；
- Agent Runtime 自身的日志、指标和追踪。

Python 不负责 DevPilot 用户认证、RBAC、Workspace/Project/Task/GitHub 业务事实，也不得直接访问 Java MySQL 中的
`dp_*` 表。Python 收到的 identity/scope 只能用于请求关联，最终权限判断仍由 Java 在入口和 Tool 执行时完成。

## 数据所有权

| 数据 | 权威拥有者 | 原因 |
|---|---|---|
| User/RBAC | Java | 认证和权限事实 |
| Workspace/Project | Java | 业务事实 |
| GitHub Snapshot | Java | 已有同步与 Scope |
| Task | Java | 状态机、事务与 Audit |
| Audit/Notification | Java | 审计事实、可靠通知与接收人权限属于正式业务边界 |
| AgentRun 业务投影 | Java（计划） | 用户可见业务记录、Scope 与 Audit |
| Agent runtime step/checkpoint | Python | 执行期内部状态 |
| Conversation runtime context | Python | Agent 执行上下文 |
| Knowledge index | 后续单独决策 | 本章不定 |
| Long-term memory | 后续单独决策 | 本章不定 |

必须保持以下不变量：

- 两个服务不共享同一业务表；
- Python 不直接 `SELECT/INSERT/UPDATE/DELETE dp_*` 业务表；
- Java 不直接读取 Python Runtime 内部表；
- Python 不把模型输出变成 SQL；
- 跨边界调用使用版本化 RPC Contract。

## Contract-first 与双向 RPC

```text
Java devpilot-agent ── AgentRuntime.StartRun/StreamRun/CancelRun ──> Python agent-service
Python agent-service ── DevPilotToolGateway.ExecuteTool ───────────> Java devpilot-agent
```

`.proto` 是唯一跨语言契约来源。Java/Python Stub 将来都从同一版本生成，不允许两端维护同语义 DTO。当前 proto
只提供最小字段占位，尚未定义真实身份传播、错误模型、AgentEvent payload、Tool catalog、Proposal 或 Confirm。

Browser 只能访问 Java HTTP API，不能绕过 Java 直接调用 Python。Tool Gateway 收到调用后，未来必须通过公开
Application Service / Port 使用 Project、Task、GitHub 能力，不得操作这些模块的 Mapper、Entity 或 Repository 实现。

## Java 模块依赖

本章的最小依赖是：

```text
framework
├─ identity
└─ project → identity

devpilot-agent → framework + identity + project
devpilot-boot  → devpilot-agent（以及既有模块）
```

本章没有 Java 类需要这些依赖，但 POM 先明确允许的边界。未来需要 Task/GitHub 时，应先定义或复用公开
Application/Port，并在确认无环后增加依赖；`devpilot-agent` 不得依赖任何模块的 persistence 包。Python 是独立进程，
不是 Maven dependency。

## 部署与 Compose 评估

未来可把 Python gRPC endpoint 命名为 `devpilot-agent-service:50051`。本章不修改 Compose，因为目前没有可启动的
gRPC Server、health endpoint 或镜像；提前加入 service 会产生无法真实启动的配置。Python 不配置 Java MySQL
连接，也不引入 LLM API Key。真正部署前需单独确定 health、timeout、TLS/服务身份、重试、流式背压与优雅停机。

## 本章明确未实现

- LLM provider、Hello-Agents、ReAct、LangGraph；
- RAG、Knowledge index、Memory、Multi-Agent、DeerFlow；
- AgentRun 表和任何 Flyway migration；
- gRPC Stub 生成、Server/Client 与网络联调；
- Tool Gateway 真实业务、Proposal/Confirm；
- Agent HTTP API、鉴权 metadata 和运行时存储；
- Spring Cloud、Nacos、Sentinel、Kubernetes 或消息队列。

## 后果

优点是权限与业务事务继续收敛在 Java，Python 可以独立选择 Agent 生态且不能绕过本地应用服务；代价是未来需要维护
双向 RPC 的超时、兼容、可观测性与部署。当前只接受这项结构成本，不提前引入运行时框架或数据库。

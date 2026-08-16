# Agent 第 1 章文件地图：微服务边界、目录骨架与契约

## 本章目标

只固定 Java/Python 进程边界、数据所有权、Maven/Python 工程骨架和双向 RPC Contract。没有实现真实 Agent、
AgentRun 表、Tool、gRPC 网络或 Compose service。

## 完整变更清单

| 状态 | 文件/目录 | 职责 |
|---|---|---|
| [MOD] | `.gitignore` | 忽略 Python 缓存/虚拟环境，放行本文件 |
| [MOD] | `README.md` | 增加 Agent 服务边界、目录和验证入口 |
| [MOD] | `pom.xml` | 把 `devpilot-agent` 纳入 reactor 与 dependency management |
| [MOD] | `devpilot-boot/pom.xml` | 由组合根装配 `devpilot-agent` |
| [MOD] | `DevPilotModuleArchitectureTest.java`、`CrossModulePersistenceBoundaryTest.java` | 禁止既有模块反向依赖 agent，并预留 agent 跨模块持久化守护 |
| [MOD] | `docs/architecture.md` | 将 Agent 规划更新为双进程边界 |
| [MOD] | `docs/database-design.md` | 记录本章无表、无 migration 与数据所有权 |
| [MOD] | `docs/capability-coverage-and-roadmap.md` | 标记 Agent L0 边界骨架完成，L1/L2/L3 仍未实现 |
| [NEW] | `devpilot-agent/pom.xml` | Java Agent Integration / Application Boundary 空模块 |
| [NEW] | `agent-service/pyproject.toml` | Python 3.11、src layout、pytest/Ruff 配置 |
| [NEW] | `agent-service/README.md` | Python Runtime 职责、禁区与本地验证 |
| [NEW] | `agent-service/src/devpilot_agent_service/` | config 与 runtime/model/tools/rpc/observability 包骨架 |
| [NEW] | `agent-service/tests/test_package_smoke.py` | 包导入和安全默认值 smoke test |
| [NEW] | `contracts/agent/v1/README.md` | Contract-first、方向、演进约束 |
| [NEW] | `contracts/agent/v1/agent_runtime.proto` | 两个 Service 与最小 request identity 草案 |
| [NEW] | `docs/agent/01-service-boundary.md` | 服务边界与数据所有权 ADR |
| [NEW] | 本文件 | 文件、依赖、调用方向、阅读顺序与未实现边界 |
| [DEL] | 无 | 没有删除或覆盖既有文件 |

## 进程图

```text
Browser
  │ HTTP
  ▼
Java DevPilot process
  ├─ devpilot-agent: Browser API / RBAC / Run projection / RPC adapters / Tool gateway
  └─ identity + project + task + github + audit 等正式业务模块
  │
  │ AgentRuntime gRPC
  ▼
Python agent-service process
  └─ future LLM / Agent Loop / runtime state / conversation context
```

## 模块依赖图

```text
devpilot-boot → devpilot-agent
devpilot-agent → devpilot-framework
               → devpilot-identity → devpilot-framework
               → devpilot-project  → devpilot-framework + devpilot-identity

agent-service -X→ Maven reactor
```

后续 agent 使用 Task/GitHub 时只能经过公开 Application Service / Port。当前没有增加 agent→task/github 依赖，
也没有任何 agent→persistence 依赖。

## 数据所有权

| 数据 | 权威拥有者 | 原因 |
|---|---|---|
| User/RBAC | Java | 认证和权限事实 |
| Workspace/Project | Java | 业务事实 |
| GitHub Snapshot | Java | 已有同步与 Scope |
| Task | Java | 状态机/事务/Audit |
| Audit/Notification | Java | 审计事实、可靠通知与接收人权限属于正式业务边界 |
| AgentRun 业务投影 | Java（计划） | 用户可见记录、Scope、Audit |
| Agent runtime step/checkpoint | Python | 执行期内部状态 |
| Conversation runtime context | Python | Agent 执行上下文 |
| Knowledge index | 后续单独决策 | 本章不定 |
| Long-term memory | 后续单独决策 | 本章不定 |

## 未来双向 gRPC 调用图

```text
Java devpilot-agent
  └─ AgentRuntime Client ── StartRun / StreamRun / CancelRun ──> Python agent-service

Python agent-service
  └─ DevPilotToolGateway Client ── ExecuteTool ────────────────> Java devpilot-agent
       └─ RBAC / scope / risk / confirmation / audit
          └─ 正式业务 Application Service
```

## 关键设计决策

1. Java `devpilot-agent` 保留，因为它是面向 Browser 与业务模块的集成边界，不是 Agent Runtime。
2. Python 独立于 Maven，以 Python 3.11 `src/` layout 管理；本章无运行时第三方依赖。
3. `.proto` 是唯一跨语言 DTO 来源；当前字段有意保持最小，避免过早冻结复杂 schema。
4. 两个进程不共享业务表。Python 不连接 `dp_*`，Java 不读取 Python runtime 内部存储。
5. Compose 只完成评估；没有假 health、假 server 或 secret 配置，因此不修改 `compose.yaml`。
6. Hatchling 只作为标准 `src/` package 的轻量构建后端；pytest 提供 smoke test，Ruff 提供格式/静态检查，三者都
   只属于 Python 工程，未给 Java reactor 增加依赖。

## 推荐阅读顺序

1. `docs/agent/01-service-boundary.md`：先理解职责和数据所有权。
2. `contracts/agent/v1/README.md` 与 `agent_runtime.proto`：理解双向 RPC 与契约演进。
3. `devpilot-agent/pom.xml`、根 POM、Boot POM：理解 Java 组合边界。
4. `agent-service/README.md`、`pyproject.toml`、包目录和 smoke test：理解 Python 独立工程。
5. `DevPilotModuleArchitectureTest.java`：确认反向依赖仍被守护。
6. `docs/architecture.md`、`docs/database-design.md` 与 roadmap：核对全局文档一致性。
7. `docs/changes/agent-01-boundary-audit-report.md`：查看第 1 章逐项验收证据和真实测试结果。

## 本章明确未实现

LLM provider、Hello-Agents、ReAct、LangGraph、RAG、Memory、Multi-Agent、DeerFlow、AgentRun 数据表、
真实 Tool、Proposal/Confirm、gRPC Stub/网络联调、Agent HTTP API、Python Runtime 存储、Compose service、
Spring Cloud/Nacos/Sentinel/Kubernetes 均未实现。

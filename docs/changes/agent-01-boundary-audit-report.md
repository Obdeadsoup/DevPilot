# Agent 第 1 章边界验收与加固报告

## 1. 验收结论

- 验收基线：`6a836b90cf25e71a7abdf8faace66e06963ec11e`（`6a836b9 纯后端开发部分前端对齐,AGENT初步架构搭建`）。
- 最终结论：**PASS**。第 1 章所要求的 Java 装配边界、Python 服务骨架、Proto 契约和跨语言数据所有权均已成立。
- 本轮只发现一处文档缺口：数据所有权说明没有明确列出 `Audit/Notification -> Java`。现已在 ADR、总架构、数据库设计、契约说明和文件地图中统一补齐。
- 没有修改 Java/Python 业务代码、POM、Proto、Compose 或架构测试；没有提前实现 Agent 第 2 章能力。
- 初始工作树存在 11 个用户创建的零字节 Python 文件，均未纳入本轮实现、未修改、未删除，按 `OUT-OF-SCOPE` 记录。

## 2. 验收矩阵

### A. Maven 与 Java 模块边界

| 检查项 | 结果 | 证据与结论 |
| --- | --- | --- |
| 根 Reactor 声明 `devpilot-agent` | PASS | 根 `pom.xml` 的 modules 与 dependencyManagement 均包含 `devpilot-agent`。 |
| Boot 装配 Agent 模块 | PASS | `devpilot-boot/pom.xml` 依赖 `devpilot-agent`，装配方向为 Boot -> Agent。 |
| Agent 依赖最小化 | PASS | `devpilot-agent/pom.xml` 仅依赖 framework、identity、project，无业务模块反向依赖。 |
| Agent 不直接持久化 | PASS | 模块没有 Java 源码，也没有 MyBatis/JDBC/Flyway 依赖。 |
| 现有模块不得反向依赖 Agent | PASS | 各模块 `*ModuleTest` 的 ArchUnit 规则覆盖反向依赖禁令。 |
| Agent 不得越过 persistence 边界 | PASS | `CrossModulePersistenceBoundaryTest` 已把 `agent` 纳入 owner 列表；未来 Agent 源码不能直接访问其他模块 Mapper/Entity/Repository。 |
| Reactor 可识别并构建 Agent | PASS | 两次 Maven Reactor 均列出 11 个模块，Agent 空边界 JAR 构建成功。 |

### B. Python Agent Service 骨架

| 检查项 | 结果 | 证据与结论 |
| --- | --- | --- |
| Python 版本 | PASS | `agent-service/pyproject.toml` 声明 `requires-python = ">=3.11"`；实际使用 Python 3.11.9。 |
| `src/` 布局 | PASS | 包位于 `agent-service/src/devpilot_agent_service`，pytest 的 `pythonpath` 指向 `src`。 |
| 责任包齐备 | PASS | 已有 runtime、models、tools、rpc、observability 等边界包和最小骨架。 |
| 不访问业务数据库 | PASS | 扫描未发现 MySQL、SQLAlchemy、JDBC、业务表或 Mapper/Repository 访问。 |
| 不引入运行时框架 | PASS | 运行时 `dependencies = []`；未引入 LangGraph、LLM SDK、gRPC/MCP 实现。 |
| 可导入、可测试、可 lint | PASS | 服务身份对象可从仓库根目录导入；pytest 与 Ruff 均通过。 |

### C. Proto 契约

| 检查项 | 结果 | 证据与结论 |
| --- | --- | --- |
| 包名稳定 | PASS | Proto package 为 `devpilot.agent.v1`，Java package 为 `com.obdeadsoup.devpilot.agent.contract.v1`。 |
| 服务与方向明确 | PASS | `AgentRuntime` 提供 StartRun/StreamRun/CancelRun；`ToolGateway` 提供 ExecuteTool，方向在契约 README 中明确。 |
| 字段保持最小 | PASS | 仅定义第 1 章需要的身份、运行、事件、取消和工具调用字段。 |
| 演进规则明确 | PASS | Proto 是唯一事实源；字段号稳定；删除字段使用 `reserved`；后续采用 proto-first Stub 生成。 |
| 无重复手写 RPC DTO | PASS | Java 与 Python 扫描未发现复制 Proto message 的 DTO/模型。 |

### D. 跨语言与数据所有权

| 检查项 | 结果 | 证据与结论 |
| --- | --- | --- |
| Browser 只访问 Java HTTP | PASS | ADR 明确浏览器不直连 Python。 |
| 业务数据归 Java | GAP -> FIXED | 原文已包含 Identity/Project/GitHub/Task/Outbox，但未明确 Audit/Notification；本轮已统一补齐。 |
| Python 通过 ToolGateway 访问业务能力 | PASS | Python 不持有业务权限与数据库权威；工具调用须回到 Java Application Service。 |
| Java 保持鉴权与规则权威 | PASS | RBAC、scope、业务校验、事务和审计仍在 Java 边界。 |
| 无越界运行时实现 | PASS | 没有 AgentRun 持久化、gRPC 传输实现、Agent Runtime 实现或 Compose Agent 服务。 |

### E. 工作树范围审计

以下文件在本轮开始前已存在，均为零字节且未被 Git 跟踪。它们的命名对应第 2 章可能出现的抽象，但当前没有代码、依赖、导入或调用链，因此判定为 `OUT-OF-SCOPE working-tree placeholders`，不是第 1 章实现：

- `agent-service/src/devpilot_agent_service/agents/agent.py`
- `agent-service/src/devpilot_agent_service/agents/react_agent.py`
- `agent-service/src/devpilot_agent_service/agents/reflection_agent.py`
- `agent-service/src/devpilot_agent_service/core/agent.py`
- `agent-service/src/devpilot_agent_service/core/config.py`
- `agent-service/src/devpilot_agent_service/core/exceptions.py`
- `agent-service/src/devpilot_agent_service/core/llm.py`
- `agent-service/src/devpilot_agent_service/core/message.py`
- `agent-service/src/devpilot_agent_service/tools/base.py`
- `agent-service/src/devpilot_agent_service/tools/chain.py`
- `agent-service/src/devpilot_agent_service/tools/registry.py`

## 3. 本轮文件地图

### [NEW]

| 文件 | 职责 | 模块/调用关系 | 配置、事务、并发、安全与测试 |
| --- | --- | --- | --- |
| `docs/changes/agent-01-boundary-audit-report.md` | 固化验收矩阵、证据、命令结果和剩余风险 | 仓库级文档；无运行时调用方/被调用方 | 不参与配置、事务或并发；安全价值是防止边界漂移；由差异检查和人工审计验证。 |

### [MOD]

| 文件 | 职责 | 模块/调用关系 | 配置、事务、并发、安全与测试 |
| --- | --- | --- | --- |
| `.gitignore` | 允许提交本验收报告 | 仓库级 Git 配置 | 不影响运行时；由 `git status` 验证报告可见。 |
| `README.md` | 增加验收报告入口 | 仓库文档导航 | 无运行时语义；由链接与差异检查验证。 |
| `docs/agent/01-service-boundary.md` | 明确 Audit/Notification 数据归 Java | Agent 第 1 章 ADR | 强化 Java 的权限、事务与数据权威；由文档一致性审计验证。 |
| `docs/architecture.md` | 在总架构中补齐数据权威范围 | 全仓架构 | 不改变调用链；由 ArchUnit 与文档审计共同验证。 |
| `docs/database-design.md` | 明确 Audit/Notification 与未来 AgentRun 投影归 Java | 数据库设计 | 不新增表或迁移；仅收紧所有权表述。 |
| `contracts/agent/v1/README.md` | 补齐契约侧 Java 权威清单 | Java <-> Python 契约 | 不改变 Proto；由契约扫描验证。 |
| `docs/changes/agent-01-service-boundary-file-map.md` | 补齐数据所有权条目并加入报告阅读入口 | 第 1 章文件地图 | 无运行时语义；由差异检查验证。 |

### [DEL]

无。

上述 11 个用户零字节占位文件不属于本轮文件地图，保持原样。

## 4. 真实调用链边界

当前第 1 章只建立设计与装配边界，尚未实现网络传输：

```text
Browser
  -> Java HTTP API
  -> devpilot-agent
  -> AgentRuntime RPC
  -> Python agent-service
```

```text
Python agent-service
  -> ToolGateway RPC
  -> devpilot-agent
  -> Java Application Service
  -> RBAC / scope / business rule / transaction
  -> Java-owned database
```

Python 不能直接读取业务库，不能绕过 Java Application Service，也不能提升当前用户权限。工具写操作、确认令牌和审计属于后续章节，本轮没有提前实现。

## 5. 关键差异与设计决定

1. 保留已有 Java/Python/Proto 骨架，不为“看起来完整”而增加运行时依赖或空实现。
2. 将 `Audit/Notification -> Java` 写入所有主要边界文档，消除不同文档之间的数据权威歧义。
3. Proto 保持唯一契约事实源，不生成或手写 Stub/DTO；第 1 章没有足够理由引入 protobuf/gRPC 构建插件。
4. 保留用户的未跟踪占位文件并明确报告，避免越权删除，也避免将其误报为已完成的 Agent 实现。

## 6. 推荐阅读顺序

1. `docs/agent/01-service-boundary.md`
2. `contracts/agent/v1/agent_runtime.proto`
3. `contracts/agent/v1/README.md`
4. `devpilot-agent/pom.xml`
5. `agent-service/README.md`
6. `agent-service/pyproject.toml`
7. `devpilot-boot/src/test/java/com/obdeadsoup/devpilot/architecture/ModuleDependencyArchitectureTest.java` 与 `CrossModulePersistenceBoundaryTest.java`
8. `agent-service/tests/test_smoke.py`

各包的 `__init__.py` 与最小配置对象只承载骨架，可在理解上述边界后快速浏览。

## 7. 验证记录

| 命令/检查 | 状态 | 实际结果 |
| --- | --- | --- |
| `mvn -B -ntp -pl devpilot-boot -am test` | PASS | 原工作区真实运行 112 个测试，Failures 0、Errors 0、Skipped 0；11 个 Reactor 模块全部成功，耗时 4:56。 |
| `mvn -B -ntp clean verify`（原工作区首次） | BLOCKED | 前 10 个模块成功；Boot clean 无法删除正在被用户本地 DevPilot 进程占用的 JAR。没有停止该用户进程。 |
| `mvn -o -B -ntp clean verify`（当前工作树隔离副本，使用既有本地 Maven 缓存） | PASS | 11 个 Reactor 模块全部成功，BUILD SUCCESS，耗时 2:31；沙箱无 Docker named-pipe 权限，Boot 112 个测试中 100 个 Testcontainers 条件跳过，其余 12 个通过。完整容器覆盖由上一条 112/0/0/0 的原工作区命令提供。 |
| `python -m pytest agent-service/tests` | PASS | 1 passed in 0.02s。 |
| Python 根目录导入 smoke | PASS | 输出 `ServiceIdentity(name='devpilot-agent-service', contract_version='agent.v1')`。 |
| `python -m ruff check agent-service` | PASS | 系统无 Ruff；临时安装到系统临时目录后执行，输出 `All checks passed!`，未修改仓库或全局环境。 |
| `docker compose config --quiet` | PASS | 退出码 0，无输出。 |
| Python 数据库/运行时依赖扫描 | PASS | 未发现业务数据库访问或 LLM/LangGraph/gRPC/MCP 运行时导入。 |
| RPC DTO 重复实现扫描 | PASS | 未发现 Java/Python 手写重复契约。 |
| Agent Java/Compose 越界实现扫描 | PASS | 无 Java 实现、无 Agent Compose 服务。 |
| `git diff --check` | PASS | 退出码 0；仅有 Git 的 LF -> CRLF 工作区提示，无空白错误。 |

## 8. 依赖变化

无新增 Java 或 Python 运行时依赖。Ruff 仅安装在系统临时目录用于本轮验证，不属于项目依赖。

## 9. 剩余风险与后续边界

- `devpilot-agent` 当前按设计生成空 JAR；只有装配和架构边界，没有运行时能力。
- Proto 当前未编译生成 Stub；在真正引入 RPC 实现时，应先确定版本锁定、生成目录和兼容性检查，再添加构建依赖。
- 11 个未跟踪零字节占位文件可能让后续读者误以为第 2 章已开始；本轮按用户工作保留，建议进入第 2 章时由文件所有者决定实现、重命名或删除。
- 当前用户本地 DevPilot 进程持续占用原工作区 Boot JAR，因此原目录的 `clean` 会失败；这属于本地运行状态，不是本轮代码缺陷。
- Maven 隔离重跑时沙箱禁止 Docker named pipe，导致 Testcontainers 条件跳过；原工作区要求命令已经提供完整的 112 个不跳过测试结果。

## 10. 变更控制

本轮未提交、未暂存、未推送、未创建 PR，也未修改 Git remote。用户原有未跟踪文件均已保留。

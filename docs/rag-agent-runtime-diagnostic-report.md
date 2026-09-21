# DevPilot RAG + Agent Runtime 诊断修复报告

## Baseline 与边界

- 基线：`main@9c62bc4cfbe295e2de083b8b5b561390552af44b`。本轮直接在当前工作区修改；未切换分支、未 reset、未 commit、未 push。
- 开始时已有工作区修改：`.gitignore`、`compose.yaml`、`docs/full-compose-startup-report.md`。这些修改均保留。
- 环境：Docker Desktop 约 7.47 GiB 内存；Full Compose 的 MySQL、Redis、Nacos、Mailpit、MinIO、agent-service、Core、Gateway、Web 起初健康，两个 TEI 服务反复重启。
- Full Compose 验收使用真实 TEI 和 DeepSeek；单元测试中的 fake 仅用于隔离逻辑，没有充当最终结果。报告只记录稳定状态、错误类别、耗时与计数，不记录 Secret、文档正文或 Tool 大 payload。

## 问题 1：知识文档全部 FAILED，TEI 不可用

**现象**：项目 `workspaceId=1, projectId=4` 的 4 份 Markdown 均为 `FAILED`、`chunk_count=0`。

| 文档 | document_id | 版本 | failure_code | 原始对象 |
| --- | --- | ---: | --- | --- |
| README.md | `15eec597-7d16-407e-8ba5-24d523d3ec3b` | 5 | `RESOURCEACCESSEXCEPTION` | 存在且可读 |
| full-stack-testing-finalization-report.md | `83442016-94bb-4d73-a5a4-4ff26176c010` | 2 | `RESOURCEACCESSEXCEPTION` | 存在且可读 |
| frontend-product-copy-audit.md | `2f661f9d-ea4e-4567-8e4f-7771b641fa8e` | 2 | `RESOURCEACCESSEXCEPTION` | 存在且可读 |
| full-compose-startup-report.md | `14bfd560-6b09-4753-b2f9-965567c17a22` | 5 | `RESOURCEACCESSEXCEPTION` | 存在且可读 |

**证据与失败边界**：`dp_knowledge_chunk` 对上述文档计数均为 0。MinIO bucket 和四个对象已通过 `mc ls/stat/cat` 验证；Core 向两个 TEI `/health` 请求起初均为连接拒绝。Docker 事件记录两个 TEI 容器反复 `oom`、`die 137`；容器运行中读取的 `State.OOMKilled=false` 不能代表之前的重启原因。[embedding ONNX](https://huggingface.co/intfloat/multilingual-e5-small/tree/main/onnx) 与 [reranker ONNX](https://huggingface.co/BAAI/bge-reranker-base/tree/main/onnx) 权重在官方模型仓库存在，容器缓存内出现不完整的 `.sync.part` 文件。失败集中在 TEI 模型下载/加载与 `EMBED` 边界，不在 MinIO 上传。

**根因**：两个 TEI 在约 7.47 GiB 的 Docker 内存中并行下载/加载，默认启动 19 个 tokenization workers、批量上限 16384 tokens；峰值导致内存耗尽。Core 原先仅等待 `service_started`，允许模型尚未提供服务时开始异步入库。

**修改**：`compose.yaml` 为两个 TEI 增加 `/health` serving healthcheck，Core 等待两者 `service_healthy`，reranker 在 embedding 健康后启动；保留模型缓存卷。两个 TEI 配置为 2 个 tokenization workers、1024 batch tokens、2 batch requests，并给首次下载留足 start period。TEI 启动日志报告后端将 batch requests 强制设为 8，因此实际值以服务端为准。单独启动 embedding 后，470 MB ONNX 成功缓存；收紧参数后 `/health=200`、重启计数 0、常驻内存约 1.1 GiB。Compose 网络内真实 `/embed` 两次均 HTTP 200、384 维，耗时 85/107 ms（模型已加载后的首请求/重复请求）。

**回归测试**：`KnowledgeIngestionServiceTest` 覆盖成功入库和 embedding 异常稳定失败码；`KnowledgeDocumentServiceTest` 覆盖 Retry、版本冲突与入库事件；`KnowledgeRetrievalServiceTest` 覆盖空搜索成功且不调用 TEI、有候选时命中与 RBAC；`TeiKnowledgeAdaptersTest` 覆盖 `/embed` 与 `/rerank` 请求及响应合约。真实 reranker 已在 Compose 网络内通过 HTTP 200，返回 2 个带 `index/score` 的结果，首次/重复请求 183/94 ms。20 条短候选首次/重复为 780/280 ms；20 条较长候选为 3322/2674 ms。通过合法登录态调用原有 Retry API 后，四份文档均正常入库：`README.md` READY / 15 chunks / version 8；`full-stack-testing-finalization-report.md` READY / 10 / version 5；`frontend-product-copy-audit.md` READY / 9 / version 5；`full-compose-startup-report.md` READY / 18 / version 8。此处版本是最终 API 返回值，上表为故障时基线。

## 问题 2：入库与 Tool 错误缺少分层诊断

**现象**：页面仅显示 `FAILED/0 chunks`，Agent 显示 `TOOL_ERROR`，无法分辨存储、embedding、检索与 Tool Gateway 边界。

**证据与失败边界**：原 `KnowledgeIngestionService.ingest` 捕获所有 `RuntimeException`，仅写入异常类名；原 Java/Python Tool Gateway 未输出有界、脱敏的调用边界信息。Agent 日志记录 `ToolExecutionError`，但原运行时无法仅凭该值断定是认证、deadline 或检索依赖。

**根因**：缺少安全结构化日志和面向用户的稳定失败码展示。

**修改**：入库日志增加 `documentId/databaseId/stage/failureCode/exceptionType/elapsedMs`；检索记录 scope、候选数、hits 与 embedding/rerank 耗时；Java/Python Gateway 记录 runId、toolName、stable kind、耗时，Java 同时记录 exceptionType。知识页面为 FAILED 显示可理解的原因与可折叠的 Technical Details 中的 `failureCode`。日志不包含文档正文、请求参数、原始远端异常消息或密钥。

**回归测试**：Java 入库测试验证 `RESOURCEACCESSEXCEPTION`；Python 测试验证 gRPC 失败种类和日志不泄露 service key/payload；前端浏览器测试验证失败行信息与折叠详情。

**真实 gRPC 隔离**：从运行中的 `agent-service` 通过 `devpilot-core:50052` 调用，正确 service key 加不存在的 Run 得到 Java `RUN_NOT_FOUND`、Python `NOT_FOUND`；错误 key 在拦截器被拒，Python 得到 `UNAUTHENTICATED`。Java 日志仅含 runId、toolName、kind、exceptionType、elapsedMs。该实验说明网络与 key 边界正常，但不代表普通工具已通过有效 Run/RBAC。

## 问题 2a：知识检索的 3 秒 Tool deadline 不足

**现象与证据**：当前 `DEVPILOT_JAVA_TOOL_GRPC_DEADLINE_SECONDS=3`。真实 Compose TEI 对 20 条接近文档 chunk 长度的候选仅 rerank 就用了首次 3322 ms、重复 2674 ms；再加 query embedding、MySQL、RRF、trace 写入，3 秒可能触发 `DEADLINE`。

**失败边界与根因**：`knowledge.search` 的 Python→Java gRPC deadline 短于正常 CPU rerank 的实测上界。原始历史 `TOOL_ERROR` 没有足够日志把它与 TEI 不可用区分，因此不能断言过去每次 Tool 失败都是 deadline。

**修改**：仅 `knowledge.search` 使用可配置且有界的 30 秒 deadline；普通工具保持 3 秒。新增 Python 测试验证两个 deadline 的隔离和自定义 15 秒配置。Compose 已加载新配置，运行容器检查结果为普通 3.0 秒、知识检索 30.0 秒。

**回归测试**：Python Tool Gateway 测试 15 项通过；真实长候选 rerank HTTP 200。

## 问题 3：Agent History List 500

**现象**：页面“Agent 运行历史无法加载”。Smoke 脚本仅 GET 单条 Run，却打印 History PASS。

**证据与失败边界**：真实 Compose MySQL 回归首次执行 `AgentRunMapper.findHistory` 报 SQL 语法错误，MyBatis 最终 SQL 以 `SELECTid, ...` 开头。`dp_agent_run` 的 `repository_full_name` 字段存在，Flyway V19 已执行；问题位于 Java Mapper 的文本块拼接，不在 schema 缺字段。

**根因**：`@Select` 文本块中的 `SELECT` 与 `COLUMNS` 拼接后缺少空格。

**修改**：给共享列清单显式增加前导空格。Smoke 脚本在终态后 GET `?page=0&size=20`，检查 HTTP/business code 与 `items` 包含新 `runId`，且单条 Run 必须有非空 `finalOutput` 才通过。

**回归测试**：新增 `AgentRunMapperMySqlTest`，对真实 MySQL 在事务内插入测试行并直接执行 JDBC rollback，验证 list、count、状态筛选、排序、分页与 deleted 过滤，最终通过。初版测试误用 MyBatis `session.rollback()`，8 条测试记录已按精确测试条件清理；修正后两轮失败/成功测试均确认未来年份测试记录数为 0。

**真实验收**：合法 Bearer 对 Core `:8080`、Gateway `:8081`、Web `:5173` 的 History List 分别得到 HTTP 200、`COMMON_0000`，第一页包含新完成的 Run。最终 Smoke 也实际检查 `?page=0&size=20` 的新 runId，而不再只检查单条 Run。

## 问题 4：SSE 断开被误报为运行中断

**现象**：页面显示“实时进度暂时中断”；此前 Gateway 有一次 SSE `PrematureCloseException`，但单条日志不能证明所有终态断开都是服务端故障。

**证据与失败边界**：Java `AgentRunEventHub` 发送 terminal 事件后正常 `complete()`；原前端 Fetch 读到 EOF 时不通知页面，`reconnectAttempts` 在新 Run、打开 History Run、连接成功后未重置。前端在错误回调中尚未查询权威终态便显示中断。

**根因**：浏览器侧缺少连接结束与终态的区分，以及旧连接回调/重连计时器隔离。

**修改**：Fetch SSE 校验 `Content-Type` 并通知 `onOpen/onClose`；页面收到 terminal event 后标记完成并断开读取，异常关闭时先 GET 权威 Run 状态，再决定是否有界重连。新 Run、历史 Run 与连接建立时重置相关状态；旧连接回调按 generation/runId 丢弃。History 请求异常现可稳定展示产品错误。

**回归测试**：定向 Playwright 测试验证 Bearer Header、terminal 后无“实时进度暂时中断”，以及断线重连携带 `Last-Event-ID` 并正常完成；已有 Java SSE 测试覆盖 Last-Event-ID replay 和 terminal complete。真实浏览器链路结果见 Golden Paths。

**真实流发现的第二层问题与修复**：首次真实 Run 完成后，Core/Gateway/Web 的 SSE 连接曾以 curl exit 18 结束。Core 异步完成 dispatch 抛 `AuthorizationDeniedException`，原因是 Bearer 过滤器跳过异步 dispatch。改为在该 dispatch 再执行 Bearer 认证，不放宽端点 RBAC。随后三层 SSE 均 HTTP 200、`text/event-stream`、正常 EOF（curl exit 0）；`Last-Event-ID` 重放也正常 EOF。Core 重启后旧终态 Run 的事件缓存消失，原连接会挂起；现在控制器查询权威终态，Hub 对没有缓存的终态发送 `replay-gap` 并关闭。三层旧 Run 重放均验证正常关闭。`AgentRunStreamControllerTest` 和 `AgentRunEventHubTest` 已覆盖该边界。

## 问题 5：文档 READY 后 Direct Search 仍 500

**证据与失败边界**：四份文档 Retry 成功后，Direct Search 首先在 MyBatis 报 `SELECTchunk`；修复后，TEI reranker 返回 HTTP 422，明确指出候选批量 50 超过服务端允许的 32。问题依次位于 Chunk Mapper SQL 拼接和 Core→TEI rerank 请求，不是浏览器代理或上传。

**修改与验证**：给 `KnowledgeChunkMapper.COLUMNS` 加前导空格；新增真实 Compose MySQL 的 Mapper 回归。`TeiKnowledgeReranker` 按 16 条拆批、合并分数和索引；33 条候选契约测试覆盖跨批结果。最终同一知识检索请求经 Core/Gateway/Web 均 HTTP 200、`COMMON_0000`、5 hits；最终 Web 再验得到 5 hits，来源包括 `README.md`、`full-compose-startup-report.md` 和 `full-stack-testing-finalization-report.md`。三层此前实测耗时约 9–14 秒；实际 TEI 运行时的 30 秒专用 Tool deadline 得到真实依据。

## 问题 6：Tool 完成后 DeepSeek 模型协议失败

**证据与失败边界**：真实浏览器 Run 中 `task.list_open` 与 `knowledge.search` 均为 TOOL_COMPLETED，第二次模型调用却返回 HTTP 400；因此不能再把它归为 Tool Gateway 失败。DeepSeek 工具协议要求 thinking 工具回合保留 `reasoning_content`，并限定函数名只含字母、数字、下划线或连字符（[官方工具调用说明](https://api-docs.deepseek.com/api/create-chat-completion/)）。原 Adapter 丢弃 reasoning、工具名仍使用内部 `knowledge.search` 形式。另有工作流主动发起的检索工具调用，它没有模型原生 reasoning，却被当成模型发起的工具回合重放。

**修改**：从响应解析、运行时消息、图消息、上下文预算、持久化到请求发送保留模型实际提供的 reasoning；工具调用的 assistant `content` 使用非 null 字符串。把工作流发起的只读 ToolResult 转成标明不可信的用户证据消息，保留图内 Tool Gateway 与事件生命周期。Provider 使用可逆函数别名（如点号转下划线）并检查碰撞，返回调用映射回内部原名；Java Tool Gateway 名称和 RBAC 不变。给 Provider Adapter、工作流协议及别名冲突加测试。完成后真实浏览器 Run `26645acc-146e-4a1c-96c8-165f4fa8dad9` 经 DeepSeek 和 `knowledge.search` 到 SUCCEEDED，最终输出非空，页面无 SSE 中断提示。

**后续发现**：Java Tool Gateway 的实际知识结果是 `sources/content`，Python 的限长与安全追踪原先只识别演示用的 `hits/text`，导致约 5.6 KB 证据被截成无效 JSON、`rag_sources=[]`。现保留有效 JSON、来源文件与 chunk 身份，并继续支持旧形状。完整 Python 测试通过。最终纯 RAG Run `6353077e-f04d-4602-8b0e-9d86674f5850` 的安全追踪列出三个真实来源；混合 Run `d454b90f-a92c-45f6-bbba-c3f3bb5ca401` 列出相同来源，同时显示 `used_business_tool=true`、`used_rag=true`、`planner_route=HYBRID`，Tool 共 6 次、模型共 5 次，均 SUCCEEDED。追踪只有文件名、工具名、计数与状态，不包含正文。

## 修改文件地图

| 范围 | 文件 |
| --- | --- |
| Compose readiness 与资源上限 | `compose.yaml` |
| 知识入库、检索诊断 | `devpilot-knowledge/.../KnowledgeIngestionService.java`、`KnowledgeRetrievalService.java` |
| Tool Gateway 诊断与有界 deadline | `devpilot-agent/.../DevPilotToolGatewayGrpcService.java`、`agent-service/.../tool_gateway_client.py`、`compose.yaml` |
| History SQL 与测试 | `devpilot-agent/.../AgentRunMapper.java`、`AgentRunMapperMySqlTest.java`、`devpilot-agent/pom.xml` |
| Chunk SQL 与 Rerank 分批 | `devpilot-knowledge/.../KnowledgeChunkMapper.java`、`TeiKnowledgeReranker.java`、对应 MySQL/批量测试、`devpilot-knowledge/pom.xml` |
| 模型协议、来源与上下文 | `agent-service/.../model/providers/openai_compatible.py`、`model/types.py`、`graph/nodes/agent.py`、`graph/workflow.py`、`context/manager.py`、`harness/workflow.py`、`harness/workflow_fake.py`、运行时消息/持久化与测试 |
| SSE 异步认证与旧 Run | `devpilot-identity/.../BearerTokenAuthenticationFilter.java`、`devpilot-agent/.../AgentRunStreamController.java`、`AgentRunEventHub.java` 与测试 |
| 前端知识、SSE、History | `devpilot-web/src/views/knowledge/ProjectKnowledgeView.vue`、`src/views/agent/AgentRunView.vue`、`src/services/agentRunStream.ts` |
| Smoke 与回归 | `ops/fullstack/Invoke-DevPilotAgentSmoke.ps1`、Java/Python/Playwright 测试文件 |
| 报告可跟踪 | `.gitignore`、本文件 |

## 执行命令与测试

- `git branch --show-current`、`git rev-parse HEAD`、`git status`、`git diff`；`docker compose --profile full ps`；各服务 `docker compose logs --tail`；`docker events`、`docker stats`。
- 真实 MySQL 查询 `dp_knowledge_document`、`dp_knowledge_chunk`、`dp_agent_run`、`SHOW CREATE TABLE`；MinIO `mc ls/stat/cat`；Compose 网络内 TEI `/health` 与 `/embed`。
- `docker compose --profile full config --quiet`：通过。
- `mvn -q -pl devpilot-knowledge -am test`：通过。
- `docker run ... maven:3.9.11-eclipse-temurin-21 mvn -q -pl devpilot-agent -am test`：首轮真实 MySQL 测试发现 `SELECTid`；隔离源码副本中的定向真实 MySQL 测试在修复后通过。新增 SSE Controller/Hub 定向测试在 Linux Maven 容器通过。Windows 本机 Maven 的 protobuf 插件被应用控制策略拦截，因此 Agent 模块在 Linux Maven 容器中运行。
- 真实 Compose MySQL 的 `KnowledgeChunkMapperMySqlTest` 通过；33 条候选的 reranker 分批测试通过。
- `docker run ... devpilot-agent-service:local ... pytest tests/test_tool_gateway_client.py -q`：15 项通过，包括脱敏日志、Java INTERNAL 分类与知识检索 deadline。本机 Python grpcio 1.83.0 与生成代码要求的 ≥1.83.1 不匹配，故在镜像运行测试。
- `agent-service/.venv/Scripts/python.exe -m pytest -q`：全套通过。首次全套测试发现确定性 fake 模型未识别新格式的工作流证据，造成混合路径多调一次普通 Tool；只修正该离线模型识别规则后重跑通过。此结果不是最终真实 DeepSeek 成功证据。
- `npm run typecheck`、`npm run build`：通过。定向 Playwright：Agent terminal、失败文档详情、SSE `Last-Event-ID` 重连共 3 项通过；这些为 UI 定向测试，不能代替真实 Full Compose 验收。
- PowerShell smoke 脚本语法解析错误数：0。
- `docker compose --profile full build devpilot-core agent-service devpilot-web` 与 `docker compose --profile full up -d --no-build`：构建成功；最终 `docker compose --profile full ps` 的 11 个服务全部 healthy，`config --quiet` 通过。
- 不带 Bearer 的 History 与 SSE GET 在 Core/Gateway/Web 三层均为 HTTP 401；合法 Bearer 三层 History 和 SSE 另已通过。初次未使用 `-NoProxy` 的本机请求被主机代理返回 502，修正后直连 localhost。没有绕过认证。
- 真实登录 `wzx` 后，在浏览器执行 Run 并在 API 执行 Retry、Search、History、SSE、Smoke；输出和本报告不包含密码、Bearer 或 DeepSeek key。`Invoke-DevPilotAgentSmoke.ps1 -WorkspaceId 1 -ProjectId 4 -RepositoryBindingId 3 -BranchName main` 使用真实 `deepseek` 模式；最终纯 RAG 和自定义混合 prompt 两次输出 `AGENT_GOLDEN_SMOKE_PASS`。Smoke 同时校验 Tool started/completed、SSE terminal、单条 Run SUCCEEDED 非空输出及 History List 包含新 Run。最后再次查询三层 History 均 `COMMON_0000` 且含新 Run；三层终态 SSE 均 HTTP 200、`text/event-stream`、正常 EOF（curl exit 0，事件流各 10591 bytes）。重新构建包含全部源代码的最终 agent-service 镜像后，再次真实 Smoke 通过，Run `d1ac83ac-cbf0-4516-996d-36edaee31934`，追踪来源为三份实际 Markdown。

## Golden Paths 实测状态

| 路径 | 当前结果 |
| --- | --- |
| TEI embedding | `/health=200`，`/embed=200`，384 维，模型已加载后的两次请求 85/107 ms |
| TEI reranker | `/health=200`，`/rerank=200`，返回 `index/score`，首次/重复 183/94 ms；20 条较长候选 3322/2674 ms |
| RAG Retry → READY → search hits | 4 份 Markdown 均 READY，chunks 分别 18/9/10/15；Core/Gateway/Web Direct Search HTTP 200、`COMMON_0000`、5 hits |
| 普通 Tool Gateway / knowledge.search Tool | 真实 Run 中 `task.list_open` 和 `knowledge.search` 均 TOOL_COMPLETED，Java/Python 日志均记录成功；知识工具约 8–13 秒 |
| 真实 DeepSeek Agent SUCCEEDED | 浏览器 Run `26645acc-146e-4a1c-96c8-165f4fa8dad9`、纯 RAG Smoke `6353077e-f04d-4602-8b0e-9d86674f5850`、HYBRID Smoke `d454b90f-a92c-45f6-bbba-c3f3bb5ca401` 及最终镜像 Smoke `d1ac83ac-cbf0-4516-996d-36edaee31934` 均 SUCCEEDED、finalOutput 非空，后面三次追踪有真实来源文件 |
| SSE Browser → Web → Gateway → Core | 实际浏览器无中断误报；三层 HTTP 200、`text/event-stream`、terminal 正常 EOF；`Last-Event-ID` 与 Core 重启后旧终态 `replay-gap` 正常关闭 |
| History Core/Gateway/Web | 三层 HTTP 200、`COMMON_0000`，第一页含新 SUCCEEDED Run；Smoke 重查 History List |

## Definition of Done 对照

| 条件 | 状态 |
| --- | --- |
| 至少一个 Markdown READY 且 chunkCount > 0 | 已达成；4 份 READY，合计 52 chunks |
| Direct Knowledge Search 返回真实 hits | 已达成；三层 200、5 hits |
| TEI readiness 可观测 | 已达成；两个 `/health=200` 且 Full Compose healthy |
| FAILED 有稳定安全诊断 | 已达成；数据库稳定码、结构化日志、页面 Technical Details |
| 普通 Tool Gateway 成功 | 已达成；`task.list_open` 真实调用完成 |
| knowledge.search Tool 成功 | 已达成；真实 Run 多次完成并返回文档来源 |
| 真实 DeepSeek Tool Run SUCCEEDED | 已达成；浏览器和最终两次 Smoke 均通过 |
| SSE terminal 正常关闭，不误报中断 | 已达成；浏览器与三层真实流验证 |
| History List 真实 200 且包含新 Run | 已达成；三层 API 和 Smoke 验证 |
| Full-stack smoke 真验证 History List | 已达成；新 Run 位于 List 第一页 |
| 不 push、不 commit | 已遵守 |

## 剩余风险与依赖

- reranker 1.11 GB ONNX 首次下载耗时约 18.6 分钟；模型缓存卷保留。两个 TEI 同时健康时常驻内存约 1.1 GiB（embedding）+ 2.1 GiB（reranker），仍需观察多次完整 Run 后的峰值。
- `knowledge.search` 最终真实 Tool Gateway 耗时约 8–13 秒，专用 30 秒 deadline 有余量，但大型知识库或冷启动仍可能增加耗时。reranker 分批会增加多次远端请求；当前 5 hits、最多 50 候选的真实检索已通过。
- 最终修复前曾出现一次工具均完成后的 `MODEL_ERROR`，重启部署后纯 RAG 与 HYBRID 两次连续真实 Smoke 通过；由于当次日志未保存 Provider 的稳定失败分类，不能断言该单次错误根因。现模型节点只记录脱敏的 Provider kind / exception type，后续若再发生可继续定位，不暴露响应正文。
- Core 周期日志中的 Outbox backlog `NullPointerException` 与 GitHub 远端网络错误独立于本次 RAG/Agent 路径，本轮未修改相应模块。

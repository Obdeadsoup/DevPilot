# DevPilot Agent Service

`agent-service` 是独立的 Python Agent Runtime 工程，要求 Python 3.11 或更高版本。

它当前提供 Provider 无关的 Message、Model Protocol、结构化 ToolCall/ModelResponse、ToolRegistry、无副作用
EchoTool，以及带 `max_steps`、`max_tool_calls` 和重复 `tool_call_id` 防护的同步 Agent Loop。真实 Provider
Adapter 使用 OpenAI-compatible Chat Completions 协议，默认连接 DeepSeek；自动化测试使用 FakeModel/Fake Client，
不访问网络、不需要 API Key，也不消耗 Token。

P0-06 在保留 Unary `StartRun` 的同时实现 `StreamRun` Server Streaming。同步 AgentLoop 通过可选
Provider-neutral `RuntimeEvent` hook 产生 model/tool 生命周期，Servicer 用容量 64 的 Queue 和独立
worker thread 桥接为严格递增的 protobuf `AgentEvent`。当前已有协作式 `CancelRun`；
仅显式取消设置 token，客户端断流不取消 Run。

P0-07 实现反向 `DevPilotToolGateway.ExecuteTool`：生产 DeepSeek Runtime 注册
`project.get_summary`、`task.list_open` 和 `project.list_recent_activity` 三个 Remote Tool。AgentLoop 只新增
Provider-neutral `RunContext` 传递，仍不感知 gRPC；Python 通过一个长生命周期 Channel 携带内部 service key
调用 Java，Java 从 runId 恢复 actor/scope 并实时 RBAC。Tool result 最大 64 KiB，项目/任务/Activity 文本整体按
untrusted data 处理。

P1-01 增加 RuntimeRun、Model/Tool Step 和版本化 JSON Checkpoint，通过 Repository 抽象写入独立 SQLite。
每个执行边界原子提交 Step 事实、Run 进度和 Checkpoint，支持按 run_id 查询最新快照。
P1-02 在已有协作式 Cancel 上增加持久取消意图、CAS、Checkpoint v2 显式恢复和单实例启动收敛。
它不属于 Maven reactor，不嵌入 `devpilot-agent` Java 模块，也不得直接连接或修改 DevPilot 的 `dp_*` 业务表。

跨进程通信只能基于 `../contracts/agent/v1` 中的 `.proto` 契约：Java 可通过 BlockingStub 调用 Unary
`StartRun`，正式 Browser 链路通过 async Stub 调用 `StreamRun`。Python→Java 只开放上述三个只读业务 Tool；
`EchoTool` 留在教学和测试路径。当前仍没有写 Tool、Proposal/HITL、LangGraph、RAG、Memory 或 MCP，Python 也没有
任何 `dp_*` 数据库连接。

核心代码：

```text
runtime/message.py    内部统一 Message
model/base.py         Model Protocol
model/types.py        ModelResponse / ToolCall
model/providers/      OpenAI-compatible 配置、消息映射与响应归一化
tools/base.py         Tool Protocol / ToolDefinition
tools/registry.py     注册、查找与错误边界
runtime/agent_loop.py 有界运行循环与轻量 Trace
runtime/events.py     不含 protobuf/runId 的公开 Runtime 生命周期事件
runtime/context.py    只含 runId/requestId 的 Provider-neutral RunContext
runtime/persistence.py RuntimeRun / RuntimeStep / RuntimeCheckpointState v1
runtime/repository.py  可替换的 Repository 与事务接口
runtime/sqlite_repository.py 独立 SQLite Runtime Store
runtime/redaction.py   Prompt/结果/快照落库前脱敏
runtime/recovery.py    恢复资格、控制状态校验与暂时性失败分类
runtime/schema.py      SQLite schema v2 与保留 P1-01 数据的迁移
rpc/application.py   gRPC 与 AgentLoop 之间的轻量门面
rpc/servicer.py      Unary 与有界 Queue Server Streaming 边界
rpc/tool_gateway_client.py  长生命周期 Python→Java Unary Client
tools/devpilot.py     三个只读 Remote Tool Adapter
rpc/server.py        Server、真实 ToolRegistry 和 Channel 生命周期装配
rpc/generated/       由共享 proto 生成，不手工修改
```

DeepSeek 配置只从环境变量读取：

```text
DEEPSEEK_API_KEY      必填，不写入日志或异常
DEEPSEEK_BASE_URL     可选，默认 https://api.deepseek.com
DEEPSEEK_MODEL        可选，默认 deepseek-v4-flash
DEVPILOT_JAVA_TOOL_GRPC_TARGET             默认 127.0.0.1:50052
DEVPILOT_AGENT_TOOL_SERVICE_KEY             必填，不写入 proto/日志/Prompt
DEVPILOT_JAVA_TOOL_GRPC_DEADLINE_SECONDS    默认 3
DEVPILOT_JAVA_TOOL_GRPC_MAX_RESULT_BYTES    默认 65536
AGENT_RUNTIME_DB_PATH                     默认 .runtime/agent-runtime.sqlite3
```

真实 smoke 只供人工执行；缺少 Key 时会明确输出 `NOT RUN`：

```powershell
python agent-service/examples/deepseek_tool_smoke.py
```

重新生成 Python Stub：

```powershell
python agent-service/scripts/generate_grpc.py
```

启动真实 gRPC Server：

```powershell
# 确定性本地联调，不访问 LLM
$env:AGENT_MODEL_MODE = "fake"
python -m devpilot_agent_service.rpc.server

# 默认 DeepSeek 路径，需要 DEEPSEEK_API_KEY
Remove-Item Env:AGENT_MODEL_MODE -ErrorAction SilentlyContinue
python -m devpilot_agent_service.rpc.server
```

默认绑定 `AGENT_GRPC_HOST=0.0.0.0`、`AGENT_GRPC_PORT=50051`。当前 Server 是无服务身份的 plaintext 边界，
只适合本地/受控网络；TLS、Service-to-Service Auth 和部署发现留待后续章节。

跨语言 smoke 必须先启动独立 Python fake Server，再在另一个终端运行 Java 测试：

```powershell
$env:DEVPILOT_AGENT_CROSS_LANGUAGE_SMOKE = "true"
mvn -pl devpilot-agent -am `
  "-Dtest=CrossLanguageGrpcSmokeTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

本地验证：

```powershell
python -m pytest agent-service/tests
python -c "import sys; sys.path.insert(0, 'agent-service/src'); import devpilot_agent_service"
python -m ruff check agent-service
```

Ruff 通过 `dev` extra 声明；若本机尚未安装，可以先在隔离虚拟环境中安装 `.[dev]`，不要修改系统 Python。

## Runtime Persistence

`AgentLoop` 构造时必须注入 `repository`。Server 的 fake 和 DeepSeek 模式都通过
`config.create_runtime_repository()` 装配 SQLite；教学脚本使用同一工厂，测试注入 `tmp_path` 下的数据库。
本地相对路径基于 Python 进程工作目录，自动创建父目录。Compose 固定使用
`/app/data/agent-runtime.sqlite3`，挂载 `devpilot-agent-runtime-data`，普通 `compose down` 保留数据。

StartRun/StreamRun 的重复 `run_id` 返回 `ALREADY_EXISTS`。ResumeRun 是独立的 Server Streaming RPC，
仅接受原 run_id/request_id，从最新 v2 Checkpoint 恢复；不接收 user_input，不隐式重跑原请求。
脱敏后的快照标记 `redacted=true`，本阶段明确拒绝恢复，防止缺失凭据或参数被错误重放。
Runtime 存储包含经过脱敏的执行上下文，仍应限制数据目录的读取权限；它不是长期 Memory。

本地查询（在与 Server 相同的工作目录/配置下运行）：

```python
from devpilot_agent_service.config import create_runtime_repository

repository = create_runtime_repository()
run = repository.get_run("your-run-id")
steps = repository.list_steps("your-run-id")
checkpoint = repository.get_latest_checkpoint("your-run-id")
if checkpoint is not None:
    state = checkpoint.state  # 显式重建 Message / ToolCall，未知版本会拒绝。
    print(checkpoint.checkpoint_no, state.status, state.next_action)
```

完整学习材料：[文件地图、调用链、Checkpoint 生命周期、关键 Diff、Schema 和设计取舍](docs/runtime-persistence.md)。

## Cancel / Resume

`CancelRun` 先持久化 `CANCEL_REQUESTED` 再通知 Event；当前 Model/Tool I/O 返回后停止后续执行并提交
`CANCELLED`。取消不会撤销已经成功的 Tool。原 accepted/status 字段不变，新增 runtime_status 返回持久状态。
终态受条件更新保护，CANCELLED 不允许 Resume，断流不产生 Cancel 意图。

Server 启动时自动保留并迁移 P1-01 数据库，然后在监听请求前将旧 RUNNING/PENDING 标记
`FAILED/RUNTIME_INTERRUPTED/retryable`，将 CANCEL_REQUESTED 收敛为 CANCELLED；不自动执行恢复。
此策略要求一个数据库只有一个 Runtime 服务实例，不是 lease/heartbeat 或多实例接管方案。
旧 v1 快照保留原 JSON 供查询，无法自动转换成完整 v2 控制状态，恢复会返回 UNSUPPORTED_STATE_VERSION。

```python
import grpc
from devpilot_agent_service.rpc.generated import agent_runtime_pb2 as pb
from devpilot_agent_service.rpc.generated import agent_runtime_pb2_grpc as rpc

with grpc.insecure_channel("127.0.0.1:50051") as channel:
    stub = rpc.AgentRuntimeStub(channel)
    for event in stub.ResumeRun(
        pb.ResumeRunRequest(run_id="your-run-id", request_id="original-request-id"),
        timeout=60,
    ):
        print(event.sequence, event.type, event.step)
```

只有明确暂时性失败和中断记录可能恢复；仍需通过版本、快照完整性、脱敏、预算和状态校验。
重试继续消耗原预算，已完成 Tool 不重复执行。事件序号从本次流的 1 开始，不提供历史 replay。
本次新增的是内部 Runtime RPC，没有增加浏览器或 Java 业务侧恢复入口；真实 Remote Tool 仍受 Java
Run 状态和 RBAC 校验约束，详见 [Cancel / Resume 学习材料与集成边界](docs/cancel-resume.md)。

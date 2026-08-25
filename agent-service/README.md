# DevPilot Agent Service

`agent-service` 是独立的 Python Agent Runtime 工程，要求 Python 3.11 或更高版本。

它当前提供 Provider 无关的 Message、Model Protocol、结构化 ToolCall/ModelResponse、ToolRegistry、无副作用
EchoTool，以及带 `max_steps`、`max_tool_calls` 和重复 `tool_call_id` 防护的同步 Agent Loop。真实 Provider
Adapter 使用 OpenAI-compatible Chat Completions 协议，默认连接 DeepSeek；自动化测试使用 FakeModel/Fake Client，
不访问网络、不需要 API Key，也不消耗 Token。

P0-04 已加入由共享 proto 生成的 Python Stub 和真正可启动的同步 gRPC Server。`StartRun` 会校验请求、委托
AgentLoop，并返回 Final；`StreamRun`、`CancelRun` 和反向 `DevPilotToolGateway` 仍保持 `UNIMPLEMENTED`。

它未来还会负责执行期 step/checkpoint、Conversation runtime context、工具调用编排与流式事件产生。
它不属于 Maven reactor，不嵌入 `devpilot-agent` Java 模块，也不得直接连接或修改 DevPilot 的 `dp_*` 业务表。

跨进程通信只能基于 `../contracts/agent/v1` 中的 `.proto` 契约：Java 当前可通过 generated Stub 调用 Python
`AgentRuntime.StartRun`。Python→Java `DevPilotToolGateway` 尚未实现。当前仍没有 LangGraph、RAG、Memory 或
DevPilot 业务 Tool；`EchoTool` 只用于教学和测试，不代表 Tool Gateway 已实现。

核心代码：

```text
runtime/message.py    内部统一 Message
model/base.py         Model Protocol
model/types.py        ModelResponse / ToolCall
model/providers/      OpenAI-compatible 配置、消息映射与响应归一化
tools/base.py         Tool Protocol / ToolDefinition
tools/registry.py     注册、查找与错误边界
runtime/agent_loop.py 有界运行循环与轻量 Trace
rpc/application.py   gRPC 与 AgentLoop 之间的轻量门面
rpc/servicer.py      StartRun 校验、调用与 Status 边界
rpc/server.py        Server 配置、模型模式和进程入口
rpc/generated/       由共享 proto 生成，不手工修改
```

DeepSeek 配置只从环境变量读取：

```text
DEEPSEEK_API_KEY      必填，不写入日志或异常
DEEPSEEK_BASE_URL     可选，默认 https://api.deepseek.com
DEEPSEEK_MODEL        可选，默认 deepseek-v4-flash
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

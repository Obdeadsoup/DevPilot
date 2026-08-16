# DevPilot Agent Service

`agent-service` 是独立的 Python Agent Runtime 工程，要求 Python 3.11 或更高版本。

它当前提供 Provider 无关的 Message、Model Protocol、结构化 ToolCall/ModelResponse、ToolRegistry、无副作用
EchoTool，以及带 `max_steps` 硬停止线的同步最小 Agent Loop。核心测试全部使用脚本化 FakeModel，不访问网络、
不需要 API Key，也不消耗 Token。

它未来还会负责真实 LLM Adapter、执行期 step/checkpoint、Conversation runtime context、工具调用编排与流式事件产生。
它不属于 Maven reactor，不嵌入 `devpilot-agent` Java 模块，也不得直接连接或修改 DevPilot 的 `dp_*` 业务表。

跨进程通信只能基于 `../contracts/agent/v1` 中的 `.proto` 契约：Java 调用 Python 的 `AgentRuntime`；Python
调用 Java 的 `DevPilotToolGateway`。当前 Agent Loop 全部在 Python 单进程内运行，没有启动服务、gRPC Stub、真实
LLM、LangGraph、RAG、Memory 或 DevPilot 业务 Tool。`EchoTool` 只用于教学和测试，不代表 Tool Gateway 已实现。

核心代码：

```text
runtime/message.py    内部统一 Message
model/base.py         Model Protocol
model/types.py        ModelResponse / ToolCall
tools/base.py         Tool Protocol / ToolDefinition
tools/registry.py     注册、查找与错误边界
runtime/agent_loop.py 有界运行循环与轻量 Trace
```

本地验证：

```powershell
python -m pytest agent-service/tests
python -c "import sys; sys.path.insert(0, 'agent-service/src'); import devpilot_agent_service"
python -m ruff check agent-service
```

Ruff 通过 `dev` extra 声明；若本机尚未安装，可以先在隔离虚拟环境中安装 `.[dev]`，不要修改系统 Python。

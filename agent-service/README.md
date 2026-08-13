# DevPilot Agent Service

`agent-service` 是独立的 Python Agent Runtime 进程骨架，要求 Python 3.11 或更高版本。

它未来负责 LLM / Agent Loop、执行期 step/checkpoint、Conversation runtime context、工具调用编排与流式事件产生。
它不属于 Maven reactor，不嵌入 `devpilot-agent` Java 模块，也不得直接连接或修改 DevPilot 的 `dp_*` 业务表。

跨进程通信只能基于 `../contracts/agent/v1` 中的 `.proto` 契约：Java 调用 Python 的 `AgentRuntime`；Python
调用 Java 的 `DevPilotToolGateway`。当前章节只建立可导入包和 smoke test，没有启动服务、gRPC Stub、LLM、
LangGraph、RAG、Memory 或真实 Tool。

本地验证：

```powershell
python -m pytest agent-service/tests
python -c "import sys; sys.path.insert(0, 'agent-service/src'); import devpilot_agent_service"
python -m ruff check agent-service
```

Ruff 通过 `dev` extra 声明；若本机尚未安装，可以先在隔离虚拟环境中安装 `.[dev]`，不要修改系统 Python。

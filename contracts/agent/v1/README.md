# DevPilot Agent v1 RPC Contract

本目录是 Java `devpilot-agent` 与 Python `agent-service` 之间唯一的跨语言契约来源。两端未来都从这里的
`.proto` 生成 Stub；禁止各自在 Java/Python 中维护同语义、独立演进的跨进程 DTO。

## 调用方向

| Service | 调用方 → 提供方 | 职责 |
|---|---|---|
| `AgentRuntime` | Java → Python | 启动、流式观察和取消 Agent Run |
| `DevPilotToolGateway` | Python → Java | 在 Java 认证、Scope、风险策略与审计边界内申请执行工具 |

浏览器不直接调用 Python。请求先进入 Java，由 Java 解析当前用户并校验 Workspace/Project scope，再通过
`AgentRuntime` Adapter 调用 Python。Python 需要项目事实或业务动作时，只能调用 Java
`DevPilotToolGateway`；Gateway 后续再委托 Task/Project/GitHub 的公开 Application Service，不能直连 Mapper。

## v1 演进策略

`StartRun` 已形成第一个可联调的同步 Unary 契约：Java 生成 `request_id/run_id` 并提交 `user_input`，Python
运行 AgentLoop 后返回同一 `run_id`、`final_output` 和稳定 `RunStatus`。Java 与 Python Stub 都从本文件生成，
generated code 不承载手工业务逻辑，也不作为 Application Core DTO。

`StreamRun` 已用于正式 Browser 链路：Java 提交 request/run/input，Python 以 Server Streaming 返回带严格
sequence/eventId 的类型化生命周期事件。事件只含 step、tool name、final output 和稳定 failure kind；不承载
reasoning、Prompt、Tool 参数/结果、Provider body 或凭据。`CancelRun` 仍保持 `UNIMPLEMENTED`。

`DevPilotToolGateway.ExecuteTool` 已形成 Python→Java 的 Unary v1 契约：Python 只提交
`request_id/run_id/tool_call_id/tool_name/Struct arguments`，不提交 userId、Workspace、Project、角色或权限。
Java 根据 runId 恢复权威委托上下文并重新 RBAC，成功响应回显 tool_call_id，并用 Struct 返回有界、标记为
untrusted 的结果。后续扩展必须保持字段号稳定，删除字段时使用 `reserved`，并先修改 proto 再生成两端代码。

跨服务的 authentication/authorization 不由占位字段替代。Java 始终是 User/RBAC、Workspace/Project、
GitHub Snapshot、Task、Audit/Notification 和未来 AgentRun 业务投影的权威拥有者；Python 保存的 request
identity 仅用于关联一次 RPC，不可据此提升权限。

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
reasoning、Prompt、Tool 参数/结果、Provider body 或凭据。`CancelRun` 已实现持久意图和协作式取消；
原 `accepted=1/status=2` 字段保留，新增 `runtime_status=3` 用于返回持久 Runtime 状态。

P1-02 追加 `ResumeRun(ResumeRunRequest) returns (stream AgentEvent)`，请求只有原 run_id/request_id。
P1-03 追加 `ResumeApproval`、`CreateToolProposal` 和 `GetToolProposal`。浏览器审批走 Java HTTP，
Runtime 恢复时只携带 proposal_id，再从 Java 读取固化决议和结果，不重传 Tool 参数。
恢复拒绝返回安全的 NOT_FOUND 或 FAILED_PRECONDITION，原因包括状态不可重试、快照缺失/损坏/版本不支持、
脱敏和预算耗尽；恢复后执行失败继续使用原 RUN_FAILED 分类。Resume 从最新显式控制状态继续，
不重新提交用户输入。每次调用的事件序号从 1 开始，不提供跨调用历史 replay。
Java Stub 仍从共享 proto 生成；本次没有新增 Java REST Resume 业务流程。

`DevPilotToolGateway.ExecuteTool` 已形成 Python→Java 的 Unary v1 契约：Python 只提交
`request_id/run_id/tool_call_id/tool_name/Struct arguments`，不提交 userId、Workspace、Project、角色或权限。
Java 根据 runId 恢复权威委托上下文并重新 RBAC，成功响应回显 tool_call_id，并用 Struct 返回有界、标记为
untrusted 的结果。后续扩展必须保持字段号稳定，删除字段时使用 `reserved`，并先修改 proto 再生成两端代码。

跨服务的 authentication/authorization 不由占位字段替代。Java 始终是 User/RBAC、Workspace/Project、
GitHub Snapshot、Task、Audit/Notification 和未来 AgentRun 业务投影的权威拥有者；Python 保存的 request
identity 仅用于关联一次 RPC，不可据此提升权限。

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

`StreamRun`、`CancelRun` 和 `DevPilotToolGateway` 仍只保留方向与占位字段，本章保持 `UNIMPLEMENTED`，没有伪造
流式事件、取消或业务 Tool。后续扩展必须保持字段号稳定，删除字段时使用 `reserved`，并先修改 proto 再生成两端
代码。错误详情、鉴权 metadata、AgentEvent payload、Proposal/Confirm 和 Tool catalog 仍待后续设计。

跨服务的 authentication/authorization 不由占位字段替代。Java 始终是 User/RBAC、Workspace/Project、
GitHub Snapshot、Task、Audit/Notification 和未来 AgentRun 业务投影的权威拥有者；Python 保存的 request
identity 仅用于关联一次 RPC，不可据此提升权限。

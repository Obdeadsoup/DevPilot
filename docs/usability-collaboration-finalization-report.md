# DevPilot 可用性与协作能力完善报告

## 基线

- Branch: `agent`
- Before SHA: `5ce0b4abedf89f845a49a6f862f06e1defd378f4`
- 本轮不提交 Git commit；工作区还包含先前认证、邮箱验证和前端改动，均被保留。

## 已实现

### Agent 运行历史

- `GET /api/v1/workspaces/{workspaceId}/projects/{projectId}/agent-runs` 支持 `status`、`page`（从 0 起）和 `size`（默认 20，最大 100）。
- 列表按 `started_at DESC, id DESC` 返回轻量摘要，不返回 `userInput` 或 `finalOutput`；详情仍使用既有 `GET .../{runId}`。
- 列表和详情都在 Java Core 中以 `AGENT_READ` 验证 workspace/project scope；不会泄露其它项目的 run。
- Agent 页面增加全部/成功/失败历史、分页、加载/错误/空态和详情跳转；运行中的详情继续使用已有 SSE。

### GitHub 分支

- 新增 `GET /api/v1/workspaces/{workspaceId}/projects/{projectId}/github-repositories/{bindingId}/branches`。
- 分支读取经既有 GitHub credential resolver、SSRF policy、rate limit/retry、受控日志和 `REPOSITORY_READ`；浏览器不直连 GitHub。
- 不创建 branch table；返回实时 branch name 与 commit SHA。

### Workspace 协作

- 沿用既有 `dp_workspace_member`、`dp_project_member`、Workspace/Project RBAC。
- 新增兼容迁移使邀请可进入 `REJECTED` 终态，保留审计而不产生权限。
- 新增成员 HTTP 契约：成员列表、按已注册 ACTIVE 账户邮箱邀请、当前用户接受/拒绝、改角色、移除、所有权转移。
- 接受/拒绝严格绑定当前登录用户；移除仍通过已有事务同时撤销该用户 Project 成员访问。
- Workspace 详情页提供成员列表与按邮箱邀请表单。

## 关键调用链

```text
AgentHistoryPage -> Java AgentRunController -> AgentRunApplicationService
-> ProjectAuthorizationService(AGENT_READ) -> AgentRunMapper -> dp_agent_run

WorkspaceDetail -> WorkspaceMemberController -> WorkspaceMemberService
-> CurrentUser + WorkspaceAuthorizationService -> UserAccountService(email)
-> dp_workspace_member -> dp_project_member(revoke on removal)

Browser -> GitHubRepositoryController -> GitHubRepositoryBindingService
-> ProjectAuthorizationService(REPOSITORY_READ) -> GitHubBranchClient
-> GitHubApiHttpExecutor -> GitHub REST
```

## 数据库迁移

- `V16__add_workspace_member_rejected_status.sql`：扩展既有成员状态 CHECK 为 `REJECTED`，不 drop 表、不改历史数据。

## 验证结果

- `npm run build`（`devpilot-web`）：通过。
- `mvn -pl devpilot-github -am -DskipTests compile`：通过。
- `mvn -pl devpilot-agent -DskipTests compile`：通过。
- `docker compose config`：通过。
- `mvn -pl devpilot-project,devpilot-agent -am test` 与 `mvn -pl devpilot-github -am test` 已启动并完成已输出模块的测试；当前桌面命令通道在完整反应堆结束前截断，未将其记作最终全绿。
- 未进行真实全栈 smoke：当前 Compose 仅定义 MySQL/Redis，且本机没有可用 Docker Desktop socket；没有真实 GitHub/SMTP 凭据。

## 已知限制与后续契约工作

当前 protobuf `StartRun` 命令没有 repository、branch 或 commit SHA 字段。因此本轮只安全完成分支读取 API，**没有伪造**“分支已传给 Python Agent”或把 branch/commit 写入 `dp_agent_run`。要完成该能力，需要先修改 proto source、再生成代码，并在 Java Agent command/Python runtime 同步加入 branch snapshot；这是明确的跨服务契约变更，不应通过硬编码绕过。

邀请目前写入现有成员表并可由 UI 列表看到；未向 notification 模块直接调用，以保持模块依赖方向。若需站内通知，应由现有 outbox/notification 订阅成员邀请事件实现。

## 更新文件地图

新增：

- `devpilot-agent/.../AgentRunHistoryItem.java`：运行历史摘要投影；由 Application Service/API 使用。
- `devpilot-agent/.../AgentRunHistoryResponse.java`：历史 HTTP 响应。
- `devpilot-github/.../GitHubBranch*.java`：受控 GitHub 分支读取 Client 和 DTO。
- `devpilot-project/.../WorkspaceMemberController.java` 及 DTO：成员协作 HTTP 契约。
- `devpilot-boot/.../V16__add_workspace_member_rejected_status.sql`：拒绝邀请状态兼容迁移。

修改：

- `AgentRunMapper`、`AgentRunPersistenceService`、`AgentRunApplicationService`、`AgentRunController`：真实分页历史链及 RBAC。
- `GitHubRepositoryBindingService`、`GitHubRepositoryController`：绑定仓库分支入口。
- `UserMapper`、`UserAccountService`、`WorkspaceMemberService`、`WorkspaceMemberMapper`：邮箱解析和邀请闭环。
- `devpilot-web/src/views/agent/AgentRunView.vue`、`views/workspace/WorkspaceDetail.vue` 及 API/types：实际 UI 操作链。

推荐阅读顺序：`AgentRunController` → `AgentRunApplicationService` → `AgentRunPersistenceService` → `AgentRunMapper` → `WorkspaceMemberController` → `WorkspaceMemberService` → `GitHubRepositoryBindingService` → `AgentRunView.vue`。

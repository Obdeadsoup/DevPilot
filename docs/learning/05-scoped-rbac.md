# 第 5 节：Workspace / Project 作用域 RBAC

## 1. User、Role 与 Permission

User 回答“当前是谁”，Role 是一组稳定的职责，Permission 是业务动作。DevPilot 的业务服务
优先询问“是否拥有某个 Permission”，而不是在每个分支重复比较 `role == ADMIN`。当前角色在
Java 枚举中把权限保存为不可变 `Set`，数据库只保存角色名，不保存动态权限关系。

## 2. 全局角色与作用域角色

全局角色在整个平台生效；作用域角色只在特定 Workspace 或 Project 生效。同一用户可以是
Workspace A 的 ADMIN、Workspace B 的 VIEWER，同时是 A 中 Project X 的
DEVELOPER。Access Token 只保存 `id / username / displayName` 等身份，不缓存这些易变化的
作用域角色。

## 3. 为什么全局 ADMIN / USER 不够

如果只区分全局 ADMIN/USER，一个 Workspace 的管理员可能读取或修改另一个 Workspace 的
资源。多租户协作系统必须把“谁”“在哪个 Workspace”“哪个 Project”“做什么动作”一起判断，
而不是把登录等同于授权。

## 4. 平台、Workspace、Project 三层

平台层当前只有登录身份，没有实现平台 Break-glass。Workspace 层由 identity 模块负责
OWNER/ADMIN/MEMBER/VIEWER。Project 层由 project 模块负责
PROJECT_ADMIN/DEVELOPER/VIEWER、项目可见性和资源归属。当前模块依赖是单向
`project → identity`。

## 5. Workspace OWNER 如何存储

OWNER 只从 `dp_workspace.owner_user_id` 推导，不在 `dp_workspace_member.role` 中保存。
这样一个 Workspace 只有一个权威所有者，也不会出现两条 OWNER 成员记录。V4 对无法推断
owner 的历史数据保留 NULL 迁移兼容，但所有新 Workspace 和测试 fixture 必须明确 owner；
NULL 不会获得 OWNER 权限，迁移也不会创建固定生产用户。

所有权转移只能调用 `WorkspaceMemberService.transferOwnership`。它要求当前用户是 OWNER，
验证新 OWNER 为 ACTIVE 用户，然后执行：

```sql
UPDATE dp_workspace
SET owner_user_id = ?, version = version + 1
WHERE id = ?
  AND owner_user_id = ?
  AND version = ?
  AND status = 'ACTIVE'
  AND deleted = 0
```

只有一个并发请求能更新一行。成功后，新 OWNER 原有成员记录标记 REMOVED，旧 OWNER 在同一
事务中写入或更新为 ACTIVE ADMIN。

## 6. Workspace 角色权限矩阵

| Permission | OWNER | ADMIN | MEMBER | VIEWER |
|---|---:|---:|---:|---:|
| WORKSPACE_READ | ✓ | ✓ | ✓ | ✓ |
| WORKSPACE_UPDATE | ✓ | ✓ |  |  |
| WORKSPACE_DELETE | ✓ |  |  |  |
| WORKSPACE_TRANSFER_OWNERSHIP | ✓ |  |  |  |
| WORKSPACE_MEMBER_LIST | ✓ | ✓ | ✓ | ✓ |
| WORKSPACE_MEMBER_INVITE | ✓ | ✓ |  |  |
| WORKSPACE_MEMBER_ROLE_UPDATE | ✓ | ✓ |  |  |
| WORKSPACE_MEMBER_REMOVE | ✓ | ✓ |  |  |
| PROJECT_CREATE | ✓ | ✓ | ✓ |  |
| WORKSPACE_AUDIT_READ | ✓ | ✓ |  |  |
| WORKSPACE_AGENT_POLICY_MANAGE | ✓ | ✓ |  |  |

ADMIN 不能通过普通成员操作设置 OWNER，也不能转移或删除 Workspace。当前策略还禁止 ADMIN
邀请/任命 ADMIN 或管理另一名 ADMIN；这些治理动作留给 OWNER。用户不能通过
`changeMemberRole` 修改自己的角色。

只有 ACTIVE Workspace Member 有业务权限。INVITED、SUSPENDED、REMOVED 以及 DISABLED
Workspace 都不产生有效权限。邀请与激活已经有应用服务，其中激活是未暴露 HTTP API 的受控
内部操作。

## 7. Project 角色权限矩阵

| Permission | PROJECT_ADMIN | DEVELOPER | VIEWER |
|---|---:|---:|---:|
| PROJECT_READ | ✓ | ✓ | ✓ |
| PROJECT_UPDATE | ✓ | ✓ |  |
| PROJECT_ARCHIVE | ✓ |  |  |
| PROJECT_MEMBER_LIST | ✓ | ✓ | ✓ |
| PROJECT_MEMBER_MANAGE | ✓ |  |  |
| PROJECT_ACTIVITY_READ | ✓ | ✓ | ✓ |
| REPOSITORY_READ | ✓ | ✓ | ✓ |
| REPOSITORY_BIND / UNBIND | ✓ |  |  |
| REPOSITORY_UPDATE | ✓ | ✓ |  |
| TASK_READ | ✓ | ✓ | ✓ |
| TASK_CREATE / UPDATE / ASSIGN / STATUS_CHANGE | ✓ | ✓ |  |
| TASK_DELETE | ✓ |  |  |
| AGENT_READ | ✓ | ✓ | ✓ |
| AGENT_PROPOSE | ✓ | ✓ |  |
| AGENT_EXECUTE_CONFIRMED | ✓ |  |  |
| PROJECT_AUDIT_READ | ✓ |  |  |

Workspace OWNER/ADMIN 对本 Workspace 下所有 Project 获得 PROJECT_ADMIN 等效权限。
Project Admin 可以管理 DEVELOPER/VIEWER，但不能任命、修改或移除另一名 PROJECT_ADMIN；
只有 Workspace OWNER/ADMIN 可以管理 PROJECT_ADMIN。Project Role 永远不会反向授予
Workspace 管理权限。

## 8. PRIVATE 与 INTERNAL

PRIVATE Project：Workspace OWNER/ADMIN 可访问，其他用户必须同时是 ACTIVE Workspace
Member 和 ACTIVE Project Member。

INTERNAL Project：ACTIVE Workspace Member 即使没有 Project Membership，也获得 VIEWER
只读集合；写权限仍要求 Project Membership 或 Workspace OWNER/ADMIN。Project ARCHIVED
后所有来源都只保留标记为只读的 Permission，普通成员变更和项目修改被拒绝。

## 9. 有效权限如何计算

`WorkspaceAuthorizationService` 先读取未删除 Workspace 并检查 ACTIVE，再比较
`owner_user_id`；非 OWNER 只接受 ACTIVE `dp_workspace_member`。

`ProjectAuthorizationService` 随后：

1. 用 `workspaceId + projectId` 查询未删除 Project；
2. 计算当前用户的有效 Workspace Role；
3. OWNER/ADMIN 走 Workspace 管理来源；
4. 其他用户查询同一 scope 的 ACTIVE Project Member；
5. 没有 Project Member 时，仅 INTERNAL 提供 VIEWER 权限；
6. ARCHIVED 过滤掉写 Permission。

任何一步不成立都不产生权限。

## 10. 接口权限、作用域权限、资源归属

第一层 Security Filter Chain 判断是否已登录，未登录返回 JSON 401。第二层方法安全判断
具体 Permission。第三层授权服务和 SQL 验证 Project 确实属于 URL 中的 Workspace。
当前敏感 Activity 接口对跨 Workspace、项目无权访问或不存在有效成员关系统一返回 JSON
403，避免通过错误差异探测资源归属。

## 11. RBAC 与资源所有者规则

RBAC 说明一个角色通常能做什么；“只有当前 OWNER 可以转移所有权”“不能修改自己角色”
“Project Admin 不能任命同级 Admin”属于额外的资源所有者或防提权规则。它们不能仅靠一个
Permission Set 表达，因此集中在成员管理应用服务，而不是散落在 Controller。

## 12. 方法级安全

Boot 启用 `@EnableMethodSecurity`。Activity 查询使用：

```java
@PreAuthorize(
    "@projectAuthorization.hasPermission("
        + "authentication, #workspaceId, #projectId, 'PROJECT_ACTIVITY_READ')"
)
```

Maven 编译使用 `-parameters`（编译日志为 `debug parameters release 21`），表达式能解析参数
名。表达式接收当前 `Authentication`，并只接受 `DevPilotUserPrincipal`，不会信任客户端
传入 userId。

## 13. 为什么 Service 层仍要授权

Controller 不是唯一调用入口；以后还会有内部任务和 Agent 工具。把用户查询授权放在
`ProjectActivityService.queryTimeline`，可以防止其他入口绕过 Controller。Webhook 的
`recordGitHubActivity` 是已验签 Delivery 的系统处理链路，不伪装成登录用户，因此没有套用
用户读取权限。

成员服务也在应用层调用授权服务。角色变更使用 `version` 条件 UPDATE，更新数不是 1 就返回
409；Workspace Member 被移除时，通过 identity 定义、project 实现的最小端口在同一事务把
其 Project Membership 标记 REMOVED。

## 14. SQL 为什么必须带作用域

只按裸 `projectId` 查询再忽略 URL 的 `workspaceId` 会制造越权窗口。当前 Project 查询使用
`id + workspace_id`，Activity 使用 `workspace_id + project_id`，Project Member 使用
`workspace_id + project_id + user_id`。数据库的 `(project_id, workspace_id)` 复合外键还
防止写入一个声称属于错误 Workspace 的成员关系。

唯一约束比“先查再插”可靠：两个并发邀请都可能先查不到，但只有一个能通过
`UNIQUE(workspace_id, user_id)`；Project Member 同理由
`UNIQUE(project_id, user_id)` 保证。

## 15. GitHub 身份与本地权限边界

Webhook `sender.id/login` 只写为外部 actor 元数据。`actor_login` 即使与本地 username
相同，也不能恢复 Authentication。GitHub repository role 不直接映射 DevPilot Role；
GitHub App permission 只决定 App 能调用哪些外部 API，不代替本地用户授权。未来账号关联
必须使用稳定 GitHub user id，不能只依赖可修改的 login。自动角色映射最多生成管理员确认的
建议，不能直接提权。

## 16. 从固定角色演进到动态角色

固定角色的优势是矩阵可审查、代码路径简单、测试明确，适合当前阶段。如果未来确有租户自定义
需求，可以新增 role/permission/role_permission 表，让内置角色仍作为不可删除模板，并保持
业务服务只判断 Permission。不能让动态化绕过 OWNER、资源归属和防自提权不变量。

## 17. 当前没有实现什么

本阶段没有动态角色表、权限管理后台、成员管理 HTTP API、平台 Break-glass、审计表落地、
GitHub OAuth/账号绑定、GitHub 成员同步、权限缓存、Task 完整业务或 Agent。尤其没有把未来
审计和 Redis 权限缓存描述成已完成；当前权限每次基于数据库状态计算，以保证成员移除立即
生效。

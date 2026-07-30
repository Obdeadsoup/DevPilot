# DevPilot 数据库设计

## 原则

MySQL 8，表前缀 `dp_`。作用域数据携带 `workspace_id`，Project 子资源携带
`workspace_id + project_id`；数据库唯一约束负责并发下的最终幂等，`version` 条件更新防止
静默覆盖。所有结构变更只追加 Flyway migration，不修改已经执行的版本。

## 当前已落地表

### dp_user

V3 创建：

`id, username, email, display_name, password_hash, status, created_at, updated_at, version, deleted`

`username`、`email` 分别唯一并要求小写；状态为 `ACTIVE / LOCKED / DISABLED`。

### dp_workspace

V1 创建基础字段，V4 追加所有权与乐观锁：

`id, name, slug, description, owner_user_id, status, created_at, updated_at, version, deleted`

`slug` 唯一，`owner_user_id` 外键指向 `dp_user.id`，它是 OWNER 的唯一权威来源。
V4 为无法自动推断 owner 的历史行保留 NULL 兼容；应用和全部新测试数据必须显式提供 owner，
授权服务不会为 NULL 推导 OWNER。迁移不创建固定生产用户。

### dp_workspace_member

V4 创建：

`id, workspace_id, user_id, role, status, invited_by, joined_at, created_at, updated_at, version`

角色只允许 `ADMIN / MEMBER / VIEWER`，OWNER 不存入此表；状态只允许
`INVITED / ACTIVE / SUSPENDED / REMOVED`。唯一 `(workspace_id, user_id)`，并对
Workspace、成员用户和邀请人设置外键。Workspace/状态与用户/状态索引用于授权查询，
`version >= 0`。

### dp_project

V1 创建基础字段，V5 增加创建人并修复活跃 Key 唯一性：

`id, workspace_id, name, project_key, description, status, visibility, created_by, created_at, updated_at, version, deleted, active_project_key`

状态 `PLANNING / ACTIVE / ARCHIVED`，可见性 `PRIVATE / INTERNAL`。`created_by` 外键指向
`dp_user.id`；V5 会用 Workspace Owner 安全回填可推断的历史行，无法推断的旧行允许暂时为
NULL，所有新建 Project 都由应用显式写入创建人。`version >= 0`。

V1 的唯一 `(workspace_id, project_key, deleted)` 有一个隐蔽缺陷：同一 Key 最多只能有一条
`deleted = 1` 历史记录，第二轮“创建—删除”会冲突。V5 删除该索引，新增生成列：

```sql
active_project_key = CASE WHEN deleted = 0 THEN project_key ELSE NULL END
UNIQUE (workspace_id, active_project_key)
```

MySQL 唯一索引允许多行 NULL，因此每个 Workspace 的活动 Project Key 仍唯一，而任意数量的
已删除历史不会阻止重新创建。`(id, workspace_id)` 唯一约束继续作为 Project 子资源复合外键
的目标。

Workspace `slug` 的全局唯一索引没有改为逻辑删除感知：slug 是稳定租户标识，删除后永久
保留，防止旧链接、审计引用或外部配置意外指向另一个 Workspace。这是有意策略。

### dp_project_member

V4 创建：

`id, workspace_id, project_id, user_id, role, status, created_by, created_at, updated_at, version`

角色 `PROJECT_ADMIN / DEVELOPER / VIEWER`，状态 `ACTIVE / REMOVED`。唯一
`(project_id, user_id)`；`(project_id, workspace_id)` 复合外键保证 Project 确实属于
Workspace，并对成员用户、创建人设置外键。查询和更新始终携带
`workspace_id + project_id + user_id`，`version >= 0`。

### GitHub 与 Activity

- `dp_github_repository`：本地 Workspace/Project 绑定与 `credential_ref`，不保存明文
  Secret。
- `dp_github_delivery`：原始 Payload、`payload_sha256`、Delivery 状态机、有限重试字段和
  `version`；`github_delivery_id` 全局唯一。
- `dp_project_activity`：项目时间线；`(source_type, source_delivery_id)` 唯一，
  时间线索引包含 `workspace_id + project_id`。`external_actor_id/actor_login` 只是外部
  GitHub 元数据，不是本地用户外键。

## 尚未创建的规划表

`dp_sync_job`、Task、Notification、Audit、Outbox 和 Agent 表仍是后续规划，本阶段没有创建。

## 当前 Flyway 顺序

```text
V1 create github webhook vertical slice
V2 add github delivery processing scan index
V3 create identity user
V4 add scoped rbac
V5 add project lifecycle constraints
```

V4 不修改 V1–V3，包含 `dp_workspace.owner_user_id/version`、
`dp_workspace_member` 和 `dp_project_member`。V5 不修改旧迁移，增加
`dp_project.created_by`、Project version 检查以及活动 Project Key 生成列唯一索引。

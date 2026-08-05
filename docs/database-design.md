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

- `dp_github_repository`：本地 Workspace/Project Binding。V6 将旧 `credential_ref` 原地重命名为
  `webhook_secret_ref`，并新增 `api_credential_ref`、`last_verified_at`、`created_by`。两列 Credential
  只保存环境变量引用，不保存明文 Token/Secret；`last_verified_at` 表示最近一次成功从 GitHub 核实身份和
  元数据，`last_synced_at` 保留给未来 Issue/PR 等业务同步，不混用。
- `dp_github_delivery`：原始 Payload、`payload_sha256`、Delivery 状态机、有限重试字段和
  `version`；`github_delivery_id` 全局唯一。
- `dp_project_activity`：项目时间线；`(source_type, source_delivery_id)` 唯一，
  时间线索引包含 `workspace_id + project_id`。`external_actor_id/actor_login` 只是外部
  GitHub 元数据，不是本地用户外键。
- `dp_github_commit`：Webhook/API 共享的 Commit 事实表；`(github_repository_id, commit_sha)` 唯一，
  `(repository_binding_id, workspace_id, project_id)` 复合外键保证 Scope。`author_email` 仅内部保存，当前
  普通 API 不返回。`first_seen_source` 只记录首次来源。
- `dp_github_sync_checkpoint`：每个 Binding/资源一行可靠读取边界；唯一
  `(repository_binding_id, resource_type)`，`last_seen_commit_sha` 是页级安全进度，
  `last_successful_sync_at` 只在整轮成功后推进。
- `dp_github_sync_run`：`PENDING/RUNNING/RETRY_WAIT/SUCCEEDED/DEAD` 状态机。开放状态生成列唯一键限制
  同一 Binding/资源最多一个开放 Run；状态/重试/超时和 Binding 索引支持扫描，version 支持 claim。
- `dp_github_issue`：Issue 当前快照；Repository + stable Issue ID、Repository + number 双唯一键。
- `dp_github_pull_request`：PR 当前快照；Repository + stable PR ID、Repository + number 双唯一键，draft 独立于 status，
  `reviews_synced_at` 是 PR 级 Review 对账水位。
- `dp_github_pull_request_review`：Review 当前快照；Repository + stable Review ID 唯一，本地 PR 复合外键同时约束 Binding/Workspace/Project/Repository Scope。

三张快照表的 Body 使用有界 `TEXT` 并以 `CHAR_LENGTH <= 10000` CHECK 限制；数组使用原生 JSON 且限制序列化后
最多 4000 字符。应用层还会先安全截断并生成稳定 JSON。`content_hash` 不对外返回；它与
`github_updated_at` 判断外部快照幂等，`version` 仅处理本地并发条件 UPDATE。

### dp_notification

V11 创建可靠站内通知表。`UNIQUE(recipient_user_id, dedupe_key)` 是重复扫描和多实例并发的最终
幂等防线；`status/read_at` CHECK 保证 UNREAD/READ 一致，Project 复合外键保证 Scope。
索引覆盖接收人未读分页、Workspace/Project 时间线及来源排查。历史通知不级联删除，不保存
GitHub Body、Payload、Token 或 Secret。

## 尚未创建的规划表

Audit、Outbox 和 Agent 表仍是后续规划。V10 已创建 `dp_task`、不可变
`dp_task_status_history` 和软移除的 `dp_task_github_link`；ACTIVE Link 的生成列唯一键保证同一 GitHub
stable object 同时最多关联一个 Task。

## 当前 Flyway 顺序

```text
V1 create github webhook vertical slice
V2 add github delivery processing scan index
V3 create identity user
V4 add scoped rbac
V5 add project lifecycle constraints
V6 add github repository binding lifecycle
V7 add github repository metadata validators
V8 add github commit reconciliation
V9 add github issue pr review sync
V10 add task workflow and github links
V11 add reliable notifications
```

V4 不修改 V1–V3，包含 `dp_workspace.owner_user_id/version`、
`dp_workspace_member` 和 `dp_project_member`。V5 不修改旧迁移，增加
`dp_project.created_by`、Project version 检查以及活动 Project Key 生成列唯一索引。

V6 不修改 V1–V5。它保留 `(id, workspace_id, project_id)` 和 Delivery 复合外键，删除不感知软删除的
Repository ID 唯一索引与存在第二轮解绑冲突的 `(workspace_id, full_name, deleted)`，改用：

```sql
active_github_repository_id =
    CASE WHEN deleted = 0 THEN github_repository_id ELSE NULL END
active_repository_full_name =
    CASE WHEN deleted = 0 THEN full_name ELSE NULL END

UNIQUE(active_github_repository_id)
UNIQUE(workspace_id, active_repository_full_name)
```

已解绑历史的两个生成列均为 NULL，因此同一仓库可经历任意多轮绑定/解绑；活动 Binding 仍全局按稳定
GitHub Repository ID 唯一。V6 还增加 Repository `version >= 0` CHECK，原有
`ACTIVE / DISABLED` CHECK 保持不变。

V7 不修改 V1–V6，仅为 `dp_github_repository` 增加内部字段 `metadata_etag VARCHAR(255)` 和
`metadata_last_modified DATETIME(6)`。它们支持 Repository Metadata Conditional GET，不进入普通前端
Response。304 路径保留原校验器与权威元数据，只更新 `last_verified_at` 并按当前并发策略 `version + 1`。

V8 不修改 V1–V7，新增 `dp_github_commit`、`dp_github_sync_checkpoint` 和 `dp_github_sync_run`。Commit 的
SHA CHECK 只允许 40 位小写十六进制；Checkpoint/Run 仅允许 `COMMIT`；所有 version 非负。项目时间线
CHECK 增加 `GITHUB_COMMIT_DISCOVERED`。主要索引覆盖 Project/Repository 时间线、Run 的 PENDING/到期
RETRY_WAIT 扫描、超时 RUNNING 扫描和 Binding 历史查询。

V9 不修改 V1–V8，新增三张快照表，把 Checkpoint/Run 的 resource_type 扩展到
`ISSUE / PULL_REQUEST / PULL_REQUEST_REVIEW`，并扩展 Activity 类型 CHECK。所有快照子资源都有 Scope 外键、
stable ID 唯一键、更新时间查询索引、非负 version 与内容 Hash CHECK。

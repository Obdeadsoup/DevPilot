# 第 6 节：Workspace 与 Project 生命周期、范围查询和乐观锁

## 1. Workspace 是租户边界，也是安全边界

DevPilot 中的 Workspace 不只是一个展示用分组。成员身份、项目可见范围和绝大多数业务资源都
先归属于 Workspace，因此 `workspace_id` 同时承担租户隔离和授权范围的作用。

本节把 Workspace、Workspace Member、Project、Project Member、两级 Role/Permission 和
授权服务统一放在 `devpilot-project`。`devpilot-identity` 只负责用户、登录认证、安全
Principal、Access Token 和 Security Filter。依赖方向保持：

```text
identity -> framework
project  -> framework + identity
github   -> framework + project
boot     -> framework + identity + project + github
```

Identity 不依赖 Project。GitHub 也不直接访问 Workspace/Project Mapper，而是调用 Project
模块提供的应用服务。

## 2. Workspace 与 Project 的归属关系

每个 Project 必须有一个有效的 `workspace_id`，数据库外键保证它不能指向不存在的
Workspace。Project 的子资源同时保存 `workspace_id + project_id`，并通过复合外键引用
`dp_project(id, workspace_id)`，从数据库层阻止“Project ID 属于 A Workspace，但子资源伪称
属于 B Workspace”。

应用查询同样不会只用裸 `projectId`：

```sql
SELECT ...
FROM dp_project
WHERE id = #{projectId}
  AND workspace_id = #{workspaceId}
  AND deleted = 0
```

授权判断和 SQL Scope 缺一不可：前者判断用户能否操作，后者保证实际读写的资源确实属于 URL
声明的 Workspace。

## 3. Workspace 生命周期

Workspace 只有两个业务状态：

```text
ACTIVE ──disableWorkspace──> DISABLED
ACTIVE <─reactivateWorkspace─ DISABLED
```

- 创建时是 ACTIVE，当前用户同时是 Creator 和 Owner；数据库只保存权威的
  `owner_user_id`，不重复保存 Workspace `created_by`。
- disable 只能由 Owner 执行，并要求当前状态为 ACTIVE。
- reactivate 只能由 Owner 执行，并要求当前状态为 DISABLED。
- DISABLED Workspace 不再向普通成员提供 Project 权限；Owner 仍可查看 Workspace 本身并执行
  reactivate。

普通资料更新只能修改 name 和 description，不能借此改变 Owner、status 或 deleted。

## 4. Project 生命周期

Project 使用三个业务状态：

```text
PLANNING ──activateProject──> ACTIVE
PLANNING ──archiveProject───> ARCHIVED
ACTIVE   ──archiveProject───> ARCHIVED
ACTIVE   <─restoreProject──── ARCHIVED
```

Project 创建时固定为 PLANNING。普通资料更新允许修改 name、description、visibility，但
ARCHIVED Project 拒绝普通写操作。恢复是明确的 `restoreProject`，不会把任意字符串状态交给
Controller。

`ProjectAuthorizationService` 对 ARCHIVED Project 保留读权限，并只额外保留
`PROJECT_ARCHIVE` 作为受控恢复权限；成员管理和普通更新等写权限会被移除。新的 GitHub
Activity 明确拒绝写入 ARCHIVED Project，已有 Activity 仍可由有读权限的用户查看。

## 5. 生命周期状态不等于逻辑删除

ACTIVE、DISABLED、PLANNING、ARCHIVED 描述资源仍存在时的业务阶段；`deleted` 描述记录是否
进入逻辑删除历史。归档 Project 仍然存在、可读取、可恢复，因此不能把 archive 实现为
`deleted = 1`。

本节没有提供 Workspace/Project 删除接口，也没有物理删除接口。V5 只修复了未来逻辑删除场景
所需的唯一索引语义，集成测试通过数据库夹具验证多轮软删除历史。

## 6. 为什么不提供万能 updateStatus

如果接口是 `updateStatus(resourceId, newStatus)`，调用方就能绕过每条边的权限、前置状态和副作用
规则。显式动作把规则固定在服务端：

- activate 只接受 PLANNING；
- archive 只接受 PLANNING 或 ACTIVE；
- restore 只接受 ARCHIVED；
- disable/reactivate 只允许 Workspace Owner。

Mapper 也为每个动作提供独立的条件 UPDATE。即使应用层判断后发生并发变化，数据库条件仍会
拒绝非法覆盖。

## 7. ProjectVisibility 与 ProjectRole 是两条不同维度

`ProjectVisibility` 决定“谁有资格发现和读取 Project”：

- INTERNAL：任何 ACTIVE Workspace Member 都能获得只读访问；
- PRIVATE：普通 Workspace Member 还必须拥有 ACTIVE Project Membership。

`ProjectRole` 决定已经拥有 Project Membership 的人可以做什么：

- PROJECT_ADMIN；
- DEVELOPER；
- VIEWER。

Visibility 不会授予管理角色。INTERNAL 只为普通 Workspace Member 提供 Viewer 等效的只读
权限；要修改项目仍需 Project Membership 对应的 Role。Workspace Owner/Admin 则通过继承
获得 PROJECT_ADMIN 等效权限。

## 8. project_key 为什么创建后不随意修改

Project Key 常用于任务编号、链接、导出内容和外部引用。随意改 Key 会让 `DEV-42` 一类稳定
引用失效。

本节创建时先去除首尾空白并转为大写，再验证：

- 长度 2～12；
- 大写英文字母开头；
- 后续只允许大写字母或数字。

例如 ` dev1 ` 会保存为 `DEV1`，`1DEV`、`DEV-1`、单字符和超过 12 位都会被
`INVALID_PROJECT_KEY` 拒绝。Update DTO 和 Mapper 都没有 projectKey 字段。

## 9. workspaceId + projectId 范围查询

单资源读写、成员查询和 Activity 查询都同时携带两级 ID。错误的组合不会退化成“先按
projectId 找到，再相信它属于 URL 中的 Workspace”。这既防止越权，也让 SQL、日志和测试都
明确表达租户范围。

`ProjectActivityService.queryTimeline` 的执行顺序是：

```text
PROJECT_ACTIVITY_READ 方法授权
-> 验证 ACTIVE Workspace + 非删除/非 ARCHIVED Project Scope
-> 使用 workspace_id + project_id 查询 Activity
```

因此权限失败发生在时间线数据库查询之前，Mapper 的双 Scope 条件仍作为数据边界。

## 10. 复合外键保护资源归属

V1 在 `dp_project` 上提供 `UNIQUE(id, workspace_id)`。Repository、Delivery、Activity 和
Project Member 等子资源可引用 `(project_id, workspace_id)`，数据库据此验证两个值是同一个
Project 的真实组合。

单列 Project 外键只能证明 Project 存在；复合外键还能证明它属于声明的 Workspace。应用层
Scope 查询和复合外键共同构成纵深防御。

## 11. version 乐观锁

客户端读取 DTO 时得到 version，提交修改时发送 `expectedVersion`。典型 archive SQL 是：

```sql
UPDATE dp_project
SET status = 'ARCHIVED',
    version = version + 1
WHERE id = #{projectId}
  AND workspace_id = #{workspaceId}
  AND status IN ('PLANNING', 'ACTIVE')
  AND deleted = 0
  AND version = #{expectedVersion}
```

两个请求同时携带 version 0 时，最多一个能把记录更新为 version 1；另一个更新 0 行并返回
`PROJECT_VERSION_CONFLICT`。这里不需要悲观锁或 Redis 分布式锁。

本节所有 Workspace/Project 资料更新和状态动作都会增加 version。更新 0 行不会被当作成功，
错误码区分资源不存在、禁用/归档、非法状态转换和 version 冲突。

## 12. 旧 Project 唯一索引的缺陷

V1 使用：

```text
UNIQUE(workspace_id, project_key, deleted)
```

它看似兼容逻辑删除，实际只允许同一 Workspace/Key 有一条 `deleted = 1`。第一次删除后可以
重建，但第二个同 Key Project 再删除时，会与第一条删除历史冲突。

“把 deleted 放进唯一索引”只适合不需要多轮历史的简单模型，不满足 DevPilot 的 Key 重用
要求。

## 13. active_project_key 生成列

V5 删除旧索引并增加：

```sql
active_project_key =
    CASE WHEN deleted = 0 THEN project_key ELSE NULL END

UNIQUE(workspace_id, active_project_key)
```

活动记录的生成列等于真实 Key，因此同 Workspace 内仍只能有一个活动 Key。删除记录的生成列
为 NULL；MySQL 唯一索引允许多行 NULL，所以同一个 Key 可以经历任意多轮创建和删除。

生成列由数据库根据 deleted 计算，应用不能忘记同步维护第二份值。

## 14. Workspace slug 为什么永久保留

Workspace slug 与 Project Key 的策略不同。slug 是全局租户标识，可能进入旧链接、Webhook
配置、审计记录和人工文档。删除后复用会让旧引用指向另一个安全边界，因此
`uk_workspace_slug` 继续保持全局唯一且不感知 deleted。

这意味着已使用 slug 永久保留，是主动的安全取舍，不是遗漏。

## 15. 列表查询如何实施数据范围

Workspace 列表的 WHERE 条件只返回：

- `owner_user_id = currentUserId`；或
- 存在当前用户的 ACTIVE Workspace Membership。

使用 EXISTS 避免 JOIN 导致重复，并在数据库中分页。

Project 列表先要求 Workspace ACTIVE 且用户是 Owner 或 ACTIVE Member，再实施第二层条件：

- Workspace Owner/Admin：全部未删除 Project；
- 普通 ACTIVE Workspace Member：INTERNAL Project；
- 普通成员的 PRIVATE Project：还要存在 ACTIVE Project Membership。

status 和 visibility 从 Java 枚举传入，只作为绑定参数，不拼接用户提供的 SQL。count 和 page
查询使用相同的数据范围条件，避免 total 与 items 不一致。

## 16. Project Activity 如何接入 RBAC

浏览器查询 Activity 使用 Spring Method Security：

```java
@PreAuthorize(
    "@projectAuthorization.hasPermission(" +
    "authentication, #workspaceId, #projectId, 'PROJECT_ACTIVITY_READ')"
)
```

GitHub Webhook Worker 没有浏览器用户 Principal，`recordGitHubActivity` 因而是受信任的内部
应用调用，不依赖当前 Authentication。但它不会绕过资源边界：仍验证 Workspace ACTIVE、
Project 确实属于 Workspace、未删除且未归档，然后才幂等插入 Activity。

外部 GitHub sender 只是元数据，不能据此构造本地 Authentication 或提升权限。

## 17. 当前尚未实现的内容

本节完成时已经落地 Workspace/Project 生命周期、范围列表、乐观锁、两级 RBAC 和 V5 索引修复；
随后第 7 节又实现了 GitHub Repository 绑定生命周期。当前仍未实现：

- Issue/PR 同步；
- 完整 GitHub App JWT / Installation Token、API 分页、条件请求和复杂 Rate Limit 重试；
- Task、Notification 和 Agent 业务；
- 审计模块和审计表；
- Outbox 或消息队列事件投递；
- 动态角色、权限管理后台和 Redis 权限缓存；
- Workspace/Project 逻辑删除 API。

现有 GitHub Webhook 接收、数据库恢复扫描、有限重试和 DEAD 状态保持原状。Repository Binding
管理接口现在已经实现，但它使用的是最小 Metadata Client，不能据此声称 Issue/PR 同步或完整
GitHub App 已经完成。

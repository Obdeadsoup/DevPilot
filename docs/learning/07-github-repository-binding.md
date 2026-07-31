# 第 7 节：GitHub Repository 绑定生命周期与可信元数据校验

## 1. Repository 与 Binding 不是同一个概念

GitHub Repository 是 GitHub 托管的外部资源；DevPilot Repository Binding 是一条本地关系，表示
“哪个 Workspace 下的哪个 Project 接入了这个 GitHub Repository”，并保存接收 Webhook 和查询
GitHub API 所需的凭据引用。解绑只结束这条本地关系，不会删除 GitHub 仓库。

## 2. `github_repository_id` 与 `full_name`

`github_repository_id` 是 GitHub 分配的稳定数字身份。仓库改名或转移 Owner 后，`full_name` 会从
`old-owner/old-name` 变化，但数字 ID 保持不变。因此唯一性、刷新校验和 Webhook 定位都以数字 ID 为
身份；`full_name` 是可更新的展示和查询元数据，不能作为唯一身份来源。

## 3. 为什么不能信任客户端仓库元数据

绑定请求只接受 `owner`、`repositoryName`、`apiCredentialRef` 和 `webhookSecretRef`。客户端不能提交
`githubRepositoryId/fullName/htmlUrl/defaultBranch/visibility`。若由客户端决定这些字段，攻击者可以把
一个可访问仓库的名称和另一个仓库的 ID 拼在一起，污染 Webhook 路由和数据归属。

`GitHubRepositoryBindingService` 先解析 API Credential，再由
`GitHubRepositoryMetadataClient` 请求 GitHub；写入数据库的身份和元数据全部来自这次受认证响应。

## 4. 第一版一个 Repository 只能绑定一个 Project

当前产品不支持同一 GitHub Repository 同时绑定多个 Project，也不会自动执行跨 Project 转移。已有
活动 Binding 在相同 Project 中返回 `REPOSITORY_ALREADY_BOUND`，在其他 Project 中返回
`REPOSITORY_BOUND_TO_ANOTHER_PROJECT`。如需改变归属，必须先显式解绑，再在目标 Project 绑定。

## 5. ACTIVE、DISABLED 与 unbound

```text
新绑定 ──> ACTIVE ──disable──> DISABLED
             ↑                   │
             └────reactivate─────┘

ACTIVE 或 DISABLED ──unbind──> deleted = 1（历史 Binding）
```

- `ACTIVE`：管理 API 可读写，Webhook 可验签接收；
- `DISABLED`：Binding 和历史仍可读，刷新/恢复仍可执行，但 Webhook 返回 Repository Disabled；
- unbound：不是第三个 `binding_status`，而是 `deleted=1` 的历史记录；常规查询和 Webhook 都看不到它。

## 6. 为什么不用万能 `updateStatus`

disable、reactivate 和 unbind 的前置条件、副作用和凭据检查不同。reactivate 必须重新解析两类凭据、访问
GitHub 并核对数字 ID；disable 不需要外部调用；unbind 要保留历史。独立动作方法和独立条件 UPDATE 能把
规则固定在服务端，避免客户端提交任意目标状态绕过状态机。

## 7. API Credential 与 Webhook Secret

两者用途完全不同：

- API Credential 用作 `Authorization: Bearer ...`，让 DevPilot 主动读取 GitHub REST API；
- Webhook Secret 是 GitHub 与 DevPilot 共享的 HMAC 密钥，用来验证 GitHub 主动发送的原始请求体。

API Token 不能代替 Webhook Secret 验签，Webhook Secret 也不能用于访问 REST API。

## 8. `credential_ref` 的职责拆分

V6 将旧 `credential_ref` 原地重命名为 `webhook_secret_ref`，原有数据和 Webhook 语义不丢失；同时新增
可空的 `api_credential_ref`。数据库只保存引用名称：

```text
DEVPILOT_GITHUB_API_TOKEN_[A-Z0-9_]+
DEVPILOT_GITHUB_WEBHOOK_SECRET_[A-Z0-9_]+
```

两个 Resolver 各自只接受自己的白名单。任意 Spring Property 名、另一类凭据引用、小写后缀或空值都不会
被解析。原始 Token/Secret 不进入 Controller 响应、数据库和日志。

## 9. Fine-grained PAT 与 GitHub App 的取舍

本阶段可使用权限最小化、仓库范围明确的 Fine-grained PAT 完成个人开发和学习验证，实现成本低，也足以
建立真实 REST 调用链。但 PAT 的生命周期和人员账号绑定不适合最终团队安装模型。

完整 GitHub App 还需要 App 私钥、JWT、Installation Token、安装范围和 Token 刷新，本节没有实现，不能把
环境变量 PAT 描述成 GitHub App。后续迁移时，`GitHubApiCredentialResolver` 与 Metadata Client 接口提供了
替换边界。

## 10. 绑定完整调用链

```text
Authenticated Request
→ GitHubRepositoryController（Bean Validation）
→ GitHubRepositoryBindingService
→ CurrentUserProvider
→ ProjectAuthorizationService(REPOSITORY_BIND)
→ GitHubApiCredentialResolver
→ GitHubRepositoryMetadataClient
→ GET https://api.github.com/repos/{owner}/{repo}
→ WebhookSecretResolver
→ 应用层重复检查
→ dp_github_repository INSERT
→ 活动 Binding 唯一索引最终仲裁
→ 不含凭据引用的 GitHubRepositoryResponse
```

Project 授权计算同时验证 Workspace ACTIVE、`workspaceId + projectId` 归属和 Project 状态。ARCHIVED
Project 会过滤写权限，所以不能绑定、停用、恢复、刷新或解绑；PLANNING/ACTIVE 可按角色执行。

## 11. RBAC 权限映射

| 动作 | Permission | PROJECT_ADMIN | DEVELOPER | VIEWER |
|---|---|---:|---:|---:|
| 列表、详情 | REPOSITORY_READ | ✓ | ✓ | ✓ |
| 绑定 | REPOSITORY_BIND | ✓ |  |  |
| 停用、恢复、刷新 | REPOSITORY_UPDATE | ✓ | ✓ |  |
| 解绑 | REPOSITORY_UNBIND | ✓ |  |  |

Workspace OWNER/ADMIN 继承 PROJECT_ADMIN 等效权限。PRIVATE Project 的普通 Workspace Member 没有
Project Membership 时不能读取 Binding；INTERNAL 只提供 Viewer 等效读取。Controller 不接受
`currentUserId`，当前用户只能来自 SecurityContext。

## 12. `version` 条件更新

客户端读取 Binding 时得到 `version`，状态动作再提交 `expectedVersion`。例如 disable 同时检查：

```sql
WHERE id = ?
  AND workspace_id = ?
  AND project_id = ?
  AND deleted = 0
  AND binding_status = 'ACTIVE'
  AND version = ?
```

成功时原子执行 `version = version + 1`。并发请求携带相同版本时最多一个更新一行；更新数为 0 不会当作
成功，而是返回稳定的版本冲突。refresh 和 reactivate 还把元数据更新与版本递增放在同一条 UPDATE 中。

## 13. 唯一索引是并发最终防线

应用层先查已有 Binding，是为了返回“同项目已绑定”或“其他项目已绑定”的友好错误。但两个并发事务仍可
同时查不到，所以 INSERT 必须由数据库唯一索引最终仲裁。失败事务捕获 `DuplicateKeyException`，再用
锁定读取识别获胜的活动 Binding，转换成稳定 409，而不是返回 SQL 细节。

## 14. 活动 Binding 生成列

V1 的全局 `UNIQUE(github_repository_id)` 让软删除历史永远占用 ID；
`UNIQUE(workspace_id, full_name, deleted)` 又只允许一条相同的 `deleted=1` 历史。V6 删除这两个索引并新增：

```sql
active_github_repository_id =
    CASE WHEN deleted = 0 THEN github_repository_id ELSE NULL END

active_repository_full_name =
    CASE WHEN deleted = 0 THEN full_name ELSE NULL END

UNIQUE(active_github_repository_id)
UNIQUE(workspace_id, active_repository_full_name)
```

MySQL 唯一索引允许多行 NULL，因此活动记录仍唯一，任意数量的解绑历史不再阻止重绑。生成值由数据库计算，
应用不会漏同步。`(id, workspace_id, project_id)` 仍保留给 Delivery 复合外键使用。

## 15. 解绑后为什么保留历史事件

解绑只把 Binding 标记为 `deleted=1`。`dp_github_delivery` 和 `dp_project_activity` 不删除，旧 Delivery 的
Repository 复合外键仍指向原 Binding。这样项目时间线、故障定位和未来审计不会因解绑而失去事实。
环境变量中的 Token/Secret 也不会被应用删除；凭据轮换或删除属于外部运维动作。

## 16. SSRF 防护

真实 Client 的生产 Base URL 在代码中固定为 `https://api.github.com`。请求 DTO 没有完整 URL、API URL 或
HTML URL 字段，owner/repositoryName 先经过字符校验，Client 又使用独立 Path Segment 构造 URI。即使直接
向 Client 传入类似 URL 的字符串，它也只能成为 `api.github.com` 下的编码 Path，而不能改变 Scheme 或
Host。当前明确不支持 GitHub Enterprise Base URL。

Client 设置连接/读取超时，并只发送 GitHub Media Type、Bearer Token、API Version 和固定 User-Agent。
401/403/404/429/5xx、超时和缺少稳定 ID 的响应都转换为安全错误，不记录响应正文或 Authorization Header。

## 17. Webhook 如何消费 Binding

Webhook 链路没有改用 API Credential：

```text
原始 Payload
→ 提取 repository.id
→ GitHubRepositoryMapper.findByGitHubRepositoryId(deleted = 0)
→ 要求 binding_status = ACTIVE
→ 用 webhook_secret_ref 解析 HMAC Secret
→ 对完全相同的原始 byte[] 验签
→ Delivery 幂等落库与既有重试/恢复/Activity 链路
```

因此 disable 立即让 Webhook 返回 `GITHUB_0406`；unbind 后查不到活动 Binding，返回 `GITHUB_0405`；
reactivate 核对 GitHub ID 并成功更新后恢复接收。仓库 rename 只更新元数据，不改变 Webhook 身份。

## 18. 下一节仍需补齐什么

本节的 REST Client 只实现单仓库元数据读取和基础错误映射，没有实现复杂重试。下一节需要系统补充：

- 主限流和次限流的 Header 解析；
- `Retry-After` 与受限次数退避；
- 列表 API 分页；
- ETag / `If-None-Match` 条件请求；
- Token 失效后的受控处理；
- GitHub App JWT、Installation Token 和安装范围；
- API 对账与 Issue/PR 同步。

这些能力目前都不能描述成已完成。本节也没有实现跨 Project 转移、Audit、Outbox 或凭据管理后台。

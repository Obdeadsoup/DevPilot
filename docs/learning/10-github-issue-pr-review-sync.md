# 第 10 节：GitHub Issue、Pull Request 与 Review 同步

## 1. Snapshot 与 Event History

本节三张表保存“当前快照”，不是完整事件历史。Issue 从 opened 到 edited 再 closed，
`dp_github_issue` 仍只有一行；动作痕迹由受限的 `dp_project_activity` 表达。Delivery Inbox
保留原始外部事件和处理状态，但它不是供前端查询的业务快照。

## 2. GitHub PR 与 Issue 的关系

GitHub 的 Issues API 会同时列出 Issue 和 PR，PR 条目带 `pull_request` 字段。
`RestClientGitHubIssueClient` 必须先过滤该字段；PR 再由 Pull Requests API 获取。
否则同一 PR 会被错误写进 Issue 表，而且 Issues API 的条目 ID 也不能替代真正 PR ID。

## 3. Issue ID、PR ID 与 number

- `github_issue_id`、`github_pull_request_id`、`github_review_id` 是 GitHub stable ID，作为外部身份。
- `issue_number`、`pull_request_number` 只在一个 Repository 内唯一，用于 URL 和展示。
- Review 使用独立 `github_review_id`；reviewer、状态、PR number 都不是 Review 身份。

## 4. 为什么分三张表

Issue、PR、Review 的生命周期、字段、唯一键和查询方式不同。PR 有 head/base、draft、merge；
Review 有 reviewer、submittedAt、commitSha，并依赖本地 PR。分表让约束直接表达真实领域，避免
一张稀疏大表和模糊的 type 分支。

## 5. PR 状态与 draft

PR 状态计算顺序固定：`merged=true` 或 `mergedAt != null` 为 `MERGED`；否则 state=closed
为 `CLOSED`；其余为 `OPEN`。`draft` 单独保存，Draft PR 仍然是 OPEN。Webhook action
也单独解析，不能把 `opened`、`synchronize` 或 `ready_for_review` 写进 status。

## 6. Review 状态

当前支持 `COMMENTED / APPROVED / CHANGES_REQUESTED / DISMISSED`。`submitted`、`edited`、
`dismissed` 是 Webhook action；Review 表中的 state 始终表示当前快照。

## 7. 双入口统一 Upsert

```text
Webhook Parser ─┐
                ├→ Issue/PR/Review ApplicationService → Snapshot Mapper
GitHub API DTO ─┘
```

Webhook 和 API 必须汇合到同一 Upsert，否则唯一键、乱序、限长、Scope 和 Activity 规则会分叉。
首次插入由数据库唯一索引仲裁；并发 DuplicateKey 回滚后读取胜者，再执行相同更新判断。

## 8. github_updated_at、content_hash 与 version

`github_updated_at` 是外部时间顺序：incoming 更早就不覆盖；时间相同且 `content_hash`
相同就是幂等。时间更新或 Hash 变化才尝试更新。`content_hash` 是规范化快照的 SHA-256，
不是 Webhook HMAC 签名，也不对 API 返回。

`version` 是本地行并发顺序：SQL 使用 `id + expectedVersion + github_updated_at <= incoming`
条件更新。前者避免两个本地 Worker 静默互盖，后者避免旧 GitHub 事件覆盖新快照；两者不能互相替代。

## 9. Snapshot Diff 与 Activity

Mapper 不判断业务语义。`GitHubSnapshotDiffService` 在写前显式比较旧、新快照：Issue 识别创建、
编辑、关闭/重开、Assignee/Label；PR 识别创建、编辑、Draft/Ready、headSha、关闭/重开/合并、
Reviewer；Review 按当前 state 选择 Activity。

同一 Webhook Delivery 使用 Delivery ID 作为 Activity 唯一来源键，因此最多一条。API 对账用
对象 ID + contentHash 派生稳定来源键，重复对账不会重复。无语义元数据刷新不创建 Activity。
Activity 只含 number、限长 title、安全摘要和 URL，不含完整 Body。

## 10. API Backfill

第一次没有 Checkpoint 时来源为 `API_BACKFILL`，只建立当前快照，不创建“刚刚发生”的 Activity。
这是明确选择：本地首次看见不等于事件此刻发生。后续来源为 `API_RECONCILE`，只有真实语义差异
才创建 Activity，occurredAt 使用 GitHub updated/submitted 时间而不是本机当前时间。

## 11. 三类 API Client

三者都复用 `GitHubApiHttpExecutor` 的固定 Host、Timeout、GET 有限 Retry、Rate Limit、
Credential Semaphore、安全 Header/日志和同源 Link Cursor：

- Issue：`state=all + since + sort=updated + asc`，过滤 `pull_request`。
- PR：Pull Requests API，`updated desc`，读取真实 PR ID、draft、mergedAt、head/base。
- Review：按经过筛选的 PR number 分页，保存 stable Review ID、state、commitSha、submittedAt。

## 12. Run、Checkpoint 与事务边界

`resource_type` 已扩展为 `COMMIT / ISSUE / PULL_REQUEST / PULL_REQUEST_REVIEW`。网络与分页循环
不在长事务中；每条快照是独立短事务。整轮数据成功后，Checkpoint 与 Run SUCCEEDED 在同一短事务
推进；失败记录继续使用独立事务，复用 RATE_LIMITED/网络/5xx 的有限重试与鉴权、ID mismatch、
Malformed 的 DEAD 分类。

Issue 从 Checkpoint 减 overlap 读取。PR 按 updated desc 到达 overlap 边界即停。Review 仅选择
初始 lookback 内活跃、且 `reviews_synced_at` 落后于 PR `github_updated_at` 的有限批次；一个 PR
全部 Review 页完成后才推进 PR 级水位。它不是每轮扫描所有历史 PR 的无限 N+1。

## 13. 数据库约束

- Issue：唯一 `(github_repository_id, github_issue_id)` 与 `(github_repository_id, issue_number)`。
- PR：唯一 `(github_repository_id, github_pull_request_id)` 与 `(github_repository_id, pull_request_number)`。
- Review：唯一 `(github_repository_id, github_review_id)`，复合外键绑定本地 PR 和完整 Scope。
- 三表均由 `(repository_binding_id, workspace_id, project_id)` 或 PR 复合外键限制 Scope，version 非负。

数据库唯一索引比“先查后插”可靠：两个事务可同时查不到，只有唯一索引能在最终写点仲裁。

## 14. 有界外部文本与安全

Title 512、Body 10000、数组 JSON 4000、login 100、ref 255、URL 500。Body 使用有界 `TEXT + CHECK`，
不是无限大类型；应用也先截断。数组排序去重后再序列化，永不在 JSON 中间截断。HTML URL 只接受
`https://github.com`，日志不打印 Body/Payload。

这些处理不等于内容可信。响应标记 `externalUntrustedContent=true`，前端必须用禁止脚本和危险 HTML
的安全 Markdown Renderer，不能直接 `innerHTML`。未来 Agent 也只能把正文作为外部不可信材料，
不得把其中指令当系统命令。GitHub login 不恢复本地 Authentication，也不自动授予权限。

## 15. 只读 API 与 RBAC

五个 Project 范围 API 均要求 authenticated + `PROJECT_READ`。Application Service 先按当前用户
授权，Mapper SQL 仍携带 `workspaceId + projectId`。列表不返回 Body；详情才返回。响应不包含
author email、原始 Payload、credential reference 或内部 Hash。

## 16. 未支持 action 策略

三类 Parser 对未知 action 返回 empty plan。Delivery 正常 SUCCEEDED，不创建快照或 Activity；
`GitHubWebhookActionMetrics` 只以固定 event 类型为标签计数，不把任意 action 或 Payload 放入日志/指标，
避免高基数和敏感数据泄漏。

## 17. 可执行手动真实测试清单

准备：在测试 Repository 配好指向本地公开地址的 Webhook，只记录 Delivery ID、Event、状态和下列 SQL；
不要截图 PAT、Secret 或私有 Payload。

Issue 步骤：

| 操作 | 预期 Event | Delivery | 快照 / Activity | SQL |
|---|---|---|---|---|
| 创建 | `issues:opened` | SUCCEEDED | 新 Issue / CREATED | `SELECT state,title FROM dp_github_issue WHERE issue_number=?;` |
| 编辑 | `issues:edited` | SUCCEEDED | title/body 更新 / EDITED | `SELECT title,github_updated_at,version FROM dp_github_issue WHERE issue_number=?;` |
| 加/删 Label | `labeled/unlabeled` | SUCCEEDED | labels_json / LABELS_CHANGED | `SELECT labels_json FROM dp_github_issue WHERE issue_number=?;` |
| 分配/取消 | `assigned/unassigned` | SUCCEEDED | assignee JSON / ASSIGNEES_CHANGED | `SELECT assignee_summary_json FROM dp_github_issue WHERE issue_number=?;` |
| Close | `closed` | SUCCEEDED | CLOSED / CLOSED | `SELECT state,github_closed_at FROM dp_github_issue WHERE issue_number=?;` |
| Reopen | `reopened` | SUCCEEDED | OPEN / REOPENED | 同上 |

PR 步骤：

| 操作 | 预期 Event | Delivery | 快照 / Activity | SQL |
|---|---|---|---|---|
| 创建 Draft | `pull_request:opened` | SUCCEEDED | OPEN + draft=1 / CREATED | `SELECT status,draft FROM dp_github_pull_request WHERE pull_request_number=?;` |
| Ready | `ready_for_review` | SUCCEEDED | draft=0 / READY | 同上 |
| Push Commit | `synchronize` | SUCCEEDED | head_sha 更新 / SYNCHRONIZED | `SELECT head_sha FROM dp_github_pull_request WHERE pull_request_number=?;` |
| 编辑 | `edited` | SUCCEEDED | title/body / EDITED | `SELECT title,version FROM dp_github_pull_request WHERE pull_request_number=?;` |
| Close/Reopen | `closed/reopened` | SUCCEEDED | CLOSED/OPEN / 对应 Activity | `SELECT status FROM dp_github_pull_request WHERE pull_request_number=?;` |
| Merge | `closed` | SUCCEEDED | MERGED / MERGED | `SELECT status,github_merged_at FROM dp_github_pull_request WHERE pull_request_number=?;` |

Review 步骤：

| 操作 | 预期 Event | Delivery | 快照 / Activity | SQL |
|---|---|---|---|---|
| Comment | `submitted` | SUCCEEDED | COMMENTED | `SELECT github_review_id,state FROM dp_github_pull_request_review WHERE pull_request_id=?;` |
| Request Changes | `submitted` | SUCCEEDED | CHANGES_REQUESTED | 同上 |
| Approve | `submitted` | SUCCEEDED | APPROVED | 同上 |
| Dismiss | `dismissed` | SUCCEEDED | DISMISSED | 同上 |

每步另查：`SELECT processing_status,last_error_code FROM dp_github_delivery WHERE github_delivery_id=?;`
以及 `SELECT activity_type,occurred_at FROM dp_project_activity WHERE source_delivery_id=?;`。重复发送同一 Delivery
应保持单行；同 ID 不同 Payload Hash 应返回 409 且不覆盖原记录。

## 18. 当前边界与下一节

已完成的是读取方向的当前快照、补偿和查询。尚未实现 GitHub App Installation Token、写 Issue/PR、
人工 DEAD 管理、无限历史 Event Store、Outbox/MQ 或前端 Markdown Renderer。下一节应建立本地 Task 与
GitHub Issue/PR 的显式关联，继续使用 stable ID、Scope、乐观锁和人工确认，不能仅靠 number 或正文猜测关联。

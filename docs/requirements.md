# DevPilot 产品需求

## 角色

- 工作空间所有者：成员、角色、仓库和审计管理。
- 项目管理员：项目成员、任务、迭代、仓库配置和规则。
- 开发成员：任务处理、活动查看、只读 Agent 和草案提交。
- 只读成员：仅查看授权项目。
- Agent：继承发起人的工作空间和项目权限，不是超级用户。

## V1 功能

### 身份与权限

- 用户登录与当前用户查询
- 工作空间创建、成员邀请
- OWNER / ADMIN / MEMBER / VIEWER
- 项目成员权限
- 接口权限、数据范围和资源归属三层校验
- 越权访问统一处理
- 登录和关键变更审计

### 项目与仓库

- 创建项目
- 绑定、启停、解绑 GitHub 仓库
- 保存 GitHub repository id、owner、repo、默认分支
- 同一工作空间禁止重复绑定同一仓库
- V1 可用 PAT 做个人测试，真实团队阶段迁移 GitHub App

### Webhook

支持 `ping`、`push`、`issues`、`pull_request`、`pull_request_review`。

处理步骤：读取原始请求体、定位仓库、验签、读取 Delivery ID 和事件类型、幂等落库、快速返回、异步处理、更新状态、失败重试、超过上限进入 DEAD。

Webhook 请求线程不得调用大模型或执行全量同步。

### 活动时间线

统一记录提交代码、Issue 创建/关闭、PR 创建/合并/关闭、Review、任务状态变化、成员变化和 Agent 建议。活动包含工作空间、项目、仓库、行为人、类型、外部链接、摘要、来源和发生时间。

### 同步与补偿

- 保存同步游标
- 定时查询最近 Issue/PR
- 对比本地数据并补偿遗漏
- 处理分页、超时、主限流、次限流和指数退避
- 记录同步日志与限流信息

### 任务状态机（V1.1）

状态：`BACKLOG → TODO → IN_PROGRESS → REVIEW → DONE`，任意未完成状态可按规则进入 `CANCELLED`，`REVIEW` 可退回 `IN_PROGRESS`。

不提供任意 updateStatus，而提供 `startTask`、`submitForReview`、`requestChanges`、`completeTask`、`cancelTask` 等领域动作。使用乐观锁和状态历史。

### 时效规则

不生搬客服 SLA，而采用真实研发规则：

- 任务截止前提醒和逾期升级
- PR 等待 Review 超时提醒
- Webhook Delivery 处理 SLO
- 同步积压告警
- Agent 待确认操作过期

### 通知

任务分配、@提及、PR 待 Review、任务到期、同步失败、授权失效、Agent 草案待确认和执行结果。V1 先做站内通知。

### 审计

审计登录失败、成员和角色变更、仓库绑定、凭据更新、任务状态、人工重试、Agent 工具调用、人工确认和执行结果。普通用户不可修改或删除审计。

### Agent

L1 只读：项目概况、最近活动、成员任务、长期未更新任务、PR 摘要、项目文档、日报周报草案。

L2 提议：会议纪要转任务、需求拆分、负责人和优先级推荐、Issue 草案、Review 清单。

L3 受控写：人工确认后创建本地任务或 GitHub Issue、添加标签、分配负责人、更新任务状态。

Agent 禁止直接访问 Mapper。

## 非功能要求

- Webhook HMAC-SHA256 验签与恒定时间比较
- Delivery ID 唯一约束
- 状态机、幂等重试、乐观锁、Outbox 和定时对账
- GitHub API 超时、分页、限流和退避
- Trace ID、结构化日志和业务指标
- 私有仓库数据隔离、敏感日志脱敏
- Testcontainers 集成测试

## V1 不做

微服务、Kubernetes、复刻 GitHub、自建 Git、实时协同编辑、复杂工作流设计器、同时接入 GitLab/Gitee/Jira、无人工确认的高风险 Agent、虚构高并发。

## 验收场景

1. 相同 Delivery 两次投递，只生成一次业务活动。
2. 错误签名被拒绝且不处理 Payload。
3. 异步失败进入 FAILED/RETRY_WAIT，最终成功或 DEAD。
4. 跨工作空间访问私有项目返回 403。
5. 两人并发更新任务，仅一个成功。
6. Agent 查询先校验项目权限并记录工具调用。
7. Agent 创建 Issue 先生成草案、人工确认、再次校验权限、执行并审计。

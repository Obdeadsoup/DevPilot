# 第 11 节变更文件地图

| 区域 | 文件 | 职责 |
|---|---|---|
| Maven | `pom.xml`、`devpilot-task/pom.xml`、Boot/GitHub POM | 新模块和无环依赖 |
| Schema | `V10__add_task_workflow_and_github_links.sql` | Task/History/Link、Scope FK、Activity CHECK |
| 领域 | `TaskTransitionPolicy`、Task enum | 纯状态矩阵与 Link 枚举 |
| 写链路 | `TaskApplicationService`、`TaskWorkflowService`、`TaskPersistenceService` | 创建、资料、分配、动作与原子 History/Activity |
| 授权 | `TaskAuthorizationService` | Project Permission + Reporter/Assignee/Manager |
| 关联 | `TaskGitHubLinkService`、Task Port | stable Snapshot ID、ACTIVE 唯一键、from Issue |
| Adapter | `GitHubTaskReferenceAdapter` | scoped Issue/PR Snapshot 读取 |
| API | `TaskController`、`api/dto/*` | Task、动作、Link、from Issue、列表 |
| Activity | `RecordTaskProjectActivityCommand` | 明确本地 Task 来源 |
| 测试 | `TaskTransitionPolicyTest`、`DevPilotApplicationTests` | 状态矩阵、Boot Bean 装配 |

调用链：`TaskController → Application/Workflow/Link Service → TaskAuthorizationService → Mapper +
TaskPersistenceService → HistoryMapper + ProjectActivityService`；关联读取为
`TaskGitHubReferenceReader → GitHubTaskReferenceAdapter → GitHub Snapshot Mapper`。

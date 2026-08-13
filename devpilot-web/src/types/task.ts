export type TaskStatus = 'BACKLOG' | 'TODO' | 'IN_PROGRESS' | 'IN_REVIEW' | 'DONE' | 'CANCELED'

export type TaskPriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT'

export type TaskAction =
  | 'CREATED'
  | 'PLANNED'
  | 'RETURNED_TO_BACKLOG'
  | 'STARTED'
  | 'SUBMITTED_FOR_REVIEW'
  | 'CHANGES_REQUESTED'
  | 'COMPLETED'
  | 'CANCELED'
  | 'REOPENED'

export type TaskGitHubResourceType = 'ISSUE' | 'PULL_REQUEST'

export type TaskGitHubRelationType = 'TRACKS' | 'IMPLEMENTED_BY' | 'RELATED_TO'

export type TaskGitHubLinkStatus = 'ACTIVE' | 'REMOVED'

export interface TaskResponse {
  id: number
  workspaceId: number
  projectId: number
  displayKey: string
  title: string
  description: string | null
  status: TaskStatus
  priority: TaskPriority
  reporterUserId: number
  assigneeUserId: number | null
  dueAt: string | null
  completedAt: string | null
  canceledAt: string | null
  createdAt: string
  updatedAt: string
  version: number
}

export interface TaskStatusHistoryResponse {
  id: number
  taskId: number
  fromStatus: TaskStatus | null
  toStatus: TaskStatus
  action: TaskAction
  actorUserId: number
  reason: string | null
  taskVersion: number
  occurredAt: string
}

export interface TaskDetailResponse {
  task: TaskResponse
  history: TaskStatusHistoryResponse[]
}

export interface CreateTaskRequest {
  title: string
  description?: string
  priority?: TaskPriority
  assigneeUserId?: number
  dueAt?: string
}

export interface UpdateTaskRequest {
  title: string
  description?: string
  priority: TaskPriority
  dueAt?: string
  expectedVersion: number
}

export interface AssignTaskRequest {
  assigneeUserId: number
  expectedVersion: number
}

export interface TaskActionRequest {
  reason?: string
  expectedVersion: number
}

export interface CreateTaskFromIssueRequest {
  priority?: TaskPriority
  assigneeUserId?: number
  dueAt?: string
}

export interface CreateTaskGitHubLinkRequest {
  resourceType: TaskGitHubResourceType
  snapshotId: number
  relationType?: TaskGitHubRelationType
  expectedTaskVersion: number
}

export interface TaskGitHubLinkResponse {
  id: number
  taskId: number
  resourceType: TaskGitHubResourceType
  snapshotId: number
  externalNumber: number
  externalTitle: string
  relationType: TaskGitHubRelationType
  status: TaskGitHubLinkStatus
  createdAt: string
  updatedAt: string
  version: number
}

export interface RemoveTaskGitHubLinkRequest {
  expectedTaskVersion: number
  expectedLinkVersion: number
}

export interface TaskListQueryFilter {
  page?: number
  size?: number
  status?: TaskStatus
  priority?: TaskPriority
  assigneeUserId?: number
  reporterUserId?: number
  dueBefore?: string
}

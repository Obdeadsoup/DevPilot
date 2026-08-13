import { request } from '../client'
import type { ApiResult, PageResponse } from '@/types/api'
import type {
  TaskResponse,
  TaskDetailResponse,
  TaskGitHubLinkResponse,
  CreateTaskRequest,
  UpdateTaskRequest,
  AssignTaskRequest,
  TaskActionRequest,
  CreateTaskFromIssueRequest,
  CreateTaskGitHubLinkRequest,
  RemoveTaskGitHubLinkRequest,
  TaskListQueryFilter,
} from '@/types/task'

export function createTaskApi(
  workspaceId: number,
  projectId: number,
  data: CreateTaskRequest
): Promise<ApiResult<TaskResponse>> {
  return request<TaskResponse>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}/tasks`,
    method: 'POST',
    data,
  })
}

export function listTasksApi(
  workspaceId: number,
  projectId: number,
  filter?: TaskListQueryFilter
): Promise<ApiResult<PageResponse<TaskResponse>>> {
  return request<PageResponse<TaskResponse>>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}/tasks`,
    method: 'GET',
    params: filter,
  })
}

export function createTaskFromIssueApi(
  workspaceId: number,
  projectId: number,
  issueSnapshotId: number,
  data: CreateTaskFromIssueRequest
): Promise<ApiResult<TaskResponse>> {
  return request<TaskResponse>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}/tasks/from-github-issue/${issueSnapshotId}`,
    method: 'POST',
    data,
  })
}

export function getTaskApi(
  workspaceId: number,
  projectId: number,
  taskId: number
): Promise<ApiResult<TaskDetailResponse>> {
  return request<TaskDetailResponse>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}/tasks/${taskId}`,
    method: 'GET',
  })
}

export function updateTaskApi(
  workspaceId: number,
  projectId: number,
  taskId: number,
  data: UpdateTaskRequest
): Promise<ApiResult<TaskResponse>> {
  return request<TaskResponse>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}/tasks/${taskId}`,
    method: 'PUT',
    data,
  })
}

export function assignTaskApi(
  workspaceId: number,
  projectId: number,
  taskId: number,
  data: AssignTaskRequest
): Promise<ApiResult<TaskResponse>> {
  return request<TaskResponse>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}/tasks/${taskId}/assign`,
    method: 'POST',
    data,
  })
}

export function unassignTaskApi(
  workspaceId: number,
  projectId: number,
  taskId: number,
  data: TaskActionRequest
): Promise<ApiResult<TaskResponse>> {
  return request<TaskResponse>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}/tasks/${taskId}/unassign`,
    method: 'POST',
    data,
  })
}

export function planTaskApi(
  workspaceId: number,
  projectId: number,
  taskId: number,
  data: TaskActionRequest
): Promise<ApiResult<TaskResponse>> {
  return request<TaskResponse>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}/tasks/${taskId}/plan`,
    method: 'POST',
    data,
  })
}

export function returnTaskToBacklogApi(
  workspaceId: number,
  projectId: number,
  taskId: number,
  data: TaskActionRequest
): Promise<ApiResult<TaskResponse>> {
  return request<TaskResponse>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}/tasks/${taskId}/return-to-backlog`,
    method: 'POST',
    data,
  })
}

export function startTaskApi(
  workspaceId: number,
  projectId: number,
  taskId: number,
  data: TaskActionRequest
): Promise<ApiResult<TaskResponse>> {
  return request<TaskResponse>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}/tasks/${taskId}/start`,
    method: 'POST',
    data,
  })
}

export function submitTaskForReviewApi(
  workspaceId: number,
  projectId: number,
  taskId: number,
  data: TaskActionRequest
): Promise<ApiResult<TaskResponse>> {
  return request<TaskResponse>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}/tasks/${taskId}/submit-for-review`,
    method: 'POST',
    data,
  })
}

export function requestTaskChangesApi(
  workspaceId: number,
  projectId: number,
  taskId: number,
  data: TaskActionRequest
): Promise<ApiResult<TaskResponse>> {
  return request<TaskResponse>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}/tasks/${taskId}/request-changes`,
    method: 'POST',
    data,
  })
}

export function completeTaskApi(
  workspaceId: number,
  projectId: number,
  taskId: number,
  data: TaskActionRequest
): Promise<ApiResult<TaskResponse>> {
  return request<TaskResponse>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}/tasks/${taskId}/complete`,
    method: 'POST',
    data,
  })
}

export function cancelTaskApi(
  workspaceId: number,
  projectId: number,
  taskId: number,
  data: TaskActionRequest
): Promise<ApiResult<TaskResponse>> {
  return request<TaskResponse>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}/tasks/${taskId}/cancel`,
    method: 'POST',
    data,
  })
}

export function reopenTaskApi(
  workspaceId: number,
  projectId: number,
  taskId: number,
  data: TaskActionRequest
): Promise<ApiResult<TaskResponse>> {
  return request<TaskResponse>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}/tasks/${taskId}/reopen`,
    method: 'POST',
    data,
  })
}

export function createTaskGitHubLinkApi(
  workspaceId: number,
  projectId: number,
  taskId: number,
  data: CreateTaskGitHubLinkRequest
): Promise<ApiResult<TaskResponse>> {
  return request<TaskResponse>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}/tasks/${taskId}/github-links`,
    method: 'POST',
    data,
  })
}

export function listTaskGitHubLinksApi(
  workspaceId: number,
  projectId: number,
  taskId: number
): Promise<ApiResult<TaskGitHubLinkResponse[]>> {
  return request<TaskGitHubLinkResponse[]>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}/tasks/${taskId}/github-links`,
    method: 'GET',
  })
}

export function removeTaskGitHubLinkApi(
  workspaceId: number,
  projectId: number,
  taskId: number,
  linkId: number,
  data: RemoveTaskGitHubLinkRequest
): Promise<ApiResult<TaskResponse>> {
  return request<TaskResponse>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}/tasks/${taskId}/github-links/${linkId}/remove`,
    method: 'POST',
    data,
  })
}

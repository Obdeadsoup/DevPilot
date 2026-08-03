import { request } from '../client'
import type {
  Project,
  CreateProjectRequest,
  UpdateProjectRequest,
  PageResponse,
  ApiResult,
} from '@/types/api'

export function listProjectsApi(
  workspaceId: number,
  params?: { page?: number; size?: number; status?: string; visibility?: string }
): Promise<ApiResult<PageResponse<Project>>> {
  return request<PageResponse<Project>>({
    url: `/api/v1/workspaces/${workspaceId}/projects`,
    method: 'GET',
    params,
  })
}

export function createProjectApi(
  workspaceId: number,
  data: CreateProjectRequest
): Promise<ApiResult<Project>> {
  return request<Project>({
    url: `/api/v1/workspaces/${workspaceId}/projects`,
    method: 'POST',
    data,
  })
}

export function getProjectApi(workspaceId: number, projectId: number): Promise<ApiResult<Project>> {
  return request<Project>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}`,
    method: 'GET',
  })
}

export function updateProjectApi(
  workspaceId: number,
  projectId: number,
  data: UpdateProjectRequest
): Promise<ApiResult<Project>> {
  return request<Project>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}`,
    method: 'PUT',
    data,
  })
}

export function activateProjectApi(
  workspaceId: number,
  projectId: number,
  expectedVersion: number
): Promise<ApiResult<Project>> {
  return request<Project>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}/activate`,
    method: 'POST',
    data: { expectedVersion },
  })
}

export function archiveProjectApi(
  workspaceId: number,
  projectId: number,
  expectedVersion: number
): Promise<ApiResult<Project>> {
  return request<Project>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}/archive`,
    method: 'POST',
    data: { expectedVersion },
  })
}

export function restoreProjectApi(
  workspaceId: number,
  projectId: number,
  expectedVersion: number
): Promise<ApiResult<Project>> {
  return request<Project>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}/restore`,
    method: 'POST',
    data: { expectedVersion },
  })
}

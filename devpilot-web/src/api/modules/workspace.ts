import { request } from '../client'
import type {
  Workspace,
  CreateWorkspaceRequest,
  UpdateWorkspaceRequest,
  PageResponse,
  ApiResult,
} from '@/types/api'

export function listWorkspacesApi(page = 1, size = 20): Promise<ApiResult<PageResponse<Workspace>>> {
  return request<PageResponse<Workspace>>({
    url: '/api/v1/workspaces',
    method: 'GET',
    params: { page, size },
  })
}

export function createWorkspaceApi(data: CreateWorkspaceRequest): Promise<ApiResult<Workspace>> {
  return request<Workspace>({
    url: '/api/v1/workspaces',
    method: 'POST',
    data,
  })
}

export function getWorkspaceApi(workspaceId: number): Promise<ApiResult<Workspace>> {
  return request<Workspace>({
    url: `/api/v1/workspaces/${workspaceId}`,
    method: 'GET',
  })
}

export function updateWorkspaceApi(
  workspaceId: number,
  data: UpdateWorkspaceRequest
): Promise<ApiResult<Workspace>> {
  return request<Workspace>({
    url: `/api/v1/workspaces/${workspaceId}`,
    method: 'PUT',
    data,
  })
}

export function disableWorkspaceApi(
  workspaceId: number,
  expectedVersion: number
): Promise<ApiResult<Workspace>> {
  return request<Workspace>({
    url: `/api/v1/workspaces/${workspaceId}/disable`,
    method: 'POST',
    data: { expectedVersion },
  })
}

export function reactivateWorkspaceApi(
  workspaceId: number,
  expectedVersion: number
): Promise<ApiResult<Workspace>> {
  return request<Workspace>({
    url: `/api/v1/workspaces/${workspaceId}/reactivate`,
    method: 'POST',
    data: { expectedVersion },
  })
}

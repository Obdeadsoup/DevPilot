import { request } from '../client'
import type {
  Workspace,
  CreateWorkspaceRequest,
  UpdateWorkspaceRequest,
  PageResponse,
  ApiResult,
  WorkspaceMember,
} from '@/types/api'

export function listWorkspacesApi(page = 1, size = 20): Promise<ApiResult<PageResponse<Workspace>>> {
  return request<PageResponse<Workspace>>({
    url: '/api/v1/workspaces',
    method: 'GET',
    params: { page, size },
  })
}

export function listWorkspaceMembersApi(workspaceId: number): Promise<ApiResult<WorkspaceMember[]>> {
  return request<WorkspaceMember[]>({ url: `/api/v1/workspaces/${workspaceId}/members`, method: 'GET' })
}

export function inviteWorkspaceMemberApi(workspaceId: number, email: string, role: WorkspaceMember['role']): Promise<ApiResult<null>> {
  return request<null>({ url: `/api/v1/workspaces/${workspaceId}/members/invitations`, method: 'POST', data: { email, role } })
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

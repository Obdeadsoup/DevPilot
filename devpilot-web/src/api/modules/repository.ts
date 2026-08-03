import { request } from '../client'
import type {
  GitHubRepositoryBinding,
  CreateGitHubRepositoryRequest,
  PageResponse,
  ApiResult,
} from '@/types/api'

export function listRepositoriesApi(
  workspaceId: number,
  projectId: number,
  params?: { page?: number; size?: number; status?: string }
): Promise<ApiResult<PageResponse<GitHubRepositoryBinding>>> {
  return request<PageResponse<GitHubRepositoryBinding>>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}/github-repositories`,
    method: 'GET',
    params,
  })
}

export function createRepositoryApi(
  workspaceId: number,
  projectId: number,
  data: CreateGitHubRepositoryRequest
): Promise<ApiResult<GitHubRepositoryBinding>> {
  return request<GitHubRepositoryBinding>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}/github-repositories`,
    method: 'POST',
    data,
  })
}

export function getRepositoryApi(
  workspaceId: number,
  projectId: number,
  bindingId: number
): Promise<ApiResult<GitHubRepositoryBinding>> {
  return request<GitHubRepositoryBinding>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}/github-repositories/${bindingId}`,
    method: 'GET',
  })
}

export function disableRepositoryApi(
  workspaceId: number,
  projectId: number,
  bindingId: number,
  expectedVersion: number
): Promise<ApiResult<GitHubRepositoryBinding>> {
  return request<GitHubRepositoryBinding>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}/github-repositories/${bindingId}/disable`,
    method: 'POST',
    data: { expectedVersion },
  })
}

export function reactivateRepositoryApi(
  workspaceId: number,
  projectId: number,
  bindingId: number,
  expectedVersion: number
): Promise<ApiResult<GitHubRepositoryBinding>> {
  return request<GitHubRepositoryBinding>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}/github-repositories/${bindingId}/reactivate`,
    method: 'POST',
    data: { expectedVersion },
  })
}

export function refreshRepositoryApi(
  workspaceId: number,
  projectId: number,
  bindingId: number,
  expectedVersion: number
): Promise<ApiResult<GitHubRepositoryBinding>> {
  return request<GitHubRepositoryBinding>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}/github-repositories/${bindingId}/refresh`,
    method: 'POST',
    data: { expectedVersion },
  })
}

export function unbindRepositoryApi(
  workspaceId: number,
  projectId: number,
  bindingId: number,
  expectedVersion: number
): Promise<ApiResult<null>> {
  return request<null>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}/github-repositories/${bindingId}/unbind`,
    method: 'POST',
    data: { expectedVersion },
  })
}

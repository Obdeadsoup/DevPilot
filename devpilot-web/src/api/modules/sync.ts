import { request } from '../client'
import type { SyncRunReceipt, GitHubSyncRun, ApiResult } from '@/types/api'

export function triggerCommitSyncApi(
  workspaceId: number,
  projectId: number,
  bindingId: number
): Promise<ApiResult<SyncRunReceipt>> {
  return request<SyncRunReceipt>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}/github-repositories/${bindingId}/sync/commits`,
    method: 'POST',
  })
}

export function getSyncRunApi(
  workspaceId: number,
  projectId: number,
  bindingId: number,
  runId: number
): Promise<ApiResult<GitHubSyncRun>> {
  return request<GitHubSyncRun>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}/github-repositories/${bindingId}/sync-runs/${runId}`,
    method: 'GET',
  })
}

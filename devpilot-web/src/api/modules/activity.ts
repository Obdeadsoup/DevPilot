import { request } from '../client'
import type { ActivityResponse, GitHubSnapshotPageResponse, ApiResult } from '@/types/api'

export function listActivitiesApi(
  workspaceId: number,
  projectId: number,
  page = 1,
  size = 20
): Promise<ApiResult<GitHubSnapshotPageResponse<ActivityResponse>>> {
  return request<GitHubSnapshotPageResponse<ActivityResponse>>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}/activities`,
    method: 'GET',
    params: { page, size },
  })
}

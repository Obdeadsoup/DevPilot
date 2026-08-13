import { request } from '../client'
import type { ApiResult, PageResponse } from '@/types/api'
import type {
  DeadOutboxEventResponse,
  DeadGitHubSyncRunResponse,
  ReplayRequest,
  ReplayReceiptResponse,
} from '@/types/operations'

export function listDeadOutboxEventsApi(
  workspaceId: number,
  projectId: number,
  page = 1,
  size = 20
): Promise<ApiResult<PageResponse<DeadOutboxEventResponse>>> {
  return request<PageResponse<DeadOutboxEventResponse>>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}/operations/outbox/dead`,
    method: 'GET',
    params: { page, size },
  })
}

export function getDeadOutboxEventApi(
  workspaceId: number,
  projectId: number,
  eventId: number
): Promise<ApiResult<DeadOutboxEventResponse>> {
  return request<DeadOutboxEventResponse>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}/operations/outbox/${eventId}`,
    method: 'GET',
  })
}

export function replayOutboxEventApi(
  workspaceId: number,
  projectId: number,
  eventId: number,
  data: ReplayRequest
): Promise<ApiResult<ReplayReceiptResponse>> {
  return request<ReplayReceiptResponse>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}/operations/outbox/${eventId}/replays`,
    method: 'POST',
    data,
  })
}

export function listDeadGitHubSyncRunsApi(
  workspaceId: number,
  projectId: number,
  bindingId: number,
  page = 1,
  size = 20
): Promise<ApiResult<PageResponse<DeadGitHubSyncRunResponse>>> {
  return request<PageResponse<DeadGitHubSyncRunResponse>>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}/github-repositories/${bindingId}/sync-runs`,
    method: 'GET',
    params: { status: 'DEAD', page, size },
  })
}

export function replayGitHubSyncRunApi(
  workspaceId: number,
  projectId: number,
  bindingId: number,
  runId: number,
  data: ReplayRequest
): Promise<ApiResult<ReplayReceiptResponse>> {
  return request<ReplayReceiptResponse>({
    url: `/api/v1/workspaces/${workspaceId}/projects/${projectId}/github-repositories/${bindingId}/sync-runs/${runId}/replay`,
    method: 'POST',
    data,
  })
}

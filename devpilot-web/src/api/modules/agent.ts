import { request } from '../client'
import type { AgentRun, AgentRunHistoryItem, ApiResult, PageResponse } from '@/types/api'

function runBase(workspaceId: number, projectId: number) {
  return `/api/v1/workspaces/${workspaceId}/projects/${projectId}/agent-runs`
}

/** Agent HTTP 契约只指向 Java Core；浏览器不会直接访问 Python gRPC 服务。 */
export function startAgentRunApi(
  workspaceId: number,
  projectId: number,
  input: string,
): Promise<ApiResult<AgentRun>> {
  return request<AgentRun>({
    url: runBase(workspaceId, projectId),
    method: 'POST',
    data: { input },
  })
}

export function getAgentRunApi(
  workspaceId: number,
  projectId: number,
  runId: string,
): Promise<ApiResult<AgentRun>> {
  return request<AgentRun>({
    url: `${runBase(workspaceId, projectId)}/${encodeURIComponent(runId)}`,
    method: 'GET',
  })
}

export function listAgentRunsApi(
  workspaceId: number,
  projectId: number,
  page = 0,
  size = 20,
  status?: string,
): Promise<ApiResult<PageResponse<AgentRunHistoryItem>>> {
  return request<PageResponse<AgentRunHistoryItem>>({
    url: runBase(workspaceId, projectId),
    method: 'GET',
    params: { page, size, ...(status ? { status } : {}) },
  })
}

export function cancelAgentRunApi(
  workspaceId: number,
  projectId: number,
  runId: string,
): Promise<ApiResult<AgentRun>> {
  return request<AgentRun>({
    url: `${runBase(workspaceId, projectId)}/${encodeURIComponent(runId)}/cancel`,
    method: 'POST',
  })
}

export function agentRunStreamUrl(workspaceId: number, projectId: number, runId: string) {
  return `${runBase(workspaceId, projectId)}/${encodeURIComponent(runId)}/stream`
}

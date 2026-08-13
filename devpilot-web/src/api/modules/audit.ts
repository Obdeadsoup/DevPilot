import { request } from '../client'
import type { ApiResult, PageResponse } from '@/types/api'
import type { AuditRecordResponse, AuditLogQueryFilter } from '@/types/audit'

export function listAuditLogsApi(
  workspaceId: number,
  filter?: AuditLogQueryFilter
): Promise<ApiResult<PageResponse<AuditRecordResponse>>> {
  return request<PageResponse<AuditRecordResponse>>({
    url: `/api/v1/workspaces/${workspaceId}/audit-logs`,
    method: 'GET',
    params: filter,
  })
}

export function getAuditLogApi(
  workspaceId: number,
  auditId: number,
  projectId?: number
): Promise<ApiResult<AuditRecordResponse>> {
  return request<AuditRecordResponse>({
    url: `/api/v1/workspaces/${workspaceId}/audit-logs/${auditId}`,
    method: 'GET',
    params: { projectId: projectId || undefined },
  })
}

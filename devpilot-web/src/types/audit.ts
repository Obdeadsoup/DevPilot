export type AuditActorType = 'USER' | 'SYSTEM'

export type AuditResult = 'SUCCESS' | 'FAILURE' | 'DENIED'

export type AuditActionType =
  | 'OUTBOX_REPLAY_REQUESTED'
  | 'OUTBOX_REPLAY_CREATED'
  | 'OUTBOX_REPLAY_REJECTED'
  | 'GITHUB_SYNC_REPLAY_REQUESTED'
  | 'GITHUB_SYNC_REPLAY_CREATED'
  | 'GITHUB_SYNC_REPLAY_REJECTED'
  | 'OUTBOX_DEAD_VIEWED'
  | 'GITHUB_SYNC_DEAD_VIEWED'

export type AuditResourceType =
  | 'OUTBOX_EVENT'
  | 'GITHUB_SYNC_RUN'
  | 'GITHUB_REPOSITORY_BINDING'

export interface AuditRecordResponse {
  id: number
  workspaceId: number
  projectId: number | null
  actorType: AuditActorType
  actorUserId: number | null
  actionType: AuditActionType
  resourceType: AuditResourceType
  resourceId: number | null
  result: AuditResult
  reason: string | null
  errorCode: string | null
  requestId: string | null
  correlationId: string | null
  metadataJson: string | null
  occurredAt: string
}

export interface AuditLogQueryFilter {
  projectId?: number
  actorUserId?: number
  actionType?: AuditActionType
  resourceType?: AuditResourceType
  result?: AuditResult
  occurredFrom?: string
  occurredTo?: string
  page?: number
  size?: number
}

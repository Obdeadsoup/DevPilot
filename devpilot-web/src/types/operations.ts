export interface DeadOutboxEventResponse {
  id: number
  workspaceId: number
  projectId: number
  eventType: string
  aggregateType: string
  aggregateId: string
  status: 'DEAD'
  retryCount: number
  lastErrorCode: string | null
  createdAt: string
  updatedAt: string
  version: number
}

export interface DeadGitHubSyncRunResponse {
  id: number
  repositoryBindingId: number
  resourceType: string
  triggerType: string
  status: 'DEAD'
  attemptCount: number
  completedAt: string | null
  lastErrorCode: string | null
  createdAt: string
  updatedAt: string
  version: number
}

export interface ReplayRequest {
  reason: string
  expectedVersion: number
}

export interface ReplayReceiptResponse {
  replayId: number
  status: string
}

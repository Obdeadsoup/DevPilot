export type NotificationStatus = 'UNREAD' | 'READ'

export type NotificationType =
  | 'TASK_DUE_SOON'
  | 'TASK_OVERDUE'
  | 'TASK_OVERDUE_ESCALATED'
  | 'TASK_REVIEW_TIMEOUT'
  | 'PULL_REQUEST_REVIEW_TIMEOUT'
  | 'TASK_ASSIGNED'
  | 'TASK_UNASSIGNED'
  | 'TASK_SUBMITTED_FOR_REVIEW'
  | 'TASK_CHANGES_REQUESTED'
  | 'TASK_COMPLETED'
  | 'TASK_REOPENED'

export type NotificationTargetType = 'TASK' | 'PULL_REQUEST' | 'PROJECT'

export type NotificationSourceType = 'TASK' | 'GITHUB_PULL_REQUEST'

export interface NotificationResponse {
  id: number
  workspaceId: number
  projectId: number
  type: NotificationType
  title: string
  content: string
  targetType: NotificationTargetType
  targetId: number
  targetPath: string
  sourceType: NotificationSourceType
  sourceId: number
  status: NotificationStatus
  readAt: string | null
  occurredAt: string
  createdAt: string
  version: number
}

export interface NotificationPageResponse {
  items: NotificationResponse[]
  page: number
  size: number
  total: number
  totalPages: number
}

export interface MarkNotificationReadRequest {
  expectedVersion: number
}

export interface NotificationConnectedSseData {
  connected: boolean
  unreadCount: number
}

export interface NotificationCreatedSseData {
  notificationId: number
  unreadCount: number
  occurredAt: string
}

import { request } from '../client'
import type { ApiResult } from '@/types/api'
import type {
  NotificationPageResponse,
  MarkNotificationReadRequest,
  NotificationStatus,
} from '@/types/notification'

export function listNotificationsApi(
  status?: NotificationStatus,
  page = 1,
  size = 20
): Promise<ApiResult<NotificationPageResponse>> {
  return request<NotificationPageResponse>({
    url: '/api/v1/notifications',
    method: 'GET',
    params: { status: status || undefined, page, size },
  })
}

export function getUnreadNotificationCountApi(): Promise<ApiResult<{ count: number }>> {
  return request<{ count: number }>({
    url: '/api/v1/notifications/unread-count',
    method: 'GET',
  })
}

export function markNotificationReadApi(
  id: number,
  expectedVersion: number
): Promise<ApiResult<null>> {
  return request<null>({
    url: `/api/v1/notifications/${id}/read`,
    method: 'POST',
    data: { expectedVersion } as MarkNotificationReadRequest,
  })
}

export function markAllNotificationsReadApi(): Promise<ApiResult<{ updated: number }>> {
  return request<{ updated: number }>({
    url: '/api/v1/notifications/read-all',
    method: 'POST',
  })
}

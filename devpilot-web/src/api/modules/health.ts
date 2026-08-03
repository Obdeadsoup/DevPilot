import { request } from '../client'
import type { HealthResponse, ApiResult } from '@/types/api'

export function getHealthApi(): Promise<ApiResult<HealthResponse>> {
  return request<HealthResponse>({
    url: '/actuator/health',
    method: 'GET',
  })
}

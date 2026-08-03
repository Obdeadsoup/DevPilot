import { request } from '../client'
import type { LoginRequest, LoginResponse, User, ApiResult } from '@/types/api'

export function loginApi(data: LoginRequest): Promise<ApiResult<LoginResponse>> {
  return request<LoginResponse>({
    url: '/api/v1/auth/login',
    method: 'POST',
    data,
  })
}

export function getMeApi(): Promise<ApiResult<User>> {
  return request<User>({
    url: '/api/v1/auth/me',
    method: 'GET',
  })
}

export function logoutApi(): Promise<ApiResult<null>> {
  return request<null>({
    url: '/api/v1/auth/logout',
    method: 'POST',
  })
}

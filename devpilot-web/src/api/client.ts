import axios, { type AxiosRequestConfig, type AxiosResponse, AxiosError } from 'axios'
import { useAuthStore } from '@/stores/auth'
import { useDeveloperConsoleStore } from '@/stores/developerConsole'
import { redactHeaders, redactBody } from '@/utils/redaction'
import type { ApiResult, RequestAuditLog } from '@/types/api'
import router from '@/router'

export const apiClient = axios.create({
  baseURL: '',
  headers: {
    'Content-Type': 'application/json',
  },
})

// Request Interceptor: Attach Token & Log Audit
apiClient.interceptors.request.use((config) => {
  const authStore = useAuthStore()
  if (authStore.accessToken) {
    config.headers.Authorization = `Bearer ${authStore.accessToken}`
  }
  ;(config as any)._startTime = Date.now()
  return config
})

// Core request wrapper returning standardized ApiResult<T>
export async function request<T = any>(config: AxiosRequestConfig): Promise<ApiResult<T>> {
  const startTime = Date.now()
  const devConsole = useDeveloperConsoleStore()
  const authStore = useAuthStore()

  const logEntry: RequestAuditLog = {
    id: String(Date.now()) + '-' + Math.random().toString(36).substring(2, 7),
    timestamp: new Date().toLocaleTimeString(),
    method: (config.method || 'GET').toUpperCase(),
    url: config.url || '',
    headers: redactHeaders(config.headers as Record<string, any>),
    body: redactBody(config.data),
    httpStatus: null,
    code: '',
    message: '',
    durationMs: 0,
    rawResponse: null,
    success: false,
  }

  try {
    const response: AxiosResponse = await apiClient(config)
    const durationMs = Date.now() - startTime
    logEntry.durationMs = durationMs
    logEntry.httpStatus = response.status
    logEntry.rawResponse = response.data

    // Check Actuator Health special case
    if (config.url?.includes('/actuator/health')) {
      logEntry.code = response.data?.status || 'UP'
      logEntry.message = 'Health Check'
      logEntry.success = response.status === 200
      devConsole.addLog(logEntry)

      return {
        success: response.status === 200,
        httpStatus: response.status,
        code: response.data?.status || 'UP',
        message: 'Health Check',
        data: response.data as T,
        rawJson: response.data,
        networkError: false,
        durationMs,
      }
    }

    // Standard DevPilot ApiResponse<T>
    const body = response.data || {}
    const code = body.code || 'COMMON_0000'
    const message = body.message || 'Success'
    const data = body.data !== undefined ? body.data : null

    logEntry.code = code
    logEntry.message = message
    logEntry.success = response.status >= 200 && response.status < 300 && code === 'COMMON_0000'
    devConsole.addLog(logEntry)

    return {
      success: response.status >= 200 && response.status < 300 && code === 'COMMON_0000',
      httpStatus: response.status,
      code,
      message,
      data,
      rawJson: response.data,
      networkError: false,
      durationMs,
    }
  } catch (err: any) {
    const durationMs = Date.now() - startTime
    logEntry.durationMs = durationMs

    if (axios.isAxiosError(err)) {
      const axiosError = err as AxiosError<any>
      const status = axiosError.response?.status || null
      const responseData = axiosError.response?.data

      logEntry.httpStatus = status
      logEntry.rawResponse = responseData || axiosError.message

      let code = 'CLIENT_ERROR'
      let message = axiosError.message

      if (responseData && typeof responseData === 'object') {
        if (responseData.code) code = responseData.code
        if (responseData.message) message = responseData.message
      }

      logEntry.code = code
      logEntry.message = message
      logEntry.success = false
      devConsole.addLog(logEntry)

      // Handle 401 Unauthorized
      if (status === 401) {
        authStore.clearAuth()
        const currentPath = router.currentRoute.value.fullPath
        if (currentPath !== '/login') {
          router.push({ path: '/login', query: { returnUrl: currentPath } })
        }
      }

      return {
        success: false,
        httpStatus: status,
        code,
        message,
        data: null,
        rawJson: responseData || null,
        networkError: !axiosError.response,
        durationMs,
      }
    }

    // Unknown Network or System error
    logEntry.code = 'CLIENT_NETWORK_ERROR'
    logEntry.message = String(err)
    logEntry.success = false
    devConsole.addLog(logEntry)

    return {
      success: false,
      httpStatus: null,
      code: 'CLIENT_NETWORK_ERROR',
      message: String(err),
      data: null,
      rawJson: null,
      networkError: true,
      durationMs,
    }
  }
}

import type { ApiResult } from '@/types/api'

type Failure = Pick<ApiResult<unknown>, 'httpStatus' | 'networkError'>

export function productErrorMessage(
  failure: Partial<Failure> | null | undefined,
  fallback: string,
  conflictMessage = '内容已发生变化，请刷新后重试。',
) {
  if (failure?.networkError) return '暂时无法连接服务，请检查网络后重试。'
  if (failure?.httpStatus === 403) return '你没有权限执行此操作。'
  if (failure?.httpStatus === 404) return '没有找到需要的内容，请刷新后重试。'
  if (failure?.httpStatus === 409) return conflictMessage
  return fallback
}

export function unexpectedErrorMessage(_error: unknown, fallback: string) {
  return fallback
}

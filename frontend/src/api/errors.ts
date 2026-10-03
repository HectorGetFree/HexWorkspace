import axios from 'axios'

export class ApiError extends Error {
  constructor(
    public readonly httpStatus: number | undefined,
    public readonly code: string,
    message: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

export function normalizeApiError(error: unknown): ApiError {
  if (axios.isAxiosError(error)) {
    const payload = error.response?.data as { code?: string; info?: string } | undefined
    if (payload?.code || payload?.info) {
      return new ApiError(error.response?.status, payload.code ?? 'UNKNOWN', payload.info ?? '请求失败')
    }
    if (!error.response) return new ApiError(undefined, 'NETWORK_ERROR', '网络连接失败，请检查服务是否启动')
    return new ApiError(error.response.status, 'HTTP_ERROR', '请求失败，请稍后重试')
  }
  if (error instanceof ApiError) return error
  return new ApiError(undefined, 'UNKNOWN', '请求失败，请稍后重试')
}

export function userFacingError(error: unknown): string {
  const normalized = normalizeApiError(error)
  if (normalized.code === 'U401') return '用户名或密码错误'
  if (normalized.code === 'U400') return normalized.message
  return normalized.message
}

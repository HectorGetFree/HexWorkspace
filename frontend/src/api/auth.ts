import client from './client'
import { ApiError } from './errors'
import { setAccessToken } from './session'
import type { ApiEnvelope, LoginResponse, User } from '../types/auth'

function requireSuccess<T>(envelope: ApiEnvelope<T>, status = 200): T {
  if (envelope.code !== '0000' || !envelope.data) {
    throw new ApiError(status, envelope.code, envelope.info || '请求失败')
  }
  return envelope.data
}

export async function login(username: string, password: string): Promise<User> {
  const response = await client.post<ApiEnvelope<LoginResponse>>('/api/v1/users/login', {
    data: { username, password },
  })
  const payload = requireSuccess(response.data, response.status)
  if (!payload.token) throw new ApiError(response.status, 'AUTH_ERROR', '登录响应缺少 access token')
  setAccessToken(payload.token)
  return getCurrentUser()
}

export async function getCurrentUser(): Promise<User> {
  const response = await client.get<ApiEnvelope<User>>('/api/v1/users/me')
  return requireSuccess(response.data, response.status)
}

export async function restoreSession(): Promise<User> {
  const response = await client.post<ApiEnvelope<LoginResponse>>('/api/v1/users/refresh')
  const payload = requireSuccess(response.data, response.status)
  if (!payload.token) throw new ApiError(response.status, 'AUTH_ERROR', '刷新响应缺少 access token')
  setAccessToken(payload.token)
  return getCurrentUser()
}

export async function logout(): Promise<void> {
  try {
    await client.post('/api/v1/users/logout')
  } finally {
    setAccessToken(null)
  }
}

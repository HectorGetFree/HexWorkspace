import axios, { type AxiosRequestConfig, type AxiosResponse } from 'axios'
import { ApiError, normalizeApiError } from './errors'
import { getAccessToken, setAccessToken } from './session'

const baseURL = import.meta.env.VITE_API_BASE_URL || ''
const rawClient = axios.create({ baseURL, withCredentials: true })
const client = axios.create({ baseURL, withCredentials: true })
let refreshPromise: Promise<string> | null = null
let onAuthCleared: (() => void) | null = null

export function bindAuthClearHandler(handler: () => void): void {
  onAuthCleared = handler
}

export async function refreshAccessToken(): Promise<string> {
  if (!refreshPromise) {
    refreshPromise = rawClient.post('/api/v1/users/refresh')
      .then((response: AxiosResponse<{ data: { token: string } | null }>) => {
        const token = response.data.data?.token
        if (!token) throw new ApiError(response.status, 'AUTH_ERROR', '登录状态已失效，请重新登录')
        setAccessToken(token)
        return token
      })
      .finally(() => { refreshPromise = null })
  }
  return refreshPromise
}

async function fetchWithCurrentToken(input: RequestInfo | URL, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers)
  const token = getAccessToken()
  if (token) headers.set('Authorization', `Bearer ${token}`)
  return fetch(input, { ...init, headers })
}

/** 原生 fetch（例如 SSE）也必须复用 Axios 的刷新锁，避免过期 Token 直接把页面判定为掉线。 */
export async function authenticatedFetch(input: RequestInfo | URL, init: RequestInit = {}): Promise<Response> {
  const response = await fetchWithCurrentToken(input, init)
  if (response.status !== 401) return response

  try {
    await refreshAccessToken()
    return fetchWithCurrentToken(input, init)
  } catch (refreshError) {
    setAccessToken(null)
    onAuthCleared?.()
    throw normalizeApiError(refreshError)
  }
}

client.interceptors.request.use((config) => {
  const token = getAccessToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

client.interceptors.response.use(
  (response) => response,
  async (error: unknown) => {
    if (!axios.isAxiosError(error)) return Promise.reject(normalizeApiError(error))
    const config = error.config as (AxiosRequestConfig & { _authRetry?: boolean }) | undefined
    const url = config?.url ?? ''
    const isAuthEndpoint = url.includes('/users/login') || url.includes('/users/refresh') || url.includes('/users/logout')
    if (error.response?.status !== 401 || !config || config._authRetry || isAuthEndpoint) {
      return Promise.reject(normalizeApiError(error))
    }
    config._authRetry = true
    try {
      await refreshAccessToken()
      return client(config)
    } catch (refreshError) {
      setAccessToken(null)
      onAuthCleared?.()
      return Promise.reject(normalizeApiError(refreshError))
    }
  },
)

export default client

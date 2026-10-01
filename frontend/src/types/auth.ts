export interface User {
  userId: number
  username: string
  status: string
}

export interface LoginResponse {
  token: string
  tokenType: string
  userId: number
  username: string
  status: string
}

export interface ApiEnvelope<T> {
  code: string
  info: string
  data: T | null
}

import { api } from './http'
import type { AuthResponse, LoginRequest, RegisterRequest, UserResponse } from './types'

/**
 * POST /api/auth/register. Creates the account and returns the user - NO tokens. Signing
 * in afterwards is a separate call to login.
 * 409 when the email is taken; 400 with the field messages when validation fails.
 */
export async function register(request: RegisterRequest): Promise<UserResponse> {
  const response = await api.post<UserResponse>('/api/auth/register', request)
  return response.data
}

/** POST /api/auth/login. Returns tokens and nothing about the user. 401 on bad credentials. */
export async function login(request: LoginRequest): Promise<AuthResponse> {
  const response = await api.post<AuthResponse>('/api/auth/login', request)
  return response.data
}

/** GET /api/auth/me. Who the access token belongs to. */
export async function me(): Promise<UserResponse> {
  const response = await api.get<UserResponse>('/api/auth/me')
  return response.data
}

/**
 * POST /api/auth/logout. Revokes the refresh token on the server. Idempotent there: an
 * unknown or already-revoked token is a 204, not an error.
 */
export async function logout(refreshToken: string): Promise<void> {
  await api.post('/api/auth/logout', { refreshToken })
}

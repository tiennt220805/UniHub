import { request } from '@/shared/api/http-client'
import type { components } from '@/shared/api/schema.gen'

export type LoginRequest = components['schemas']['LoginRequest']
export type LoginResponse = components['schemas']['LoginResponse']

export const login = (body: LoginRequest) =>
  request<LoginResponse>('/api/v1/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })

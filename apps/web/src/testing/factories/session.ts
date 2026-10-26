import { type Permission, type Session, startSession } from '@/features/auth'
import type { components } from '@/shared/api/schema.gen'

type LoginResponse = components['schemas']['LoginResponse']

export const STUDENT_ID = '7a1d2c3e-4b5f-4a6b-8c9d-0e1f2a3b4c5d'

export function aLoginResponse(overrides: Partial<LoginResponse> = {}): LoginResponse {
  return {
    // Opaque to the client, as in production: the client never decodes it.
    accessToken: 'opaque-access-token',
    expiresIn: 1800,
    user: { id: STUDENT_ID, fullName: 'Nguyễn Văn An', email: 'an@student.test', roles: ['Student'] },
    permissions: ['REGISTRATION_CREATE'],
    ...overrides,
  }
}

/** Starts a session as the app would after a sign-in; the setup file ends it after each test. */
export function signInAs(
  options: { permissions?: Permission[]; expiresAt?: number; userId?: string } = {},
): Session {
  const session: Session = {
    accessToken: 'opaque-access-token',
    expiresAt: options.expiresAt ?? Date.now() + 30 * 60 * 1000,
    user: {
      id: options.userId ?? STUDENT_ID,
      fullName: 'Nguyễn Văn An',
      email: 'an@student.test',
      roles: ['Student'],
    },
    permissions: new Set(options.permissions ?? ['REGISTRATION_CREATE']),
  }
  startSession(session)
  return session
}

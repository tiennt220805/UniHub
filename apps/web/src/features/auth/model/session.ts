import type { components } from '@/shared/api/schema.gen'
import type { Permission } from './permissions'

type LoginResponse = components['schemas']['LoginResponse']
type UserSummary = components['schemas']['UserSummary']

export type Session = {
  accessToken: string
  /** Epoch ms. Computed at sign-in from `LoginResponse.expiresIn`: the token is never decoded. */
  expiresAt: number
  /** Display only. `user.roles` never gates anything (web-coding-standards.md §11). */
  user: UserSummary
  /** The permission codes the token carries. For UX only; the Backend API enforces. */
  permissions: ReadonlySet<string>
}

/** What `sessionStorage` holds: a Set does not survive JSON. */
export type StoredSession = Omit<Session, 'permissions'> & { permissions: string[] }

export function toSession(response: LoginResponse, now: number): Session {
  return {
    accessToken: response.accessToken,
    expiresAt: now + response.expiresIn * 1000,
    user: response.user,
    permissions: new Set(response.permissions),
  }
}

export const toStored = (session: Session): StoredSession => ({
  ...session,
  permissions: [...session.permissions],
})

export const fromStored = (stored: StoredSession): Session => ({
  ...stored,
  permissions: new Set(stored.permissions),
})

export const hasAnyPermission = (session: Session, anyOf: readonly Permission[]) =>
  anyOf.some((permission) => session.permissions.has(permission))

export const isLive = (session: Session, now: number) => session.expiresAt > now

import { ApiError, type Api } from '@/shared/api/api-client'
import type { VerificationKey } from '@/shared/crypto/verification-key'
import { decodeBase64Url } from '@/shared/lib/base64url'
import type { SecretStore } from '@/shared/secure/secret-store'

// One storage key, so there is one cached key: writing replaces it. There is no `kid`, no list and no
// "previous key" slot (`checkin.md` §6): a ticket signed under a replaced key stops verifying at once.
const KEY_STORAGE = 'unihub.verificationKey'

const isCoordinate = (value: unknown): value is string =>
  typeof value === 'string' && decodeBase64Url(value)?.length === 32

export async function loadVerificationKey(store: SecretStore): Promise<VerificationKey | null> {
  const stored = await store.get(KEY_STORAGE)
  if (stored === null) return null
  try {
    const parsed: unknown = JSON.parse(stored)
    if (typeof parsed === 'object' && parsed !== null) {
      const { x, y } = parsed as Record<string, unknown>
      if (isCoordinate(x) && isCoordinate(y)) return { x, y }
    }
  } catch {
    // fall through: an unreadable entry is no key
  }
  return null
}

export type KeyRefresh =
  | { outcome: 'UPDATED'; key: VerificationKey }
  /** The Backend API said this account may not read the key (`403`): not Check-in Staff. */
  | { outcome: 'NOT_PERMITTED' }
  /** Anything else: the cached key, if any, is untouched. */
  | { outcome: 'KEPT' }

/**
 * Fetches the verification key and overwrites the cached one with it. A failure while online keeps
 * the old key: the device can still scan, and the next successful sync tries again (D59).
 */
export async function refreshVerificationKey(api: Api, store: SecretStore): Promise<KeyRefresh> {
  try {
    const response = await api.verificationKey()
    if (response.kty !== 'EC' || response.crv !== 'P-256') return { outcome: 'KEPT' }
    if (!isCoordinate(response.x) || !isCoordinate(response.y)) return { outcome: 'KEPT' }
    const key = { x: response.x, y: response.y }
    await store.set(KEY_STORAGE, JSON.stringify(key))
    return { outcome: 'UPDATED', key }
  } catch (error) {
    if (error instanceof ApiError && error.status === 403) return { outcome: 'NOT_PERMITTED' }
    return { outcome: 'KEPT' }
  }
}

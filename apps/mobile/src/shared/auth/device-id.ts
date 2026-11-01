import type { SecretStore } from '@/shared/secure/secret-store'

const DEVICE_ID_KEY = 'unihub.deviceId'
/** `check_in_scans.device_id VARCHAR(100)` on the server, and the length `ScanEvent.deviceId` allows. */
export const MAX_DEVICE_ID_LENGTH = 100

export const isValidDeviceId = (value: unknown): value is string =>
  typeof value === 'string' && value.length >= 1 && value.length <= MAX_DEVICE_ID_LENGTH

/**
 * This installation's identity in `scan.deviceId`: a random UUID made the first time the app is
 * opened and kept, so it is the same after every sign-out (`D59`). Deleting the app makes a new one.
 */
export async function getOrCreateDeviceId(
  store: SecretStore,
  newId: () => string,
): Promise<string> {
  const existing = await store.get(DEVICE_ID_KEY)
  if (isValidDeviceId(existing)) return existing
  const created = newId()
  if (!isValidDeviceId(created)) throw new Error('A device id must be 1 to 100 characters')
  await store.set(DEVICE_ID_KEY, created)
  return created
}

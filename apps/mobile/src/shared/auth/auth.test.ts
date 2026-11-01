import { memorySecretStore } from '@/testing/secret-store'
import { asVerificationKey, issueTicket, newTestKey } from '@/testing/tickets'
import { fakeAuthApi, httpError, offline, staffLogin } from '@/testing/fake-api'
import { verifyTicket } from '@/shared/ticket'

import { getOrCreateDeviceId, isValidDeviceId } from './device-id'
import { createSessionStore } from './session-store'
import { endSession, restoreSession, signIn } from './session'
import { loadVerificationKey, refreshVerificationKey } from './verification-key'

const keyResponse = (key = newTestKey()) => ({ ...key.publicJwk })
const NOW = 1_800_000_000_000

describe('the verification key is cached as one key (AC59.2)', () => {
  it('stores the key it fetched and reads it back', async () => {
    const store = memorySecretStore()
    const response = keyResponse()
    const { apiFor } = fakeAuthApi({ key: response })

    const result = await refreshVerificationKey(apiFor('t'), store)

    expect(result).toEqual({ outcome: 'UPDATED', key: { x: response.x, y: response.y } })
    expect(await loadVerificationKey(store)).toEqual({ x: response.x, y: response.y })
  })

  it('a new key replaces the old one: there is never a second entry, and the old key no longer verifies', async () => {
    const store = memorySecretStore()
    const oldKey = newTestKey()
    const newKey = newTestKey()
    await refreshVerificationKey(fakeAuthApi({ key: keyResponse(oldKey) }).apiFor('t'), store)
    const oldTicket = issueTicket(oldKey)
    expect(verifyTicket(oldTicket, await loadVerificationKey(store)).ok).toBe(true)
    const entriesBefore = store.entries.size

    await refreshVerificationKey(fakeAuthApi({ key: keyResponse(newKey) }).apiFor('t'), store)

    expect(store.entries.size).toBe(entriesBefore)
    const cached = await loadVerificationKey(store)
    expect(cached).toEqual(asVerificationKey(newKey))
    expect(verifyTicket(oldTicket, cached)).toEqual({ ok: false, reason: 'SIGNATURE' })
    expect(verifyTicket(issueTicket(newKey), cached).ok).toBe(true)
  })

  it('keeps the old key when fetching fails while online', async () => {
    const store = memorySecretStore()
    const first = keyResponse()
    await refreshVerificationKey(fakeAuthApi({ key: first }).apiFor('t'), store)

    for (const failure of [offline(), httpError(500), httpError(401)]) {
      expect(
        await refreshVerificationKey(fakeAuthApi({ key: failure }).apiFor('t'), store),
      ).toEqual({
        outcome: 'KEPT',
      })
    }
    expect(await loadVerificationKey(store)).toEqual({ x: first.x, y: first.y })
  })

  it('keeps the old key when the answer is not a P-256 key', async () => {
    const store = memorySecretStore()
    const first = keyResponse()
    await refreshVerificationKey(fakeAuthApi({ key: first }).apiFor('t'), store)

    for (const bad of [
      { ...first, crv: 'P-384' },
      { ...first, kty: 'RSA' },
      { ...first, x: 'AAAA' },
      { ...first, y: '!!!' },
    ]) {
      expect(await refreshVerificationKey(fakeAuthApi({ key: bad }).apiFor('t'), store)).toEqual({
        outcome: 'KEPT',
      })
    }
    expect(await loadVerificationKey(store)).toEqual({ x: first.x, y: first.y })
  })

  it('answers NOT_PERMITTED for a 403 and stores nothing', async () => {
    const store = memorySecretStore()

    expect(
      await refreshVerificationKey(fakeAuthApi({ key: httpError(403) }).apiFor('t'), store),
    ).toEqual({
      outcome: 'NOT_PERMITTED',
    })
    expect(store.entries.size).toBe(0)
    expect(await loadVerificationKey(store)).toBeNull()
  })

  it('treats an unreadable stored key as no key', async () => {
    const store = memorySecretStore()
    await store.set('unihub.verificationKey', '{not json')

    expect(await loadVerificationKey(store)).toBeNull()
  })
})

describe('signing in', () => {
  it('stores the session and the key for a Check-in Staff account, fetching the key with the new token', async () => {
    const store = memorySecretStore()
    const response = keyResponse()
    const { apiFor, tokensSeen } = fakeAuthApi({ login: staffLogin(), key: response })

    const result = await signIn(
      { apiFor, secrets: store, now: () => NOW },
      'staff@dev.test',
      'pw',
      false,
    )

    expect(result).toMatchObject({ kind: 'SIGNED_IN', hasKey: true })
    expect(tokensSeen).toEqual([null, 'opaque-token'])
    const restored = await restoreSession(store)
    expect(restored).toMatchObject({ accessToken: 'opaque-token', expiresAt: NOW + 1_800_000 })
    expect(await loadVerificationKey(store)).toEqual({ x: response.x, y: response.y })
  })

  it('refuses a Student: no permission to scan, nothing stored, no key fetched', async () => {
    const store = memorySecretStore()
    const { apiFor, tokensSeen } = fakeAuthApi({
      login: staffLogin({ permissions: ['REGISTRATION_CREATE'] }),
      key: keyResponse(),
    })

    const result = await signIn(
      { apiFor, secrets: store, now: () => NOW },
      's@dev.test',
      'pw',
      false,
    )

    expect(result).toEqual({ kind: 'NOT_STAFF' })
    expect(store.entries.size).toBe(0)
    expect(tokensSeen).toEqual([null])
  })

  it('refuses an account the key endpoint calls 403 even if the login listed the permission', async () => {
    const store = memorySecretStore()
    const { apiFor } = fakeAuthApi({ login: staffLogin(), key: httpError(403) })

    expect(await signIn({ apiFor, secrets: store, now: () => NOW }, 'a', 'b', false)).toEqual({
      kind: 'NOT_STAFF',
    })
    expect(store.entries.size).toBe(0)
  })

  it('wrong credentials and an unreachable server are told apart', async () => {
    const store = memorySecretStore()

    expect(
      await signIn(
        { apiFor: fakeAuthApi({ login: httpError(401) }).apiFor, secrets: store, now: () => NOW },
        'a',
        'b',
        false,
      ),
    ).toEqual({ kind: 'BAD_CREDENTIALS' })
    expect(
      await signIn(
        { apiFor: fakeAuthApi({ login: offline() }).apiFor, secrets: store, now: () => NOW },
        'a',
        'b',
        false,
      ),
    ).toEqual({ kind: 'UNAVAILABLE' })
    expect(store.entries.size).toBe(0)
  })

  it('signs in with the cached key when the key cannot be fetched, and says there is none when it cannot', async () => {
    const withCache = await signIn(
      {
        apiFor: fakeAuthApi({ login: staffLogin(), key: offline() }).apiFor,
        secrets: memorySecretStore(),
        now: () => NOW,
      },
      'a',
      'b',
      true,
    )
    const without = await signIn(
      {
        apiFor: fakeAuthApi({ login: staffLogin(), key: offline() }).apiFor,
        secrets: memorySecretStore(),
        now: () => NOW,
      },
      'a',
      'b',
      false,
    )

    expect(withCache).toMatchObject({ kind: 'SIGNED_IN', hasKey: true })
    expect(without).toMatchObject({ kind: 'SIGNED_IN', hasKey: false })
  })

  it('a session whose token has expired is still the stored session: scanning goes on offline', async () => {
    const store = memorySecretStore()
    const { apiFor } = fakeAuthApi({ login: staffLogin({ expiresIn: 60 }), key: keyResponse() })
    await signIn({ apiFor, secrets: store, now: () => NOW }, 'a', 'b', false)

    const restored = await restoreSession(store)

    expect(restored).toMatchObject({ accessToken: 'opaque-token', expiresAt: NOW + 60_000 })
    expect(restored!.expiresAt).toBeLessThan(NOW + 3_600_000)
  })

  it('after a restart past the token’s expiry the person is still signed in with the key, and can scan', async () => {
    const store = memorySecretStore()
    const response = keyResponse()
    const { apiFor } = fakeAuthApi({ login: staffLogin({ expiresIn: 60 }), key: response })
    await signIn({ apiFor, secrets: store, now: () => NOW }, 'a', 'b', false)

    // A new process, two hours later: nothing but the keystore carries over.
    const restarted = createSessionStore({
      secrets: store,
      apiFor,
      now: () => NOW + 2 * 3_600_000,
      newId: () => 'device',
    })
    await restarted.bootstrap()

    expect(restarted.get().session?.user.id).toBe(staffLogin().user.id)
    expect(restarted.get().key).toEqual({ x: response.x, y: response.y })
    expect(restarted.get().expired).toBe(false)
  })

  it('an unreadable stored session is no session and is removed', async () => {
    const store = memorySecretStore()
    await store.set('unihub.session', '{"accessToken": 1}')

    expect(await restoreSession(store)).toBeNull()
    expect(store.entries.has('unihub.session')).toBe(false)
  })

  it('ending a session leaves the key and the device id', async () => {
    const store = memorySecretStore()
    const response = keyResponse()
    const { apiFor } = fakeAuthApi({ login: staffLogin({ expiresIn: 60 }), key: response })
    await signIn({ apiFor, secrets: store, now: () => NOW }, 'a', 'b', false)
    const deviceId = await getOrCreateDeviceId(store, () => 'device-uuid')

    await endSession(store)

    expect(await restoreSession(store)).toBeNull()
    expect(await loadVerificationKey(store)).toEqual({ x: response.x, y: response.y })
    expect(await getOrCreateDeviceId(store, () => 'a-different-id')).toBe(deviceId)
  })
})

describe('the device id', () => {
  it('is created once and is the same from then on', async () => {
    const store = memorySecretStore()
    let made = 0

    const first = await getOrCreateDeviceId(store, () => `device-${++made}`)
    const second = await getOrCreateDeviceId(store, () => `device-${++made}`)

    expect(first).toBe('device-1')
    expect(second).toBe('device-1')
    expect(made).toBe(1)
  })

  it('is 1 to 100 characters, boundaries included (BVA)', async () => {
    expect([0, 1, 2, 99, 100, 101].map((n) => isValidDeviceId('d'.repeat(n)))).toEqual([
      false,
      true,
      true,
      true,
      true,
      false,
    ])
    expect(isValidDeviceId(undefined)).toBe(false)
    await expect(getOrCreateDeviceId(memorySecretStore(), () => 'd'.repeat(101))).rejects.toThrow()
    await expect(getOrCreateDeviceId(memorySecretStore(), () => '')).rejects.toThrow()
  })

  it('replaces a stored value that is not a valid id', async () => {
    const store = memorySecretStore()
    await store.set('unihub.deviceId', 'x'.repeat(500))

    expect(await getOrCreateDeviceId(store, () => 'fresh')).toBe('fresh')
  })
})

describe('what the keystore holds', () => {
  it('keeps no ticket and no scan: only the session, the key and the device id', async () => {
    const store = memorySecretStore()
    const { apiFor } = fakeAuthApi({ login: staffLogin(), key: keyResponse() })
    await signIn({ apiFor, secrets: store, now: () => NOW }, 'a', 'b', false)
    await getOrCreateDeviceId(store, () => 'device')

    expect([...store.entries.keys()].sort()).toEqual([
      'unihub.deviceId',
      'unihub.session',
      'unihub.verificationKey',
    ])
  })
})

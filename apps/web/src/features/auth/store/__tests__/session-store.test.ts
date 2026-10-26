import { beforeEach, describe, expect, it, vi } from 'vitest'

import { aLoginResponse } from '@/testing/factories/session'
import { fromStored, hasAnyPermission, toSession, toStored } from '../../model/session'
import { currentAccessToken, currentSession, endSession, startSession } from '../session-access'
import { restoreSession } from '../session-store'

const KEY = 'unihub.session'
const NOW = 1_800_000_000_000

beforeEach(() => sessionStorage.clear())

describe('toSession', () => {
  it('turns expiresIn seconds into an absolute expiry without ever decoding the token', () => {
    const session = toSession(aLoginResponse({ expiresIn: 1800, accessToken: 'not-a-jwt' }), NOW)

    expect(session.expiresAt).toBe(NOW + 1_800_000)
    expect(session.accessToken).toBe('not-a-jwt')
  })

  it('takes the permissions from LoginResponse.permissions (AC14.4)', () => {
    const session = toSession(aLoginResponse({ permissions: ['WORKSHOP_EDIT'] }), NOW)

    expect([...session.permissions]).toEqual(['WORKSHOP_EDIT'])
  })
})

describe('permission gating (AC14.4)', () => {
  it('asks only for permissions: any one of the listed codes is enough', () => {
    const session = toSession(aLoginResponse({ permissions: ['WORKSHOP_EDIT'] }), NOW)

    expect(hasAnyPermission(session, ['WORKSHOP_CREATE', 'WORKSHOP_EDIT'])).toBe(true)
    expect(hasAnyPermission(session, ['WORKSHOP_CREATE'])).toBe(false)
  })

  it('never reads the role label: an Organizer role without the permission is denied', () => {
    const session = toSession(
      aLoginResponse({
        permissions: [],
        user: { id: 'u', fullName: 'Lan', email: 'lan@x.test', roles: ['Organizer'] },
      }),
      NOW,
    )

    expect(hasAnyPermission(session, ['WORKSHOP_CREATE'])).toBe(false)
  })
})

describe('restoreSession', () => {
  it('restores the session after a reload (AC14.1)', () => {
    const stored = toStored(toSession(aLoginResponse({ permissions: ['REGISTRATION_CREATE'] }), NOW))
    sessionStorage.setItem(KEY, JSON.stringify(stored))

    const restored = restoreSession(NOW + 60_000)

    expect(restored?.accessToken).toBe('opaque-access-token')
    expect(restored?.permissions.has('REGISTRATION_CREATE')).toBe(true)
    expect(restored?.user.fullName).toBe('Nguyễn Văn An')
  })

  it('discards a token that expired and removes it from storage (AC14.2)', () => {
    const stored = toStored(toSession(aLoginResponse({ expiresIn: 1800 }), NOW))
    sessionStorage.setItem(KEY, JSON.stringify(stored))

    expect(restoreSession(NOW + 1_800_000)).toBeNull() // exactly at expiry: expired
    expect(sessionStorage.getItem(KEY)).toBeNull()
  })

  it('keeps a token one millisecond before its expiry', () => {
    const stored = toStored(toSession(aLoginResponse({ expiresIn: 1800 }), NOW))
    sessionStorage.setItem(KEY, JSON.stringify(stored))

    expect(restoreSession(NOW + 1_800_000 - 1)).not.toBeNull()
  })

  it('treats corrupt or foreign data as no session', () => {
    sessionStorage.setItem(KEY, '{not json')
    expect(restoreSession(NOW)).toBeNull()

    sessionStorage.setItem(KEY, JSON.stringify({ accessToken: 5 }))
    expect(restoreSession(NOW)).toBeNull()
    expect(sessionStorage.getItem(KEY)).toBeNull()
  })

  it('does not throw when storage itself is unavailable', () => {
    const spy = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })

    expect(restoreSession(NOW)).toBeNull()
    spy.mockRestore()
  })
})

describe('the live session', () => {
  it('start keeps it in storage and in memory, end removes both (AC14.3)', () => {
    startSession(fromStored(toStored(toSession(aLoginResponse(), Date.now()))))

    expect(sessionStorage.getItem(KEY)).not.toBeNull()
    expect(currentAccessToken()).toBe('opaque-access-token')

    endSession()

    expect(sessionStorage.getItem(KEY)).toBeNull()
    expect(currentSession()).toBeNull()
  })

  it('sends no token once the session has expired', () => {
    startSession(toSession(aLoginResponse({ expiresIn: 1 }), Date.now() - 5_000))

    expect(currentSession()).toBeNull()
    expect(currentAccessToken()).toBeNull()
  })
})

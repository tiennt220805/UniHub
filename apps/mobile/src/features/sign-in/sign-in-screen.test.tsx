import { fireEvent, screen, waitFor } from '@testing-library/react-native'

import type { ApiError } from '@/shared/api/api-client'
import { fakeAuthApi, httpError, offline, staffLogin } from '@/testing/fake-api'
import { makeHarness, renderWithServices, type Harness } from '@/testing/render-with-services'
import { newTestKey } from '@/testing/tickets'
import { createSessionStore } from '@/shared/auth/session-store'

import { SignInScreen } from './SignInScreen'

let harness: Harness
afterEach(() => harness.close())

function withAuth(options: Parameters<typeof fakeAuthApi>[0]) {
  harness = makeHarness({ signedInAs: null })
  const { apiFor } = fakeAuthApi(options)
  const session = createSessionStore({
    secrets: harness.secrets,
    apiFor,
    now: () => Date.now(),
    newId: () => 'device-id',
  })
  harness.services.session = session
  session.store.setState({ phase: 'ready' })
  return session
}

async function submit() {
  await renderWithServices(harness, <SignInScreen />, { sync: false })
  await fireEvent.changeText(screen.getByLabelText('Email'), 'staff@dev.test')
  await fireEvent.changeText(screen.getByLabelText('Mật khẩu'), 'pw')
  await fireEvent.press(screen.getByRole('button', { name: 'Đăng nhập' }))
}

describe('the sign-in screen (8.2)', () => {
  it('signs a Check-in Staff account in and caches the key', async () => {
    const session = withAuth({ login: staffLogin(), key: { ...newTestKey().publicJwk } })

    await submit()

    await waitFor(() => expect(session.get().session).not.toBeNull())
    expect(session.get().key).not.toBeNull()
  })

  it.each([
    [httpError(401), 'Email hoặc mật khẩu không đúng.'],
    [offline(), /Không kết nối được máy chủ/],
  ])('says what went wrong at login: %#', async (failure: ApiError, message) => {
    const session = withAuth({ login: failure })

    await submit()

    expect(await screen.findByText(message)).toBeTruthy()
    expect(session.get().session).toBeNull()
  })

  it('refuses a Student account and stores nothing', async () => {
    const session = withAuth({ login: staffLogin({ permissions: ['REGISTRATION_CREATE'] }) })

    await submit()

    expect(await screen.findByText('Tài khoản này không phải nhân viên check-in.')).toBeTruthy()
    expect(session.get().session).toBeNull()
    expect(harness.secrets.entries.size).toBe(0)
  })

  it('says why when a session ended because the token expired', async () => {
    harness = makeHarness({ signedInAs: null })
    harness.session.store.setState({ expired: true })

    await renderWithServices(harness, <SignInScreen />, { sync: false })

    expect(screen.getByText(/Phiên đăng nhập đã hết hạn/)).toBeTruthy()
    expect(screen.getByText(/vẫn được giữ trên máy/)).toBeTruthy()
  })

  it('keeps the sign-in button off until both fields are filled', async () => {
    harness = makeHarness({ signedInAs: null })
    await renderWithServices(harness, <SignInScreen />, { sync: false })

    expect(screen.getByRole('button', { name: 'Đăng nhập' })).toBeDisabled()
  })
})

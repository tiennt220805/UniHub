import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'

import { currentSession } from '@/features/auth'
import { aLoginResponse, signInAs } from '@/testing/factories/session'
import { aWorkshopPage } from '@/testing/factories/workshop'
import { server } from '@/testing/msw-server'
import { renderRoute } from '@/testing/render-route'

const problem = (status: number, title: string) =>
  HttpResponse.json({ type: 'about:blank', title, status }, {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  })

async function fillAndSubmit(user: ReturnType<typeof userEvent.setup>, password = 'secret') {
  await user.type(screen.getByLabelText('Email'), 'an@student.test')
  await user.type(screen.getByLabelText('Mật khẩu'), password)
  await user.click(screen.getByRole('button', { name: 'Đăng nhập' }))
}

describe('SignInPage', () => {
  it('signs in, keeps the token and permissions from LoginResponse, and moves on (AC14.4)', async () => {
    let body: unknown
    server.use(
      http.post('/api/v1/auth/login', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json(aLoginResponse({ permissions: ['REGISTRATION_CREATE'] }))
      }),
      http.get('/api/v1/workshops', () => HttpResponse.json(aWorkshopPage([]))),
    )
    const user = userEvent.setup()
    const { router } = renderRoute('/login')

    await fillAndSubmit(user)

    await waitFor(() => expect(router.state.location.pathname).toBe('/workshops'))
    expect(body).toEqual({ email: 'an@student.test', password: 'secret' })
    expect(currentSession()?.accessToken).toBe('opaque-access-token')
    expect(currentSession()?.permissions.has('REGISTRATION_CREATE')).toBe(true)
    expect(sessionStorage.getItem('unihub.session')).not.toBeNull()
  })

  it('returns the user to the page a guard bounced them from', async () => {
    server.use(
      http.post('/api/v1/auth/login', () => HttpResponse.json(aLoginResponse())),
      http.get('/api/v1/workshops/:id', () => HttpResponse.json({}, { status: 404 })),
    )
    const user = userEvent.setup()
    const { router } = renderRoute('/login', {
      state: { from: { pathname: '/workshops/abc', search: '?x=1' } },
    })

    await fillAndSubmit(user)

    await waitFor(() => expect(router.state.location.pathname).toBe('/workshops/abc'))
    expect(router.state.location.search).toBe('?x=1')
  })

  it('does not follow a return path that leaves the app', async () => {
    server.use(
      http.post('/api/v1/auth/login', () => HttpResponse.json(aLoginResponse())),
      http.get('/api/v1/workshops', () => HttpResponse.json(aWorkshopPage([]))),
    )
    const user = userEvent.setup()
    const { router } = renderRoute('/login', { state: { from: { pathname: '//evil.test/' } } })

    await fillAndSubmit(user)

    await waitFor(() => expect(router.state.location.pathname).toBe('/workshops'))
  })

  it('says the credentials are wrong on a 401 and starts no session', async () => {
    server.use(http.post('/api/v1/auth/login', () => problem(401, 'Unauthorized')))
    const user = userEvent.setup()
    renderRoute('/login')

    await fillAndSubmit(user, 'wrong')

    expect(await screen.findByRole('alert')).toHaveTextContent('Email hoặc mật khẩu không đúng.')
    expect(currentSession()).toBeNull()
  })

  it('tells the user how long to wait on a 429', async () => {
    server.use(
      http.post('/api/v1/auth/login', () =>
        HttpResponse.json({ type: 'about:blank', title: 'Too many', status: 429 }, {
          status: 429,
          headers: { 'Content-Type': 'application/problem+json', 'Retry-After': '42' },
        }),
      ),
    )
    const user = userEvent.setup()
    renderRoute('/login')

    await fillAndSubmit(user)

    expect(await screen.findByRole('alert')).toHaveTextContent('sau 42 giây')
  })

  it('rejects a malformed email before sending anything', async () => {
    let called = false
    server.use(
      http.post('/api/v1/auth/login', () => {
        called = true
        return HttpResponse.json(aLoginResponse())
      }),
    )
    const user = userEvent.setup()
    renderRoute('/login')

    await user.type(screen.getByLabelText('Email'), 'not-an-email')
    await user.type(screen.getByLabelText('Mật khẩu'), 'secret')
    await user.click(screen.getByRole('button', { name: 'Đăng nhập' }))

    expect(await screen.findByText('Email không hợp lệ.')).toBeInTheDocument()
    expect(called).toBe(false)
  })

  it('sends a signed-in user straight on', async () => {
    server.use(http.get('/api/v1/workshops', () => HttpResponse.json(aWorkshopPage([]))))
    signInAs()
    const { router } = renderRoute('/login')

    await waitFor(() => expect(router.state.location.pathname).toBe('/workshops'))
  })
})

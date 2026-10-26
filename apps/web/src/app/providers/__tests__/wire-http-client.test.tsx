import { screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'

import { currentSession } from '@/features/auth'
import { signInAs } from '@/testing/factories/session'
import { aWorkshop, WORKSHOP_ID } from '@/testing/factories/workshop'
import { server } from '@/testing/msw-server'
import { renderRoute } from '@/testing/render-route'

// A public page that sends the token (getWorkshop shows a DRAFT only to a manager), rendered through
// the real route table, which is wired the way the app is.
const PATH = `/workshops/${WORKSHOP_ID}`

describe('the http client, wired to the session by app/', () => {
  it('sends the session token as a bearer token', async () => {
    let authorization: string | null = null
    server.use(
      http.get('/api/v1/workshops/:id', ({ request }) => {
        authorization = request.headers.get('Authorization')
        return HttpResponse.json(aWorkshop())
      }),
    )
    signInAs()

    renderRoute(PATH)

    expect(await screen.findByRole('heading', { name: aWorkshop().title })).toBeInTheDocument()
    expect(authorization).toBe('Bearer opaque-access-token')
  })

  it('a 401 on a request that carried a token ends the session and drops the cache (AC14.3)', async () => {
    server.use(
      http.get('/api/v1/workshops/:id', ({ request }) =>
        request.headers.has('Authorization')
          ? HttpResponse.json({ type: 'about:blank', title: 'Unauthorized', status: 401 }, {
              status: 401,
              headers: { 'Content-Type': 'application/problem+json' },
            })
          : HttpResponse.json(aWorkshop()),
      ),
    )
    signInAs()

    const { queryClient } = renderRoute(PATH)
    queryClient.setQueryData(['registrations', 'list'], { items: [] })

    await waitFor(() => expect(currentSession()).toBeNull())
    expect(sessionStorage.getItem('unihub.session')).toBeNull()
    expect(queryClient.getQueryData(['registrations', 'list'])).toBeUndefined()
  })

  it('does not send an expired token', async () => {
    let authorization: string | null = 'unset'
    server.use(
      http.get('/api/v1/workshops/:id', ({ request }) => {
        authorization = request.headers.get('Authorization')
        return HttpResponse.json(aWorkshop())
      }),
    )
    signInAs({ expiresAt: Date.now() - 1000 })

    renderRoute(PATH)

    expect(await screen.findByRole('heading', { name: aWorkshop().title })).toBeInTheDocument()
    expect(authorization).toBeNull()
  })
})

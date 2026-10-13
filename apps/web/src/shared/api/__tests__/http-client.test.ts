import { http, HttpResponse } from 'msw'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { server } from '@/testing/msw-server'
import { AppError } from '../errors'
import { configureHttpClient, request } from '../http-client'

const noSession = { getAccessToken: () => null, onUnauthorized: () => {} }

describe('request', () => {
  afterEach(() => configureHttpClient(noSession))

  it('returns the parsed JSON body of a 2xx response', async () => {
    server.use(http.get('/api/v1/ping', () => HttpResponse.json({ ok: true })))

    await expect(request('/api/v1/ping')).resolves.toEqual({ ok: true })
  })

  it('sends the bearer token only when a session exists', async () => {
    const seen: (string | null)[] = []
    server.use(
      http.get('/api/v1/ping', ({ request: req }) => {
        seen.push(req.headers.get('Authorization'))
        return HttpResponse.json({})
      }),
    )

    await request('/api/v1/ping')
    configureHttpClient({ ...noSession, getAccessToken: () => 'token-123' })
    await request('/api/v1/ping')

    expect(seen).toEqual([null, 'Bearer token-123'])
  })

  it('turns a problem+json response into an AppError carrying the problem and Retry-After', async () => {
    server.use(
      http.get('/api/v1/ping', () =>
        HttpResponse.json(
          { type: 'about:blank', title: 'Too Many Requests', status: 429, correlationId: 'abc' },
          { status: 429, headers: { 'Content-Type': 'application/problem+json', 'Retry-After': '12' } },
        ),
      ),
    )

    const error = await request('/api/v1/ping').catch((e: unknown) => e)

    expect(error).toBeInstanceOf(AppError)
    expect(error).toMatchObject({ status: 429, retryAfterSeconds: 12, problem: { correlationId: 'abc' } })
  })

  it('turns a network failure into an AppError with a null status', async () => {
    server.use(http.get('/api/v1/ping', () => HttpResponse.error()))

    await expect(request('/api/v1/ping')).rejects.toMatchObject({ status: null, problem: null })
  })

  it('reports a 401 as session end only when the request carried a token', async () => {
    const onUnauthorized = vi.fn()
    server.use(http.get('/api/v1/ping', () => new HttpResponse(null, { status: 401 })))

    configureHttpClient({ getAccessToken: () => null, onUnauthorized })
    await request('/api/v1/ping').catch(() => {})
    expect(onUnauthorized).not.toHaveBeenCalled()

    configureHttpClient({ getAccessToken: () => 'token-123', onUnauthorized })
    await request('/api/v1/ping').catch(() => {})
    expect(onUnauthorized).toHaveBeenCalledOnce()
  })
})

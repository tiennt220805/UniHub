import { connect } from 'node:tls'

import { expect, test } from '@playwright/test'

// M-67 and the Content-Security-Policy header (Phase 7 task 9.4; AC85.1, AC97.1, AC97.2): what travels between a
// client and the cluster. These probes use no browser, so they run once (project `transport`), not once per engine.
const host = new URL(process.env.E2E_BASE_URL ?? 'https://unihub.local:8443')
const httpBase = process.env.E2E_HTTP_URL ?? 'http://unihub.local:8080'

type Handshake = { authorized: boolean; protocol: string | null; error?: NodeJS.ErrnoException }

function handshake(options: { minVersion: 'TLSv1' | 'TLSv1.2'; maxVersion: 'TLSv1.1' | 'TLSv1.3' }): Promise<Handshake> {
  return new Promise((resolve) => {
    const socket = connect({
      host: '127.0.0.1',
      port: Number(host.port || 443),
      servername: host.hostname,
      ...options,
      // Security level 0 only so that THIS client is willing to offer TLS 1.1 at all: the refusal must come from
      // the gateway, not from this client declining to try.
      ciphers: 'DEFAULT:@SECLEVEL=0',
    })
    socket.once('secureConnect', () => {
      const result = { authorized: socket.authorized, protocol: socket.getProtocol() }
      socket.destroy()
      resolve(result)
    })
    socket.once('error', (error: NodeJS.ErrnoException) => {
      resolve({ authorized: false, protocol: null, error })
    })
  })
}

test('the gateway presents a certificate that the local CA signed, over TLS 1.2 or newer', async () => {
  const result = await handshake({ minVersion: 'TLSv1.2', maxVersion: 'TLSv1.3' })
  expect(result.error?.message).toBeUndefined()
  expect(result.authorized, 'certificate verified against the local CA').toBe(true)
  expect(['TLSv1.2', 'TLSv1.3']).toContain(result.protocol)
})

test('the gateway refuses a handshake that offers only TLS 1.1 or older', async () => {
  const result = await handshake({ minVersion: 'TLSv1', maxVersion: 'TLSv1.1' })
  expect(result.protocol).toBeNull()
  // The refusal must be the server's alert, not a client that would not start (that would pass for nothing).
  expect(result.error?.code, `the error was: ${result.error?.message}`).toMatch(/ALERT|WRONG_VERSION|UNSUPPORTED_PROTOCOL|ECONNRESET/)
  expect(result.error?.code).not.toBe('ERR_SSL_NO_PROTOCOLS_AVAILABLE')
})

test('plain HTTP never reaches the API: it is redirected to HTTPS and no JSON comes back', async ({ playwright }) => {
  const http = await playwright.request.newContext({ baseURL: httpBase, maxRedirects: 0 })
  try {
    for (const [method, path] of [
      ['GET', '/api/v1/workshops'],
      ['POST', '/api/v1/auth/login'],
    ] as const) {
      const response = await http.fetch(path, {
        method,
        ...(method === 'POST' ? { data: { email: 'x@x.test', password: 'not-a-password' } } : {}),
      })
      expect([301, 308], `${method} ${path} over HTTP`).toContain(response.status())
      expect(response.headers().location).toMatch(/^https:\/\//)
      expect(response.headers()['content-type'] ?? '').not.toContain('json')
    }
  } finally {
    await http.dispose()
  }
})

test('the Web is served with a Content-Security-Policy header that forbids framing', async ({ request }) => {
  for (const path of ['/', '/workshops', '/me/registrations']) {
    const response = await request.get(path)
    expect(response.status(), path).toBe(200)
    const policy = response.headers()['content-security-policy']
    expect(policy, `Content-Security-Policy on ${path}`).toBeDefined()
    expect(policy).toContain("frame-ancestors 'none'")
  }
})

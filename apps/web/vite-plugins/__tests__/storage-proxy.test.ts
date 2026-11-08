import type { IncomingMessage, ServerResponse } from 'node:http'
import { describe, expect, it } from 'vitest'
import {
  BUCKET,
  COVER_HEADERS,
  COVER_URL_PREFIX,
  coverMethods,
  coverPathToBucket,
  storageProxy,
  uploadMethods,
} from '../storage-proxy.ts'

function reply() {
  const sent: { status?: number; headers: Record<string, string>; ended: boolean } = { headers: {}, ended: false }
  const res = {
    set statusCode(v: number) {
      sent.status = v
    },
    setHeader(name: string, value: string) {
      sent.headers[name] = value
    },
    end() {
      sent.ended = true
    },
  } as unknown as ServerResponse
  return { res, sent }
}

const request = (method: string) => ({ method }) as IncomingMessage

describe('the development proxy to object storage', () => {
  const proxy = storageProxy('http://127.0.0.1:9000')

  it('serves exactly two paths, and /api is not one of them', () => {
    expect(Object.keys(proxy).sort()).toEqual([COVER_URL_PREFIX, `/${BUCKET}/`].sort())
  })

  it('maps a cover path onto the bucket prefix the anonymous policy opens, and nothing else', () => {
    expect(coverPathToBucket('/media/covers/w1/a.png')).toBe('/unihub-brochures/covers/w1/a.png')
    // The proxy key only matches /media/covers/, so a path like /media/brochures/x never gets here.
    expect(COVER_URL_PREFIX).toBe('/media/covers/')
  })

  it('keeps the Host and the path of an upload: a presigned URL signs both', () => {
    for (const route of Object.values(proxy)) {
      expect(route.changeOrigin).toBe(false)
    }
    expect(proxy[`/${BUCKET}/`].rewrite).toBeUndefined()
    expect(proxy[`/${BUCKET}/`].target).toBe('http://127.0.0.1:9000')
  })

  it('reads covers with GET and HEAD only; any other method is a 405 that is not forwarded', () => {
    for (const method of ['GET', 'HEAD']) {
      const { res, sent } = reply()
      expect(coverMethods(request(method), res)).toBeUndefined()
      expect(sent.ended).toBe(false)
    }
    for (const method of ['PUT', 'POST', 'DELETE']) {
      const { res, sent } = reply()
      expect(coverMethods(request(method), res)).toBe(false)
      expect(sent.status).toBe(405)
      expect(sent.headers.Allow).toBe('GET, HEAD')
    }
  })

  it('takes uploads with PUT only: the bucket is never read through this path', () => {
    const { res, sent } = reply()
    expect(uploadMethods(request('PUT'), res)).toBeUndefined()
    expect(sent.ended).toBe(false)
    for (const method of ['GET', 'HEAD', 'DELETE', 'POST']) {
      const { res: denied, sent: answer } = reply()
      expect(uploadMethods(request(method), denied)).toBe(false)
      expect(answer.status).toBe(405)
    }
  })

  it('stamps a cover response with nosniff and an immutable year, as the edge does in the cluster', () => {
    const headers: Record<string, unknown> = {}
    const handlers: Record<string, (r: { headers: Record<string, unknown> }) => void> = {}
    proxy[COVER_URL_PREFIX].configure?.({ on: (event: string, h: never) => (handlers[event] = h) } as never, {} as never)

    handlers.proxyRes({ headers })

    expect(headers).toMatchObject(COVER_HEADERS)
    expect(headers['x-content-type-options']).toBe('nosniff')
    expect(String(headers['cache-control'])).toContain('immutable')
  })
})

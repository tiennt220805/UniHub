import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { PutError, putWithProgress } from '../put-with-progress'

// A stand-in for XMLHttpRequest that the test drives by hand, so progress and abort can be seen (jsdom reports neither).
class FakeXhr {
  static last: FakeXhr
  method = ''
  url = ''
  headers: Record<string, string> = {}
  body: unknown = null
  status = 0
  aborted = false
  upload: { onprogress: ((event: { lengthComputable: boolean; loaded: number; total: number }) => void) | null } = { onprogress: null }
  onload: (() => void) | null = null
  onerror: (() => void) | null = null
  ontimeout: (() => void) | null = null
  onabort: (() => void) | null = null
  constructor() {
    FakeXhr.last = this
  }
  open(method: string, url: string) {
    this.method = method
    this.url = url
  }
  setRequestHeader(name: string, value: string) {
    this.headers[name] = value
  }
  send(body: unknown) {
    this.body = body
  }
  abort() {
    this.aborted = true
    this.onabort?.()
  }
}

beforeEach(() => vi.stubGlobal('XMLHttpRequest', FakeXhr))
afterEach(() => vi.unstubAllGlobals())

const file = new Blob(['bytes'], { type: 'image/png' })

describe('putWithProgress', () => {
  it('PUTs the file to the URL exactly as issued, with only the Content-Type it was signed for', async () => {
    const done = putWithProgress({ url: 'https://unihub.local/unihub-brochures/covers/w/1.png?X-Amz-Signature=abc', body: file, contentType: 'image/png' })
    const xhr = FakeXhr.last
    expect(xhr.method).toBe('PUT')
    expect(xhr.url).toBe('https://unihub.local/unihub-brochures/covers/w/1.png?X-Amz-Signature=abc')
    expect(xhr.headers).toEqual({ 'Content-Type': 'image/png' }) // no Authorization, no Idempotency-Key
    expect(xhr.body).toBe(file)
    xhr.status = 200
    xhr.onload?.()
    await expect(done).resolves.toBeUndefined()
  })

  it('reports how much has gone, as a fraction, and 1 when it is done', async () => {
    const seen: number[] = []
    const done = putWithProgress({ url: 'https://s/x', body: file, contentType: 'image/png', onProgress: (f) => seen.push(f) })
    const xhr = FakeXhr.last
    xhr.upload.onprogress?.({ lengthComputable: true, loaded: 25, total: 100 })
    xhr.upload.onprogress?.({ lengthComputable: false, loaded: 50, total: 0 }) // unknown length: ignored
    xhr.upload.onprogress?.({ lengthComputable: true, loaded: 100, total: 100 })
    xhr.status = 200
    xhr.onload?.()
    await done
    expect(seen).toEqual([0.25, 1, 1])
  })

  it('rejects with the storage status when it answers something other than success', async () => {
    const done = putWithProgress({ url: 'https://s/x', body: file, contentType: 'image/png' })
    FakeXhr.last.status = 403
    FakeXhr.last.onload?.()
    await expect(done).rejects.toMatchObject({ failure: { kind: 'rejected', status: 403 } })
  })

  it('rejects as a network failure when there is no answer', async () => {
    const done = putWithProgress({ url: 'https://s/x', body: file, contentType: 'image/png' })
    FakeXhr.last.onerror?.()
    await expect(done).rejects.toBeInstanceOf(PutError)
    await expect(done).rejects.toMatchObject({ failure: { kind: 'network' } })
  })

  it('stops when the signal aborts, and never starts when it already has', async () => {
    const controller = new AbortController()
    const done = putWithProgress({ url: 'https://s/x', body: file, contentType: 'image/png', signal: controller.signal })
    controller.abort()
    expect(FakeXhr.last.aborted).toBe(true)
    await expect(done).rejects.toMatchObject({ failure: { kind: 'aborted' } })

    const before = FakeXhr.last
    await expect(putWithProgress({ url: 'https://s/x', body: file, contentType: 'image/png', signal: controller.signal })).rejects.toMatchObject({
      failure: { kind: 'aborted' },
    })
    expect(FakeXhr.last).toBe(before) // no second request was made
  })
})

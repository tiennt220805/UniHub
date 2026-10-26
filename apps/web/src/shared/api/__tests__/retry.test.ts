import { describe, expect, it } from 'vitest'

import { AppError } from '../errors'
import { MAX_AUTOMATIC_RETRIES, retryDelayMs, shouldRetryAutomatically } from '../retry'

const error = (status: number | null, retryAfter: number | null = null) =>
  new AppError(status, null, retryAfter)

describe('shouldRetryAutomatically', () => {
  it('retries a network failure and a 503', () => {
    expect(shouldRetryAutomatically(0, error(null))).toBe(true)
    expect(shouldRetryAutomatically(0, error(503))).toBe(true)
  })

  it.each([400, 401, 403, 404, 409, 422, 429, 500])('never retries a %s by itself', (status) => {
    expect(shouldRetryAutomatically(0, error(status))).toBe(false)
  })

  it('stops after three retries (AC15.3)', () => {
    expect(MAX_AUTOMATIC_RETRIES).toBe(3)
    expect(shouldRetryAutomatically(0, error(503))).toBe(true)
    expect(shouldRetryAutomatically(1, error(503))).toBe(true)
    expect(shouldRetryAutomatically(2, error(503))).toBe(true)
    expect(shouldRetryAutomatically(3, error(503))).toBe(false)
  })
})

describe('retryDelayMs', () => {
  const noJitter = () => 0
  const fullJitter = () => 0.999

  it('backs off 1 s, 2 s, 4 s without a Retry-After', () => {
    expect([0, 1, 2].map((n) => retryDelayMs(n, error(503), noJitter))).toEqual([1000, 2000, 4000])
  })

  it('waits at least as long as the server asked', () => {
    expect(retryDelayMs(0, error(503, 30), noJitter)).toBe(30_000)
  })

  it('does not wait less than its own backoff when the server asks for less', () => {
    expect(retryDelayMs(2, error(503, 1), noJitter)).toBe(4000)
  })

  it('adds under a second of jitter', () => {
    expect(retryDelayMs(0, error(503), fullJitter)).toBeGreaterThanOrEqual(1000)
    expect(retryDelayMs(0, error(503), fullJitter)).toBeLessThan(2000)
  })
})

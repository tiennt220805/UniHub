import { describe, expect, it } from 'vitest'

import { AppError, isTransient, problemSlug, toAppError } from '../errors'

const problemError = (type: string | undefined, status = 409) =>
  new AppError(
    status,
    type === undefined ? null : { type, title: 'Problem', status },
    null,
  )

describe('problemSlug', () => {
  it('reads the slug of a urn:unihub:problem type', () => {
    expect(problemSlug(problemError('urn:unihub:problem:sold-out'))).toBe('sold-out')
    expect(problemSlug(problemError('urn:unihub:problem:idempotency-key-in-use'))).toBe(
      'idempotency-key-in-use',
    )
  })

  it('is null for about:blank, a missing body and a URI of another scheme', () => {
    expect(problemSlug(problemError('about:blank'))).toBeNull()
    expect(problemSlug(problemError(undefined))).toBeNull()
    expect(problemSlug(problemError('https://example.com/problems/sold-out'))).toBeNull()
  })

  it('returns a slug it does not know as it is, so callers can fall back to the status (AC8.3)', () => {
    expect(problemSlug(problemError('urn:unihub:problem:something-added-later'))).toBe(
      'something-added-later',
    )
  })
})

describe('toAppError', () => {
  it('keeps the problem type of a problem+json body', async () => {
    const response = new Response(
      JSON.stringify({ type: 'urn:unihub:problem:sold-out', title: 'Sold out', status: 409 }),
      { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
    )

    const error = await toAppError(response)

    expect(error.status).toBe(409)
    expect(problemSlug(error)).toBe('sold-out')
  })

  it('has no problem when the body is not problem+json', async () => {
    const error = await toAppError(new Response('<html>', { status: 502 }))

    expect(error.problem).toBeNull()
    expect(problemSlug(error)).toBeNull()
  })
})

describe('isTransient', () => {
  it.each([
    [null, true],
    [429, true],
    [503, true],
    [409, false],
    [422, false],
    [500, false],
  ])('status %s is transient: %s', (status, expected) => {
    expect(isTransient(new AppError(status, null, null))).toBe(expected)
  })
})

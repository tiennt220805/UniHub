import { describe, expect, it } from 'vitest'

import { safeReturnPath } from '../return-path'

describe('safeReturnPath', () => {
  it('returns the page the guard bounced the user from, with its query string', () => {
    expect(safeReturnPath({ from: { pathname: '/me/registrations', search: '?page=2' } })).toBe(
      '/me/registrations?page=2',
    )
  })

  it.each([
    ['nothing at all', undefined],
    ['no from', {}],
    ['an absolute URL', { from: { pathname: 'https://evil.test/' } }],
    ['a protocol-relative URL', { from: { pathname: '//evil.test/' } }],
    ['a backslash trick', { from: { pathname: '/\\evil.test' } }],
    ['the sign-in page itself', { from: { pathname: '/login' } }],
    ['a non-string path', { from: { pathname: 42 } }],
  ])('falls back to the catalog for %s', (_, state) => {
    expect(safeReturnPath(state)).toBe('/workshops')
  })
})

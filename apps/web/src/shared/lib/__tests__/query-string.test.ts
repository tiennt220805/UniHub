import { describe, expect, it } from 'vitest'

import { toQueryString } from '../query-string'

describe('toQueryString', () => {
  it('is empty when no value is set', () => {
    expect(toQueryString({})).toBe('')
    expect(toQueryString({ q: undefined, speaker: null, location: '' })).toBe('')
  })

  it('encodes the set values and skips the rest', () => {
    expect(toQueryString({ q: 'redis & locks', page: 0, speaker: undefined })).toBe('?q=redis+%26+locks&page=0')
  })
})

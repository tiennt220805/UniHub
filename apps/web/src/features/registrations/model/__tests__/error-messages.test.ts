import { describe, expect, it } from 'vitest'

import { AppError } from '@/shared/api/errors'
import { registrationErrorMessage } from '../error-messages'

const error = (status: number | null, slug?: string, type?: string, retryAfter: number | null = null) =>
  new AppError(
    status,
    status === null
      ? null
      : { type: type ?? (slug ? `urn:unihub:problem:${slug}` : 'about:blank'), title: 'Problem', status },
    retryAfter,
  )

describe('registrationErrorMessage', () => {
  it.each([
    ['sold-out', 'Workshop đã hết chỗ.'],
    ['already-registered', 'Bạn đã đăng ký workshop này rồi.'],
    ['registration-not-open', 'Workshop này hiện không mở đăng ký.'],
  ])('gives each 409 reason its own message: %s', (slug, message) => {
    expect(registrationErrorMessage(error(409, slug))).toBe(message)
  })

  it('tells the user a key that is still being processed will settle', () => {
    expect(registrationErrorMessage(error(409, 'idempotency-key-in-use'))).toContain('đang được xử lý')
  })

  it('treats a reused key (422) as a client bug, not a retry case', () => {
    expect(registrationErrorMessage(error(422, 'idempotency-key-reused'))).toContain('tải lại trang')
  })

  it('falls back to the HTTP status for a problem type it does not know (AC8.3)', () => {
    const unknown = error(409, 'a-reason-added-after-this-client-shipped')

    expect(registrationErrorMessage(unknown)).toBe('Không thể đăng ký workshop ở trạng thái hiện tại.')
  })

  it('falls back to the HTTP status for a type that is not even a UniHub URN', () => {
    expect(registrationErrorMessage(error(503, undefined, 'https://other.example/x'))).toBe(
      'Hệ thống đang bận. Vui lòng thử lại sau ít phút.',
    )
  })

  it.each([
    [null, 'Mất kết nối'],
    [403, 'không có quyền'],
    [404, 'Không tìm thấy workshop'],
    [429, 'quá nhanh'],
    [503, 'Hệ thống đang bận'],
    [500, 'Không đăng ký được'],
  ])('status %s has its own message', (status, fragment) => {
    expect(registrationErrorMessage(error(status))).toContain(fragment)
  })

  it('says how long to wait on a 429', () => {
    expect(registrationErrorMessage(error(429, undefined, undefined, 20))).toContain('sau 20 giây')
  })
})

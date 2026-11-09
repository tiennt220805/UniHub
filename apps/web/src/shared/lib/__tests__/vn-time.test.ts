import { describe, expect, it } from 'vitest'

import { formatPrice } from '../format'
import { addDays, clock, dayBadge, dayHeading, endOfDay, endOfWeek, isYmd, startOfDay, ymdOf } from '../vn-time'

describe('vn-time', () => {
  it('reads the calendar day in Vietnam, not in UTC', () => {
    // 18:00Z on the 10th is already 01:00 on the 11th in Vietnam.
    expect(ymdOf('2026-11-10T18:00:00Z')).toBe('2026-11-11')
    expect(ymdOf('2026-11-10T16:59:59Z')).toBe('2026-11-10')
  })

  it('starts a day at 00:00 and ends it at 23:59:59.999 Vietnam time, as UTC instants', () => {
    expect(startOfDay('2026-11-09')).toBe('2026-11-08T17:00:00.000Z')
    expect(endOfDay('2026-11-09')).toBe('2026-11-09T16:59:59.999Z')
  })

  it('gives the same start for any moment of the same day, so the request (and its cache key) does not change', () => {
    expect(startOfDay(ymdOf('2026-11-09T01:00:00Z'))).toBe(startOfDay(ymdOf('2026-11-09T15:30:12Z')))
  })

  it.each([
    ['2026-11-09', '2026-11-15'], // Monday → that Sunday
    ['2026-11-11', '2026-11-15'], // Wednesday
    ['2026-11-15', '2026-11-15'], // Sunday is its own week's end
    ['2026-12-28', '2027-01-03'], // across the year
  ])('ends the week of %s on %s', (day, sunday) => {
    expect(endOfWeek(day)).toBe(sunday)
  })

  it('adds days across month ends', () => {
    expect(addDays('2026-11-30', 1)).toBe('2026-12-01')
    expect(addDays('2026-03-01', -1)).toBe('2026-02-28')
  })

  it.each([
    ['2026-11-09', true],
    ['2026-02-30', false],
    ['2026-13-01', false],
    ['09/11/2026', false],
    ['', false],
  ])('validates %s as a date: %s', (value, ok) => {
    expect(isYmd(value)).toBe(ok)
  })

  it('writes a day heading with the Vietnamese weekday', () => {
    expect(dayHeading('2026-11-11')).toBe('Thứ Tư, 11/11/2026')
    expect(dayHeading('2026-11-15')).toBe('Chủ Nhật, 15/11/2026')
  })

  it('writes the clock and the card badge in Vietnam time', () => {
    expect(clock('2026-11-11T02:00:00Z')).toBe('09:00')
    expect(dayBadge('2026-11-11T02:00:00Z')).toEqual({ day: '11', month: 'Thg 11' })
    expect(dayBadge('2026-11-01T17:30:00Z')).toEqual({ day: '2', month: 'Thg 11' })
  })
})

describe('formatPrice', () => {
  it('writes the number with the suffix "đ" (D146)', () => {
    expect(formatPrice(150000)).toBe('150.000 đ')
    expect(formatPrice(99500)).toBe('99.500 đ')
  })
})

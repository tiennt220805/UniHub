import { describe, expect, it } from 'vitest'

import { coverArt } from '../cover-art'
import { groupByDay } from '../day-groups'
import { seatFill, seatLevel } from '../seat-level'

describe('seat level (D118)', () => {
  it.each([
    [60, 0, 'ok'],
    [60, 47, 'ok'], // 13 left is 21.7 %
    [60, 48, 'ok'], // 12 left is exactly 20 %: not under it
    [60, 49, 'low'],
    [60, 59, 'low'],
    [60, 60, 'full'],
    [60, 75, 'full'], // an over-count never reads as "plenty"
    [0, 0, 'full'],
  ])('capacity %i with %i taken is %s', (capacity, taken, level) => {
    expect(seatLevel(capacity, taken)).toBe(level)
  })

  it('is low only when strictly under 20 % is left', () => {
    expect(seatLevel(50, 40)).toBe('ok') // 10 left: exactly 20 %
    expect(seatLevel(50, 41)).toBe('low') // 9 left
  })

  it('fills the bar from 0 to 1 and never beyond', () => {
    expect(seatFill(60, 30)).toBe(0.5)
    expect(seatFill(60, 90)).toBe(1)
    expect(seatFill(0, 0)).toBe(1)
  })
})

describe('cover art (D144)', () => {
  const id = '3f2b8c1e-6d4a-4a57-9b0e-1c2d3e4f5a6b'

  it('is the same for the same id', () => {
    expect(coverArt(id)).toEqual(coverArt(id))
  })

  it('differs across ids, and uses every family over enough of them', () => {
    const ids = Array.from({ length: 60 }, (_, i) => `00000000-0000-4000-8000-${String(i).padStart(12, '0')}`)
    const families = new Set(ids.map((x) => coverArt(x).family))
    expect(families).toEqual(new Set(['sun', 'bars', 'tiles']))
    expect(new Set(ids.map((x) => coverArt(x).background)).size).toBeGreaterThan(2)
  })

  it('keeps its numbers in range', () => {
    const art = coverArt(id)
    expect(art.x).toBeGreaterThanOrEqual(0)
    expect(art.x).toBeLessThanOrEqual(1)
    expect(Math.abs(art.turn)).toBeLessThanOrEqual(20)
  })
})

describe('day groups (D140)', () => {
  it('splits a sorted list into one group per day in Vietnam', () => {
    const groups = groupByDay([
      { startTime: '2026-11-11T02:00:00Z', n: 1 },
      { startTime: '2026-11-11T07:00:00Z', n: 2 },
      // 18:30Z is 01:30 the next morning in Vietnam: a different day from the two above.
      { startTime: '2026-11-11T18:30:00Z', n: 3 },
    ])
    expect(groups.map((g) => [g.day, g.heading, g.items.map((i) => i.n)])).toEqual([
      ['2026-11-11', 'Thứ Tư, 11/11/2026', [1, 2]],
      ['2026-11-12', 'Thứ Năm, 12/11/2026', [3]],
    ])
  })

  it('has no groups for an empty list', () => {
    expect(groupByDay([])).toEqual([])
  })
})

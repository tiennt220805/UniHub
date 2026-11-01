import { describe, expect, it } from 'vitest'

import { formatPercent } from '@/shared/lib/format'
import { describeCheckInRate } from '../check-in-rate'

describe('describeCheckInRate', () => {
  it('null is "no data", never 0%', () => {
    expect(describeCheckInRate(null, formatPercent)).toEqual({ kind: 'none' })
  })

  it('0 is a real 0%: nobody of the confirmed showed up', () => {
    expect(describeCheckInRate(0, formatPercent)).toEqual({
      kind: 'rate',
      label: '0%',
      barPercent: 0,
      exceedsAll: false,
    })
  })

  it('2/3 is 67% with a bar at two thirds', () => {
    const view = describeCheckInRate(2 / 3, formatPercent)

    expect(view).toMatchObject({ kind: 'rate', label: '67%', exceedsAll: false })
    expect(view.kind === 'rate' && view.barPercent).toBeCloseTo(66.67, 1)
  })

  it('exactly 1 fills the bar and is not "more than all"', () => {
    expect(describeCheckInRate(1, formatPercent)).toMatchObject({
      label: '100%',
      barPercent: 100,
      exceedsAll: false,
    })
  })

  it('above 1 caps the bar but keeps the real number in the text', () => {
    expect(describeCheckInRate(1.25, formatPercent)).toEqual({
      kind: 'rate',
      label: '125%',
      barPercent: 100,
      exceedsAll: true,
    })
  })
})

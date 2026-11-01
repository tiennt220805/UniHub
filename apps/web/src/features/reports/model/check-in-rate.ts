export type CheckInRateView =
  | { kind: 'none' }
  | { kind: 'rate'; label: string; barPercent: number; exceedsAll: boolean }

/**
 * How the check-in rate is shown. The server sends a ratio that is `null` when nobody is CONFIRMED
 * (a rate over nothing is undefined, and 0% would claim nobody showed up of nobody) and that is not
 * clamped: it can top 1, because a check-in outlives the cancellation of its registration. The bar
 * is capped at full; the text keeps the real number.
 */
export function describeCheckInRate(
  rate: number | null,
  formatPercent: (ratio: number) => string,
): CheckInRateView {
  if (rate === null) return { kind: 'none' }
  return {
    kind: 'rate',
    label: formatPercent(rate),
    barPercent: Math.min(Math.max(rate, 0), 1) * 100,
    exceedsAll: rate > 1,
  }
}

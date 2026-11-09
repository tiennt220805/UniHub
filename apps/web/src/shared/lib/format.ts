// Pinned to UniHub's operating zone rather than the viewer's browser zone, the same choice
// docs/technical-design/modules/user.md §7 makes for the backend: a workshop's time is a fact about
// the campus, not about wherever the viewer's device happens to be.
const dateTimeFormat = new Intl.DateTimeFormat('vi-VN', {
  timeZone: 'Asia/Ho_Chi_Minh',
  dateStyle: 'medium',
  timeStyle: 'short',
})

const numberFormat = new Intl.NumberFormat('vi-VN')

export function formatDateTime(iso: string): string {
  return dateTimeFormat.format(new Date(iso))
}

export function formatNumber(value: number): string {
  return numberFormat.format(value)
}

/**
 * A workshop's price: the number and the suffix "đ" ("150.000 đ"). The contract does not name the currency of
 * `Workshop.price`; the suffix is the owner's decision (Phase 8 D146, 2026-11-09), made here and nowhere else.
 */
export function formatPrice(value: number): string {
  return `${numberFormat.format(value)} đ`
}

const percentFormat = new Intl.NumberFormat('vi-VN', { style: 'percent', maximumFractionDigits: 0 })

/** A ratio as a whole percent: 0.5 → "50%". Not clamped: 1.25 → "125%". */
export function formatPercent(ratio: number): string {
  return percentFormat.format(ratio)
}

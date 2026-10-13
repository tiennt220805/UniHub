// Pinned to UniHub's operating zone rather than the viewer's browser zone, the same choice
// docs/technical-design/modules/user.md §7 makes for the backend: a workshop's time is a fact about
// the campus, not about wherever the viewer's device happens to be.
const dateTimeFormat = new Intl.DateTimeFormat('vi-VN', {
  timeZone: 'Asia/Ho_Chi_Minh',
  dateStyle: 'medium',
  timeStyle: 'short',
})

// No currency symbol: neither the contract nor docs/technical-design names the currency of
// Workshop.price, so only the number is rendered.
const numberFormat = new Intl.NumberFormat('vi-VN')

export function formatDateTime(iso: string): string {
  return dateTimeFormat.format(new Date(iso))
}

export function formatNumber(value: number): string {
  return numberFormat.format(value)
}

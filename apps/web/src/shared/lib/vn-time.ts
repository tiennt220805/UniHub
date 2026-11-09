// UniHub's operating zone is Asia/Ho_Chi_Minh, UTC+7 all year with no daylight saving (the same choice as format.ts and
// docs/technical-design/modules/user.md §7). A calendar day here is therefore a fixed 24 h window, which lets the day
// arithmetic below be plain string and UTC maths instead of a time-zone library.
//
// A "ymd" is a calendar date as `YYYY-MM-DD`, the value of an `<input type="date">` and of the catalog URL's date filters.

const OFFSET = '+07:00'
const YMD = /^(\d{4})-(\d{2})-(\d{2})$/

const ymdParts = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Ho_Chi_Minh',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
})
const weekdayName = new Intl.DateTimeFormat('vi-VN', { timeZone: 'Asia/Ho_Chi_Minh', weekday: 'long' })
const clockFormat = new Intl.DateTimeFormat('vi-VN', {
  timeZone: 'Asia/Ho_Chi_Minh',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
})

/** The calendar date, in Vietnam, of an instant. */
export function ymdOf(instant: Date | string): string {
  const parts = Object.fromEntries(ymdParts.formatToParts(new Date(instant)).map((p) => [p.type, p.value]))
  return `${parts.year}-${parts.month}-${parts.day}`
}

/** Whether `value` is a real calendar date written as `YYYY-MM-DD` (2026-02-30 is not). */
export function isYmd(value: string): boolean {
  const m = YMD.exec(value)
  if (!m) return false
  const [y, mo, d] = [Number(m[1]), Number(m[2]), Number(m[3])]
  const probe = new Date(Date.UTC(y, mo - 1, d))
  return probe.getUTCFullYear() === y && probe.getUTCMonth() === mo - 1 && probe.getUTCDate() === d
}

/** 00:00:00.000 of that day in Vietnam, as the UTC instant the API's `dateFrom` takes. Stable for the whole day. */
export function startOfDay(ymd: string): string {
  return new Date(`${ymd}T00:00:00.000${OFFSET}`).toISOString()
}

/** 23:59:59.999 of that day in Vietnam, as the UTC instant the API's `dateTo` takes. */
export function endOfDay(ymd: string): string {
  return new Date(`${ymd}T23:59:59.999${OFFSET}`).toISOString()
}

export function addDays(ymd: string, days: number): string {
  const [y, m, d] = ymd.split('-').map(Number)
  return new Date(Date.UTC(y, m - 1, d + days)).toISOString().slice(0, 10)
}

/** The Sunday that ends the week `ymd` is in (Monday first); a Sunday is its own week's end. */
export function endOfWeek(ymd: string): string {
  const [y, m, d] = ymd.split('-').map(Number)
  const dayOfWeek = new Date(Date.UTC(y, m - 1, d)).getUTCDay() // 0 = Sunday
  return addDays(ymd, (7 - dayOfWeek) % 7)
}

/** "Thứ Tư, 11/11/2026" for a ymd. */
export function dayHeading(ymd: string): string {
  const [y, m, d] = ymd.split('-')
  return `${capitalise(weekdayName.format(new Date(startOfDay(ymd))))}, ${d}/${m}/${y}`
}

/** "09:00" for an instant, in Vietnam. */
export function clock(instant: string): string {
  return clockFormat.format(new Date(instant))
}

/** The day number ("11") and the month label ("Thg 11") of the date badge on a card. */
export function dayBadge(instant: string): { day: string; month: string } {
  const [, m, d] = ymdOf(instant).split('-')
  return { day: String(Number(d)), month: `Thg ${Number(m)}` }
}

/** "dd/mm/yyyy" for a ymd, the way a filter is quoted back to the user. */
export function dayShort(ymd: string): string {
  const [y, m, d] = ymd.split('-')
  return `${d}/${m}/${y}`
}

const capitalise = (text: string) => text.charAt(0).toUpperCase() + text.slice(1)

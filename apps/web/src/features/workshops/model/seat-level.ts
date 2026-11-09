// The level the catalog shows instead of a number (D118): the list is cached for 30 s, so an exact count would be
// wrong more often than right. "Sắp hết" is under 20 % of the seats left. Only the detail page shows the live count.

export type SeatLevel = 'ok' | 'low' | 'full'

export const SEAT_LEVEL_LABEL: Record<SeatLevel, string> = {
  ok: 'Còn chỗ',
  low: 'Sắp hết',
  full: 'Hết chỗ',
}

export function seatLevel(capacity: number, seatsTaken: number): SeatLevel {
  const left = capacity - seatsTaken
  if (left <= 0) return 'full'
  return left / capacity < 0.2 ? 'low' : 'ok'
}

/** How full the bar is, from 0 to 1. */
export function seatFill(capacity: number, seatsTaken: number): number {
  if (capacity <= 0) return 1
  return Math.min(Math.max(seatsTaken / capacity, 0), 1)
}

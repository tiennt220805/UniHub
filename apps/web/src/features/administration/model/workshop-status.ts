import type { WorkshopStatus } from '../api/requests'

// What each status is called, and its tone (web-design-system.md §5). The words are new at G1/G4; the icon is chosen by the
// component that draws the badge.
export const WORKSHOP_STATUS: Record<WorkshopStatus, { label: string; tone: 'muted' | 'success' | 'destructive' | 'info' }> = {
  DRAFT: { label: 'Nháp', tone: 'muted' },
  PUBLISHED: { label: 'Đã công bố', tone: 'success' },
  CANCELLED: { label: 'Đã hủy', tone: 'destructive' },
  COMPLETED: { label: 'Đã kết thúc', tone: 'info' },
}

/** A cancelled or completed workshop cannot be edited, published or cancelled any more (the backend answers 409). */
export const isTerminal = (status: WorkshopStatus) => status === 'CANCELLED' || status === 'COMPLETED'

/**
 * "Đã diễn ra": a published workshop whose end has passed (D158). Nothing sets COMPLETED, so this is how a finished one is
 * told apart; it uses the browser clock, which only decides a label, never what the user may do.
 */
export const hasEnded = (status: WorkshopStatus, endTime: string, nowMs: number) =>
  status === 'PUBLISHED' && new Date(endTime).getTime() < nowMs

/** A seat bar turns amber from 80 % full; the numbers say the rest. */
export const isNearlyFull = (capacity: number, seatsTaken: number) =>
  capacity > 0 && seatsTaken < capacity && seatsTaken / capacity >= 0.8

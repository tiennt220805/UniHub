import type { Registration } from '../api/requests'

export type TicketState =
  | { kind: 'ISSUED'; qrPayload: string; ticketCode: string | null }
  /** Confirmed, but no ticket was ever signed for it (a row confirmed before Phase 5). */
  | { kind: 'NOT_ISSUED' }
  /** Still RESERVED: the ticket is signed when the registration is confirmed. */
  | { kind: 'PENDING' }
  | { kind: 'ENDED'; status: 'CANCELLED' | 'EXPIRED' }

/**
 * Whether the page may draw a QR. The decision is the registration's status, never the presence of
 * `qrPayload`: the contract keeps `ticketCode`/`qrPayload` on a row whose status moved on, so a
 * CANCELLED registration can still carry a payload that must not be shown as a usable ticket.
 */
export function ticketStateOf(registration: Registration): TicketState {
  switch (registration.status) {
    case 'RESERVED':
      return { kind: 'PENDING' }
    case 'CANCELLED':
    case 'EXPIRED':
      return { kind: 'ENDED', status: registration.status }
    case 'CONFIRMED':
      return registration.qrPayload
        ? { kind: 'ISSUED', qrPayload: registration.qrPayload, ticketCode: registration.ticketCode ?? null }
        : { kind: 'NOT_ISSUED' }
  }
}

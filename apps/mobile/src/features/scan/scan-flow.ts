import type { VerificationKey } from '@/shared/crypto/verification-key'
import type { Db } from '@/shared/db/types'

import { type RejectionReason, verifyTicket } from '@/shared/ticket'
import {
  enqueueScan,
  lastScanOfTicket,
  RAW_PREVIEW_LENGTH,
  type ScanWarning,
} from '@/shared/db/scan-queue'

export type ScanContext = {
  db: Db
  /** The cached verification key; null means the device has never fetched one and must not scan. */
  key: VerificationKey | null
  deviceId: string
  staffUserId: string
  /** The workshop of the room the staff member picked: the scan is recorded for this one. */
  roomWorkshopId: string
  now: () => Date
  newId: () => string
}

export type ScanOutcome =
  /** Signature correct. Whether the registration still counts is decided by the server later. */
  | {
      kind: 'VALID_PENDING'
      ticketCode: string
      warnings: ScanWarning[]
      previousScanAt: string | null
    }
  | { kind: 'REJECTED'; reason: RejectionReason }
  /** No key cached: nothing was scanned and nothing was recorded. */
  | { kind: 'NO_KEY' }
  /** The scan could not be written. It is not shown as valid, because it would not be sent. */
  | { kind: 'WRITE_FAILED' }

const previewOf = (raw: unknown): string =>
  typeof raw === 'string' ? raw.slice(0, RAW_PREVIEW_LENGTH) : `[${typeof raw}]`

/**
 * One scan, end to end: verify offline, write the row to the durable queue, and only then say what
 * happened. A device with no key records nothing and says so; a write that fails is never "valid".
 * Scanning the same ticket again on this device warns, and is still recorded: the server needs it to
 * decide which scan came first (`checkin.md` §7).
 */
export function processScan(context: ScanContext, raw: unknown): ScanOutcome {
  const { db, key, deviceId, staffUserId, roomWorkshopId } = context
  if (key === null) return { kind: 'NO_KEY' }

  const verdict = verifyTicket(raw, key)
  const base = {
    id: context.newId(),
    workshopId: roomWorkshopId,
    scannedAt: context.now().toISOString(),
    deviceId,
    staffUserId,
  }

  if (!verdict.ok) {
    try {
      enqueueScan(db, {
        ...base,
        deviceVerdict: 'REJECTED_SIGNATURE',
        rejectionReason: verdict.reason,
        rawPreview: previewOf(raw),
        warning: null,
      })
    } catch {
      return { kind: 'WRITE_FAILED' }
    }
    return { kind: 'REJECTED', reason: verdict.reason }
  }

  const { ticketCode, workshopId: ticketWorkshopId } = verdict.ticket
  const warnings: ScanWarning[] = []
  let previousScanAt: string | null = null
  try {
    const previous = lastScanOfTicket(db, ticketCode)
    if (previous) {
      warnings.push('REPEAT')
      previousScanAt = previous.scannedAt
    }
    if (ticketWorkshopId !== roomWorkshopId.toLowerCase()) warnings.push('WRONG_ROOM')
    enqueueScan(db, {
      ...base,
      deviceVerdict: 'VERIFIED',
      ticketCode,
      ticketWorkshopId,
      // A column holds one warning for the history; the screen shows all of them.
      warning: warnings.includes('WRONG_ROOM') ? 'WRONG_ROOM' : (warnings[0] ?? null),
    })
  } catch {
    return { kind: 'WRITE_FAILED' }
  }
  return { kind: 'VALID_PENDING', ticketCode, warnings, previousScanAt }
}

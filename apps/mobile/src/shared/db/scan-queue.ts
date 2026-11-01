import { and, asc, desc, eq, inArray, ne, sql } from 'drizzle-orm'

import { scans } from './schema'
import type { Db } from './types'

export type DeviceVerdict = 'VERIFIED' | 'REJECTED_SIGNATURE'
export type ScanWarning = 'WRONG_ROOM' | 'REPEAT'
export type StoredScan = typeof scans.$inferSelect

/** The most a rejected scan keeps of what was scanned, for display only (`checkin.md` §6). */
export const RAW_PREVIEW_LENGTH = 64

/** What the sync request's batch size allows (`syncScanBatch`: 1…500). */
export const MAX_BATCH = 500

export type NewScan = {
  id: string
  workshopId: string
  scannedAt: string
  deviceId: string
  staffUserId: string
  warning: ScanWarning | null
} & (
  | { deviceVerdict: 'VERIFIED'; ticketCode: string; ticketWorkshopId: string }
  | { deviceVerdict: 'REJECTED_SIGNATURE'; rejectionReason: string; rawPreview: string }
)

/** Appends a scan to the durable queue. Throws if the row cannot be written; nothing is shown before it returns. */
export function enqueueScan(db: Db, scan: NewScan): void {
  db.insert(scans)
    .values({
      id: scan.id,
      deviceVerdict: scan.deviceVerdict,
      ticketCode: scan.deviceVerdict === 'VERIFIED' ? scan.ticketCode : null,
      ticketWorkshopId: scan.deviceVerdict === 'VERIFIED' ? scan.ticketWorkshopId : null,
      workshopId: scan.workshopId,
      rejectionReason: scan.deviceVerdict === 'REJECTED_SIGNATURE' ? scan.rejectionReason : null,
      rawPreview:
        scan.deviceVerdict === 'REJECTED_SIGNATURE'
          ? scan.rawPreview.slice(0, RAW_PREVIEW_LENGTH)
          : null,
      scannedAt: scan.scannedAt,
      deviceId: scan.deviceId,
      staffUserId: scan.staffUserId,
      warning: scan.warning,
    })
    .run()
}

/** When this device last scanned this ticket code, if it did — the "already scanned at hh:mm" warning. */
export function lastScanOfTicket(db: Db, ticketCode: string): StoredScan | undefined {
  return db
    .select()
    .from(scans)
    .where(eq(scans.ticketCode, ticketCode))
    .orderBy(desc(scans.seq))
    .limit(1)
    .all()[0]
}

/** One staff member's queued scans, oldest first, at most one batch. */
export function pendingBatch(db: Db, staffUserId: string, limit = MAX_BATCH): StoredScan[] {
  return db
    .select()
    .from(scans)
    .where(and(eq(scans.syncState, 'PENDING'), eq(scans.staffUserId, staffUserId)))
    .orderBy(asc(scans.seq))
    .limit(Math.min(limit, MAX_BATCH))
    .all()
}

export function countPending(db: Db, staffUserId: string): number {
  return (
    db
      .select({ count: sql<number>`count(*)` })
      .from(scans)
      .where(and(eq(scans.syncState, 'PENDING'), eq(scans.staffUserId, staffUserId)))
      .all()[0]?.count ?? 0
  )
}

/** Scans waiting for someone else to sign in and send them (D53). */
export function countPendingOfOthers(db: Db, staffUserId: string): number {
  return (
    db
      .select({ count: sql<number>`count(*)` })
      .from(scans)
      .where(and(eq(scans.syncState, 'PENDING'), ne(scans.staffUserId, staffUserId)))
      .all()[0]?.count ?? 0
  )
}

/**
 * Leaves the queue for exactly the scans the server answered for, by `id`, storing the outcome it
 * decided. A scan the response does not name stays pending. Returns how many left the queue.
 */
export function markSynced(
  db: Db,
  results: readonly { id: string; outcome: string }[],
  now: string,
): number {
  if (results.length === 0) return 0
  return db.transaction((tx) => {
    let left = 0
    for (const result of results) {
      left += tx
        .update(scans)
        .set({ syncState: 'SYNCED', serverOutcome: result.outcome, syncedAt: now })
        .where(and(eq(scans.id, result.id), eq(scans.syncState, 'PENDING')))
        .run().changes
    }
    return left
  })
}

/** The newest scans first, for the history screen. */
export function recentScans(db: Db, limit = 200): StoredScan[] {
  return db.select().from(scans).orderBy(desc(scans.seq)).limit(limit).all()
}

export function scansByIds(db: Db, ids: readonly string[]): StoredScan[] {
  return ids.length === 0
    ? []
    : db
        .select()
        .from(scans)
        .where(inArray(scans.id, [...ids]))
        .all()
}

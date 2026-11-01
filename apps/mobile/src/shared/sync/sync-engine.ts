import { ApiError, type Api, type ScanEvent } from '@/shared/api/api-client'
import type { Db } from '@/shared/db/types'
import { countPending, markSynced, pendingBatch, type StoredScan } from '@/shared/db/scan-queue'

export type SyncOutcome =
  /** Nothing was waiting for this person. */
  | { kind: 'NOTHING_TO_SYNC' }
  | { kind: 'SYNCED'; sent: number; left: number; remaining: number }
  /** The token is no longer accepted (or expired): every scan stays, the person signs in again. */
  | { kind: 'SESSION_EXPIRED'; remaining: number }
  /** The server was not reached or answered an error: every scan of the failed batch stays. */
  | { kind: 'FAILED'; remaining: number; sent: number; left: number }
  /** Another sync is already running: this call did nothing. */
  | { kind: 'ALREADY_RUNNING' }

export type SyncDeps = {
  db: Db
  api: Api
  staffUserId: string
  now: () => Date
  /** Fetches the verification key again; called after a successful sync, and a failure is ignored. */
  refreshKey: () => Promise<unknown>
}

/** A scan as the contract's `ScanEvent`: a rejected one carries no ticket code (D50). */
export function toScanEvent(scan: StoredScan): ScanEvent {
  const verified = scan.deviceVerdict === 'VERIFIED'
  return {
    id: scan.id,
    deviceVerdict: verified ? 'VERIFIED' : 'REJECTED_SIGNATURE',
    ticketCode: verified ? scan.ticketCode : null,
    workshopId: scan.workshopId,
    scannedAt: scan.scannedAt,
    deviceId: scan.deviceId,
  }
}

/**
 * Sends the signed-in staff member's queued scans in batches of at most 500, oldest first. A scan
 * leaves the queue only when the response names its own `id`; a failed or partial response keeps it.
 * One sync runs at a time per engine: calls that overlap (network back, foreground and the timer
 * firing together) get `ALREADY_RUNNING` instead of sending the same batch twice.
 */
export function createSyncEngine() {
  let running = false

  return async function syncOnce(deps: SyncDeps): Promise<SyncOutcome> {
    if (running) return { kind: 'ALREADY_RUNNING' }
    running = true
    try {
      const { db, api, staffUserId, now } = deps
      let sent = 0
      let left = 0
      let sentAny = false
      for (;;) {
        const batch = pendingBatch(db, staffUserId)
        if (batch.length === 0) break
        let response
        try {
          response = await api.syncScans(batch.map(toScanEvent))
        } catch (error) {
          const remaining = countPending(db, staffUserId)
          if (error instanceof ApiError && error.status === 401) {
            return { kind: 'SESSION_EXPIRED', remaining }
          }
          return { kind: 'FAILED', remaining, sent, left }
        }
        sentAny = true
        sent += batch.length
        const sentIds = new Set(batch.map((scan) => scan.id))
        // Only results for ids of this batch count; anything else in the response is ignored.
        const answered = response.results.filter((result) => sentIds.has(result.id))
        const moved = markSynced(db, answered, now().toISOString())
        left += moved
        // A response that answers nothing would make this loop send the same batch forever.
        if (moved === 0) {
          return { kind: 'FAILED', remaining: countPending(db, staffUserId), sent, left }
        }
      }
      if (!sentAny) return { kind: 'NOTHING_TO_SYNC' }
      try {
        await deps.refreshKey()
      } catch {
        // The key is refreshed on the next sync or sign-in; a failure here changes nothing about the sync.
      }
      return { kind: 'SYNCED', sent, left, remaining: countPending(db, staffUserId) }
    } finally {
      running = false
    }
  }
}

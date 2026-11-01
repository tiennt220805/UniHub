import { index, integer, sqliteTable, text } from 'drizzle-orm/sqlite-core'

/**
 * The durable queue of scan events (`checkin.md` §6, §7). One row per scan, written before the
 * screen shows any result, and kept after it has synced so the history screen can show the
 * outcome the server decided. Only `sync_state = 'PENDING'` rows are queued work.
 */
export const scans = sqliteTable(
  'scans',
  {
    /** Arrival order on this device; `id` is the scan's identity everywhere else. */
    seq: integer('seq').primaryKey({ autoIncrement: true }),
    /** UUID generated on the device: the server's idempotency key for the scan (`ScanEvent.id`). */
    id: text('id').notNull().unique(),
    /** `VERIFIED` or `REJECTED_SIGNATURE`: the device's own offline verdict. */
    deviceVerdict: text('device_verdict').notNull(),
    /** From the verified payload only; null for a rejected scan, whose content cannot be trusted. */
    ticketCode: text('ticket_code'),
    /** The workshop of a verified ticket; kept to warn about the wrong room, never sent. */
    ticketWorkshopId: text('ticket_workshop_id'),
    /** The room the staff member picked: this, not the ticket's workshop, is sent (`checkin.md` §7). */
    workshopId: text('workshop_id').notNull(),
    /** What the signature check said about a rejected scan. */
    rejectionReason: text('rejection_reason'),
    /** The first characters of a rejected scan, for the history screen. Never kept for a verified one. */
    rawPreview: text('raw_preview'),
    scannedAt: text('scanned_at').notNull(),
    deviceId: text('device_id').notNull(),
    /** `sub` of the token the scan was made under: only this person's session may send it (D53). */
    staffUserId: text('staff_user_id').notNull(),
    /** A warning shown at scan time, kept for the history: `WRONG_ROOM`, `REPEAT`, or null. */
    warning: text('warning'),
    syncState: text('sync_state').notNull().default('PENDING'),
    /** `CheckInScanOutcome` the server returned for this exact `id`. */
    serverOutcome: text('server_outcome'),
    syncedAt: text('synced_at'),
  },
  (table) => [
    index('idx_scans_pending').on(table.syncState, table.staffUserId, table.seq),
    index('idx_scans_ticket').on(table.ticketCode),
  ],
)

/** The workshops the staff member can pick as "the room", fetched while online. */
export const rooms = sqliteTable('rooms', {
  id: text('id').primaryKey(),
  title: text('title').notNull(),
  location: text('location').notNull(),
  startTime: text('start_time').notNull(),
})

/** Small non-secret device state: the picked room. Secrets (token, key, device id) are in SecureStore. */
export const deviceState = sqliteTable('device_state', {
  key: text('key').primaryKey(),
  value: text('value').notNull(),
})

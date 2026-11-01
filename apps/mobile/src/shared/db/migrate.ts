import { sql } from 'drizzle-orm'

import type { Db } from './types'

/**
 * Forward-only migrations, applied in order; `PRAGMA user_version` is the number applied. Written
 * as plain SQL and run by hand-rolled code rather than drizzle-kit, so the same list runs on the
 * device (where there is no CLI) and in Jest. Never edit an entry that has shipped: append one.
 */
const MIGRATIONS: readonly (readonly string[])[] = [
  [
    `CREATE TABLE scans (
      seq INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
      id TEXT NOT NULL UNIQUE,
      device_verdict TEXT NOT NULL CHECK (device_verdict IN ('VERIFIED', 'REJECTED_SIGNATURE')),
      ticket_code TEXT CHECK (ticket_code IS NULL OR length(ticket_code) BETWEEN 1 AND 64),
      ticket_workshop_id TEXT,
      workshop_id TEXT NOT NULL,
      rejection_reason TEXT,
      raw_preview TEXT,
      scanned_at TEXT NOT NULL,
      device_id TEXT NOT NULL CHECK (length(device_id) BETWEEN 1 AND 100),
      staff_user_id TEXT NOT NULL,
      warning TEXT,
      sync_state TEXT NOT NULL DEFAULT 'PENDING' CHECK (sync_state IN ('PENDING', 'SYNCED')),
      server_outcome TEXT,
      synced_at TEXT,
      CHECK (device_verdict = 'REJECTED_SIGNATURE' OR ticket_code IS NOT NULL),
      CHECK (device_verdict = 'VERIFIED' OR ticket_code IS NULL),
      CHECK (device_verdict = 'REJECTED_SIGNATURE' OR raw_preview IS NULL)
    )`,
    `CREATE INDEX idx_scans_pending ON scans (sync_state, staff_user_id, seq)`,
    `CREATE INDEX idx_scans_ticket ON scans (ticket_code)`,
    `CREATE TABLE rooms (
      id TEXT PRIMARY KEY NOT NULL,
      title TEXT NOT NULL,
      location TEXT NOT NULL,
      start_time TEXT NOT NULL
    )`,
    `CREATE TABLE device_state (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)`,
  ],
]

export function migrate(db: Db): void {
  const row = db.all<{ user_version: number }>(sql`PRAGMA user_version`)[0]
  const applied = row?.user_version ?? 0
  for (let version = applied; version < MIGRATIONS.length; version++) {
    db.transaction((tx) => {
      for (const statement of MIGRATIONS[version] ?? []) tx.run(sql.raw(statement))
      // PRAGMA does not take a bound parameter; the value is a loop counter, not input.
      tx.run(sql.raw(`PRAGMA user_version = ${version + 1}`))
    })
  }
}

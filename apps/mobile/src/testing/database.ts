import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import Database from 'better-sqlite3'
import { drizzle } from 'drizzle-orm/better-sqlite3'

import { migrate } from '@/shared/db/migrate'
import * as schema from '@/shared/db/schema'
import type { Db } from '@/shared/db/types'

/**
 * A real SQLite *file*, opened with the same schema and migrations the device runs. Tests that
 * must show a scan survives a restart close it and open the file again (Phase 5 checklist D58, S2).
 */
export function openTestDatabase(path?: string): { db: Db; path: string; close: () => void } {
  const file = path ?? join(mkdtempSync(join(tmpdir(), 'unihub-checkin-')), 'queue.db')
  const connection = new Database(file)
  connection.pragma('journal_mode = WAL')
  const db = drizzle(connection, { schema }) as unknown as Db
  migrate(db)
  return { db, path: file, close: () => connection.close() }
}

export const removeTestDatabase = (path: string) =>
  rmSync(path.replace(/\/[^/]+$/, ''), { recursive: true, force: true })

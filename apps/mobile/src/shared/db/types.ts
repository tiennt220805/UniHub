import type { BaseSQLiteDatabase } from 'drizzle-orm/sqlite-core'

import type * as schema from './schema'

/**
 * The one database type the app's code is written against. Both drivers — `expo-sqlite` on the
 * device and `better-sqlite3` in Jest — are synchronous Drizzle databases over this schema, so a
 * repository is written once and the same code runs in both (Phase 5 checklist D58, S2).
 */
// The first type argument is the driver's own run-result type, which differs between the two drivers.
// oxlint-disable-next-line typescript/no-explicit-any
export type Db = BaseSQLiteDatabase<'sync', any, typeof schema>

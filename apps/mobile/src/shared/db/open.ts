import { drizzle } from 'drizzle-orm/expo-sqlite'
import { openDatabaseSync } from 'expo-sqlite'

import { migrate } from './migrate'
import * as schema from './schema'
import type { Db } from './types'

const DATABASE_NAME = 'unihub-checkin.db'

let opened: Db | undefined

/** The device's database, opened and migrated once. Not used in Jest: tests open a file with `better-sqlite3`. */
export function database(): Db {
  if (opened === undefined) {
    const db = drizzle(openDatabaseSync(DATABASE_NAME), { schema })
    migrate(db)
    opened = db
  }
  return opened
}

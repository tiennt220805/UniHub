import { asc, eq } from 'drizzle-orm'

import type { Api } from '@/shared/api/api-client'
import { deviceState, rooms } from './schema'
import type { Db } from './types'

export type Room = typeof rooms.$inferSelect

const SELECTED_ROOM = 'selectedRoomId'
const MAX_PAGES = 5

/**
 * Replaces the cached list of rooms (published workshops) with the Backend API's, while online. A
 * failure leaves the cached list as it was: the staff member can still scan in the room already picked.
 */
export async function refreshRooms(api: Api, db: Db): Promise<boolean> {
  try {
    const fetched: Room[] = []
    for (let page = 0; page < MAX_PAGES; page++) {
      const response = await api.listWorkshops(page)
      for (const workshop of response.items) {
        if (workshop.status === 'PUBLISHED') {
          fetched.push({
            id: workshop.id,
            title: workshop.title,
            location: workshop.location,
            startTime: workshop.startTime,
          })
        }
      }
      if (page + 1 >= response.page.totalPages) break
    }
    db.transaction((tx) => {
      tx.delete(rooms).run()
      for (const room of fetched) tx.insert(rooms).values(room).run()
    })
    return true
  } catch {
    return false
  }
}

export const listRooms = (db: Db): Room[] =>
  db.select().from(rooms).orderBy(asc(rooms.startTime)).all()

export function selectedRoom(db: Db): Room | undefined {
  const row = db.select().from(deviceState).where(eq(deviceState.key, SELECTED_ROOM)).all()[0]
  if (!row) return undefined
  return db.select().from(rooms).where(eq(rooms.id, row.value)).all()[0]
}

export function selectRoom(db: Db, roomId: string): void {
  db.insert(deviceState)
    .values({ key: SELECTED_ROOM, value: roomId })
    .onConflictDoUpdate({ target: deviceState.key, set: { value: roomId } })
    .run()
}

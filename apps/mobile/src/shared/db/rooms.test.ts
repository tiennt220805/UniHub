import { randomUUID } from 'node:crypto'

import { ApiError, type Api } from '@/shared/api/api-client'
import { openTestDatabase, removeTestDatabase } from '@/testing/database'

import { listRooms, refreshRooms, selectedRoom, selectRoom } from './rooms'

const workshop = (title: string, status = 'PUBLISHED') => ({
  id: randomUUID(),
  title,
  location: `Phòng ${title}`,
  startTime: '2026-11-04T02:00:00Z',
  status,
})

const apiWith = (pages: ReturnType<typeof workshop>[][] | ApiError): Api =>
  ({
    listWorkshops: async (page: number) => {
      if (pages instanceof ApiError) throw pages
      return {
        items: pages[page] ?? [],
        page: { page, size: 100, totalElements: 0, totalPages: pages.length },
      }
    },
  }) as unknown as Api

let handle: ReturnType<typeof openTestDatabase>
beforeEach(() => {
  handle = openTestDatabase()
})
afterEach(() => {
  handle.close()
  removeTestDatabase(handle.path)
})

describe('the rooms a staff member can pick', () => {
  it('caches only published workshops, across pages', async () => {
    const a = workshop('A')
    const b = workshop('B')
    const draft = workshop('Nháp', 'DRAFT')
    const cancelled = workshop('Hủy', 'CANCELLED')

    expect(
      await refreshRooms(
        apiWith([
          [a, draft],
          [cancelled, b],
        ]),
        handle.db,
      ),
    ).toBe(true)

    expect(
      listRooms(handle.db)
        .map((room) => room.id)
        .sort(),
    ).toEqual([a.id, b.id].sort())
  })

  it('keeps the cached list when the refresh fails, so an offline device can still pick a room', async () => {
    const a = workshop('A')
    await refreshRooms(apiWith([[a]]), handle.db)

    expect(await refreshRooms(apiWith(new ApiError(null, 'offline')), handle.db)).toBe(false)

    expect(listRooms(handle.db).map((room) => room.id)).toEqual([a.id])
  })

  it('a refresh replaces the list: a workshop no longer published disappears', async () => {
    const a = workshop('A')
    const b = workshop('B')
    await refreshRooms(apiWith([[a, b]]), handle.db)

    await refreshRooms(apiWith([[b]]), handle.db)

    expect(listRooms(handle.db).map((room) => room.id)).toEqual([b.id])
  })

  it('remembers the picked room across a restart, and forgets one that is gone', async () => {
    const a = workshop('A')
    const b = workshop('B')
    await refreshRooms(apiWith([[a, b]]), handle.db)
    selectRoom(handle.db, a.id)
    selectRoom(handle.db, b.id)
    handle.close()

    handle = openTestDatabase(handle.path)
    expect(selectedRoom(handle.db)?.id).toBe(b.id)

    await refreshRooms(apiWith([[a]]), handle.db)
    expect(selectedRoom(handle.db)).toBeUndefined()
  })
})

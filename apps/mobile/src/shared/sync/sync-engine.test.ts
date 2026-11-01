import { randomUUID } from 'node:crypto'

import type { ScanEvent } from '@/shared/api/api-client'
import { countPending, enqueueScan, recentScans, type NewScan } from '@/shared/db/scan-queue'
import { openTestDatabase, removeTestDatabase } from '@/testing/database'
import { fakeApi, httpError, offline } from '@/testing/fake-api'

import { createSyncEngine, toScanEvent } from './sync-engine'

const STAFF = randomUUID()
const OTHER = randomUUID()
const ROOM = randomUUID()
const NOW = () => new Date('2026-11-01T03:00:00.000Z')

let handle: ReturnType<typeof openTestDatabase>
beforeEach(() => {
  handle = openTestDatabase()
})
afterEach(() => {
  try {
    handle.close()
  } catch {
    // already closed by the test
  }
  removeTestDatabase(handle.path)
})

let counter = 0
function queue(count: number, staffUserId = STAFF): string[] {
  const ids: string[] = []
  for (let i = 0; i < count; i++) {
    const id = randomUUID()
    ids.push(id)
    enqueueScan(handle.db, {
      id,
      deviceVerdict: 'VERIFIED',
      ticketCode: `CODE${counter++}`,
      ticketWorkshopId: ROOM,
      workshopId: ROOM,
      scannedAt: '2026-11-01T02:00:00.000Z',
      deviceId: 'device-1',
      staffUserId,
      warning: null,
    } satisfies NewScan)
  }
  return ids
}

const deps = (
  api: ReturnType<typeof fakeApi>['api'],
  refreshKey = jest.fn(async () => undefined),
) => ({
  db: handle.db,
  api,
  staffUserId: STAFF,
  now: NOW,
  refreshKey,
})

describe('sync sends the queue in batches and removes only what the server answered (M-45)', () => {
  it('sends 501 pending scans as two batches, 500 then 1, and all of them leave the queue', async () => {
    queue(501)
    const { api, batches } = fakeApi()

    const outcome = await createSyncEngine()(deps(api))

    expect(outcome).toEqual({ kind: 'SYNCED', sent: 501, left: 501, remaining: 0 })
    expect(batches.map((batch) => batch.length)).toEqual([500, 1])
  })

  it('does nothing and does not call the server when nothing waits', async () => {
    const { api, batches } = fakeApi()
    const refreshKey = jest.fn(async () => undefined)

    expect(await createSyncEngine()(deps(api, refreshKey))).toEqual({ kind: 'NOTHING_TO_SYNC' })
    expect(batches).toHaveLength(0)
    expect(refreshKey).not.toHaveBeenCalled()
  })

  it('stores the outcome the server returned for each id, including a duplicate decided there', async () => {
    const [first, second] = queue(2) as [string, string]
    const { api } = fakeApi({
      sync: () => [
        { id: first, outcome: 'REJECTED_DUPLICATE' },
        { id: second, outcome: 'ACCEPTED' },
      ],
    })

    await createSyncEngine()(deps(api))

    const byId = Object.fromEntries(recentScans(handle.db).map((row) => [row.id, row]))
    expect(byId[first]).toMatchObject({ syncState: 'SYNCED', serverOutcome: 'REJECTED_DUPLICATE' })
    expect(byId[second]).toMatchObject({ syncState: 'SYNCED', serverOutcome: 'ACCEPTED' })
    expect(byId[first]?.syncedAt).toBe('2026-11-01T03:00:00.000Z')
  })

  it('keeps a scan the response does not name, and answers FAILED instead of looping on it', async () => {
    const [answered, silent] = queue(2) as [string, string]
    const { api, batches } = fakeApi({ sync: () => [{ id: answered, outcome: 'ACCEPTED' }] })
    const refreshKey = jest.fn(async () => undefined)

    const outcome = await createSyncEngine()(deps(api, refreshKey))

    // The second pass sends the silent scan again, gets nothing for it, and stops.
    expect(outcome).toMatchObject({ kind: 'FAILED', remaining: 1, left: 1 })
    expect(batches.map((batch) => batch.map((scan) => scan.id))).toEqual([
      [answered, silent],
      [silent],
    ])
    expect(recentScans(handle.db).find((row) => row.id === silent)?.syncState).toBe('PENDING')
    expect(refreshKey).not.toHaveBeenCalled()
  })

  it('ignores results for ids it did not send', async () => {
    const [mine] = queue(1) as [string]
    const stranger = randomUUID()
    const { api } = fakeApi({
      sync: () => [
        { id: stranger, outcome: 'ACCEPTED' },
        { id: mine, outcome: 'ACCEPTED' },
      ],
    })

    expect(await createSyncEngine()(deps(api))).toMatchObject({ kind: 'SYNCED', left: 1 })
  })

  it('keeps every scan when the server cannot be reached', async () => {
    queue(3)
    const { api } = fakeApi({ sync: () => Promise.reject(offline()) })
    const refreshKey = jest.fn(async () => undefined)

    const outcome = await createSyncEngine()(deps(api, refreshKey))

    expect(outcome).toEqual({ kind: 'FAILED', remaining: 3, sent: 0, left: 0 })
    expect(countPending(handle.db, STAFF)).toBe(3)
    expect(refreshKey).not.toHaveBeenCalled()
  })

  it('keeps every scan when the server answers an error, and keeps what an earlier batch already sent', async () => {
    queue(501)
    let call = 0
    const { api } = fakeApi({
      sync: (scans) => {
        if (++call === 2) throw httpError(503)
        return scans.map((scan) => ({ id: scan.id, outcome: 'ACCEPTED' }))
      },
    })

    const outcome = await createSyncEngine()(deps(api))

    expect(outcome).toEqual({ kind: 'FAILED', remaining: 1, sent: 500, left: 500 })
  })

  it('an expired token keeps every scan and asks for a new sign-in', async () => {
    queue(4)
    const { api } = fakeApi({ sync: () => Promise.reject(httpError(401)) })

    expect(await createSyncEngine()(deps(api))).toEqual({ kind: 'SESSION_EXPIRED', remaining: 4 })
    expect(countPending(handle.db, STAFF)).toBe(4)
  })

  it('a 400 is not an expired session: the scans stay and the outcome is FAILED', async () => {
    queue(1)
    const { api } = fakeApi({ sync: () => Promise.reject(httpError(400)) })

    expect(await createSyncEngine()(deps(api))).toMatchObject({ kind: 'FAILED', remaining: 1 })
  })

  it('sends only the signed-in staff member’s scans; the other’s wait for them (AC53.1)', async () => {
    const mine = queue(2)
    const theirs = queue(3, OTHER)
    const { api, batches } = fakeApi()

    const outcome = await createSyncEngine()(deps(api))

    expect(outcome).toMatchObject({ kind: 'SYNCED', sent: 2 })
    expect(batches.flat().map((scan) => scan.id)).toEqual(mine)
    expect(countPending(handle.db, OTHER)).toBe(3)
    expect(
      recentScans(handle.db)
        .filter((row) => theirs.includes(row.id))
        .every((row) => row.syncState === 'PENDING'),
    ).toBe(true)
  })

  it('runs one sync at a time: overlapping triggers send each scan once', async () => {
    queue(3)
    let release!: () => void
    const gate = new Promise<void>((resolve) => (release = resolve))
    const { api, batches } = fakeApi({
      sync: async (scans) => {
        await gate
        return scans.map((scan) => ({ id: scan.id, outcome: 'ACCEPTED' }))
      },
    })
    const sync = createSyncEngine()

    const first = sync(deps(api))
    const second = await sync(deps(api))
    const third = await sync(deps(api))
    release()

    expect(second).toEqual({ kind: 'ALREADY_RUNNING' })
    expect(third).toEqual({ kind: 'ALREADY_RUNNING' })
    expect(await first).toMatchObject({ kind: 'SYNCED', sent: 3 })
    expect(batches).toHaveLength(1)
  })

  it('can run again once the previous sync has finished, even one that failed', async () => {
    queue(1)
    const sync = createSyncEngine()
    await sync(deps(fakeApi({ sync: () => Promise.reject(offline()) }).api))

    expect(await sync(deps(fakeApi().api))).toMatchObject({ kind: 'SYNCED', left: 1 })
  })

  it('fetches the key again after a successful sync, and a failure doing so does not undo it', async () => {
    queue(1)
    const refreshKey = jest.fn(async () => {
      throw new Error('key endpoint down')
    })

    const outcome = await createSyncEngine()(deps(fakeApi().api, refreshKey))

    expect(refreshKey).toHaveBeenCalledTimes(1)
    expect(outcome).toMatchObject({ kind: 'SYNCED', left: 1 })
  })

  it('after a restart (reopen the file) the unsent scans are still sent', async () => {
    queue(3)
    handle.close()
    handle = openTestDatabase(handle.path)
    const { api, batches } = fakeApi()

    expect(await createSyncEngine()(deps(api))).toMatchObject({ kind: 'SYNCED', sent: 3 })
    expect(batches[0]).toHaveLength(3)
  })
})

describe('what is sent is exactly the contract’s ScanEvent', () => {
  it('sends a verified scan with its ticket code and the room, and nothing local', () => {
    const [id] = queue(1) as [string]
    const [row] = recentScans(handle.db)

    expect(toScanEvent(row!)).toEqual({
      id,
      deviceVerdict: 'VERIFIED',
      ticketCode: row!.ticketCode,
      workshopId: ROOM,
      scannedAt: '2026-11-01T02:00:00.000Z',
      deviceId: 'device-1',
    } satisfies ScanEvent)
  })

  it('sends a rejected scan with ticketCode null and never the raw content', () => {
    enqueueScan(handle.db, {
      id: randomUUID(),
      deviceVerdict: 'REJECTED_SIGNATURE',
      rejectionReason: 'SIGNATURE',
      rawPreview: 'attacker controlled content',
      workshopId: ROOM,
      scannedAt: '2026-11-01T02:00:00.000Z',
      deviceId: 'device-1',
      staffUserId: STAFF,
      warning: null,
    })
    const [row] = recentScans(handle.db)

    const event = toScanEvent(row!)

    expect(event).toMatchObject({ deviceVerdict: 'REJECTED_SIGNATURE', ticketCode: null })
    expect(JSON.stringify(event)).not.toContain('attacker')
    expect(Object.keys(event).sort()).toEqual([
      'deviceId',
      'deviceVerdict',
      'id',
      'scannedAt',
      'ticketCode',
      'workshopId',
    ])
  })
})

import { randomUUID } from 'node:crypto'

import { sql } from 'drizzle-orm'

import { openTestDatabase, removeTestDatabase } from '@/testing/database'

import {
  countPending,
  countPendingOfOthers,
  enqueueScan,
  lastScanOfTicket,
  markSynced,
  type NewScan,
  pendingBatch,
  RAW_PREVIEW_LENGTH,
  recentScans,
} from './scan-queue'

const STAFF = randomUUID()
const OTHER_STAFF = randomUUID()
const ROOM = randomUUID()

const verified = (overrides: Partial<NewScan> = {}): NewScan =>
  ({
    id: randomUUID(),
    deviceVerdict: 'VERIFIED',
    ticketCode: 'TICKET0000000000000000001A',
    ticketWorkshopId: ROOM,
    workshopId: ROOM,
    scannedAt: '2026-11-01T02:00:00.000Z',
    deviceId: randomUUID(),
    staffUserId: STAFF,
    warning: null,
    ...overrides,
  }) as NewScan

const rejected = (overrides: Partial<NewScan> = {}): NewScan =>
  ({
    id: randomUUID(),
    deviceVerdict: 'REJECTED_SIGNATURE',
    rejectionReason: 'SIGNATURE',
    rawPreview: 'x'.repeat(500),
    workshopId: ROOM,
    scannedAt: '2026-11-01T02:00:00.000Z',
    deviceId: randomUUID(),
    staffUserId: STAFF,
    warning: null,
    ...overrides,
  }) as NewScan

describe('the scan queue is durable (M-44)', () => {
  let handle: ReturnType<typeof openTestDatabase>
  beforeEach(() => {
    handle = openTestDatabase()
  })
  afterEach(() => {
    handle.close()
    removeTestDatabase(handle.path)
  })

  it('keeps every field of a scan, and a restart (close, reopen the file) loses nothing', () => {
    const scan = verified({ warning: 'REPEAT' })
    enqueueScan(handle.db, scan)
    enqueueScan(handle.db, rejected())
    handle.close()

    const reopened = openTestDatabase(handle.path)
    try {
      const stored = recentScans(reopened.db)
      expect(stored).toHaveLength(2)
      expect(stored.find((row) => row.id === scan.id)).toMatchObject({
        id: scan.id,
        deviceVerdict: 'VERIFIED',
        ticketCode: 'TICKET0000000000000000001A',
        ticketWorkshopId: ROOM,
        workshopId: ROOM,
        scannedAt: '2026-11-01T02:00:00.000Z',
        deviceId: scan.deviceId,
        staffUserId: STAFF,
        warning: 'REPEAT',
        syncState: 'PENDING',
        serverOutcome: null,
      })
      expect(countPending(reopened.db, STAFF)).toBe(2)
    } finally {
      reopened.close()
    }
    handle = openTestDatabase(handle.path)
  })

  it('keeps the sync state across a restart: a scan that synced is still synced', () => {
    const first = verified()
    const second = verified({ ticketCode: 'TICKET0000000000000000002B' } as Partial<NewScan>)
    enqueueScan(handle.db, first)
    enqueueScan(handle.db, second)
    markSynced(handle.db, [{ id: first.id, outcome: 'ACCEPTED' }], '2026-11-01T03:00:00.000Z')
    handle.close()

    const reopened = openTestDatabase(handle.path)
    try {
      expect(pendingBatch(reopened.db, STAFF).map((row) => row.id)).toEqual([second.id])
      const synced = recentScans(reopened.db).find((row) => row.id === first.id)
      expect(synced).toMatchObject({ syncState: 'SYNCED', serverOutcome: 'ACCEPTED' })
    } finally {
      reopened.close()
    }
    handle = openTestDatabase(handle.path)
  })

  it('migrating an already migrated file changes nothing', () => {
    enqueueScan(handle.db, verified())
    handle.close()
    const again = openTestDatabase(handle.path)
    again.close()
    handle = openTestDatabase(handle.path)

    expect(recentScans(handle.db)).toHaveLength(1)
  })

  it('a rejected scan stores no ticket code, marks the device rejection, and keeps only a short preview', () => {
    enqueueScan(handle.db, rejected())

    const [row] = recentScans(handle.db)
    expect(row).toMatchObject({
      deviceVerdict: 'REJECTED_SIGNATURE',
      ticketCode: null,
      ticketWorkshopId: null,
      rejectionReason: 'SIGNATURE',
    })
    expect(row?.rawPreview).toHaveLength(RAW_PREVIEW_LENGTH)
  })

  it('a verified scan keeps no raw signed payload', () => {
    enqueueScan(handle.db, verified())

    const [row] = recentScans(handle.db)
    expect(row?.rawPreview).toBeNull()
    expect(JSON.stringify(row)).not.toContain('eyJ')
  })

  it('the database itself refuses a row whose verdict, ticket code and raw preview disagree', () => {
    const insert = (verdict: string, ticketCode: string | null, rawPreview: string | null) =>
      handle.db.run(
        sql`INSERT INTO scans (id, device_verdict, ticket_code, workshop_id, scanned_at, device_id, staff_user_id, raw_preview)
            VALUES (${randomUUID()}, ${verdict}, ${ticketCode}, ${ROOM}, 'now', 'device', ${STAFF}, ${rawPreview})`,
      )

    expect(() => insert('VERIFIED', null, null)).toThrow() // verified without a code
    expect(() => insert('REJECTED_SIGNATURE', 'LEAKED', 'raw')).toThrow() // rejected with a code
    expect(() => insert('VERIFIED', 'CODE', 'raw signed payload')).toThrow() // verified keeping the raw value
    expect(() => insert('MAYBE', 'CODE', null)).toThrow() // no such verdict
    expect(() => insert('VERIFIED', 'x'.repeat(65), null)).toThrow() // longer than the server accepts
    expect(() => insert('VERIFIED', 'CODE', null)).not.toThrow()
    expect(() => insert('REJECTED_SIGNATURE', null, 'raw')).not.toThrow()
  })

  it('an id that already exists is refused: one scan, one row', () => {
    const scan = verified()
    enqueueScan(handle.db, scan)

    expect(() => enqueueScan(handle.db, scan)).toThrow()
    expect(recentScans(handle.db)).toHaveLength(1)
  })

  it('device id length is bounded at 1…100 by the database (BVA)', () => {
    for (const length of [1, 99, 100]) {
      expect(() => enqueueScan(handle.db, verified({ deviceId: 'd'.repeat(length) }))).not.toThrow()
    }
    for (const length of [0, 101, 1000]) {
      expect(() => enqueueScan(handle.db, verified({ deviceId: 'd'.repeat(length) }))).toThrow()
    }
  })
})

describe('the queue is read per staff member and in arrival order', () => {
  let handle: ReturnType<typeof openTestDatabase>
  beforeEach(() => {
    handle = openTestDatabase()
  })
  afterEach(() => {
    handle.close()
    removeTestDatabase(handle.path)
  })

  it('returns only the signed-in staff member’s scans, oldest first, at most 500', () => {
    const mine: string[] = []
    for (let i = 0; i < 501; i++) {
      const scan = verified({ ticketCode: `T${i}` } as Partial<NewScan>)
      mine.push(scan.id)
      enqueueScan(handle.db, scan)
    }
    enqueueScan(handle.db, verified({ staffUserId: OTHER_STAFF } as Partial<NewScan>))

    const batch = pendingBatch(handle.db, STAFF)

    expect(batch).toHaveLength(500)
    expect(batch.map((row) => row.id)).toEqual(mine.slice(0, 500))
    expect(countPending(handle.db, STAFF)).toBe(501)
    expect(countPendingOfOthers(handle.db, STAFF)).toBe(1)
    expect(pendingBatch(handle.db, STAFF, 10_000)).toHaveLength(500)
  })

  it('markSynced moves exactly the named ids and counts them; an unknown id is not a scan', () => {
    const a = verified()
    const b = verified()
    enqueueScan(handle.db, a)
    enqueueScan(handle.db, b)

    const left = markSynced(
      handle.db,
      [
        { id: a.id, outcome: 'REJECTED_DUPLICATE' },
        { id: randomUUID(), outcome: 'ACCEPTED' },
      ],
      '2026-11-01T03:00:00.000Z',
    )

    expect(left).toBe(1)
    expect(pendingBatch(handle.db, STAFF).map((row) => row.id)).toEqual([b.id])
    expect(markSynced(handle.db, [{ id: a.id, outcome: 'ACCEPTED' }], 'later')).toBe(0)
    expect(recentScans(handle.db).find((row) => row.id === a.id)?.serverOutcome).toBe(
      'REJECTED_DUPLICATE',
    )
  })

  it('finds the last scan of a ticket on this device, whoever scanned it', () => {
    enqueueScan(handle.db, verified({ scannedAt: '2026-11-01T02:00:00.000Z' }))
    const later = verified({ scannedAt: '2026-11-01T02:05:00.000Z', staffUserId: OTHER_STAFF })
    enqueueScan(handle.db, later)

    expect(lastScanOfTicket(handle.db, 'TICKET0000000000000000001A')?.id).toBe(later.id)
    expect(lastScanOfTicket(handle.db, 'NEVERSCANNED')).toBeUndefined()
  })
})

import { randomUUID } from 'node:crypto'

import { recentScans } from '@/shared/db/scan-queue'
import { openTestDatabase, removeTestDatabase } from '@/testing/database'
import { asVerificationKey, issueTicket, newTestKey } from '@/testing/tickets'

import { processScan, type ScanContext } from './scan-flow'

const ROOM = randomUUID()
const STAFF = randomUUID()
const DEVICE = randomUUID()
const key = newTestKey()

let handle: ReturnType<typeof openTestDatabase>
let clock: Date

const context = (overrides: Partial<ScanContext> = {}): ScanContext => ({
  db: handle.db,
  key: asVerificationKey(key),
  deviceId: DEVICE,
  staffUserId: STAFF,
  roomWorkshopId: ROOM,
  now: () => clock,
  newId: () => randomUUID(),
  ...overrides,
})

beforeEach(() => {
  handle = openTestDatabase()
  clock = new Date('2026-11-01T02:00:00.000Z')
})
afterEach(() => {
  try {
    handle.close()
  } catch {
    // some tests close it themselves
  }
  removeTestDatabase(handle.path)
})

describe('a scan is verified offline and written before any result (M-43, M-44)', () => {
  it('records a valid scan with every field, taking the ticket code only from the verified payload', () => {
    const token = issueTicket(key, { ticketCode: 'ABCDEFGHJKMNPQRSTVWXYZ0123', workshopId: ROOM })

    const outcome = processScan(context(), token)

    expect(outcome).toEqual({
      kind: 'VALID_PENDING',
      ticketCode: 'ABCDEFGHJKMNPQRSTVWXYZ0123',
      warnings: [],
      previousScanAt: null,
    })
    const [row] = recentScans(handle.db)
    expect(row).toMatchObject({
      deviceVerdict: 'VERIFIED',
      ticketCode: 'ABCDEFGHJKMNPQRSTVWXYZ0123',
      ticketWorkshopId: ROOM,
      workshopId: ROOM,
      scannedAt: '2026-11-01T02:00:00.000Z',
      deviceId: DEVICE,
      staffUserId: STAFF,
      syncState: 'PENDING',
      warning: null,
    })
    // The signed token itself is not kept once the code is extracted.
    expect(JSON.stringify(row)).not.toContain(token.split('.')[2])
  })

  it('rejects a forged ticket, records it as a device rejection with no ticket code', () => {
    const forged = issueTicket(key, {}, { signWith: newTestKey() })

    const outcome = processScan(context(), forged)

    expect(outcome).toEqual({ kind: 'REJECTED', reason: 'SIGNATURE' })
    const [row] = recentScans(handle.db)
    expect(row).toMatchObject({
      deviceVerdict: 'REJECTED_SIGNATURE',
      ticketCode: null,
      ticketWorkshopId: null,
      rejectionReason: 'SIGNATURE',
      workshopId: ROOM,
    })
    expect(row?.rawPreview).toBe(forged.slice(0, 64))
  })

  it('rejects input of any type without a crash and records what kind it was', () => {
    for (const raw of [42, null, undefined, {}, ['a']]) {
      expect(processScan(context(), raw)).toEqual({ kind: 'REJECTED', reason: 'MALFORMED' })
    }
    // Newest first: ['a'], {}, undefined, null, 42.
    expect(recentScans(handle.db).map((row) => row.rawPreview)).toEqual([
      '[object]',
      '[object]',
      '[undefined]',
      '[object]',
      '[number]',
    ])
  })

  it('with no key cached, nothing is scanned and nothing is recorded (AC59.1)', () => {
    const outcome = processScan(context({ key: null }), issueTicket(key))

    expect(outcome).toEqual({ kind: 'NO_KEY' })
    expect(recentScans(handle.db)).toHaveLength(0)
  })

  it('a ticket for another workshop warns, and the scan is still recorded for the room', () => {
    const otherWorkshop = randomUUID()

    const outcome = processScan(context(), issueTicket(key, { workshopId: otherWorkshop }))

    expect(outcome).toMatchObject({ kind: 'VALID_PENDING', warnings: ['WRONG_ROOM'] })
    expect(recentScans(handle.db)[0]).toMatchObject({
      workshopId: ROOM,
      ticketWorkshopId: otherWorkshop,
      warning: 'WRONG_ROOM',
    })
  })

  it('scanning the same ticket again on this device warns with the earlier time and records both', () => {
    const token = issueTicket(key, { workshopId: ROOM })
    processScan(context(), token)
    clock = new Date('2026-11-01T02:07:00.000Z')

    const again = processScan(context(), token)

    expect(again).toMatchObject({
      kind: 'VALID_PENDING',
      warnings: ['REPEAT'],
      previousScanAt: '2026-11-01T02:00:00.000Z',
    })
    expect(recentScans(handle.db)).toHaveLength(2)
  })

  it('a repeat in the wrong room carries both warnings', () => {
    const token = issueTicket(key, { workshopId: randomUUID() })
    processScan(context(), token)

    expect(processScan(context(), token)).toMatchObject({ warnings: ['REPEAT', 'WRONG_ROOM'] })
  })

  it('never shows a scan as valid when it could not be written', () => {
    handle.close()

    const outcome = processScan(context(), issueTicket(key, { workshopId: ROOM }))

    expect(outcome).toEqual({ kind: 'WRITE_FAILED' })
  })

  it('after the key is replaced, a ticket signed under the old key is rejected (S-10 V7, AC59.2)', () => {
    const token = issueTicket(key, { workshopId: ROOM })
    expect(processScan(context(), token).kind).toBe('VALID_PENDING')
    const replaced = asVerificationKey(newTestKey())

    expect(processScan(context({ key: replaced }), token)).toEqual({
      kind: 'REJECTED',
      reason: 'SIGNATURE',
    })
  })

  it('never uses the network', () => {
    const spy = jest.fn(() => {
      throw new Error('no network while scanning')
    })
    const original = globalThis.fetch
    globalThis.fetch = spy as unknown as typeof fetch
    try {
      processScan(context(), issueTicket(key, { workshopId: ROOM }))
      processScan(context(), 'garbage')
    } finally {
      globalThis.fetch = original
    }

    expect(spy).not.toHaveBeenCalled()
  })
})

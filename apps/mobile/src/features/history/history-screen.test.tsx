import { randomUUID } from 'node:crypto'

import { screen } from '@testing-library/react-native'

import { enqueueScan, markSynced, type NewScan } from '@/shared/db/scan-queue'
import {
  makeHarness,
  renderWithServices,
  STAFF_ID,
  type Harness,
} from '@/testing/render-with-services'

import { HistoryScreen } from './HistoryScreen'

jest.mock('expo-router', () => {
  const { useEffect } = require('react')
  return { useFocusEffect: (effect: () => void) => useEffect(effect, [effect]) }
})

const ROOM = randomUUID()
const OTHER_STAFF = randomUUID()
let harness: Harness

const verified = (ticketCode: string, overrides: Partial<NewScan> = {}) =>
  ({
    id: randomUUID(),
    deviceVerdict: 'VERIFIED',
    ticketCode,
    ticketWorkshopId: ROOM,
    workshopId: ROOM,
    scannedAt: '2026-11-01T02:00:00.000Z',
    deviceId: 'device',
    staffUserId: STAFF_ID,
    warning: null,
    ...overrides,
  }) as NewScan

afterEach(() => harness.close())

describe('the history screen (8.7)', () => {
  it('says so when nothing was scanned', async () => {
    harness = makeHarness()

    await renderWithServices(harness, <HistoryScreen />)

    expect(screen.getByText('Chưa có lần quét nào.')).toBeTruthy()
  })

  it('shows pending scans, and synced ones with the outcome the server returned', async () => {
    harness = makeHarness()
    const pending = verified('PENDINGCODE')
    const accepted = verified('ACCEPTEDCODE')
    const duplicate = verified('DUPLICATECODE')
    const invalid = verified('INVALIDCODE')
    for (const scan of [pending, accepted, duplicate, invalid])
      enqueueScan(harness.services.db, scan)
    markSynced(
      harness.services.db,
      [
        { id: accepted.id, outcome: 'ACCEPTED' },
        { id: duplicate.id, outcome: 'REJECTED_DUPLICATE' },
        { id: invalid.id, outcome: 'REJECTED_INVALID' },
      ],
      '2026-11-01T03:00:00.000Z',
    )

    await renderWithServices(harness, <HistoryScreen />)

    const row = (id: string) => screen.getByLabelText(`scan-${id}`)
    expect(row(pending.id)).toHaveTextContent('Chờ gửi', { exact: false })
    expect(row(accepted.id)).toHaveTextContent('Đã gửi · Được chấp nhận', { exact: false })
    expect(row(duplicate.id)).toHaveTextContent('Đã gửi · Trùng: đã có lần quét sớm hơn', {
      exact: false,
    })
    expect(row(invalid.id)).toHaveTextContent('Đã gửi · Không hợp lệ (server)', { exact: false })
  })

  it('shows scans stuck for another staff member, and says who can send them', async () => {
    harness = makeHarness()
    const theirs = verified('THEIRCODE', { staffUserId: OTHER_STAFF })
    enqueueScan(harness.services.db, theirs)

    await renderWithServices(harness, <HistoryScreen />)

    expect(screen.getByLabelText(`scan-${theirs.id}`)).toHaveTextContent(
      'Chờ gửi · của nhân viên khác (gửi khi họ đăng nhập)',
      { exact: false },
    )
  })

  it('shows a device-rejected scan as an invalid ticket, never its content, and the room warnings', async () => {
    harness = makeHarness()
    const forged: NewScan = {
      id: randomUUID(),
      deviceVerdict: 'REJECTED_SIGNATURE',
      rejectionReason: 'SIGNATURE',
      rawPreview: 'attacker-controlled-text',
      workshopId: ROOM,
      scannedAt: '2026-11-01T02:00:00.000Z',
      deviceId: 'device',
      staffUserId: STAFF_ID,
      warning: null,
    }
    const wrongRoom = verified('WRONGROOMCODE', { warning: 'WRONG_ROOM' })
    enqueueScan(harness.services.db, forged)
    enqueueScan(harness.services.db, wrongRoom)

    await renderWithServices(harness, <HistoryScreen />)

    expect(screen.getByLabelText(`scan-${forged.id}`)).toHaveTextContent('Vé không hợp lệ', {
      exact: false,
    })
    expect(screen.queryByText(/attacker/)).toBeNull()
    expect(screen.getByLabelText(`scan-${wrongRoom.id}`)).toHaveTextContent('Sai phòng', {
      exact: false,
    })
  })
})

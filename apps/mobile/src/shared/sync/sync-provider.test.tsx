import { randomUUID } from 'node:crypto'

import { act, fireEvent, screen, waitFor } from '@testing-library/react-native'
import { Text } from 'react-native'

import { countPending, enqueueScan, type NewScan } from '@/shared/db/scan-queue'
import { fakeApi, httpError, offline } from '@/testing/fake-api'
import {
  makeHarness,
  renderWithServices,
  STAFF_ID,
  type Harness,
} from '@/testing/render-with-services'

import { useSync } from './SyncProvider'

let harness: Harness
afterEach(() => harness.close())

const scan = (): NewScan => ({
  id: randomUUID(),
  deviceVerdict: 'VERIFIED',
  ticketCode: `C${Math.random().toString(36).slice(2)}`,
  ticketWorkshopId: 'w',
  workshopId: 'w',
  scannedAt: '2026-11-01T02:00:00.000Z',
  deviceId: 'device',
  staffUserId: STAFF_ID,
  warning: null,
})

function Probe() {
  const sync = useSync()
  return (
    <>
      <Text accessibilityLabel="pending">{String(sync.pending)}</Text>
      <Text accessibilityLabel="outcome">{sync.lastOutcome?.kind ?? 'none'}</Text>
      <Text onPress={() => void sync.syncNow()}>sync</Text>
    </>
  )
}

describe('the sync provider (M-45)', () => {
  it('syncs once when it starts, because the first trigger is the start', async () => {
    const { api, batches } = fakeApi()
    harness = makeHarness({ api })
    enqueueScan(harness.services.db, scan())

    await renderWithServices(harness, <Probe />)

    await waitFor(() => expect(countPending(harness.services.db, STAFF_ID)).toBe(0))
    expect(batches).toHaveLength(1)
  })

  it('syncs on the minute while scans wait, and not while the queue is empty', async () => {
    const { api, batches } = fakeApi({ sync: () => Promise.reject(offline()) })
    harness = makeHarness({ api })
    await renderWithServices(harness, <Probe />)
    expect(batches).toHaveLength(0)

    await act(async () => harness.sources.fire())
    expect(batches).toHaveLength(0)

    enqueueScan(harness.services.db, scan())
    await act(async () => harness.sources.fire())
    await waitFor(() => expect(batches).toHaveLength(1))
    expect(countPending(harness.services.db, STAFF_ID)).toBe(1)
  })

  it('an expired token ends the session and keeps every scan', async () => {
    const { api } = fakeApi({ sync: () => Promise.reject(httpError(401)) })
    harness = makeHarness({ api })
    enqueueScan(harness.services.db, scan())
    enqueueScan(harness.services.db, scan())

    await renderWithServices(harness, <Probe />)

    await waitFor(() => expect(harness.session.get().session).toBeNull())
    expect(harness.session.get().expired).toBe(true)
    expect(countPending(harness.services.db, STAFF_ID)).toBe(2)
  })

  it('a failed sync reports FAILED and the scans stay', async () => {
    const { api } = fakeApi({ sync: () => Promise.reject(httpError(503)) })
    harness = makeHarness({ api })
    enqueueScan(harness.services.db, scan())
    await renderWithServices(harness, <Probe />)

    await fireEvent.press(screen.getByText('sync'))

    await waitFor(() => expect(screen.getByLabelText('outcome').props.children).toBe('FAILED'))
    expect(screen.getByLabelText('pending').props.children).toBe('1')
  })
})

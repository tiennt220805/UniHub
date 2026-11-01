import { randomUUID } from 'node:crypto'

import { act, fireEvent, screen, waitFor } from '@testing-library/react-native'

import { selectRoom } from '@/shared/db/rooms'
import { recentScans } from '@/shared/db/scan-queue'
import { rooms } from '@/shared/db/schema'
import { fakeApi, offline } from '@/testing/fake-api'
import { makeHarness, renderWithServices, type Harness } from '@/testing/render-with-services'
import { asVerificationKey, issueTicket, newTestKey } from '@/testing/tickets'

import { ScanScreen } from './ScanScreen'

const mockCamera: {
  props: { onBarcodeScanned?: (event: { data: string }) => void } | null
  mounts: number
} = {
  props: null,
  mounts: 0,
}

jest.mock('expo-camera', () => ({
  CameraView: (props: { onBarcodeScanned?: (event: { data: string }) => void }) => {
    mockCamera.props = props
    mockCamera.mounts++
    return null
  },
  useCameraPermissions: () => [{ granted: true }, jest.fn()],
}))
jest.mock('expo-router', () => {
  const { Text } = require('react-native')
  const { useEffect } = require('react')
  return {
    Link: ({ children }: { children: string }) => <Text>{children}</Text>,
    useRouter: () => ({ push: jest.fn() }),
    useFocusEffect: (effect: () => void) => useEffect(effect, [effect]),
  }
})

const ROOM = randomUUID()
const key = newTestKey()
let harness: Harness

function pickRoom(h: Harness) {
  h.services.db
    .insert(rooms)
    .values({ id: ROOM, title: 'Kỹ năng thuyết trình', location: 'Hội trường A', startTime: 'x' })
    .run()
  selectRoom(h.services.db, ROOM)
}

const scan = (data: string) =>
  act(async () => {
    mockCamera.props?.onBarcodeScanned?.({ data })
  })

beforeEach(() => {
  mockCamera.props = null
  mockCamera.mounts = 0
  jest.useFakeTimers({ doNotFake: ['nextTick', 'setImmediate'] })
})
afterEach(() => {
  jest.useRealTimers()
  harness.close()
})

describe('the scan screen (M-43, M-44, AC59.1)', () => {
  it('with no key cached, the camera is not shown and nothing can be scanned', async () => {
    harness = makeHarness({ key: null })
    pickRoom(harness)

    await renderWithServices(harness, <ScanScreen />)

    expect(screen.getByText(/Chưa có khóa xác minh nên chưa thể quét/)).toBeTruthy()
    expect(mockCamera.mounts).toBe(0)
    expect(recentScans(harness.services.db)).toHaveLength(0)
  })

  it('asks for a room before it scans, and shows no camera until there is one', async () => {
    harness = makeHarness({ key: asVerificationKey(key) })

    await renderWithServices(harness, <ScanScreen />)

    expect(screen.getByText('Chưa chọn phòng')).toBeTruthy()
    expect(screen.getByText(/chọn phòng \(workshop\) đang check-in/)).toBeTruthy()
    expect(mockCamera.mounts).toBe(0)
  })

  it('shows a valid ticket as valid pending reconciliation, after it is written to the queue', async () => {
    harness = makeHarness({ key: asVerificationKey(key) })
    pickRoom(harness)
    await renderWithServices(harness, <ScanScreen />)

    await scan(issueTicket(key, { ticketCode: 'ABCDEFGHJKMNPQRSTVWXYZ0123', workshopId: ROOM }))

    expect(screen.getByText(/Hợp lệ \(chờ đối soát\) · ABCDEFGHJKMNPQRSTVWXYZ0123/)).toBeTruthy()
    expect(recentScans(harness.services.db)).toHaveLength(1)
    expect(screen.getByLabelText('scans-pending').props.children.join('')).toContain(
      '1 lần quét chờ gửi',
    )
  })

  it('shows a forged ticket as invalid with the reason, and queues it as a rejection', async () => {
    harness = makeHarness({ key: asVerificationKey(key) })
    pickRoom(harness)
    await renderWithServices(harness, <ScanScreen />)

    await scan(issueTicket(key, {}, { signWith: newTestKey() }))

    expect(screen.getByText(/Vé không hợp lệ\. Chữ ký không đúng/)).toBeTruthy()
    expect(recentScans(harness.services.db)[0]?.deviceVerdict).toBe('REJECTED_SIGNATURE')
  })

  it('warns about the wrong room, and about a ticket already scanned on this device, with the time', async () => {
    let clock = new Date(2026, 10, 1, 9, 5)
    harness = makeHarness({ key: asVerificationKey(key), clock: () => clock })
    pickRoom(harness)
    await renderWithServices(harness, <ScanScreen />)
    const token = issueTicket(key, { workshopId: randomUUID() })

    await scan(token)
    expect(screen.getByText('Vé của workshop khác với phòng đang chọn.')).toBeTruthy()
    clock = new Date(2026, 10, 1, 9, 9)
    await scan(token)

    expect(screen.getByText('Vé này đã quét lúc 09:05.')).toBeTruthy()
    expect(recentScans(harness.services.db)).toHaveLength(2)
  })

  it('records one scan for one physical scan: the camera repeating a frame is ignored', async () => {
    harness = makeHarness({ key: asVerificationKey(key) })
    pickRoom(harness)
    await renderWithServices(harness, <ScanScreen />)
    const token = issueTicket(key, { workshopId: ROOM })

    await scan(token)
    await scan(token)
    await scan(token)

    expect(recentScans(harness.services.db)).toHaveLength(1)
  })

  it('never shows a ticket as valid when the scan could not be saved', async () => {
    harness = makeHarness({ key: asVerificationKey(key) })
    pickRoom(harness)
    await renderWithServices(harness, <ScanScreen />)
    harness.close()

    await scan(issueTicket(key, { workshopId: ROOM }))

    expect(screen.getByText('Không lưu được lần quét này. Hãy quét lại.')).toBeTruthy()
    expect(screen.queryByText(/Hợp lệ/)).toBeNull()
  })
})

describe('signing out with scans queued', () => {
  it('warns first, keeps the scans, and signs out only on the second press', async () => {
    harness = makeHarness({ key: asVerificationKey(key) })
    pickRoom(harness)
    await renderWithServices(harness, <ScanScreen />)
    await scan(issueTicket(key, { workshopId: ROOM }))

    await fireEvent.press(screen.getByText('Đăng xuất'))

    expect(screen.getByText(/Còn 1 lần quét chưa gửi/)).toBeTruthy()
    expect(harness.session.get().session).not.toBeNull()
    await fireEvent.press(screen.getByText('Hủy'))
    expect(screen.getByText('Đăng xuất')).toBeTruthy()

    await fireEvent.press(screen.getByText('Đăng xuất'))
    await fireEvent.press(screen.getByText('Vẫn đăng xuất'))

    await waitFor(() => expect(harness.session.get().session).toBeNull())
    expect(recentScans(harness.services.db)).toHaveLength(1)
  })

  it('signs out at once when nothing is queued', async () => {
    harness = makeHarness({ key: asVerificationKey(key) })
    await renderWithServices(harness, <ScanScreen />)

    await fireEvent.press(screen.getByText('Đăng xuất'))

    await waitFor(() => expect(harness.session.get().session).toBeNull())
  })
})

describe('the sync button', () => {
  it('says plainly when the server could not be reached, and keeps the count', async () => {
    const { api } = fakeApi({ sync: () => Promise.reject(offline()) })
    harness = makeHarness({ key: asVerificationKey(key), api })
    pickRoom(harness)
    await renderWithServices(harness, <ScanScreen />)
    await scan(issueTicket(key, { workshopId: ROOM }))

    await fireEvent.press(screen.getByText('Đồng bộ ngay'))

    await waitFor(() =>
      expect(screen.getByLabelText('sync-status')).toHaveTextContent('Chưa gửi được', {
        exact: false,
      }),
    )
    expect(screen.getByLabelText('scans-pending').props.children.join('')).toContain(
      '1 lần quét chờ gửi',
    )
  })

  it('sends the queue and the count drops to zero', async () => {
    const { api, batches } = fakeApi()
    harness = makeHarness({ key: asVerificationKey(key), api })
    pickRoom(harness)
    await renderWithServices(harness, <ScanScreen />)
    await scan(issueTicket(key, { workshopId: ROOM }))

    await fireEvent.press(screen.getByText('Đồng bộ ngay'))

    await waitFor(() =>
      expect(screen.getByLabelText('scans-pending').props.children.join('')).toContain(
        '0 lần quét chờ gửi',
      ),
    )
    expect(batches.flat()).toHaveLength(1)
  })
})

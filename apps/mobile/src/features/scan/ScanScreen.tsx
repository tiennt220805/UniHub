import { CameraView, useCameraPermissions } from 'expo-camera'
import { Link, useFocusEffect } from 'expo-router'
import { useCallback, useRef, useState } from 'react'
import { StyleSheet, Text, View } from 'react-native'

import { type Room, selectedRoom } from '@/shared/db/rooms'
import type { Db } from '@/shared/db/types'
import { useServices, useSession } from '@/shared/services/services'
import { useSync } from '@/shared/sync'
import { Button, Notice } from '@/shared/ui/components'
import { colors } from '@/shared/ui/theme'

import { clockTime, REJECTION_TEXT } from './messages'
import { processScan, type ScanOutcome } from './scan-flow'
import { SignOutButton } from './SignOutButton'

// The camera reports the same code many times a second while it stays in frame: one physical scan is
// one record. Holding the same code in front of it longer than this counts as a second scan, which
// is exactly what the "already scanned" warning is for.
const SAME_CODE_COOLDOWN_MS = 2_500

function readRoom(db: Db): Room | undefined {
  try {
    return selectedRoom(db)
  } catch {
    return undefined
  }
}

export function ScanScreen() {
  const { db, now, newId } = useServices()
  const key = useSession((state) => state.key)
  const deviceId = useSession((state) => state.deviceId)
  const staffUserId = useSession((state) => state.session?.user.id ?? null)
  const sync = useSync()
  const [permission, requestPermission] = useCameraPermissions()
  const [result, setResult] = useState<ScanOutcome | null>(null)
  const last = useRef<{ data: string; at: number } | null>(null)
  // The room is picked on another screen: read it again whenever this one comes back into focus,
  // not on every render, so an unreadable database cannot break the screen showing why.
  const [room, setRoom] = useState<Room | undefined>(() => readRoom(db))
  useFocusEffect(useCallback(() => setRoom(readRoom(db)), [db]))

  const canScan = key !== null && room !== undefined && deviceId !== null && staffUserId !== null

  const onScanned = useCallback(
    ({ data }: { data: string }) => {
      if (!canScan) return
      const at = now().getTime()
      if (
        last.current &&
        last.current.data === data &&
        at - last.current.at < SAME_CODE_COOLDOWN_MS
      ) {
        return
      }
      last.current = { data, at }
      setResult(
        processScan({ db, key, deviceId, staffUserId, roomWorkshopId: room.id, now, newId }, data),
      )
      sync.refreshCounts()
    },
    [canScan, db, deviceId, key, newId, now, room, staffUserId, sync],
  )

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <Text style={styles.room}>
          {room ? `${room.title} · ${room.location}` : 'Chưa chọn phòng'}
        </Text>
        <Link href="/rooms" style={styles.link}>
          {room ? 'Đổi phòng' : 'Chọn phòng'}
        </Link>
      </View>

      {key === null ? (
        <Notice tone="danger">
          Chưa có khóa xác minh nên chưa thể quét. Kết nối mạng và đăng nhập lại để tải khóa.
        </Notice>
      ) : room === undefined ? (
        <Notice tone="warn">Hãy chọn phòng (workshop) đang check-in trước khi quét.</Notice>
      ) : permission === null ? null : !permission.granted ? (
        <View style={styles.gap}>
          <Notice tone="warn">Cần quyền camera để quét mã QR.</Notice>
          <Button label="Cho phép camera" onPress={() => void requestPermission()} />
        </View>
      ) : (
        <CameraView
          style={styles.camera}
          facing="back"
          barcodeScannerSettings={{ barcodeTypes: ['qr'] }}
          onBarcodeScanned={onScanned}
        />
      )}

      {result && <ResultCard result={result} />}

      <View style={styles.footer}>
        <Text accessibilityLabel="scans-pending">
          {sync.pending} lần quét chờ gửi
          {sync.pendingOfOthers > 0 ? ` · ${sync.pendingOfOthers} của người khác` : ''}
        </Text>
        <Button
          label={sync.syncing ? 'Đang đồng bộ…' : 'Đồng bộ ngay'}
          onPress={() => void sync.syncNow()}
          disabled={sync.syncing}
        />
        <Link href="/history" style={styles.link}>
          Lịch sử quét
        </Link>
        <SignOutButton />
      </View>
    </View>
  )
}

function ResultCard({ result }: { result: ScanOutcome }) {
  switch (result.kind) {
    case 'VALID_PENDING':
      return (
        <View style={styles.gap}>
          <Notice tone="success">Hợp lệ (chờ đối soát) · {result.ticketCode}</Notice>
          {result.warnings.includes('REPEAT') && result.previousScanAt && (
            <Notice tone="warn">Vé này đã quét lúc {clockTime(result.previousScanAt)}.</Notice>
          )}
          {result.warnings.includes('WRONG_ROOM') && (
            <Notice tone="warn">Vé của workshop khác với phòng đang chọn.</Notice>
          )}
        </View>
      )
    case 'REJECTED':
      return <Notice tone="danger">Vé không hợp lệ. {REJECTION_TEXT[result.reason]}</Notice>
    case 'NO_KEY':
      return <Notice tone="danger">Chưa có khóa xác minh.</Notice>
    case 'WRITE_FAILED':
      return <Notice tone="danger">Không lưu được lần quét này. Hãy quét lại.</Notice>
  }
}

const styles = StyleSheet.create({
  container: { flex: 1, gap: 12, padding: 16, backgroundColor: colors.background },
  header: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', gap: 8 },
  room: { flex: 1, fontSize: 16, fontWeight: '600', color: colors.text },
  link: { color: colors.primary, fontSize: 15 },
  camera: { height: 320, borderRadius: 12, overflow: 'hidden' },
  gap: { gap: 8 },
  footer: { gap: 8 },
})

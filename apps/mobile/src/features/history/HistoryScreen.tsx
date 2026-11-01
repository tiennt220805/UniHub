import { useFocusEffect } from 'expo-router'
import { useCallback, useState } from 'react'
import { FlatList, StyleSheet, Text, View } from 'react-native'

import { recentScans, type StoredScan } from '@/shared/db/scan-queue'
import { useServices, useSession } from '@/shared/services/services'
import { colors } from '@/shared/ui/theme'

const OUTCOME_TEXT: Record<string, string> = {
  ACCEPTED: 'Được chấp nhận',
  REJECTED_DUPLICATE: 'Trùng: đã có lần quét sớm hơn',
  REJECTED_INVALID: 'Không hợp lệ (server)',
}

/** The line a scan gets: where it stands, and what the server decided once it has one. */
export function statusOf(scan: StoredScan, staffUserId: string | null): string {
  if (scan.syncState === 'SYNCED') {
    return `Đã gửi · ${OUTCOME_TEXT[scan.serverOutcome ?? ''] ?? scan.serverOutcome ?? 'đã đối soát'}`
  }
  return scan.staffUserId === staffUserId
    ? 'Chờ gửi'
    : 'Chờ gửi · của nhân viên khác (gửi khi họ đăng nhập)'
}

const timeOf = (iso: string) => new Date(iso).toLocaleString('vi-VN')

export function HistoryScreen() {
  const { db } = useServices()
  const staffUserId = useSession((state) => state.session?.user.id ?? null)
  const [scans, setScans] = useState<StoredScan[]>([])

  useFocusEffect(
    useCallback(() => {
      try {
        setScans(recentScans(db))
      } catch {
        setScans([])
      }
    }, [db]),
  )

  return (
    <View style={styles.container}>
      <FlatList
        data={scans}
        keyExtractor={(scan) => scan.id}
        ListEmptyComponent={<Text style={styles.muted}>Chưa có lần quét nào.</Text>}
        renderItem={({ item }) => (
          <View style={styles.row} accessibilityLabel={`scan-${item.id}`}>
            <Text style={styles.title}>
              {item.deviceVerdict === 'VERIFIED' ? item.ticketCode : 'Vé không hợp lệ'}
            </Text>
            <Text style={styles.muted}>{timeOf(item.scannedAt)}</Text>
            <Text>{statusOf(item, staffUserId)}</Text>
            {item.warning === 'WRONG_ROOM' && <Text style={styles.warn}>Sai phòng</Text>}
            {item.warning === 'REPEAT' && <Text style={styles.warn}>Quét lặp</Text>}
          </View>
        )}
      />
    </View>
  )
}

const styles = StyleSheet.create({
  container: { flex: 1, padding: 16, backgroundColor: colors.background },
  row: { borderBottomWidth: 1, borderColor: colors.border, paddingVertical: 10, gap: 2 },
  title: { fontSize: 15, fontWeight: '600', color: colors.text },
  muted: { color: colors.muted },
  warn: { color: colors.warnText },
})

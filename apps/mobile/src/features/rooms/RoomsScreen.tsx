import { useRouter } from 'expo-router'
import { useCallback, useEffect, useState } from 'react'
import { FlatList, Pressable, StyleSheet, Text } from 'react-native'

import { useServices } from '@/shared/services/services'
import { Button, Notice } from '@/shared/ui/components'
import { Screen } from '@/shared/ui/Screen'
import { colors } from '@/shared/ui/theme'

import { listRooms, refreshRooms, type Room, selectedRoom, selectRoom } from '@/shared/db/rooms'

/**
 * Which workshop is this room checking in? The scan is recorded for the room's workshop, not the
 * ticket's, so a ticket scanned in the wrong room is noticed (D51). The list is cached while online.
 */
export function RoomsScreen() {
  const { db, api } = useServices()
  const router = useRouter()
  const [rooms, setRooms] = useState<Room[]>(() => listRooms(db))
  const [failed, setFailed] = useState(false)
  const [loading, setLoading] = useState(false)
  const current = selectedRoom(db)

  const load = useCallback(async () => {
    setLoading(true)
    const ok = await refreshRooms(api, db)
    setFailed(!ok)
    setRooms(listRooms(db))
    setLoading(false)
  }, [api, db])

  // Load once when the screen opens.
  useEffect(() => {
    void load()
  }, [load])

  return (
    <Screen style={styles.container}>
      {failed && (
        <Notice tone="warn">
          Không tải được danh sách phòng. Đang dùng danh sách đã lưu trên máy.
        </Notice>
      )}
      <FlatList
        data={rooms}
        keyExtractor={(room) => room.id}
        ListEmptyComponent={
          <Text style={styles.muted}>Chưa có phòng nào. Kết nối mạng để tải.</Text>
        }
        renderItem={({ item }) => (
          <Pressable
            accessibilityRole="button"
            onPress={() => {
              selectRoom(db, item.id)
              router.back()
            }}
            style={[styles.row, current?.id === item.id && styles.selected]}
          >
            <Text style={styles.title}>{item.title}</Text>
            <Text style={styles.muted}>{item.location}</Text>
          </Pressable>
        )}
      />
      <Button
        label={loading ? 'Đang tải…' : 'Tải lại danh sách'}
        onPress={() => void load()}
        disabled={loading}
      />
    </Screen>
  )
}

const styles = StyleSheet.create({
  container: { flex: 1, gap: 12, padding: 16, backgroundColor: colors.background },
  row: { borderBottomWidth: 1, borderColor: colors.border, paddingVertical: 12, gap: 2 },
  selected: { backgroundColor: '#eff6ff' },
  title: { fontSize: 16, fontWeight: '600', color: colors.text },
  muted: { color: colors.muted },
})

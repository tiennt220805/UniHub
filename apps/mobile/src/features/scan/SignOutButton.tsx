import { useState } from 'react'
import { StyleSheet, View } from 'react-native'

import { useServices } from '@/shared/services/services'
import { useSync } from '@/shared/sync'
import { Button, Notice } from '@/shared/ui/components'

/**
 * Signing out with scans still queued is allowed but never silent: they stay on this device, and
 * only this person's next session can send them (`checkin.md` §7, D53).
 */
export function SignOutButton() {
  const { session } = useServices()
  const { pending } = useSync()
  const [confirming, setConfirming] = useState(false)

  if (confirming && pending > 0) {
    return (
      <View style={styles.box}>
        <Notice tone="warn">
          Còn {pending} lần quét chưa gửi. Chúng được giữ trên máy và chỉ gửi khi bạn đăng nhập lại.
        </Notice>
        <Button label="Vẫn đăng xuất" onPress={() => void session.signOut()} />
        <Button label="Hủy" variant="outline" onPress={() => setConfirming(false)} />
      </View>
    )
  }
  return (
    <Button
      label="Đăng xuất"
      variant="outline"
      onPress={() => (pending > 0 ? setConfirming(true) : void session.signOut())}
    />
  )
}

const styles = StyleSheet.create({ box: { gap: 8 } })

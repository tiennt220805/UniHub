import { useState } from 'react'
import { ActivityIndicator, StyleSheet, Text, TextInput, View } from 'react-native'

import { env } from '@/shared/config/env'
import { useServices, useSession } from '@/shared/services/services'
import { Button, Notice } from '@/shared/ui/components'
import { colors } from '@/shared/ui/theme'

type Failure = 'BAD_CREDENTIALS' | 'NOT_STAFF' | 'UNAVAILABLE'

const FAILURE_TEXT: Record<Failure, string> = {
  BAD_CREDENTIALS: 'Email hoặc mật khẩu không đúng.',
  NOT_STAFF: 'Tài khoản này không phải nhân viên check-in.',
  UNAVAILABLE: `Không kết nối được máy chủ (${env.apiBaseUrl}). Kiểm tra mạng rồi thử lại.`,
}

/** Sign-in for Check-in Staff. Needs the network once, to log in and to fetch the verification key. */
export function SignInScreen() {
  const { session } = useServices()
  const expired = useSession((state) => state.expired)
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [failure, setFailure] = useState<Failure | null>(null)
  const [busy, setBusy] = useState(false)

  const submit = async () => {
    setBusy(true)
    setFailure(null)
    try {
      const result = await session.signIn(email.trim(), password)
      if (result.kind !== 'SIGNED_IN') setFailure(result.kind)
    } finally {
      setBusy(false)
    }
  }

  return (
    <View style={styles.container}>
      <Text style={styles.title}>UniHub Check-in</Text>
      {expired && (
        <Notice tone="warn">
          Phiên đăng nhập đã hết hạn. Các lần quét chưa gửi vẫn được giữ trên máy.
        </Notice>
      )}
      <TextInput
        accessibilityLabel="Email"
        autoCapitalize="none"
        autoCorrect={false}
        keyboardType="email-address"
        onChangeText={setEmail}
        placeholder="Email"
        style={styles.input}
        value={email}
      />
      <TextInput
        accessibilityLabel="Mật khẩu"
        autoCapitalize="none"
        onChangeText={setPassword}
        placeholder="Mật khẩu"
        secureTextEntry
        style={styles.input}
        value={password}
      />
      {failure && <Notice tone="danger">{FAILURE_TEXT[failure]}</Notice>}
      <Button
        label="Đăng nhập"
        onPress={() => void submit()}
        disabled={busy || !email || !password}
      />
      {busy && <ActivityIndicator />}
    </View>
  )
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    gap: 12,
    justifyContent: 'center',
    padding: 24,
    backgroundColor: colors.background,
  },
  title: { fontSize: 26, fontWeight: '700', color: colors.text },
  input: {
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: 8,
    padding: 12,
    fontSize: 16,
    color: colors.text,
  },
})

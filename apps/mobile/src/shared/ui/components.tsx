import type { ReactNode } from 'react'
import { Pressable, StyleSheet, Text, type ViewStyle } from 'react-native'

import { colors } from './theme'

export function Button({
  label,
  onPress,
  disabled = false,
  variant = 'primary',
  style,
}: {
  label: string
  onPress: () => void
  disabled?: boolean
  variant?: 'primary' | 'outline'
  style?: ViewStyle
}) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ disabled }}
      disabled={disabled}
      onPress={onPress}
      style={[
        styles.button,
        variant === 'outline' ? styles.outline : styles.primary,
        disabled && styles.disabled,
        style,
      ]}
    >
      <Text style={variant === 'outline' ? styles.outlineLabel : styles.primaryLabel}>{label}</Text>
    </Pressable>
  )
}

export function Notice({
  tone,
  children,
}: {
  tone: 'success' | 'danger' | 'warn'
  children: ReactNode
}) {
  const tones = {
    success: [colors.successBg, colors.successText],
    danger: [colors.dangerBg, colors.dangerText],
    warn: [colors.warnBg, colors.warnText],
  } as const
  const [background, color] = tones[tone]
  return (
    <Text accessibilityRole="alert" style={[styles.notice, { backgroundColor: background, color }]}>
      {children}
    </Text>
  )
}

const styles = StyleSheet.create({
  button: { borderRadius: 8, paddingHorizontal: 16, paddingVertical: 12, alignItems: 'center' },
  primary: { backgroundColor: colors.primary },
  outline: { borderWidth: 1, borderColor: colors.border, backgroundColor: colors.background },
  disabled: { opacity: 0.5 },
  primaryLabel: { color: colors.onPrimary, fontWeight: '600' },
  outlineLabel: { color: colors.text, fontWeight: '600' },
  notice: { borderRadius: 8, padding: 12, fontSize: 15 },
})

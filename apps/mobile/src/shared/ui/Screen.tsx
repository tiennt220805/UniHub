import type { ReactNode } from 'react'
import type { ViewStyle } from 'react-native'
import { SafeAreaView } from 'react-native-safe-area-context'

import { colors } from './theme'

/**
 * The root of every screen: keeps its content out from under the status bar, the notch and the home
 * indicator, so nothing a staff member must tap sits where the phone's own controls are.
 */
export function Screen({ children, style }: { children: ReactNode; style?: ViewStyle }) {
  return (
    <SafeAreaView
      edges={['top', 'bottom', 'left', 'right']}
      style={[{ flex: 1, backgroundColor: colors.background }, style]}
    >
      {children}
    </SafeAreaView>
  )
}

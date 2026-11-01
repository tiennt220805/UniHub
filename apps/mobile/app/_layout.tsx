import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Stack } from 'expo-router'
import { StatusBar } from 'expo-status-bar'
import { useEffect, useState } from 'react'
import { ActivityIndicator, View } from 'react-native'

import { createServices } from '@/bootstrap/create-services'
import {
  ServicesProvider,
  useServices,
  useSession,
  type Services,
} from '@/shared/services/services'

const queryClient = new QueryClient()

export default function RootLayout() {
  const [services] = useState<Services>(() => createServices())
  return (
    <QueryClientProvider client={queryClient}>
      <ServicesProvider services={services}>
        <Gate />
      </ServicesProvider>
    </QueryClientProvider>
  )
}

// Reads the stored session, key and device id once before any screen is shown.
function Gate() {
  const { session } = useServices()
  const phase = useSession((state) => state.phase)
  useEffect(() => {
    void session.bootstrap()
  }, [session])

  if (phase === 'loading') {
    return (
      <View style={{ flex: 1, justifyContent: 'center' }}>
        <ActivityIndicator />
      </View>
    )
  }
  return (
    <>
      <StatusBar style="dark" />
      <Stack screenOptions={{ headerShown: false }} />
    </>
  )
}

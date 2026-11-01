import { Redirect, Stack } from 'expo-router'

import { syncSources } from '@/app/create-services'
import { useSession } from '@/shared/services/services'
import { SyncProvider } from '@/shared/sync'

// The session is the guard: without one, nothing here is shown. The Backend API checks the token on
// every request regardless; this only keeps the screens out of sight.
export default function StaffLayout() {
  const signedIn = useSession((state) => state.session !== null)
  if (!signedIn) return <Redirect href="/sign-in" />
  return (
    <SyncProvider sources={syncSources}>
      <Stack>
        <Stack.Screen name="scan" options={{ title: 'Quét vé' }} />
        <Stack.Screen name="rooms" options={{ title: 'Chọn phòng' }} />
        <Stack.Screen name="history" options={{ title: 'Lịch sử quét' }} />
      </Stack>
    </SyncProvider>
  )
}

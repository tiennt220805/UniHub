import { createBrowserRouter, RouterProvider } from 'react-router'

import { queryClient } from '@/shared/lib/query-client'
import { Toaster } from '@/shared/ui/toaster'
import { AppProviders } from './providers/AppProviders'
import { wireHttpClient } from './providers/wire-http-client'
import { routes } from './router/routes'

wireHttpClient(queryClient)

const router = createBrowserRouter(routes)

export function App() {
  return (
    <AppProviders>
      <RouterProvider router={router} />
      <Toaster />
    </AppProviders>
  )
}

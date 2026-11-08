import { Navigate, type RouteObject } from 'react-router'

import { Permission } from '@/features/auth'
import { WorkshopReportPage } from '@/routes/admin/WorkshopReportPage'
import { SignInPage } from '@/routes/auth/SignInPage'
import { CheckoutPage } from '@/routes/me/CheckoutPage'
import { MyRegistrationsPage } from '@/routes/me/MyRegistrationsPage'
import { NotificationsPage } from '@/routes/me/NotificationsPage'
import { RegistrationTicketPage } from '@/routes/me/RegistrationTicketPage'
import { WorkshopCatalogPage } from '@/routes/public/WorkshopCatalogPage'
import { WorkshopDetailPage } from '@/routes/public/WorkshopDetailPage'
import { AppLayout } from './AppLayout'
import { RequirePermission } from './guards/RequirePermission'
import { RequireSession } from './guards/RequireSession'

// The CSP spike page (task 2.3). `import.meta.env.VITE_SPIKE` is read here, not through shared/config/env.ts, on
// purpose: Vite replaces it with a constant at build time, so a build without the flag drops the whole branch and
// the page, with the libraries only it uses, is not in the production bundle. A Zod-parsed runtime value would
// leave the dynamic import() in the bundle.
const spikeRoutes: RouteObject[] =
  import.meta.env.VITE_SPIKE === 'true'
    ? [
        {
          path: '/__spike',
          lazy: async () => ({ Component: (await import('@/routes/dev/SpikePage')).SpikePage }),
        },
      ]
    : []

export const routes: RouteObject[] = [
  {
    element: <AppLayout />,
    children: [
      ...spikeRoutes,
      { path: '/', element: <Navigate to="/workshops" replace /> },
      { path: '/workshops', element: <WorkshopCatalogPage /> },
      { path: '/workshops/:workshopId', element: <WorkshopDetailPage /> },
      { path: '/login', element: <SignInPage /> },
      {
        // listMyRegistrations declares no permission: it is scoped to the caller, so a session is enough.
        path: '/me/registrations',
        element: (
          <RequireSession>
            <MyRegistrationsPage />
          </RequireSession>
        ),
      },
      {
        // getRegistration declares no permission: the Backend answers 403 for someone else's registration.
        path: '/me/registrations/:registrationId',
        element: (
          <RequireSession>
            <RegistrationTicketPage />
          </RequireSession>
        ),
      },
      {
        // listMyNotifications declares no permission either: scoped to the caller, a session is enough.
        path: '/me/notifications',
        element: (
          <RequireSession>
            <NotificationsPage />
          </RequireSession>
        ),
      },
      {
        // getWorkshopReport is guarded by REPORT_VIEW (held by Organizer and Admin).
        path: '/admin/workshops/:workshopId/report',
        element: (
          <RequirePermission anyOf={[Permission.REPORT_VIEW]}>
            <WorkshopReportPage />
          </RequirePermission>
        ),
      },
      {
        // chargePayment is guarded by REGISTRATION_CREATE (web-coding-standards.md §13 item 12).
        path: '/me/checkout/:registrationId',
        element: (
          <RequirePermission anyOf={[Permission.REGISTRATION_CREATE]}>
            <CheckoutPage />
          </RequirePermission>
        ),
      },
    ],
  },
]

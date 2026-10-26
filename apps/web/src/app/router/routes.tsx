import { Navigate, type RouteObject } from 'react-router'

import { Permission } from '@/features/auth'
import { SignInPage } from '@/routes/auth/SignInPage'
import { CheckoutPage } from '@/routes/me/CheckoutPage'
import { MyRegistrationsPage } from '@/routes/me/MyRegistrationsPage'
import { WorkshopCatalogPage } from '@/routes/public/WorkshopCatalogPage'
import { WorkshopDetailPage } from '@/routes/public/WorkshopDetailPage'
import { AppLayout } from './AppLayout'
import { RequirePermission } from './guards/RequirePermission'
import { RequireSession } from './guards/RequireSession'

export const routes: RouteObject[] = [
  {
    element: <AppLayout />,
    children: [
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

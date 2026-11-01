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

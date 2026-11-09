import { type RouteObject } from 'react-router'

import { Permission } from '@/features/auth'
import { AdminIndexPage } from '@/routes/admin/AdminIndexPage'
import { WorkshopCreatePage } from '@/routes/admin/WorkshopCreatePage'
import { WorkshopEditPage } from '@/routes/admin/WorkshopEditPage'
import { WorkshopListPage } from '@/routes/admin/WorkshopListPage'
import { WorkshopReportPage } from '@/routes/admin/WorkshopReportPage'
import { SignInPage } from '@/routes/auth/SignInPage'
import { CheckoutPage } from '@/routes/me/CheckoutPage'
import { MyRegistrationsPage } from '@/routes/me/MyRegistrationsPage'
import { NotificationsPage } from '@/routes/me/NotificationsPage'
import { RegistrationTicketPage } from '@/routes/me/RegistrationTicketPage'
import { HomePage } from '@/routes/public/HomePage'
import { NotFoundPage } from '@/routes/public/NotFoundPage'
import { WorkshopCatalogPage } from '@/routes/public/WorkshopCatalogPage'
import { WorkshopDetailPage } from '@/routes/public/WorkshopDetailPage'
import { AdminLayout } from './AdminLayout'
import { StudentLayout } from './StudentLayout'
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

// Whoever holds any one of these may enter /admin (S9). Each page inside still asks for its own permission.
const ADMIN_ACCESS = [Permission.WORKSHOP_CREATE, Permission.WORKSHOP_EDIT, Permission.REPORT_VIEW]

export const routes: RouteObject[] = [
  {
    // The administration shell (D136): its own frame, not the student one. The guard is the outer element, so a visitor
    // who may not enter sees the refusal, not an admin sidebar around it.
    path: '/admin',
    element: (
      <RequirePermission anyOf={ADMIN_ACCESS}>
        <AdminLayout />
      </RequirePermission>
    ),
    children: [
      { index: true, element: <AdminIndexPage /> },
      {
        // listManagedWorkshops is guarded by WORKSHOP_EDIT; WORKSHOP_CREATE alone does not list (paths/workshop.yaml).
        path: 'workshops',
        element: (
          <RequirePermission anyOf={[Permission.WORKSHOP_EDIT]}>
            <WorkshopListPage />
          </RequirePermission>
        ),
      },
      {
        // createWorkshop is guarded by WORKSHOP_CREATE. Declared before ':workshopId' so "new" is not read as an id.
        path: 'workshops/new',
        element: (
          <RequirePermission anyOf={[Permission.WORKSHOP_CREATE]}>
            <WorkshopCreatePage />
          </RequirePermission>
        ),
      },
      {
        // The page of one workshop: getWorkshop (a manager reads the Master) is let in by either permission; the form is
        // editable, and publish and cancel are offered, only with WORKSHOP_EDIT (D166).
        path: 'workshops/:workshopId',
        element: (
          <RequirePermission anyOf={[Permission.WORKSHOP_CREATE, Permission.WORKSHOP_EDIT]}>
            <WorkshopEditPage />
          </RequirePermission>
        ),
      },
      {
        // getWorkshopReport is guarded by REPORT_VIEW (held by Organizer and Admin).
        path: 'workshops/:workshopId/report',
        element: (
          <RequirePermission anyOf={[Permission.REPORT_VIEW]}>
            <WorkshopReportPage />
          </RequirePermission>
        ),
      },
    ],
  },
  {
    element: <StudentLayout />,
    children: [
      ...spikeRoutes,
      { path: '/', element: <HomePage /> },
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
        // chargePayment is guarded by REGISTRATION_CREATE (web-coding-standards.md §13 item 12).
        path: '/me/checkout/:registrationId',
        element: (
          <RequirePermission anyOf={[Permission.REGISTRATION_CREATE]}>
            <CheckoutPage />
          </RequirePermission>
        ),
      },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]

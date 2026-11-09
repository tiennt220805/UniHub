import { screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'

import type { Permission } from '@/features/auth'
import { aWorkshopReport, aAttendancePage } from '@/testing/factories/report'
import { signInAs } from '@/testing/factories/session'
import { aWorkshop, aWorkshopPage, aWorkshopSummary, WORKSHOP_ID } from '@/testing/factories/workshop'
import { server } from '@/testing/msw-server'
import { renderRoute } from '@/testing/render-route'

// Task 6.9 (Rules #13): every administration route asks for a permission, never for a role name. This walks the four routes
// with each permission alone and with none, so a route that is let in by the wrong code, or by no code, fails here.

beforeEach(() => {
  server.use(
    http.get('/api/v1/admin/workshops', () => HttpResponse.json(aWorkshopPage([aWorkshopSummary()]))),
    http.get('/api/v1/workshops/:id', () => HttpResponse.json(aWorkshop())),
    http.get('/api/v1/workshops/:id/report', () => HttpResponse.json(aWorkshopReport())),
    http.get('/api/v1/workshops/:id/attendances', () => HttpResponse.json(aAttendancePage([]))),
  )
})

const ROUTES = {
  list: { path: '/admin/workshops', needs: ['WORKSHOP_EDIT'] },
  create: { path: '/admin/workshops/new', needs: ['WORKSHOP_CREATE'] },
  one: { path: `/admin/workshops/${WORKSHOP_ID}`, needs: ['WORKSHOP_CREATE', 'WORKSHOP_EDIT'] },
  report: { path: `/admin/workshops/${WORKSHOP_ID}/report`, needs: ['REPORT_VIEW'] },
} as const satisfies Record<string, { path: string; needs: Permission[] }>

const HELD: Permission[] = ['WORKSHOP_CREATE', 'WORKSHOP_EDIT', 'REPORT_VIEW']
const REFUSED = 'Bạn không có quyền truy cập trang này.'

describe.each(Object.entries(ROUTES))('%s route', (_name, route) => {
  for (const held of HELD) {
    const allowed = (route.needs as readonly Permission[]).includes(held)
    it(`${allowed ? 'lets in' : 'refuses'} a session holding only ${held}`, async () => {
      signInAs({ permissions: [held] })
      renderRoute(route.path)
      if (allowed) {
        await screen.findByRole('complementary', { name: 'Thanh bên quản trị' })
        await waitFor(() => expect(screen.queryByText(REFUSED)).not.toBeInTheDocument())
      } else {
        expect(await screen.findByRole('alert')).toHaveTextContent(REFUSED)
      }
    })
  }

  it('refuses a session with none of them, whatever its role says, and shows no admin sidebar around the refusal', async () => {
    signInAs({ permissions: ['REGISTRATION_CREATE'] }) // a Student: the factory's role name is "Student"
    renderRoute(route.path)
    expect(await screen.findByRole('alert')).toHaveTextContent(REFUSED)
    expect(screen.queryByRole('complementary', { name: 'Thanh bên quản trị' })).not.toBeInTheDocument()
  })

  it('sends someone who is not signed in to the sign-in page', async () => {
    const { router } = renderRoute(route.path)
    await waitFor(() => expect(router.state.location.pathname).toBe('/login'))
  })
})

describe('the role name decides nothing', () => {
  it('lets in a session whose role says "Student" but whose permissions are the Organizer\'s', async () => {
    signInAs({ permissions: HELD }) // the factory names the role "Student"
    renderRoute('/admin/workshops')
    expect(await screen.findByRole('table')).toBeInTheDocument()
  })
})

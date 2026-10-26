import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { describe, expect, it } from 'vitest'

import { Permission } from '@/features/auth'
import { signInAs } from '@/testing/factories/session'
import { RequirePermission } from '../RequirePermission'
import { RequireSession } from '../RequireSession'

function renderGuarded(element: React.ReactNode) {
  const router = createMemoryRouter(
    [
      { path: '/secret', element },
      { path: '/login', element: <p>sign-in page</p> },
    ],
    { initialEntries: ['/secret?tab=1'] },
  )
  render(
    <QueryClientProvider client={new QueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

describe('RequireSession', () => {
  it('sends an anonymous visitor to sign-in, remembering where they were headed', () => {
    const router = renderGuarded(
      <RequireSession>
        <p>private</p>
      </RequireSession>,
    )

    expect(screen.getByText('sign-in page')).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/login')
    expect(router.state.location.state).toMatchObject({
      from: { pathname: '/secret', search: '?tab=1' },
    })
  })

  it('sends a visitor whose token has expired to sign-in', () => {
    signInAs({ expiresAt: Date.now() - 1 })

    renderGuarded(
      <RequireSession>
        <p>private</p>
      </RequireSession>,
    )

    expect(screen.getByText('sign-in page')).toBeInTheDocument()
  })

  it('shows the page to a signed-in user', () => {
    signInAs()

    renderGuarded(
      <RequireSession>
        <p>private</p>
      </RequireSession>,
    )

    expect(screen.getByText('private')).toBeInTheDocument()
  })
})

describe('RequirePermission', () => {
  it('shows the page when the session holds any one of the listed permissions', () => {
    signInAs({ permissions: [Permission.WORKSHOP_EDIT] })

    renderGuarded(
      <RequirePermission anyOf={[Permission.WORKSHOP_CREATE, Permission.WORKSHOP_EDIT]}>
        <p>editor</p>
      </RequirePermission>,
    )

    expect(screen.getByText('editor')).toBeInTheDocument()
  })

  it('shows a no-permission state, not the page, when the permission is missing', () => {
    signInAs({ permissions: [Permission.REGISTRATION_CREATE] })

    renderGuarded(
      <RequirePermission anyOf={[Permission.WORKSHOP_CREATE]}>
        <p>editor</p>
      </RequirePermission>,
    )

    expect(screen.queryByText('editor')).not.toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('không có quyền')
  })

  it('sends an anonymous visitor to sign-in', () => {
    renderGuarded(
      <RequirePermission anyOf={[Permission.WORKSHOP_CREATE]}>
        <p>editor</p>
      </RequirePermission>,
    )

    expect(screen.getByText('sign-in page')).toBeInTheDocument()
  })
})

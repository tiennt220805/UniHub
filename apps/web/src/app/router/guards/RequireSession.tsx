import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router'

import { useSession } from '@/features/auth'

// UX gating only: the Backend API checks the token on every request (web-coding-standards.md §11).
// The location is kept so sign-in can return the user where they were headed.
export function RequireSession({ children }: { children: ReactNode }) {
  const session = useSession()
  const location = useLocation()
  if (!session) return <Navigate to="/login" state={{ from: location }} replace />
  return children
}

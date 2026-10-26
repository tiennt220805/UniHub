import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router'

import { hasAnyPermission, type Permission, useSession } from '@/features/auth'
import { NoPermission } from '@/shared/components/NoPermission'

// `anyOf`: an operation listing several permissions is satisfied by any one of them (OR, never AND).
export function RequirePermission({
  anyOf,
  children,
}: {
  anyOf: readonly Permission[]
  children: ReactNode
}) {
  const session = useSession()
  const location = useLocation()
  if (!session) return <Navigate to="/login" state={{ from: location }} replace />
  if (!hasAnyPermission(session, anyOf)) return <NoPermission />
  return children
}

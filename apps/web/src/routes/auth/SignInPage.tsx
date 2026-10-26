import { Navigate, useLocation } from 'react-router'

import { SignInForm, useSession } from '@/features/auth'
import { safeReturnPath } from './return-path'

export function SignInPage() {
  const session = useSession()
  const location = useLocation()
  const returnTo = safeReturnPath(location.state)

  // Already signed in, or just signed in: the session appearing is what moves the user on.
  if (session) return <Navigate to={returnTo} replace />

  return (
    <main className="mx-auto max-w-sm p-6">
      <h1 className="mb-6 text-2xl font-semibold text-foreground">Đăng nhập</h1>
      <SignInForm />
    </main>
  )
}

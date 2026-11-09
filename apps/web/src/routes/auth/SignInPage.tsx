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
    <main className="mx-auto max-w-md px-4 py-10 md:py-16">
      <div className="rounded-2xl border border-border bg-card p-6 shadow-sm md:p-8 dark:shadow-none">
        <h1 className="text-3xl leading-[38px] font-bold text-foreground">Đăng nhập</h1>
        <p className="mt-1 mb-6 text-muted-foreground">Nhập email và mật khẩu của tài khoản UniHub.</p>
        <SignInForm />
      </div>
    </main>
  )
}

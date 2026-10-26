import { Link, Outlet } from 'react-router'

import { useSession, useSignOut } from '@/features/auth'
import { Button } from '@/shared/ui/button'

// The frame around every page: navigation and who is signed in. The role labels are display text
// ("Signed in as: Student"), never an input to any condition (web-coding-standards.md §11).
export function AppLayout() {
  const session = useSession()
  const signOut = useSignOut()

  return (
    <>
      <header className="border-b">
        <nav
          aria-label="Điều hướng chính"
          className="mx-auto flex max-w-3xl flex-wrap items-center gap-4 p-4 text-sm"
        >
          <Link to="/workshops" className="font-medium">
            Workshop
          </Link>
          {session && (
            <Link to="/me/registrations" className="hover:underline">
              Đăng ký của tôi
            </Link>
          )}
          <span className="ml-auto flex items-center gap-3">
            {session ? (
              <>
                <span className="text-muted-foreground">
                  {session.user.fullName}
                  {session.user.roles.length > 0 && ` (${session.user.roles.join(', ')})`}
                </span>
                <Button variant="outline" size="sm" onClick={signOut}>
                  Đăng xuất
                </Button>
              </>
            ) : (
              <Link to="/login" className="hover:underline">
                Đăng nhập
              </Link>
            )}
          </span>
        </nav>
      </header>
      <Outlet />
    </>
  )
}

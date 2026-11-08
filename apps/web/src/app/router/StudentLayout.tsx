import { Bell, Menu } from 'lucide-react'
import type { ComponentProps, ReactNode } from 'react'
import { Link, NavLink, Outlet } from 'react-router'

import { useSession, useSignOut } from '@/features/auth'
import { cn } from '@/shared/lib/utils'
import { Button } from '@/shared/ui/button'
import { Logo } from '@/shared/ui/logo'
import { Sheet, SheetClose, SheetContent, SheetDescription, SheetTitle, SheetTrigger } from '@/shared/ui/sheet'
import { ThemeToggle } from '@/shared/ui/theme-toggle'

// The frame around the student pages (G1, D136): a top bar, with the links in the bar from `md` up and behind a menu
// sheet below it. Its job is navigation and who is signed in; the role labels are display text ("Student"), never an
// input to any condition (web-coding-standards.md §11). It renders no <main>: each page has its own.
//
// Names the E2E and page tests rely on stay as they were: the links "Workshop", "Đăng ký của tôi", "Thông báo" and
// "Đăng nhập", the button "Đăng xuất", and the theme buttons. The bell on a phone is named differently ("Hộp thư thông
// báo") so that "Thông báo" is still one link. There is no unread count on it: a notification has no read state (M-81).

// Forwards every prop: inside the sheet a SheetClose wraps it with `asChild`, and the click that closes the sheet
// arrives here as an `onClick` it has to hand on to the link.
function NavItem({ mobile, ...props }: Omit<ComponentProps<typeof NavLink>, 'className'> & { mobile?: boolean }) {
  return (
    <NavLink
      {...props}
      className={cn(
        'rounded-lg px-3 py-2 text-sm font-medium text-muted-foreground transition-colors duration-150 outline-none hover:bg-accent hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring aria-[current=page]:bg-accent aria-[current=page]:font-semibold aria-[current=page]:text-foreground',
        mobile && 'block py-3 text-base',
      )}
    />
  )
}

function Links({ signedIn, mobile }: { signedIn: boolean; mobile?: boolean }) {
  const wrap = (node: ReactNode) => (mobile ? <SheetClose asChild>{node}</SheetClose> : node)
  return (
    <>
      {wrap(<NavItem to="/workshops" mobile={mobile}>Workshop</NavItem>)}
      {signedIn && wrap(<NavItem to="/me/registrations" mobile={mobile}>Đăng ký của tôi</NavItem>)}
      {signedIn && wrap(<NavItem to="/me/notifications" mobile={mobile}>Thông báo</NavItem>)}
    </>
  )
}

export function StudentLayout() {
  const session = useSession()
  const signOut = useSignOut()

  return (
    <>
      <header className="sticky top-0 z-40 border-b border-border bg-card">
        <div className="mx-auto flex h-14 max-w-6xl items-center gap-2 px-4 md:h-16 md:gap-4 md:px-6">
          <Link to="/" aria-label="UniHub" className="rounded-lg outline-none focus-visible:ring-2 focus-visible:ring-ring">
            <Logo />
          </Link>

          <nav aria-label="Điều hướng chính" className="hidden flex-1 items-center gap-1 md:flex">
            <Links signedIn={!!session} />
          </nav>

          <div className="ml-auto hidden items-center gap-3 md:flex">
            <ThemeToggle />
            {session ? (
              <>
                <span className="text-sm text-muted-foreground">{session.user.fullName}</span>
                <Button variant="outline" size="sm" onClick={signOut}>
                  Đăng xuất
                </Button>
              </>
            ) : (
              <Button asChild variant="outline" size="sm">
                <Link to="/login">Đăng nhập</Link>
              </Button>
            )}
          </div>

          <div className="ml-auto flex items-center gap-1 md:hidden">
            {session && (
              <Button asChild variant="ghost" size="icon-lg" aria-label="Hộp thư thông báo">
                <Link to="/me/notifications">
                  <Bell className="size-5" />
                </Link>
              </Button>
            )}
            <Sheet>
              <SheetTrigger asChild>
                <Button variant="ghost" size="icon-lg" aria-label="Mở menu">
                  <Menu className="size-5" />
                </Button>
              </SheetTrigger>
              <SheetContent side="right" className="w-72 gap-0 p-4">
                <SheetTitle className="text-lg font-semibold">Menu</SheetTitle>
                <SheetDescription className="sr-only">Các mục điều hướng và cài đặt hiển thị.</SheetDescription>
                <nav aria-label="Điều hướng chính" className="mt-4 grid gap-1">
                  <Links signedIn={!!session} mobile />
                </nav>
                <div className="mt-auto grid gap-3 border-t border-border pt-4">
                  <div className="flex items-center justify-between text-sm text-muted-foreground">
                    <span>Chế độ hiển thị</span>
                    <ThemeToggle />
                  </div>
                  {session ? (
                    <>
                      <span className="text-sm text-muted-foreground">{session.user.fullName}</span>
                      <SheetClose asChild>
                        <Button variant="outline" size="lg" onClick={signOut}>
                          Đăng xuất
                        </Button>
                      </SheetClose>
                    </>
                  ) : (
                    <SheetClose asChild>
                      <Button asChild variant="outline" size="lg">
                        <Link to="/login">Đăng nhập</Link>
                      </Button>
                    </SheetClose>
                  )}
                </div>
              </SheetContent>
            </Sheet>
          </div>
        </div>
      </header>
      <Outlet />
    </>
  )
}

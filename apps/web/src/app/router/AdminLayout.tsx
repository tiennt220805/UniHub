import { House, LogOut, Menu } from 'lucide-react'
import { Link, NavLink, Outlet } from 'react-router'

import { hasAnyPermission, useSession, useSignOut } from '@/features/auth'
import { cn } from '@/shared/lib/utils'
import { Button } from '@/shared/ui/button'
import { Logo } from '@/shared/ui/logo'
import { Sheet, SheetClose, SheetContent, SheetDescription, SheetTitle, SheetTrigger } from '@/shared/ui/sheet'
import { ThemeToggle } from '@/shared/ui/theme-toggle'
import { ADMIN_NAV } from './admin-nav'

// The frame around /admin/* (G1, D136): a sidebar from 1024 px, an icon rail from 768 px (D128: usable from 768, tuned for 1280),
// and below 768 px a top bar whose menu opens the same links in a sheet. The route is guarded by RequirePermission in
// routes.tsx; this component only draws the frame. It renders no <main>: each page has its own.
//
// In the rail a link's text is `sr-only`, not `hidden`: display:none would take the accessible name away with the text.

const linkClasses =
  'flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium text-sidebar-foreground transition-colors duration-150 outline-none hover:bg-sidebar-accent focus-visible:ring-2 focus-visible:ring-sidebar-ring aria-[current=page]:bg-sidebar-accent aria-[current=page]:font-semibold'

function SidebarBody({ collapsible }: { collapsible: boolean }) {
  const session = useSession()
  const signOut = useSignOut()
  const label = collapsible ? 'sr-only lg:not-sr-only' : ''
  const items = ADMIN_NAV.filter((item) => session && hasAnyPermission(session, item.anyOf))
  // In a sheet every link closes it; in the sidebar there is nothing to close.
  const wrap = (node: React.ReactElement) => (collapsible ? node : <SheetClose asChild>{node}</SheetClose>)

  return (
    <>
      {items.length > 0 && (
        <nav aria-label="Quản trị" className="grid gap-1">
          {items.map((item) => (
            <div key={item.to}>
              {wrap(
                <NavLink to={item.to} title={item.label} className={linkClasses}>
                  <item.icon aria-hidden="true" className="size-[18px] shrink-0" />
                  <span className={label}>{item.label}</span>
                </NavLink>,
              )}
            </div>
          ))}
        </nav>
      )}

      <div className={cn('mt-auto grid gap-2 border-t border-sidebar-border pt-3', collapsible && 'justify-items-center lg:justify-items-stretch')}>
        {wrap(
          <Link to="/workshops" title="Về trang sinh viên" className={linkClasses}>
            <House aria-hidden="true" className="size-[18px] shrink-0" />
            <span className={label}>Về trang sinh viên</span>
          </Link>,
        )}
        <ThemeToggle className={cn(collapsible && 'flex-col lg:flex-row', 'justify-self-center lg:justify-self-start')} />
        {session && (
          <div className="grid gap-2 px-1">
            <div className={cn('text-sm', collapsible && 'hidden lg:block')}>
              <div className="font-semibold">{session.user.fullName}</div>
              <div className="text-xs text-muted-foreground">{session.user.roles.join(', ')}</div>
            </div>
            {wrap(
              <Button variant="outline" size="sm" onClick={signOut} title="Đăng xuất">
                <LogOut aria-hidden="true" />
                <span className={label}>Đăng xuất</span>
              </Button>,
            )}
          </div>
        )}
      </div>
    </>
  )
}

export function AdminLayout() {
  return (
    <div className="min-h-svh bg-background md:flex">
      <header className="sticky top-0 z-40 flex h-14 items-center gap-2 border-b border-border bg-card px-3 md:hidden">
        <Logo />
        <span className="ml-1 text-sm text-muted-foreground">Quản trị</span>
        <Sheet>
          <SheetTrigger asChild>
            <Button variant="ghost" size="icon-lg" className="ml-auto" aria-label="Mở menu quản trị">
              <Menu className="size-5" />
            </Button>
          </SheetTrigger>
          <SheetContent side="left" className="w-72 gap-0 bg-sidebar p-4">
            <SheetTitle className="text-lg font-semibold">Quản trị</SheetTitle>
            <SheetDescription className="sr-only">Các mục quản trị và cài đặt hiển thị.</SheetDescription>
            <div className="mt-4 flex min-h-0 flex-1 flex-col">
              <SidebarBody collapsible={false} />
            </div>
          </SheetContent>
        </Sheet>
      </header>

      <aside
        aria-label="Thanh bên quản trị"
        className="sticky top-0 hidden h-svh w-16 shrink-0 flex-col gap-4 border-r border-sidebar-border bg-sidebar p-2.5 md:flex lg:w-[248px] lg:p-3"
      >
        {/* The mark is not a link: "Về trang sinh viên" below is the one way back, and two links to one place only confuse. */}
        <div className="flex justify-center px-1 py-1 lg:justify-start">
          <span className="lg:hidden">
            <span aria-hidden="true" className="grid size-9 place-items-center rounded-lg bg-primary text-base font-bold text-primary-foreground">U</span>
          </span>
          <span className="hidden lg:block">
            <Logo />
          </span>
        </div>
        <SidebarBody collapsible />
      </aside>

      <div className="min-w-0 flex-1">
        <Outlet />
      </div>
    </div>
  )
}

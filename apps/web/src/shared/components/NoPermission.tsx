import { Lock } from 'lucide-react'
import { Link } from 'react-router'

import { Button } from '@/shared/ui/button'

// A guard that refuses must not leave the user with nowhere to go: the page outside any layout (the admin guard sits
// above its own shell) would otherwise be a dead end. The sentence in the alert and the link are what the tests and
// the E2E suite look for; the heading and the icon are for the eye.
export function NoPermission() {
  return (
    <main className="mx-auto grid max-w-md justify-items-center gap-3 px-4 py-16 text-center">
      <div aria-hidden="true" className="grid size-16 place-items-center rounded-2xl bg-warning-soft text-warning">
        <Lock className="size-7" />
      </div>
      <h1 className="text-3xl leading-[38px] font-bold text-foreground">Không có quyền truy cập</h1>
      <p role="alert" className="text-muted-foreground">
        Bạn không có quyền truy cập trang này.
      </p>
      <Button asChild size="lg" className="mt-2">
        <Link to="/">Về trang chủ</Link>
      </Button>
    </main>
  )
}

import type { ReactNode } from 'react'
import { Link } from 'react-router'

import { Button } from '@/shared/ui/button'

type Props = {
  title?: string
  description?: string
  /** Extra ways out, next to "Về trang chủ". */
  children?: ReactNode
}

// A page that is not there (S16): an unknown path, or a record the API answers 404 for. Not an alert: nothing went wrong,
// the address just leads nowhere. It brings its own heading and renders no <main>; the caller's page does.
export function NotFound({ title = 'Không tìm thấy trang', description = 'Liên kết có thể đã cũ hoặc bị gõ sai.', children }: Props) {
  return (
    <div className="mx-auto grid max-w-md justify-items-center gap-3 px-4 py-16 text-center">
      <p aria-hidden="true" className="text-6xl leading-[60px] font-bold tracking-tight text-primary-text">
        404
      </p>
      <h1 className="text-3xl leading-[38px] font-bold text-foreground">{title}</h1>
      <p className="text-muted-foreground">{description}</p>
      <div className="mt-2 flex flex-wrap justify-center gap-2">
        <Button asChild size="lg">
          <Link to="/">Về trang chủ</Link>
        </Button>
        {children}
      </div>
    </div>
  )
}

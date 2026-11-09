import { ArrowRight } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'

import { useWorkshops, WorkshopCardGrid, WorkshopGridSkeleton } from '@/features/workshops'
import { LoadError } from '@/shared/components/LoadError'
import { useToday } from '@/shared/hooks/use-today'
import { dayShort, endOfDay, endOfWeek, startOfDay } from '@/shared/lib/vn-time'
import { Badge } from '@/shared/ui/badge'
import { Button } from '@/shared/ui/button'
import { EmptyState } from '@/shared/ui/state-panel'
import { Input } from '@/shared/ui/input'

const THIS_WEEK_CARDS = 3

const STEPS = [
  { title: 'Chọn workshop', body: 'Tìm theo chủ đề, diễn giả, địa điểm hoặc khoảng ngày.' },
  {
    title: 'Đăng ký và thanh toán',
    body: 'Workshop miễn phí được xác nhận ngay. Workshop có phí giữ chỗ cho bạn 15 phút để thanh toán.',
  },
  { title: 'Nhận vé QR', body: 'Vé nằm trong mục "Đăng ký của tôi". Nhân viên quét mã khi bạn đến.' },
]

// A drawing for the right of the hero, from the lg breakpoint up. SVG presentation attributes only (no <style>, no
// style=""), like the generated workshop cover: the CSP's `style-src` refuses both.
function HeroArt() {
  return (
    <div aria-hidden="true" className="relative hidden aspect-[4/3] overflow-hidden rounded-3xl bg-primary lg:block">
      <svg viewBox="0 0 400 300" preserveAspectRatio="xMidYMid slice" className="absolute inset-0 size-full">
        <rect width="400" height="300" fill="#15803D" />
        <circle cx="330" cy="60" r="120" fill="#166534" />
        <circle cx="60" cy="280" r="150" fill="#126F33" />
        <rect x="70" y="70" width="190" height="130" rx="14" fill="#FFFFFF" />
        <rect x="88" y="90" width="90" height="10" rx="5" fill="#15803D" />
        <rect x="88" y="112" width="140" height="8" rx="4" fill="#E1E6DF" />
        <rect x="88" y="130" width="120" height="8" rx="4" fill="#E1E6DF" />
        <rect x="88" y="166" width="64" height="20" rx="10" fill="#FCD34D" />
        <rect x="230" y="150" width="130" height="110" rx="14" fill="#FCD34D" />
        <rect x="248" y="168" width="58" height="58" rx="6" fill="#451A03" />
      </svg>
    </div>
  )
}

// S1: a short introduction, a search box that hands over to the catalog, and what is on this week (D139: from today
// to Sunday, in Vietnam, at most three cards). The week's request is two instants that do not change all day, so
// it hits the backend's list cache like the catalog does.
export function HomePage() {
  const navigate = useNavigate()
  const [q, setQ] = useState('')
  const today = useToday()
  const sunday = endOfWeek(today)
  const thisWeek = useWorkshops({ dateFrom: startOfDay(today), dateTo: endOfDay(sunday), size: THIS_WEEK_CARDS })

  const search = (event: FormEvent) => {
    event.preventDefault()
    const text = q.trim()
    void navigate(text ? `/workshops?${new URLSearchParams({ q: text })}` : '/workshops')
  }
  const seeAll = `/workshops?${new URLSearchParams({ dateFrom: today, dateTo: sunday })}`

  return (
    <main>
      <section className="border-b border-border bg-primary-soft">
        <div className="mx-auto grid max-w-6xl items-center gap-12 px-4 py-10 md:px-6 md:py-16 lg:grid-cols-[minmax(0,1.2fr)_minmax(0,1fr)]">
          <div className="grid max-w-2xl gap-5">
            <Badge tone="highlight" className="justify-self-start">
              Dành cho sinh viên
            </Badge>
            <h1 className="text-4xl leading-[44px] font-bold tracking-tight text-foreground lg:text-5xl lg:leading-[56px]">
              Tìm workshop hay, giữ chỗ trong vài chạm.
            </h1>
            <p className="text-lg leading-7 text-muted-foreground">
              Các buổi chia sẻ do khoa và câu lạc bộ tổ chức. Đăng ký, thanh toán và nhận vé QR ngay trên UniHub.
            </p>
            <form onSubmit={search} role="search" className="grid max-w-lg gap-2 sm:grid-cols-[1fr_auto]">
              <label htmlFor="home-q" className="sr-only">
                Tìm workshop theo chủ đề
              </label>
              <Input id="home-q" type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Tìm theo chủ đề, ví dụ: Kubernetes" className="h-12" />
              <Button type="submit" size="lg" className="h-12">
                Tìm
              </Button>
            </form>
          </div>
          <HeroArt />
        </div>
      </section>

      <section className="mx-auto max-w-6xl px-4 py-10 md:px-6">
        <div className="mb-5 flex flex-wrap items-end justify-between gap-2">
          <div>
            <h2 className="text-2xl leading-8 font-semibold">Sắp diễn ra tuần này</h2>
            <p className="text-sm text-muted-foreground">Từ hôm nay đến Chủ nhật {dayShort(sunday).slice(0, 5)}</p>
          </div>
          {thisWeek.data && thisWeek.data.page.totalElements > 0 && (
            <Link to={seeAll} className="inline-flex items-center gap-1 text-sm font-semibold text-primary-text underline-offset-4 hover:underline">
              Xem tất cả {thisWeek.data.page.totalElements} workshop
              <ArrowRight aria-hidden="true" className="size-4" />
            </Link>
          )}
        </div>
        {thisWeek.isPending ? (
          <WorkshopGridSkeleton count={THIS_WEEK_CARDS} />
        ) : thisWeek.isError ? (
          <LoadError error={thisWeek.error} message="Không tải được các workshop tuần này." onRetry={() => void thisWeek.refetch()} />
        ) : thisWeek.data.items.length === 0 ? (
          <EmptyState
            title="Tuần này chưa có workshop"
            description="Chưa có workshop nào diễn ra từ hôm nay đến Chủ nhật."
            action={
              <Button asChild variant="outline">
                <Link to="/workshops">Xem tất cả workshop</Link>
              </Button>
            }
          />
        ) : (
          <WorkshopCardGrid items={thisWeek.data.items} detailPath={(id) => `/workshops/${id}`} />
        )}
      </section>

      <section className="mx-auto max-w-6xl px-4 pt-2 pb-14 md:px-6">
        <h2 className="mb-5 text-2xl leading-8 font-semibold">Cách hoạt động</h2>
        <ol className="grid gap-6 md:grid-cols-3">
          {STEPS.map((step, index) => (
            <li key={step.title} className="grid content-start gap-2 rounded-xl border border-border bg-card p-5 shadow-sm dark:shadow-none">
              <Badge tone="muted" className="justify-self-start">
                Bước {index + 1}
              </Badge>
              <h3 className="text-xl leading-7 font-semibold">{step.title}</h3>
              <p className="text-sm text-muted-foreground">{step.body}</p>
            </li>
          ))}
        </ol>
      </section>
    </main>
  )
}

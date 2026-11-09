import type { UseQueryResult } from '@tanstack/react-query'
import { Banknote, CalendarDays, CircleX, Clock, Flag, MapPin, Sparkles } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { Link } from 'react-router'

import type { AppError } from '@/shared/api/errors'
import { NotFound } from '@/shared/components/NotFound'
import { formatDateTime, formatPrice } from '@/shared/lib/format'
import { Badge } from '@/shared/ui/badge'
import { Button } from '@/shared/ui/button'
import { Skeleton } from '@/shared/ui/skeleton'
import type { Workshop } from '../api/requests'
import { seatFill, seatLevel } from '../model/seat-level'
import { LoadError } from './LoadError'
import { WorkshopCover } from './WorkshopCover'

type Props = {
  query: UseQueryResult<Workshop, AppError>
  /** Where "see other workshops" goes for a cancelled or finished workshop: owned by the route table. */
  catalogPath: string
  /** The call to action, rendered in the registration panel while the workshop is open: the route's own button. */
  children?: ReactNode
}

// A 404 is either an unknown id or a DRAFT hidden from this caller — deliberately
// indistinguishable (paths/workshop.yaml#/workshopById). The only 400 getWorkshop returns is a path
// id that is not a UUID, which to the viewer is the same thing: there is no such workshop.
const isNotFound = (error: AppError) => error.status === 404 || error.status === 400

export function WorkshopDetailView({ query, catalogPath, children }: Props) {
  if (query.isPending) return <DetailSkeleton />
  if (query.isError) {
    return isNotFound(query.error) ? (
      <NotFound title="Không tìm thấy workshop" description="Workshop này không tồn tại hoặc không còn được công bố.">
        <Button asChild variant="outline" size="lg">
          <Link to={catalogPath}>Xem danh sách workshop</Link>
        </Button>
      </NotFound>
    ) : (
      <LoadError error={query.error} message="Không tải được thông tin workshop." onRetry={() => void query.refetch()} className="mt-6" />
    )
  }
  return (
    <WorkshopDetail workshop={query.data} catalogPath={catalogPath}>
      {children}
    </WorkshopDetail>
  )
}

function DetailSkeleton() {
  return (
    <div aria-busy="true" className="mt-4 grid gap-6 lg:grid-cols-[minmax(0,1fr)_22.5rem]">
      <p role="status" className="sr-only">
        Đang tải thông tin workshop…
      </p>
      <div className="grid gap-4">
        <Skeleton className="aspect-video rounded-2xl" />
        <Skeleton className="h-9 w-3/4" />
      </div>
      <Skeleton className="h-72 rounded-xl" />
    </div>
  )
}

const STATUS_NOTICE: Partial<Record<Workshop['status'], { text: string; detail: string; icon: typeof Flag; tone: string }>> = {
  CANCELLED: { text: 'Workshop này đã bị hủy.', detail: 'Bạn không thể đăng ký nữa.', icon: CircleX, tone: 'bg-destructive-soft text-destructive' },
  COMPLETED: { text: 'Workshop này đã kết thúc.', detail: '', icon: Flag, tone: 'bg-info-soft text-info' },
}

function Row({ icon: Icon, label, children }: { icon: typeof Clock; label: string; children: ReactNode }) {
  return (
    <div className="flex items-start gap-3">
      <Icon aria-hidden="true" className="mt-1 size-5 shrink-0 text-primary-text" />
      <div className="min-w-0">
        <dt className="text-sm text-muted-foreground">{label}</dt>
        <dd className="font-semibold [overflow-wrap:anywhere]">{children}</dd>
      </div>
    </div>
  )
}

function WorkshopDetail({ workshop, catalogPath, children }: { workshop: Workshop; catalogPath: string; children: ReactNode }) {
  // seatsTaken is near-real-time and advisory (contract: Workshop.seatsTaken); the floor at 0 only
  // keeps a momentarily inconsistent read from rendering a negative count.
  const seatsLeft = Math.max(workshop.capacity - workshop.seatsTaken, 0)
  const notice = STATUS_NOTICE[workshop.status]
  const open = notice === undefined
  const level = seatLevel(workshop.capacity, workshop.seatsTaken)
  // A hint from the viewer's clock, never a rule: the server decides whether a registration is accepted (INV-06, D141).
  const [loadedAt] = useState(() => Date.now())
  const started = open && loadedAt >= new Date(workshop.startTime).getTime()

  return (
    <article className="mt-4 grid gap-x-10 gap-y-6 lg:grid-cols-[minmax(0,1fr)_22.5rem]">
      <header className="grid content-start gap-4 lg:col-start-1">
        <WorkshopCover
          workshopId={workshop.id}
          coverImageUrl={workshop.coverImageUrl ?? null}
          dimmed={workshop.status === 'CANCELLED'}
          className="rounded-2xl"
        />
        <div className="flex flex-wrap gap-2">
          {workshop.isPaid && <Badge tone="highlight">Có phí</Badge>}
          {workshop.status === 'CANCELLED' && <Badge tone="destructive">Đã hủy</Badge>}
          {workshop.status === 'COMPLETED' && <Badge tone="info">Đã kết thúc</Badge>}
        </div>
        <h1 className="text-3xl leading-[38px] font-bold tracking-tight text-foreground [overflow-wrap:anywhere]">{workshop.title}</h1>
      </header>

      <aside
        aria-label="Thông tin và đăng ký"
        className="grid content-start gap-4 rounded-xl border border-border bg-card p-5 shadow-sm lg:sticky lg:top-24 lg:col-start-2 lg:row-span-2 lg:row-start-1 lg:self-start dark:shadow-none"
      >
        <dl className="grid gap-4">
          <Row icon={CalendarDays} label="Bắt đầu">
            <time dateTime={workshop.startTime}>{formatDateTime(workshop.startTime)}</time>
          </Row>
          <Row icon={Clock} label="Kết thúc">
            <time dateTime={workshop.endTime}>{formatDateTime(workshop.endTime)}</time>
          </Row>
          <Row icon={MapPin} label="Địa điểm">
            {workshop.location}
          </Row>
          <Row icon={Banknote} label="Phí tham dự">
            {workshop.isPaid && workshop.price != null ? formatPrice(workshop.price) : 'Miễn phí'}
          </Row>
        </dl>

        {open ? (
          <>
            <div className="grid gap-2 border-t border-border pt-4">
              <div className="flex items-baseline justify-between gap-2">
                <span className="text-sm text-muted-foreground">Số ghế</span>
                <b className="font-semibold tabular-nums">{seatsLeft === 0 ? 'Hết chỗ' : `Còn ${seatsLeft} / ${workshop.capacity} chỗ`}</b>
              </div>
              <div aria-hidden="true" className="h-2 overflow-hidden rounded-full bg-seat-track">
                <div
                  className={level === 'low' ? 'h-full rounded-full bg-seat-low' : level === 'full' ? 'h-full rounded-full bg-destructive' : 'h-full rounded-full bg-seat-ok'}
                  style={{ width: `${Math.round(seatFill(workshop.capacity, workshop.seatsTaken) * 100)}%` }}
                />
              </div>
              {level === 'low' && (
                <p className="flex items-center gap-1.5 text-sm font-semibold text-warning">
                  <Clock aria-hidden="true" className="size-4" />
                  Sắp hết
                </p>
              )}
              {started && <p className="text-sm text-muted-foreground">Workshop đã bắt đầu.</p>}
              <p className="text-xs text-muted-foreground">Số ghế cập nhật mỗi vài giây khi bạn đang xem trang này.</p>
            </div>
            {children}
            {workshop.isPaid && (
              <p className="text-sm text-muted-foreground">Workshop có phí: sau khi đăng ký, chỗ được giữ cho bạn 15 phút để thanh toán.</p>
            )}
          </>
        ) : (
          <div className="grid gap-3 border-t border-border pt-4">
            <div role="status" className={`flex items-start gap-2.5 rounded-lg p-3 text-sm ${notice.tone}`}>
              <notice.icon aria-hidden="true" className="mt-0.5 size-4.5 shrink-0" />
              <div>
                <b className="block font-semibold">{notice.text}</b>
                {notice.detail}
              </div>
            </div>
            <Button asChild variant="outline" size="lg">
              <Link to={catalogPath}>Xem workshop khác</Link>
            </Button>
          </div>
        )}
      </aside>

      <div className="grid content-start gap-7 lg:col-start-1">
        {workshop.description && (
          <section>
            <h2 className="mb-2 text-2xl leading-8 font-semibold text-foreground">Giới thiệu</h2>
            <p className="[overflow-wrap:anywhere] whitespace-pre-line">{workshop.description}</p>
          </section>
        )}
        {workshop.aiSummary && (
          <section className="rounded-xl bg-primary-soft p-4">
            <h2 className="mb-2 text-2xl leading-8 font-semibold text-foreground">Tóm tắt (AI)</h2>
            <p className="[overflow-wrap:anywhere] whitespace-pre-line">{workshop.aiSummary}</p>
            <p className="mt-2 flex items-center gap-1.5 text-xs font-semibold text-primary-text">
              <Sparkles aria-hidden="true" className="size-3.5" />
              Tạo tự động từ brochure
            </p>
          </section>
        )}
        <section>
          <h2 className="mb-2 text-2xl leading-8 font-semibold text-foreground">Về diễn giả</h2>
          <div className="flex items-start gap-4">
            <div
              aria-hidden="true"
              className="grid size-14 shrink-0 place-items-center rounded-full bg-accent text-lg font-bold text-primary-text"
            >
              {initials(workshop.speakerName)}
            </div>
            <div className="min-w-0">
              <p className="font-semibold [overflow-wrap:anywhere]">{workshop.speakerName}</p>
              {workshop.speakerBio && <p className="text-muted-foreground [overflow-wrap:anywhere]">{workshop.speakerBio}</p>}
            </div>
          </div>
        </section>
      </div>
    </article>
  )
}

/** Up to two letters for the avatar: the first of the first and of the last word ("Phạm Thu Hà" → "PH"). */
function initials(name: string): string {
  const words = name.split(/\s+/).filter(Boolean)
  if (words.length === 0) return '?'
  const letters = words.length === 1 ? [words[0]] : [words[0], words[words.length - 1]]
  return letters.map((w) => Array.from(w)[0].toUpperCase()).join('')
}

import type { UseQueryResult } from '@tanstack/react-query'
import { CalendarClock, CircleCheck, CircleX, type LucideIcon } from 'lucide-react'
import type { ReactNode } from 'react'

import type { AppError } from '@/shared/api/errors'
import { LoadError } from '@/shared/components/LoadError'
import { formatDateTime } from '@/shared/lib/format'
import { cn } from '@/shared/lib/utils'
import { Badge } from '@/shared/ui/badge'
import { EmptyState } from '@/shared/ui/state-panel'
import { Skeleton } from '@/shared/ui/skeleton'
import type { Notification, NotificationListResponse } from '../api/requests'

type Props = {
  query: UseQueryResult<NotificationListResponse, AppError>
  /**
   * Per-item link, supplied by the route: where a registration or a workshop lives is routing, and
   * the stored `body` is text, so a URL inside it would not be clickable.
   */
  renderActions?: (notification: Notification) => ReactNode
}

type Tone = 'success' | 'info' | 'destructive'

// What kind of notification it is, in words and an icon beside the colour (D115, D151). The words are the feature's: the
// contract's `type` is an enum, not copy.
const TYPE_VIEW: Record<Notification['type'], { label: string; tone: Tone; icon: LucideIcon }> = {
  REGISTRATION_CONFIRMED: { label: 'Đã xác nhận', tone: 'success', icon: CircleCheck },
  WORKSHOP_RESCHEDULED: { label: 'Đổi lịch', tone: 'info', icon: CalendarClock },
  WORKSHOP_CANCELLED: { label: 'Đã hủy', tone: 'destructive', icon: CircleX },
}

const BUBBLE: Record<Tone, string> = {
  success: 'bg-primary-soft text-primary-text',
  info: 'bg-info-soft text-info',
  destructive: 'bg-destructive-soft text-destructive',
}

// `title` and `body` are the wording rendered when the notification was created and are shown as
// they are, as text (contract: schemas/notification.yaml#/Notification). There is no read state: no dot, no count (M-81).
export function NotificationList({ query, renderActions }: Props) {
  if (query.isPending) {
    return (
      <div role="status" aria-busy="true" className="grid gap-4 rounded-xl border border-border bg-card p-5">
        <span className="sr-only">Đang tải…</span>
        {[0, 1, 2].map((n) => (
          <div key={n} className="flex gap-4">
            <Skeleton className="size-10 shrink-0 rounded-full" />
            <div className="grid flex-1 gap-2">
              <Skeleton className="h-5 w-3/5" />
              <Skeleton className="h-4 w-4/5" />
            </div>
          </div>
        ))}
      </div>
    )
  }
  if (query.isError) {
    return (
      <LoadError error={query.error} message="Không tải được danh sách thông báo." onRetry={() => void query.refetch()} />
    )
  }
  if (query.data.items.length === 0) {
    return (
      <EmptyState
        title="Bạn chưa có thông báo nào."
        description="Khi bạn đăng ký hoặc workshop thay đổi, thông báo sẽ hiện ở đây."
      />
    )
  }

  return (
    <ul className="overflow-hidden rounded-xl border border-border bg-card">
      {query.data.items.map((notification) => {
        const { label, tone, icon: Icon } = TYPE_VIEW[notification.type]
        return (
          <li key={notification.id} className="flex gap-3 border-b border-border p-4 last:border-b-0 md:gap-4 md:p-5">
            <span
              aria-hidden="true"
              className={cn('grid size-9 shrink-0 place-items-center rounded-full md:size-10', BUBBLE[tone])}
            >
              <Icon className="size-5" />
            </span>
            <div className="min-w-0 flex-1">
              <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                <Badge tone={tone}>{label}</Badge>
                <time dateTime={notification.createdAt} className="text-xs whitespace-nowrap text-muted-foreground md:order-last md:ml-auto md:text-[13px]">
                  {formatDateTime(notification.createdAt)}
                </time>
                <h2 className="w-full text-base leading-snug font-semibold md:w-auto md:min-w-0 md:flex-1">{notification.title}</h2>
              </div>
              <p className="mt-1 text-sm whitespace-pre-line text-muted-foreground">{notification.body}</p>
              {renderActions?.(notification)}
            </div>
          </li>
        )
      })}
    </ul>
  )
}

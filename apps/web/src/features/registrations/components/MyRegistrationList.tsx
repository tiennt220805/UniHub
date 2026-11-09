import type { UseQueryResult } from '@tanstack/react-query'
import type { ReactNode } from 'react'

import type { AppError } from '@/shared/api/errors'
import { LoadError } from '@/shared/components/LoadError'
import { formatDateTime } from '@/shared/lib/format'
import { cn } from '@/shared/lib/utils'
import { Badge } from '@/shared/ui/badge'
import { EmptyState } from '@/shared/ui/state-panel'
import { Skeleton } from '@/shared/ui/skeleton'
import type { RegistrationListResponse, RegistrationSummary } from '../api/requests'
import { RegistrationStatusBadge } from './RegistrationStatusBadge'

type Props = {
  query: UseQueryResult<RegistrationListResponse, AppError>
  /**
   * A registration carries only its workshop's id, so what the row shows of the workshop comes from the route that
   * composes this list with the workshops feature (a feature never imports another feature): the cover, the title with
   * its time and place, and any extra flag ("Đã qua", D150). Each is a component of the route, because each reads the
   * workshop (one request per workshop, shared by the three).
   */
  renderThumbnail: (workshopId: string) => ReactNode
  renderWorkshop: (workshopId: string) => ReactNode
  renderFlags?: (workshopId: string) => ReactNode
  /** Per-row actions: a link to the e-ticket, to payment, to the details. */
  renderActions?: (registration: RegistrationSummary) => ReactNode
  /** What leads out of an empty list. */
  emptyAction?: ReactNode
}

const TICKET_LABEL: Record<RegistrationSummary['ticketType'], string> = {
  FREE: 'Miễn phí',
  PAID: 'Có phí',
}

export function MyRegistrationList({
  query,
  renderThumbnail,
  renderWorkshop,
  renderFlags,
  renderActions,
  emptyAction,
}: Props) {
  if (query.isPending) {
    return (
      <div role="status" aria-busy="true" className="grid gap-3">
        <span className="sr-only">Đang tải…</span>
        {[0, 1, 2].map((n) => (
          <Skeleton key={n} className="h-28 rounded-xl" />
        ))}
      </div>
    )
  }
  if (query.isError) {
    return (
      <LoadError error={query.error} message="Không tải được danh sách đăng ký." onRetry={() => void query.refetch()} />
    )
  }
  if (query.data.items.length === 0) {
    return <EmptyState title="Bạn chưa đăng ký workshop nào." action={emptyAction} />
  }

  return (
    <ul className="grid gap-3">
      {query.data.items.map((registration) => (
        <li
          key={registration.id}
          className={cn(
            'rounded-xl border bg-card p-3.5 md:p-4',
            registration.status === 'RESERVED' && registration.ticketType === 'PAID' ? 'border-primary' : 'border-border',
          )}
        >
          <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:gap-4">
            <div className="flex min-w-0 flex-1 gap-3 md:gap-4">
              <div className="w-[84px] shrink-0 self-start sm:w-28">{renderThumbnail(registration.workshopId)}</div>
              <div className="min-w-0 flex-1 space-y-2">
                {renderWorkshop(registration.workshopId)}
                <div className="flex flex-wrap items-center gap-1.5">
                  <RegistrationStatusBadge status={registration.status} />
                  <Badge tone={registration.ticketType === 'PAID' ? 'highlight' : 'muted'}>
                    {TICKET_LABEL[registration.ticketType]}
                  </Badge>
                  {renderFlags?.(registration.workshopId)}
                  <span className="text-xs text-muted-foreground">
                    Đăng ký lúc <time dateTime={registration.createdAt}>{formatDateTime(registration.createdAt)}</time>
                  </span>
                </div>
              </div>
            </div>
            {renderActions && (
              <div className="grid shrink-0 grid-cols-[1fr_auto] items-center gap-2 sm:w-36 sm:grid-cols-1 sm:justify-items-stretch">
                {renderActions(registration)}
              </div>
            )}
          </div>
        </li>
      ))}
    </ul>
  )
}

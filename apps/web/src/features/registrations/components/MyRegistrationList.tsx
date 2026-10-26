import type { UseQueryResult } from '@tanstack/react-query'
import type { ReactNode } from 'react'

import type { AppError } from '@/shared/api/errors'
import { formatDateTime } from '@/shared/lib/format'
import type { RegistrationListResponse, RegistrationSummary } from '../api/requests'
import { REGISTRATION_STATUS_LABEL } from '../model/registration-status'

type Props = {
  query: UseQueryResult<RegistrationListResponse, AppError>
  /**
   * A registration carries only its workshop's id; the route that composes this list with the
   * workshops feature supplies the name (a feature never imports another feature).
   */
  renderWorkshop: (workshopId: string) => ReactNode
  /** Per-row actions, e.g. a link to pay for a RESERVED paid registration. */
  renderActions?: (registration: RegistrationSummary) => ReactNode
}

const TICKET_LABEL: Record<RegistrationSummary['ticketType'], string> = {
  FREE: 'Miễn phí',
  PAID: 'Có phí',
}

export function MyRegistrationList({ query, renderWorkshop, renderActions }: Props) {
  if (query.isPending) return <p>Đang tải…</p>
  if (query.isError) {
    const { error } = query
    return (
      <p role="alert">
        {error.status === 429
          ? 'Bạn đang gửi quá nhiều yêu cầu. Vui lòng thử lại sau.'
          : 'Không tải được danh sách đăng ký.'}
        {error.problem?.correlationId && (
          <span className="block text-sm">Mã tham chiếu: {error.problem.correlationId}</span>
        )}
      </p>
    )
  }
  if (query.data.items.length === 0) return <p>Bạn chưa đăng ký workshop nào.</p>

  return (
    <ul className="divide-y rounded-lg border">
      {query.data.items.map((registration) => (
        <li key={registration.id} className="flex flex-wrap items-center gap-x-4 gap-y-1 p-4">
          <span className="min-w-0 flex-1">{renderWorkshop(registration.workshopId)}</span>
          <span className="text-sm text-muted-foreground">{TICKET_LABEL[registration.ticketType]}</span>
          <span className="text-sm font-medium">{REGISTRATION_STATUS_LABEL[registration.status]}</span>
          <time dateTime={registration.createdAt} className="text-sm text-muted-foreground">
            {formatDateTime(registration.createdAt)}
          </time>
          {renderActions?.(registration)}
        </li>
      ))}
    </ul>
  )
}

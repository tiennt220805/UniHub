import type { UseQueryResult } from '@tanstack/react-query'
import { Link } from 'react-router'

import type { AppError } from '@/shared/api/errors'
import { formatDateTime } from '@/shared/lib/format'
import type { WorkshopListResponse } from '../api/requests'
import { LoadError } from './LoadError'

type Props = {
  query: UseQueryResult<WorkshopListResponse, AppError>
  /** URL of a workshop's detail page — owned by the route table, not by this feature. */
  detailPath: (workshopId: string) => string
}

export function WorkshopSummaryList({ query, detailPath }: Props) {
  if (query.isPending) return <p>Đang tải…</p>
  if (query.isError) return <LoadError error={query.error} message="Không tải được danh sách workshop." />
  if (query.data.items.length === 0) return <p>Chưa có workshop nào.</p>

  return (
    <ul className="divide-y divide-border">
      {query.data.items.map((workshop) => (
        <li key={workshop.id} className="py-4">
          <Link
            to={detailPath(workshop.id)}
            className="text-lg font-medium text-foreground hover:underline"
          >
            {workshop.title}
          </Link>
          <p className="text-sm text-muted-foreground">
            <time dateTime={workshop.startTime}>{formatDateTime(workshop.startTime)}</time>
            {' · '}
            {workshop.location}
          </p>
        </li>
      ))}
    </ul>
  )
}

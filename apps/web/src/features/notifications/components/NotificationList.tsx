import type { UseQueryResult } from '@tanstack/react-query'

import type { AppError } from '@/shared/api/errors'
import { formatDateTime } from '@/shared/lib/format'
import type { NotificationListResponse } from '../api/requests'

type Props = {
  query: UseQueryResult<NotificationListResponse, AppError>
}

// `title` and `body` are the wording rendered when the notification was created and are shown as
// they are, as text (contract: schemas/notification.yaml#/Notification). There is no read state.
export function NotificationList({ query }: Props) {
  if (query.isPending) return <p>Đang tải…</p>
  if (query.isError) {
    const { error } = query
    return (
      <p role="alert">
        {error.status === 429
          ? 'Bạn đang gửi quá nhiều yêu cầu. Vui lòng thử lại sau.'
          : 'Không tải được danh sách thông báo.'}
        {error.problem?.correlationId && (
          <span className="block text-sm">Mã tham chiếu: {error.problem.correlationId}</span>
        )}
      </p>
    )
  }
  if (query.data.items.length === 0) return <p>Bạn chưa có thông báo nào.</p>

  return (
    <ul className="divide-y rounded-lg border">
      {query.data.items.map((notification) => (
        <li key={notification.id} className="space-y-1 p-4">
          <div className="flex flex-wrap items-baseline gap-x-4 gap-y-1">
            <h2 className="min-w-0 flex-1 font-medium">{notification.title}</h2>
            <time dateTime={notification.createdAt} className="text-sm text-muted-foreground">
              {formatDateTime(notification.createdAt)}
            </time>
          </div>
          <p className="whitespace-pre-line text-sm text-muted-foreground">{notification.body}</p>
        </li>
      ))}
    </ul>
  )
}

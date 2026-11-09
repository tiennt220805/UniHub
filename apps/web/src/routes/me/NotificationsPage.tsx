import { Link, useSearchParams } from 'react-router'

import { NotificationList, useMyNotifications, type Notification } from '@/features/notifications'
import { Pagination } from '@/shared/ui/pagination'

const PAGE_SIZE = 20

// Page number lives in the URL (web-coding-standards.md §8), as on the registrations page: a value
// that is not a non-negative integer is page 0.
function pageFrom(params: URLSearchParams): number {
  const page = Number(params.get('page'))
  return Number.isInteger(page) && page >= 0 ? page : 0
}

const linkClass =
  'mt-1 inline-flex min-h-9 items-center text-sm font-semibold text-primary-text underline-offset-4 hover:underline'

// A confirmation points at the e-ticket of its registration; a cancelled or rescheduled workshop points at the workshop,
// where the new time or the cancellation is on the page (D151). The link costs nothing until it is followed. The rule
// reads the type and what the notification points at, so a row whose pointer is not the kind it expects gets no link.
function actionsOf(notification: Notification) {
  if (notification.type === 'REGISTRATION_CONFIRMED' && notification.relatedEntityType === 'REGISTRATION') {
    return (
      <Link to={`/me/registrations/${notification.relatedEntityId}`} className={linkClass}>
        Xem vé
      </Link>
    )
  }
  if (
    (notification.type === 'WORKSHOP_CANCELLED' || notification.type === 'WORKSHOP_RESCHEDULED') &&
    notification.relatedEntityType === 'WORKSHOP'
  ) {
    return (
      <Link to={`/workshops/${notification.relatedEntityId}`} className={linkClass}>
        Xem workshop
      </Link>
    )
  }
  return null
}

// The signed-in user's own in-app notifications, newest first (listMyNotifications is scoped to
// the caller). No unread count and no "mark as read": the contract has neither (D38).
export function NotificationsPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const page = pageFrom(searchParams)
  const notifications = useMyNotifications({ page, size: PAGE_SIZE })
  const totalPages = notifications.data?.page.totalPages ?? 0

  const goTo = (next: number) => setSearchParams(next === 0 ? {} : { page: String(next) })

  return (
    <main className="mx-auto max-w-3xl space-y-5 px-4 py-6 md:px-6 md:py-8">
      <div>
        <h1 className="text-[28px] leading-9 font-bold tracking-tight md:text-3xl md:leading-[38px]">Thông báo</h1>
        <p className="mt-1 text-sm text-muted-foreground">Mới nhất ở trên. Hộp thư làm mới khi bạn quay lại cửa sổ này.</p>
      </div>
      <NotificationList query={notifications} renderActions={actionsOf} />
      <Pagination page={page} pageCount={totalPages} onPageChange={goTo} className="pt-2" />
    </main>
  )
}

import { Link, useSearchParams } from 'react-router'

import { NotificationList, useMyNotifications } from '@/features/notifications'
import { Button } from '@/shared/ui/button'

const PAGE_SIZE = 20

// Page number lives in the URL (web-coding-standards.md §8), as on the registrations page: a value
// that is not a non-negative integer is page 0.
function pageFrom(params: URLSearchParams): number {
  const page = Number(params.get('page'))
  return Number.isInteger(page) && page >= 0 ? page : 0
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
    <main className="mx-auto max-w-3xl space-y-4 p-6">
      <h1 className="text-2xl font-semibold text-foreground">Thông báo</h1>
      <NotificationList
        query={notifications}
        renderActions={(notification) =>
          // Only a confirmation points at a page of its own (the e-ticket); a cancelled or moved
          // workshop has nothing to open that the notification does not already say.
          notification.type === 'REGISTRATION_CONFIRMED' &&
          notification.relatedEntityType === 'REGISTRATION' ? (
            <Link
              to={`/me/registrations/${notification.relatedEntityId}`}
              className="text-sm text-primary underline"
            >
              Xem vé
            </Link>
          ) : null
        }
      />
      {totalPages > 1 && (
        <nav aria-label="Phân trang" className="flex items-center gap-3">
          <Button variant="outline" size="sm" disabled={page === 0} onClick={() => goTo(page - 1)}>
            Trang trước
          </Button>
          <span className="text-sm text-muted-foreground">
            Trang {page + 1} / {totalPages}
          </span>
          <Button
            variant="outline"
            size="sm"
            disabled={page + 1 >= totalPages}
            onClick={() => goTo(page + 1)}
          >
            Trang sau
          </Button>
        </nav>
      )}
    </main>
  )
}

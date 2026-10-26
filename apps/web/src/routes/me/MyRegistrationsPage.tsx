import { Link, useSearchParams } from 'react-router'

import { MyRegistrationList, useMyRegistrations } from '@/features/registrations'
import { Button } from '@/shared/ui/button'
import { WorkshopName } from './WorkshopName'

const PAGE_SIZE = 20

// Page number lives in the URL (web-coding-standards.md §8): the list is linkable and survives a
// reload. A value that is not a non-negative integer is page 0.
function pageFrom(params: URLSearchParams): number {
  const page = Number(params.get('page'))
  return Number.isInteger(page) && page >= 0 ? page : 0
}

// The signed-in user's own registrations (listMyRegistrations is scoped to the caller). Showing the
// QR e-ticket of a confirmed one is Phase 5 (checklist 4, M-42).
export function MyRegistrationsPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const page = pageFrom(searchParams)
  const registrations = useMyRegistrations({ page, size: PAGE_SIZE })
  const totalPages = registrations.data?.page.totalPages ?? 0

  const goTo = (next: number) => setSearchParams(next === 0 ? {} : { page: String(next) })

  return (
    <main className="mx-auto max-w-3xl space-y-4 p-6">
      <h1 className="text-2xl font-semibold text-foreground">Đăng ký của tôi</h1>
      <MyRegistrationList
        query={registrations}
        renderWorkshop={(workshopId) => <WorkshopName workshopId={workshopId} />}
        renderActions={(registration) =>
          registration.status === 'RESERVED' && registration.ticketType === 'PAID' ? (
            <Link to={`/me/checkout/${registration.id}`} className="text-sm text-primary underline">
              Thanh toán
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

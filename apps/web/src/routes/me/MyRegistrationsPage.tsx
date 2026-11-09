import { Link, useSearchParams } from 'react-router'

import { MyRegistrationList, useMyRegistrations, type RegistrationSummary } from '@/features/registrations'
import { Button } from '@/shared/ui/button'
import { Pagination } from '@/shared/ui/pagination'
import { useHasStarted } from './use-has-started'
import { PastFlag, WorkshopHeading, WorkshopThumbnail } from './WorkshopParts'

const PAGE_SIZE = 20

// Page number lives in the URL (web-coding-standards.md §8): the list is linkable and survives a
// reload. A value that is not a non-negative integer is page 0.
function pageFrom(params: URLSearchParams): number {
  const page = Number(params.get('page'))
  return Number.isInteger(page) && page >= 0 ? page : 0
}

// The main action of a row follows the status (D150): the ticket of a confirmed registration (quieter once the workshop
// has started), payment of a held paid one, the details of anything else. Every row also links to its own page.
function RegistrationActions({ registration }: { registration: RegistrationSummary }) {
  const started = useHasStarted(registration.workshopId)
  const page = `/me/registrations/${registration.id}`
  if (registration.status === 'CONFIRMED') {
    return (
      <Button asChild variant={started ? 'outline' : 'default'} size="lg" className="col-span-2 sm:col-span-1">
        <Link to={page}>Xem vé</Link>
      </Button>
    )
  }
  if (registration.status === 'RESERVED' && registration.ticketType === 'PAID') {
    return (
      <>
        <Button asChild size="lg">
          <Link to={`/me/checkout/${registration.id}`}>Thanh toán</Link>
        </Button>
        <Button asChild variant="link" size="lg">
          <Link to={page}>Chi tiết</Link>
        </Button>
      </>
    )
  }
  return (
    <Button asChild variant="outline" size="lg" className="col-span-2 sm:col-span-1">
      <Link to={page}>Chi tiết</Link>
    </Button>
  )
}

// The signed-in user's own registrations (listMyRegistrations is scoped to the caller), newest registration first. There
// is no "upcoming / past" grouping: the summary carries no workshop time and the list is ordered by when the user
// registered, so a group would wait for every workshop to load and be cut in two by the paging (D150).
export function MyRegistrationsPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const page = pageFrom(searchParams)
  const registrations = useMyRegistrations({ page, size: PAGE_SIZE })
  const totalPages = registrations.data?.page.totalPages ?? 0

  const goTo = (next: number) => setSearchParams(next === 0 ? {} : { page: String(next) })

  return (
    <main className="mx-auto max-w-4xl space-y-5 px-4 py-6 md:px-6 md:py-8">
      <h1 className="text-[28px] leading-9 font-bold tracking-tight md:text-3xl md:leading-[38px]">Đăng ký của tôi</h1>
      <MyRegistrationList
        query={registrations}
        renderThumbnail={(workshopId) => <WorkshopThumbnail workshopId={workshopId} />}
        renderWorkshop={(workshopId) => <WorkshopHeading workshopId={workshopId} />}
        renderFlags={(workshopId) => <PastFlag workshopId={workshopId} />}
        renderActions={(registration) => <RegistrationActions registration={registration} />}
        emptyAction={
          <Button asChild>
            <Link to="/workshops">Xem danh sách workshop</Link>
          </Button>
        }
      />
      <Pagination page={page} pageCount={totalPages} onPageChange={goTo} className="pt-2" />
    </main>
  )
}

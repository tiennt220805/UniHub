import { useRef } from 'react'

import { Button } from '@/shared/ui/button'
import { EmptyState } from '@/shared/ui/state-panel'
import { Pagination } from '@/shared/ui/pagination'
import { Skeleton } from '@/shared/ui/skeleton'
import { useToday } from '@/shared/hooks/use-today'
import { dayShort } from '@/shared/lib/vn-time'
import { useWorkshops } from '../api/queries'
import { useCatalogSearch } from '../hooks/use-catalog-search'
import { activeFilterCount, hasBackwardsRange, toApiFilters, type CatalogSearch } from '../model/catalog-filters'
import { groupByDay } from '../model/day-groups'
import { CatalogFilters } from './CatalogFilters'
import { LoadError } from './LoadError'
import { WorkshopCardGrid } from './WorkshopCard'

type Props = {
  /** URL of a workshop's detail page: owned by the route table, not by this feature. */
  detailPath: (workshopId: string) => string
}

/** "từ 09/11/2026 đến 30/11/2026 · Chủ đề "cloud"": the filters in force, said back to the user. */
function describeFilters(search: CatalogSearch, today: string): string {
  const parts: string[] = [`từ ${dayShort(search.dateFrom || today)}`]
  if (search.dateTo) parts[0] += ` đến ${dayShort(search.dateTo)}`
  if (search.q) parts.push(`Chủ đề "${search.q}"`)
  if (search.speaker) parts.push(`Diễn giả "${search.speaker}"`)
  if (search.location) parts.push(`Địa điểm "${search.location}"`)
  return parts.join(' · ')
}

export function WorkshopGridSkeleton({ count = 6 }: { count?: number }) {
  return (
    <div aria-busy="true">
      <p role="status" className="sr-only">
        Đang tải danh sách workshop…
      </p>
      <ul className="grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
        {Array.from({ length: count }, (_, i) => (
          <li key={i} className="overflow-hidden rounded-xl border border-border bg-card">
            <Skeleton className="aspect-video rounded-none" />
            <div className="grid gap-2 p-4">
              <Skeleton className="h-4 w-1/2" />
              <Skeleton className="h-5 w-11/12" />
              <Skeleton className="h-4 w-3/5" />
              <Skeleton className="mt-2 h-1.5 w-full" />
            </div>
          </li>
        ))}
      </ul>
    </div>
  )
}

// The catalog (S2): filters that live in the URL, results grouped by day, pagination. The day grouping and the
// default start date are decisions D138-D145 of Phase 8.
export function WorkshopCatalog({ detailPath }: Props) {
  const { search, update, clear } = useCatalogSearch()
  const today = useToday()
  const backwards = hasBackwardsRange(search, today)
  const query = useWorkshops(toApiFilters(search, today), { enabled: !backwards })
  const top = useRef<HTMLDivElement>(null)

  // The server answers 400 for the one thing the client checks first; if it does anyway, say it next to the dates.
  const rangeError = backwards || query.error?.status === 400
  const goToPage = (page: number) => {
    update({ page })
    top.current?.scrollIntoView?.({ block: 'start' })
  }

  return (
    <div className="grid gap-6" ref={top}>
      <CatalogFilters search={search} today={today} rangeError={rangeError} onChange={update} onClear={clear} />
      <Results
        query={query}
        search={search}
        today={today}
        hidden={backwards || query.error?.status === 400}
        detailPath={detailPath}
        onPage={goToPage}
        onClear={clear}
      />
    </div>
  )
}

function Results({
  query,
  search,
  today,
  hidden,
  detailPath,
  onPage,
  onClear,
}: {
  query: ReturnType<typeof useWorkshops>
  search: CatalogSearch
  today: string
  hidden: boolean
  detailPath: (workshopId: string) => string
  onPage: (page: number) => void
  onClear: () => void
}) {
  if (hidden) return null
  if (query.isPending) return <WorkshopGridSkeleton />
  if (query.isError) {
    return <LoadError error={query.error} message="Không tải được danh sách workshop." onRetry={() => void query.refetch()} />
  }

  const { items, page: paging } = query.data
  const filtered = activeFilterCount(search) > 0

  if (items.length === 0 && search.page > 0 && paging.totalPages > 0) {
    return (
      <EmptyState
        title="Trang này không còn nội dung"
        description={`Bạn đang ở trang ${search.page + 1} nhưng kết quả chỉ có ${paging.totalPages} trang (kết quả có thể vừa thay đổi).`}
        action={<Button onClick={() => onPage(0)}>Về trang 1</Button>}
      />
    )
  }
  if (items.length === 0) {
    return filtered ? (
      <EmptyState
        title="Không có workshop phù hợp"
        description={`Không tìm thấy workshop nào ${describeFilters(search, today)}. Thử bỏ bớt bộ lọc hoặc mở rộng khoảng ngày.`}
        action={<Button onClick={onClear}>Xóa bộ lọc</Button>}
      />
    ) : (
      <EmptyState title="Chưa có workshop sắp diễn ra" description="Hiện chưa có workshop nào được công bố từ hôm nay. Hãy quay lại sau nhé." />
    )
  }

  return (
    <div aria-busy={query.isFetching} className={query.isPlaceholderData ? 'opacity-60 transition-opacity' : 'transition-opacity'}>
      <p role="status" className="text-sm text-muted-foreground">
        Tìm thấy <b className="font-semibold text-foreground">{paging.totalElements} workshop</b> {describeFilters(search, today)}
        {paging.totalPages > 1 && ` · Trang ${paging.page + 1}/${paging.totalPages}`}
      </p>
      {groupByDay(items).map((group) => (
        <section key={group.day} aria-labelledby={`day-${group.day}`} className="mt-8 first-of-type:mt-4">
          <div className="mb-4 flex items-baseline gap-3 border-b border-border pb-2">
            <h2 id={`day-${group.day}`} className="text-2xl leading-8 font-semibold">
              {group.heading}
            </h2>
            <span className="text-sm text-muted-foreground">{group.items.length} workshop</span>
          </div>
          <WorkshopCardGrid items={group.items} detailPath={detailPath} />
        </section>
      ))}
      <Pagination className="mt-10" page={paging.page} pageCount={paging.totalPages} onPageChange={onPage} />
    </div>
  )
}

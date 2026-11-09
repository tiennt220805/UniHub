import { Plus, Search } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { Link } from 'react-router'

import { LoadError } from '@/shared/components/LoadError'
import { useDraft } from '@/shared/hooks/use-draft'
import { formatNumber, formatPrice } from '@/shared/lib/format'
import { cn } from '@/shared/lib/utils'
import { clock, dayHeading, ymdOf } from '@/shared/lib/vn-time'
import { Button } from '@/shared/ui/button'
import { Input } from '@/shared/ui/input'
import { Pagination } from '@/shared/ui/pagination'
import { Skeleton } from '@/shared/ui/skeleton'
import { EmptyState } from '@/shared/ui/state-panel'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/shared/ui/tabs'
import { useManagedWorkshops } from '../api/queries'
import type { ManagedWorkshop } from '../api/requests'
import { useManagedSearch } from '../hooks/use-managed-search'
import { MANAGED_TABS, toManagedFilters, type ManagedSearch, type ManagedTab } from '../model/managed-search'
import { hasEnded, isNearlyFull, isTerminal } from '../model/workshop-status'
import { EndedBadge, WorkshopStatusBadge } from './WorkshopStatusBadge'

type Props = {
  /** The edit page of a workshop: owned by the route table. */
  workshopPath: (workshopId: string) => string
  /** Present only for someone who may create a workshop (WORKSHOP_CREATE). */
  createPath?: string
  /** The small cover of a row: the workshop feature's, supplied by the route (features do not import each other). */
  renderCover: (workshop: ManagedWorkshop) => ReactNode
  /** What the row's menu holds besides "Sửa": publish, cancel, the report. Supplied by the route for the same reason. */
  renderRowMenu?: (workshop: ManagedWorkshop) => ReactNode
}

const EMPTY_TEXT: Record<ManagedTab, { title: string; description: string }> = {
  ALL: { title: 'Chưa có workshop nào', description: 'Tạo workshop đầu tiên để bắt đầu.' },
  DRAFT: { title: 'Chưa có bản nháp nào', description: 'Bản nháp là workshop sinh viên chưa thấy. Tạo một workshop để bắt đầu.' },
  PUBLISHED: { title: 'Chưa có workshop nào được công bố', description: 'Công bố một bản nháp để sinh viên thấy và đăng ký.' },
  CANCELLED: { title: 'Chưa có workshop nào bị hủy', description: 'Workshop bị hủy sẽ nằm ở đây.' },
}

function errorMessage(status: number | null): string {
  if (status === 403) return 'Bạn không có quyền xem danh sách này.'
  return 'Không tải được danh sách workshop.'
}

function ListSkeleton() {
  return (
    <div aria-busy="true" className="grid gap-3 rounded-2xl border border-border bg-card p-4">
      <p role="status" className="sr-only">
        Đang tải danh sách workshop…
      </p>
      {Array.from({ length: 5 }, (_, i) => (
        <div key={i} className="flex items-center gap-3">
          <Skeleton className="h-9 w-16 rounded-md" />
          <div className="grid flex-1 gap-1.5">
            <Skeleton className="h-4 w-2/3" />
            <Skeleton className="h-3 w-1/3" />
          </div>
        </div>
      ))}
    </div>
  )
}

function SeatCell({ workshop }: { workshop: ManagedWorkshop }) {
  const fill = workshop.capacity > 0 ? Math.min(workshop.seatsTaken / workshop.capacity, 1) : 1
  return (
    <div className="grid min-w-24 gap-1 tabular-nums">
      <span>
        {formatNumber(workshop.seatsTaken)} / {formatNumber(workshop.capacity)}
      </span>
      <div aria-hidden="true" className="h-1.5 overflow-hidden rounded-full bg-seat-track">
        <div
          className={cn('h-full', isNearlyFull(workshop.capacity, workshop.seatsTaken) ? 'bg-seat-low' : 'bg-seat-ok')}
          style={{ width: `${Math.round(fill * 100)}%` }}
        />
      </div>
    </div>
  )
}

function Row({
  workshop,
  nowMs,
  workshopPath,
  renderCover,
  renderRowMenu,
}: { workshop: ManagedWorkshop; nowMs: number } & Pick<Props, 'workshopPath' | 'renderCover' | 'renderRowMenu'>) {
  const terminal = isTerminal(workshop.status)
  const start = workshop.startTime
  return (
    <tr className="border-b border-border last:border-b-0">
      <td className="px-4 py-3">
        <div className="flex min-w-0 items-center gap-3">
          <div className="w-16 shrink-0 overflow-hidden rounded-md border border-border">{renderCover(workshop)}</div>
          <div className="min-w-0">
            <Link
              to={workshopPath(workshop.id)}
              className="block font-semibold text-foreground underline-offset-4 hover:underline focus-visible:rounded focus-visible:outline-2 focus-visible:outline-ring"
            >
              {workshop.title}
            </Link>
            <span className="block text-xs text-muted-foreground">
              {workshop.speakerName}
              <span className="lg:hidden"> · {workshop.location}</span>
            </span>
          </div>
        </div>
      </td>
      <td className="px-4 py-3 whitespace-nowrap tabular-nums">
        {dayHeading(ymdOf(start))}
        <span className="block text-xs text-muted-foreground">
          {clock(start)}–{clock(workshop.endTime)}
        </span>
      </td>
      <td className="hidden px-4 py-3 lg:table-cell">{workshop.location}</td>
      <td className="px-4 py-3">
        <SeatCell workshop={workshop} />
      </td>
      <td className="hidden px-4 py-3 whitespace-nowrap tabular-nums lg:table-cell">
        {workshop.isPaid && workshop.price != null ? formatPrice(workshop.price) : 'Miễn phí'}
      </td>
      <td className="px-4 py-3">
        <div className="flex flex-col items-start gap-1">
          <WorkshopStatusBadge status={workshop.status} />
          {hasEnded(workshop.status, workshop.endTime, nowMs) && <EndedBadge />}
        </div>
      </td>
      <td className="px-4 py-3 text-right whitespace-nowrap">
        <div className="flex items-center justify-end gap-1">
          <Button asChild variant="outline" size="sm">
            <Link to={workshopPath(workshop.id)} aria-label={`${terminal ? 'Xem' : 'Sửa'} ${workshop.title}`}>
              {terminal ? 'Xem' : 'Sửa'}
            </Link>
          </Button>
          {renderRowMenu?.(workshop)}
        </div>
      </td>
    </tr>
  )
}

/** The management list (S10): tabs by status, a title search, pagination; every filter is in the URL (D159). */
export function ManagedWorkshopList({ workshopPath, createPath, renderCover, renderRowMenu }: Props) {
  const { search, update } = useManagedSearch()
  const query = useManagedWorkshops(toManagedFilters(search))
  // Read once: it only decides the "Đã diễn ra" label (D158).
  const [nowMs] = useState(() => Date.now())
  const [draft, setDraft] = useDraft(search.q, (next) => update({ q: next }, { replace: true }))

  const data = query.data
  const clearSearch = () => update({ q: '' })

  return (
    <div className="grid min-w-0 gap-4">
      <Tabs value={search.tab} onValueChange={(tab) => update({ tab: tab as ManagedTab })} className="min-w-0 gap-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <TabsList aria-label="Trạng thái">
            {MANAGED_TABS.map((tab) => (
              <TabsTrigger key={tab.value} value={tab.value}>
                {tab.label}
              </TabsTrigger>
            ))}
          </TabsList>
          <div className="relative w-full sm:w-80">
            <Search aria-hidden="true" className="pointer-events-none absolute top-3 left-3 size-4 text-muted-foreground" />
            <label htmlFor="managed-search" className="sr-only">
              Tìm theo tên workshop
            </label>
            <Input
              id="managed-search"
              type="search"
              value={draft}
              onChange={(event) => setDraft(event.target.value)}
              placeholder="Tìm theo tên workshop"
              className="pl-9"
            />
          </div>
        </div>

        <TabsContent value={search.tab} className="grid min-w-0 gap-4">
          {query.isPending ? (
            <ListSkeleton />
          ) : data === undefined ? (
            <LoadError error={query.error} message={errorMessage(query.error.status)} onRetry={() => void query.refetch()} />
          ) : data.items.length === 0 ? (
            <EmptyPanel search={search} createPath={createPath} onClearSearch={clearSearch} />
          ) : (
            <>
              <div className={cn('relative overflow-x-auto rounded-2xl border border-border bg-card', query.isPlaceholderData && 'opacity-60')}>
                <table className="w-full min-w-[720px] border-collapse text-sm">
                  <caption className="sr-only">Danh sách workshop</caption>
                  <thead>
                    <tr className="border-b border-border text-left text-xs tracking-wide text-muted-foreground uppercase">
                      <th scope="col" className="min-w-72 px-4 py-3 font-semibold">Workshop</th>
                      <th scope="col" className="px-4 py-3 font-semibold">Bắt đầu</th>
                      <th scope="col" className="hidden min-w-36 px-4 py-3 font-semibold lg:table-cell">Địa điểm</th>
                      <th scope="col" className="px-4 py-3 font-semibold">Chỗ đã giữ</th>
                      <th scope="col" className="hidden px-4 py-3 font-semibold lg:table-cell">Phí</th>
                      <th scope="col" className="px-4 py-3 font-semibold">Trạng thái</th>
                      <th scope="col" className="px-4 py-3"><span className="sr-only">Thao tác</span></th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.items.map((workshop) => (
                      <Row
                        key={workshop.id}
                        workshop={workshop}
                        nowMs={nowMs}
                        workshopPath={workshopPath}
                        renderCover={renderCover}
                        renderRowMenu={renderRowMenu}
                      />
                    ))}
                  </tbody>
                </table>
              </div>
              <div className="flex flex-wrap items-center justify-between gap-3 text-sm text-muted-foreground">
                <span className="tabular-nums">
                  Dòng {formatNumber(data.page.page * data.page.size + 1)}–
                  {formatNumber(data.page.page * data.page.size + data.items.length)} trong {formatNumber(data.page.totalElements)} workshop
                </span>
                <Pagination page={data.page.page} pageCount={data.page.totalPages} onPageChange={(page) => update({ page })} />
              </div>
            </>
          )}
        </TabsContent>
      </Tabs>
    </div>
  )
}

function EmptyPanel({ search, createPath, onClearSearch }: { search: ManagedSearch; createPath?: string; onClearSearch: () => void }) {
  if (search.q) {
    return (
      <EmptyState
        className="rounded-2xl border border-border bg-card"
        title={`Không có workshop nào khớp “${search.q}”`}
        description="Thử một phần khác của tên, hoặc xóa tìm kiếm."
        action={
          <Button variant="outline" onClick={onClearSearch}>
            Xóa tìm kiếm
          </Button>
        }
      />
    )
  }
  const text = EMPTY_TEXT[search.tab]
  const canCreateHere = createPath !== undefined && (search.tab === 'ALL' || search.tab === 'DRAFT')
  return (
    <EmptyState
      className="rounded-2xl border border-border bg-card"
      title={text.title}
      description={text.description}
      action={
        canCreateHere ? (
          <Button asChild>
            <Link to={createPath}>
              <Plus aria-hidden="true" />
              Tạo workshop
            </Link>
          </Button>
        ) : undefined
      }
    />
  )
}

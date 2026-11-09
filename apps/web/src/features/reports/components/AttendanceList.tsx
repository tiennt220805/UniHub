import { useEffect, useRef } from 'react'

import { LoadError } from '@/shared/components/LoadError'
import { formatNumber, formatTimestamp } from '@/shared/lib/format'
import { cn } from '@/shared/lib/utils'
import { Pagination } from '@/shared/ui/pagination'
import { Skeleton } from '@/shared/ui/skeleton'
import { EmptyState } from '@/shared/ui/state-panel'
import { useWorkshopAttendances } from '../api/queries'

type Props = {
  workshopId: string
  /** Zero-based, as the API's; the route keeps it in the URL. */
  page: number
  onPageChange: (page: number) => void
  /** How many check-ins the report counts now: when it differs from what the list holds, the list is read again once. */
  checkedInCount: number
}

function errorMessage(status: number | null): string {
  if (status === 403) return 'Bạn không có quyền xem danh sách điểm danh.'
  if (status === 404) return 'Không tìm thấy workshop này.'
  return 'Không tải được danh sách điểm danh.'
}

/**
 * The check-in list (S15): when each registration was scanned, oldest first. It has only the registration's id, no student name
 * (D165): the first eight characters identify a row, the whole id is its tooltip.
 */
export function AttendanceList({ workshopId, page, onPageChange, checkedInCount }: Props) {
  const query = useWorkshopAttendances(workshopId, page)
  const { refetch } = query
  const total = query.data?.page.totalElements

  // The report refreshes itself every ten seconds; this list does not poll. When the report's count moves away from the list's,
  // the list is read once more. It runs when the count changes, not on every render, so a lagging replica cannot make a loop.
  const latest = useRef({ total, refetch })
  useEffect(() => {
    latest.current = { total, refetch }
  })
  useEffect(() => {
    const { total: held, refetch: read } = latest.current
    if (held !== undefined && held !== checkedInCount) void read()
  }, [checkedInCount])

  const data = query.data
  return (
    <section aria-label="Danh sách điểm danh" className="grid gap-4 rounded-2xl border border-border bg-card p-5">
      <div>
        <h2 className="text-base leading-6 font-semibold">Danh sách điểm danh</h2>
        <p className="mt-0.5 text-sm text-muted-foreground">Sớm nhất trước. Chỉ gồm đăng ký đã được quét thành công; chưa có tên sinh viên.</p>
      </div>
      {query.isPending ? (
        <div aria-busy="true" className="grid gap-2">
          <p role="status" className="sr-only">
            Đang tải danh sách điểm danh…
          </p>
          {Array.from({ length: 4 }, (_, i) => (
            <Skeleton key={i} className="h-5 w-full" />
          ))}
        </div>
      ) : data === undefined ? (
        <LoadError error={query.error} message={errorMessage(query.error.status)} onRetry={() => void refetch()} />
      ) : data.items.length === 0 && page === 0 ? (
        <EmptyState
          title="Chưa có lượt check-in nào"
          description="Danh sách hiện khi nhân viên quét vé đầu tiên và thiết bị đồng bộ."
        />
      ) : (
        <>
          <div className={cn('relative overflow-x-auto rounded-xl border border-border', query.isPlaceholderData && 'opacity-60')}>
            <table className="w-full border-collapse text-sm">
              <caption className="sr-only">Lượt check-in, sớm nhất trước</caption>
              <thead>
                <tr className="border-b border-border text-left text-xs tracking-wide text-muted-foreground uppercase">
                  <th scope="col" className="px-4 py-3 font-semibold">Thời điểm check-in</th>
                  <th scope="col" className="px-4 py-3 font-semibold">Mã đăng ký</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((attendance) => (
                  <tr key={attendance.registrationId} className="border-b border-border last:border-b-0">
                    <td className="px-4 py-2.5 tabular-nums">{formatTimestamp(attendance.checkedInAt)}</td>
                    <td className="px-4 py-2.5 font-mono text-[13px]" title={attendance.registrationId}>
                      {attendance.registrationId.slice(0, 8)}…
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="flex flex-wrap items-center justify-between gap-3 text-sm text-muted-foreground">
            <span className="tabular-nums">
              {data.items.length === 0
                ? 'Trang này không có dòng nào.'
                : `Dòng ${formatNumber(data.page.page * data.page.size + 1)}–${formatNumber(data.page.page * data.page.size + data.items.length)} trong ${formatNumber(data.page.totalElements)}`}
            </span>
            <Pagination page={data.page.page} pageCount={data.page.totalPages} onPageChange={onPageChange} />
          </div>
        </>
      )}
    </section>
  )
}

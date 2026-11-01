import type { UseQueryResult } from '@tanstack/react-query'

import type { AppError } from '@/shared/api/errors'
import { formatNumber, formatPercent } from '@/shared/lib/format'
import { describeCheckInRate } from '../model/check-in-rate'
import type { WorkshopReport } from '../api/requests'

type Props = {
  query: UseQueryResult<WorkshopReport, AppError>
}

function Figure({ label, value }: { label: string; value: number }) {
  return (
    <div className="rounded-lg border p-4">
      <dt className="text-sm text-muted-foreground">{label}</dt>
      <dd className="text-2xl font-semibold">{formatNumber(value)}</dd>
    </div>
  )
}

function messageFor(error: AppError): string {
  if (error.status === 404) return 'Không tìm thấy workshop này.'
  if (error.status === 403) return 'Bạn không có quyền xem báo cáo này.'
  if (error.status === 429) return 'Bạn đang gửi quá nhiều yêu cầu. Vui lòng thử lại sau.'
  return 'Không tải được báo cáo.'
}

// Every figure is counted by the Backend (checkin.md §10); this view adds nothing up. A refetch that
// fails keeps showing the last figures rather than blanking the page (TanStack keeps `data`).
export function WorkshopReportView({ query }: Props) {
  if (query.isPending) return <p>Đang tải…</p>
  const report = query.data
  if (report === undefined) {
    const { error } = query
    return (
      <p role="alert">
        {messageFor(error)}
        {error.problem?.correlationId && (
          <span className="block text-sm">Mã tham chiếu: {error.problem.correlationId}</span>
        )}
      </p>
    )
  }
  const rate = describeCheckInRate(report.checkInRate, formatPercent)

  return (
    <section aria-label="Báo cáo workshop" className="space-y-6">
      <dl className="grid gap-3 sm:grid-cols-2">
        <Figure label="Sức chứa" value={report.capacity} />
        <Figure label="Chỗ đã giữ" value={report.seatsTaken} />
        <Figure label="Đăng ký đã xác nhận" value={report.confirmedCount} />
        <Figure label="Đang giữ chỗ (chưa thanh toán)" value={report.reservedCount} />
        <Figure label="Đã check-in" value={report.checkedInCount} />
      </dl>

      <div className="space-y-2">
        <h2 className="font-medium">Tỷ lệ check-in</h2>
        {rate.kind === 'none' ? (
          <p className="text-muted-foreground">Chưa có dữ liệu (chưa có đăng ký nào được xác nhận).</p>
        ) : (
          <>
            <p className="text-2xl font-semibold">{rate.label}</p>
            <div
              role="progressbar"
              aria-label="Tỷ lệ check-in"
              aria-valuemin={0}
              aria-valuemax={100}
              aria-valuenow={Math.round(rate.barPercent)}
              className="h-3 w-full overflow-hidden rounded bg-muted"
            >
              <div className="h-full bg-primary" style={{ width: `${rate.barPercent}%` }} />
            </div>
            {rate.exceedsAll && (
              <p className="text-sm text-muted-foreground">
                Số lượt check-in nhiều hơn số đăng ký đã xác nhận: có đăng ký bị hủy sau khi đã check-in.
              </p>
            )}
          </>
        )}
      </div>

      <p className="text-sm text-muted-foreground">Số liệu tự làm mới khoảng 10 giây một lần khi trang đang mở.</p>
    </section>
  )
}

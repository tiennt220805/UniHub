import type { UseQueryResult } from '@tanstack/react-query'

import type { AppError } from '@/shared/api/errors'
import { formatNumber, formatPercent } from '@/shared/lib/format'
import { describeCheckInRate } from '../model/check-in-rate'
import { CheckInRing } from './CheckInRing'
import type { WorkshopReport } from '../api/requests'

type Props = {
  query: UseQueryResult<WorkshopReport, AppError>
}

function Figure({ label, value }: { label: string; value: number }) {
  return (
    // A container, so the number sizes itself to the card it is in: 28 px when the card is wide enough, down to 20 px for a
    // nine-digit count in a narrow one (the worst-case spec). `anywhere` is only the last resort for something longer still.
    <div className="@container rounded-xl border border-border bg-card px-4 py-3.5">
      <dt className="text-[13px] leading-[18px] text-muted-foreground [overflow-wrap:anywhere]">{label}</dt>
      <dd className="mt-1 text-[clamp(1.25rem,13cqw,1.75rem)] leading-9 font-bold tabular-nums [overflow-wrap:anywhere]">{formatNumber(value)}</dd>
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
    <section aria-label="Báo cáo workshop" className="grid gap-5">
      <div className="grid gap-5 lg:grid-cols-[320px_minmax(0,1fr)]">
        <div className="rounded-2xl border border-border bg-card p-5">
          <CheckInRing rate={rate} checkedIn={report.checkedInCount} confirmed={report.confirmedCount} />
        </div>
        <dl className="m-0 grid content-start gap-3 sm:grid-cols-2 xl:grid-cols-3">
          <Figure label="Sức chứa" value={report.capacity} />
          <Figure label="Chỗ đã giữ" value={report.seatsTaken} />
          <Figure label="Đăng ký đã xác nhận" value={report.confirmedCount} />
          <Figure label="Đang giữ chỗ (chưa thanh toán)" value={report.reservedCount} />
          <Figure label="Đã check-in" value={report.checkedInCount} />
        </dl>
      </div>
      <p className="text-sm text-muted-foreground">Số liệu tự làm mới khoảng 10 giây một lần khi trang đang mở.</p>
    </section>
  )
}

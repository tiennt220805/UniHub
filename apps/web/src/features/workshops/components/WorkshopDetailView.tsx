import type { UseQueryResult } from '@tanstack/react-query'

import type { AppError } from '@/shared/api/errors'
import { formatDateTime, formatNumber } from '@/shared/lib/format'
import type { Workshop } from '../api/requests'
import { LoadError } from './LoadError'

type Props = {
  query: UseQueryResult<Workshop, AppError>
}

// A 404 is either an unknown id or a DRAFT hidden from this caller — deliberately
// indistinguishable (paths/workshop.yaml#/workshopById). The only 400 getWorkshop returns is a path
// id that is not a UUID, which to the viewer is the same thing: there is no such workshop.
const isNotFound = (error: AppError) => error.status === 404 || error.status === 400

export function WorkshopDetailView({ query }: Props) {
  if (query.isPending) return <p>Đang tải…</p>
  if (query.isError) {
    return isNotFound(query.error) ? (
      <p>Không tìm thấy workshop.</p>
    ) : (
      <LoadError error={query.error} message="Không tải được thông tin workshop." />
    )
  }
  return <WorkshopDetail workshop={query.data} />
}

const STATUS_NOTICE: Partial<Record<Workshop['status'], string>> = {
  CANCELLED: 'Workshop này đã bị hủy.',
  COMPLETED: 'Workshop này đã kết thúc.',
}

function WorkshopDetail({ workshop }: { workshop: Workshop }) {
  // seatsTaken is near-real-time and advisory (contract: Workshop.seatsTaken); the floor at 0 only
  // keeps a momentarily inconsistent read from rendering a negative count.
  const seatsLeft = Math.max(workshop.capacity - workshop.seatsTaken, 0)
  const notice = STATUS_NOTICE[workshop.status]

  return (
    <article className="mt-4 space-y-6">
      <h1 className="text-2xl font-semibold text-foreground">{workshop.title}</h1>
      {notice && (
        <p role="status" className="rounded-md bg-muted p-3 text-foreground">
          {notice}
        </p>
      )}

      <dl className="grid grid-cols-[max-content_1fr] gap-x-6 gap-y-2">
        <dt className="text-muted-foreground">Diễn giả</dt>
        <dd>{workshop.speakerName}</dd>
        <dt className="text-muted-foreground">Địa điểm</dt>
        <dd>{workshop.location}</dd>
        <dt className="text-muted-foreground">Bắt đầu</dt>
        <dd>
          <time dateTime={workshop.startTime}>{formatDateTime(workshop.startTime)}</time>
        </dd>
        <dt className="text-muted-foreground">Kết thúc</dt>
        <dd>
          <time dateTime={workshop.endTime}>{formatDateTime(workshop.endTime)}</time>
        </dd>
        <dt className="text-muted-foreground">Số ghế</dt>
        <dd>{seatsLeft === 0 ? 'Hết chỗ' : `Còn ${seatsLeft} / ${workshop.capacity} chỗ`}</dd>
        <dt className="text-muted-foreground">Phí tham dự</dt>
        <dd>{workshop.isPaid && workshop.price != null ? formatNumber(workshop.price) : 'Miễn phí'}</dd>
      </dl>

      {workshop.description && (
        <section>
          <h2 className="mb-2 text-lg font-medium text-foreground">Giới thiệu</h2>
          <p>{workshop.description}</p>
        </section>
      )}
      {workshop.aiSummary && (
        <section>
          <h2 className="mb-2 text-lg font-medium text-foreground">Tóm tắt (AI)</h2>
          <p>{workshop.aiSummary}</p>
        </section>
      )}
      {workshop.speakerBio && (
        <section>
          <h2 className="mb-2 text-lg font-medium text-foreground">Về diễn giả</h2>
          <p>{workshop.speakerBio}</p>
        </section>
      )}
    </article>
  )
}

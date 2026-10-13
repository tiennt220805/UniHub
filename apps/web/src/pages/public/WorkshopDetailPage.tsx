import { useQuery } from '@tanstack/react-query'
import { Link, useParams } from 'react-router'

import { formatDateTime, formatNumber } from '@/lib/format'
import type { components } from '@/types/schema.gen'

type Workshop = components['schemas']['Workshop']

// GET /api/v1/workshops/{id} is public (packages/api-contract/paths/workshop.yaml: security: []).
// No token is sent yet, so a DRAFT workshop always comes back 404 here — by design the API makes
// it indistinguishable from an unknown id. A 400 can only mean the path id is not a UUID, which to
// the viewer is the same thing: there is no such workshop.
export function WorkshopDetailPage() {
  const { workshopId = '' } = useParams()
  const { data, isPending, isError } = useQuery({
    queryKey: ['workshops', 'detail', workshopId],
    queryFn: async (): Promise<Workshop | null> => {
      const response = await fetch(`/api/v1/workshops/${encodeURIComponent(workshopId)}`)
      if (response.status === 404 || response.status === 400) {
        return null
      }
      if (!response.ok) {
        throw new Error(`GET /api/v1/workshops/${workshopId} failed: ${response.status}`)
      }
      return response.json()
    },
  })

  return (
    <main className="mx-auto max-w-3xl p-6">
      <Link to="/workshops" className="text-sm text-muted-foreground hover:underline">
        ← Danh sách workshop
      </Link>
      {isPending && <p>Đang tải…</p>}
      {isError && <p role="alert">Không tải được thông tin workshop.</p>}
      {data === null && <p>Không tìm thấy workshop.</p>}
      {data && <WorkshopDetail workshop={data} />}
    </main>
  )
}

const STATUS_NOTICE: Partial<Record<Workshop['status'], string>> = {
  CANCELLED: 'Workshop này đã bị hủy.',
  COMPLETED: 'Workshop này đã kết thúc.',
}

function WorkshopDetail({ workshop }: { workshop: Workshop }) {
  // seatsTaken can be up to 3s stale (contract: Workshop.seatsTaken); the floor at 0 only keeps a
  // momentarily inconsistent read from rendering a negative count.
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

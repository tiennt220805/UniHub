import { Link } from 'react-router'

import { useWorkshop, WorkshopCover } from '@/features/workshops'
import { formatDateTime, formatPrice } from '@/shared/lib/format'
import { Skeleton } from '@/shared/ui/skeleton'

/**
 * What is being paid for: the workshop's cover, name, time and place, and the fee. The registration carries only the
 * workshop's id, and `getWorkshop` is the cached read the detail page already made. If the workshop cannot be read the
 * card shrinks to its name; the payment itself does not depend on it.
 */
export function CheckoutSummary({ workshopId }: { workshopId: string }) {
  const workshop = useWorkshop(workshopId)
  const data = workshop.data
  return (
    <section aria-labelledby="checkout-workshop" className="rounded-xl border border-border bg-card p-4 md:p-5">
      <h2 id="checkout-workshop" className="mb-3 text-lg leading-6 font-semibold">
        Workshop bạn đăng ký
      </h2>
      {workshop.isPending ? (
        <div role="status" aria-busy="true" className="flex gap-4">
          <span className="sr-only">Đang tải…</span>
          <Skeleton className="aspect-video w-36 shrink-0 rounded-lg" />
          <div className="grid flex-1 content-center gap-2">
            <Skeleton className="h-5 w-4/5" />
            <Skeleton className="h-4 w-3/5" />
          </div>
        </div>
      ) : (
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:gap-4">
          <WorkshopCover
            workshopId={workshopId}
            coverImageUrl={data?.coverImageUrl ?? null}
            className="w-full shrink-0 rounded-lg sm:w-36"
          />
          <div className="min-w-0">
            <Link to={`/workshops/${workshopId}`} className="text-lg leading-snug font-semibold hover:underline">
              {data?.title ?? 'Workshop'}
            </Link>
            {data && (
              <p className="mt-0.5 text-sm text-muted-foreground">
                <time dateTime={data.startTime}>{formatDateTime(data.startTime)}</time>
                {' · '}
                {data.location}
              </p>
            )}
          </div>
        </div>
      )}
      {data?.price != null && (
        <div className="mt-4 flex items-baseline justify-between border-t border-border pt-3">
          <span className="text-sm text-muted-foreground">Phí tham dự</span>
          <b className="text-[22px] leading-[30px] tabular-nums">{formatPrice(data.price)}</b>
        </div>
      )}
    </section>
  )
}

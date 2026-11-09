import { Ban, Globe } from 'lucide-react'
import { useState } from 'react'

import { formatNumber } from '@/shared/lib/format'
import { cn } from '@/shared/lib/utils'
import { Button } from '@/shared/ui/button'
import type { Workshop } from '../api/requests'
import { isNearlyFull, isTerminal } from '../model/workshop-status'
import { CancelWorkshopDialog, PublishWorkshopDialog } from './LifecycleDialogs'
import { WorkshopStatusBadge } from './WorkshopStatusBadge'

type Props = {
  workshop: Workshop
  canEdit: boolean
  onDone: (workshop: Workshop) => void
  onSettled?: () => void
}

/** The side card of the edit page: status, the seats now, and the two one-way changes (6.5). */
export function WorkshopStatusCard({ workshop, canEdit, onDone, onSettled }: Props) {
  const [dialog, setDialog] = useState<'publish' | 'cancel' | null>(null)
  const fill = workshop.capacity > 0 ? Math.min(workshop.seatsTaken / workshop.capacity, 1) : 1
  const canPublish = canEdit && workshop.status === 'DRAFT'
  const canCancel = canEdit && !isTerminal(workshop.status)

  return (
    <section aria-label="Trạng thái" className="grid gap-4 rounded-2xl border border-border bg-card p-5">
      <div className="flex items-center justify-between gap-2">
        <h2 className="text-base leading-6 font-semibold">Trạng thái</h2>
        <WorkshopStatusBadge status={workshop.status} />
      </div>
      <div className="grid gap-1.5">
        <div className="flex justify-between gap-3 text-sm tabular-nums">
          <span className="text-muted-foreground">Chỗ đã giữ</span>
          <span>
            <b className="font-semibold">{formatNumber(workshop.seatsTaken)}</b> / {formatNumber(workshop.capacity)}
          </span>
        </div>
        <div aria-hidden="true" className="h-1.5 overflow-hidden rounded-full bg-seat-track">
          <div
            className={cn('h-full', isNearlyFull(workshop.capacity, workshop.seatsTaken) ? 'bg-seat-low' : 'bg-seat-ok')}
            style={{ width: `${Math.round(fill * 100)}%` }}
          />
        </div>
        <p className="text-xs text-muted-foreground">Số liệu từ cơ sở dữ liệu chính, không qua cache.</p>
      </div>
      {canPublish && (
        <Button onClick={() => setDialog('publish')}>
          <Globe aria-hidden="true" />
          Công bố
        </Button>
      )}
      {canCancel && (
        <Button variant="outline" className="border-destructive text-destructive hover:bg-destructive/10" onClick={() => setDialog('cancel')}>
          <Ban aria-hidden="true" />
          {workshop.status === 'DRAFT' ? 'Hủy bản nháp' : 'Hủy workshop'}
        </Button>
      )}
      {canPublish && (
        <PublishWorkshopDialog
          workshop={workshop}
          open={dialog === 'publish'}
          onOpenChange={(open) => setDialog(open ? 'publish' : null)}
          onDone={onDone}
          onSettled={onSettled}
        />
      )}
      {canCancel && (
        <CancelWorkshopDialog
          workshop={workshop}
          open={dialog === 'cancel'}
          onOpenChange={(open) => setDialog(open ? 'cancel' : null)}
          onDone={onDone}
          onSettled={onSettled}
        />
      )}
    </section>
  )
}

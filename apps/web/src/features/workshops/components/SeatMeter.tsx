import { Check, CircleX, Clock } from 'lucide-react'

import { cn } from '@/shared/lib/utils'
import { seatFill, seatLevel, SEAT_LEVEL_LABEL, type SeatLevel } from '../model/seat-level'

const LEVEL_STYLE: Record<SeatLevel, { bar: string; text: string; Icon: typeof Check }> = {
  ok: { bar: 'bg-seat-ok', text: 'text-primary-text', Icon: Check },
  low: { bar: 'bg-seat-low', text: 'text-warning', Icon: Clock },
  full: { bar: 'bg-destructive', text: 'text-destructive', Icon: CircleX },
}

/**
 * The seat level of a catalog card (D118): a bar, and the words "Còn chỗ" / "Sắp hết" / "Hết chỗ" with an icon, so the
 * level never rides on colour alone (D115). A level, not a count: the list behind it is up to 30 s old.
 */
export function SeatMeter({ capacity, seatsTaken, className }: { capacity: number; seatsTaken: number; className?: string }) {
  const level = seatLevel(capacity, seatsTaken)
  const { bar, text, Icon } = LEVEL_STYLE[level]
  return (
    <div className={cn('grid gap-1.5', className)} data-level={level}>
      <div aria-hidden="true" className="h-1.5 overflow-hidden rounded-full bg-seat-track">
        <div className={cn('h-full rounded-full', bar)} style={{ width: `${Math.round(seatFill(capacity, seatsTaken) * 100)}%` }} />
      </div>
      <p className={cn('flex items-center gap-1.5 text-sm font-semibold', text)}>
        <Icon aria-hidden="true" className="size-4" />
        {SEAT_LEVEL_LABEL[level]}
      </p>
    </div>
  )
}

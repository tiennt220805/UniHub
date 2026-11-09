import { Clock, Timer, TimerOff } from 'lucide-react'
import { useEffect, useRef } from 'react'

import { useCountdown } from '@/shared/hooks/use-countdown'
import { cn } from '@/shared/lib/utils'
import { clock } from '@/shared/lib/vn-time'
import { Notice } from '@/shared/ui/notice'
import type { Registration } from '../api/requests'

const pad = (n: number) => String(n).padStart(2, '0')

const FIVE_MINUTES = 300
const ONE_MINUTE = 60

type Milestone = 'start' | 'five' | 'one'

const milestoneOf = (left: number): Milestone =>
  left > FIVE_MINUTES ? 'start' : left > ONE_MINUTE ? 'five' : 'one'

// Three looks for one clock (D148): the colour, the icon and the words all change, so no look rides on colour alone (D115).
const LOOK = {
  start: { box: 'bg-accent text-foreground', icon: Clock, warning: null },
  five: { box: 'bg-warning-soft text-warning', icon: Clock, warning: 'Sắp hết giờ giữ chỗ' },
  one: { box: 'bg-destructive-soft text-destructive', icon: Timer, warning: 'Sắp hết giờ giữ chỗ' },
} as const

const announcementFor = (milestone: Milestone, until: string): string =>
  milestone === 'start'
    ? `Chỗ của bạn được giữ đến ${clock(until)}.`
    : milestone === 'five'
      ? 'Còn dưới 5 phút giữ chỗ.'
      : 'Còn dưới 1 phút giữ chỗ.'

/**
 * How long the seat stays held for a RESERVED registration; the server's `holdExpiresAt` decides, this only shows it.
 *
 * The number changes every second and is a `timer`, which a screen reader does not announce. What it hears comes from a
 * separate, visually hidden `status` that changes only at the milestones: when the page opens, with 5 minutes left and with
 * 1 minute left. The end is the visible "Đã hết thời gian giữ chỗ." itself, a `status` that appears at that moment.
 */
export function HoldCountdown({ registration, size = 'md' }: { registration: Registration; size?: 'md' | 'lg' }) {
  const until = registration.status === 'RESERVED' ? (registration.holdExpiresAt ?? null) : null
  const left = useCountdown(until)
  const milestone = left === null || left === 0 ? null : milestoneOf(left)

  // The region is in the page from the first render and its words are written afterwards, so the first words are announced
  // as a change; a live region that arrives with its text already inside is announced unreliably. The words go straight to
  // the node (an effect that writes to the DOM, not to React state) because no React child of it ever changes.
  const announcer = useRef<HTMLParagraphElement>(null)
  useEffect(() => {
    if (announcer.current) {
      announcer.current.textContent = milestone === null || until === null ? '' : announcementFor(milestone, until)
    }
  }, [milestone, until])

  if (left === null) return null
  if (left === 0) {
    return (
      <Notice tone="destructive" icon={TimerOff} role="status">
        Đã hết thời gian giữ chỗ.
      </Notice>
    )
  }

  const look = LOOK[milestoneOf(left)]
  const Icon = look.icon
  return (
    <div>
      <div data-slot="hold-countdown" className={cn('flex items-center gap-3 rounded-xl px-4 py-3', look.box)}>
        <Icon aria-hidden="true" className={size === 'lg' ? 'size-5 shrink-0 lg:size-6' : 'size-5 shrink-0'} />
        <p className="grid gap-0.5 text-sm font-semibold">
          Chỗ của bạn được giữ trong{' '}
          <span
            role="timer"
            className={cn('font-bold tabular-nums tracking-tight', size === 'lg' ? 'text-3xl leading-tight lg:text-5xl lg:leading-none' : 'text-3xl leading-tight')}
          >
            {pad(Math.floor(left / 60))}:{pad(left % 60)}
          </span>
          {look.warning && <span className="text-xs font-semibold">{look.warning}</span>}
        </p>
      </div>
      <p ref={announcer} role="status" className="sr-only" />
    </div>
  )
}

import { useCountdown } from '@/shared/hooks/use-countdown'
import type { Registration } from '../api/requests'

const pad = (n: number) => String(n).padStart(2, '0')

/** How long the seat stays held for a RESERVED registration; the server's `holdExpiresAt` decides. */
export function HoldCountdown({ registration }: { registration: Registration }) {
  const left = useCountdown(registration.status === 'RESERVED' ? registration.holdExpiresAt : null)
  if (left === null) return null
  if (left === 0) {
    return <p role="status">Đã hết thời gian giữ chỗ.</p>
  }
  return (
    <p role="status">
      Chỗ của bạn được giữ trong{' '}
      <span className="font-medium tabular-nums">
        {pad(Math.floor(left / 60))}:{pad(left % 60)}
      </span>
      .
    </p>
  )
}

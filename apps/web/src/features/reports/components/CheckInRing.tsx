import { Info } from 'lucide-react'

import { Notice } from '@/shared/ui/notice'
import type { CheckInRateView } from '../model/check-in-rate'

const RADIUS = 62
const CIRCUMFERENCE = 2 * Math.PI * RADIUS

/**
 * The check-in rate as a ring (D164). The ring is drawn clamped to 0–100% (the server does not clamp, and a check-in outlives the
 * cancellation of its registration), while the words keep the real number. Presentation attributes only, no `<style>` and no
 * `style=""` (the CSP's `style-src`). The role and the name stay what the page has always exposed: a progressbar "Tỷ lệ check-in".
 */
export function CheckInRing({ rate, checkedIn, confirmed }: { rate: CheckInRateView; checkedIn: number; confirmed: number }) {
  if (rate.kind === 'none') {
    return (
      <div className="grid justify-items-center gap-2 text-center">
        <svg aria-hidden="true" width="160" height="160" viewBox="0 0 160 160" className="size-40">
          <circle cx="80" cy="80" r={RADIUS} fill="none" strokeWidth="16" className="stroke-seat-track" />
        </svg>
        <h2 className="text-base leading-6 font-semibold">Tỷ lệ check-in</h2>
        <p className="max-w-[30ch] text-sm text-muted-foreground">Chưa có dữ liệu (chưa có đăng ký nào được xác nhận).</p>
      </div>
    )
  }
  const percent = Math.round(rate.barPercent)
  return (
    <div className="grid justify-items-center gap-2 text-center">
      <div
        role="progressbar"
        aria-label="Tỷ lệ check-in"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={percent}
        className="relative size-40"
      >
        <svg aria-hidden="true" width="160" height="160" viewBox="0 0 160 160" className="size-40">
          <circle cx="80" cy="80" r={RADIUS} fill="none" strokeWidth="16" className="stroke-seat-track" />
          <circle
            cx="80"
            cy="80"
            r={RADIUS}
            fill="none"
            strokeWidth="16"
            className="stroke-seat-ok"
            strokeDasharray={`${((CIRCUMFERENCE * rate.barPercent) / 100).toFixed(1)} ${CIRCUMFERENCE.toFixed(1)}`}
            transform="rotate(-90 80 80)"
          />
        </svg>
        <span className="absolute inset-0 grid place-items-center text-3xl font-bold tabular-nums">{rate.label}</span>
      </div>
      <h2 className="text-base leading-6 font-semibold">Tỷ lệ check-in</h2>
      <p className="text-sm text-muted-foreground tabular-nums">
        {checkedIn} lượt check-in / {confirmed} đăng ký đã xác nhận
      </p>
      {rate.exceedsAll && (
        <Notice tone="muted" icon={Info} className="text-left">
          Số lượt check-in nhiều hơn số đăng ký đã xác nhận: có đăng ký bị hủy sau khi đã check-in. Vòng vẽ đầy ở 100%.
        </Notice>
      )}
    </div>
  )
}

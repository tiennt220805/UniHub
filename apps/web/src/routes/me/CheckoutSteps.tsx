import { Check } from 'lucide-react'

import type { Payment } from '@/features/payments'
import type { Registration } from '@/features/registrations'
import { cn } from '@/shared/lib/utils'

type StepState = 'done' | 'current' | 'todo'
type Step = { title: string; caption: string; state: StepState }

// Three steps, read off what the server last said and never ahead of it (Rules at a Glance #8): the hold exists while the
// registration is RESERVED or later; the payment is done once it SUCCEEDED or the registration is CONFIRMED; the
// confirmation is the last move, a consumer's, and shows as running while a SUCCEEDED payment waits for it.
function stepsOf(registration: Registration, payment: Payment | undefined): Step[] {
  const confirmed = registration.status === 'CONFIRMED'
  const paid = confirmed || payment?.status === 'SUCCEEDED'
  const payCaption =
    paid
      ? 'Đã nhận thanh toán'
      : payment?.status === 'PENDING'
        ? 'Đang xử lý'
        : payment?.status === 'FAILED' || payment?.status === 'CANCELLED'
          ? 'Chưa thành công, bạn có thể thử lại'
          : 'Bạn đang ở bước này'
  return [
    { title: 'Giữ chỗ', caption: 'Đã giữ chỗ cho bạn', state: 'done' },
    { title: 'Thanh toán', caption: payCaption, state: paid ? 'done' : 'current' },
    {
      title: 'Xác nhận',
      caption: confirmed ? 'Vé đã được phát' : paid ? 'Đang xác nhận' : 'Vé được phát sau khi xác nhận',
      state: confirmed ? 'done' : paid ? 'current' : 'todo',
    },
  ]
}

export function CheckoutSteps({ registration, payment }: { registration: Registration; payment: Payment | undefined }) {
  const steps = stepsOf(registration, payment)
  return (
    <ol aria-label="Các bước" className="grid gap-6">
      {steps.map((step, index) => (
        <li key={step.title} aria-current={step.state === 'current' ? 'step' : undefined} className="relative flex gap-3">
          <span
            aria-hidden="true"
            className={cn(
              'grid size-7 shrink-0 place-items-center rounded-full text-xs font-bold',
              step.state === 'done' && 'bg-primary text-primary-foreground',
              step.state === 'current' && 'border-2 border-primary bg-card text-primary-text',
              step.state === 'todo' && 'bg-muted text-muted-foreground',
            )}
          >
            {step.state === 'done' ? <Check className="size-4 stroke-[3]" /> : index + 1}
          </span>
          {index < steps.length - 1 && <span aria-hidden="true" className="absolute top-8 left-[13px] h-5 w-0.5 bg-border" />}
          <div className="min-w-0">
            <div className={cn('text-[15px] leading-7 font-semibold', step.state === 'todo' && 'text-muted-foreground')}>
              {step.title}
            </div>
            <div className="text-xs leading-snug text-muted-foreground">{step.caption}</div>
          </div>
        </li>
      ))}
    </ol>
  )
}

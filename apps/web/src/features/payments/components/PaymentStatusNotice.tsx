import { Ban, CircleCheck, CircleX, Loader2, Undo2, type LucideIcon } from 'lucide-react'

import { Notice } from '@/shared/ui/notice'
import type { Payment } from '../api/requests'

type Tone = 'success' | 'warning' | 'destructive' | 'info'

// The words of each status are the ones Playwright and the page tests assert; the tone and the icon are G3's
// (web-design-system.md §5): a status is never only a colour.
const VIEW: Record<Payment['status'], { text: string; tone: Tone; icon: LucideIcon; spin?: boolean }> = {
  PENDING: { text: 'Đang xử lý thanh toán…', tone: 'info', icon: Loader2, spin: true },
  SUCCEEDED: { text: 'Thanh toán thành công.', tone: 'success', icon: CircleCheck },
  FAILED: {
    text: 'Thanh toán bị từ chối. Bạn có thể thử lại bằng phương thức khác.',
    tone: 'destructive',
    icon: CircleX,
  },
  CANCELLED: { text: 'Thanh toán đã bị hủy. Bạn có thể thử lại.', tone: 'warning', icon: Ban },
  REFUND_REQUIRED: {
    text: 'Thanh toán đã thành công nhưng đăng ký không còn hiệu lực. Khoản tiền sẽ được hoàn lại.',
    tone: 'warning',
    icon: Undo2,
  },
  REFUNDED: { text: 'Khoản thanh toán đã được hoàn lại.', tone: 'info', icon: CircleCheck },
}

/** The state of the payment as the server reports it; never a guess ahead of the server. */
export function PaymentStatusNotice({ payment }: { payment: Payment }) {
  const { text, tone, icon, spin } = VIEW[payment.status]
  return (
    <Notice
      role="status"
      tone={tone}
      icon={icon}
      className={spin ? '[&>svg]:motion-safe:animate-spin' : undefined}
    >
      {text}
    </Notice>
  )
}

import type { Payment } from '../api/requests'

const NOTICE: Record<Payment['status'], string> = {
  PENDING: 'Đang xử lý thanh toán…',
  SUCCEEDED: 'Thanh toán thành công.',
  FAILED: 'Thanh toán bị từ chối. Bạn có thể thử lại bằng phương thức khác.',
  CANCELLED: 'Thanh toán đã bị hủy. Bạn có thể thử lại.',
  REFUND_REQUIRED:
    'Thanh toán đã thành công nhưng đăng ký không còn hiệu lực. Khoản tiền sẽ được hoàn lại.',
  REFUNDED: 'Khoản thanh toán đã được hoàn lại.',
}

/** The state of the payment as the server reports it; never a guess ahead of the server. */
export function PaymentStatusNotice({ payment }: { payment: Payment }) {
  return (
    <p role="status" className="rounded-md bg-muted p-3 text-foreground">
      {NOTICE[payment.status]}
    </p>
  )
}

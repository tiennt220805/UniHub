import type { AppError } from '@/shared/api/errors'
import { problemSlug } from '@/shared/api/errors'

/**
 * What to tell the user when `chargePayment` fails (web-coding-standards.md §6). Branches on the
 * problem `type` slug and falls back to the HTTP status for a slug it does not know.
 */
export function chargeErrorMessage(error: AppError): string {
  switch (problemSlug(error)) {
    case 'registration-not-reserved':
      return 'Đăng ký này không còn được giữ chỗ (có thể đã hết hạn hoặc đã thanh toán).'
    case 'payment-in-progress':
      return 'Đang có một thanh toán cho đăng ký này. Vui lòng chờ kết quả.'
    case 'idempotency-key-in-use':
      return 'Yêu cầu thanh toán trước đó của bạn vẫn đang được xử lý. Vui lòng thử lại sau giây lát.'
    case 'idempotency-key-reused':
      return 'Yêu cầu không hợp lệ. Vui lòng tải lại trang rồi thử lại.'
  }
  switch (error.status) {
    case null:
      return 'Mất kết nối. Vui lòng kiểm tra mạng rồi thử lại; hệ thống sẽ không trừ tiền hai lần.'
    case 403:
      return 'Bạn không phải là người đăng ký này.'
    case 404:
      return 'Không tìm thấy đăng ký.'
    case 409:
      return 'Không thể thanh toán ở trạng thái hiện tại của đăng ký.'
    case 429:
      return error.retryAfterSeconds === null
        ? 'Bạn thao tác quá nhanh. Vui lòng thử lại sau.'
        : `Bạn thao tác quá nhanh. Vui lòng thử lại sau ${error.retryAfterSeconds} giây.`
    case 503:
      // payment.md §3: nothing was charged and the hold is untouched.
      return 'Cổng thanh toán tạm thời không khả dụng. Chỗ của bạn vẫn được giữ trong 15 phút, vui lòng thử lại sau ít phút.'
    default:
      return 'Không thanh toán được. Vui lòng thử lại.'
  }
}

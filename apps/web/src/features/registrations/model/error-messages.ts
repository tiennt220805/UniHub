import type { AppError } from '@/shared/api/errors'
import { problemSlug } from '@/shared/api/errors'

/**
 * What to tell the user when `createRegistration` fails (web-coding-standards.md §6). Branches on
 * the problem `type` slug (backend-coding-standards.md §17); a slug this client does not know falls
 * back to the HTTP status, so the server can add a type without breaking a deployed client.
 */
export function registrationErrorMessage(error: AppError): string {
  switch (problemSlug(error)) {
    case 'sold-out':
      return 'Workshop đã hết chỗ.'
    case 'already-registered':
      return 'Bạn đã đăng ký workshop này rồi.'
    case 'registration-not-open':
      return 'Workshop này hiện không mở đăng ký.'
    case 'idempotency-key-in-use':
      return 'Yêu cầu trước đó của bạn vẫn đang được xử lý. Vui lòng thử lại sau giây lát.'
    case 'idempotency-key-reused':
      return 'Yêu cầu không hợp lệ. Vui lòng tải lại trang rồi thử lại.'
  }
  switch (error.status) {
    case null:
      return 'Mất kết nối. Vui lòng kiểm tra mạng rồi thử lại.'
    case 403:
      return 'Tài khoản của bạn không có quyền đăng ký workshop.'
    case 404:
      return 'Không tìm thấy workshop.'
    case 409:
      return 'Không thể đăng ký workshop ở trạng thái hiện tại.'
    case 429:
      return error.retryAfterSeconds === null
        ? 'Bạn thao tác quá nhanh. Vui lòng thử lại sau.'
        : `Bạn thao tác quá nhanh. Vui lòng thử lại sau ${error.retryAfterSeconds} giây.`
    case 503:
      return 'Hệ thống đang bận. Vui lòng thử lại sau ít phút.'
    default:
      return 'Không đăng ký được. Vui lòng thử lại.'
  }
}

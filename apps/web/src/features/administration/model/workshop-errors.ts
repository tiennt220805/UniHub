import type { AppError } from '@/shared/api/errors'

// What the user is told for each answer of a write (web-coding-standards.md §6). The words of the 409s are the G4 mockup's, with
// one difference: the backend answers a 409 on an edit for four reasons (another edit won, the workshop is cancelled or
// completed, the capacity is below the seats taken, the price does not fit the workshop) and gives no problem type that tells
// them apart, so one message names them all and offers "Tải lại".

const tooMany = (error: AppError) =>
  error.retryAfterSeconds === null
    ? 'Bạn đang gửi quá nhiều yêu cầu. Vui lòng thử lại sau.'
    : `Bạn đang gửi quá nhiều yêu cầu. Vui lòng thử lại sau ${error.retryAfterSeconds} giây.`

export type SaveErrorView = { message: string; offersReload: boolean }

export function saveErrorView(error: AppError, action: 'create' | 'update'): SaveErrorView {
  switch (error.status) {
    case 409:
      return {
        message:
          'Không lưu được. Workshop có thể vừa được thay đổi ở nơi khác, đã bị hủy, hoặc giá trị mới không khớp với dữ liệu hiện có (ví dụ sức chứa thấp hơn số chỗ đã giữ). Tải lại để xem bản mới nhất; những gì bạn vừa nhập sẽ mất.',
        offersReload: true,
      }
    case 400:
      return { message: 'Dữ liệu chưa hợp lệ. Kiểm tra lại các trường rồi thử lại.', offersReload: false }
    case 403:
      return { message: action === 'create' ? 'Bạn không có quyền tạo workshop.' : 'Bạn không có quyền sửa workshop này.', offersReload: false }
    case 404:
      return { message: 'Không tìm thấy workshop này.', offersReload: false }
    case 429:
      return { message: tooMany(error), offersReload: false }
    default:
      return {
        message: error.status === null ? 'Mất kết nối. Kiểm tra mạng rồi thử lại.' : 'Không lưu được. Vui lòng thử lại.',
        offersReload: false,
      }
  }
}

export function publishErrorMessage(error: AppError): string {
  switch (error.status) {
    case 409:
      return 'Workshop này không còn là bản nháp, nên không công bố được. Danh sách đã được tải lại.'
    case 403:
      return 'Bạn không có quyền công bố workshop.'
    case 404:
      return 'Không tìm thấy workshop này.'
    case 429:
      return tooMany(error)
    default:
      return error.status === null ? 'Mất kết nối. Kiểm tra mạng rồi thử lại.' : 'Không công bố được. Vui lòng thử lại.'
  }
}

export function cancelErrorMessage(error: AppError): string {
  switch (error.status) {
    case 409:
      return 'Workshop này đã bị hủy hoặc đã kết thúc rồi.'
    case 403:
      return 'Bạn không có quyền hủy workshop.'
    case 404:
      return 'Không tìm thấy workshop này.'
    case 429:
      return tooMany(error)
    default:
      return error.status === null ? 'Mất kết nối. Kiểm tra mạng rồi thử lại.' : 'Không hủy được. Vui lòng thử lại.'
  }
}

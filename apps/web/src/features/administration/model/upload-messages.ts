import type { UploadFailure } from '../hooks/use-asset-upload'

// What each way an upload can fail says, and what the user can do about it (G4 boards G and H, D162, D163). The words of the cover
// and the brochure differ only by the noun and by what "not acceptable" means.

export type FailureView = {
  tone: 'destructive' | 'warning'
  title: string
  body?: string
  /** `retry`: ask again for the same file; `choose`: pick another file; none: nothing the user can do here. */
  action: 'retry' | 'choose' | null
  retryLabel?: string
}

export type Asset = 'cover' | 'brochure'

const NOUN: Record<Asset, string> = { cover: 'ảnh', brochure: 'brochure' }
const NOUN_CAP: Record<Asset, string> = { cover: 'Ảnh', brochure: 'Brochure' }

export function describeFailure(failure: UploadFailure, asset: Asset): FailureView {
  const noun = NOUN[asset]
  switch (failure.kind) {
    case 'client':
      return { tone: 'destructive', title: failure.message, action: null }
    case 'rejected':
      return {
        tone: 'destructive',
        title: 'Máy chủ không nhận file này.',
        body: `${asset === 'cover' ? 'Định dạng không đúng hoặc ảnh bị hỏng.' : 'File không đọc được.'} File đã bị xóa khỏi kho lưu trữ; chọn ${noun} khác.`,
        action: 'choose',
      }
    case 'storage-unavailable':
      return {
        tone: 'warning',
        title: `Chưa xác nhận được ${noun}.`,
        body: 'Kho lưu trữ tạm thời không dùng được. File đã tải lên; thử lại sau ít phút, không cần chọn lại.',
        action: 'retry',
        retryLabel: 'Thử lại',
      }
    case 'not-stored':
      return {
        tone: 'warning',
        title: `Tải ${noun} chưa xong.`,
        body: 'Liên kết tải lên chỉ dùng được 10 phút, hoặc kết nối bị ngắt. Bấm Tải lên để lấy liên kết mới.',
        action: 'retry',
        retryLabel: 'Tải lên lại',
      }
    case 'conflict':
      return failure.slug?.endsWith('-upload-not-issued')
        ? {
            tone: 'destructive',
            title: 'Lần tải trước đã được xác nhận hoặc đã bị loại.',
            body: `Chọn lại file để tải ${noun} lên.`,
            action: 'choose',
          }
        : { tone: 'destructive', title: `Workshop này đã bị hủy hoặc đã kết thúc, nên không đổi ${noun} được.`, action: null }
    case 'forbidden':
      return { tone: 'destructive', title: `Bạn không có quyền tải ${noun} lên.`, action: null }
    case 'busy':
      return {
        tone: 'warning',
        title:
          failure.retryAfterSeconds === null
            ? 'Bạn đang gửi quá nhiều yêu cầu. Vui lòng thử lại sau.'
            : `Bạn đang gửi quá nhiều yêu cầu. Vui lòng thử lại sau ${failure.retryAfterSeconds} giây.`,
        action: null,
      }
    case 'other':
      return { tone: 'destructive', title: `Không tải ${noun} lên được. Vui lòng thử lại.`, action: 'retry', retryLabel: 'Thử lại' }
  }
}

export { NOUN_CAP }

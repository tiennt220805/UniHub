import type { RejectionReason } from '@/shared/ticket'

export const REJECTION_TEXT: Record<RejectionReason, string> = {
  NO_KEY: 'Chưa có khóa xác minh.',
  MALFORMED: 'Đây không phải mã vé của UniHub.',
  ALGORITHM: 'Mã vé dùng thuật toán không hợp lệ.',
  SIGNATURE: 'Chữ ký không đúng: vé giả, bị sửa, hoặc đã bị thay bằng khóa mới.',
  CLAIMS: 'Nội dung vé không hợp lệ.',
}

export const clockTime = (iso: string) => {
  const date = new Date(iso)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`
}

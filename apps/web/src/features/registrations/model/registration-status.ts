import type { RegistrationStatus } from '../api/requests'

/** Statuses a registration never leaves (backend state machine: RESERVED is the only live one). */
export const isRegistrationFinal = (status: RegistrationStatus): boolean => status !== 'RESERVED'

export const REGISTRATION_STATUS_LABEL: Record<RegistrationStatus, string> = {
  RESERVED: 'Đang giữ chỗ',
  CONFIRMED: 'Đã xác nhận',
  CANCELLED: 'Đã hủy',
  EXPIRED: 'Hết hạn giữ chỗ',
}

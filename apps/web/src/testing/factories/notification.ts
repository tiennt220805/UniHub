import type { components } from '@/shared/api/schema.gen'

type Notification = components['schemas']['Notification']
type NotificationListResponse = components['schemas']['NotificationListResponse']

export function aNotification(overrides: Partial<Notification> = {}): Notification {
  return {
    id: 'n0000000-0000-4000-8000-000000000001',
    type: 'REGISTRATION_CONFIRMED',
    title: 'Đăng ký thành công: Kỹ năng thuyết trình',
    body: 'Đăng ký của bạn cho workshop "Kỹ năng thuyết trình" đã được xác nhận.',
    relatedEntityType: 'REGISTRATION',
    relatedEntityId: '9b8a7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d',
    status: 'SENT',
    sentAt: '2026-10-26T01:00:05Z',
    createdAt: '2026-10-26T01:00:00Z',
    ...overrides,
  }
}

export function aNotificationPage(items: Notification[]): NotificationListResponse {
  return {
    items,
    page: { page: 0, size: 20, totalElements: items.length, totalPages: items.length === 0 ? 0 : 1 },
  }
}

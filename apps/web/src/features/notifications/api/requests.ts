import { request } from '@/shared/api/http-client'
import type { components, operations } from '@/shared/api/schema.gen'
import { toQueryString } from '@/shared/lib/query-string'

export type Notification = components['schemas']['Notification']
export type NotificationListResponse = components['schemas']['NotificationListResponse']
export type NotificationFilters = NonNullable<operations['listMyNotifications']['parameters']['query']>

export const listMyNotifications = (filters: NotificationFilters) =>
  request<NotificationListResponse>(`/api/v1/notifications${toQueryString(filters)}`)

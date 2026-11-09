import { request } from '@/shared/api/http-client'
import type { components, operations } from '@/shared/api/schema.gen'
import { toQueryString } from '@/shared/lib/query-string'

export type ManagedWorkshop = components['schemas']['WorkshopSummary']
export type ManagedWorkshopList = components['schemas']['WorkshopListResponse']
export type ManagedWorkshopFilters = NonNullable<operations['listManagedWorkshops']['parameters']['query']>
export type WorkshopStatus = components['schemas']['WorkshopStatus']

// The Organizer's and Admin's view (D119): every status, read from the Master, never cached. A separate operation from
// the public listWorkshops, whose cache key and payload stay what the load model of Phase 9 counts on.
export const listManagedWorkshops = (filters: ManagedWorkshopFilters) =>
  request<ManagedWorkshopList>(`/api/v1/admin/workshops${toQueryString(filters)}`)

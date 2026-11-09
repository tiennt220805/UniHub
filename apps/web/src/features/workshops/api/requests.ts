import { request } from '@/shared/api/http-client'
import type { components, operations } from '@/shared/api/schema.gen'
import { toQueryString } from '@/shared/lib/query-string'

export type Workshop = components['schemas']['Workshop']
export type WorkshopSummary = components['schemas']['WorkshopSummary']
export type WorkshopListResponse = components['schemas']['WorkshopListResponse']
export type WorkshopFilters = NonNullable<operations['listWorkshops']['parameters']['query']>

export const listWorkshops = (filters: WorkshopFilters) =>
  request<WorkshopListResponse>(`/api/v1/workshops${toQueryString(filters)}`)

export const getWorkshop = (id: string) =>
  request<Workshop>(`/api/v1/workshops/${encodeURIComponent(id)}`)

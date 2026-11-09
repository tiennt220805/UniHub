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

export type Workshop = components['schemas']['Workshop']
export type CreateWorkshopRequest = components['schemas']['CreateWorkshopRequest']
export type UpdateWorkshopRequest = components['schemas']['UpdateWorkshopRequest']

const workshopPath = (id: string) => `/api/v1/workshops/${encodeURIComponent(id)}`
const json = (body: unknown): RequestInit => ({ headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })

export const createWorkshop = (body: CreateWorkshopRequest) => request<Workshop>('/api/v1/workshops', { method: 'POST', ...json(body) })

// PATCH with only the fields that changed (D155): the contract has no `version`, so a stale form can overwrite another
// editor's change to a field it sends, and to none it does not.
export const updateWorkshop = (id: string, body: UpdateWorkshopRequest) =>
  request<Workshop>(workshopPath(id), { method: 'PATCH', ...json(body) })

export const publishWorkshop = (id: string) => request<Workshop>(`${workshopPath(id)}/publish`, { method: 'POST' })

export const cancelWorkshop = (id: string) => request<Workshop>(`${workshopPath(id)}/cancel`, { method: 'POST' })

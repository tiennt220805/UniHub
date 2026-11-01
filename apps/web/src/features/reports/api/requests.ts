import { request } from '@/shared/api/http-client'
import type { components } from '@/shared/api/schema.gen'

export type WorkshopReport = components['schemas']['WorkshopReport']

export const getWorkshopReport = (workshopId: string) =>
  request<WorkshopReport>(`/api/v1/workshops/${encodeURIComponent(workshopId)}/report`)

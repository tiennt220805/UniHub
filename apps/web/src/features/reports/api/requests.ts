import { request } from '@/shared/api/http-client'
import type { components } from '@/shared/api/schema.gen'

export type WorkshopReport = components['schemas']['WorkshopReport']

export const getWorkshopReport = (workshopId: string) =>
  request<WorkshopReport>(`/api/v1/workshops/${encodeURIComponent(workshopId)}/report`)

export type AttendanceList = components['schemas']['AttendanceListResponse']
export type Attendance = components['schemas']['AttendanceSummary']

// One row per registration with a winning scan: the registration's id and the time, no student name (D165). Oldest first, so
// a page boundary is stable. A page past the last is 200 with no items.
export const listWorkshopAttendances = (workshopId: string, page: number) =>
  request<AttendanceList>(`/api/v1/workshops/${encodeURIComponent(workshopId)}/attendances${page > 0 ? `?page=${page}` : ''}`)

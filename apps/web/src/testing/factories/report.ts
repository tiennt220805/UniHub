import type { components } from '@/shared/api/schema.gen'
import { WORKSHOP_ID } from './workshop'

type WorkshopReport = components['schemas']['WorkshopReport']

/** 3 CONFIRMED, 1 RESERVED, 2 checked in: the numbers of the Backend's own report test. */
export function aWorkshopReport(overrides: Partial<WorkshopReport> = {}): WorkshopReport {
  return {
    workshopId: WORKSHOP_ID,
    capacity: 40,
    seatsTaken: 4,
    confirmedCount: 3,
    reservedCount: 1,
    checkedInCount: 2,
    checkInRate: 2 / 3,
    ...overrides,
  }
}

type AttendanceList = components['schemas']['AttendanceListResponse']
type Attendance = components['schemas']['AttendanceSummary']

export function anAttendance(overrides: Partial<Attendance> = {}): Attendance {
  return { registrationId: '3f9a21c4-0000-4000-8000-000000000001', checkedInAt: '2026-11-15T06:32:07Z', ...overrides }
}

export function aAttendancePage(items: Attendance[], page: Partial<AttendanceList['page']> = {}): AttendanceList {
  return {
    items,
    page: { page: 0, size: 20, totalElements: items.length, totalPages: items.length === 0 ? 0 : 1, ...page },
  }
}

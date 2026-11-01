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

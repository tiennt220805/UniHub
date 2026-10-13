import type { components } from '@/shared/api/schema.gen'

type Workshop = components['schemas']['Workshop']
type WorkshopSummary = components['schemas']['WorkshopSummary']
type WorkshopListResponse = components['schemas']['WorkshopListResponse']

export const WORKSHOP_ID = '3f2b8c1e-6d4a-4a57-9b0e-1c2d3e4f5a6b'

export function aWorkshop(overrides: Partial<Workshop> = {}): Workshop {
  return {
    id: WORKSHOP_ID,
    title: 'Intro to Distributed Locks',
    description: 'Redis locks and why they are only advisory.',
    aiSummary: null,
    speakerName: 'Dr. Lan Nguyen',
    speakerBio: 'Distributed systems lecturer.',
    location: 'Hall A',
    startTime: '2026-11-01T02:00:00Z',
    endTime: '2026-11-01T04:00:00Z',
    capacity: 50,
    seatsTaken: 15,
    isPaid: false,
    price: null,
    hasBrochure: false,
    status: 'PUBLISHED',
    version: 3,
    ...overrides,
  }
}

export function aWorkshopSummary(overrides: Partial<WorkshopSummary> = {}): WorkshopSummary {
  return {
    id: WORKSHOP_ID,
    title: 'Intro to Distributed Locks',
    location: 'Hall A',
    startTime: '2026-11-01T02:00:00Z',
    status: 'PUBLISHED',
    ...overrides,
  }
}

export function aWorkshopPage(items: WorkshopSummary[]): WorkshopListResponse {
  return {
    items,
    page: { page: 0, size: 20, totalElements: items.length, totalPages: items.length === 0 ? 0 : 1 },
  }
}

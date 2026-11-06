import { randomUUID } from 'node:crypto'
import { expect, type APIRequestContext } from '@playwright/test'

import type { Account } from './accounts'

// The Organizer's operations that the Web has no page for (create, publish, edit, reschedule: the Web builds only
// the report page, Phase 7 checklist Q-M70), called through the same gateway over HTTPS as the browser.
export type Workshop = { id: string; title: string; status: string; startTime: string; endTime: string }

export class OrganizerApi {
  private token = ''

  constructor(private readonly request: APIRequestContext) {}

  async signIn(account: Account): Promise<this> {
    const response = await this.request.post('/api/v1/auth/login', {
      data: { email: account.email, password: account.password },
    })
    expect(response.status(), 'sign-in of the Organizer').toBe(200)
    this.token = ((await response.json()) as { accessToken: string }).accessToken
    return this
  }

  private headers(withKey = true): Record<string, string> {
    return withKey
      ? { Authorization: `Bearer ${this.token}`, 'Idempotency-Key': randomUUID() }
      : { Authorization: `Bearer ${this.token}` }
  }

  async create(input: { title: string; paid: boolean; capacity?: number; daysAhead?: number }): Promise<Workshop> {
    const start = new Date(Date.now() + (input.daysAhead ?? 2) * 86_400_000)
    const end = new Date(start.getTime() + 2 * 3_600_000)
    const response = await this.request.post('/api/v1/workshops', {
      headers: this.headers(),
      data: {
        title: input.title,
        speakerName: 'E2E Speaker',
        location: 'Room E2E',
        startTime: start.toISOString(),
        endTime: end.toISOString(),
        capacity: input.capacity ?? 5,
        isPaid: input.paid,
        ...(input.paid ? { price: 150000 } : {}),
      },
    })
    expect(response.status(), 'createWorkshop').toBe(201)
    return (await response.json()) as Workshop
  }

  async publish(id: string): Promise<void> {
    const response = await this.request.post(`/api/v1/workshops/${id}/publish`, { headers: this.headers() })
    expect(response.status(), 'publishWorkshop').toBe(200)
  }

  async update(id: string, changes: Record<string, unknown>): Promise<Workshop> {
    const response = await this.request.patch(`/api/v1/workshops/${id}`, {
      headers: this.headers(),
      data: changes,
    })
    expect(response.status(), 'updateWorkshop').toBe(200)
    return (await response.json()) as Workshop
  }

  async get(id: string): Promise<Workshop> {
    const response = await this.request.get(`/api/v1/workshops/${id}`, { headers: this.headers(false) })
    expect(response.status(), 'getWorkshop').toBe(200)
    return (await response.json()) as Workshop
  }
}

export type Registration = { id: string; status: string; ticketType: string }

// A Student acting through the API where the journey is about something else than the Student's own screens
// (the Organizer's report counts one registration).
export class StudentApi {
  private token = ''

  constructor(private readonly request: APIRequestContext) {}

  async signIn(account: Account): Promise<this> {
    const response = await this.request.post('/api/v1/auth/login', {
      data: { email: account.email, password: account.password },
    })
    expect(response.status(), 'sign-in of the Student').toBe(200)
    this.token = ((await response.json()) as { accessToken: string }).accessToken
    return this
  }

  async register(workshopId: string): Promise<Registration> {
    const response = await this.request.post('/api/v1/registrations', {
      headers: { Authorization: `Bearer ${this.token}`, 'Idempotency-Key': randomUUID() },
      data: { workshopId },
    })
    expect(response.status(), 'createRegistration').toBe(201)
    return (await response.json()) as Registration
  }
}

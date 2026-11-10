import { randomUUID } from 'node:crypto'
import { expect, type APIRequestContext } from '@playwright/test'

import type { Account } from './accounts'

// The Organizer's operations called through the API where a journey is about something else than the administration
// screens: setting up the workshops a Student's or a catalog test needs, and cleaning them up. The Web has the screens for
// create, publish, edit, reschedule and the uploads since Phase 8; `organizer-journey.spec.ts` drives those in the browser.
export type Workshop = { id: string; title: string; status: string; startTime: string; endTime: string }

export class OrganizerApi {
  private token = ''
  private readonly created: string[] = []

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

  // The rate limit (INV-17) is per identity: a burst of calls (the sweep before a run) can use up the Organizer's
  // budget, and the next call is then answered 429. Wait as the response says, then call again.
  private async send(method: 'post' | 'patch', url: string, data?: unknown) {
    for (let attempt = 0; ; attempt++) {
      const response = await this.request[method](url, { headers: this.headers(), ...(data === undefined ? {} : { data }) })
      if (response.status() !== 429 || attempt >= 3) return response
      const wait = Number(response.headers()['retry-after'] ?? '5')
      await new Promise((resolve) => setTimeout(resolve, (Number.isFinite(wait) ? wait : 5) * 1000 + 250))
    }
  }

  /** A workshop made in the browser: the clean-up after the test takes it out of the catalog too. */
  track(id: string): void {
    this.created.push(id)
  }

  async create(input: {
    title: string
    paid: boolean
    capacity?: number
    daysAhead?: number
    speakerName?: string
    location?: string
  }): Promise<Workshop> {
    const start = new Date(Date.now() + (input.daysAhead ?? 2) * 86_400_000)
    const end = new Date(start.getTime() + 2 * 3_600_000)
    const response = await this.send('post', '/api/v1/workshops', {
      title: input.title,
      speakerName: input.speakerName ?? 'E2E Speaker',
      location: input.location ?? 'Room E2E',
      startTime: start.toISOString(),
      endTime: end.toISOString(),
      capacity: input.capacity ?? 5,
      isPaid: input.paid,
      ...(input.paid ? { price: 150000 } : {}),
    })
    expect(response.status(), 'createWorkshop').toBe(201)
    const workshop = (await response.json()) as Workshop
    this.created.push(workshop.id)
    return workshop
  }

  async publish(id: string): Promise<void> {
    const response = await this.send('post', `/api/v1/workshops/${id}/publish`)
    expect(response.status(), 'publishWorkshop').toBe(200)
  }

  async update(id: string, changes: Record<string, unknown>): Promise<Workshop> {
    const response = await this.send('patch', `/api/v1/workshops/${id}`, changes)
    expect(response.status(), 'updateWorkshop').toBe(200)
    return (await response.json()) as Workshop
  }

  /** Takes the workshop out of the catalog. A workshop already cancelled (or never published) is not an error. */
  async cancel(id: string): Promise<void> {
    const response = await this.send('post', `/api/v1/workshops/${id}/cancel`)
    expect([200, 409], `cancelWorkshop ${id} answered ${response.status()}`).toContain(response.status())
  }

  async cancelAllCreated(): Promise<void> {
    for (const id of this.created.splice(0)) await this.cancel(id).catch(() => undefined)
  }

  /** The published workshops, every page of the public catalog. */
  async catalog(): Promise<Workshop[]> {
    const found: Workshop[] = []
    for (let page = 0; ; page++) {
      const response = await this.request.get(`/api/v1/workshops?page=${page}&size=100`)
      expect(response.status(), 'listWorkshops').toBe(200)
      const body = (await response.json()) as { items: Workshop[]; page: { totalPages: number } }
      found.push(...body.items)
      if (page + 1 >= body.page.totalPages) return found
    }
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

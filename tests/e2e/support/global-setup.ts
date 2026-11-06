import { request } from '@playwright/test'

import { organizer } from './accounts'
import { OrganizerApi } from './api'

// The Web's catalog page shows only the first page of 20 published workshops (WorkshopCatalogPage calls
// `useWorkshops({})`: no search, no paging). A journey that starts at the catalog can find its workshop only if
// fewer than 20 are published. So: take down what earlier runs of this suite left behind, then refuse to start
// if the catalog is still too full, instead of letting every journey time out for no visible reason.
const FIRST_PAGE = 20

export default async function globalSetup(): Promise<void> {
  const baseURL = process.env.E2E_BASE_URL ?? 'https://unihub.local:8443'
  const context = await request.newContext({ baseURL })
  try {
    const api = await new OrganizerApi(context).signIn(organizer())
    // Not a second read of the list: it is cached for 30 s and a cancellation does not evict it.
    const published = await api.catalog()
    const mine = published.filter((workshop) => workshop.title.startsWith('E2E '))
    for (const workshop of mine) await api.cancel(workshop.id)
    const left = published.length - mine.length
    if (left > FIRST_PAGE - 4) {
      throw new Error(
        `${left} workshops are published and the Web's catalog shows only the first ${FIRST_PAGE}: the journeys could not ` +
          'find the workshops they create. Cancel the others, or give the catalog page a search or paging first.',
      )
    }
  } finally {
    await context.dispose()
  }
}

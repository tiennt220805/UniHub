import { request } from '@playwright/test'

import { organizer } from './accounts'
import { OrganizerApi } from './api'

// Takes down what earlier runs of this suite left behind (a run that was interrupted never reached its clean-up): the
// published workshops whose title starts with "E2E ". The journeys no longer depend on the size of the catalog, since a
// Student finds a workshop by searching for its unique title (M-82), so there is nothing to refuse to start over.
export default async function globalSetup(): Promise<void> {
  const baseURL = process.env.E2E_BASE_URL ?? 'https://unihub.local:8443'
  const context = await request.newContext({ baseURL })
  try {
    const api = await new OrganizerApi(context).signIn(organizer())
    // Not a second read of the list: it is cached for 30 s and a cancellation does not evict it.
    const published = await api.catalog()
    for (const workshop of published.filter((entry) => entry.title.startsWith('E2E '))) await api.cancel(workshop.id)
  } finally {
    await context.dispose()
  }
}

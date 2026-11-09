import { endOfDay, isYmd, startOfDay } from '@/shared/lib/vn-time'
import type { WorkshopFilters } from '../api/requests'

// The catalog's filters live in the URL, so a link can be shared (checklist 4.3, D145). Every field is a string, `''` for
// "not set", and `page` is zero-based like the API's. Nothing here reads the clock: the caller passes "today" in.

export type CatalogSearch = {
  q: string
  speaker: string
  location: string
  /** `YYYY-MM-DD`. Absent means "from today" (D138), not "from the beginning". */
  dateFrom: string
  /** `YYYY-MM-DD`. Absent means no upper bound. */
  dateTo: string
  page: number
}

export const EMPTY_SEARCH: CatalogSearch = { q: '', speaker: '', location: '', dateFrom: '', dateTo: '', page: 0 }

const text = (params: URLSearchParams, key: string) => (params.get(key) ?? '').trim()
const day = (params: URLSearchParams, key: string) => {
  const value = text(params, key)
  return isYmd(value) ? value : '' // a hand-edited bad date is the same as none
}

export function parseCatalogSearch(params: URLSearchParams): CatalogSearch {
  const page = Number(params.get('page'))
  return {
    q: text(params, 'q'),
    speaker: text(params, 'speaker'),
    location: text(params, 'location'),
    dateFrom: day(params, 'dateFrom'),
    dateTo: day(params, 'dateTo'),
    page: Number.isInteger(page) && page > 0 ? page : 0,
  }
}

/** The URL's query for `search`: only what is set, and no `page` for the first page. */
export function toCatalogParams(search: CatalogSearch): URLSearchParams {
  const params = new URLSearchParams()
  if (search.q) params.set('q', search.q)
  if (search.speaker) params.set('speaker', search.speaker)
  if (search.location) params.set('location', search.location)
  if (search.dateFrom) params.set('dateFrom', search.dateFrom)
  if (search.dateTo) params.set('dateTo', search.dateTo)
  if (search.page > 0) params.set('page', String(search.page))
  return params
}

/**
 * The `listWorkshops` query for `search`. With no `dateFrom` the list starts at the beginning of `today` in Vietnam
 * (D138): the backend lists every PUBLISHED workshop, past ones included, and orders them oldest first. The instants
 * are the start and the end of a day, never "now", so the request is identical all day and keeps hitting the
 * backend's 30-second list cache. The first page sends no `page`, for the same reason.
 */
export function toApiFilters(search: CatalogSearch, today: string): WorkshopFilters {
  return {
    q: search.q || undefined,
    speaker: search.speaker || undefined,
    location: search.location || undefined,
    dateFrom: startOfDay(search.dateFrom || today),
    dateTo: search.dateTo ? endOfDay(search.dateTo) : undefined,
    page: search.page > 0 ? search.page : undefined,
  }
}

/** Whether the range is backwards: the one thing the server answers 400 for, caught before the request. */
export function hasBackwardsRange(search: CatalogSearch, today: string): boolean {
  return search.dateTo !== '' && (search.dateFrom || today) > search.dateTo
}

/** How many filters the user has set (the default start date is not one of them). */
export function activeFilterCount(search: CatalogSearch): number {
  return [search.q, search.speaker, search.location, search.dateFrom, search.dateTo].filter(Boolean).length
}

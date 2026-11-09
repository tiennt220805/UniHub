import type { ManagedWorkshopFilters, WorkshopStatus } from '../api/requests'

// The management list's filters live in the URL (D159), like the catalog's: a link to "the drafts" can be shared. `page` is
// zero-based like the API's. There is no tab for COMPLETED (D158): nothing in the backend ever sets that status.

export type ManagedTab = 'ALL' | Exclude<WorkshopStatus, 'COMPLETED'>

export const MANAGED_TABS: readonly { value: ManagedTab; label: string }[] = [
  { value: 'ALL', label: 'Tất cả' },
  { value: 'DRAFT', label: 'Nháp' },
  { value: 'PUBLISHED', label: 'Đã công bố' },
  { value: 'CANCELLED', label: 'Đã hủy' },
]

export type ManagedSearch = { tab: ManagedTab; q: string; page: number }

export const EMPTY_MANAGED_SEARCH: ManagedSearch = { tab: 'ALL', q: '', page: 0 }

const isTab = (value: string | null): value is ManagedTab => MANAGED_TABS.some((tab) => tab.value === value)

export function parseManagedSearch(params: URLSearchParams): ManagedSearch {
  const status = params.get('status')
  const page = Number(params.get('page'))
  return {
    // A hand-typed status the list has no tab for (COMPLETED, nonsense) is the same as none.
    tab: isTab(status) ? status : 'ALL',
    q: (params.get('q') ?? '').trim(),
    page: Number.isInteger(page) && page > 0 ? page : 0,
  }
}

/** The URL's query for `search`: only what is set, no `status` for "all" and no `page` for the first page. */
export function toManagedParams(search: ManagedSearch): URLSearchParams {
  const params = new URLSearchParams()
  if (search.tab !== 'ALL') params.set('status', search.tab)
  if (search.q) params.set('q', search.q)
  if (search.page > 0) params.set('page', String(search.page))
  return params
}

export function toManagedFilters(search: ManagedSearch): ManagedWorkshopFilters {
  return {
    status: search.tab === 'ALL' ? undefined : search.tab,
    q: search.q || undefined,
    page: search.page > 0 ? search.page : undefined,
  }
}

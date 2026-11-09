import { useCallback, useMemo } from 'react'
import { useSearchParams } from 'react-router'

import { parseManagedSearch, toManagedParams, type ManagedSearch } from '../model/managed-search'

/**
 * The management list's tab, search text and page, read from and written to the URL (D159). Any change but a page change
 * returns to the first page. `replace` keeps typing out of the history; a tab or a page pushes an entry.
 */
export function useManagedSearch() {
  const [params, setParams] = useSearchParams()
  const search = useMemo(() => parseManagedSearch(params), [params])

  const update = useCallback(
    (patch: Partial<ManagedSearch>, options: { replace?: boolean } = {}) => {
      setParams(toManagedParams({ ...search, ...patch, page: patch.page ?? 0 }), { replace: options.replace })
    },
    [search, setParams],
  )
  return { search, update }
}

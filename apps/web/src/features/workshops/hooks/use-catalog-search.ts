import { useCallback, useMemo } from 'react'
import { useSearchParams } from 'react-router'

import { parseCatalogSearch, toCatalogParams, type CatalogSearch } from '../model/catalog-filters'

/**
 * The catalog's filters, read from and written to the URL (D145). Any change but a page change returns to the first
 * page. `replace` keeps typing out of the history; a date or a page pushes an entry, so Back goes to the previous view.
 */
export function useCatalogSearch() {
  const [params, setParams] = useSearchParams()
  const search = useMemo(() => parseCatalogSearch(params), [params])

  const update = useCallback(
    (patch: Partial<CatalogSearch>, options: { replace?: boolean } = {}) => {
      setParams(toCatalogParams({ ...search, ...patch, page: patch.page ?? 0 }), { replace: options.replace })
    },
    [search, setParams],
  )
  const clear = useCallback(() => setParams(new URLSearchParams()), [setParams])

  return { search, update, clear }
}

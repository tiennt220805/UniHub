import { describe, expect, it } from 'vitest'

import {
  activeFilterCount,
  EMPTY_SEARCH,
  hasBackwardsRange,
  parseCatalogSearch,
  toApiFilters,
  toCatalogParams,
} from '../catalog-filters'

const parse = (query: string) => parseCatalogSearch(new URLSearchParams(query))

describe('catalog filters in the URL', () => {
  it('reads every field, trimmed', () => {
    expect(parse('q=%20cloud%20&speaker=An&location=A2&dateFrom=2026-11-09&dateTo=2026-11-30&page=2')).toEqual({
      q: 'cloud',
      speaker: 'An',
      location: 'A2',
      dateFrom: '2026-11-09',
      dateTo: '2026-11-30',
      page: 2,
    })
  })

  it('treats a bad date or page as absent, so a hand-edited link still opens', () => {
    expect(parse('dateFrom=2026-02-30&dateTo=tomorrow&page=-1')).toEqual(EMPTY_SEARCH)
    expect(parse('page=abc')).toEqual(EMPTY_SEARCH)
    expect(parse('page=1.5').page).toBe(0)
  })

  it('writes only what is set, and no page for the first page', () => {
    expect(toCatalogParams({ ...EMPTY_SEARCH, q: 'cloud', page: 0 }).toString()).toBe('q=cloud')
    expect(toCatalogParams({ ...EMPTY_SEARCH, page: 3 }).toString()).toBe('page=3')
    expect(toCatalogParams(EMPTY_SEARCH).toString()).toBe('')
  })

  it('round-trips', () => {
    const search = { q: 'đám mây', speaker: 'Lê', location: 'B', dateFrom: '2026-11-10', dateTo: '2026-11-12', page: 1 }
    expect(parseCatalogSearch(toCatalogParams(search))).toEqual(search)
  })
})

describe('the listWorkshops query for a search', () => {
  it('starts at the beginning of today when there is no dateFrom (D138), and sends no page for page 0', () => {
    expect(toApiFilters(EMPTY_SEARCH, '2026-11-09')).toEqual({
      q: undefined,
      speaker: undefined,
      location: undefined,
      dateFrom: '2026-11-08T17:00:00.000Z',
      dateTo: undefined,
      page: undefined,
    })
  })

  it('lets an explicit dateFrom win over the default, and ends dateTo at the end of its day', () => {
    const filters = toApiFilters({ ...EMPTY_SEARCH, dateFrom: '2026-10-01', dateTo: '2026-10-31', page: 2 }, '2026-11-09')
    expect(filters.dateFrom).toBe('2026-09-30T17:00:00.000Z')
    expect(filters.dateTo).toBe('2026-10-31T16:59:59.999Z')
    expect(filters.page).toBe(2)
  })

  it('is the same request all day, whatever the hour: it carries no "now"', () => {
    expect(toApiFilters(EMPTY_SEARCH, '2026-11-09')).toEqual(toApiFilters(EMPTY_SEARCH, '2026-11-09'))
  })
})

describe('the date range', () => {
  it('is backwards when dateFrom is after dateTo, with today standing in for a missing dateFrom', () => {
    expect(hasBackwardsRange({ ...EMPTY_SEARCH, dateFrom: '2026-11-30', dateTo: '2026-11-09' }, '2026-11-01')).toBe(true)
    expect(hasBackwardsRange({ ...EMPTY_SEARCH, dateTo: '2026-11-05' }, '2026-11-09')).toBe(true)
  })

  it('is fine for the same day, an open end, or an ordered pair', () => {
    expect(hasBackwardsRange({ ...EMPTY_SEARCH, dateFrom: '2026-11-09', dateTo: '2026-11-09' }, '2026-11-09')).toBe(false)
    expect(hasBackwardsRange(EMPTY_SEARCH, '2026-11-09')).toBe(false)
  })
})

describe('active filters', () => {
  it('counts the filters the user set, not the page or the default start date', () => {
    expect(activeFilterCount(EMPTY_SEARCH)).toBe(0)
    expect(activeFilterCount({ ...EMPTY_SEARCH, q: 'a', dateTo: '2026-11-30', page: 4 })).toBe(2)
  })
})

type QueryValue = string | number | boolean | null | undefined

/** `?a=1&b=x` from the defined entries of `params`, or `''` when there are none. */
export function toQueryString(params: Record<string, QueryValue>): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== '') search.append(key, String(value))
  }
  const query = search.toString()
  return query ? `?${query}` : ''
}

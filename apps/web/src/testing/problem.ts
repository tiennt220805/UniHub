import { HttpResponse } from 'msw'

type ProblemOptions = {
  /** The slug after `urn:unihub:problem:`; omitted means `about:blank`. */
  slug?: string
  /** A complete `type` URI, for a type this client does not know. */
  type?: string
  retryAfter?: number
  correlationId?: string
}

/** An RFC 7807 response as the Backend API sends it (common/error.yaml). */
export function problem(status: number, options: ProblemOptions = {}) {
  const headers: Record<string, string> = { 'Content-Type': 'application/problem+json' }
  if (options.retryAfter !== undefined) headers['Retry-After'] = String(options.retryAfter)
  return HttpResponse.json(
    {
      type: options.type ?? (options.slug ? `urn:unihub:problem:${options.slug}` : 'about:blank'),
      title: 'Problem',
      status,
      ...(options.correlationId && { correlationId: options.correlationId }),
    },
    { status, headers },
  )
}

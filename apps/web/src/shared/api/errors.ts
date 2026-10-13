import type { components } from '@/shared/api/schema.gen'

export type ProblemDetail = components['schemas']['error'] // common/error.yaml (RFC 7807)

// Explicit fields rather than constructor parameter properties: tsconfig.app.json sets
// erasableSyntaxOnly, which rejects parameter properties.
export class AppError extends Error {
  /** null: no HTTP response (network failure, abort) */
  readonly status: number | null
  /** null: body was not application/problem+json */
  readonly problem: ProblemDetail | null
  readonly retryAfterSeconds: number | null

  constructor(
    status: number | null,
    problem: ProblemDetail | null,
    retryAfterSeconds: number | null,
    options?: ErrorOptions,
  ) {
    super(problem?.title ?? (status === null ? 'Network error' : `HTTP ${status}`), options)
    this.name = 'AppError'
    this.status = status
    this.problem = problem
    this.retryAfterSeconds = retryAfterSeconds
  }
}

/** Transient: the same request may succeed later (network failure, 429, 503). */
export function isTransient(error: AppError): boolean {
  return error.status === null || error.status === 429 || error.status === 503
}

export async function toAppError(response: Response): Promise<AppError> {
  const isProblem = response.headers.get('Content-Type')?.includes('application/problem+json')
  const problem = isProblem ? ((await response.json()) as ProblemDetail) : null
  const retryAfter = Number(response.headers.get('Retry-After'))
  return new AppError(
    response.status,
    problem,
    Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter : null,
  )
}

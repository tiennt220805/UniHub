import type { AppError } from './errors'

/** Automatic retries are capped at 3 (web-coding-standards.md §7, checklist D15). */
export const MAX_AUTOMATIC_RETRIES = 3

/**
 * Whether an idempotency-guarded mutation retries by itself: only after a network failure or a 503,
 * never after a 429 or any other status, and at most three times. The caller must resend the same
 * `Idempotency-Key` on every retry, which holds when the key travels in the mutation variables.
 * `failureCount` is the number of failures already retried (TanStack's `retry` contract).
 */
// `error` is typed AppError, not unknown: this function is passed to useMutation as `retry`, where
// its parameter type decides the mutation's error type. The http client only ever throws AppError.
export function shouldRetryAutomatically(failureCount: number, error: AppError): boolean {
  return failureCount < MAX_AUTOMATIC_RETRIES && (error.status === null || error.status === 503)
}

/**
 * How long to wait before the next automatic retry: the server's `Retry-After` when it sent one,
 * else 1 s, 2 s, 4 s; plus up to a second of jitter so clients that failed together do not return
 * together.
 */
export function retryDelayMs(
  failureCount: number,
  error: AppError,
  random: () => number = Math.random,
): number {
  const backoff = 1000 * 2 ** failureCount
  const requested = error.retryAfterSeconds === null ? backoff : error.retryAfterSeconds * 1000
  return Math.max(requested, backoff) + Math.floor(random() * 1000)
}

import type { AppError } from '@/shared/api/errors'

// UX by status per web-coding-standards.md §6: 429 says when to try again (Retry-After); anything
// else shows the caller's message plus the correlationId a user hands to support.
export function LoadError({ error, message }: { error: AppError; message: string }) {
  if (error.status === 429) {
    return (
      <p role="alert">
        Bạn đang gửi quá nhiều yêu cầu.{' '}
        {error.retryAfterSeconds === null
          ? 'Vui lòng thử lại sau.'
          : `Vui lòng thử lại sau ${error.retryAfterSeconds} giây.`}
      </p>
    )
  }
  const correlationId = error.problem?.correlationId
  return (
    <p role="alert">
      {message}
      {correlationId && <span className="block text-sm">Mã tham chiếu: {correlationId}</span>}
    </p>
  )
}

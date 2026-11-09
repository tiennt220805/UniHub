import type { AppError } from '@/shared/api/errors'
import { Button } from '@/shared/ui/button'
import { ErrorState } from '@/shared/ui/state-panel'

// UX by status per web-coding-standards.md §6: 429 says when to try again (Retry-After); a lost connection says so;
// anything else shows the caller's message plus the correlationId a user hands to support. Every state but 429 offers
// "Thử lại" when the caller can retry (a 429 is told to wait, so a button would only invite the next 429).
export function LoadError({
  error,
  message,
  onRetry,
  className,
}: {
  error: AppError
  message: string
  onRetry?: () => void
  className?: string
}) {
  const retry = onRetry && error.status !== 429 ? (
    <Button variant="outline" onClick={onRetry}>
      Thử lại
    </Button>
  ) : undefined

  if (error.status === 429) {
    return (
      <ErrorState
        className={className}
        title="Bạn đang gửi quá nhiều yêu cầu."
        description={[
          error.retryAfterSeconds === null ? 'Vui lòng thử lại sau.' : `Vui lòng thử lại sau ${error.retryAfterSeconds} giây.`,
          error.problem?.correlationId ? `Mã tham chiếu: ${error.problem.correlationId}` : null,
        ]
          .filter(Boolean)
          .join(' ')}
      />
    )
  }
  if (error.status === null) {
    return (
      <ErrorState
        className={className}
        title="Mất kết nối."
        description="Vui lòng kiểm tra mạng rồi thử lại."
        action={retry}
      />
    )
  }
  const correlationId = error.problem?.correlationId
  return (
    <ErrorState
      className={className}
      title={message}
      description={correlationId ? `Mã tham chiếu: ${correlationId}` : undefined}
      action={retry}
    />
  )
}

import { useState } from 'react'

import { Button } from '@/shared/ui/button'
import { useChargePayment } from '../api/mutations'
import type { Payment } from '../api/requests'
import { chargeErrorMessage } from '../model/error-messages'
import { TEST_PAYMENT_TOKENS } from '../model/test-tokens'

type Props = {
  registrationId: string
  /** Sandbox builds only (checklist D11): without it there is no way to choose a token. */
  testMode: boolean
  /** The route knows the hold has run out; this is UX only, the server decides. */
  disabled?: boolean
  onCharged: (payment: Payment) => void
}

export function CheckoutForm({ registrationId, testMode, disabled = false, onCharged }: Props) {
  const [token, setToken] = useState<string>(TEST_PAYMENT_TOKENS[0].value)
  const { charge, isPending, error } = useChargePayment(registrationId, { onSuccess: onCharged })

  if (!testMode) {
    return (
      <p role="status" className="rounded-md bg-muted p-3 text-foreground">
        Thanh toán trực tuyến chưa khả dụng trong phiên bản này.
      </p>
    )
  }

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault()
        charge(token)
      }}
      className="space-y-4"
    >
      <div className="space-y-1">
        <label htmlFor="payment-method" className="text-sm font-medium">
          Phương thức thanh toán (chế độ thử nghiệm)
        </label>
        <select
          id="payment-method"
          value={token}
          onChange={(event) => setToken(event.target.value)}
          disabled={isPending}
          className="h-9 w-full rounded-lg border border-input bg-background px-3 text-sm"
        >
          {TEST_PAYMENT_TOKENS.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
      </div>
      <Button type="submit" disabled={isPending || disabled}>
        {isPending ? 'Đang thanh toán…' : 'Thanh toán'}
      </Button>
      {error && !isPending && (
        <p role="alert" className="text-sm text-destructive">
          {chargeErrorMessage(error)}
          {error.problem?.correlationId && (
            <span className="block">Mã tham chiếu: {error.problem.correlationId}</span>
          )}
        </p>
      )}
    </form>
  )
}

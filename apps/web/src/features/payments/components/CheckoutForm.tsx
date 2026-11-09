import { Info, Loader2 } from 'lucide-react'
import { useState } from 'react'

import { formatPrice } from '@/shared/lib/format'
import { Button } from '@/shared/ui/button'
import { Field, FieldHint, Label } from '@/shared/ui/field'
import { controlClasses } from '@/shared/ui/control-classes'
import { Notice } from '@/shared/ui/notice'
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
  /** What is being paid, for the bar that sticks to the bottom of a phone; the server charges what it knows. */
  amount?: number | null
  onCharged: (payment: Payment) => void
}

export function CheckoutForm({ registrationId, testMode, disabled = false, amount = null, onCharged }: Props) {
  const [token, setToken] = useState<string>(TEST_PAYMENT_TOKENS[0].value)
  const { charge, isPending, error } = useChargePayment(registrationId, { onSuccess: onCharged })

  if (!testMode) {
    return (
      <Notice tone="muted" icon={Info} role="status">
        Thanh toán trực tuyến chưa khả dụng trong phiên bản này.
      </Notice>
    )
  }

  // The bar with the button is not inside the <form>: a sticky element sticks only inside its parent, and the form is a
  // short card at the top of the column. As a sibling it sticks along the whole column. It is still one button, tied to
  // the form by `form="checkout-form"` (a second button would be counted twice by anything that looks for it by name).
  return (
    <>
      <form
        id="checkout-form"
        onSubmit={(event) => {
          event.preventDefault()
          charge(token)
        }}
        className="grid gap-4 rounded-xl border border-border bg-card p-4 md:p-5"
      >
        <Field>
          <Label htmlFor="payment-method">Phương thức thanh toán (chế độ thử nghiệm)</Label>
          {/* A native <select>, by decision (D149): the Radix one injects a <style> the CSP refuses, and this control exists
              only in the sandbox build. Same class string as the other form controls, 48 px tall. */}
          <select
            id="payment-method"
            value={token}
            onChange={(event) => setToken(event.target.value)}
            disabled={isPending}
            aria-describedby="payment-method-hint"
            className={`${controlClasses} h-12`}
          >
            {TEST_PAYMENT_TOKENS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
          <FieldHint id="payment-method-hint">Chế độ thử nghiệm: không có khoản tiền thật nào bị trừ.</FieldHint>
        </Field>
        {error && !isPending && (
          <Notice tone="destructive" icon={Info} role="alert">
            {chargeErrorMessage(error)}
            {error.problem?.correlationId && (
              <span className="mt-1 block text-xs">Mã tham chiếu: {error.problem.correlationId}</span>
            )}
          </Notice>
        )}
      </form>
      {/* Below `lg` the bar sticks to the bottom edge of the screen with the total; from `lg` it is a plain button under the card. */}
      <div className="sticky bottom-0 z-20 -mx-4 flex items-center gap-3 border-t border-border bg-card px-4 py-3 pb-[calc(0.75rem+env(safe-area-inset-bottom))] md:-mx-6 md:px-6 lg:static lg:mx-0 lg:border-0 lg:bg-transparent lg:p-0">
        {amount !== null && (
          <div className="leading-tight lg:hidden">
            <div className="text-xs text-muted-foreground">Tổng cộng</div>
            <b className="text-lg tabular-nums">{formatPrice(amount)}</b>
          </div>
        )}
        <Button type="submit" form="checkout-form" size="lg" className="h-12 flex-1 text-base" disabled={isPending || disabled}>
          {isPending && <Loader2 aria-hidden="true" className="motion-safe:animate-spin" />}
          {isPending ? 'Đang thanh toán…' : 'Thanh toán'}
        </Button>
      </div>
    </>
  )
}

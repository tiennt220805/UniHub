/**
 * The Stripe test payment methods checkout offers in the sandbox (checklist D11): one that is
 * accepted and one that is declined. They are opaque tokens to the app; no card data is ever
 * handled or stored here. `paymentMethodToken` stays a generic token in the contract, so Stripe.js
 * can replace this list without an API change.
 */
export const TEST_PAYMENT_TOKENS = [
  { value: 'pm_card_visa', label: 'Thẻ test Visa — thanh toán thành công' },
  { value: 'pm_card_chargeDeclined', label: 'Thẻ test — bị từ chối' },
] as const

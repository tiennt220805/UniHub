import { expect, signInThroughTheForm, test, uniqueTitle } from '../support/fixtures'

// M-69 (Phase 7 task 9.2): a Student discovers a workshop in the catalog, registers (free, then paid through the
// stub gateway with a test token) and sees the QR e-ticket. Everything the Student does is in the browser; only
// the workshops are made through the API, because the Web has no page to create one.
const CROCKFORD_TICKET_CODE = /^[0-9A-HJKMNP-TV-Z]{26}$/

test('a Student registers for a free workshop and sees the QR e-ticket', async ({ page, organizerApi, student }) => {
  const title = uniqueTitle('free')
  const workshop = await organizerApi.create({ title, paid: false })
  await organizerApi.publish(workshop.id)

  // Discover: the catalog is public; the new workshop is in it (the catalog is cached, so give it a moment).
  await page.goto('/workshops')
  await expect(page.getByRole('heading', { name: 'Workshops' })).toBeVisible()
  const entry = page.getByRole('link', { name: title })
  await expect(async () => {
    await page.reload()
    await expect(entry).toBeVisible({ timeout: 3_000 })
  }).toPass({ timeout: 30_000 })
  await entry.click()
  await expect(page.getByRole('heading', { level: 1, name: title })).toBeVisible()

  // Not signed in: the button is a link to the sign-in page, and signing in returns to the workshop.
  await page.getByRole('link', { name: 'Đăng nhập để đăng ký' }).click()
  await expect(page).toHaveURL(/\/login$/)
  await signInThroughTheForm(page, student)
  await expect(page).toHaveURL(new RegExp(`/workshops/${workshop.id}$`))

  await page.getByRole('button', { name: 'Đăng ký tham dự' }).click()
  await expect(page.getByText('Đăng ký thành công. Chỗ của bạn đã được xác nhận.')).toBeVisible()

  // The ticket: from the list of registrations, one click on the row of this workshop.
  await page.getByRole('link', { name: 'Đăng ký của tôi' }).click()
  const row = page.getByRole('listitem').filter({ hasText: title })
  await row.getByRole('link', { name: 'Xem vé' }).click()
  const ticket = page.getByRole('region', { name: 'E-ticket' })
  await expect(ticket.getByRole('img', { name: 'Mã QR e-ticket' })).toBeVisible()
  await expect(ticket.locator('.font-mono')).toHaveText(CROCKFORD_TICKET_CODE)
})

test('a Student registers for a paid workshop, pays with a test token and sees the QR e-ticket', async ({
  page,
  organizerApi,
  student,
}) => {
  const title = uniqueTitle('paid')
  const workshop = await organizerApi.create({ title, paid: true })
  await organizerApi.publish(workshop.id)

  await page.goto('/login')
  await signInThroughTheForm(page, student)
  await page.goto(`/workshops/${workshop.id}`)
  await expect(page.getByRole('heading', { level: 1, name: title })).toBeVisible()

  // A paid registration is RESERVED and the page moves on to checkout.
  await page.getByRole('button', { name: 'Đăng ký tham dự' }).click()
  await expect(page).toHaveURL(/\/me\/checkout\/[0-9a-f-]{36}/)
  await expect(page.getByRole('heading', { name: 'Thanh toán' })).toBeVisible()

  // The test-mode build offers the gateway's test tokens; the first one succeeds.
  await expect(page.getByLabel('Phương thức thanh toán (chế độ thử nghiệm)')).toHaveValue('pm_card_visa')
  await page.getByRole('button', { name: 'Thanh toán' }).click()
  await expect(page.getByText('Thanh toán thành công.')).toBeVisible()
  await expect(page.getByText('Đăng ký của bạn đã được xác nhận.')).toBeVisible()

  await page.getByRole('link', { name: 'Xem đăng ký của tôi' }).click()
  const row = page.getByRole('listitem').filter({ hasText: title })
  await expect(row.getByText('Có phí')).toBeVisible()
  await row.getByRole('link', { name: 'Xem vé' }).click()
  const ticket = page.getByRole('region', { name: 'E-ticket' })
  await expect(ticket.getByRole('img', { name: 'Mã QR e-ticket' })).toBeVisible()
  await expect(ticket.locator('.font-mono')).toHaveText(CROCKFORD_TICKET_CODE)
})

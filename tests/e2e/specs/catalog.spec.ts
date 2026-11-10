import type { Page } from '@playwright/test'

import type { OrganizerApi } from '../support/api'
import { expect, test, uniqueTitle } from '../support/fixtures'

// M-82 (Phase 8 task 8.3): the catalog finds a workshop by topic, speaker, place and date range, and pages. Each test
// makes its own workshops, all carrying one unique tag in the title, and looks for the tag, so what the catalog already holds
// (other tests, other runs, the seeded data) cannot change what is counted. The state is in the URL, so every step is checked there too.

/** The workshop cards inside the page area: each is one link whose name holds the title. */
const cards = (page: Page, tag: string) => page.getByRole('main').getByRole('link').filter({ hasText: tag })

async function publish(api: OrganizerApi, input: Parameters<OrganizerApi['create']>[0]) {
  const workshop = await api.create(input)
  await api.publish(workshop.id)
  return workshop
}

/** The date in Vietnam, `YYYY-MM-DD`, of a moment `days` from now: what a `type=date` field takes. */
const vietnamDate = (days: number) =>
  new Date(Date.now() + days * 86_400_000).toLocaleDateString('en-CA', { timeZone: 'Asia/Ho_Chi_Minh' })

test('a topic narrows the catalog, in the URL and on the page', async ({ page, organizerApi }) => {
  const tag = uniqueTitle('topic')
  const alpha = await publish(organizerApi, { title: `${tag} alpha`, paid: false })
  const beta = await publish(organizerApi, { title: `${tag} beta`, paid: false })

  await page.goto('/workshops')
  await page.getByLabel('Chủ đề').fill(tag)
  await expect(page).toHaveURL(new RegExp(`[?&]q=${encodeURIComponent(tag).replace(/%20/g, '(\\+|%20)')}`))
  await expect(page.getByText('Tìm thấy 2 workshop')).toBeVisible()
  await expect(cards(page, tag)).toHaveCount(2)

  await page.getByLabel('Chủ đề').fill(`${tag} alpha`)
  await expect(page.getByText('Tìm thấy 1 workshop')).toBeVisible()
  await expect(page.getByRole('link', { name: new RegExp(`${tag} alpha`) })).toHaveAttribute('href', `/workshops/${alpha.id}`)
  await expect(page.getByRole('link', { name: new RegExp(`${tag} beta`) })).toHaveCount(0)
  expect(beta.id).not.toBe(alpha.id)

  // What was found opens, and Back returns to the same search.
  await page.getByRole('link', { name: new RegExp(`${tag} alpha`) }).click()
  await expect(page.getByRole('heading', { level: 1, name: `${tag} alpha` })).toBeVisible()
  await page.goBack()
  await expect(page.getByLabel('Chủ đề')).toHaveValue(`${tag} alpha`)
  await expect(page.getByText('Tìm thấy 1 workshop')).toBeVisible()
})

test('a speaker and a place each narrow the catalog, and "Xóa bộ lọc" brings the rest back', async ({ page, organizerApi }) => {
  const tag = uniqueTitle('who-where')
  const speaker = `Diễn giả ${tag}`
  const place = `Phòng ${tag}`
  await publish(organizerApi, { title: `${tag} one`, paid: false, speakerName: speaker, location: 'Room E2E' })
  await publish(organizerApi, { title: `${tag} two`, paid: false, location: place })

  await page.goto('/workshops')
  await page.getByLabel('Chủ đề').fill(tag)
  await expect(cards(page, tag)).toHaveCount(2)

  await page.getByLabel('Diễn giả').fill(speaker)
  await expect(cards(page, tag)).toHaveCount(1)
  await expect(page.getByRole('link', { name: new RegExp(`${tag} one`) })).toBeVisible()

  await page.getByLabel('Diễn giả').fill('')
  await page.getByLabel('Địa điểm').fill(place)
  await expect(cards(page, tag)).toHaveCount(1)
  await expect(page.getByRole('link', { name: new RegExp(`${tag} two`) })).toBeVisible()

  // Both at once match nothing, and the page says so and offers the way out.
  await page.getByLabel('Diễn giả').fill(speaker)
  await expect(page.getByText('Không có workshop phù hợp')).toBeVisible()
  await page.getByRole('button', { name: 'Xóa bộ lọc' }).first().click()
  await expect(page).not.toHaveURL(/[?&](q|speaker|location)=/)
  await expect(page.getByLabel('Chủ đề')).toHaveValue('')
})

test('a date range keeps the workshops inside it', async ({ page, organizerApi }) => {
  const tag = uniqueTitle('dates')
  await publish(organizerApi, { title: `${tag} soon`, paid: false, daysAhead: 3 })
  await publish(organizerApi, { title: `${tag} later`, paid: false, daysAhead: 20 })

  await page.goto('/workshops')
  await page.getByLabel('Chủ đề').fill(tag)
  await expect(cards(page, tag)).toHaveCount(2)

  await page.getByLabel('Đến ngày').fill(vietnamDate(10))
  await expect(cards(page, tag)).toHaveCount(1)
  await expect(page.getByRole('link', { name: new RegExp(`${tag} soon`) })).toBeVisible()
  await expect(page).toHaveURL(new RegExp(`dateTo=${vietnamDate(10)}`))

  await page.getByLabel('Đến ngày').fill('')
  await page.getByLabel('Từ ngày').fill(vietnamDate(10))
  await expect(cards(page, tag)).toHaveCount(1)
  await expect(page.getByRole('link', { name: new RegExp(`${tag} later`) })).toBeVisible()

  // An end before the start is said at the dates and sends no search.
  await page.getByLabel('Đến ngày').fill(vietnamDate(5))
  await expect(page.getByRole('alert').filter({ hasText: 'phải trước hoặc bằng' })).toBeVisible()
})

// 21 workshops are 21 creates, 21 publishes and 21 cancels against the Organizer's budget (100 calls a minute, INV-17), and
// the page size is the server's (20). The paging is the same code on every engine, so one engine pays for it.
test('more than a page of results pages, keeping the search', async ({ page, organizerApi }, testInfo) => {
  test.skip(testInfo.project.name !== 'chromium', 'one engine seeds 21 workshops: three would spend the Organizer\'s call budget')
  test.setTimeout(240_000)
  const tag = uniqueTitle('paging')
  const total = 21
  for (let from = 0; from < total; from += 7) {
    await Promise.all(
      Array.from({ length: Math.min(7, total - from) }, (_, i) => publish(organizerApi, { title: `${tag} n${from + i}`, paid: false })),
    )
  }

  await page.goto(`/workshops?q=${encodeURIComponent(tag)}`)
  await expect(page.getByText(`Tìm thấy ${total} workshop`)).toBeVisible()
  await expect(cards(page, tag)).toHaveCount(20)

  const pages = page.getByRole('navigation', { name: 'Phân trang' })
  await pages.getByRole('button', { name: 'Trang 2' }).click()
  await expect(page).toHaveURL(/[?&]page=1(&|$)/)
  await expect(cards(page, tag)).toHaveCount(1)
  await expect(pages.getByRole('button', { name: 'Trang 2' })).toHaveAttribute('aria-current', 'page')

  // The page is in the address: a reload stays there, and "Trước" goes back.
  await page.reload()
  await expect(cards(page, tag)).toHaveCount(1)
  await pages.getByRole('button', { name: 'Trước' }).click()
  await expect(cards(page, tag)).toHaveCount(20)
  await expect(page).not.toHaveURL(/[?&]page=[1-9]/)
})

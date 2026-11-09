import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'

import { signInAs } from '@/testing/factories/session'
import { aWorkshop, WORKSHOP_ID } from '@/testing/factories/workshop'
import { server } from '@/testing/msw-server'
import { problem } from '@/testing/problem'
import { renderRoute } from '@/testing/render-route'

const PATH = `/admin/workshops/${WORKSHOP_ID}`
const DETAIL = '/api/v1/workshops/:id'
// The storage is reached on the application's own origin (D120), so the tests PUT to a same-origin path: jsdom applies CORS to
// anything else, which the real deployment never meets.
const STORAGE = '/unihub-brochures'
const asManager = () => signInAs({ permissions: ['WORKSHOP_CREATE', 'WORKSHOP_EDIT', 'REPORT_VIEW'] })
// The inputs are `accept`-filtered in a browser; the page checks the file itself, so the tests send what the filter would not let through.
const userWithoutAccept = () => userEvent.setup({ applyAccept: false })

const image = (name = 'hoi-truong.jpg', type = 'image/jpeg', bytes = 1024) => new File([new Uint8Array(bytes)], name, { type })
const pdf = (name = 'brochure.pdf') => new File([new Uint8Array(2048)], name, { type: 'application/pdf' })

type Calls = { urlRequests: unknown[]; puts: { contentType: string | null; authorization: string | null; idempotencyKey: string | null; size: number }[]; confirms: number }

/** The storage side: the URL request, the PUT to the issued URL, and the confirm, each recorded. `confirm` decides the answer. */
function serveCover(options: { confirm?: () => Response; put?: () => Response; url?: () => Response } = {}, workshop = aWorkshop({ status: 'DRAFT', seatsTaken: 0 })): Calls {
  const calls: Calls = { urlRequests: [], puts: [], confirms: 0 }
  server.use(
    http.get(DETAIL, () => HttpResponse.json(workshop)),
    http.post(`${DETAIL}/cover-image-upload-url`, async ({ request }) => {
      calls.urlRequests.push(await request.json())
      return options.url?.() ?? HttpResponse.json({ uploadUrl: `${STORAGE}/covers/${WORKSHOP_ID}/k.jpg`, expiresAt: '2026-11-09T10:00:00Z' })
    }),
    http.put(`${STORAGE}/covers/*`, async ({ request }) => {
      calls.puts.push({
        contentType: request.headers.get('content-type'),
        authorization: request.headers.get('authorization'),
        idempotencyKey: request.headers.get('idempotency-key'),
        size: (await request.arrayBuffer()).byteLength,
      })
      return options.put?.() ?? new HttpResponse(null, { status: 200 })
    }),
    http.post(`${DETAIL}/cover-image-upload-confirm`, () => {
      calls.confirms += 1
      return options.confirm?.() ?? HttpResponse.json({ coverImageUrl: `/media/covers/${WORKSHOP_ID}/k.jpg` })
    }),
  )
  return calls
}

const coverCard = () => screen.getByRole('region', { name: 'Ảnh bìa' })
const choose = async (user: ReturnType<typeof userEvent.setup>, file: File) => user.upload(within(coverCard()).getByLabelText('Chọn tệp ảnh bìa'), file)

describe('Cover image upload (S13, D162)', () => {
  it('previews the chosen file, sends it straight to the storage with only its Content-Type, then confirms', async () => {
    const calls = serveCover()
    asManager()
    const user = userEvent.setup()
    renderRoute(PATH)
    await screen.findByRole('heading', { level: 1 })

    await choose(user, image('hoi-truong.jpg', 'image/jpeg', 1500))

    expect(await within(coverCard()).findByRole('img', { name: 'Xem trước ảnh đã chọn' })).toHaveAttribute('src', expect.stringMatching(/^data:/))
    expect(within(coverCard()).getByText('hoi-truong.jpg')).toBeInTheDocument()
    expect(calls.urlRequests).toHaveLength(0) // nothing is asked until the user presses "Tải lên"

    await user.click(within(coverCard()).getByRole('button', { name: 'Tải lên' }))

    expect(await within(coverCard()).findByText('Đã cập nhật ảnh bìa.')).toBeInTheDocument()
    expect(calls.urlRequests).toEqual([{ contentType: 'image/jpeg', contentLength: 1500 }])
    expect(calls.puts).toEqual([{ contentType: 'image/jpeg', authorization: null, idempotencyKey: null, size: 1500 }])
    expect(calls.confirms).toBe(1)
  })

  it('refuses a type the backend does not take, and a file over 2 MB, before any request', async () => {
    const calls = serveCover()
    asManager()
    const user = userWithoutAccept()
    renderRoute(PATH)
    await screen.findByRole('heading', { level: 1 })

    await choose(user, image('logo.svg', 'image/svg+xml'))
    expect(await within(coverCard()).findByRole('alert')).toHaveTextContent('Chỉ nhận ảnh JPEG, PNG hoặc WebP. SVG và GIF không được dùng.')

    await choose(user, image('anh-lon.jpg', 'image/jpeg', 3 * 1_048_576))
    expect(await within(coverCard()).findByRole('alert')).toHaveTextContent('Ảnh nặng 3,0 MB, lớn hơn mức 2,0 MB. Chọn ảnh nhỏ hơn.')

    await choose(user, image('gif.gif', 'image/gif'))
    expect(await within(coverCard()).findByRole('alert')).toHaveTextContent('Chỉ nhận ảnh JPEG, PNG hoặc WebP')
    expect(calls.urlRequests).toHaveLength(0)
  })

  it('takes a file with no type by its extension, and uses that one type for the request and the PUT', async () => {
    const calls = serveCover()
    asManager()
    const user = userWithoutAccept()
    renderRoute(PATH)
    await screen.findByRole('heading', { level: 1 })

    await choose(user, image('anh.PNG', '', 800))
    await user.click(await within(coverCard()).findByRole('button', { name: 'Tải lên' }))

    await within(coverCard()).findByText('Đã cập nhật ảnh bìa.')
    expect(calls.urlRequests).toEqual([{ contentType: 'image/png', contentLength: 800 }])
    expect(calls.puts[0].contentType).toBe('image/png')
  })

  it('on a 422 says the file was refused and deleted, and offers another choice', async () => {
    serveCover({ confirm: () => problem(422, { slug: 'cover-image-rejected' }) })
    asManager()
    const user = userEvent.setup()
    renderRoute(PATH)
    await screen.findByRole('heading', { level: 1 })
    await choose(user, image())
    await user.click(await within(coverCard()).findByRole('button', { name: 'Tải lên' }))

    const alert = await within(coverCard()).findByRole('alert')
    expect(alert).toHaveTextContent('Máy chủ không nhận file này.')
    expect(alert).toHaveTextContent('File đã bị xóa khỏi kho lưu trữ')

    await user.click(within(alert).getByRole('button', { name: 'Chọn file khác' }))
    expect(within(coverCard()).getByText('Kéo ảnh vào đây')).toBeInTheDocument()
  })

  it('on a 503 at the confirm repeats only the confirm: the file is already stored and the key is kept', async () => {
    let answered = 0
    const calls = serveCover({ confirm: () => (++answered === 1 ? problem(503, { slug: 'storage-unavailable', retryAfter: 5 }) : HttpResponse.json({ coverImageUrl: `/media/covers/${WORKSHOP_ID}/k.jpg` })) })
    asManager()
    const user = userEvent.setup()
    renderRoute(PATH)
    await screen.findByRole('heading', { level: 1 })
    await choose(user, image())
    await user.click(await within(coverCard()).findByRole('button', { name: 'Tải lên' }))

    const alert = await within(coverCard()).findByRole('alert')
    expect(alert).toHaveTextContent('Kho lưu trữ tạm thời không dùng được.')
    await user.click(within(alert).getByRole('button', { name: 'Thử lại' }))

    await within(coverCard()).findByText('Đã cập nhật ảnh bìa.')
    expect(calls.confirms).toBe(2)
    expect(calls.urlRequests).toHaveLength(1) // no new URL
    expect(calls.puts).toHaveLength(1) // no second upload
  })

  it('when the PUT fails asks for a new URL on retry: the old one may be spent', async () => {
    let put = 0
    const calls = serveCover({ put: () => (++put === 1 ? new HttpResponse(null, { status: 403 }) : new HttpResponse(null, { status: 200 })) })
    asManager()
    const user = userEvent.setup()
    renderRoute(PATH)
    await screen.findByRole('heading', { level: 1 })
    await choose(user, image())
    await user.click(await within(coverCard()).findByRole('button', { name: 'Tải lên' }))

    const alert = await within(coverCard()).findByRole('alert')
    expect(alert).toHaveTextContent('Liên kết tải lên chỉ dùng được 10 phút')
    expect(calls.confirms).toBe(0) // an unfinished upload is never confirmed
    await user.click(within(alert).getByRole('button', { name: 'Tải lên lại' }))

    await within(coverCard()).findByText('Đã cập nhật ảnh bìa.')
    expect(calls.urlRequests).toHaveLength(2)
  })

  it('says a cancelled or completed workshop takes no new cover (409 at the URL request)', async () => {
    serveCover({ url: () => problem(409) })
    asManager()
    const user = userEvent.setup()
    renderRoute(PATH)
    await screen.findByRole('heading', { level: 1 })
    await choose(user, image())
    await user.click(await within(coverCard()).findByRole('button', { name: 'Tải lên' }))
    expect(await within(coverCard()).findByRole('alert')).toHaveTextContent('đã bị hủy hoặc đã kết thúc')
  })

  it('shows the current cover with "Đổi ảnh bìa" when there is one, and no drop zone', async () => {
    serveCover({}, aWorkshop({ status: 'DRAFT', coverImageUrl: `/media/covers/${WORKSHOP_ID}/old.jpg` }))
    asManager()
    renderRoute(PATH)
    await screen.findByRole('heading', { level: 1 })
    expect(await within(coverCard()).findByRole('img', { name: 'Ảnh bìa hiện tại' })).toHaveAttribute('src', `/media/covers/${WORKSHOP_ID}/old.jpg`)
    expect(within(coverCard()).getByRole('button', { name: 'Đổi ảnh bìa' })).toBeInTheDocument()
    expect(within(coverCard()).queryByText('Kéo ảnh vào đây')).not.toBeInTheDocument()
  })

  it('offers no upload for a cancelled workshop or to a session with neither permission to create nor to edit', async () => {
    serveCover({}, aWorkshop({ status: 'CANCELLED' }))
    asManager()
    renderRoute(PATH)
    await screen.findByRole('heading', { level: 1 })
    expect(within(coverCard()).queryByLabelText('Chọn tệp ảnh bìa')).not.toBeInTheDocument()
  })

  it('lets WORKSHOP_CREATE alone upload, though the form is read-only', async () => {
    serveCover()
    signInAs({ permissions: ['WORKSHOP_CREATE'] })
    renderRoute(PATH)
    await screen.findByRole('heading', { level: 1 })
    expect(within(coverCard()).getByLabelText('Chọn tệp ảnh bìa')).toBeInTheDocument()
    expect(screen.getByLabelText(/^Tên workshop/)).toBeDisabled()
  })
})

describe('Brochure upload (S14, D163)', () => {
  const card = () => screen.getByRole('region', { name: 'Brochure' })
  const chooseBrochure = async (user: ReturnType<typeof userEvent.setup>, file: File) => user.upload(within(card()).getByLabelText('Chọn tệp brochure PDF'), file)

  function serveBrochure(workshop = aWorkshop({ status: 'DRAFT', seatsTaken: 0 }), confirm?: () => Response) {
    const calls = { urlRequests: [] as unknown[], puts: 0, confirms: 0, gets: 0 }
    server.use(
      http.get(DETAIL, () => ((calls.gets += 1), HttpResponse.json(workshop))),
      http.post(`${DETAIL}/brochure-upload-url`, async ({ request }) => {
        calls.urlRequests.push(await request.json())
        return HttpResponse.json({ uploadUrl: `${STORAGE}/brochures/${WORKSHOP_ID}/b.pdf`, expiresAt: '2026-11-09T10:00:00Z' })
      }),
      http.put(`${STORAGE}/brochures/*`, async ({ request }) => {
        calls.puts += 1
        expect(request.headers.get('content-type')).toBe('application/pdf')
        expect(request.headers.get('authorization')).toBeNull()
        return new HttpResponse(null, { status: 200 })
      }),
      http.post(`${DETAIL}/brochure-upload-confirm`, () => {
        calls.confirms += 1
        return confirm?.() ?? HttpResponse.json({ hasBrochure: true })
      }),
    )
    return calls
  }

  it('sends a PDF through the three calls and says it was received', async () => {
    const calls = serveBrochure()
    asManager()
    const user = userEvent.setup()
    renderRoute(PATH)
    await screen.findByRole('heading', { level: 1 })

    await chooseBrochure(user, pdf('brochure-ml.pdf'))
    expect(within(card()).getByText('brochure-ml.pdf')).toBeInTheDocument()
    await user.click(within(card()).getByRole('button', { name: 'Tải lên' }))

    expect(await within(card()).findByText('Đã nhận brochure.')).toBeInTheDocument()
    expect(calls.urlRequests).toEqual([{ contentType: 'application/pdf' }])
    expect(calls.puts).toBe(1)
    expect(calls.confirms).toBe(1)
  })

  it('takes only a PDF', async () => {
    const calls = serveBrochure()
    asManager()
    const user = userWithoutAccept()
    renderRoute(PATH)
    await screen.findByRole('heading', { level: 1 })

    await chooseBrochure(user, new File(['x'], 'ghi-chu.docx', { type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' }))

    expect(await within(card()).findByRole('alert')).toHaveTextContent('Chỉ nhận file PDF.')
    expect(calls.urlRequests).toHaveLength(0)
  })

  it('says "Chưa có tóm tắt" without claiming it is being made, and "Kiểm tra lại" reads the workshop once more', async () => {
    const calls = serveBrochure(aWorkshop({ status: 'DRAFT', hasBrochure: true, aiSummary: null }))
    asManager()
    const user = userEvent.setup()
    renderRoute(PATH)
    await screen.findByRole('heading', { level: 1 })

    const notice = await within(card()).findByText(/Chưa có tóm tắt\./)
    expect(notice.closest('[data-slot="notice"]')).toHaveTextContent('Có thể hệ thống đang tạo, hoặc không đọc được brochure')
    const before = calls.gets
    await user.click(within(card()).getByRole('button', { name: 'Kiểm tra lại' }))

    await waitFor(() => expect(calls.gets).toBeGreaterThan(before))
    // Once, by the user's request: nothing polls.
    await new Promise((resolve) => setTimeout(resolve, 150))
    expect(calls.gets).toBe(before + 1)
  })

  it('shows the summary when there is one, and replaces a brochure with "Thay brochure"', async () => {
    serveBrochure(aWorkshop({ status: 'PUBLISHED', hasBrochure: true, aiSummary: 'Buổi học giới thiệu học máy.' }))
    asManager()
    renderRoute(PATH)
    await screen.findByRole('heading', { level: 1 })
    expect(await within(card()).findByText('Buổi học giới thiệu học máy.')).toBeInTheDocument()
    expect(within(card()).queryByText(/Chưa có tóm tắt/)).not.toBeInTheDocument()
    expect(within(card()).getByRole('button', { name: 'Thay brochure' })).toBeInTheDocument()
  })

  it('on a 503 at the confirm retries only the confirm', async () => {
    let answered = 0
    const calls = serveBrochure(undefined, () => (++answered === 1 ? problem(503, { slug: 'storage-unavailable' }) : HttpResponse.json({ hasBrochure: true })))
    asManager()
    const user = userEvent.setup()
    renderRoute(PATH)
    await screen.findByRole('heading', { level: 1 })
    await chooseBrochure(user, pdf())
    await user.click(within(card()).getByRole('button', { name: 'Tải lên' }))

    const alert = await within(card()).findByRole('alert')
    expect(alert).toHaveTextContent('Chưa xác nhận được brochure.')
    await user.click(within(alert).getByRole('button', { name: 'Thử lại' }))

    await within(card()).findByText('Đã nhận brochure.')
    expect(calls.confirms).toBe(2)
    expect(calls.puts).toBe(1)
    expect(calls.urlRequests).toHaveLength(1)
  })
})

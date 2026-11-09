import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'

import { aWorkshopReport } from '@/testing/factories/report'
import { signInAs } from '@/testing/factories/session'
import { aWorkshopPage, WORKSHOP_ID } from '@/testing/factories/workshop'
import { server } from '@/testing/msw-server'
import { renderRoute } from '@/testing/render-route'

const emptyCatalog = () => server.use(http.get('/api/v1/workshops', () => HttpResponse.json(aWorkshopPage([]))))
const REPORT = `/admin/workshops/${WORKSHOP_ID}/report`
const reportOk = () => server.use(http.get('/api/v1/workshops/:id/report', () => HttpResponse.json(aWorkshopReport())))

describe('StudentLayout', () => {
  it('signed out: the catalog link, a sign-in link and the theme choices; nothing that needs a session', async () => {
    emptyCatalog()
    renderRoute('/workshops')

    const nav = screen.getByRole('navigation', { name: 'Điều hướng chính' })
    expect(within(nav).getByRole('link', { name: 'Workshop' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Đăng nhập' })).toHaveAttribute('href', '/login')
    for (const name of ['Sáng', 'Tối', 'Theo hệ thống']) expect(screen.getByRole('button', { name })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Đăng ký của tôi' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Thông báo' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Hộp thư thông báo' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Đăng xuất' })).not.toBeInTheDocument()
  })

  it('signed in: the links of a student, the name, a sign-out button, and one "Thông báo" link', async () => {
    emptyCatalog()
    signInAs()
    renderRoute('/workshops')

    const nav = screen.getByRole('navigation', { name: 'Điều hướng chính' })
    expect(within(nav).getByRole('link', { name: 'Đăng ký của tôi' })).toHaveAttribute('href', '/me/registrations')
    expect(within(nav).getByRole('link', { name: 'Thông báo' })).toHaveAttribute('href', '/me/notifications')
    expect(screen.getAllByRole('link', { name: 'Thông báo' })).toHaveLength(1)
    expect(screen.getByText('Nguyễn Văn An')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Đăng xuất' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Đăng nhập' })).not.toBeInTheDocument()
  })

  it('marks the page the user is on', async () => {
    emptyCatalog()
    renderRoute('/workshops')
    expect(screen.getByRole('link', { name: 'Workshop' })).toHaveAttribute('aria-current', 'page')
  })

  it('has no unread count on the phone bell: a notification has no read state', async () => {
    emptyCatalog()
    signInAs()
    renderRoute('/workshops')
    const bell = screen.getByRole('link', { name: 'Hộp thư thông báo' })
    expect(bell).toHaveAttribute('href', '/me/notifications')
    expect(bell).toHaveTextContent('')
  })

  it('the phone menu opens the same links in a sheet and closes when one is followed', async () => {
    emptyCatalog()
    signInAs()
    server.use(http.get('/api/v1/registrations', () => HttpResponse.json({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })))
    const user = userEvent.setup()
    const { router } = renderRoute('/workshops')

    await user.click(screen.getByRole('button', { name: 'Mở menu' }))
    const sheet = screen.getByRole('dialog', { name: 'Menu' })
    expect(within(sheet).getByRole('link', { name: 'Workshop' })).toBeInTheDocument()
    expect(within(sheet).getByRole('button', { name: 'Đăng xuất' })).toBeInTheDocument()
    expect(within(sheet).getByRole('group', { name: 'Chế độ hiển thị' })).toBeInTheDocument()

    await user.click(within(sheet).getByRole('link', { name: 'Đăng ký của tôi' }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/me/registrations'))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Menu' })).not.toBeInTheDocument())
  })

  it('signing out from the phone menu ends the session', async () => {
    emptyCatalog()
    signInAs()
    const user = userEvent.setup()
    renderRoute('/workshops')
    await user.click(screen.getByRole('button', { name: 'Mở menu' }))
    await user.click(within(screen.getByRole('dialog', { name: 'Menu' })).getByRole('button', { name: 'Đăng xuất' }))
    expect(sessionStorage.getItem('unihub.session')).toBeNull()
  })

  it('the theme choice in the header changes the page', async () => {
    emptyCatalog()
    const user = userEvent.setup()
    renderRoute('/workshops')
    await user.click(screen.getByRole('button', { name: 'Tối' }))
    expect(document.documentElement).toHaveClass('dark')
    await user.click(screen.getByRole('button', { name: 'Sáng' }))
    expect(document.documentElement).not.toHaveClass('dark')
  })
})

describe('AdminLayout and its guard', () => {
  it('a visitor who is not signed in is sent to sign in, and comes back to the page', async () => {
    const { router } = renderRoute(REPORT)
    await waitFor(() => expect(router.state.location.pathname).toBe('/login'))
    expect(router.state.location.state).toMatchObject({ from: { pathname: REPORT } })
  })

  it('a student with none of the admin permissions is refused, with a way out, and sees no admin frame', async () => {
    signInAs({ permissions: ['REGISTRATION_CREATE'] })
    renderRoute(REPORT)
    expect(await screen.findByRole('alert')).toHaveTextContent('Bạn không có quyền truy cập trang này.')
    expect(screen.getByRole('link', { name: 'Về trang chủ' })).toHaveAttribute('href', '/')
    expect(screen.queryByRole('complementary')).not.toBeInTheDocument()
  })

  it('whoever holds one admin permission gets the shell: the sidebar, the way back, the theme and sign-out', async () => {
    reportOk()
    signInAs({ permissions: ['REPORT_VIEW'] })
    renderRoute(REPORT)

    const side = await screen.findByRole('complementary', { name: 'Thanh bên quản trị' })
    expect(within(side).getByRole('link', { name: 'Về trang sinh viên' })).toHaveAttribute('href', '/workshops')
    expect(within(side).getByRole('group', { name: 'Chế độ hiển thị' })).toBeInTheDocument()
    expect(within(side).getByText('Nguyễn Văn An')).toBeInTheDocument()
    expect(within(side).getByText('Student')).toBeInTheDocument()
    expect(within(side).getByRole('button', { name: 'Đăng xuất' })).toBeInTheDocument()
    // The student frame is not around an admin page.
    expect(screen.queryByRole('navigation', { name: 'Điều hướng chính' })).not.toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Báo cáo workshop' })).toBeInTheDocument()
  })

  it('the shell is let in by WORKSHOP_EDIT alone, and the report page still asks for REPORT_VIEW', async () => {
    signInAs({ permissions: ['WORKSHOP_EDIT'] })
    renderRoute(REPORT)
    expect(await screen.findByRole('complementary', { name: 'Thanh bên quản trị' })).toBeInTheDocument()
    expect(await screen.findByRole('alert')).toHaveTextContent('Bạn không có quyền truy cập trang này.')
  })

  it('the sidebar offers only what the session may use: the list for WORKSHOP_EDIT, nothing for a report-only session', async () => {
    reportOk()
    signInAs({ permissions: ['WORKSHOP_CREATE', 'WORKSHOP_EDIT', 'REPORT_VIEW'] })
    renderRoute(REPORT)
    await screen.findByRole('complementary', { name: 'Thanh bên quản trị' })
    const nav = screen.getByRole('navigation', { name: 'Quản trị' })
    expect(within(nav).getByRole('link', { name: 'Danh sách workshop' })).toHaveAttribute('href', '/admin/workshops')
  })

  it('a report-only session has no admin navigation: the list needs WORKSHOP_EDIT', async () => {
    reportOk()
    signInAs({ permissions: ['REPORT_VIEW'] })
    renderRoute(REPORT)
    await screen.findByRole('complementary', { name: 'Thanh bên quản trị' })
    expect(screen.queryByRole('navigation', { name: 'Quản trị' })).not.toBeInTheDocument()
  })

  it('the phone menu opens the admin links in a sheet', async () => {
    reportOk()
    signInAs({ permissions: ['REPORT_VIEW'] })
    const user = userEvent.setup()
    renderRoute(REPORT)
    await user.click(await screen.findByRole('button', { name: 'Mở menu quản trị' }))
    const sheet = screen.getByRole('dialog', { name: 'Quản trị' })
    expect(within(sheet).getByRole('link', { name: 'Về trang sinh viên' })).toBeInTheDocument()
    expect(within(sheet).getByRole('button', { name: 'Đăng xuất' })).toBeInTheDocument()
  })

  it('signing out of the admin shell ends the session', async () => {
    reportOk()
    signInAs({ permissions: ['REPORT_VIEW'] })
    const user = userEvent.setup()
    renderRoute(REPORT)
    const side = await screen.findByRole('complementary', { name: 'Thanh bên quản trị' })
    await user.click(within(side).getByRole('button', { name: 'Đăng xuất' }))
    expect(sessionStorage.getItem('unihub.session')).toBeNull()
  })
})

import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { RouterProvider, createMemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { AdminPageError } from '../AdminPageError'

// Task 7.5/8.2: what a person sees when an administration chunk cannot be loaded. The browser half (a real blocked chunk, the
// sidebar still in place, recovery after a reload) is tests/e2e/specs-local/lazy-chunks.spec.ts.
function Broken(): never {
  throw new TypeError('Failed to fetch dynamically imported module: https://unihub.local/assets/WorkshopListPage-abc123.js')
}

function renderBroken() {
  const router = createMemoryRouter([{ path: '/', element: <Broken />, errorElement: <AdminPageError /> }])
  vi.spyOn(console, 'error').mockImplementation(() => {})
  return render(<RouterProvider router={router} />)
}

afterEach(() => vi.restoreAllMocks())

describe('AdminPageError', () => {
  it('says in Vietnamese that the page could not be loaded, and shows nothing of the error itself', () => {
    renderBroken()
    expect(screen.getByText('Không tải được trang này.')).toBeInTheDocument()
    expect(screen.getByText(/Tải lại trang để lấy bản mới nhất/)).toBeInTheDocument()
    // Not React Router's default page, and not the module URL.
    expect(screen.queryByText(/Unexpected Application Error/i)).not.toBeInTheDocument()
    expect(document.body.textContent).not.toMatch(/WorkshopListPage-abc123|dynamically imported/)
  })

  it('"Tải lại" reloads the page, which fetches the current build', async () => {
    const reload = vi.fn()
    vi.spyOn(window, 'location', 'get').mockReturnValue({ ...window.location, reload } as Location)
    renderBroken()
    await userEvent.click(screen.getByRole('button', { name: 'Tải lại' }))
    expect(reload).toHaveBeenCalledTimes(1)
  })
})

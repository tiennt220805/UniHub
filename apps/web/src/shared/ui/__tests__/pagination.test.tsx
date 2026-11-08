import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { pageWindow } from '@/shared/lib/page-window'
import { Pagination } from '../pagination'

describe('pageWindow', () => {
  it('shows every page when there are few', () => {
    expect(pageWindow(0, 3)).toEqual([0, 1, 2])
  })

  it('keeps the first, the last and the neighbours, with a gap marker between', () => {
    expect(pageWindow(5, 12)).toEqual([0, null, 4, 5, 6, null, 11])
    expect(pageWindow(0, 12)).toEqual([0, 1, null, 11])
    expect(pageWindow(11, 12)).toEqual([0, null, 10, 11])
  })

  it('does not mark a gap where only one page is missing', () => {
    expect(pageWindow(2, 5)).toEqual([0, 1, 2, 3, 4])
  })
})

describe('Pagination', () => {
  it('renders nothing for a single page', () => {
    const { container } = render(<Pagination page={0} pageCount={1} onPageChange={() => {}} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('is a labelled navigation and marks the current page', () => {
    render(<Pagination page={1} pageCount={3} onPageChange={() => {}} />)
    expect(screen.getByRole('navigation', { name: 'Phân trang' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Trang 2' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('button', { name: 'Trang 1' })).not.toHaveAttribute('aria-current')
  })

  it('goes back and forward by one, and to a numbered page', async () => {
    const onPageChange = vi.fn()
    render(<Pagination page={1} pageCount={5} onPageChange={onPageChange} />)
    await userEvent.click(screen.getByRole('button', { name: 'Sau' }))
    await userEvent.click(screen.getByRole('button', { name: 'Trước' }))
    await userEvent.click(screen.getByRole('button', { name: 'Trang 5' }))
    expect(onPageChange.mock.calls).toEqual([[2], [0], [4]])
  })

  it('cannot go before the first page or after the last', () => {
    const { rerender } = render(<Pagination page={0} pageCount={3} onPageChange={() => {}} />)
    expect(screen.getByRole('button', { name: 'Trước' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Sau' })).toBeEnabled()
    rerender(<Pagination page={2} pageCount={3} onPageChange={() => {}} />)
    expect(screen.getByRole('button', { name: 'Sau' })).toBeDisabled()
  })
})

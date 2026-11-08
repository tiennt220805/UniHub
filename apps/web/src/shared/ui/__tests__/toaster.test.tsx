import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { toast, TOAST_EXIT_MS, useToastStore } from '@/shared/store/toast-store'
import { Toaster } from '../toaster'

beforeEach(() => {
  vi.useFakeTimers()
  useToastStore.setState({ toasts: [] })
})
afterEach(() => {
  vi.useRealTimers()
  useToastStore.setState({ toasts: [] })
})

const advance = (ms: number) => act(() => void vi.advanceTimersByTime(ms))

describe('Toaster', () => {
  it('has both live regions in the DOM before anything is shown: a region added with its content is not announced', () => {
    render(<Toaster />)
    expect(screen.getByLabelText('Thông báo')).toBeEmptyDOMElement()
    expect(screen.getByLabelText('Thông báo')).toHaveAttribute('aria-live', 'polite')
    expect(screen.getByLabelText('Lỗi')).toBeEmptyDOMElement()
    expect(screen.getByLabelText('Lỗi')).toHaveAttribute('aria-live', 'assertive')
    // No role on them: a page's own `role="alert"` stays the only alert.
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('puts success and information in the polite region and errors in the alert region', () => {
    render(<Toaster />)
    act(() => {
      toast.success('Đã lưu thay đổi.')
      toast.info('Có phiên bản mới.')
      toast.error('Không lưu được.')
    })
    const polite = screen.getByLabelText('Thông báo')
    const assertive = screen.getByLabelText('Lỗi')
    expect(polite).toHaveTextContent('Đã lưu thay đổi.')
    expect(polite).toHaveTextContent('Có phiên bản mới.')
    expect(polite).not.toHaveTextContent('Không lưu được.')
    expect(assertive).toHaveTextContent('Không lưu được.')
  })

  it('dismisses a success by itself after five seconds, through the exit transition', () => {
    render(<Toaster />)
    act(() => void toast.success('Đã lưu.'))
    advance(4900)
    expect(screen.getByText('Đã lưu.')).toBeInTheDocument()
    advance(200)
    expect(screen.getByText('Đã lưu.').closest('[data-slot="toast"]')).toHaveAttribute('data-state', 'closed')
    advance(TOAST_EXIT_MS + 10)
    expect(screen.queryByText('Đã lưu.')).not.toBeInTheDocument()
  })

  it('never dismisses an error by a timer: it has to be read', () => {
    render(<Toaster />)
    act(() => void toast.error('Không lưu được.'))
    advance(60_000)
    expect(screen.getByText('Không lưu được.')).toBeInTheDocument()
  })

  it('holds the timer while the pointer is on it, and starts it again afterwards', () => {
    render(<Toaster />)
    act(() => void toast.success('Đã lưu.'))
    const item = screen.getByText('Đã lưu.').closest('[data-slot="toast"]') as HTMLElement
    fireEvent.mouseEnter(item)
    advance(30_000)
    expect(screen.getByText('Đã lưu.')).toBeInTheDocument()
    fireEvent.mouseLeave(item)
    advance(5000 + TOAST_EXIT_MS + 10)
    expect(screen.queryByText('Đã lưu.')).not.toBeInTheDocument()
  })

  it('holds the timer while focus is inside it', () => {
    render(<Toaster />)
    act(() => void toast.success('Đã lưu.'))
    fireEvent.focus(screen.getByRole('button', { name: 'Đóng thông báo' }))
    advance(30_000)
    expect(screen.getByText('Đã lưu.')).toBeInTheDocument()
  })

  it('closes on the close button', () => {
    render(<Toaster />)
    act(() => void toast.error('Không lưu được.'))
    fireEvent.click(screen.getByRole('button', { name: 'Đóng thông báo' }))
    advance(TOAST_EXIT_MS + 10)
    expect(screen.queryByText('Không lưu được.')).not.toBeInTheDocument()
  })

  it('runs an action once and then closes', () => {
    const onClick = vi.fn()
    render(<Toaster />)
    act(() => void toast.error('Workshop đã được người khác sửa.', { action: { label: 'Tải lại', onClick } }))
    fireEvent.click(screen.getByRole('button', { name: 'Tải lại' }))
    expect(onClick).toHaveBeenCalledTimes(1)
    advance(TOAST_EXIT_MS + 10)
    expect(screen.queryByText('Workshop đã được người khác sửa.')).not.toBeInTheDocument()
  })

  it('keeps at most three open: a burst pushes the oldest out', () => {
    render(<Toaster />)
    act(() => {
      toast.info('một')
      toast.info('hai')
      toast.info('ba')
      toast.info('bốn')
    })
    advance(TOAST_EXIT_MS + 10)
    expect(screen.queryByText('một')).not.toBeInTheDocument()
    ;['hai', 'ba', 'bốn'].forEach((text) => expect(screen.getByText(text)).toBeInTheDocument())
  })

  it('draws its entrance and exit with transitions on transform and opacity, not keyframes', () => {
    render(<Toaster />)
    act(() => void toast.success('Đã lưu.'))
    const { className } = screen.getByText('Đã lưu.').closest('[data-slot="toast"]') as HTMLElement
    expect(className).toContain('transition-[opacity,transform]')
    expect(className).toContain('starting:opacity-0')
    expect(className).toContain('motion-reduce:translate-y-0')
  })
})

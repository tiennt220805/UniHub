import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { POLL_WINDOW_MS, usePollWindow } from '../use-poll-window'

beforeEach(() => vi.useFakeTimers())
afterEach(() => vi.useRealTimers())

describe('usePollWindow', () => {
  it('does not poll while inactive', () => {
    const { result } = renderHook(() => usePollWindow(false))

    expect(result.current).toMatchObject({ polling: false, expired: false })
  })

  it('polls for two minutes, then expires (AC15.2)', () => {
    const { result } = renderHook(() => usePollWindow(true))
    expect(result.current.polling).toBe(true)

    act(() => vi.advanceTimersByTime(POLL_WINDOW_MS - 1))
    expect(result.current).toMatchObject({ polling: true, expired: false })

    act(() => vi.advanceTimersByTime(1))
    expect(result.current).toMatchObject({ polling: false, expired: true })
  })

  it('restart opens a fresh window', () => {
    const { result } = renderHook(() => usePollWindow(true))
    act(() => vi.advanceTimersByTime(POLL_WINDOW_MS))
    expect(result.current.expired).toBe(true)

    act(() => result.current.restart())

    expect(result.current).toMatchObject({ polling: true, expired: false })
    act(() => vi.advanceTimersByTime(POLL_WINDOW_MS))
    expect(result.current.expired).toBe(true)
  })

  it('stops as soon as it is no longer active, without expiring', () => {
    const { result, rerender } = renderHook(({ active }) => usePollWindow(active), {
      initialProps: { active: true },
    })

    rerender({ active: false })
    act(() => vi.advanceTimersByTime(POLL_WINDOW_MS * 2))

    expect(result.current).toMatchObject({ polling: false, expired: false })
  })

  it('opens a fresh window each time it becomes active again, even after an expired one', () => {
    const { result, rerender } = renderHook(({ active }) => usePollWindow(active), {
      initialProps: { active: true },
    })
    act(() => vi.advanceTimersByTime(POLL_WINDOW_MS))
    expect(result.current.expired).toBe(true)

    // The thing being followed settled (e.g. a failed payment) and a new one began.
    rerender({ active: false })
    rerender({ active: true })

    expect(result.current).toMatchObject({ polling: true, expired: false })
    act(() => vi.advanceTimersByTime(POLL_WINDOW_MS - 1))
    expect(result.current.polling).toBe(true)
    act(() => vi.advanceTimersByTime(1))
    expect(result.current.expired).toBe(true)
  })
})

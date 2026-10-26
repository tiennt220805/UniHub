import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useCountdown } from '../use-countdown'

const NOW = new Date('2026-10-26T01:00:00Z')

beforeEach(() => vi.useFakeTimers({ now: NOW }))
afterEach(() => vi.useRealTimers())

describe('useCountdown', () => {
  it('is null without a target', () => {
    expect(renderHook(() => useCountdown(null)).result.current).toBeNull()
    expect(renderHook(() => useCountdown(undefined)).result.current).toBeNull()
  })

  it('counts down the whole seconds left, once a second', () => {
    const { result } = renderHook(() => useCountdown('2026-10-26T01:00:10Z'))
    expect(result.current).toBe(10)

    act(() => vi.advanceTimersByTime(3_000))

    expect(result.current).toBe(7)
  })

  it('stops at zero and stays there once the time has passed', () => {
    const { result } = renderHook(() => useCountdown('2026-10-26T01:00:02Z'))

    act(() => vi.advanceTimersByTime(10_000))

    expect(result.current).toBe(0)
  })

  it('is already zero for a target in the past', () => {
    expect(renderHook(() => useCountdown('2026-10-26T00:59:00Z')).result.current).toBe(0)
  })

  it('catches up when the target arrives late', () => {
    const { result, rerender } = renderHook(({ until }) => useCountdown(until), {
      initialProps: { until: null as string | null },
    })
    act(() => vi.advanceTimersByTime(30_000)) // the hook has been mounted for 30 s without a target

    rerender({ until: '2026-10-26T01:01:00Z' }) // 30 s from now, not 60

    act(() => vi.advanceTimersByTime(0))
    expect(result.current).toBe(30)
  })
})

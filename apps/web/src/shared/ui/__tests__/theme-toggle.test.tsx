import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'

import { useThemeStore } from '@/shared/store/theme-store'
import { ThemeToggle } from '../theme-toggle'

beforeEach(() => {
  window.localStorage.clear()
  document.documentElement.classList.remove('dark')
  useThemeStore.setState({ preference: 'system' })
})

describe('ThemeToggle', () => {
  it('offers light, dark and follow-the-system, with the current one pressed', () => {
    render(<ThemeToggle />)
    expect(screen.getByRole('group', { name: 'Chế độ hiển thị' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Theo hệ thống' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Sáng' })).toHaveAttribute('aria-pressed', 'false')
    expect(screen.getByRole('button', { name: 'Tối' })).toHaveAttribute('aria-pressed', 'false')
  })

  it('choosing dark switches the page and moves the pressed state', async () => {
    render(<ThemeToggle />)
    await userEvent.click(screen.getByRole('button', { name: 'Tối' }))
    expect(document.documentElement).toHaveClass('dark')
    expect(screen.getByRole('button', { name: 'Tối' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Theo hệ thống' })).toHaveAttribute('aria-pressed', 'false')

    await userEvent.click(screen.getByRole('button', { name: 'Sáng' }))
    expect(document.documentElement).not.toHaveClass('dark')
  })
})

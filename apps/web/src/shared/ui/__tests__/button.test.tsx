import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { Button } from '../button'

describe('Button', () => {
  it('renders as a button role and is queryable via RTL', () => {
    render(<Button>Click me</Button>)

    const button = screen.getByRole('button', { name: 'Click me' })
    expect(button).toBeInTheDocument()
    expect(button.className).toContain('bg-primary')
  })

  it('hovers to primary-hover, never to a translucent primary that fails 4.5:1 with white text', () => {
    render(<Button>Đăng ký</Button>)
    const { className } = screen.getByRole('button')
    expect(className).toContain('hover:bg-primary-hover')
    expect(className).not.toContain('hover:bg-primary/')
  })

  it('has a solid focus ring: the half-opacity one falls under 3:1', () => {
    render(<Button>Lưu</Button>)
    const { className } = screen.getByRole('button')
    expect(className).toContain('focus-visible:ring-ring')
    expect(className).not.toMatch(/ring-ring\/\d+/)
  })

  it('scales on press only when motion is allowed, and animates it', () => {
    render(<Button>Lưu</Button>)
    const { className } = screen.getByRole('button')
    expect(className).toContain('motion-safe:active:scale-[0.97]')
    // Tailwind v4's scale-* is the `scale` property, not `transform`: it must be in the transition list or the press is instant.
    expect(className).toMatch(/transition-\[[^\]]*\bscale\b[^\]]*\]/)
  })

  it.each([
    ['outline', 'border-input'],
    ['secondary', 'bg-secondary'],
    ['ghost', 'hover:bg-accent'],
    ['destructive', 'text-destructive'],
    ['link', 'text-primary-text'],
  ] as const)('the %s variant carries %s', (variant, expected) => {
    render(<Button variant={variant}>x</Button>)
    expect(screen.getByRole('button').className).toContain(expected)
  })

  it('the large size is the 44 px touch target', () => {
    render(<Button size="lg">Thanh toán</Button>)
    expect(screen.getByRole('button').className).toContain('h-11')
  })

  it('does not act while disabled and acts once when enabled', async () => {
    const onClick = vi.fn()
    const { rerender } = render(<Button disabled onClick={onClick}>Gửi</Button>)
    await userEvent.click(screen.getByRole('button'))
    expect(onClick).not.toHaveBeenCalled()
    rerender(<Button onClick={onClick}>Gửi</Button>)
    await userEvent.click(screen.getByRole('button'))
    expect(onClick).toHaveBeenCalledTimes(1)
  })

  it('can render as a link without losing its look', () => {
    render(
      <Button asChild>
        <a href="/workshops">Xem workshop</a>
      </Button>,
    )
    const link = screen.getByRole('link', { name: 'Xem workshop' })
    expect(link.className).toContain('bg-primary')
  })
})

import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { Badge } from '../badge'
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from '../card'
import { Skeleton } from '../skeleton'
import { EmptyState, ErrorState } from '../state-panel'

describe('Badge', () => {
  it.each([
    ['success', 'bg-primary-soft'],
    ['warning', 'bg-warning-soft'],
    ['destructive', 'bg-destructive-soft'],
    ['info', 'bg-info-soft'],
    ['muted', 'bg-muted'],
    ['highlight', 'bg-highlight'],
  ] as const)('the %s tone paints %s', (tone, expected) => {
    render(<Badge tone={tone}>Nhãn</Badge>)
    const badge = screen.getByText('Nhãn')
    expect(badge.className).toContain(expected)
    expect(badge).toHaveAttribute('data-tone', tone)
  })

  it('defaults to muted and keeps the words the feature passes in, with its icon', () => {
    render(
      <Badge>
        <svg aria-hidden="true" data-testid="icon" />
        Nháp
      </Badge>,
    )
    expect(screen.getByText('Nháp')).toHaveAttribute('data-tone', 'muted')
    expect(screen.getByTestId('icon')).toBeInTheDocument()
  })
})

describe('Card', () => {
  it('composes a header, a title, a description, content and a footer', () => {
    render(
      <Card>
        <CardHeader>
          <CardTitle>Docker cho người mới bắt đầu</CardTitle>
          <CardDescription>ThS. Trần Minh Khoa</CardDescription>
        </CardHeader>
        <CardContent>Phòng A2.03</CardContent>
        <CardFooter>Có phí</CardFooter>
      </Card>,
    )
    expect(screen.getByRole('heading', { level: 3, name: 'Docker cho người mới bắt đầu' })).toBeInTheDocument()
    expect(screen.getByText('ThS. Trần Minh Khoa')).toBeInTheDocument()
    expect(screen.getByText('Phòng A2.03')).toBeInTheDocument()
    expect(screen.getByText('Có phí')).toBeInTheDocument()
  })

  it('draws a border, and drops the shadow in dark mode', () => {
    const { container } = render(<Card>x</Card>)
    const card = container.firstElementChild as HTMLElement
    expect(card.className).toContain('border-border')
    expect(card.className).toContain('shadow-sm')
    expect(card.className).toContain('dark:shadow-none')
  })

  it('the title level and the card element can be chosen', () => {
    render(
      <Card asChild>
        <article aria-label="Workshop">
          <CardTitle asChild>
            <h2>Tiêu đề</h2>
          </CardTitle>
        </article>
      </Card>,
    )
    expect(screen.getByRole('article', { name: 'Workshop' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: 'Tiêu đề' })).toBeInTheDocument()
  })
})

describe('Skeleton', () => {
  it('is decorative and pulses only when motion is allowed', () => {
    const { container } = render(<Skeleton className="h-4 w-24" />)
    const skeleton = container.firstElementChild as HTMLElement
    expect(skeleton).toHaveAttribute('aria-hidden', 'true')
    expect(skeleton.className).toContain('motion-safe:animate-pulse')
    expect(skeleton.className).toContain('h-4')
  })
})

describe('EmptyState and ErrorState', () => {
  it('an empty state shows what the feature says and offers the way out', () => {
    render(
      <EmptyState
        title="Không có workshop nào khớp"
        description="Thử bỏ bớt bộ lọc."
        action={<button type="button">Xóa bộ lọc</button>}
      />,
    )
    expect(screen.getByRole('heading', { name: 'Không có workshop nào khớp' })).toBeInTheDocument()
    expect(screen.getByText('Thử bỏ bớt bộ lọc.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Xóa bộ lọc' })).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('an error state is announced', () => {
    render(<ErrorState title="Hệ thống đang bận" description="Chưa tải được dữ liệu (503)." action={<button type="button">Thử lại</button>} />)
    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent('Hệ thống đang bận')
    expect(screen.getByRole('button', { name: 'Thử lại' })).toBeInTheDocument()
  })

  it('has no description or action unless given, and hides the icon from screen readers', () => {
    const { container } = render(<EmptyState title="Trống" />)
    expect(container.querySelector('p')).toBeNull()
    expect(container.querySelector('[aria-hidden="true"]')).not.toBeNull()
  })
})

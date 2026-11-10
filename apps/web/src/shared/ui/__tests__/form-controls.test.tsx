import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'

import { Field, FieldError, FieldHint, Label } from '../field'
import { Input, Textarea } from '../input'

describe('Input and Textarea', () => {
  it('is 16 px at every breakpoint, so iOS does not zoom the page on focus', () => {
    render(<Input aria-label="Tìm" />)
    const { className } = screen.getByRole('textbox')
    expect(className).toContain('text-base')
    expect(className).not.toMatch(/\b(md|sm|lg):text-(sm|xs)\b/)
  })

  it('draws its edge with the `input` token (3:1), not the decorative `border`', () => {
    render(<Input aria-label="Tìm" />)
    const { className } = screen.getByRole('textbox')
    expect(className).toContain('border-input')
    expect(className).toContain('focus-visible:outline-ring')
    expect(className).not.toMatch(/outline-ring\/\d+/)
  })

  it('takes typed text and passes native attributes through', async () => {
    render(<Input aria-label="Tên" placeholder="Tên workshop" maxLength={10} />)
    const input = screen.getByRole('textbox', { name: 'Tên' })
    await userEvent.type(input, 'Docker')
    expect(input).toHaveValue('Docker')
    expect(input).toHaveAttribute('maxlength', '10')
  })

  it('is the same control for the native date and datetime-local inputs (D134)', () => {
    render(
      <>
        <Input type="date" aria-label="Từ ngày" defaultValue="2026-11-12" />
        <Input type="datetime-local" aria-label="Bắt đầu" defaultValue="2026-11-12T14:00" />
      </>,
    )
    expect(screen.getByLabelText('Từ ngày')).toHaveValue('2026-11-12')
    expect(screen.getByLabelText('Bắt đầu')).toHaveValue('2026-11-12T14:00')
    expect(screen.getByLabelText('Từ ngày').className).toContain('border-input')
  })

  it('marks an invalid control for assistive technology and for its red edge', () => {
    render(<Input aria-label="Sức chứa" aria-invalid="true" />)
    const input = screen.getByRole('textbox')
    expect(input).toBeInvalid()
    expect(input.className).toContain('aria-invalid:border-destructive')
  })

  it('Textarea shares the look and is multi-line', () => {
    render(<Textarea aria-label="Mô tả" />)
    const area = screen.getByRole('textbox', { name: 'Mô tả' })
    expect(area.tagName).toBe('TEXTAREA')
    expect(area.className).toContain('border-input')
  })
})

describe('Field, Label, FieldHint and FieldError', () => {
  it('connects a label, a control and an error the way a form does', () => {
    render(
      <Field>
        <Label htmlFor="cap">Sức chứa</Label>
        <Input id="cap" aria-describedby="cap-err" aria-invalid="true" />
        <FieldError id="cap-err">Sức chứa phải lớn hơn 0.</FieldError>
        <FieldHint>Số chỗ tối đa.</FieldHint>
      </Field>,
    )
    const input = screen.getByLabelText('Sức chứa')
    expect(input).toHaveAccessibleDescription('Sức chứa phải lớn hơn 0.')
    expect(screen.getByText('Số chỗ tối đa.')).toBeInTheDocument()
  })

  it('an error is words plus an icon, and the icon is hidden from screen readers', () => {
    const { container } = render(<FieldError>Thiếu tiêu đề.</FieldError>)
    expect(screen.getByText('Thiếu tiêu đề.')).toBeInTheDocument()
    expect(container.querySelector('svg')).toHaveAttribute('aria-hidden', 'true')
  })
})

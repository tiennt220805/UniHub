import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '../alert-dialog'
import { Button } from '../button'
import { Dialog, DialogContent, DialogDescription, DialogTitle, DialogTrigger } from '../dialog'
import { Sheet, SheetContent, SheetDescription, SheetTitle, SheetTrigger } from '../sheet'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '../tabs'

afterEach(() => document.body.removeAttribute('style'))

describe('Dialog', () => {
  it('opens, locks the page scroll with the CSP-safe lock, and closes with its Vietnamese close button', async () => {
    render(
      <Dialog>
        <DialogTrigger asChild>
          <Button>Mở</Button>
        </DialogTrigger>
        <DialogContent>
          <DialogTitle>Hộp thoại</DialogTitle>
          <DialogDescription>Nội dung.</DialogDescription>
        </DialogContent>
      </Dialog>,
    )
    await userEvent.click(screen.getByRole('button', { name: 'Mở' }))
    expect(screen.getByRole('dialog', { name: 'Hộp thoại' })).toBeInTheDocument()
    expect(document.body.style.overflow).toBe('hidden')
    await userEvent.click(screen.getByRole('button', { name: 'Đóng' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(document.body.style.overflow).toBe('')
  })

  it('closes on Escape', async () => {
    render(
      <Dialog defaultOpen>
        <DialogContent>
          <DialogTitle>Hộp thoại</DialogTitle>
          <DialogDescription>Nội dung.</DialogDescription>
        </DialogContent>
      </Dialog>,
    )
    await userEvent.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})

describe('AlertDialog', () => {
  function Confirm({ onConfirm }: { onConfirm: () => void }) {
    return (
      <AlertDialog>
        <AlertDialogTrigger asChild>
          <Button variant="destructive">Hủy workshop</Button>
        </AlertDialogTrigger>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Hủy workshop này?</AlertDialogTitle>
            <AlertDialogDescription>Sinh viên đã đăng ký sẽ được thông báo.</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Giữ lại</AlertDialogCancel>
            <AlertDialogAction variant="destructive" onClick={onConfirm}>
              Hủy workshop
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    )
  }

  it('is an alertdialog that locks the scroll, and Cancel closes it without acting', async () => {
    const onConfirm = vi.fn()
    render(<Confirm onConfirm={onConfirm} />)
    await userEvent.click(screen.getByRole('button', { name: 'Hủy workshop' }))
    const dialog = screen.getByRole('alertdialog', { name: 'Hủy workshop này?' })
    expect(dialog).toHaveAccessibleDescription('Sinh viên đã đăng ký sẽ được thông báo.')
    expect(document.body.style.overflow).toBe('hidden')
    await userEvent.click(screen.getByRole('button', { name: 'Giữ lại' }))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(onConfirm).not.toHaveBeenCalled()
    expect(document.body.style.overflow).toBe('')
  })

  it('the action runs once and closes it', async () => {
    const onConfirm = vi.fn()
    render(<Confirm onConfirm={onConfirm} />)
    await userEvent.click(screen.getByRole('button', { name: 'Hủy workshop' }))
    await userEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Hủy workshop' }))
    expect(onConfirm).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  })
})

describe('Sheet', () => {
  it('opens as a dialog, locks the scroll and closes with Đóng', async () => {
    render(
      <Sheet>
        <SheetTrigger asChild>
          <Button>Menu</Button>
        </SheetTrigger>
        <SheetContent>
          <SheetTitle>Điều hướng</SheetTitle>
          <SheetDescription>Các mục chính.</SheetDescription>
        </SheetContent>
      </Sheet>,
    )
    await userEvent.click(screen.getByRole('button', { name: 'Menu' }))
    expect(screen.getByRole('dialog', { name: 'Điều hướng' })).toBeInTheDocument()
    expect(document.body.style.overflow).toBe('hidden')
    await userEvent.click(screen.getByRole('button', { name: 'Đóng' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})

describe('Tabs', () => {
  it('switches on click and on the arrow keys, and says which one is selected', async () => {
    render(
      <Tabs defaultValue="draft">
        <TabsList aria-label="Trạng thái">
          <TabsTrigger value="draft">Nháp</TabsTrigger>
          <TabsTrigger value="published">Đã công bố</TabsTrigger>
        </TabsList>
        <TabsContent value="draft">Danh sách nháp</TabsContent>
        <TabsContent value="published">Danh sách đã công bố</TabsContent>
      </Tabs>,
    )
    expect(screen.getByRole('tab', { name: 'Nháp' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByText('Danh sách nháp')).toBeVisible()
    await userEvent.click(screen.getByRole('tab', { name: 'Đã công bố' }))
    expect(screen.getByRole('tab', { name: 'Đã công bố' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByText('Danh sách đã công bố')).toBeVisible()
    screen.getByRole('tab', { name: 'Đã công bố' }).focus()
    await userEvent.keyboard('{ArrowLeft}')
    expect(screen.getByRole('tab', { name: 'Nháp' })).toHaveFocus()
  })

  it('tells the active tab apart by more than colour, and has a solid focus ring', () => {
    render(
      <Tabs defaultValue="a">
        <TabsList>
          <TabsTrigger value="a">A</TabsTrigger>
        </TabsList>
      </Tabs>,
    )
    const { className } = screen.getByRole('tab', { name: 'A' })
    expect(className).toContain('data-[state=active]:border-primary')
    expect(className).toContain('data-[state=active]:font-semibold')
    expect(className).toContain('focus-visible:ring-ring')
    expect(className).not.toMatch(/ring-ring\/\d+/)
  })
})

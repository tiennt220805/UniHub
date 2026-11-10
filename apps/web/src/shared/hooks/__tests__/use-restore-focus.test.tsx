import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useRef, useState } from 'react'
import { describe, expect, it } from 'vitest'

import { AlertDialog, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogTitle } from '@/shared/ui/alert-dialog'
import { useRestoreFocus } from '../use-restore-focus'

/** A dialog opened by setting `open`, with no Radix trigger: the case the hook is for. */
function Controlled({ withFallback = false }: { withFallback?: boolean }) {
  const [open, setOpen] = useState(false)
  const [menuOpen, setMenuOpen] = useState(false)
  const fallback = useRef<HTMLButtonElement>(null)
  const onCloseAutoFocus = useRestoreFocus(open, withFallback ? fallback : undefined)
  return (
    <>
      <button ref={fallback}>Mở menu</button>
      {menuOpen && (
        // Stands for a menu item: it is the focused element when the dialog opens, and it is gone when the dialog closes.
        <button
          onClick={() => {
            setMenuOpen(false)
            setOpen(true)
          }}
        >
          Mục menu
        </button>
      )}
      {!withFallback && <button onClick={() => setOpen(true)}>Mở hộp</button>}
      {withFallback && <button onClick={() => setMenuOpen(true)}>Hiện mục</button>}
      <AlertDialog open={open} onOpenChange={setOpen}>
        <AlertDialogContent onCloseAutoFocus={onCloseAutoFocus}>
          <AlertDialogTitle>Hộp</AlertDialogTitle>
          <AlertDialogDescription>Nội dung</AlertDialogDescription>
          <AlertDialogCancel>Đóng</AlertDialogCancel>
        </AlertDialogContent>
      </AlertDialog>
    </>
  )
}

describe('useRestoreFocus', () => {
  it('on Escape puts focus back on the opener, not on <body>', async () => {
    const user = userEvent.setup()
    render(<Controlled />)
    const opener = screen.getByRole('button', { name: 'Mở hộp' })
    opener.focus()
    await user.keyboard('{Enter}')
    await screen.findByRole('alertdialog')
    await user.keyboard('{Escape}')
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(opener).toHaveFocus()
  })

  it('uses the fallback when what opened the dialog is gone (a menu item)', async () => {
    const user = userEvent.setup()
    render(<Controlled withFallback />)
    await user.click(screen.getByRole('button', { name: 'Hiện mục' }))
    await user.click(screen.getByRole('button', { name: 'Mục menu' }))
    await screen.findByRole('alertdialog')
    await user.keyboard('{Escape}')
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(screen.getByRole('button', { name: 'Mở menu' })).toHaveFocus()
  })
})

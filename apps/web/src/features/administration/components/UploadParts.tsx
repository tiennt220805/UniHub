import { CircleAlert, FileText, Image as ImageIcon, TriangleAlert, Upload } from 'lucide-react'
import { useRef, useState, type ReactNode } from 'react'

import { cn } from '@/shared/lib/utils'
import { Button } from '@/shared/ui/button'
import { Notice } from '@/shared/ui/notice'
import type { FailureView } from '../model/upload-messages'

/** A place to drop a file or pick one. The hidden input is the real control; the button opens it (keyboard and touch work). */
export function DropZone({
  accept,
  icon,
  title,
  hint,
  buttonLabel,
  inputLabel,
  disabled,
  invalid,
  onFile,
}: {
  accept: string
  icon: 'image' | 'file'
  title: string
  hint?: ReactNode
  buttonLabel: string
  /** The accessible name of the file input itself. */
  inputLabel: string
  disabled?: boolean
  invalid?: boolean
  onFile: (file: File) => void
}) {
  const input = useRef<HTMLInputElement>(null)
  const [over, setOver] = useState(false)
  const Icon = icon === 'image' ? ImageIcon : FileText
  return (
    <div
      onDragOver={(event) => {
        if (disabled) return
        event.preventDefault()
        setOver(true)
      }}
      onDragLeave={() => setOver(false)}
      onDrop={(event) => {
        event.preventDefault()
        setOver(false)
        const file = event.dataTransfer.files[0]
        if (!disabled && file) onFile(file)
      }}
      className={cn(
        'grid justify-items-center gap-1.5 rounded-xl border-[1.5px] border-dashed border-input bg-background px-3 py-5 text-center',
        over && 'border-primary bg-accent',
        invalid && 'border-destructive',
      )}
    >
      <Icon aria-hidden="true" className="size-6 text-muted-foreground" />
      <b className="text-sm font-semibold">{title}</b>
      {hint && <span className="text-xs text-muted-foreground">{hint}</span>}
      <input
        ref={input}
        type="file"
        accept={accept}
        aria-label={inputLabel}
        tabIndex={-1}
        className="sr-only"
        disabled={disabled}
        onChange={(event) => {
          const file = event.target.files?.[0]
          event.target.value = '' // the same file can be chosen again
          if (file) onFile(file)
        }}
      />
      <Button type="button" variant="outline" size="sm" disabled={disabled} onClick={() => input.current?.click()}>
        {buttonLabel}
      </Button>
    </div>
  )
}

export function FileRow({ name, detail, icon }: { name: string; detail: string; icon: 'image' | 'file' }) {
  const Icon = icon === 'image' ? ImageIcon : FileText
  return (
    <div className="flex items-center gap-2.5 rounded-lg border border-border px-3 py-2.5">
      <Icon aria-hidden="true" className="size-5 shrink-0 text-muted-foreground" />
      <div className="min-w-0">
        <b className="block text-sm leading-[18px] font-semibold [overflow-wrap:anywhere]">{name}</b>
        <small className="text-xs text-muted-foreground">{detail}</small>
      </div>
    </div>
  )
}

/** Progress of the bytes on their way. The bar is decorative; the role carries the number. */
export function UploadProgress({ label, fraction, step }: { label: string; fraction: number; step: 'requesting' | 'sending' | 'confirming' }) {
  const percent = Math.round(fraction * 100)
  return (
    <div className="grid gap-1.5">
      <div
        role="progressbar"
        aria-label={label}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={percent}
        className="h-2 overflow-hidden rounded-full bg-seat-track"
      >
        <div
          className="h-full origin-left bg-primary transition-transform duration-150 ease-out-strong motion-reduce:transition-none"
          style={{ transform: `scaleX(${percent / 100})` }}
        />
      </div>
      <div className="flex justify-between text-sm text-muted-foreground tabular-nums">
        <span>{step === 'confirming' ? 'Đang xác nhận…' : step === 'requesting' ? 'Đang chuẩn bị…' : 'Đang tải lên…'}</span>
        <span>{percent}%</span>
      </div>
    </div>
  )
}

export function FailureNotice({ view, onRetry, onChoose }: { view: FailureView; onRetry: () => void; onChoose: () => void }) {
  const Icon = view.tone === 'warning' ? TriangleAlert : CircleAlert
  return (
    <Notice tone={view.tone} icon={Icon} role="alert">
      <b className="font-semibold">{view.title}</b>
      {view.body && <span className="mt-0.5 block">{view.body}</span>}
      {view.action === 'retry' && (
        <Button type="button" variant="outline" size="sm" className="mt-2" onClick={onRetry}>
          {view.retryLabel ?? 'Thử lại'}
        </Button>
      )}
      {view.action === 'choose' && (
        <Button type="button" variant="outline" size="sm" className="mt-2" onClick={onChoose}>
          Chọn file khác
        </Button>
      )}
    </Notice>
  )
}

export { Upload as UploadIcon }

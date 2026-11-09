import { CircleCheck, Upload } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'

import { Button } from '@/shared/ui/button'
import { Notice } from '@/shared/ui/notice'
import { useAssetUpload } from '../hooks/use-asset-upload'
import { confirmCoverImageUpload, requestCoverImageUploadUrl } from '../api/uploads'
import { checkCoverFile, COVER_TYPES, megabytes } from '../model/upload-rules'
import { describeFailure } from '../model/upload-messages'
import { DropZone, FailureNotice, FileRow, UploadProgress } from './UploadParts'

type Props = {
  workshopId: string
  /** The path the cover is served from (`/media/…`), or null while there is none and the cards use the generated one. */
  coverImageUrl: string | null
  /** WORKSHOP_CREATE or WORKSHOP_EDIT; false for a cancelled or completed workshop too (the backend answers 409). */
  canUpload: boolean
  /** The server accepted a new cover: the route reads the workshop again. */
  onUploaded: () => void
}

/** The preview of a chosen file: a `data:` URL, which the CSP's `img-src 'self' data:` already allows (F5). */
function usePreview(file: File | null): string | null {
  const [preview, setPreview] = useState<{ file: File; url: string } | null>(null)
  useEffect(() => {
    if (!file) return
    let current = true
    const reader = new FileReader()
    reader.onload = () => {
      if (current && typeof reader.result === 'string') setPreview({ file, url: reader.result })
    }
    reader.readAsDataURL(file)
    return () => {
      current = false
      if (reader.readyState === 1) reader.abort()
    }
  }, [file])
  return file && preview?.file === file ? preview.url : null
}

/** S13: the cover image of a workshop (D114, D162). Chosen, previewed, sent straight to the storage, then confirmed. */
export function CoverImageCard({ workshopId, coverImageUrl, canUpload, onUploaded }: Props) {
  const upload = useAssetUpload({
    requestUrl: (file, contentType) => requestCoverImageUploadUrl(workshopId, { contentType, contentLength: file.size }),
    confirm: () => confirmCoverImageUpload(workshopId),
  })
  const { state } = upload
  const chosenFile = state.phase === 'chosen' || state.phase === 'uploading' ? state.file : state.phase === 'failed' ? state.file : null
  const preview = usePreview(chosenFile)
  const reported = useRef<unknown>(null)
  useEffect(() => {
    if (state.phase === 'done' && reported.current !== state.result) {
      reported.current = state.result
      onUploaded()
    }
  }, [state, onUploaded])

  const pick = (file: File) => {
    const check = checkCoverFile(file)
    if (check.ok) upload.choose(file, check.contentType)
    else upload.fail({ kind: 'client', message: check.message })
  }
  const shownUrl = state.phase === 'done' ? state.result.coverImageUrl : coverImageUrl
  const accept = COVER_TYPES.join(',')
  const busy = state.phase === 'uploading'

  return (
    <section aria-label="Ảnh bìa" className="grid gap-4 rounded-2xl border border-border bg-card p-5">
      <h2 className="text-base leading-6 font-semibold">Ảnh bìa</h2>

      {(state.phase === 'chosen' || state.phase === 'uploading') && preview && (
        <img src={preview} alt="Xem trước ảnh đã chọn" className={`aspect-video w-full rounded-lg border border-border object-cover ${busy ? 'opacity-60' : ''}`} />
      )}
      {state.phase === 'idle' && shownUrl && (
        <img src={shownUrl} alt="Ảnh bìa hiện tại" className="aspect-video w-full rounded-lg border border-border object-cover" />
      )}
      {state.phase === 'done' && (
        <>
          <img src={state.result.coverImageUrl} alt="Ảnh bìa hiện tại" className="aspect-video w-full rounded-lg border border-border object-cover" />
          <Notice tone="success" icon={CircleCheck} role="status">
            Đã cập nhật ảnh bìa.
          </Notice>
        </>
      )}

      {state.phase === 'chosen' && (
        <>
          <FileRow icon="image" name={state.file.name} detail={`${megabytes(state.file.size)} · ${state.contentType.replace('image/', '').toUpperCase()}`} />
          <div className="flex flex-wrap gap-2">
            <Button type="button" onClick={() => void upload.upload(state.file, state.contentType)}>
              <Upload aria-hidden="true" />
              Tải lên
            </Button>
            <Button type="button" variant="ghost" onClick={upload.reset}>
              Chọn ảnh khác
            </Button>
          </div>
        </>
      )}
      {state.phase === 'uploading' && (
        <>
          <UploadProgress label="Tiến trình tải ảnh bìa" fraction={state.fraction} step={state.step} />
          {state.step === 'sending' && (
            <Button type="button" variant="outline" size="sm" className="justify-self-start" onClick={upload.stop}>
              Dừng
            </Button>
          )}
        </>
      )}
      {state.phase === 'failed' && (
        <FailureNotice view={describeFailure(state.failure, 'cover')} onRetry={upload.retry} onChoose={upload.reset} />
      )}

      {canUpload && state.phase === 'idle' && !shownUrl && (
        <DropZone
          accept={accept}
          icon="image"
          title="Kéo ảnh vào đây"
          hint={
            <>
              JPEG, PNG hoặc WebP, tối đa 2 MB. Khuyến nghị tỉ lệ 16:9.
              <br />
              Chưa có ảnh bìa thì thẻ của sinh viên dùng ảnh tự sinh theo mã workshop.
            </>
          }
          buttonLabel="Chọn ảnh"
          inputLabel="Chọn tệp ảnh bìa"
          onFile={pick}
        />
      )}
      {canUpload && (state.phase === 'done' || (state.phase === 'idle' && shownUrl)) && (
        <ChangeButton accept={accept} label="Đổi ảnh bìa" inputLabel="Chọn tệp ảnh bìa mới" onFile={pick} />
      )}
      {canUpload && state.phase === 'failed' && state.failure.kind === 'client' && (
        <DropZone
          accept={accept}
          icon="image"
          title="Kéo ảnh vào đây"
          buttonLabel="Chọn ảnh"
          inputLabel="Chọn tệp ảnh bìa"
          invalid
          onFile={pick}
        />
      )}
      {!canUpload && state.phase === 'idle' && !shownUrl && (
        <p className="text-sm text-muted-foreground">Chưa có ảnh bìa. Thẻ của sinh viên dùng ảnh tự sinh.</p>
      )}
    </section>
  )
}

function ChangeButton({ accept, label, inputLabel, onFile }: { accept: string; label: string; inputLabel: string; onFile: (file: File) => void }) {
  const input = useRef<HTMLInputElement>(null)
  return (
    <>
      <input
        ref={input}
        type="file"
        accept={accept}
        aria-label={inputLabel}
        tabIndex={-1}
        className="sr-only"
        onChange={(event) => {
          const file = event.target.files?.[0]
          event.target.value = ''
          if (file) onFile(file)
        }}
      />
      <Button type="button" variant="outline" onClick={() => input.current?.click()}>
        {label}
      </Button>
    </>
  )
}

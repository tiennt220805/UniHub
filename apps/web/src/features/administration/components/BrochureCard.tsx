import { CircleCheck, Info, RefreshCw, Upload } from 'lucide-react'
import { useEffect, useRef } from 'react'

import { Button } from '@/shared/ui/button'
import { Notice } from '@/shared/ui/notice'
import { useAssetUpload } from '../hooks/use-asset-upload'
import { confirmBrochureUpload, requestBrochureUploadUrl } from '../api/uploads'
import { BROCHURE_TYPE, checkBrochureFile, megabytes } from '../model/upload-rules'
import { describeFailure } from '../model/upload-messages'
import { DropZone, FailureNotice, FileRow, UploadProgress } from './UploadParts'

type Props = {
  workshopId: string
  hasBrochure: boolean
  /** The summary the AI service wrote, or null: "not yet" and "the brochure was rejected" look the same (D142). */
  aiSummary: string | null
  canUpload: boolean
  /** A new brochure was accepted: the route reads the workshop again. */
  onUploaded: () => void
  /** "Kiểm tra lại": reads the workshop again, once, by the user's request (no polling, D163). */
  onRecheck: () => void
  rechecking: boolean
}

/** S14: the brochure PDF and what became of it (D163). The summary has two states only: there is one, or there is not yet. */
export function BrochureCard({ workshopId, hasBrochure, aiSummary, canUpload, onUploaded, onRecheck, rechecking }: Props) {
  const upload = useAssetUpload({
    requestUrl: () => requestBrochureUploadUrl(workshopId),
    confirm: () => confirmBrochureUpload(workshopId),
  })
  const { state } = upload
  const reported = useRef<unknown>(null)
  useEffect(() => {
    if (state.phase === 'done' && reported.current !== state.result) {
      reported.current = state.result
      onUploaded()
    }
  }, [state, onUploaded])

  const pick = (file: File) => {
    const check = checkBrochureFile(file)
    if (check.ok) upload.choose(file, BROCHURE_TYPE)
    else upload.fail({ kind: 'client', message: check.message })
  }
  const received = hasBrochure || state.phase === 'done'
  const picking = state.phase === 'idle' || (state.phase === 'failed' && state.failure.kind === 'client')

  return (
    <section aria-label="Brochure" className="grid gap-4 rounded-2xl border border-border bg-card p-5">
      <h2 className="text-base leading-6 font-semibold">Brochure</h2>

      {state.phase === 'chosen' && (
        <>
          <FileRow icon="file" name={state.file.name} detail={`${megabytes(state.file.size)} · PDF`} />
          <div className="flex flex-wrap gap-2">
            <Button type="button" onClick={() => void upload.upload(state.file, state.contentType)}>
              <Upload aria-hidden="true" />
              Tải lên
            </Button>
            <Button type="button" variant="ghost" onClick={upload.reset}>
              Chọn file khác
            </Button>
          </div>
        </>
      )}
      {state.phase === 'uploading' && (
        <>
          <FileRow icon="file" name={state.file.name} detail={`${megabytes(state.file.size)} · PDF`} />
          <UploadProgress label="Tiến trình tải brochure" fraction={state.fraction} step={state.step} />
          {state.step === 'sending' && (
            <Button type="button" variant="outline" size="sm" className="justify-self-start" onClick={upload.stop}>
              Dừng
            </Button>
          )}
        </>
      )}
      {state.phase === 'failed' && (
        <FailureNotice view={describeFailure(state.failure, 'brochure')} onRetry={upload.retry} onChoose={upload.reset} />
      )}

      {received && (state.phase === 'idle' || state.phase === 'done') && (
        <>
          <Notice tone="success" icon={CircleCheck} role={state.phase === 'done' ? 'status' : undefined}>
            {state.phase === 'done' ? 'Đã nhận brochure.' : 'Đã tải brochure.'}
          </Notice>
          <div className="grid gap-1.5">
            <span className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">Tóm tắt AI</span>
            {aiSummary ? (
              <p className="border-l-[3px] border-primary pl-3 text-sm leading-5">{aiSummary}</p>
            ) : (
              <>
                <Notice tone="muted" icon={Info}>
                  <b className="font-semibold">Chưa có tóm tắt.</b> Có thể hệ thống đang tạo, hoặc không đọc được brochure. Kiểm tra lại sau ít phút.
                </Notice>
                <Button type="button" variant="outline" size="sm" className="justify-self-start" onClick={onRecheck} disabled={rechecking}>
                  <RefreshCw aria-hidden="true" />
                  Kiểm tra lại
                </Button>
              </>
            )}
          </div>
        </>
      )}

      {canUpload && picking && !received && (
        <DropZone
          accept={BROCHURE_TYPE}
          icon="file"
          title="Kéo file PDF vào đây"
          buttonLabel="Chọn file PDF"
          inputLabel="Chọn tệp brochure PDF"
          invalid={state.phase === 'failed'}
          onFile={pick}
        />
      )}
      {canUpload && received && (state.phase === 'idle' || state.phase === 'done') && (
        <ReplaceBrochure onFile={pick} />
      )}
      {!canUpload && !received && <p className="text-sm text-muted-foreground">Chưa có brochure.</p>}
    </section>
  )
}

function ReplaceBrochure({ onFile }: { onFile: (file: File) => void }) {
  const input = useRef<HTMLInputElement>(null)
  return (
    <>
      <input
        ref={input}
        type="file"
        accept={BROCHURE_TYPE}
        aria-label="Chọn tệp brochure PDF mới"
        tabIndex={-1}
        className="sr-only"
        onChange={(event) => {
          const file = event.target.files?.[0]
          event.target.value = ''
          if (file) onFile(file)
        }}
      />
      <Button type="button" variant="outline" onClick={() => input.current?.click()}>
        <Upload aria-hidden="true" />
        Thay brochure
      </Button>
    </>
  )
}

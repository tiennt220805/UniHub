import { useCallback, useEffect, useRef, useState } from 'react'

import { AppError, problemSlug } from '@/shared/api/errors'
import { PutError, putWithProgress } from '@/shared/api/put-with-progress'

// The upload of a cover image or a brochure as one small state machine (D162, D163). Three calls, in order: ask for a URL, send
// the bytes, confirm. Which one failed decides what "try again" means:
//   - the confirm answered 503: the bytes are already in the storage and the issued key is kept, so only the confirm is repeated;
//   - anything else (a refused request, a failed PUT, a link that expired): a new URL is asked for, because the old one is spent
//     or may be.

export type UploadFailure =
  | { kind: 'client'; message: string }
  /** The server read the stored file and refused it (422): the object was deleted, a new URL is needed. */
  | { kind: 'rejected' }
  /** Storage is down at the confirm (503): the same confirm succeeds later. */
  | { kind: 'storage-unavailable' }
  /** The bytes did not arrive: a dropped connection, a link past its ten minutes, or the confirm found nothing (404). */
  | { kind: 'not-stored' }
  /** The workshop is cancelled or completed, or the issued URL was already used or discarded (409). */
  | { kind: 'conflict'; slug: string | null }
  | { kind: 'forbidden' }
  | { kind: 'busy'; retryAfterSeconds: number | null }
  | { kind: 'other' }

export type UploadState<TDone> =
  | { phase: 'idle' }
  | { phase: 'chosen'; file: File; contentType: string }
  | { phase: 'uploading'; file: File; fraction: number; step: 'requesting' | 'sending' | 'confirming' }
  | { phase: 'done'; result: TDone }
  | { phase: 'failed'; failure: UploadFailure; file: File | null; contentType: string | null; confirmOnly: boolean }

type Steps<TDone> = {
  /** Asks for the signed URL for this file. */
  requestUrl: (file: File, contentType: string) => Promise<{ uploadUrl: string }>
  /** Confirms that the file reached the storage. */
  confirm: () => Promise<TDone>
}

function failureOf(error: unknown, stage: 'request' | 'put' | 'confirm'): UploadFailure {
  if (error instanceof PutError) return { kind: 'not-stored' }
  if (!(error instanceof AppError)) return { kind: 'other' }
  switch (error.status) {
    case 404:
      return { kind: 'not-stored' }
    case 409:
      return { kind: 'conflict', slug: problemSlug(error) }
    case 422:
      return { kind: 'rejected' }
    case 503:
      return stage === 'confirm' ? { kind: 'storage-unavailable' } : { kind: 'other' }
    case 403:
      return { kind: 'forbidden' }
    case 429:
      return { kind: 'busy', retryAfterSeconds: error.retryAfterSeconds }
    default:
      return { kind: 'other' }
  }
}

export function useAssetUpload<TDone>({ requestUrl, confirm }: Steps<TDone>) {
  const [state, setState] = useState<UploadState<TDone>>({ phase: 'idle' })
  const abort = useRef<AbortController | null>(null)
  // The steps are the caller's closures over the workshop id: keep the latest without making `run` change every render.
  const steps = useRef({ requestUrl, confirm })
  const latest = useRef(state)
  useEffect(() => {
    steps.current = { requestUrl, confirm }
    latest.current = state
  })
  useEffect(() => () => abort.current?.abort(), [])

  const choose = useCallback((file: File, contentType: string) => setState({ phase: 'chosen', file, contentType }), [])
  const fail = useCallback((failure: UploadFailure, file: File | null = null) => setState({ phase: 'failed', failure, file, contentType: null, confirmOnly: false }), [])
  const reset = useCallback(() => {
    abort.current?.abort()
    setState({ phase: 'idle' })
  }, [])

  const confirmStep = useCallback(async (file: File, contentType: string) => {
    setState({ phase: 'uploading', file, fraction: 1, step: 'confirming' })
    try {
      const result = await steps.current.confirm()
      setState({ phase: 'done', result })
    } catch (error) {
      const failure = failureOf(error, 'confirm')
      setState({ phase: 'failed', failure, file, contentType, confirmOnly: failure.kind === 'storage-unavailable' })
    }
  }, [])

  const upload = useCallback(
    async (file: File, contentType: string) => {
      const controller = new AbortController()
      abort.current = controller
      setState({ phase: 'uploading', file, fraction: 0, step: 'requesting' })
      let uploadUrl: string
      try {
        uploadUrl = (await steps.current.requestUrl(file, contentType)).uploadUrl
      } catch (error) {
        setState({ phase: 'failed', failure: failureOf(error, 'request'), file, contentType, confirmOnly: false })
        return
      }
      setState({ phase: 'uploading', file, fraction: 0, step: 'sending' })
      try {
        await putWithProgress({
          url: uploadUrl,
          body: file,
          contentType,
          signal: controller.signal,
          onProgress: (fraction) => setState({ phase: 'uploading', file, fraction, step: 'sending' }),
        })
      } catch (error) {
        if (error instanceof PutError && error.failure.kind === 'aborted') return // "Dừng" already set the state
        setState({ phase: 'failed', failure: failureOf(error, 'put'), file, contentType, confirmOnly: false })
        return
      }
      await confirmStep(file, contentType)
    },
    [confirmStep],
  )

  /** Repeats the right step after a failure: the confirm alone after a 503, the whole upload with a new URL otherwise. */
  const retry = useCallback(() => {
    const current = latest.current
    if (current.phase !== 'failed' || !current.file || !current.contentType) return
    const { file, contentType, confirmOnly } = current
    void (confirmOnly ? confirmStep(file, contentType) : upload(file, contentType))
  }, [confirmStep, upload])

  const stop = useCallback(() => {
    abort.current?.abort()
    setState({ phase: 'idle' })
  }, [])

  return { state, choose, fail, reset, upload, retry, stop }
}

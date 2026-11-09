// A presigned PUT to object storage (web-coding-standards.md §6 is about the Backend API; this is the other call the browser makes).
// It is not `request()`: the URL is the storage's, it is signed, and it must be sent exactly as issued. No `Authorization`
// (the URL is the credential), no `Idempotency-Key`, no JSON; only the `Content-Type` the URL was signed for. `fetch` cannot report
// how much of an upload has gone, so this is an XMLHttpRequest.

export type PutFailure =
  /** No answer: the connection dropped, or the link had expired and the storage closed it. */
  | { kind: 'network' }
  /** The storage answered, but not with success (a 403 for a link that expired or a signature that does not match). */
  | { kind: 'rejected'; status: number }
  | { kind: 'aborted' }

export class PutError extends Error {
  readonly failure: PutFailure
  constructor(failure: PutFailure) {
    super(`Upload failed: ${failure.kind}`)
    this.name = 'PutError'
    this.failure = failure
  }
}

export function putWithProgress(options: {
  url: string
  body: Blob
  /** The exact `Content-Type` the URL was issued for. */
  contentType: string
  onProgress?: (fraction: number) => void
  signal?: AbortSignal
}): Promise<void> {
  const { url, body, contentType, onProgress, signal } = options
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(new PutError({ kind: 'aborted' }))
      return
    }
    const xhr = new XMLHttpRequest()
    xhr.open('PUT', url)
    xhr.setRequestHeader('Content-Type', contentType)
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable && event.total > 0) onProgress?.(Math.min(event.loaded / event.total, 1))
    }
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        onProgress?.(1)
        resolve()
      } else {
        reject(new PutError({ kind: 'rejected', status: xhr.status }))
      }
    }
    xhr.onerror = () => reject(new PutError({ kind: 'network' }))
    xhr.ontimeout = () => reject(new PutError({ kind: 'network' }))
    xhr.onabort = () => reject(new PutError({ kind: 'aborted' }))
    signal?.addEventListener('abort', () => xhr.abort(), { once: true })
    xhr.send(body)
  })
}

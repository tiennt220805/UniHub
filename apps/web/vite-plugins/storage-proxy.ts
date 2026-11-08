// The same-origin path to object storage in development (Phase 8, D120-D122). In the cluster the Istio gateway
// does this (k8s/base/gateway.yaml); here Vite does, so the browser sees the same two paths on the same origin:
//
//   PUT /unihub-brochures/**     an Organizer's upload on a presigned URL, forwarded to the bucket untouched
//   GET /media/covers/**         a cover image, read without credentials; mapped onto the bucket's covers/ prefix
//
// A presigned URL's signature covers the Host and the path, so the PUT is forwarded with the Host the browser
// sent (`changeOrigin: false`) and the path as it came. The application mints those URLs for this origin when
// UNIHUB_OBJECT_STORAGE_PUBLIC_ENDPOINT is http://localhost:5173 (apps/backend/env.example).
//
// Nothing else of the bucket is reachable: a path outside these two, and any other method, is answered here.
import type { IncomingMessage, ServerResponse } from 'node:http'
import type { ProxyOptions } from 'vite'

export const BUCKET = 'unihub-brochures'
export const COVER_URL_PREFIX = '/media/covers/'
export const COVER_HEADERS = {
  'x-content-type-options': 'nosniff',
  // A cover's key is immutable (a new upload is a new key), so it never changes under a URL.
  'cache-control': 'public, max-age=31536000, immutable',
} as const

const COVER_METHODS = ['GET', 'HEAD']
const UPLOAD_METHODS = ['PUT']

/** `/media/covers/{workshop}/{file}` → `/unihub-brochures/covers/{workshop}/{file}`. */
export function coverPathToBucket(requestPath: string): string {
  return requestPath.replace(/^\/media\//, `/${BUCKET}/`)
}

/** Answers `405` and returns false for a method the route does not allow; `undefined` lets Vite proxy it. */
function onlyMethods(allowed: string[]) {
  return (req: IncomingMessage, res: ServerResponse | undefined): false | undefined => {
    if (allowed.includes(req.method ?? '')) return undefined
    if (res) {
      res.statusCode = 405
      res.setHeader('Allow', allowed.join(', '))
      res.end()
    }
    return false
  }
}

export const coverMethods = onlyMethods(COVER_METHODS)
export const uploadMethods = onlyMethods(UPLOAD_METHODS)

export function storageProxy(target: string): Record<string, ProxyOptions> {
  return {
    [COVER_URL_PREFIX]: {
      target,
      changeOrigin: false,
      rewrite: coverPathToBucket,
      bypass: coverMethods,
      configure: (proxy) => {
        // MinIO sets neither header; the edge does (nginx or the gateway in the cluster).
        proxy.on('proxyRes', (proxyRes) => {
          for (const [name, value] of Object.entries(COVER_HEADERS)) proxyRes.headers[name] = value
        })
      },
    },
    [`/${BUCKET}/`]: {
      target,
      changeOrigin: false,
      bypass: uploadMethods,
    },
  }
}

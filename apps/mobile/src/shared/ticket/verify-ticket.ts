import { p256 } from '@noble/curves/nist.js'

import type { VerificationKey } from '@/shared/crypto/verification-key'
import { asciiBytes, asciiOf, decodeBase64Url } from '@/shared/lib/base64url'

export type RejectionReason =
  /** No key is cached: nothing can be verified, so nothing is scanned (`checkin.md` §8). */
  | 'NO_KEY'
  /** Not a compact JWS of three base64url parts, or a part that does not decode. */
  | 'MALFORMED'
  /** The header names an algorithm other than ES256 (including `none` and the HMAC family). */
  | 'ALGORITHM'
  /** The signature does not verify under the cached key. */
  | 'SIGNATURE'
  /** The signature verifies but the payload is not the ticket the server issues. */
  | 'CLAIMS'

export type VerifiedTicket = {
  readonly ticketCode: string
  readonly workshopId: string
  readonly issuedAt: number
}

export type VerifyResult =
  | { readonly ok: true; readonly ticket: VerifiedTicket }
  | { readonly ok: false; readonly reason: RejectionReason }

// A real ticket is 260 characters (`checkin.md` §5). Anything much longer is not one, and is
// refused before it is decoded or parsed.
const MAX_TOKEN_LENGTH = 1024
const SIGNATURE_BYTES = 64
const COORDINATE_BYTES = 32
// The server stores `ticket_code VARCHAR(64)` and refuses a longer value (`ScanEvent`), so a code the
// device accepts must fit there; a shorter or longer rule would make the batch fail on the server.
const TICKET_CODE = /^[0-9A-Za-z]{1,64}$/
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

const rejected = (reason: RejectionReason): VerifyResult => ({ ok: false, reason })

function jsonObject(bytes: Uint8Array): Record<string, unknown> | null {
  const text = asciiOf(bytes)
  if (text === null) return null
  try {
    const value: unknown = JSON.parse(text)
    return typeof value === 'object' && value !== null && !Array.isArray(value)
      ? (value as Record<string, unknown>)
      : null
  } catch {
    return null
  }
}

/** The key as an uncompressed SEC1 point (0x04 ‖ x ‖ y), or null if it is not a P-256 point's shape. */
function publicPoint(key: VerificationKey): Uint8Array | null {
  const x = decodeBase64Url(key.x)
  const y = decodeBase64Url(key.y)
  if (x?.length !== COORDINATE_BYTES || y?.length !== COORDINATE_BYTES) return null
  const point = new Uint8Array(1 + 2 * COORDINATE_BYTES)
  point[0] = 0x04
  point.set(x, 1)
  point.set(y, 1 + COORDINATE_BYTES)
  return point
}

/**
 * Verifies a scanned QR as the ticket the Backend API signed, offline: ES256 only, against the one
 * cached key, and nothing else is trusted — the header's other members (`jwk`, `kid`, `jku`) are
 * never read, so a ticket cannot choose the key it is checked against (S-10 V3, V5).
 *
 * Pure JavaScript on purpose (`@noble/curves`): Hermes has no `crypto.subtle`, and this is the one
 * function both the phone and the Jest suite run (Phase 5 checklist D58). It never touches the
 * network and never throws: any input, of any type, ends in a result.
 */
export function verifyTicket(token: unknown, key: VerificationKey | null): VerifyResult {
  if (key === null) return rejected('NO_KEY')
  if (typeof token !== 'string' || token.length === 0 || token.length > MAX_TOKEN_LENGTH) {
    return rejected('MALFORMED')
  }
  const parts = token.split('.')
  if (parts.length !== 3) return rejected('MALFORMED')
  const [encodedHeader, encodedPayload, encodedSignature] = parts as [string, string, string]
  if (!encodedHeader || !encodedPayload) return rejected('MALFORMED')

  const headerBytes = decodeBase64Url(encodedHeader)
  const payloadBytes = decodeBase64Url(encodedPayload)
  const signature = decodeBase64Url(encodedSignature)
  if (headerBytes === null || payloadBytes === null || signature === null) {
    return rejected('MALFORMED')
  }
  const header = jsonObject(headerBytes)
  if (header === null) return rejected('MALFORMED')
  // Pinned: the algorithm comes from this code, never from the ticket. `none` and `HS256` end here.
  if (header.alg !== 'ES256' || 'crit' in header) return rejected('ALGORITHM')
  if (signature.length !== SIGNATURE_BYTES) return rejected('SIGNATURE')

  const point = publicPoint(key)
  if (point === null) return rejected('NO_KEY')
  let verified: boolean
  try {
    // lowS: false — Nimbus signs with the raw ECDSA output, so about half of all genuine signatures
    // have a high `s`; noble's default of true would refuse each of those.
    verified = p256.verify(signature, asciiBytes(`${encodedHeader}.${encodedPayload}`), point, {
      lowS: false,
    })
  } catch {
    return rejected('SIGNATURE')
  }
  if (!verified) return rejected('SIGNATURE')

  const claims = jsonObject(payloadBytes)
  if (claims === null) return rejected('CLAIMS')
  const { ticket_code: ticketCode, workshop_id: workshopId, iat } = claims
  if (
    typeof ticketCode !== 'string' ||
    !TICKET_CODE.test(ticketCode) ||
    typeof workshopId !== 'string' ||
    !UUID.test(workshopId) ||
    typeof iat !== 'number' ||
    !Number.isInteger(iat)
  ) {
    return rejected('CLAIMS')
  }
  return { ok: true, ticket: { ticketCode, workshopId: workshopId.toLowerCase(), issuedAt: iat } }
}

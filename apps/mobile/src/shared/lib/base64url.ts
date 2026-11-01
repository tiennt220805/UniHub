const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_'
const LOOKUP = new Map([...ALPHABET].map((char, index) => [char, index]))

/**
 * Strict base64url without padding (RFC 7515 §2). Returns null for anything else — a character
 * outside the alphabet, padding, or a length no byte string encodes to — instead of guessing, so a
 * ticket cannot be made to decode differently here than on the server. Plain JS: nothing here
 * depends on `atob`, `Buffer` or `TextDecoder`, which the device runtime may not provide.
 */
export function decodeBase64Url(input: string): Uint8Array | null {
  if (input.length % 4 === 1) return null
  const out = new Uint8Array(Math.floor((input.length * 3) / 4))
  let buffer = 0
  let bits = 0
  let written = 0
  for (const char of input) {
    const value = LOOKUP.get(char)
    if (value === undefined) return null
    buffer = (buffer << 6) | value
    bits += 6
    if (bits >= 8) {
      bits -= 8
      out[written++] = (buffer >> bits) & 0xff
      buffer &= (1 << bits) - 1
    }
  }
  // The unused low bits of the last character must be zero, or two strings decode to the same bytes.
  if (buffer !== 0) return null
  return out
}

/** The bytes as ASCII text, or null if any byte is outside 0x00–0x7F. */
export function asciiOf(bytes: Uint8Array): string | null {
  let text = ''
  for (const byte of bytes) {
    if (byte > 0x7f) return null
    text += String.fromCharCode(byte)
  }
  return text
}

export function asciiBytes(text: string): Uint8Array {
  const out = new Uint8Array(text.length)
  for (let i = 0; i < text.length; i++) out[i] = text.charCodeAt(i) & 0x7f
  return out
}

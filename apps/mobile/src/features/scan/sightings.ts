/**
 * Tells one physical scan from the camera reporting the same code again. The camera reports a code
 * many times a second while it is in frame, and with two codes in frame it alternates between them,
 * so each code is remembered on its own: comparing only with the code reported last let a screen
 * showing several QR codes produce a record for every report (163 scans from about ten, 2026-11-01).
 *
 * A sighting that follows another of the same code within `cooldownMs` is the same scan, and it
 * renews the code's time, so a code that stays in view is one scan however long it stays. It counts
 * again only after the camera has not reported it for `cooldownMs` — taking it away and bringing it
 * back — which is exactly what the "already scanned at hh:mm" warning is for.
 */
export function createSightings(cooldownMs: number) {
  const lastSeen = new Map<string, number>()

  return function isNewScan(code: string, atMs: number): boolean {
    for (const [seen, at] of lastSeen) {
      if (atMs - at >= cooldownMs) lastSeen.delete(seen)
    }
    const previous = lastSeen.get(code)
    lastSeen.set(code, atMs)
    return previous === undefined || atMs - previous >= cooldownMs
  }
}

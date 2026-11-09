import { QRCodeSVG } from 'qrcode.react'

/**
 * The signed ticket as a QR code. Error-correction level Q (25%): the Check-in Client reads it from
 * a phone screen in a lit hall, and ES256 keeps the symbol small enough to afford it
 * (`checkin.md` §5: 260 characters, version 15). Inline SVG with no `style` attribute, so the
 * build's Content-Security-Policy (`style-src 'self'`, `img-src 'self' data:`) needs nothing new.
 * The light background is part of the symbol, not the theme: a QR needs a light quiet zone.
 */
export function TicketQrCode({ value }: { value: string }) {
  return (
    <QRCodeSVG
      value={value}
      level="Q"
      size={288}
      marginSize={4}
      title="Mã QR e-ticket"
      className="h-auto w-full max-w-72 md:max-w-64"
    />
  )
}

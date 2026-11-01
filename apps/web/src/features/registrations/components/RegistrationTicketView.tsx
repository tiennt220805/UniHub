import type { ReactNode } from 'react'

import type { Registration } from '../api/requests'
import { ticketStateOf } from '../model/ticket-state'
import { HoldCountdown } from './HoldCountdown'
import { TicketQrCode } from './TicketQrCode'

type Props = {
  registration: Registration
  /**
   * The workshop's name and time. A registration carries only the workshop's id, so the route that
   * composes this view with the workshops feature supplies them (a feature never imports another).
   */
  workshop: ReactNode
}

/** The e-ticket of one registration, or the reason there is none. */
export function RegistrationTicketView({ registration, workshop }: Props) {
  const ticket = ticketStateOf(registration)

  return (
    <section className="space-y-4" aria-label="E-ticket">
      {workshop}
      {ticket.kind === 'ISSUED' && (
        <div className="space-y-3">
          <div className="inline-block rounded-lg border bg-white p-2">
            <TicketQrCode value={ticket.qrPayload} />
          </div>
          {ticket.ticketCode && (
            <p>
              Mã vé: <span className="font-mono font-medium tracking-wider">{ticket.ticketCode}</span>
            </p>
          )}
          <p className="text-sm text-muted-foreground">
            Xuất trình mã QR này cho nhân viên check-in tại cửa workshop.
          </p>
        </div>
      )}
      {ticket.kind === 'NOT_ISSUED' && (
        <p role="status" className="rounded-md bg-muted p-3 text-foreground">
          Vé chưa được phát cho đăng ký này.
        </p>
      )}
      {ticket.kind === 'PENDING' && (
        <>
          <p role="status" className="rounded-md bg-muted p-3 text-foreground">
            Chưa có vé. Vé được phát khi đăng ký được xác nhận.
          </p>
          <HoldCountdown registration={registration} />
        </>
      )}
      {ticket.kind === 'ENDED' && (
        <p role="status" className="rounded-md bg-muted p-3 text-foreground">
          {ticket.status === 'CANCELLED'
            ? 'Đăng ký này đã bị hủy, vé không còn hiệu lực.'
            : 'Đã hết thời gian giữ chỗ, đăng ký này không còn hiệu lực.'}
        </p>
      )}
    </section>
  )
}

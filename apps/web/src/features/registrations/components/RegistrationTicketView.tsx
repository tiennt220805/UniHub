import { CircleX, Info, SunMedium, TimerOff } from 'lucide-react'
import type { ReactNode } from 'react'

import { formatDateTime } from '@/shared/lib/format'
import { Notice } from '@/shared/ui/notice'
import type { Registration } from '../api/requests'
import { ticketStateOf } from '../model/ticket-state'
import { HoldCountdown } from './HoldCountdown'
import { RegistrationStatusBadge } from './RegistrationStatusBadge'
import { TicketQrCode } from './TicketQrCode'

type Props = {
  registration: Registration
  /**
   * The workshop's name, time and place. A registration carries only the workshop's id, so the route that composes this
   * view with the workshops feature supplies them (a feature never imports another).
   */
  workshop: ReactNode
}

/**
 * The e-ticket of one registration, or the reason there is none (D152). The QR sits on white whatever the theme: the
 * light quiet zone is part of the symbol, and a dark page around it changes nothing about how it is read.
 */
export function RegistrationTicketView({ registration, workshop }: Props) {
  const ticket = ticketStateOf(registration)

  return (
    <section aria-label="E-ticket">
      {ticket.kind === 'ISSUED' ? (
        <div className="overflow-hidden rounded-2xl border border-border bg-card md:flex">
          <div className="flex min-w-0 flex-1 flex-col gap-4 p-5 md:p-8">
            <RegistrationStatusBadge status={registration.status} />
            {workshop}
            {ticket.ticketCode && (
              <div className="border-t border-border pt-4">
                <span className="block text-xs text-muted-foreground">Mã vé</span>
                <span className="font-mono text-base leading-snug font-semibold tracking-[0.08em] break-all">
                  {ticket.ticketCode}
                </span>
              </div>
            )}
            <p className="mt-auto text-sm text-muted-foreground">
              {registration.ticketType === 'PAID' ? 'Có phí' : 'Miễn phí'} · Đăng ký lúc{' '}
              <time dateTime={registration.createdAt}>{formatDateTime(registration.createdAt)}</time>
            </p>
          </div>
          <div className="flex flex-col items-center gap-3 border-t-2 border-dashed border-border p-5 text-center md:w-[380px] md:shrink-0 md:border-t-0 md:border-l-2 md:p-8">
            <div className="w-full max-w-[290px] rounded-xl border border-border bg-white leading-[0] md:max-w-[258px]">
              <TicketQrCode value={ticket.qrPayload} />
            </div>
            <p className="max-w-72 text-sm">Xuất trình mã QR này cho nhân viên check-in tại cửa workshop.</p>
            <p className="flex max-w-72 items-start gap-2 text-left text-[13px] text-muted-foreground md:hidden">
              <SunMedium aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
              Tăng độ sáng màn hình khi đưa vé cho nhân viên.
            </p>
          </div>
        </div>
      ) : (
        <div className="grid max-w-xl gap-4">
          <RegistrationStatusBadge status={registration.status} />
          {workshop}
          {ticket.kind === 'NOT_ISSUED' && (
            <Notice tone="muted" icon={Info} role="status">
              Vé chưa được phát cho đăng ký này.
            </Notice>
          )}
          {ticket.kind === 'PENDING' && (
            <>
              <Notice tone="muted" icon={Info} role="status">
                Chưa có vé. Vé được phát khi đăng ký được xác nhận.
              </Notice>
              <HoldCountdown registration={registration} />
            </>
          )}
          {ticket.kind === 'ENDED' && (
            <Notice tone={ticket.status === 'CANCELLED' ? 'destructive' : 'muted'} icon={ticket.status === 'CANCELLED' ? CircleX : TimerOff} role="status">
              {ticket.status === 'CANCELLED'
                ? 'Đăng ký này đã bị hủy, vé không còn hiệu lực.'
                : 'Đã hết thời gian giữ chỗ, đăng ký này không còn hiệu lực.'}
            </Notice>
          )}
        </div>
      )}
    </section>
  )
}

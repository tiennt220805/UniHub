import { screen } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'

import type { components } from '@/shared/api/schema.gen'
import { aWorkshop, WORKSHOP_ID } from '@/testing/factories/workshop'
import { server } from '@/testing/msw-server'
import { renderRoute } from '@/testing/render-route'

type Workshop = components['schemas']['Workshop']

function serveDetail(body: Workshop) {
  server.use(
    http.get('/api/v1/workshops/:id', ({ params }) =>
      params.id === body.id ? HttpResponse.json(body) : new HttpResponse(null, { status: 404 }),
    ),
  )
}

function serveProblem(status: number) {
  server.use(
    http.get('/api/v1/workshops/:id', () =>
      HttpResponse.json(
        { type: 'about:blank', title: 'Problem', status },
        { status, headers: { 'Content-Type': 'application/problem+json' } },
      ),
    ),
  )
}

describe('WorkshopDetailPage', () => {
  it('renders speaker, location, schedule and description from GET /api/v1/workshops/{id}', async () => {
    serveDetail(aWorkshop())

    renderRoute(`/workshops/${WORKSHOP_ID}`)

    expect(await screen.findByRole('heading', { level: 1, name: 'Intro to Distributed Locks' })).toBeInTheDocument()
    expect(screen.getByText('Dr. Lan Nguyen')).toBeInTheDocument()
    expect(screen.getByText('Distributed systems lecturer.')).toBeInTheDocument()
    expect(screen.getByText('Hall A')).toBeInTheDocument()
    expect(screen.getByText('09:00 1 thg 11, 2026')).toBeInTheDocument()
    expect(screen.getByText('11:00 1 thg 11, 2026')).toBeInTheDocument()
    expect(screen.getByText('Redis locks and why they are only advisory.')).toBeInTheDocument()
  })

  it('shows remaining seats as capacity minus seatsTaken', async () => {
    serveDetail(aWorkshop({ capacity: 50, seatsTaken: 15 }))

    renderRoute(`/workshops/${WORKSHOP_ID}`)

    expect(await screen.findByText('Còn 35 / 50 chỗ')).toBeInTheDocument()
  })

  it('shows "Hết chỗ" when every seat is taken', async () => {
    serveDetail(aWorkshop({ capacity: 50, seatsTaken: 50 }))

    renderRoute(`/workshops/${WORKSHOP_ID}`)

    expect(await screen.findByText('Hết chỗ')).toBeInTheDocument()
  })

  it('shows the price for a paid workshop', async () => {
    serveDetail(aWorkshop({ isPaid: true, price: 150000 }))

    renderRoute(`/workshops/${WORKSHOP_ID}`)

    expect(await screen.findByText('150.000')).toBeInTheDocument()
    expect(screen.queryByText('Miễn phí')).not.toBeInTheDocument()
  })

  it('shows "Miễn phí" when isPaid is false', async () => {
    serveDetail(aWorkshop())

    renderRoute(`/workshops/${WORKSHOP_ID}`)

    expect(await screen.findByText('Miễn phí')).toBeInTheDocument()
  })

  it('renders the AI summary only once it is available', async () => {
    serveDetail(aWorkshop({ aiSummary: 'Locks are advisory; the DB check decides.' }))

    renderRoute(`/workshops/${WORKSHOP_ID}`)

    expect(await screen.findByText('Locks are advisory; the DB check decides.')).toBeInTheDocument()
  })

  it('omits optional sections when description, bio and AI summary are null', async () => {
    serveDetail(aWorkshop({ description: null, speakerBio: null, aiSummary: null }))

    renderRoute(`/workshops/${WORKSHOP_ID}`)

    await screen.findByRole('heading', { level: 1, name: 'Intro to Distributed Locks' })
    expect(screen.queryByRole('heading', { name: 'Giới thiệu' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Tóm tắt (AI)' })).not.toBeInTheDocument()
  })

  it('flags a cancelled workshop', async () => {
    serveDetail(aWorkshop({ status: 'CANCELLED' }))

    renderRoute(`/workshops/${WORKSHOP_ID}`)

    expect(await screen.findByRole('status')).toHaveTextContent('Workshop này đã bị hủy.')
  })

  it('shows "not found" when the API returns 404 (unknown id, or a DRAFT hidden from this caller)', async () => {
    serveProblem(404)

    renderRoute(`/workshops/${WORKSHOP_ID}`)

    expect(await screen.findByText('Không tìm thấy workshop.')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('shows "not found" for a malformed id the API rejects with 400', async () => {
    serveProblem(400)

    renderRoute('/workshops/not-a-uuid')

    expect(await screen.findByText('Không tìm thấy workshop.')).toBeInTheDocument()
  })

  it('shows an error message when the API fails', async () => {
    serveProblem(500)

    renderRoute(`/workshops/${WORKSHOP_ID}`)

    expect(await screen.findByRole('alert')).toHaveTextContent('Không tải được thông tin workshop.')
  })

  it('links back to the catalog', async () => {
    serveDetail(aWorkshop())

    renderRoute(`/workshops/${WORKSHOP_ID}`)

    expect(await screen.findByRole('link', { name: /Danh sách workshop/ })).toHaveAttribute('href', '/workshops')
  })
})

import { render } from '@testing-library/react'
import { createRef } from 'react'
import { afterEach, describe, expect, it } from 'vitest'

import { RemoveScroll } from '../csp-safe-remove-scroll'

afterEach(() => {
  document.body.removeAttribute('style')
})

describe('the CSP-safe RemoveScroll that stands in for react-remove-scroll', () => {
  it('locks the body scroll while mounted and puts back what was there', () => {
    document.body.style.overflow = 'auto'
    const { unmount } = render(<RemoveScroll>content</RemoveScroll>)
    expect(document.body.style.overflow).toBe('hidden')
    unmount()
    expect(document.body.style.overflow).toBe('auto')
  })

  it('keeps the lock until the last of several layers closes', () => {
    const first = render(<RemoveScroll>one</RemoveScroll>)
    const second = render(<RemoveScroll>two</RemoveScroll>)
    first.unmount()
    expect(document.body.style.overflow).toBe('hidden')
    second.unmount()
    expect(document.body.style.overflow).toBe('')
  })

  it('does nothing while disabled', () => {
    render(<RemoveScroll enabled={false}>idle</RemoveScroll>)
    expect(document.body.style.overflow).toBe('')
  })

  it('renders the element it is asked to, forwards the ref and keeps the extra props off the DOM', () => {
    const ref = createRef<HTMLElement>()
    const { container } = render(
      <RemoveScroll as="section" ref={ref} className="layer" allowPinchZoom shards={[]} data-testid="layer">
        inside
      </RemoveScroll>,
    )
    const element = container.querySelector('section')
    expect(element).toHaveClass('layer')
    expect(element).toHaveTextContent('inside')
    expect(ref.current).toBe(element)
    expect(element?.hasAttribute('allowpinchzoom')).toBe(false)
    expect(element?.hasAttribute('shards')).toBe(false)
  })
})

import { Link } from 'react-router'

import { NotFound } from '@/shared/components/NotFound'
import { Button } from '@/shared/ui/button'

// The catch-all route (S16): an address that leads nowhere.
export function NotFoundPage() {
  return (
    <main>
      <NotFound>
        <Button asChild variant="outline" size="lg">
          <Link to="/workshops">Xem danh sách workshop</Link>
        </Button>
      </NotFound>
    </main>
  )
}

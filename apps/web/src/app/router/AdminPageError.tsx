import { Button } from '@/shared/ui/button'
import { ErrorState } from '@/shared/ui/state-panel'

/**
 * What stands in the page area of /admin when the page itself could not be built (task 7.5). The administration pages are lazy
 * chunks, so after a rolling deploy a tab that is still open can ask for a chunk whose hash the pod it reaches no longer has, or
 * the connection can drop mid-request; React Router's default would then show an English stack message. It sits inside the admin
 * frame (the route that carries it is a child of AdminLayout), so the sidebar stays. The error's own message is not shown: it
 * is a module URL and means nothing to the person looking at it. A reload is the way out, and it fetches the current build.
 */
export function AdminPageError() {
  return (
    <main className="mx-auto max-w-xl p-6">
      <ErrorState
        title="Không tải được trang này."
        description="Kết nối có thể đã bị gián đoạn, hoặc ứng dụng vừa được cập nhật. Tải lại trang để lấy bản mới nhất."
        action={
          <Button variant="outline" onClick={() => window.location.reload()}>
            Tải lại
          </Button>
        }
      />
    </main>
  )
}

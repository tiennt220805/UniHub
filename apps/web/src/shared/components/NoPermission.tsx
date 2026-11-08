import { Link } from 'react-router'

// A guard that refuses must not leave the user with nowhere to go: the page outside any layout (the admin guard sits
// above its own shell) would otherwise be a dead end.
export function NoPermission() {
  return (
    <main className="mx-auto max-w-3xl space-y-3 p-6">
      <p role="alert">Bạn không có quyền truy cập trang này.</p>
      <Link to="/" className="text-primary-text underline underline-offset-4">
        Về trang chủ
      </Link>
    </main>
  )
}

import { Bar, BarChart, Cell, Pie, PieChart, ResponsiveContainer, XAxis } from 'recharts'

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '@/shared/ui/alert-dialog'
import { WorkshopCardGrid, type WorkshopSummary } from '@/features/workshops'
import { toast } from '@/shared/store/toast-store'
import { Button } from '@/shared/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogTitle, DialogTrigger } from '@/shared/ui/dialog'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/shared/ui/dropdown-menu'
import { Popover, PopoverContent, PopoverTrigger } from '@/shared/ui/popover'
import { Sheet, SheetContent, SheetDescription, SheetTitle, SheetTrigger } from '@/shared/ui/sheet'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/shared/ui/tabs'

// Task 2.3 (Phase 8 checklist, D125): every UI library that the design leans on is opened in a real browser under
// the production Content-Security-Policy (`style-src 'self'`, no 'unsafe-inline') before a screen depends on it.
// Only a build made with VITE_SPIKE=true has this route (app/router/routes.tsx); tests/e2e/specs-local/spike.spec.ts
// drives it on the three engines. It is a test fixture, not a screen: nothing links here.

const BARS = [
  { slot: '08:00', count: 4 },
  { slot: '08:15', count: 11 },
  { slot: '08:30', count: 7 },
  { slot: '08:45', count: 2 },
]
const SHARE = [
  { name: 'Đã check-in', value: 24, fill: 'var(--chart-1)' },
  { name: 'Chưa check-in', value: 36, fill: 'var(--chart-5)' },
]

// `?only=numberflow,charts` renders just those sections, so a violation can be pinned on one library.
const ONLY = new URLSearchParams(window.location.search).get('only')?.split(',')
const shows = (section: string) => !ONLY || ONLY.includes(section)

// Task 4.4 (D144): the workshop card with a generated cover, in the three seat levels. Twelve ids so that every family of
// cover is drawn; none has an image, so nothing is requested from /media.
const CARD_SAMPLES: WorkshopSummary[] = Array.from({ length: 12 }, (_, i) => ({
  id: `00000000-0000-4000-8000-${String(i).padStart(12, '0')}`,
  title: `Workshop thử số ${i + 1}`,
  location: 'Phòng A2.301',
  startTime: '2026-11-11T02:00:00Z',
  endTime: '2026-11-11T04:30:00Z',
  status: 'PUBLISHED',
  speakerName: 'TS. Lê Minh Quân',
  capacity: 60,
  seatsTaken: [10, 55, 60][i % 3],
  isPaid: i % 2 === 0,
  price: i % 2 === 0 ? 150000 : null,
  coverImageUrl: null,
}))

export function SpikePage() {

  return (
    <main className="mx-auto grid max-w-3xl gap-6 p-4">
      <h1 className="text-xl font-semibold">Kiểm tra CSP của thư viện giao diện</h1>

      {shows('dialog') && (
        <section aria-label="Dialog" className="flex items-center gap-3">
          <Dialog>
            <DialogTrigger asChild>
              <Button>Mở hộp thoại</Button>
            </DialogTrigger>
            <DialogContent>
              <DialogTitle>Hộp thoại thử</DialogTitle>
              <DialogDescription>Nội dung hộp thoại.</DialogDescription>
            </DialogContent>
          </Dialog>
        </section>
      )}

      {shows('alertdialog') && (
        <section aria-label="AlertDialog">
          <AlertDialog>
            <AlertDialogTrigger asChild>
              <Button variant="destructive">Hủy workshop</Button>
            </AlertDialogTrigger>
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>Xác nhận hủy thử</AlertDialogTitle>
                <AlertDialogDescription>Sinh viên đã đăng ký sẽ được thông báo.</AlertDialogDescription>
              </AlertDialogHeader>
              <AlertDialogFooter>
                <AlertDialogCancel>Giữ lại</AlertDialogCancel>
                <AlertDialogAction>Hủy workshop</AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        </section>
      )}

      {shows('date') && (
        // Native date controls: no library, so the question is only whether an engine reports them under the policy.
        <section aria-label="Ô ngày giờ" className="grid max-w-xs gap-3">
          <label className="grid gap-1 text-sm">
            Từ ngày
            <input type="date" defaultValue="2026-11-12" className="h-9 rounded-lg border border-input bg-card px-3" />
          </label>
          <label className="grid gap-1 text-sm">
            Bắt đầu
            <input
              type="datetime-local"
              defaultValue="2026-11-12T14:00"
              className="h-9 rounded-lg border border-input bg-card px-3"
            />
          </label>
        </section>
      )}

      {shows('popover') && (
        <section aria-label="Popover">
          <Popover>
            <PopoverTrigger asChild>
              <Button variant="outline">Mở popover</Button>
            </PopoverTrigger>
            <PopoverContent>Nội dung popover</PopoverContent>
          </Popover>
        </section>
      )}

      {shows('menu') && (
        <section aria-label="Menu">
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="outline">Mở menu</Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent>
              <DropdownMenuItem>Mục một</DropdownMenuItem>
              <DropdownMenuItem>Mục hai</DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </section>
      )}

      {shows('sheet') && (
        <section aria-label="Ngăn bên">
          <Sheet>
            <SheetTrigger asChild>
              <Button variant="outline">Mở ngăn bên</Button>
            </SheetTrigger>
            <SheetContent>
              <SheetTitle>Ngăn bên thử</SheetTitle>
              <SheetDescription>Nội dung ngăn bên.</SheetDescription>
            </SheetContent>
          </Sheet>
        </section>
      )}

      {shows('cards') && (
        <section aria-label="Thẻ workshop" className="max-w-5xl">
          <WorkshopCardGrid items={CARD_SAMPLES} detailPath={(id) => `/workshops/${id}`} />
        </section>
      )}

      {shows('toast') && (
        // The <Toaster /> itself is mounted once in app/App.tsx, like in the real app.
        <section aria-label="Toast" className="flex gap-3">
          <Button onClick={() => toast.success('Đã lưu thay đổi.')}>Hiện thông báo</Button>
          <Button variant="destructive" onClick={() => toast.error('Không lưu được.')}>
            Hiện lỗi
          </Button>
        </section>
      )}

      {shows('tabs') && (
        <section aria-label="Tabs">
          <Tabs defaultValue="one">
            <TabsList>
              <TabsTrigger value="one">Tab một</TabsTrigger>
              <TabsTrigger value="two">Tab hai</TabsTrigger>
            </TabsList>
            <TabsContent value="one">Nội dung tab một</TabsContent>
            <TabsContent value="two">Nội dung tab hai</TabsContent>
          </Tabs>
        </section>
      )}


      {shows('charts') && (
        <section aria-label="Biểu đồ" className="grid grid-cols-2 gap-4">
          <div role="img" aria-label="Biểu đồ cột" className="h-40">
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={BARS}>
                <XAxis dataKey="slot" />
                <Bar dataKey="count" fill="var(--chart-1)" />
              </BarChart>
            </ResponsiveContainer>
          </div>
          <div role="img" aria-label="Biểu đồ vòng" className="h-40">
            <ResponsiveContainer width="100%" height="100%">
              <PieChart>
                <Pie data={SHARE} dataKey="value" nameKey="name" innerRadius={30} outerRadius={60}>
                  {SHARE.map((entry) => (
                    <Cell key={entry.name} fill={entry.fill} />
                  ))}
                </Pie>
              </PieChart>
            </ResponsiveContainer>
          </div>
        </section>
      )}
    </main>
  )
}

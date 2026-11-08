# Phase 8 — Web UI (thiết kế & hoàn thiện giao diện): Checklist Triển Khai

> **Mục đích file này:** checklist *thực thi* cho Phase 8, cùng vai trò với `phase-1-foundation.md` … `phase-7-kubernetes.md`. Phase này **không có trong `proposal.md` §15 bản gốc**: owner quyết định chen nó vào trước kiểm thử tải (2026-11-08), và kiểm thử tải đổi thành Phase 9 (Mục 1). Nó không thay thế `test-matrix.md` (trạng thái từng test) hay `phase-template.md` (sign-off).
>
> **Cách dùng:** tick `[x]` khi xong thật, không tick trước. Mỗi mục nên có commit tương ứng. Cập nhật **Trạng thái tổng thể** sau mỗi buổi làm việc.

| Trường | Giá trị |
|---|---|
| Phase | 8 — Web UI |
| Ngày bắt đầu | 2026-11-08 (checklist soạn cùng ngày, sau buổi phỏng vấn owner) |
| Trạng thái tổng thể | `☐ Chưa bắt đầu` / `☑ Đang làm` / `☐ Chờ sign-off` / `☐ Hoàn tất` — **Mục 0A (D105–D116) owner chốt qua phỏng vấn 2026-11-08; Mục 0 (Q1–Q12 → D117–D128, C72 → D129) owner chốt cùng ngày: Q1, Q2 trả lời riêng, Q3–Q12 + C72 + hai mặc định D110/D115 "chấp nhận tất cả đề xuất". Mục 0B (C63–C73) chờ xử lý trong các Mục tương ứng** (C63 xong cùng Mục 1). **Mục 1 (đánh số lại → Phase 9) xong 2026-11-08** |
| Nhánh làm việc | `main` (push thẳng, không PR) |
| Phạm vi | `apps/web` (thiết kế lại toàn bộ + màn hình còn thiếu theo SRS) và **phần backend tối thiểu** mà các màn hình đó cần (D105). **Không** gồm `apps/mobile` (D107) |
| INV liên quan | Không INV mới. Giữ nguyên: INV-06 (không cập nhật ghế/trạng thái lạc quan trên UI), idempotency key cho `createRegistration`/`chargePayment`, RBAC theo permission, INV-15 (migration `V9` chỉ mở rộng), CSP `'self'` |
| Matrix rows | Mới: M-82 … M-88 (Mục 10). Đóng: `phase-7.md` §5 dòng 2 (M-70 thành hành trình đầy đủ) và dòng 10 (catalog có tìm/lọc/phân trang) |
| Tham chiếu | `requirement.md` §1.3.1–§1.3.4 · `web-coding-standards.md` (Rules at a Glance, §3, §6, §7, §10, §11, §13 #10) · `packages/api-contract/paths/workshop.yaml` · `apps/web/nginx/default.conf` (CSP) · `tests/e2e/` · `.claude/skills/ui-ux-pro-max`, `design-system`, `ui-styling`, `emil-design-eng` và các skill animation của Emil |
| Trang xem trước bảng màu | https://claude.ai/artifact/52iL8GWS2cK7qyeHAX6RZS (owner chọn phương án B) |

### Ký hiệu nhãn

Giữ quy ước các phase trước: **`[M-82]`** đậm = task là bằng chứng của dòng; `[→ M-82]` = tiền đề; `[§5 r2]` = đóng dòng 2 của `phase-7.md` §5; `[G1]` … `[G4]` = cổng duyệt mockup (owner duyệt trước khi code nhóm màn hình đó, D116); `[— không dòng]` = không có dòng matrix.

---

## Ranh giới Phase 8 ↔ các phase khác (đọc trước khi làm)

- **Không đổi hành vi nghiệp vụ.** Số ghế vẫn hỏi lại theo `staleTime` 3 s / `refetchInterval` 5 s khi tab hiển thị; trạng thái thanh toán vẫn hỏi 2 s, dừng ở trạng thái cuối hoặc sau 2 phút (`web-coding-standards.md` §13 #7). Mô hình tải của Phase 9 (IMPLEMENTATION-READINESS C9) dựa trên đúng nhịp này; đổi nó là việc của owner, không phải tác dụng phụ của việc làm đẹp.
- **Không cập nhật lạc quan** số ghế, trạng thái đăng ký hay thanh toán (Rules at a Glance #8), kể cả để animation trông mượt hơn.
- **Không topology AMQP mới hay đổi tham số hàng đợi.** Task 8.4 của Phase 7 cho thấy topology không có expand/contract. Kiểm tra ảnh bìa làm đồng bộ lúc xác nhận, không qua consumer mới.
- **Hoãn (D105):** `cancelRegistration` (contract ghi "inferred", quy tắc hoàn tiền chưa quyết), `listWorkshopRegistrations`, các trang quản trị legacy-sync (`STUDENT_SYNC_*` chưa seed; SRS §1.3.6 đòi "không cần thao tác tay"). Contract giữ nguyên, Web không gọi.
- **Phase 9 (kiểm thử tải, M-71 … M-74)** chạy trên bản Web cuối cùng của phase này.

---

## Kiểm kê màn hình (SRS → route → operation → quyền)

| # | Màn hình | SRS | Route | Operation(s) | Quyền | Hiện trạng | Cổng |
|---|---|---|---|---|---|---|---|
| S1 | Trang chủ: giới thiệu gọn + ô tìm + "Sắp diễn ra tuần này" | §1.3.1 | `/` | `listWorkshops` (`dateFrom`/`dateTo`) | công khai | **mới** (hiện chuyển thẳng `/workshops`) | G2 |
| S2 | Catalog: lưới thẻ nhóm theo ngày, tìm theo chủ đề, lọc diễn giả/địa điểm/khoảng ngày, phân trang | §1.3.1 | `/workshops` | `listWorkshops` (`q`, `speaker`, `location`, `dateFrom`, `dateTo`, `page`, `size`) | công khai | có, **chỉ trang 0, không tìm/lọc** | G2 |
| S3 | Chi tiết workshop: ảnh bìa, tóm tắt AI, diễn giả, địa điểm, số ghế trực tiếp, nút đăng ký | §1.3.1, §1.3.4 | `/workshops/:id` | `getWorkshop`, `createRegistration` | công khai / `REGISTRATION_CREATE` | có, thiết kế lại + ảnh bìa | G2 |
| S4 | Đăng nhập | — | `/login` | `login` | công khai | có, thiết kế lại | G2 |
| S5 | Thanh toán + đếm ngược giữ chỗ 15 phút + theo dõi đến hoàn tiền | §1.3.1 | `/me/checkout/:id` | `chargePayment`, `getPayment`, `getRegistration` | `REGISTRATION_CREATE` | có, thiết kế lại | G3 |
| S6 | Đăng ký của tôi | §1.3.1 | `/me/registrations` | `listMyRegistrations` | phiên đăng nhập | có, thiết kế lại | G3 |
| S7 | E-ticket (QR) | §1.3.1 | `/me/registrations/:id` | `getRegistration` | phiên đăng nhập | có, thiết kế lại | G3 |
| S8 | Hộp thư thông báo | §1.3.2 | `/me/notifications` | `listMyNotifications` | phiên đăng nhập | có, thiết kế lại | G3 |
| S9 | Khung quản trị (sidebar) | §1.3.3 | `/admin/*` | — | `WORKSHOP_CREATE`/`WORKSHOP_EDIT`/`REPORT_VIEW` (anyOf) | **mới** | G1 |
| S10 | Danh sách workshop quản trị: tab Nháp / Đã publish / Đã hủy / Đã kết thúc | §1.3.3 | `/admin/workshops` | **mới** (Q3) | `WORKSHOP_EDIT` | **mới, cần backend** | G4 |
| S11 | Tạo workshop | §1.3.3 | `/admin/workshops/new` | `createWorkshop` | `WORKSHOP_CREATE` | **mới** | G4 |
| S12 | Sửa / đổi lịch / publish / hủy (hộp xác nhận nói rõ sinh viên sẽ được báo) | §1.3.3 | `/admin/workshops/:id` | `getWorkshop`, `updateWorkshop`, `publishWorkshop`, `cancelWorkshop` | `WORKSHOP_EDIT` | **mới** | G4 |
| S13 | Tải ảnh bìa | (D114) | trong S11/S12 | **mới** (Q5, Q6) | `WORKSHOP_CREATE`/`WORKSHOP_EDIT` | **mới, cần backend** | G4 |
| S14 | Tải brochure PDF + trạng thái tóm tắt AI | §1.3.4 | trong S12 | `requestBrochureUploadUrl`, `confirmBrochureUpload` | `WORKSHOP_CREATE`/`WORKSHOP_EDIT` | **mới, cần đường lưu trữ cùng origin** | G4 |
| S15 | Báo cáo: số đăng ký, tỷ lệ check-in theo thời gian thực, biểu đồ, danh sách điểm danh | §1.3.3 | `/admin/workshops/:id/report` | `getWorkshopReport`, `listWorkshopAttendances` | `REPORT_VIEW` | có, thiết kế lại (giữ region `Báo cáo workshop`) | G4 |
| S16 | Trang lỗi chung: 404, không có quyền, mất kết nối, `503` | — | `*` | — | — | một phần (`NoPermission`) | G2 |

---

## 0A. Đã chốt qua phỏng vấn owner — D105–D116 (2026-11-08)

D105–D109, D111–D114, D116 là lời owner trong buổi phỏng vấn. Hai mục **do Claude Code chọn mặc định**: phần "mặc định theo hệ thống" của D110 (owner chỉ nói "sáng và tối, có thể toggle") và D115; **owner xác nhận cả hai cùng ngày** ("chấp nhận tất cả đề xuất").

| D | Quyết định | Ghi chú |
|---|---|---|
| D105 | Phạm vi: Web cộng backend tối thiểu cho §1.3.3 và §1.3.4; hoãn `cancelRegistration`, `listWorkshopRegistrations`, legacy-sync admin | Owner chọn phương án được đề xuất |
| D106 | Phase UI là **Phase 8**; kiểm thử tải đổi thành **Phase 9** | Mục 1 sửa mọi tham chiếu |
| D107 | Chỉ Web, không đụng `apps/mobile` | Bằng chứng thiết bị của Phase 5 giữ nguyên |
| D108 | Không theo nhận diện trường; UniHub có nhận diện riêng | — |
| D109 | Phong cách: **trẻ trung, năng động, hướng tới sinh viên nhưng vẫn hợp một hệ thống đại học, không lòe loẹt** | Lời owner |
| D110 | Bảng màu **B — xanh rừng + hổ phách**, hai chế độ sáng/tối có nút chuyển. Lần đầu (chưa chọn) theo `prefers-color-scheme` — *mặc định của Claude Code, owner xác nhận 2026-11-08* | Giá trị ở bảng dưới; xem trang xem trước |
| D111 | Font **Be Vietnam Pro**, tự host qua `@fontsource` (CSP `font-src 'self'`) | Thay Geist |
| D112 | Sinh viên: thanh điều hướng trên, ưu tiên điện thoại. Quản trị: `/admin` có sidebar trái, ưu tiên máy tính | — |
| D113 | Animation **tinh tế, có mục đích**: dưới 300 ms, tôn trọng `prefers-reduced-motion`, theo chuẩn bộ skill Emil | — |
| D114 | Trang chủ gọn + workshop nổi bật; catalog là **lưới thẻ nhóm theo ngày**; **Organizer tải ảnh bìa lên** (cần backend) | Ảnh bìa mở rộng phạm vi backend so với D105; owner chọn rõ ràng |
| D115 | WCAG 2.2 AA là mức tối thiểu (chữ ≥ 4,5:1, viền điều khiển và vòng focus ≥ 3:1); trạng thái không chỉ dựa vào màu | *Mặc định của Claude Code, owner xác nhận 2026-11-08* |
| D116 | Mỗi nhóm màn hình có **mockup HTML** dựng từ token thật, owner duyệt rồi mới code (G1 … G4) | — |

**Token bảng màu B (D110), đã đo độ tương phản 2026-11-08:**

| Token | Sáng | Tối | Cặp đo (sáng / tối) |
|---|---|---|---|
| `background` | `#FAFAF7` | `#0A120D` | chữ trên nền 15,18 / 16,32 |
| `card` | `#FFFFFF` | `#111B15` | chữ trên card 15,88 / 15,14 |
| `foreground` | `#14261B` | `#E7F0E9` | — |
| `muted-foreground` | `#566358` | `#9BAD9F` | trên nền 6,05 / 8,03; trên card 6,32 / 7,45 |
| `primary` / `primary-foreground` | `#15803D` / `#FFFFFF` | `#4ADE80` / `#052E16` | 5,02 / 8,55 |
| `primary-text` (link, ngày giờ) | `#166534` | `#4ADE80` | trên nền 6,82 / 10,90 |
| `primary-soft` | `#DCFCE7` | `#103A20` | primary-text trên nó 6,49 / 7,31 |
| `accent` / `accent-foreground` (chip, nhãn "Có phí") | `#FCD34D` / `#451A03` | `#FCD34D` / `#451A03` | 10,39 / 10,39 |
| `warning` (chữ / nền) | `#C2410C` / `#FFEDD5` | `#FDBA74` / `#3A1F08` | 4,52 / 9,03 |
| `destructive` (chữ / nền) | `#B91C1C` / `#FEE2E2` | `#F87171` / `#3B1414` | 5,30 / 5,86 |
| `info` (chữ / nền) | `#1D4ED8` / `#DBEAFE` | `#93C5FD` / `#14243F` | 5,49 / 8,60 |
| `border` (trang trí) | `#E1E6DF` | `#223127` | không phải viền điều khiển |
| `input` (viền ô nhập) | **`#7F8C82`** | **`#627566`** | 3,51 / 3,57. Giá trị ở trang xem trước (`#CDD6CC` / `#2D3F33`) chỉ đạt 1,49 / 1,57 nên đã thay |
| `ring` (vòng focus) | `#15803D` | `#4ADE80` | 4,80 / 10,90 |
| thanh ghế: còn / sắp hết | `#15803D` / `#EA580C` | `#4ADE80` / `#FB923C` | trên rãnh 4,32 · 3,07 / 8,58 · 6,60 |

Primary và "thành công" cùng sắc xanh, nên mọi trạng thái (Đã xác nhận, Đang giữ chỗ, Hết chỗ…) **phải kèm chữ hoặc biểu tượng** (D115).

---

## 0. Quyết định trước khi code (Q1–Q12) — ✅ chốt 2026-11-08 → D117–D128

- [x] **Q1 — Dữ liệu trên thẻ catalog.** `WorkshopSummary` chỉ có `id`, `title`, `location`, `startTime`, `status`, nên thẻ không vẽ được thanh ghế, giá, diễn giả hay ảnh bìa. **Không** được gọi `getWorkshop` cho từng thẻ (nhân lưu lượng, phá mô hình tải C9, đụng rate limiter theo danh tính).
  - **Đề xuất:** mở rộng `WorkshopSummary` thêm `speakerName`, `endTime`, `capacity`, `seatsTaken`, `isPaid`, `price`, `coverImageUrl`. Đây là thay đổi contract (contract-diff bắt). Số ghế trên catalog đến từ cache danh sách 30 s, nên ghi là "khoảng" hoặc chỉ hiện mức (Còn nhiều / Sắp hết / Hết chỗ); trang chi tiết vẫn là số trực tiếp.
  - Phương án này làm **lớn payload `listWorkshops`** mà Phase 9 sẽ đo. Chấp nhận được vì Phase 9 chạy trên bản Web cuối (Ranh giới), và kịch bản tải phải dùng payload mới.
  - Thay thế: bỏ thanh ghế và giá khỏi thẻ catalog.
- [x] **Q2 — Mức ghế trên catalog.** Số chính xác "Còn 18/60" hay chỉ mức (Còn chỗ / Sắp hết dưới 20% / Hết chỗ)? **Đề xuất:** chỉ mức, vì dữ liệu có thể cũ tới 30 s.
- [x] **Q3 — Danh sách workshop cho quản trị.** **Đề xuất:** operation mới `listManagedWorkshops` (`GET /api/v1/admin/workshops` hoặc tương đương), quyền `WORKSHOP_EDIT`, đọc ở Master như workshop manager hiện nay, lọc theo `status`, phân trang. **Không** thêm tham số `status` vào `listWorkshops` công khai, vì cache key và payload của nó nằm trong mô hình tải Phase 9. Đóng `web-coding-standards.md` §13 #10 trong cùng thay đổi.
- [x] **Q4 — Đường lưu trữ cùng origin (brochure tải lên, ảnh bìa tải lên, ảnh bìa đọc).** Hiện URL presigned trỏ `http://minio:9000`: trình duyệt không tới được và CSP `connect-src 'self'` chặn. **Đề xuất:** một cơ chế cho cả ba, giữ CSP ở `'self'`: định tuyến đường dẫn bucket (ví dụ `/unihub-media/…`) qua cùng origin: nginx của Web image, Istio gateway trên cluster, proxy của Vite khi dev, và presign với endpoint công khai đúng bằng thứ MinIO nhận được. Origin của trình duyệt khác nhau giữa dev (Vite) và cluster (`unihub.local`), nên endpoint presign **cấu hình theo môi trường**. Route gateway chỉ mở đúng đường dẫn bucket và chỉ `GET`/`HEAD`/`PUT`; API quản trị của MinIO không lộ ra.
  - **Bẫy SigV4:** chữ ký presigned PUT phủ cả Host và path. Proxy **không được** bỏ tiền tố hay đổi Host, nếu không chữ ký hỏng. Proxy Vite phải để `changeOrigin: false`. Phải chứng minh bằng một PUT thật qua proxy: IT trên Testcontainers MinIO, rồi trên cluster qua Istio, rồi qua proxy Vite.
- [x] **Q5 — Đọc ảnh bìa.** **Đề xuất:** policy ẩn danh chỉ `s3:GetObject` trên `covers/*` (không `s3:ListBucket`; brochure **không bao giờ** đọc ẩn danh), key bất biến (đổi ảnh = key mới), `Cache-Control: public, max-age=31536000, immutable`, đi qua đường cùng origin của Q4. **Không** phục vụ ảnh qua API: mọi operation trừ webhook đều tính vào rate limiter, nên một trang 20 ảnh có thể ăn `429`.
- [x] **Q6 — An toàn file tải lên được phục vụ từ origin của app.** **Đề xuất:** ảnh bìa chỉ nhận JPEG, PNG, WebP (**không SVG**: SVG cùng origin là lỗ hổng stored XSS, và phản hồi từ MinIO không mang header CSP của nginx); giới hạn kích thước (ví dụ 2 MB, tỉ lệ 16:9 khuyến nghị); kiểm tra magic bytes lúc xác nhận (đồng bộ, không consumer mới); lưu `Content-Type` cố định và gửi `X-Content-Type-Options: nosniff`. Áp cùng header cho brochure PDF.
- [x] **Q7 — Workshop chưa có ảnh bìa.** **Đề xuất:** ảnh bìa tự sinh ổn định theo `id` (hoa văn hình học trong bảng màu B, kèm ngày lớn), vẽ bằng CSS/SVG nội tuyến của chính app (không phải file người dùng tải lên).
- [x] **Q8 — Nút chuyển chế độ sáng/tối không nháy.** Cần chạy script trước lần vẽ đầu, nhưng CSP chặn inline script. **Đề xuất:** file cùng origin `/theme-init.js` nạp chặn trong `<head>`, đọc `localStorage` trong `try/catch`, mặc định theo `prefers-color-scheme`; không dùng thư viện tiêm script kiểu `next-themes`.
- [x] **Q9 — Thư viện UI thêm vào.** Mỗi thư viện phải qua bộ Playwright CSP guard trên cả ba engine **trước** khi thiết kế dựa vào nó (một số thư viện tiêm `<style>` lúc chạy, và `style-src 'self'` chặn). Ứng viên: Sonner (toast), một thư viện biểu đồ cho báo cáo (recharts, hoặc SVG tự vẽ), NumberFlow (đổi số ghế). **Đề xuất:** thử cả ba trong trang spike (task 2.3); cái nào vi phạm CSP thì bỏ hoặc tự làm.
- [x] **Q10 — Biểu đồ trang báo cáo.** **Đề xuất:** hàng ô số (Đăng ký / Đã check-in / Tỷ lệ), một biểu đồ vòng tỷ lệ check-in, một biểu đồ cột check-in theo khung 15 phút **nếu dữ liệu `listWorkshopAttendances` đủ** (kiểm tra trường thời gian ở task 6.8); vẽ theo skill `dataviz`.
- [x] **Q11 — Ngôn ngữ.** **Đề xuất:** chỉ tiếng Việt như hiện nay (mọi chuỗi E2E đang là tiếng Việt), không thêm i18n.
- [x] **Q12 — Trình duyệt và màn hình hỗ trợ.** **Đề xuất:** ba engine của Playwright (Chromium, Firefox, WebKit), chiều rộng từ 360 px; khu quản trị dùng được từ 768 px, tối ưu từ 1280 px.

**Quyết định (owner, 2026-11-08):**

| D | Q | Quyết định |
|---|---|---|
| D117 | Q1 | Mở rộng `WorkshopSummary` (`speakerName`, `endTime`, `capacity`, `seatsTaken`, `isPaid`, `price`, `coverImageUrl`); Phase 9 đo trên payload mới |
| D118 | Q2 | Catalog chỉ hiện mức ghế: Còn chỗ / Sắp hết (dưới 20%) / Hết chỗ; số chính xác chỉ ở trang chi tiết |
| D119 | Q3 | Operation danh sách quản trị riêng, `WORKSHOP_EDIT`, đọc Master, lọc `status`, phân trang; `listWorkshops` công khai không đổi tham số |
| D120 | Q4 | Một đường lưu trữ cùng origin cho brochure và ảnh bìa; CSP giữ `'self'`; endpoint presign theo môi trường; route chỉ đường dẫn bucket, chỉ `GET`/`HEAD`/`PUT`; proxy giữ nguyên Host và path |
| D121 | Q5 | Đọc ảnh bìa ẩn danh: chỉ `s3:GetObject` trên `covers/*`, key bất biến, cache 1 năm `immutable`; không qua API |
| D122 | Q6 | Ảnh bìa chỉ JPEG/PNG/WebP, giới hạn kích thước, kiểm tra magic bytes đồng bộ lúc xác nhận, `Content-Type` cố định + `nosniff` (cả brochure) |
| D123 | Q7 | Chưa có ảnh bìa → ảnh tự sinh ổn định theo `id` trong bảng màu B |
| D124 | Q8 | `/theme-init.js` cùng origin, nạp chặn trong `<head>`; không thư viện tiêm script |
| D125 | Q9 | Mỗi thư viện UI qua CSP guard ba engine ở trang spike trước khi dùng |
| D126 | Q10 | Báo cáo: ô số, biểu đồ vòng tỷ lệ check-in, biểu đồ cột theo khung 15 phút nếu dữ liệu đủ |
| D127 | Q11 | Chỉ tiếng Việt, không i18n |
| D128 | Q12 | Chromium, Firefox, WebKit; từ 360 px; quản trị dùng được từ 768 px, tối ưu từ 1280 px |
| D129 | C72 | Giữ route `/admin`; sửa `web-coding-standards.md` §3 cho khớp (task 10.2) |

---

## 0B. Đối soát chéo — mâu thuẫn và rủi ro tìm thấy (2026-11-08)

- [x] **C63** `proposal.md` §15 không có phase UI; Phase 8 ở đó là kiểm thử tải → Mục 1. *(Xong 2026-11-08, cùng Mục 1.)*
- [ ] **C64** `web-coding-standards.md` §13 #10 (không có danh sách workshop `DRAFT`) → đóng bởi Q3.
- [ ] **C65** `WorkshopSummary` thiếu dữ liệu cho thẻ catalog đã duyệt ở trang xem trước → Q1.
- [ ] **C66** URL presigned `http://minio:9000` (`k8s/base/kustomization.yaml:45`) không tới được từ trình duyệt; CSP `connect-src 'self'` → Q4.
- [ ] **C67** `src/styles/index.css` còn đồ thừa của scaffold Vite (`#root` rộng 1126 px, `text-align: center`, `h1` 56 px, `--text`/`--accent` trùng tên token shadcn `--accent`) → task 2.1.
- [ ] **C68** Chuỗi và accessible name mà Playwright dùng (`'Đăng ký tham dự'`, `'Xem vé'`, region `'E-ticket'`, `'Báo cáo workshop'`, img `'Mã QR e-ticket'`, các câu thông báo thành công, heading `'Workshops'`, `'Thanh toán'`, link `'Đăng ký của tôi'`…) → giữ nguyên, hoặc sửa spec **trong cùng commit** (task 8.1).
- [ ] **C69** `design-system` / `ui-ux-pro-max --persist` mặc định tạo thư mục `design-system/` ở gốc repo → luôn dùng `--output-dir docs/guidelines/web-design-system/` (task 2.5).
- [ ] **C70** Ảnh bìa (D114) mở rộng phạm vi backend so với D105 (cột mới, operation mới, đường lưu trữ) → ghi rõ ở `phase-8.md` §5.
- [ ] **C71** Bảng màu ở trang xem trước có viền ô nhập dưới 3:1 → đã thay trong bảng token (0A).
- [ ] **C72** `web-coding-standards.md` §3 đặt feature `administration/` ở route `/administration`, nhưng code hiện có `/admin/workshops/:id/report` (M-70 và E2E dùng nó). **Đề xuất:** giữ `/admin` (đã có trong code và E2E), sửa §3 cho khớp; owner chốt cùng Mục 0.
- [ ] **C73** S1 biến `/` từ chuyển hướng thành trang: `WorkshopCatalogPage.test.tsx` ("redirects / to the catalog") phải đổi cùng commit; `transport.spec.ts` chỉ đòi `/` trả `200` + CSP nên không ảnh hưởng.

---

## 1. Đánh số lại: kiểm thử tải → Phase 9 (D106, C63)

- [x] 1.1 `proposal.md` §15: thêm dòng "Phase 8 — Web UI", đổi dòng kiểm thử tải thành Phase 9 `[— không dòng]` *(Xong 2026-11-08: "Phase 8" và "Phase 9" cùng độ rộng nên bảng không phải căn lại.)*
- [x] 1.2 `test-matrix.md`: cột Phase của M-71 … M-74 → 9; "Rows by phase" thêm `8: 7` (M-82 … M-88) và `9: 4` *(Xong 2026-11-08, **lệch có chủ ý so với dòng trên:** ô "Rows by phase" là tổng khớp với 81 dòng của bảng, mà M-82 … M-88 chưa tồn tại, nên chỉ ghi `9: 4`; **`8: 7` thêm ở 10.1/10.3** cùng lúc các dòng được tạo. Cùng lý do, bảng §4.1 "Not started, by phase" chỉ đổi dòng `8` thành `9`; dòng Phase 8 thêm ở Mục 10. Dòng "Phase" của §2.2 ghi "1–9".)*
- [x] 1.3 `IMPLEMENTATION-READINESS.md` C9, E2; **thêm ghi chú đánh số lại** vào `phase-7.md` (không sửa bảng §5 đã ký, xem 1.4); `test-plan.md`, `performance-load.md`, `test-strategy.md`, `e2e-testing.md` và các tài liệu khác nhắc "Phase 8" theo nghĩa kiểm thử tải *(Xong 2026-11-08. Ngoài các file tên ở trên còn đổi: `database-schema.md`, `payment.md`, `registration.md`, `observability.md`, `deployment-and-cicd.md`, `mocking-guidelines.md`, `phase-template.md` (cũng "1–8" → "1–9", "eight phases" → "nine phases"), `backend-coding-standards.md`, `web-coding-standards.md:551`; comment, không đổi hành vi, ở 4 file Java, `k8s/overlays/loadtest/resource-patch.yaml`, `tests/load/k6/README.md`, `tests/load/k6/adversarial.js`; `test-plan.md` thêm dòng Phase 8 — Web UI ở cả hai bảng; `CLAUDE.md` chỉ đổi câu "is next". Ghi chú đánh số lại thêm vào đầu `phase-3.md`, `phase-7.md` (đã ký), ba checklist đã đóng `phase-2/3/7-*.md` và `phase-7-cluster-checks.md`: **các checklist đã đóng và báo cáo cluster không có tên trong 1.4, nên Claude Code áp cùng quy tắc "chỉ thêm ghi chú"** vì sửa D3/D12/D29 là sửa bản ghi quyết định đã có ngày; owner đổi ý được.)*
- [x] 1.4 Kiểm tra bằng `grep -rn "Phase 8" docs checklists CLAUDE.md`: mọi chỗ còn lại đều nghĩa là Web UI. Sign-off đã ký (`phase-1.md` … `phase-7.md`) là hồ sơ lịch sử: **chỉ thêm ghi chú**, không sửa nội dung đã ký *(Xong 2026-11-08. Lệnh grep đúng chữ không thể trống, vì hồ sơ lịch sử giữ "Phase 8 = kiểm thử tải". Kết quả kiểm: mọi hit còn lại là (i) Web UI: `proposal.md:331`, `test-plan.md` §1 và bảng exit criteria, `CLAUDE.md`, file này; hoặc (ii) nằm trong sáu file lịch sử có ghi chú đánh số lại ở đầu file: `release-sign-offs/phase-3.md`, `phase-7.md`, `checklists/phase-2-registration-payment.md`, `phase-3-concurrency-protection.md`, `phase-7-kubernetes.md`, `reports/phase-7-cluster-checks.md`. Quét thêm các cách viết khác (`P8`, "1–8", "1…8", "eight phases", cột Phase của matrix, `README.md` gốc, `.github/`, `observability/`, `apps/*/README.md`): không còn sót ở file sống.)*

## 2. Nền móng design system — cổng G1

- [x] 2.1 Dọn `src/styles/index.css` (C67); đặt token bảng màu B (0A) cho `:root` và `.dark`; `@theme inline` ánh xạ thêm `primary-text`, `primary-soft`, `warning`, `info`, `success` `[→ M-86]` *(Xong 2026-11-08. Đã xoá toàn bộ đồ thừa scaffold (`--text`, `--bg`, `#root`, `h1`…) và khối `@media (prefers-color-scheme: dark)`: **chế độ tối chỉ còn là lớp `.dark`** để nút chuyển không thua hệ điều hành; `color-scheme` là `light`/`dark` theo từng chế độ; vòng focus là `outline-ring` đặc, không `/50` (nửa độ đục tụt dưới 3:1). `vite-plugins/__tests__/design-tokens-contrast.test.ts` (61 test) đọc `index.css` và kiểm mọi cặp của bảng 0A cộng các cặp suy ra, kể cả nút destructive nền `/10`, `/20`: `[→ M-86]`. **Hai điểm lệch/tự chọn, owner xem ở G1:** (1) shadcn dùng `--accent` làm màu nền khi hover/chọn của nút ghost, menu và select; ánh xạ hổ phách vào đó sẽ tô vàng mọi mục menu (ngược D109), nên `--accent` là xanh nhạt `#E6EFE7`/`#1C2D22` và hổ phách là token riêng `--highlight`/`--highlight-foreground` (hex giữ đúng bảng 0A). (2) Bảng 0A không ghi màu rãnh thanh ghế và nền `muted`: lấy từ trang xem trước owner đã chọn, `#ECEFEA`/`#1D2A21`, cho ra đúng 4,32 và 3,07 của bảng (một lần thử đầu bằng `border` cho 2,81, sai). `secondary`, `accent`, sidebar, `chart-*` là giá trị suy ra trong bảng màu B, `chart-*` tạm đến 6.8. Thêm `--primary-hover` vì `hover:bg-primary/80` của nút mặc định tụt dưới 4,5:1 với chữ trắng; 2.6 phải dùng nó.)*
- [x] 2.2 `@fontsource/be-vietnam-pro`: **kiểm tra có file subset `vietnamese` cho mọi weight dùng (400/500/600/700)**; bỏ `@fontsource-variable/geist`; thang chữ và khoảng cách ghi trong tài liệu design system *(Xong 2026-11-08, trừ thang chữ và khoảng cách, ghi ở 2.5. `@fontsource/be-vietnam-pro` `5.3.0` (ghim đúng), có `vietnamese` cho cả bốn weight (11,5–12,5 KB, trên ngưỡng 4 KB nên Vite không nhúng `data:`); sau `npm run build` có đủ 12 file woff2 trong `dist/assets`, `data:font` = 0 trong CSS, không còn Geist. `index.html`: `lang="vi"`, `<title>UniHub</title>`. `schema.gen.ts` không đổi sau build. Hệ quả cần biết: bố cục cũ dựa vào `#root` rộng 1126 px và `text-align: center` của scaffold, nay không còn; các trang được dựng lại ở Mục 4–6.)*
- [x] 2.3 **Trang spike CSP** (chỉ ở bản dev/test): Dialog, Select, Popover, DropdownMenu, Tabs, Sheet của Radix/shadcn, Sonner, thư viện biểu đồ, NumberFlow; chạy Playwright CSP guard ba engine. Ghi kết quả từng thư viện, chốt Q9 `[→ M-86]` *(Xong 2026-11-08 — **Q9 chốt, có hai điều owner cần xem ở G1**. Trang `/__spike` chỉ có trong bản build đặt `VITE_SPIKE=true` (hằng số lúc build; bản thường 544 KB JS, 0 chỗ chứa chữ của trang spike). `tests/e2e/playwright.local.config.ts` + `specs-local/spike.spec.ts`: bản build production thật (có thẻ CSP `style-src 'self'`, không `unsafe-inline`) chạy bằng `vite preview` trên 127.0.0.1:4399, ba engine, dùng đúng `watch()` của guard cũ (tách ra `support/page-health.ts`, `fixtures.ts` chỉ import lại). Có đối chứng: test đầu kiểm thẻ CSP có trong bản build và một `<style>` chèn tay bị chặn, nếu không mọi kết quả là đậu giả. Mỗi thư viện một trang `?only=…` để vi phạm gán đúng thủ phạm; mỗi test còn kiểm thành phần *có hiện và có style* (không chỉ "không vi phạm"). **51/51 pass** (17 mỗi engine: 10 spike + 7 theme của 2.4) sau khi xử lý bên dưới. Lệnh: `npm run test:local` trong `tests/e2e`.
  - **Đạt nguyên trạng:** Tabs, Popover, DropdownMenu*, Dialog*, **AlertDialog*** (mở, khoá cuộn, hai nút đều đóng được), Sheet* (*sau điểm 1), **recharts** (cột + vòng, 0 vi phạm cả ba engine), và **ô `date` / `datetime-local` native**: nhận giá trị, style bằng class của ta, **0 báo cáo trên cả Chromium, Firefox và WebKit** (WebKit báo `<select>` native nhưng không báo ô ngày). Đề xuất ở G1: ô ngày native, **không** thư viện DateTimePicker (popup chọn ngày là của hệ điều hành, không đổi theme được).
  - **Bị loại (D125 "bỏ hoặc tự làm"), cùng bằng chứng ở ba engine:** **Sonner** — module tự chèn hai thẻ `<style>` lúc import (`style-src-elem`, WebKit thêm `style-src-attr`), `[data-sonner-toaster]` ở `position: static` tức không có CSS; không có chế độ tắt chèn CSS; đã gỡ khỏi `package.json`; toast sẽ tự làm ở 2.6 (không dùng `shadcn add sonner` vì nó kéo `next-themes`). **NumberFlow** — chèn `<style>` lúc tải (`style-src-elem`), cột chữ số hiện thô `0123456789…`; đã gỡ; hiệu ứng đổi số ghế bỏ (hoặc tự làm bằng CSS nếu G1 muốn). **Radix Select** (`@radix-ui/react-select` 2.3.7) — `SelectViewport` luôn render một thẻ `<style>` (`[data-radix-select-viewport]{scrollbar-width:none…}`), mỗi lần mở danh sách bị `style-src-elem` chặn, ở cả ba engine, cả có và không có `<form>`; `shared/ui/select.tsx` đã xoá. Không dùng được trừ khi thêm hash vào `style-src`: **đó là đổi CSP, quyền của owner, tôi không làm.** Màn hình đang cần chọn từ danh sách? Catalog lọc diễn giả/địa điểm (ô chữ) và ngày (ô ngày); quản trị dùng tab trạng thái và công tắc phí: chưa có chỗ nào bắt buộc `Select`. Nếu sau này cần: hoặc hash, hoặc `<select>` native (đẹp trên điện thoại) nhưng WebKit báo `style-src-attr` rỗng cho mọi `<select>` (ca ngoại lệ checkout 2026-11-06) nên phải mở rộng ngoại lệ đó, cũng của owner.
  - **Điểm 1 — Radix modal cần một bản thay `react-remove-scroll` (owner xác nhận ở G1).** Dialog, Sheet, Menu, DropdownMenu (và Select) khoá cuộn trang bằng `react-remove-scroll`, gói này chèn `<style>` lúc chạy qua `react-style-singleton`; mỗi lần mở một modal, ba engine báo `style-src-elem` (hash `sha256-47DEQ…` và `sha256-nzTgY…`). Cách xử lý: `src/shared/lib/csp-safe-remove-scroll.tsx` khoá cuộn bằng CSSOM (`body.style.overflow` + bù độ rộng thanh cuộn, đếm lớp lồng nhau), `vite.config.ts` đặt alias `^react-remove-scroll$` về file đó (Vitest dùng cùng cấu hình), Radix không bị sửa. Spec kiểm `document.body.style.overflow` là `hidden` khi mở và trả về như cũ khi đóng; bản build spike không còn chuỗi `data-scroll-locked`. Cái bị bỏ: gói thật còn chặn cuộn bánh xe/cảm ứng trên phần tử cuộn được *ngoài* lớp đang mở; ở đây body ngừng cuộn và overlay nhận con trỏ, đủ cho modal. **Thay thế nếu owner không muốn alias:** thêm hai hash đó vào `style-src` (đổi CSP, dễ vỡ khi nâng phiên bản gói). `AlertDialog` (task 2.6) dựng trên cùng primitive nên phải qua lại spike khi thêm.
  - Hạ tầng: cổng 4173 mặc định của Vite thường bị tiến trình khác trên máy chiếm (một `node server.mjs --dev` không phải của dự án này, trả 404 cho `/login`), nên dùng 4399 và địa chỉ `127.0.0.1` cho cả server lẫn `baseURL`. `npm audit --omit=dev`: 15 (1 thấp, 6 vừa, 8 cao) trước và sau, không thêm do các thay đổi này (thuộc `shadcn`, `vite`, `zod`).)*
- [x] 2.4 Nút chuyển sáng/tối theo Q8: `/theme-init.js`, store Zustand chỉ giữ lựa chọn (`light`/`dark`/`system`); kiểm tra không nháy ở lần tải đầu trên ba engine `[M-86]` *(Xong 2026-11-08. `public/theme-init.js` là script cổ điển chặn trong `<head>`, sau thẻ CSP, trước script module; bản build chép nguyên vẹn (`cmp` giống hệt). `shared/store/theme-store.ts` (§8: trạng thái UI không nghiệp vụ ở `shared/store/`) chỉ giữ lựa chọn, áp lớp `.dark`, theo hệ điều hành khi lựa chọn là `system` ngay cả khi trang đang mở, theo tab khác qua sự kiện `storage`, áp lại lúc khởi động (phòng trang cũ trong cache không có script); `localStorage` luôn trong `try/catch`. Hằng số khoá/giá trị ở `shared/lib/theme-storage.ts` không phụ thuộc DOM để `vite-plugins/__tests__/theme-init.test.ts` chạy **chính file script** trong `vm` với giả lập `localStorage`/`matchMedia`, nối khoá của script với khoá của store (lệch là test đỏ). Nút `ThemeToggle` (Sáng / Tối / Theo hệ thống, `aria-pressed`) tạm gắn vào `AppLayout`, 2.7 sẽ thay khung. Không nháy: `specs-local/theme.spec.ts` hỏi lớp `.dark` *tại khoảnh khắc `<body>` được tạo* (sau script ở `<head>`, trước mọi lần vẽ), 7 test × 3 engine, gồm hệ điều hành tối, lựa chọn lưu thắng hệ thống cả hai chiều, nút chuyển + tải lại, theo hệ thống khi đổi, màu nền thật `rgb(250, 250, 247)`/`rgb(10, 18, 13)`, và **đối chứng**: chặn `theme-init.js` thì trang dựng ở chế độ sáng, nên kiểm tra có thể đỏ. Không vi phạm CSP từ script.)*
- [x] 2.5 Tài liệu `docs/guidelines/web-design-system.md`: token, bảng tương phản, thang chữ, khoảng cách, bo góc, đổ bóng, chuyển động (thời lượng, easing theo `emil-design-eng`), quy tắc trạng thái (màu + chữ/biểu tượng); link từ `web-coding-standards.md` (§ mới) và nêu vị trí thành phần dùng chung `shared/ui` *(Xong 2026-11-08, viết tay, không dùng script `--persist`, nên không có thư mục `design-system/` ở gốc repo (C69). Có: token và bảng tương phản đo từ `index.css`, quy tắc trạng thái theo enum thật của contract, kết quả spike và chỗ lệch so với mặc định shadcn (bảng §8), link từ `web-coding-standards.md` §15. Thang chữ, khoảng cách, bo góc, đổ bóng, độ rộng nội dung ghi là *proposed*: chốt khi owner duyệt G1.)*
- [ ] 2.6 Thành phần dùng chung trong `shared/ui` (không kiến thức nghiệp vụ, Rules #4): Button (biến thể), Input, Select, Textarea, Badge, Card, Skeleton, EmptyState, ErrorState, Dialog/AlertDialog, Tabs, Toast, Pagination, DateTimePicker (theo kết quả 2.3)
- [ ] 2.7 Hai khung: `StudentLayout` (top nav, menu điện thoại, chuông thông báo, nút chế độ) và `AdminLayout` (sidebar, breadcrumb, gập trên màn hình hẹp); route `/admin/*` bọc `RequirePermission anyOf` (S9)
- [ ] 2.8 **G1:** mockup HTML (token thật) của design system và hai khung; owner duyệt

## 3. Backend tối thiểu (sau Q1, Q3–Q6)

- [ ] 3.1 Contract: `WorkshopSummary` mở rộng (Q1); operation danh sách quản trị (Q3); hai operation ảnh bìa (yêu cầu URL tải lên, xác nhận) theo mẫu brochure; `coverImageUrl` trong `Workshop`. `api:types` sinh lại `schema.gen.ts` `[→ M-87, M-84]`
- [ ] 3.2 Migration `V9` chỉ mở rộng (`cover_image_key`, `pending_cover_image_key` hoặc tương đương); qua `MigrationLintTest` và `MigrationCompatibilityIT` `[→ M-84]`
- [ ] 3.3 Danh sách quản trị: use case + controller đọc Master, quyền `WORKSHOP_EDIT`, IT cho lọc theo trạng thái, phân trang, `403` khi thiếu quyền, `DRAFT` có trong kết quả **[M-87]**
- [ ] 3.4 Ảnh bìa: URL tải lên presigned (chỉ JPEG/PNG/WebP, giới hạn kích thước), xác nhận đồng bộ (magic bytes, kích thước, `Content-Type`), key bất biến, `503 storage-unavailable` khi bucket hỏng như brochure; IT trên Testcontainers MinIO **[M-84]**
- [ ] 3.5 Đường lưu trữ cùng origin (Q4, Q5): nginx Web image, proxy Vite, `k8s/` (Istio gateway route tới MinIO, policy đọc ẩn danh chỉ `covers/`); header `nosniff` và `Content-Type` cố định; **một IT làm PUT presigned thật đi qua proxy** **[M-88]**
- [ ] 3.6 Cập nhật `docs/technical-design/modules/workshop.md` (phần brochure/S3 và ảnh bìa), `database-schema.md` (`V9`), `deployment-and-cicd.md` (route lưu trữ); đóng `web-coding-standards.md` §13 #10 (C64)
- [ ] 3.7 Không consumer mới, không đổi tham số hàng đợi (Ranh giới); `ConsumerRedeliveryCoverageTest` không đổi

## 4. Màn hình sinh viên — cổng G2 (S1–S4, S16)

- [ ] 4.1 **G2:** mockup trang chủ, catalog (có trạng thái rỗng, đang tải, lỗi), chi tiết, đăng nhập, trang lỗi; owner duyệt
- [ ] 4.2 Trang chủ S1: giới thiệu ngắn, ô tìm (gửi sang `/workshops?q=`), "Sắp diễn ra tuần này"
- [ ] 4.3 Catalog S2: bộ lọc là trạng thái URL (chia sẻ link được), nhóm theo ngày, phân trang, debounce ô tìm, `400` khi `dateFrom` sau `dateTo` hiện lỗi gần trường **[M-82]** `[§5 r10]`
- [ ] 4.4 Thẻ workshop: ảnh bìa hoặc ảnh tự sinh (Q7), ngày giờ, tiêu đề, diễn giả, địa điểm, nhãn Miễn phí / Có phí, mức ghế (Q2)
- [ ] 4.5 Chi tiết S3: ảnh bìa, tóm tắt AI (có trạng thái "đang tạo tóm tắt"), diễn giả + giới thiệu, địa điểm, thời gian, số ghế trực tiếp (nhịp giữ nguyên), nút đăng ký với các trạng thái: chưa đăng nhập, hết chỗ, đã đăng ký, workshop bị hủy
- [ ] 4.6 Đăng nhập S4, trang lỗi S16 (404, không có quyền, `503` có nút thử lại)

## 5. Đăng ký, thanh toán, vé, thông báo — cổng G3 (S5–S8)

- [ ] 5.1 **G3:** mockup luồng đăng ký miễn phí và có phí, thanh toán, đếm ngược giữ chỗ, danh sách đăng ký, e-ticket, hộp thư; owner duyệt
- [ ] 5.2 Luồng đăng ký: một `Idempotency-Key` cho mỗi ý định, dùng lại khi thử lại; `409`/`503` theo bảng UX §6; **không** cập nhật lạc quan
- [ ] 5.3 Thanh toán S5: đếm ngược 15 phút nổi bật, trạng thái thanh toán đến cuối (gồm `REFUND_REQUIRED` → `REFUNDED`); dropdown token thử chỉ ở build chế độ thử
- [ ] 5.4 Đăng ký của tôi S6: nhóm Sắp tới / Đã qua, trạng thái có chữ + biểu tượng, link `Xem vé`
- [ ] 5.5 E-ticket S7: QR mức Q giữ nguyên kích thước quét được, độ sáng cao ở cả chế độ tối (QR luôn trên nền trắng), thông tin workshop, trạng thái
- [ ] 5.6 Hộp thư S8: mới nhất trước, refetch khi focus cửa sổ (giữ nguyên), link tới vé

## 6. Khu quản trị — cổng G4 (S9–S15)

- [ ] 6.1 **G4:** mockup danh sách quản trị, form tạo/sửa, đổi lịch, publish, hủy, tải ảnh bìa, tải brochure, báo cáo; owner duyệt
- [ ] 6.2 Danh sách S10: tab theo trạng thái, tìm, phân trang, hành động nhanh theo quyền **[M-83]**
- [ ] 6.3 Form tạo S11 (RHF + Zod ở `features/administration/model/`, ánh xạ rõ sang `CreateWorkshopRequest`; ràng buộc `startTime`/`endTime`, `isPaid`/`price`) **[M-83]**
- [ ] 6.4 Sửa / đổi lịch S12: `updateWorkshop` gửi đúng `version` (xung đột `409` hiện thông báo tải lại); đổi giờ thì hộp xác nhận ghi "Sinh viên đã đăng ký sẽ được thông báo" **[M-83]**
- [ ] 6.5 Publish và hủy: AlertDialog xác nhận, hủy nói rõ hệ quả (thông báo, giải phóng chỗ) **[M-83]**
- [ ] 6.6 Tải ảnh bìa S13: kéo thả, xem trước, kiểm tra loại/kích thước phía client (UX, backend là nơi thực thi), tiến trình, lỗi `503 storage-unavailable` **[M-84]**
- [ ] 6.7 Tải brochure S14: PDF, tiến trình, trạng thái tóm tắt AI hiện ở chi tiết **[M-85]**
- [ ] 6.8 Báo cáo S15: ô số, biểu đồ (Q10), danh sách điểm danh; giữ region `Báo cáo workshop`
- [ ] 6.9 Mọi nút và route quản trị gate theo permission, không theo tên vai trò (Rules #13)

## 7. Hoàn thiện chất lượng

- [ ] 7.1 Chuyển động: `find-animation-opportunities` rồi `review-animations` trên toàn app; tôn trọng `prefers-reduced-motion`
- [ ] 7.2 `break-ui` trên thẻ workshop, danh sách quản trị, e-ticket, báo cáo (tên dài, tiếng Việt dài, số lớn, danh sách rỗng)
- [ ] 7.3 `mobile-native` trên các màn hình sinh viên (vùng an toàn, ô nhập không phóng to, hover dính)
- [ ] 7.4 Kiểm tra a11y: điều hướng bàn phím mọi luồng, focus nhìn thấy được, nhãn form, `aria-live` cho số ghế và đếm ngược (không đọc mỗi 5 s), tương phản cả hai chế độ **[M-86]**
- [ ] 7.5 Ngân sách bundle: ghi kích thước JS/CSS trước và sau; tách route quản trị bằng lazy load

## 8. Kiểm thử

- [ ] 8.1 Giữ accessible name và chuỗi Playwright (C68), hoặc sửa spec cùng commit
- [ ] 8.2 Vitest + RTL cho mọi màn hình mới/sửa (MSW, factory hiện có); kiểm thử lọc catalog qua URL, form quản trị, upload
- [ ] 8.3 Playwright: M-69 mở rộng sang tìm/lọc/phân trang **[M-82]**; M-70 thành hành trình đầy đủ qua trình duyệt (tạo, publish, sửa, đổi lịch, tải ảnh bìa và brochure, đọc báo cáo) `[§5 r2]`; kiểm tra nút chuyển chế độ **[M-86]**
- [ ] 8.4 CSP guard trên mọi spec mới, ba engine; không thêm ngoại lệ mới khi chưa có owner duyệt

## 9. Cluster & CI/CD

- [ ] 9.1 CI xanh cả mười job; ghi thời gian job `web` và `integration-test` trước và sau
- [ ] 9.2 `make deploy-dev` với image CI của commit cuối; PUT ảnh bìa và brochure thật qua Istio gateway; đọc ảnh bìa ẩn danh **[M-88]**
- [ ] 9.3 `make smoke` và `make e2e` (13 test cũ + test mới) xanh trên cluster dưới STRICT. *(Thêm từ 2.3/2.4: một probe `transport` kiểm `GET /theme-init.js` qua gateway trả `javascript` chứ không phải HTML shell, trên image CI thật; allowlist `.dockerignore` đã đọc và cho `apps/web/public/`, test tĩnh `theme-init.test.ts` ghim HTML và `try_files`, nhưng chưa có bằng chứng trên image.)*

## 10. Tài liệu & Test Matrix

- [ ] 10.1 Dòng matrix mới (đề xuất, chốt cùng Q): **M-82** catalog tìm/lọc/phân trang trên Web (REQ §1.3.1) · **M-83** quản trị workshop trên Web: tạo, sửa, đổi lịch, publish, hủy (REQ §1.3.3) · **M-84** tải và hiển thị ảnh bìa (D114) · **M-85** tải brochure trên Web (REQ §1.3.4) · **M-86** chế độ sáng/tối, tương phản AA, bàn phím (D110, D115) · **M-87** danh sách quản trị gồm `DRAFT`, chỉ với `WORKSHOP_EDIT` (Q3) · **M-88** đường lưu trữ cùng origin: PUT presigned qua proxy, chỉ ảnh raster, `nosniff` (Q4–Q6)
- [ ] 10.2 Cập nhật `web-coding-standards.md` (§3 feature map: `administration`; design system; §13 #10), `workshop.md`, `CLAUDE.md` (trạng thái Phase 8, "Phase 9 is next")
- [ ] 10.3 Đếm lại matrix từ cột Status

## 11. Đóng Phase 8 (Exit / Sign-off)

- [ ] 11.1 Exit criteria: mọi màn hình S1–S16 có ở Web; M-82 … M-88 `Passing`; M-69, M-70 `Passing` với nội dung mới; G1–G4 owner đã duyệt; CI xanh
- [ ] 11.2 `docs/testing/reports/release-sign-offs/phase-8.md` theo `phase-template.md`; §5 ghi C70 và các mục hoãn của D105
- [ ] 11.3 Owner ký

---

## Ghi chú cho Claude Code

- Trước khi viết code Web: đọc "Rules at a Glance" của `web-coding-standards.md`, rồi chỉ các mục liên quan. Trước code backend: tương tự với `backend-coding-standards.md`.
- Mỗi cổng G là điểm dừng: dựng mockup, gửi link, **chờ owner duyệt**, không code nhóm màn hình đó trước.
- Dùng skill: `ui-ux-pro-max` (tra phong cách, UX, stack shadcn/react), `design-system` (token), `ui-styling` (shadcn/Tailwind), `emil-design-eng` + `animate` / `review-animations` (chuyển động), `break-ui`, `mobile-native`, `dataviz` (biểu đồ báo cáo). Không dùng gợi ý nào vi phạm CSP hay Rules at a Glance.
- Không commit `.gitleaksignore` mới hay ngoại lệ CSP mới khi chưa có owner duyệt.

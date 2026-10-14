# Phase 2 — Registration & Payment: Checklist Triển Khai

> **Mục đích file này:** checklist *thực thi* cho Phase 2, cùng vai trò với `phase-1-foundation.md`. Nó không thay thế `docs/testing/strategy/test-plan.md` §1 (nguồn thẩm quyền cho exit criteria), `docs/testing/reports/test-matrix.md` (trạng thái từng test) hay `docs/testing/reports/release-sign-offs/phase-template.md` (sign-off). File này chỉ **tổng hợp và sắp thứ tự công việc**, trỏ vào 3 file đó làm bằng chứng. Nơi có mâu thuẫn, 3 file kia luôn đúng.
>
> **Cách dùng:** tick `[x]` khi xong thật, không tick trước. Mỗi mục nên có commit tương ứng. Cập nhật **Trạng thái tổng thể** sau mỗi buổi làm việc.

| Trường | Giá trị |
|---|---|
| Phase | 2 — Registration & Payment |
| Ngày bắt đầu | — |
| Trạng thái tổng thể | `☑ Chưa bắt đầu` / `☐ Đang làm` / `☐ Chờ sign-off` / `☐ Hoàn tất` |
| Nhánh làm việc | `main` (push thẳng, không PR — `test-matrix.md` §4.4) |
| INV trong scope | INV-02, INV-03, INV-04, INV-05, INV-10 (`test-plan.md` §3) |
| Matrix rows | M-18 … M-27 (Phase 2), M-08 (hoãn từ Phase 1 sang đây, `phase-1.md` §5), M-75, M-77 (derived requirement Phase 2, thêm vào `test-matrix.md` ở Bước 2 — D2, D5), M-76 (derived, Phase 3 — chỉ tài liệu ở đây), M-78 (dòng mới đề xuất cho web Phase 2 — Mục 4) |
| Tham chiếu | `proposal.md` §15 · `test-plan.md` §1 (dòng Phase 2) · `registration.md`, `payment.md` · `messaging.md` §2/§4/§5 · `database-schema.md` §4/§5/§8 · `distributed-coordination.md` §2 · `backend-coding-standards.md` §7, §8, §19–§21, §25 · `web-coding-standards.md` |

### Ký hiệu nhãn `[M-xx]` ở đầu mỗi task

| Nhãn | Nghĩa |
|---|---|
| **`[M-20]`** (đậm, đứng đầu) | Task này **là** bằng chứng của dòng đó — xong task thì dòng có thể lên `Passing` |
| `[→ M-19]` | Task là **tiền đề / hạ tầng** cho dòng đó; tự nó không làm dòng lên `Passing` |
| `[M-33 · P3]` | Dòng thuộc phase khác (P3 = Phase 3…): task chỉ chuẩn bị, bằng chứng nằm ở phase kia — **không** cập nhật Status dòng đó trong Phase 2 |
| `[§4.5]` | Task đóng một mục hoãn trong `test-matrix.md` §4.5 (dòng gốc ghi kèm) |
| `[— không dòng]` | Không có dòng matrix nào; lý do ghi ngay sau. Nếu task cần bằng chứng riêng, thêm dòng mới theo skeleton `test-matrix.md` §2.3 |

**Bảng tra ngược (dòng → task):**

| Dòng | INV | Task chứng minh | Task tiền đề |
|---|---|---|---|
| M-08 | — | 1.5 | 1.5 |
| M-18 | — | 3.2 | 0.Q3, Q4, Q6, Q7, Q11 · 1.2 · 2.1 · 2.2 · 3.1 |
| M-19 | — | 3.5 | 0.Q2, Q3, Q4, Q6, Q7, Q11, Q14 · 1.3 · 2.1 · 2.2 · 3.1 |
| M-20 | INV-02 | 3.3 | 0.Q1, Q5 · 1.2 |
| M-21 | INV-02 | 3.3 | 0.Q1 · 1.2 |
| M-22 | INV-03 | 3.8 | 1.1 |
| M-23 | INV-04 | 3.6 | 1.3 · 3.5 (webhook) |
| M-24 | INV-05 | 3.7 | 0.Q5, Q8 · 3.5 (adapter Stripe) |
| M-25 | INV-10 | 3.4 | 1.3 |
| M-26 | INV-02, INV-04 | 3.9 | M-20, M-21, M-23 |
| M-27 | INV-05 | 3.9 | M-24 |
| M-75 *(derived)* | — | 3.5 (nhánh bù trừ, tới `REFUND_REQUIRED`) | D2 · 1.3 |
| M-76 *(derived, P3)* | — | — (Phase 3) | D2 |
| M-77 *(derived)* | — | 3.5 (`chargePayment`) | D5 · D1 |
| M-78 *(mới)* | — | 4 | 0.Q6, Q12–Q15 |

**Trạng thái gỡ chặn** (cập nhật 2026-10-14, sau khi chốt C2/C6/C10/C11/G1/G2 — chi tiết ở Mục 0A):

| Dòng | Trạng thái | Đã gỡ bởi | Còn chờ |
|---|---|---|---|
| M-20 | **Unblocked** | D1 (Q1 + C2, G1, G2); Q5 được D1 giải quyết theo hệ quả | Tài liệu sở hữu cập nhật ở Bước 2 (C1–C5, C24–C27); C26 cần một thay đổi nhỏ ở shared kernel khi code |
| M-21 | **Unblocked** | D1 | Như M-20 (cùng migration `V3`) |
| M-23 | **Unblocked** về G2 | D1 ràng buộc 8 | — (vẫn theo thứ tự Mục 3.6) |
| M-19 | **Unblocked** về cơ chế xác nhận, bù trừ (Phase 2) và thử lại | D2 (Q2 + C10, C11), D5 (C6); seat claim D3, D4 | C28 (hình dạng HTTP của decline đồng bộ); Q4, Q6, Q7, Q14 chưa chốt |
| M-75 | **Unblocked** (derived, Phase 2) | D2 | Dòng được thêm vào `test-matrix.md` ở Bước 2 |
| M-77 | **Unblocked** (derived, Phase 2) | D5 | C28; Q6 cho mã lỗi máy đọc được của `409` |
| M-18 | Seat claim đã rõ | D3 (Q3), D4 (Q11) | Q4, Q6, Q7 chưa chốt |

---

## Ranh giới Phase 2 ↔ Phase 3 (đọc trước khi làm)

`proposal.md` §15 tách *luồng* đăng ký/thanh toán (Phase 2) khỏi *bảo vệ đồng thời* (Phase 3). Hệ quả cụ thể cho checklist này:

| Thuộc Phase 2 | Để Phase 3 (không làm ở đây) |
|---|---|
| Seat claim trong **một transaction** với `registrations` write, chặn bởi `version` + CHECK `seats_taken <= capacity` (đã có trong `V1`) — đây là lớp quyết định, phải có ngay từ đăng ký đầu tiên (M-18, M-19) | Bằng chứng 0% overbooking dưới tải đồng thời: M-28 … M-32 |
| Port `SeatLock` và lời gọi `lock → transaction → unlock` đúng §19.2 (theo quyết định Q11) | Redis-outage → `503` (INV-08, M-34) — nếu Q11 chọn Redisson ngay thì code fail-closed có thể đã có, nhưng **test** vẫn là Phase 3 |
| Đăng ký paid vào `RESERVED` với `hold_expires_at = now + 15m` (M-19) | Consumer hết hạn giữ chỗ `hold.expired` → `EXPIRED` + trả ghế (INV-07, M-33) |
| Job đơn instance qua port `JobLock` (`InProcessJobLock`, đúng tiền lệ M-09) | `RedissonJobLock` thay thế + chứng minh 1-instance (INV-09, M-35) |
| Outbox row commit cùng transaction (INV-10 nửa atomicity, M-25); relay chạy được về mặt chức năng | Chứng minh relay không bỏ sót row + consumer idempotent dưới redelivery là Phase 4 (M-40, M-41) |

> ⚠ Hệ quả có chủ đích: trong Phase 2, ghế `RESERVED` của đăng ký paid **không tự hết hạn** cho tới Phase 3 (M-33). Đó là gap đã biết, ghi vào `phase-2.md` §5 lúc sign-off — không "sửa nhanh" bằng một cron polling (`architecture.md` §4.3 đã loại polling).

---

## 0. Quyết định phải chốt trước khi code (blockers)

Nguồn: `backend-coding-standards.md` §27, `web-coding-standards.md` §13, `IMPLEMENTATION-READINESS.md`, `paths/user.yaml`. Theo `CLAUDE.md`: task phụ thuộc mục còn mở thì **dừng lại hỏi**, không tự đoán. Tick khi quyết định đã được **ghi vào tài liệu sở hữu** (cột cuối), không phải khi đã nghĩ xong. Mục 0 không làm dòng nào lên `Passing` — mọi nhãn ở đây đều là tiền đề.

### 0.1 Backend

> **Chốt 2026-10-13:** Q1, Q2, Q3, Q11 (→ D1–D4 ở Mục 0A); Q5 được D1 giải quyết theo hệ quả. **Chốt 2026-10-14:** C2, C6, C10, C11, G1, G2 (→ D1, D2, D5). Các ô dưới vẫn **chưa tick** vì quyết định chưa được ghi vào tài liệu sở hữu, và đối soát (Mục 0B) cho thấy các tài liệu đó cần sửa trước.

- [ ] `[→ M-20, M-21]` **Q1 — ✅ Đã chốt (D1), chờ sửa tài liệu C1–C5.** Schema `idempotency_keys` lưu Result, không lưu HTTP response** (§27 #1, **blocker M-20**): thay `response_status`/`response_body` bằng `result_type VARCHAR` + `result_body JSONB`; sửa chữ ở `registration.md` §4, `payment.md` §8. → `database-schema.md` §8.1, rồi migration `V3__…` (Mục 1.2)
- [ ] `[→ M-19]` **Q2 — ✅ Đã chốt (D2), chờ sửa tài liệu C7–C13.** Routing key "payment succeeded → registration `CONFIRMED`"** (§27 #8, **blocker M-19**): đặt tên theo quy ước `unihub.<domain>.<entity>.<action>`, queue đích, retry chain riêng (§4). Nửa còn lại của #8 ("workshop cancelled → hủy registrations, trả ghế") không có dòng matrix nào ở Phase 2 — có thể để mở, ghi rõ. → `messaging.md` §2, `registration.md`, `payment.md`
- [ ] `[→ M-18, M-19]` `[M-28 · P3]` **Q3 — ✅ Đã chốt (D3), chờ sửa tài liệu C14–C18.** `workshops.version` dùng chung cho seat claim và organizer edit** (§27 #9): giữ chung version, hay seat counter chỉ dựa vào atomic increment + CHECK. Ảnh hưởng trực tiếp tới seat-claim SQL của Mục 3.1 → `database-schema.md` §3.1, `registration.md` §2
- [ ] `[→ M-18, M-19]` **Q4 — Permission cho `POST /api/v1/registrations`** (§27 #10, web §13 #12): contract hiện không khai báo (mọi user đã xác thực + ownership). Chốt "không cần" hoặc đặt tên code mới + migration seed cho Student → `auth-and-identity.md`, `user.md`
- [ ] `[→ M-20, M-24]` **Q5 — ✅ Giải quyết theo D1 (lỗi hạ tầng không được ghi), chờ sửa `payment.md` §8 (C4).** `503` của `chargePayment` có được ghi idempotency không** (web §13 #6): `payment.md` §8 ghi lại response circuit-open; §21 rule 5 nói lỗi tạm thời không ghi; contract nói "safe to retry". Đề xuất: sửa `payment.md` §8 theo §21 → `payment.md` §8
- [ ] `[→ M-18, M-19, M-78]` **Q6 — Lý do `409` máy đọc được** (web §13 #5): "sold out" vs "already registered" (và 2 nguyên nhân `409` của `chargePayment`). Đề xuất: `type` URI riêng cho từng lý do → `common/error.yaml`, `paths/registration.yaml`, `paths/payment.yaml`, `backend-coding-standards.md` §17
- [ ] `[→ M-18, M-19]` **Q7 — Mật khẩu cho tài khoản Student đồng bộ từ legacy** (`paths/user.yaml` login description: first-login setup **chưa có thiết kế**). Phase 2 cần ít nhất một Student đăng nhập được để đăng ký. Lựa chọn: (a) chỉ seed tài khoản test có `password_hash` cho dev/test, thiết kế first-login để sau; (b) thiết kế first-login ngay. → `user.md`, `paths/user.yaml`
- [ ] `[→ M-24]` **Q8 — `minimumNumberOfCalls` của breaker** (`IMPLEMENTATION-READINESS.md` E2) → `payment.md` §2
- [ ] `[— không dòng]` **Q9 — Phase sở hữu test chữ ký webhook S-17** (E6) — S-17 chưa gắn dòng matrix nào. Đề xuất Phase 2, vì webhook được xây ở đây; nếu đồng ý thì thêm dòng mới (M-79) → `security-testing.md` §2.2, `test-matrix.md` §2.2
- [ ] `[M-54 · P6†]` `[§4.5 — M-04]` **Q10 — AuditTrail**: xây port `AuditTrail` ngay ở Phase 2 (registration/payment là thao tác bị audit, `backend-coding-standards.md` §12) hay tiếp tục hoãn như M-04. Nếu hoãn: thêm dòng vào `test-matrix.md` §4.5 cho registration/payment
- [ ] `[→ M-18, M-19]` `[M-34 · P3]` **Q11 — ✅ Đã chốt (D4), chờ sửa tài liệu C19–C21.** `SeatLock` ở Phase 2: (a) port + adapter in-process (tiền lệ `InProcessJobLock`), Redisson ở Phase 3; hay (b) Redisson adapter ngay vì Mục 1.5 đã thêm Redisson cho M-08. Cả hai đều giữ DB check làm lớp quyết định. → ghi trong `test-matrix.md` §4.5 như dòng M-09

### 0.2 Web Application

- [ ] `[→ M-78]` **Q12 — Lưu access token ở đâu** (web §13 #3; tạm thời: memory only) → `auth-and-identity.md` (mục Web-client), rồi `web-coding-standards.md` §8/§11
- [ ] `[→ M-78]` **Q13 — Nguồn permission để gate UI** (web §13 #4): cho phép đọc claim `permissions` chỉ cho UX, hay thêm permissions vào `LoginResponse` → `schemas/user.yaml`, `auth-and-identity.md` §2
- [ ] `[→ M-19, M-78]` **Q14 — Luồng thu phương thức thanh toán phía client** (web §13 #8): `paymentMethodToken` là field suy luận. Đề xuất cho sandbox: Stripe test PaymentMethod (`pm_card_visa`…) qua Stripe.js, không giữ dữ liệu thẻ ở UniHub → `payment.md`, `schemas/payment.yaml`
- [ ] `[→ M-78]` **Q15 — Giá trị thời gian phía client** (web §13 #7): `staleTime`/`refetchInterval` số ghế (cận dưới 3s), chu kỳ + điều kiện dừng poll `getPayment`, ngân sách tự retry `503` → `web-coding-standards.md` §7/§10

---

## 0A. Decisions (ADR) — Q1, Q2, Q3, Q11 + C2, C6, C10, C11, G1, G2

Chốt bởi Nguyễn Thành Tiến: D1–D4 ngày 2026-10-13; C2, C6, C10, C11 và hai bản vá G1, G2 ngày 2026-10-14 (đã gộp vào D1, D2 và D5 bên dưới). Mỗi quyết định gồm: quyết định · lý do · ràng buộc · acceptance criteria (AC, dùng làm điều kiện tick các task liên quan) · test cần có (tên đề xuất, chốt khi viết) · tài liệu phải sửa. Mâu thuẫn tìm thấy khi đối soát nằm ở Mục 0B (mã `Cnn`). **Chưa có dòng code nào cho các quyết định này.**

### D1 — Q1 (+ C2, G1, G2): Idempotency lưu Result của use case `[→ M-20, M-21, M-23]`

- **Quyết định:** `idempotency_keys` lưu Result của use case (`result_type VARCHAR` + `result_body JSONB`, migration `V3`), ghi trong cùng transaction với use case. Bỏ `response_status`/`response_body`. Khóa unique gồm **scope + actor + key** (C2).
- **Lý do:** lớp application không biết HTTP (`backend-coding-standards.md` §3, §21 rule 1). Map Result → HTTP là tất định, nên replay vẫn ra đúng response cũ. Đúng đề xuất của §27 #1. Về khóa (C2): bỏ scope thì một key dùng lại giữa đăng ký và thanh toán sẽ đụng nhau (`database-schema.md` §8.1); thiếu actor thì key của user này có thể chặn hoặc replay Result của user khác.
- **Ràng buộc:**
  1. Lưu **request fingerprint** (hash của payload đã chuẩn hóa). Cùng key nhưng khác payload → `422`. *(C1, C26)*
  2. **Khóa unique `(scope, actor_user_id, idempotency_key)`, cột `actor_user_id` bắt buộc (C2). Bất biến: actor luôn lấy từ danh tính đã xác thực (JWT `sub` → `Actor.UserActor`), không bao giờ từ request body** — nếu lấy từ body, user có thể dò Result đã lưu của người khác.
  3. Insert key với trạng thái `IN_PROGRESS` dưới unique constraint; request đồng thời cùng key → `409`. Cách giữ đúng invariant "cùng transaction" khi có `IN_PROGRESS` *(C3)*:
     - `REGISTRATION_CREATE`: insert là **câu lệnh đầu tiên** của business transaction. Row `IN_PROGRESS` không bao giờ được commit riêng mà được update thành `COMPLETED` + Result trước khi commit. Request đồng thời cùng key bị chặn ở unique index: bên kia commit thì nhận `DuplicateKeyException` → `409`; bên kia rollback thì chạy tiếp bình thường.
     - `PAYMENT_CHARGE` (orchestrator §19.4, gateway gọi **ngoài** transaction): tx1 commit **nguyên tử** `payments` `PENDING` + key `IN_PROGRESS` (key mang id của payment, C27); gọi gateway; tx2 update `payments` + key `COMPLETED` + Result. Key `IN_PROGRESS` đã commit luôn đi cùng hiệu ứng của tx1 (row ý định trừ tiền), nên không bao giờ tồn tại một mình. Request cùng key trong lúc đó → `409`, gateway không bị gọi lần hai *(C5)*.
  4. Chỉ lưu Result nghiệp vụ: thành công + domain failure là biến thể của sealed Result (sold-out, already-registered, payment đang sống, …). Lỗi hạ tầng (lock không lấy được, mất race version, gateway/breaker `503`, DB lỗi) → rollback, **không lưu**, retry cùng key chạy thật *(C4: thay thế cách ghi `503` của `payment.md` §8)*.
  5. Mapping Result → HTTP là contract ổn định: mỗi biến thể có đúng một status + body, khóa lại bằng test. Đổi mapping là breaking change.
  6. Có TTL dọn key (`unihub:lock:job:idempotency-cleanup`, cửa sổ theo `database-schema.md` §8.1 — "minutes"). Cleanup **chỉ xóa key `COMPLETED`**; không bao giờ xóa key `IN_PROGRESS` khi payment tương ứng chưa được giải quyết.
  7. **G1 — charge khi breaker đang mở** *(C24)*:
     - (a) Xin quyền gọi của breaker **trước** tx1. Không được phép → `503` ngay, không ghi gì (không `payments`, không key).
     - (b) Backstop: nếu cuộc gọi vẫn bị breaker từ chối **sau** khi tx1 đã commit (breaker vừa chuyển sang `OPEN` giữa hai bước), một transaction bù chuyển payment `PENDING → CANCELLED` (không bị partial index của D5 chặn) và **xóa** key `IN_PROGRESS`. Trả `503`; retry cùng key chạy thật.
     - Khác với timeout/lỗi **sau khi request đã gửi tới gateway**: trường hợp đó giữ payment `PENDING` + key `IN_PROGRESS` cho reconciliation, vì gateway có thể đã trừ tiền.
  8. **G2 — crash giữa tx1 và tx2 của charge** *(C25)*: reconciliation, khi giải quyết một payment `PENDING`, cập nhật key `IN_PROGRESS` tương ứng sang `COMPLETED` + Result **trong cùng transaction** với việc resolve payment. Nhờ vậy client retry cùng key sẽ nhận replay, không bị `409` mãi mãi.
- **Acceptance criteria:**
  - **AC1.1** Cùng actor + scope + key + payload → trả về đúng Result gốc (status + body giống hệt), không có hiệu ứng thứ hai (không thêm `registrations`/`payments`, gateway không bị gọi lại).
  - **AC1.2** Cùng key, khác fingerprint → `422` `ProblemDetail`, không có hiệu ứng.
  - **AC1.3** N request song song cùng key → đúng 1 hiệu ứng; các request còn lại `409` hoặc replay. Không bao giờ có 2 hiệu ứng.
  - **AC1.4** Business write rollback → không còn row key; business write commit → row key `COMPLETED` có Result.
  - **AC1.5** Lỗi hạ tầng → không có row key `COMPLETED`; retry cùng key thực thi lại.
  - **AC1.6** Mỗi biến thể Result có đúng một mapping HTTP, được test cố định.
  - **AC1.7** Key `COMPLETED` quá cửa sổ bị dọn, key trong cửa sổ còn nguyên (biên: cửa sổ −1s / đúng biên / +1s); key `IN_PROGRESS` của payment chưa giải quyết không bao giờ bị dọn.
  - **AC1.8** Cùng key, khác actor → hai thao tác độc lập; actor trong body (nếu client gửi) bị bỏ qua.
  - **AC1.9 (G1a)** Breaker `OPEN` trước tx1 → `503`, không có row `payments`, không có row key.
  - **AC1.10 (G1b)** Breaker từ chối sau tx1 → payment `CANCELLED`, key `IN_PROGRESS` bị xóa, `503`; retry cùng key gọi gateway thật đúng 1 lần.
  - **AC1.11** Timeout/lỗi sau khi request đã gửi tới gateway → payment `PENDING` + key `IN_PROGRESS` còn nguyên; retry cùng key → `409`, gateway không bị gọi lại.
  - **AC1.12 (G2)** Crash giữa tx1 và tx2 → reconciliation resolve payment và chuyển key sang `COMPLETED` + Result trong cùng transaction; retry cùng key sau đó → replay.
- **Test cần có:** xem Mục 0C, nhóm T-IDEM.
- **Tài liệu phải sửa (trước khi code):** `database-schema.md` §8.1 + §10 (đặc tả migration `V3`); `backend-coding-standards.md` §13.1, §17 (`422`), §19.4, §21 và đánh dấu §27 #1 xong; `web-coding-standards.md` §10, §13 #6; `registration.md` §4; `payment.md` §3, §6, §8, §10; `test-design-techniques.md` §3.3; `common/headers.yaml`, `paths/registration.yaml`, `paths/payment.yaml` (`422`, `409` cho key đang xử lý, `503` không được ghi); `architecture.md` §4.5 **không sửa** — xem C3.

### D2 — Q2 (+ C10, C11): Payment `SUCCEEDED` → Registration `CONFIRMED` bất đồng bộ qua outbox; nhánh bù trừ hoàn tiền `[→ M-19, M-25]` `[M-75]` `[M-76 · P3]`

- **Quyết định:** khi payment chuyển `SUCCEEDED` (webhook hoặc reconciliation), cùng transaction đó ghi outbox `unihub.payment.charge.succeeded`. Message được route tới `unihub.payment-confirmation.queue` (retry chain riêng). Module Registration consume và chuyển `RESERVED` → `CONFIRMED`. Khi registration không còn `RESERVED`, đi **nhánh bù trừ** (C10): Registration phát event từ chối, Payment chuyển sang `REFUND_REQUIRED`, gọi refund, rồi chuyển `REFUNDED`. Không tự cấp lại ghế. Không thêm bảng inbox chung (C11).
- **Lý do:** đúng interaction type 4 (`backend-coding-standards.md` §7): Payment không chờ Registration, và Payment là module duy nhất gọi gateway. Ngoại lệ type 2 (`MANDATORY`) chỉ dành cho anti-overbooking. Về phân phase (C10): enum trong contract phải có ngay, vì thêm giá trị enum vào response sau khi client đã chạy là breaking change; ngược lại, gọi refund thật ở Phase 2 là trả trước chi phí (call-site mới, sửa ADR breaker, adapter) cho một nhánh chưa thể xảy ra trên luồng thật — Phase 2 chưa có consumer hết hạn và chưa có hủy đăng ký. Về dedup (C11): ở nhánh từ chối, registration không đổi trạng thái, nên mỗi lần redeliver "payment thành công" lại sinh thêm một event từ chối mới; dedup theo event id là sai chỗ, khóa theo payment mới bền vững.
- **Ràng buộc:**
  1. Consumer xác nhận **và** consumer hết hạn giữ chỗ (`hold.expired`, Phase 3) đều chuyển trạng thái bằng conditional UPDATE `… WHERE id = ? AND status = 'RESERVED'` (kèm `version`) và kiểm tra affected rows. 0 row → đọc lại tại Master: đã `CONFIRMED` → no-op (redelivery); `EXPIRED`/`CANCELLED` → nhánh bù trừ. Không ném lỗi *(C8, C9)*.
  2. **Nhánh bù trừ** (C10, tên trạng thái chốt: `REFUND_REQUIRED`, `REFUNDED`):
     1. Consumer Registration: không đổi registration; ghi outbox `unihub.registration.confirmation.rejected` (payload: `paymentId`, `registrationId`, lý do `HOLD_EXPIRED`/`REGISTRATION_CANCELLED`). Redelivery sinh thêm event — chấp nhận, vì phía Payment tự dedup.
     2. Consumer Payment trên `unihub.payment-refund.queue`: conditional UPDATE `SUCCEEDED → REFUND_REQUIRED` + kiểm tra affected rows. **Phase 2 dừng ở đây (chỉ đổi trạng thái).**
     3. **Phase 3** (cùng consumer hết hạn giữ chỗ, thứ thật sự kích hoạt nhánh này): gọi refund của gateway **ngoài** transaction với idempotency key phía gateway **tất định theo payment** (`refund:{paymentId}`); thành công → `REFUND_REQUIRED → REFUNDED`; refund là call-site thứ ba gọi gateway nên phải cập nhật ADR breaker; xử lý refund bị kẹt. Ghi vào `test-matrix.md` §4.5.
     4. Không tự "cấp lại ghế" cho registration đã hết hạn: ghế có thể đã thuộc người khác, và chuyển `EXPIRED → CONFIRMED` là transition bị cấm (`test-design-techniques.md` §2).
     5. Client thấy registration `EXPIRED`/`CANCELLED` và payment `REFUND_REQUIRED` (Phase 2) / `REFUNDED` (từ Phase 3).
     6. Refund là thao tác dịch chuyển tiền nên phụ thuộc Q10 (AuditTrail) — **chưa chốt**, chỉ ghi nhận.
  3. **Dedup (C11):** không thêm bảng inbox. Mọi chuyển trạng thái tự dedup bằng conditional UPDATE trên trạng thái hiện tại + kiểm tra affected rows. Refund dedup theo **payment**: transition `SUCCEEDED → REFUND_REQUIRED` có điều kiện + idempotency key gateway `refund:{paymentId}`. Quy tắc cho Phase 3: xử lý lại mà gặp `REFUND_REQUIRED` thì **phải gọi refund tiếp** (trường hợp crash sau commit, trước khi gọi gateway), không coi là no-op; chỉ `REFUNDED` mới là no-op.
  4. Outbox relay + retry chain 3 tầng + parking-lot chạy được trong Phase 2. Có alert khi `unihub.parking-lot` > 0 *(C12: rule đã có ở `observability.md` §6 nhưng Prometheus chưa tồn tại tới Phase 6/7)*.
  5. API spec mô tả trạng thái trung gian cho client *(C13)*: payment `SUCCEEDED` + registration vẫn `RESERVED` = "đang xác nhận" (client tiếp tục poll `getRegistration`); và kết cục hoàn tiền.
- **Acceptance criteria:**
  - **AC2.1** Payment `SUCCEEDED` + registration `RESERVED` → `CONFIRMED` + outbox `ticket.confirmed` trong cùng transaction; `seats_taken` không đổi (ghế đã được claim lúc `RESERVED`).
  - **AC2.2** Redeliver cùng message (≥ 2 lần) → đúng một transition, đúng một `ticket.confirmed`.
  - **AC2.3** Race xác nhận ↔ hết hạn (hai transaction đồng thời trên cùng registration) → đúng một bên có affected rows = 1; trạng thái cuối là `CONFIRMED` **hoặc** (`EXPIRED` + nhánh bù trừ), không bao giờ cả hai; `seats_taken` khớp với số registration `RESERVED`/`CONFIRMED`.
  - **AC2.4 (Phase 2)** Payment `SUCCEEDED` khi registration đã `EXPIRED`/`CANCELLED` → outbox `confirmation.rejected` → payment `REFUND_REQUIRED`; registration không đổi; không gọi gateway.
  - **AC2.4b (Phase 2)** Redeliver "payment thành công" và/hoặc event từ chối nhiều lần → payment chỉ chuyển sang `REFUND_REQUIRED` đúng một lần (affected rows = 1 đúng một lần).
  - **AC2.4c (Phase 3)** `REFUND_REQUIRED` → refund được gọi với key `refund:{paymentId}` → `REFUNDED`; xử lý lại khi đang `REFUND_REQUIRED` vẫn gọi refund; `REFUNDED` là no-op.
  - **AC2.5** Message lỗi vĩnh viễn → `unihub.parking-lot` (cả routing key gốc + lý do trong header), không mất message; lỗi tạm thời → retry đúng 3 lần rồi mới vào parking-lot.
  - **AC2.6** Contract mô tả rõ cửa sổ "đang xác nhận" và kết cục hoàn tiền (gồm `REFUND_REQUIRED`/`REFUNDED` trong enum ngay từ Phase 2); `contract-diff` xanh.
- **Test cần có:** Mục 0C, nhóm T-CONF, T-MSG.
- **Tài liệu phải sửa:** `messaging.md` §2, §4, §6 (2 key + 2 queue mới, thêm 2 retry chain); `registration.md` (mục xác nhận + bù trừ); `payment.md` (§1, mục bù trừ mới, §9, §10; ADR breaker sửa ở **Phase 3**); `database-schema.md` §5.1 + §10 (đặc tả `V3`: trạng thái payment mới); `test-design-techniques.md` §2 (transition mới); `schemas/payment.yaml`, `paths/payment.yaml`, `paths/registration.yaml`, `schemas/registration.yaml`; `test-matrix.md` (derived requirement M-75/M-76 + §4.5); `backend-coding-standards.md` §8.5, §27 #8 (đánh dấu nửa "payment succeeded" xong).

### D3 — Q3: Giữ chung `workshops.version`; xem lại ở Phase 8 `[→ M-18, M-19]` `[M-28 · P3]` `[M-72, M-74 · P8]`

- **Quyết định:** seat claim và organizer edit tiếp tục dùng chung `workshops.version`.
- **Lý do:** giữ nguyên văn invariant "version column + check constraint" (`architecture.md` §4.2, §6.1; `CLAUDE.md`). Phương án atomic increment không có version sẽ phải sửa invariant, và chưa có số liệu nào chứng minh xung đột là vấn đề thật.
- **Ràng buộc:**
  1. Seat claim: `UPDATE workshops SET seats_taken = seats_taken + 1, version = version + 1 WHERE id = ? AND version = ? AND seats_taken < capacity`. 0 row → đọc lại để phân loại: version đổi → retry; đầy → sold-out *(C14)*.
  2. Organizer edit chỉ UPDATE các cột được sửa, **tuyệt đối không ghi `seats_taken`** *(C15 — code hiện tại vi phạm)*.
  3. Seat claim gặp version conflict → retry nội bộ tối đa **N = 3** lần, mỗi lần là một transaction mới, vẫn nằm trong lock. Không trả `409` thẳng cho student. Hết retry → `503` + `Retry-After`, **không** ghi idempotency (D1 ràng buộc 3) *(C16)*.
  4. Chặn giảm capacity < `seats_taken`: CHECK `seats_taken <= capacity` (V1) + domain validation — **đã có** *(C17)*.
  5. Tiêu chí xem lại ở Phase 8 *(C18 — metric chưa có trong `observability.md`)*: `unihub_seat_claim_version_conflicts_total`, `unihub_seat_claim_retries_exhausted_total`, `unihub_workshop_edit_conflicts_total`. Ngưỡng đề xuất: xem lại thiết kế nếu trong load test 12,000 CCU (M-72) có retries-exhausted > 0,1% số seat claim **hoặc** organizer edit conflict > 5% số lần sửa.
- **Acceptance criteria:**
  - **AC3.1** Organizer edit đồng thời với seat claim → cả hai cuối cùng thành công (claim nhờ retry; edit nhận `409` có thể thử lại theo contract), `seats_taken` đúng bằng số claim thành công.
  - **AC3.2** Organizer edit không bao giờ xuất hiện `seats_taken` trong câu UPDATE (kiểm qua SQL thực thi hoặc qua kết quả race ở AC3.1).
  - **AC3.3** Conflict liên tục > N lần → `503` + `Retry-After`, không có row idempotency, không có registration.
  - **AC3.4** Đầy chỗ → sold-out (`409`), được ghi idempotency (là domain failure).
  - **AC3.5** Giảm capacity xuống dưới `seats_taken` bị từ chối ở cả domain lẫn DB (đã có test — giữ xanh).
  - **AC3.6** Ba metric ở ràng buộc 5 được expose (Micrometer) và có test đếm.
- **Test cần có:** Mục 0C, nhóm T-SEAT.
- **Tài liệu phải sửa:** `database-schema.md` §3.1 (ghi quyết định, đánh dấu §27 #9 xong); `registration.md` §2 (SQL + retry); `backend-coding-standards.md` §25 (retry thay cho ném `503` ngay); `observability.md` §4 (3 metric). **Không sửa** `architecture.md`.

### D4 — Q11: `SeatLock` in-process ở Phase 2; Redisson + fail-closed ở Phase 3 `[→ M-18, M-19]` `[M-28, M-32, M-34 · P3]`

- **Quyết định:** port `SeatLock` + adapter in-process ở Phase 2. `RedissonSeatLock` (Redis down → `503`, INV-08) ở Phase 3, cùng lúc với M-34.
- **Lý do:** đúng tiền lệ `JobLock`/`InProcessJobLock` (M-09). DB check (D3) vẫn là lớp quyết định. Hành vi fail-closed nên đi cùng test của nó (Phase 3).
- **Ràng buộc:**
  1. Port: `SeatLockHandle tryAcquire(WorkshopId workshopId, Duration waitTimeout, Duration leaseTime)` (handle `AutoCloseable`). Thất bại → `SeatLockUnavailableException` (`ProblemKind.UNAVAILABLE`). Từ Phase 2, web map thành `503` + `Retry-After` *(C19, C20)*. Giá trị mặc định theo `registration.md` §2: wait 2s, lease 5s.
  2. Lock bao trọn transaction: acquire **trước** khi transaction bắt đầu, release **sau** commit (hoặc sau rollback) — two-bean pattern §19.2/§19.3.
  3. Adapter in-process: một lock riêng cho mỗi `workshopId` (không có lock toàn cục), `tryLock` có timeout, có cleanup (entry bị xóa khi không còn ai giữ hoặc chờ, để map không phình). `leaseTime` không áp dụng được với lock JVM — ghi rõ trong Javadoc *(C21)*.
  4. Thêm dòng vào `test-matrix.md` §4.5 (cùng commit với adapter): adapter in-process **không bảo vệ khi chạy nhiều instance**; lớp đúng đắn duy nhất lúc đó là DB check (D3); hết hạn khi `RedissonSeatLock` qua contract test ở Phase 3.
- **Acceptance criteria:**
  - **AC4.1** Hai request cùng workshop không bao giờ ở trong vùng găng cùng lúc; hai workshop khác nhau không chặn nhau.
  - **AC4.2** Hết `waitTimeout` → `SeatLockUnavailableException` → HTTP `503` + `Retry-After`, không ghi idempotency.
  - **AC4.3** Request thứ hai lấy được lock thì luôn thấy dữ liệu đã commit của request thứ nhất (lock nhả sau commit, không trước).
  - **AC4.4** Lock được nhả cả khi transaction rollback hoặc ném exception.
  - **AC4.5** Sau khi mọi request xong, adapter không còn entry nào.
  - **AC4.6** Dòng §4.5 tồn tại trong `test-matrix.md`.
- **Test cần có:** Mục 0C, nhóm T-LOCK.
- **Tài liệu phải sửa:** `backend-coding-standards.md` §25 (`withLock` → `tryAcquire` + handle, tên exception); `registration.md` §2 (ghi chú Phase 2 in-process).

### D5 — C6: Mỗi registration tối đa một payment "đang sống", được thử lại sau thất bại `[→ M-19, M-20]` `[M-77]`

- **Quyết định:** thay `payments.registration_id UNIQUE` tuyệt đối bằng **partial unique index** chỉ trên các trạng thái còn hiệu lực: `PENDING`, `SUCCEEDED`, `REFUND_REQUIRED`, `REFUNDED`. Payment `FAILED` hoặc `CANCELLED` không chặn lần thử mới.
- **Lý do:** thẻ bị decline là chuyện bình thường; khóa student trong suốt thời gian giữ chỗ không bảo vệ thêm được gì. Partial index vẫn giữ bất biến quan trọng nhất: không bao giờ có hai lần trừ tiền song song cho cùng một registration, kể cả khi hai tab gửi hai idempotency key khác nhau. `REFUND_REQUIRED`/`REFUNDED` vẫn nằm trong index: đó là tiền đã trừ, và registration của nó không còn `RESERVED` nên cũng không thể charge lại.
- **Ràng buộc:**
  1. Charge thất bại là domain failure, được ghi Result dưới key cũ. Muốn thử lại thì client phải dùng idempotency key **mới** *(C28: hình dạng HTTP của decline đồng bộ còn mở)*.
  2. Vi phạm partial index → domain failure `409` "đã có thanh toán đang xử lý", với loại lỗi máy đọc được riêng — gắn với Q6 khi Q6 được chốt (chưa chốt).
  3. Partial index không dùng được làm target kiểu `ON CONFLICT ON CONSTRAINT`. Adapter bắt `DuplicateKeyException` trên index này và dịch thành domain failure (Result variant), không phải lỗi hạ tầng.
  4. Giao thoa với G1b: payment bị hủy bởi transaction bù (`CANCELLED`) không chặn retry cùng key.
- **Acceptance criteria:**
  - **AC5.1** Payment `FAILED` (hoặc `CANCELLED`) → charge mới với key mới cho cùng registration được chấp nhận và gọi gateway.
  - **AC5.2** Hai charge song song cho cùng registration với **hai key khác nhau** → đúng một payment sống; bên còn lại `409` (domain failure, được ghi dưới key của nó), gateway chỉ bị gọi một lần.
  - **AC5.3** Retry cùng key sau một charge `FAILED` → replay kết quả cũ, không gọi gateway.
  - **AC5.4** Vi phạm partial index không bao giờ lộ ra thành `500`.
- **Test cần có:** Mục 0C, nhóm T-PAY.
- **Tài liệu phải sửa:** `database-schema.md` §5.1, §10 (đặc tả `V3`), §11; `payment.md` §1, §8, §10; `paths/payment.yaml` (`409`, mô tả thử lại); `web-coding-standards.md` §10 (key mới sau `FAILED`); `test-matrix.md` (derived requirement M-77).

---

## 0B. Đối soát chéo — mâu thuẫn tìm thấy

Đối chiếu D1–D5 với `architecture.md` (§4.2, §4.3, §4.5, §6.1, interaction types), `backend-coding-standards.md` (§7, §8.5, §9.2, §21, §25, §27 #1/#8/#9), DDL `V1`/`V2`, `test-matrix.md`, cùng `registration.md`, `payment.md`, `messaging.md`, `observability.md`, `test-design-techniques.md` và contract. **Chưa sửa tài liệu nào; `architecture.md` không đụng tới.** Mức độ: **Chặn** = phải giải quyết trước code của dòng liên quan · **Doc** = chỉ cần cập nhật tài liệu · **Code** = code hiện có lệch · **OK** = không mâu thuẫn.

| # | QĐ | File · mục | Nội dung lệch | Mức | Đề xuất |
|---|---|---|---|---|---|
| C1 | D1 | `V1` DDL; `database-schema.md` §8.1; `paths/registration.yaml`, `paths/payment.yaml`; `backend-coding-standards.md` §21 | Không có cột fingerprint; contract không khai báo `422`; §21 không nhắc tới payload khác | Chặn M-20 | `V3` thêm `request_fingerprint`; thêm `422` vào cả hai operation; bổ sung §21 |
| C2 | D1 | `database-schema.md` §8.1; `V1` `uq_idempotency_keys_scope_key UNIQUE (scope, idempotency_key)` | Ràng buộc ghi `(actor_id, key)` — bỏ mất `scope`, trong khi §8.1 giải thích scope tồn tại chính để chặn trùng key giữa hai operation. Bảng cũng chưa có cột actor | ✅ **Đã chốt 2026-10-14 → D1** | `UNIQUE (scope, actor_user_id, idempotency_key)`, `actor_user_id UUID NOT NULL`, actor chỉ lấy từ JWT `sub` |
| C3 | D1 | `architecture.md` §4.5 ("can never exist independently"); `CLAUDE.md` invariant idempotency; `backend-coding-standards.md` §21 rule 2 | Một row `IN_PROGRESS` commit **trước** hiệu ứng sẽ vi phạm invariant "cùng transaction" | Chặn M-20 | Dùng cách ở D1 ràng buộc 2: registration không bao giờ commit `IN_PROGRESS`; charge chỉ commit `IN_PROGRESS` nguyên tử cùng row `payments` `PENDING`. Không sửa invariant |
| C4 | D1 | `payment.md` §8 | §8 ghi response circuit-open `503` vào idempotency; D1 cấm (= Q5) | Doc | Sửa `payment.md` §8 theo §21 rule 5 |
| C5 | D1 | `payment.md` §3 (sơ đồ + đoạn "No payments row … gateway was never reached"), §6, §9 | §3 coi **timeout** giống circuit-open (không tạo row), nhưng khi timeout gateway có thể **đã** trừ tiền. Theo D1, tx1 tạo `PENDING` trước khi gọi, nên timeout để lại row `PENDING` cho reconciliation. §6 lại giả định đã có `gateway_transaction_id` (NULL nếu crash/timeout trước khi có response) | Chặn M-23/M-24 — đồng bộ với G1 (C24) | Circuit-open phát hiện **trước** tx1 → không row (G1a); breaker từ chối **sau** tx1 → transaction bù `PENDING → CANCELLED` + xóa key (G1b); timeout/lỗi **sau khi request đã gửi** → giữ `PENDING` + key `IN_PROGRESS` cho reconciliation (G2). Gửi Stripe idempotency key = `payments.id` để reconciliation tra được kể cả khi `gateway_transaction_id` NULL |
| C6 | D1/D2 | `V1` `payments.registration_id UNIQUE`; `database-schema.md` §5.1 | Lần charge `FAILED` chặn mọi lần charge sau (key mới) cho cùng registration, nên student không thử thẻ khác được trong 15 phút giữ chỗ | ✅ **Đã chốt 2026-10-14 → D5** | Partial unique index `WHERE status IN ('PENDING','SUCCEEDED','REFUND_REQUIRED','REFUNDED')`; thử lại cần key mới |
| C7 | D2 | `messaging.md` §2 (2 bảng + sơ đồ), §4 ("five queues", "15 retry queues"), §6 | Không có `unihub.payment.charge.succeeded` / `unihub.payment-confirmation.queue` (và `unihub.registration.confirmation.rejected` của nhánh bù trừ) | Doc (chặn M-19) | Thêm key + queue + retry chain; cập nhật số đếm |
| C8 | D2 | `backend-coding-standards.md` §9.2 (Tier A: load aggregate → so version → JPA UPDATE theo version); `registration.md` §3 (SELECT rồi UPDATE theo version) | Ràng buộc yêu cầu `WHERE status = 'RESERVED'` + đếm affected rows, khác dạng code mẫu. Ngữ nghĩa tương đương (mọi transition đều bump version) | Doc (thấp) | Adapter dùng `WHERE id AND version AND status = 'RESERVED'` → affected rows ≠ 1 → `OptimisticConflictException` → use case đọc lại và rẽ nhánh. Ghi chú ở §9.2 |
| C9 | D2 | `registration.md` §3; `architecture.md` §4.3 | Ràng buộc nói "expiry job"; thiết kế là consumer của delay queue (đã loại polling). Thuộc Phase 3 (M-33) | Doc (từ ngữ) | AC áp dụng cho consumer `hold.expired`; Phase 2 mô phỏng hết hạn trong test |
| C10 | D2 | `V1` CHECK `payments.status`/`registrations.status`; `database-schema.md` §4.1/§5.1; `schemas/payment.yaml` `PaymentStatus`; `test-design-techniques.md` §2; `requirement.md` (không nhắc refund); `payment.md` §3 trade-off + ADR breaker | Không nơi nào có `REFUND_REQUIRED`/`REFUNDED` hay luồng refund. Refund là **call-site thứ ba gọi gateway**, nhưng ADR breaker chỉ biết hai call-site. `payment.md` §3 nói hold có thể hết khi gateway sập, nhưng bỏ ngỏ trường hợp payment thành công *sau* khi hold hết | ✅ **Đã chốt 2026-10-14 → D2** (tài liệu đầy đủ ở Phase 2; code: Phase 2 tới `REFUND_REQUIRED`, Phase 3 gọi refund) | Migration thêm 2 trạng thái; ST catalog thêm `SUCCEEDED → REFUND_REQUIRED → REFUNDED`; derived requirement trong `test-matrix.md`; ADR breaker (call-site thứ ba) sửa ở Phase 3 |
| C11 | D2 | `architecture.md` §6.1 + `CLAUDE.md` (đúng **ba** Technical Pattern Entity); `backend-coding-standards.md` §8.5; `database-schema.md` §8 | Một bảng inbox/dedup chung sẽ là Technical Pattern Entity thứ tư → phải đổi kiến trúc. §8.5 đã có quy tắc: transition dùng re-check, hiệu ứng ngoài dedup theo event id ở bảng của chính nó | ✅ **Đã chốt 2026-10-14 → D2** | Không thêm inbox; mọi transition dedup bằng conditional UPDATE + affected rows. **Bỏ** cột source event id; refund dedup theo payment: `SUCCEEDED → REFUND_REQUIRED` có điều kiện + idempotency key gateway `refund:{paymentId}` |
| C12 | D2 | `observability.md` §4, §6 (alert `rabbitmq_queue_messages_ready{queue="unihub.parking-lot"} > 0`, Critical); `messaging.md` §4 | (a) Rule alert đã có nhưng Prometheus/Grafana và plugin `rabbitmq_prometheus` chưa có tới Phase 6/7, nên không thể "có alert" thật trong Phase 2. (b) DLQ là **một** `unihub.parking-lot` dùng chung, không phải DLQ riêng từng queue | Doc + thêm §4.5 | Phase 2: bật plugin ở docker-compose, test message vào parking-lot, ghi rule vào file trong repo; alert thật hoãn tới Phase 6 (thêm dòng §4.5). Đã sửa lỗi "parking-lot riêng" ở Mục 1.3 |
| C13 | D2 | `paths/registration.yaml` (`getRegistration`), `paths/payment.yaml` (`getPayment`), `schemas/registration.yaml` `RegistrationStatus` | Không mô tả cửa sổ "payment `SUCCEEDED`, registration vẫn `RESERVED`", cũng không có kết cục hoàn tiền | Doc (chặn M-78) | Thêm mô tả + điều kiện dừng poll (gắn Q15) |
| C14 | D3 | `registration.md` §2; `backend-coding-standards.md` §25.2 | Mẫu SQL hiện tại: SELECT kiểm chỗ rồi UPDATE chỉ theo version. Ràng buộc thêm `seats_taken < capacity` vào WHERE nên 0 row trở nên mơ hồ | Doc (thấp) | Đọc lại sau 0 row để phân loại; cập nhật mẫu SQL |
| C15 | D3 | Code: `WorkshopJpaEntity` (`seats_taken` updatable, không `@DynamicUpdate`); `JpaWorkshopRepository#save`; `backend-coding-standards.md` §9.2 (`copyState` chép toàn bộ) | Organizer edit hiện UPDATE **mọi** cột, kể cả `seats_taken` (lấy từ aggregate). Version check chặn được lost update nhưng vẫn vi phạm "không ghi `seats_taken`" | **Code** | `@Column(updatable = false)` cho `seats_taken`; seat chỉ đổi qua SQL riêng trong Workshop `api` (`MANDATORY`). Thêm task ở Mục 3.1 |
| C16 | D3 | `backend-coding-standards.md` §25.1 (`OptimisticConflictException` → `SeatTemporarilyUnavailableException` ngay) | Chưa có retry nội bộ | Doc | Retry 3 lần rồi mới ném; `test-design-techniques.md` §3.1 dòng 2 ("retry or 409, caller's choice") đã cho phép |
| C17 | D3 | `V1` CHECK `seats_taken <= capacity`; `WorkshopLifecycleTest#revise_rejectsCapacityBelowSeatsTaken` | — | **OK** | Đã thỏa |
| C18 | D3 | `observability.md` §4 | Không có metric conflict/retry | Doc | Thêm 3 metric (D3 ràng buộc 5) |
| C19 | D4 | `backend-coding-standards.md` §25.1 (`seatLock.withLock(workshopId, supplier)`) | Hình dạng port khác: `tryAcquire(workshopId, wait, lease)` + handle | Doc | Sửa §25 theo D4 |
| C20 | D4 | `backend-coding-standards.md` §25 (`SeatTemporarilyUnavailableException`); `ProblemKind` | Hai tên exception cho hai nguyên nhân `503` (lock bận / hết retry). `ProblemKind.UNAVAILABLE` đã có | Doc (thấp) | Giữ cả hai, cùng `UNAVAILABLE`: `SeatLockUnavailableException` (lock), `SeatTemporarilyUnavailableException` (hết retry) |
| C21 | D4 | `registration.md` §2 (lease 5s, không watchdog) | Lock JVM không có lease; ràng buộc "release sau commit" với `ReentrantLock` đòi cùng một thread | Doc (thấp) | Javadoc: `leaseTime` bị bỏ qua ở in-process; acquire/release trên cùng request thread |
| C22 | D2 | `registration.md` §6 dòng cuối; Phase 2 không có consumer hết hạn | Ở Phase 2 registration paid không bao giờ `EXPIRED`, nên payment đến muộn vẫn được xác nhận dù quá `hold_expires_at` | Doc + §5 sign-off | Chấp nhận cho Phase 2 (đã có ở bảng ranh giới); test race mô phỏng `EXPIRED` trực tiếp |
| C23 | — | `test-matrix.md` §4.5 | Chưa có dòng cho SeatLock in-process (D4), alert parking-lot (C12), AuditTrail nếu hoãn (Q10), refund gateway hoãn sang Phase 3 (D2) | Doc | Refund: thêm ở Bước 2 cùng derived requirement; các dòng còn lại thêm cùng commit với code tương ứng |
| C24 | D1 (G1) | `payment.md` §3 ("No `payments` row is created for a fallback response"), §9; `test-design-techniques.md` §3.3 dòng `OPEN` × sync charge; `paths/payment.yaml` `503` | Luồng D1 commit `PENDING` + key `IN_PROGRESS` ở tx1 **rồi** mới gọi gateway qua breaker. Nếu breaker chặn ngay lúc gọi, hệ thống để lại row + key, mâu thuẫn với "circuit-open không tạo row" và "lỗi hạ tầng không ghi idempotency"; retry cùng key không chạy thật | ✅ **Đã chốt 2026-10-14 → D1 ràng buộc 7** | (a) Xin quyền breaker trước tx1 → `503`, không ghi gì; (b) bị từ chối sau tx1 → transaction bù `PENDING → CANCELLED` + xóa key. "Không tạo row" chỉ còn đúng cho (a); (b) để lại một row `CANCELLED`, không bao giờ `PENDING` |
| C25 | D1 (G2) | Checklist D1 ràng buộc 6 (bản cũ); `payment.md` §6; `database-schema.md` §8.1 | Chỉ quy định cleanup không dọn key `IN_PROGRESS` trước khi payment được giải quyết, nhưng không chỉ định ai ghi Result. Crash giữa tx1 và tx2 → client retry cùng key nhận `409` mãi mãi | ✅ **Đã chốt 2026-10-14 → D1 ràng buộc 8** | Reconciliation hoàn tất key `IN_PROGRESS` + Result trong cùng transaction với việc resolve payment; cleanup chỉ xóa `COMPLETED` |
| C26 | D1 | Code `shared/domain/ProblemKind.java` (`INVALID_REQUEST`, `NOT_FOUND`, `CONFLICT`, `UNAVAILABLE`); `backend-coding-standards.md` §17 | Không có kind nào map sang `422`, nên fingerprint mismatch (D1 ràng buộc 1) không biểu diễn được | Doc + **Code** (shared kernel, Phase 2) | Thêm `ProblemKind.UNPROCESSABLE` → `422`; ghi ở §17 và §21 |
| C27 | D1 (G2) | `database-schema.md` §8.1 ("no FK into any business table … check by key before the guarded row's id exists") | Để làm G2, reconciliation phải tìm được key `IN_PROGRESS` của một payment, nhưng bảng không có liên kết nào tới row được bảo vệ | Doc — **nên review** | Cột `resource_id UUID NULL` (không FK, đa hình như `event_publications.aggregate_id`): `PAYMENT_CHARGE` ghi `payments.id` ngay ở tx1; `REGISTRATION_CREATE` ghi id registration khi hoàn tất (hoặc để NULL). Lý do "không FK" của §8.1 vẫn đúng |
| C28 | D5 | `paths/payment.yaml` `chargePayment` ("The response is always `PENDING`, never a final outcome"); `payment.md` §1 | D5 ràng buộc 1 nói charge thất bại là domain failure được ghi dưới key cũ. Nếu gateway decline **ngay trong lời gọi đồng bộ**, contract không có response nào cho kết cục đó (chỉ có `201` `PENDING`) | **Cần bạn quyết** (chặn hình dạng HTTP, không chặn schema) | Lựa chọn: (a) giữ "luôn `PENDING`" — decline đồng bộ vẫn trả `201` với payment `PENDING`, payment chuyển `FAILED` ngay sau đó và client thấy qua `getPayment`; (b) thêm response cho decline đồng bộ (vd. `201` với `status: FAILED`, hoặc `402`). Tài liệu Bước 2 chỉ ghi phần không phụ thuộc lựa chọn: `FAILED` là kết cục cuối của key đó, thử lại cần key mới |

---

## 0C. Test case mới phải bổ sung

Tên là đề xuất. Chốt tên thật khi viết test, rồi điền vào `test-matrix.md`.

**T-IDEM — idempotency (D1)** → M-20, M-21
- [ ] `ReserveSeatIdempotencyIT#sameKeySamePayload_replaysOriginalResult` (AC1.1)
- [ ] `ReserveSeatIdempotencyIT#sameKeySoldOut_replays409` (AC1.1, domain failure được ghi)
- [ ] `ReserveSeatIdempotencyIT#sameKeyDifferentPayload_is422` (AC1.2)
- [ ] `ReserveSeatIdempotencyIT#concurrentSameKey_exactlyOneEffect` — N thread, cùng key (AC1.3)
- [ ] `ReserveSeatIdempotencyIT#sameKeyOtherActor_isIndependent` (C2)
- [ ] `ReserveSeatIdempotencyIT#businessRollback_leavesNoKey` (AC1.4)
- [ ] `ReserveSeatIdempotencyIT#lockUnavailable_notRecorded_retryExecutes` (AC1.5)
- [ ] `ChargePaymentIdempotencyIT#sameKey_gatewayCalledOnce` (AC1.1)
- [ ] `ChargePaymentIdempotencyIT#concurrentSameKey_inProgress409_gatewayCalledOnce` (AC1.3, C3)
- [ ] `ChargePaymentIdempotencyIT#circuitOpenBeforeBegin_writesNothing` — breaker `OPEN` trước tx1: `503`, không row `payments`, không row key (AC1.9, G1a)
- [ ] `ChargePaymentIdempotencyIT#breakerRejectsAfterBegin_cancelsPaymentAndDropsKey_retryExecutes` — breaker chuyển `OPEN` giữa tx1 và lời gọi: payment `CANCELLED`, key bị xóa, retry cùng key gọi gateway thật đúng 1 lần (AC1.10, G1b)
- [ ] `ChargePaymentIdempotencyIT#gatewayTimeout_leavesPendingForReconciliation` — retry cùng key → `409`, gateway không bị gọi lại (AC1.11, C5)
- [ ] `PaymentReconciliationIT#crashBetweenBeginAndComplete_completesKey_retryReplays` — mô phỏng crash sau tx1; reconciliation resolve payment + chuyển key sang `COMPLETED` trong cùng transaction; retry cùng key → replay (AC1.12, G2) `[→ M-23]`
- [ ] `ChargePaymentIdempotencyIT#sameKeyOtherActor_isIndependent` + `#actorInBody_isIgnored` (AC1.8, C2)
- [ ] `RegistrationResultHttpMappingTest` / `PaymentResultHttpMappingTest` — mọi biến thể → status + body cố định (AC1.6)
- [ ] `IdempotencyCleanupIT#window_boundaries` (−1s / biên / +1s) + `#inProgressCharge_notSweptBeforeResolved` + `#onlyCompletedKeysSwept` (AC1.7)

**T-CONF — xác nhận và bù trừ (D2)** → M-19, M-25
- [ ] `PaymentConfirmationIT#succeededWhileReserved_confirmsWithTicketOutbox` (AC2.1)
- [ ] `PaymentConfirmationIT#redelivery_isNoOp` (AC2.2)
- [ ] `PaymentConfirmationIT#raceConfirmVsExpire_exactlyOneWins` — hai thread: consumer xác nhận vs conditional UPDATE `EXPIRED` mô phỏng hết hạn; assert đúng một bên affected = 1 và `seats_taken` nhất quán (AC2.3)
- [ ] `PaymentConfirmationIT#succeededAfterExpired_emitsRejection_paymentRefundRequired` / `#succeededAfterCancelled_emitsRejection_paymentRefundRequired` — registration không đổi, gateway không bị gọi (AC2.4) `[M-75]`
- [ ] `PaymentCompensationIT#redeliveredSuccessAndRejection_refundRequiredExactlyOnce` (AC2.4b, C11) `[M-75]`
- [ ] `PaymentStatusTransitionTest` — transition mới `SUCCEEDED → REFUND_REQUIRED` (Phase 2) và `REFUND_REQUIRED → REFUNDED` (Phase 3), terminal reject (C10) `[M-75]`
- [ ] `[M-76 · P3]` `RefundIT#refundRequired_callsGatewayWithDeterministicKey_thenRefunded`, `#reprocessWhileRefundRequired_callsRefundAgain`, `#refunded_isNoOp` (AC2.4c) — **Phase 3, không làm ở Phase 2**

**T-PAY — thử lại và payment đang sống (D5)** → M-77, M-19
- [ ] `ChargePaymentIT#failedThenNewKey_isAllowed` (AC5.1)
- [ ] `ChargePaymentIT#cancelledByCompensationThenSameKey_isAllowed` (D5 ràng buộc 4, G1b)
- [ ] `ChargePaymentIT#twoLivePaymentsSameRegistration_differentKeys_secondIs409_gatewayCalledOnce` (AC5.2)
- [ ] `ChargePaymentIT#sameKeyAfterFailed_replays_noGatewayCall` (AC5.3)
- [ ] `ChargePaymentIT#partialIndexViolation_neverSurfacesAs500` (AC5.4)

**T-MSG — relay và retry chain (D2)** → tiền đề M-19, M-23 (bằng chứng relay là M-41, P4)
- [ ] `ConsumerRetryChainIT#transientFailure_retriedThreeTimes_thenParkingLot` (AC2.5)
- [ ] `ConsumerRetryChainIT#nonRetryable_goesStraightToParkingLot_withOriginalRoutingKey` (AC2.5)

**T-SEAT — seat claim (D3)** → M-18, M-19
- [ ] `SeatClaimIT#versionConflictFromOrganizerEdit_retriesAndSucceeds` (AC3.1)
- [ ] `SeatClaimIT#organizerEdit_neverWritesSeatsTaken` — claim + edit đồng thời, `seats_taken` cuối đúng (AC3.2, C15)
- [ ] `SeatClaimIT#retriesExhausted_is503_notRecorded` (AC3.3)
- [ ] `SeatClaimIT#full_isSoldOut_recorded` (AC3.4)
- [ ] `SeatClaimMetricsTest#conflictAndExhaustion_counted` (AC3.6)

**T-LOCK — SeatLock (D4)** → tiền đề M-18, M-19 (bằng chứng đa instance là M-32/M-34, P3)
- [ ] `InProcessSeatLockTest#sameWorkshop_mutuallyExclusive`, `#differentWorkshops_doNotContend` (AC4.1)
- [ ] `InProcessSeatLockTest#waitTimeout_throwsSeatLockUnavailable` (AC4.2)
- [ ] `InProcessSeatLockTest#entriesRemovedAfterRelease` (AC4.5)
- [ ] `ReserveSeatLockOrderingIT#lockReleasedOnlyAfterCommit` — request 2 lấy được lock thì đã thấy row của request 1 (AC4.3)
- [ ] `ReserveSeatLockOrderingIT#lockReleasedOnRollback` (AC4.4)
- [ ] `ReserveSeatWebTest#lockUnavailable_is503_withRetryAfter` (AC4.2)

---

## 1. Hạ tầng dùng chung (shared, trước nghiệp vụ)

Tất cả code ở `com.unihub.shared` (rule 3, `backend-coding-standards.md` §13). Mỗi mục kèm test của chính nó.

### 1.1 Read/Write routing — INV-03
- [ ] `[→ M-22]` `AbstractRoutingDataSource` bọc trong `LazyConnectionDataSourceProxy`; `readOnly` → Replica, `readOnly` + `@ReadFromPrimary` → Master, read-write → Master (§20)
- [ ] `[→ M-22]` `[M-13]` Cấu hình datasource replica qua `@ConfigurationProperties`, secrets inject runtime (Gitleaks M-13 phải tiếp tục xanh); local dev trỏ replica về Master (`docker-compose.yml` không có replica — giữ nguyên)
- [ ] `[→ M-22]` `[M-16]` `ReplicaIntegrationTestSupport` (`integration-testing.md` §1–§2): streaming replica từ `pg_basebackup` trên `postgres:16-alpine`, helper pause/resume WAL replay, resume trong `finally` và assert `pg_is_wal_replay_paused() = false`, **không** `withReuse(true)` — đóng mục F4 trong `IMPLEMENTATION-READINESS.md`; `IntegrationTestInfrastructureIT` (M-16) vẫn xanh với base class mới
- [ ] `[→ M-22]` Test unit/slice cho routing key chọn đúng datasource (bằng chứng chính là M-22 ở Mục 3.8)

### 1.2 Idempotency — INV-02 (phụ thuộc Q1)
- [ ] `[→ M-20, M-21]` `[M-58 · P7]` Migration `V3__idempotency_store_result.sql` (Q1) — chịu được đọc bởi pod cũ/mới (INV-15, `architecture.md` §6.2); bảng còn rỗng nên an toàn
- [ ] `[→ M-20]` `IdempotencyStore` port + `IdempotencyScope` (`REGISTRATION_CREATE`, `PAYMENT_CHARGE`) + `IdempotencyKeyInUseException` (`CONFLICT`) (§21)
- [ ] `[→ M-20]` Adapter JDBC: `find` đọc Master, `record` insert bằng `JdbcTemplate` để unique violation nổi lên ngay, dịch `DuplicateKeyException` (§21 rule 4)
- [ ] `[→ M-20]` `[M-17]` Web: đọc + validate header `Idempotency-Key` đúng contract (thiếu/sai định dạng → `400` dạng `ProblemDetail`, `ProblemShapeIT` phủ nhánh mới)
- [ ] `[→ M-21]` `[M-35 · P3]` Job dọn key quá hạn (`unihub:lock:job:idempotency-cleanup`, qua `JobLock`), cửa sổ giữ theo `database-schema.md` §8.1, `Clock` inject được; **chỉ xóa key `COMPLETED`** (D1 ràng buộc 6)

### 1.3 Outbox & RabbitMQ — INV-10
- [ ] `[→ M-25]` `[M-16]` Thêm `spring-boot-starter-amqp`; RabbitMQ container trong `IntegrationTestSupport` dùng thật (M-16 vẫn xanh)
- [ ] `[→ M-25]` `OutboxMessage`, `EventPublisher` (port), `OutboxEventPublisher` ghi `event_publications` **trong** business transaction (`MANDATORY`) (§8.2–§8.3)
- [ ] `[→ M-19, M-23]` Khai báo topology `unihub.events` + các queue Phase 2 cần (`unihub.reconciliation.queue`, queue của Q2; khai báo đủ bảng `messaging.md` §2 nếu rẻ) + retry chain 3 tầng **riêng cho từng queue**, tất cả dồn về **một** `unihub.parking-lot` dùng chung (`messaging.md` §4; C12)
- [ ] `[→ M-19, M-23]` `[M-41 · P4]` `[M-35 · P3]` Relay: chu kỳ 1s, batch 200, publisher confirm từng message, `mandatory = true`, không transaction bao quanh publish, khóa `unihub:lock:job:outbox-relay` qua `JobLock` (`messaging.md` §5)
- [ ] `[→ M-25]` Job dọn `event_publications` đã publish (`unihub:lock:job:event-publication-cleanup`)
- [ ] `[→ M-19, M-23]` `[M-40 · P4]` `[M-12]` Hạ tầng consumer: listener gọi đúng một `port/in`, không nuốt exception, `NonRetryableMessageException` → parking-lot; `AmqpRejectAndDontRequeueException` bị cấm (§8.5–§8.6) — cân nhắc thêm rule ArchUnit (M-12) cho lệnh cấm này

### 1.4 Nợ từ Phase 1 dùng hạ tầng ở 1.3
- [ ] `[§4.5 — M-04]` `[M-38 · P4]` `CancelWorkshop`/`UpdateWorkshop` publish `WorkshopCancelled`/`WorkshopRescheduled` qua translator → outbox (`unihub.workshop.event.cancelled`/`.rescheduled`); thêm test vào M-04, gỡ dòng tương ứng khỏi `test-matrix.md` §4.5 (thông báo cho sinh viên là M-38, Phase 4)
- [ ] `[§4.5 — M-04]` `[M-54 · P6†]` Nếu Q10 = xây ngay: `AuditTrail` (after-commit, không bao giờ throw, metric khi lỗi — §12) và gọi từ các use case workshop; gỡ dòng §4.5 tương ứng

### 1.5 Redis — M-08 (hoãn từ Phase 1; không phải invariant)
- [ ] `[→ M-08]` `[M-16]` Thêm Redisson, cấu hình qua properties, Redis container trong IT
- [ ] `[→ M-08]` Cache-aside `detail` (300s), `seats` (3s), `list:{filterHash}` (30s) đúng `workshop.md` §2 / `distributed-coordination.md` §5; Redis lỗi (`distributed-coordination.md` §4) → rơi xuống DB, không lỗi request
- [ ] `[→ M-08]` Invalidation `DEL` sau commit cho edit/cancel/reschedule; seat change từ Registration invalidate **qua interface của Workshop**, không chạm key `workshop:*` trực tiếp
- [ ] **`[M-08]`** Test (Integration, Redis thật): hit/miss, refresh khi nguồn đổi, cache down vẫn đúng; gỡ M-08 khỏi `test-matrix.md` §4.5 và §4.2 (`⚠`)
> Nếu thiếu thời gian, M-08 được hoãn tiếp (không chặn sign-off) — ghi lý do vào `test-matrix.md` §4.5. Không bao giờ để số ghế dùng cho *quyết định* đi qua cache.

---

## 2. Tiền đề định danh & catalog

### 2.1 Đăng nhập (Student cần token thật để đăng ký)
- [ ] `[→ M-18, M-19]` Q7 đã chốt; dữ liệu Student test có `password_hash` (seed hoặc theo luồng đã thiết kế)
- [ ] `[→ M-18, M-19]` `[M-02]` `[M-17]` `POST /api/v1/auth/login` đúng `paths/user.yaml`: BCrypt, kiểm `users.status = 'ACTIVE'` trước khi ký, lỗi không tiết lộ lý do, JWT HS256 30 phút mang permission list đã resolve (`auth-and-identity.md` §2–§4; token do login cấp phải qua được decoder của M-02), body trả `UserSummary`; contract-diff (M-17) phủ operation mới
- [ ] `[§4.5 — M-01…M-03]` Test S-9 (đã hoãn từ Phase 1): user `SUSPENDED` bị từ chối đăng nhập mới; token cấp trước đó vẫn chạy tới `exp` — thêm test vào M-02, gỡ dòng S-9 khỏi §4.5
- [ ] `[M-61 · P7]` Rate limit cho login **không** làm ở đây (INV-17) — chỉ ghi chú

### 2.2 Publish workshop
- [ ] `[→ M-18, M-19]` `[— không dòng]` `POST /api/v1/workshops/{id}/publish` (`DRAFT`→`PUBLISHED`, `WORKSHOP_EDIT`) — không có workshop `PUBLISHED` thì không đăng ký được. Contract đánh dấu endpoint này là suy luận và M-04 loại trừ rõ nên không có dòng matrix. Vẫn viết unit + IT + contract-diff (M-17); quyết định có thêm dòng mới hay không

---

## 3. TDD theo thứ tự phụ thuộc nghiệp vụ

Với mỗi mục: Red → Green → Refactor → IT nếu dòng yêu cầu → cập nhật `test-matrix.md` §2.2 trong **cùng commit** (`Not started` → `Drafted` → `Passing`, `Class#method` thật).

### 3.1 Domain model & seat claim (phụ thuộc Q3, Q11)
- [ ] `[→ M-18, M-19]` `[M-33 · P3]` Aggregate `Registration` (Tier A): state machine `RESERVED`/`CONFIRMED`/`CANCELLED`/`EXPIRED` theo [ST §2] `registrations.status`, `Version`, invariant `RESERVED ⇔ hold_expires_at` (§9.2)
- [ ] `[→ M-19, M-23]` Aggregate `Payment` (Tier A): `PENDING`→`SUCCEEDED`/`FAILED`/`CANCELLED` theo [ST §2] `payments.status`, terminal phải reject
- [ ] `[→ M-18, M-19]` JPA entity riêng + repository adapter so version tường minh, ném `OptimisticConflictException`; conditional UPDATE `status = 'RESERVED'` theo D2 ràng buộc 1 (C8)
- [ ] `[→ M-18, M-19]` **(D3, C15)** `WorkshopJpaEntity.seatsTaken` → `@Column(updatable = false)`; organizer edit không bao giờ ghi `seats_taken`; test `SeatClaimIT#organizerEdit_neverWritesSeatsTaken`
- [ ] `[→ M-18, M-19]` `[M-30 · P3]` Workshop `api` port claim/release seat với `Propagation.MANDATORY` — SQL đổi `seats_taken` chỉ nằm trong module Workshop (§7 type 2, §19.5); IT: gọi ngoài transaction bị từ chối
- [ ] `[→ M-18, M-19]` `[M-28, M-34 · P3]` `SeatLock` port + adapter theo Q11; `ReserveSeat` (entry, không `@Transactional`) + `ReserveSeatTransaction` đúng §25 (two-bean, không gọi qua `this.`)
- [ ] `[M-12]` `[M-15]` ArchUnit vẫn 18 rule xanh (thêm rule mới nếu cần, không nới rule cũ); JaCoCo phủ package `registration`/`payment` mới

### 3.2 Đăng ký miễn phí
- [ ] **`[M-18]`** Workshop free: `createRegistration` → `CONFIRMED` ngay, `seats_taken + 1` cùng transaction, **không** chạm gateway ([PW §5] row 1: `is_paid=false` × mọi trạng thái breaker); đã đăng ký → `409`; hết ghế → `409`; workshop không `PUBLISHED` → theo contract. `ticket_code`/`qr_payload` để `NULL` tới Phase 5 (`[M-42 · P5]`)

### 3.3 Idempotency trên registration — INV-02
- [ ] **`[M-20]`** Cùng `Idempotency-Key` chạy đúng một lần; retry trả lại *đúng* Result gốc (kể cả `409` sold-out); hai request cùng key song song → một thắng, một nhận `IdempotencyKeyInUseException`; lỗi tạm thời (`503`, mất race version) **không** được ghi; key row commit cùng transaction với registration (rollback cả hai)
- [ ] **`[M-21]`** Key chỉ giữ trong cửa sổ có hạn; job dọn xóa đúng biên ([BVA §4] shape TTL), dùng `Clock` inject, không chờ thật

### 3.4 Outbox atomicity — INV-10
- [ ] **`[M-25]`** Row `unihub.registration.ticket.confirmed` commit nguyên tử cùng registration write: ép lỗi sau insert outbox → không có cả hai; thành công → có cả hai (fixture `test-data-management.md` §3). Consumer gửi thông báo là `[M-37 · P4]`

### 3.5 Đăng ký có phí & thanh toán (phụ thuộc Q2, Q5, Q14)
- [ ] `[→ M-19]` `[M-33 · P3]` Workshop paid: `createRegistration` → `RESERVED`, `hold_expires_at = now + 15m` (`Clock`); có publish `hold.reserved` ở Phase 2 không thì theo bảng ranh giới (consumer là Phase 3)
- [ ] `[→ M-19, M-23, M-24]` `[M-13]` Adapter Stripe **sandbox** (`infrastructure/out/client`), secret key inject runtime (INV-18), HTTP `connectTimeout`/`readTimeout` ≤ 3s
- [ ] `[→ M-19]` `[→ M-20]` `chargePayment` (`PAYMENT_CHARGE`): ownership, registration phải `RESERVED` và chưa hết hạn, orchestrator gọi gateway **ngoài** transaction (§19.4), tạo `payments` `PENDING`, partial unique index "một payment sống" (D5) làm backstop; test retry cùng key không gọi gateway lần hai bổ sung vào M-20 (scope `PAYMENT_CHARGE`); **G1:** xin quyền breaker trước tx1, transaction bù `PENDING → CANCELLED` + xóa key nếu bị từ chối sau tx1 (D1 ràng buộc 7); **D5:** partial unique index — vi phạm → `409` domain failure, thử lại cần key mới `[M-77]`
- [ ] `[→ M-19]` `[→ M-78]` `[M-17]` `getPayment`, `getRegistration`, `listMyRegistrations` (ownership; `404` cho người không sở hữu theo contract) — web cần để poll trạng thái; contract-diff phủ cả ba
- [ ] `[→ M-19, M-23]` Webhook `POST /api/v1/webhooks/payment-gateway`: verify chữ ký trên **raw body** trước khi parse, không JWT, không rate limit; `400` khi chữ ký sai; không tìm thấy giao dịch → `200` + log; không `PENDING` → `200` no-op (`payment.md` §7)
- [ ] `[→ M-19]` `[M-40 · P4]` Payment `SUCCEEDED` → outbox (routing key Q2) → consumer Registration re-check trạng thái tại Master → `CONFIRMED` + `ticket.confirmed` outbox cùng transaction; redelivery là no-op
- [ ] `[→ M-19]` **(D2, C10, C11)** `[M-75]` Nhánh bù trừ, **phần Phase 2**: payment `SUCCEEDED` khi registration không còn `RESERVED` → Registration ghi outbox `unihub.registration.confirmation.rejected` → consumer Payment trên `unihub.payment-refund.queue` chuyển `SUCCEEDED → REFUND_REQUIRED` bằng conditional UPDATE (chỉ đổi trạng thái, không gọi gateway)
- [ ] `[M-76 · P3]` Nhánh bù trừ, **phần Phase 3** (không làm ở đây): gọi refund với key `refund:{paymentId}`, `REFUND_REQUIRED → REFUNDED`, sửa ADR breaker cho call-site thứ ba, xử lý refund bị kẹt; phụ thuộc Q10
- [ ] **`[M-19]`** Paid: ghế chỉ `CONFIRMED` sau khi payment hoàn tất; gồm unit test cho payment orchestration và nhóm test T-CONF (Mục 0C)
- [ ] `[— không dòng]` Test S-17 chữ ký webhook (nếu Q9 = Phase 2, ghi vào dòng mới M-79): chữ ký giả/thiếu/body bị sửa → `400`, không đọc gì từ body

### 3.6 Reconciliation — INV-04
- [ ] `[→ M-23]` `[M-35 · P3]` Sweep (`unihub:lock:job:payment-reconciliation` qua `JobLock`): `status = 'PENDING' AND initiated_at < now - 15m` → mỗi row một `unihub.payment.reconciliation.requested` qua outbox
- [ ] `[→ M-23, M-24]` Consumer trên `unihub.reconciliation.queue`: gọi status API qua breaker (TimeLimiter 10s, Retry 3, backoff 1/2/4s), re-check `status <> 'PENDING'` → no-op, update có điều kiện `version`; **G2:** khi resolve một payment `PENDING`, chuyển key `IN_PROGRESS` tương ứng (`resource_id` = payment id, C27) sang `COMPLETED` + Result trong cùng transaction (D1 ràng buộc 8)
- [ ] **`[M-23]`** Payment kẹt `PENDING` > 15 phút được giải quyết mà **không cần webhook**; race webhook ↔ sweep hội tụ đúng một trạng thái ([ST §2], [DT §3.2], [BVA §4] ngưỡng 15 phút) — `Clock` + TTL rút ngắn, gateway stub (WireMock)

### 3.7 Circuit breaker & fault isolation — INV-05 (phụ thuộc Q8)
- [ ] `[→ M-24]` Resilience4j: **một** instance `paymentGateway` dùng chung cho charge + sweep; COUNT_BASED 20, 50%, open 30s, half-open 5; thứ tự CircuitBreaker → Retry → TimeLimiter (`payment.md` §2)
- [ ] `[→ M-24, M-27]` Fallback sync: `503` + thông báo rõ, **không** tạo `payments` row, hold không đổi (`payment.md` §3); fallback async: bỏ qua chu kỳ (`payment.md` §5)
- [ ] **`[M-24]`** Gateway chậm/chết mở breaker, fail fast, tự phục hồi qua `HALF_OPEN`; browsing + đăng ký free vẫn chạy ([DT §3.3], [PW §5] row 1; `resilience-testing.md` §2.2 scenario 1). Nửa Kubernetes là `[M-64 · P7]`
> ⚠ Exit criterion Phase 2 nói "never cascades into browsing/check-in", nhưng module Check-in chưa tồn tại tới Phase 5. M-24 ở Phase 2 chỉ chứng minh được browsing + free registration — ghi rõ phần check-in là gap ở `phase-2.md` §5 (hoặc sửa criterion trong `test-plan.md`), **không** tick như đã chứng minh.

### 3.8 Master routing — INV-03
- [ ] **`[M-22]`** Với WAL replay trên replica bị pause: lookup idempotency và view ngay-sau-ghi vẫn thấy dữ liệu (đọc Master); một query `readOnly` thường đọc replica (chứng minh routing thực sự tách) — `resilience-testing.md` §2.5, `ReplicaIntegrationTestSupport`

### 3.9 Rollups
- [ ] **`[M-26]`** (SC-05) — Không trừ tiền trùng khi retry; không payment nào treo vĩnh viễn — rollup M-20, M-21, M-23 (chỉ `Passing` khi cả ba `Passing`)
- [ ] **`[M-27]`** (SC-07) — Gateway outage không lan sang browsing/check-in/reporting — rollup M-24 (nửa Kubernetes là `[M-64 · P7]`; nửa check-in xem cảnh báo ở 3.7)

> `[— không dòng]` Không thuộc Phase 2: `cancelRegistration` (contract đánh dấu suy luận, cần permission riêng), `listWorkshopRegistrations` (`REPORT_VIEW`, gắn `[M-49 · P5†]`). Không làm trừ khi bạn yêu cầu.

---

## 4. Web Application (phụ thuộc Q6, Q12–Q15)

Theo `web-coding-standards.md` (`app → routes → features → shared`, types từ `openapi-typescript`, idempotency key, query keys/invalidation). Toàn bộ mục này là bằng chứng cho dòng mới **M-78** (tạo ở task đầu tiên); hành trình E2E đầy đủ là `[M-69 · P7]`.

- [ ] **`[M-78]`** Thêm dòng `M-78` vào `test-matrix.md` §2.2 theo skeleton §2.3: Req ID `REQ-1.3.1.3a, REQ-1.3.1.3b`, "Web sign-in, registration, checkout and my-registrations components render and handle the Phase 2 endpoints' outcomes", Level `Unit — e2e-testing.md §1 (Vitest + RTL)`, Phase 2 — Phase 2 chưa có dòng nào cho phần web
- [ ] `[→ M-78]` Regenerate types từ `packages/api-contract` sau mọi thay đổi contract ở Mục 0
- [ ] `[M-78]` Đăng nhập: trang sign-in, store token (Q12), HTTP client gắn bearer, `401` → về sign-in; gate UI theo permission (Q13)
- [ ] `[M-78]` Nút đăng ký trên trang detail: sinh `Idempotency-Key` một lần cho mỗi ý định (giữ qua retry), free → hiện `CONFIRMED`, paid → chuyển checkout; `409` hiện đúng lý do (Q6), `503` retry theo ngân sách (Q15); invalidate query số ghế
- [ ] `[M-78]` Checkout: thu phương thức thanh toán (Q14), `chargePayment` với idempotency key riêng, poll `getPayment` (Q15), hiện thời gian giữ chỗ còn lại; `503` hiện thông báo của `payment.md` §3
- [ ] `[M-78]` `[M-42 · P5]` Trang "Đăng ký của tôi" (`listMyRegistrations`) — hiển thị e-ticket QR là Phase 5
- [ ] **`[M-78]`** Component test Vitest + RTL cho từng màn hình trên (gồm nhánh `409`/`503`/`401`); điền `Class#method` thật vào dòng M-78

---

## 5. CI/CD

- [ ] `[M-14]` `[→ M-78]` Job `web` trong `.github/workflows/ci.yml`: install, typecheck, lint (oxlint + ESLint boundaries), Vitest — đóng phần "not yet run in CI" của web §13 #1; cập nhật ô Test case(s) của M-14 cho job mới
- [ ] `[M-17]` `contract-diff` phủ mọi operation mới (registration, payment, webhook, login, publish); không mở rộng exclusion ở `test-matrix.md` §4.4; cập nhật số operation trong ô M-17
- [ ] `[M-15]` JaCoCo tự phủ `registration`/`payment` `application.usecase` + `domain.*` ≥ 80% — kiểm báo cáo thật sự liệt kê các package mới, cập nhật số liệu trong ô M-15
- [ ] `[— không dòng]` Hoàn tất mục còn lại của Phase 1: OWASP Dependency-Check xanh + tick ở `phase-1-foundation.md` Mục 4 (`NVD_API_KEY`, D6) — job non-blocking, không gắn dòng matrix (`phase-1.md` §4 U4)
- [ ] `[— không dòng]` (Tùy chọn, D6) ghim tag image Semgrep/Dependency-Check/Gitleaks thay cho `latest`

---

## 6. Đồng bộ Test Matrix

- [ ] `[M-08, M-18…M-27, M-78]` Mỗi mục ở Mục 1–4 xong: cập nhật đúng dòng ở `test-matrix.md` §2.2 trong cùng commit
- [ ] `[§4.5]` Mọi mục §4.5 được giải quyết trong Phase 2 (S-9 của M-01…M-03, outbox của M-04, có thể audit của M-04, M-08) được gỡ khỏi §4.5; mục mới hoãn (Q10, Q11, hold expiry M-33, refund gateway của M-75 → M-76 ở Phase 3) được thêm vào
- [ ] `[§4, §5]` Refresh §4 và §5 trước sign-off (tổng số dòng tăng nếu thêm M-78/M-79)

---

## 7. Đóng Phase 2 (Exit / Sign-off)

Exit criteria chép từ `test-plan.md` §1, dòng Phase 2:

- [ ] **`[M-20, M-26]`** Idempotency test chứng minh retry payment request tạo **0** lần trừ tiền trùng
- [ ] **`[M-22]`** Master-routing test chứng minh không read replica cũ nào làm lọt duplicate
- [ ] **`[M-25]`** Outbox test chứng minh event row và business write cùng commit hoặc cùng rollback
- [ ] **`[M-23]`** Reconciliation test chứng minh payment `PENDING` kẹt tự giải quyết thành Succeeded/Cancelled mà không cần webhook
- [ ] **`[M-24, M-27]`** Circuit-breaker test chứng minh gateway outage không lan sang browsing/check-in (xem cảnh báo phần check-in ở Mục 3.7)

Universal checklist:

- [ ] `[M-18…M-27]` Tất cả = `Passing` (mọi dòng mang INV-02/03/04/05/10 — M-20…M-27 — **bắt buộc**); dòng không-INV chưa xong (M-08, M-18, M-19, M-78) phải có lý do ở `phase-2.md` §5
- [ ] `[M-15]` JaCoCo ≥ 80% trên `usecase`/`domain` của Registration/Payment
- [ ] `[M-13, M-14, M-17]` CI xanh toàn bộ (`lint`, `unit-test`, `integration-test`, `contract-diff`, `secret-scan-gitleaks`, `web`) trên **cùng một commit SHA**
- [ ] `[— không dòng]` Không `TODO`/`FIXME` trong test code Phase 2; không `@Disabled`/`.skip(` trên test gắn INV của Phase 2
- [ ] `[— không dòng]` Copy `phase-template.md` → `docs/testing/reports/release-sign-offs/phase-2.md`, điền §2–§6 với evidence thật
- [ ] `[— không dòng]` Ký xác nhận §6 của `phase-2.md`

> Phase 2 chỉ hoàn tất khi `phase-2.md` tồn tại, mọi ô được tick với evidence thật, và §6 đã ký — **không phải khi code chạy được**.

---

## Ghi chú cho Claude Code

- Khi được yêu cầu "tiếp tục Phase 2", đọc file này trước, làm mục chưa tick đầu tiên theo thứ tự Mục 0 → 1 → 2 → 3 → 4 → 5 → 6 → 7. Mục 4 (web) có thể làm song song với Mục 3 khi endpoint tương ứng đã có.
- D1–D5 (Mục 0A) là quyết định đã chốt; AC của chúng là điều kiện tick các task liên quan. Mâu thuẫn mức **Chặn** ở Mục 0B phải được giải quyết (sửa tài liệu sở hữu, hoặc người dùng duyệt đề xuất) trước khi viết code của dòng bị chặn.
- Mục 0 là quyết định của người dùng: **không tự chốt**. Gặp mục phụ thuộc một Q chưa tick thì dừng và hỏi, đưa kèm khuyến nghị.
- Chỉ đổi Status của dòng mang nhãn **đậm** `[M-xx]` khi task đó xong; nhãn `[→ …]` và `[… · Pn]` không bao giờ làm dòng lên `Passing`.
- Không kéo việc Phase 3 vào đây (xem bảng ranh giới). Không bỏ lớp DB check với lý do "đã có lock", và không coi lock là đủ.
- Không tick khi test chưa pass thật và `test-matrix.md` chưa cập nhật.

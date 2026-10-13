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
| Matrix rows | M-18 … M-27 (Phase 2), M-08 (hoãn từ Phase 1 sang đây, `phase-1.md` §5), M-75 (dòng mới đề xuất cho web Phase 2 — Mục 4) |
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
| M-75 *(mới)* | — | 4 | 0.Q6, Q12–Q15 |

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

- [ ] `[→ M-20, M-21]` **Q1 — Schema `idempotency_keys` lưu Result, không lưu HTTP response** (§27 #1, **blocker M-20**): thay `response_status`/`response_body` bằng `result_type VARCHAR` + `result_body JSONB`; sửa chữ ở `registration.md` §4, `payment.md` §8. → `database-schema.md` §8.1, rồi migration `V3__…` (Mục 1.2)
- [ ] `[→ M-19]` **Q2 — Routing key "payment succeeded → registration `CONFIRMED`"** (§27 #8, **blocker M-19**): đặt tên theo quy ước `unihub.<domain>.<entity>.<action>`, queue đích, retry chain riêng (§4). Nửa còn lại của #8 ("workshop cancelled → hủy registrations, trả ghế") không có dòng matrix nào ở Phase 2 — có thể để mở, ghi rõ. → `messaging.md` §2, `registration.md`, `payment.md`
- [ ] `[→ M-18, M-19]` `[M-28 · P3]` **Q3 — `workshops.version` dùng chung cho seat claim và organizer edit** (§27 #9): giữ chung version, hay seat counter chỉ dựa vào atomic increment + CHECK. Ảnh hưởng trực tiếp tới seat-claim SQL của Mục 3.1 → `database-schema.md` §3.1, `registration.md` §2
- [ ] `[→ M-18, M-19]` **Q4 — Permission cho `POST /api/v1/registrations`** (§27 #10, web §13 #12): contract hiện không khai báo (mọi user đã xác thực + ownership). Chốt "không cần" hoặc đặt tên code mới + migration seed cho Student → `auth-and-identity.md`, `user.md`
- [ ] `[→ M-20, M-24]` **Q5 — `503` của `chargePayment` có được ghi idempotency không** (web §13 #6): `payment.md` §8 ghi lại response circuit-open; §21 rule 5 nói lỗi tạm thời không ghi; contract nói "safe to retry". Đề xuất: sửa `payment.md` §8 theo §21 → `payment.md` §8
- [ ] `[→ M-18, M-19, M-75]` **Q6 — Lý do `409` máy đọc được** (web §13 #5): "sold out" vs "already registered" (và 2 nguyên nhân `409` của `chargePayment`). Đề xuất: `type` URI riêng cho từng lý do → `common/error.yaml`, `paths/registration.yaml`, `paths/payment.yaml`, `backend-coding-standards.md` §17
- [ ] `[→ M-18, M-19]` **Q7 — Mật khẩu cho tài khoản Student đồng bộ từ legacy** (`paths/user.yaml` login description: first-login setup **chưa có thiết kế**). Phase 2 cần ít nhất một Student đăng nhập được để đăng ký. Lựa chọn: (a) chỉ seed tài khoản test có `password_hash` cho dev/test, thiết kế first-login để sau; (b) thiết kế first-login ngay. → `user.md`, `paths/user.yaml`
- [ ] `[→ M-24]` **Q8 — `minimumNumberOfCalls` của breaker** (`IMPLEMENTATION-READINESS.md` E2) → `payment.md` §2
- [ ] `[— không dòng]` **Q9 — Phase sở hữu test chữ ký webhook S-17** (E6) — S-17 chưa gắn dòng matrix nào. Đề xuất Phase 2, vì webhook được xây ở đây; nếu đồng ý thì thêm dòng mới (M-76) → `security-testing.md` §2.2, `test-matrix.md` §2.2
- [ ] `[M-54 · P6†]` `[§4.5 — M-04]` **Q10 — AuditTrail**: xây port `AuditTrail` ngay ở Phase 2 (registration/payment là thao tác bị audit, `backend-coding-standards.md` §12) hay tiếp tục hoãn như M-04. Nếu hoãn: thêm dòng vào `test-matrix.md` §4.5 cho registration/payment
- [ ] `[→ M-18, M-19]` `[M-34 · P3]` **Q11 — `SeatLock` ở Phase 2**: (a) port + adapter in-process (tiền lệ `InProcessJobLock`), Redisson ở Phase 3; hay (b) Redisson adapter ngay vì Mục 1.5 đã thêm Redisson cho M-08. Cả hai đều giữ DB check làm lớp quyết định. → ghi trong `test-matrix.md` §4.5 như dòng M-09

### 0.2 Web Application

- [ ] `[→ M-75]` **Q12 — Lưu access token ở đâu** (web §13 #3; tạm thời: memory only) → `auth-and-identity.md` (mục Web-client), rồi `web-coding-standards.md` §8/§11
- [ ] `[→ M-75]` **Q13 — Nguồn permission để gate UI** (web §13 #4): cho phép đọc claim `permissions` chỉ cho UX, hay thêm permissions vào `LoginResponse` → `schemas/user.yaml`, `auth-and-identity.md` §2
- [ ] `[→ M-19, M-75]` **Q14 — Luồng thu phương thức thanh toán phía client** (web §13 #8): `paymentMethodToken` là field suy luận. Đề xuất cho sandbox: Stripe test PaymentMethod (`pm_card_visa`…) qua Stripe.js, không giữ dữ liệu thẻ ở UniHub → `payment.md`, `schemas/payment.yaml`
- [ ] `[→ M-75]` **Q15 — Giá trị thời gian phía client** (web §13 #7): `staleTime`/`refetchInterval` số ghế (cận dưới 3s), chu kỳ + điều kiện dừng poll `getPayment`, ngân sách tự retry `503` → `web-coding-standards.md` §7/§10

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
- [ ] `[→ M-21]` `[M-35 · P3]` Job dọn key quá hạn (`unihub:lock:job:idempotency-cleanup`, qua `JobLock`), cửa sổ giữ theo `database-schema.md` §8.1, `Clock` inject được

### 1.3 Outbox & RabbitMQ — INV-10
- [ ] `[→ M-25]` `[M-16]` Thêm `spring-boot-starter-amqp`; RabbitMQ container trong `IntegrationTestSupport` dùng thật (M-16 vẫn xanh)
- [ ] `[→ M-25]` `OutboxMessage`, `EventPublisher` (port), `OutboxEventPublisher` ghi `event_publications` **trong** business transaction (`MANDATORY`) (§8.2–§8.3)
- [ ] `[→ M-19, M-23]` Khai báo topology `unihub.events` + các queue Phase 2 cần (`unihub.reconciliation.queue`, queue của Q2; khai báo đủ bảng `messaging.md` §2 nếu rẻ) + retry chain 3 tầng + parking-lot riêng từng queue (`messaging.md` §4)
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
- [ ] `[→ M-18, M-19]` JPA entity riêng + repository adapter so version tường minh, ném `OptimisticConflictException`
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
- [ ] `[→ M-19]` `[→ M-20]` `chargePayment` (`PAYMENT_CHARGE`): ownership, registration phải `RESERVED` và chưa hết hạn, orchestrator gọi gateway **ngoài** transaction (§19.4), tạo `payments` `PENDING`, `registration_id UNIQUE` làm backstop; test retry cùng key không gọi gateway lần hai bổ sung vào M-20 (scope `PAYMENT_CHARGE`)
- [ ] `[→ M-19]` `[→ M-75]` `[M-17]` `getPayment`, `getRegistration`, `listMyRegistrations` (ownership; `404` cho người không sở hữu theo contract) — web cần để poll trạng thái; contract-diff phủ cả ba
- [ ] `[→ M-19, M-23]` Webhook `POST /api/v1/webhooks/payment-gateway`: verify chữ ký trên **raw body** trước khi parse, không JWT, không rate limit; `400` khi chữ ký sai; không tìm thấy giao dịch → `200` + log; không `PENDING` → `200` no-op (`payment.md` §7)
- [ ] `[→ M-19]` `[M-40 · P4]` Payment `SUCCEEDED` → outbox (routing key Q2) → consumer Registration re-check trạng thái tại Master → `CONFIRMED` + `ticket.confirmed` outbox cùng transaction; redelivery là no-op
- [ ] **`[M-19]`** Paid: ghế chỉ `CONFIRMED` sau khi payment hoàn tất; gồm unit test cho payment orchestration
- [ ] `[— không dòng]` Test S-17 chữ ký webhook (nếu Q9 = Phase 2, ghi vào dòng mới M-76): chữ ký giả/thiếu/body bị sửa → `400`, không đọc gì từ body

### 3.6 Reconciliation — INV-04
- [ ] `[→ M-23]` `[M-35 · P3]` Sweep (`unihub:lock:job:payment-reconciliation` qua `JobLock`): `status = 'PENDING' AND initiated_at < now - 15m` → mỗi row một `unihub.payment.reconciliation.requested` qua outbox
- [ ] `[→ M-23, M-24]` Consumer trên `unihub.reconciliation.queue`: gọi status API qua breaker (TimeLimiter 10s, Retry 3, backoff 1/2/4s), re-check `status <> 'PENDING'` → no-op, update có điều kiện `version`
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

Theo `web-coding-standards.md` (`app → routes → features → shared`, types từ `openapi-typescript`, idempotency key, query keys/invalidation). Toàn bộ mục này là bằng chứng cho dòng mới **M-75** (tạo ở task đầu tiên); hành trình E2E đầy đủ là `[M-69 · P7]`.

- [ ] **`[M-75]`** Thêm dòng `M-75` vào `test-matrix.md` §2.2 theo skeleton §2.3: Req ID `REQ-1.3.1.3a, REQ-1.3.1.3b`, "Web sign-in, registration, checkout and my-registrations components render and handle the Phase 2 endpoints' outcomes", Level `Unit — e2e-testing.md §1 (Vitest + RTL)`, Phase 2 — Phase 2 chưa có dòng nào cho phần web
- [ ] `[→ M-75]` Regenerate types từ `packages/api-contract` sau mọi thay đổi contract ở Mục 0
- [ ] `[M-75]` Đăng nhập: trang sign-in, store token (Q12), HTTP client gắn bearer, `401` → về sign-in; gate UI theo permission (Q13)
- [ ] `[M-75]` Nút đăng ký trên trang detail: sinh `Idempotency-Key` một lần cho mỗi ý định (giữ qua retry), free → hiện `CONFIRMED`, paid → chuyển checkout; `409` hiện đúng lý do (Q6), `503` retry theo ngân sách (Q15); invalidate query số ghế
- [ ] `[M-75]` Checkout: thu phương thức thanh toán (Q14), `chargePayment` với idempotency key riêng, poll `getPayment` (Q15), hiện thời gian giữ chỗ còn lại; `503` hiện thông báo của `payment.md` §3
- [ ] `[M-75]` `[M-42 · P5]` Trang "Đăng ký của tôi" (`listMyRegistrations`) — hiển thị e-ticket QR là Phase 5
- [ ] **`[M-75]`** Component test Vitest + RTL cho từng màn hình trên (gồm nhánh `409`/`503`/`401`); điền `Class#method` thật vào dòng M-75

---

## 5. CI/CD

- [ ] `[M-14]` `[→ M-75]` Job `web` trong `.github/workflows/ci.yml`: install, typecheck, lint (oxlint + ESLint boundaries), Vitest — đóng phần "not yet run in CI" của web §13 #1; cập nhật ô Test case(s) của M-14 cho job mới
- [ ] `[M-17]` `contract-diff` phủ mọi operation mới (registration, payment, webhook, login, publish); không mở rộng exclusion ở `test-matrix.md` §4.4; cập nhật số operation trong ô M-17
- [ ] `[M-15]` JaCoCo tự phủ `registration`/`payment` `application.usecase` + `domain.*` ≥ 80% — kiểm báo cáo thật sự liệt kê các package mới, cập nhật số liệu trong ô M-15
- [ ] `[— không dòng]` Hoàn tất mục còn lại của Phase 1: OWASP Dependency-Check xanh + tick ở `phase-1-foundation.md` Mục 4 (`NVD_API_KEY`, D6) — job non-blocking, không gắn dòng matrix (`phase-1.md` §4 U4)
- [ ] `[— không dòng]` (Tùy chọn, D6) ghim tag image Semgrep/Dependency-Check/Gitleaks thay cho `latest`

---

## 6. Đồng bộ Test Matrix

- [ ] `[M-08, M-18…M-27, M-75]` Mỗi mục ở Mục 1–4 xong: cập nhật đúng dòng ở `test-matrix.md` §2.2 trong cùng commit
- [ ] `[§4.5]` Mọi mục §4.5 được giải quyết trong Phase 2 (S-9 của M-01…M-03, outbox của M-04, có thể audit của M-04, M-08) được gỡ khỏi §4.5; mục mới hoãn (Q10, Q11, hold expiry M-33) được thêm vào
- [ ] `[§4, §5]` Refresh §4 và §5 trước sign-off (tổng số dòng tăng nếu thêm M-75/M-76)

---

## 7. Đóng Phase 2 (Exit / Sign-off)

Exit criteria chép từ `test-plan.md` §1, dòng Phase 2:

- [ ] **`[M-20, M-26]`** Idempotency test chứng minh retry payment request tạo **0** lần trừ tiền trùng
- [ ] **`[M-22]`** Master-routing test chứng minh không read replica cũ nào làm lọt duplicate
- [ ] **`[M-25]`** Outbox test chứng minh event row và business write cùng commit hoặc cùng rollback
- [ ] **`[M-23]`** Reconciliation test chứng minh payment `PENDING` kẹt tự giải quyết thành Succeeded/Cancelled mà không cần webhook
- [ ] **`[M-24, M-27]`** Circuit-breaker test chứng minh gateway outage không lan sang browsing/check-in (xem cảnh báo phần check-in ở Mục 3.7)

Universal checklist:

- [ ] `[M-18…M-27]` Tất cả = `Passing` (mọi dòng mang INV-02/03/04/05/10 — M-20…M-27 — **bắt buộc**); dòng không-INV chưa xong (M-08, M-18, M-19, M-75) phải có lý do ở `phase-2.md` §5
- [ ] `[M-15]` JaCoCo ≥ 80% trên `usecase`/`domain` của Registration/Payment
- [ ] `[M-13, M-14, M-17]` CI xanh toàn bộ (`lint`, `unit-test`, `integration-test`, `contract-diff`, `secret-scan-gitleaks`, `web`) trên **cùng một commit SHA**
- [ ] `[— không dòng]` Không `TODO`/`FIXME` trong test code Phase 2; không `@Disabled`/`.skip(` trên test gắn INV của Phase 2
- [ ] `[— không dòng]` Copy `phase-template.md` → `docs/testing/reports/release-sign-offs/phase-2.md`, điền §2–§6 với evidence thật
- [ ] `[— không dòng]` Ký xác nhận §6 của `phase-2.md`

> Phase 2 chỉ hoàn tất khi `phase-2.md` tồn tại, mọi ô được tick với evidence thật, và §6 đã ký — **không phải khi code chạy được**.

---

## Ghi chú cho Claude Code

- Khi được yêu cầu "tiếp tục Phase 2", đọc file này trước, làm mục chưa tick đầu tiên theo thứ tự Mục 0 → 1 → 2 → 3 → 4 → 5 → 6 → 7. Mục 4 (web) có thể làm song song với Mục 3 khi endpoint tương ứng đã có.
- Mục 0 là quyết định của người dùng: **không tự chốt**. Gặp mục phụ thuộc một Q chưa tick thì dừng và hỏi, đưa kèm khuyến nghị.
- Chỉ đổi Status của dòng mang nhãn **đậm** `[M-xx]` khi task đó xong; nhãn `[→ …]` và `[… · Pn]` không bao giờ làm dòng lên `Passing`.
- Không kéo việc Phase 3 vào đây (xem bảng ranh giới). Không bỏ lớp DB check với lý do "đã có lock", và không coi lock là đủ.
- Không tick khi test chưa pass thật và `test-matrix.md` chưa cập nhật.

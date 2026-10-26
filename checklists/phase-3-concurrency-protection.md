# Phase 3 — Concurrency Protection: Checklist Triển Khai

> **Mục đích file này:** checklist *thực thi* cho Phase 3, cùng vai trò với `phase-1-foundation.md` và `phase-2-registration-payment.md`. Nó không thay thế `docs/testing/strategy/test-plan.md` §1 (nguồn thẩm quyền cho exit criteria), `docs/testing/reports/test-matrix.md` (trạng thái từng test) hay `docs/testing/reports/release-sign-offs/phase-template.md` (sign-off). File này chỉ **tổng hợp và sắp thứ tự công việc**, trỏ vào 3 file đó làm bằng chứng. Nơi có mâu thuẫn, 3 file kia luôn đúng.
>
> **Cách dùng:** tick `[x]` khi xong thật, không tick trước. Mỗi mục nên có commit tương ứng. Cập nhật **Trạng thái tổng thể** sau mỗi buổi làm việc.

| Trường | Giá trị |
|---|---|
| Phase | 3 — Concurrency Protection |
| Ngày bắt đầu | 2026-10-26 |
| Trạng thái tổng thể | `☐ Chưa bắt đầu` / `☑ Đang làm` / `☐ Chờ sign-off` / `☐ Hoàn tất` — **Mục 0 đã chốt 2026-10-26 (D19–D30, Mục 0A)**; tài liệu sở hữu đã sửa, Q2 đã kiểm chứng; **Mục 1: 1.1–1.3 xong, 1.4 chờ bằng chứng Mục 2/4**; chưa bắt đầu Mục 2 (cập nhật 2026-10-26) |
| Nhánh làm việc | `main` (push thẳng, không PR — `test-matrix.md` §4.4) |
| INV trong scope | INV-06, INV-07, INV-08, INV-09 (`test-plan.md` §3) |
| Matrix rows | M-28 … M-36 (Phase 3), M-76 (derived DR-PAY-2, Phase 3†), các mục hoãn sang Phase 3 ở `test-matrix.md` §4.5: M-09 (`RedissonJobLock`; chạy chồng lấn `UNIQUE (email)`), M-11 (hoãn từ Phase 1), M-28/M-32/M-34 (`InProcessSeatLock`) |
| Tham chiếu | `proposal.md` §11.1–11.2, §15 · `test-plan.md` §1 (dòng Phase 3) · `architecture.md` §4.2, §4.3, §4.6, §6.1 · `registration.md` §2, §3, §5, §6 · `payment.md` ADR breaker, §9, §10 · `distributed-coordination.md` §2, §4, §5 · `messaging.md` §2, §4 · `test-design-techniques.md` §3.1 · `resilience-testing.md` §2.3 · `performance-load.md` §2.2, §2.3 · `backend-coding-standards.md` §8.4–§8.7, §19, §25 · `phase-2.md` §5 (hàng 2, 3, 6, 12) |

### Ký hiệu nhãn

Giữ nguyên quy ước của `phase-2-registration-payment.md` ("Ký hiệu nhãn `[M-xx]`"): **`[M-33]`** đậm = task là bằng chứng của dòng; `[→ M-33]` = tiền đề; `[M-54 · P6]` = dòng của phase khác, chỉ chuẩn bị; `[§4.5]` = đóng một mục hoãn ở `test-matrix.md` §4.5; `[— không dòng]` = không có dòng matrix (ghi lý do).

**Bảng tra ngược (dòng → task):**

| Dòng | INV | Task chứng minh | Task tiền đề |
|---|---|---|---|
| M-28 | INV-06 | 4.1 | Q3 · 1.1, 1.2 |
| M-29 | INV-06 | 4.2 | Q5 · 4.1 |
| M-30 | INV-06 | 4.3 | — |
| M-31 | INV-06 | 4.4 | Q6 |
| M-32 | INV-06 | 4.5 | Q4 · 1.2 |
| M-33 | INV-07 | 3.1–3.3 | Q1, Q9 · C2, C4, C5 · 1.2 |
| M-34 | INV-08 | 2.1 | Q2, Q3 · 1.2 |
| M-35 | INV-09 | 2.2 | Q4 · 1.3 |
| M-36 | INV-06 | 5.1 | Q12 · 4.1 |
| M-76 *(derived)* | — | 3.4, 3.5 | Q7, Q8 · 3.1 |
| M-09 *(§4.5)* | INV-13 | 1.3 (job lock), 2.3 (chồng lấn) | Q10 |
| M-11 *(§4.5)* | — | 2.4 (hoặc hoãn lại có lý do) | Q11 |

---

## Ranh giới Phase 3 ↔ các phase khác (đọc trước khi làm)

Phase 2 đã có *luồng* đăng ký/thanh toán với lớp quyết định ở PostgreSQL (`version` + CHECK). Phase 3 thêm **lớp phối hợp thật** (Redis), **vòng đời giữ chỗ** và **bằng chứng** rằng mỗi lớp tự đứng được.

| Thuộc Phase 3 | Không làm ở đây |
|---|---|
| `RedissonSeatLock`, `RedissonJobLock` thay adapter in-process; fail-closed `503` khi Redis không với tới (INV-08) | Rate limiter (`distributed-coordination.md` §3) — INV-17, Phase 7 |
| Consumer `hold.expired` → `EXPIRED` + trả ghế (INV-07) | Endpoint hủy đăng ký của Student — không có trong scope nào đã lên lịch |
| Refund qua gateway `REFUND_REQUIRED → REFUNDED` (M-76) | "Workshop bị hủy → hủy registrations + trả ghế" (`backend-coding-standards.md` §27 #8, nửa còn mở) — **chưa được lên lịch**; nêu ra với owner, không làm lặng lẽ |
| Bằng chứng 0% overbooking qua 3 biến thể (full stack, bỏ lock, chỉ DB) trên Testcontainers | Chạy K6 ở topology đầy đủ / 12,000 CCU — Phase 8 (M-71…M-74) |
| K6 adversarial 500 VU — **chỉ viết + chạy smoke 1 lần ở local**, không phải evidence gate | Audit publish lên RabbitMQ + consumer `audit_events` — Phase 6 (M-54) |
| Job chạy đúng 1 instance qua `RedissonJobLock` (INV-09) | Alert rules (parking-lot, refund kẹt, reconciliation kẹt) — Phase 6 |
| | Consumer idempotent dưới redelivery như **bằng chứng riêng** (M-40, M-41) — Phase 4. Phase 3 vẫn phải viết consumer idempotent và test redelivery của chính nó |

> ⚠ Không "đơn giản hóa" lớp nào của INV-06: Redis lock là **advisory**; quyết định nhận/từ chối vẫn là conditional UPDATE + CHECK ở Master (`CLAUDE.md`, `architecture.md` §4.2). Biến thể "bỏ lock" (M-29) chỉ tồn tại **trong test**, không bao giờ là cờ cấu hình production.

---

## 0. Quyết định phải chốt trước khi code (blockers)

Theo `CLAUDE.md`: task phụ thuộc mục còn mở thì **dừng lại hỏi**, không tự đoán. Mỗi mục dưới đây có phương án + **đề xuất** của Claude Code. **Chốt 2026-10-26: Nguyễn Thành Tiến chọn mọi đề xuất** (Q1–Q12 → D19–D30, cùng C1, C2, C5, C10), ghi ở Mục 0A. Đánh số tiếp từ D19 để không trùng D1–D18 của Phase 2. Các ô **chưa tick**: chỉ tick khi quyết định đã được **ghi vào tài liệu sở hữu** (cột →), giống Phase 2 Mục 0.

### 0.1 Chặn trực tiếp invariant (mục nào chặn mục nào: xem "Bản đồ chặn" ngay trước Mục 1)

- [x] `[→ M-33, M-34]` **Q1 — ✅ Đã chốt 2026-10-26 (D19): (a), thu hẹp.** INV-07 × INV-08: giữ chỗ hết hạn khi Redis sập lâu. Consumer `hold.expired` lấy cùng seat lock (`registration.md` §3); seat lock fail-closed (INV-08). Redis sập lâu hơn retry chain (5s + 30s + 5m, `messaging.md` §4) → message vào `unihub.parking-lot`, ghế **không bao giờ** được trả nếu không ai can thiệp. `resilience-testing.md` §2.3 đã ghi TODO này ("INV-07 may need a stated outage bound"). — **(2026-10-26) Đã ghi vào tài liệu sở hữu:** `messaging.md` §4 (bảng ngoại lệ), `registration.md` §3, §6, `resilience-testing.md` §2.3, `observability.md` §4
  - (a) Giữ lock. Lỗi "lock không lấy được" của riêng consumer này là lỗi tạm thời **không bao giờ vào parking-lot**: retry tiếp ở tầng 5m cho tới khi thành công. Thêm counter (`unihub_seat_hold_expiry_lock_unavailable_total`), alert ở Phase 6.
  - (b) Giữ lock + chain hiện tại. Ghi rõ giới hạn của INV-07 ("trả ghế trong vòng 15 phút + thời gian Redis sập, tối đa ~5,5 phút sập; quá đó cần replay parking-lot thủ công"), kèm runbook replay.
  - (c) Consumer trả ghế **không** cần Redis lock khi Redis không với tới, chỉ dựa vào conditional UPDATE + `version`. Trả ghế không thể gây overbooking, nhưng phải đọc lại INV-08 ("registration writes are rejected") cho riêng thao tác trả ghế → sửa `architecture.md` §4.2 diễn giải. Không đề xuất.
  - **Đề xuất: (a).** Giữ nguyên văn cả INV-07 lẫn INV-08, không cần thao tác thủ công. Đây là **ngoại lệ có ghi chép, riêng cho queue này**, đối với quy tắc chung "lỗi tạm thời retry đúng 3 lần rồi vào parking-lot" (`messaging.md` §4, AC2.5 của Phase 2); không đổi mặc định của queue khác. **Không cần queue mới** (đã kiểm 2026-10-26): `RetryChainRecoverer` republish ở tầng ứng dụng và đã có header `x-exception-class`, nên chỉ cần quyết định theo (queue, kiểu exception) là gửi lại tầng `.retry.5m`. → `registration.md` §3, §6; `messaging.md` §4; `resilience-testing.md` §2.3 (gỡ TODO)
- [x] `[→ M-34]` **Q2 — ✅ Đã chốt 2026-10-26 (D20): (a), dùng chung client; còn phải kiểm chứng bằng test `pause`.** Redisson với kết nối treo (biến thể `pause`). `distributed-coordination.md` §2 và `registration.md` §2 chỉ bắt `RedisConnectionException`. Kết nối treo trả về `RedisTimeoutException` (sau `commandTimeout` 0,5s, `retryAttempts` 0, `RedisConfig`). Cần chốt: (a) adapter bắt mọi `org.redisson.client.RedisException` → seat lock ném `SeatLockUnavailableException`, job lock bỏ qua chu kỳ; (b) chỉ bắt connection + timeout. Và: lock dùng **chung** client (timeout 0,5s) với cache hay client riêng? **Đề xuất: (a), dùng chung client** (một client, `distributed-coordination.md` §1). Lý do: test khẳng định *kết quả*, không khẳng định kiểu exception (`resilience-testing.md` §2.3). Phải **xác minh trên Redisson 3.45.1 đã pin** bằng test `pause` thật trước khi tick. → `distributed-coordination.md` §2, §4; `registration.md` §2; `resilience-testing.md` §2.3 (gỡ TODO) — **(2026-10-26)** Quy tắc đã ghi vào `distributed-coordination.md` §2, §4, `registration.md` §2, `resilience-testing.md` §2.3; **đã tick 2026-10-26** sau khi `RedissonSeatLockIT` (task 1.2) xanh với cả biến thể stopped và paused trên Redisson 3.45.1 (client dựng bằng đúng `RedisConfig`: command timeout 0,5s, 0 retry): cả hai cho `SeatLockUnavailableException` trong < 4s (đo thực tế ≈ 0,5–1,1s). Phát hiện kèm theo: acquire bị timeout lúc Redis `pause` vẫn có thể được Redis thực thi khi `unpause`, để lại một lock mồ côi tới hết lease 5s; test phục hồi vì vậy chờ tới lease + 2s. Test `pause` ở mức hệ thống (M-34) vẫn thuộc task 2.1.
- [x] `[→ M-28, M-34]` **Q3 — ✅ Đã chốt 2026-10-26 (D21): (a) bắt buộc Redis; lock in-process chuyển sang code test.** Ứng dụng chạy khi không cấu hình Redis. Hiện tại không có `unihub.redis.address` thì không có Redis bean (cache tắt, app vẫn chạy). Với seat lock ở Phase 3: (a) **bắt buộc có Redis**: thiếu address → app không khởi động (fail fast lúc start); (b) thiếu address → tự lùi về `InProcessSeatLock`/`InProcessJobLock`. (b) nghĩa là cấu hình sai ở môi trường nhiều pod sẽ **im lặng chạy không phối hợp**, trái tinh thần INV-08. **Đề xuất: (a).** Chỉ *thiếu cấu hình* mới làm hỏng lúc start; Redis *không với tới* lúc chạy thì app vẫn khởi động bình thường (lazy init sẵn có trong `RedisConfig`) và từ chối đăng ký bằng `503`, đúng Readiness ADR (`observability.md` §5, `resilience-testing.md` §2.3 2b). Adapter in-process chuyển sang test fixtures (owner chọn 2026-10-26). `docker-compose.yml` local đã có Redis. Hệ quả đã kiểm: 41/44 context IT hiện chạy không có Redis, nên phải nối Redis singleton cho mọi IT và cô lập cache catalog giữa các test (D21). → `distributed-coordination.md` §1, `registration.md` §2, `application.yml` — **(2026-10-26) Đã ghi vào tài liệu sở hữu:** `distributed-coordination.md` §1, `registration.md` §2, §6, §7, `integration-testing.md` §2. Chú thích Redis trong `application.yml` đổi cùng code ở task 1.2 (đổi trước sẽ mô tả sai hành vi hiện tại)
- [x] `[→ M-32, M-35]` **Q4 — ✅ Đã chốt 2026-10-26 (D22): (a).** Harness "≥ 2 instance". M-32 (lock nhận diện xuyên instance) và M-35 (job đúng 1 instance) cần hai ứng dụng thật dùng chung Postgres/Redis/RabbitMQ. (a) Hai `ConfigurableApplicationContext` trong cùng JVM (`SpringApplicationBuilder`, cổng ngẫu nhiên, cùng singleton container); (b) hai process `bootJar` riêng (nặng hơn, CI chậm hơn, cần quản lý process). **Đề xuất: (a)**, với điều kiện hai context **không** chia sẻ bean/static state nào giữ lock. **Khẳng định phải nhắm vào lock, không chỉ kết quả:** "không bao giờ cả hai xác nhận ghế cuối" đúng ngay cả với `InProcessSeatLock`, vì DB check đã chặn, nên test chỉ đo kết quả sẽ pass mà lock không làm gì. Cách đo: một decorator test-only bọc port `SeatLock` (hoặc `JobLock`) trong mỗi context, ghi thời điểm acquire/release vào một cấu trúc dùng chung ở mức JVM, rồi khẳng định **không có hai khoảng nắm lock chồng nhau** (không cần hook vào `ReserveSeat`). Đối chứng: cùng harness với adapter in-process phải cho thấy khoảng chồng nhau. Nếu không thấy thì harness không tách instance. → `integration-testing.md` §1–§2 — **(2026-10-26) Đã ghi vào tài liệu sở hữu:** `integration-testing.md` §2 (multi-instance harness)
- [x] `[→ M-29]` **Q5 — ✅ Đã chốt 2026-10-26 (D23).** Seam "bỏ bước lấy lock" (M-29). Phải là **test-only**: một `SeatLock` no-op đăng ký qua `@TestConfiguration`/`@Primary` trong source set `integrationTest`, không bao giờ là property production. **Đề xuất:** như trên, kèm một rule ArchUnit (hoặc test) khẳng định không class nào ở `src/main` implement `SeatLock` ngoài `RedissonSeatLock` (`InProcessSeatLock` đã chuyển sang code test theo D21). → `test-design-techniques.md` §3.1 (ghi cách hiện thực) — **(2026-10-26) Đã ghi vào tài liệu sở hữu:** `test-design-techniques.md` §3.1
- [x] `[→ M-31]` **Q6 — ✅ Đã chốt 2026-10-26 (D24): (a) `READ COMMITTED`.** Isolation level của seat claim (REQ-2.6.3, `⚠` — chưa có test nào được lên kế hoạch).** Không tài liệu nào ghi isolation level. Thực tế: `READ COMMITTED` mặc định + quyết định bằng **một** conditional UPDATE nguyên tử (`… WHERE version = ? AND seats_taken < capacity`), không có read-then-write nào được tin. (a) Chốt `READ COMMITTED` là đủ vì lý do trên; test chứng minh lần đọc phân loại (sau 0 row) không làm sai kết quả và không có lost update; (b) nâng transaction seat claim lên `REPEATABLE READ`/`SERIALIZABLE` (thêm lỗi serialization phải retry). **Đề xuất: (a)**, ghi rõ lý do vào `database-schema.md` §3.1 và có test khẳng định isolation thực tế của transaction đó. → `database-schema.md` §3.1, `test-matrix.md` M-31 (gỡ `⚠`) — **(2026-10-26) Đã ghi vào tài liệu sở hữu:** `database-schema.md` §3.1. Gỡ `⚠` của M-31 ở `test-matrix.md` §4.2 khi test (4.4) tồn tại

### 0.2 Refund (M-76)

- [x] `[→ M-76]` **Q7 — ✅ Đã chốt 2026-10-26 (D25), đã đối chiếu tài liệu Stripe.** Call-site thứ ba gọi gateway: refund. `payment.md` ADR breaker ghi "Pending revision (Phase 3)". Cần chốt: (1) refund dùng **chung** breaker `paymentGateway` (đề xuất: có, đúng lý do của ADR: "the system" singular); (2) `TimeLimiter`/`Retry` của refund: đề xuất `TimeLimiter` 10s, **không** retry trong lời gọi, vì consumer đã có retry chain 3 tầng (tránh retry chồng retry); (3) breaker `OPEN` khi consumer chạy → lỗi tạm thời → retry chain; hết chain → parking-lot, payment vẫn `REFUND_REQUIRED`; (4) tín hiệu "refund kẹt": đề xuất gauge `unihub_payment_refund_required_oldest_age_seconds` + counter khi vào parking-lot, alert ở Phase 6; (5) refund toàn bộ số tiền, Stripe sandbox `Idempotency-Key: refund:{paymentId}`; (6) **ánh xạ kết quả refund** (tương đương D16 của charge): `Refund.status` của Stripe là `pending`, `requires_action`, `succeeded`, `failed`, `canceled` (đã đối chiếu 2026-10-26). Stripe API v1 **trả lại response đã lưu** khi gửi lại cùng idempotency key, và key có thể bị xóa sau ≥ 24 giờ, nên gọi lại "create refund" không bao giờ cho thấy refund tiến triển. Hiện webhook **chưa** xử lý event refund nào. Chốt: trước khi tạo, **liệt kê refund theo PaymentIntent**, có rồi thì dùng nó (không migration, không lưu `gateway_refund_id`); chỉ `succeeded` → `REFUNDED`; `pending`/`requires_action` → lỗi tạm thời, retry và kiểm lại bằng list/GET; `failed`/`canceled` → parking-lot + tín hiệu kẹt; lỗi *chưa chạm gateway* (breaker `OPEN`, lỗi kết nối) retry ở tầng 5m không giới hạn, cùng quy tắc với D19; không thêm refund webhook ở Phase 3. Audit refund **đã chốt** (D6 ràng buộc 1, `Actor.system("payment-refund")`), không mở lại. → `payment.md` ADR + §2, §3, §9, §10, §11; `observability.md` §4 — **(2026-10-26) Đã ghi vào tài liệu sở hữu:** `payment.md` ADR breaker, §2, §9, §10, §11, bảng audit; `observability.md` §4; `messaging.md` §4
- [x] `[→ M-76]` **Q8 — ✅ Đã chốt 2026-10-26 (D26): (a′), xem dưới.** Payment "thành công muộn" không có gì kích hoạt refund. *(Tìm thấy khi đối soát code, 2026-10-26.)* `PaymentResolution` (nhánh `LATE_SUCCESS`, webhook và reconciliation) chuyển `CANCELLED`/`FAILED → REFUND_REQUIRED` mà **không ghi outbox nào**. Refund ở `payment.md` §9 chỉ được gọi từ consumer `unihub.payment-refund.queue`, mà queue này chỉ nhận `confirmation.rejected` của Registration. Nếu không sửa, tiền của nhánh này nằm ở `REFUND_REQUIRED` mãi. — **(2026-10-26) Đã ghi vào tài liệu sở hữu:** `payment.md` §6, §7, §9, §10, §11; `messaging.md` §2, §4, §6 (queue `unihub.payment-refund-execution.queue`); `registration.md` §5, §7
  - (a) Transaction late-success ghi thêm một Deferred Job tự gửi cho chính module (`backend-coding-standards.md` §8.4), routing key mới, ví dụ `unihub.payment.refund.requested`, bind vào `unihub.payment-refund.queue` (hoặc queue riêng). Consumer refund xử lý cả hai loại message theo `paymentId`.
  - (b) Reconciliation sweep quét thêm các payment `REFUND_REQUIRED` cũ và refund chúng. Cách này cũng chữa được refund kẹt, nhưng mở rộng một sweep 15 phút thành đường refund chính.
  - **Ràng buộc của (a):** các tầng retry của `unihub.payment-refund.queue` dead-letter về bằng **key cố định** `confirmation.rejected` (Javadoc `Topology`), nên một message `refund.requested` sau khi đi qua retry sẽ quay lại mang routing key kia. Listener phải phân loại bằng header type/payload, **không bao giờ** bằng routing key, hoặc dùng queue riêng có retry chain riêng.
  - (a′) **Mọi** chuyển trạng thái sang `REFUND_REQUIRED` (cả `RequireRefundTransaction` lẫn late-success của `PaymentResolution`) ghi trong cùng transaction một Deferred Job `unihub.payment.refund.requested` vào **queue riêng có retry chain riêng**; chỉ consumer của queue đó gọi refund. `ConfirmationRejectedListener` dừng ở `SUCCEEDED → REFUND_REQUIRED` + outbox row. Không có bẫy routing key ở trên, và outbox xử lý trường hợp crash giữa commit và lời gọi gateway.
  - **Đề xuất ban đầu: (a). Chốt: (a′)** (gọn hơn, advisor đề xuất ở lần tư vấn quyết định). Sửa một quy tắc của D2 (Phase 2): listener rejected không còn phải gọi lại refund khi gặp `REFUND_REQUIRED`. Phương án (b) có thể là backstop sau này, không làm ở Phase 3. → `payment.md` §6, §7, §9; `messaging.md` §2, §6

### 0.3 Vòng đời giữ chỗ (M-33)

- [x] `[→ M-33]` **Q9 — ✅ Đã chốt 2026-10-26 (D27).** Consumer có kiểm `hold_expires_at` không, và độ trễ relay. `registration.md` §3 chỉ kiểm `status`; Javadoc `SeatHoldExpiryRequested` nói consumer đọc lại cả `hold_expires_at` (C4). TTL của delay queue tính từ lúc **relay publish**, không phải lúc giữ chỗ, nên hold thực tế = 15 phút + độ trễ relay. Trong khoảng đó `chargePayment` đã từ chối (`ChargePaymentSteps`: `!now.isBefore(holdExpiresAt)`), nên ghế bị giữ mà không ai trả tiền được. **Đề xuất:** consumer chỉ trả ghế khi `status = RESERVED` **và** `hold_expires_at <= now` (Clock của app). Nếu message tới sớm (lệch đồng hồ) thì ném lỗi tạm thời → tầng retry 5s. Độ trễ relay được chấp nhận là trade-off có ghi chép (bình thường ≤ vài giây, đã có `unihub_outbox_oldest_pending_age_seconds`). Không thêm polling (`architecture.md` §4.3). → `registration.md` §3, §6 — **(2026-10-26) Đã ghi vào tài liệu sở hữu:** `registration.md` §3, §6

### 0.4 Nợ hoãn và K6

- [x] `[§4.5 — M-09]` **Q10 — ✅ Chốt lại 2026-10-26 (D28): (a″) — thay cho (a) ban đầu.** Hai lần sync chồng lấn vỡ `UNIQUE (email)`. `RunNightlySyncIT#m09_concurrentRuns_dataConverges`: dữ liệu hội tụ nhưng lần chạy chậm hơn có thể `FAILED` với `PIPELINE_ERROR`, vì `ON CONFLICT` chỉ hấp thụ race trên `legacy_student_id`. §4.5 yêu cầu sửa `user.md` §8 trước rồi chọn cách sửa. (a) Bắt vi phạm unique trên `email` **theo từng row**, đưa vào error store (`DUPLICATE`) thay vì làm hỏng cả batch; (b) chấp nhận, ghi giới hạn (lease overrun là hiếm, đã có alert vận hành). **Đề xuất: (a)**, đúng tinh thần M-10 ("vài record xấu không làm dừng batch"). → `user.md` §8
  - **(2026-10-26) Đã ghi vào tài liệu sở hữu:** `user.md` §8 (mục Correction). Lịch sử: (a) được chốt rồi mở lại vì khi sửa `user.md` §8 phát hiện hai điều: (1) `legacy_sync_errors.reason_code` có CHECK chỉ cho `MALFORMED_ROW`, `INVALID_FIELD`, `DUPLICATE_IN_FILE` (`V1`), không có `DUPLICATE`, nên "cách ly vào error store" cần **migration mới**; (2) trong lần chạy chồng lấn, row vi phạm `UNIQUE (email)` là row **hợp lệ** mà lần chạy kia vừa ghi xong, nên cách ly nó sẽ báo lỗi sai về dữ liệu nguồn. Owner chốt (a″): bắt vi phạm unique trên `email` theo từng row và **chạy lại upsert của row đó một lần**; lần hai đi vào nhánh `ON CONFLICT (legacy_student_id)` và hội tụ. **Điều kiện tiên quyết:** sync commit 500 row mỗi transaction, và một vi phạm unique làm hỏng (abort) cả transaction đó trong PostgreSQL, nên muốn chạy lại một row thì mỗi row phải nằm trong một **SAVEPOINT** riêng (rollback về savepoint rồi chạy lại); không có savepoint thì không retry được. Nếu vẫn vi phạm (email đã thuộc một `legacy_student_id` khác trong DB, không phải race) thì đó là xung đột dữ liệu thật — **hôm nay trường hợp này cũng đã làm hỏng cả batch** (lỗ hổng có sẵn, ngoài phạm vi D28); xử lý nó cần reason code mới + migration, owner quyết riêng. 
- [x] `[§4.5 — M-11]` **Q11 — ✅ Đã chốt 2026-10-26 (D29): (b) hoãn sang Phase 8.** M-11 (sync không làm gián đoạn user đang dùng). `⚠`, không phải invariant; §4.5 hẹn "Phase 3, cùng các test đồng thời". (a) Viết một IT: chạy sync lớn đồng thời với đọc/ghi registration, khẳng định không request nào lỗi và độ trễ trong một ngưỡng đặt trước; (b) hoãn tiếp sang Phase 8, vì "không gián đoạn" là một khẳng định về độ trễ, đo đúng nhất dưới tải. **Đề xuất: (b)**, sửa dòng §4.5 với lý do mới. Owner có thể chọn (a) nếu muốn đóng sớm. → `test-matrix.md` §4.5 — **(2026-10-26) Đã ghi vào tài liệu sở hữu:** `test-matrix.md` §4.5 (dòng M-11 → Phase 8)
- [x] `[→ M-36]` **Q12 — ✅ Đã chốt 2026-10-26 (D30).** K6 smoke. Chưa có file K6 nào trong repo. Cần chốt: (1) vị trí script (đề xuất `tests/load/k6/` ở gốc repo, ngoài `apps/` vì không thuộc deployable nào; xác nhận với `deployment-and-cicd.md`); (2) số lần retry tối đa sau `503` (`performance-load.md` §2.2 TODO; đề xuất 10, tôn trọng `Retry-After`); (3) mint token offline với `permissions: ["REGISTRATION_CREATE"]` (xem C3) và secret đọc lúc chạy, không commit (INV-18); (4) biến thể paid: không làm ở Phase 3 (TODO của §2.2 giữ nguyên). → `performance-load.md` §2.2, §2.3 — **(2026-10-26) Đã ghi vào tài liệu sở hữu:** `performance-load.md` §2.1 (vị trí script), §2.2 (retry cap), §2.3 (permission)

---

## 0A. Decisions (ADR) — D19–D30 (Q1–Q12) + C1, C2, C5, C10

Chốt bởi Nguyễn Thành Tiến ngày 2026-10-26: chọn mọi đề xuất của Claude Code (sau tư vấn của advisor). Mỗi quyết định gồm: quyết định · lý do · ràng buộc · acceptance criteria (AC) · test cần có · tài liệu phải sửa. **Tài liệu sở hữu đã sửa ngày 2026-10-26 (D28 chốt lại theo (a″)). Đã có code cho D20 và D21 (Mục 1); các quyết định còn lại chưa.**

### D19 — Q1: Trả ghế khi Redis sập lâu: retry không giới hạn, riêng cho "không lấy được lock" `[→ M-33, M-34]`

- **Quyết định:** trên `unihub.seat-hold-expiry.queue`, lỗi `SeatLockUnavailableException` (tranh chấp hoặc Redis lỗi) được `RetryChainRecoverer` gửi lại tầng `.retry.5m` **không giới hạn**, không vào parking-lot. Mọi lỗi khác của queue này, và mọi queue khác, giữ quy tắc retry 3 lần rồi vào parking-lot.
- **Lý do:** giữ nguyên văn INV-07 (ghế luôn được trả) và INV-08 (không ghi khi không có phối hợp). Ghế được trả chậm nhất khoảng 5 phút sau khi Redis hồi phục, không cần thao tác thủ công. Loại (b) vì cần replay thủ công. Loại (c) vì diễn giải lại INV-08, trái lý do ở `registration.md` §3.
- **Ràng buộc:** (1) không thêm queue mới: quyết định theo (queue, kiểu exception) trong `RetryChainRecoverer`; (2) đây là ngoại lệ có ghi chép đối với `messaging.md` §4 / AC2.5 Phase 2; (3) counter `unihub_seat_hold_expiry_lock_unavailable_total`, alert ở Phase 6.
- **AC:** **AC19.1** Redis sập lâu hơn toàn bộ chain 3 tầng → message không vào parking-lot; Redis sống lại → registration `EXPIRED`, `seats_taken` giảm đúng 1. **AC19.2** Lỗi khác (ví dụ payload hỏng) trên cùng queue vẫn vào parking-lot sau 3 lần. **AC19.3** Counter tăng mỗi lần gặp lỗi lock.
- **Test:** `SeatHoldExpiryIT` (biến thể Redis sập kéo dài, retry delay rút ngắn trong test), `RetryChainRecovererTest` (theo queue + exception).
- **Tài liệu phải sửa:** `registration.md` §3, §6; `messaging.md` §4; `resilience-testing.md` §2.3 (gỡ TODO "INV-07 may need a stated outage bound"); `observability.md` §4.

### D20 — Q2: Mọi `RedisException` = không có lock; một client Redis dùng chung `[→ M-34]`

- **Quyết định:** `RedissonSeatLock` map mọi `org.redisson.client.RedisException` (kể cả `RedisTimeoutException` khi kết nối treo) thành `SeatLockUnavailableException`; `RedissonJobLock` trả empty (bỏ chu kỳ). Lock dùng chung `RedissonClient` với cache (timeout 0,5s, `retryAttempts` 0).
- **Lý do:** một client duy nhất (`distributed-coordination.md` §1); test khẳng định kết quả, không khẳng định kiểu exception (`resilience-testing.md` §2.3).
- **Ràng buộc:** phải **kiểm chứng trên Redisson 3.45.1 đã pin** bằng biến thể `pause` thật trước khi tick Q2.
- **AC:** **AC20.1** Redis stopped → `503` + `Retry-After`. **AC20.2** Redis paused → `503` + `Retry-After` trong thời gian chờ có giới hạn (≤ wait 2s + timeout), không treo request. **AC20.3** Job lock trong cả hai biến thể → bỏ chu kỳ, thân job không chạy.
- **Test:** `RedisOutageIT` (stopped, paused), `RedissonSeatLockIT`.
- **Tài liệu phải sửa:** `distributed-coordination.md` §2 (mẫu code, C8), §4; `registration.md` §2; `resilience-testing.md` §2.3 (gỡ TODO Redisson).

### D21 — Q3: Bắt buộc cấu hình Redis; lock in-process chỉ còn trong code test `[→ M-28, M-34]`

- **Quyết định:** thiếu `unihub.redis.address` → app **không khởi động**. Redis không với tới lúc chạy → app vẫn khởi động (lazy init sẵn có) và đăng ký trả `503`. `InProcessSeatLock`/`InProcessJobLock` chuyển sang test fixtures, không còn trong `src/main`.
- **Lý do:** cấu hình sai ở môi trường nhiều pod không được phép lặng lẽ chạy không phối hợp (INV-08). Readiness không phụ thuộc Redis (`observability.md` §5).
- **Ràng buộc:** (1) nối Redis singleton của `IntegrationTestSupport` cho mọi context IT (hiện 41/44 context chạy không có Redis); (2) cô lập cache catalog giữa các test (dọn Redis giữa test, hoặc cờ tắt cache riêng cho IT), để test ghi DB trực tiếp không đọc trúng cache cũ; (3) `WorkshopCacheRedisDownIT` vẫn chạy được với địa chỉ chết (`redis://127.0.0.1:1`) — đó là "không với tới", không phải "thiếu cấu hình".
- **AC:** **AC21.1** Khởi động không có address → thất bại với thông điệp rõ ràng. **AC21.2** Address trỏ tới Redis đã chết → app khởi động, readiness vẫn `UP` (Redis nằm ngoài Readiness, `observability.md` §5), đăng ký `503`. **AC21.3** Không class nào trong `src/main` implement `SeatLock`/`JobLock` ngoài adapter Redisson (ArchUnit).
- **Test:** `RedisRequiredStartupIT`, rule ArchUnit mới, toàn bộ IT hiện có vẫn xanh.
- **Tài liệu phải sửa:** `distributed-coordination.md` §1; `registration.md` §2, §7; `application.yml` (chú thích Redis); `integration-testing.md` §1–§2; `test-matrix.md` §4.5 (dòng M-09, M-28/M-32/M-34).

### D22 — Q4: Harness 2 instance = 2 Spring context cùng JVM, đo trực tiếp lock `[→ M-32, M-35]`

- **Quyết định:** hai `ConfigurableApplicationContext` (`SpringApplicationBuilder`, cổng ngẫu nhiên) dùng chung container Postgres/Redis/RabbitMQ; **mỗi context tự tạo `RedissonClient` riêng**. Một decorator test-only quanh `SeatLock`/`JobLock` ghi các khoảng acquire→release vào cấu trúc dùng chung ở mức JVM.
- **Lý do:** đo kết quả (không ai xác nhận ghế cuối hai lần) không chứng minh gì về lock, vì DB check đã đảm bảo kết quả. Quyền sở hữu lock của Redisson = client id + thread.
- **Ràng buộc:** không chia sẻ bean/static state nào giữ lock giữa hai context; có đối chứng bằng lock in-process.
- **AC:** **AC22.1** Với adapter Redisson: không có hai khoảng nắm lock chồng nhau (cùng key). **AC22.2** Đối chứng in-process: phải thấy khoảng chồng nhau; không thấy → harness hỏng.
- **Test:** `MultiInstanceHarness` (support), dùng bởi `SeatLockMultiInstanceIT` (M-32) và `JobSingleInstanceIT` (M-35).
- **Tài liệu phải sửa:** `integration-testing.md` §1–§2.

### D23 — Q5: Seam "bỏ lock" chỉ có trong test `[→ M-29]`

- **Quyết định:** `SeatLock` no-op đăng ký qua `@TestConfiguration`/`@Primary` trong source set `integrationTest`. Không có property production nào tắt lock.
- **Lý do:** cờ production bỏ lock làm yếu INV-08.
- **AC:** **AC23.1** M-29 chạy với lock không bao giờ được gọi (decorator đếm 0 lần acquire) và Redis vẫn sống. **AC23.2** AC21.3 giữ nguyên.
- **Test:** `RegistrationConcurrencyIT` (biến thể lock-disabled).
- **Tài liệu phải sửa:** `test-design-techniques.md` §3.1 (ghi cách hiện thực).

### D24 — Q6: Seat claim ở `READ COMMITTED`, có lý do ghi lại `[→ M-31]`

- **Quyết định:** giữ `READ COMMITTED`. Quyết định nhận/từ chối là **một** conditional UPDATE nguyên tử (`… WHERE version = ? AND seats_taken < capacity`); lần đọc sau 0 row chỉ để phân loại (sold-out hay retry), không bao giờ quyết định ghi.
- **Lý do:** đáp ứng REQ-2.6.3 mà không thêm lỗi serialization phải retry.
- **AC:** **AC24.1** Isolation thực tế bên trong transaction seat claim là `read committed` (đọc `current_setting('transaction_isolation')` trong transaction). **AC24.2** Đọc phân loại xen kẽ với một ghi đồng thời không làm sai kết quả (không lost update, không overbooking).
- **Test:** `SeatClaimIsolationIT`.
- **Tài liệu phải sửa:** `database-schema.md` §3.1; `test-matrix.md` M-31 (gỡ `⚠`, §4.2).

### D25 — Q7: Refund qua gateway `[→ M-76]`

- **Quyết định:** (1) dùng chung breaker `paymentGateway`, sửa ADR breaker (call-site thứ ba); (2) `TimeLimiter` 10s, không retry trong lời gọi; (3) refund toàn bộ số tiền, Stripe sandbox, idempotency key `refund:{paymentId}`; (4) **trước khi tạo, liệt kê refund theo PaymentIntent**, đã có thì dùng; (5) `succeeded` → `REFUNDED`; `pending`/`requires_action` → lỗi tạm thời, retry và kiểm lại bằng list/GET; `failed`/`canceled` → parking-lot; (6) lỗi chưa chạm gateway (breaker `OPEN`, lỗi kết nối) retry tầng 5m không giới hạn (cùng quy tắc D19); (7) tín hiệu kẹt: gauge `unihub_payment_refund_required_oldest_age_seconds` + counter khi vào parking-lot, alert ở Phase 6; (8) audit mọi transition với `Actor.system("payment-refund")` (D6).
- **Lý do:** Stripe API v1 trả lại response đã lưu khi gửi lại cùng key và có thể xóa key sau ≥ 24 giờ, nên key chỉ chống tạo đôi trong cửa sổ ngắn; kiểm refund có sẵn là chống trùng lâu dài (Stripe cũng không cho refund quá số tiền đã thu). Không cần migration.
- **Ràng buộc:** không thêm refund webhook ở Phase 3; không lưu `gateway_refund_id`.
- **AC:** **AC25.1** Refund thành công → `REFUNDED`, đúng một refund trên gateway. **AC25.2** Xử lý lại khi đã có refund trên gateway → không tạo refund thứ hai. **AC25.3** `pending` → giữ `REFUND_REQUIRED`, lần sau thấy `succeeded` → `REFUNDED`. **AC25.4** `failed`/`canceled` → parking-lot, gauge tăng. **AC25.5** Breaker `OPEN` → không gọi gateway, message retry, payment giữ `REFUND_REQUIRED`. **AC25.6** `REFUNDED` là no-op.
- **Test:** `PaymentStatusTransitionTest`, `RefundIT` (các tên ở Phase 2 Mục 0C + AC25.2–AC25.5), `StripePaymentGatewayRefundTest`.
- **Tài liệu phải sửa:** `payment.md` ADR breaker, §2, §3, §9, §10, §11; `observability.md` §4.

### D26 — Q8: Mọi `REFUND_REQUIRED` phát `refund.requested` vào queue riêng `[→ M-76]`

- **Quyết định (a′):** mọi chuyển trạng thái sang `REFUND_REQUIRED` (`RequireRefundTransaction` và late-success của `PaymentResolution`) ghi trong cùng transaction một Deferred Job nội bộ của Payment (`backend-coding-standards.md` §8.4), routing key `unihub.payment.refund.requested`, vào một **queue riêng có retry chain riêng** (tên chốt khi sửa `messaging.md` §2). Chỉ consumer của queue này gọi refund (D25). `ConfirmationRejectedListener` dừng ở `SUCCEEDED → REFUND_REQUIRED` + outbox row.
- **Lý do:** sửa lỗ hổng late-success (C7); refund chỉ có một đường vào; không phải phân loại message theo routing key; outbox nguyên tử với chuyển trạng thái nên crash giữa commit và gọi gateway được outbox tự xử lý.
- **Ràng buộc:** **sửa quy tắc của D2** (Phase 2, ràng buộc 3): "xử lý lại mà gặp `REFUND_REQUIRED` thì phải gọi refund tiếp" chuyển từ listener rejected sang consumer refund; listener rejected gặp `REFUND_REQUIRED` là no-op. Queue mới khai báo trong `Topology`.
- **AC:** **AC26.1** Late success → `REFUND_REQUIRED` + đúng một outbox `refund.requested` trong cùng transaction. **AC26.2** `confirmation.rejected` → `REFUND_REQUIRED` + đúng một `refund.requested`; redeliver nhiều lần → vẫn một transition, một outbox row. **AC26.3** Rollback → không có cả hai.
- **Test:** `RequireRefundIT`, `PaymentWebhookResolutionIT` (late success), `TopologyTest`.
- **Tài liệu phải sửa:** `payment.md` §6, §7, §9; `messaging.md` §2, §4, §6; `registration.md` §5 (dòng compensation).

### D27 — Q9: Chỉ trả ghế khi đã quá `hold_expires_at` `[→ M-33]`

- **Quyết định:** consumer trả ghế khi `status = RESERVED` **và** `hold_expires_at <= now` (Clock của app). Message tới sớm (lệch đồng hồ) → lỗi tạm thời → tầng retry 5s. Độ trễ relay (hold thực tế = 15 phút + độ trễ publish) là trade-off có ghi chép, không thêm polling.
- **AC:** **AC27.1** Message tới khi chưa hết hạn → không đổi gì, được retry. **AC27.2** Đã hết hạn → `EXPIRED`. **AC27.3** Đã `CONFIRMED`/`EXPIRED`/`CANCELLED` → no-op.
- **Test:** `ExpireSeatHoldTest`, `SeatHoldExpiryIT`.
- **Tài liệu phải sửa:** `registration.md` §3, §6 (C4).

### D28 — Q10: Sync chồng lấn: thử lại đúng row bị trùng email, trong một SAVEPOINT `[§4.5 — M-09]`

- **Quyết định (a″, chốt lại 2026-10-26, thay cho (a)):** mỗi row hợp lệ được upsert trong một SAVEPOINT riêng. Vi phạm unique trên index `email` → rollback về savepoint (chỉ hủy row đó) → chạy lại upsert của row đó **đúng một lần**. Lần thử lại đi vào nhánh `ON CONFLICT (legacy_student_id)` và hội tụ.
- **Lý do:** khi hai lần sync chạy chồng nhau, row bị lỗi là row hợp lệ mà lần chạy kia vừa ghi. Cách ly nó (phương án (a)) sẽ báo sai về dữ liệu nguồn và cần reason code mới (migration, vì CHECK của `legacy_sync_errors.reason_code` chỉ cho `MALFORMED_ROW`, `INVALID_FIELD`, `DUPLICATE_IN_FILE`). Không có savepoint thì một lỗi làm hỏng cả transaction 500 row, không còn gì để thử lại.
- **Ràng buộc:** (1) chỉ thử lại một lần, không lặp; (2) chỉ áp dụng cho vi phạm index `email`; (3) thử lại vẫn bị từ chối = xung đột dữ liệu thật (email thuộc `legacy_student_id` khác trong DB). Trường hợp này hôm nay đã làm hỏng cả lần chạy, là lỗ hổng có sẵn **ngoài phạm vi D28**, cần quyết định riêng (reason code mới + migration).
- **AC:** **AC28.1** Hai lần sync chồng lấn trên cùng file có sinh viên mới → cả hai `COMPLETED`, không `PIPELINE_ERROR`, dữ liệu hội tụ, không có row nào vào `legacy_sync_errors`. **AC28.2** Row lỗi được thử lại đúng một lần; 499 row còn lại của lô vẫn commit.
- **Test:** `RunNightlySyncIT#m09_concurrentRuns_dataConverges` (siết lại: khẳng định cả hai lần chạy `COMPLETED`), unit test cho nhánh savepoint/thử lại.
- **Tài liệu đã sửa:** `user.md` §8 (2026-10-26). Còn lại: `test-matrix.md` §4.5 (gỡ dòng M-09 "two overlapping runs" khi test xanh, task 2.3).

### D29 — Q11: Hoãn M-11 sang Phase 8 `[§4.5 — M-11]`

- **Quyết định:** không viết test M-11 ở Phase 3. "Không gián đoạn user" là khẳng định về độ trễ, đo đúng nhất dưới tải ở Phase 8.
- **Tài liệu phải sửa:** `test-matrix.md` §4.5 (dòng M-11: lý do mới, "Revisit when" = Phase 8).

### D30 — Q12: K6 smoke `[→ M-36]`

- **Quyết định:** script ở `tests/load/k6/` (gốc repo); retry tối đa 10 lần sau `503`, theo `Retry-After`; token mint offline với `permissions: ["REGISTRATION_CREATE"]`, secret đọc lúc chạy, không commit; chỉ biến thể free; chạy một lần ở local, không vào CI.
- **Tài liệu phải sửa:** `performance-load.md` §2.2 (gỡ TODO retry cap), §2.3 (C3).

### Đề xuất sửa tài liệu được duyệt cùng (chi tiết ở Mục 0B)

- **C1** — M-35 dùng một job thật; bỏ câu "test trả ghế chứng minh INV-09" ở `test-plan.md` §1.
- **C2** — Độ dài giữ chỗ một nguồn: `Topology` lấy TTL delay queue từ `unihub.registration.seat-hold`; test dùng TTL ngắn.
- **C5** — Trả ghế thua race `version` → tối đa 3 transaction trong lock, hết thì lỗi tạm thời; không bao giờ trả ghế mà không chuyển `EXPIRED` trong cùng transaction.
- **C10** — INV-09 hiểu là "không chạy chồng nhau"; không đổi trigger sang cron.

---

## 0B. Đối soát chéo — mâu thuẫn tìm thấy (2026-10-26)

Mỗi mục sửa ở tài liệu được ghi, trước khi code phụ thuộc vào nó. Tick khi đã sửa.

- [x] **C1** *(duyệt 2026-10-26)* — `test-plan.md` §1 dòng Phase 3: "seat-hold release test … doubling as the first proof that a scheduled job runs on exactly one instance (INV-09)". Sai bản chất: trả ghế là **consumer DLX/TTL**, không phải scheduled job (`architecture.md` §4.3 đã loại polling), nên không chứng minh được INV-09. M-35 phải dùng một job thật (đề xuất: reconciliation sweep hoặc idempotency cleanup) trên `RedissonJobLock` qua hai instance, và đếm **số lần thân job thực thi** (decorator quanh `JobLock` hoặc quanh thân scheduler, chính là "side-effect counter on the job" của `resilience-testing.md` §2.3). Không đếm hiệu ứng dữ liệu: mọi job được thiết kế idempotent dưới lần chạy trùng (`distributed-coordination.md` §2), nên lần dọn thứ hai xóa 0 row và không lộ ra lần chạy đôi. Sửa câu trong `test-plan.md` §1 (Deliverable, giữ nguyên Exit criteria) và `test-strategy.md` nếu có câu tương ứng. — **(2026-10-26) Đã sửa:** sửa `test-plan.md` §1 (dòng Phase 3) và `test-strategy.md` §2 (INV-09)
- [x] **C2** *(duyệt 2026-10-26)* — `test-plan.md` §1: "via injectable `Clock`/shortened TTL". 15 phút giữ chỗ là `x-message-ttl` phía broker (`Topology.HOLD_WINDOW`, hằng `static`), Clock không có tác dụng gì ở đó. Thêm nữa, **hai nguồn** cho cùng con số 15 phút: `unihub.registration.seat-hold: PT15M` (tính `hold_expires_at`) và `Topology.HOLD_WINDOW` (TTL queue). Đề xuất: `Topology` nhận độ dài hold từ cùng property, để test dùng TTL ngắn trên broker riêng (như `retryChain(…, delays)` đã nhận `delays`). Ghi ràng buộc: đổi giá trị ở môi trường có broker cũ = khai báo queue mới. — **(2026-10-26) Đã sửa:** sửa `test-plan.md` §1, `registration.md` §3, `messaging.md` §6; code `Topology` đổi ở task 3.1
- [x] **C3** — `performance-load.md` §2.3 ghi "OPEN … Student role carries *no* permissions … `createRegistration` needs none". Đã lỗi thời: `V4` seed `REGISTRATION_CREATE` cho Student và endpoint yêu cầu nó (Phase 2 D7). Sửa đoạn này và gỡ "TODO: verify the required column set" sau khi đối chiếu DDL `V1`. — **(2026-10-26) Đã sửa:** sửa `performance-load.md` §2.3 (kèm gỡ TODO cột bắt buộc, đã đối chiếu `V1`)
- [x] **C4** — `registration.md` §3 (pseudocode consumer chỉ kiểm `status`) ↔ Javadoc `SeatHoldExpiryRequested` ("re-reads `status` and `hold_expires_at`"). Sửa theo kết quả Q9. — **(2026-10-26) Đã sửa:** sửa `registration.md` §3 theo D27
- [x] **C5** *(duyệt 2026-10-26)* — `registration.md` §3: câu trả ghế `UPDATE workshops SET seats_taken = seats_taken - 1 … WHERE version = :expected` có thể **thua race version** với organizer edit (edit không nằm trong seat lock). Không có quy tắc retry nào cho nhánh này (D3 chỉ quy định retry 3 lần cho seat claim). Đề xuất: cùng quy tắc như claim (tối đa 3 transaction trong lock), hết thì ném lỗi tạm thời → retry chain; **không bao giờ** trả ghế mà không chuyển registration sang `EXPIRED` trong cùng transaction. — **(2026-10-26) Đã sửa:** sửa `registration.md` §3, `database-schema.md` §3.1
- [x] **C6** — `test-matrix.md` §4.5 (dòng M-28/M-32/M-34) nói `RedissonSeatLock` "passes the same contract tests", nhưng chỉ có `InProcessSeatLockTest`, **không có `SeatLockContractTest`** (khác với `JobLockContractTest` đã có). Task 1.1 tách contract test ra trước. — **(2026-10-26) Đã sửa:** `SeatLockContractTest` (abstract, `src/test`), `InProcessSeatLockTest` kế thừa; adapter Redisson kế thừa ở task 1.2
- [x] **C7** — `payment.md` §9 + `PaymentResolution`: nhánh late-success tới `REFUND_REQUIRED` không có đường kích hoạt refund. Chi tiết ở Q8; chốt theo D26 (a′). — **(2026-10-26) Đã sửa:** sửa theo D26 ở `payment.md` §6, §7, §9
- [x] **C8** — `distributed-coordination.md` §2 (mẫu code job lock) chỉ bắt `RedisConnectionException`, cùng lỗ hổng với Q2. Sửa cùng lúc với Q2. — **(2026-10-26) Đã sửa:** sửa `distributed-coordination.md` §2 (mẫu code: `RedisException`, kiểm reentrancy)
- [x] **C9** — `resilience-testing.md` §2.3 yêu cầu M-34 khẳng định thêm (1) probe check-in sync và reporting không bị ảnh hưởng, (2) rate limiter fail open. Cả hai **chưa tồn tại ở Phase 3**: Check-in là Phase 5, rate limiter là Phase 7 (INV-17). Ghi hai khẳng định này là N/A ở Phase 3 kèm phase sẽ thêm chúng (sửa `resilience-testing.md` §2.3 và/hoặc thêm vào `test-matrix.md` §4.5), **trước** sign-off. Không chặn exit criterion của INV-08 ("writes are rejected, never uncoordinated"), nhưng để im sẽ lặp lại đúng bẫy B1 của Phase 2. — **(2026-10-26) Đã sửa:** sửa `resilience-testing.md` §2.3
- [x] **C10** *(duyệt 2026-10-26)* — INV-09 có hai cách diễn đạt: `architecture.md` §4.6 nói "exactly one pod per **cycle**", `test-matrix.md` M-35 nói "exactly one instance **at a time**". Hai câu chỉ trùng nhau khi trigger căn theo cron hoặc lease giữ suốt chu kỳ. Hiện 4/5 job dùng `fixedDelay` (relay, reconciliation, hai cleanup), lock nhả ở `finally`, nên "một lần mỗi chu kỳ" không định nghĩa được. Đề xuất: M-35 khẳng định "không chạy chồng nhau" (đúng chữ của matrix), sửa `architecture.md` §4.6/`distributed-coordination.md` §2 cho thống nhất. Không đổi trigger sang cron. — **(2026-10-26) Đã sửa:** sửa `architecture.md` §4.6 (Consistency Guarantees), `distributed-coordination.md` §2

---

**Bản đồ chặn (dùng thống nhất ở mọi chỗ trong file):** Mục 1 ← Q2 (quy tắc D20 đã chốt; phần kiểm chứng làm **trong** task 1.2, không chặn việc bắt đầu Mục 1), Q3 · Mục 2 ← Q4, Q10, Q11 · Mục 3 ← Q1, Q7, Q8, Q9 · Mục 4 ← Q4, Q5, Q6 · Mục 5 ← Q12. Mục 0B sửa tài liệu trước khi code phụ thuộc vào nó.

## 1. Lớp phối hợp Redis (sau khi chốt Q2, Q3)

- [x] **1.1** `[→ M-28, M-32, M-34]` `[§4.5]` Tách `SeatLockContractTest` (abstract, theo khuôn `JobLockContractTest`) từ `InProcessSeatLockTest`: loại trừ nhau trên cùng workshop (AC4.1), workshop khác không chặn nhau, hết `waitTimeout` → `SeatLockUnavailableException` (AC4.2), handle đóng hai lần không lỗi, nhả sau rollback/exception (AC4.4). `InProcessSeatLockTest` kế thừa và vẫn xanh. — **(2026-10-26) Xong:** mọi khẳng định "đã nhả" đều chiếm lại ở thread khác (cả `ReentrantLock` lẫn `RLock` đều reentrant theo thread); `size()` và interrupt giữ riêng ở `InProcessSeatLockTest`.
- [x] **1.2** `[→ M-28, M-32, M-33, M-34]` `RedissonSeatLock` (`registration/infrastructure/out/lock`): key `unihub:lock:seat:{workshopId}`, lease 5s **không watchdog**, wait 2s (`registration.md` §2, `distributed-coordination.md` §5), nhả bằng `unlock` chỉ khi còn là holder (lease có thể đã hết, `registration.md` §2 Trade-off). Lỗi Redis → `SeatLockUnavailableException` theo Q2. Qua `SeatLockContractTest` trên container Redis thật. Wiring theo Q3. Lock vẫn bọc **ngoài** transaction (§19.2): `ReserveSeatLockOrderingIT` vẫn xanh với adapter mới. **Kiểm chứng D20 (đóng Q2):** `RedissonSeatLockIT` chạy trên **container Redis riêng** của class (không đụng singleton) hai biến thể: *stopped* (connection refused) và *paused* (Docker `pause`, kết nối treo). Cả hai phải ném `SeatLockUnavailableException` trong thời gian có giới hạn (≤ wait 2s + command timeout 0,5s, kèm biên an toàn), không treo thread. Khẳng định kết quả, không khẳng định kiểu exception gốc của Redisson. Xanh → tick Q2. — **(2026-10-26) Xong:** `RedissonSeatLock` (`@Component`, `unlock` chỉ bởi holder, `close()` không bao giờ ném), `RedissonSeatLockContractIT` (6, qua `SeatLockContractTest`), `RedissonSeatLockIT` (4: lease hết hạn, stopped, paused, close khi paused). D21 đã nối: `RedisProperties` bắt buộc `address` (blank → không khởi động), bỏ `NoopWorkshopCache`, `InProcessSeatLock`/`InProcessJobLock` chuyển sang `src/test`, `IntegrationTestSupport` nối Redis singleton + `FLUSHALL` trước mỗi test, rule ArchUnit AC21.3, `RedisRequiredStartupIT` (AC21.1, AC21.2 phần `503`; phần Readiness `UP` chưa kiểm được vì Backend chưa có actuator/readiness), `contract-diff.sh` mặc định `UNIHUB_REDIS_ADDRESS=redis://127.0.0.1:1`.
- [x] **1.3** `[→ M-35]` `[§4.5 — M-09]` `RedissonJobLock` (`shared/infrastructure/out/lock`): `tryLock(0, lease)`, lỗi Redis → empty (bỏ chu kỳ). **Bẫy reentrancy:** `RLock` của Redisson reentrant trên cùng thread, còn port `JobLock` yêu cầu *không* reentrant (holder gọi lại → empty), nên `RLock` thuần sẽ trượt `JobLockContractTest`. Phải kiểm `isHeldByCurrentThread()` trước, hoặc dùng primitive không reentrant (SET NX PX qua `RBucket#setIfAbsent`). Hai context trong harness Q4 phải mỗi bên tự tạo `RedissonClient` riêng, vì quyền sở hữu lock = client id + thread. Qua `JobLockContractTest` trên Redis container; thay `InProcessJobLock` cho cả 5 job (`distributed-coordination.md` §2). Lease mỗi job đúng bảng §5. — **(2026-10-26) Xong:** `RedissonJobLock` (`@Component`, kiểm `isHeldByCurrentThread()` trong `try`), `RedissonJobLockContractIT` (6, qua `JobLockContractTest`), `RedissonJobLockIT` (3: lease hết hạn, stopped, paused → bỏ chu kỳ). Key + lease của 5 scheduler đã đối chiếu với `distributed-coordination.md` §5: khớp. Harness 2 context (mỗi bên một `RedissonClient`) thuộc task 2.2.
- [ ] **1.4** `[— không dòng]` Sửa `test-matrix.md` §4.5: gỡ dòng M-09 "trigger takes the cross-instance job lock" và dòng M-28/M-32/M-34 `InProcessSeatLock` khi bằng chứng tương ứng (Mục 2, 4) đã `Passing` — không gỡ sớm hơn.

## 2. Fail-safe & một instance

- [ ] **2.1** **`[M-34]`** `RedisOutageIT` theo `resilience-testing.md` §2.3, trên **container Redis riêng** của class (không đụng singleton): biến thể *stopped* và *paused*. Khẳng định: `POST /api/v1/registrations` → `503` + `Retry-After`, **không** write nào tới Postgres (`seats_taken` không đổi, 0 `registrations`, 0 `idempotency_keys`), chạy dưới N luồng với workshop còn ghế; workshop list/detail vẫn `200`; một tick job bị bỏ qua (counter side-effect); webhook payment không bị ảnh hưởng; probe check-in/reporting và rate limiter fail-open là N/A ở Phase 3 (C9); **phục hồi**: bỏ pause/khởi động lại → đăng ký thành công trong thời gian chờ có giới hạn, không restart app. Khẳng định kết quả, không khẳng định kiểu exception.
- [ ] **2.2** **`[M-35]`** `JobSingleInstanceIT` với harness Q4: hai instance cùng chạy một job thật (theo C1) trên `RedissonJobLock`; khẳng định **không có hai lần thực thi thân job chồng nhau về thời gian** (cùng decorator ghi khoảng start→end như M-32, theo C1), không đếm số row bị dọn (job idempotent nên lần chạy đôi không để lại dấu vết dữ liệu). **Không** khẳng định "đúng một lần mỗi chu kỳ": 4/5 job dùng `fixedDelay` (chỉ nightly sync dùng `cron`), lock nhả ở `finally`, nên instance B có thể chạy hợp lệ ngay sau khi A nhả trong cùng một "chu kỳ" (xem C10). Đối chứng: cùng harness với `InProcessJobLock` phải cho thấy khoảng chồng nhau (chứng minh harness thật sự tách instance).
- [ ] **2.3** `[§4.5 — M-09]` Code theo D28 (`user.md` §8 đã sửa); `RunNightlySyncIT#m09_concurrentRuns_dataConverges` khẳng định **cả hai** lần chạy kết thúc `COMPLETED` (không còn `PIPELINE_ERROR` trên `UNIQUE (email)`). Gỡ dòng §4.5 tương ứng.
- [ ] **2.4** `[§4.5 — M-11]` Theo Q11: hoặc viết IT, hoặc sửa dòng §4.5 sang Phase 8 với lý do mới.

## 3. Vòng đời giữ chỗ & refund (sau khi chốt Q1, Q7, Q8, Q9 và sửa C2, C4, C5)

- [ ] **3.1** `[→ M-33]` Seam TTL theo C2: độ dài hold một nguồn duy nhất; test dựng topology với delay queue TTL ngắn. `TopologyTest` khẳng định TTL delay queue = property, `x-dead-letter-routing-key` = `hold.expired`.
- [ ] **3.2** **`[M-33]`** Use case `ExpireSeatHold` + `SeatHoldExpiredListener` trên `unihub.seat-hold-expiry.queue` (`registration.md` §3): lock → transaction ở Master → re-check (`status`, và `hold_expires_at` theo Q9) → `RESERVED → EXPIRED` bằng conditional UPDATE + kiểm tra affected rows → `seats_taken - 1` **cùng transaction** (C5) → nhả lock. Audit qua `AuditTrail` với `Actor.system("seat-hold-expiry")` (`backend-coding-standards.md` §27 #6). Lỗi "lock không lấy được" xử lý theo Q1. Unit test cho bảng trạng thái (`RESERVED`/`CONFIRMED`/`EXPIRED`/`CANCELLED` → hành động) trong `registration.application.usecase` (JaCoCo ≥ 80%).
- [ ] **3.3** **`[M-33]`** `SeatHoldExpiryIT` (end-to-end qua broker thật, TTL ngắn): đăng ký paid → hold → message hết hạn → `EXPIRED`, `seats_taken` giảm đúng 1, ghế mua lại được; **không chặn đường đăng ký** (một registration khác trên cùng workshop trong lúc expiry chạy vẫn thành công trong ngưỡng); redeliver `hold.expired` ≥ 2 lần → đúng một lần trả ghế; **sau mỗi lần redelivery và mỗi race, khẳng định `seats_taken` = số registration `RESERVED` + `CONFIRMED`** (Phase 3 là code đầu tiên *giảm* `seats_taken`; trả ghế đôi sẽ âm thầm tạo thêm chỗ, tức là thất bại INV-06. CHECK `seats_taken >= 0` đã có ở `V1` nhưng chỉ chặn khi về âm, không chặn trả đôi khi còn ghế); registration đã `CONFIRMED` → no-op; **race xác nhận ↔ hết hạn** (AC2.3 của Phase 2): đúng một bên thắng, kết cục `CONFIRMED` **hoặc** `EXPIRED` + nhánh bù trừ, `seats_taken` khớp số registration `RESERVED`/`CONFIRMED`. Hành vi khi Redis sập trong lúc expiry theo Q1.
- [ ] **3.4** `[→ M-76]` Port `PaymentGateway#refund(...)` + Stripe sandbox adapter với key `refund:{paymentId}`; decorators và ánh xạ kết quả theo D25 (liệt kê refund theo PaymentIntent trước khi tạo); sửa ADR breaker ở `payment.md` (call-site thứ ba) **cùng commit**. Trigger theo D26: mọi chuyển sang `REFUND_REQUIRED` ghi outbox `unihub.payment.refund.requested` vào queue riêng (khai báo ở `Topology` + retry chain, sửa `messaging.md` cùng commit); `ConfirmationRejectedListener` không gọi refund.
- [ ] **3.5** **`[M-76]`** Consumer refund: sau `SUCCEEDED → REFUND_REQUIRED` (đã có), gọi refund **ngoài** transaction, rồi `REFUND_REQUIRED → REFUNDED` bằng conditional UPDATE; audit mọi transition. Test (tên đề xuất ở Phase 2 Mục 0C; chưa class nào tồn tại): `PaymentStatusTransitionTest` (`REFUND_REQUIRED → REFUNDED`; `REFUNDED → *` bị từ chối), `RefundIT#refundRequired_callsGatewayWithDeterministicKey_thenRefunded`, `#reprocessWhileRefundRequired_callsRefundAgain`, `#refunded_isNoOp`, cộng một test cho trigger late-success (Q8), một test breaker `OPEN` → retry chain, payment giữ `REFUND_REQUIRED`, và **một IT end-to-end trên luồng thật qua queue thật** (Phase 3 là phase đầu tiên nhánh này thật sự xảy ra được): paid hold → charge `PENDING` → hold hết hạn (TTL ngắn) → gateway báo thành công muộn → `charge.succeeded` → `confirmation.rejected` → `REFUND_REQUIRED` → `REFUNDED`; registration giữ `EXPIRED`, ghế không bị cấp lại, gateway refund đúng một lần. Tick nốt 3 mục `[M-76 · P3]` còn mở của `phase-2-registration-payment.md` (Mục 0C: `PaymentStatusTransitionTest` `REFUND_REQUIRED → REFUNDED` và nhóm `RefundIT` AC2.4c; Mục 3.5: "Nhánh bù trừ, **phần Phase 3**").

## 4. Bằng chứng chống overbooking — INV-06 (sau Mục 1)

Ba biến thể đầu **là** bảng quyết định `test-design-techniques.md` §3.1, đọc cùng nhau. Mỗi test: workshop capacity C, `seats_taken = C − k`, N ≫ k luồng bắt đầu cùng lúc (latch), user khác nhau, retry `503` cùng `Idempotency-Key` tới khi có kết quả dứt khoát. Điều kiện pass là **SQL** (`seats_taken <= capacity`, số `registrations` sống = `seats_taken`), không chỉ là bộ đếm response.

- [ ] **4.1** **`[M-28]`** `RegistrationConcurrencyIT#acceptsExactlyRemainingSeats_whenConcurrentRequestsExceedCapacity` (tên đã có trong `test-matrix.md` §2.3): full stack (Redisson lock + version + CHECK) → đúng k thành công, còn lại `409` sau khi hội tụ; response đầu tiên được phân loại `{409, 503 + Retry-After}` (`test-design-techniques.md` §3.1). Thêm biến thể paid (`RESERVED`).
- [ ] **4.2** **`[M-29]`** Cùng kịch bản với seam Q5 (lock không bao giờ được gọi, Redis vẫn sống) → vẫn 0% overbooking; version check/`seats_taken < capacity` là thứ chặn.
- [ ] **4.3** **`[M-30]`** UPDATE thô đồng thời (JDBC, bỏ qua ứng dụng, `seats_taken = seats_taken + 1` không có điều kiện) → CHECK `seats_taken <= capacity` từ chối phần vượt; `seats_taken` không bao giờ > `capacity`.
- [ ] **4.4** **`[M-31]`** Theo Q6: test khẳng định isolation level thực tế của transaction seat claim và rằng kết quả không phụ thuộc vào lần đọc không lặp lại được. Gỡ `⚠` của M-31 ở `test-matrix.md` §4.2.
- [ ] **4.5** **`[M-32]`** Harness Q4, hai instance cùng tranh ghế cuối của một workshop qua `RedissonSeatLock` → không bao giờ cả hai cùng xác nhận ghế cuối, **và** lock thật sự loại trừ xuyên instance: decorator test-only quanh `SeatLock` ở mỗi context ghi khoảng acquire→release vào cấu trúc dùng chung của JVM, khẳng định không có hai khoảng chồng nhau trên cùng workshop (Q4). Chỉ đo kết quả là chưa đủ, vì DB check đã làm kết quả đúng ngay cả khi không có lock. Đối chứng `InProcessSeatLock`: phải thấy khoảng chồng nhau. Gỡ `⚠` của M-32.

## 5. K6 adversarial — smoke, không phải gate (sau Mục 4)

- [ ] **5.1** **`[M-36]`** Script `performance-load.md` §2.2 (500 VU × 1 iteration vs. 10 ghế cuối, `is_paid=false`), seed bằng SQL + token mint offline theo Q12; chạy **một lần** ở local (docker-compose + `bootRun`), sau đó SQL hậu kiểm của §4. Ghi kết quả vào `test-matrix.md` M-36 với ghi chú "smoke, not the gate". Không đưa vào CI.

## 6. Web Application

- [ ] **6.1** `[— không dòng]` Kiểm `HoldCountdown`, `CheckoutPage`, `PaymentStatusNotice`, `MyRegistrationsPage` với chuyển trạng thái **thật** của Phase 3: registration `RESERVED → EXPIRED` khi đang ở checkout (dừng đếm ngược, không cho trả tiền, thông điệp rõ ràng), payment `REFUND_REQUIRED → REFUNDED` hiển thị đúng. Thêm test Vitest/RTL cho mỗi chuyển trạng thái chưa có. Nếu cần thêm dòng matrix (dòng web mới như M-78), thêm theo skeleton `test-matrix.md` §2.3. Không có endpoint mới → không có thay đổi contract nào, trừ khi Q8 làm đổi mô tả trong `paths/payment.yaml`.

## 7. CI/CD

- [ ] **7.1** `integration-test` xanh với các IT mới (Redis riêng cho class fault-injection, harness hai instance). Đo thời gian job; nếu tăng mạnh thì ghi lại, **không** đánh dấu `@Disabled`/bỏ test INV nào để tiết kiệm thời gian (`test-plan.md` §2).
- [ ] **7.2** Không test concurrency nào bị flaky: chạy `RegistrationConcurrencyIT`, `SeatHoldExpiryIT`, `JobSingleInstanceIT` ≥ 10 lần liên tiếp ở local, ghi số lần vào evidence. Dòng nào `Flaky — investigate` thì chặn sign-off (U2). Lưu ý trước khi kết luận flaky: dưới tranh chấp cao (M-28, M-36), một loạt `503` có thể đến từ giới hạn subscription pool của Redisson khi chờ lock, không phải tranh chấp thật. Kiểm các giới hạn này trước.
- [ ] **7.3** `contract-diff` xanh (kể cả khi Q8 thêm mô tả); `web` job xanh.

## 8. Đồng bộ tài liệu & Test Matrix

- [ ] **8.1** Sau mỗi task ở Mục 1–6: cập nhật dòng tương ứng trong `test-matrix.md` §2.2 (Status, `Class#method` thật).
- [ ] **8.2** Cập nhật `test-matrix.md` §4.1, §4.2 (M-31, M-32), §4.5 (gỡ các dòng đã đóng, sửa dòng M-11 nếu hoãn tiếp) và §5 trước khi sign-off; đếm lại từ cột Status.
- [ ] **8.3** Đánh dấu các TODO đã giải quyết trong `resilience-testing.md` §2.3 (Redisson hung connection; hold-expiry khi Redis sập lâu) và `performance-load.md` §2.2/§2.3.
- [ ] **8.4** Cập nhật "Project Status" trong `CLAUDE.md` (Phase 3 xong, các adapter Redisson, consumer hold-expiry, refund).

## 9. Đóng Phase 3 (Exit / Sign-off)

Exit criteria, chép nguyên văn từ `test-plan.md` §1 dòng Phase 3 (4 khẳng định → 4 hàng ở `phase-3.md` §3):

1. "Local (non-K8s) Testcontainers tests demonstrate 0% overbooking through each protection layer independently — full stack, lock-disabled, and DB-only (INV-06) — before this is trusted at full-topology load (Phase 8)" → M-28, M-29, M-30 (+ M-31, M-32)
2. "Redis-outage test proves writes are rejected, never uncoordinated (INV-08)" → M-34
3. "seat-hold release test proves automatic release with no blocked request (INV-07)" → M-33
4. "one-instance test proves no duplicate job execution across parallel instances (INV-09)" → M-35 (theo C1, **không** dựa vào test trả ghế)

- [ ] Mọi dòng INV-06/07/08/09 = `Passing`; M-36 và M-76 `Passing` hoặc ghi ở §5 với lý do (M-36 là smoke, không phải gate)
- [ ] Coverage ≥ 80% (JaCoCo xanh), gồm use case mới (`ExpireSeatHold`, refund)
- [ ] CI xanh toàn bộ trên **cùng một commit SHA**
- [ ] Không `TODO`/`FIXME` trong test code; không `@Disabled`/`.skip` trên test của INV-06…INV-09
- [ ] Copy `phase-template.md` → `docs/testing/reports/release-sign-offs/phase-3.md`, điền §2–§6 với evidence thật; §5 mang sang các mục còn hoãn của `phase-2.md` §5 chưa đóng ở Phase 3 (ví dụ hàng 4, 5, 7–11)
- [ ] Owner ký §6 của `phase-3.md`

> Phase 3 chỉ hoàn tất khi `phase-3.md` tồn tại, mọi ô được tick với evidence thật, và §6 đã ký — **không phải khi code chạy được**.

---

## Ghi chú cho Claude Code

- Khi được yêu cầu "tiếp tục Phase 3": đọc file này trước, làm theo thứ tự Mục 0 → 0B → 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9.
- **Không bắt đầu một Mục khi các Q chặn nó (xem "Bản đồ chặn" trước Mục 1) chưa chốt.** Không tự chọn phương án "đề xuất" thay owner.
- Không tick mục nào trước khi test thật sự pass và `test-matrix.md` đã được cập nhật.
- Không thêm cron/polling cho hold expiry (`architecture.md` §4.3), không thêm cờ production tắt seat lock, không bỏ CHECK constraint hay version check như "thừa".

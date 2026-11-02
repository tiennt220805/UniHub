# Phase 6 — Observability: Checklist Triển Khai

> **Mục đích file này:** checklist *thực thi* cho Phase 6, cùng vai trò với `phase-1-foundation.md` … `phase-5-offline-checkin.md`. Nó không thay thế `docs/testing/strategy/test-plan.md` §1 (nguồn thẩm quyền cho exit criteria), `docs/testing/reports/test-matrix.md` (trạng thái từng test) hay `docs/testing/reports/release-sign-offs/phase-template.md` (sign-off). File này chỉ **tổng hợp và sắp thứ tự công việc**, trỏ vào 3 file đó làm bằng chứng. Nơi có mâu thuẫn, 3 file kia luôn đúng.
>
> **Cách dùng:** tick `[x]` khi xong thật, không tick trước. Mỗi mục nên có commit tương ứng. Cập nhật **Trạng thái tổng thể** sau mỗi buổi làm việc.

| Trường | Giá trị |
|---|---|
| Phase | 6 — Observability |
| Ngày bắt đầu | 2026-11-02 (checklist soạn cùng ngày, sau khi ký `phase-5.md`) |
| Trạng thái tổng thể | `☑ Chưa bắt đầu` / `☐ Đang làm` / `☐ Chờ sign-off` / `☐ Hoàn tất` — **Mục 0 chốt 2026-11-02 (Q1–Q18 → D63–D80; owner giao chốt thay, rồi đồng ý cho commit và sửa tài liệu); tài liệu sở hữu đã sửa và Mục 0B (C34–C44) đã đóng cùng ngày, trừ phần contract của Q3/C35 đi cùng code ở task 1.3; chưa có dòng code nào — việc tiếp theo là Mục 1** |
| Nhánh làm việc | `main` (push thẳng, không PR — `test-matrix.md` §4.4) |
| INV trong scope | INV-14 (toàn bộ: fail-open **và** "lỗi publish phải hiện thành cảnh báo vận hành"). Gián tiếp: INV-10/INV-11 (thêm consumer thứ tám, relay mang `correlationId`), INV-03 (thêm lớp JDBC observation quanh datasource định tuyến) — chỉ chạy lại, không chứng minh mới |
| Matrix rows | M-53 … M-57 (`test-matrix.md` §4.1: "6 — M-53 … M-57 (5 rows)"); dòng `⚠` (§4.2): M-54, M-56, M-57. Cộng các mục hoãn có "Revisit when" = Phase 6 ở `test-matrix.md` §4.5: M-17 (`correlationId` trong problem body), alert parking-lot và reconciliation-stuck, `RabbitAuditTrail` + consumer `audit_events` |
| Tham chiếu | `requirement.md` §2.11 (audit), §3.1 · `proposal.md` §15 (Phase 6) · `test-plan.md` §1 (dòng Phase 6, bảng P1–P4), §2 · `test-strategy.md` §2 (INV-14) · `architecture.md` §1.2, §5.5 · `observability.md` (toàn bộ, 348 dòng: ADR, §1–§7) · `messaging.md` §2, §3, §4 · `database-schema.md` §8.2, §8.3 · `deployment-and-cicd.md` ADR Mesh mTLS (c), §2 · `resilience-testing.md` §2.7 · `backend-coding-standards.md` §12, §13, §17, §27 · `web-coding-standards.md` (correlationId trên trạng thái lỗi) · `IMPLEMENTATION-READINESS.md` B3 · `packages/api-contract/common/headers.yaml`, `common/error.yaml` · `phase-5.md` §5 (hàng 9, 10) · `phase-4.md` §5 (hàng 6) |

### Ký hiệu nhãn

Giữ nguyên quy ước của các phase trước: **`[M-55]`** đậm = task là bằng chứng của dòng; `[→ M-55]` = tiền đề; `[M-64 · P7]` = dòng của phase khác, chỉ chuẩn bị; `[§4.5]` = đóng một mục hoãn ở `test-matrix.md` §4.5; `[— không dòng]` = không có dòng matrix (ghi lý do).

**Bảng tra ngược (dòng → task):** *(dự kiến — sửa lại sau khi chốt Mục 0)*

| Dòng | INV | Task chứng minh | Task tiền đề |
|---|---|---|---|
| M-53 | — | 3.6 | Q3, Q4, Q5 · 1.1–1.4, 3.1–3.5 |
| M-54 | — | 2.6 | Q6, Q7, Q8, Q9 · 2.1–2.5 |
| M-55 | INV-14 | 2.7, 6.6 | Q6, Q10, Q15 · 2.1–2.5, 6.1 |
| M-56 | — | 5.5 | Q11, Q12, Q13 · 1.1, 5.1–5.4 |
| M-57 | — | 4.4, 6.4, 6.5 | Q1, Q2, Q14, Q16 · 1.x, 4.1–4.3, 6.2–6.3 |
| M-17 (§4.5) | — | 3.5 | 3.1–3.3 |
| Alert parking-lot / reconciliation-stuck (§4.5) | — | 6.1, 6.4 | Q15 |
| `RabbitAuditTrail` + consumer (§4.5) | INV-14 | 2.2–2.4 | Q6–Q8 |
| P1 (`test-plan.md` §1, cổng của Phase 7) | — | 6.1 (định nghĩa + rule) | Q15 |

---

## Ranh giới Phase 6 ↔ các phase khác (đọc trước khi làm)

Hiện trạng đã kiểm 2026-11-02:

- **Không có Actuator, không có registry Prometheus, không có tracing.** `build.gradle.kts` chỉ có `micrometer-core` (API) và `resilience4j-micrometer`; mọi meter từ Phase 2–5 ghi vào `Metrics.globalRegistry` và **chưa bao giờ được export** (`phase-4.md` §5 hàng 6).
- **Một nửa đường correlation đã có:** `OutboxEventPublisher` ghi `MDC.get("correlationId")` vào `event_publications.correlation_id` (`V1` dòng 250, `VARCHAR(32)`); `RabbitMessageBroker` đặt lại giá trị đó vào MDC trước khi relay publish; `CorrelationIdPostProcessor` chép MDC → header `correlationId`; `shared.infrastructure.in.messaging.CorrelationIdListenerAdvice` có ở phía consumer. **Thiếu:** không có gì đặt `correlationId` vào MDC ở phía HTTP (không filter, không trace ID), nên hiện mọi giá trị đều `NULL`. Không response nào có `X-Correlation-Id`; `GlobalProblemHandler` không điền `correlationId` (M-17 hoãn).
- **Audit:** port `AuditTrail` + adapter duy nhất `LoggingAuditTrail` (logger `unihub.audit`, `AsyncAppender` `neverBlock=true`, dòng bị bỏ **không được đếm** — `backend-coding-standards.md` §12). Bộ đếm `unihub.audit.publish.failures` có. `AuditActions` có workshop (create/publish/update/reschedule/cancel, brochure), registration (created/confirmed/expired), payment (6 trạng thái), check-in (`CHECKIN_SCANS_SYNCED`). **Không có use case đổi role/permission** nên hạng mục audit thứ năm (`architecture.md` §5.5) không có producer. `Topology` đã khai báo `unihub.audit.queue` + chuỗi retry (dòng 117) nhưng **không có listener**; `AuditPublishingAbsentTest` (ArchUnit) đang khóa "chỉ `LoggingAuditTrail` implement `AuditTrail`" — phải thay **trong cùng commit** với `RabbitAuditTrail`. Bảng `audit_events` có từ `V1` (không có cột nào nối row với message nguồn).
- **Listener hiện có (7):** summarization, seat-hold expiry, payment confirmation, notification (container factory riêng), confirmation rejected, reconciliation, refund requested. Audit sẽ là listener thứ tám → `ConsumerRedeliveryCoverageTest` đòi một mục.
- **Client bên thứ ba không phải loại Spring tự instrument:** Stripe đi qua `JdkStripeHttpClient` (`java.net.http`), AI qua `HttpSummarizationClient` (`java.net.http`), S3 qua `ApacheHttpClient` của AWS SDK. Không có `RestTemplate`/`RestClient`/`WebClient` nào → không span nào tự sinh cho "third-party API calls" mà SRS §3.1 nêu tên.
- **Metric trong catalog `observability.md` §4 chưa có code:** `unihub_registration_active_holds`, `unihub_outbox_oldest_pending_age_seconds`, `unihub_postgres_replica_lag_seconds`, `unihub_legacy_sync_last_run_quarantine_ratio`. **Ngược lại**, có meter trong code mà catalog không liệt kê: `unihub.notification.delivery`, `unihub.notification.pending.oldest.age`, `unihub.checkin.scan.clock.ahead`, `unihub.checkin.scan.resubmission.conflicts`.
- `docker-compose.yml`: Postgres (một instance, Master = Replica), Redis, RabbitMQ `3.13.7-management` (plugin `rabbitmq_prometheus` chưa bật), Mailpit, MinIO. Không có Prometheus/Grafana/Tempo. Không Dockerfile, không manifest K8s.

| Thuộc Phase 6 | Không làm ở đây |
|---|---|
| Correlation ID = trace ID: filter HTTP, header `X-Correlation-Id`, MDC, outbox → RabbitMQ → consumer → dòng log (M-53); `correlationId` trong mọi problem body (M-17) | Gắn tag ở Ingress (SRS §3.1 "at the Ingress"): không có Ingress trước Phase 7. Phase 6 chứng minh từ cổng ứng dụng vào trong |
| `RabbitAuditTrail` + consumer ghi `audit_events` (M-54); outage audit không chặn nghiệp vụ và lỗi publish hiện thành cảnh báo (M-55, INV-14) | Endpoint đọc `audit_events` / màn hình tra cứu audit: không tài liệu nào yêu cầu (nêu ở Q9) |
| Tracing: Micrometer Tracing + OTel bridge, OTLP → Tempo; span cho HTTP, JDBC, RabbitMQ, Stripe/AI/S3 (M-56) | OTel Collector / tail sampling (`observability.md` §2 Trade-off: cố ý không làm) |
| Actuator + `/actuator/prometheus`; metric golden-signal + catalog §4 đầy đủ; dashboard Grafana provision bằng file (M-57) | Manifest K8s cho Prometheus/Grafana/Tempo, chế độ mesh của stack observability (P4): Phase 7 |
| Alert rule Grafana Unified Alerting cho bảng §6 + các hàng §4 ghi "alert rule with Phase 6" + **định nghĩa** tín hiệu và rule P1 | Probe Readiness/Liveness trên K8s và bằng chứng của nó (M-64, M-65): Phase 7. Phase 6 chỉ cấu hình nhóm health (Q2) |
| Stack observability cục bộ trong `docker-compose.yml` (Prometheus, Grafana, Tempo, plugin `rabbitmq_prometheus`) | Alertmanager / PagerDuty / Slack (`observability.md` ADR Alerting Rule Engine: không thêm pod nào) |
| Điều tra M-38 `Flaky — investigate` có giới hạn thời gian (Q17) | Rate limiter (INV-17), mTLS (INV-20), CI stage 5 Docker: Phase 7 |

> ⚠ INV-14 là **bất biến không được nới** (`CLAUDE.md`): một pipeline audit hỏng **không bao giờ** chặn hay làm hỏng thao tác nghiệp vụ, **và** lỗi publish audit phải hiện thành cảnh báo vận hành, không biến mất lặng lẽ. "Không chặn" gồm cả *không làm treo* (broker bị `pause`, kết nối bị memory alarm chặn), không chỉ "không ném lỗi". Một bộ đếm tăng **chưa phải** một cảnh báo: Q10 quyết định bằng chứng cho vế thứ hai, không để bộ đếm âm thầm thay cho alert.

---

## 0. Quyết định phải chốt trước khi code (blockers)

Theo `CLAUDE.md`: task phụ thuộc mục còn mở thì **dừng lại hỏi**, không tự đoán. Mỗi mục dưới đây có phương án + **đề xuất** của Claude Code. Đánh số tiếp từ D63 để không trùng D1–D62; Q1–Q18 → D63–D80. Chỉ tick khi quyết định đã được **ghi vào tài liệu sở hữu** (cột →).

### 0.1 Phạm vi, hạ tầng cục bộ

- [x] `[→ M-57, M-56]` **Q1 — Stack observability chạy ở đâu và file provisioning đặt ở đâu.** Phase 6 chưa có K8s; `deployment-and-cicd.md` ADR Mesh mTLS (c) đã giả định Prometheus, Grafana, Tempo sẽ chạy trong cluster ở Phase 7.
  - **✅ Chốt 2026-11-02 (D63) — owner giao Claude Code + advisor chốt thay trong phiên làm việc, owner xác nhận sau:** (a), thư mục `observability/` ở gốc repo (owner đã đồng ý trong phiên). **Đã ghi vào tài liệu sở hữu (2026-11-02):** `observability.md` §8 (stack cục bộ), `deployment-and-cicd.md` §2 (ghi chú C43). Tên biến Grafana trong `env.example` thêm ở task 1.4 cùng docker-compose.
  - (a) Thêm `prometheus`, `grafana`, `tempo` vào `apps/backend/docker-compose.yml` (image ghim phiên bản ≥ 2 tuần tuổi), cấu hình bằng **file provisioning trong repo** (scrape config, datasource Prometheus + Tempo, dashboard JSON, alert rule YAML) đặt ở một thư mục mà Phase 7 dùng lại nguyên vẹn cho ConfigMap; Prometheus scrape ứng dụng chạy trên host (`host.docker.internal`) và cổng `15692` của RabbitMQ (bật `rabbitmq_prometheus` qua `enabled_plugins` mount vào container). Mật khẩu admin Grafana lấy từ biến môi trường (`env.example` có tên biến, không có giá trị; Gitleaks).
  - (b) Chỉ export metric/trace, không dựng stack; dashboard và alert viết "trên giấy" cho Phase 7. Không có bằng chứng M-57 nào chạy được trước Phase 7.
  - **Đề xuất: (a).** Thư mục: **`observability/` ở gốc repo** (cạnh `apps/`, `packages/`), chứa **nội dung** giám sát không phụ thuộc môi trường: `grafana/provisioning/{datasources,dashboards,alerting}/`, `grafana/dashboards/*.json`, `tempo/tempo.yaml`, và `prometheus/prometheus.compose.yml` (cấu hình scrape **chỉ** cho docker-compose: `host.docker.internal`, `rabbitmq:15692`). `docker-compose.yml` mount từ đó. **Không** đặt trong `k8s/`: Phase 6 không chạy Kubernetes, và các file này không phụ thuộc Kubernetes. Phase 7 (gợi ý, Phase 7 chốt — `[· P7]`): `k8s/observability/` chỉ chứa **cách triển khai** (Deployment/Service của Prometheus, Grafana, Tempo; cấu hình scrape bằng service discovery) và một `configMapGenerator` đọc file từ `../../observability/` — Makefile đã dùng `--load-restrictor LoadRestrictionsNone` (`deployment-and-cicd.md` §7), nên đọc file ngoài `k8s/` được. Một bản nội dung, hai nơi dùng, không lệch nhau (xem C43).
- [x] `[→ M-57, M-65 · P7]` **Q2 — Actuator lộ ra thế nào.** `deployment-and-cicd.md` ADR Mesh (c) ghi "Actuator on 8080" (cùng cổng API). Security hiện chặn mọi đường bằng JWT; `/actuator/prometheus` lộ ra Internet qua Ingress là rò thông tin vận hành.
  - **✅ Chốt 2026-11-02 (D64) — owner giao Claude Code + advisor chốt thay trong phiên làm việc, owner xác nhận sau:** (a): cổng quản trị `8081` (test: `0` + `@LocalManagementPort`), chỉ `health` + `prometheus`; indicator Redisson tự viết, timeout 1 s; `db` chỉ Master; nhóm health cấu hình ngay. **Đã ghi vào tài liệu sở hữu (2026-11-02):** `observability.md` §5 (cổng quản trị, indicator Redisson, `db` chỉ Master), §7; `deployment-and-cicd.md` ADR Mesh (c); `auth-and-identity.md` §6.
  - (a) **Cổng quản trị riêng** (`management.server.port`, ví dụ `8081`), chỉ expose `health` (gồm nhóm `liveness`, `readiness`, `dependencies` theo `observability.md` §5) và `prometheus`; security chain riêng cho cổng đó `permitAll`; Ingress (Phase 7) không bao giờ route tới nó. Contract-diff không đổi (springdoc chỉ thấy cổng API) — **kiểm**.
  - (b) Cùng cổng 8080, `permitAll` cho `/actuator/health/**` và `/actuator/prometheus`, Phase 7 chặn ở Ingress.
  - Nhóm health: cấu hình `readiness = readinessState, db (Master)` và `dependencies = redis, rabbit` (trạng thái `DEGRADED`, HTTP 200) **ngay bây giờ** vì Actuator vào ở phase này; bằng chứng K8s của chúng vẫn là M-64/M-65 ở Phase 7.
  - ⚠ **Không có health indicator `redis` sẵn:** dự án dùng `org.redisson:redisson` thuần, không `spring-boot-starter-data-redis`, nên Boot không tự sinh contributor `redis`. Cần một **indicator tự viết** (ping Redisson với **timeout cứng**, vd. 1 s — không có timeout thì Redis bị `pause` làm treo lời gọi health và mọi lần scrape đọc nó). Indicator `db` mặc định gom **mọi** `DataSource` (gồm đích Replica): phải giới hạn rõ về Master.
  - Với cổng riêng, IT dùng `management.server.port=0` + `@LocalManagementPort` để các context chạy song song không giành cùng một cổng.
  - **Đề xuất: (a)** + cấu hình nhóm health ngay. → `observability.md` §5, `deployment-and-cicd.md` (ADR Mesh (c), §2), `auth-and-identity.md` (security chain mới)

### 0.2 Correlation ID (M-53, M-17)

- [x] `[→ M-53]` **Q3 — `X-Correlation-Id` do client gửi lên.** Ba nguồn mâu thuẫn (C35): `headers.yaml#/CorrelationIdRequest` nói giá trị client gửi "propagated … and echoed back"; ADR Correlation ID Identity nói correlation ID **là** trace ID, không có ID thứ hai; `web-coding-standards.md` nói client **không** tạo header này.
  - **✅ Chốt 2026-11-02 (D65) — owner giao Claude Code + advisor chốt thay trong phiên làm việc, owner xác nhận sau:** (a): header do client gửi bị **bỏ qua** (không từ chối, nên client lạc gửi lên vô hại); contract bỏ `CorrelationIdRequest`. Đã kiểm 2026-11-02: `apps/mobile` không gửi header này (chỉ có kiểu sinh trong `schema.gen.ts`). **Đã ghi vào tài liệu sở hữu (2026-11-02):** `observability.md` §8. Phần contract (`headers.yaml` + 9 controller + `schema.gen.ts`) sửa **cùng code** ở task 1.3, vì sửa riêng contract làm `contract-diff` đỏ (như C23 của Phase 5).
  - (a) **Bỏ qua** header client gửi; correlation ID luôn là trace ID của request (tiếp nối trace qua `traceparent` nếu có, `observability.md` §1). Xóa `CorrelationIdRequest` khỏi các operation trong contract, giữ `CorrelationIdResponse`. Client muốn nối trace thì gửi `traceparent`.
  - (b) Chấp nhận một giá trị 32-hex hợp lệ làm trace ID (propagator tùy biến). Client điều khiển được trace ID: hai request khác nhau có thể cố ý trùng ID, làm nhiễu điều tra; thêm mã tùy biến.
  - (c) Ghi giá trị client vào một thuộc tính riêng (`client.correlation_id`) trên span/log, correlation ID vẫn là trace ID. Hai ID, đúng điều ADR đã loại.
  - **Đề xuất: (a)** (khớp ADR và `web-coding-standards.md`; thay đổi contract nhỏ, `contract-diff` phải xanh cùng commit). → `packages/api-contract/common/headers.yaml` + mọi `paths/*.yaml` tham chiếu nó; `observability.md` §1
- [x] `[→ M-53]` **Q4 — Correlation cho công việc không bắt đầu từ HTTP, và cho request không được lấy mẫu.** Lấy mẫu là `ParentBased(TraceIdRatioBased(0.10))`: 90% request không được sample. Job định kỳ (relay, reconciliation sweep, nightly sync, các cleanup) và message không có header `correlationId` (relay của một row `NULL`, message cũ) không có request nào đứng trước.
  - **✅ Chốt 2026-11-02 (D66) — owner giao Claude Code + advisor chốt thay trong phiên làm việc, owner xác nhận sau:** như đề xuất (i)–(iii). **Điều kiện dừng:** nếu test 3.4 với sampling 0.0 cho thấy không có trace ID, dừng lại và nêu ra — **không** lùi về UUID (đó là phương án hai-ID mà ADR đã loại). **Đã ghi vào tài liệu sở hữu (2026-11-02):** `observability.md` §8.
  - Đề xuất: (i) giá trị luôn là trace ID của span hiện hành **kể cả khi không được sample** (OTel vẫn sinh trace ID cho span không ghi) — **phải kiểm bằng test với sampling = 0.0**, không giả định; (ii) mỗi chu kỳ job chạy trong một `Observation` riêng → có trace ID → có `correlationId` trong MDC và trong các row outbox nó ghi; (iii) consumer nhận message không có header dùng trace ID của span consumer. → `observability.md` §1
- [x] `[→ M-53]` **Q5 — "Đến tận log output": định dạng log.** Boot 3.3 chưa có structured logging dựng sẵn (có từ 3.4). SRS: "all the way to log output (files or database)".
  - **✅ Chốt 2026-11-02 (D67) — owner giao Claude Code + advisor chốt thay trong phiên làm việc, owner xác nhận sau:** (a): pattern logback có `%X{correlationId}`, `traceId`, `spanId`; không dependency mới. **Đã ghi vào tài liệu sở hữu (2026-11-02):** `observability.md` §8.
  - (a) Pattern console thêm `%X{correlationId}` (và `traceId`/`spanId`), giữ logger `unihub.audit` dạng JSON như hiện tại.
  - (b) Toàn bộ log JSON qua `logstash-logback-encoder` (dependency mới, ghim phiên bản).
  - **Đề xuất: (a)** — đủ cho yêu cầu, không thêm dependency; bằng chứng là dòng log **bắt được thật** ở phía consumer (không chỉ đọc MDC). → `observability.md` §1, `logback-spring.xml`

### 0.3 Audit (M-54, M-55, INV-14)

- [x] `[→ M-54, M-55]` **Q6 — `RabbitAuditTrail` không được *làm treo* request, không chỉ không ném lỗi.** `backend-coding-standards.md` §12 cho publish trong `afterCommit` — tức **trên thread request**. Broker bị `pause` hoặc kết nối bị memory alarm chặn có thể giữ thread đó lâu (Phase 4 đã gặp: mở channel chờ `channel-rpc-timeout` 10 phút, sửa thành 5 s ở M-41). Nếu dùng chung connection với relay, một đường audit tắc còn có thể chặn cả outbox relay.
  - **✅ Chốt 2026-11-02 (D68) — owner giao Claude Code + advisor chốt thay trong phiên làm việc, owner xác nhận sau:** (a) cùng mọi bổ sung ở trên (chụp context, drain khi tắt, `reason` đăng ký sẵn ở 0, connection factory riêng). Số: 1 thread, hàng đợi 10 000, confirm timeout 5 s, connection/RPC timeout 5 s (như M-41), drain 5 s. `LoggingAuditTrail` bị thay. **Đã ghi vào tài liệu sở hữu (2026-11-02):** `backend-coding-standards.md` §12 (quy tắc 5–8), `messaging.md` §3, `observability.md` §1, §4, §7, §8.
  - (a) `afterCommit` chỉ **đưa entry vào một executor có giới hạn** (1 thread, hàng đợi cố định, ví dụ 10 000); đầy → bỏ và **đếm** (`unihub_audit_publish_failures_total{reason="dropped"}`); thread executor publish qua **`CachingConnectionFactory` riêng** cho audit, publisher confirm bất đồng bộ (nack/return/timeout → đếm `reason="nack"|"timeout"`, exception → `reason="error"`), timeout kết nối/RPC ngắn. Request không bao giờ chạm socket broker.
  - (b) Publish đồng bộ trong `afterCommit` với timeout ngắn (vd. 1 s). Đơn giản hơn nhưng mỗi request vẫn có thể chờ tới timeout khi broker treo — trái chữ "never sits on the critical path" (`architecture.md` §5.5).
  - **Correlation qua executor:** thread executor có MDC rỗng, nên `CorrelationIdPostProcessor` sẽ publish message audit **không có** `correlationId` và không có trace context. Phải chụp `correlationId` + context observation lúc `record()` (trên thread request) và đặt lại trên thread executor (snapshot của Micrometer context-propagation, hoặc executor bọc lại). Xem C44.
  - **Tắt máy:** entry còn trong hàng đợi executor khi pod dừng phải được **drain có timeout** (vd. 5 s), phần còn lại đếm `reason="dropped"` — không thì mất lặng lẽ, đúng điều INV-14 cấm. JVM crash thì không đếm được: ghi vào `phase-6.md` §5 như một giới hạn có tên.
  - **Mọi chuỗi `reason` đăng ký sẵn ở 0** lúc khởi động: `increase()`/`rate()` của Prometheus bỏ qua một chuỗi *sinh ra* đã lớn hơn 0, nên chuỗi chỉ được tạo ở lỗi đầu tiên có thể không bao giờ làm alert bắn.
  - Cũng chốt: `LoggingAuditTrail` bị **thay** (không chạy song song); entry bị bỏ/lỗi vẫn được log `WARN` một dòng (không mang metadata nhạy cảm). Nhãn `reason` là thêm vào metric đã định nghĩa — ghi vào catalog.
  - **Đề xuất: (a).** → `backend-coding-standards.md` §12, `messaging.md` §3, `observability.md` §4
- [x] `[→ M-54]` **Q7 — Consumer audit và bất biến "consumer idempotent khi redelivery".** `messaging.md` §3 chấp nhận row trùng ("cosmetic noise") và `database-schema.md` §8.3 không có ràng buộc unique; `CLAUDE.md` xếp idempotency của consumer vào Non-Negotiable Invariants; `ConsumerRedeliveryCoverageTest` đỏ với listener không có mục (C36).
  - **✅ Chốt 2026-11-02 (D69) — owner giao Claude Code + advisor chốt thay trong phiên làm việc, owner xác nhận sau:** (a): `V8` thêm `audit_events.source_message_id` + unique index, `ON CONFLICT DO NOTHING`. (b) là nới một bất biến của `CLAUDE.md`, không thuộc phạm vi được giao. Đã kiểm 2026-11-02: `backend-coding-standards.md` §13 xếp Audit Event là Technical Pattern Entity của `shared`, và `shared.infrastructure..` đã chứa phía `in` (`CorrelationIdListenerAdvice`, các scheduler), nên consumer đặt ở `shared.infrastructure.in.messaging`. **Đã ghi vào tài liệu sở hữu (2026-11-02):** `messaging.md` §3, `database-schema.md` §8.3 + §10.5 (`V8`), `backend-coding-standards.md` §12.
  - (a) **Dedup theo message-id:** migration `V8` thêm `audit_events.source_message_id UUID` nullable + unique index (thêm cột nullable: an toàn khi rollout, INV-15), consumer `INSERT … ON CONFLICT (source_message_id) DO NOTHING`; publisher đặt `message-id` = UUID mới mỗi entry. Có test tuần tự + đồng thời như sáu consumer kia.
  - (b) Mục miễn trừ có ghi lý do trong `ConsumerRedeliveryCoverageTest` và sửa `CLAUDE.md`. Là **nới** một bất biến → theo `CLAUDE.md` phải dừng lại nêu ra, không tự làm.
  - Consumer đặt ở `shared.infrastructure.in.messaging` (audit không thuộc module nào, `messaging.md` §3) — kiểm `backend-coding-standards.md` §13 có cho phép không.
  - **Đề xuất: (a).** → `messaging.md` §3, `database-schema.md` §8.3 + §10 (`V8`), `backend-coding-standards.md` §12–§13
- [x] `[→ M-55]` **Q8 — Tham số của `unihub.audit.queue`.** `Topology` đã khai báo queue (không giới hạn độ dài); nhiều khả năng nó **đã tồn tại** trên broker local và trong mọi Testcontainers đang chạy. Tham số queue không đổi được sau khi khai báo (đã gặp ở D44).
  - **✅ Chốt 2026-11-02 (D70) — owner giao Claude Code + advisor chốt thay trong phiên làm việc, owner xác nhận sau:** **`x-max-length` 100 000 + `x-overflow=reject-publish-dlx`, `x-dead-letter-exchange` = `""`, `x-dead-letter-routing-key` = `unihub.parking-lot`.** Thí nghiệm 2026-11-02 trên `rabbitmq:3.13.7` (pika, `x-max-length` = 1): với `reject-publish`, publisher nhận nack nhưng message từ retry quay về queue đầy **mất lặng lẽ** (không vào đâu cả); với `reject-publish-dlx`, publisher nhận nack **và** cả message bị từ chối lẫn message từ retry đều vào queue dead-letter. Như vậy mọi đường tràn đều được đếm (nack → `reason="nack"`) hoặc vào parking lot (alert Critical sẵn có). Queue nghiệp vụ hiện **không** có tham số DLX (`Topology` dựng `QueueBuilder.durable(name).build()`), nên đây là queue đầu tiên có — xóa `unihub.audit.queue` cũ một lần trên broker local (như D44). **Đã ghi vào tài liệu sở hữu (2026-11-02):** `messaging.md` §2 (bảng queue), §3 (kết quả thí nghiệm).
  - (a) Giữ nguyên (không `x-max-length`): consumer chết lâu → queue lớn dần → memory alarm chặn **mọi** publisher, gồm relay.
  - (b) Thêm `x-max-length` (ví dụ 100 000) + `x-overflow=reject-publish`: khi đầy, broker nack → publisher confirm của Q6 (a) đếm `reason="nack"` → hiện thành cảnh báo; relay không bị ảnh hưởng. Cần xóa queue cũ (rỗng, vì chưa ai publish) một lần trên broker local, như D44.
  - ⚠ **Phải kiểm trước khi chốt (b):** message quay lại từ `unihub.audit.retry.*` (dead-letter khi hết TTL) vào một queue đã đầy **không có publisher nào nhận nack** — với `reject-publish` chúng có thể bị bỏ lặng lẽ. Kiểm hành vi trên RabbitMQ 3.13.7; nếu đúng vậy, so với `x-overflow=reject-publish-dlx` (bị từ chối → dead-letter về parking lot, có alert) hoặc ghi rõ đó là một đường mất có tên.
  - **Đề xuất: (b)** (hoặc `reject-publish-dlx` theo kết quả kiểm), giá trị owner chốt. → `messaging.md` §2–§3
- [x] `[→ M-54]` **Q9 — Phạm vi bằng chứng của M-54.** REQ-2.11.4 nêu bốn hạng mục: registration, payment, check-in, đổi lịch workshop. `architecture.md` §5.5 thêm hạng mục thứ năm (đổi role/permission) — **không có use case nào** đổi role/permission.
  - **✅ Chốt 2026-11-02 (D71) — owner giao Claude Code + advisor chốt thay trong phiên làm việc, owner xác nhận sau:** như đề xuất: bốn hạng mục của REQ-2.11.4 qua HTTP thật; hạng mục role/permission ghi là gap (không có use case); không endpoint đọc audit. **Đã ghi vào tài liệu sở hữu (2026-11-02):** `test-matrix.md` M-54 (test dự kiến; `⚠` gỡ khi test được viết, như các phase trước).
  - Đề xuất: M-54 chứng minh qua HTTP thật rằng mỗi hạng mục trong bốn tạo đúng row `audit_events` (actor, action, target, `occurred_at`; actor `SYSTEM` cho hold hết hạn và reconciliation): tạo registration (free và paid), charge + webhook, check-in sync, reschedule + cancel workshop. Hạng mục thứ năm ghi vào `phase-6.md` §5 là "không có producer vì không có use case", không xây endpoint đổi role. Không xây endpoint đọc audit. → `test-matrix.md` M-54 (gỡ `⚠`)
- [x] `[→ M-55]` **Q10 — Bằng chứng cho vế "hiện thành cảnh báo vận hành" của INV-14.** Exit criterion: "the audit-publish failure surfaces as an operational alert, never a silent drop".
  - **✅ Chốt 2026-11-02 (D72) — owner giao Claude Code + advisor chốt thay trong phiên làm việc, owner xác nhận sau:** **(b), đổi so với đề xuất ban đầu.** Lý do: `test-plan.md` §2 cấm làm yếu bằng chứng của bất kỳ INV nào, và exit criterion đòi chính *test* outage chứng minh lỗi "surfaces as an operational alert"; (a) để nửa vế INV dựa vào một lần chạy tay, còn tiền lệ M-44 là một dòng không mang INV. Hình dạng: một IT per-push với container Prometheus (`exposeHostPorts`, scrape 1 s) và container Grafana provision từ **chính** file trong `observability/` (đồng thời chứng minh file nạp được), poll API alert của Grafana tới `Firing`; ước tính thêm khoảng 1 phút cho job 12–14 phút. Lần chạy compose có ghi lại vẫn làm, nhưng chỉ là bằng chứng dashboard của M-57. **Điều kiện dừng:** nếu IT này không đạt 10/10 lần chạy local, đưa lại cho owner, **không** lặng lẽ lùi về (a). **Đã ghi vào tài liệu sở hữu (2026-11-02):** `resilience-testing.md` §2.7, `test-matrix.md` M-55, `integration-testing.md` (dòng INV-14), `observability.md` §6.
  - (a) **IT per-push** chứng minh: (1) nghiệp vụ vẫn `2xx` khi RabbitMQ bị `stop` **và** bị `pause`, kèm **giới hạn thời gian phản hồi** (vd. < 2 s, để `pause` làm treo thì test đỏ); (2) `unihub_audit_publish_failures_total` tăng và **xuất hiện trong một lần scrape thật** `/actuator/prometheus`; (3) row audit tiếp tục sau khi phục hồi. **Cộng** một test tĩnh: rule alert provision có tham chiếu đúng metric đó. **Cộng** một lần chạy docker-compose **có ghi lại** (`docs/testing/reports/phase-6-observability-checks.md`): dừng broker, alert Grafana chuyển `Firing`. Lần chạy tay là **giới hạn bằng chứng có tên** ở sign-off (như B1 của Phase 5).
  - (b) IT dựng Prometheus + Grafana bằng Testcontainers, đợi API alert của Grafana báo `Firing`. Chứng minh trọn vẹn tự động, nhưng tốn: chu kỳ scrape + `for` + đánh giá rule, cộng hai container, trên một job `integration-test` đã 12–14 phút.
  - **Đề xuất: (a)**, với giới hạn bằng chứng ghi rõ; (b) là phương án nếu owner muốn vế alert tự động hoàn toàn. → `resilience-testing.md` §2.7 (bỏ TODO lỗi thời, C34), `test-matrix.md` M-55

### 0.4 Tracing (M-56)

- [x] `[→ M-56]` **Q11 — Span cho lời gọi bên thứ ba.** Không client nào là loại Spring tự instrument (C40). SRS §3.1 nêu tên "third-party API calls (Stripe/Momo)".
  - **✅ Chốt 2026-11-02 (D73) — owner giao Claude Code + advisor chốt thay trong phiên làm việc, owner xác nhận sau:** Stripe, AI, S3 có span (chỉ kết quả + thời lượng); SMTP để ngoài, lý do: SRS chỉ nêu Stripe/Momo, và gửi mail đã có metric `unihub.notification.delivery`. **Đã ghi vào tài liệu sở hữu (2026-11-02):** `observability.md` §2, `payment.md` §2, `workshop.md`.
  - Đề xuất: bọc bằng `Observation` thủ công, **chỉ** ghi kết quả (success/failure/timeout) và thời lượng, không body, không header (guardrail `observability.md` §2): Stripe (trong `JdkStripeHttpClient` hoặc adapter gateway), AI (`HttpSummarizationClient`), S3 (ExecutionInterceptor của AWS SDK, hoặc bọc ở adapter). SMTP (Mailpit) để ngoài, ghi lý do — hoặc thêm nếu owner muốn. Kiểm context trace còn đi qua các chỗ đổi thread (circuit breaker / time limiter quanh Stripe). → `observability.md` §2, `payment.md`, `workshop.md`
- [x] `[→ M-56]` **Q12 — Span JDBC.** `observability.md` §2 bắt buộc `datasource-micrometer` (bọc Hikari). Ở đây có **datasource định tuyến Master/Replica** (INV-03).
  - **✅ Chốt 2026-11-02 (D74) — owner giao Claude Code + advisor chốt thay trong phiên làm việc, owner xác nhận sau:** `net.ttddyy.observation:datasource-micrometer-spring-boot:1.0.6` (2024-11-17, dựng trên Boot 3.0.13; dòng 1.1.x dựng trên Boot 3.4, 1.2.x trên 3.5, 2.x trên Boot 4 — không dùng). Chỉ SQL, không giá trị bind; bọc từng datasource đích. AC: toàn bộ IT của INV-03 và INV-06 xanh không sửa. **Đã ghi vào tài liệu sở hữu (2026-11-02):** `observability.md` §7.
  - Đề xuất: `net.ttddyy.observation:datasource-micrometer-spring-boot` ghim phiên bản ≥ 2 tuần tuổi tương thích Boot 3.3; chỉ ghi câu SQL, **không bao giờ** giá trị bind; bọc **từng** datasource đích (không làm lệch định tuyến); chạy lại toàn bộ IT của INV-03 sau khi thêm. Test guardrail: một email/số tiền dùng làm tham số không xuất hiện trong bất kỳ thuộc tính span nào; header `Authorization` cũng không. → `observability.md` §2
- [x] `[→ M-56]` **Q13 — Bằng chứng M-56.**
  - **✅ Chốt 2026-11-02 (D75) — owner giao Claude Code + advisor chốt thay trong phiên làm việc, owner xác nhận sau:** (a): exporter trong bộ nhớ. AC: request và consumer là **hai** trace nối bằng cùng một `correlationId`. **Đã ghi vào tài liệu sở hữu (2026-11-02):** `test-matrix.md` M-56.
  - (a) IT per-push với **exporter span trong bộ nhớ** (`opentelemetry-sdk-testing`, sampling 1.0 trong profile test): một request đăng ký có trả phí sinh span HTTP server, JDBC, publish/consume RabbitMQ (qua relay: hai trace nối bằng cùng `correlationId`, đúng `observability.md` §1), Stripe (WireMock); một summarization sinh span S3 + AI. Cộng một lần chạy tay với Tempo trong docker-compose, ghi vào báo cáo kiểm tay.
  - (b) IT với container Tempo thật, truy vấn API Tempo. Chậm hơn, phụ thuộc thời gian flush.
  - **Đề xuất: (a).** Kiểm trước: `@SpringBootTest` mặc định **tắt** tracing và registry không-in-memory nếu thiếu `@AutoConfigureObservability` — IT có thể xanh hoặc đỏ vì lý do sai. → `test-matrix.md` M-56 (gỡ `⚠`), `integration-testing.md`

### 0.5 Metric, dashboard, alert (M-57, P1)

- [x] `[→ M-57]` **Q14 — Metric còn thiếu trong catalog, và catalog còn thiếu meter.**
  - **✅ Chốt 2026-11-02 (D76) — owner giao Claude Code + advisor chốt thay trong phiên làm việc, owner xác nhận sau:** bốn gauge; cache một-phần-tử tự viết, 10 s. Replica lag trả **NaN** (không phải 0) khi instance không ở chế độ recovery hoặc timestamp replay là `NULL`, để không bao giờ báo một số 0 giả. **Đã ghi vào tài liệu sở hữu (2026-11-02):** `observability.md` §4.
  - Xây bốn gauge chưa có code: `unihub_registration_active_holds`, `unihub_outbox_oldest_pending_age_seconds`, `unihub_postgres_replica_lag_seconds` (debounce 10 s, `observability.md` §4), `unihub_legacy_sync_last_run_quarantine_ratio` (đặt khi nightly sync xong). Replica lag: `pg_last_xact_replay_timestamp()` trả `NULL` trên primary, mà local/Testcontainers chỉ có một instance → định nghĩa rõ giá trị khi `NULL` (đề xuất: không phát mẫu, hoặc `0` khi `pg_is_in_recovery()` sai — owner chọn).
  - Debounce: (a) Caffeine (phiên bản do Boot BOM quản lý, như catalog gợi ý); (b) một cache một-phần-tử tự viết. **Đề xuất: (b)** — ba gauge, không cần dependency mới; `RefundStuckSignals`/`MicrometerNotificationMetrics` đã có gauge DB, kiểm chúng debounce chưa và dùng chung một cách.
  - Thêm vào catalog §4 các meter đã có code mà chưa có tên ở đó (notification delivery/pending age, check-in clock-ahead/resubmission conflicts), và nhãn `reason` của Q6.
  - **Mọi** meter Phase 2–5 (đang ở `globalRegistry`) phải được thấy trong một lần scrape thật (task 4.4) — kiểm `Metrics.globalRegistry` có được nối vào registry Prometheus của Boot không. → `observability.md` §4, §7
- [x] `[→ M-55, M-57, P1]` **Q15 — Ngưỡng alert cho các hàng chưa có ngưỡng, và tín hiệu P1.** §6 có ngưỡng cho latency, 5xx, breaker, parking-lot, queue lag, quarantine, replica lag, heap. **Chưa có** cho: audit-publish failures, reconciliation stuck, refund parked, refund-required oldest age, seat-hold lock unavailable, outbox oldest age, notification pending oldest age. P1 (`test-plan.md` §1): chưa có metric lẫn rule cho "Redis/RabbitMQ xuống" — **là cổng sign-off của Phase 7**, Phase 6 là nơi định nghĩa nó.
  - **✅ Chốt 2026-11-02 (D77) — owner giao Claude Code + advisor chốt thay trong phiên làm việc, owner xác nhận sau:** ngưỡng như đề xuất, là giá trị khởi điểm; P1 qua probe nền có timeout. Đã kiểm 2026-11-02: relay chạy mỗi `PT1S` (`unihub.jobs.outbox-relay.interval`), nên 60 s / 300 s cho outbox oldest age là hàng chục chu kỳ, không báo nhầm. **Đã ghi vào tài liệu sở hữu (2026-11-02):** `observability.md` §4, §6, §7; `test-plan.md` §1 (P1); `IMPLEMENTATION-READINESS.md` A1, B3.
  - Đề xuất khởi điểm (owner sửa): audit failures `increase(…[5m]) > 0` → Warning, `for: 0s`; reconciliation stuck `increase > 0` → Critical, `for: 0s`; refund parked `increase > 0` → Critical, `for: 0s`; refund-required oldest age > 1 h Warning / > 24 h Critical; seat-hold lock unavailable `rate > 0` liên tục 5 m → Warning; outbox oldest age > 60 s Warning / > 300 s Critical; notification pending oldest age > 300 s Warning. **P1:** gauge `unihub_dependency_up{dependency="redis"|"rabbitmq"}` (1/0) lấy từ hai health indicator của nhóm `dependencies` (Q2), rule `== 0` trong 1 m → Critical; cộng `up{job="rabbitmq"} == 0` từ scrape của broker. ⚠ Gauge **không** được gọi health indicator trong lúc scrape: Redis/RabbitMQ bị `pause` sẽ làm treo `/actuator/prometheus`, Prometheus đánh dấu cả target `up=0` và **mọi** metric biến mất đúng lúc cần alert. Thay vào đó: một probe nền chạy theo lịch, timeout cứng cho từng dependency (cả indicator Rabbit — kiểm nó dùng connection factory nào và timeout gì), gauge chỉ đọc kết quả gần nhất.
  - → `observability.md` §4, §6, §7; `test-plan.md` §1 (P1: "NOT MET — no alert defined" → "defined, rule provisioned; bằng chứng K8s vẫn Phase 7")
- [x] `[→ M-57]` **Q16 — Bộ dashboard và bằng chứng M-57.**
  - **✅ Chốt 2026-11-02 (D78) — owner giao Claude Code + advisor chốt thay trong phiên làm việc, owner xác nhận sau:** ba dashboard + test tĩnh tham chiếu metric. **Đã ghi vào tài liệu sở hữu (2026-11-02):** `observability.md` §8, `test-matrix.md` M-57.
  - Đề xuất ba dashboard provision bằng JSON: (1) **API & JVM** — golden signals (p95 qua `histogram_quantile`, rps, tỷ lệ 5xx), Hikari, heap, CPU; (2) **Nghiệp vụ** — active holds, seat-claim conflicts/retries exhausted, breaker `paymentGateway`, reconciliation stuck, refund, check-in, notification; (3) **Messaging & audit** — độ sâu từng queue gồm `unihub.parking-lot`, outbox oldest age, audit failures, dependency up.
  - Bằng chứng: (i) test tĩnh per-push: mọi tên metric trong PromQL của dashboard **và** alert rule có mặt trong một lần scrape thật của ứng dụng (hoặc trong danh sách cố định metric của broker `rabbitmq_*`) — để dashboard/alert không trỏ vào tên sai im lặng; (ii) một lần chạy tay docker-compose có ghi lại: Prometheus target `UP`, mỗi dashboard có dữ liệu, ảnh chụp vào báo cáo kiểm tay. → `test-matrix.md` M-57 (gỡ `⚠`), `observability.md` (mục dashboard mới)

### 0.6 Nêu ra với owner, không làm lặng lẽ

- [x] `[→ M-38]` **Q17 — M-38 `Flaky — investigate`.** Phase 6 instrument chính đường notification. Đề xuất: một task điều tra **có giới hạn thời gian** (Mục 7), dùng trace/metric mới; không chặn sign-off Phase 6 nếu chưa tìm ra nguyên nhân, nhưng kết quả ghi vào `phase-6.md` §5.
  - **✅ Chốt 2026-11-02 (D79) — owner giao Claude Code + advisor chốt thay trong phiên làm việc, owner xác nhận sau:** điều tra có giới hạn một buổi, không chặn sign-off. **Đã ghi vào tài liệu sở hữu (2026-11-02):** chỉ checklist này (Mục 7); không tài liệu sở hữu nào cần đổi.
- [x] `[— không dòng]` **Q18 — Phụ thuộc mới và quét bảo mật.** OTLP exporter kéo theo sender (OkHttp hoặc JDK) và gRPC tùy cấu hình; `datasource-micrometer`; image Prometheus/Grafana/Tempo. Đề xuất: exporter OTLP **HTTP/protobuf với sender JDK** (không OkHttp, không gRPC) nếu bản OTel được Boot 3.3 quản lý hỗ trợ — kiểm; ghi delta Dependency-Check ở Mục 8.3. Các CVE đang mở (`httpclient5`/`httpcore5`, CVE-2025-7962, nền Spring Boot 3.3.5) **không** xử lý ở Phase 6 trừ khi owner yêu cầu.
  - **✅ Chốt 2026-11-02 (D80) — owner giao Claude Code + advisor chốt thay trong phiên làm việc, owner xác nhận sau:** exporter OTLP HTTP/protobuf với **sender OkHttp mặc định**. Đã kiểm 2026-11-02: Boot 3.3.5 quản lý OpenTelemetry `1.37.0` (micrometer 1.13.6, micrometer-tracing 1.3.5), và `opentelemetry-exporter-sender-jdk:1.37.0` **không tồn tại** trên Maven Central. OkHttp (+ Kotlin stdlib) ghi vào delta OWASP ở 8.3. Không gRPC. **Đã ghi vào tài liệu sở hữu (2026-11-02):** `observability.md` §8.

---

## 0A. Decisions (ADR) — D63–D80 (Q1–Q18) — ✅ chốt 2026-11-02

Owner giao Claude Code thảo luận với advisor để chốt thay (2026-11-02: "bạn hãy thảo luận với advisor để chốt các Q1 đến Q18 cho tôi"). Sau khi xem kết quả, owner nói "Được rồi, vậy phase này đã sẵn sàng để commit sử dụng chưa? Nếu rồi thì bạn hãy commit và push cho tôi" và sau đó "Được, sửa tài liệu và đóng C34–C44 luôn đi". Đó là lời đồng ý cho làm tiếp, **không** phải xác nhận từng dòng: trạng thái các dòng D vẫn là "owner xác nhận sau" (cùng cách ghi với B4 của Phase 5). Một thay đổi so với bản nháp: **Q10 → (b)**. Ba dòng được chốt nhờ kiểm thực tế: Q8 (thí nghiệm RabbitMQ), Q12 và Q18 (phiên bản trên Maven Central / BOM của Boot). Ô ở Mục 0 đã tick 2026-11-02, sau khi tài liệu sở hữu được sửa.

| D | Q | Quyết định | AC | Test |
|---|---|---|---|---|
| D63 | Q1 | Nội dung giám sát ở `observability/` (gốc repo), compose mount từ đó; `k8s/` là việc Phase 7 | AC63.1 `docker compose up` nạp đủ datasource, dashboard, rule không lỗi. AC63.2 Không mật khẩu Grafana trong repo | Lần chạy 6.5; Gitleaks |
| D64 | Q2 | Cổng quản trị 8081, chỉ `health`+`prometheus`; indicator Redisson 1 s; `db` chỉ Master | AC64.1 Cổng API trả `401`/`404` cho `/actuator/**`. AC64.2 `/v3/api-docs` vẫn ở cổng API, `contract-diff` vẫn boot với Redis chết. AC64.3 Redis `pause` → `dependencies` `DEGRADED` trong ≤ 2 s | `MetricsExportIT`, `ManagementPortIT` |
| D65 | Q3 | Correlation ID luôn là trace ID; header client gửi bị bỏ qua; contract bỏ `CorrelationIdRequest` | AC65.1 Gửi `X-Correlation-Id: abc` → response mang trace ID khác `abc`. AC65.2 `contract-diff`, `web`, `mobile` xanh | `CorrelationPropagationIT` |
| D66 | Q4 | Trace ID kể cả khi không sample; mỗi chu kỳ job một observation | AC66.1 Sampling 0.0 → header, MDC, `event_publications.correlation_id` đều 32-hex. AC66.2 Row outbox do job ghi có `correlation_id` | `CorrelationPropagationIT` |
| D67 | Q5 | Pattern logback có `correlationId`/`traceId`/`spanId` | AC67.1 Dòng log bắt được của consumer chứa đúng giá trị | `CorrelationPropagationIT` |
| D68 | Q6 | Executor 1 thread/10 000, connection riêng, confirm 5 s, drain 5 s, mọi mất mát đếm theo `reason` (đăng ký sẵn ở 0), chụp context | AC68.1 Broker `pause` → request `2xx` < 2 s. AC68.2 Mỗi nhánh lỗi tăng đúng `reason`. AC68.3 Tắt máy còn entry → đếm `dropped`. AC68.4 Message audit mang `correlationId` của request | `RabbitAuditTrailTest`, `AuditOutageIT`, `AuditTrailIT` |
| D69 | Q7 | `V8` `source_message_id` unique, `ON CONFLICT DO NOTHING`; consumer ở `shared.infrastructure.in.messaging` | AC69.1 Redelivery tuần tự và đồng thời → một row. AC69.2 `ConsumerRedeliveryCoverageTest` có mục audit | `AuditRedeliveryIT`, `ConsumerRedeliveryCoverageTest` |
| D70 | Q8 | `x-max-length` 100 000, `reject-publish-dlx` → `unihub.parking-lot`; xóa queue cũ một lần | AC70.1 Queue đầy → publish bị nack và được đếm; message từ retry vào parking lot, không mất | `AuditQueueOverflowIT` (queue nhỏ trong test) |
| D71 | Q9 | M-54 phủ bốn hạng mục REQ-2.11.4; role/permission là gap | AC71.1 Mỗi hạng mục → row đúng actor/action/target. AC71.2 Rollback → không row | `AuditTrailIT` |
| D72 | Q10 | Vế alert của INV-14 tự động: IT với Prometheus + Grafana thật, poll tới `Firing` | AC72.1 Dừng RabbitMQ → alert audit failures `Firing` trong giới hạn thời gian. AC72.2 10/10 lần local, nếu không → đưa owner | `AuditAlertIT` |
| D73 | Q11 | Span thủ công cho Stripe, AI, S3; SMTP ngoài | AC73.1 Mỗi lời gọi có một span chỉ mang outcome + thời lượng | `TracingIT` |
| D74 | Q12 | `datasource-micrometer-spring-boot` 1.0.6, không giá trị bind | AC74.1 IT của INV-03, INV-06 xanh không sửa. AC74.2 Email/số tiền không xuất hiện trong span | `TracingIT`, `SpanGuardrailTest` |
| D75 | Q13 | Exporter trong bộ nhớ | AC75.1 Span HTTP, JDBC, AMQP, Stripe, S3, AI có mặt. AC75.2 Request và consumer: hai trace, một `correlationId` | `TracingIT` |
| D76 | Q14 | Bốn gauge, cache 10 s tự viết; replica lag NaN khi không phải replica | AC76.1 Mọi metric catalog §3–§4 có trong scrape thật. AC76.2 Replica lag là NaN trên primary | `MetricsExportIT` |
| D77 | Q15 | Ngưỡng khởi điểm theo Q15; P1 = `unihub_dependency_up` từ probe nền | AC77.1 Dependency `pause` → scrape vẫn < 2 s. AC77.2 Rule P1 provision | `DependencyProbeIT`, test tĩnh 6.4 |
| D78 | Q16 | Ba dashboard; test tĩnh tham chiếu metric | AC78.1 Mọi metric trong PromQL có trong scrape hoặc danh sách `rabbitmq_*` | `ObservabilityProvisioningTest` |
| D79 | Q17 | M-38 điều tra một buổi | AC79.1 Kết quả (sửa được hay giả thuyết đã loại) ghi vào matrix và §5 | — |
| D80 | Q18 | OTLP HTTP/protobuf, sender OkHttp (OTel 1.37.0 không có sender JDK) | AC80.1 Delta OWASP ghi ở `phase-6.md` §5 | Job Dependency-Check |

---

## 0B. Đối soát chéo — mâu thuẫn tìm thấy (2026-11-02)

Mỗi mục sửa ở tài liệu được ghi, trước khi code phụ thuộc vào nó. Đánh số C34 trở đi (C34–C44) để không trùng C1–C33.

- [x] **C34** *(sửa 2026-11-02 — `resilience-testing.md` §2.7; rule ở `observability.md` §6)* *(→ Q10, Q15)* — `resilience-testing.md` §2.7 "half two, blocked: … define no audit-publish signal … TODO" ↔ `observability.md` §4 đã định nghĩa `unihub_audit_publish_failures_total` từ 2026-10-14. TODO lỗi thời; nhưng §6 **vẫn** không có rule cho metric đó.
- [x] **C35** *(sửa 2026-11-02 — phía tài liệu: `observability.md` §8 (D65); phía contract ở task 1.3, cùng code)* *(→ Q3)* — `headers.yaml#/CorrelationIdRequest` (client gửi, server propagate và echo) ↔ ADR Correlation ID Identity (trace ID là correlation ID, không ID thứ hai) ↔ `web-coding-standards.md` (client không tạo header).
- [x] **C36** *(sửa 2026-11-02 — `messaging.md` §3, `database-schema.md` §8.3 + §10.5)* *(→ Q7)* — `messaging.md` §3 ("Idempotency: deliberately not pursued", row trùng chấp nhận được) ↔ `CLAUDE.md` Non-Negotiable Invariants ("Consumers must be idempotent under redelivery") ↔ `ConsumerRedeliveryCoverageTest`.
- [x] **C37** *(sửa 2026-11-02 — `observability.md` §1)* *(→ —)* — `observability.md` §1 nói "all five queues", "four outbox-backed message kinds", "five separate listener implementations" ↔ thực tế: tám queue tiêu thụ (`messaging.md` §4), bảy listener hiện có + audit; số routing key của outbox lớn hơn bốn (`OutboxRoutingCoverageTest`). Sửa số, giữ ý.
- [x] **C38** *(sửa 2026-11-02 — `observability.md` §1 (ghi chú Correction); tên property kiểm lại khi code ở task 5.1)* *(→ Q13)* — `observability.md` §1 "trace-context propagation is automatic … with zero application code" ↔ Boot 3.3: observation của `RabbitTemplate` và listener container **tắt mặc định** (`spring.rabbitmq.template.observation-enabled`, `spring.rabbitmq.listener.simple.observation-enabled`) — **kiểm với phiên bản đã ghim**; và các bean dựng tay (`notificationListenerContainerFactory`, template sau `ConfirmedSender`, connection factory audit của Q6) phải tự bật.
- [x] **C39** *(sửa 2026-11-02 — `deployment-and-cicd.md` ADR Mesh (c))* *(→ Q2)* — `deployment-and-cicd.md` ADR Mesh (c) "Actuator on 8080" ↔ Q2 (cổng quản trị riêng).
- [x] **C40** *(sửa 2026-11-02 — `observability.md` §2, `payment.md` §2, `workshop.md`)* *(→ Q11)* — `observability.md` §2 "instrumenting `RestTemplate`/`WebClient` … third-party call (Stripe sandbox, AI Summarization Service) … produces a span" ↔ code: không client nào là `RestTemplate`/`WebClient` (Stripe, AI: `java.net.http`; S3: Apache).
- [x] **C41** *(sửa 2026-11-02 — `observability.md` §4; `backend-coding-standards.md` §12 (quy tắc 6))* *(→ Q14)* — Catalog `observability.md` §4 ↔ code, hai chiều: bốn metric catalog chưa có code; bốn meter có code chưa có trong catalog. `backend-coding-standards.md` §12 "dropped lines are not counted — a known gap closed in Phase 6" → đóng bằng Q6 (`reason="dropped"`).
- [x] **C42** *(sửa 2026-11-02 — `observability.md` §8)* *(→ Q4)* — SRS §3.1 "tagged at the Ingress" và "OpenTelemetry Agent" ↔ ADR (trace ID sinh/tiếp nối ở ứng dụng; Micrometer Tracing thay Agent). ADR đã chấp nhận phần Agent; phần Ingress ghi rõ là Phase 7, Phase 6 chứng minh từ cổng ứng dụng.
- [x] **C43** *(sửa 2026-11-02 — `deployment-and-cicd.md` §2)* *(→ Q1)* — `architecture.md` §1.2 xếp "Observability Stack" vào Core Components, và `deployment-and-cicd.md` ADR Mesh mTLS (c) giả định Prometheus, Grafana, Tempo chạy trong cluster ↔ cây `k8s/` của `deployment-and-cicd.md` §2 **không có manifest nào** cho ba thành phần đó (chỉ backend, Ingress, Postgres, Redis, RabbitMQ, MinIO, PeerAuthentication, Flyway Job). Phase 6 chỉ ghi vào `deployment-and-cicd.md` §2 rằng nội dung giám sát nằm ở `observability/` (Q1) và manifest của stack là việc của Phase 7; **không** tạo `k8s/` ở Phase 6.
- [x] **C44** *(sửa 2026-11-02 — `observability.md` §1, §8)* *(→ Q6)* — `observability.md` §1: audit "is published inside the request and keeps a single continuous trace" ↔ Q6 (a): publish chạy trên thread executor, không trong request. Trace/correlation chỉ liên tục nếu context được chụp và đặt lại (Q6); sửa câu trong §1 theo cách đã chốt.

---

## 1. Nền móng: Actuator, registry, tracing bridge, stack cục bộ (sau Q1, Q2, Q3, Q18)

- [ ] **1.1** `[→ M-53, M-56, M-57]` Dependency: `spring-boot-starter-actuator`, `micrometer-registry-prometheus`, `micrometer-tracing-bridge-otel`, `opentelemetry-exporter-otlp` (sender theo Q18); test: `opentelemetry-sdk-testing`. Cấu hình `management.*` theo Q2 (cổng, exposure, nhóm health, `percentiles-histogram` cho `http.server.requests`), `management.tracing.sampling.probability=0.10`, endpoint OTLP lấy từ cấu hình (tắt export khi không có endpoint, để CI không cố gửi trace).
- [ ] **1.2** `[→ M-57]` Security chain cho cổng quản trị (Q2): chỉ `health` + `prometheus`; không token → `200`; cổng API không phục vụ `/actuator/**`. Indicator Redisson có timeout; `db` giới hạn về Master. `contract-diff` xanh.
- [ ] **1.3** `[→ M-53]` Contract theo Q3: sửa `headers.yaml` và các operation tham chiếu **và** annotation `@Parameter(name = "X-Correlation-Id" …)` trong **chín controller** đang khai báo nó cho springdoc (`AuthController`, `WorkshopController`, `BrochureController`, `RegistrationController`, `NotificationController`, `PaymentController`, `PaymentWebhookController`, `CheckInController`, `WorkshopReportController`) — sửa một phía là `contract-diff` đỏ; sinh lại `apps/web/src/shared/api/schema.gen.ts` (và kiểu của mobile nếu có nhắc header) để job `web`/`mobile` xanh. Bỏ các comment "documented only — no correlation-ID filter exists yet" khi filter có ở 3.1.
- [ ] **1.4** `[→ M-56, M-57]` `docker-compose.yml`: `prometheus`, `grafana`, `tempo` (image ghim), `rabbitmq_prometheus` bật; file provisioning ở `observability/` (Q1) (datasource, scrape config; dashboard/alert để Mục 6); biến môi trường Grafana trong `env.example`. Kiểm tay: Prometheus target ứng dụng + broker `UP`.
- [ ] **1.5** `[— không dòng]` Kiểm cấu hình test: các IT hiện có không vô tình export trace/đổi hành vi; IT của Phase 6 dùng `@AutoConfigureObservability` có chủ đích. Toàn bộ `integrationTest` xanh trước khi sang Mục 2.

## 2. Audit pipeline — M-54, M-55 (INV-14) (sau Q6–Q10)

Ưu tiên cao nhất của phase (`test-plan.md` §2: INV-14 thuộc nhóm 1).

- [ ] **2.1** `[→ M-54]` Migration `V8` theo Q7 (cột `source_message_id` nullable + unique index), và tham số queue theo Q8 trong `Topology` (kèm thao tác xóa queue một lần trên broker local, ghi lại như D44).
- [ ] **2.2** `[→ M-54, M-55]` `shared.infrastructure.out.messaging.RabbitAuditTrail` theo Q6 và `backend-coding-standards.md` §12 (1)–(4): sau commit / ngay nếu không có transaction, không qua outbox, không bao giờ ném, mọi lỗi được đếm theo `reason` (mọi `reason` đăng ký sẵn ở 0), ánh xạ actor đúng `audit_events`. Unit test cho từng nhánh (không transaction, rollback → không publish, executor đầy → `dropped`, nack, exception, **tắt máy còn entry trong hàng đợi → drain rồi đếm phần còn lại**).
- [ ] **2.3** `[→ M-54]` Consumer `unihub.audit.queue` → `audit_events` (`ON CONFLICT DO NOTHING`); lỗi → chuỗi retry → parking lot như mọi consumer.
- [ ] **2.4** `[→ M-54]` **Cùng commit với 2.2:** thay `AuditPublishingAbsentTest` bằng quy tắc mới (chỉ `RabbitAuditTrail` implement `AuditTrail` trong `src/main`; routing key audit chỉ được nhắc ở `Topology` + adapter + consumer); thêm mục audit vào `ConsumerRedeliveryCoverageTest` + test redelivery tuần tự và đồng thời (Q7). Xóa `LoggingAuditTrail` và cấu hình `AsyncAppender` của logger `unihub.audit` nếu Q6 chốt thay thế. Gỡ **mọi** chỗ đăng ký `unihub.audit.publish.failures` không có tag (adapter cũ, fixture test): registry Prometheus đòi mọi meter cùng tên có cùng bộ tag key — kiểm Micrometer 1.13 báo lỗi hay bỏ qua lặng lẽ.
- [ ] **2.5** `[— không dòng]` Chạy lại toàn bộ IT đang khẳng định trên dòng log audit (nếu có) — chuyển sang khẳng định trên row `audit_events`.
- [ ] **2.6** **`[M-54]`** `AuditTrailIT` (per-push, HTTP thật): bốn hạng mục của Q9 tạo đúng row (actor `USER`/`SYSTEM`, action, target, `occurred_at`); thao tác bị rollback không tạo row; redelivery không tạo row thứ hai; message audit mang đúng `correlationId` của request đã sinh ra nó (khẳng định thêm ở 3.6 khi filter có).
- [ ] **2.7** **`[M-55]`** `AuditOutageIT` (per-push, RabbitMQ riêng của test): `stop` **và** `pause`; một registration free, một charge (gateway mock), một check-in sync, một reschedule đều `2xx` **trong giới hạn thời gian** của Q10; `unihub_audit_publish_failures_total` tăng và có trong một lần scrape thật; sau phục hồi row audit tiếp tục. Biến thể: consumer lỗi → chuỗi retry → parking lot, nghiệp vụ không bị ảnh hưởng. Sửa `resilience-testing.md` §2.7 ("As built").

## 3. Correlation ID — M-53, M-17 (sau Q3, Q4, Q5, Mục 1)

- [ ] **3.1** `[→ M-53]` `CorrelationIdFilter`: chạy **sau** filter observation của Boot (trace đã có) và **trước** chuỗi Spring Security (để `401`/`403` của resource server cũng có header và body); `MDC.put` + `finally` `MDC.remove`; echo `X-Correlation-Id` trên mọi response.
- [ ] **3.2** `[→ M-53]` Job định kỳ và consumer không có header theo Q4 (`Observation` mỗi chu kỳ); kiểm `CorrelationIdListenerAdvice` phủ cả container dựng tay (`notificationListenerContainerFactory`).
- [ ] **3.3** `[→ M-53]` `logback-spring.xml` theo Q5.
- [ ] **3.4** `[→ M-53]` Test với sampling = 0.0: header, MDC, `event_publications.correlation_id` vẫn có giá trị 32-hex.
- [ ] **3.5** **`[M-17 · §4.5]`** `GlobalProblemHandler` + entry point/access-denied handler điền `correlationId`; `ProblemShapeIT` khẳng định `correlationId` khớp header trên **mọi** problem body, gồm `401` và `403`; `VerificationKeyIT` khẳng định header (đóng `phase-5.md` §5 hàng 9). Đưa `correlationId` vào `required` của `common/error.yaml` (lý do hoãn M-17 là nó chưa required), sinh lại `schema.gen.ts`, `contract-diff` xanh cùng commit. Gỡ M-17 khỏi `test-matrix.md` §4.5.
- [ ] **3.6** **`[M-53]`** `CorrelationPropagationIT` (per-push): một registration trả phí qua HTTP thật → giá trị `X-Correlation-Id` = `event_publications.correlation_id` = header `correlationId` của message = giá trị trong **dòng log bắt được** của consumer (payment confirmation, notification); qua ít nhất một bước async (exit criterion). Không rò sang message kế tiếp trên cùng thread (hai message liên tiếp, giá trị khác nhau).

## 4. Metric — M-57 (phần ứng dụng) (sau Q14)

- [ ] **4.1** `[→ M-57]` Bốn gauge còn thiếu (Q14), debounce 10 s dùng chung; xử lý `NULL` của replica lag.
- [ ] **4.2** `[→ M-57]` Nối `Metrics.globalRegistry` vào registry Prometheus (nếu Boot chưa tự nối) để meter Phase 2–5 được export; `unihub_dependency_up` của P1 (Q15) bằng probe nền có timeout, không gọi health trong lúc scrape; test: dependency bị `pause` thì scrape vẫn trả trong < 2 s.
- [ ] **4.3** `[→ M-57]` Cập nhật catalog `observability.md` §4, §7 (C41).
- [ ] **4.4** **`[M-57]`** `MetricsExportIT` (per-push): một lần scrape thật cổng quản trị chứa **mọi** metric trong catalog §3–§4 (sau khi kích hoạt đường code sinh ra nó nếu là counter), có bucket histogram của `http_server_requests_seconds`; cổng API không lộ `/actuator/prometheus`.

## 5. Tracing — M-56 (sau Q11–Q13)

- [ ] **5.1** `[→ M-56]` Bật observation cho `RabbitTemplate` và listener container (C38), gồm bean dựng tay.
- [ ] **5.2** `[→ M-56]` `datasource-micrometer` theo Q12; **chạy lại mọi IT của INV-03** (định tuyến Master/Replica) và INV-06 (seat claim) — phải xanh không sửa.
- [ ] **5.3** `[→ M-56]` `Observation` thủ công cho Stripe, AI, S3 (Q11); kiểm context qua circuit breaker.
- [ ] **5.4** `[→ M-56]` Test guardrail: không giá trị bind, không `Authorization`, không body trong thuộc tính span.
- [ ] **5.5** **`[M-56]`** `TracingIT` (per-push, exporter trong bộ nhớ) theo Q13 (a).

## 6. Alert và dashboard — M-55 (vế alert), M-57, P1 (sau Q10, Q15, Q16)

- [ ] **6.1** `[→ M-55, P1 · P7]` File alert rule Grafana Unified Alerting: toàn bộ bảng §6 + các hàng của Q15 + P1. Sửa `test-plan.md` §1 dòng P1 (định nghĩa xong; bằng chứng K8s vẫn Phase 7).
- [ ] **6.2** `[→ M-57]` Ba dashboard JSON theo Q16.
- [ ] **6.3** `[→ M-57]` Datasource Tempo + exemplar từ panel latency (ADR Trace Backend).
- [ ] **6.4** **`[M-57, §4.5]`** Test tĩnh per-push: mọi metric trong PromQL của dashboard và rule có trong scrape thật (hoặc danh sách cố định `rabbitmq_*`); rule parking-lot, reconciliation-stuck, audit failures tồn tại với đúng ngưỡng; **mỗi chuỗi `unihub_audit_publish_failures_total{reason=…}` có trong scrape ở giá trị 0 trước khi tiêm lỗi nào** (để `increase()` thấy được lần tăng đầu). Gỡ mục alert khỏi `test-matrix.md` §4.5.
- [ ] **6.6** **`[M-55]`** `AuditAlertIT` (per-push, D72): Prometheus (`exposeHostPorts`, scrape 1 s) + Grafana provision từ chính `observability/`; dừng RabbitMQ của test → poll API alert của Grafana tới `Firing` cho rule audit failures; 10/10 lần local (không đạt → đưa owner, không lùi về chạy tay).
- [ ] **6.5** **`[M-56, M-57]`** Lần chạy docker-compose có ghi lại, vào `docs/testing/reports/phase-6-observability-checks.md`: target `UP`; ba dashboard có dữ liệu (ảnh chụp); một trace đăng ký trả phí trong Tempo; dừng RabbitMQ → alert audit failures và P1 `Firing`; chạy lại → `Normal`. Ghi rõ phiên bản image, thời điểm, người chạy.

## 7. M-38 — điều tra có giới hạn (sau Q17)

- [ ] **7.1** `[→ M-38]` Tối đa một buổi: chạy `NotificationDeliveryIT#fanOutWithAChannelFailingMidway` lặp lại dưới điều kiện giống CI (CPU giới hạn), đọc log/trace mới. Tìm ra → sửa + 10 lần xanh → `Passing`; không → ghi giả thuyết đã loại vào `test-matrix.md` M-38 và `phase-6.md` §5, giữ `Flaky — investigate`.

## 8. CI/CD

- [ ] **8.1** `integration-test` xanh với IT mới. Đo thời gian job; tăng thì ghi lại, **không** `@Disabled`/bỏ test INV nào (`test-plan.md` §2).
- [ ] **8.2** Không test nào flaky: `AuditOutageIT`, `AuditAlertIT`, `CorrelationPropagationIT`, `TracingIT` ≥ 10 lần liên tiếp ở local dưới JDK 21 (`cleanIntegrationTest` mỗi vòng), báo cáo XML từng vòng.
- [ ] **8.3** `contract-diff`, `web`, `mobile`, Gitleaks xanh (không mật khẩu Grafana nào trong repo); báo cáo OWASP: delta do dependency mới (Q18) ghi vào `phase-6.md` §5.

## 9. Đồng bộ tài liệu & Test Matrix

- [ ] **9.1** Sau mỗi task ở Mục 1–7: cập nhật dòng tương ứng trong `test-matrix.md` §2.2 (Status, `Class#method` thật).
- [ ] **9.2** `test-matrix.md` §4.1, §4.2 (gỡ `⚠` của M-54, M-56, M-57), §4.5 (M-17, alert, audit publishing), §5; đếm lại từ cột Status.
- [ ] **9.3** `observability.md` (§1, §2, §4, §6, §7, mục stack cục bộ, mục dashboard), `messaging.md` §2–§3, `database-schema.md` §8.3 + §10 (`V8`), `backend-coding-standards.md` §12–§13, `resilience-testing.md` §2.7, `deployment-and-cicd.md` (Q1, Q2, C43), `test-plan.md` §1 (P1), `IMPLEMENTATION-READINESS.md` (B3), `integration-testing.md` (dòng INV-14).
- [ ] **9.4** "Project Status" trong `CLAUDE.md`.

## 10. Đóng Phase 6 (Exit / Sign-off)

Exit criteria của `test-plan.md` §1 dòng Phase 6, đọc đúng chữ:

- [ ] Test propagation pass qua **ít nhất một bước async** (M-53).
- [ ] Test outage audit (INV-14) chứng minh nghiệp vụ vẫn thành công **và** lỗi publish audit hiện thành cảnh báo vận hành, không biến mất lặng lẽ (M-55; theo hình thức bằng chứng đã chốt ở Q10, giới hạn của nó ghi ở §5).
- [ ] M-54, M-56, M-57 `Passing` hoặc ghi ở §5 với lý do (dòng `⚠`, không mang INV).
- [ ] Coverage ≥ 80% (JaCoCo xanh), gồm adapter/consumer audit.
- [ ] CI xanh toàn bộ (chín job) trên **cùng một commit SHA**.
- [ ] Không `TODO`/`FIXME` trong test code; không `@Disabled` trên test của INV-14.
- [ ] Copy `phase-template.md` → `docs/testing/reports/release-sign-offs/phase-6.md`, điền §2–§5 với evidence thật; §5 gồm ít nhất: entry audit mất khi JVM crash (không đếm được, Q6), hạng mục audit thứ năm không có producer, gắn tag ở Ingress (Phase 7), kết quả Mục 7 (M-38), delta OWASP, các mục mang từ `phase-5.md` §5 chưa đóng.
- [ ] Owner ký §6 của `phase-6.md`.

> Phase 6 chỉ được coi là hoàn tất khi `phase-6.md` tồn tại, mọi ô được tick với evidence thật, và §6 đã ký — **không phải khi code chạy được**.

---

## Ghi chú cho Claude Code

- Khi được yêu cầu "tiếp tục Phase 6", đọc file này trước, tìm mục đầu tiên chưa tick theo thứ tự Mục 0 → 0B → 1 → … → 10, và làm mục đó.
- Mục 0 và 0B xong 2026-11-02 (D63–D80, C34–C44). Bắt đầu từ Mục 1. Nếu owner đổi một quyết định: sửa dòng Q/D tương ứng và tài liệu sở hữu trước, rồi mới code.
- Không nới INV-14 để qua một bài test: nếu một phương án có vẻ cần audit đi qua outbox, publish đồng bộ không giới hạn thời gian, hoặc nuốt lỗi publish mà không đếm, dừng lại và nêu ra.
- Không bật thu header/body/giá trị bind trên span "cho dễ debug" (`observability.md` §2 guardrail).
- Không tự ý tick một mục — chỉ tick sau khi test thật sự pass và `test-matrix.md` đã được cập nhật tương ứng.

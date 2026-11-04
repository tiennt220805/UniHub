# Phase 6 — Kiểm tay stack quan sát bằng docker-compose (task 6.5)

> **Mục đích.** Các IT của Phase 6 chứng minh từng mảnh (`AuditAlertIT` nạp rule vào một Grafana thật; `ObservabilityProvisioningIT` kiểm tên metric; `TracingIT` kiểm span). Chúng **không** chứng minh được một người mở stack của dự án lên và thấy nó chạy: dashboard có dữ liệu, một trace trong Tempo, cảnh báo bật và tắt. Đó là việc của lần chạy này (D78, D72: "chỉ là bằng chứng dashboard của M-57").
> **Một ô trống là một khoảng trống, không phải một lần đạt.** Những gì chưa làm được nằm ở mục "Chưa làm được" bên dưới; sign-off Phase 6 phải ghi lại chúng.

## Ai chạy, khi nào, ở đâu

| | |
|---|---|
| Người chạy | **Claude Code (trợ lý), trên máy và theo yêu cầu của Nguyễn Thành Tiến** ("setup và chạy trên máy của tôi"). Chủ máy chưa xem lại từng bước; không phải chữ ký của chủ máy. |
| Thời điểm | 2026-11-04, 21:20–21:36 (UTC+7) |
| Máy | MacBook của chủ dự án, macOS (Darwin 25.6), Docker Desktop, JDK 21 (Temurin) |
| Mã | commit `2ffee25` + thay đổi chưa commit của Mục 6 (rule, dashboard, `datasources.yml`, cờ exemplar trong compose) |

### Phiên bản

| Thành phần | Phiên bản |
|---|---|
| Prometheus | `prom/prometheus:v3.15.0`, chạy với `--enable-feature=exemplar-storage` |
| Grafana | `grafana/grafana:13.2.4` (anonymous Viewer; dùng `admin`/`admin` mặc định cho API) |
| Tempo | `grafana/tempo:2.10.8` |
| RabbitMQ | `rabbitmq:3.13.7-management`, container được **tạo lại** để mount `prometheus.conf` |
| Postgres / Redis / Mailpit / MinIO | `postgres:16-alpine` / `redis:7` / `axllent/mailpit:v1.20.7` / `cgr.dev/chainguard/minio` (digest trong compose) — container đang chạy sẵn, không đụng |
| Trình duyệt chụp ảnh | Google Chrome headless, vào Grafana với tư cách anonymous Viewer |

## Điều khác với kịch bản gốc của 6.5 (đã làm, ghi để biết)

1. **Cổng quản trị 18081, không phải 8081.** Cổng 8081 đang do Expo Metro (`apps/mobile`) giữ — đúng điểm lệch (5) đã ghi ở Mục 1. Backend chạy với `MANAGEMENT_SERVER_PORT=18081` và `observability/prometheus/prometheus.compose.yml` được sửa tạm sang `host.docker.internal:18081`, **đã revert** sau khi chạy.
2. **Cổng thanh toán là một stub Python cục bộ**, không phải Stripe sandbox (máy không có khóa Stripe). Nó trả hai câu trả lời mà WireMock của các IT trả (tạo và confirm PaymentIntent). Vì vậy span `unihub.stripe.request` trong lần chạy này đo thời gian của stub, không phải của Stripe.
3. **Lấy mẫu trace 100%** (`MANAGEMENT_TRACING_SAMPLING_PROBABILITY=1.0`) và `MANAGEMENT_OTLP_TRACING_ENDPOINT=http://localhost:4318/v1/traces`, để trace nào cũng vào Tempo. Mặc định của dự án là 10%.
4. **Áp migration V8** lên DB dev (`audit_events.source_message_id`, migration cộng thêm đã qua CI). Backend cần nó cho consumer audit.
5. **Ba container bị tạo lại** bởi `docker compose up`: **RabbitMQ** (không volume; trước đó có 35 queue, tất cả 0 message nên không mất message nào; để mount `prometheus.conf`), **Prometheus** (đổi command để bật exemplar; volume TSDB còn) và **Grafana** (không có volume, nên mọi trạng thái đặt trong giao diện, ví dụ mật khẩu admin đã đổi, mất; Grafana chỉ được cấu hình bằng file provisioning). Tempo được khởi động lại, không tạo lại.
6. **Dữ liệu thử** (tiền tố `RUN65`: 2 workshop, 5 sinh viên, 5 đăng ký, 1 thanh toán, 10 thông báo, 4 audit event, 6 idempotency key) đã **xóa sạch** khỏi Postgres; 5 thư gửi tới `run65-*` đã xóa khỏi Mailpit (133 thư của chủ máy còn nguyên); hàng đợi `hold-delay` của lần chạy đã purge. Bucket MinIO `unihub-brochures` do backend tự tạo (đúng cấu hình dev) vẫn còn.
7. **Trả máy về như cũ:** Prometheus, Grafana, Tempo đã dừng lại (trước đó chúng ở trạng thái `Exited`); RabbitMQ để chạy (đã tạo lại).

## Kết quả

### 1. Target Prometheus

Prometheus báo cả hai target `up`: `unihub-backend` (`host.docker.internal:18081/actuator/prometheus`) và `rabbitmq` (`rabbitmq:15692/metrics`). **Đạt.**

### 2. Ba dashboard có dữ liệu

Ảnh chụp (Grafana thật, 15 phút gần nhất, ngay sau khi có traffic): [`api-jvm.png`](phase-6-observability-checks/api-jvm.png), [`business.png`](phase-6-observability-checks/business.png), [`messaging-audit.png`](phase-6-observability-checks/messaging-audit.png). Cộng thêm một phép đếm qua proxy datasource của Grafana (`/api/ds/query`, từng truy vấn của từng panel):

| Dashboard | Panel có dữ liệu | Panel rỗng |
|---|---|---|
| API and JVM | 7 / 8 | `5xx ratio` — chưa có lỗi 5xx nào xảy ra (đúng) |
| Business | 9 / 11 | `Legacy sync quarantine ratio` — NaN cho tới lần sync đầu; `Replica lag` — NaN trên primary (cả hai đúng theo thiết kế, §4) |
| Messaging and audit | 9 / 9 | — |

**Đạt**, với ba panel rỗng đúng thiết kế. Lần chụp đầu lộ một lỗi trình bày: panel `Payment circuit breaker` có trục −1…0 vì resilience4j báo `failure_rate = -1` khi chưa đủ số lời gọi tối thiểu; đã sửa (`clamp_min(…, 0)`) và chụp lại `business.png`.

### 3. Một trace đăng ký trả phí trong Tempo

Một đăng ký có phí (workshop 150.000 VND) và request `POST /api/v1/payments/charge` trả `201`, `X-Correlation-Id` = `6735322e07a75579d1d36202da94fb01`. `GET /api/traces/6735322e…` của Tempo trả **30 span**:

| Span | Số lượng |
|---|---|
| `http post /api/v1/payments/charge` (SERVER) | 1 |
| `query` (CLIENT, JDBC) | 17 |
| `unihub.stripe.request` | 2 (tạo và confirm) |
| `unihub.events/unihub.system.audit.created send` / `unihub.audit.queue receive` | 2 / 2 |
| spring security (`authenticate`, `authorize`, `secured request`, `security filterchain`) | 6 |

Consumer của message `charge.succeeded` nằm ở trace **khác** (`be7d2247…`, gốc `task event-relay-scheduler.run`), nối với request bằng cùng `correlationId`: `event_publications.correlation_id` của `charge.succeeded` và `ticket.confirmed` đều là `6735322e…`. Đăng ký chuyển `RESERVED` → `CONFIRMED` trong vài giây. **Đạt**, đúng hình dạng mà `TracingIT` khẳng định.

### 4. Exemplar (hai điểm 6.3 còn bỏ ngỏ)

`GET /api/v1/query_exemplars?query=http_server_requests_seconds_bucket` của Prometheus trả 4 chuỗi có exemplar, mỗi cái có nhãn `trace_id` và `span_id`. Tức là Prometheus 3.15 **thương lượng được OpenMetrics với Boot 3.3.5** và **lưu được exemplar** khi có `--enable-feature=exemplar-storage`. Ảnh `api-jvm.png` thấy các chấm hồng của exemplar trên heatmap latency. **Đạt** cho phần Prometheus. Chưa làm: bấm một chấm trên giao diện để nhảy sang Tempo (chỉ kiểm bằng API rằng `trace_id` của exemplar có thể tra trong Tempo).

### 5. Dừng RabbitMQ → cảnh báo bật; chạy lại → về Normal

Mốc thời gian (chi tiết từng 15 giây: [`alert-timeline.log`](phase-6-observability-checks/alert-timeline.log)). Cả 22 rule nạp, 0 rule lỗi, 0 rule nào ngoài ba rule dưới đây bật ở bất kỳ thời điểm nào.

| Việc | Mốc |
|---|---|
| `docker stop unihub-rabbitmq` | 21:30:14 |
| Ba đăng ký trong lúc broker chết | `201` mỗi cái, **0,02–0,03 s** (INV-14: thao tác nghiệp vụ không bị chặn) |
| `unihub-audit-publish-failures` → **Firing** | sau 31 s |
| `unihub-dependency-down` (P1) → **Firing** | sau 92 s (rule có `for: 1m`) |
| `unihub-rabbitmq-target-down` → **Firing** | sau 92 s |
| `docker start unihub-rabbitmq` | 21:31:45 |
| `unihub-dependency-down` → Normal | sau 45 s |
| `unihub-rabbitmq-target-down` → Normal | sau 60 s |
| `unihub-audit-publish-failures` → Normal | sau 226 s (rule xét `increase(...[5m])` nên còn bật tới khi lần mất cuối trượt khỏi cửa sổ 5 phút) |

`unihub-parking-lot` không bật. **Đạt.**

## Chưa làm được

- **Không có ảnh chụp của giao diện cảnh báo lúc `Firing`.** Bằng chứng cho phần cảnh báo là API của Grafana và log mốc thời gian, không phải ảnh.
- **Chưa bấm exemplar trên giao diện để nhảy sang Tempo** (xem mục 4).
- **Stripe là stub**, không phải sandbox thật (xem trên).
- **Người chạy không phải chủ máy.** Chủ máy cần xem ảnh và quyết có chấp nhận lần chạy này làm bằng chứng của 6.5, như đã làm với M-44.
- Chưa đo ảnh hưởng của tải thật (lần chạy chỉ vài chục request).

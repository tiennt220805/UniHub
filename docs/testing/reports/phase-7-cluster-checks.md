# Phase 7 — Kiểm tay trên cluster Minikube (task 5.1, 5.3, 5.6, 5.7, 6.1, 6.3, 6.4, 7.1–7.6, 8.1–8.6)

> **Mục đích.** Các IT và test tĩnh của Phase 7 chứng minh từng mảnh (manifest khớp mã, stub trả đúng cái adapter gọi, migration tương thích). Chúng **không** chứng minh rằng các manifest chạy được trong một cluster có Istio. Tài liệu này ghi những gì đã chạy trên cluster, bằng chứng của từng bước, và những gì **chưa** chạy.
> **Đây là các bản luyện tập:** task 5.x và 6.x chạy dưới `PeerAuthentication` PERMISSIVE (riêng `backend-api` và `rabbitmq` STRICT từ 6.1), task 7.5–7.6 dưới STRICT trong cả hai namespace nhưng trên cluster đã dùng từ 5.7, không phải một lần deploy mới. Bản bằng chứng chính thức chạy lại ở Mục 10, dưới STRICT, trên một lần deploy mới (D96). Một ô trống là một khoảng trống, không phải một lần đạt.

## Ai chạy, khi nào, ở đâu

| | |
|---|---|
| Người chạy | **Claude Code (trợ lý), trên máy và theo yêu cầu của Nguyễn Thành Tiến** ("Tôi vừa nâng Docker lên 12GB, bạn hãy triển khai cho tôi"). Chủ máy chưa xem lại từng bước; đây **không** phải chữ ký chấp nhận của chủ máy (D102): M-60 và M-66 vì thế chưa `Passing`. |
| Thời điểm | 2026-11-06, khoảng 08:40–09:30 (UTC+7) |
| Máy | MacBook của chủ dự án (Apple Silicon, `arm64`), macOS, Docker Desktop 27.4.0 với 12 529 770 496 byte (11,67 GiB) và 8 CPU |
| Mã | commit `1cf9499` (CI run [42071356615](https://github.com/tiennt220805/UniHub/actions/runs/42071356615), mười job xanh); tag image là SHA đầy đủ của commit đó |
| Việc làm trước | Dừng (không xóa) compose `unihub-dev` để nhường bộ nhớ: `docker compose stop`, năm volume còn nguyên. Compose chưa được bật lại. |

### Phiên bản thật (đọc từ cluster, không từ tài liệu)

| Thành phần | Phiên bản |
|---|---|
| Minikube | v1.38.1, profile `unihub`, driver `docker`, `--memory=10g --cpus=6` (container node: `HostConfig.Memory` = 10 737 418 240 byte, 6 CPU) |
| Kubernetes | server **v1.35.1**; `kubectl` client v1.36.2 (Kustomize v5.8.1); runtime của node: Docker Engine `docker://29.2.1` (qua cri-dockerd), kernel 6.10.14-linuxkit; **allocatable 8 CPU / 12 236 104 KiB là tổng của VM Docker, không phải giới hạn 10 GiB của container node** (kubelet đọc bộ nhớ của VM): scheduler sẽ nhận pod tới 11,67 GiB, vượt trần thật |
| Istio | `istioctl` client, control plane và data plane **1.31.3** (bản 1.31 mới nhất đã phát hành ≥ 2 tuần: 2026-10-22), profile `default`, SHA256 của bản tải đã kiểm với file `.sha256` của release |
| Image ứng dụng | `thanhtien2208/unihub-backend@sha256:bc63dcf0a43d079d79128a3ef9ac6c172c0fd4b6f6d7af7bbb53d0352ed4675d`, `thanhtien2208/unihub-web@sha256:a81d2f2b0c90ed7e98fa7ac8144b8eb25787fd7691ecc993385beb4c2db94204` |
| Phụ thuộc | `postgres:16-alpine`, `redis:7`, `rabbitmq:3.13.7-management`, `axllent/mailpit:v1.20.7`, MinIO (digest của compose), `wiremock/wiremock:3.13.2`, `flyway/flyway:13.8.0-alpine` |

## Kết quả

### 5.1 — Dựng cluster (`k8s/scripts/cluster-up.sh`, `make cluster-up`)

- **AC81.1 đạt:** `istioctl x precheck` → "No issues found when checking the cluster. Istio is safe to install or upgrade!" (chạy trước khi cài).
- **AC81.2 đạt, bằng một pod thật được inject** (không suy từ mặc định): `istio-proxy` nằm trong `initContainers` với `restartPolicy: Always`. Cài **bản mặc định, không đặt `ENABLE_NATIVE_SIDECARS`**: ở Istio 1.31.3 trên Kubernetes 1.35.1 sidecar gốc bật sẵn. Câu hỏi mở của Q1 có đáp án.
- Thời gian: Minikube + Istio + kiểm tra khoảng 5 phút lần đầu (kể cả tải image của node). Script chạy lại được (giữ cluster đang chạy, không cài lại Istio cùng bản): đã chạy ba lần.
- Hai lỗi của chính script bắt được khi chạy, đã sửa: `minikube version | head -1` làm `pipefail` thoát 141 (SIGPIPE); namespace thăm dò còn đang bị xóa từ lần chạy trước từ chối pod mới.

### 5.7 — `make deploy-dev` rồi `make deploy-loadtest` trên cluster mới (PERMISSIVE)

- `make deploy-dev TAG=1cf9499…`: **thành công ngay lần đầu**, 7 phút 46 giây (gồm kéo toàn bộ image). `check-images` in digest hai image; Job Flyway `Complete` (**AC94.1 dưới PERMISSIVE**: pod Job có `istio-init istio-proxy | flyway`, `0/2 Completed`), "Successfully applied 10 migrations … now at version v8"; mười pod `Running`, **0 lần restart**.
- `make deploy-loadtest TAG=1cf9499…`: thành công, 2 phút 17 giây (ConfigMap mới nên API cuốn chiếu; 3 API, replica Postgres 1). Toàn cluster: **0 container có restart hoặc bản ghi termination, không `OOMKilled`**.
- **AC84.2 đạt:** `imageID` của container `backend-api` là `sha256:bc63dcf0…675d` và của `web` là `sha256:a81d2f2b…4204` — đúng digest của **index** đa kiến trúc mà CI đã push (không phải digest của manifest `arm64` `3e3fed28…`/`f5e1a647…`: runtime Docker của node báo digest của index trong `imageID`). Image không được `minikube image load` mà kéo từ Docker Hub.
- **Staff/Student qua gateway (AC86.3, `k8s/scripts/journey.sh`):** bốn tài khoản seeder đăng nhập với quyền của role — Student `REGISTRATION_CREATE`; Organizer và Admin `REPORT_VIEW, WORKSHOP_CREATE, WORKSHOP_EDIT`; Check-in Staff `CHECKIN_SCAN`; mật khẩu sai → 401. Organizer tạo workshop có phí (`DRAFT`), publish (200); Student đăng ký (`RESERVED`), `charge` qua stub WireMock → `SUCCEEDED`, `gatewayTransactionId` dạng `pi_<uuid>`; đăng ký `CONFIRMED` sau 2 giây; một email "Xác nhận đăng ký" nằm trong Mailpit. Chạy hai lần liên tiếp.
- Seeder: log "Dev Student seeding done: 3 created" và "Dev staff seeding done: 3 created".

### 5.3 — Replica trong cluster đã inject (M-60, INV-03)

- **Thứ tự init container của `postgres-replica-0`: `istio-init istio-proxy basebackup`** — `basebackup` chạy sau khi proxy lên, nên không treo chờ Master (đây là điều kiện D94 cho init container của replica).
- `pg_stat_replication` trên Master: `walreceiver | streaming | async`; `pg_is_in_recovery()` trên replica là `t`.
- **Ứng dụng đọc ở replica:** `loadtest` trỏ `UNIHUB_DATASOURCE_REPLICA_URL` vào `postgres-replica` (và `dev` vào `postgres-master`, đã kiểm trong ConfigMap đã áp). Gọi `GET /api/v1/workshops/{id}/report` sáu lần qua gateway làm tổng lượt quét của các bảng `workshops`/`registrations`/`attendances`/`check_in_scans` **trên replica tăng 12, trên Master tăng 1**; API giữ 30 kết nối tới replica. (Mức tăng 1 ở Master là một lần đọc khác; chưa tách nguồn của nó.)
- **Xóa `postgres-master-0`:** pod mới (`uid` đổi từ `57f9b4df…` sang `504b4743…`) **cùng tên, cùng PVC `data-postgres-master-0` với cùng `uid` `ec49583d…` và cùng volume**; sẵn sàng sau 4 giây (pod tạo 02:02:36, điều kiện `Ready` chuyển 02:02:40 UTC; đọc từ pod, không từ `rollout status`); dữ liệu còn nguyên (6 user, 1 đăng ký, 10 dòng `flyway_schema_history` trước và sau); replication tự nối lại (`streaming`); Student đăng nhập lại được và đăng ký vẫn `CONFIRMED`. Dữ liệu kiểm là dữ liệu ứng dụng tự tạo, không ghi dòng thử bằng `psql`.

### 5.6 — Gateway, TLS (D83, D97)

- `k8s/scripts/make-local-ca.sh --apply`: CA cục bộ + chứng chỉ `unihub.local` (SAN) + Secret `unihub-tls` trong `istio-system`. `istioctl proxy-config secret` trên gateway: `kubernetes://unihub-tls` `ACTIVE`, `VALID CERT true`, hết hạn 2027-11-06.
- Qua `kubectl port-forward` tới `istio-ingressgateway` (không cần sudo), `curl --resolve unihub.local:8443:127.0.0.1 --cacert k8s/.local-ca/ca.crt`: `GET /` → HTTP/2 200 **kèm `Content-Security-Policy` có `frame-ancestors 'none'` (AC85.1)**; `GET /api/v1/workshops` → 200 JSON với `x-correlation-id`; **HTTP thường → `301` tới `https://…`, không bao giờ JSON (AC97.1)**; TLS 1.3, `Verify return code: 0 (ok)` với CA cục bộ.
- **Cổng quản trị không ra ngoài:** `/actuator/health` qua gateway trả **shell HTML của Web** (nginx trả SPA cho mọi đường dẫn không bắt đầu bằng `/api/`), không phải JSON của actuator; `/api/actuator/health` → 401 từ API; Service `backend-api-service` chỉ công bố 8080.
- `istioctl analyze -n unihub`: "No validation issues found". Chỉ có một `PeerAuthentication` (`unihub/default`, PERMISSIVE): **không có ngoại lệ cho hop gateway → pod** (AC83.2 về phía cấu hình; mTLS thật của hop đó là `check-mtls.sh`, Mục 7).

### 6.1 — Stack observability trong cluster (`k8s/observability/`, `make deploy-observability`)

*Chạy 2026-11-06, buổi chiều (UTC+7), cùng người chạy và cùng điều kiện như trên; chạy trên cluster `loadtest` đang có, image `1cf9499` (của lần 5.7); lần diễn tập P1 (6.4) chạy sau khi đổi sang image `a9c1a8f`.*

- **Triển khai:** `make deploy-observability` → namespace `unihub-observability` (nhãn `istio-injection: enabled`), ba pod `2/2 Running` (ứng dụng + `istio-proxy` là **sidecar gốc**, ở `initContainers` với `restartPolicy: Always`). Image là của docker-compose (`prom/prometheus:v3.15.0`, `grafana/tempo:2.10.8`, `grafana/grafana:13.2.4`). Bộ nhớ lúc rảnh: Grafana 378 MiB, Prometheus 102 MiB, Tempo 72 MiB; **cả container node 5,4 GiB / 10 GiB (54%)** với `loadtest` + stack, **không `OOMKilled`, không pod nào restart** — điều kiện dừng D82 (kể cả OOM của chính container node) không bị chạm. Giới hạn bộ nhớ của Grafana nâng từ 512 Mi lên 768 Mi (378 Mi là 74% của 512).
- **Chứng chỉ của sidecar ra volume dùng chung:** `/etc/istio-certs` trong container Prometheus có `cert-chain.pem`, `key.pem`, `root-cert.pem` (`OUTPUT_CERTS` + `sidecar.istio.io/userVolumeMount` **chạy được với sidecar gốc**).
- **Trình tự tìm ra (đây là phát hiện, không phải lý thuyết) — scrape mTLS bằng chứng chỉ của sidecar trên Istio 1.31.3:**

| Trạng thái của target | Kết quả scrape | Cách nhìn thấy |
|---|---|---|
| Namespace `unihub` **PERMISSIVE**, scrape `https` | **DOWN**: API `server gave HTTP response to HTTPS client`, broker `connection reset by peer` | `/api/v1/targets` của Prometheus. Istio chuyển một kết nối TLS **không mang ALPN của Istio** thẳng cho ứng dụng; chỉ nơi pod là STRICT thì kết nối mới được kết thúc như mTLS |
| `backend-api`, `rabbitmq` **STRICT**, Prometheus bật HTTP/2 (mặc định) | API **DOWN** `unexpected EOF`; broker **UP** | Cổng quản lý 8081 của API không nằm trong Service nào nên sidecar chuyển luồng đã giải mã thành TCP thô và Tomcat đóng kết nối HTTP/2. `curl --http1.1` từ `istio-proxy` của Prometheus: `200`; không có `--http1.1`: đóng kết nối |
| **STRICT** + `enable_http2: false` | **Cả bốn target `UP`** (3 API + broker) | `/api/v1/targets`; ở sidecar của API: `istio_tcp_sent_bytes_total{source_principal="spiffe://cluster.local/ns/unihub-observability/sa/prometheus", connection_security_policy="mutual_tls"}` = 135 702 byte (trước khi sửa: 0) — **scrape đi bằng mTLS với danh tính của Prometheus** |

  Điều kiện dừng của D95 (Prometheus không scrape được bằng chứng chỉ của sidecar) **không bị chạm**: scrape chạy trên 1.31, chỉ cần đích là STRICT. Quyết định (ủy quyền, chờ chủ dự án xác nhận): `k8s/base/peer-authentication-scraped.yaml` — hai `PeerAuthentication` **STRICT theo nhãn cho `backend-api` và `rabbitmq`, không `portLevelMtls`**: siết chặt `default` PERMISSIVE, không phải ngoại lệ của STRICT; 7.5 làm `default` STRICT và xóa file này. Test tĩnh ghim "không PeerAuthentication nào có `portLevelMtls`, trừ `default` đều STRICT" (AC95.2). Hai bản thăm dò đặt tay lúc tìm ra (`probe-strict-*`) đã xóa; cluster khớp với git.
- **Ảnh hưởng của hai STRICT lên phần còn lại (kiểm ngay):** `k8s/scripts/journey.sh` qua gateway xanh (bốn tài khoản đăng nhập đúng quyền, tạo và publish workshop, đăng ký, thanh toán qua stub, `CONFIRMED` sau 1 s) — **hop gateway → `backend-api` giờ bắt buộc mTLS và vẫn chạy** (bằng chứng P2 sớm, một phần: bằng chứng chính thức là 7.2); API ↔ RabbitMQ chạy (consumer vẫn xác nhận đăng ký). Probe kubelet của API (viết lại qua sidecar) vẫn xanh.
- **Grafana:** nạp **25 rule** (22 của Phase 6, `unihub-rate-limit-bypassed` và hai rule mới của 6.2), kiểm bằng API `/api/prometheus/grafana/api/v1/rules` (một lỗi provisioning sẽ dừng Grafana lặng lẽ); `unihub-app-target-down` và `unihub-app-restarted` `inactive`/`ok` khi cả bốn target UP.
- **Một quan sát có ích về 6.2:** trước khi đổi image, rule `Replica lag above 10 s` **đang `Firing`** trên `loadtest` (ba instance, primary rảnh, `unihub_postgres_replica_lag_seconds` tăng mãi): đúng lỗi mà công thức mới sửa. Ảnh chụp trước khi sửa: `replica-lag-firing-before-fix.png` (ở thư mục tạm của phiên, không commit).

### 6.3 — Tracing ở gateway (D98)

- `k8s/istio/install.yaml` (IstioOperator: profile `default` + provider `opentelemetry` tên `tempo`, gRPC 4317) được `cluster-up.sh` đưa cho `istioctl install -f` (chạy lại trên cluster đang chạy: istiod và gateway "installed", ConfigMap `istio` có `extensionProviders`); `k8s/observability/gateway-tracing.yaml` là `Telemetry` ở `istio-system`, **selector chỉ gateway**, 10%.
- **Đo:** 60 request tới `/api/v1/workshops` qua gateway → 60 `X-Correlation-Id` khác nhau; **8 trong 60 (13%) là một trace trong Tempo** (52 trả 404, đúng với lấy mẫu 10% chứ không phải 100%). Một trace được lấy ra: `istio-ingressgateway.istio-system` có span `backend-api-service.unihub.svc.cluster.local:8080/api/*` (gốc) và span `router outbound|8080||backend-api-service…` (con); span `http get /api/v1/workshops` **của ứng dụng là con của span router của gateway** — **AC98.1 đạt**: `X-Correlation-Id` của response = trace ID có span của gateway trong Tempo.
- **Phần còn lại của câu hỏi (request không được lấy mẫu có `traceparent` không?)**, đo bằng cách tạm thời trỏ một `VirtualService` thăm dò (đã xóa) vào stub WireMock, mà journal của nó ghi header nhận được: **100 trong 100 request đều tới đích với `traceparent`** (+ `tracestate`, `x-request-id`), **8 trong đó gắn cờ sampled (`-01`)**. Envoy sinh trace ID cho **mọi** request, không chỉ những request được lấy mẫu; vậy correlation ID là trace ID do Ingress sinh cho tất cả, và yêu cầu `requirement.md` §3.1 ("tagged at the Ingress") được đáp ứng đầy đủ, không chỉ trên 10% mẫu.
- **Quan sát khác (có từ Phase 6, không do 6.3):** span của ứng dụng trong Tempo mang `service.name` là `unknown_service` (Spring không đặt `spring.application.name`), nên ở Tempo có hai dịch vụ — gateway và `unknown_service`. Không sửa trong task này; nêu để chủ dự án quyết định.

### 6.4 — P1 trên cluster (`unihub-dependency-down`), bản luyện tập dưới PERMISSIVE

*Chạy 2026-11-06 03:12–03:23 UTC (10:12–10:23 UTC+7), cùng người chạy và máy như trên, bằng kịch bản tạm ngoài repository (`smoke.sh` là Mục 9). Mã: **image `a9c1a8f`** (CI [42150946786](https://github.com/tiennt220805/UniHub/actions/runs/42150946786), mười job xanh; `imageID` của pod là `sha256:b4e10a52…`), ba API pod của `overlays/loadtest`; manifest observability của `c94c76f`. Chưa có traffic: không ai gọi API trong lúc thử.*

| | Redis `scale 0` | RabbitMQ `scale 0` |
|---|---|---|
| `unihub-dependency-down` `Pending` | +51 s | +41 s |
| **`Firing`** (ba instance, một mỗi API pod, nhãn `dependency` đúng) | **+112 s** | **+101 s** |
| Giữ mất kết nối | 244 s | 243 s |
| Pod API trong lúc mất | **3/3 `2/2 Ready`**, readiness HTTP **200** | giống |
| `GET /actuator/health/dependencies` | `DEGRADED`, `redis: ping refused` | `DEGRADED`, `rabbit: AmqpIOException` |
| Rule khác đổi trạng thái | không | `unihub-rabbitmq-target-down` `Pending` → **`Firing`** (xem dưới) |
| Khôi phục (`scale 1`) → `Normal` | **+45 s** sau khôi phục | **+98 s** sau khôi phục |
| Pod API sau khi khôi phục | 3/3 Ready, `dependencies` `UP` | giống |

- **Ảnh chụp (Grafana thật, trình duyệt headless, lúc đang `Firing`)** — phần Phase 6 còn thiếu: [`redis-firing.png`](phase-7-cluster-checks/redis-firing.png) (`Redis or RabbitMQ down`, 3 instances), [`rabbitmq-firing.png`](phase-7-cluster-checks/rabbitmq-firing.png) (cộng `RabbitMQ metrics target down`), và [`redis-normal.png`](phase-7-cluster-checks/redis-normal.png), [`rabbitmq-normal.png`](phase-7-cluster-checks/rabbitmq-normal.png) sau khôi phục; nhật ký có dấu thời gian: [`redis-drill.log`](phase-7-cluster-checks/redis-drill.log), [`rabbitmq-drill.log`](phase-7-cluster-checks/rabbitmq-drill.log).
- **Hiệu chỉnh một dự đoán (lỗ hổng tìm ra khi chạy, đã sửa trước lần chạy):** với service discovery theo pod, `scale 0` làm **chuỗi `up{job="rabbitmq"}` biến mất**, mà chuỗi vắng là "no data" và mọi rule đọc là OK — `unihub-rabbitmq-target-down` sẽ im. Biểu thức đã đổi thành `up{job="rabbitmq"} or on() vector(0)` (cùng cách rule của ứng dụng, `observability.md` §6, `ObservabilityProvisioningIT` ghim chữ); bằng chứng là rule đó `Firing` trong lần RabbitMQ. Phần P1 vốn không phụ thuộc điều này (nó đến từ gauge `unihub_dependency_up` do chính ứng dụng thăm dò).
- **Hai rule của 6.2 trên cluster thật:** `unihub-app-target-down` `Pending` rồi `Normal` khi rollout thay pod (đúng: target cũ biến mất lúc pod bị xóa); và **công thức replica lag mới** — trước khi đổi image rule `Replica lag above 10 s` `Firing` trên ba pod (xem `replica-lag-firing-before-fix.png`); sau khi đổi sang `a9c1a8f` gauge của cả ba pod đọc `0` và rule về `Normal` trong ~2 phút (không có `Firing` nào còn lại ở baseline của lần Redis).
- **Ồn có thể gặp ở mỗi lần rollout (quan sát, chưa xử lý):** khi image `a9c1a8f` được áp, `unihub-app-target-down` ở `Pending` khoảng 2 phút (03:10–03:12 UTC) rồi về `Normal`. Pod discovery tạo target ngay khi pod có IP, tức là trước khi ứng dụng lắng nghe cổng 8081 (lúc đó target `down` với `EOF`; thấy ở pod mới của lần rollout trước), và với `for: 1m` một lần kéo image lâu hơn ~1 phút (lần 5.7 đầu mất vài phút) sẽ làm rule `Critical` bắn trong một lần deploy bình thường. Chưa xử lý; phải quyết định trước 8.x (rehearsal M-58 và Mục 10 sẽ gặp): chỉ giữ pod đã `Ready` trong relabel (mất khả năng thấy pod crash-loop), hoặc chấp nhận và ghi nhận.
- **Sau `istioctl install -f` (cả lần tay và `make cluster-up` chạy lại):** ConfigMap `istio` vẫn giữ `defaultProviders.metrics: [prometheus]` và `enablePrometheusMerge`, sidecar vẫn xuất `istio_requests_total`; `make cluster-up` chạy lại thành công trên cluster đang chạy (đường `install -f` của script đã được thực thi, không chỉ lệnh tay).
- **Chưa kiểm:** `unihub-app-restarted` `Firing` khi Liveness giết container (AC99.2, kịch bản 7b — thuộc 8.x); `unihub-rate-limit-bypassed` với Redis tắt **có** traffic (không có traffic nên không bắn, như dự đoán); không cấu hình contact point (quyết định của chủ dự án, `observability.md` §6): P1 "có hiệu lực" theo D99 là rule được nạp, đánh giá, và `Firing` thấy được trong Grafana.
- **Đây là bản luyện tập dưới PERMISSIVE** (với hai workload được siết STRICT, 6.1): dòng P1 của `test-plan.md` §1 **không sửa** (*2026-11-08, Mục 12: đã ghi bằng chứng của Mục 10 vào ô P1, vẫn "NOT MET" tới khi chủ dự án chấp nhận; nếu chủ dự án muốn giữ đúng lời cũ, hoàn tác đúng ô đó*) — sửa khi chủ dự án chấp nhận và bản chạy lại ở Mục 10 dưới STRICT xong (D96, D102).

### 7.1–7.4 — P2, P3, P4 trước khi bật STRICT (namespace còn PERMISSIVE; `backend-api` và `rabbitmq` đã STRICT từ 6.1)

*Chạy 2026-11-06 ~13:00 (UTC+7), cùng người chạy và máy; cluster `loadtest`, image `a9c1a8f`.*

- **7.1:** cả 12 pod của `unihub` và 3 pod của `unihub-observability` có `istio-proxy` ở `initContainers` với `restartPolicy: Always` (sidecar gốc); mọi probe `httpGet` đã được Istio viết lại thành `/app-health/<container>/…` ở cổng 15020 của sidecar (Postgres, Redis, RabbitMQ dùng probe `exec` nên không đổi). Kiểm bằng `k8s/scripts/check-mesh.sh`.
- **7.2 (P2):** đọc ở sidecar của đích: `istio_requests_total{reporter="destination", source_workload="istio-ingressgateway"}` — `backend-api` và `web` đều **`connection_security_policy="mutual_tls"`** (3 và 1 chuỗi); không chuỗi nào khác.
- **7.3 (P3):** `make migrate` với namespace được inject: Job `Complete` trong 17 s, `istio-init`, `istio-proxy` và `flyway` đều `terminated` mã 0 (sidecar gốc kết thúc theo container chính, không `quitquitquit`); log Flyway `Schema "public" is up to date`. `postgres-replica-0`: `istio-init` → `istio-proxy` (chạy) → `basebackup` mã 0, `pg_stat_replication` `streaming`.
- **7.4 (P4):** cả bốn target Prometheus `UP` qua mTLS; datasource Prometheus và Tempo của Grafana `health OK`; `grafana → prometheus` (≈ 7 280 request) và `grafana → tempo` là `mutual_tls` ở sidecar của đích; WireMock (2), Mailpit và MinIO có sidecar (7.1).

### 7.5, 7.6 — STRICT trong cả hai namespace, S-19 (`k8s/scripts/check-mtls.sh`)

- **Đối chứng âm lần đầu, trước khi bật (`--expect permissive`, chỉ phủ các cổng chưa STRICT):** từ pod không inject, **mọi cổng trả lời plaintext trừ các cổng của `backend-api` và `rabbitmq`** (đang STRICT riêng từ 6.1): `web`, hai Postgres, Redis, MinIO, Mailpit (SMTP, UI), hai stub, Prometheus, Tempo (3200, 4318, 4317), Grafana trả 1–64 byte. Vậy phép thử phân biệt được hai chế độ (một lần đo đầu dùng `GET / HTTP/1.0` qua `nc` làm sai kết quả với cổng HTTP — Envoy trả `400` hoặc im lặng — nên phần HTTP dùng `wget`, đếm dòng trạng thái; chỉ một kết nối TCP được chấp nhận **không** chứng minh gì, vì Envoy chấp nhận rồi mới từ chối).
- **Đối chứng âm đầy đủ, sau khi bật (`check-mtls.sh --negative-control`)** — thêm sau khi advisor chỉ ra lần đầu chưa phủ cổng của `backend-api` (8080, 8081) và `rabbitmq` (đã STRICT riêng từ 6.1), nên "refused" ở đó chưa từng được chứng minh là khác với "không bao giờ trả lời": script áp một `PeerAuthentication` PERMISSIVE theo nhãn cho từng workload (chính sách theo workload thắng chính sách của namespace) trong đúng một lần chạy rồi xóa. Kết quả: **cả 19 cổng trả lời plaintext, kể cả 8081 và ba cổng RabbitMQ**, và đối chứng dương `min(up{job="unihub-backend"})` = **0** (API PERMISSIVE → scrape mTLS hỏng, đúng phát hiện của 6.1 được tái hiện); ngay sau đó chạy thường: **cả 19 từ chối** (0 byte), `min(up)` về 1. Grafana qua port-forward sau STRICT: 25 rule nạp, đều `inactive`/`ok`; ảnh chụp trình duyệt headless vẫn chạy. Mục 10 chạy lại đối chứng này bằng chính script (không có khoảnh khắc PERMISSIVE nào trong lần deploy mới).
- **Bật STRICT:** `base/peer-authentication.yaml` và `k8s/observability/peer-authentication.yaml` (mới) thành `STRICT`; `base/peer-authentication-scraped.yaml` xóa; hai object cũ `strict-*` xóa khỏi cluster. Không `portLevelMtls`, không `selector` (`ObservabilityManifestsTest#bothNamespacesAreStrict`).
- **`check-mtls.sh` dưới STRICT — `check-mtls: OK`:** 19 cổng (mọi cổng Service của hai namespace, cộng cổng quản lý 8081 của API theo địa chỉ pod, vì không Service nào công bố nó), mỗi cổng nói đúng giao thức của nó từ một pod ở namespace **không** inject: **cả 19 từ chối plaintext (0 byte)**; pod trong namespace có inject (`unihub-probe-mesh`) được trả lời ở mọi cổng trừ 8081 (không Service nào nên sidecar không biết cổng, gửi thô — đúng thiết kế; đối chứng dương của nó là `min(up{job="unihub-backend"}) = 1`, Prometheus scrape bằng chứng chỉ của chính nó). Cổng 80 và 443 công khai của gateway là biên của mesh, ngoài phạm vi.
- **Hệ quả của STRICT, kiểm ngay trên cluster:** (a) toàn bộ pod giữ `2/2 Ready`; (b) `k8s/scripts/journey.sh` xanh (đăng nhập bốn role, tạo và publish, đăng ký, thanh toán qua stub, `CONFIRMED` sau 1 s); (c) `make migrate` → Job `Complete`, sidecar thoát cùng (**AC94.1 dưới STRICT**); (d) **replica dựng lại từ volume trống** (xóa pod và PVC `data-postgres-replica-0`): `istio-init` → `istio-proxy` → `basebackup` mã 0 trong 2 s, `streaming` — `pg_basebackup` đi qua mesh dưới STRICT; (e) `rollout restart` của API: ba pod mới Ready, probe kubelet đi qua sidecar bình thường; (f) trace của gateway vẫn tới Tempo qua gRPC (6 trên 60 request thành trace, span gateway + span ứng dụng); (g) Prometheus vẫn scrape bằng chứng chỉ sidecar (bốn target `UP`); (h) `check-mesh.sh` OK.
- **Đây là bản luyện tập, chưa phải bằng chứng đã ký:** do Claude Code chạy, chưa có chấp nhận của chủ dự án (D102); M-63 là `Drafted`; bản được ký là lần chạy lại ở Mục 10 trên một lần deploy mới.

### 8.1, 8.2 — kịch bản 7 (xóa pod, đóng băng pod; `k8s/scripts/kill-pod.sh`)

*Chạy 2026-11-06 14:25–14:31 (UTC+7), `overlays/loadtest` (3 API pod), STRICT, image `a9c1a8f`; traffic thăm dò: `GET /api/v1/workshops` mỗi 0,2 s qua gateway, mỗi lần dùng token của một trong bốn tài khoản seed (limiter tính 100 request / 60 s mỗi user nên bốn user mang ~5 request/s mà không bị `429`).*

| | 7a — xóa pod (lần 1) | 7a (lần 2) | 7b — đóng băng (lần 1) | 7b (lần 2) |
|---|---|---|---|---|
| request thăm dò | 236 | 253 | 291 | 248 |
| `200` | 236 | 253 | 283 | 242 |
| **5xx** | **0** | **0** | 3 (`503`) | 2 (`503`) |
| lỗi kết nối / timeout client 3 s (`000`) | 0 | 0 | 5 | 4 |
| pod biến mất / rời rotation | gone T+8–9 s | gone T+9 s | `Ready=False` T+17–18 s | T+17 s |
| container restart tại chỗ (Liveness) | — | — | T+28–35 s (`restartCount` 0→1, 1→2) | T+28 s |
| Deployment / pod Ready lại | 3 pod Ready T+42 s | T+47 s | pod Ready bền T+64–69 s | T+64 s |
| `unihub-app-restarted` | không bắn (đúng: pod bị xóa là chuỗi mới) | không | **`Firing` T+78 s** (baseline `inactive`) | `Firing` sẵn từ lần 1 (cửa sổ 10 phút) |

- **7a đạt (M-59, D89):** hai lần, **0 phản hồi 5xx và 0 lỗi kết nối**; `preStop` + tắt máy êm làm việc đúng. Rule `unihub-app-target-down` chỉ `Pending` (pod thay thế chưa lắng nghe) rồi về `Normal`.
- **7b đạt theo định nghĩa của §2.8** (rời rotation, bị restart, traffic tiếp tục ở pod khác, thời gian đo được, `unihub-app-restarted` `Firing` — **AC99.2**; đóng băng bằng `docker pause` qua `minikube ssh`, không công tắc nào trong ứng dụng) **nhưng có hai khoảng lỗi đo được, không giấu:** (i) **~4–5 request timeout** (client 3 s) trong 17–18 s đầu: request tới pod đã đóng băng treo cho tới khi Readiness (3 lần × 5 s + timeout) gỡ nó khỏi rotation — một client không có timeout sẽ treo lâu hơn (Istio không đặt timeout route mặc định); (ii) **2–3 phản hồi `503` trong ~1 s lúc container được restart**: điều kiện `Ready` của pod thoáng `True` ngay khi container mới khởi động (lần 2, đọc mỗi giây: `Ready=False` T+17, `True` T+27, `False` T+28, rồi `True` bền từ T+64) nên gateway gửi request vào ứng dụng chưa lắng nghe và sidecar của pod trả `503`; các retry mặc định của Istio không che được. Đây là hành vi của kubelet với container đã restart (lần chạy đầu cho thấy cùng cửa sổ: sự kiện `Readiness probe failed … 500` 2 s sau `Started`), không phải của ứng dụng; chưa xử lý. **Phát hiện này nên được chủ dự án biết:** "0 5xx" của Q9 áp dụng cho 7a và đạt; 7b có một cửa sổ ~1 s mà hệ thống chưa che được.
- Cập nhật tài liệu: `resilience-testing.md` §2.8 ghi chu kỳ probe thật (Readiness 5 s, Liveness 10 s, `failureThreshold` 3 mỗi cái), không phải "10 s / 15 s".

### 8.3 — S-15, S-16 (a) qua gateway (`k8s/scripts/check-rate-limit.sh`) — **tìm ra một lỗi thật**

- **S-15 đạt:** sáu lần đăng ký liên tiếp của một user qua `https://unihub.local`: `201 409 409 409 409 429`, lần thứ sáu `429` kèm `Retry-After: 10`; sáu request do **ba pod** khác nhau phục vụ (đếm ở sidecar của từng pod) và ngân sách vẫn dùng chung (Redis).
- **S-16 (a), phần 1 đạt (điều kiện dừng của Q11 không bị chạm):** một route thăm dò tạm thời tới stub WireMock (journal ghi header nhận được): `X-Envoy-External-Address` do client gửi (`203.0.113.77`) **bị gateway ghi đè** (`127.0.0.1`, địa chỉ gateway thấy qua `port-forward`) và **tới được pod qua hop sidecar**; `X-Forwarded-For` tới pod là `<giá trị client>,<địa chỉ gateway>` (ứng dụng không đọc).
- **S-16 (a), phần 2 FAIL trên image `a9c1a8f`:** 130 request chưa xác thực trong một lần gửi, mỗi request một `X-Forwarded-For` khác: **130 trên 130 là `200`**, không `429`; các khóa Redis là `unihub:ratelimit:global-ip:198.51.100.N` — **limiter khóa theo `X-Forwarded-For` do client viết**. (Hai lần chạy đầu của script cho cùng kết quả vì một lỗi của chính script — mỗi `curl` riêng làm tốc độ ~1,4 req/s, dưới mức 100/60 s — nên đã gửi dồn trong một kết nối; chạy thường không kèm header giả cho 95 `200` rồi 155 `429`, tức limiter vẫn đúng với một địa chỉ cố định.)
- **Nguyên nhân gốc:** ứng dụng không đặt `server.forward-headers-strategy`, nên **trên Kubernetes Spring Boot tự nhận ra nền tảng và bật `native`** (Tomcat `RemoteIpValve`): valve thay `remoteAddr` bằng mục không riêng tư cuối cùng của `X-Forwarded-For` do client viết, **trước** `ClientAddress`, nên `127.0.0.6` và `X-Envoy-External-Address` không bao giờ được đọc. `RateLimitIT` (MockMvc, không Tomcat, không nền tảng cloud) không thể thấy; ranh giới INV-17 bị hở chỉ trên cluster. Đúng loại lỗi mà S-16 trên cluster có để bắt.
- **Sửa (`d509fda`):** `server.forward-headers-strategy: none` trong `application.yml` (bình luận nêu lý do); **`RateLimitForwardedHeadersIT`** khởi động server thật với `spring.main.cloud-platform=kubernetes` và gửi 130 request qua socket thật, mỗi request một `X-Forwarded-For`: **đỏ trước khi sửa** (`AssertionError`), xanh sau; `RateLimitIT` vẫn xanh.
- **Sau khi sửa, trên cluster** (image build cục bộ từ cây làm việc, nạp vào node bằng `minikube image load`, **chưa phải image CI**): S-15 vẫn đạt; S-16 (a) phần 2: **đúng 100 `200` rồi 30 `429`, cái đầu tiên ở request 101**; `check-rate-limit: OK`. Bằng chứng CI cho bản sửa **chưa có**: Gitleaks đỏ trên `d509fda` (xem "Điều cần chủ dự án quyết" ở cuối mục này), nên `build-and-push` bị chặn.

### 8.4 — diễn tập rollout, N-1 → HEAD (`k8s/scripts/rollout-rehearsal.sh`) — **phần schema đạt, phần topology AMQP là phát hiện**

*Chạy 2026-11-06 15:32 (UTC+7). N-1 = `a58e814` (cha của commit thêm migration V8; image dựng cục bộ từ `git archive` với Dockerfile của HEAD, vì N-1 không có image trên registry), HEAD = cây làm việc `d509fda`+ (image cục bộ). Phá hủy dữ liệu dev: API về 0, hai volume Postgres xóa, Redis và RabbitMQ restart.*

- **Cơ sở dữ liệu rỗng → V7** (Flyway `-target=7`, Job có inject, STRICT) → **N-1 khởi động** (3 pod) → **traffic thăm dò** (ba student, `GET /api/v1/workshops` mỗi 0,25 s).
- **Migration của HEAD chạy khi N-1 đang phục vụ — đạt (AC88.3):** Job → V8; **110 request, 0 không-2xx** trong lúc chạy và 20 s sau; 3 pod N-1 vẫn Ready trên V8; **đăng nhập `200`** trên schema đã migrate.
- **Rollout HEAD đè lên N-1 — KHÔNG hoàn tất:** pod HEAD **crash-loop** (Startup probe `500`, bị kubelet giết nhiều lần): `PRECONDITION_FAILED - inequivalent arg 'x-dead-letter-exchange' for queue 'unihub.audit.queue'`. Broker giữ hàng đợi do **N-1** khai báo (không `x-dead-letter-exchange`); HEAD khai báo nó **có** `x-dead-letter-exchange`, `x-max-length`, `x-overflow: reject-publish-dlx`, `x-dead-letter-routing-key` (commit `ab9cd91`, Phase 6 Mục 2, cùng commit với V8 — nên **cặp N-1/HEAD của M-58 luôn đi qua thay đổi này**). Đây là điều đã nhìn thấy một lần bằng tay ở Mục 4 ("AMQP topology không có expand/contract"); lần này đo được:
  - **Cái cứu:** Deployment giữ nguyên ba pod N-1 (`maxUnavailable` 25% của 3 làm tròn xuống 0): trong lúc rollout kẹt **706 request thăm dò, 0 không-2xx**. Rollout kẹt là **an toàn nhưng không bao giờ xong**.
  - **Biện pháp 1 (xóa hàng đợi khi pod N-1 còn chạy): không hiệu quả** — các consumer của N-1 khai báo lại hàng đợi cũ ngay.
  - **Biện pháp 2 (dừng N-1 trước, tức Recreate): hiệu quả nhưng là sự cố ngừng dịch vụ** — HEAD Ready sau **85 s** kể từ lúc N-1 dừng; probe trong lúc đó: **236 `503`** (gateway không có pod).
  - Sau cùng: hàng đợi có đủ bốn tham số mới, không còn `PRECONDITION_FAILED`, `unihub_audit_publish_failures_total` = 0.
- **Kết luận có giới hạn:** (i) `INV-15` ở mức **schema** được chứng minh trên cluster (Job dưới STRICT + sidecar, N-1 đọc/ghi trên schema mới, 0 lỗi). (ii) **Một thay đổi tham số của hàng đợi AMQP không đi được qua rolling update** — không có expand/contract cho topology. Với cặp thật (một bản phát hành chưa từng nằm trên cluster này, vì cluster dựng lần đầu ở HEAD của Phase 7) thì chỉ gặp một lần, nhưng quy tắc cần được chủ dự án quyết: (a) ghi nhận là bước vận hành một lần (xóa hàng đợi và dừng bản cũ) cho bản phát hành vượt `ab9cd91`; (b) đổi các tham số giới hạn/DLX của hàng đợi sang **policy của RabbitMQ** (`set_policy`) thay vì `x-arguments` khi khai báo — policy đổi được mà không gây `PRECONDITION_FAILED`; (c) khi đổi tham số, đặt tên hàng đợi mới (expand) rồi bỏ hàng đợi cũ (contract). Tôi **không** chọn thay chủ dự án và không sửa code ở đây.
- M-58 vẫn `Drafted` (cả vì chờ chấp nhận, D102, cả vì rollout chưa xong).

### 8.5 — stub Stripe chết (kịch bản 1b, M-64; `k8s/scripts/dependency-outage.sh stripe`)

*Chạy 2026-11-06 14:57 (UTC+7), `loadtest`, STRICT, image `a9c1a8f`.* Stub `payment-gateway-stub` scale 0; **42 lần charge → toàn bộ `503`** (không treo, không lỗi khác); breaker mỗi pod cần 10 lần gọi hỏng nên mở theo từng pod (Prometheus, sau 3 vòng): **`resilience4j_circuitbreaker_state{state="open"}` = 1 trên cả ba pod**. Khi breaker mở: **3/3 pod `Ready`**, readiness `200` ở mọi pod (gateway không là điều kiện Readiness), **catalog `200`**, **đăng ký free `201`**, một lần charge bị từ chối **nhanh: `503` trong 0,03 s**. Khôi phục: stub scale 1, sau khi breaker thăm dò lại **charge `201`** và **đăng ký `CONFIRMED`**. `dependency-outage stripe: OK`.

### 8.6 — Redis rồi RabbitMQ scale 0 (kịch bản 2b, M-65; `dependency-outage.sh redis|rabbitmq`)

*Chạy 2026-11-06 14:58–15:03 (UTC+7), cùng điều kiện.* Cả hai: **3/3 pod `Ready`, readiness `200`, nhóm `dependencies` `DEGRADED` ở mọi pod, catalog `200`** (quan sát). **Redis:** đăng ký trả **`503`** (INV-08: khóa chỗ fail-closed); **P1 `unihub-dependency-down` `Firing` sau 80 s**, **`Normal` sau 54 s** kể từ khi Redis về; đăng ký `201` trở lại. **RabbitMQ:** đăng ký free vẫn **`201`** (quan sát: outbox giữ ý định); P1 **`Firing` sau 109 s**, **`Normal` sau 39 s** kể từ khi về; `unihub-rabbitmq-target-down` **`Pending`** lúc đó (chuỗi vắng được đọc là 0, nhưng cho tới lúc kiểm vẫn chưa hết `for: 1m`), đăng ký `201` sau khi về. Hai lần `dependency-outage: OK`. Đây là bản chạy lần hai của P1 (lần đầu: 6.4), lần này từ một kịch bản commit được và dưới STRICT.

### Điều chủ dự án cần quyết (Mục 8)

1. **Gitleaks đang đỏ trên `d509fda`** (rule `curl-auth-user`, `k8s/scripts/kill-pod.sh:86`: `curl -u "admin:$(kubectl get secret … | base64 -d)"`, mật khẩu đọc từ cluster lúc chạy, không nằm trong file — dương tính giả). Dòng đã được thay ở commit sau (header Basic dựng bằng Python trong `lib.sh`), nhưng job quét toàn bộ lịch sử và `build-and-push` phụ thuộc vào nó. Cách theo quy ước (`.gitleaksignore`, có tiền lệ "với sự chấp thuận của chủ dự án" ngày 2026-11-01) cần chủ dự án duyệt; **tôi không tự thêm** và không viết lại lịch sử. Fingerprint: `d509fdad641faacd4353fe76c3cbfa60f52fe733:k8s/scripts/kill-pod.sh:curl-auth-user:86`.
2. **Topology AMQP không có expand/contract** (8.4): chọn (a), (b) hoặc (c) ở trên.
3. **Cửa sổ ~1 s `503` khi container restart tại chỗ** (8.2): chấp nhận hay muốn xử lý.
4. **`unihub-app-target-down` có thể bắn khi rollout** (đã nêu ở Mục 6): trong 8.1 chỉ `Pending` ~40 s (đủ dưới `for: 1m`); vẫn có thể bắn khi kéo image lâu.

### Đo tài nguyên (thay số tạm của `overlays/loadtest`; ADR Resource Profile, sai lệch có tên của D82)

`kubectl top` khi **rảnh** (không tải), sau khi cả hai overlay đã chạy:

| | `dev` (1 API) | `loadtest` (3 API + replica) |
|---|---|---|
| `backend-api` (mỗi pod, giới hạn 1 Gi) | 559 MiB | 512 / 524 / 520 MiB |
| sidecar `istio-proxy` (mỗi pod) | 36–42 MiB | 37–41 MiB |
| `postgres-master` / `postgres-replica` | 107 MiB / — | 86 MiB / 79 MiB |
| RabbitMQ / MinIO / Redis / Mailpit | 226 / 68 / 9 / 15 MiB | 228 / 68 / 10 / 15 MiB |
| hai stub WireMock | 111 và 114 MiB | 112 và 116 MiB |
| `istiod` / gateway | 56 / 37 MiB | 84 / 37 MiB |
| **cả node** (`kubectl top node`, so với **10 GiB** của container node) | 2855 MiB (**28%**) | **4275 MiB (42%)** — `docker stats` của container node: 4,28 GiB / 10 GiB |
| request đã đặt trên node | 5618 MiB (**55%**) | **8050 MiB (79%)** |

- **Kết luận có giới hạn:** giới hạn 1 Gi của API có chỗ trống (≈ 52% đang dùng khi rảnh, kể cả sidecar riêng), và `loadtest` + Istio vừa 10 GB mà không pod nào bị `OOMKilled` — **điều kiện dừng của D82 không bị chạm**. Con số này là **lúc rảnh**: không có tải, nên không nói được gì về `OOMKilled` dưới tải, về Postgres khi đọc/ghi nhiều, hay về 12 000 CCU (Phase 8). Postgres đang dùng 79–107 MiB so với request 768 Mi: số của `overlays/loadtest` giữ nguyên (chưa có căn cứ để hạ), ghi lại là **đo lúc rảnh**, không phải số cuối.
- **Mẫu số là 10 GiB, không phải 11,67 GiB.** Container node bị giới hạn 10 GiB nhưng kubelet báo allocatable theo VM (11,67 GiB), nên scheduler không dừng ở 10 GiB: ở Mục 6, Prometheus, Grafana và Tempo kèm sidecar có thể không làm pod `Pending` mà đẩy **chính container node** vào OOM killer của Docker — trông khác hẳn một pod `OOMKilled`. Điều kiện dừng của D82 bao gồm cả trường hợp đó.
- Phần observability (Mục 6): đã đo ở 6.1 — cả container node 5,4 GiB / 10 GiB (54%) với `loadtest` và đủ ba thành phần Prometheus, Grafana, Tempo, lúc rảnh.

## 9.1 — `smoke.sh` và bộ Playwright `tests/e2e/` (Mục 9, viết 2026-11-06; bằng chứng 9.2–9.4 ở mục ngay sau)

*Chạy 2026-11-06 (UTC) trên cluster `loadtest` đang có, dưới STRICT; `backend-api` là image dựng cục bộ `unihub-backend:local-head` (3 pod), `web` là `thanhtien2208/unihub-web:a9c1a8f…` (CI). **Bản luyện tập**: CI chưa dựng image cho HEAD (Gitleaks đỏ ở `d509fda`, mục `.gitleaksignore` đã duyệt và push ở `8e5e04a`, run 42242416340 còn chạy lúc ghi).*

- **`k8s/scripts/smoke.sh`** (Q21; `make smoke`): Flyway, mọi workload Ready, `readiness` 200 và `dependencies` `UP` trên từng pod API, bốn tài khoản seed đăng nhập đúng quyền (`REPORT_VIEW`, `CHECKIN_SCAN`, `WORKSHOP_CREATE`, `REGISTRATION_CREATE`), catalog 200, đăng ký free `201` + `CONFIRMED`, `X-Correlation-Id` là trace ID 32 hex, **email xác nhận trong Mailpit** (outbox → RabbitMQ → consumer trong cluster), mọi target Prometheus `up` (4), **trace trong Tempo** (60 request qua gateway, tìm theo trace ID), M-60 (`pg_stat_replication` `streaming`, trễ 0 s; xóa `postgres-master-0` → pod mới, **cùng PVC**, số workshop giữ nguyên, API đọc lại được, replica `streaming` lại). `--resilience` gọi `dependency-outage.sh stripe|redis|rabbitmq` (M-64, M-65, P1) — **chưa chạy ở Mục 9**, đã chạy ở 8.5/8.6.
- **Hai lần chạy, ghi cả lần hỏng:** lần 1 có **2 FAIL do lỗi của chính script**, không phải của cluster: (a) Job Flyway tự xóa sau 1 giờ (`ttlSecondsAfterFinished: 3600`) nên "Job `Complete`" không kiểm được trên cluster đã chạy lâu → script nay chấp nhận Job `Complete` **hoặc** `flyway_schema_history` có đủ số file migration trong ConfigMap và không dòng nào `success = false`; (b) request mang `traceparent` đã đánh dấu sampled **không** làm gateway ghi trace (gateway tự quyết 10%) → script gửi 60 request và tìm các trace ID. Lần 2: **tất cả `ok`** (3 trong 60 request có trace — khớp mức lấy mẫu 10%; trace có `istio-ingressgateway.istio-system` và `unknown_service`).
- **Một quan sát:** span của ứng dụng mang `service.name` = **`unknown_service`** trong Tempo (ứng dụng không đặt `spring.application.name`/`OTEL_SERVICE_NAME`). Không sửa ở đây (ngoài scope Mục 9); chủ dự án quyết có đặt hay không.
- **`tests/e2e/`** (Playwright `1.64.1`, xuất bản 2026-10-14; TypeScript `6.0.2` như `apps/web`): ba engine + project `transport`; một Student mỗi engine; mỗi test tự tạo workshop tên duy nhất; `retries: 0`; mật khẩu đọc từ Secret của cluster khi cần, không ghi ra đâu cả; **mọi test trình duyệt thất bại khi có sự kiện `securitypolicyviolation` hoặc lỗi CSP trong console** (đối chứng âm: một `<script>` nội tuyến chèn vào trang bị báo và test đỏ). `k8s/scripts/e2e.sh` (`make e2e`) mở port-forward gateway (HTTPS 8443, HTTP 8080) rồi chạy.
- **M-70 chỉ là hành trình một phần (chủ dự án chọn "API + trình duyệt", 2026-11-06):** Web không có trang tạo, publish, sửa hay đổi lịch workshop, chỉ có trang báo cáo. Bốn bước đó là lời gọi API qua cùng gateway và HTTPS; trình duyệt đăng nhập Organizer và Admin và đọc trang báo cáo. Ghi vào `phase-7.md` §5.
- **Gỡ lỗi trước khi máy tin CA** (cấu hình tạm đã xóa, không commit) chỉ để sửa hai selector; kết quả chính thức của 9.2–9.4 ở mục ngay dưới.

## 9.2–9.4 — Playwright qua HTTPS tin cậy thật, ba engine (chạy 2026-11-06 UTC; **Drafted**, chờ chủ dự án chấp nhận theo D102)

*Điều kiện: Minikube profile `unihub`, overlay `loadtest` (3 pod API, replica Postgres) **dưới `PeerAuthentication` STRICT** ở cả hai namespace; vào bằng `https://unihub.local:8443` (port-forward của gateway Istio, `k8s/scripts/e2e.sh`); CA cục bộ `k8s/.local-ca/ca.crt` đã được chủ dự án thêm vào keychain, `unihub.local` → `127.0.0.1` trong `/etc/hosts`; **không có `ignoreHTTPSErrors`**. `backend-api` là image dựng cục bộ `unihub-backend:local-head` (`sha256:9e8602da…`), `web` là image CI `thanhtien2208/unihub-web@sha256:a81d2f2b…` (commit `a9c1a8f`). Mã của bộ test: commit `2bedbb8` là lần đầu 13/13 nhưng **guard của nó đã sai** (xem "Kiểm chứng guard"); bằng chứng là commit `7e24085` (guard cuối, năm lần 13/13). CI xanh cả mười job trên `8e5e04a` (run 42242416340) và `60a270d` (run 42244869326), cả hai có `build-and-push`, nên Mục 10 có SHA xanh với image Docker Hub để deploy mới. **Bản luyện tập**: Mục 10 chạy lại trên bản deploy từ một SHA xanh CI.*

**Kết quả cuối: 13/13 xanh, năm lần chạy liên tiếp trên guard ở bản commit này (mỗi lần 1,1–1,2 phút, `retries: 0`)** — 4 probe `transport`, rồi trên mỗi engine (chromium, firefox, webkit) M-69 free, M-69 trả phí, M-70.

| Mục | Bằng chứng |
|---|---|
| 9.2 / M-69 | Mỗi engine: catalog (tải lại tới khi workshop xuất hiện) → chi tiết → "Đăng nhập để đăng ký" → đăng nhập → đăng ký → "Xem vé" → ảnh QR (`role=img`) và mã vé 26 ký tự Crockford; bản trả phí đi qua checkout với `pm_card_visa` tới "Thanh toán thành công." rồi QR. Một Student cho mỗi engine (student1/2/3). |
| 9.3 / M-70 | **Hành trình một phần (chủ dự án chọn "API + trình duyệt", 2026-11-06; vào `phase-7.md` §5):** tạo DRAFT (khách vãng lai nhận 404), publish, sửa và đổi lịch (`PATCH`), một Student đăng ký là lời gọi API qua cùng gateway; trình duyệt đăng nhập Organizer rồi Admin, thấy tiêu đề, địa điểm, giờ mới và trang báo cáo (sức chứa 5, giữ 1, xác nhận 1). |
| 9.4 / M-67 | `transport.spec.ts`: TLS ≥ 1.2 với chứng chỉ do CA cục bộ ký (xác minh thật trong Node); chào TLS 1.1 bị từ chối bằng alert của máy chủ (không phải lỗi "không có giao thức" phía client); HTTP cổng 8080 → 301 tới `https://`, không có JSON, cho cả GET workshops và POST login; header `Content-Security-Policy` có `frame-ancestors 'none'` trên `/`, `/workshops`, `/me/registrations`. **CSP guard** (`support/fixtures.ts`): mọi test trình duyệt đỏ nếu có sự kiện `securitypolicyviolation` hoặc lỗi CSP trong console, trên cả ba engine (đã chèn thử một script nội tuyến để thấy guard đỏ). Đây là kiểm CSP lặp lại được của test-matrix §4.5, nếu chủ dự án coi Playwright là kiểm đó. |

**Ba phát hiện trong lúc chạy (hai chưa phải việc của Mục 9):**

1. **WebKit báo `style-src-attr` khi trang có `<select>` gốc** (lần chạy đầu: 12/13, chỉ test trả phí của WebKit đỏ). Nguồn là `createElement('select')` trong bundle ở `/me/checkout/…`, không phải mã của UniHub: một trang tối thiểu có đúng CSP của chúng ta chỉ có `<select>` cũng bị WebKit báo, Chromium và Firefox thì không (trang không `<select>` sạch trên cả ba). `<select>` token thử nghiệm là phần tử `<select>` duy nhất của Web và chỉ có ở bản test-mode. **Quyết định của chủ dự án 2026-11-06: giữ CSP nguyên, guard cho phép đúng một dạng** (project `webkit`, `style-src-attr`, sample rỗng, đường dẫn `/me/checkout/…`, cộng đúng bấy nhiêu thông báo "Refused to apply a stylesheet" trong console); mọi thứ khác trên mọi engine vẫn đỏ, và test đính kèm `tolerated-csp-webkit-select` mỗi khi dùng ngoại lệ. Cần ghi ở `phase-7.md` §5.
2. **Catalog của Web chỉ hiện trang 0 (20 mục), không có tìm kiếm, không có lọc, không có phân trang — một khoảng trống so với REQ-1.3.1.1** ("search/filter by topic, speaker, or location"; API có, M-05; Web chưa có giao diện nào cho nó, và khoảng trống này chưa được ghi ở sign-off nào trước đây) (`WorkshopCatalogPage` gọi `useWorkshops({})`; API sắp theo giờ bắt đầu tăng dần). Khi có hơn 20 workshop sắp tới, các workshop sau đó không thể mở được từ giao diện. Lần chạy thứ hai và các lần sau đỏ 3/3 engine ở bước catalog vì các lần gỡ lỗi trước đã để 40 workshop `E2E …` lấp trang 1, tức là bộ test chạm đúng khoảng trống này. **Không sửa ở đây** (một tính năng Web ngoài Mục 9; chủ dự án quyết có làm không). Bộ test nay tự dọn: mỗi test hủy workshop nó tạo, `globalSetup` hủy mọi workshop `E2E …` còn sót và từ chối chạy khi catalog còn quá 16 workshop khác, với thông báo nói rõ lý do.
3. **Hủy workshop không xóa cache danh sách** (TTL 30 s, theo thiết kế, `workshop.md`): workshop vừa hủy còn hiện tối đa 30 s. Test chờ catalog tối đa 60 s (hai lần TTL) và `globalSetup` không đọc lại danh sách để đếm.

**Kiểm chứng guard (làm lại sau mỗi lần sửa guard; các lần sửa sai cũng ghi ở đây):**
- Ngoại lệ lần đầu chỉ dựa vào đường dẫn và nguồn bundle, nên một style nội tuyến thật trên `/me/checkout/…` trong WebKit cũng bị bỏ qua. Lần siết thứ nhất (hỏi `document.querySelector('select')` lúc sự kiện) **sai**: sự kiện phát ra ngay lúc `createElement`, có khi trước khi phần tử vào DOM, nên 4 trong 5 lần chạy đỏ. Lần siết thứ hai (bọc `createElement` để đếm) cũng **sai**: WebKit gán nguồn báo cáo cho nơi gọi, nên báo cáo trỏ vào script của test (`web-inspector://bootstrap.js`) và 5/5 lần đỏ. Bản cuối đếm `<select>` bằng `MutationObserver` (không nằm trong call stack) và chỉ tha tối đa hai báo cáo cho mỗi `<select>` vào DOM, trên project `webkit`, đường dẫn `/me/checkout/…`, sample rỗng, nguồn là bundle `/assets/…js`.
- **Đối chứng trên guard cuối, project `webkit`, đều đỏ như phải đỏ:** (A) style nội tuyến trên `/me/checkout/x` không có `<select>`; (B) `<select>` tạo trên `/workshops`; (C) style nội tuyến trên `/me/checkout/x` cạnh một `<select>`; (D) script nội tuyến. Test đối chứng không được commit. Ca dương tính là hành trình thật: 5 lần liên tiếp xanh.
- **Hạn chế còn lại, nói thẳng:** một báo cáo `style-src-attr` thật do chính bundle của ứng dụng gây ra, trên trang checkout, đúng lúc có `<select>` và không quá hai cái mỗi `<select>`, vẫn bị bỏ qua trên WebKit. Chromium và Firefox thì không có ngoại lệ nào.

**Tác dụng phụ của lượt quét `globalSetup` (kiểm sau lần chạy):** nó hủy hơn 40 workshop `E2E …` còn sót, đa số có đăng ký `CONFIRMED`, một số trả phí. Sau đó `unihub.parking-lot` và các hàng đợi hoàn tiền đều 0 tin, không có thanh toán nào ở `REFUND_REQUIRED` (35 `SUCCEEDED`), 93 đăng ký vẫn `CONFIRMED` (hủy workshop không tự hủy đăng ký) — nên không có gì bị kẹt; `unihub.registration.hold-delay.queue` có 16 tin, là hàng đợi trễ giữ chỗ bình thường. Mailpit nhận thêm thư thông báo hủy, nên lần `smoke.sh` kế tiếp thấy hộp thư không rỗng (nó chỉ tìm thư mới của student1).

**Sửa của bộ test trong lần chạy này:** hàm gửi của `OrganizerApi` chờ theo `Retry-After` khi gặp 429 (lượt quét đầu run dùng hết ngân sách của danh tính Organizer, INV-17 hoạt động đúng); `NODE_EXTRA_CA_CERTS` đặt trong `e2e.sh` vì `globalSetup` chạy trong tiến trình chính, nơi biến đặt trong config không còn tác dụng; timeout mỗi test 120 s.

**Chưa chứng minh:** không phải Safari thật (WebKit của Playwright), không có thiết bị di động, không đo tải; backend là image cục bộ chứ chưa phải image CI của HEAD; `smoke.sh --resilience` chưa chạy; chưa có sign-off của chủ dự án cho nhóm bằng chứng này.

## 10.1 — lần dựng lại từ đầu, **lần thử thứ nhất, KHÔNG sạch** (2026-11-07 UTC; không tick 10.1 và 10.2)

*Do Claude Code chạy theo yêu cầu "chạy tiếp mục 10" của chủ dự án. Bản deploy mới, một SHA xanh CI, STRICT từ chính manifest; nhưng host quá tải làm cluster chập chờn, nên **chưa có lần chạy bằng chứng sạch**. Ghi đầy đủ, cả phần hỏng.*

| | |
|---|---|
| Lệnh | `minikube delete -p unihub` (14:14:45) → `make cluster-up` (**1 phút 27 giây**, Minikube v1.38.1, Kubernetes v1.35.1, Istio 1.31.3, native sidecar) → `k8s/scripts/make-local-ca.sh --apply` (giữ CA cũ nên keychain và `/etc/hosts` vẫn đúng) → `make secrets` → `make deploy-loadtest TAG=9b0888487cccd112ccc6341e7861d7d8f2f12218` (14:24:40–14:32:24) → `make smoke` → `make e2e` |
| SHA và image | HEAD `9b08884` (CI xanh cả mười job, run 42260396743). `check-images`: backend index `sha256:2ed3cad5e2e9fc0aceb9654a319f1df7d884e7f1477b272f69c7f1179ebc8262`, web `sha256:a81d2f2b0c90ed7e98fa7ac8144b8eb25787fd7691ecc993385beb4c2db94204` (cả hai `amd64`+`arm64`). **`imageID` của 3 pod API và pod Web trên cluster bằng đúng hai digest đó** — lần đầu cluster chạy image CI của HEAD (Mục 8 và 9 dùng `unihub-backend:local-head`); đây cũng là nửa cluster của M-66 trên bản deploy mới. |
| STRICT | `kubectl get peerauthentication -A`: `STRICT` ở `unihub` và `unihub-observability`, do `k8s/base/peer-authentication.yaml` và `k8s/observability/peer-authentication.yaml` trong chính lần deploy; không áp tay. |
| Triển khai | `make rollout` **hết hạn sau 5 phút** ("0 of 3 updated replicas are available") nên `make deploy-loadtest` thoát lỗi; các pod tự hội tụ sau khoảng 9 phút kể từ lúc áp. Backend khởi động mất 80 s (`Started UniHubApplication in 79.868 seconds`) và bị restart 2–3 lần trong lúc đó; RabbitMQ khởi động mất 53 s, restart 2 lần. |
| `smoke.sh` | **Xanh toàn bộ** (14:38:14–14:39:33): Flyway `Complete`, mọi workload Ready, 3 pod `UP`, bốn tài khoản, catalog, đăng ký free `CONFIRMED`, thư trong Mailpit, 4 target Prometheus up, trace trong Tempo (5/60), M-60 (replica `streaming`, `postgres-master-0` về cùng PVC). Nhật ký: `phase-7-cluster-checks/10-1-smoke-2026-11-07.log`. |
| Playwright lần 1 | `make e2e` (4 worker): **7 trong 13 đỏ** (6 xanh: 4 probe `transport` và hai test WebKit), mỗi test chạy 1–2 phút thay vì 10–35 s; lỗi là đăng ký `503`, không thấy "Đăng ký thành công", không thấy "Thanh toán thành công". Sau lần chạy gần như **mọi pod đã restart một lần** (14 lần), do liveness: `pg_isready`, `redis-cli ping`, probe của sidecar và của ứng dụng đều quá hạn **cùng lúc**. Nhật ký: không commit (`test-results/` bị bỏ qua). |
| Playwright lần 2 | Sau khi cluster ổn định (không restart thêm trong hai phút), `--workers=2`: **12/13 xanh**, không pod nào restart *trong* lần chạy. Một test đỏ: Firefox trả phí, trang hiện "Cổng thanh toán tạm thời không khả dụng" sau một lần gọi stub thất bại (breaker **không** mở, `max_over_time` = 0 trên cả ba pod; hai lần gọi thất bại trong 20 phút). Ngay sau lần chạy, **RabbitMQ restart lần thứ 4**. |
| Không chạy | 8.1, 8.2, 8.3, 8.5, 8.6, 6.4 và 7.6 (`check-mtls.sh`): chạy trên một cluster đang chập chờn thì kết quả không có nghĩa. 8.4 không chạy theo quyết định của chủ dự án còn chờ (topology AMQP; N-1 `a58e814` cũng không có image trên Docker Hub). 10.2 sau bộ kiểm chỉ có số lúc rảnh (dưới). |

**Điều gây ra sự chập chờn (đo được, chưa phải toàn bộ nguyên nhân):**
- **Host quá tải.** MacBook 16 GiB RAM; VM của Docker Desktop (12 GiB đã cấp) chiếm **12 GiB thường trú**, swap đã dùng 7,2/8 GiB, load average 10–15 trên 8 core khi chạy ba engine trình duyệt cùng 4 worker. Probe đều bị quá hạn *cùng lúc* trên mọi pod, kể cả `pg_isready` và `redis-cli ping`: dấu hiệu cả VM bị đứng, không phải một ứng dụng hỏng. Với `--workers=2` và không có trình duyệt, load còn 6–10 và không có restart.
- **RabbitMQ là mắt xích yếu nhất.** Giới hạn 500m CPU và 768 Mi; **lần kết thúc cuối của container là `OOMKilled` (exit 137)**; probe là `rabbitmq-diagnostics -q ping` (một BEAM mới mỗi lần, timeout 10 s) và **đo trực tiếp lúc rảnh mất 3,0 / 5,3 / 9,0 s**, tức sát giới hạn; pod kịch trần CPU (491m/500m) ngay cả khi chỉ có 3 kết nối và 30 channel (không có vòng kết nối lại). `RabbitMQ` đã làm các consumer báo `Failed to check/redeclare auto-delete queue(s)` mỗi lần nó restart.
- **Probe của backend:** `startupProbe` có `timeoutSeconds: 1`; khi CPU căng, 1 s không đủ để `/actuator/health/liveness` trả lời, và sự kiện ghi `Startup probe failed: HTTP probe failed with statuscode: 500` lúc 14:5x.
- **Số đo mà "không `OOMKilled`" của bản luyện tập trước giờ không còn đúng**: ở lần deploy tươi này RabbitMQ bị `OOMKilled` ít nhất một lần.

**Số lúc rảnh (10.2, một phần; sau khi CPU hạ xuống, 14:38 UTC, trước khi chạy tải; `kubectl top`, `docker stats`):** API 524–529 MiB mỗi pod (giới hạn 1 GiB), `postgres-master` 136 MiB, `postgres-replica` 75 MiB, RabbitMQ 138 MiB, Redis 4 MiB, Mailpit 5 MiB, MinIO 57 MiB, hai stub WireMock 110/124 MiB, Web 7 MiB, Grafana 426 MiB, Prometheus 64 MiB, Tempo 50 MiB, `istio-proxy` 34–43 MiB mỗi pod, `istiod` 76 MiB; node theo `kubectl top`: 5 340 MiB, 893m (44% bộ nhớ, 11% CPU); `docker stats unihub`: **5,22 GiB / 10 GiB**. Sau bộ Playwright: API 619–666 MiB, RabbitMQ tới 241 MiB. **Chưa đo dưới tải** và chưa đo sau bộ 8.x.

**Việc cần chủ dự án quyết trước khi thử lại 10.1:** (a) cho phép sửa manifest để deploy tươi không chập chờn — RabbitMQ: probe `tcpSocket` 5672 cho readiness, liveness timeout rộng hơn, `startupProbe`, và nâng giới hạn CPU/bộ nhớ; backend: `startupProbe` `timeoutSeconds` lớn hơn — rồi **một commit mới, CI xanh, và dựng lại từ `minikube delete`** (một SHA mới); (b) hoặc giảm tải lên host (thu nhỏ VM Docker, đóng ứng dụng khác khi chạy bằng chứng, chạy `--workers=2`); (c) hoặc ghi lần thử này vào `phase-7.md` §5 như một giới hạn của máy và dừng ở đây.

## 10.1 — lần thử 2 (2026-11-07 UTC; sau khi sửa manifest; **bộ kiểm chạy hết trừ 8.4; 10.1 tick 2026-11-08 theo quyết định của chủ dự án loại 8.4**)

*Do Claude Code chạy theo yêu cầu "hỏi advisor để quyết định cho tôi, sau đó thực hiện tiếp" của chủ dự án (quyết định được giao ngày 2026-11-07: phương án (a), chỉ sửa các lỗi mà nhật ký lần thử 1 đã chỉ ra). Lần thử 1 giữ nguyên ở mục trên. Mọi dòng ma trận vẫn `Drafted` (D102); dòng P1 của `test-plan.md` không đụng tới.*

**Sửa trước lần thử 2 (commit `f648fd0`, CI xanh cả mười job, run 42388031802, có `build-and-push`):** RabbitMQ đọc bộ nhớ của VM (12 GiB) thay vì giới hạn 768 Mi nên watermark là 5,01 GB và nó bị `OOMKilled`: `k8s/base/rabbitmq-memory.conf` đặt `total_memory_available_override_value = 768MiB` (watermark nay **0,322 GB**, kiểm trên cluster); probe `rabbitmq-diagnostics ping` (3–9 s trên 10 s) thay bằng `httpGet /metrics:15692` cho start-up và readiness (một probe `tcpSocket` bị loại: sidecar trả lời kết nối dù broker chưa lên), liveness giữ `ping` với timeout 30 s; `startupProbe` của backend `timeoutSeconds` 1 → 3; mặc định Playwright 4 → 2 worker. **Chẩn đoán CPU của advisor không được xác nhận** (`schedulers_online` là 1, không phải 8) nên không áp cờ busy-wait. Kiểm trước trên cluster cũ: RabbitMQ không restart qua một lần `make e2e`.

| | |
|---|---|
| Lệnh và giờ | `minikube delete -p unihub` 16:11:18 → `make cluster-up` 16:11:53–16:13:44 → `make-local-ca.sh --apply` → `make secrets` → `make deploy-loadtest TAG=f648fd0f82d9f48c7f4bfe2db853d5a546284764` 16:13:45–16:17:52 (**4 phút 07 giây, rollout xong, không hết hạn**) → idle → `make smoke` 16:19:32–16:20:02 → `make e2e` → 8.3 → 8.1 → 8.2 → 8.5 → 8.6 (RabbitMQ, rồi Redis) → `check-mesh.sh` → `check-mtls.sh --negative-control` → `check-mtls.sh` |
| Image | `thanhtien2208/unihub-backend@sha256:2ed3cad5e2e9fc0aceb9654a319f1df7d884e7f1477b272f69c7f1179ebc8262`, `thanhtien2208/unihub-web@sha256:a81d2f2b0c90ed7e98fa7ac8144b8eb25787fd7691ecc993385beb4c2db94204` (cả hai `amd64`+`arm64`; trùng digest với `9b08884` vì từ `9b08884` tới `f648fd0` chỉ đổi manifest, tài liệu và cấu hình test, không đổi mã ứng dụng). **`imageID` của ba pod API và pod Web trên cluster bằng đúng hai digest đó.** |
| Phiên bản (đọc từ cluster) | Minikube v1.38.1, Kubernetes v1.35.1, Istio 1.31.3 (control plane và data plane), native sidecar; STRICT từ manifest ở `unihub` và `unihub-observability` |
| Khởi động | Backend `Started UniHubApplication in 61,7 s`; RabbitMQ `Time to start RabbitMQ: 36 571 ms` (lần 1: 80 s và 53 s). **Sau deploy: 0 restart trên mọi pod.** |
| `smoke.sh` | Xanh toàn bộ (17 mục kiểm gồm M-60), `smoke.log` |
| Playwright | `make e2e` (2 worker): **13/13 xanh trong 1,9 phút, 0 restart** (`playwright.log`). Một lần chạy sạch trên cluster tươi; chưa lặp lại 5 lần như trên cluster đã ấm. |
| 8.3 (M-62) | S-15: `201 409 409 409 409 429`, `Retry-After: 10`, ngân sách chung giữa các pod; S-16 (a): `X-Envoy-External-Address` giả (`203.0.113.77`) bị gateway ghi đè (`127.0.0.1` tới pod qua sidecar), 130 request đổi `X-Forwarded-For` và header giả vẫn bị chặn ở request 101 (100 `200`, 30 `429`). **Hai lần đầu đỏ vì lỗi của chính script** (bên dưới), lần thứ ba đạt: `check-rate-limit: OK`. |
| 8.1 (M-59) | `kill-pod.sh 7a`: 251 request thăm dò, **0 5xx, 0 lỗi kết nối, 0 `429`**. Lần đầu đỏ ở bước đăng nhập (xem dưới); lần hai đạt. |
| 8.2 (M-59) | `kill-pod.sh 7b`: pod rời rotation T+15 s, container restart tại chỗ T+31 s, Ready bền T+66 s, **`unihub-app-restarted` `Firing` T+81 s (AC99.2)**; traffic tiếp tục ở pod khác (312 `200`). **Lỗi không bằng 0, như lần luyện tập:** 4 timeout (tới pod đã đóng băng) và **2 `503` quanh T+30 s** lúc container restart nên script thoát mã 1; chủ dự án quyết có xử lý hay không. Đây là restart duy nhất của cả lần chạy (`backend-api-…-9gnvp`, có chủ đích). |
| 8.5 (M-64) | `dependency-outage.sh stripe`: 42 charge `503`, breaker `OPEN` trên cả 3 pod, 3/3 Ready, catalog `200`, đăng ký free `201` khi breaker mở, charge bị từ chối trong 0,22 s; stub về → charge `201`, đăng ký `CONFIRMED`. |
| 8.6 + 6.4 (M-65, P1) | **RabbitMQ:** `unihub-dependency-down` `Firing` sau 94 s, `Normal` sau 35 s; 3/3 pod Ready, `dependencies` `DEGRADED`, đăng ký free `201` (outbox), `201` trở lại. **Redis:** `Firing` sau 102 s (lần 1) và 87 s (lần 2, chạy lại để chụp ảnh), `Normal` sau 55/54 s; đăng ký `503` (INV-08), `201` trở lại. Ảnh Grafana thật lúc `Firing` và sau khôi phục: `phase-7-cluster-checks/attempt2/{redis,rabbitmq}-{firing,normal}.png` (rule `Redis or RabbitMQ down`, 3 instances). |
| 7.6 (M-63) | `check-mtls.sh --negative-control`: cả 19 cổng trả lời plaintext (đối chứng), rồi xóa các policy PERMISSIVE; `check-mtls.sh`: **cả 19 từ chối** (0 byte), `min(up{job="unihub-backend"})` = 1; STRICT còn nguyên sau đó. `check-mesh.sh` (P2, P4, 7.1, 7.2, 7.4): đạt sau khi Grafana đã đọc Tempo (lần đầu đỏ ở "grafana → tempo" vì Grafana trên cluster mới chưa từng đọc nó; chính script nêu điều kiện này). |
| Không chạy | **8.4** (diễn tập rollout, M-58): chờ quyết định topology AMQP; N-1 `a58e814` cũng không có image trên Docker Hub. **Quyết định của chủ dự án 2026-11-08 ("cách 2"): 8.4 nằm ngoài 10.1**, nên 10.1 được tick; 8.4 vẫn chưa tick và M-58 vẫn `Drafted`. Phải ghi ở `phase-7.md` §5, và tiêu chí exit "pod phiên bản cũ không hỏng khi đang rollout" chưa đạt trọn vẹn. |

**Ba lỗi của script bộ kiểm, tìm ra vì cluster mới chậm hơn cluster đã ấm (đã sửa hoặc ghi):**
1. `check-rate-limit.sh` (8.3, hai lần đỏ): vòng chờ "route tạm tới stub đã sẵn sàng" kiểm `200`, nhưng nginx của Web trả `200` cho mọi đường dẫn (SPA fallback), nên vòng chờ qua ngay khi route chưa lan tới gateway và request thăm dò đi vào Web, không vào stub (`DELETE` trả `405`, nhật ký stub rỗng). Sửa: chờ nội dung `"mappings"` của WireMock tối đa 60 s và báo lỗi nếu không thấy; lần chạy sau đạt.
2. `kill-pod.sh` (8.1, một lần đỏ): đăng nhập ở đầu script nhận `429`, vì 8.3 vừa dùng hết ngân sách theo địa chỉ (130 request ẩn danh). Chạy lại sau một cửa sổ 60 s thì đạt. Chưa sửa script (thứ tự chạy đã tránh được); ghi lại.
3. `check-mesh.sh`: điều kiện "Grafana đã đọc Tempo" cần thao tác trước trên cluster mới; ghi lại, không sửa.

**10.2 — tài nguyên đo được (`kubectl top`, `docker stats`; `resources-idle.txt` lúc 16:19 sau khi CPU hạ, `resources-after-battery.txt` sau bộ 8.x/7.6):**

| Thành phần | Lúc rảnh | Sau bộ kiểm | Giới hạn |
|---|---|---|---|
| `backend-api` (mỗi pod, 3 pod) | 524–527 MiB | 590–612 MiB | 1 GiB |
| RabbitMQ | 142 MiB | 128 MiB | 768 Mi (watermark nay 322 MB) |
| Postgres master / replica | 136 / 76 MiB | 72 / 79 MiB | — |
| Redis | 10 MiB | 7 MiB | 256 Mi |
| Grafana / Prometheus / Tempo | 328 / 66 / 34 MiB | 505 / 95 / 177 MiB | — |
| MinIO / Mailpit / hai stub / Web | 78 / 8 / 115–117 / 7 MiB | 79 / 28 / 127 / 7 MiB | — |
| Node (`kubectl top`) | 4 900 MiB (41%), 1 718m | 5 965 MiB (49%), 1 344m | — |
| Container node (`docker stats`) | **4,80 GiB / 10 GiB** | **5,83 GiB / 10 GiB** | 10 GiB |

Không `OOMKilled` ở lần chạy này (trừ lần thử 1, đã ghi). **Nhận xét cho Phase 8 (Q2):** (1) giới hạn thật của việc chạy K6 trên cluster này là **host**, không phải node: máy 16 GiB cấp 12 GiB cho VM Docker, VM chiếm 12 GiB thường trú, swap đã dùng 6,8–7,2/8 GiB và load average 7–15 ngay cả khi không có tải; lần thử 1 cho thấy mọi probe quá hạn cùng lúc khi host đứng. Bước tải thật cần host rảnh (đóng ứng dụng khác), hoặc nhiều RAM hơn, hoặc VM nhỏ hơn với số pod ít hơn; (2) bộ nhớ lúc rảnh còn dư (4,8 GiB trên 10 GiB) nhưng đỉnh dưới tải chưa đo: ba pod API 612 MiB mỗi pod sau bộ kiểm trên giới hạn 1 GiB; (3) Grafana (505 MiB, không giới hạn) là thành phần ăn thêm nhiều nhất sau khi mở các trang; (4) khởi động nguội mất 60–80 s cho backend và 36 s cho RabbitMQ, nên các kịch bản K6 phải chờ cluster ấm.

**Điều còn chờ chủ dự án:** 8.4 (quyết định topology AMQP); chấp nhận bằng chứng cluster (D102) cho M-58…M-67, M-69, M-70 và ghi vào `phase-7.md` §5: lỗi 503 ~1 s lúc container restart (8.2), hành trình một phần của M-70, ngoại lệ CSP của WebKit, và giới hạn host ở trên.

## Quan sát khác

- **`Connection reset` tới RabbitMQ lúc khởi động:** backend ghi 216 dòng WARN `ForgivingExceptionHandler … Connection reset` trong **đúng hai phút đầu** (01:55:31–01:57:31 UTC), rồi dừng hẳn. Nguyên nhân hợp lý: `make deploy-*` áp mọi thứ cùng lúc, backend khởi động trước khi RabbitMQ (và sidecar của nó) sẵn sàng, và client AMQP tự thử lại. Readiness của API không phụ thuộc broker (thiết kế), nên pod vẫn `Ready`. Chưa làm gì; nếu muốn log yên hơn, thêm probe/`initContainer` chờ broker là quyết định riêng.

## Chưa làm được / chưa chạy

- **5.5:** `make smoke` đã có từ Mục 9 (`k8s/scripts/smoke.sh`, xem 9.1); M-64 và M-65 nằm ở `--resilience`/`dependency-outage.sh`.
- **`minikube tunnel`:** cần `sudo`, không chạy; gateway được vào bằng `port-forward`. Playwright qua HTTPS với CA tin cậy thật và `unihub.local` đã chạy (mục 9.2–9.4 ở trên).
**S-16 trên cluster** (`X-Envoy-External-Address` thật sau sidecar).
- Không có tải: không có K6, không đo p95, không đo `OOMKilled` dưới tải.
- Không kiểm `readOnlyRootFilesystem`, không có NetworkPolicy (không thuộc Phase 7).

## Điều kiện để các dòng ma trận lên `Passing` (D102)

M-60 (replica `streaming`, `postgres-master-0` cùng tên và PVC) và M-66 (cluster kéo đúng digest CI) đã có bằng chứng ở đây, nhưng do Claude Code chạy: chủ dự án phải nói rõ **chấp nhận** nhóm bằng chứng này (ở `phase-7.md`, điều kiện B của sign-off). Bản chạy lại ở Mục 10 dưới STRICT là bản được ký.

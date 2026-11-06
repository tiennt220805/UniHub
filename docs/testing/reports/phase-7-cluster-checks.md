# Phase 7 — Kiểm tay trên cluster Minikube (task 5.1, 5.3, 5.6, 5.7, 6.1, 6.3, 6.4)

> **Mục đích.** Các IT và test tĩnh của Phase 7 chứng minh từng mảnh (manifest khớp mã, stub trả đúng cái adapter gọi, migration tương thích). Chúng **không** chứng minh rằng các manifest chạy được trong một cluster có Istio. Tài liệu này ghi những gì đã chạy trên cluster, bằng chứng của từng bước, và những gì **chưa** chạy.
> **Đây là bản luyện tập dưới `PeerAuthentication` PERMISSIVE** (task 5.7). Bản bằng chứng chính thức chạy lại ở Mục 10, dưới STRICT, trên một lần deploy mới (D96). Một ô trống là một khoảng trống, không phải một lần đạt.

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

*Chạy 2026-11-06, buổi chiều (UTC+7), cùng người chạy và cùng điều kiện như trên; chạy trên cluster `loadtest` đang có, image `1cf9499` (vẫn là image của lần 5.7 — xem "Mã" của 6.4 cho image của lần diễn tập P1).*

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
- Phần observability (Mục 6) chưa có, nên cluster chưa được thử với đủ ba thành phần Prometheus, Grafana, Tempo.

## Quan sát khác

- **`Connection reset` tới RabbitMQ lúc khởi động:** backend ghi 216 dòng WARN `ForgivingExceptionHandler … Connection reset` trong **đúng hai phút đầu** (01:55:31–01:57:31 UTC), rồi dừng hẳn. Nguyên nhân hợp lý: `make deploy-*` áp mọi thứ cùng lúc, backend khởi động trước khi RabbitMQ (và sidecar của nó) sẵn sàng, và client AMQP tự thử lại. Readiness của API không phụ thuộc broker (thiết kế), nên pod vẫn `Ready`. Chưa làm gì; nếu muốn log yên hơn, thêm probe/`initContainer` chờ broker là quyết định riêng.

## Chưa làm được / chưa chạy

- **5.5:** `Makefile` có `cluster-up` nhưng **chưa có `smoke`** (Mục 9, D101); `journey.sh` mới là một hành trình, không phải toàn bộ `smoke.sh` (chưa có M-64, M-65).
- **`/etc/hosts` + `minikube tunnel` + trình duyệt tin CA:** cần `sudo`, không chạy; kiểm bằng `port-forward`. Playwright qua HTTPS (AC97.2) là 9.4.
- **STRICT, `check-mtls.sh`, S-19, P2–P4** (Mục 7); **kill-pod, đóng băng pod, M-59, M-62, M-64, M-65, rollout rehearsal M-58** (Mục 8); **observability trong cluster, alert trên cluster** (Mục 6); **S-16 trên cluster** (`X-Envoy-External-Address` thật sau sidecar).
- Không có tải: không có K6, không đo p95, không đo `OOMKilled` dưới tải.
- Không kiểm `readOnlyRootFilesystem`, không có NetworkPolicy (không thuộc Phase 7).

## Điều kiện để các dòng ma trận lên `Passing` (D102)

M-60 (replica `streaming`, `postgres-master-0` cùng tên và PVC) và M-66 (cluster kéo đúng digest CI) đã có bằng chứng ở đây, nhưng do Claude Code chạy: chủ dự án phải nói rõ **chấp nhận** nhóm bằng chứng này (ở `phase-7.md`, điều kiện B của sign-off). Bản chạy lại ở Mục 10 dưới STRICT là bản được ký.

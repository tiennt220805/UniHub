# UniHub Workshop System — System Architecture

---

## 1. Overall Architecture Overview

### 1.1 Architectural Style

**Chosen architecture:** Stateless Modular Monolith with clean, infrastructure-agnostic module boundaries.

The backend is delivered as a single deployable Spring Boot application, internally partitioned into independently reasoned-about business modules (User, Workshop, Registration, Payment, Check-in, Notification). Each module owns its business logic and keeps that logic decoupled from the specific infrastructure it depends on, so the system can evolve — or be split into standalone services — without a rewrite.

Statelessness is the defining property of the application layer: no instance retains user session state locally, and identity/authorization context travels with the request (via JWT) rather than living on a specific server. This is what allows the system to scale horizontally by adding instances rather than by growing a single instance, and it is a prerequisite for the self-healing behavior described in Section 1.4.

### 1.2 Core Components

| Component               | Purpose                                                                                         | Notes                                                                                                                       |
| :---------------------- | :---------------------------------------------------------------------------------------------- | :-------------------------------------------------------------------------------------------------------------------------- |
| **Web Application**     | Discovery and registration for Students; catalog and reporting management for Organizers/Admins | Single responsive web client; views are gated by permission-based access control                                            |
| **Check-in Client**     | E-ticket scanning at venue entrances                                                            | Offline-capable web client; queues scan events locally and syncs once connectivity returns                                  |
| **Ingress Controller**  | Single external entry point for all client traffic                                              | Kubernetes-native Ingress; performs TLS termination and load balancing across Backend API instances                         |
| **Backend API**         | Central business logic and orchestration for every module                                       | Java 21 / Spring Boot 3.x, stateless Modular Monolith, deployed as multiple parallel instances                              |
| **PostgreSQL**          | System of record for all transactional data                                                     | Master-Slave replication topology; writes are routed to the Master, reads to Slave replicas                                 |
| **Redis**               | Distributed coordination and caching                                                            | Backs distributed locking and rate limiting (via Redisson), and serves as a read-through cache for low-change workshop data |
| **Message Broker**      | Asynchronous, decoupled task processing                                                         | RabbitMQ; carries seat-hold expiry, notification delivery, and reconciliation triggers off the synchronous request path     |
| **Object Storage**      | Durable storage for Organizer-uploaded promotional PDFs                                         | AWS S3; decouples large binary storage from the transactional database and serves as the source file for AI summarization   |
| **Observability Stack** | Operational visibility across all running instances                                             | Prometheus + Grafana for metrics and dashboards; OpenTelemetry for distributed request tracing                              |

### 1.3 Communication Protocols

- **Client to Backend (Web Application & Check-in Client):** HTTPS with JSON payloads; authentication carried via a stateless JWT, verified independently by any Backend API instance.
- **Ingress to Backend API:** Internal HTTP, load-balanced across all healthy API instances behind the Ingress Controller.
- **Backend API to PostgreSQL:** JDBC, with write traffic routed to the Master and read traffic routed to Slave replicas via a dynamic routing datasource.
- **Backend API to Redis:** Redis protocol via the Redisson client, used for distributed locks, rate-limit counters, and cached read data.
- **Backend API to Message Broker:** AMQP, for publishing and consuming asynchronous tasks.
- **Backend API to Object Storage:** HTTPS via the AWS S3 API, for uploading and retrieving PDF brochures; the Background Workers use the same API to fetch a stored PDF before invoking summarization.
- **Backend API to External Services:** HTTPS for the Payment Gateway, the AI Summarization Service, and Notification Providers.
- **Legacy Student Data Ingestion:** Scheduled batch file intake (nightly export), processed out-of-band from live user traffic.
- **Backend API to Observability Stack:** Metrics scraped over HTTP (Actuator endpoints); traces exported via the OpenTelemetry protocol.

### 1.4 Resilience Strategy

- **Layered seat-protection.** Overbooking is prevented through independent safeguards at the request-handling, coordination, and storage layers, so no single point of failure can result in an oversold seat.
- **Fault isolation around external dependencies.** Calls to the Payment Gateway are wrapped in a circuit-breaker boundary, so a gateway outage degrades only payment-related functionality and does not cascade into browsing, check-in, or reporting.
- **Idempotent request handling.** Registration and payment requests are safe to retry; duplicate submissions caused by network retries or repeated clicks are resolved to a single outcome.
- **Asynchronous, retry-safe background work.** Time-bound and background processes — seat-hold expiry, notification delivery, financial reconciliation — run off the critical path via the Message Broker, so they never block a user-facing request.
- **Self-healing application instances.** Kubernetes health probes detect and restart unhealthy Backend API instances automatically, without operator intervention.
- **Offline-first check-in with deterministic reconciliation.** Check-in continues to function without network connectivity at the venue, with server-side reconciliation ensuring exactly one valid check-in is recorded per ticket after sync.

---

## 2. C4 Diagrams

### 2.1. Level 1 — System Context

<p align="center">
  <img src="./diagrams/c4_system_context.png" style="width:80%;" alt="System Context Diagram">
</p>

The system context view positions the **UniHub Workshop System** as the central platform coordinating the entire workshop lifecycle for "Skills and Career Week." Three primary personas interact with the system directly. **Students** discover workshops, register, pay, and check in using an issued e-ticket. **Organizers/Admins** manage the workshop catalog end-to-end — creation, editing, cancellation, and rescheduling — and monitor registration and check-in activity in real time. **Check-in Staff** use the system at venue entrances to validate e-tickets, including under unstable network conditions.

Four external systems define the boundary of UniHub's responsibility. The **Legacy Student Management System** supplies a nightly batch export used for identity verification, flowing into UniHub without a return path. The **Payment Gateway** authorizes paid registrations and is treated as an inherently unreliable dependency, isolated behind a fault-tolerance boundary. The **AI Summarization Service** is invoked to generate concise workshop descriptions from Organizer-uploaded promotional PDFs. **Notification Providers** deliver registration confirmations and lifecycle alerts (cancellation, reschedule), and are integrated through a channel-agnostic boundary so new providers can be added without touching core business modules.

This diagram establishes the trust boundary of the system: all user interaction happens over authenticated HTTPS, while every external system is treated as a dependency that may be slow, unavailable, or delayed — never as something the core registration and payment guarantees rely on being perfectly reliable.

### 2.2. Level 2 — Container

<p align="center">
  <img src="./diagrams/c4_container.png" style="width:80%;" alt="Container Diagram">
</p>

The container view decomposes UniHub into its client applications, the backend service, and its supporting infrastructure. The **Web Application** serves both Students and Organizers/Admins through a single responsive client with permission-gated views. The **Check-in Client** is a separate, offline-capable web client purpose-fit for venue scanning, since its connectivity assumptions differ fundamentally from the rest of the system.

All client traffic enters through a single **Ingress Controller**, which terminates TLS and load-balances requests across the running instances of the **Backend API** — a stateless Spring Boot Modular Monolith holding every business module described in Section 1.2. The Backend API is the only component that talks directly to the data and messaging layer: it routes writes to the **PostgreSQL Master** and read-heavy queries to **PostgreSQL Replicas**, coordinates concurrent access and caches low-change reads through **Redis**, offloads time-deferred work to the **Message Broker** for asynchronous processing, and persists Organizer-uploaded promotional PDFs to **Object Storage** rather than the transactional database.

Cross-cutting the entire container landscape, the **Observability Stack** collects metrics and distributed traces from every running Backend API instance, giving operators a real-time view of system health — independent of which instance is handling any given request. External systems (Legacy Student Management System, Payment Gateway, AI Summarization Service, Notification Providers) are integrated exclusively through the Backend API, keeping the boundary of external dependency in one place.

This container structure is what allows the system to scale horizontally under registration-peak load, maintain strict write consistency for seat allocation and payment, and keep check-in operational at the venue even when the network is not.

### 2.3. Level 3 — Component (Backend API)

<p align="center">
  <img src="./diagrams/c4_component_backend.png" style="width:80%;" alt="Component Diagram — Backend API">
</p>

This diagram deconstructs the **Backend API** container from Section 2.2 into the six business modules introduced in Section 1.1: **User**, **Workshop**, **Registration**, **Payment**, **Check-in**, and **Notification**. Each module is a distinct component with its own responsibility boundary, and the diagram makes explicit which infrastructure and external systems each module — not the monolith as a whole — actually depends on.

The **Ingress Controller** routes incoming requests directly to the module that owns them, rather than to an undifferentiated "Backend API" black box. The **Registration Module** sits at the center of the highest-risk workflow: it calls the **Workshop Module** to check and reserve seat capacity, calls the **Payment Module** for paid tickets, and calls the **User Module** to resolve student identity — reflecting the same request-handling, coordination, and storage layering described in Section 1.4, now attributed to specific modules rather than the system as a whole.

Two modules own the system's external-facing integrations exclusively: the **Payment Module** is the only component that talks to the Payment Gateway, keeping circuit-breaker isolation contained to a single, well-defined boundary; and the **Notification Module** is the only component that talks to Notification Providers, consuming events from the Message Broker rather than being called synchronously by other modules — which is what makes the notification channel pluggable without touching the modules that trigger it. The **Workshop Module** owns both the **Object Storage** and **AI Summarization Service** dependencies, since brochure upload and summarization are part of the same workshop-content responsibility. The **User Module** is the sole target of the nightly student data sync from the **Legacy Student Management System**, consistent with it being the module of record for identity data.

This decomposition is what justifies the Modular Monolith decision in Section 1.1: each module already has a narrow, enforceable dependency footprint on shared infrastructure, so extracting any one of them into a standalone service later would not require redrawing these boundaries — only relocating them.

---

## 3. High-Level Architecture Diagram

<p align="center">
  <img src="./diagrams/system_architecture.png" style="width:80%;" alt="High-Level Architecture Diagram">
</p>

This diagram grounds the Container view (Section 2.2) in a concrete runtime topology, showing how each container is horizontally scaled and wired together in production. **Client traffic** enters exclusively through the **Ingress Controller**, which terminates TLS and load-balances requests across every running **Backend API** instance — the Application Layer is drawn as multiple parallel instances specifically to make explicit that no request is bound to a specific instance.

Every Backend API instance shares the same **Data & Coordination Layer**: writes are sent to the **PostgreSQL Master**, read-heavy queries are served from the **PostgreSQL Slave** (kept current via replication), and **Redis** is used uniformly for distributed locking, rate limiting, and caching low-change reads. Because this layer is shared rather than instance-local, any Backend API instance can safely process any request without coordination gaps.

Work that does not need to block the caller — notification delivery, seat-hold expiry, and financial reconciliation — is handed off to **RabbitMQ** and picked up by **Background Workers**, which write results back to PostgreSQL and hand off to Notification Providers. The **Legacy Student Management System** feeds into this same asynchronous path via its nightly batch file, keeping bulk data processing off the synchronous request path entirely. PDF brochure processing follows the same pattern: the Backend API uploads the file directly to **Object Storage (AWS S3)** and returns to the Organizer immediately, while a Background Worker later fetches that same file from S3 to invoke the AI Summarization Service — keeping large binary I/O and third-party summarization latency off the request path.

The **Observability Stack** and **External Services** sit at the edges of the diagram to reflect their role: every Backend API instance reports traces and metrics outward to OpenTelemetry, Prometheus, and Grafana regardless of load, while calls to the Payment Gateway and AI Summarization Service are drawn as isolated, single-direction dependencies — consistent with the fault-isolation principle in Section 1.4, where failure on either side must never propagate back into the core request path.

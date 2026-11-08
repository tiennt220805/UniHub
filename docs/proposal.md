# PROJECT PROPOSAL

## PROJECT: UNIHUB WORKSHOP SYSTEM

---

## Table of Contents

- [1. Title Page / Project Information](#1-title-page-project-information)
- [2. Executive Summary](#2-executive-summary)
- [3. Background & Problem Statement](#3-background-problem-statement)
- [4. Objectives](#4-objectives)
  - [4.1 Business Objectives](#41-business-objectives)
  - [4.2 Technical Objectives](#42-technical-objectives)
- [5. Scope](#5-scope)
  - [5.1 In-Scope](#51-in-scope)
  - [5.2 Out-of-Scope](#52-out-of-scope)
- [6. Assumptions](#6-assumptions)
- [7. Stakeholders & User Roles](#7-stakeholders-user-roles)
- [8. Proposed Solution Overview](#8-proposed-solution-overview)
- [9. System Architecture](#9-system-architecture)
- [10. Technology Decisions](#10-technology-decisions)
- [11. Key Technical Challenges & Proposed Solutions](#11-key-technical-challenges-proposed-solutions)
  - [11.1 Preventing Overbooking](#111-preventing-overbooking)
  - [11.2 Payment Idempotency](#112-payment-idempotency)
  - [11.3 Resilience to Payment Gateway Failures](#113-resilience-to-payment-gateway-failures)
  - [11.4 Offline Check-in and Reconciliation](#114-offline-check-in-and-reconciliation)
  - [11.5 Coordinating Scheduled Background Work](#115-coordinating-scheduled-background-work)
- [12. Non-Functional Requirements & Success Criteria](#12-non-functional-requirements-success-criteria)
- [13. Testing & QA Strategy](#13-testing-qa-strategy)
- [14. Deployment & DevOps Strategy](#14-deployment-devops-strategy)
- [15. Project Timeline & Milestones](#15-project-timeline-milestones)
- [16. Risk Assessment & Mitigation](#16-risk-assessment-mitigation)
- [17. Expected Deliverables](#17-expected-deliverables)
- [18. Conclusion](#18-conclusion)
- [19. References](#19-references)

---

## 1. Title Page / Project Information

| Field                    | Detail                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| :----------------------- | :----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Project Name**         | UniHub Workshop System                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| **Document Type**        | Project Proposal                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| **Document Version**     | v2.0                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| **Author**               | Nguyễn Thành Tiến (tiennt220805@gmail.com)                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| **Date**                 | 2026-07-17                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| **Source Specification** | `docs/requirement.md` (Software Requirements Specification)                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| **Engineering Context**  | A distributed, cloud-native event registration platform designed to support the concurrent registration workload defined in the SRS, deployed on a self-managed Kubernetes cluster (Minikube) and instrumented for operational observability. This proposal defines the problem, the proposed direction, the high-level architecture, and the delivery plan. Detailed implementation design is intentionally out of scope for this document and will be captured separately as the project progresses. |

---

## 2. Executive Summary

University A's "Skills and Career Week" currently relies on a manual, Google Forms-based registration process that cannot guarantee seat fairness and does not scale to the event's concurrent demand. As registration volume grows, this process produces duplicate confirmations, unreliable payment tracking, and a significant manual workload for the Organizing Committee (OC).

The **UniHub Workshop System** is proposed to replace this process with an automated platform covering the full workshop lifecycle: discovery, registration, payment, notification, and on-site check-in. The system is designed around three guarantees that the current process cannot provide — **fair, race-free seat allocation**, **consistent payment handling**, and **check-in that continues to function during network interruptions at the venue**.

Architecturally, the system is proposed as a **stateless, horizontally scalable backend service**, built with Java and Spring Boot, and deployed on Kubernetes so that capacity can be added by running additional instances rather than by depending on a single point of processing. Data consistency under concurrent access is addressed through a layered protection strategy spanning the request-handling, coordination, and storage layers, so that no single point of failure can result in an oversold seat. Time-sensitive and background work — seat-hold expiry, notifications, and payment reconciliation — is handled asynchronously so it does not block the core registration flow. Payment processing is integrated with a third-party gateway in a way that tolerates duplicate requests and isolates the rest of the system from gateway outages.

The system is also designed to be operable, not just functional: it includes structured monitoring and tracing so the OC and technical team can observe system health in real time, particularly during registration peaks, and its correctness is intended to be verified through automated testing, including tests that simulate the exact concurrent-access scenarios the SRS identifies as high-risk (e.g., many requests competing for a small number of remaining seats).

This proposal presents the problem, the objectives, the scope, the proposed architecture direction, the key technical decisions and their rationale, the major engineering challenges and how they will be addressed, and a phased delivery plan for building the system as a proof-of-concept on a local Kubernetes environment.

---

## 3. Background & Problem Statement

University A's "Skills and Career Week" is a five-day, multi-track event with 8 to 12 concurrently running workshops per day across multiple campus locations. Registration is currently handled entirely manually by the Organizing Committee through **Google Forms**, with seat confirmation and ticket delivery performed by hand via email.

This manual process exhibits several critical, compounding failures as the event scales:

- **No concurrency control.** Google Forms has no mechanism to prevent two students from being confirmed for the same limited seat; seat allocation fairness cannot be guaranteed once submission volume rises.
- **System collapse under peak load.** When registration opens, the OC observes traffic spikes far beyond what a forms-based workflow can absorb, resulting in failed submissions, duplicate entries, and OC staff manually reconciling spreadsheets under time pressure.
- **No payment integrity.** For paid workshops, there is no reliable linkage between payment confirmation and seat reservation — leading to scenarios where students pay without a guaranteed seat, or seats are held indefinitely without payment.
- **No offline resilience at check-in.** Lecture halls frequently suffer unstable network connectivity; a manual or purely online check-in process cannot function correctly in these conditions, causing entry delays and inaccurate attendance records.
- **No systemic notification pipeline.** Cancellations and reschedules must be communicated manually, which is slow, error-prone, and does not scale past a handful of events.
- **No integration with authoritative student data.** Verifying student identity against the university's legacy student management system requires manual cross-referencing, since no live integration exists.

These are structural limitations of the current process rather than isolated bugs to patch — they stem from using a tool with no concurrency, consistency, or offline guarantees for a problem that inherently requires all three. UniHub is proposed to address each of these failure modes directly, through a system designed from the outset for concurrent, fault-tolerant operation.

---

## 4. Objectives

### 4.1 Business Objectives

- **Full automation** of the workshop registration and ticket-issuance lifecycle, removing manual OC intervention from the critical path.
- **Fair seat allocation** — every seat is granted on a correctly-ordered, first-valid-claim basis, with no possibility of duplicate or conflicting confirmations.
- **Zero overselling**, guaranteed even under high concurrent load (many simultaneous requests for a small number of remaining seats).
- **Uninterrupted on-site check-in**, ensuring the event's physical operations are not blocked by transient campus network instability.
- **Reduced OC operational burden** through automated notifications, real-time reporting, and AI-assisted content generation.

### 4.2 Technical Objectives

- **Horizontal scalability**: the system must absorb the registration surge described in the SRS (approximately 12,000 concurrent users within a 10-minute window) without downtime or degraded correctness.
- **Operational observability**: system behavior and health must be visible in real time, and individual requests must be traceable across the system's processing stages.
- **Automated recovery**: application instances should recover from failure without manual operator intervention.
- **Verified correctness**: core business logic must be validated through automated testing, with particular emphasis on the concurrency-sensitive paths (seat reservation, payment).

---

## 5. Scope

### 5.1 In-Scope

- **Workshop Discovery** — browsing, filtering, and real-time seat-availability display.
- **High-Concurrency Registration** — free and paid ticket flows with a time-bound temporary seat hold.
- **Sandbox Payment Processing** — third-party payment gateway integration with reliable transaction handling and automated reconciliation.
- **Digital Ticket Generation** — unique, verifiable QR-code e-tickets.
- **Offline-Capable Check-in** — mobile scanning workflow that functions without network connectivity and reconciles centrally once restored.
- **Notification Pipelines** — extensible, asynchronous email/in-app notification delivery for registration confirmation and workshop cancellation/reschedule events.
- **AI Summarization** — automated generation of workshop promotional summaries from uploaded materials.
- **Student Batch Ingestion** — nightly, fault-tolerant synchronization of legacy student data with error isolation.
- **Fine-Grained Access Control** — permission-based access across Student, Organizer/Admin, and Check-in Staff roles.
- **Web Application** — a responsive client (React + TypeScript) covering the full Student journey (discovery, registration, payment, and viewing/downloading the issued QR e-ticket) and the Organizer/Admin catalog and reporting workflows described above.
- **Mobile Application** — a dedicated, staff-only client (React Native / Expo) implementing the offline-capable check-in requirement above: Check-in Staff use it exclusively to scan a Student's e-ticket at the venue entrance and record the check-in offline. Students do not install or use this application in any form.

### 5.2 Out-of-Scope

- **Public app-store distribution.** The Mobile Application (Section 5.1) is built and delivered as a functioning client for Check-in Staff, including the offline scanning and reconciliation workflow; publishing a signed build to the Apple App Store or Google Play Store is out-of-scope. The proof-of-concept is distributed through non-store channels (e.g., Expo preview/internal builds), consistent with the local, non-production deployment posture in Section 5.2's other items.
- **Real cloud production deployment.** Infrastructure is validated against a local Kubernetes (Minikube) cluster; deployment to a managed cloud provider is not part of this delivery. The one exception is AWS S3, used specifically as the object store for uploaded PDF brochures (see Section 6, Assumptions) — this is a scoped, single-purpose managed dependency, not a production cloud rollout of the system itself.
- **Real financial settlement.** Payment integration operates strictly in sandbox/test mode; no live monetary transactions or production PCI-compliance workstreams are in scope.

---

## 6. Assumptions

This proposal is made under the following assumptions. These define the boundary of responsibility for the project and should be revisited if any of them do not hold:

- The university will provide the nightly student data export in a consistent, agreed-upon format and delivery mechanism.
- A payment gateway with sandbox/test capabilities is available and accessible for integration during development.
- Outbound email delivery infrastructure (e.g., an SMTP provider or transactional email service) is available for the notification pipeline.
- Kubernetes is the target deployment environment for this phase of the project; no alternative orchestration platform is assumed.
- An AWS account with S3 access is available for storing Organizer-uploaded PDF brochures; this is the one managed cloud dependency used alongside the otherwise self-hosted, local Kubernetes topology.
- The project targets a functionally complete **proof-of-concept** deployed on a local cluster, rather than a production cloud rollout — performance and reliability targets are validated at this scale, not at live-production scale.
- The Organizing Committee will continue to own workshop content curation (speaker information, room assignments); the system automates process and data flow, not event content decisions.

---

## 7. Stakeholders & User Roles

| Role                  | High-Level Goal                                                                                                                                                                                                      |
| :-------------------- | :------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Student**           | Discover relevant workshops quickly, register with confidence that a successful confirmation means a guaranteed seat, and check in on-site without friction — even under poor network conditions.                    |
| **Organizer / Admin** | Manage the full workshop catalog (create, edit, cancel, reschedule) with minimal manual effort, and gain real-time visibility into registration and check-in metrics to make operational decisions during the event. |
| **Check-in Staff**    | Verify and admit students reliably at classroom entrances using a mobile scanning device, with zero dependency on continuous network availability during the scanning process itself.                                |

Access to functionality is governed by a permission-based access model, decoupling functional capability from role labels so new roles (e.g., Department Lead, Volunteer Staff) can be introduced without code changes.

---

## 8. Proposed Solution Overview

A student browses the workshop catalog and views up-to-date seat availability for each workshop. When registering, free workshops are confirmed immediately. For paid workshops, the student's seat is placed on a temporary hold while they complete payment; if payment is not completed within the allowed time window, the hold is automatically released so the seat becomes available to other students again, without requiring any manual action.

Once payment is confirmed — including in cases where the payment provider's confirmation is delayed or lost in transit — the system finalizes the registration and issues a digital ticket, and the student is notified through email and in-app notification. If an Organizer cancels or reschedules a workshop, all registered students are notified automatically without OC intervention.

On the event day, Check-in Staff scan each student's digital ticket at the classroom entrance. This check-in step is designed to continue working even if the venue's network connection is temporarily unavailable, with scan records synchronized back to the central system once connectivity returns. If a ticket is scanned more than once — whether by the same device or by different staff — only a single valid check-in is ultimately recorded.

Throughout this journey, the Organizing Committee has access to real-time registration and check-in reporting, giving visibility into event operations as they happen rather than after the fact.

---

## 9. System Architecture

The system is structured as a single deployable backend service, internally organized into clearly bounded business modules (User, Workshop, Registration, Payment, Check-in, Notification), each keeping its business logic independent of the specific infrastructure it relies on. This keeps the system easier to reason about and change, while leaving the option open to split individual modules into separate services later if needed.

All application instances are stateless with respect to user session — no instance holds user-specific state that another instance cannot also access — which is what allows the system to run as multiple parallel instances behind a load-balancing entry point, adding capacity by running more instances rather than by growing a single one.

```
               ┌───────────────────────────┐   ┌───────────────────────────┐
               │      Web Application        │   │     Mobile Application       │
               │  (Student discovery/reg./    │   │  (Check-in Staff ONLY —      │
               │   e-ticket, Organizer/Admin   │   │   offline QR scanning at     │
               │   dashboard)                  │   │   venue entrances)           │
               │  React + TS (Vite)           │   │  React Native (Expo) + TS    │
               └──────────────┬────────────────┘   └──────────────┬────────────────┘
                              │                                    │
                              └─────────────────┬──────────────────┘
                                                │
                                                ▼
                                   ┌────────────────────────┐
                                   │     Entry Point /        │
                                   │   Load Balancer          │
                                   │  (Ingress Controller)    │
                                   └────────────┬─────────────┘
                                                │
                    ┌───────────────────────────┼───────────────────────────┐
                    ▼                           ▼                           ▼
          ┌──────────────────┐       ┌──────────────────┐       ┌──────────────────┐
          │  Application       │       │  Application       │       │  Application       │
          │  Instance          │  ...  │  Instance          │  ...  │  Instance          │
          │  (stateless API)   │       │  (stateless API)   │       │  (stateless API)   │
          └─────────┬──────────┘       └─────────┬──────────┘       └─────────┬──────────┘
                    │                            │                            │
        ┌───────────┼────────────────────────────┼────────────────────────────┼───────────┐
        ▼           ▼                            ▼                            ▼           ▼
┌────────────────┐ ┌───────────────────┐ ┌────────────────────┐ ┌───────────────────────────┐
│  Coordination &  │ │  Asynchronous       │ │  Primary Data Store  │ │   Read Replicas            │
│  Caching Layer   │ │  Task Queue         │ │  (writes)             │ │   (reads)                   │
│  - Locking        │ │  - Notifications     │ │                       │ │                             │
│  - Rate limiting  │ │  - Reconciliation    │ │                       │ │                             │
│  - Read cache      │ │  - Delayed tasks     │ │                       │ │                             │
└────────────────┘ └───────────────────┘ └──────────┬───────────┘ └──────────────┬────────────┘
                                                     └────────── kept in sync ─────┘

        ┌──────────────────────────┐
        │  Object Storage            │  ◄── Organizer-uploaded PDF brochures
        │  (managed cloud storage)   │       (also read by the async summarization task)
        └──────────────────────────┘

        Cross-cutting: metrics & dashboards, request tracing, structured logging
        External dependencies: Payment gateway (isolated via fault-tolerance safeguards);
                                Object storage (managed cloud service, outside the local cluster)
```

At a component level:

- **Client applications** are two independent codebases, not one shared client, and they map to disjoint personas — no persona uses both: a **Web Application** (React + TypeScript, Vite) for the Student's entire journey (discovery, registration, payment, and viewing/downloading the e-ticket) and the Organizer/Admin dashboard, and a **Mobile Application** (React Native / Expo, TypeScript), used exclusively by Check-in Staff for offline QR scanning at the venue. The mobile client's offline requirement (Section 11.4) is why it is a separate, native-capable codebase rather than a responsive view inside the same web client — not a reflection of who uses it. Full stack detail is in Section 10.
- The **entry point** is the system's single point of contact with clients, distributing incoming traffic across all available application instances.
- **Application instances** contain the business logic for every module and can be scaled up or down independently of the data layer.
- The **coordination and caching layer** supports two responsibilities: protecting shared resources (such as seat counts) from conflicting concurrent updates, and reducing repeated load on the primary data store for frequently read, rarely changed information.
- The **asynchronous task queue** decouples time-consuming or time-delayed work — such as sending notifications or releasing an expired seat hold — from the main request path, so a student's registration request is never held up waiting for that work to complete.
- The **primary data store** separates write responsibility (a single authoritative point) from read responsibility (one or more replicas), so that heavy read traffic — such as students browsing the catalog — does not compete with the correctness-critical write path.
- **Object storage** holds uploaded promotional PDFs outside the transactional database, so large binary files never inflate the primary data store or its backups; the AI summarization step reads directly from this store rather than from a request payload.
- Cross-cutting concerns (monitoring, tracing, logging) span every component so that both routine operation and incidents are observable end-to-end.

---

## 10. Technology Decisions

This section explains the major architectural decisions behind the system, the alternatives considered, and the reasoning behind each choice. Specific product/library selections are included because they are the concrete expression of each decision, not because this document is an implementation guide.

| Decision                                                                              | Alternative Considered                                                              | Reason                                                                                                                                                                                                                                                                              |
| :------------------------------------------------------------------------------------ | :---------------------------------------------------------------------------------- | :---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Stateless Modular Monolith** (Java 21 / Spring Boot 3.x)                            | Microservices from day one                                                          | A single, well-modularized service is significantly faster to build and operate correctly at this project's scope, while the module boundaries keep the door open to splitting into microservices later if the system's scale or team structure warrants it.                        |
| **Relational database with a single write authority and read replicas** (PostgreSQL)  | A NoSQL document store                                                              | The system's core correctness requirements — no double-booked seats, no duplicate payments — depend on strong transactional guarantees that a relational database provides more directly than most NoSQL alternatives.                                                              |
| **A shared coordination and caching layer** (Redis)                                   | Relying on in-process/local locking only                                            | Because the system runs as multiple parallel instances, any coordination mechanism (such as a lock on seat availability) must be visible to all instances at once — a local, in-process lock cannot provide that.                                                                   |
| **An asynchronous task queue** (RabbitMQ)                                             | Handling all background work synchronously or via periodic polling                  | Seat-hold expiry and notification delivery are time-sensitive but should not block the request that triggered them; a message queue lets this work happen reliably in the background instead.                                                                                       |
| **Web client as a client-rendered SPA** (React + TypeScript, Vite; TanStack Query + Zustand for server/client state; Tailwind CSS + Shadcn UI; React Hook Form + Zod) | A server-rendered framework (e.g., Next.js), or a heavier global-state library (Redux) | The web surface is a permission-gated dashboard over a stateless JSON API (Section 9); a client-rendered SPA avoids operating a Node.js rendering tier the backend doesn't otherwise need. TanStack Query's cache/revalidate model maps directly onto the read-replica-plus-cache data the Backend API already exposes, and Zod validation mirrors the same contract the backend enforces, catching invalid input before it reaches the network. |
| **Mobile client as a single cross-platform codebase, staff-only** (React Native / Expo + TypeScript; TanStack Query + Zustand; Expo SQLite via Drizzle ORM; Expo Camera; JOSE + Expo SecureStore) | Separate native iOS/Android codebases, a browser-based PWA for check-in, or extending the Web Application with a scanning view | A single Expo codebase covers both platforms without maintaining two native codebases, consistent with this project's single-developer delivery constraint (Section 16). This client is scoped to Check-in Staff only — Students never install or authenticate into it, they only ever hold the Web-Application-issued e-ticket that gets scanned. Offline check-in (Section 11.4) is the defining requirement: Expo SQLite via Drizzle ORM gives the device a durable, queryable local queue for scan events that survives an app restart or a connectivity gap — a plain in-memory queue would not. JOSE performs the ticket-signature verification from Section 11.4 entirely on-device, with zero network round-trip; Expo SecureStore keeps the on-device verification key material and the client's auth token behind the platform's hardware-backed keystore rather than plain app storage. |
| **Container orchestration platform** (Kubernetes, run locally via Minikube)           | Docker Compose                                                                      | The project's reliability goals — automatic recovery from instance failure, and running multiple instances of both the application and the database — are native capabilities of an orchestration platform, and are the intended target environment even at proof-of-concept scale. |
| **Structured observability stack** (metrics dashboards + distributed request tracing) | Relying on application logs only                                                    | At the target scale, diagnosing an issue from logs alone across many parallel instances is impractical; dashboards and request-level tracing are necessary to understand system behavior during a live registration peak.                                                           |
| **Fault-isolation around the payment gateway** (circuit breaker pattern)              | Calling the gateway directly with retries only                                      | The SRS explicitly requires that a payment gateway outage must not degrade unrelated functionality; a fault-isolation mechanism is the standard way to contain that failure to the payment path only.                                                                               |
| **Managed object storage for uploaded files** (AWS S3)                                | Storing PDF brochures as BLOBs in PostgreSQL                                        | Large binary files degrade database performance and bloat backups; a dedicated object store keeps the transactional database focused on structured data while giving the AI summarization step direct, durable access to the source file.                                           |
| **Zero-Trust internal network via Service Mesh** (Istio + Envoy sidecar proxies)      | Trusting the cluster network as an implicit boundary (unencrypted internal traffic) | The SRS only requires encryption for client-facing traffic; this goes beyond that baseline deliberately, to demonstrate a defense-in-depth security posture as part of this project's portfolio scope — accepting the added resource and latency cost in exchange.                  |
| **Integration testing against real infrastructure** (containerized test dependencies) | In-memory test substitutes                                                          | The behaviors that matter most (locking, transaction isolation) are specific to the real database and coordination layer; an in-memory substitute would not reliably exercise these behaviors.                                                                                      |

---

## 11. Key Technical Challenges & Proposed Solutions

The SRS identifies several problems that are materially harder than typical CRUD functionality. This section describes each at the level of design intent — what guarantee is being provided and why the approach is sound — rather than as an implementation specification.

### 11.1 Preventing Overbooking

Seat allocation is the system's highest-risk correctness requirement: many students may attempt to claim the same limited seats at the same moment. The proposed approach applies protection at multiple layers rather than relying on a single mechanism — traffic is smoothed before it reaches the seat-reservation logic, concurrent attempts to modify the same seat's availability are coordinated so only one succeeds at a time, and the data store itself is configured to reject any update that was based on stale information. This layered approach means that even if one layer were bypassed or misconfigured, the others still prevent an oversold seat. Under this design, when many requests compete for a small number of remaining seats, exactly the available number succeed and the rest are informed clearly and immediately that the seat is no longer available.

### 11.2 Payment Idempotency

Because network conditions may cause a student's device to resend a payment or registration request, every such request carries a unique identifier generated by the client. The system guarantees that a given identifier is acted on exactly once — a repeated request with the same identifier returns the original result rather than being processed again. This prevents duplicate charges and duplicate registrations without requiring the client to avoid retries altogether.

### 11.3 Resilience to Payment Gateway Failures

If the payment gateway becomes slow or unavailable, the system is designed to detect this condition and temporarily stop sending it requests, rather than allowing failed or slow calls to accumulate and affect unrelated parts of the system. During this period, users attempting to pay receive a clear, immediate message, while browsing, check-in, and reporting continue to function normally. The system periodically re-checks whether the gateway has recovered and resumes normal payment processing automatically once it has.

### 11.4 Offline Check-in and Reconciliation

Each digital ticket is issued in a form that check-in devices can validate as authentic without needing a live network connection at the moment of scanning. Scan events are recorded locally on the device, including which ticket, which staff member, and when the scan occurred. Once connectivity is restored, scan records from all devices are synchronized to the central system, which reconciles them so that only a single valid check-in is retained per ticket — determined by which scan happened first — regardless of how many devices scanned the same ticket or in what order the data arrives.

### 11.5 Coordinating Scheduled Background Work

Because the application runs as multiple parallel instances, scheduled tasks (nightly student data ingestion, financial reconciliation, expired seat-hold cleanup) must not run redundantly on every instance at once. The system uses a shared coordination mechanism to ensure that a given scheduled task executes on exactly one instance per run, regardless of how many instances are active.

---

## 12. Non-Functional Requirements & Success Criteria

| Dimension                    | Success Criterion                                                                                                                                                                                                     |
| :--------------------------- | :-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Overbooking**              | 0% — zero seats sold or held beyond their configured capacity across all load-test scenarios.                                                                                                                         |
| **Peak Load Capacity**       | System remains available and correct while serving approximately 12,000 concurrent users within a 10-minute window, with 60% of traffic concentrated in the first 3 minutes.                                          |
| **Registration Latency**     | Sub-second (p95) response time for seat-reservation and registration-confirmation endpoints under peak load.                                                                                                          |
| **Concurrency Correctness**  | When many concurrent requests compete for a small number of remaining seats, exactly that number succeed; all others receive a clear, immediate "unavailable" response — no silent failures, no double-confirmations. |
| **Payment Consistency**      | Zero duplicate charges under retried/duplicated requests; zero transactions left permanently unresolved without eventual automated resolution.                                                                        |
| **Check-in Data Integrity**  | Exactly one valid check-in recorded per ticket after offline sync reconciliation, with zero data loss across intermittent connectivity.                                                                               |
| **Fault Isolation**          | Payment gateway outages produce zero cascading failure into non-payment functionality (browsing, check-in, reporting continue operating normally).                                                                    |
| **API Contract Consistency** | All API responses conform to consistent, predictable response and error formats.                                                                                                                                      |
| **Test Coverage**            | ≥80% code coverage on core business-logic services, verified through automated testing against real infrastructure.                                                                                                   |

---

## 13. Testing & QA Strategy

- **Unit Testing:** Core service-layer business logic (seat reservation, payment orchestration, access-control evaluation, reconciliation rules) is unit-tested in isolation, targeting a minimum of **80% code coverage** as a quality gate.
- **Integration Testing against real infrastructure:** Behaviors that depend on the specific characteristics of the database and coordination layer — transaction isolation, locking correctness, seat-release timing — are verified against real, ephemeral instances of the actual infrastructure used in production, rather than in-memory substitutes that would not reproduce these behaviors faithfully.
- **Concurrency & Load Testing:**
  - A scripted load test simulates the documented registration surge (~12,000 concurrent users within a 10-minute window) to validate stability and latency under realistic peak conditions.
  - A dedicated adversarial test drives a large number of simultaneous requests against a workshop with only a handful of remaining seats, confirming that exactly the available seats are granted and all other requests are rejected clearly — providing empirical evidence that the overbooking-prevention design (Section 11.1) holds under real concurrent pressure.
- **API Contract Validation:** Automated tests assert that API responses conform to their defined shapes across success, conflict, and failure paths.

---

## 14. Deployment & DevOps Strategy

The runtime environment is declared as version-controlled infrastructure configuration and run on a local Kubernetes cluster, so that the project exercises real orchestration behavior (health-based recovery, coordinated scaling) rather than a simplified local setup:

- **Multiple parallel application instances** run behind the entry point, each monitored so that an unhealthy instance is automatically restarted and traffic is only routed to instances that are ready to serve it.
- **Database components** are deployed with stable identity and persistent storage across restarts, which is necessary for a dependable write/read-replica setup.
- **Configuration and secrets** (environment settings, credentials, API keys) are managed separately from application code, so that sensitive values never live in the source tree.
- **A single external entry point** handles incoming traffic and distributes it across the available application instances.
- **Continuous Integration** runs automatically on every code change: code style/lint checks, unit tests, integration tests against real infrastructure, and packaging of a deployable build artifact — ensuring unverified code does not reach a deployable state.

---

## 15. Project Timeline & Milestones

The project is delivered incrementally, with each phase building on a working system rather than deferring integration to the end. Concurrency protection and resilience are treated as core functionality, not later hardening — they are introduced as soon as registration and payment exist.

| Phase                                           | Focus                                                                                                                                                         |
| :---------------------------------------------- | :------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **Phase 1 — Foundation**                        | Core domain model, authentication, and the Workshop module (creation, listing, detail view); Web Application scaffolding (React + TypeScript / Vite) against these first endpoints.                                                                  |
| **Phase 2 — Registration & Payment**            | Registration flow for free and paid workshops; payment gateway integration.                                                                                   |
| **Phase 3 — Concurrency Protection**            | Seat-hold lifecycle, overbooking prevention, and payment idempotency, validated against the scenarios in Section 11.1–11.2.                                   |
| **Phase 4 — Notifications**                     | Asynchronous, extensible notification pipeline for registration confirmations and workshop changes.                                                           |
| **Phase 5 — Offline Check-in**                  | Digital ticket issuance (issued to and displayed via the Web Application), offline-capable scanning, and check-in reconciliation; Mobile Application build (React Native / Expo), staff-only, covering Check-in Staff's offline scanning workflow.                                                                               |
| **Phase 6 — Observability**                     | Metrics, dashboards, and request tracing across the system.                                                                                                   |
| **Phase 7 — Kubernetes Deployment**             | Deployment of the full system to the local Kubernetes cluster, including automated recovery and CI/CD.                                                        |
| **Phase 8 — Web UI**                            | Redesign of the Web Application (design system with light and dark themes, student and administration screens, workshop management, cover images) and the minimal backend support those screens need; delivered before load testing so that Phase 9 runs against the final Web Application.|
| **Phase 9 — Performance & Concurrency Testing** | Load testing against the documented registration surge and the overbooking-adversarial scenario; final validation against the success criteria in Section 12. |

---

## 16. Risk Assessment & Mitigation

| Risk                                                                                                                                                                                       | Impact                                                          | Mitigation                                                                                                                                                                                                                                        |
| :----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | :-------------------------------------------------------------- | :------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **High local resource consumption** — running a full Kubernetes cluster, database replicas, messaging, monitoring, and a Service Mesh sidecar on every pod together on developer hardware. | Development environment instability, slow iteration.            | Constrain resource usage per component during day-to-day development; run the full topology at scale only for dedicated load-test sessions.                                                                                                       |
| **Learning curve for infrastructure-heavy tooling** — container-based integration testing, orchestration, and tracing are each non-trivial subsystems.                                     | Slower-than-planned delivery of infrastructure-adjacent work.   | Build and validate core business logic first against a simpler local setup, then progressively introduce infrastructure-realistic testing and deployment once functional correctness is established (reflected in the phased plan in Section 15). |
| **Coordination edge cases** — timing issues between the coordination layer and the storage-level safeguard during seat reservation.                                                        | Rare, hard-to-reproduce correctness issues under concurrency.   | Treat the storage-level safeguard as the non-negotiable final guarantee, independent of the coordination layer, so a coordination-layer issue cannot by itself cause an oversold seat.                                                            |
| **Payment gateway sandbox behavior may not fully mirror production timing.**                                                                                                               | Fault-isolation thresholds tuned in sandbox may not generalize. | Treat scheduled reconciliation, not gateway callbacks, as the authoritative source of truth for payment status, so correctness does not depend on realistic callback timing.                                                                      |
| **Single-developer delivery bandwidth** across a wide technical surface.                                                                                                                   | Schedule risk on lower-priority scope.                          | Prioritize registration, payment, and concurrency correctness as the critical path; treat AI summarization and additional notification channels as scope delivered after core correctness is proven.                                              |

---

## 17. Expected Deliverables

- **Source Code** — the complete backend application (organized by business module), the Web Application (React + TypeScript), and the Mobile Application (React Native / Expo).
- **Infrastructure Configuration** — deployment manifests for the full Kubernetes-based topology (application, data layer, entry point, configuration/secrets).
- **CI/CD Pipeline Definitions** — automated lint, test, and build pipeline.
- **Load Test Scripts** — covering both the registration-surge scenario and the overbooking-adversarial scenario.
- **Observability Configuration** — dashboards visualizing system health metrics.
- **Automated Test Suite** — unit and integration tests, including the concurrency-sensitive scenarios described in Section 11.
- **API Documentation** — endpoint contracts, request/response shapes, and access-control requirements per endpoint.
- **This Proposal Document** (`docs/proposal.md`) and the source `docs/requirement.md`, maintained as the project's reference documents at this stage.

---

## 18. Conclusion

UniHub Workshop System is proposed to replace a manual registration process with an automated platform designed around the specific guarantees the current process cannot provide: fair seat allocation under concurrent load, consistent payment handling, and check-in that continues to function without a reliable network connection. The proposed direction — a stateless, horizontally scalable backend with layered concurrency protection, asynchronous handling of background work, and isolation from third-party failures — is intended to be appropriate for the scale and constraints described in the SRS, while remaining feasible to deliver as a proof-of-concept within the project's scope. The phased delivery plan and the testing strategy are designed to validate these guarantees directly, rather than assume them.

---

## 19. References

- `docs/requirement.md` — UniHub Workshop System Software Requirements Specification (SRS), Parts 1–3.
- Spring Framework Documentation — Spring Boot 3.x, Spring Data JPA, Spring Security Reference Guides.
- Kubernetes Documentation — core orchestration concepts (Deployments, StatefulSets, ConfigMaps/Secrets, Ingress).
- Redis / Redisson Documentation — distributed coordination primitives.
- RabbitMQ Documentation — asynchronous messaging patterns.
- Resilience4j Documentation — circuit breaker pattern reference.
- Payment Gateway (Stripe) Documentation — idempotent request handling and webhook-based confirmation.
- OpenTelemetry Documentation — distributed tracing concepts.
- Prometheus & Grafana Documentation — metrics exposition and dashboard design guides.
- Testcontainers Documentation — integration testing against real, containerized infrastructure.
- React, Vite, TanStack Query, Zustand, Tailwind CSS, Shadcn UI, React Hook Form, Zod Documentation — Web Application stack references.
- Expo, React Native, Drizzle ORM, JOSE Documentation — Mobile Application stack references, including Expo SQLite and Expo SecureStore.

# SOFTWARE REQUIREMENTS SPECIFICATION (SRS)

## PROJECT: UNIHUB WORKSHOP SYSTEM

**[Project Artifact](https://claude.ai/code/artifact/b03b3a58-dbdf-4251-9e0f-75083fd94897?org=7c823fc2-de5f-4a3a-bfec-3521a95dc1fd)**

---

# PART 1: BUSINESS REQUIREMENTS

## 1.1. Background and Objectives

Every year, University A holds the "Skills and Career Week" event — a large-scale series of events spanning 5 days, with 8 to 12 workshops running simultaneously each day at multiple locations across campus.

Currently, workshop registration is managed manually by the Organizing Committee (OC) through Google Forms, with confirmation emails sent by hand. This manual process has become overwhelming and is no longer sustainable as the scale of the event continues to grow.

The **UniHub Workshop** project was created to digitize the entire lifecycle of a workshop: from scheduling and promotion, to registration, payment, and finally check-in at the classroom.

**Business Objectives:**

- Fully automate the registration and ticket-issuance process, eliminating manual work for the Organizing Committee.
- Ensure a smooth registration experience for students even during peak periods when thousands of people access the system at once.
- Guarantee absolute fairness and accuracy in seat reservation — no seat should ever be sold to more than one person.
- Provide a reliable check-in solution that remains stable even when the campus's internal network connection is unstable.
- Reduce the Organizing Committee's operational workload through automated notifications and consolidated reporting.

## 1.2. User Groups and Access Control

The system serves three main user groups, each granted permissions corresponding to their role:

| User Group | Description & Permissions |
| :--- | :--- |
| **Students** | Search for and view detailed workshop information; register and pay for tickets (free or paid); receive an e-ticket to self check-in at the event. |
| **Organizers/Admin** | Full management rights over workshops (create, edit, cancel, reschedule); upload promotional materials for the system to automatically summarize; monitor real-time registration and check-in reports. |
| **Check-in Staff** | Perform student check-in at classroom entrances using a mobile device; can continue working normally even during temporary network outages. |

Access control is not hard-coded by Role, but built around specific **Permissions** — for example: permission to create a workshop, permission to edit a workshop, permission to scan check-ins, permission to view reports, etc. Each Role is simply a collection of assigned Permissions. This approach allows the Organizing Committee to flexibly reconfigure the permissions of an existing role, or introduce new roles in the future (for example: Department Lead, Volunteer Support Staff...) without requiring the technical team to modify the system.

## 1.3. Functional Requirements by Business Area

### 1.3.1. Workshop Discovery and Registration

- Students can browse the list of workshops by schedule, and search/filter by topic, speaker, or location.
- The workshop detail page displays full speaker and location information, along with **real-time updated seat availability**, so students immediately know their chances of successfully registering.
- The system supports two registration types:
  - **Free tickets:** the seat reservation is confirmed immediately upon registration.
  - **Paid tickets:** students must complete online payment before the seat reservation is officially confirmed.
- **Temporary seat-hold lifecycle:** For paid tickets, as soon as a student begins the registration process, the system moves the corresponding seat into a **"Reserved"** state for a maximum of **15 minutes**, allowing the student time to complete payment. If the transaction is not completed within this window, the system must automatically release the seat back to the **"Available"** state so another student can register.
- **Payment transaction reconciliation:** In the event a payment transaction is interrupted (network loss, the student closing the app mid-transaction, etc.) such that the system never receives a final result from the payment gateway, the system must be able to automatically re-check the actual transaction status and update it accurately (successful or cancelled), ensuring no transaction is ever left "hanging" indefinitely and no mismatch occurs between the amount collected and the number of tickets confirmed.
- Every successful registration is issued a **unique e-ticket (QR code)**, used to verify the student's identity at check-in.
- The system must guarantee absolutely no **overselling of seats** for any workshop, even when a very large number of people register at the same moment.

### 1.3.2. User Notifications

- Upon successful registration, students must immediately receive a confirmation notification with their e-ticket via email and an in-app notification.
- When the Organizing Committee cancels or reschedules a workshop, all registered students must be notified automatically and promptly.
- The notification system must be designed so that new notification channels can be **added in the future** (e.g., SMS, Zalo, Telegram...) without affecting existing channels or requiring changes to other business modules of the system.

### 1.3.3. Workshop Administration

- The Organizing Committee has full rights to create, edit, cancel, or reschedule workshops.
- When a workshop is cancelled or rescheduled, the system must automatically trigger the notification process to all affected students, with no manual action required from the Organizing Committee.
- The Organizing Committee needs clear, visual reports and statistics on registration counts and real-time check-in rates for each workshop.

### 1.3.4. AI-Powered Content Summarization

- The Organizing Committee can upload a promotional document (PDF format) for a workshop.
- The system automatically reads the document's content and generates a short, engaging summary to display on the workshop's detail page, reducing the content-editing effort required from the Organizing Committee.

### 1.3.5. On-Site Check-in

- Check-in staff use a mobile device to scan students' e-tickets at classroom entrances.
- Because network quality in lecture halls is often unstable, check-in **must remain functional even when the device temporarily loses network connectivity**, and check-in data must automatically sync to the central system as soon as connectivity is restored — with no data loss and no duplication.

### 1.3.6. Student Data Synchronization

- Since the university's current student management system (legacy system) has no direct connectivity, student data is exported by the university on a nightly basis as a data file.
- UniHub must automatically ingest, process, and update this data into the system every night to support identity verification during registration, with no manual intervention required.
- Processing this data must not disrupt or affect the system's operation for users currently being served.

## 1.4. Expected Operating Scale

- At the moment registration opens, the system must be able to serve approximately **12,000 concurrent users within the first 10 minutes**, with roughly 60% of that traffic concentrated within the first 3 minutes.
- Workshops with limited capacity (e.g., 60 seats) may receive hundreds of registration requests submitted at the exact same moment.
- The system must remain stable and must not become unavailable or "crash" during the peak periods described above.

## 1.5. Key Business Constraints

- A seat must never be sold to or held by more than one student (no overbooking).
- A student must never be charged twice, nor should two transactions ever be created for the same registration request, even if the student repeatedly clicks the register button due to network lag.
- A seat left in the "Reserved" state for more than 15 minutes without completed payment must be automatically released so as not to cost other students the opportunity to register.
- A payment transaction left abnormally long in the "Pending" state (with no final result received from the payment gateway) must be automatically reviewed and resolved by the system, and must never be left outstanding in a way that causes financial discrepancies.
- If the third-party payment gateway experiences an outage, functions unrelated to payment (browsing listings, check-in, etc.) must continue to operate normally, without being affected in a cascading manner.
- Offline check-in data, once synced to the system, must be reconciled so that only **a single valid check-in** is recorded per ticket, based on the earliest scan timestamp, discarding all duplicate scans.
- If the nightly student data sync encounters malformed or duplicate records, this must not disrupt the entire process; erroneous records must be separated out for the Organizing Committee/administrators to review manually afterward.

---

# PART 2: TECHNICAL REQUIREMENTS

## 2.1. Codebase Organization by Business Module

- The backend system must be organized into clearly separated business modules (e.g., User, Workshop, Registration, Check-in, Notification), with each module encapsulating its own business logic and minimizing cross-module dependencies.
- The core business logic of each module must be completely decoupled from external infrastructure components (data storage, email delivery services, payment gateways, etc.), so that changes to these infrastructure components in the future do not affect the business logic.
- The notification module must be designed as "pluggable": new notification channels can be added without modifying the core source code of other business modules.

## 2.2. Scalability and Concurrent User Handling

- The system must be able to run across **multiple application processes/servers operating in parallel** to share the load, rather than depending on a single processing point.
- A component must sit in front of the application servers, acting as the single entry point and evenly distributing incoming traffic to the servers behind it.
- Application servers must not store user login/session state locally on any individual machine — session state must be managed in a way that allows any server to receive and process any user's request, regardless of which server handled that user's previous request.

## 2.3. High-Load Data Storage Architecture

- The primary data store must separate responsibilities: **a single point that accepts write operations** (ensuring correctness and consistency), and **one or more points that serve read operations**, kept in near real-time sync from the write point, in order to reduce load on the primary write point when read query volume is very high.
- The backend application must be able to automatically route traffic: read-only requests are directed to the read-serving point, while requests involving writes/updates/deletes are directed to the primary write point.

## 2.4. Caching for Low-Change Data

- Static or infrequently changing workshop information (description, location, room configuration, etc.) must be stored in an intermediate caching layer, so that when students browse listings or view workshop details during peak periods, the system does not need to query the primary data store directly for every request.
- Data held in the cache must be refreshed/updated appropriately whenever the underlying source data changes, to avoid displaying outdated information to users.

## 2.5. Abuse Prevention and Access-Rate Control

- The system must have a mechanism to limit the maximum number of requests a given user/device can send within a specified time window, in order to prevent spam behavior, automated scripts, or attacks that overload the system at the moment registration opens.
- This rate-limiting mechanism must be applied consistently across all application servers running in parallel, not just locally on each individual machine.

## 2.6. Resolving Concurrent Seat-Reservation Conflicts (Anti-Overbooking)

- When multiple registration requests compete for the same limited seat at the same moment, the system must have a mechanism to guarantee that **exactly one request is confirmed successful**, with all remaining requests correctly rejected or informed that the seat is no longer available.
- This conflict-resolution mechanism must function correctly in an environment with multiple application servers running in parallel — meaning that the temporary lock/hold on a seat must be recognized consistently across all servers, so that two different servers can never simultaneously confirm success for the same final seat.
- The primary data store must enforce a sufficiently strict level of transactional consistency to prevent reading incomplete data or non-repeatable reads during the seat-reservation process.
- In addition to the distributed locking mechanism at the application layer described above, the primary data store must include an additional **final safeguard at the storage layer** to protect seat data: every seat record carries a version number, and any update operation is only accepted if the version being updated matches the version currently present in the data store (an approach known as optimistic concurrency control — Optimistic Locking). This ensures that even if the application-layer distributed locking mechanism fails or is interrupted, the system will still never allow two requests to overwrite each other and corrupt the seat-availability count.

## 2.7. Fault Tolerance for Third-Party Service Dependencies

- If the third-party payment service experiences an outage or prolonged slow/failed responses, the system must detect this condition and **temporarily stop calling that service** for a period of time, rather than continuing to send requests and causing the entire system to hang or become congested as a result.
- While the payment service is experiencing an outage, the system must respond to users with a clear, friendly message, while all functions unrelated to payment continue to operate normally.
- The system must have a mechanism to automatically attempt reconnecting to the third-party service after a period of time, restoring normal payment operation once that service becomes stable again.

## 2.8. Preventing Duplicate Transaction Processing (Idempotency)

- Every registration/payment request from a user must be tagged with a unique identifier generated on the client side.
- If the system receives multiple requests bearing the same identifier (due to the user repeatedly clicking a button, or the request being resent because of network lag), the system must execute the payment/registration logic **exactly once**; any subsequent duplicate requests must return the result of the original processing without re-executing the logic.
- This identifier only needs to be retained for a reasonable period sufficient to cover the risk of duplicate submission (e.g., a few minutes) — it does not need to be stored permanently.

## 2.9. Offline Check-in Data Synchronization and Reconciliation

- The mobile check-in application must allow ticket verification and scan recording based on data already available locally on the device, even when there is no network connection at the time of scanning.
- Every offline scan must be recorded with complete information: which ticket, which staff member performed the scan, the scan status, and the exact scan timestamp from the device.
- Once the device regains network connectivity, its entire offline scan history must be automatically uploaded to the central system for reconciliation.
- The central system must be able to accept multiple batches of scan data from multiple devices concurrently, reconcile them, and accept only **the first valid check-in** for each ticket (based on the earliest scan timestamp), discarding duplicate scans that result from repeated scanning or from multiple staff members scanning the same ticket.

## 2.10. Background Task Processing and Scheduled Data Synchronization

- The nightly student data synchronization process must handle data through a sequential, step-by-step validation pipeline: verifying the format of each record before writing it into the primary system.
- Valid records are updated normally; invalid or duplicate records must be separated out into a dedicated error store for administrators to review and handle manually afterward — **a handful of faulty records must never be allowed to halt the entire synchronization process**.
- Because the system runs across multiple application servers in parallel, scheduled background tasks (such as the nightly data sync) must guarantee that **only a single server executes the task at any given time**, preventing the task from running redundantly across multiple machines at once and causing data inconsistencies.
- **Automatic release of expired temporary seat holds:** releasing a seat after its 15-minute hold period expires (see section 1.3.1) must be carried out asynchronously and must never block the main processing flow of a registration request. This can be implemented using a delayed message queue (Delay Queue/TTL Message, e.g., on RabbitMQ) or by listening for seat-hold key-expiration events (a Key Expiration Listener on Redis) to automatically trigger seat release the moment the hold period ends.
- **Automated financial reconciliation:** a scheduled background task (cronjob) must scan for transactions that have remained in the "Pending" state for more than 15 minutes, proactively call the payment gateway's status-query API (e.g., Stripe, Momo) to check the transaction, and automatically update the corresponding ticket status (Successful/Cancelled) — ensuring financial data between UniHub and the payment gateway always stays consistent, even when no webhook response is ever received.

## 2.11. General Reliability and Security Requirements

- All communication between the client (web/mobile) and the system must be encrypted in transit.
- User authentication must function consistently across all application servers, without depending on state stored locally on any single specific server.
- E-tickets (QR codes) must contain encrypted/protected information so they cannot be forged or copied for reuse by another person.
- The system must be able to record logs (audit trail) for critical operations (registration, payment, check-in, workshop schedule changes) to support investigation when needed.
- **Fine-Grained Role-Based Access Control (RBAC):** the database must be designed with a three-way mapping structure between Users, Roles, and Permissions (`users` – `roles` – `permissions`), allowing a single role to be assigned multiple specific permissions (e.g., `WORKSHOP_CREATE`, `WORKSHOP_EDIT`, `CHECKIN_SCAN`, `REPORT_VIEW`) and to be reconfigured without any source code changes.
- The authentication token (JWT) issued to the client must carry the user's list of specific authorities, not merely their role name.
- At the API layer, access control checks for each endpoint must be performed based on specific permissions (e.g., checking `hasAuthority('WORKSHOP_CREATE')`) rather than hard-coded role checks, ensuring flexibility and fine-grained security as the system expands with new roles in the future.

---

# PART 3: OPERATIONS, OBSERVABILITY & QUALITY ASSURANCE REQUIREMENTS

> This section describes the requirements for the system to meet production-grade operational standards: observable, traceable when incidents occur, rigorously tested, and deployed using a modern infrastructure model on Kubernetes.

## 3.1. Observability Requirements

- **Correlation ID (Trace ID) integration:** Every request entering the system through the Nginx/Ingress Load Balancer must be tagged with a unique identifier (Correlation ID) at the header level. This identifier must be propagated throughout every processing layer of the Spring Boot Nodes, through asynchronous tasks in RabbitMQ, and all the way to log output (files or database), ensuring that the entire processing flow of any given request can be traced even as it passes through multiple different components.
- **Distributed Tracing (OpenTelemetry):** Integrate an OpenTelemetry Agent into the Spring Boot Instances to automatically collect trace information for database queries, third-party API calls (Stripe/Momo), and messages sent through RabbitMQ, enabling fast identification of bottlenecks or failures when incidents occur in the multi-node distributed environment.
- **Metrics & Dashboard (Prometheus + Grafana):**
  - Use Spring Boot Actuator to expose operational metrics: JVM memory, CPU, active database connections, request latency, and RabbitMQ queue depth.
  - Configure Prometheus to periodically pull these metrics.
  - Design a Grafana dashboard suite that visualizes these metrics in real time, allowing the Organizing Committee and operations team to easily monitor system health, especially during registration peak periods.

## 3.2. Quality & Performance Testing Requirements

- **Unit & Integration Testing (JUnit 5 & Mockito):** Core business-logic classes (Core Service) must achieve a minimum code coverage of **80%**.
- **Integration Testing with Testcontainers:** Testcontainers must be used to spin up real containers of PostgreSQL, Redis, and RabbitMQ during integration test runs — in-memory fake databases (e.g., H2) must never be used — to ensure data-layer-specific behaviors (Transaction Isolation Levels, locking mechanisms, etc.) are verified with 100% accuracy against real production-like conditions.
- **Concurrency & Load Testing (via K6/JMeter):**
  - Build a load-testing scenario using K6 (or JMeter) to simulate the surge of **12,000 concurrent users** described in section 1.4.
  - Establish a concurrency test scenario (e.g., 500 Virtual Users) all competing for the last 10 remaining seats of a single workshop, in order to prove the correctness of the anti-overbooking mechanism described in section 2.6: the system must return an HTTP 409 (Conflict) status code or an accurate "sold out" message for requests arriving afterward, guaranteeing that no transaction can slip past either of the two protection layers — Distributed Locking and Optimistic Locking.

## 3.3. Kubernetes Deployment & DevOps Process Requirements

- **Orchestration and management via Kubernetes (Minikube):** Instead of using plain Docker Compose, the system must be packaged and run on a local Kubernetes cluster (using Minikube). A complete set of configuration manifests (K8s YAML files) must be provided, including:
  - **Deployments & Pods:** Configuration to run at least 2 Spring Boot Pods (API instances) in parallel, with self-healing capability via Liveness and Readiness Probes.
  - **StatefulSets:** Applied to manage PostgreSQL data under a Master-Slave Replication model in a stable manner, ensuring each Pod's network identity and attached storage remain consistent even when the Pod restarts.
  - **ConfigMaps & Secrets:** Centralized management of environment configuration (ConfigMaps) and sensitive information (Secrets) such as database passwords, JWT secret keys, and third-party API keys, kept entirely separate from the application source code.
  - **Ingress Controller:** Configure an Ingress (e.g., Nginx Ingress) to act as the single entry point, performing load balancing to distribute requests to the Spring Boot Pods behind it.
- **Continuous Integration (CI) automation via GitHub Actions:** Set up an automated pipeline so that on every push or pull request event to the main branch, GitHub Actions must automatically:
  1. Check code syntax/formatting (Lint).
  2. Run unit tests.
  3. Run integration tests (with Testcontainers).
  4. Package the application into a Docker image and push it to Docker Hub.

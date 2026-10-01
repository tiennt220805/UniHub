<div align="center">

# UniHub

**A university workshop platform - from discovery and registration to payment and offline on-site check-in.**

_Author: **Nguyễn Thành Tiến**_

<br/>

![Java](https://img.shields.io/badge/Java-21-ED8B00?style=flat-square&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3-6DB33F?style=flat-square&logo=springboot&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-4169E1?style=flat-square&logo=postgresql&logoColor=white)
![Redis](https://img.shields.io/badge/Redis-DC382D?style=flat-square&logo=redis&logoColor=white)
![RabbitMQ](https://img.shields.io/badge/RabbitMQ-FF6600?style=flat-square&logo=rabbitmq&logoColor=white)

![React](https://img.shields.io/badge/React-61DAFB?style=flat-square&logo=react&logoColor=black)
![TypeScript](https://img.shields.io/badge/TypeScript-3178C6?style=flat-square&logo=typescript&logoColor=white)
![React Native](https://img.shields.io/badge/React_Native-Expo-000020?style=flat-square&logo=expo&logoColor=white)
![Kubernetes](https://img.shields.io/badge/Kubernetes-326CE5?style=flat-square&logo=kubernetes&logoColor=white)

![Status](https://img.shields.io/badge/status-design%20%26%20scaffolding-blue?style=flat-square)

</div>

---

## Overview

UniHub replaces manual, spreadsheet-driven workshop management with a system designed around three guarantees a spreadsheet cannot give: **fair, race-free seat allocation**, **consistent payment handling**, and **check-in that keeps working when the venue loses network**.

It combines three architectural styles that hold at the same time: a **Modular Monolith** (one deployable, six clearly-bounded modules), **Clean / Hexagonal Architecture** inside each module (dependencies point inward - the domain never touches a framework or I/O), and **Event-Driven Architecture** for cross-module effects (through a transactional outbox). Simple to run today, with clean seams to split into services later.

## Engineering Highlights

- **Layered anti-overbooking** - a Redis distributed lock is advisory only; the real accept/reject decision is PostgreSQL optimistic concurrency (version + check constraint). No single layer is trusted to be correct on its own.
- **Transactional outbox** - the business write and the intent-to-publish commit in the same transaction, so no event is ever lost or published for a rolled-back change.
- **Offline-first check-in** - QR e-tickets are self-verifying via on-device signature checks (no server round-trip); scans queue durably on the device and reconcile later, earliest-timestamp-wins.
- **Permission-based RBAC** - every check asks "does this user have permission X", never "is the role Y"; the JWT carries the resolved permission list.
- **Enforced boundaries** - module and layer rules are verified in the build by ArchUnit, so the architecture can't quietly erode.
- **Tested against real infrastructure** - integration tests run on real PostgreSQL, Redis and RabbitMQ via Testcontainers, because the behaviours under test (locking, isolation, redelivery) are exactly what an in-memory fake would hide.

## Tech Stack

| Layer                 | Choices                                                                              |
| --------------------- | ------------------------------------------------------------------------------------ |
| **Backend**           | Java 21 · Spring Boot 3 · Gradle - Modular Monolith · Clean/Hexagonal · Event-Driven |
| **Data & messaging**  | PostgreSQL (master/replica) · Redis (Redisson) · RabbitMQ · AWS S3                   |
| **Web**               | React · TypeScript · Vite · TanStack Query · Tailwind · shadcn/ui                    |
| **Mobile (check-in)** | React Native (Expo) · SQLite/Drizzle · offline-first                                 |
| **Platform**          | Kubernetes · Istio (mTLS) · Prometheus · Grafana · OpenTelemetry                     |
| **Quality**           | JUnit · Testcontainers · ArchUnit · contract-first OpenAPI 3.1                       |

## Repository

```
UniHub/
├── apps/
│   ├── backend/          # Java 21 · Spring Boot · Gradle (Modular Monolith)
│   └── web/              # React · TypeScript · Vite
├── packages/
│   └── api-contract/     # OpenAPI 3.1 - the contract-first HTTP surface
└── docs/                 # SRS · proposal · C4 architecture · technical design
```

The `docs/` corpus is the source of truth: requirements, the rationale behind each decision, C4 diagrams, and physical-level design (schema, Redis keys, messaging topology).

## Status

This is a **design-first** project. The requirements, architecture and API contracts are complete, and the backend/web project skeletons (including the backend's ArchUnit boundary tests) are in place; no entity, migration, or business endpoint has been implemented yet. **Setup and run instructions will be added as the codebase lands.**

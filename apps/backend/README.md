# UniHub Backend API

The Backend API: a stateless **Modular Monolith** on Java 21 and Spring Boot 3.3, with six business modules (User, Workshop, Registration, Payment, Check-in, Notification), PostgreSQL, Redis, RabbitMQ and S3-compatible object storage. Background work (the outbox relay and the queue consumers) runs inside the same deployable.

Design: [`docs/architecture.md`](../../docs/architecture.md). Coding standard: [`docs/guidelines/backend-coding-standards.md`](../../docs/guidelines/backend-coding-standards.md). HTTP contract: [`packages/api-contract`](../../packages/api-contract).

## Prerequisites

- JDK 21
- Docker with Compose (local infrastructure, and the integration tests)
- Node.js 22 (used by the ticket-key script and the contract check)

## Setup

All commands run from this directory (`apps/backend`).

### 1. Start the infrastructure

```sh
docker compose up -d
```

PostgreSQL 16, Redis 7, RabbitMQ 3.13, Mailpit (SMTP catcher), MinIO, Prometheus, Grafana and Tempo, all bound to `127.0.0.1`. Every default is a throwaway local value, so no setup is needed for this step.

### 2. Configure

```sh
cp env.example .env                      # .env is git-ignored, never commit it
scripts/generate-ticket-key.sh --write   # fills UNIHUB_TICKET_SIGNING_KEY in .env
```

Then edit `.env` and set:

- `UNIHUB_SECURITY_JWT_SECRET`: a random string of at least 32 bytes, for example `openssl rand -base64 48`.
- The four `UNIHUB_DEV_SEED_*_PASSWORD` variables: passwords for the dev accounts. A blank one seeds nothing, and there is no default.

`env.example` already points everything else at the compose stack: PostgreSQL and RabbitMQ (`SPRING_DATASOURCE_*`, `SPRING_RABBITMQ_*`), Redis, SMTP and MinIO. If you change a port or credential in the compose file, change it here too. The application will not start without a JWT secret, a ticket signing key, a Redis address and an SMTP host.

### 3. Migrate

```sh
./gradlew flywayMigrate
```

The application never migrates its own schema at boot: migrations are versioned and run as an explicit step before rollout. The task uses the compose PostgreSQL defaults.

## Run

```sh
set -a; . ./.env; set +a
SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun
```

- API: `http://localhost:8080`
- Health and Prometheus metrics: `http://localhost:8081/actuator/health` (the management port; never route it from outside). Port 8081 is also Expo's default Metro port; when you run the mobile app, start Metro on another port (`npx expo start --port 8082`) rather than moving the backend, because the compose Prometheus scrapes the backend on 8081.
- The `dev` profile seeds `student1..3@dev.unihub.local`, `organizer1@dev.unihub.local`, `admin1@dev.unihub.local` and `staff1@dev.unihub.local`, each with the password you set above. The profile does not exist in a deployed environment.

| Service | URL |
| --- | --- |
| Mailpit (emails the system sends) | http://127.0.0.1:8025 |
| RabbitMQ management | http://127.0.0.1:15672 |
| MinIO console | http://127.0.0.1:9001 |
| Prometheus · Grafana · Tempo | http://127.0.0.1:9090 · http://127.0.0.1:3000 · http://127.0.0.1:3200 |

Traces are exported only when `MANAGEMENT_OTLP_TRACING_ENDPOINT` is set (with compose's Tempo: `http://localhost:4318/v1/traces`).

## Test

| Command | What it runs |
| --- | --- |
| `./gradlew test` | Unit tests and the ArchUnit architecture rules. No infrastructure needed. |
| `./gradlew integrationTest` | The integration suite. Needs Docker: Testcontainers starts real PostgreSQL, Redis, RabbitMQ and MinIO. It never uses the compose stack above. Takes many minutes. Needs a full git clone (not `--depth 1`): it also builds the previous release from git history for the migration-compatibility check. |
| `./gradlew check` | Spotless, Checkstyle, tests and the coverage gate. |
| `./gradlew spotlessApply` | Fixes formatting. |
| `scripts/contract-diff.sh` | Checks that what the controllers expose matches the OpenAPI contract in `packages/api-contract`. Needs Java 21, Node 22 and Docker. |

Test strategy and the requirement-to-test matrix are in [`docs/testing/`](../../docs/testing).

## Build the image

```sh
docker build -t unihub-backend:dev .
```

The build context is this directory. The image is multi-stage, runs as a non-root user, and CI builds it for `amd64` and `arm64`. To deploy it, see the Kubernetes section of the [root README](../../README.md).

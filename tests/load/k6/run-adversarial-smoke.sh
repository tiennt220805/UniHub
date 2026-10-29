#!/usr/bin/env bash
# Scenario B smoke, once, locally (performance-load.md §2.2; Phase 3 checklist 5.1, M-36). Not in CI.
#
# Needs: docker (the `docker compose up -d` stack of apps/backend, container unihub-postgres), k6, node,
# JDK 21 for Gradle, and the application already running against $DB at $BASE_URL with the SAME JWT
# secret. The compose RabbitMQ user is unihub/unihub_dev_only, not Spring's guest/guest default, so the
# application must be told. This is the exact sequence that was run on 2026-10-28:
#
#   export JAVA_HOME=/path/to/jdk-21
#   export UNIHUB_SECURITY_JWT_SECRET="$(openssl rand -hex 40)"          # >= 64 bytes, never committed
#   docker exec unihub-postgres psql -U unihub -d postgres -c 'CREATE DATABASE unihub_k6_smoke'
#   (cd apps/backend && POSTGRES_DB=unihub_k6_smoke ./gradlew flywayMigrate)
#   (cd apps/backend && \
#      SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/unihub_k6_smoke \
#      SPRING_DATASOURCE_USERNAME=unihub SPRING_DATASOURCE_PASSWORD=unihub_dev_only \
#      UNIHUB_REDIS_ADDRESS=redis://127.0.0.1:6379 \
#      UNIHUB_NOTIFICATION_SMTP_HOST=127.0.0.1 UNIHUB_NOTIFICATION_SMTP_PORT=1025 \
#      SPRING_RABBITMQ_HOST=127.0.0.1 SPRING_RABBITMQ_USERNAME=unihub SPRING_RABBITMQ_PASSWORD=unihub_dev_only \
#      ./gradlew bootRun)                                                  # leave it running
#
# Then, in another shell with the same UNIHUB_SECURITY_JWT_SECRET:
#
#   tests/load/k6/run-adversarial-smoke.sh
#
# Afterwards stop the application and drop the throwaway database:
#
#   docker exec unihub-postgres psql -U unihub -d postgres -c 'DROP DATABASE unihub_k6_smoke'
#
# The secret comes from the environment only; the tokens it signs are written to a git-ignored file.
set -euo pipefail
cd "$(dirname "$0")"

: "${UNIHUB_SECURITY_JWT_SECRET:?set the same secret the application runs with}"
DB="${DB:-unihub_k6_smoke}"
PG="${PG_CONTAINER:-unihub-postgres}"
PGUSER_="${POSTGRES_USER:-unihub}"
BASE_URL="${BASE_URL:-http://localhost:8080}"
CAPACITY="${CAPACITY:-100}"
WINNERS="${WINNERS:-10}"
CONTENDERS="${CONTENDERS:-500}"
RUN_TAG="$(date +%s | tail -c 7)"
WORKSHOP_ID="$(uuidgen | tr 'A-Z' 'a-z')"

psql_() { docker exec -i "$PG" psql -U "$PGUSER_" -d "$DB" -v ON_ERROR_STOP=1 -q "$@"; }

echo "[1/5] seed: workshop $WORKSHOP_ID, capacity $CAPACITY, $WINNERS seats left, $CONTENDERS contenders"
psql_ -v run_tag="$RUN_TAG" -v workshop_id="$WORKSHOP_ID" -v capacity="$CAPACITY" \
      -v remaining="$WINNERS" -v contenders="$CONTENDERS" < seed.sql

echo "[2/5] mint $CONTENDERS tokens offline (permissions read from the Student role)"
PERMISSIONS="$(psql_ -At -c "SELECT string_agg(p.code, ',') FROM roles r
  JOIN role_permissions rp ON rp.role_id = r.id JOIN permissions p ON p.id = rp.permission_id
  WHERE r.name = 'Student'")"
psql_ -At -c "SELECT id FROM users WHERE email LIKE 'k6-${RUN_TAG}-c%@loadtest.local' ORDER BY email" > ids.txt
PERMISSIONS="$PERMISSIONS" node mint-tokens.mjs ids.txt > tokens.json

echo "[3/5] the application answers at $BASE_URL"
curl -fsS -o /dev/null "$BASE_URL/api/v1/workshops" || { echo "application not reachable" >&2; exit 1; }

echo "[4/5] k6"
set +e
BASE_URL="$BASE_URL" WORKSHOP_ID="$WORKSHOP_ID" WINNERS="$WINNERS" CONTENDERS="$CONTENDERS" \
  k6 run adversarial.js
K6_EXIT=$?
set -e

echo "[5/5] SQL invariant (the pass condition; both lists must be empty)"
psql_ -v workshop_id="$WORKSHOP_ID" < verify.sql
echo "k6 exit code: $K6_EXIT"
exit "$K6_EXIT"

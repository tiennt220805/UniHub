#!/usr/bin/env bash
# The rollout rehearsal of INV-15 on the cluster (Phase 7 checklist 8.4, M-58, D88): the previous release serves
# traffic while the migration of the new release runs, and the new release is then rolled out over it — under
# mesh STRICT, with the Flyway Job a sidecar-injected Job. The per-push counterpart is MigrationCompatibilityIT
# (child JVMs, no cluster); this adds what only a cluster has: the Job under injection, a rolling update behind the
# gateway, and the broker both releases share.
#
#   k8s/scripts/rollout-rehearsal.sh <previous-image> <new-image>
#
# e.g. a CI image pair `thanhtien2208/unihub-backend:<sha-of-N-1>` and `…:<sha-of-HEAD>`, or two images loaded into the
# node by hand (`minikube image load`) when the previous release has none on the registry.
#
# DESTRUCTIVE to the development data of `unihub`: the API is scaled to 0, the PostgreSQL Master and Replica lose their
# volumes (so the schema starts empty), and Redis and RabbitMQ are restarted (so the broker holds no queue of the new
# release). It restores nothing; run `make deploy-loadtest TAG=<sha>` afterwards for a normal state. It asks for
# nothing: do not run it on a cluster you want to keep.
#
# Steps, each timed: (1) empty database migrated to the schema BEFORE the newest migration (Flyway `-target`, from the
# newest migration file's version minus one), (2) the previous release deployed and probed, (3) the migration Job of
# HEAD run while it serves, 0 failed probe requests, (4) the new release rolled out under the same probe, 0 failed
# probe requests, (5) the broker's queues and the log of both releases read for topology errors. Probe traffic:
# the three seeded students, GET /api/v1/workshops every 0.25 s through the gateway (the previous release has no
# staff accounts). Pass: every step 0 non-2xx probe responses. What it finds besides is printed, not hidden.
set -euo pipefail
cd "$(dirname "$0")/../.."   # repository root
# shellcheck source=k8s/scripts/lib.sh
. k8s/scripts/lib.sh

OLD="${1:-}"; NEW="${2:-}"
[ -n "$OLD" ] && [ -n "$NEW" ] || { echo "usage: $0 <previous-image> <new-image>" >&2; exit 2; }
for tool in kubectl curl python3; do command -v "$tool" >/dev/null || { echo "$tool is not installed" >&2; exit 1; }; done
fail=0
ok()  { echo "ok    $*"; }
bad() { echo "FAIL  $*"; fail=1; }
work="$(mktemp -d)"
cleanup() { lib_cleanup; rm -rf "$work"; }
trap cleanup EXIT

newest="$(ls apps/backend/src/main/resources/db/migration | sed -E 's/^V([0-9]+)(_[0-9]+)?__.*/\1/' | sort -n | tail -1)"
before=$(( newest - 1 ))
echo "== rollout rehearsal at $(now) UTC: $OLD -> $NEW; schema before the newest migration: V$before, after: V$newest; mesh $(kubectl get peerauthentication default -n unihub -o jsonpath='{.spec.mtls.mode}')"

# ---- (1) an empty database at V$before -------------------------------------------------------------------------------
echo "[1] resetting the data: API to 0, the database volumes deleted, Redis and RabbitMQ restarted"
kubectl scale deploy/backend-api -n unihub --replicas=0 >/dev/null
kubectl delete statefulset postgres-replica -n unihub --wait=true >/dev/null 2>&1 || true
kubectl delete pvc data-postgres-replica-0 -n unihub --ignore-not-found --wait=true >/dev/null
# the Master's StatefulSet would re-create its pod before the volume could be deleted: scale it to 0 around the delete
kubectl scale statefulset/postgres-master -n unihub --replicas=0 >/dev/null
kubectl wait --for=delete pod/postgres-master-0 -n unihub --timeout=120s >/dev/null 2>&1 || true
kubectl delete pvc data-postgres-master-0 -n unihub --ignore-not-found --wait=true >/dev/null
kubectl scale statefulset/postgres-master -n unihub --replicas=1 >/dev/null
kubectl rollout restart deploy/redis deploy/rabbitmq -n unihub >/dev/null
make --no-print-directory render PROFILE=loadtest TAG=rehearsal | kubectl apply -f - -l app=postgres-replica >/dev/null   # the replica StatefulSet again, after the Master
kubectl wait --for=condition=Ready pod/postgres-master-0 -n unihub --timeout=240s >/dev/null
kubectl rollout status deploy/redis -n unihub --timeout=240s >/dev/null
kubectl rollout status deploy/rabbitmq -n unihub --timeout=240s >/dev/null
sed "s|\"-connectRetries=10\"|\"-connectRetries=10\"\\n            - \"-target=$before\"|" k8s/jobs/flyway-migrate-job.yaml > "$work/job.yaml"
kubectl delete job flyway-migrate -n unihub --ignore-not-found >/dev/null
kubectl apply -f "$work/job.yaml" >/dev/null
kubectl wait --for=condition=complete --timeout=180s job/flyway-migrate -n unihub >/dev/null || { kubectl logs job/flyway-migrate -n unihub -c flyway --tail=20; exit 1; }
# the version of the last successful migration ("1.1" is a version too, so no cast to a number)
schema() { kubectl exec postgres-master-0 -n unihub -c postgres -- sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "select version from flyway_schema_history where success and version is not null order by installed_rank desc limit 1"'; }
[ "$(schema)" = "$before" ] && ok "the database is at V$before, nothing newer" || bad "the database is at V$(schema), not V$before"

# ---- (2) the previous release serves ---------------------------------------------------------------------------------
echo "[2] the previous release ($OLD) starts on that schema"
kubectl set image deploy/backend-api backend-api="$OLD" -n unihub >/dev/null
kubectl scale deploy/backend-api -n unihub --replicas=3 >/dev/null
kubectl rollout status deploy/backend-api -n unihub --timeout=300s | tail -1
gateway_up 18449
STU="$(login student1 UNIHUB_DEV_SEED_STUDENT_PASSWORD)"
tokens=""
for n in 1 2 3; do tokens="$tokens $(login "student$n" UNIHUB_DEV_SEED_STUDENT_PASSWORD)"; done
probe_log="$work/probe.log"
( i=0; set -- $tokens
  while :; do
    i=$(( (i % $#) + 1 )); tok="$(eval echo "\${$i}")"
    code="$(c -m 5 -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $tok" "$BASE/api/v1/workshops" || true)"
    echo "$(date +%s) ${code:-000}" >> "$probe_log"
    sleep 0.25
  done ) &
probe_pid=$!
LIB_PIDS="$LIB_PIDS $probe_pid"
sleep 8
phase_report() { # label start-line -> prints counts and fails the step on a non-2xx
  local label="$1" from="$2" total bad_n
  total=$(( $(wc -l < "$probe_log") - from ))
  bad_n="$(tail -n +"$(( from + 1 ))" "$probe_log" | awk '$2 !~ /^2/ {n++} END{print n+0}')"
  echo "   probe during $label: $total requests, $bad_n not 2xx $(tail -n +"$(( from + 1 ))" "$probe_log" | awk '$2 !~ /^2/ {c[$2]++} END{for(k in c) printf "(%s x%d) ", k, c[k]}')"
  [ "$bad_n" = 0 ] && ok "$label: no failed probe request" || bad "$label: $bad_n failed probe requests"
}

# ---- (3) the migration of HEAD while it serves -----------------------------------------------------------------------
echo "[3] the migration Job of HEAD runs while the previous release serves"
mark=$(wc -l < "$probe_log")
kubectl delete job flyway-migrate -n unihub --ignore-not-found >/dev/null
kubectl apply -f k8s/jobs/flyway-migrate-job.yaml >/dev/null
kubectl wait --for=condition=complete --timeout=180s job/flyway-migrate -n unihub >/dev/null || { kubectl logs job/flyway-migrate -n unihub -c flyway --tail=20; exit 1; }
[ "$(schema)" = "$newest" ] && ok "the database is at V$newest" || bad "the database is at V$(schema), not V$newest"
sleep 20   # the previous release keeps serving on the migrated schema
phase_report "the migration and 20 s after it, the previous release serving" "$mark"
[ "$(api_ready_count)" = 3 ] && ok "the previous release's 3 pods are Ready on V$newest" || bad "an API pod of the previous release is not Ready"
login_code="$(c -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"student1@dev.unihub.local\",\"password\":\"$(secret UNIHUB_DEV_SEED_STUDENT_PASSWORD)\"}")"
[ "$login_code" = 200 ] && ok "the previous release signs a user in on the migrated schema (login 200)" || bad "login answered $login_code on the migrated schema"

# ---- (4) the new release rolls out ----------------------------------------------------------------------------------
echo "[4] the new release ($NEW) rolls out over it"
mark=$(wc -l < "$probe_log"); t0=$(date +%s)
kubectl set image deploy/backend-api backend-api="$NEW" -n unihub >/dev/null
stuck=0
if kubectl rollout status deploy/backend-api -n unihub --timeout=240s >/dev/null 2>&1; then
  echo "   rollout took $(( $(date +%s) - t0 )) s"
else
  stuck=1
  newpod="$(kubectl get pods -n unihub -l app=backend-api --no-headers | awk '$2!="2/2" {print $1}' | head -1)"
  cause="$(kubectl logs "$newpod" -n unihub -c backend-api 2>&1 | grep -o "PRECONDITION_FAILED - inequivalent arg '[^']*' for queue '[^']*'" | head -1)"
  bad "the new release did not become Ready within 4 minutes (pod ${newpod:-?}; startup log: ${cause:-no AMQP topology error found})"
  phase_report "the stuck rollout (the previous release's pods kept serving)" "$mark"
  echo "   the Deployment keeps the previous release's pods while the new one cannot start: $(kubectl get pods -n unihub -l app=backend-api --no-headers | awk '{printf "%s(%s) ", substr($1,length($1)-4), $2}')"
  # A remedy, tried and measured, not assumed: delete the queue the previous release declared. Its consumers are still
  # running and re-declare it with their own arguments, which is the race this step looks at.
  echo "   remedy 1: delete unihub.audit.queue and let the rollout continue (previous pods still running)"
  kubectl exec -n unihub deploy/rabbitmq -c rabbitmq -- rabbitmqctl -q delete_queue unihub.audit.queue >/dev/null 2>&1 || true
  if kubectl rollout status deploy/backend-api -n unihub --timeout=150s >/dev/null 2>&1; then
    echo "   remedy 1 worked: the rollout completed after the queue was deleted"
  else
    echo "   remedy 1 did not help: the previous release's pods declared the old queue again, or the new pod is still failing"
    echo "   remedy 2: stop the previous release first (a Recreate: an outage), then start the new one"
    mark2=$(wc -l < "$probe_log"); t2=$(date +%s)
    kubectl scale deploy/backend-api -n unihub --replicas=0 >/dev/null
    kubectl wait --for=delete pod -l app=backend-api -n unihub --timeout=120s >/dev/null 2>&1 || true
    kubectl exec -n unihub deploy/rabbitmq -c rabbitmq -- rabbitmqctl -q delete_queue unihub.audit.queue >/dev/null 2>&1 || true
    kubectl scale deploy/backend-api -n unihub --replicas=3 >/dev/null
    kubectl rollout status deploy/backend-api -n unihub --timeout=300s >/dev/null 2>&1 \
      && echo "   remedy 2 worked: the new release is Ready $(( $(date +%s) - t2 )) s after the previous one was stopped (the probe was unanswered for that long)" \
      || echo "   remedy 2 did not work either"
    echo "   probe during the Recreate: $(tail -n +"$(( mark2 + 1 ))" "$probe_log" | awk '{c[$2]++} END{for(k in c) printf "%s x%d  ", k, c[k]}')"
  fi
fi
sleep 10
if [ "$stuck" = 0 ]; then phase_report "the rolling update" "$mark"; fi
[ "$(api_ready_count)" = 3 ] && ok "3 pods of the new release are Ready" || bad "an API pod of the new release is not Ready"

# ---- (5) what the two releases share: the broker ---------------------------------------------------------------------
echo "[5] the broker and the logs"
echo "   unihub.audit.queue arguments now: $(kubectl exec -n unihub deploy/rabbitmq -c rabbitmq -- rabbitmqctl -q list_queues name arguments 2>/dev/null | awk '$1=="unihub.audit.queue"{ $1=""; print }')"
for pod in $(kubectl get pods -n unihub -l app=backend-api -o jsonpath='{.items[*].metadata.name}'); do
  n="$(kubectl logs "$pod" -n unihub -c backend-api 2>&1 | grep -c 'PRECONDITION_FAILED' || true)"
  echo "   $pod: $n PRECONDITION_FAILED line(s) in its log"
  tot_precond=$(( ${tot_precond:-0} + n ))
done
[ "${tot_precond:-0}" = 0 ] && ok "no AMQP topology conflict between the two releases" || bad "AMQP topology conflict: $tot_precond PRECONDITION_FAILED line(s) (the broker kept the queue the previous release declared and the new release declares it with other arguments: AMQP topology has no expand/contract)"
echo "   audit failures counted by the new release: $(prom 'sum(unihub_audit_publish_failures_total)' | awk '{print $2}')"

kill "$probe_pid" 2>/dev/null || true; wait "$probe_pid" 2>/dev/null || true
echo "== probe traffic overall: $(wc -l < "$probe_log") requests; $(awk '{c[$2]++} END{for(k in c) printf "%s x%d  ", k, c[k]}' "$probe_log")"
if [ "$fail" = 0 ]; then echo "rollout-rehearsal: OK"; else echo "rollout-rehearsal: FAILED" >&2; fi
exit "$fail"

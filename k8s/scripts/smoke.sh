#!/usr/bin/env bash
# The smoke test of a deployed cluster (Phase 7 task 9.1; D101, resilience-testing.md §2.2 and §2.3): is the whole
# topology up and doing its job, before the Playwright suite and the resilience scripts run on it?
#
#   k8s/scripts/smoke.sh                run the checks that change nothing but add one workshop and one registration
#   k8s/scripts/smoke.sh --resilience   then M-60's master restart and the dependency outages (M-64, M-65, P1),
#                                       by calling dependency-outage.sh, which already does them
#
# Checks, in order (each prints `ok` or `FAIL`; the exit status is 1 when any failed):
#   1  the Flyway Job is Complete and every Deployment and StatefulSet has all its replicas Ready
#   2  every API pod: readiness 200 and the `dependencies` health group UP (management port 8081, from the sidecar)
#   3  through https://unihub.local: the four seeded accounts sign in, the catalog answers, a free registration of a
#      new workshop is CONFIRMED, and the response carries an X-Correlation-Id that is a trace ID
#   4  the confirmation email is in Mailpit (outbox -> RabbitMQ -> the consumer running in the cluster)
#   5  every Prometheus target is up
#   6  sixty requests through the gateway: about one in ten has a trace in Tempo (the gateway starts the trace, AC98.1)
#   7  M-60 (only where there is a replica: overlays/loadtest): `pg_stat_replication` says `streaming` and the lag is
#      small; deleting `postgres-master-0` brings back a pod of the same name on the same PVC, with the data
#
# Needs the local CA (make-local-ca.sh --apply), the Secret `backend-api-secrets`, and the observability stack for 5
# and 6. Starts its own port-forwards (no sudo; the name unihub.local is not resolved through /etc/hosts here) and
# stops them. No password or token is printed.
set -euo pipefail
cd "$(dirname "$0")/../.."   # repository root
# shellcheck source=k8s/scripts/lib.sh
. k8s/scripts/lib.sh
trap lib_cleanup EXIT

resilience=false
case "${1:-}" in "") ;; --resilience) resilience=true ;; *) echo "usage: $0 [--resilience]" >&2; exit 2 ;; esac
for tool in kubectl curl python3; do command -v "$tool" >/dev/null || { echo "$tool is not installed" >&2; exit 1; }; done

fail=0
ok()   { echo "ok    $*"; }
bad()  { echo "FAIL  $*"; fail=1; }
skip() { echo "skip  $*"; }
wait_for() { # seconds command...   (the command succeeds when the condition holds)
  local limit="$1"; shift
  local t0; t0="$(date +%s)"
  until "$@"; do
    [ $(( $(date +%s) - t0 )) -lt "$limit" ] || return 1
    sleep 2
  done
}

echo "== smoke at $(now) UTC; mesh: $(kubectl get peerauthentication default -n unihub -o jsonpath='{.spec.mtls.mode}' 2>/dev/null || echo '?')"
echo "   backend-api $(kubectl get deploy backend-api -n unihub -o jsonpath='{.spec.template.spec.containers[0].image}') x$(kubectl get deploy backend-api -n unihub -o jsonpath='{.spec.replicas}')"
echo "   web         $(kubectl get deploy web -n unihub -o jsonpath='{.spec.template.spec.containers[0].image}')"

# 1 -- the migration and the workloads. The Job removes itself an hour after it finishes (ttlSecondsAfterFinished), so
# a cluster that has been up longer shows the migration only in Flyway's own history table: every migration file the
# cluster was given must be there and none may have failed.
job="$(kubectl get job flyway-migrate -n unihub -o jsonpath='{.status.conditions[?(@.type=="Complete")].status}' 2>/dev/null || true)"
if [ "$job" = True ]; then
  ok "the Flyway Job is Complete"
else
  files="$(kubectl get cm -n unihub -o json | python3 -c '
import json, sys
for o in json.load(sys.stdin)["items"]:
    if o["metadata"]["name"].startswith("flyway-migrations"):
        print(len(o["data"]))')"
  applied="$(kubectl exec -n unihub postgres-master-0 -c postgres -- sh -c 'psql -tAq -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select count(*) filter (where success) || '"'"' '"'"' || count(*) filter (where not success) from flyway_schema_history"' 2>/dev/null || echo '? ?')"
  if [ "${applied% *}" = "$files" ] && [ "${applied#* }" = 0 ]; then
    ok "the Flyway Job has expired (TTL), and flyway_schema_history holds all $files migrations with none failed"
  else
    bad "the Flyway Job is gone and flyway_schema_history says '$applied' (success failed) for $files migration files"
  fi
fi
not_ready="$(kubectl get deploy,statefulset -n unihub -o json | python3 -c '
import json, sys
for o in json.load(sys.stdin)["items"]:
    want = o["spec"].get("replicas", 1)
    got = o["status"].get("readyReplicas", 0)
    if want != got:
        print("%s/%s %s of %s ready" % (o["kind"].lower(), o["metadata"]["name"], got, want))')"
if [ -z "$not_ready" ]; then ok "every Deployment and StatefulSet has all its replicas Ready"; else bad "not Ready: $not_ready"; fi

# 2 -- health of every API pod
health="$(api_dependencies)"
if [ -n "$health" ] && ! echo "$health" | grep -qv 'UP readiness=200'; then
  ok "every API pod: readiness 200, dependencies UP ($(echo "$health" | wc -l | tr -d ' ') pods)"
else
  bad "API pod health: $health"
fi

# 3 -- the journey through the gateway
gateway_up 18449
STU="$(login student1 UNIHUB_DEV_SEED_STUDENT_PASSWORD)"
ORG="$(login organizer1 UNIHUB_DEV_SEED_ORGANIZER_PASSWORD)"
# The two accounts above have signed in or the lines above would have stopped the script (a missing token breaks
# `json`); the other two roles are checked for the permission each is seeded for.
for pair in admin1:UNIHUB_DEV_SEED_ADMIN_PASSWORD:REPORT_VIEW staff1:UNIHUB_DEV_SEED_CHECKIN_STAFF_PASSWORD:CHECKIN_SCAN \
            organizer1:UNIHUB_DEV_SEED_ORGANIZER_PASSWORD:WORKSHOP_CREATE student1:UNIHUB_DEV_SEED_STUDENT_PASSWORD:REGISTRATION_CREATE; do
  IFS=: read -r who key permission <<<"$pair"
  perms="$(c -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$who@dev.unihub.local\",\"password\":\"$(secret "$key")\"}" | json '" ".join(d["permissions"])' 2>/dev/null || true)"
  case " $perms " in *" $permission "*) ok "$who signs in and holds $permission" ;; *) bad "$who: no $permission at sign-in" ;; esac
done
catalog="$(c -o /dev/null -w '%{http_code}' "$BASE/api/v1/workshops")"
[ "$catalog" = 200 ] && ok "the catalog answers 200" || bad "the catalog answered $catalog"

WID="$(new_workshop free)"
read -r code RID <<<"$(register "$WID")"
status="$(c "$BASE/api/v1/registrations/$RID" -H "Authorization: Bearer $STU" | json 'd["status"]' 2>/dev/null || echo '?')"
if [ "$code" = 201 ] && [ "$status" = CONFIRMED ]; then ok "a free registration is $code and CONFIRMED"; else bad "free registration: HTTP $code, status $status"; fi
cid="$(c -D - -o /dev/null "$BASE/api/v1/workshops" | tr -d '\r' | awk -F': ' 'tolower($1)=="x-correlation-id"{print $2}')"
if echo "$cid" | grep -Eq '^[0-9a-f]{32}$'; then ok "X-Correlation-Id is a trace ID ($cid)"; else bad "X-Correlation-Id is '$cid'"; fi

# 4 -- the confirmation email, from the consumer in the cluster, in Mailpit
kubectl -n unihub port-forward svc/mailpit 18025:8025 >/dev/null 2>&1 &
LIB_PIDS="$LIB_PIDS $!"
# The newest mail to student1 and its age; the consumer sends one per confirmed registration.
newest_mail_age() {
  curl -s "http://localhost:18025/api/v1/search?query=$(python3 -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.argv[1]))' "to:student1@dev.unihub.local")&limit=1" \
    | python3 -c '
import datetime, json, sys
try:
    m = json.load(sys.stdin).get("messages", [])
except ValueError:
    m = []   # the port-forward is not up yet, or Mailpit answered something else
if not m: print(99999); sys.exit()
t = datetime.datetime.fromisoformat(m[0]["Created"].replace("Z", "+00:00"))
print(int((datetime.datetime.now(datetime.timezone.utc) - t).total_seconds()))'
}
mail_is_fresh() { [ "$(newest_mail_age)" -lt 120 ]; }
if wait_for 90 mail_is_fresh; then
  ok "a confirmation email for student1 reached Mailpit in the last two minutes (outbox -> RabbitMQ -> consumer)"
else
  bad "no confirmation email for student1 in Mailpit within 90 s"
fi

# 5 -- Prometheus
down="$(prom 'up == 0' || true)"
total="$(prom 'count(up)' | awk '{print $2}' || true)"
# One target per API pod (overlays/dev runs 1, overlays/loadtest 3) plus RabbitMQ. A fixed floor of 4 fitted only the loadtest
# overlay (Phase 8 task 9.3: on `dev` it failed with no target down).
expected=$(( $(kubectl -n unihub get deploy backend-api -o jsonpath='{.spec.replicas}') + 1 ))
if [ -z "$down" ] && [ "${total:-0}" -ge "$expected" ]; then ok "every Prometheus target is up (${total} targets, at least ${expected} expected)"; else bad "Prometheus targets down: ${down:-none}; total ${total:-?}, at least ${expected} expected"; fi

# 6 -- traces in Tempo. The gateway samples 10% and starts the trace (AC98.1), so a request is recorded about one
# time in ten: send sixty and look for their trace IDs (X-Correlation-Id is the trace ID). The chance that none is
# sampled is under 0.2%; the number found is itself the evidence of the sampling rate.
kubectl -n unihub-observability port-forward svc/tempo 13200:3200 >/dev/null 2>&1 &
LIB_PIDS="$LIB_PIDS $!"
cids=""
for _ in $(seq 1 60); do
  cids="$cids $(c -D - -o /dev/null "$BASE/api/v1/registrations/$RID" -H "Authorization: Bearer $STU" | tr -d '\r' | awk -F': ' 'tolower($1)=="x-correlation-id"{print $2}')"
done
found_traces() {
  local n=0 id
  for id in $cids; do
    [ "$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:13200/api/traces/$id")" = 200 ] && n=$((n + 1))
  done
  FOUND=$n
  [ "$n" -ge 1 ]
}
FOUND=0
if wait_for 60 found_traces; then
  one="$(for id in $cids; do [ "$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:13200/api/traces/$id")" = 200 ] && echo "$id" && break; done)"
  services="$(curl -s "http://localhost:13200/api/traces/$one" | python3 -c '
import json, sys
names = set()
for b in json.load(sys.stdin).get("batches", []):
    for a in b["resource"]["attributes"]:
        if a["key"] == "service.name":
            names.add(a["value"]["stringValue"])
print(",".join(sorted(names)))')"
  ok "$FOUND of 60 requests are in Tempo (the gateway samples 10%); trace $one has services: $services"
else
  bad "none of 60 requests has a trace in Tempo after 60 s"
fi

# 7 -- M-60, only where a replica exists
if [ "$(kubectl get statefulset postgres-replica -n unihub -o jsonpath='{.spec.replicas}' 2>/dev/null || echo 0)" = 0 ]; then
  skip "M-60: this overlay runs no Postgres replica (overlays/loadtest does)"
else
  psql_master() { kubectl exec -n unihub postgres-master-0 -c postgres -- sh -c "psql -tAq -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -c \"$1\""; }
  repl="$(psql_master "select state || ' ' || coalesce(extract(epoch from replay_lag)::int::text, '0') from pg_stat_replication")"
  state="${repl%% *}"; lag="${repl##* }"
  if [ "$state" = streaming ] && [ "$lag" -le 5 ]; then ok "M-60: the replica is streaming (replay lag ${lag}s)"; else bad "M-60: pg_stat_replication says '$repl'"; fi

  uid_before="$(kubectl get pod postgres-master-0 -n unihub -o jsonpath='{.metadata.uid}')"
  pvc_before="$(kubectl get pvc data-postgres-master-0 -n unihub -o jsonpath='{.metadata.uid}')"
  count_before="$(psql_master 'select count(*) from workshops')"
  kubectl delete pod postgres-master-0 -n unihub --wait=true >/dev/null
  master_back() { [ "$(kubectl get pod postgres-master-0 -n unihub -o jsonpath='{.status.containerStatuses[?(@.name=="postgres")].ready}' 2>/dev/null)" = true ]; }
  if wait_for 180 master_back; then
    uid_after="$(kubectl get pod postgres-master-0 -n unihub -o jsonpath='{.metadata.uid}')"
    pvc_after="$(kubectl get pvc data-postgres-master-0 -n unihub -o jsonpath='{.metadata.uid}')"
    count_after="$(psql_master 'select count(*) from workshops')"
    if [ "$uid_before" != "$uid_after" ] && [ "$pvc_before" = "$pvc_after" ] && [ "$count_before" = "$count_after" ]; then
      ok "M-60: postgres-master-0 came back as a new pod on the same PVC, $count_after workshops kept"
    else
      bad "M-60: pod uid $uid_before -> $uid_after, PVC uid $pvc_before -> $pvc_after, workshops $count_before -> $count_after"
    fi
    workshop_readable() { [ "$(c -o /dev/null -w '%{http_code}' "$BASE/api/v1/workshops/$WID")" = 200 ]; }
    if wait_for 120 workshop_readable; then
      ok "M-60: the API reads the workshop made before the restart"
    else
      bad "M-60: the API does not answer for the workshop after the master came back"
    fi
    replica_streams() { [ "$(psql_master 'select state from pg_stat_replication' 2>/dev/null)" = streaming ]; }
    if wait_for 120 replica_streams; then ok "M-60: the replica streams again"; else bad "M-60: the replica does not stream again within 120 s of the master's return"; fi
  else
    bad "M-60: postgres-master-0 was not Ready again within 180 s"
  fi
fi

if [ "$fail" -ne 0 ]; then echo "== smoke FAILED at $(now) UTC"; exit 1; fi
echo "== smoke passed at $(now) UTC"

if [ "$resilience" = true ]; then
  lib_cleanup; LIB_PIDS=""
  for what in stripe redis rabbitmq; do
    echo; echo "== k8s/scripts/dependency-outage.sh $what"
    k8s/scripts/dependency-outage.sh "$what"
  done
fi

#!/usr/bin/env bash
# Scenario 7 of resilience-testing.md §2.8 (INV-16, M-59; Phase 7 checklist 8.1, 8.2, D89): one API pod goes away
# while probe traffic runs through the gateway, and the others keep serving.
#
#   k8s/scripts/kill-pod.sh 7a   delete a pod (graceful: preStop, then Tomcat's graceful shutdown)
#   k8s/scripts/kill-pod.sh 7b   freeze a pod's application container FROM THE NODE (`docker pause` through
#                                `minikube ssh`: SIGSTOP to PID 1 from inside the container is ignored by the kernel),
#                                so it is running but answers nothing: readiness takes it out of rotation, liveness
#                                restarts it in place
#
# Needs `overlays/loadtest` (at least two API pods), the local CA (k8s/scripts/make-local-ca.sh) and the Secret
# `backend-api-secrets`; it starts its own port-forwards (gateway, Grafana). Probe traffic: GET /api/v1/workshops
# every 0.2 s through https://unihub.local, with the bearer token of one of the four seeded accounts in turn (the
# rate limiter charges a user 100 requests per 60 s, so four users carry about 5 requests per second without a 429).
# Nothing is printed that is a secret. Results: counts by status, every non-200 with its time, the pod's timeline.
#
# Pass for 7a: no 5xx, and the Deployment is back to its replicas Ready. Connection errors (status 000) are counted
# and reported, never hidden: a request in flight on the killed pod may fail.
# Pass for 7b: the frozen pod leaves rotation, is restarted, traffic never stops being answered by the others (no
# 5xx), and `unihub-app-restarted` reaches Firing (read from Grafana's rule API).
set -euo pipefail
cd "$(dirname "$0")/../.."   # repository root

scenario="${1:-}"
case "$scenario" in 7a|7b) ;; *) echo "usage: $0 7a|7b" >&2; exit 2 ;; esac
for tool in kubectl curl python3 minikube; do command -v "$tool" >/dev/null || { echo "$tool is not installed" >&2; exit 1; }; done

# shellcheck source=k8s/scripts/lib.sh
. k8s/scripts/lib.sh
PROFILE="${MINIKUBE_PROFILE:-unihub}"
work="$(mktemp -d)"
cleanup() { lib_cleanup; rm -rf "$work"; }
trap cleanup EXIT

replicas="$(kubectl get deploy backend-api -n unihub -o jsonpath='{.spec.replicas}')"
[ "$replicas" -ge 2 ] || { echo "backend-api has $replicas replica(s): deploy overlays/loadtest first" >&2; exit 1; }
[ "$(api_ready_count)" = "$replicas" ] || { echo "not every API pod is Ready yet" >&2; exit 1; }
ready_count() { api_ready_count; }

gateway_up 18444
grafana_up
PORT=$GW_PORT

# ---- tokens of the four seeded accounts ------------------------------------------------------------------------------
tokens=""
for pair in "student1:UNIHUB_DEV_SEED_STUDENT_PASSWORD" "organizer1:UNIHUB_DEV_SEED_ORGANIZER_PASSWORD" \
            "admin1:UNIHUB_DEV_SEED_ADMIN_PASSWORD" "staff1:UNIHUB_DEV_SEED_CHECKIN_STAFF_PASSWORD"; do
  tok="$(login "${pair%%:*}" "${pair#*:}")"
  [ -n "$tok" ] || { echo "login of ${pair%%:*} failed" >&2; exit 1; }
  tokens="$tokens $tok"
done

# ---- probe traffic ---------------------------------------------------------------------------------------------------
probe_log="$work/probe.log"
( i=0; set -- $tokens
  while :; do
    i=$(( (i % $#) + 1 )); tok="$(eval echo "\${$i}")"
    code="$(c -m 3 -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $tok" "$BASE/api/v1/workshops" || true)"
    echo "$(date +%s) ${code:-000}" >> "$probe_log"
    sleep 0.2
  done ) &
probe_pid=$!
LIB_PIDS="$LIB_PIDS $probe_pid"

sleep 5
echo "== scenario $scenario at $(now) UTC; $replicas API pods; probe traffic running ($(wc -l < "$probe_log") requests so far)"
before_restarted="$(grafana_state unihub-app-restarted)"
echo "[before] restarted rule: $before_restarted, target-down rule: $(grafana_state unihub-app-target-down)"
[ "$before_restarted" = inactive ] || echo "note: not a clean baseline: a container restarted in the last 10 minutes, so \"Firing\" below does not prove this run"
victim="$(kubectl get pods -n unihub -l app=backend-api -o jsonpath='{.items[0].metadata.name}')"
t0="$(date +%s)"

if [ "$scenario" = 7a ]; then
  echo "[T+0] deleting $victim at $(now)"
  kubectl delete pod "$victim" -n unihub --wait=false >/dev/null
  # gone from the API's point of view, and the Deployment whole again
  gone=""; whole=""
  for _ in $(seq 1 180); do
    if [ -z "$gone" ] && ! kubectl get pod "$victim" -n unihub >/dev/null 2>&1; then gone=$(( $(date +%s) - t0 )); echo "[T+${gone}s] $victim is gone"; fi
    if [ -z "$whole" ] && [ "$(ready_count)" = "$replicas" ] && ! kubectl get pod "$victim" -n unihub >/dev/null 2>&1; then
      whole=$(( $(date +%s) - t0 )); echo "[T+${whole}s] the Deployment has $replicas Ready pods again"; break; fi
    sleep 1
  done
  sleep 20   # a little traffic after recovery too
  echo "[after] restarted rule: $(grafana_state unihub-app-restarted) (a deleted pod is a new series: it is expected NOT to fire), target-down rule: $(grafana_state unihub-app-target-down)"
else
  cid="$(kubectl get pod "$victim" -n unihub -o jsonpath='{.status.containerStatuses[?(@.name=="backend-api")].containerID}' | sed 's|docker://||')"
  [ -n "$cid" ] || { echo "no container id for $victim" >&2; exit 1; }
  restarts0="$(kubectl get pod "$victim" -n unihub -o jsonpath='{.status.containerStatuses[?(@.name=="backend-api")].restartCount}')"
  echo "[T+0] freezing the application container of $victim from the node at $(now) (restartCount $restarts0)"
  minikube ssh -p "$PROFILE" "docker pause $cid" >/dev/null
  notready=""; restarted=""; back=""; fired=""; last_ready=""; last_rc="$restarts0"; last_fire_check=0
  for _ in $(seq 1 600); do
    el=$(( $(date +%s) - t0 ))
    ready="$(kubectl get pod "$victim" -n unihub -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || true)"
    rc="$(kubectl get pod "$victim" -n unihub -o jsonpath='{.status.containerStatuses[?(@.name=="backend-api")].restartCount}' 2>/dev/null || true)"
    # every change of the pod's Ready condition and of the restart count, as it is seen (about once a second)
    if [ "$ready" != "$last_ready" ]; then echo "[T+${el}s] Ready=$ready"; last_ready="$ready"; fi
    if [ -n "$rc" ] && [ "$rc" != "$last_rc" ]; then echo "[T+${el}s] container restarted in place (restartCount $rc)"; last_rc="$rc"; restarted=${restarted:-$el}; fi
    [ -z "$notready" ] && [ "$ready" = False ] && notready=$el
    [ -n "$restarted" ] && [ "$ready" = True ] && [ "$el" -ge $(( restarted + 20 )) ] && [ -z "$back" ] && { back=$el; echo "[T+${el}s] $victim is Ready and stayed Ready for 20 s"; }
    if [ -z "$fired" ] && [ $(( el - last_fire_check )) -ge 5 ]; then
      last_fire_check=$el
      if [ "$(grafana_state unihub-app-restarted)" = firing ]; then fired=$el; echo "[T+${el}s] unihub-app-restarted is Firing"; fi
    fi
    [ -n "$back" ] && [ -n "$fired" ] && break
    sleep 1
  done
  sleep 15
  echo "[after] restarted rule: $(grafana_state unihub-app-restarted), target-down rule: $(grafana_state unihub-app-target-down); Ready pods: $(ready_count)/$replicas"
fi

kill "$probe_pid" 2>/dev/null || true   # stop the probe loop
wait "$probe_pid" 2>/dev/null || true
echo "== probe traffic: $(wc -l < "$probe_log") requests"
awk '{c[$2]++} END{for(k in c) print "   status " k ": " c[k]}' "$probe_log" | sort
python3 - "$probe_log" "$t0" <<'PY'
import sys
log, t0 = sys.argv[1], int(sys.argv[2])
bad = [(int(t) - t0, c) for t, c in (l.split() for l in open(log)) if not c.startswith("2")]
five = [x for x in bad if x[1].startswith("5")]
print("   non-2xx at T+: %s" % (", ".join("%ds=%s" % x for x in bad[:40]) or "none"))
print("   5xx: %d, connection errors (000): %d, 429: %d" % (len(five), sum(1 for x in bad if x[1] == "000"), sum(1 for x in bad if x[1] == "429")))
sys.exit(1 if five else 0)
PY

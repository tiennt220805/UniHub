#!/usr/bin/env bash
# The Kubernetes-layer halves of scenarios 1 and 2 of resilience-testing.md (Phase 7 checklist 8.5, 8.6, D99, D101;
# M-64, M-65, P1): a dependency is taken away from a deployed cluster and the application keeps answering what it can.
#
#   k8s/scripts/dependency-outage.sh stripe     the payment-gateway stub scaled to 0 (1b, M-64): charges fail until every
#                                               pod's breaker is OPEN; then every API pod is still Ready, the catalog
#                                               and a FREE registration still answer through the gateway, a charge is
#                                               refused fast with 503 — and after the stub returns a charge succeeds
#   k8s/scripts/dependency-outage.sh redis      Redis scaled to 0 (2b, M-65): pods stay Ready, `dependencies` is
#                                               DEGRADED, a registration is refused with 503 (INV-08: the seat lock
#                                               fails closed), the P1 rule unihub-dependency-down reaches Firing; after
#                                               Redis returns the rule is Normal and a registration is accepted again
#   k8s/scripts/dependency-outage.sh rabbitmq   the same with RabbitMQ (no INV-08 claim for registration here: it is
#                                               recorded as observed)
#
# Needs `overlays/loadtest` under STRICT, the stack of unihub-observability, the local CA and the Secret
# `backend-api-secrets`; starts its own port-forwards. Every dependency is put back, also when the script fails (trap).
# One workshop or two and a few registrations are left behind. No secret is printed. Exit 1 when a check fails.
set -euo pipefail
cd "$(dirname "$0")/../.."   # repository root
# shellcheck source=k8s/scripts/lib.sh
. k8s/scripts/lib.sh

what="${1:-}"
case "$what" in stripe|redis|rabbitmq) ;; *) echo "usage: $0 stripe|redis|rabbitmq" >&2; exit 2 ;; esac
for tool in kubectl curl python3; do command -v "$tool" >/dev/null || { echo "$tool is not installed" >&2; exit 1; }; done

fail=0
ok()  { echo "ok    $*"; }
bad() { echo "FAIL  $*"; fail=1; }
case "$what" in
  stripe)   deployment=payment-gateway-stub ;;
  redis)    deployment=redis ;;
  rabbitmq) deployment=rabbitmq ;;
esac
restore() {
  kubectl scale "deploy/$deployment" -n unihub --replicas=1 >/dev/null 2>&1 || true
  lib_cleanup
}
trap restore EXIT

replicas="$(kubectl get deploy backend-api -n unihub -o jsonpath='{.spec.replicas}')"
[ "$replicas" -ge 2 ] || { echo "backend-api has $replicas replica(s): deploy overlays/loadtest first" >&2; exit 1; }
[ "$(api_ready_count)" = "$replicas" ] || { echo "not every API pod is Ready" >&2; exit 1; }
gateway_up 18448
grafana_up
STU="$(login student1 UNIHUB_DEV_SEED_STUDENT_PASSWORD)"
ORG="$(login organizer1 UNIHUB_DEV_SEED_ORGANIZER_PASSWORD)"

wait_for() { # seconds description command...   (the command succeeds when the condition holds)
  local limit="$1" what="$2"; shift 2
  local t0; t0="$(date +%s)"
  until "$@"; do
    [ $(( $(date +%s) - t0 )) -lt "$limit" ] || { echo "   (gave up after ${limit}s waiting for: $what)"; return 1; }
    sleep 3
  done
  echo "   ($what after $(( $(date +%s) - t0 ))s)"
}
pods_ready_all() { [ "$(api_ready_count)" = "$replicas" ]; }
dependency_gone() { ! kubectl get pods -n unihub -l "app=$deployment" --no-headers 2>/dev/null | grep -q .; }
all_readiness_ok() { ! api_dependencies | grep -qv 'readiness=200'; }

echo "== $what outage at $(now) UTC; $replicas API pods; mesh: $(kubectl get peerauthentication default -n unihub -o jsonpath='{.spec.mtls.mode}')"
FREE="$(new_workshop free)"; PAID="$(new_workshop paid)"
read -r _ RID <<<"$(register "$PAID")"
echo "workshops ready; paid registration $RID is RESERVED"

if [ "$what" = stripe ]; then
  echo "[breakers before] $(prom 'resilience4j_circuitbreaker_state{name="paymentGateway",state="open"}' | awk '{printf "%s=%s ", substr($1,length($1)-4), $2}')"
  kubectl scale deploy/payment-gateway-stub -n unihub --replicas=0 >/dev/null
  wait_for 90 "the stub is gone" dependency_gone || true
  echo "[charges against a dead gateway] each pod needs 10 failed calls before its breaker judges the failure rate"
  codes=""
  open_all=0
  for round in 1 2 3 4 5; do
    for _ in $(seq 1 14); do read -r code _ <<<"$(charge "$RID")"; codes="$codes $code"; done
    sleep 16   # one scrape interval, so the pods' state is in Prometheus
    states="$(prom 'resilience4j_circuitbreaker_state{name="paymentGateway",state="open"}')"
    echo "   round $round: $(echo "$states" | awk '{printf "%s=%s ", substr($1,length($1)-4), $2}')"
    if [ "$(echo "$states" | grep -c ' 1$' || true)" = "$replicas" ]; then open_all=1; break; fi
  done
  echo "   charge statuses: $(echo "$codes" | tr ' ' '\n' | grep . | sort | uniq -c | awk '{printf "%s x%s  ", $2, $1}')"
  [ "$open_all" = 1 ] && ok "M-64 the breaker is OPEN on every API pod (resilience4j_circuitbreaker_state{state=\"open\"} = 1)" || bad "M-64 the breaker did not open on every pod"
  [ -z "$(echo "$codes" | tr ' ' '\n' | grep . | grep -v '^503$' || true)" ] && ok "M-64 every charge against the dead gateway was answered 503, none hung or crashed" || bad "M-64 a charge was answered something other than 503: $codes"

  pods_ready_all && ok "M-64 every API pod is still Ready ($(api_ready_count)/$replicas)" || bad "M-64 an API pod is not Ready"
  all_readiness_ok && ok "M-64 readiness answers 200 on every pod (the gateway is not a Readiness condition)" || bad "M-64 a pod's readiness is not 200: $(api_dependencies | tr '\n' ';')"
  cat_code="$(c -o /dev/null -w '%{http_code}' "$BASE/api/v1/workshops")"
  [ "$cat_code" = 200 ] && ok "M-64 the catalog is served through the gateway (200)" || bad "M-64 the catalog answered $cat_code"
  read -r reg_code _ <<<"$(register "$FREE")"
  [ "$reg_code" = 201 ] && ok "M-64 a FREE registration is accepted while the breaker is OPEN (201)" || bad "M-64 a free registration answered $reg_code"
  read -r fast_code fast_time <<<"$(charge "$RID")"
  [ "$fast_code" = 503 ] && ok "M-64 a charge is refused fast while the breaker is OPEN (503 in ${fast_time}s)" || bad "M-64 a charge answered $fast_code"

  echo "[restore] the stub comes back; the breakers probe again after their 30 s open state"
  kubectl scale deploy/payment-gateway-stub -n unihub --replicas=1 >/dev/null
  kubectl rollout status deploy/payment-gateway-stub -n unihub --timeout=180s | tail -1
  recovered=""
  for _ in $(seq 1 40); do
    read -r code _ <<<"$(charge "$RID")"
    case "$code" in 200|201) recovered="$code"; break ;; esac
    sleep 3
  done
  [ -n "$recovered" ] && ok "M-64 a charge succeeds again after the stub returns ($recovered)" || bad "M-64 charges still fail 2 minutes after the stub returned"
  for _ in $(seq 1 20); do
    st="$(c "$BASE/api/v1/registrations/$RID" -H "Authorization: Bearer $STU" | json 'd["status"]' 2>/dev/null || true)"
    [ "$st" = CONFIRMED ] && break; sleep 2
  done
  [ "${st:-}" = CONFIRMED ] && ok "M-64 the registration is CONFIRMED once the payment went through" || bad "M-64 the registration is $st, not CONFIRMED"
else
  rule=unihub-dependency-down
  echo "[before] $rule: $(grafana_state "$rule"); dependencies: $(api_dependencies | awk '{print $2}' | sort | uniq -c | tr '\n' ' ')"
  [ "$(grafana_state "$rule")" = inactive ] || echo "note: the rule is not inactive at the start; wait for a clean baseline before relying on this run"
  kubectl scale "deploy/$deployment" -n unihub --replicas=0 >/dev/null
  t0="$(date +%s)"
  wait_for 90 "$deployment is gone" dependency_gone || true
  sleep 12   # the dependency probe runs every 5 s
  echo "[during] $(now): $(api_dependencies | tr '\n' ';')"
  pods_ready_all && ok "M-65 every API pod is still Ready ($(api_ready_count)/$replicas) with $what down" || bad "M-65 an API pod is not Ready"
  all_readiness_ok && ok "M-65 readiness answers 200 on every pod" || bad "M-65 a pod's readiness is not 200"
  [ "$(api_dependencies | grep -c ' DEGRADED ' || true)" = "$replicas" ] && ok "M-65 the dependencies group is DEGRADED on every pod" || bad "M-65 dependencies is not DEGRADED everywhere: $(api_dependencies | tr '\n' ';')"
  cat_code="$(c -o /dev/null -w '%{http_code}' "$BASE/api/v1/workshops")"
  echo "   observed: the catalog answered $cat_code through the gateway"
  read -r reg_code _ <<<"$(register "$FREE")"
  if [ "$what" = redis ]; then
    [ "$reg_code" = 503 ] && ok "M-65 INV-08: a registration is refused with 503 while Redis is down (the seat lock fails closed)" || bad "M-65 INV-08: a registration answered $reg_code, not 503"
  else
    echo "   observed: a free registration answered $reg_code with RabbitMQ down (the outbox keeps the intent)"
  fi
  fired=""
  for _ in $(seq 1 100); do
    [ "$(grafana_state "$rule")" = firing ] && { fired=$(( $(date +%s) - t0 )); break; }
    sleep 3
  done
  [ -n "$fired" ] && ok "P1 $rule is Firing ${fired}s after $what went down" || bad "P1 $rule did not fire within 5 minutes"
  [ "$what" = rabbitmq ] && echo "   observed: unihub-rabbitmq-target-down is $(grafana_state unihub-rabbitmq-target-down) (the absent target reads as 0)"

  echo "[restore] $(now)"
  kubectl scale "deploy/$deployment" -n unihub --replicas=1 >/dev/null
  kubectl rollout status "deploy/$deployment" -n unihub --timeout=180s | tail -1
  t1="$(date +%s)"
  normal=""
  for _ in $(seq 1 100); do
    [ "$(grafana_state "$rule")" = inactive ] && { normal=$(( $(date +%s) - t1 )); break; }
    sleep 3
  done
  [ -n "$normal" ] && ok "P1 $rule is back to Normal ${normal}s after $what returned" || bad "P1 $rule is not Normal 5 minutes after $what returned"
  # a fresh workshop: the earlier attempt may have left a registration behind
  FREE2="$(new_workshop free)"
  recovered=""
  for _ in $(seq 1 20); do
    read -r code _ <<<"$(register "$FREE2")"
    [ "$code" = 201 ] && { recovered=1; break; }; sleep 3
  done
  [ -n "$recovered" ] && ok "M-65 a registration is accepted again after $what returned (201)" || bad "M-65 registration still answers $code after $what returned"
fi

if [ "$fail" = 0 ]; then echo "dependency-outage $what: OK"; else echo "dependency-outage $what: FAILED" >&2; fi
exit "$fail"

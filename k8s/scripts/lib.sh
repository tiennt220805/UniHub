# Shared helpers of the cluster scripts that talk to the application through the gateway (sourced, not run).
# Phase 7 Mục 8; Mục 9's smoke.sh uses the same. Needs kubectl, curl, python3 and the local CA. No function prints a
# secret. The caller sets `set -euo pipefail` and, before `gateway_up`, nothing else.
#
#   gateway_up PORT          port-forward the ingress gateway to localhost:PORT (stopped by `lib_cleanup`, which the
#                            caller's EXIT trap must call); defines `c` (curl through https://unihub.local) and BASE
#   login USER SECRETKEY     prints the access token of USER@dev.unihub.local
#   json 'EXPR'              evaluates a Python expression over the JSON on stdin as `d`
#   prom 'PROMQL'            prints the value(s) of an instant query as "label-set value" lines
#   grafana_up               port-forward Grafana to localhost:13001 (needed by grafana_state)
#   grafana_state UID        the state of one provisioned alert rule: inactive | pending | firing | ?
#   api_ready_count          API pods whose Ready condition is True (not counting a terminating one)
#   api_dependencies         one line per API pod: the status of the `dependencies` health group
#   new_workshop free|paid   creates and publishes a workshop as the organizer, prints its id
#   register WID             the student registers: prints "HTTP-status registration-id"
#   charge RID               the student charges a registration: prints "HTTP-status seconds"
CA=k8s/.local-ca/ca.crt
LIB_PIDS=""
lib_cleanup() { for p in $LIB_PIDS; do kill "$p" 2>/dev/null || true; wait "$p" 2>/dev/null || true; done; }
now() { date -u +%H:%M:%S; }
secret() { kubectl -n unihub get secret backend-api-secrets -o "jsonpath={.data.$1}" | base64 -d; }
json() { python3 -c "import json,sys; d=json.load(sys.stdin); print(eval(sys.argv[1]))" "$1"; }

gateway_up() {
  [ -f "$CA" ] || { echo "$CA is missing: run k8s/scripts/make-local-ca.sh --apply" >&2; return 1; }
  GW_PORT="$1"; BASE="https://unihub.local:$GW_PORT"
  kubectl -n istio-system port-forward svc/istio-ingressgateway "$GW_PORT:443" >/dev/null 2>&1 &
  LIB_PIDS="$LIB_PIDS $!"
  c() { curl -s --resolve "unihub.local:$GW_PORT:127.0.0.1" --cacert "$CA" "$@"; }
  for _ in $(seq 1 30); do c -o /dev/null "$BASE/" 2>/dev/null && return 0; sleep 1; done
  echo "the gateway does not answer on $BASE" >&2; return 1
}

login() {
  c -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$1@dev.unihub.local\",\"password\":\"$(secret "$2")\"}" | json 'd["accessToken"]'
}

prom() {
  local pod q
  pod="$(kubectl get pod -n unihub-observability -l app=prometheus -o jsonpath='{.items[0].metadata.name}')"
  q="$(python3 -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.argv[1]))' "$1")"
  kubectl exec -n unihub-observability "$pod" -c prometheus -- wget -qO- "http://localhost:9090/api/v1/query?query=$q" \
    | python3 -c '
import json, sys
for r in json.load(sys.stdin)["data"]["result"]:
    print("%s %s" % (r["metric"].get("pod", r["metric"].get("dependency", "-")), r["value"][1]))'
}

grafana_up() {
  kubectl -n unihub-observability port-forward svc/grafana 13001:3000 >/dev/null 2>&1 &
  LIB_PIDS="$LIB_PIDS $!"
  # read once into the environment of this shell, never printed and never on a command line
  GRAFANA_PW="$(kubectl get secret grafana-admin -n unihub-observability -o jsonpath='{.data.GF_SECURITY_ADMIN_PASSWORD}' | base64 -d)"
  export GRAFANA_PW
  sleep 2
}
# The rules API with basic auth, called from Python so that the credentials are an HTTP header built at run time
# (a `curl -u user:password` line is what secret scanners flag, and rightly so elsewhere).
grafana_state() {
  python3 - "$1" <<'PY'
import base64, json, os, sys, urllib.request
uid = sys.argv[1]
req = urllib.request.Request("http://localhost:13001/api/prometheus/grafana/api/v1/rules")
req.add_header("Authorization", "Basic " + base64.b64encode(("admin:" + os.environ["GRAFANA_PW"]).encode()).decode())
data = json.load(urllib.request.urlopen(req, timeout=10))
for g in data["data"]["groups"]:
    for r in g["rules"]:
        if r["uid"] == uid:
            print(r["state"])
            sys.exit()
print("?")
PY
}

api_ready_count() {
  kubectl get pods -n unihub -l app=backend-api -o json | python3 -c '
import json, sys
n = 0
for p in json.load(sys.stdin)["items"]:
    if p["metadata"].get("deletionTimestamp"):
        continue
    if any(c["type"] == "Ready" and c["status"] == "True" for c in p["status"].get("conditions", [])):
        n += 1
print(n)'
}

api_dependencies() {
  for pod in $(kubectl get pods -n unihub -l app=backend-api -o jsonpath='{.items[*].metadata.name}'); do
    echo "$pod $(kubectl exec -n unihub "$pod" -c istio-proxy -- curl -s -m 3 http://127.0.0.1:8081/actuator/health/dependencies 2>/dev/null | json 'd["status"]' 2>/dev/null || echo '?') readiness=$(kubectl exec -n unihub "$pod" -c istio-proxy -- curl -s -m 3 -o /dev/null -w '%{http_code}' http://127.0.0.1:8081/actuator/health/readiness 2>/dev/null || echo '?')"
  done
}

new_workshop() { # free|paid
  local kind="$1" paid=false price="" start end body
  [ "$kind" = paid ] && { paid=true; price=',"price":150000'; }
  start="$(date -u -v+2d +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d '+2 days' +%Y-%m-%dT%H:%M:%SZ)"
  end="$(date -u -v+2d -v+2H +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d '+2 days +2 hours' +%Y-%m-%dT%H:%M:%SZ)"
  body="$(c -X POST "$BASE/api/v1/workshops" -H "Authorization: Bearer $ORG" -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
    -d "{\"title\":\"$kind $(date +%H%M%S)\",\"speakerName\":\"Speaker\",\"location\":\"Room 1\",\"startTime\":\"$start\",\"endTime\":\"$end\",\"capacity\":5,\"isPaid\":$paid$price}")"
  local id; id="$(echo "$body" | json 'd["id"]')"
  c -o /dev/null -X POST "$BASE/api/v1/workshops/$id/publish" -H "Authorization: Bearer $ORG" -H "Idempotency-Key: $(uuidgen)"
  echo "$id"
}

register() { # WID -> "status id"
  local out; out="$(c -w '\n%{http_code}' -X POST "$BASE/api/v1/registrations" -H "Authorization: Bearer $STU" -H "Idempotency-Key: $(uuidgen)" \
    -H 'Content-Type: application/json' -d "{\"workshopId\":\"$1\"}")"
  echo "$(echo "$out" | tail -1) $(echo "$out" | head -1 | json 'd.get("id","-")' 2>/dev/null || echo -)"
}

charge() { # RID -> "status seconds"
  c -o /dev/null -w '%{http_code} %{time_total}' -X POST "$BASE/api/v1/payments/charge" -H "Authorization: Bearer $STU" -H "Idempotency-Key: $(uuidgen)" \
    -H 'Content-Type: application/json' -d "{\"registrationId\":\"$1\",\"paymentMethodToken\":\"pm_card_visa\"}"
}

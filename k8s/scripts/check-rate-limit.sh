#!/usr/bin/env bash
# S-15 and S-16 (a) through the real gateway (Phase 7 checklist 8.3, M-62, INV-17, D90, D91).
#
#   S-15: six rapid registration attempts of one user through https://unihub.local on `overlays/loadtest`: the sixth is
#         429 with Retry-After, whichever pod served each. The limiter lives in Redis, so three pods share one budget;
#         the per-pod request counters before and after show the six requests really were spread over the pods.
#   S-16 (a): the budget of an unauthenticated caller is per ADDRESS, and the address is the one the gateway writes
#         (X-Envoy-External-Address), not anything the client sends. Two parts:
#         1. what reaches a pod: a throw-away route sends /zz-probe/ to the AI stub, whose request journal shows the
#            headers it received after the gateway and the sidecar: the client's forged X-Envoy-External-Address must
#            be gone (overwritten) and the real one present (D91's stop condition: if Istio 1.31 did not overwrite it,
#            or did not keep it across the sidecar hop, this fails and the owner decides);
#         2. what the limiter does: 130 unauthenticated requests in one burst, each with a different X-Forwarded-For and a
#            different forged X-Envoy-External-Address, are still stopped by a 429 (if either header chose the
#            budget's key, every request would have a fresh one and none would be refused).
#
# Needs the local CA, the Secret `backend-api-secrets`, `overlays/loadtest`. Takes about three minutes (it waits for
# the limiter's windows). It leaves one workshop and one registration behind (like journey.sh). No secret is printed.
set -euo pipefail
cd "$(dirname "$0")/../.."   # repository root
for tool in kubectl curl python3; do command -v "$tool" >/dev/null || { echo "$tool is not installed" >&2; exit 1; }; done
CA=k8s/.local-ca/ca.crt
[ -f "$CA" ] || { echo "$CA is missing: run k8s/scripts/make-local-ca.sh --apply" >&2; exit 1; }
PORT=18445
BASE="https://unihub.local:$PORT"
fail=0
pf=""
cleanup() {
  kubectl delete virtualservice zz-rate-limit-probe -n unihub --ignore-not-found >/dev/null 2>&1 || true
  [ -z "$pf" ] || kill "$pf" 2>/dev/null || true
}
trap cleanup EXIT
c() { curl -s --resolve "unihub.local:$PORT:127.0.0.1" --cacert "$CA" "$@"; }
secret() { kubectl -n unihub get secret backend-api-secrets -o "jsonpath={.data.$1}" | base64 -d; }
json() { python3 -c "import json,sys; d=json.load(sys.stdin); print(eval(sys.argv[1]))" "$1"; }
check() { if [ "$1" = ok ]; then echo "ok    $2"; else echo "FAIL  $2"; fail=1; fi; }

replicas="$(kubectl get deploy backend-api -n unihub -o jsonpath='{.spec.replicas}')"
[ "$replicas" -ge 2 ] || { echo "backend-api has $replicas replica(s): deploy overlays/loadtest first" >&2; exit 1; }
kubectl -n istio-system port-forward svc/istio-ingressgateway "$PORT:443" >/dev/null 2>&1 &
pf=$!
for _ in $(seq 1 30); do c -o /dev/null "$BASE/" 2>/dev/null && break; sleep 1; done

login() { c -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$1@dev.unihub.local\",\"password\":\"$(secret "$2")\"}" | json 'd["accessToken"]'; }
STU="$(login student1 UNIHUB_DEV_SEED_STUDENT_PASSWORD)"
ORG="$(login organizer1 UNIHUB_DEV_SEED_ORGANIZER_PASSWORD)"

# ---- S-15 ------------------------------------------------------------------------------------------------------------
START="$(date -u -v+2d +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d '+2 days' +%Y-%m-%dT%H:%M:%SZ)"
END="$(date -u -v+2d -v+2H +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d '+2 days +2 hours' +%Y-%m-%dT%H:%M:%SZ)"
W="$(c -X POST "$BASE/api/v1/workshops" -H "Authorization: Bearer $ORG" -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d "{\"title\":\"S-15 $(date +%H%M%S)\",\"speakerName\":\"Speaker\",\"location\":\"Room 1\",\"startTime\":\"$START\",\"endTime\":\"$END\",\"capacity\":5,\"isPaid\":false}")"
WID="$(echo "$W" | json 'd["id"]')"
c -o /dev/null -X POST "$BASE/api/v1/workshops/$WID/publish" -H "Authorization: Bearer $ORG" -H "Idempotency-Key: $(uuidgen)"

pod_counts() { # per API pod: requests its sidecar has answered (destination reporter)
  for pod in $(kubectl get pods -n unihub -l app=backend-api -o name); do
    n="$(kubectl exec "$pod" -n unihub -c istio-proxy -- pilot-agent request GET stats/prometheus 2>/dev/null \
      | grep '^istio_requests_total' | grep 'reporter="destination"' | sed -E 's/.*\} ([0-9]+)$/\1/' | paste -sd+ - | bc)"
    echo "${pod#pod/} ${n:-0}"
  done
}
echo "== S-15: six rapid registration attempts of one user through the gateway ($replicas API pods)"
sleep 12   # the registration budget is 5 per 10 s; start from a full one
pod_counts > /tmp/s15-before.$$
codes=""
for i in 1 2 3 4 5 6; do
  out="$(c -o /dev/null -D - -X POST "$BASE/api/v1/registrations" -H "Authorization: Bearer $STU" -H "Idempotency-Key: $(uuidgen)" \
        -H 'Content-Type: application/json' -d "{\"workshopId\":\"$WID\"}" | tr -d '\r')"
  code="$(echo "$out" | awk 'NR==1{print $2}')"; codes="$codes $code"
  [ "$i" = 6 ] && retry="$(echo "$out" | awk -F': ' 'tolower($1)=="retry-after"{print $2}')"
done
pod_counts > /tmp/s15-after.$$
echo "   statuses:$codes   (Retry-After on the 6th: ${retry:-none})"
served="$(paste /tmp/s15-before.$$ /tmp/s15-after.$$ | awk '{d=$4-$2; if (d>0) n++; printf "   %s answered %d request(s)\n", $1, d} END{print "PODS " n+0}' | tee /dev/stderr | awk '/^PODS/{print $2}')"
rm -f /tmp/s15-before.$$ /tmp/s15-after.$$
set -- $codes
[ "${6}" = 429 ] && [ "$1$2$3$4$5" != "" ] && [ "$(echo "$codes" | tr ' ' '\n' | grep -c '^429$')" = 1 ] && check ok "S-15 the sixth attempt is 429 (and only the sixth)" || check no "S-15 the sixth attempt is 429 (and only the sixth): $codes"
[ -n "${retry:-}" ] && check ok "S-15 the 429 carries Retry-After: $retry" || check no "S-15 the 429 carries Retry-After"
[ "${served:-0}" -ge 2 ] && check ok "S-15 the burst was served by $served different pods, and the budget was still shared" || check no "S-15 the burst was served by one pod only ($served): it proves nothing about sharing"

# ---- S-16 (a) 1: what reaches a pod ----------------------------------------------------------------------------------
echo "== S-16 (a), part 1: the headers a pod behind the gateway receives"
kubectl apply -f - >/dev/null <<'YAML'
apiVersion: networking.istio.io/v1
kind: VirtualService
metadata: { name: zz-rate-limit-probe, namespace: unihub }
spec:
  hosts: ["unihub.local"]
  gateways: ["unihub-gateway"]
  http:
    - match: [{ uri: { prefix: /zz-admin/ } }]
      rewrite: { uri: /__admin/ }
      route: [{ destination: { host: ai-summarization-stub, port: { number: 8080 } } }]
    - match: [{ uri: { prefix: /zz-probe/ } }]
      route: [{ destination: { host: ai-summarization-stub, port: { number: 8080 } } }]
YAML
for _ in $(seq 1 30); do [ "$(c -o /dev/null -w '%{http_code}' "$BASE/zz-admin/mappings")" = 200 ] && break; sleep 1; done
c -o /dev/null -X DELETE "$BASE/zz-admin/requests"
FORGED=203.0.113.77
c -o /dev/null "$BASE/zz-probe/x" -H "X-Envoy-External-Address: $FORGED" -H "X-Forwarded-For: 198.51.100.9"
journal="$(c "$BASE/zz-admin/requests")"
echo "$journal" | python3 -c '
import json, sys
forged = sys.argv[1]
reqs = json.load(sys.stdin)["requests"]
if not reqs:
    print("FAIL  the stub saw no request"); sys.exit(1)
h = {k.lower(): v for k, v in reqs[0]["request"]["headers"].items()}
ext = h.get("x-envoy-external-address", "")
xff = h.get("x-forwarded-for", "")
print("   X-Envoy-External-Address at the pod: %r (the client sent %r)" % (ext, forged))
print("   X-Forwarded-For at the pod: %r (the client sent %r; the application does not read it)" % (xff, "198.51.100.9"))
ok = True
if not ext:
    print("FAIL  X-Envoy-External-Address did not reach the pod: the application would fall back to the socket address"); ok = False
if ext == forged or forged in ext:
    print("FAIL  the client-sent X-Envoy-External-Address was kept: a client could choose its own budget"); ok = False
if ok: print("ok    the gateway overwrote the client-sent X-Envoy-External-Address and it reaches the pod across the sidecar hop")
sys.exit(0 if ok else 1)
' "$FORGED" || fail=1
kubectl delete virtualservice zz-rate-limit-probe -n unihub --ignore-not-found >/dev/null

# ---- S-16 (a) 2: the limiter ---------------------------------------------------------------------------------------
echo "== S-16 (a), part 2: 130 unauthenticated requests in one burst, a different X-Forwarded-For and forged X-Envoy-External-Address each"
sleep 61   # a fresh 60 s window for the per-address budget
# One curl process, one connection (a handshake per request would make the rate too slow to ever reach 100 in 60 s: the
# limiter refills continuously), each request with its own forged headers.
cfg="$(mktemp)"
python3 - "$BASE" "$PORT" "$CA" > "$cfg" <<'PY'
import sys
base, port, ca = sys.argv[1:4]
for i in range(1, 131):
    # `next` resets every per-request option, the resolution and the CA included
    print('silent')
    print('resolve = "unihub.local:%s:127.0.0.1"' % port)
    print('cacert = "%s"' % ca)
    print('url = "%s/api/v1/workshops"' % base)
    print('header = "X-Forwarded-For: 198.51.100.%d"' % (i % 250 + 1))
    print('header = "X-Envoy-External-Address: 203.0.113.%d"' % (i % 250 + 1))
    print('output = "/dev/null"')
    print('write-out = "%{http_code}\\n"')
    if i < 130:
        print("next")
PY
codes="$(curl -K "$cfg" | tr -d '\r')"
rm -f "$cfg"
ok200="$(echo "$codes" | grep -c '^200$' || true)"
n429="$(echo "$codes" | grep -c '^429$' || true)"
first429="$(echo "$codes" | grep -n '^429$' | head -1 | cut -d: -f1 || true)"
echo "   200: $ok200, 429: $n429 (first 429 at request ${first429:-none}), others: $(( $(echo "$codes" | grep -c . || true) - ok200 - n429 ))"
[ "$n429" -ge 1 ] && [ "$ok200" -le 105 ] && check ok "S-16 (a) the unauthenticated budget was not reset by rotating X-Forwarded-For / X-Envoy-External-Address" \
  || check no "S-16 (a) the budget was reset by a client-sent header (200: $ok200, 429: $n429)"

if [ "$fail" = 0 ]; then echo "check-rate-limit: OK"; else echo "check-rate-limit: FAILED" >&2; fi
exit "$fail"

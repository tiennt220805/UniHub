#!/usr/bin/env bash
# The mesh facts of Phase 7 tasks 7.1, 7.2 and 7.4 (P2, P4), read from the running cluster; exit 1 when one fails.
# Re-run at Mục 10 under STRICT. check-mtls.sh is the other half (S-19: plaintext refused).
#
#   7.1  every pod of `unihub` and `unihub-observability` has an `istio-proxy` (a native sidecar), and its HTTP
#        probes are the ones Istio rewrote to the sidecar's port 15020
#   7.2  the hops gateway -> backend-api and gateway -> web are mutual_tls (read at the destination's sidecar)
#   7.4  Prometheus reaches every application and broker target (UP), and Grafana reads Prometheus and Tempo over
#        mutual_tls
#
# 7.2 and 7.4 read counters that need traffic: the script sends a few requests through the gateway first, through
# a port-forward it starts itself (and local CA, k8s/scripts/make-local-ca.sh).
set -euo pipefail
cd "$(dirname "$0")/../.."   # repository root
command -v kubectl >/dev/null || { echo "kubectl is not installed" >&2; exit 1; }
fail=0
ok()   { echo "ok    $*"; }
bad()  { echo "FAIL  $*"; fail=1; }

# ---- 7.1 -------------------------------------------------------------------------------------------------------
for ns in unihub unihub-observability; do
  kubectl get pods -n "$ns" -o json | python3 -c '
import json, sys
ns = sys.argv[1]
bad = 0
for p in json.load(sys.stdin)["items"]:
    if p["status"]["phase"] == "Succeeded":
        continue
    name = p["metadata"]["name"]
    init = {c["name"]: c.get("restartPolicy") for c in p["spec"].get("initContainers", [])}
    if init.get("istio-proxy") != "Always":
        print("FAIL  %s/%s: no native istio-proxy (init containers: %s)" % (ns, name, sorted(init))); bad = 1
    for c in p["spec"]["containers"]:
        for k in ("startupProbe", "readinessProbe", "livenessProbe"):
            h = c.get(k, {}).get("httpGet")
            if h and not (h["path"].startswith("/app-health/") and h["port"] == 15020):
                print("FAIL  %s/%s %s %s: probe not rewritten to the sidecar (%s:%s)" % (ns, name, c["name"], k, h["path"], h["port"])); bad = 1
sys.exit(bad)
' "$ns" > /tmp/check-mesh.$$ && ok "7.1 every pod of $ns has a native istio-proxy and rewritten HTTP probes" || { cat /tmp/check-mesh.$$; fail=1; }
  rm -f /tmp/check-mesh.$$
done

# ---- 7.2: traffic through the gateway, then the destinations' counters ------------------------------------------
CA=k8s/.local-ca/ca.crt
if [ -f "$CA" ]; then
  kubectl -n istio-system port-forward svc/istio-ingressgateway 18443:443 >/dev/null 2>&1 &
  pf=$!
  trap 'kill $pf 2>/dev/null || true' EXIT
  for _ in $(seq 1 20); do curl -s -o /dev/null --resolve unihub.local:18443:127.0.0.1 --cacert "$CA" https://unihub.local:18443/ 2>/dev/null && break; sleep 1; done
  for _ in 1 2 3; do
    curl -s -o /dev/null --resolve unihub.local:18443:127.0.0.1 --cacert "$CA" https://unihub.local:18443/
    curl -s -o /dev/null --resolve unihub.local:18443:127.0.0.1 --cacert "$CA" https://unihub.local:18443/api/v1/workshops
  done
else
  echo "note  $CA missing: no traffic sent; 7.2 reads whatever the counters already hold"
fi
for app in backend-api web; do
  total=0; plain=0
  for pod in $(kubectl get pods -n unihub -l app="$app" -o name); do
    lines="$(kubectl exec "$pod" -n unihub -c istio-proxy -- pilot-agent request GET stats/prometheus 2>/dev/null \
      | grep '^istio_requests_total' | grep 'reporter="destination"' | grep 'source_workload="istio-ingressgateway"' || true)"
    [ -n "$lines" ] || continue
    total=$((total + $(echo "$lines" | wc -l)))
    plain=$((plain + $(echo "$lines" | grep -vc 'connection_security_policy="mutual_tls"' || true)))
  done
  if [ "$total" -gt 0 ] && [ "$plain" -eq 0 ]; then ok "7.2 gateway -> $app: every series is mutual_tls ($total series)"
  else bad "7.2 gateway -> $app: $total series, $plain not mutual_tls"; fi
done

# ---- 7.4 -------------------------------------------------------------------------------------------------------
prom="$(kubectl get pod -n unihub-observability -l app=prometheus -o jsonpath='{.items[0].metadata.name}')"
targets="$(kubectl exec -n unihub-observability "$prom" -c prometheus -- wget -qO- http://localhost:9090/api/v1/targets)"
echo "$targets" | python3 -c '
import json, sys
t = json.load(sys.stdin)["data"]["activeTargets"]
down = [x["labels"].get("pod") for x in t if x["health"] != "up"]
jobs = sorted({x["labels"]["job"] for x in t})
print("%d targets, jobs %s, not up: %s" % (len(t), jobs, down))
sys.exit(1 if down or jobs != ["rabbitmq", "unihub-backend"] else 0)
' && ok "7.4 every target is UP over mTLS" || bad "7.4 a target is down or a job is missing"
for dst in prometheus tempo; do
  pod="$(kubectl get pod -n unihub-observability -l app="$dst" -o jsonpath='{.items[0].metadata.name}')"
  lines="$(kubectl exec -n unihub-observability "$pod" -c istio-proxy -- pilot-agent request GET stats/prometheus 2>/dev/null \
    | grep '^istio_requests_total' | grep 'reporter="destination"' | grep 'source_workload="grafana"' || true)"
  if [ -n "$lines" ] && [ "$(echo "$lines" | grep -vc 'connection_security_policy="mutual_tls"' || true)" -eq 0 ]; then
    ok "7.4 grafana -> $dst is mutual_tls"
  else bad "7.4 grafana -> $dst: no series, or not mutual_tls (Grafana has to have read it: open a dashboard or check the datasource)"; fi
done

if [ "$fail" = 0 ]; then echo "check-mesh: OK"; else echo "check-mesh: FAILED" >&2; fi
exit "$fail"

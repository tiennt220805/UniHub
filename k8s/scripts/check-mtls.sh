#!/usr/bin/env bash
# S-19 / M-63 (Phase 7 checklist 7.6, D96, INV-20): a plaintext connection to any pod in the mesh is refused, and a pod
# with a sidecar still connects.
#
#   k8s/scripts/check-mtls.sh                    expect STRICT: plaintext refused (exit 1 when any port answers)
#   k8s/scripts/check-mtls.sh --negative-control the CONTROL: applies, for the duration of the run, a PERMISSIVE
#                                                PeerAuthentication selected by label for every workload (a policy for a
#                                                workload overrides the namespace's STRICT), expects EVERY port to answer
#                                                plaintext, then removes the policies. It proves the probe can tell
#                                                refusing from answering, port by port (8081 included). Run it, then the
#                                                plain run, in the same session: STRICT is in the manifests, so a fresh deploy
#                                                has no PERMISSIVE moment of its own.
#   k8s/scripts/check-mtls.sh --expect permissive  the same expectation without touching the cluster (for a mesh you made
#                                                PERMISSIVE yourself)
#
# Two throw-away pods, one in a namespace WITHOUT injection (`unihub-probe`: no sidecar, so no mesh identity, so
# plaintext) and one in an injected namespace (`unihub-probe-mesh`: its sidecar originates mutual TLS). Each port is
# spoken to in its own protocol and counts as "answered" when it sends back at least one byte: a bare TCP connect
# proves nothing, because Envoy accepts the connection before it decides to refuse it. The ports are those of Q16:
# the API (8080, and the management port 8081 by pod address, since no Service publishes it), PostgreSQL (Master and
# Replica), Redis, RabbitMQ (5672, 15692, 15672), MinIO, Mailpit (SMTP 1025, UI 8025), the two WireMock stubs, the
# Web, and Prometheus, Tempo (3200, 4318, 4317) and Grafana of unihub-observability.
#
# The management port is the one a sidecar client cannot reach in plaintext either (no Service, so its sidecar does not
# know the port and sends the bytes as they are): the positive control for it is Prometheus's scrape, which carries
# its own certificates (k8s/observability/prometheus.yaml), so it is read from Prometheus's targets, not probed here.
# The public ports of the ingress gateway (80, 443) are the mesh's edge and out of scope: they take traffic from outside.
set -euo pipefail

expect=strict
control=false
case "${1:-}" in
  "") ;;
  --negative-control) expect=permissive; control=true ;;
  --expect) expect="${2:-}"; case "$expect" in strict|permissive) ;; *) echo "usage: $0 [--negative-control | --expect strict|permissive]" >&2; exit 2 ;; esac ;;
  *) echo "usage: $0 [--negative-control | --expect strict|permissive]" >&2; exit 2 ;;
esac

command -v kubectl >/dev/null || { echo "kubectl is not installed" >&2; exit 1; }
PLAIN_NS=unihub-probe
MESH_NS=unihub-probe-mesh
IMAGE=busybox:1.36

CONTROL_LABEL="app.kubernetes.io/managed-by=check-mtls"
cleanup() {
  kubectl delete namespace "$PLAIN_NS" "$MESH_NS" --wait=false >/dev/null 2>&1 || true
  kubectl delete peerauthentication -l "$CONTROL_LABEL" -n unihub >/dev/null 2>&1 || true
  kubectl delete peerauthentication -l "$CONTROL_LABEL" -n unihub-observability >/dev/null 2>&1 || true
}
trap cleanup EXIT
if [ "$control" = true ]; then
  # One PERMISSIVE policy per workload, selected by its `app` label; removed by the trap, whatever happens.
  for target in unihub:backend-api unihub:web unihub:postgres-master unihub:postgres-replica unihub:redis unihub:rabbitmq \
                unihub:minio unihub:mailpit unihub:payment-gateway-stub unihub:ai-summarization-stub \
                unihub-observability:prometheus unihub-observability:tempo unihub-observability:grafana; do
    ns="${target%%:*}"; app="${target#*:}"
    kubectl apply -f - >/dev/null <<YAML
apiVersion: security.istio.io/v1
kind: PeerAuthentication
metadata:
  name: control-$app
  namespace: $ns
  labels:
    app.kubernetes.io/managed-by: check-mtls
spec:
  selector: { matchLabels: { app: $app } }
  mtls: { mode: PERMISSIVE }
YAML
  done
  echo "negative control: PERMISSIVE applied to every workload for this run (removed on exit); waiting 30 s for the sidecars"
  sleep 30
fi
# A namespace still terminating from an earlier run would refuse the pods.
kubectl delete namespace "$PLAIN_NS" "$MESH_NS" --ignore-not-found --wait=true >/dev/null
kubectl create namespace "$PLAIN_NS" >/dev/null
kubectl create namespace "$MESH_NS" >/dev/null
kubectl label namespace "$MESH_NS" istio-injection=enabled >/dev/null
for ns in "$PLAIN_NS" "$MESH_NS"; do
  kubectl run client -n "$ns" --image="$IMAGE" --restart=Never -- sleep 600 >/dev/null
done
kubectl wait --for=condition=Ready pod/client -n "$PLAIN_NS" --timeout=120s >/dev/null
kubectl wait --for=condition=Ready pod/client -n "$MESH_NS" --timeout=180s >/dev/null
# The probe pod without a sidecar must really be without one, and the other must have it.
[ "$(kubectl get pod client -n "$PLAIN_NS" -o jsonpath='{.spec.containers[*].name} {.spec.initContainers[*].name}' | grep -c istio-proxy)" = 0 ] \
  || { echo "the plaintext probe pod has a sidecar: the check would prove nothing" >&2; exit 1; }
kubectl get pod client -n "$MESH_NS" -o jsonpath='{.spec.initContainers[*].name}' | grep -q istio-proxy \
  || { echo "the meshed probe pod has no sidecar" >&2; exit 1; }

# Whether the destination answers, spoken from <namespace> in the protocol of <kind>, within 4 s. Prints the bytes that
# came back (HTTP: the number of status lines: a 4xx is an answer, a reset is not).
answered() { # namespace host port kind
  local payload
  case "$4" in
    http)  kubectl exec -n "$1" client -- sh -c "wget -S -T 4 -qO- http://$2:$3/ 2>&1 | grep -c '^  HTTP/' || true" | tr -d '[:space:]'; return ;;
    redis) payload='PING\r\n' ;;
    pg)    payload='\0\0\0\10\4\322\26\57' ;;            # SSLRequest: PostgreSQL answers 'N' or 'S'
    amqp)  payload='AMQP\0\0\11\1' ;;                    # protocol header: the broker answers Connection.Start
    smtp)  payload='QUIT\r\n' ;;                         # the server speaks first (220 banner)
    h2)    payload='PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n' ;; # gRPC: the server answers with its SETTINGS frame
  esac
  kubectl exec -n "$1" client -- sh -c "(printf '$payload'; sleep 2) | nc -w 4 $2 $3 2>/dev/null | head -c 64 | wc -c || true" | tr -d '[:space:]'
}

api_pod_ip="$(kubectl get pod -n unihub -l app=backend-api -o jsonpath='{.items[0].status.podIP}')"
# name | host | port | kind | reached by a sidecar client in plaintext?  (yes: the sidecar upgrades it)
TARGETS="
api 8080|backend-api-service.unihub|8080|http|yes
web 8080|web.unihub|8080|http|yes
api management 8081 (pod address)|$api_pod_ip|8081|http|no
postgres-master 5432|postgres-master.unihub|5432|pg|yes
postgres-replica 5432|postgres-replica.unihub|5432|pg|yes
redis 6379|redis.unihub|6379|redis|yes
rabbitmq amqp 5672|rabbitmq.unihub|5672|amqp|yes
rabbitmq prometheus 15692|rabbitmq.unihub|15692|http|yes
rabbitmq management 15672|rabbitmq.unihub|15672|http|yes
minio 9000|minio.unihub|9000|http|yes
mailpit smtp 1025|mailpit.unihub|1025|smtp|yes
mailpit ui 8025|mailpit.unihub|8025|http|yes
payment-gateway-stub 8080|payment-gateway-stub.unihub|8080|http|yes
ai-summarization-stub 8080|ai-summarization-stub.unihub|8080|http|yes
prometheus 9090|prometheus.unihub-observability|9090|http|yes
tempo http 3200|tempo.unihub-observability|3200|http|yes
tempo otlp http 4318|tempo.unihub-observability|4318|http|yes
tempo otlp grpc 4317|tempo.unihub-observability|4317|h2|yes
grafana 3000|grafana.unihub-observability|3000|http|yes
"

fail=0
printf '%-36s %-12s %s\n' "port" "plaintext" "sidecar client"
while IFS='|' read -r name host port kind via_sidecar; do
  [ -n "$name" ] || continue
  plain="$(answered "$PLAIN_NS" "$host" "$port" "$kind")"
  mesh="$(answered "$MESH_NS" "$host" "$port" "$kind")"
  verdict_plain=refused; [ "${plain:-0}" -gt 0 ] && verdict_plain=ANSWERED
  verdict_mesh=answered; [ "${mesh:-0}" -eq 0 ] && verdict_mesh=silent
  status=ok
  if [ "$expect" = strict ]; then
    [ "$verdict_plain" = refused ] || status="FAIL: plaintext was answered"
    if [ "$via_sidecar" = yes ] && [ "$verdict_mesh" != answered ]; then status="FAIL: the sidecar client got no answer"; fi
  else
    [ "$verdict_plain" = ANSWERED ] || status="FAIL: the negative control saw plaintext refused"
  fi
  [ "$status" = ok ] || fail=1
  printf '%-36s %-12s %-15s %s\n' "$name" "$verdict_plain($plain B)" "$verdict_mesh($mesh B)" "$status"
done <<< "$TARGETS"

# The management port's positive control: Prometheus scrapes it over mTLS with its own certificates.
prom="$(kubectl get pod -n unihub-observability -l app=prometheus -o jsonpath='{.items[0].metadata.name}')"
up="$(kubectl exec -n unihub-observability "$prom" -c prometheus -- wget -qO- 'http://localhost:9090/api/v1/query?query=min(up%7Bjob%3D%22unihub-backend%22%7D)' \
  | sed -E 's/.*"value":\[[0-9.]+,"([^"]*)"\].*/\1/')"
echo "management port, positive control: min(up{job=\"unihub-backend\"}) = ${up:-?} (1 = Prometheus scrapes it over mTLS)"
if [ "$expect" = strict ] && [ "$up" != 1 ]; then fail=1; echo "FAIL: Prometheus does not reach the management port" >&2; fi

if [ "$fail" = 0 ]; then echo "check-mtls: OK (expected $expect)"; else echo "check-mtls: FAILED (expected $expect)" >&2; fi
exit "$fail"

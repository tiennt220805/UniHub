#!/usr/bin/env bash
# Builds the local cluster (Phase 7 checklist task 5.1): Minikube at the pinned Kubernetes version and size,
# then the pinned Istio. Safe to run again: a running cluster is kept, and k8s/istio/install.yaml is applied again
# (with nothing changed istioctl leaves the pods alone).
#
#   k8s/scripts/cluster-up.sh
#
# Versions and size: k8s/versions.env. It uses its own Minikube profile (`unihub`), so a cluster you already have
# under the default profile is not touched, and it switches kubectl's context to this one.
#
# Steps, each of which stops the script when it fails (D81: nothing here changes the Kubernetes version to make
# a step pass):
#   1. istioctl at ISTIO_VERSION into k8s/.tools/ (git-ignored), checked against the release's own .sha256
#   2. minikube start (docker driver) at KUBERNETES_VERSION, MINIKUBE_MEMORY, MINIKUBE_CPUS; metrics-server on
#   3. istioctl x precheck (AC81.1)
#   4. istioctl install -f k8s/istio/install.yaml: profile `default` (istiod and the ingress gateway) and the
#      gateway's tracing provider, waited for
#   5. a throw-away pod in an injected namespace, to read whether the proxy runs as a native sidecar (AC81.2)
#   6. the versions that are really running, printed for the cluster checks report
set -euo pipefail

cd "$(dirname "$0")/../.."   # repository root
# shellcheck source=k8s/versions.env
. k8s/versions.env

TOOLS="k8s/.tools"
ISTIOCTL="$TOOLS/istioctl"

for tool in minikube kubectl docker curl shasum; do
  command -v "$tool" >/dev/null || { echo "$tool is not installed" >&2; exit 1; }
done

# ---- 1. istioctl ----------------------------------------------------------------------------------------------
if [ "$("$ISTIOCTL" version --remote=false 2>/dev/null || true)" != "client version: $ISTIO_VERSION" ]; then
  git check-ignore -q "$ISTIOCTL" || { echo "refusing: $TOOLS is not git-ignored" >&2; exit 1; }
  case "$(uname -s)-$(uname -m)" in
    Darwin-arm64) platform="osx-arm64" ;;
    Darwin-x86_64) platform="osx-amd64" ;;
    Linux-x86_64) platform="linux-amd64" ;;
    Linux-aarch64) platform="linux-arm64" ;;
    *) echo "no istioctl build known for $(uname -s)-$(uname -m)" >&2; exit 1 ;;
  esac
  archive="istioctl-$ISTIO_VERSION-$platform.tar.gz"
  base="https://github.com/istio/istio/releases/download/$ISTIO_VERSION"
  mkdir -p "$TOOLS"
  tmp="$(mktemp -d)"
  curl -fsSL -o "$tmp/$archive" "$base/$archive"
  curl -fsSL -o "$tmp/$archive.sha256" "$base/$archive.sha256"
  (cd "$tmp" && echo "$(cut -d' ' -f1 "$archive.sha256")  $archive" | shasum -a 256 -c -)
  tar -xzf "$tmp/$archive" -C "$TOOLS"
  rm -rf "$tmp"
  echo "istioctl $ISTIO_VERSION installed in $TOOLS"
fi

# ---- 2. Minikube ----------------------------------------------------------------------------------------------
if [ "$(minikube status -p "$MINIKUBE_PROFILE" --format '{{.Host}}' 2>/dev/null || true)" != "Running" ]; then
  minikube start -p "$MINIKUBE_PROFILE" --driver=docker \
    --kubernetes-version="$KUBERNETES_VERSION" --memory="$MINIKUBE_MEMORY" --cpus="$MINIKUBE_CPUS"
else
  echo "minikube profile $MINIKUBE_PROFILE is already running"
fi
kubectl config use-context "$MINIKUBE_PROFILE" >/dev/null
# `kubectl top` is how the resource numbers of task 5.7 are measured.
minikube addons enable metrics-server -p "$MINIKUBE_PROFILE" >/dev/null

# ---- 3. precheck ----------------------------------------------------------------------------------------------
"$ISTIOCTL" x precheck

# ---- 4. Istio -------------------------------------------------------------------------------------------------
installed="$(kubectl -n istio-system get deploy istiod -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || true)"
# k8s/istio/install.yaml is the installation: the `default` profile and the tracing provider of the ingress gateway
# (task 6.3). Applied on every run, not only on a version change, so an edit to that file reaches a running
# cluster; with nothing changed istioctl leaves the pods alone.
case "$installed" in
  *":$ISTIO_VERSION") echo "istiod $ISTIO_VERSION is already installed; applying k8s/istio/install.yaml" ;;
esac
"$ISTIOCTL" install -y -f k8s/istio/install.yaml
kubectl -n istio-system rollout status deployment/istiod --timeout=300s
kubectl -n istio-system rollout status deployment/istio-ingressgateway --timeout=300s

# ---- 5. native sidecars ---------------------------------------------------------------------------------------
probe_ns="istio-native-sidecar-probe"
# A namespace still terminating from a previous run would refuse the pod below.
kubectl delete namespace "$probe_ns" --ignore-not-found --wait=true >/dev/null
kubectl create namespace "$probe_ns" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl label namespace "$probe_ns" istio-injection=enabled --overwrite >/dev/null
trap 'kubectl delete namespace "$probe_ns" --wait=false >/dev/null 2>&1 || true' EXIT
kubectl -n "$probe_ns" delete pod probe --ignore-not-found >/dev/null
kubectl -n "$probe_ns" run probe --image=busybox:1.36 --restart=Never -- sleep 120 >/dev/null
for _ in $(seq 1 60); do
  kubectl -n "$probe_ns" get pod probe -o jsonpath='{.spec.initContainers[*].name}' 2>/dev/null | grep -q istio-proxy && break
  sleep 2
done
restart_policy="$(kubectl -n "$probe_ns" get pod probe -o jsonpath='{.spec.initContainers[?(@.name=="istio-proxy")].restartPolicy}')"
if [ "$restart_policy" != "Always" ]; then
  echo "istio-proxy is not a native sidecar (initContainers[istio-proxy].restartPolicy='$restart_policy')." >&2
  echo "Stop here (D81, D94): the Flyway Job and the replica's init container need it. Do not change versions silently." >&2
  exit 1
fi
echo "native sidecar: istio-proxy runs as an init container with restartPolicy Always"

# ---- 6. what is really running --------------------------------------------------------------------------------
echo "---- versions"
minikube version --short
kubectl version | grep -E "Client|Server|Kustomize"
"$ISTIOCTL" version
docker info --format 'docker: {{.ServerVersion}}, {{.MemTotal}} bytes, {{.NCPU}} CPUs'
kubectl get nodes -o jsonpath='node: {.items[0].status.nodeInfo.containerRuntimeVersion}, kernel {.items[0].status.nodeInfo.kernelVersion}, allocatable cpu={.items[0].status.allocatable.cpu} memory={.items[0].status.allocatable.memory}{"\n"}'

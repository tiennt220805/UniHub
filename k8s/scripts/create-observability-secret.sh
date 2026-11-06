#!/usr/bin/env bash
# Creates the Secret `grafana-admin` in the namespace `unihub-observability` (Phase 7 checklist task 6.1): the one
# way a Grafana password reaches the cluster. Nothing is written to a file in the repository and no value is
# printed. The password is read from GRAFANA_ADMIN_PASSWORD, or made up at random; the user name is `admin`.
#
#   k8s/scripts/create-observability-secret.sh            create it; keeps an existing one
#   k8s/scripts/create-observability-secret.sh --replace  delete it and create it again
#
# The key list is the keys of k8s/observability/secret.example.yaml; KubernetesManifestsTest fails when the two
# differ. Read the password back with:
#   kubectl get secret grafana-admin -n unihub-observability -o jsonpath='{.data.GF_SECURITY_ADMIN_PASSWORD}' | base64 -d
set -euo pipefail

cd "$(dirname "$0")/../.."   # repository root

NAMESPACE="unihub-observability"
SECRET="grafana-admin"

command -v kubectl >/dev/null || { echo "kubectl is not installed" >&2; exit 1; }
command -v openssl >/dev/null || { echo "openssl is not installed" >&2; exit 1; }

replace=false
case "${1:-}" in
  "") ;;
  --replace) replace=true ;;
  *) echo "usage: $0 [--replace]" >&2; exit 2 ;;
esac

kubectl apply -f k8s/observability/namespace.yaml >/dev/null

if kubectl get secret "$SECRET" -n "$NAMESPACE" >/dev/null 2>&1; then
  if [ "$replace" = true ]; then
    kubectl delete secret "$SECRET" -n "$NAMESPACE" >/dev/null
  else
    echo "secret/$SECRET already exists in $NAMESPACE; keeping it (--replace changes it)" >&2
    exit 0
  fi
fi

password="${GRAFANA_ADMIN_PASSWORD:-$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | cut -c1-32)}"

# Through a private temporary file read by kubectl, never through its command line (visible in `ps`).
umask 077
env_file="$(mktemp)"
trap 'rm -f "$env_file"' EXIT
{
  echo "GF_SECURITY_ADMIN_USER=admin"
  echo "GF_SECURITY_ADMIN_PASSWORD=$password"
} > "$env_file"

kubectl create secret generic "$SECRET" -n "$NAMESPACE" --from-env-file="$env_file" >/dev/null
echo "created secret/$SECRET in $NAMESPACE with $(grep -c '=' "$env_file") keys (no value is printed)"

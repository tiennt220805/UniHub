#!/usr/bin/env bash
# Runs the Playwright suite of tests/e2e against the cluster (Phase 7 task 9.1; D100, e2e-testing.md §3). By hand,
# after `make deploy-*`; never from CI.
#
#   k8s/scripts/e2e.sh [playwright args...]     e.g.  k8s/scripts/e2e.sh --project=chromium
#
# It port-forwards the Istio gateway (HTTPS on 8443, HTTP on 8080, no sudo) and runs the suite against
# https://unihub.local:8443. Two things on this machine are the owner's to do once, because they need a password
# (D97; nothing here changes them):
#   - the local CA is trusted:  security add-trusted-cert -r trustRoot -k ~/Library/Keychains/login.keychain-db k8s/.local-ca/ca.crt
#   - the name resolves:        echo '127.0.0.1 unihub.local' | sudo tee -a /etc/hosts
# Without them a browser (rightly) refuses the certificate or cannot find the host. The Student, Organizer and
# Admin passwords are read from the cluster's Secret by the suite itself and never printed.
set -euo pipefail
cd "$(dirname "$0")/../.."   # repository root
REPO_ROOT="$PWD"
# shellcheck source=k8s/scripts/lib.sh
source k8s/scripts/lib.sh
trap lib_cleanup EXIT

command -v node >/dev/null || { echo "node is not installed" >&2; exit 1; }
[ -d tests/e2e/node_modules ] || (cd tests/e2e && npm ci --no-audit --no-fund && npx playwright install chromium firefox webkit)

gateway_up 8443
kubectl -n istio-system port-forward svc/istio-ingressgateway 8080:80 >/dev/null 2>&1 &
LIB_PIDS="$LIB_PIDS $!"

if ! grep -Eq '^[^#]*[[:space:]]unihub\.local([[:space:]]|$)' /etc/hosts; then
  echo "unihub.local is not in /etc/hosts: add  127.0.0.1 unihub.local  (see the header of this script)" >&2
  exit 1
fi

echo "[$(now)] image of backend-api: $(kubectl -n unihub get deploy backend-api -o jsonpath='{.spec.template.spec.containers[0].image}')"
echo "[$(now)] image of web:         $(kubectl -n unihub get deploy web -o jsonpath='{.spec.template.spec.containers[0].image}')"
echo "[$(now)] API replicas: $(kubectl -n unihub get deploy backend-api -o jsonpath='{.spec.replicas}')"
cd tests/e2e
# The CA must be in the environment before Node starts: globalSetup runs in the runner process itself.
NODE_EXTRA_CA_CERTS="${NODE_EXTRA_CA_CERTS:-$REPO_ROOT/k8s/.local-ca/ca.crt}" \
  E2E_BASE_URL=https://unihub.local:8443 E2E_HTTP_URL=http://unihub.local:8080 npx playwright test "$@"

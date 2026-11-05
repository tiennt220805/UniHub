#!/usr/bin/env bash
# A local certificate authority and a certificate for unihub.local (Phase 7 checklist D97, task 5.6). The browser
# and Playwright trust the CA, never an exception for one certificate.
#
#   k8s/scripts/make-local-ca.sh           make the CA and the certificate under k8s/.local-ca/ (git-ignored)
#   k8s/scripts/make-local-ca.sh --apply   also create the Secret `unihub-tls` in `istio-system`
#
# The Secret goes in the namespace of the gateway WORKLOAD (istio-system with the default install): Istio reads a
# Gateway's `credentialName` from there, not from `unihub` (deployment-and-cicd.md §2). It is created by hand and
# never committed. Nothing is trusted on this machine by the script (that needs sudo and is your decision); it
# prints the one macOS command that would. Never use this CA anywhere but this laptop: its key sits on disk.
#
# Safe to run again: it keeps an existing CA (a browser that trusts it keeps trusting it) and only makes the
# certificate again when it is missing or about to expire.
set -euo pipefail

cd "$(dirname "$0")/../.."   # repository root

command -v openssl >/dev/null || { echo "openssl is not installed" >&2; exit 1; }

DIR="k8s/.local-ca"
HOST="unihub.local"
DAYS_CA=1825
DAYS_CERT=365

apply=false
case "${1:-}" in
  "") ;;
  --apply) apply=true ;;
  *) echo "usage: $0 [--apply]" >&2; exit 2 ;;
esac

git check-ignore -q "$DIR/ca.key" || { echo "refusing: $DIR is not git-ignored" >&2; exit 1; }

umask 077
mkdir -p "$DIR"

if [ ! -f "$DIR/ca.key" ] || [ ! -f "$DIR/ca.crt" ]; then
  openssl genrsa -out "$DIR/ca.key" 4096 2>/dev/null
  openssl req -x509 -new -nodes -key "$DIR/ca.key" -sha256 -days "$DAYS_CA" \
    -subj "/CN=UniHub local development CA" -out "$DIR/ca.crt"
  echo "made a new CA: $DIR/ca.crt"
else
  echo "keeping the existing CA: $DIR/ca.crt"
fi

# 14 days of margin; -checkend takes seconds.
if [ ! -f "$DIR/tls.crt" ] || ! openssl x509 -checkend $((14 * 86400)) -noout -in "$DIR/tls.crt" >/dev/null; then
  openssl genrsa -out "$DIR/tls.key" 2048 2>/dev/null
  openssl req -new -key "$DIR/tls.key" -subj "/CN=$HOST" -out "$DIR/tls.csr"
  # A modern browser ignores the CN: the name has to be in the SAN.
  printf 'subjectAltName=DNS:%s\nbasicConstraints=CA:FALSE\nkeyUsage=digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n' "$HOST" > "$DIR/tls.ext"
  openssl x509 -req -in "$DIR/tls.csr" -CA "$DIR/ca.crt" -CAkey "$DIR/ca.key" -CAcreateserial \
    -out "$DIR/tls.crt" -days "$DAYS_CERT" -sha256 -extfile "$DIR/tls.ext" 2>/dev/null
  rm -f "$DIR/tls.csr" "$DIR/tls.ext"
  echo "made the certificate for $HOST: $DIR/tls.crt"
else
  echo "keeping the certificate for $HOST: $DIR/tls.crt"
fi

openssl verify -CAfile "$DIR/ca.crt" "$DIR/tls.crt" >/dev/null
openssl x509 -in "$DIR/tls.crt" -noout -subject -enddate -ext subjectAltName | sed 's/^/  /'

if [ "$apply" = true ]; then
  command -v kubectl >/dev/null || { echo "kubectl is not installed" >&2; exit 1; }
  kubectl get namespace istio-system >/dev/null 2>&1 || { echo "istio-system does not exist: install Istio first" >&2; exit 1; }
  kubectl create secret tls unihub-tls -n istio-system --cert="$DIR/tls.crt" --key="$DIR/tls.key" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  echo "secret/unihub-tls is in istio-system"
fi

cat <<MSG

To reach the gateway from this machine (task 5.6; the CA is yours to trust, nothing above did):
  1. echo "127.0.0.1 $HOST" | sudo tee -a /etc/hosts        # once
  2. minikube tunnel                                        # in a terminal that stays open
  3. sudo security add-trusted-cert -d -r trustRoot -k /Library/Keychains/System.keychain $DIR/ca.crt   # macOS, optional
MSG

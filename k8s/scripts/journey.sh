#!/usr/bin/env bash
# One journey through the gateway of a deployed cluster (Phase 7 task 5.7; Mục 9 grows it into smoke.sh, D101):
# the four seeded accounts sign in with the permissions of their role, an Organizer creates and publishes a paid
# workshop, a Student registers and pays through the stub gateway, and the registration is CONFIRMED.
#
# Needs the gateway reachable on this machine without sudo:
#   kubectl -n istio-system port-forward svc/istio-ingressgateway 8443:443 8080:80
# and the local CA (k8s/scripts/make-local-ca.sh). The passwords are read from the cluster's Secret into shell
# variables and never printed. It leaves one workshop, one registration and one payment behind.
set -euo pipefail
cd "$(dirname "$0")/../.."   # repository root
CA=k8s/.local-ca/ca.crt
BASE=https://unihub.local:8443
c() { curl -s --resolve unihub.local:8443:127.0.0.1 --cacert "$CA" "$@"; }
secret() { kubectl -n unihub get secret backend-api-secrets -o "jsonpath={.data.$1}" | base64 -d; }
j() { ruby -rjson -e 'd=JSON.parse(STDIN.read); puts eval(ARGV[0])' "$1"; }

login() { # email secretKey -> prints the token, reports the permissions on stderr
  local body
  body=$(c -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' -d "{\"email\":\"$1\",\"password\":\"$(secret "$2")\"}")
  echo "login $1 -> permissions: $(echo "$body" | j 'Array(d["permissions"] || d.dig("user","permissions")).join(",")')" >&2
  echo "$body" | j 'd["accessToken"]'
}
STU=$(login student1@dev.unihub.local UNIHUB_DEV_SEED_STUDENT_PASSWORD)
ORG=$(login organizer1@dev.unihub.local UNIHUB_DEV_SEED_ORGANIZER_PASSWORD)
login admin1@dev.unihub.local UNIHUB_DEV_SEED_ADMIN_PASSWORD >/dev/null
login staff1@dev.unihub.local UNIHUB_DEV_SEED_CHECKIN_STAFF_PASSWORD >/dev/null
[ -n "$STU" ] && [ -n "$ORG" ]
echo "wrong password -> $(c -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' -d '{"email":"admin1@dev.unihub.local","password":"nope-nope-nope"}')"
START=$(date -u -v+2d +%Y-%m-%dT%H:%M:%SZ); END=$(date -u -v+2d -v+2H +%Y-%m-%dT%H:%M:%SZ)
W=$(c -X POST "$BASE/api/v1/workshops" -H "Authorization: Bearer $ORG" -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d "{\"title\":\"Cluster rehearsal $(date +%H%M%S)\",\"speakerName\":\"Speaker\",\"location\":\"Room 1\",\"startTime\":\"$START\",\"endTime\":\"$END\",\"capacity\":5,\"isPaid\":true,\"price\":150000}")
WID=$(echo "$W" | j 'd["id"]'); echo "organizer created workshop $WID status=$(echo "$W" | j 'd["status"]')"
P=$(c -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/workshops/$WID/publish" -H "Authorization: Bearer $ORG" -H "Idempotency-Key: $(uuidgen)"); echo "publish -> $P"
R=$(c -X POST "$BASE/api/v1/registrations" -H "Authorization: Bearer $STU" -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' -d "{\"workshopId\":\"$WID\"}")
RID=$(echo "$R" | j 'd["id"]'); echo "student registered $RID status=$(echo "$R" | j 'd["status"]')"
C=$(c -X POST "$BASE/api/v1/payments/charge" -H "Authorization: Bearer $STU" -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' -d "{\"registrationId\":\"$RID\",\"paymentMethodToken\":\"pm_card_visa\"}")
echo "charge status=$(echo "$C" | j 'd["status"]') gatewayTransactionId=$(echo "$C" | j 'd["gatewayTransactionId"][0,3]')…"
for i in $(seq 1 20); do s=$(c "$BASE/api/v1/registrations/$RID" -H "Authorization: Bearer $STU" | j 'd["status"]'); [ "$s" = CONFIRMED ] && break; sleep 1; done
echo "registration status after payment: $s (waited ${i}s)"
[ "$s" = CONFIRMED ] || { echo "the registration did not become CONFIRMED" >&2; exit 1; }

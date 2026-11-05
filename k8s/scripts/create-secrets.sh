#!/usr/bin/env bash
# Creates the Secret `backend-api-secrets` in the namespace `unihub` (Phase 7 checklist D87). The only way a real
# secret reaches the cluster: nothing here is written to a file in the repository, and no value is ever printed.
#
#   k8s/scripts/create-secrets.sh            create the Secret; refuses when it already exists
#   k8s/scripts/create-secrets.sh --replace  delete it and create it again (see the warning below)
#
# Each value is read from the environment of whoever runs the script, and a credential that is not set is made
# up at random (openssl): a development cluster needs a password, not a particular one. Variables read:
#   POSTGRES_PASSWORD, RABBITMQ_PASSWORD, UNIHUB_REDIS_PASSWORD, UNIHUB_SECURITY_JWT_SECRET,
#   UNIHUB_OBJECT_STORAGE_SECRET_KEY, UNIHUB_DEV_SEED_STUDENT_PASSWORD, UNIHUB_DEV_SEED_ORGANIZER_PASSWORD,
#   UNIHUB_DEV_SEED_ADMIN_PASSWORD, UNIHUB_DEV_SEED_CHECKIN_STAFF_PASSWORD, UNIHUB_TICKET_SIGNING_KEY
# (the ticket key defaults to a fresh one from apps/backend/scripts/generate-ticket-key.sh). The values that are
# fixed names, not secrets (the database user, the broker user, the bucket's access key) are `unihub`.
#
# --replace is dangerous on a cluster that already has data: the postgres image reads POSTGRES_PASSWORD only when
# it first creates its data directory, so a new password locks the application out of the existing volume, and a
# new ticket key makes every issued ticket stop verifying (checkin.md §6). On a cluster you mean to keep, delete
# the volumes first (`kubectl delete pvc --all -n unihub`) or keep the old Secret.
#
# The key list is the keys of k8s/base/secret.example.yaml; KubernetesManifestsTest fails when the two differ.
set -euo pipefail

cd "$(dirname "$0")/../.."   # repository root

NAMESPACE="unihub"
SECRET="backend-api-secrets"

command -v kubectl >/dev/null || { echo "kubectl is not installed" >&2; exit 1; }
command -v openssl >/dev/null || { echo "openssl is not installed" >&2; exit 1; }

replace=false
case "${1:-}" in
  "") ;;
  --replace) replace=true ;;
  *) echo "usage: $0 [--replace]" >&2; exit 2 ;;
esac

kubectl apply -f k8s/namespace.yaml >/dev/null

if kubectl get secret "$SECRET" -n "$NAMESPACE" >/dev/null 2>&1; then
  if [ "$replace" = true ]; then
    kubectl delete secret "$SECRET" -n "$NAMESPACE" >/dev/null
  else
    echo "secret/$SECRET already exists in $NAMESPACE; keeping it (--replace changes it, read the warning in this script)" >&2
    exit 0
  fi
fi

# 32 characters from a small alphabet: safe in a URL, a shell and an env file without quoting.
random() { openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | cut -c1-32; }

postgres_password="${POSTGRES_PASSWORD:-$(random)}"
rabbitmq_password="${RABBITMQ_PASSWORD:-$(random)}"
redis_password="${UNIHUB_REDIS_PASSWORD:-$(random)}"
object_storage_secret="${UNIHUB_OBJECT_STORAGE_SECRET_KEY:-$(random)}"
seed_student_password="${UNIHUB_DEV_SEED_STUDENT_PASSWORD:-$(random)}"
seed_organizer_password="${UNIHUB_DEV_SEED_ORGANIZER_PASSWORD:-$(random)}"
seed_admin_password="${UNIHUB_DEV_SEED_ADMIN_PASSWORD:-$(random)}"
seed_checkin_staff_password="${UNIHUB_DEV_SEED_CHECKIN_STAFF_PASSWORD:-$(random)}"
# HMAC-SHA256 needs at least 32 bytes; this is 48 random characters.
jwt_secret="${UNIHUB_SECURITY_JWT_SECRET:-$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | cut -c1-48)}"
ticket_key="${UNIHUB_TICKET_SIGNING_KEY:-$(apps/backend/scripts/generate-ticket-key.sh)}"
# Any value will do: the payment gateway is a stub. Made at random so that no literal that looks like a
# Stripe key is ever in the repository.
stripe_key="stub-$(random)"

# The values go through a private temporary file read by kubectl, never through its command line (visible in `ps`).
umask 077
env_file="$(mktemp)"
trap 'rm -f "$env_file"' EXIT

{
  echo "POSTGRES_USER=unihub"
  echo "POSTGRES_PASSWORD=$postgres_password"
  echo "POSTGRES_DB=unihub"
  echo "SPRING_DATASOURCE_USERNAME=unihub"
  echo "SPRING_DATASOURCE_PASSWORD=$postgres_password"
  echo "UNIHUB_DATASOURCE_REPLICA_USERNAME=unihub"
  echo "UNIHUB_DATASOURCE_REPLICA_PASSWORD=$postgres_password"
  echo "SPRING_RABBITMQ_USERNAME=unihub"
  echo "SPRING_RABBITMQ_PASSWORD=$rabbitmq_password"
  echo "UNIHUB_REDIS_PASSWORD=$redis_password"
  echo "UNIHUB_SECURITY_JWT_SECRET=$jwt_secret"
  echo "UNIHUB_PAYMENT_STRIPE_SECRETKEY=$stripe_key"
  echo "UNIHUB_OBJECT_STORAGE_ACCESS_KEY=unihub"
  echo "UNIHUB_OBJECT_STORAGE_SECRET_KEY=$object_storage_secret"
  echo "UNIHUB_DEV_SEED_STUDENT_PASSWORD=$seed_student_password"
  echo "UNIHUB_DEV_SEED_ORGANIZER_PASSWORD=$seed_organizer_password"
  echo "UNIHUB_DEV_SEED_ADMIN_PASSWORD=$seed_admin_password"
  echo "UNIHUB_DEV_SEED_CHECKIN_STAFF_PASSWORD=$seed_checkin_staff_password"
  echo "UNIHUB_NOTIFICATION_SMTP_USERNAME="
  echo "UNIHUB_NOTIFICATION_SMTP_PASSWORD="
  echo "UNIHUB_TICKET_SIGNING_KEY=$ticket_key"
  echo "UNIHUB_AI_SUMMARIZATION_API_KEY="
} > "$env_file"

kubectl create secret generic "$SECRET" -n "$NAMESPACE" --from-env-file="$env_file" >/dev/null
echo "created secret/$SECRET in $NAMESPACE with $(grep -c '=' "$env_file") keys (no value is printed)"

#!/usr/bin/env bash
# Makes a private EC P-256 JWK for the QR e-ticket signing key (checkin.md §4; Phase 5 checklist D49).
# Needs only Node >= 15.9 (already required by contract-diff and the web app); no openssl, no new tool.
#
#   scripts/generate-ticket-key.sh            print the JWK (one line of JSON) to stdout; nothing is written
#   scripts/generate-ticket-key.sh --write    store it as UNIHUB_TICKET_SIGNING_KEY in apps/backend/.env
#
# For `./gradlew bootRun` the key must reach the environment of the process. `.env` is read by
# docker-compose, not by Gradle, so after --write run
#   set -a; . ./.env; set +a
# or, without a file:
#   export UNIHUB_TICKET_SIGNING_KEY="$(scripts/generate-ticket-key.sh)"
#
# --write refuses to run when a key is already set. Replacing the key is rotation: every ticket ever
# issued stops verifying (checkin.md §6), so it is never done by accident; delete the line by hand if
# that is what you mean on a development database. It also refuses unless .env is git-ignored: this is
# a private key and must never reach the repository (Gitleaks would block it, and should).
#
# Never commit the output, never paste it in a ticket or a chat, never use a development key anywhere
# that issues real tickets. Production gets the key from a Kubernetes Secret (deployment-and-cicd.md §2).
set -euo pipefail

cd "$(dirname "$0")/.."   # apps/backend

NAME="UNIHUB_TICKET_SIGNING_KEY"

jwk="$(node -e '
  const { generateKeyPairSync } = require("node:crypto");
  const { privateKey } = generateKeyPairSync("ec", { namedCurve: "P-256" });
  process.stdout.write(JSON.stringify(privateKey.export({ format: "jwk" })));
')"

if [ "${1:-}" != "--write" ]; then
  printf '%s\n' "$jwk"
  exit 0
fi

if ! git check-ignore -q .env; then
  echo "refusing to write: apps/backend/.env is not git-ignored" >&2
  exit 1
fi
if [ -f .env ] && grep -Eq "^${NAME}=.+" .env; then
  echo "refusing to overwrite: ${NAME} is already set in .env (replacing it rotates the key and" >&2
  echo "kills every ticket issued with the old one; remove the line yourself if you mean it)" >&2
  exit 1
fi

# A blank "${NAME}=" line (copied from env.example) is filled in place; otherwise the line is appended.
umask 077
touch .env
line="${NAME}='${jwk}'"
if grep -Eq "^${NAME}=" .env; then
  tmp="$(mktemp)"
  LINE="$line" awk -v name="$NAME" 'index($0, name "=") == 1 { print ENVIRON["LINE"]; next } { print }' .env > "$tmp"
  cat "$tmp" > .env
  rm -f "$tmp"
else
  printf '%s\n' "$line" >> .env
fi
echo "wrote ${NAME} to apps/backend/.env (git-ignored). Load it for bootRun with: set -a; . ./.env; set +a" >&2

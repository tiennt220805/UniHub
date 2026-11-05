#!/usr/bin/env bash
# contract-diff: proves the HTTP shape the controllers actually expose (springdoc) matches the hand-written
# contract in packages/api-contract, for every operation both describe.
#
# The one command behind both the CI `contract-diff` job and local reproduction — see
# docs/technical-design/platform/deployment-and-cicd.md (Contract-Diff Mechanism ADR, §5) and
# docs/testing/test-levels/contract-testing.md §2. Run from anywhere; it works in apps/backend.
#
# Needs: Java 21 (gradlew), Node >= 22.12 (npx @redocly/cli), Docker (oasdiff). `jq` is used if installed,
# otherwise run through the ghcr.io/jqlang/jq image.
#
# Env:
#   SPRING_DATASOURCE_URL  generateOpenApiDocs boots the app, and JdbcTemplate needs *a* DataSource URL to
#                          exist. No database has to be listening — nothing connects while the spec is read.
#   UNIHUB_SECURITY_JWT_SECRET  the app refuses to boot without a JWT secret; generateOpenApiDocs only reads the
#                          spec and never verifies a token, so a throwaway placeholder is enough (not a credential).
#   UNIHUB_REDIS_ADDRESS   Redis is required configuration (the app will not start without it), but the client is
#                          lazy and generateOpenApiDocs never issues a command, so a dead address is enough.
#   UNIHUB_RATE_LIMIT_ENABLED  false: reading the spec is one request to a Redis that is not there; the limiter would only
#                          fail open after its timeout. Switched off here so the run does not depend on that path.
#   UNIHUB_NOTIFICATION_SMTP_HOST  the EMAIL channel's SMTP host is required configuration (the app will not start
#                          without it), but nothing connects while the spec is read, so a dead address is enough.
#   UNIHUB_TICKET_SIGNING_KEY  the QR e-ticket signing key is required configuration (the app will not start
#                          without a private EC P-256 JWK), but generateOpenApiDocs never signs a ticket, so a
#                          key generated for this run is enough. Never committed, never a real key: when unset,
#                          scripts/generate-ticket-key.sh makes a throwaway one (Node, already needed below).
#   MANAGEMENT_SERVER_PORT  forced to -1 below (Actuator's management server off): the run only reads /v3/api-docs on
#                          the API port, and a fixed management port (8081) would fail the boot on any machine that
#                          already uses it, Expo's Metro bundler included. Not overridable on purpose.
#   CONTRACT_DIFF_DIR      where intermediate specs are written (default: a fresh mktemp dir, kept on exit).
set -euo pipefail

cd "$(dirname "$0")/.."   # apps/backend

TMP="${CONTRACT_DIFF_DIR:-$(mktemp -d)}"
mkdir -p "$TMP"
export MSYS_NO_PATHCONV=1   # Git Bash on Windows: stop it rewriting /specs inside `docker run`

host_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }

# Always reads its document on stdin, so a host file path never has to cross into the container.
run_jq() {
  if command -v jq >/dev/null 2>&1; then
    jq "$@"
  else
    docker run --rm -i ghcr.io/jqlang/jq:latest "$@"
  fi
}

# Descriptive metadata of the whole API, not of an implemented operation; plus two things that are
# compared only through a normalisation that must be reviewed when it changes:
#   - info/servers/tags: API-level metadata.
#   - securitySchemes.bearerAuth.description: prose only. The global `security` requirement, the scheme's
#     type/scheme/bearerFormat and each operation's own `security` ARE compared (Mục 2.2 wired Spring
#     Security in; the earlier blanket exclusion of `.security`/`.components.securitySchemes` is gone —
#     test-matrix.md §4.4). The long description lives in the contract only.
#   - format: "int32": springdoc always emits it for a Java `int`; the contract writes plain
#     `type: integer`. Only int32 is dropped — int64 (`long`) is still compared.
NORMALISE='
  del(.info, .servers, .tags, .components.securitySchemes.bearerAuth.description)
  | walk(if type == "object" and .format == "int32" then del(.format) else . end)
'

echo "[1/5] generateOpenApiDocs (controller annotations -> spec)"
rm -f build/openapi.json   # never diff a stale spec from an earlier run
TICKET_KEY="${UNIHUB_TICKET_SIGNING_KEY:-$(scripts/generate-ticket-key.sh)}"
SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL:-jdbc:postgresql://127.0.0.1:5432/unihub}" \
UNIHUB_SECURITY_JWT_SECRET="${UNIHUB_SECURITY_JWT_SECRET:-contract-diff-placeholder-not-a-credential-0123456789abcdef}" \
UNIHUB_REDIS_ADDRESS="${UNIHUB_REDIS_ADDRESS:-redis://127.0.0.1:1}" \
UNIHUB_RATE_LIMIT_ENABLED=false \
UNIHUB_NOTIFICATION_SMTP_HOST="${UNIHUB_NOTIFICATION_SMTP_HOST:-127.0.0.1}" \
UNIHUB_TICKET_SIGNING_KEY="$TICKET_KEY" \
MANAGEMENT_SERVER_PORT=-1 \
  ./gradlew generateOpenApiDocs --no-daemon
cp build/openapi.json "$TMP/generated.json"

echo "[2/5] bundle the hand-written multi-file contract into one JSON file"
npx --yes @redocly/cli bundle ../../packages/api-contract/openapi.yaml \
  -o "$(host_path "$TMP/hand-written-full.json")"

echo "[3/5] keep only the operations (method + path) the generated spec already has"
# Phased rollout (test-plan.md): a module with no controller yet, or a path with only some of its
# operations built, contributes nothing to the diff. An operation present in code but absent from the
# contract is NOT removed here, so an undocumented endpoint still fails the job.
GENERATED_PATHS="$(run_jq '.paths' < "$TMP/generated.json")"
run_jq --argjson gen "$GENERATED_PATHS" '
  .paths |= (
    with_entries(select($gen[.key] != null))
    | with_entries(.key as $p | .value |= with_entries(select(.key as $k | ($gen[$p] | has($k)))))
  )' < "$TMP/hand-written-full.json" > "$TMP/hand-written-filtered.json"

echo "[4/5] dereference every \$ref, drop orphaned components, normalise both sides"
# Dereferencing compares each operation's fully resolved shape instead of component names: springdoc
# inlines parameters/headers/schemas the contract keeps as named \$refs, which says nothing about the wire.
for side in hand-written-filtered generated; do
  npx --yes @redocly/cli bundle "$(host_path "$TMP/$side.json")" --dereferenced --remove-unused-components \
    -o "$(host_path "$TMP/$side-deref.json")"
done
run_jq "$NORMALISE" < "$TMP/hand-written-filtered-deref.json" > "$TMP/hand-written.json"
run_jq "$NORMALISE" < "$TMP/generated-deref.json" > "$TMP/generated-norm.json"

echo "[5/5] oasdiff --fail-on-diff"
# description/summary/title/examples are prose, not shape — the contract's wording is not copied into
# Java annotations. --flatten-allof: the contract writes `allOf: [$ref X]` where springdoc writes X inline.
docker run --rm -v "$(host_path "$TMP"):/specs" tufin/oasdiff:latest diff \
  /specs/hand-written.json /specs/generated-norm.json \
  --flatten-allof --exclude-elements description,examples,summary,title --fail-on-diff

echo "contract-diff: OK (intermediate files: $TMP)"

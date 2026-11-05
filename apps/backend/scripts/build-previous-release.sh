#!/usr/bin/env bash
# Resolves and builds "the previous release": the application of the commit before the newest migration.
# INV-15 / Phase 7 checklist D88, 4.2; used by the integrationTest Gradle task `buildPreviousRelease`.
#
#   scripts/build-previous-release.sh <output-dir>
#
# The previous release (N-1) is the PARENT of the commit that added the newest Flyway migration of
# src/main/resources/db/migration — the code that is still serving while that migration runs. If the newest
# migration is not committed yet (you are adding it now), N-1 is HEAD. MigrationCompatibilityIT starts N-1's boot
# jar against a database migrated to the schema of HEAD.
#
# It writes <output-dir>/previous-release.properties (commit, jar, newestMigration, headCommit, uncommitted) and
# builds the jar once per commit (<output-dir>/<sha>/), from `git archive`, not from a worktree: nothing is
# registered in .git, so `gradlew clean` leaves no dangling worktree behind.
#
# It REFUSES a shallow clone. With one commit of history the commit that added the migration is HEAD itself and
# the "previous release" would be HEAD: the test would compare the application with itself and pass. CI checks out
# with fetch-depth: 0 for this job.
set -euo pipefail

OUT="${1:?usage: build-previous-release.sh <output-dir>}"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"

cd "$(git rev-parse --show-toplevel)"
MIGRATIONS="apps/backend/src/main/resources/db/migration"

if [ "$(git rev-parse --is-shallow-repository)" = "true" ]; then
  echo "build-previous-release: this is a shallow clone, so the commit before the newest migration is unknown." >&2
  echo "  Fetch the history (git fetch --unshallow; in GitHub Actions: actions/checkout with fetch-depth: 0)." >&2
  exit 2
fi

# The newest migration by Flyway version (V1 < V1_1 < V2 < V10): "1_1" sorts as 1.1.
newest="$(ls "$MIGRATIONS" | awk -F'__' '/^V[0-9_]+__.*\.sql$/ { v = substr($1, 2); gsub(/_/, ".", v); print v "\t" $0 }' \
  | sort -t. -k1,1n -k2,2n | tail -1 | cut -f2)"
[ -n "$newest" ] || { echo "build-previous-release: no migration found in $MIGRATIONS" >&2; exit 2; }

head_sha="$(git rev-parse HEAD)"
if git cat-file -e "HEAD:$MIGRATIONS/$newest" 2>/dev/null; then
  added_in="$(git log --diff-filter=A --format=%H -- "$MIGRATIONS/$newest" | tail -1)"
  previous="$(git rev-parse "$added_in^" 2>/dev/null)" \
    || { echo "build-previous-release: $newest was added by the root commit: there is no previous release" >&2; exit 2; }
  uncommitted=false
else
  previous="$head_sha"
  uncommitted=true
fi

dir="$OUT/$previous"
if ! ls "$dir"/apps/backend/build/libs/unihub-*-SNAPSHOT.jar >/dev/null 2>&1; then
  rm -rf "$dir"
  mkdir -p "$dir"
  git archive "$previous" | tar -x -C "$dir"
  # the whole tree is extracted: that commit's build.gradle.kts reads ../../packages and ../../observability
  (cd "$dir/apps/backend" && ./gradlew bootJar -x test --no-daemon -q)
fi
jar="$(ls "$dir"/apps/backend/build/libs/unihub-*-SNAPSHOT.jar | grep -v -- '-plain' | head -1)"

cat > "$OUT/previous-release.properties" <<PROPS
commit=$previous
jar=$jar
newestMigration=$newest
headCommit=$head_sha
uncommitted=$uncommitted
PROPS
echo "previous release: $previous (newest migration $newest; HEAD $head_sha; uncommitted=$uncommitted)"

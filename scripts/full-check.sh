#!/usr/bin/env bash
# The full check (CI): builds and tests everything, then runs every module's integration tests
# side by side. Run with --help for usage.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
# The modules with integration tests (*IT), whose tests run at the same time in step 2.
IT_MODULES=(lantern-sandbox lantern-extract lantern-link lantern-compose lantern-app)

usage() {
  cat <<EOF
Usage: scripts/full-check.sh [<Maven argument>...]

Runs the same tests as ./mvnw verify, in two steps:
  1. ./mvnw -T 1C install -DskipITs: compiles, runs the unit and frontend tests and the
     formatting check, and installs every module.
  2. Runs the integration tests of ${IT_MODULES[*]}
     at the same time, one Maven process each (failsafe:integration-test failsafe:verify).

Both steps install to and read from a local Maven repository of this checkout,
target/full-check/repository, chained to the shared one, which they only read
(maven.repo.local.tail). So step 2 tests this checkout's classes, and other checkouts'
builds never see them. Each module's step 2 output is in target/full-check/<module>.log.

Maven arguments, such as -Dlantern.test.project0=<path>, are passed to both steps.
Exits non-zero if any step fails.
EOF
}

case "${1:-}" in
  -h | --help) usage; exit 0 ;;
esac

cd "$REPO"
OUT="$REPO/target/full-check"
mkdir -p "$OUT"
HEAD_REPO="$OUT/repository"

# The shared local repository, as Maven's settings name it.
SHARED_REPO="$(./mvnw -q -N help:evaluate -Dexpression=settings.localRepository -DforceStdout "$@")"
[[ -d "$SHARED_REPO" ]] || { echo "full-check.sh: no local Maven repository at $SHARED_REPO" >&2; exit 1; }

REPOS=(
  "-Dmaven.repo.local=$HEAD_REPO"
  "-Dmaven.repo.local.tail=$SHARED_REPO"
  "-Dlantern.test.localRepository=$SHARED_REPO"
)

start=$SECONDS
echo "full-check.sh: step 1, build and unit tests" >&2
./mvnw -T 1C install -DskipITs "${REPOS[@]}" "$@"
step1=$((SECONDS - start))

echo "full-check.sh: step 2, integration tests of ${IT_MODULES[*]}" >&2
declare -A pids
# Stopping the script stops the Maven processes of step 2 too.
trap 'kill "${pids[@]}" 2>/dev/null; exit 130' INT TERM
for module in "${IT_MODULES[@]}"; do
  ./mvnw -pl "$module" "${REPOS[@]}" "$@" failsafe:integration-test failsafe:verify \
    >"$OUT/$module.log" 2>&1 &
  pids[$module]=$!
done

failed=()
for module in "${IT_MODULES[@]}"; do
  if wait "${pids[$module]}"; then
    result=passed
  else
    result=FAILED
    failed+=("$module")
  fi
  summary=$(grep -E '^\[(INFO|WARNING|ERROR)\] Tests run:' "$OUT/$module.log" | tail -1 \
    | sed -E 's/^\[[A-Z]+\] //' || true)
  echo "full-check.sh: $module $result: ${summary:-no tests run} (log: $OUT/$module.log)" >&2
done
step2=$((SECONDS - start - step1))

echo "full-check.sh: step 1 took ${step1}s, step 2 ${step2}s, in all $((SECONDS - start))s" >&2
if [[ ${#failed[@]} -gt 0 ]]; then
  for module in "${failed[@]}"; do
    echo "--- $module: last lines of $OUT/$module.log" >&2
    tail -40 "$OUT/$module.log" >&2
  done
  echo "full-check.sh: FAILED: ${failed[*]}" >&2
  exit 1
fi
echo "full-check.sh: all passed" >&2

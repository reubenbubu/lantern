#!/usr/bin/env bash
# Sets up the synthetic test set project-8, FleetCare (docs/adr/0029-synthetic-test-set-project-0.md,
# amendment for #285): fetches the pinned commit of lantern-test-fleetcare listed in pins.txt into a
# temporary folder and runs its setup.sh, which makes one local git repository per made-up repository
# under the destination, then copies the set's expected answers (its expected/ folder: the reference
# and its alias table) to <destination>/expected, where the scorer reads them.
#
# Usage: testsets/project-8/setup.sh [destination]
# The destination defaults to ~/workspace/lantern-test-projects/project-8. The repository is
# private, so git needs credentials for github.com (for example from `gh auth setup-git`).
# Records the pin in <destination>/.lantern-pin, so scripts can tell the set is at the current pin.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export GIT_TERMINAL_PROMPT=0

fail() {
  echo "error: $*" >&2
  exit 1
}

pin="$(grep -v -e '^#' -e '^[[:space:]]*$' "$here/pins.txt" || true)"
[[ -n "$pin" && "$(wc -l <<< "$pin")" -eq 1 ]] || fail "pins.txt must list exactly one repository"
read -r name url commit extra <<< "$pin"
[[ -n "$url" && -z "${extra:-}" ]] || fail "bad line in pins.txt: $pin"
[[ "$commit" =~ ^[0-9a-f]{40}$ ]] || fail "$name: commit must be a full 40-character hash: $commit"

dest="${1:-$HOME/workspace/lantern-test-projects/project-8}"
checkout="$(mktemp -d)"
trap 'rm -rf "$checkout"' EXIT
git init --quiet "$checkout"
git -C "$checkout" remote add origin "$url"
git -C "$checkout" fetch --quiet --depth 1 origin "$commit"
git -C "$checkout" -c advice.detachedHead=false checkout --quiet --detach "$commit"
echo "$name @ ${commit:0:12}"
bash "$checkout/setup.sh" "$dest"
rm -rf "$dest/expected"
cp -R "$checkout/expected" "$dest/expected"
# Records the pin the destination was set up from, which scripts/score-testsets.sh checks.
echo "$commit" > "$dest/.lantern-pin"

#!/usr/bin/env bash
# Sets up the project-0 test set (docs/adr/0029-synthetic-test-set-project-0.md): fetches the
# pinned commit of lantern-project-0 listed in pins.txt into a temporary folder and runs its
# setup.sh, which makes one local git repository per made-up repository under the destination.
#
# Usage: testsets/project-0/setup.sh [destination]
# The destination defaults to ~/workspace/lantern-test-projects/project-0. The repository is
# private, so git needs credentials for github.com (for example from `gh auth setup-git`).
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

checkout="$(mktemp -d)"
trap 'rm -rf "$checkout"' EXIT
git init --quiet "$checkout"
git -C "$checkout" remote add origin "$url"
git -C "$checkout" fetch --quiet --depth 1 origin "$commit"
git -C "$checkout" -c advice.detachedHead=false checkout --quiet --detach "$commit"
echo "$name @ ${commit:0:12}"
bash "$checkout/setup.sh" "$@"

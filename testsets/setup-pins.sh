#!/usr/bin/env bash
# Puts the repositories listed in a test set's pins.txt at their pinned commits (docs/adr/0029).
# Used by the setup.sh of testsets/project-1 to project-7.
#
# Usage: testsets/setup-pins.sh <set folder> [destination]
# The destination defaults to ~/workspace/lantern-test-projects/<set folder name>. Each repository
# is cloned to <destination>/<name> if missing, then fetched and checked out detached at its pin;
# safe to run again. A repository with local changes that conflict with the pin is left as it is
# and setup fails. Private repositories need git credentials for github.com (`gh auth setup-git`).
set -euo pipefail

set_dir="$(cd "${1:?usage: setup-pins.sh <set folder> [destination]}" && pwd)"
dest="${2:-$HOME/workspace/lantern-test-projects/$(basename "$set_dir")}"
export GIT_TERMINAL_PROMPT=0

fail() {
  echo "error: $*" >&2
  exit 1
}

pins="$(grep -v -e '^#' -e '^[[:space:]]*$' "$set_dir/pins.txt" || true)"
[[ -n "$pins" ]] || fail "pins.txt lists no repository"
mkdir -p "$dest"
while read -r name url commit extra; do
  [[ -n "$name" && -n "$url" && -n "$commit" && -z "${extra:-}" ]] || fail "bad line in pins.txt: $name $url $commit ${extra:-}"
  [[ "$commit" =~ ^[0-9a-f]{40}$ ]] || fail "$name: commit must be a full 40-character hash: $commit"
  repo="$dest/$name"
  if [[ ! -d "$repo/.git" ]]; then
    [[ ! -e "$repo" ]] || fail "$repo exists and is not a git repository"
    git clone --quiet "$url" "$repo"
  fi
  if ! git -C "$repo" cat-file -e "$commit^{commit}" 2> /dev/null; then
    git -C "$repo" fetch --quiet origin "$commit" || git -C "$repo" fetch --quiet origin
  fi
  git -C "$repo" -c advice.detachedHead=false checkout --quiet --detach "$commit"
  echo "$name @ ${commit:0:12}"
done <<< "$pins"

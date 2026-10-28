#!/usr/bin/env bash
# Clones the OpenWMS test set listed in repos.txt, each at its pinned commit, into
# workspace/openwms/<name> at the top of this repository (gitignored).
#
# Safe to run again: a clone already at its pinned commit is left alone, and one at another
# commit is moved to the pinned one. The script stops, changing nothing in that clone, if a clone
# has a different origin URL or uncommitted changes to tracked files. Untracked files such as
# build output are kept.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
list="$here/repos.txt"
dest="$root/workspace/openwms"

export GIT_TERMINAL_PROMPT=0

fail() {
  echo "error: $*" >&2
  exit 1
}

# Fetches only the pinned commit and checks it out without a branch.
checkout_pinned() {
  local dir="$1" commit="$2"
  git -C "$dir" fetch --quiet --depth 1 origin "$commit"
  git -C "$dir" -c advice.detachedHead=false checkout --quiet --detach "$commit"
  [[ "$(git -C "$dir" rev-parse HEAD)" == "$commit" ]] || fail "$dir is not at $commit after checkout"
}

tmp=""
trap 'if [[ -n "$tmp" ]]; then rm -rf "$tmp"; fi' EXIT

mkdir -p "$dest"
count=0
declare -A seen=()
# The list is read on file descriptor 3 so that nothing run inside the loop can consume it.
while read -r -u 3 name url commit extra; do
  commit="${commit%$'\r'}"
  extra="${extra%$'\r'}"
  [[ -z "$name" || "$name" == \#* ]] && continue
  [[ -n "$url" && -n "$commit" && -z "${extra:-}" ]] || fail "bad line in $list: $name $url $commit $extra"
  [[ "$name" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || fail "bad repository name in $list: $name"
  [[ "$commit" =~ ^[0-9a-f]{40}$ ]] || fail "$name: commit must be a full 40-character hash: $commit"
  [[ -z "${seen[$name]:-}" ]] || fail "$name is listed twice in $list"
  seen[$name]=1
  count=$((count + 1))
  dir="$dest/$name"

  if [[ ! -e "$dir" ]]; then
    # Clone into a temporary directory first, so an interrupted run leaves no half-made clone.
    tmp="$dest/.$name.partial"
    rm -rf "$tmp"
    git init --quiet "$tmp"
    git -C "$tmp" remote add origin "$url"
    checkout_pinned "$tmp" "$commit"
    mv "$tmp" "$dir"
    tmp=""
    echo "cloned   $name @ ${commit:0:12}"
    continue
  fi

  [[ "$(git -C "$dir" rev-parse --show-toplevel 2>/dev/null)" == "$(cd "$dir" && pwd -P)" ]] \
    || fail "$dir exists but is not a git clone; move it away and run again"
  actual_url="$(git -C "$dir" config --get remote.origin.url 2>/dev/null || true)"
  [[ "$actual_url" == "$url" ]] || fail "$dir has origin '$actual_url', expected '$url'"
  if [[ "$(git -C "$dir" rev-parse HEAD 2>/dev/null || true)" == "$commit" ]]; then
    echo "ok       $name @ ${commit:0:12}"
    continue
  fi
  [[ -z "$(git -C "$dir" status --porcelain --untracked-files=no)" ]] \
    || fail "$dir has uncommitted changes; commit, stash or discard them and run again"
  checkout_pinned "$dir" "$commit"
  echo "moved    $name @ ${commit:0:12}"
done 3< "$list"

[[ "$count" -gt 0 ]] || fail "no repositories listed in $list"
echo "$count repositories at their pinned commits in $dest"

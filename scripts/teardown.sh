#!/usr/bin/env bash
# Stops the local database that scripts/launch.sh started.
# Run with --help for usage.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
COMPOSE_FILE="$REPO/compose.yaml"
PROJECT="${COMPOSE_PROJECT_NAME:-lantern}"

usage() {
  cat <<EOF
Usage: scripts/teardown.sh [--purge [--yes]]

Stops and removes the database container and network of compose project "$PROJECT"
(docker compose down). The data volume is kept, so the next launch finds the same data.
Nothing outside this compose project is touched.

Options:
  --purge     Also delete the project's data volume, and with it every stored snapshot,
              fact and summary. Asks first.
  -y, --yes   Do not ask before deleting the volume.
  -h, --help  Show this help.

Environment:
  COMPOSE_PROJECT_NAME  Compose project to stop. Default lantern.
EOF
}

die() {
  echo "teardown.sh: $*" >&2
  exit 1
}

purge=false
assume_yes=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --purge) purge=true ;;
    -y | --yes) assume_yes=true ;;
    -h | --help) usage; exit 0 ;;
    *) die "unknown argument $1 (see --help)" ;;
  esac
  shift
done

command -v docker >/dev/null 2>&1 || die "docker is not on the PATH"
docker info >/dev/null 2>&1 || die "the Docker daemon is not reachable (is it running, and may you use it?)"

down_args=(down)
if $purge; then
  if ! $assume_yes; then
    [[ -t 0 ]] || die "--purge deletes the data volume of project $PROJECT; pass --yes to confirm when not at a terminal"
    answer=""
    read -r -p "Delete the data volume of project $PROJECT and all its data? [y/N] " answer || true
    [[ "$answer" =~ ^[Yy]([Ee][Ss])?$ ]] || die "cancelled; nothing was stopped or deleted"
  fi
  down_args+=(--volumes)
fi

echo "teardown.sh: stopping the database of project $PROJECT" >&2
docker compose -f "$COMPOSE_FILE" -p "$PROJECT" "${down_args[@]}"
if $purge; then
  echo "teardown.sh: removed the data volume of project $PROJECT" >&2
else
  echo "teardown.sh: kept the data volume of project $PROJECT; --purge removes it" >&2
fi

#!/usr/bin/env bash
# Sets up the project-4 test set (docs/adr/0029-synthetic-test-set-project-0.md, amendment for #251):
# clones the repositories in pins.txt and checks each out at its pinned commit.
# Usage: testsets/project-4/setup.sh [destination]   (default ~/workspace/lantern-test-projects/project-4)
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$here/../setup-pins.sh" "$here" "$@"

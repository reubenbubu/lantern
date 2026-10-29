#!/usr/bin/env bash
# Sets up the HELD-OUT project-5 test set ("Held-out sets" in docs/design.md): clones the repositories in pins.txt and checks each out at its pinned commit.
# Nobody reads its code or analyzes it before Phase 2 acceptance (2D); see CLAUDE.md, How work is organized.
# Usage: testsets/project-5/setup.sh [destination]   (default ~/workspace/lantern-test-projects/project-5)
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$here/../setup-pins.sh" "$here" "$@"

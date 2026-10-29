#!/usr/bin/env bash
# HELD-OUT: pinned only, never read or analyzed before 2D acceptance (rule: docs/design.md, Test sets, and CLAUDE.md). Sets up the pins in pins.txt.
# Usage: testsets/project-7/setup.sh [destination]   (default ~/workspace/lantern-test-projects/project-7)
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$here/../setup-pins.sh" "$here" "$@"

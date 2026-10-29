#!/usr/bin/env bash
# Runs Lantern on the development test sets and scores each system report against the set's
# reference (docs/design.md, Quality bar; docs/adr/0072-scoring-harness.md).
# Run with --help for usage.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"

usage() {
  cat <<EOF
Usage: scripts/score-testsets.sh [options] [<set>...]

For each set (default: project-1 project-2 project-3 project-4), runs "analyze" on the
set's repositories as one system named after the set (scripts/launch.sh, which starts the
local database), then scores the system report against testsets/<set>/reference with
lantern-web/scripts/score.mjs. A real model costs money: only with the user's go-ahead.

Writes to <workspace>/scores/<label>/: for each set <set>/_system.c4.html and run.json
(the run's model for every role, its calls and cost), <set>.scorecard.json and
<set>.scorecard.md; with --compare-with, <set>.compare.md beside them.

Options:
  --llm-properties FILE     Named LLM settings file (docs/adr/0009-llm-configuration.md).
                            Default: \$LANTERN_LLM_PROPERTIES.
  --mirror-properties FILE  Optional Maven mirror settings (docs/adr/0030-maven-mirror.md).
  --role ROLE=MODEL         Serve ROLE (summarize, resolve, infer-intent, name-groups,
                            propose-rules, compose, translate) with the model named MODEL
                            under lantern.llm.models, for this run. Repeatable.
  --tier TIER=MODEL         The same for a tier (high-volume, reasoning). Repeatable.
  --label NAME              Names this run's folder of scorecards. Default: the date and
                            time, e.g. 20261029-1830.
  --compare-with LABEL      Also write each set's scorecard side by side with the one of
                            an earlier run of that label.
  --sets-dir DIR            Where the sets were set up (testsets/<set>/setup.sh).
                            Default: ~/workspace/lantern-test-projects.
  --fake-llm                Use a free fake model for every role instead of a settings file
                            (Spring profile fake-llm; models fake and fake-priced, the latter
                            priced like a real model so costs show). Nothing is called.
  --held-out                Allow the held-out sets project-5 to project-7. Nobody analyzes
                            them before Phase 2's real-model acceptance (docs/design.md, Test
                            sets); without this option they are refused.
  --score-only              Do not analyze: score each set's newest report as it is. The
                            sets need not be set up.
  -y, --yes                 Do not ask before analyzing (a real model costs money).
  -h, --help                Show this help.

Environment:
  LANTERN_WORKSPACE         Where builds, documents and scores go. Default <repository>/workspace.
  COMPOSE_PROJECT_NAME      Compose project of the database (see scripts/launch.sh).
EOF
}

die() {
  echo "score-testsets.sh: $*" >&2
  exit 1
}

info() {
  echo "score-testsets.sh: $*" >&2
}

llm_file="${LANTERN_LLM_PROPERTIES:-}"
mirror_file="${LANTERN_MIRROR_PROPERTIES:-}"
overrides=()
label="$(date +%Y%m%d-%H%M)"
compare_with=""
sets_dir="$HOME/workspace/lantern-test-projects"
score_only=false
fake_llm=false
held_out=false
assume_yes=false
sets=()

# A role or tier override as the app's property, e.g. summarize=haiku as
# lantern.llm.roles.summarize=haiku.
override() {
  local kind="$1" value="$2" key model
  [[ "$value" == *=* ]] || die "--${kind%s} needs NAME=MODEL, got '$value'"
  key="${value%%=*}"
  model="${value#*=}"
  [[ "$key" =~ ^[a-z][a-z-]*$ && -n "$model" ]] || die "bad --${kind%s} '$value'"
  overrides+=("--set" "lantern.llm.$kind.$key=$model")
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --llm-properties) [[ $# -ge 2 ]] || die "--llm-properties needs a file"; llm_file="$2"; shift 2 ;;
    --llm-properties=*) llm_file="${1#*=}"; shift ;;
    --mirror-properties) [[ $# -ge 2 ]] || die "--mirror-properties needs a file"; mirror_file="$2"; shift 2 ;;
    --mirror-properties=*) mirror_file="${1#*=}"; shift ;;
    --role) [[ $# -ge 2 ]] || die "--role needs ROLE=MODEL"; override roles "$2"; shift 2 ;;
    --role=*) override roles "${1#*=}"; shift ;;
    --tier) [[ $# -ge 2 ]] || die "--tier needs TIER=MODEL"; override tiers "$2"; shift 2 ;;
    --tier=*) override tiers "${1#*=}"; shift ;;
    --label) [[ $# -ge 2 ]] || die "--label needs a name"; label="$2"; shift 2 ;;
    --label=*) label="${1#*=}"; shift ;;
    --compare-with) [[ $# -ge 2 ]] || die "--compare-with needs a label"; compare_with="$2"; shift 2 ;;
    --compare-with=*) compare_with="${1#*=}"; shift ;;
    --sets-dir) [[ $# -ge 2 ]] || die "--sets-dir needs a folder"; sets_dir="$2"; shift 2 ;;
    --sets-dir=*) sets_dir="${1#*=}"; shift ;;
    --score-only) score_only=true; shift ;;
    --fake-llm) fake_llm=true; shift ;;
    --held-out) held_out=true; shift ;;
    -y | --yes) assume_yes=true; shift ;;
    -h | --help) usage; exit 0 ;;
    -*) die "unknown option $1 (see --help)" ;;
    *) sets+=("$1"); shift ;;
  esac
done
[[ ${#sets[@]} -gt 0 ]] || sets=(project-1 project-2 project-3 project-4)
[[ "$label" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || die "a label is letters, digits, '.', '_' and '-': '$label'"

workspace="${LANTERN_WORKSPACE:-$REPO/workspace}"
scores="$workspace/scores/$label"

# Node runs the scorer: the one on the PATH, else the one the Maven build installed.
node_bin="$(command -v node || true)"
[[ -n "$node_bin" ]] || node_bin="$REPO/lantern-web/target/node/node"
[[ -x "$node_bin" ]] || die "no node on the PATH and none in lantern-web/target/node; run ./mvnw install -DskipTests"

for set in "${sets[@]}"; do
  [[ -f "$REPO/testsets/$set/pins.txt" ]] || die "$set is not a test set (no testsets/$set/pins.txt)"
  if grep -q '^#.*HELD-OUT' "$REPO/testsets/$set/pins.txt" && ! $held_out; then
    die "$set is held out: nobody analyzes it before Phase 2's real-model acceptance (docs/design.md, Test sets); pass --held-out only then"
  fi
  [[ -f "$REPO/testsets/$set/reference/reference.c4.json" ]] || die "$set has no reference in testsets/$set/reference"
  if ! $score_only; then
    [[ -d "$sets_dir/$set" ]] || die "$set is not set up in $sets_dir/$set; run testsets/$set/setup.sh"
  fi
done

if $fake_llm && ! $score_only; then
  [[ -z "$llm_file" ]] || die "--fake-llm and --llm-properties exclude each other"
  # The settings of the fake models, outside the repository as launch.sh requires.
  llm_file="$(mktemp "${TMPDIR:-/tmp}/lantern-fake-llm-XXXXXX.properties")"
  trap 'rm -f "$llm_file"' EXIT
  cat > "$llm_file" <<'PROPERTIES'
lantern.llm.models.fake.provider=fake
lantern.llm.models.fake.id=fake-model
lantern.llm.models.fake.context-window=1000000
lantern.llm.models.fake.prices.input=0
lantern.llm.models.fake.prices.output=0
lantern.llm.models.fake-priced.provider=fake
lantern.llm.models.fake-priced.id=fake-priced
lantern.llm.models.fake-priced.context-window=1000000
lantern.llm.models.fake-priced.prices.input=3
lantern.llm.models.fake-priced.prices.output=15
lantern.llm.tiers.high-volume=fake
lantern.llm.tiers.reasoning=fake
PROPERTIES
  overrides+=("--set" "spring.profiles.active=fake-llm")
  assume_yes=true
fi

if ! $score_only && ! $assume_yes; then
  [[ -t 0 ]] || die "pass --yes to analyze when not at a terminal (a real model costs money)"
  answer=""
  read -r -p "Analyze ${#sets[@]} test set(s) with the models of ${llm_file:-<no settings file>}${overrides[*]:+ and ${overrides[*]}}? [y/N] " answer || true
  [[ "$answer" =~ ^[Yy]([Ee][Ss])?$ ]] || die "cancelled"
fi

# The newest snapshot folder of a system's documents, or nothing.
newest_documents() {
  local dir="$workspace/documents/$1" newest=""
  [[ -d "$dir" ]] || return 0
  newest="$(find "$dir" -mindepth 1 -maxdepth 1 -type d -regex '.*/[0-9]+' -printf '%f\n' | sort -n | tail -n 1)"
  [[ -n "$newest" ]] && echo "$dir/$newest"
  return 0
}

mkdir -p "$scores"
for set in "${sets[@]}"; do
  if ! $score_only; then
    repos=()
    while read -r name _url _commit; do
      [[ -z "$name" || "$name" == \#* ]] && continue
      path="$sets_dir/$set/$name"
      [[ -d "$path/.git" ]] || die "$path is not set up; run testsets/$set/setup.sh"
      repos+=("$name=$path")
    done < "$REPO/testsets/$set/pins.txt"
    [[ ${#repos[@]} -gt 0 ]] || die "$set: no repository to analyze"
    launch=("$REPO/scripts/launch.sh" --yes --system "$set")
    [[ -n "$llm_file" ]] && launch+=(--llm-properties "$llm_file")
    [[ -n "$mirror_file" ]] && launch+=(--mirror-properties "$mirror_file")
    info "$set: analyzing ${#repos[@]} repositories"
    LANTERN_WORKSPACE="$workspace" "${launch[@]}" ${overrides[@]+"${overrides[@]}"} -- "${repos[@]}" ||
      die "$set: the analysis failed"
  fi
  documents="$(newest_documents "$set")"
  [[ -n "$documents" && -f "$documents/_system.c4.html" ]] || die "$set: no system report under $workspace/documents/$set"
  mkdir -p "$scores/$set"
  cp "$documents/_system.c4.html" "$scores/$set/"
  run_args=()
  if [[ -f "$documents/run.json" ]]; then
    cp "$documents/run.json" "$scores/$set/"
    run_args=(--run "$scores/$set/run.json")
  fi
  "$node_bin" "$REPO/lantern-web/scripts/score.mjs" "$scores/$set/_system.c4.html" "$REPO/testsets/$set" \
    ${run_args[@]+"${run_args[@]}"} --out "$scores/$set" >&2
  if [[ -n "$compare_with" ]]; then
    earlier="$workspace/scores/$compare_with/$set.scorecard.json"
    if [[ -f "$earlier" ]]; then
      "$node_bin" "$REPO/lantern-web/scripts/score.mjs" compare "$earlier" "$scores/$set.scorecard.json" \
        --labels "$compare_with,$label" --out "$scores/$set.compare.md" >&2
    else
      info "$set: no scorecard of run $compare_with to compare with ($earlier)"
    fi
  fi
done
info "scorecards are in $scores"

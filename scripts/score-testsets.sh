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
lantern-web/scripts/score.mjs. A synthetic set without one there, such as project-8, is
scored against the expected answers its setup.sh puts in <sets-dir>/<set>/expected, and all
the git repositories of its checkout are analyzed. A real model costs money: only with the
user's go-ahead.

Writes to <workspace>/scores/<label>/: for each set <set>/_system.c4.html, run.json
(the run's model for every role, its calls and cost), extracted.jsonl.gz (the facts
the extractors found), linked.jsonl.gz and llm.jsonl.gz (the linker's facts and those of
origin llm, which the rows per role read with testsets/<set>/answer-key.json),
wording.jsonl.gz (the wording judge's verdicts, with --judge-wording), rejections.jsonl.gz
(every answer the checks rejected and every item dropped, with why), <set>.scorecard.json and <set>.scorecard.md, the extraction
coverage <set>.coverage.json and <set>.coverage.md (lantern-web/scripts/coverage.mjs,
when the set has a testsets/<set>/coverage.json); with --compare-with, <set>.compare.md
beside them.

Options:
  --llm-properties FILE     Named LLM settings file (docs/adr/0009-llm-configuration.md).
                            Default: \$LANTERN_LLM_PROPERTIES.
  --mirror-properties FILE  Optional Maven mirror settings (docs/adr/0030-maven-mirror.md).
  --role ROLE=MODEL         Serve ROLE (summarize, resolve, infer-intent, name-groups,
                            propose-rules, compose, translate, judge, screen, wording-judge) with the model named MODEL
                            under lantern.llm.models, for this run. Repeatable.
  --tier TIER=MODEL         The same for a tier (high-volume, reasoning). Repeatable.
  --set PROPERTY=VALUE      Set an app property for each run (scripts/launch.sh --set), such as
                            lantern.harness.calibrate=investigate-leads:1 for packet-size
                            calibration (docs/adr/0112-packet-size-calibration.md). Repeatable.
  --label NAME              Names this run's folder of scorecards. Default: the date and
                            time, e.g. 20261029-1830.
  --compare-with LABEL      Also write each set's scorecard side by side with the one of
                            an earlier run of that label.
  --sets-dir DIR            Where the sets were set up (testsets/<set>/setup.sh).
                            Default: ~/workspace/lantern-test-projects.
  --fake-llm                Use a free fake model for every role instead of a settings file
                            (Spring profile fake-llm; models fake and fake-priced, the latter
                            priced like a real model so costs show). Nothing is called.
  --record-calls            Write every LLM call of each run, request and response, one file
                            a call, to <set>/calls/ beside its report (lantern.llm.record-to).
                            A model's batch API is not used while recording, so batchable
                            calls cost the interactive price. The folder must be empty.
  --judge-wording           End each run with the wording judge (docs/adr/0111-wording-judge.md),
                            whose verdicts the scorecard's judged rows read. Serve it with a
                            model none of the writing roles use (--role wording-judge=MODEL).
  --held-out                Allow the held-out sets project-5 to project-7. Nobody analyzes
                            them before Phase 2's real-model acceptance (docs/design.md, Test
                            sets); without this option they are refused.
  --gates                   After each set's run, check the harness's two gates (docs/adr/0072-scoring-harness.md,
                            amendment for #755): rerun it unchanged (warm.run.json; it should make no
                            call), then with a one-line comment committed to one Java file in a
                            temporary clone of its repository (change.run.json and change-file.txt;
                            it should re-ask only the items resting on that file). The file is the
                            first controller of the set's repositories, else the first Java file.
  --change-file REPO:PATH   The file --gates changes, in the sets that have that repository.
  --jobs N                  Analyze up to N sets at once (#785). Each set runs as its own call of
                            this script, on a database of its own (compose project
                            <COMPOSE_PROJECT_NAME>-<set> on a free port, stopped when the set ends,
                            its data kept; interrupted, the sets not finished lose theirs), and
                            writes its log to <scores>/<set>.log. Lantern's jar
                            is built first, once, when it is missing or older than its sources.
                            Default 1: one set after another on one database.
  --score-only              Do not analyze: score each set's newest report as it is, the
                            one whose run.json was written last (the highest-numbered folder
                            when none has a run.json). The sets need not be set up.
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
judge_wording=false
record_calls=false
gates=false
change_file=""
held_out=false
assume_yes=false
max_jobs=1
sets=()
# Every argument but the sets and --jobs, for the call per set that --jobs makes.
passthrough=()

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
  token=("${@:1:2}")
  before=$#
  positional=false
  case "$1" in
    --jobs) [[ $# -ge 2 && "$2" =~ ^[1-9][0-9]*$ ]] || die "--jobs needs a number from 1"; max_jobs="$2"; shift 2; continue ;;
    --jobs=*) [[ "${1#*=}" =~ ^[1-9][0-9]*$ ]] || die "--jobs needs a number from 1"; max_jobs="${1#*=}"; shift; continue ;;
    --llm-properties) [[ $# -ge 2 ]] || die "--llm-properties needs a file"; llm_file="$2"; shift 2 ;;
    --llm-properties=*) llm_file="${1#*=}"; shift ;;
    --mirror-properties) [[ $# -ge 2 ]] || die "--mirror-properties needs a file"; mirror_file="$2"; shift 2 ;;
    --mirror-properties=*) mirror_file="${1#*=}"; shift ;;
    --role) [[ $# -ge 2 ]] || die "--role needs ROLE=MODEL"; override roles "$2"; shift 2 ;;
    --role=*) override roles "${1#*=}"; shift ;;
    --tier) [[ $# -ge 2 ]] || die "--tier needs TIER=MODEL"; override tiers "$2"; shift 2 ;;
    --tier=*) override tiers "${1#*=}"; shift ;;
    --set) [[ $# -ge 2 && "$2" == ?*=* ]] || die "--set needs PROPERTY=VALUE"; overrides+=("--set" "$2"); shift 2 ;;
    --set=?*=*) overrides+=("--set" "${1#--set=}"); shift ;;
    --label) [[ $# -ge 2 ]] || die "--label needs a name"; label="$2"; shift 2 ;;
    --label=*) label="${1#*=}"; shift ;;
    --compare-with) [[ $# -ge 2 ]] || die "--compare-with needs a label"; compare_with="$2"; shift 2 ;;
    --compare-with=*) compare_with="${1#*=}"; shift ;;
    --sets-dir) [[ $# -ge 2 ]] || die "--sets-dir needs a folder"; sets_dir="$2"; shift 2 ;;
    --sets-dir=*) sets_dir="${1#*=}"; shift ;;
    --score-only) score_only=true; shift ;;
    --fake-llm) fake_llm=true; shift ;;
    --held-out) held_out=true; shift ;;
    --judge-wording) judge_wording=true; shift ;;
    --record-calls) record_calls=true; shift ;;
    --gates) gates=true; shift ;;
    --change-file) [[ $# -ge 2 && "$2" == ?*:?* ]] || die "--change-file needs REPO:PATH"; change_file="$2"; shift 2 ;;
    --change-file=?*:?*) change_file="${1#*=}"; shift ;;
    -y | --yes) assume_yes=true; shift ;;
    -h | --help) usage; exit 0 ;;
    -*) die "unknown option $1 (see --help)" ;;
    *) sets+=("$1"); positional=true; shift ;;
  esac
  $positional || passthrough+=("${token[@]:0:$((before - $#))}")
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
  if [[ -f "$REPO/testsets/$set/reference/reference.c4.json" ]]; then
    if ! $score_only; then
      [[ -d "$sets_dir/$set" ]] || die "$set is not set up in $sets_dir/$set; run testsets/$set/setup.sh"
    fi
  else
    # A synthetic set keeps its expected answers in its source, and its setup.sh puts them beside
    # the checkout (docs/adr/0029-synthetic-test-set-project-0.md, amendment for #285).
    [[ -f "$sets_dir/$set/expected/reference/reference.c4.json" ]] ||
      die "$set has no reference in testsets/$set/reference or $sets_dir/$set/expected/reference; run testsets/$set/setup.sh"
    pinned="$(grep -v -e '^#' -e '^[[:space:]]*$' "$REPO/testsets/$set/pins.txt" | awk '{ print $3 }')"
    [[ "$(cat "$sets_dir/$set/.lantern-pin" 2>/dev/null)" == "$pinned" ]] ||
      die "$sets_dir/$set is not set up at the pin in testsets/$set/pins.txt; run testsets/$set/setup.sh"
  fi
done

# The folder whose reference/ the scorer reads: the set's own, or a synthetic set's expected answers.
reference_dir() {
  if [[ -d "$REPO/testsets/$1/reference" ]]; then
    echo "$REPO/testsets/$1"
  else
    echo "$sets_dir/$1/expected"
  fi
}

# Temporary files and folders, removed however the script ends.
temporary=()
temporary_dirs=()
cleanup() {
  [[ ${#temporary[@]} -eq 0 ]] || rm -f "${temporary[@]}"
  [[ ${#temporary_dirs[@]} -eq 0 ]] || rm -rf "${temporary_dirs[@]}"
}
trap cleanup EXIT

if $fake_llm && ! $score_only; then
  [[ -z "$llm_file" ]] || die "--fake-llm and --llm-properties exclude each other"
  # The settings of the fake models, in a temporary file of their own.
  llm_file="$(mktemp "${TMPDIR:-/tmp}/lantern-fake-llm-XXXXXX.properties")"
  temporary+=("$llm_file")
  cat > "$llm_file" <<'PROPERTIES'
lantern.llm.models.fake.provider=fake
lantern.llm.models.fake.id=fake-model
lantern.llm.models.fake.context-window=1000000
lantern.llm.models.fake.prices.input=0
lantern.llm.models.fake.prices.output=0
lantern.llm.models.fake.prices.cache-write=0
lantern.llm.models.fake.prices.cache-read=0
lantern.llm.models.fake.prices.cache-write-long=0
lantern.llm.models.fake.capabilities=prompt-caching,batch,tool-use
lantern.llm.models.fake-priced.provider=fake
lantern.llm.models.fake-priced.id=fake-priced
lantern.llm.models.fake-priced.context-window=1000000
lantern.llm.models.fake-priced.prices.input=3
lantern.llm.models.fake-priced.prices.output=15
lantern.llm.models.fake-priced.prices.cache-write=3.75
lantern.llm.models.fake-priced.prices.cache-read=0.3
lantern.llm.models.fake-priced.prices.cache-write-long=6
lantern.llm.models.fake-priced.capabilities=prompt-caching,batch,tool-use
lantern.llm.tiers.high-volume=fake
lantern.llm.tiers.reasoning=fake
PROPERTIES
  overrides+=("--set" "spring.profiles.active=fake-llm")
  assume_yes=true
fi
if [[ "$judge_wording" == true ]]; then
  overrides+=("--set" "lantern.judge.wording=true")
fi

if ! $score_only && ! $assume_yes; then
  [[ -t 0 ]] || die "pass --yes to analyze when not at a terminal (a real model costs money)"
  answer=""
  read -r -p "Analyze ${#sets[@]} test set(s) with the models of ${llm_file:-<no settings file>}${overrides[*]:+ and ${overrides[*]}}? [y/N] " answer || true
  [[ "$answer" =~ ^[Yy]([Ee][Ss])?$ ]] || die "cancelled"
fi

# --jobs: each set as its own call of this script, on a database of its own (#785).
if ((max_jobs > 1)) && ($score_only || ((${#sets[@]} == 1))); then
  info "--jobs has nothing to run at once here (one set, or --score-only); one after another"
fi
if ((max_jobs > 1)) && ((${#sets[@]} > 1)) && ! $score_only; then
  base_project="${COMPOSE_PROJECT_NAME:-lantern}"
  # Lantern's jar is built here, once, so the calls per set never build it at the same time.
  "$REPO/scripts/launch.sh" --build-only >&2 || die "the app jar could not be built"
  mkdir -p "$scores"
  # A free port for each set's database, none handed out twice, unless its database already runs.
  free_port() {
    local port="$1"
    while ((port <= 65535)); do
      if [[ " ${taken[*]-} " != *" $port "* ]] && ! (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
        echo "$port"
        return 0
      fi
      ((port++))
    done
    return 1
  }
  taken=()
  next_port=$((${LANTERN_DB_PORT:-5432} + 1))
  declare -A pid_of=()
  started=()
  # However this ends (a failure, Ctrl-C), no call per set and no database of one is left behind:
  # a background call ignores Ctrl-C, so it is stopped here, with what it started.
  # Stops a process and everything it started, children first.
  kill_tree() {
    local child
    for child in $(pgrep -P "$1" 2>/dev/null || true); do
      kill_tree "$child"
    done
    kill -TERM "$1" 2>/dev/null || true
  }
  stop_sets() {
    trap - INT TERM EXIT
    local set
    local -A unfinished=()
    for set in "${started[@]-}"; do
      [[ -n "$set" ]] || continue
      if kill -0 "${pid_of[$set]}" 2>/dev/null; then
        unfinished[$set]=1
        kill_tree "${pid_of[$set]}"
      fi
    done
    wait 2>/dev/null || true
    # A set stopped part way may have stopped its database while it was first set up, which leaves
    # data a later start cannot use: its data goes. A finished set has already stopped its own.
    for set in "${!unfinished[@]}"; do
      COMPOSE_PROJECT_NAME="$base_project-$set" "$REPO/scripts/teardown.sh" --purge --yes >/dev/null 2>&1 || true
    done
    cleanup
  }
  trap 'stop_sets; exit 130' INT TERM
  trap 'stop_sets' EXIT
  started_at=$SECONDS
  for set in "${sets[@]}"; do
    while (($(jobs -rp | wc -l) >= max_jobs)); do
      wait -n || true
    done
    project="$base_project-$set"
    port=""
    if [[ -z "$(docker compose -p "$project" ps -q --status running postgres 2>/dev/null)" ]]; then
      port="$(free_port "$next_port")" || die "no free port for the database of $set"
      taken+=("$port")
      next_port=$((port + 1))
    fi
    info "$set: analyzing on its own database (project $project${port:+, port $port}); log in $scores/$set.log"
    (
      export COMPOSE_PROJECT_NAME="$project"
      # A database of this set that already runs keeps its own port.
      if [[ -n "$port" ]]; then export LANTERN_DB_PORT="$port"; else unset LANTERN_DB_PORT; fi
      status=0
      # The label is passed on: a default one, from the clock, could differ between calls.
      "$0" ${passthrough[@]+"${passthrough[@]}"} --label "$label" --yes "$set" > "$scores/$set.log" 2>&1 || status=$?
      "$REPO/scripts/teardown.sh" >> "$scores/$set.log" 2>&1 || true
      exit "$status"
    ) &
    pid_of[$set]=$!
    started+=("$set")
  done
  failed=()
  for set in "${sets[@]}"; do
    wait "${pid_of[$set]}" || failed+=("$set")
  done
  trap - INT TERM EXIT
  info "${#sets[@]} sets in $((SECONDS - started_at)) s, up to $max_jobs at once"
  [[ ${#failed[@]} -eq 0 ]] || die "failed: ${failed[*]} (see $scores/<set>.log)"
  info "scorecards are in $scores"
  exit 0
fi

# The snapshot folders of a system's documents whose run.json was written after the file given,
# or all of them without one, newest run.json first. A folder's number is the snapshot's, which
# starts from 1 again after the database is purged (scripts/teardown.sh --purge), so the highest
# number need not be the latest run (#610).
written_documents() {
  local dir="$workspace/documents/$1" since="${2:-}"
  [[ -d "$dir" ]] || return 0
  local newer=()
  [[ -n "$since" ]] && newer=(-newer "$since")
  find "$dir" -mindepth 2 -maxdepth 2 -name run.json -regex '.*/[0-9]+/run\.json' \
    ${newer[@]+"${newer[@]}"} -printf '%T@ %h\n' | sort -rn | cut -d' ' -f2-
}

# The newest documents of a system without analyzing: the folder whose run.json was written last,
# else, where no folder has one (documents from before run.json), the highest-numbered folder.
newest_documents() {
  local dir="$workspace/documents/$1" folders
  mapfile -t folders < <(written_documents "$1")
  if [[ ${#folders[@]} -gt 0 ]]; then
    echo "${folders[0]}"
  elif [[ -d "$dir" ]]; then
    local newest
    newest="$(find "$dir" -mindepth 1 -maxdepth 1 -type d -regex '.*/[0-9]+' -printf '%f\n' | sort -n | tail -n 1)"
    [[ -z "$newest" ]] || echo "$dir/$newest"
  fi
  return 0
}

# The folder of documents the run just made, which must be the only one written since it started.
run_documents() {
  local set="$1" since="$2" folders
  mapfile -t folders < <(written_documents "$set" "$since")
  [[ ${#folders[@]} -eq 1 ]] ||
    die "$set: expected the analysis to write one folder of documents under $workspace/documents/$set, found ${#folders[@]}${folders[*]:+: ${folders[*]}}"
  echo "${folders[0]}"
}

# The file --gates changes in a set: --change-file where the set has its repository, else the first
# controller of its repositories in their order, else the first repository's first Java file; as
# repo:path. A controller is one most items rest on: its type's summary and its unit's description.
gate_file() {
  local name path file pattern
  if [[ -n "$change_file" ]]; then
    for entry in "$@"; do
      name="${entry%%=*}"
      if [[ "$name" == "${change_file%%:*}" ]]; then
        echo "$change_file"
        return 0
      fi
    done
  fi
  for pattern in '*Controller.java' '*.java'; do
    for entry in "$@"; do
      name="${entry%%=*}"
      path="${entry#*=}"
      file="$(git -C "$path" ls-files -- "$pattern" | grep '/src/main/java/\|^src/main/java/' | sort | head -n 1 || true)"
      if [[ -n "$file" ]]; then
        echo "$name:$file"
        return 0
      fi
    done
  done
  return 1
}

# Analyzes a set's repositories once more for a gate and copies the run's record to the file given.
# The run's documents are removed, so the set's newest documents stay those of its main run
# (--score-only scores them).
gate_run() {
  local set="$1" out="$2" since documents
  shift 2
  since="$(mktemp "${TMPDIR:-/tmp}/lantern-score-started-XXXXXX")"
  temporary+=("$since")
  LANTERN_WORKSPACE="$workspace" "${gate_launch[@]}" ${overrides[@]+"${overrides[@]}"} -- "$@" ||
    die "$set: the gate's analysis failed"
  documents="$(run_documents "$set" "$since")" || exit 1
  [[ -f "$documents/run.json" ]] || die "$set: the gate's run wrote no run.json"
  cp "$documents/run.json" "$out"
  rm -rf "$documents"
}

mkdir -p "$scores"
for set in "${sets[@]}"; do
  if ! $score_only; then
    repos=()
    if [[ -f "$sets_dir/$set/.lantern-pin" ]]; then
      # A synthetic set: its one pinned source made every git repository of the checkout.
      for path in "$sets_dir/$set"/*/; do
        path="${path%/}"
        [[ -d "$path/.git" ]] && repos+=("$(basename "$path")=$path")
      done
    else
      while read -r name _url _commit; do
        [[ -z "$name" || "$name" == \#* ]] && continue
        path="$sets_dir/$set/$name"
        [[ -d "$path/.git" ]] || die "$path is not set up; run testsets/$set/setup.sh"
        repos+=("$name=$path")
      done < "$REPO/testsets/$set/pins.txt"
    fi
    [[ ${#repos[@]} -gt 0 ]] || die "$set: no repository to analyze"
    launch=("$REPO/scripts/launch.sh" --yes --system "$set")
    [[ -n "$llm_file" ]] && launch+=(--llm-properties "$llm_file")
    [[ -n "$mirror_file" ]] && launch+=(--mirror-properties "$mirror_file")
    # The gates' runs record no calls of their own.
    gate_launch=("${launch[@]}")
    # Each set's calls get their own folder, so one set's or one run's never mix with another's.
    if [[ "$record_calls" == true ]]; then
      [[ -z "$(ls -A "$scores/$set/calls" 2>/dev/null)" ]] ||
        die "$scores/$set/calls already holds calls; choose another --label"
      launch+=(--set "lantern.llm.record-to=$scores/$set/calls")
    fi
    info "$set: analyzing ${#repos[@]} repositories"
    started="$(mktemp "${TMPDIR:-/tmp}/lantern-score-started-XXXXXX")"
    temporary+=("$started")
    LANTERN_WORKSPACE="$workspace" "${launch[@]}" ${overrides[@]+"${overrides[@]}"} -- "${repos[@]}" ||
      die "$set: the analysis failed"
    documents="$(run_documents "$set" "$started")" || exit 1
    # A gate's runs belong to the main run they follow, never to a later one under the same label.
    rm -f "$scores/$set/warm.run.json" "$scores/$set/change.run.json" "$scores/$set/change-file.txt"
    if $gates; then
      mkdir -p "$scores/$set"
      info "$set: the warm rerun gate (an unchanged rerun should make no call)"
      gate_run "$set" "$scores/$set/warm.run.json" "${repos[@]}"
      target="$(gate_file "${repos[@]}")" || die "$set: no Java file to change for the one-file change gate"
      changed_repo="${target%%:*}"
      changed_path="${target#*:}"
      for entry in "${repos[@]}"; do
        if [[ "${entry%%=*}" == "$changed_repo" ]]; then
          # A Java file git tracks, so a // comment changes it and nothing else.
          [[ "$changed_path" == *.java ]] && git -C "${entry#*=}" ls-files --error-unmatch -- "$changed_path" >/dev/null 2>&1 ||
            die "$set: --change-file $target is not a Java file git tracks in $changed_repo"
        fi
      done
      clones="$(mktemp -d "${TMPDIR:-/tmp}/lantern-change-XXXXXX")"
      temporary_dirs+=("$clones")
      changed_repos=()
      for entry in "${repos[@]}"; do
        if [[ "${entry%%=*}" == "$changed_repo" ]]; then
          git clone --quiet "${entry#*=}" "$clones/$changed_repo"
          printf '// A comment the harness gate adds (#755).\n' >> "$clones/$changed_repo/$changed_path"
          git -C "$clones/$changed_repo" -c user.name="Lantern gate" -c user.email=gate@lantern.invalid \
            commit --quiet -am "A one-line change for the harness gate"
          changed_repos+=("$changed_repo=$clones/$changed_repo")
        else
          changed_repos+=("$entry")
        fi
      done
      info "$set: the one-file change gate (a comment in $target)"
      gate_run "$set" "$scores/$set/change.run.json" "${changed_repos[@]}"
      echo "$target" > "$scores/$set/change-file.txt"
    fi
  else
    documents="$(newest_documents "$set")"
  fi
  [[ -n "$documents" && -f "$documents/_system.c4.html" ]] || die "$set: no system report under $workspace/documents/$set"
  info "$set: scoring the documents in $documents"
  mkdir -p "$scores/$set"
  cp "$documents/_system.c4.html" "$scores/$set/"
  run_args=()
  if [[ -f "$documents/run.json" ]]; then
    cp "$documents/run.json" "$scores/$set/"
    run_args=(--run "$scores/$set/run.json")
  fi
  # The run's facts the rows per role read beside run.json, and the set's answer key
  # (docs/adr/0072-scoring-harness.md, amendment for #667).
  # With every answer the checks rejected and every item dropped, for the owner to read (#682).
  facts_files=(linked.jsonl.gz llm.jsonl.gz rejections.jsonl.gz)
  # The wording judge's verdicts only from a run that judged, never an earlier run's.
  [[ "$judge_wording" == true ]] && facts_files+=(wording.jsonl.gz)
  for facts in "${facts_files[@]}"; do
    [[ -f "$documents/$facts" ]] && cp "$documents/$facts" "$scores/$set/"
  done
  if [[ -f "$REPO/testsets/$set/answer-key.json" ]]; then
    run_args+=(--answer-key "$REPO/testsets/$set/answer-key.json")
  fi
  # The gates' runs, made by --gates now or before under the same label (#755).
  [[ -f "$scores/$set/warm.run.json" ]] && run_args+=(--warm "$scores/$set/warm.run.json")
  if [[ -f "$scores/$set/change.run.json" ]]; then
    run_args+=(--change "$scores/$set/change.run.json")
    [[ -f "$scores/$set/change-file.txt" ]] && run_args+=(--change-file "$(cat "$scores/$set/change-file.txt")")
  fi
  "$node_bin" "$REPO/lantern-web/scripts/score.mjs" "$scores/$set/_system.c4.html" "$(reference_dir "$set")" \
    ${run_args[@]+"${run_args[@]}"} --out "$scores/$set" >&2
  # Extraction coverage (docs/adr/0089-extraction-coverage.md): which reference items the
  # extracted facts back, before linking.
  if [[ -f "$documents/extracted.jsonl.gz" && -f "$REPO/testsets/$set/coverage.json" ]]; then
    cp "$documents/extracted.jsonl.gz" "$scores/$set/"
    "$node_bin" "$REPO/lantern-web/scripts/coverage.mjs" "$scores/$set/extracted.jsonl.gz" "$REPO/testsets/$set" \
      --out "$scores/$set" >&2
  else
    info "$set: no extraction coverage (needs extracted.jsonl.gz beside the report and testsets/$set/coverage.json)"
  fi
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

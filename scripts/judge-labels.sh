#!/usr/bin/env bash
# Judges the owner's labelled wording texts with the wording judge's current prompts.
# Run with --help for usage.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"

usage() {
  cat <<EOF
Usage: scripts/judge-labels.sh [options] [-- <judge-labels option>...]

Judges the labelled wording texts in testsets/wording-labels/ with the wording judge's
prompts as they are now in prompts/ (wording-judge-role.task.txt and
wording-judge-specific.task.txt), and prints, for each question, how many texts the labels
pass the judge passes, how many the labels fail it fails, and each text it got wrong with its
reasoning (docs/adr/0113-prompts-folder.md). Edit a prompt and run again: no rebuild. It
makes real calls on the model the settings file gives the wording-judge role (about \$0.30 a
round when that is Sonnet), without asking first; a rerun with unchanged prompts reuses the
stored answers and costs nothing.

It starts the local database through scripts/launch.sh, which it leaves running; stop it with
scripts/teardown.sh.

Options:
  --llm-properties FILE  LLM settings whose wording-judge role names the judge's model.
                         Default: \$LANTERN_JUDGE_PROPERTIES, else
                         ~/workspace/lantern-llm-deepseek-judge.properties.
  --key-file FILE        File holding the Anthropic API key, passed to the run only and never
                         printed. Default: \$LANTERN_ANTHROPIC_KEY_FILE, else
                         ~/workspace/anthropic.key. Not needed when ANTHROPIC_API_KEY is set.
  -h, --help             Show this help.

Options after -- go to the command:
  --split=tune|test|all  Which texts: tune (default) are for tuning; test are held out to
                         validate the judge at the end, so do not tune against them.
  --dimension=role|specific
                         One question only. Default: both.
  --labels=FOLDER        Another labels folder. Default: testsets/wording-labels.

Any other launch.sh option (such as --set) can be given before --.
EOF
}

die() {
  echo "judge-labels.sh: $*" >&2
  exit 1
}

llm_file="${LANTERN_JUDGE_PROPERTIES:-$HOME/workspace/lantern-llm-deepseek-judge.properties}"
key_file="${LANTERN_ANTHROPIC_KEY_FILE:-$HOME/workspace/anthropic.key}"
launch_options=()
command_options=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --llm-properties) [[ $# -ge 2 ]] || die "--llm-properties needs a file"; llm_file="$2"; shift 2 ;;
    --llm-properties=*) llm_file="${1#*=}"; shift ;;
    --key-file) [[ $# -ge 2 ]] || die "--key-file needs a file"; key_file="$2"; shift 2 ;;
    --key-file=*) key_file="${1#*=}"; shift ;;
    -h | --help) usage; exit 0 ;;
    --) shift; command_options+=("$@"); break ;;
    *) launch_options+=("$1"); shift ;;
  esac
done

[[ -f "$llm_file" ]] || die "LLM settings file not found: $llm_file (see --help)"
if [[ -z "${ANTHROPIC_API_KEY:-}" ]]; then
  if [[ -f "$key_file" ]]; then
    ANTHROPIC_API_KEY="$(tr -d '[:space:]' < "$key_file")"
    export ANTHROPIC_API_KEY
  else
    echo "judge-labels.sh: no Anthropic key file at $key_file and ANTHROPIC_API_KEY is not set;" \
      "a model reached through the Anthropic API will refuse the calls" >&2
  fi
fi

exec "$REPO/scripts/launch.sh" --yes --llm-properties "$llm_file" \
  ${launch_options[@]+"${launch_options[@]}"} --judge-labels \
  -- ${command_options[@]+"${command_options[@]}"}

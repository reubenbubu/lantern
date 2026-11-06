#!/usr/bin/env bash
# Starts Lantern's local database and runs an end-to-end analysis.
# Run with --help for usage.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
COMPOSE_FILE="$REPO/compose.yaml"
PROJECT="${COMPOSE_PROJECT_NAME:-lantern}"

usage() {
  cat <<EOF
Usage: scripts/launch.sh [options] [--] [<name>=]<path>...
       scripts/launch.sh [options] --judge-labels [-- <judge-labels option>...]

Starts this project's PostgreSQL database (compose project "$PROJECT"), waits until it
is healthy, builds the app jar if it is missing, and runs "analyze" on the given
repositories as one named system (docs/adr/0071-named-systems.md). Stop the database
afterwards with scripts/teardown.sh. With --judge-labels it instead judges the labelled
wording texts in testsets/wording-labels/ with the wording judge's current prompts and prints
how well the judge agrees with the labels (docs/adr/0113-prompts-folder.md); options after --
go to that command: --split=tune|test|all (default tune) and --dimension=role|specific.

Every run reads its prompts from this repository's prompts/ folder, so an edited prompt
takes effect without a rebuild.

Options:
  --llm-properties FILE     LLM settings (docs/adr/0009-llm-configuration.md). Required
                            unless --db-only. Default: \$LANTERN_LLM_PROPERTIES.
  --mirror-properties FILE  Optional Maven mirror settings (docs/adr/0030-maven-mirror.md).
                            Default: \$LANTERN_MIRROR_PROPERTIES.
  --system NAME             The system the repositories belong to. Default: the name of
                            the folder they are all in.
  --title TITLE             The system's title, kept for later runs (blank clears it).
  --set PROPERTY=VALUE      Set an app property for this run, e.g. --set
                            lantern.llm.roles.summarize=haiku to serve one role with another
                            model named in the LLM settings. Repeatable.
  --auto-port               If the database port is taken, use the next free port instead
                            of failing.
  --rebuild                 Build the app jar even if one exists. Without it the jar is built
                            when it is missing or older than any source file or pom.xml of the
                            app's modules (#785).
  --db-only                 Only start the database; do not build or analyze.
  --build-only              Only build the app jar, when missing or out of date; start no
                            database and analyze nothing.
  --judge-labels            Judge the labelled wording texts instead of analyzing.
  -y, --yes                 Do not ask before running analyze (a real model costs money).
                            Needed when not at a terminal.
  -h, --help                Show this help.

Environment:
  LANTERN_DB_URL            JDBC URL of a database to use as it is, such as one the host runs,
                            with LANTERN_DB_USERNAME and LANTERN_DB_PASSWORD (default lantern):
                            no database is started, no port checked, nothing stopped (#926).
  LANTERN_DB_PORT           Host port of the database. Default 5432.
  COMPOSE_PROJECT_NAME      Compose project to use. Default lantern.
  LANTERN_WORKSPACE         Where documents go. Default <repository>/workspace.
  LANTERN_BUILDS            Where the builds of the analyzed repositories are kept, shared by
                            every checkout, so a commit already built is not compiled again.
                            Default ~/workspace/lantern-builds.

Both properties files must be outside the repository.
EOF
}

die() {
  echo "launch.sh: $*" >&2
  exit 1
}

info() {
  echo "launch.sh: $*" >&2
}

llm_file="${LANTERN_LLM_PROPERTIES:-}"
command="analyze"
mirror_file="${LANTERN_MIRROR_PROPERTIES:-}"
auto_port=false
system=""
title=""
title_given=false
rebuild=false
db_only=false
build_only=false
assume_yes=false
app_options=()
args=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --llm-properties)
      [[ $# -ge 2 ]] || die "--llm-properties needs a file"
      llm_file="$2"
      shift 2
      ;;
    --llm-properties=*) llm_file="${1#*=}"; shift ;;
    --mirror-properties)
      [[ $# -ge 2 ]] || die "--mirror-properties needs a file"
      mirror_file="$2"
      shift 2
      ;;
    --mirror-properties=*) mirror_file="${1#*=}"; shift ;;
    --system)
      [[ $# -ge 2 ]] || die "--system needs a name"
      system="$2"
      shift 2
      ;;
    --system=*) system="${1#*=}"; shift ;;
    --title)
      [[ $# -ge 2 ]] || die "--title needs a title"
      title="$2"
      title_given=true
      shift 2
      ;;
    --title=*) title="${1#*=}"; title_given=true; shift ;;
    --set)
      [[ $# -ge 2 && "$2" == ?*=* ]] || die "--set needs PROPERTY=VALUE"
      app_options+=("--$2")
      shift 2
      ;;
    --set=?*=*) app_options+=("--${1#--set=}"); shift ;;
    --auto-port) auto_port=true; shift ;;
    --rebuild) rebuild=true; shift ;;
    --db-only) db_only=true; shift ;;
    --build-only) build_only=true; shift ;;
    --judge-labels) command="judge-labels"; shift ;;
    -y | --yes) assume_yes=true; shift ;;
    -h | --help) usage; exit 0 ;;
    --) shift; args+=("$@"); break ;;
    -*) die "unknown option $1 (see --help)" ;;
    *) args+=("$1"); shift ;;
  esac
done

# Resolves a properties file to an absolute path and refuses one inside the repository, unless
# keys_only is "keyless-ok" and the file sets no property whose name ends in an API key, a
# secret, a password or a token, in any spelling Spring accepts: the committed LLM settings of the
# lantern-commands skill (docs/adr/0102-batch-api-and-openai.md, amendment for #754) hold none.
check_properties_file() {
  local what="$1" file="$2" keys_only="${3:-}" path
  [[ -f "$file" && -r "$file" ]] || die "$what file not found or not readable: $file"
  path="$(realpath "$file")"
  if [[ "$path" == "$REPO" || "$path" == "$REPO"/* ]]; then
    if [[ "$keys_only" != "keyless-ok" ]] || grep -Eiq '^[[:space:]]*[^#![:space:]][^=:]*(api[-_.]?key|secret|password|token)[[:space:]]*[=:]' "$path"; then
      die "$what file $path is inside the repository; keep it outside so it is never committed"
    fi
  fi
  echo "$path"
}

if ! $db_only && ! $build_only; then
  if [[ "$command" == analyze ]]; then
    [[ ${#args[@]} -gt 0 ]] || die "no repositories to analyze given (see --help), or use --db-only"
  fi
  [[ -n "$llm_file" ]] || die "no LLM properties file; pass --llm-properties FILE or set LANTERN_LLM_PROPERTIES"
  llm_file="$(check_properties_file "LLM properties" "$llm_file" keyless-ok)"
  if [[ -n "$mirror_file" ]]; then
    mirror_file="$(check_properties_file "Mirror properties" "$mirror_file")"
  fi
  if ! $assume_yes && [[ ! -t 0 ]]; then
    die "pass --yes to run $command when not at a terminal (a real model costs money)"
  fi
fi

# --- App jar (the functions) -----------------------------------------------------------

# Prints the newest app jar, or nothing if there is none.
find_jar() {
  local jar newest=""
  for jar in "$REPO"/lantern-app/target/lantern-app-*-exec.jar; do
    [[ -f "$jar" ]] || continue
    if [[ -z "$newest" || "$jar" -nt "$newest" ]]; then
      newest="$jar"
    fi
  done
  echo "$newest"
}

# Prints a source file or pom.xml of the app's modules newer than the jar given, or nothing.
newer_source() {
  local sources=("$REPO/pom.xml")
  local module
  for module in "$REPO"/lantern-*; do
    [[ -f "$module/pom.xml" ]] && sources+=("$module/pom.xml")
    [[ -d "$module/src/main" ]] && sources+=("$module/src/main")
  done
  # The report template, built into the jar from the frontend's sources and its build files, and
  # the report specifications the app copies in.
  local extra
  for extra in lantern-web/src lantern-web/package.json lantern-web/package-lock.json \
    lantern-web/vite.config.ts lantern-web/index.html lantern-web/tsconfig.json report-specs; do
    [[ -e "$REPO/$extra" ]] && sources+=("$REPO/$extra")
  done
  find "${sources[@]}" -type f -newer "$1" -print -quit 2>/dev/null
}

# Sets jar to the app jar, building it first when it is missing, out of date or --rebuild asks.
ensure_jar() {
  jar="$(find_jar)"
  local why=""
  if $rebuild; then
    why="--rebuild"
  elif [[ -z "$jar" ]]; then
    why="no jar yet"
  else
    local newer
    newer="$(newer_source "$jar")"
    [[ -z "$newer" ]] || why="${newer#"$REPO"/} is newer than the jar"
  fi
  if [[ -n "$why" ]]; then
    info "building the app jar ($why; ./mvnw install -DskipTests)"
    (cd "$REPO" && ./mvnw -q install -DskipTests) >&2 || die "the build failed"
    jar="$(find_jar)"
    [[ -n "$jar" ]] || die "the build finished but no lantern-app-*-exec.jar is in lantern-app/target"
  fi
}

# --- Prerequisites -------------------------------------------------------------------

command -v git >/dev/null 2>&1 || die "git is not on the PATH"
if ! $build_only; then
  command -v docker >/dev/null 2>&1 || die "docker is not on the PATH"
  docker info >/dev/null 2>&1 || die "the Docker daemon is not reachable (is it running, and may you use it?)"
  docker compose version >/dev/null 2>&1 || die "the docker compose plugin is not installed"
fi
if ! $db_only; then
  command -v java >/dev/null 2>&1 || die "java is not on the PATH; Lantern needs JDK 21 or later"
  java_version="$(java -XshowSettings:properties -version 2>&1 |
    sed -n 's/^ *java\.specification\.version = //p')"
  [[ "$java_version" =~ ^[0-9]+$ ]] || die "could not read the Java version from 'java -version'"
  ((java_version >= 21)) || die "java is version $java_version; Lantern needs JDK 21 or later"
fi

if $build_only; then
  ensure_jar
  info "app jar: $jar"
  exit 0
fi

compose() {
  docker compose -f "$COMPOSE_FILE" -p "$PROJECT" "$@"
}

# --- Database ------------------------------------------------------------------------

# With LANTERN_DB_URL set, that database is used as it is, such as a PostgreSQL of the host that
# outlives containers (#926): no database is started, no port checked, nothing stopped.
if [[ -n "${LANTERN_DB_URL:-}" ]]; then
  info "using the database at $LANTERN_DB_URL, as it is (LANTERN_DB_URL)"
else
  # --- Database port -------------------------------------------------------------------

  # Prints what holds a TCP port on this host, or nothing if the port is free.
  port_holder() {
    local port="$1" out=""
    local containers
    containers="$(docker ps --filter "publish=$port" --format '{{.Names}} ({{.Ports}})')"
    [[ -n "$containers" ]] && out+="Docker container(s): $containers"$'\n'
    local listeners=""
    if command -v ss >/dev/null 2>&1; then
      listeners="$(ss -Htlnp "sport = :$port" 2>/dev/null || true)"
    elif command -v lsof >/dev/null 2>&1; then
      # lsof sees only this user's processes, so the connection probe below backs it up.
      listeners="$(lsof -nP -iTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)"
    fi
    if [[ -z "$listeners" ]] && (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
      listeners="something accepts connections on 127.0.0.1:$port"
    fi
    [[ -n "$listeners" ]] && out+="Listening: $listeners"$'\n'
    printf '%s' "$out"
  }

  requested_port="${LANTERN_DB_PORT:-5432}"
  if ! [[ "$requested_port" =~ ^[1-9][0-9]{0,4}$ ]] || ((requested_port > 65535)); then
    die "LANTERN_DB_PORT must be a port number from 1 to 65535, got '$requested_port'"
  fi

  running_port=""
  if [[ -n "$(compose ps -q --status running postgres 2>/dev/null)" ]]; then
    running_port="$({ compose port postgres 5432 2>/dev/null || true; } | sed -n 's/.*:\([0-9][0-9]*\)$/\1/p' | head -n 1)"
  fi

  if [[ -n "$running_port" ]]; then
    if [[ "$running_port" != "$requested_port" ]]; then
      if [[ -n "${LANTERN_DB_PORT:-}" ]]; then
        die "the database of project $PROJECT already runs on port $running_port, not $LANTERN_DB_PORT;" \
          "unset LANTERN_DB_PORT to use it, or stop it first with scripts/teardown.sh"
      fi
      info "the database of project $PROJECT already runs on port $running_port; using that port"
    else
      info "the database of project $PROJECT already runs on port $running_port"
    fi
    port="$running_port"
  else
    port="$requested_port"
    holder="$(port_holder "$port")"
    if [[ -n "$holder" ]]; then
      if ! $auto_port; then
        die "port $port is already in use, so the database cannot start on it."$'\n'"$holder"$'\n'"Set LANTERN_DB_PORT to a free port, or pass --auto-port."
      fi
      for ((candidate = port + 1; candidate <= port + 100 && candidate <= 65535; candidate++)); do
        if [[ -z "$(port_holder "$candidate")" ]]; then
          break
        fi
      done
      ((candidate <= port + 100 && candidate <= 65535)) || die "no free port found from $port to $((port + 100))"
      info "port $port is in use; using port $candidate instead"
      port="$candidate"
    fi
  fi

  # --- Database ------------------------------------------------------------------------

  export LANTERN_DB_PORT="$port"
  info "starting the database of project $PROJECT on port $port"
  compose up -d --wait postgres >&2 || die "the database did not start or did not become healthy; see 'docker compose -p $PROJECT logs postgres'"
  # Healthy inside its container is not yet reachable from the host: under rootless Docker the port
  # is forwarded a moment later, longer when several databases start at once (#785).
  # Postgres itself must answer: an SSL request, which it answers with one byte, N or S, while a
  # forwarder that is not ready yet accepts the connection and closes it.
  postgres_answers() {
    local reply=""
    exec 3<>"/dev/tcp/127.0.0.1/$1" || return 1
    printf '\x00\x00\x00\x08\x04\xd2\x16\x2f' >&3
    IFS= read -r -n 1 -t 2 reply <&3 || true
    exec 3<&- 3>&-
    [[ "$reply" == N || "$reply" == S ]]
  }
  for ((waited = 0; waited < 60; waited++)); do
    postgres_answers "$port" 2>/dev/null && break
    sleep 1
  done
  ((waited < 60)) || die "the database is healthy but port $port did not accept a connection within 60 s"
  export LANTERN_DB_URL="jdbc:postgresql://localhost:$port/lantern"
  info "database ready at $LANTERN_DB_URL"

fi

if $db_only; then
  exit 0
fi

# --- App jar -------------------------------------------------------------------------

ensure_jar

# --- Analyze -------------------------------------------------------------------------

locations="file:$llm_file"
[[ -n "$mirror_file" ]] && locations+=",file:$mirror_file"

info "LLM settings: $llm_file. A real model costs money; a fake one does not."
if ! $assume_yes; then
  answer=""
  if [[ "$command" == analyze ]]; then
    read -r -p "Run analyze on ${#args[@]} repositor$([[ ${#args[@]} -eq 1 ]] && echo y || echo ies)? [y/N] " answer || true
  else
    read -r -p "Judge the labelled wording texts? [y/N] " answer || true
  fi
  [[ "$answer" =~ ^[Yy]([Ee][Ss])?$ ]] || die "cancelled"
fi

# Relative repository paths stay relative to the caller's directory; documents still go to the
# repository's workspace folder, as when the app runs from the repository root.
export LANTERN_WORKSPACE="${LANTERN_WORKSPACE:-$REPO/workspace}"
info "documents go to $LANTERN_WORKSPACE/documents/<system>"
options=()
[[ -n "$system" ]] && options+=("--system=$system")
$title_given && options+=("--title=$title")
# The prompts of this repository, read at run time (docs/adr/0113-prompts-folder.md).
app_options+=("--lantern.prompts.dir=$REPO/prompts")
# One build store for every checkout on the machine (#786).
app_options+=("--lantern.sandbox.builds-dir=${LANTERN_BUILDS:-$HOME/workspace/lantern-builds}")
if [[ "$command" == judge-labels ]]; then
  cd "$REPO"
  exec java -jar "$jar" --spring.config.additional-location="$locations" \
    "${app_options[@]}" judge-labels ${args[@]+"${args[@]}"}
fi
exec java -jar "$jar" --spring.config.additional-location="$locations" \
  "${app_options[@]}" analyze ${options[@]+"${options[@]}"} "${args[@]}"

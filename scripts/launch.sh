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

Starts this project's PostgreSQL database (compose project "$PROJECT"), waits until it
is healthy, builds the app jar if it is missing, and runs "analyze" on the given
repositories as one named system (docs/adr/0071-named-systems.md). Stop the database
afterwards with scripts/teardown.sh.

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
  --rebuild                 Build the app jar even if one exists. An existing jar is not
                            rebuilt otherwise, so pass this after changing the code.
  --db-only                 Only start the database; do not build or analyze.
  -y, --yes                 Do not ask before running analyze (a real model costs money).
                            Needed when not at a terminal.
  -h, --help                Show this help.

Environment:
  LANTERN_DB_PORT           Host port of the database. Default 5432.
  COMPOSE_PROJECT_NAME      Compose project to use. Default lantern.
  LANTERN_WORKSPACE         Where builds and documents go. Default <repository>/workspace.

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
mirror_file="${LANTERN_MIRROR_PROPERTIES:-}"
auto_port=false
system=""
title=""
title_given=false
rebuild=false
db_only=false
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
    -y | --yes) assume_yes=true; shift ;;
    -h | --help) usage; exit 0 ;;
    --) shift; args+=("$@"); break ;;
    -*) die "unknown option $1 (see --help)" ;;
    *) args+=("$1"); shift ;;
  esac
done

# Resolves a properties file to an absolute path and refuses one inside the repository.
check_properties_file() {
  local what="$1" file="$2" path
  [[ -f "$file" && -r "$file" ]] || die "$what file not found or not readable: $file"
  path="$(realpath "$file")"
  [[ "$path" == "$REPO" || "$path" == "$REPO"/* ]] &&
    die "$what file $path is inside the repository; keep it outside so it is never committed"
  echo "$path"
}

if ! $db_only; then
  [[ ${#args[@]} -gt 0 ]] || die "no repositories to analyze given (see --help), or use --db-only"
  [[ -n "$llm_file" ]] || die "no LLM properties file; pass --llm-properties FILE or set LANTERN_LLM_PROPERTIES"
  llm_file="$(check_properties_file "LLM properties" "$llm_file")"
  if [[ -n "$mirror_file" ]]; then
    mirror_file="$(check_properties_file "Mirror properties" "$mirror_file")"
  fi
  if ! $assume_yes && [[ ! -t 0 ]]; then
    die "pass --yes to run analyze when not at a terminal (a real model costs money)"
  fi
fi

# --- Prerequisites -------------------------------------------------------------------

command -v git >/dev/null 2>&1 || die "git is not on the PATH"
command -v docker >/dev/null 2>&1 || die "docker is not on the PATH"
docker info >/dev/null 2>&1 || die "the Docker daemon is not reachable (is it running, and may you use it?)"
docker compose version >/dev/null 2>&1 || die "the docker compose plugin is not installed"
if ! $db_only; then
  command -v java >/dev/null 2>&1 || die "java is not on the PATH; Lantern needs JDK 21 or later"
  java_version="$(java -XshowSettings:properties -version 2>&1 |
    sed -n 's/^ *java\.specification\.version = //p')"
  [[ "$java_version" =~ ^[0-9]+$ ]] || die "could not read the Java version from 'java -version'"
  ((java_version >= 21)) || die "java is version $java_version; Lantern needs JDK 21 or later"
fi

compose() {
  docker compose -f "$COMPOSE_FILE" -p "$PROJECT" "$@"
}

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
export LANTERN_DB_URL="jdbc:postgresql://localhost:$port/lantern"
info "database ready at $LANTERN_DB_URL"

if $db_only; then
  exit 0
fi

# --- App jar -------------------------------------------------------------------------

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

jar="$(find_jar)"
if $rebuild || [[ -z "$jar" ]]; then
  info "building the app jar (./mvnw install -DskipTests)"
  (cd "$REPO" && ./mvnw -q install -DskipTests) >&2 || die "the build failed"
  jar="$(find_jar)"
  [[ -n "$jar" ]] || die "the build finished but no lantern-app-*-exec.jar is in lantern-app/target"
fi

# --- Analyze -------------------------------------------------------------------------

locations="file:$llm_file"
[[ -n "$mirror_file" ]] && locations+=",file:$mirror_file"

info "LLM settings: $llm_file. A real model costs money; a fake one does not."
if ! $assume_yes; then
  answer=""
  read -r -p "Run analyze on ${#args[@]} repositor$([[ ${#args[@]} -eq 1 ]] && echo y || echo ies)? [y/N] " answer || true
  [[ "$answer" =~ ^[Yy]([Ee][Ss])?$ ]] || die "cancelled"
fi

# Relative repository paths stay relative to the caller's directory; documents still go to the
# repository's workspace folder, as when the app runs from the repository root.
export LANTERN_WORKSPACE="${LANTERN_WORKSPACE:-$REPO/workspace}"
info "documents go to $LANTERN_WORKSPACE/documents/<system>"
options=()
[[ -n "$system" ]] && options+=("--system=$system")
$title_given && options+=("--title=$title")
exec java -jar "$jar" --spring.config.additional-location="$locations" \
  ${app_options[@]+"${app_options[@]}"} analyze ${options[@]+"${options[@]}"} "${args[@]}"

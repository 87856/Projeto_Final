#!/usr/bin/env bash
# multi.sh — launch several bots into the same arena room for bot-vs-bot testing.
#
# Usage:
#   bash multi.sh --room SALA123 [--modes berserker,coward,explorer] [--no-gui] [--no-build]
#
# Defaults: 3 bots (opportunist, berserker, coward), each with GUI.
# Ctrl+C kills all child bots cleanly.
#
# Source code is compiled; opponents only ever receive the jar.

set -u

JAR="target/agente-explorador-1.0-SNAPSHOT.jar"
DEFAULT_MODES=("opportunist" "berserker" "coward")
BOT_NAMES=("Alpha" "Beta" "Gamma" "Delta" "Epsilon" "Zeta" "Eta" "Theta")

ROOM=""
MODES=()
NO_GUI=0
DO_BUILD=1

while [ $# -gt 0 ]; do
  case "$1" in
    --room)     shift; ROOM="$1" ;;
    --modes)    shift; IFS=',' read -r -a MODES <<< "$1" ;;
    --no-gui)   NO_GUI=1 ;;
    --no-build) DO_BUILD=0 ;;
    -h|--help)
      cat <<'HELP'

USAGE
  bash multi.sh --room <code> [OPTIONS]

OPTIONS
  --room <code>       Arena room code (required)
  --modes m1,m2,...   Comma-separated mode list (default: opportunist,berserker,coward)
  --no-gui            Suppress HeatMap windows for all bots (faster, less VRAM)
  --no-build          Skip Maven build; reuse existing jar
  -h, --help          Show this help

EXAMPLES
  # 3 bots, with radar windows
  # room defaults to last used (saved by start.sh)
  bash multi.sh --modes opportunist,berserker,coward

  # 3 bots, explicit room
  bash multi.sh --room TEST99 --modes opportunist,berserker,coward

  # 5 bots headless (mode explorer + 4 rivals)
  bash multi.sh --room TEST99 --modes explorer,berserker,coward,farmer,ghost --no-gui

  # only build once, then reuse for rapid re-runs
  bash multi.sh --room TEST99 --no-build

NOTES
  - Each bot gets a unique name (Alpha, Beta, Gamma, ...).
  - Ollama inference is shared; bots don't block each other (async threads).
  - The jar is compiled code — opponents cannot read your source.
  - Use a private room code so other students don't join your test session.

HELP
      exit 0 ;;
    *) echo "[multi] Unknown flag: $1"; exit 1 ;;
  esac
  shift
done

if [ -z "$ROOM" ]; then
  PROPS="$HOME/.arena_agent.properties"
  if [ -f "$PROPS" ]; then
    ROOM=$(grep '^sala=' "$PROPS" | cut -d'=' -f2- | tr -d '[:space:]')
  fi
  if [ -z "$ROOM" ]; then
    echo "[multi] ERROR: --room not given and no saved room in ~/.arena_agent.properties."
    echo "  Run start.sh once manually, or pass --room TEST123."
    exit 1
  fi
  echo "[multi] Using saved room: $ROOM"
fi

[ ${#MODES[@]} -eq 0 ] && MODES=("${DEFAULT_MODES[@]}")

cd "$(dirname "$0")" || { echo "[multi] cannot cd to script dir"; exit 1; }

# Optional build — mirrors start.sh's /tmp workaround for NTFS mounts
if [ "$DO_BUILD" -eq 1 ]; then
  echo "[multi] Building (via /tmp workaround for NTFS)..."
  TMPBUILD=$(mktemp -d)
  cp -r src "$TMPBUILD/" && cp pom.xml "$TMPBUILD/"
  if ! mvn -f "$TMPBUILD/pom.xml" clean package -q; then
    echo "[multi] Build failed."
    rm -rf "$TMPBUILD"
    exit 1
  fi
  mkdir -p target
  cp "$TMPBUILD/target/"*.jar target/
  rm -rf "$TMPBUILD"
  echo "[multi] Build OK."
fi

if [ ! -f "$JAR" ]; then
  echo "[multi] Jar not found: $JAR — run without --no-build first."
  exit 1
fi

# Kill all child processes on Ctrl+C
PIDS=()
cleanup() {
  echo ""
  echo "[multi] Stopping all bots..."
  for pid in "${PIDS[@]}"; do
    kill "$pid" 2>/dev/null
  done
  wait 2>/dev/null
  echo "[multi] Done."
  exit 0
}
trap cleanup INT TERM

export OLLAMA_MAX_LOADED_MODELS="${OLLAMA_MAX_LOADED_MODELS:-2}"

echo "[multi] Room: $ROOM | Bots: ${#MODES[@]} | GUI: $([ $NO_GUI -eq 1 ] && echo OFF || echo ON)"
echo ""

for i in "${!MODES[@]}"; do
  NAME="${BOT_NAMES[$i]:-Bot$i}"
  MODE="${MODES[$i]}"

  CMD=(java
    "-Dbot.name=$NAME"
    "-Dbot.room=$ROOM"
    "-Dbot.mode=$MODE"
  )
  [ "$NO_GUI" -eq 1 ] && CMD+=("-Dbot.noGui=true")
  CMD+=("-jar" "$JAR")

  echo "[multi] Starting $NAME ($MODE)..."
  "${CMD[@]}" &
  PIDS+=($!)
  sleep 0.3   # stagger slightly so arena registrations don't collide
done

echo ""
echo "[multi] ${#PIDS[@]} bots running. Press Ctrl+C to stop all."
wait

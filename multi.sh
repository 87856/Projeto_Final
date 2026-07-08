#!/usr/bin/env bash
# multi.sh — launch several bots into the same arena room for bot-vs-bot testing.
#
# Interactive when run with no --modes flag.
# Non-interactive: bash multi.sh --room SALA --modes berserker,coward,explorer
#
# Ctrl+C kills all child bots cleanly.

set -u

JAR="target/agente-explorador-1.0-SNAPSHOT.jar"
DEFAULT_NAMES=("Alpha" "Beta" "Gamma" "Delta" "Epsilon" "Zeta" "Eta" "Theta")
STAGGER_MS=1500   # ms between bot registrations (avoids arena race)

ROOM=""
NO_GUI=0
DO_BUILD=1
NONINTERACTIVE=0
BOT_SERVER=""

# Per-bot arrays (populated either interactively or from --modes flag)
BOT_NAMES=()
BOT_MODES=()
BOT_BACKTRACK=()   # 0 or 1 per bot

# ---- colours ----------------------------------------------------------------
if [ -t 1 ]; then
  G="\033[32m"; Y="\033[33m"; C="\033[36m"; W="\033[97m"; Z="\033[0m"
else
  G=""; Y=""; C=""; W=""; Z=""
fi

# ---- arg parsing ------------------------------------------------------------
while [ $# -gt 0 ]; do
  case "$1" in
    --room)     shift; ROOM="$1" ;;
    --no-gui)   NO_GUI=1 ;;
    --no-build) DO_BUILD=0 ;;
    --local)
      shift 2>/dev/null || true
      _port="${1:-8080}"
      [[ "$_port" =~ ^[0-9]+$ ]] && BOT_SERVER="http://localhost:$_port" || BOT_SERVER="http://localhost:8080" ;;
    --server)   shift; BOT_SERVER="$1" ;;
    --modes)
      shift
      NONINTERACTIVE=1
      IFS=',' read -r -a _MODES <<< "$1"
      for i in "${!_MODES[@]}"; do
        BOT_NAMES+=("${DEFAULT_NAMES[$i]:-Bot$i}")
        BOT_MODES+=("${_MODES[$i]}")
        BOT_BACKTRACK+=(0)
      done ;;
    -h|--help)
      cat <<'HELP'

USAGE
  bash multi.sh [--room <code>] [OPTIONS]

  Run with no --modes to get an interactive prompt for each bot.

OPTIONS
  --room <code>       Arena room code (reads saved room if omitted)
  --modes m1,m2,...   Non-interactive: comma-separated mode list
  --no-gui            Suppress HeatMap windows for all bots
  --no-build          Skip Maven build; reuse existing jar
  -h, --help          Show this help

INTERACTIVE EXAMPLE
  bash multi.sh

NON-INTERACTIVE EXAMPLE
  bash multi.sh --room TEST99 --modes berserker,coward,explorer --no-gui

HELP
      exit 0 ;;
    *) echo "[multi] Unknown flag: $1"; exit 1 ;;
  esac
  shift
done

cd "$(dirname "$0")" || { echo "[multi] cannot cd to script dir"; exit 1; }

# ---- resolve room -----------------------------------------------------------
if [ -z "$ROOM" ]; then
  PROPS="$HOME/.arena_agent.properties"
  if [ -f "$PROPS" ]; then
    ROOM=$(grep '^sala=' "$PROPS" | cut -d'=' -f2- | tr -d '[:space:]')
  fi
  if [ -z "$ROOM" ]; then
    printf "${Y}Room code:${Z} "; read -r ROOM
    [ -z "$ROOM" ] && { echo "[multi] No room given. Aborting."; exit 1; }
  else
    printf "${C}[multi] Saved room: ${W}%s${Z}\n" "$ROOM"
  fi
fi

# ---- confirm room (always, unless non-interactive) -------------------------
if [ "$NONINTERACTIVE" -eq 0 ]; then
  printf "${Y}Room [${W}%s${Y}] — correct? [Y/n/edit]:${Z} " "$ROOM"
  read -r _rc
  case "$_rc" in
    [Nn]) printf "New room code: "; read -r ROOM; [ -z "$ROOM" ] && { echo "Aborted."; exit 1; } ;;
    [Ee]*) printf "Room code [%s]: " "$ROOM"; read -r _nr; [ -n "$_nr" ] && ROOM="$_nr" ;;
  esac
fi

# ---- interactive bot config -------------------------------------------------
if [ "$NONINTERACTIVE" -eq 0 ]; then
  printf "\n${G}=== Available Modes ===${Z}\n"
  printf "  ${C}%-14s${Z} %s\n" "opportunist"  "Balanced default"
  printf "  ${C}%-14s${Z} %s\n" "berserker"    "All-in aggression; flee at HP 20"
  printf "  ${C}%-14s${Z} %s\n" "dominator"    "Attack any rival in range 3"
  printf "  ${C}%-14s${Z} %s\n" "bully"        "Hunt weakest rival"
  printf "  ${C}%-14s${Z} %s\n" "coward"       "HIDE always; never fights"
  printf "  ${C}%-14s${Z} %s\n" "ghost"        "Flee from any visible rival"
  printf "  ${C}%-14s${Z} %s\n" "survivor"     "Resources first; flee at HP 120"
  printf "  ${C}%-14s${Z} %s\n" "passive"      "Retaliate only when cornered"
  printf "  ${C}%-14s${Z} %s\n" "farmer"       "Farm resources; ignore combat"
  printf "  ${C}%-14s${Z} %s\n" "treasure"     "Hunt chests; skip combat"
  printf "  ${C}%-14s${Z} %s\n" "hoarder"      "Chests first; light combat"
  printf "  ${C}%-14s${Z} %s\n" "rich"         "Open chests only"
  printf "  ${C}%-14s${Z} %s\n" "assassin"     "Attack only with 50+ HP advantage"
  printf "  ${C}%-14s${Z} %s\n" "scavenger"    "Farm safely; avoid combat"
  printf "  ${C}%-14s${Z} %s\n" "explorer"     "Max map coverage; skip combat"
  printf "  ${C}%-14s${Z} %s\n" "no-llm"       "Pure heuristic; no AI calls"
  printf "\n${G}=== Bot Configuration ===${Z}\n"

  printf "${Y}How many bots?${Z} [3]: "; read -r N
  N="${N:-3}"
  if ! [[ "$N" =~ ^[1-9][0-9]*$ ]]; then
    echo "[multi] Invalid number. Aborting."; exit 1
  fi

  for i in $(seq 1 "$N"); do
    idx=$((i-1))
    DEF_NAME="${DEFAULT_NAMES[$idx]:-Bot$idx}"
    printf "\n${G}── Bot %d ───────────────────────${Z}\n" "$i"

    printf "  ${Y}Name${Z} [%s]: " "$DEF_NAME"; read -r bname
    BOT_NAMES+=("${bname:-$DEF_NAME}")

    printf "  ${Y}Mode${Z} [opportunist]: "; read -r bmode
    BOT_MODES+=("${bmode:-opportunist}")

    printf "  ${Y}--no-backtrack?${Z} [y/N]: "; read -r bnb
    [[ "$bnb" =~ ^[Yy] ]] && BOT_BACKTRACK+=(1) || BOT_BACKTRACK+=(0)
  done

  printf "\n${Y}Suppress GUI for all bots?${Z} [y/N]: "; read -r ng
  [[ "$ng" =~ ^[Yy] ]] && NO_GUI=1
fi

# ---- summary + confirm ------------------------------------------------------
printf "\n${G}=== Launch Summary ===${Z}\n"
printf "  ${C}Room:${Z}  %s\n" "$ROOM"
printf "  ${C}GUI:${Z}   %s\n" "$([ "$NO_GUI" -eq 1 ] && echo OFF || echo ON)"
printf "  ${C}Bots:${Z}  %d\n\n" "${#BOT_MODES[@]}"
for i in "${!BOT_MODES[@]}"; do
  nb_str="$([ "${BOT_BACKTRACK[$i]}" -eq 1 ] && echo "+no-backtrack" || echo "")"
  printf "  ${W}%d.${Z} %-12s  mode=%-14s %s\n" \
    "$((i+1))" "${BOT_NAMES[$i]}" "${BOT_MODES[$i]}" "$nb_str"
done
printf "\n${Y}Launch? [Y/n]:${Z} "; read -r confirm
[[ "$confirm" =~ ^[Nn] ]] && { echo "[multi] Aborted."; exit 0; }

# ---- build ------------------------------------------------------------------
if [ "$DO_BUILD" -eq 1 ]; then
  printf "\n${C}[multi] Building (via /tmp — contorna restricoes NTFS)...${Z}\n"
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
  printf "${G}[multi] Build OK.${Z}\n"
fi

if [ ! -f "$JAR" ]; then
  echo "[multi] Jar not found: $JAR — run without --no-build first."
  exit 1
fi

# ---- kill all on Ctrl+C -----------------------------------------------------
PIDS=()
cleanup() {
  printf "\n${Y}[multi] Stopping all bots...${Z}\n"
  for pid in "${PIDS[@]}"; do kill "$pid" 2>/dev/null; done
  wait 2>/dev/null
  printf "${G}[multi] Done.${Z}\n"
  exit 0
}
trap cleanup INT TERM

export OLLAMA_MAX_LOADED_MODELS="${OLLAMA_MAX_LOADED_MODELS:-2}"

# ---- logging setup ----------------------------------------------------------
mkdir -p logs
TIMESTAMP=$(date +%Y-%m-%d_%H-%M-%S)
MULTI_LOG="logs/${TIMESTAMP}_multi_orchestration.log"
{
  echo "# multi.sh orchestration log"
  echo "# Started: $(date)"
  echo "# Room:    $ROOM"
  echo "# Bots:    ${#BOT_MODES[@]}"
  for i in "${!BOT_MODES[@]}"; do
    echo "#   $((i+1)). ${BOT_NAMES[$i]} (${BOT_MODES[$i]})"
  done
  echo "# ----------------------------------------"
} > "$MULTI_LOG"

# ---- launch -----------------------------------------------------------------
printf "\n${G}[multi] Launching ${#BOT_MODES[@]} bots into room ${ROOM}...${Z}\n\n"
printf "${C}[multi] Orchestration log: ${W}%s${Z}\n\n" "$MULTI_LOG"

for i in "${!BOT_MODES[@]}"; do
  NAME="${BOT_NAMES[$i]}"
  MODE="${BOT_MODES[$i]}"
  NB="${BOT_BACKTRACK[$i]}"
  BOT_LOG="logs/${TIMESTAMP}_${NAME}_${MODE}.log"

  # Per-bot log header
  {
    echo "# Bot log: $NAME"
    echo "# Started: $(date)"
    echo "# Room:    $ROOM"
    echo "# Mode:    $MODE"
    echo "# Backtrack: $NB"
    echo "# ----------------------------------------"
  } > "$BOT_LOG"
  ln -sf "$(basename "$BOT_LOG")" "logs/latest_${NAME}.log" 2>/dev/null || true

  CMD=(java
    "-Dbot.name=$NAME"
    "-Dbot.room=$ROOM"
    "-Dbot.mode=$MODE"
  )
  [ "$NB" -eq 1 ]           && CMD+=("-Dbot.antiBacktrack=true")
  [ "$NO_GUI" -eq 1 ]       && CMD+=("-Dbot.noGui=true")
  [ -n "$BOT_SERVER" ]      && CMD+=("-Dbot.server=$BOT_SERVER")
  CMD+=("-jar" "$JAR")

  printf "${C}[multi] Starting${Z} ${W}%s${Z} (mode=${W}%s${Z}%s) → ${C}%s${Z}\n" \
    "$NAME" "$MODE" \
    "$([ "$NB" -eq 1 ] && echo ", no-backtrack" || echo "")" \
    "$BOT_LOG"

  echo "[multi] Starting $NAME ($MODE)" >> "$MULTI_LOG"
  "${CMD[@]}" >> "$BOT_LOG" 2>&1 &
  PIDS+=($!)

  # Stagger registrations so the arena doesn't reject simultaneous connections.
  if [ "$((i+1))" -lt "${#BOT_MODES[@]}" ]; then
    sleep "$(echo "$STAGGER_MS" | awk '{printf "%.1f", $1/1000}')"
  fi
done

printf "\n${G}[multi] ${#PIDS[@]} bots running. Ctrl+C to stop all.${Z}\n"
printf "${Y}[multi] To follow a bot: tail -f logs/latest_<NAME>.log${Z}\n\n"
wait

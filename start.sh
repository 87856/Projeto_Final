#!/usr/bin/env bash
# Arena 3D RAG - Agent Launcher (Linux)
# Mirror of start.ps1 for Linux devs (CachyOS/Arch, etc.)
#
# NOTE: on a fuseblk/NTFS mount the executable bit may not stick, so run with:
#   bash start.sh
# Optional flags:
#   --no-build     skip "mvn clean package", run existing jar
#   --no-ollama    skip Ollama checks/start (forces heuristic-only mode)
#   --pull         force re-pull of all Ollama models
#   --models m1,m2 override the LLM models to ensure (default: the three below)
#   -h, --help     show this help
# Any other args are passed through to `java -jar`.

set -u

# ---- config -----------------------------------------------------------------
OLLAMA_URL="http://localhost:11434"
JAR="target/agente-explorador-1.0-SNAPSHOT.jar"
# fast tactical, planner, embeddings (see plan: llama3.2:1b is the new fast tier)
DEFAULT_MODELS=("qwen2.5:1.5b" "qwen2.5:7b" "nomic-embed-text")
# keep fast+planner resident together (6GB VRAM is enough for 1b+3b Q4)
export OLLAMA_MAX_LOADED_MODELS="${OLLAMA_MAX_LOADED_MODELS:-2}"

# ---- colors -----------------------------------------------------------------
if [ -t 1 ]; then
  G="\033[32m"; Y="\033[33m"; R="\033[31m"; C="\033[36m"; Z="\033[0m"
else
  G=""; Y=""; R=""; C=""; Z=""
fi
ok()   { printf "${G}[OK]${Z} %s\n"   "$*"; }
info() { printf "${C}[INFO]${Z} %s\n" "$*"; }
warn() { printf "${Y}[AVISO]${Z} %s\n" "$*"; }
err()  { printf "${R}[ERRO]${Z} %s\n" "$*"; }

# ---- args -------------------------------------------------------------------
DO_BUILD=1; DO_OLLAMA=1; FORCE_PULL=0
MODELS=("${DEFAULT_MODELS[@]}")
BOT_MODE=""
PASSTHROUGH=()
while [ $# -gt 0 ]; do
  case "$1" in
    --no-build)  DO_BUILD=0 ;;
    --no-ollama) DO_OLLAMA=0 ;;
    --pull)      FORCE_PULL=1 ;;
    --models)    shift; IFS=',' read -r -a MODELS <<< "${1:-}" ;;
    --mode)
      shift
      case "${1:-}" in
        list|--list)
          printf "\n%-14s %s\n" "MODE" "DESCRIPTION"
          printf "%-14s %s\n" "----" "-----------"
          printf "%-14s %s\n" "opportunist"  "Default balanced play — equal weight to all objectives"
          printf "%-14s %s\n" "berserker"    "HUNT always; attack regardless of HP margin; flee only at HP 20"
          printf "%-14s %s\n" "dominator"    "Attack any rival within distance 3; never checks HP margin"
          printf "%-14s %s\n" "bully"        "Targets lowest-HP rival in range; attacks if any HP advantage"
          printf "%-14s %s\n" "coward"       "HIDE always; flee threshold HP 150; never initiates combat"
          printf "%-14s %s\n" "ghost"        "Flee from ANY visible rival regardless of distance"
          printf "%-14s %s\n" "survivor"     "Resources always priority; extreme flee HP 120; no combat"
          printf "%-14s %s\n" "passive"      "Retaliates only when rival is adjacent (dist 1); never initiates"
          printf "%-14s %s\n" "farmer"       "FARM always — resources above everything; skips combat"
          printf "%-14s %s\n" "treasure"     "Pathfinds to chests first; skips combat; grabs resources near death"
          printf "%-14s %s\n" "hoarder"      "Chests first, resources second; only fights very weak rivals"
          printf "%-14s %s\n" "rich"         "Opens chests only; ignores combat and resources entirely"
          printf "%-14s %s\n" "assassin"     "Cherry-picks weaklings — attacks only when HP advantage > 50"
          printf "%-14s %s\n" "scavenger"    "Farms resources safely after battles; avoids all combat"
          printf "%-14s %s\n" "explorer"     "Maximise map coverage; skip combat; resources only near death"
          printf "%-14s %s\n" "no-llm"       "Pure heuristic — no Ollama calls at all; fastest possible loop"
          echo
          exit 0 ;;
        "")
          err "--mode requires a value (use --mode list to see options)"; exit 1 ;;
        *)
          BOT_MODE="$1" ;;
      esac ;;
    -h|--help)
      cat <<'HELP'

USAGE
  bash start.sh [OPTIONS]

OPTIONS
  --mode <name>     Set the bot's behaviour mode (default: opportunist).
                    Use "--mode list" to see all available modes.
  --no-build        Skip "mvn clean package"; use existing jar.
  --no-ollama       Skip Ollama checks/start (forces heuristic loop).
  --pull            Force re-pull of all Ollama models.
  --models m1,m2    Override which Ollama models to ensure are installed.
  -h, --help        Show this help screen.

Any other arguments are passed through to "java -jar".

MODES (quick reference)
  opportunist   Balanced default
  berserker     All-in aggression
  dominator     Attack anything within range 3
  bully         Hunt weakest rival
  coward        Never fight; HIDE always
  ghost         Flee from any visible rival
  survivor      Resources > everything; no combat
  passive       Retaliate only when cornered
  farmer        Farm resources; ignore combat
  treasure      Hunt chests; skip combat
  hoarder       Chests first; light combat
  rich          Open chests only
  assassin      Attack only with 50+ HP advantage
  scavenger     Farm safely after battles
  explorer      Cover the map; skip combat
  no-llm        Pure heuristic; no AI calls

Run "bash start.sh --mode list" for full descriptions.

HELP
      exit 0 ;;
    *) PASSTHROUGH+=("$1") ;;
  esac
  shift
done

cd "$(dirname "$0")" || { err "cannot cd to script dir"; exit 1; }

printf "${G}============================================${Z}\n"
printf "${G}  ARENA 3D RAG - Agent Launcher (Linux)${Z}\n"
printf "${G}============================================${Z}\n\n"

# ---- Java -------------------------------------------------------------------
if command -v java >/dev/null 2>&1; then
  ok "Java encontrado: $(java -version 2>&1 | head -n1)"
else
  err "Java nao encontrado. Instala Java 11+ (ex: sudo pacman -S jdk-openjdk)."
  exit 1
fi

# ---- Maven ------------------------------------------------------------------
MVN=""
if command -v mvn >/dev/null 2>&1; then
  MVN="mvn"
else
  for p in /usr/bin/mvn /opt/maven/bin/mvn "$HOME/.sdkman/candidates/maven/current/bin/mvn"; do
    [ -x "$p" ] && MVN="$p" && break
  done
fi
if [ -z "$MVN" ] && [ "$DO_BUILD" -eq 1 ]; then
  err "Maven nao encontrado. Instala (sudo pacman -S maven) ou usa --no-build."
  exit 1
fi
[ -n "$MVN" ] && ok "Maven encontrado: $MVN"

# ---- Ollama -----------------------------------------------------------------
ollama_up() { curl -fsS --max-time 3 "$OLLAMA_URL" >/dev/null 2>&1; }

if [ "$DO_OLLAMA" -eq 1 ]; then
  if ollama_up; then
    ok "Ollama esta a correr."
  else
    warn "Ollama nao responde. A tentar iniciar 'ollama serve'..."
    if command -v ollama >/dev/null 2>&1; then
      nohup ollama serve >/dev/null 2>&1 &
      for _ in 1 2 3 4 5 6 7 8 9 10; do
        sleep 1; ollama_up && break
      done
      if ollama_up; then ok "Ollama iniciado."
      else warn "Ollama nao iniciou. Agente correra em modo heuristico."; DO_OLLAMA=0; fi
    else
      warn "Binario 'ollama' nao encontrado. Modo heuristico."
      DO_OLLAMA=0
    fi
  fi
fi

# ---- models -----------------------------------------------------------------
if [ "$DO_OLLAMA" -eq 1 ] && command -v ollama >/dev/null 2>&1; then
  info "A verificar modelos Ollama..."
  INSTALLED="$(ollama list 2>/dev/null)"
  for m in "${MODELS[@]}"; do
    if [ "$FORCE_PULL" -eq 1 ] || ! grep -q "${m%%:*}" <<< "$INSTALLED"; then
      warn "Modelo '$m' em falta. A descarregar..."
      ollama pull "$m" || warn "Falha ao puxar $m (segue na mesma)."
    fi
  done
  ok "Modelos verificados (OLLAMA_MAX_LOADED_MODELS=$OLLAMA_MAX_LOADED_MODELS)."
fi

# ---- build ------------------------------------------------------------------
if [ "$DO_BUILD" -eq 1 ]; then
  info "A compilar com Maven..."
  if ! "$MVN" clean package -q; then
    err "Falha na compilacao."
    exit 1
  fi
  ok "Compilacao bem-sucedida."
fi

if [ ! -f "$JAR" ]; then
  err "Jar nao encontrado: $JAR (corre sem --no-build primeiro)."
  exit 1
fi

# ---- run --------------------------------------------------------------------
info "A iniciar o Agente Explorador..."
echo
java ${BOT_MODE:+-Dbot.mode="$BOT_MODE"} -jar "$JAR" "${PASSTHROUGH[@]}"

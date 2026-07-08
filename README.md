# Projeto Final — Arena SaaS 2026 Agent

Java/Maven bot that competes on the HTTP arena at `arena.pmonteiro.ovh`.
Two-tier LLM brain layered on top of a pure-heuristic fallback pipeline.

---

## AI Models

### `qwen2.5:7b` — Chest RAG + Strategy Planner
**Why Qwen2.5:** significantly better instruction-following than Llama 3.2 at strict
"respond ONLY with X" prompts. Critical for chest key extraction where hallucinating
extra words breaks the unlock.

| Sub-role | When it fires | What it does |
|----------|--------------|--------------|
| **Chest key extractor** | Bot steps on a chest with an unsolved enigma | Receives the most relevant manual paragraph (retrieved by `nomic-embed-text`) + the enigma text; outputs the exact unlock keyword. Temperature 0.0, max 20 tokens. |
| **Strategy planner** | Every ~3.2 s (8 ticks) when rivals are visible | Classifies each rival as `AGGRESSIVE / DEFENSIVE / PASSIVE`, scores their threat (0-10), and sets a global goal (`HUNT / FARM / HIDE / OPPORTUNIST`) that biases the heuristic thresholds for the rest of the match. |

---

### `qwen2.5:1.5b` — Fast Tactical Override (Tier 1)
**Why 1.5b:** needs to respond in well under 400 ms (one tick) so the main loop
never blocks. Qwen2.5 at 1.5b follows JSON output constraints reliably enough for
a 4-action enum; Llama 3.2:1b was less consistent.

**When it fires:** trigger = rival inside the 5x5 window around the bot **OR** HP below the
mode's flee threshold.

**What it does:** receives a compact ASCII map of the local area:

```
. . R . .
. . . . .
. . @ . .
. $ . . .
. . . . .
```

(`@` = self, `R` = rival, `$` = resource, `C` = chest, `#` = wall)

Outputs `{"action":"MOVER_NORTE|SUL|ESTE|OESTE","reason":"short"}`. The action is
validated against the legal enum and against known walls before use; invalid output
silently falls back to the heuristic pipeline.

Runs on a **daemon thread** — the main loop reads the most recent suggestion (if
fresh, <= 2 ticks old) and never blocks waiting for it.

---

### `nomic-embed-text` — Semantic Embeddings for RAG
**Why:** converts text to vectors (768-dim) that encode meaning, enabling similarity
search rather than keyword matching. A chest enigma like *"falha no sistema de
arrefecimento"* will match a manual paragraph about cooling faults even if no words
overlap.

**When it fires:** **once at startup**, after the arena serves the room manual.
Every paragraph is vectorized and stored in RAM (`List<Vetores>`). Cosine similarity
(`Vetores.calcularSimilaridade`) then finds the best-matching paragraph for each
chest enigma in O(n) time -- no further model calls needed.

After startup Ollama evicts it from VRAM (`OLLAMA_MAX_LOADED_MODELS=2`) so the two
Qwen models can stay resident together (~5.8 GB total on the RTX 2060 Mobile).

---

## VRAM Budget (RTX 2060 Mobile -- 6 GB)

| Model | Size (Q4) | Resident |
|-------|-----------|---------|
| `qwen2.5:1.5b` | ~1.0 GB | Always (fast tactical) |
| `qwen2.5:7b` | ~4.7 GB | Always (planner + chest) |
| `nomic-embed-text` | ~0.3 GB | Startup only, then evicted |

---

## Running

```bash
# Linux
bash start.sh --mode opportunist

# Windows
.\start.ps1

# Anti-backtrack: penalises recent trail so bot keeps exploring new space
bash start.sh --mode explorer --no-backtrack
.\start.ps1 --mode explorer --no-backtrack

# See all modes
bash start.sh --mode list

# Help
bash start.sh --help
```

## Flags

| Flag | Effect |
|------|--------|
| `--mode <name>` | Select a bot behaviour profile (default: `opportunist`) |
| `--no-backtrack` | Adds +25 heat penalty to the last 8 visited cells during exploration. Forces the bot to prefer unexplored space over retracing its own path. Combinable with any mode. Penalty is additive so the bot can still backtrack when every other direction is blocked. |
| `--no-build` | Skip `mvn clean package`; reuse existing jar |
| `--no-ollama` | Skip Ollama checks; forces pure-heuristic loop |
| `--pull` | Force re-pull all Ollama models |

## Bot Modes

| Mode | Behaviour |
|------|-----------|
| `opportunist` | Default balanced play |
| `berserker` | Attack regardless of HP margin; flee only at HP 20 |
| `dominator` | Attack any rival within distance 3 |
| `bully` | Target lowest-HP rival; attack if any advantage |
| `coward` | HIDE always; flee at HP 150; never fights |
| `ghost` | Flee from ANY visible rival |
| `survivor` | Resources priority; flee at HP 120; no combat |
| `passive` | Retaliate only when cornered (dist 1) |
| `farmer` | Resources above everything; ignores combat |
| `treasure` | Pathfinds to chests; skips combat |
| `hoarder` | Chests first; only fights very weak rivals |
| `rich` | Opens chests only; ignores everything else |
| `assassin` | Attacks only with 50+ HP advantage |
| `scavenger` | Farms resources safely; avoids combat |
| `explorer` | Maximise map coverage; skip combat |
| `no-llm` | Pure heuristic; no Ollama calls |

## Developers

- Simon (Linux -- CachyOS)
- JJ (Windows)

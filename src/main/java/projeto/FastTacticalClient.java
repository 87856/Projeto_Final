package projeto;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tier 1 — fast tactical override.
 *
 * <p>Runs a daemon thread that watches the latest {@link TickSnapshot}
 * published by {@link Agent}. When the danger trigger activates
 * (rival inside the 5×5 window OR HP below {@link Agent#HP_FUGA}),
 * it builds a compact ASCII map of the local area and asks a small
 * local model to pick an immediate action.
 *
 * <p>The suggestion is published into a shared {@link AtomicReference};
 * the main loop reads the most-recent value but only uses it if it is
 * fresh (within {@link #SUGGESTION_TTL_TICKS} ticks). The fast thread
 * never blocks the main loop.
 *
 * <p>JSON output is validated strictly: an invalid enum, missing field,
 * or move-pointing-into-a-known-wall discards the suggestion and lets
 * the heuristic pipeline take over.
 */
public class FastTacticalClient implements Runnable {

    /** How long a published suggestion stays "fresh" in main-loop read. */
    private static final int SUGGESTION_TTL_TICKS = 2;

    /** Window radius (cells) around the agent used for the ASCII map. */
    private static final int WINDOW_HALF = 2;

    /** Idle cadence when nothing is happening. */
    private static final long IDLE_SLEEP_MS = 100;

    /** Minimum gap between two model calls so we don't hammer Ollama. */
    private static final long MIN_CALL_GAP_MS = 120;

    public static final class TacticalSuggestion {
        public final String action;   // MOVER_NORTE | MOVER_SUL | MOVER_ESTE | MOVER_OESTE
        public final String reason;
        public final long   tick;
        public final int    selfX;
        public final int    selfY;

        public TacticalSuggestion(String action, String reason,
                                  long tick, int selfX, int selfY) {
            this.action = action;
            this.reason = reason;
            this.tick = tick;
            this.selfX = selfX;
            this.selfY = selfY;
        }

        public boolean isFresh(long currentTick) {
            return currentTick - tick <= SUGGESTION_TTL_TICKS;
        }
    }

    private static final Set<String> LEGAL_ACTIONS = new HashSet<>(Arrays.asList(
            "MOVER_NORTE", "MOVER_SUL", "MOVER_ESTE", "MOVER_OESTE"));

    private final Ollama_Client ollama;
    private final AtomicReference<TickSnapshot> snapshotRef;
    private final AtomicReference<TacticalSuggestion> suggestionRef =
            new AtomicReference<>(null);

    private volatile boolean running = false;
    private Thread thread;
    private long lastCallMs = 0;

    public FastTacticalClient(Ollama_Client ollama,
                              AtomicReference<TickSnapshot> snapshotRef) {
        this.ollama = ollama;
        this.snapshotRef = snapshotRef;
    }

    public void start() {
        running = true;
        thread = new Thread(this, "FastTacticalClient");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    public AtomicReference<TacticalSuggestion> getSuggestionRef() {
        return suggestionRef;
    }

    /** Most recent suggestion — may be null or stale. Loop checks freshness itself. */
    public TacticalSuggestion getSuggestion() { return suggestionRef.get(); }

    @Override
    public void run() {
        while (running) {
            try {
                TickSnapshot snap = snapshotRef.get();
                if (snap == null || snap.perception == null) {
                    Thread.sleep(IDLE_SLEEP_MS);
                    continue;
                }

                // Trigger: rival inside 5x5 OR HP < HP_FUGA.
                boolean rivalNear = snap.hasRivalWithin(5);
                boolean hpLow = snap.hp < Agent.HP_FUGA;
                if (!rivalNear && !hpLow) {
                    Thread.sleep(IDLE_SLEEP_MS);
                    continue;
                }

                long now = System.currentTimeMillis();
                if (now - lastCallMs < MIN_CALL_GAP_MS) {
                    Thread.sleep(IDLE_SLEEP_MS);
                    continue;
                }

                char[][] map = buildAsciiMap(snap.perception, snap.selfX, snap.selfY);
                String prompt = buildPrompt(map, snap);
                lastCallMs = System.currentTimeMillis();
                String raw = ollama.generateJson(
                        Ollama_Client.MODELO_FAST, prompt, 0.1, 30, /*asJson*/ true);

                TacticalSuggestion parsed =
                        parseAndValidate(raw, snap.tick, snap.selfX, snap.selfY, map);
                if (parsed != null) {
                    suggestionRef.set(parsed);
                    System.out.printf(
                            "[FastTactical] t=%d hp=%d → %s (%s)%n",
                            snap.tick, snap.hp, parsed.action, parsed.reason);
                }

            } catch (InterruptedException e) {
                if (!running) break;
            } catch (Exception e) {
                System.err.println("[FastTactical] erro loop: " + e.getMessage());
            }
        }
    }

    // ----- helpers --------------------------------------------------------

    /** Build a (2*WINDOW_HALF+1) × (2*WINDOW_HALF+1) ASCII map centered on the agent. */
    private static char[][] buildAsciiMap(JsonObject p, int cx, int cy) {
        int size = 2 * WINDOW_HALF + 1;
        char[][] g = new char[size][size];
        for (int i = 0; i < size; i++) {
            Arrays.fill(g[i], '.');
        }
        g[WINDOW_HALF][WINDOW_HALF] = '@';

        // overlay in priority order: walls, resources, chests, rivals (last wins on overlap)
        overlay(g, p, "objetos_fixos",    cx, cy, '#');
        overlay(g, p, "recursos_no_mundo", cx, cy, '$');
        overlay(g, p, "cofres_no_mundo",  cx, cy, 'C');
        overlay(g, p, "outros_robots",    cx, cy, 'R');
        return g;
    }

    private static void overlay(char[][] g, JsonObject p, String field,
                                int cx, int cy, char symbol) {
        if (!p.has(field)) return;
        JsonElement e = p.get(field);
        if (!e.isJsonArray()) return;
        JsonArray arr = e.getAsJsonArray();
        int size = g.length;
        for (JsonElement je : arr) {
            try {
                JsonObject obj = je.getAsJsonObject();
                int wx = obj.get("x").getAsInt();
                int wy = obj.get("y").getAsInt();
                int lx = (wx - cx) + WINDOW_HALF;
                int ly = (wy - cy) + WINDOW_HALF;
                if (lx < 0 || lx >= size || ly < 0 || ly >= size) continue;
                if (g[ly][lx] == '.') {
                    g[ly][lx] = symbol;
                }
            } catch (Exception ignored) {}
        }
    }

    private static String buildPrompt(char[][] map, TickSnapshot snap) {
        StringBuilder ascii = new StringBuilder();
        for (char[] row : map) {
            ascii.append(new String(row)).append('\n');
        }
        return "<|im_start|>system\n" +
                "És um controlador tático de um robô numa arena 5×5 centrada em @.\n" +
                "Símbolos: . vazio | # parede | $ recurso | C cofre | R rival | @ self.\n" +
                "Devolve APENAS JSON estrito com a forma exata:\n" +
                "{\"action\":\"MOVER_NORTE|MOVER_SUL|MOVER_ESTE|MOVER_OESTE\",\"reason\":\"curta\"}.\n" +
                "Não escrevas nada fora do JSON. Não advances para uma parede (#).\n" +
                "<|im_end|>\n" +
                "<|im_start|>user\n" +
                "HP: " + snap.hp + " | Estratégia: " + snap.goal + " | Tick: " + snap.tick + "\n" +
                "MAPA (N no topo, S em baixo, E direita, O esquerda):\n" +
                ascii +
                "Escolhe UMA ação para sobreviver. Responde só com o JSON pedido.\n" +
                "<|im_end|>\n" +
                "<|im_start|>assistant\n";
    }

    private static TacticalSuggestion parseAndValidate(String raw, long tick,
                                                       int selfX, int selfY,
                                                       char[][] map) {
        if (raw == null || raw.isBlank()) return null;

        // Path 1 — strict JSON (preferred, honours Ollama format:{}).
        int start = raw.indexOf('{');
        int end   = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            String candidate = raw.substring(start, end + 1);
            try {
                JsonObject obj = JsonParser.parseString(candidate).getAsJsonObject();
                if (obj.has("action") && !obj.get("action").isJsonNull()) {
                    String action = obj.get("action").getAsString();
                    if (LEGAL_ACTIONS.contains(action)) {
                        if (isDirectionLegal(map, action)) {
                            String reason = obj.has("reason") && !obj.get("reason").isJsonNull()
                                    ? obj.get("reason").getAsString() : "";
                            if (reason.length() > 80) reason = reason.substring(0, 80);
                            return new TacticalSuggestion(action, reason, tick, selfX, selfY);
                        }
                        System.err.println(
                                "[FastTactical] ação " + action + " aponta para parede; descartada.");
                        return null;
                    }
                    System.err.println("[FastTactical] ação inválida: " + action);
                    return null;
                }
            } catch (Exception ex) {
                // fall through to regex backup
            }
        }

        // Path 2 — regex extraction fallback for older Ollama versions where
        // format:{} is unsupported and the model returns plain text.
        String regexAction = regexSearchAction(raw);
        if (regexAction != null && isDirectionLegal(map, regexAction)) {
            System.out.println("[FastTactical] regex fallback extracted: " + regexAction);
            return new TacticalSuggestion(regexAction, "regex-fallback",
                    tick, selfX, selfY);
        }

        System.err.println("[FastTactical] sem ação utilizável: " + truncate(raw));
        return null;
    }

    private static String regexSearchAction(String raw) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\bMOVER_(NORTE|SUL|ESTE|OESTE)\\b")
                .matcher(raw);
        return m.find() ? "MOVER_" + m.group(1) : null;
    }

    private static boolean isDirectionLegal(char[][] map, String action) {
        int nx = WINDOW_HALF, ny = WINDOW_HALF;
        switch (action) {
            case "MOVER_NORTE": ny -= 1; break;
            case "MOVER_SUL":   ny += 1; break;
            case "MOVER_ESTE":  nx += 1; break;
            case "MOVER_OESTE": nx -= 1; break;
            default: return false;
        }
        int size = map.length;
        if (nx < 0 || nx >= size || ny < 0 || ny >= size) return false;
        char c = map[ny][nx];
        // Walkable: empty, resource, chest, rival. Not: wall.
        return c != '#';
    }

    private static String truncate(String s) {
        if (s == null) return "<null>";
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }
}

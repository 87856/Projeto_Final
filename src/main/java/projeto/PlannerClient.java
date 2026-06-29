package projeto;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tier 2 — strategy planner.
 *
 * <p>Daemon thread that wakes every {@link #PLANNER_INTERVAL_TICKS} ticks
 * (~3.2 s at 400 ms cadence). Reads the latest {@link TickSnapshot},
 * builds a summary prompt with self state + rival profiles, and asks a
 * small local model to emit a strategy JSON.
 *
 * <p>The output is validated strictly per the plan's contract:
 * <pre>
 * {
 *   "goal":   "HUNT|FARM|HIDE|OPPORTUNIST",
 *   "rivals": [ { "id": "...", "class": "...", "threat": 0 } ],
 *   "target": { "x": 0, "y": 0 }    // optional
 * }
 * </pre>
 *
 * On any parse/enum/threat error the planner discards the output and
 * leaves the current {@link StrategyState} untouched. On valid partial
 * output it applies what it can.
 */
public class PlannerClient implements Runnable {

    /** How often the planner fires (≈3.2 s at 400 ms cadence). */
    public static final int PLANNER_INTERVAL_TICKS = 8;

    public static final int THREAT_MIN = 0;
    public static final int THREAT_MAX = 10;

    private final Ollama_Client ollama;
    private final RivalTracker tracker;
    private final StrategyState strategyState;
    private final AtomicReference<TickSnapshot> snapshotRef;

    private volatile boolean running = false;
    private Thread thread;
    private long lastSeenTick = -1;

    public PlannerClient(Ollama_Client ollama,
                         RivalTracker tracker,
                         StrategyState strategyState,
                         AtomicReference<TickSnapshot> snapshotRef) {
        this.ollama = ollama;
        this.tracker = tracker;
        this.strategyState = strategyState;
        this.snapshotRef = snapshotRef;
    }

    public void start() {
        running = true;
        thread = new Thread(this, "PlannerClient");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    @Override
    public void run() {
        while (running) {
            try {
                TickSnapshot snap = snapshotRef.get();
                if (snap == null || snap.perception == null || snap.tick == lastSeenTick) {
                    Thread.sleep(150);
                    continue;
                }

                // Fire only every N ticks (>=1 rival profile OR planner has run once).
                if (snap.rivals.isEmpty() && !strategyState.get().valid) {
                    Thread.sleep(200);
                    continue;
                }
                if ((snap.tick - lastSeenTick) < PLANNER_INTERVAL_TICKS) {
                    Thread.sleep(200);
                    continue;
                }
                lastSeenTick = snap.tick;

                String prompt = buildPrompt(snap);
                String raw = ollama.generateJson(
                        Ollama_Client.MODELO_PLANNER, prompt, 0.1, 80, /*asJson*/ true);

                if (raw == null || raw.isBlank()) {
                    Thread.sleep(150);
                    continue;
                }

                Parsed p = parseAndValidate(raw);
                if (p != null) {
                    applyToState(snap.tick, p);
                }

                Thread.sleep(150);

            } catch (InterruptedException e) {
                if (!running) break;
            } catch (Exception e) {
                System.err.println("[PlannerClient] erro loop: " + e.getMessage());
            }
        }
    }

    // ----- prompt builders ------------------------------------------------

    private static String buildPrompt(TickSnapshot snap) {
        StringBuilder rivals = new StringBuilder("[");
        boolean first = true;
        for (RivalProfile p : snap.rivals) {
            if (!first) rivals.append(", ");
            first = false;
            rivals.append(String.format(
                    "{\"id\":\"%s\",\"x\":%d,\"y\":%d,\"hp\":%d,\"class_hint\":\"%s\"}",
                    escape(p.getId()),
                    p.getLastX(), p.getLastY(), p.getLastHP(),
                    p.getClazz()));
        }
        rivals.append("]");

        return "<|im_start|>system\n" +
                "És um planner tático. Devolve APENAS JSON estrito no formato:\n" +
                "{\"goal\":\"HUNT|FARM|HIDE|OPPORTUNIST\"," +
                "\"rivals\":[{\"id\":\"string\",\"class\":\"AGGRESSIVE|DEFENSIVE|PASSIVE\",\"threat\":0}]," +
                "\"target\":{\"x\":0,\"y\":0}}\n" +
                "Sem comentários, sem texto fora do JSON.\n" +
                "HUNT = procurar combate. FARM = recursos. HIDE = fugir cedo. OPPORTUNIST = reativo.\n" +
                "<|im_end|>\n" +
                "<|im_start|>user\n" +
                "Self: HP=" + snap.hp + " pos=(" + snap.selfX + "," + snap.selfY + ")\n" +
                "Rivais: " + rivals + "\n" +
                "Tick: " + snap.tick + "\n" +
                "<|im_end|>\n" +
                "<|im_start|>assistant\n";
    }

    private static String escape(String s) {
        if (s == null) return "";
        // Strip JSON-hostile chars from id only — model can't quote them
        // back anyway, and our ids already come from a closed enum set +
        // coordinates so this is safe.
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('_');
            else sb.append(c);
        }
        return sb.toString();
    }

    // ----- parsing --------------------------------------------------------

    /** The substring of the planner output we successfully extracted. */
    private static final class Parsed {
        StrategyState.Goal goal;
        Map<String, RivalProfile.Classification> classes;
        Map<String, Integer> threats;
        int targetX = Integer.MIN_VALUE;
        int targetY = Integer.MIN_VALUE;
    }

    private static Parsed parseAndValidate(String raw) {
        if (raw == null || raw.isBlank()) return null;

        // Path 1 — strict JSON (preferred, honours Ollama format:{}).
        int start = raw.indexOf('{');
        int end   = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            String candidate = raw.substring(start, end + 1);
            Parsed out = new Parsed();
            try {
                JsonObject obj = JsonParser.parseString(candidate).getAsJsonObject();

                // goal: optional (keep prior on invalid)
                if (obj.has("goal") && !obj.get("goal").isJsonNull()) {
                    out.goal = StrategyState.Goal.parse(obj.get("goal").getAsString());
                }
                // target: optional
                if (obj.has("target") && obj.get("target").isJsonObject()) {
                    try {
                        JsonObject t = obj.getAsJsonObject("target");
                        if (t.has("x") && t.has("y")) {
                            out.targetX = t.get("x").getAsInt();
                            out.targetY = t.get("y").getAsInt();
                        }
                    } catch (Exception ignored) {}
                }
                // rivals: optional (apply partial lists too)
                if (obj.has("rivals") && obj.get("rivals").isJsonArray()) {
                    JsonArray arr = obj.getAsJsonArray("rivals");
                    out.classes = new HashMap<>();
                    out.threats = new HashMap<>();
                    for (JsonElement e : arr) {
                        if (!e.isJsonObject()) continue;
                        JsonObject robj = e.getAsJsonObject();
                        String id;
                        if (!robj.has("id") || robj.get("id").isJsonNull()) continue;
                        try {
                            id = robj.get("id").getAsString();
                        } catch (Exception ex) {
                            continue;
                        }
                        if (id.isBlank()) continue;

                        RivalProfile.Classification c = null;
                        if (robj.has("class") && !robj.get("class").isJsonNull()) {
                            c = RivalProfile.Classification.parse(
                                    robj.get("class").getAsString());
                        }
                        int threat = -1;
                        if (robj.has("threat") && !robj.get("threat").isJsonNull()) {
                            try {
                                threat = robj.get("threat").getAsInt();
                            } catch (Exception ignored) {}
                        }
                        if (c != null) out.classes.put(id, c);
                        if (threat >= THREAT_MIN && threat <= THREAT_MAX) {
                            out.threats.put(id, threat);
                        }
                    }
                    if (out.classes.isEmpty()) {
                        out.classes = null;
                        out.threats = null;
                    }
                }

                if (out.goal != null || out.classes != null || out.targetX != Integer.MIN_VALUE) {
                    return out;
                }
                // JSON parsed but nothing usable — fall through to regex backup.
            } catch (Exception ex) {
                // fall through to regex backup
            }
        }

        // Path 2 — regex extraction fallback for older Ollama versions where
        // format:{} is unsupported and the model returns plain text. Only the
        // goal keyword is reliably extractable; rivals cannot be recovered.
        Parsed fallback = new Parsed();
        String goalKeyword = regexSearchGoal(raw);
        if (goalKeyword != null) {
            fallback.goal = StrategyState.Goal.parse(goalKeyword);
            if (fallback.goal != null) {
                System.out.println("[Planner] regex fallback extracted goal: " + fallback.goal);
                return fallback;
            }
        }

        System.err.println("[Planner] sem resposta utilizável: " + truncate(raw));
        return null;
    }

    private static String regexSearchGoal(String raw) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\b(HUNT|FARM|HIDE|OPPORTUNIST)\\b")
                .matcher(raw);
        return m.find() ? m.group(1) : null;
    }

    // ----- application ----------------------------------------------------

    private void applyToState(long tick, Parsed p) {
        // Apply rival classifications first so GUI sees them immediately.
        if (p.classes != null) {
            tracker.applyPlannerResult(p.classes, p.threats);
        }

        // Build the new Strategy record: keep prior values when model
        // omitted a field, but always bump the stamp and mark valid.
        StrategyState.Strategy prev = strategyState.get();
        StrategyState.Goal newGoal = p.goal != null ? p.goal : prev.goal;
        int newTargetX = p.targetX != Integer.MIN_VALUE ? p.targetX : prev.targetX;
        int newTargetY = p.targetY != Integer.MIN_VALUE ? p.targetY : prev.targetY;

        strategyState.set(new StrategyState.Strategy(
                newGoal,
                p.classes != null ? p.classes : prev.rivals,
                p.threats != null ? p.threats : prev.threats,
                newTargetX, newTargetY,
                tick, true
        ));

        System.out.printf("[Planner] t=%d goal=%s rivals=%d target=(%d,%d)%n",
                tick, newGoal,
                p.classes != null ? p.classes.size() : 0,
                newTargetX == Integer.MIN_VALUE ? -999 : newTargetX,
                newTargetY == Integer.MIN_VALUE ? -999 : newTargetY);
    }

    private static String truncate(String s) {
        if (s == null) return "<null>";
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }
}

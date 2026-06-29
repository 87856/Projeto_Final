package projeto;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Tracks every rival the agent has seen this match.
 *
 * <h3>Identity resolution</h3>
 * Arena schema is unconfirmed (see plan Assumption 1). The class therefore
 * follows a "best-effort identity" strategy:
 *
 * <ol>
 *   <li>If the rival payload carries one of {@code id / nome / robot_id / name},
 *       that value is the profile key — trivially stable across ticks.</li>
 *   <li>Otherwise the rival is matched positionally against existing
 *       profiles within Manhattan radius {@value #POS_MATCH_RADIUS}.</li>
 *   <li>If no profile matches, a fresh profile is keyed by a synthetic
 *       {@code rival@x,y#tick_i} tag. These keys last at most until pruning.</li>
 * </ol>
 *
 * The first non-empty perception is logged once so the actual schema can be
 * verified against Assumption 1.
 */
public class RivalTracker {

    /** Manhattan radius for positional re-identification of rivals. */
    private static final int POS_MATCH_RADIUS = 2;

    /** Drop profiles not seen within this many ticks (≈12 s at 400 ms cadence). */
    private static final int STALE_TICK_THRESHOLD = 30;

    private final Map<String, RivalProfile> profiles = new HashMap<>();
    private int currentTick = 0;
    private boolean schemaLogged = false;

    /**
     * Sequential counter used to mint short, AI-friendly synthetic IDs for rivals
     * that arrive without a stable arena key. Counts up monotonically across
     * the entire match so each new positional profile gets a unique tag like
     * "rival-7" — far easier for a 1b/3b model to echo verbatim than the
     * previous verbose {@code "rival@x,y#t..._i"} keys.
     */
    private int nextPositionalSeq = 0;

    public int getCurrentTick() { return currentTick; }

    /**
     * Feed one tick of perception into the tracker.
     *
     * @param percepcao the latest {@code JsonObject} from the arena
     * @param selfX     current agent x (used for nearest-within queries)
     * @param selfY     current agent y
     */
    public void updateFromPerception(JsonObject percepcao, int selfX, int selfY) {
        currentTick++;

        if (percepcao == null || !percepcao.has("outros_robots")) {
            pruneStale();
            return;
        }
        try {
            JsonElement elem = percepcao.get("outros_robots");
            if (!elem.isJsonArray()) {
                pruneStale();
                return;
            }
            JsonArray rivals = elem.getAsJsonArray();

            // Assumption 1 verification: dump first non-empty payload once.
            if (!schemaLogged && rivals.size() > 0) {
                System.out.println("[RivalTracker] outros_robots schema sample: " + rivals.get(0));
                schemaLogged = true;
            }

            // Two-pass update:
            //  1) stable-id rivals (instant key match)
            //  2) positional rivals (greedy nearest-match against unmatched)
            Map<String, RivalProfile> unmatched = new HashMap<>(profiles);
            List<int[]> positionalObs    = new ArrayList<>();
            List<Integer> positionalHPs   = new ArrayList<>();
            List<String> positionalStableIds = new ArrayList<>(); // for logging

            for (JsonElement e : rivals) {
                JsonObject r = e.getAsJsonObject();
                int rx = readInt(r, "x", 0);
                int ry = readInt(r, "y", 0);
                int rhp = readInt(r, "hp", 100);

                String stableId = extractStableId(r);
                if (stableId != null) {
                    RivalProfile p = profiles.computeIfAbsent(stableId,
                            k -> new RivalProfile(k));
                    p.update(rx, ry, rhp, currentTick);
                    unmatched.remove(stableId);
                } else {
                    positionalObs.add(new int[]{rx, ry});
                    positionalHPs.add(rhp);
                    positionalStableIds.add(null);
                }
            }

            // Second pass: positional matching for non-id rivals.
            for (int i = 0; i < positionalObs.size(); i++) {
                int[] pos = positionalObs.get(i);
                RivalProfile matched = findClosest(pos[0], pos[1], unmatched.values());
                if (matched != null) {
                    matched.update(pos[0], pos[1], positionalHPs.get(i), currentTick);
                    unmatched.remove(matched.getId());
                } else {
                    String key = "rival-" + (nextPositionalSeq++);
                    RivalProfile p = new RivalProfile(key);
                    p.update(pos[0], pos[1], positionalHPs.get(i), currentTick);
                    profiles.put(key, p);
                }
            }

            pruneStale();
        } catch (Exception ex) {
            System.err.println("[RivalTracker] Erro no update: " + ex.getMessage());
        }
    }

    /**
     * Returns the closest rival profile to {@code (selfX, selfY)} within
     * Manhattan {@code window}, or {@code null} if none.
     */
    public RivalProfile nearestRivalWithin(int window, int selfX, int selfY) {
        RivalProfile best = null;
        int bestDist = Integer.MAX_VALUE;
        for (RivalProfile p : profiles.values()) {
            if (p.getLastX() == Integer.MIN_VALUE) continue;
            int dist = Math.abs(p.getLastX() - selfX) + Math.abs(p.getLastY() - selfY);
            if (dist <= window && dist < bestDist) {
                bestDist = dist;
                best = p;
            }
        }
        return best;
    }

    /** Lightweight snapshot used to feed the planner prompt. */
    public List<RivalProfile> snapshotForPlanner() {
        List<RivalProfile> copy = new ArrayList<>(profiles.size());
        for (RivalProfile p : profiles.values()) copy.add(p);
        return copy;
    }

    /** All current profiles (for the GUI overlay). */
    public Collection<RivalProfile> all() {
        return Collections.unmodifiableCollection(profiles.values());
    }

    /** Applies classifications/threats produced by the planner. */
    public void applyPlannerResult(Map<String, RivalProfile.Classification> classes,
                                   Map<String, Integer> threats) {
        if (classes == null) return;
        for (Map.Entry<String, RivalProfile.Classification> e : classes.entrySet()) {
            RivalProfile p = profiles.get(e.getKey());
            if (p == null) continue;
            int t = (threats != null && threats.containsKey(e.getKey()))
                    ? threats.get(e.getKey())
                    : p.getThreat();
            p.overrideClassification(e.getValue(), t);
        }
    }

    // ------- internals ----------------------------------------------------

    private void pruneStale() {
        Iterator<Map.Entry<String, RivalProfile>> it = profiles.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, RivalProfile> e = it.next();
            if (currentTick - e.getValue().getLastSeenTick() > STALE_TICK_THRESHOLD) {
                it.remove();
            }
        }
    }

    private static RivalProfile findClosest(int x, int y,
                                            Collection<RivalProfile> candidates) {
        RivalProfile best = null;
        int bestDist = Integer.MAX_VALUE;
        for (RivalProfile p : candidates) {
            if (p.getLastX() == Integer.MIN_VALUE) continue;
            int dist = Math.abs(p.getLastX() - x) + Math.abs(p.getLastY() - y);
            if (dist <= POS_MATCH_RADIUS && dist < bestDist) {
                bestDist = dist;
                best = p;
            }
        }
        return best;
    }

    private static String extractStableId(JsonObject r) {
        for (String field : new String[]{"id", "nome", "robot_id", "name"}) {
            if (r.has(field) && !r.get(field).isJsonNull()) {
                JsonElement v = r.get(field);
                if (v.isJsonPrimitive()) {
                    String s = v.getAsString();
                    if (s != null && !s.isBlank()) return s;
                }
            }
        }
        return null;
    }

    private static int readInt(JsonObject r, String field, int fallback) {
        try {
            return r.has(field) ? r.get(field).getAsInt() : fallback;
        } catch (Exception ex) {
            return fallback;
        }
    }
}

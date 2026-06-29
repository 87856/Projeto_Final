package projeto;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable per-tick snapshot that the main loop hands off to the daemon
 * threads. We put every piece of state the threads need into one record and
 * publish through an AtomicReference so they never call back into Agent and
 * never traverse a concurrently-mutated {@link RivalTracker}.
 *
 * <p>Producer: {@link Agent} once per tick.
 * <p>Consumers: {@link FastTacticalClient}, {@link PlannerClient},
 *               {@link HeatMap}.
 */
public final class TickSnapshot {

    public final long tick;
    public final int selfX;
    public final int selfY;
    public final int hp;
    public final JsonObject perception;       // raw perception (may be null)
    public final StrategyState.Goal goal;
    public final List<RivalProfile> rivals;  // immutable copy of tracker view

    public TickSnapshot(long tick,
                        int selfX, int selfY, int hp,
                        JsonObject perception,
                        StrategyState.Goal goal,
                        List<RivalProfile> rivals) {
        this.tick = tick;
        this.selfX = selfX;
        this.selfY = selfY;
        this.hp = hp;
        this.perception = perception;
        this.goal = goal != null ? goal : StrategyState.Goal.OPPORTUNIST;
        this.rivals = rivals != null
                ? Collections.unmodifiableList(new ArrayList<>(rivals))
                : Collections.emptyList();
    }

    public boolean hasRivalWithin(int window) {
        for (RivalProfile p : rivals) {
            int dx = Math.abs(p.getLastX() - selfX);
            int dy = Math.abs(p.getLastY() - selfY);
            if (dx + dy <= window) return true;
        }
        return false;
    }

    /** Map of rival id → classification, used by the planner and the GUI. */
    public java.util.Map<String, RivalProfile.Classification> rivalClassMap() {
        java.util.Map<String, RivalProfile.Classification> m =
                new java.util.HashMap<>(rivals.size());
        for (RivalProfile p : rivals) m.put(p.getId(), p.getClazz());
        return m;
    }
}

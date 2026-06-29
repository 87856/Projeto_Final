package projeto;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe shared strategy shard. The Planner writes here; the main loop
 * reads it each tick and threads its values into the heuristic decisions.
 *
 * Because every mutation goes through {@link AtomicReference} and the value
 * is an immutable {@link Strategy} record, no locks are needed in
 * {@code decidirAcao}.
 */
public class StrategyState {

    public enum Goal {
        HUNT,        // seek combat — engage at smaller HP margin
        FARM,        // prioritise resources
        HIDE,        // flee earlier / prefer reversals
        OPPORTUNIST; // default behaviour unchanged

        public static Goal parse(String s) {
            if (s == null) return null;
            try {
                return Goal.valueOf(s.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
    }

    /** Immutable snapshot. Replace the whole record on every planner write. */
    public static final class Strategy {
        public final Goal goal;
        public final Map<String, RivalProfile.Classification> rivals;
        public final Map<String, Integer> threats;
        public final int targetX;
        public final int targetY;
        public final long stampTick;
        public final boolean valid;

        public Strategy(Goal goal,
                        Map<String, RivalProfile.Classification> rivals,
                        Map<String, Integer> threats,
                        int targetX, int targetY,
                        long stampTick, boolean valid) {
            this.goal = goal;
            this.rivals = rivals != null
                    ? Collections.unmodifiableMap(new HashMap<>(rivals))
                    : Collections.emptyMap();
            this.threats = threats != null
                    ? Collections.unmodifiableMap(new HashMap<>(threats))
                    : Collections.emptyMap();
            this.targetX = targetX;
            this.targetY = targetY;
            this.stampTick = stampTick;
            this.valid = valid;
        }

        public static Strategy defaultStrategy() {
            return new Strategy(Goal.OPPORTUNIST,
                    new HashMap<>(), new HashMap<>(),
                    Integer.MIN_VALUE, Integer.MIN_VALUE,
                    0L, false);
        }
    }

    private final AtomicReference<Strategy> ref =
            new AtomicReference<>(Strategy.defaultStrategy());

    public Strategy get() { return ref.get(); }

    public void set(Strategy s) {
        if (s != null) ref.set(s);
    }

    public Goal currentGoal() {
        return ref.get().goal;
    }
}

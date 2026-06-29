package projeto;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Per-rival telemetry tracked over time.
 *
 * Owned and mutated by {@link RivalTracker} on the main loop thread.
 * Read by the planner thread (snapshot) and by the GUI (classifications).
 *
 * Identity: when the arena sends a stable {@code id} we use it directly.
 * Otherwise RivalTracker maps each observation to the nearest prior profile
 * and keys new ones positionally.
 */
public class RivalProfile {

    public enum Classification {
        AGGRESSIVE, DEFENSIVE, PASSIVE, UNKNOWN;

        public static Classification parse(String s) {
            if (s == null) return null;
            try {
                return Classification.valueOf(s.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
    }

    /** Cap on retained samples to bound memory in long matches. */
    private static final int RING_SIZE = 12;

    private final String id;
    private final Deque<int[]> recentPositions = new ArrayDeque<>(RING_SIZE);
    private final Deque<Integer> recentHP       = new ArrayDeque<>(RING_SIZE);

    private int lastSeenTick = -1;
    // Cross-thread: clazz/threat are written by RivalTracker.update (main loop)
    // AND by PlannerClient.applyPlannerResult (daemon thread), and read by
    // the Swing paint thread for colour-by-class. Marked volatile to give
    // every cross-thread reader a happens-before edge.
    private volatile Classification clazz = Classification.UNKNOWN;
    private volatile int threat = 0;

    // Cached latest coordinates for fast lookup (frequent: trigger check,
    // GUI overlay, planner snapshot).
    private int lastX = Integer.MIN_VALUE;
    private int lastY = Integer.MIN_VALUE;
    private int lastHP = -1;

    public RivalProfile(String id) {
        this.id = id;
    }

    public String getId() { return id; }
    public int getLastSeenTick() { return lastSeenTick; }
    public Classification getClazz() { return clazz; }
    public int getThreat() { return threat; }
    public int getLastX() { return lastX; }
    public int getLastY() { return lastY; }
    public int getLastHP() { return lastHP; }

    /**
     * Records one observation and refreshes the cheap heuristic classifier.
     * Called once per tick per observed rival.
     */
    public void update(int x, int y, int hp, int tick) {
        recentPositions.addFirst(new int[]{x, y});
        while (recentPositions.size() > RING_SIZE) recentPositions.removeLast();
        recentHP.addFirst(hp);
        while (recentHP.size() > RING_SIZE) recentHP.removeLast();

        lastX = x;
        lastY = y;
        lastHP = hp;
        lastSeenTick = tick;

        refreshHeuristicClassification();
    }

    /** Called by the planner to override the local heuristic with model output. */
    public void overrideClassification(Classification c, int t) {
        if (c != null) clazz = c;
        threat = Math.max(0, Math.min(10, t));
    }

    /** Whether the rival moved across the recent ring window (mobile). */
    public boolean isMobile() {
        if (recentPositions.size() < 2) return false;
        int[] first = recentPositions.peekFirst();
        int[] last  = recentPositions.peekLast();
        return Math.abs(first[0] - last[0]) > 0 || Math.abs(first[1] - last[1]) > 0;
    }

    /** Whether the rival is closing on the agent's current position. */
    public boolean isApproaching(int selfX, int selfY) {
        if (recentPositions.size() < 2) return false;
        // Self position was recorded against an earlier rival position; for a
        // stable approach signal we compare earlier distance vs latest
        // distance (selfX/selfY = current self).
        int[] earliest = recentPositions.peekLast();
        int oldDist = Math.abs(earliest[0] - selfX) + Math.abs(earliest[1] - selfY);
        int newDist = Math.abs(lastX - selfX)        + Math.abs(lastY - selfY);
        return newDist < oldDist;
    }

    /** HP delta over the ring window (positive = rival is gaining HP). */
    public int hpDelta() {
        if (recentHP.size() < 2) return 0;
        return recentHP.peekFirst() - recentHP.peekLast();
    }

    /**
     * Cheap fallback classifier used until the planner takes over. The planner
     * is the source of truth: once it has classified a rival (overrideClassification),
     * we leave the tag alone for as long as the profile lives — otherwise the
     * per-tick {@link #update} call would reset the GUI colour back to the
     * heuristic on every observation and the planner's work would flicker away.
     */
    private void refreshHeuristicClassification() {
        if (clazz != Classification.UNKNOWN) return; // planner already set it
        if (!isMobile()) {
            clazz = Classification.PASSIVE;
            threat = 1;
            return;
        }
        if (hpDelta() > 0) {
            clazz = Classification.DEFENSIVE;
            threat = 3;
            return;
        }
        // Mobile + losing HP = hunting us: aggressive.
        clazz = Classification.AGGRESSIVE;
        threat = 5;
    }
}

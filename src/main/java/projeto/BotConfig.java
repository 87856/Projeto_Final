package projeto;

import java.util.Arrays;
import java.util.List;

/**
 * Immutable per-run configuration derived from the {@code --mode} launch flag
 * (passed as {@code -Dbot.mode=<name>} by start.sh / start.ps1).
 *
 * Every mode is a pre-built static constant. {@link #fromMode(String)} is the
 * only public factory — Agent reads it once at construction time.
 */
public final class BotConfig {

    // Default flee threshold — mirrors Agent.HP_FUGA without creating a
    // circular compile-time dependency.
    private static final int DEFAULT_HP_FUGA = 60;

    // ---- public fields (all final) ----------------------------------------

    public final String name;

    // goal / planner
    public final StrategyState.Goal initialGoal;
    public final boolean lockGoal;    // planner cannot change goal when true
    public final boolean runPlanner;  // whether to start PlannerClient thread
    public final boolean llmDisabled; // disable ALL LLM use (fast + planner + RAG)

    // combat
    public final boolean skipCombat;          // never engage (avaliarCombate returns null)
    public final int     attackDistMax;       // Manhattan dist to consider engaging (default 2)
    public final int     combatMarginDefault; // attack if selfHP > rivalHP + margin (default 20)
    public final boolean fleeFromAny;         // flee from ANY visible rival regardless of dist
    public final boolean targetWeakest;       // prefer lowest-HP rival in range
    public final int     requiredHpAdvantage; // only attack if self advantage >= this (assassin)

    // flee / resources
    public final int     hpFuga;        // flee-for-resource threshold (default 60)
    public final boolean skipResources; // never navigate to resources

    // chests
    public final boolean skipChests;  // skip chest-navigation (still unlocks if standing on one)
    public final boolean chestFirst;  // navigate to chests BEFORE combat/resources in pipeline
    public final boolean chestOnly;   // chestFirst + skipCombat + skipResources

    // ---- factory -----------------------------------------------------------

    public static BotConfig fromMode(String mode) {
        if (mode == null || mode.isBlank()) return OPPORTUNIST;
        switch (mode.toLowerCase().trim()) {
            case "opportunist": return OPPORTUNIST;
            case "berserker":   return BERSERKER;
            case "dominator":   return DOMINATOR;
            case "bully":       return BULLY;
            case "coward":      return COWARD;
            case "ghost":       return GHOST;
            case "survivor":    return SURVIVOR;
            case "passive":     return PASSIVE;
            case "farmer":      return FARMER;
            case "treasure":    return TREASURE;
            case "hoarder":     return HOARDER;
            case "rich":        return RICH;
            case "assassin":    return ASSASSIN;
            case "scavenger":   return SCAVENGER;
            case "explorer":    return EXPLORER;
            case "no-llm":      return NO_LLM;
            default:
                System.err.println("[BotConfig] Unknown mode '" + mode + "' — using opportunist.");
                return OPPORTUNIST;
        }
    }

    /** Returns name + one-line description for every mode, for --mode list. */
    public static List<String[]> allModes() {
        return Arrays.asList(
            new String[]{"opportunist", "Default balanced play — equal weight to all objectives"},
            new String[]{"berserker",   "HUNT always; attack regardless of HP margin; flee only at HP 20"},
            new String[]{"dominator",   "Attack any rival within distance 3; never checks HP margin"},
            new String[]{"bully",       "Targets lowest-HP rival in range; attacks if any HP advantage"},
            new String[]{"coward",      "HIDE always; flee threshold HP 150; never initiates combat"},
            new String[]{"ghost",       "Flee from ANY visible rival regardless of distance; pure explorer"},
            new String[]{"survivor",    "Resources always priority; extreme flee HP 120; no combat"},
            new String[]{"passive",     "Retaliates only when rival is adjacent (dist 1); never initiates"},
            new String[]{"farmer",      "FARM always — resources above everything; skips combat"},
            new String[]{"treasure",    "Pathfinds to chests above all; skips combat; grabs resources near death"},
            new String[]{"hoarder",     "Chests first, resources second; only fights very weak rivals (margin 50)"},
            new String[]{"rich",        "Opens chests only; ignores combat and resources entirely"},
            new String[]{"assassin",    "Cherry-picks weaklings — attacks only when HP advantage > 50"},
            new String[]{"scavenger",   "Farms resources safely after battles; avoids all combat"},
            new String[]{"explorer",    "Maximise map coverage; skip combat; resources only if near death (HP 40)"},
            new String[]{"no-llm",      "Pure heuristic — no Ollama calls at all; fastest possible loop"}
        );
    }

    @Override
    public String toString() { return name; }

    // ---- builder -----------------------------------------------------------

    private static final class Builder {
        String name = "opportunist";
        StrategyState.Goal initialGoal = StrategyState.Goal.OPPORTUNIST;
        boolean lockGoal = false;
        boolean runPlanner = true;
        boolean llmDisabled = false;
        boolean skipCombat = false;
        int attackDistMax = 2;
        int combatMarginDefault = 20;
        boolean fleeFromAny = false;
        boolean targetWeakest = false;
        int requiredHpAdvantage = 0;
        int hpFuga = DEFAULT_HP_FUGA;
        boolean skipResources = false;
        boolean skipChests = false;
        boolean chestFirst = false;
        boolean chestOnly = false;

        Builder name(String v)           { name = v; return this; }
        Builder goal(StrategyState.Goal v){ initialGoal = v; return this; }
        Builder lockGoal()               { lockGoal = true; return this; }
        Builder noPlanner()              { runPlanner = false; return this; }
        Builder llmOff()                 { llmDisabled = true; return this; }
        Builder skipCombat()             { skipCombat = true; return this; }
        Builder attackDist(int v)        { attackDistMax = v; return this; }
        Builder margin(int v)            { combatMarginDefault = v; return this; }
        Builder fleeAny()                { fleeFromAny = true; return this; }
        Builder weakest()                { targetWeakest = true; return this; }
        Builder advantage(int v)         { requiredHpAdvantage = v; return this; }
        Builder hpFuga(int v)            { hpFuga = v; return this; }
        Builder noResources()            { skipResources = true; return this; }
        Builder noChests()               { skipChests = true; return this; }
        Builder chestFirst()             { chestFirst = true; return this; }
        Builder chestOnly()              { chestOnly = true; return this; }

        BotConfig build() { return new BotConfig(this); }
    }

    private BotConfig(Builder b) {
        name                = b.name;
        initialGoal         = b.initialGoal;
        lockGoal            = b.lockGoal;
        runPlanner          = b.runPlanner;
        llmDisabled         = b.llmDisabled;
        // chestOnly implies skipCombat + skipResources + chestFirst
        skipCombat          = b.skipCombat || b.chestOnly;
        attackDistMax       = b.attackDistMax;
        combatMarginDefault = b.combatMarginDefault;
        fleeFromAny         = b.fleeFromAny;
        targetWeakest       = b.targetWeakest;
        requiredHpAdvantage = b.requiredHpAdvantage;
        hpFuga              = b.hpFuga;
        skipResources       = b.skipResources || b.chestOnly;
        skipChests          = b.skipChests;
        chestFirst          = b.chestFirst || b.chestOnly;
        chestOnly           = b.chestOnly;
    }

    // ---- mode definitions --------------------------------------------------

    public static final BotConfig OPPORTUNIST = new Builder()
            .name("opportunist")
            .build();

    // Aggressive: attack anything; flee only when nearly dead.
    public static final BotConfig BERSERKER = new Builder()
            .name("berserker")
            .goal(StrategyState.Goal.HUNT).lockGoal().noPlanner()
            .margin(-9999).hpFuga(20)
            .build();

    // Aggressive: attack within dist 3; ignores HP comparison entirely.
    public static final BotConfig DOMINATOR = new Builder()
            .name("dominator")
            .goal(StrategyState.Goal.HUNT).lockGoal().noPlanner()
            .margin(-9999).attackDist(3).hpFuga(40)
            .build();

    // Aggressive: hunts weakest rival; attacks if any HP advantage.
    public static final BotConfig BULLY = new Builder()
            .name("bully")
            .goal(StrategyState.Goal.HUNT).lockGoal()
            .weakest().margin(0).attackDist(3).hpFuga(40)
            .build();

    // Defensive: HIDE always; never fights; flee threshold 150.
    public static final BotConfig COWARD = new Builder()
            .name("coward")
            .goal(StrategyState.Goal.HIDE).lockGoal().noPlanner()
            .skipCombat().hpFuga(150)
            .build();

    // Defensive: flee from any visible rival; pure exploration.
    public static final BotConfig GHOST = new Builder()
            .name("ghost")
            .goal(StrategyState.Goal.HIDE).lockGoal().noPlanner()
            .skipCombat().fleeAny().hpFuga(200)
            .build();

    // Defensive: extreme flee HP 120; never initiates combat.
    public static final BotConfig SURVIVOR = new Builder()
            .name("survivor")
            .goal(StrategyState.Goal.FARM).lockGoal().noPlanner()
            .skipCombat().hpFuga(120)
            .build();

    // Passive: only retaliates when rival is adjacent (dist 1); no initiation.
    public static final BotConfig PASSIVE = new Builder()
            .name("passive")
            .attackDist(1).margin(0).hpFuga(60)
            .build();

    // Objective: FARM always; resources above everything.
    public static final BotConfig FARMER = new Builder()
            .name("farmer")
            .goal(StrategyState.Goal.FARM).lockGoal().noPlanner()
            .skipCombat().hpFuga(250)
            .build();

    // Objective: pathfinds to chests; skips combat; grabs resources if near death (HP 30).
    public static final BotConfig TREASURE = new Builder()
            .name("treasure")
            .skipCombat().noResources().chestFirst().hpFuga(30)
            .build();

    // Objective: chests first, then resources; only fights very weak rivals.
    public static final BotConfig HOARDER = new Builder()
            .name("hoarder")
            .chestFirst().margin(50).hpFuga(60)
            .build();

    // Objective: chests only; ignores combat and resources entirely.
    public static final BotConfig RICH = new Builder()
            .name("rich")
            .chestOnly()
            .build();

    // Smart: cherry-picks weaklings; needs 50 HP advantage to engage.
    public static final BotConfig ASSASSIN = new Builder()
            .name("assassin")
            .goal(StrategyState.Goal.HUNT).lockGoal()
            .weakest().advantage(50).attackDist(4).hpFuga(80)
            .build();

    // Smart: farms resources safely; avoids all combat.
    public static final BotConfig SCAVENGER = new Builder()
            .name("scavenger")
            .goal(StrategyState.Goal.FARM).lockGoal().noPlanner()
            .skipCombat().hpFuga(80)
            .build();

    // Smart: maximise map coverage; resources only near death (HP 40).
    public static final BotConfig EXPLORER = new Builder()
            .name("explorer")
            .skipCombat().hpFuga(40)
            .build();

    // Utility: pure heuristic — no Ollama at all.
    public static final BotConfig NO_LLM = new Builder()
            .name("no-llm")
            .llmOff().noPlanner()
            .build();
}

package net.kyle.everlight;

import net.botwithus.api.game.hud.inventories.Backpack;
import net.botwithus.api.game.hud.inventories.Bank;
import net.botwithus.internal.scripts.ScriptDefinition;
import net.botwithus.rs3.game.Client;
import net.botwithus.rs3.game.Coordinate;
import net.botwithus.rs3.game.minimenu.MiniMenu;
import net.botwithus.rs3.game.queries.builders.objects.SceneObjectQuery;
import net.botwithus.rs3.game.scene.entities.characters.player.LocalPlayer;
import net.botwithus.rs3.game.scene.entities.object.SceneObject;
import net.botwithus.rs3.game.skills.Skills;
import net.botwithus.rs3.script.Execution;
import net.botwithus.rs3.script.LoopingScript;
import net.botwithus.rs3.script.config.ScriptConfig;

import java.util.Random;

/**
 * Everlight Porcelain Miner — mines Porcelain clay rocks in the Everlight Digsite
 * cave and banks at the surface Bank chest when the backpack fills, then returns.
 *
 * All object names / options / tiles / region ids are hardcoded below (confirmed via
 * in-game discovery), so there is nothing to configure. Runs on onLoop (the script
 * thread) where blocking Execution.delay* / NavPath calls are safe.
 */
public class EverlightMinerScript extends LoopingScript {

    public enum BotState { IDLE, MINING, BANKING }

    // ── Hardcoded targets (confirmed via in-game dumps) ────────────────────────
    private static final String ROCK_NAME   = "Porcelain clay rock";
    private static final String MINE_OPTION = "Mine";
    private static final String ORE_NAME    = "Porcelain clay";   // for the mined counter

    private static final int CAVE_REGION            = 9077;   // the rocks' cave
    private static final int EVERLIGHT_BANK_REGION  = 14642;  // surface, bank side
    private static final int EVERLIGHT_EXCAV_REGION = 14898;  // surface, cave-entrance side

    private static final int ROCK_X = 2267, ROCK_Y = 7518, ROCK_Z = 2;

    private static final String CAVE_EXIT_NAME = "Cave", CAVE_EXIT_OPTION = "Exit";
    private static final int CAVE_EXIT_X = 2272, CAVE_EXIT_Y = 7540, CAVE_EXIT_Z = 2;

    private static final String SCAFFOLD_NAME = "Scaffold", SCAFFOLD_OPTION = "Skip over";
    private static final int SCAFFOLD_X = 3706, SCAFFOLD_Y = 3210, SCAFFOLD_Z = 0;

    private static final String BANK_NAME = "Bank chest";
    private static final String BANK_PRESET_OPTION = "Load Last Preset from"; // deposits + reloads preset in one click
    private static final int BANK_X = 3701, BANK_Y = 3214, BANK_Z = 0;

    private static final String CAVE_ENTER_NAME = "Cave entrance", CAVE_ENTER_OPTION = "Enter";
    private static final int CAVE_ENTER_X = 3741, CAVE_ENTER_Y = 3222, CAVE_ENTER_Z = 0;

    // Stranded-recovery teleport chain: Archaeology journal (item) -> Guild -> Dig
    // sites map -> fast travel to Everlight. Only used when the character is away
    // from the dig site; normal banking uses the surface route above.
    private static final String JOURNAL_NAME = "Archaeology journal", JOURNAL_TELE = "Teleport";
    private static final int GUILD_REGION_A = 13108, GUILD_REGION_B = 13364;
    private static final String MAP_NAME = "Dig sites map", MAP_OPTION = "View";
    private static final int MAP_X = 3327, MAP_Y = 3373;
    // Dig sites map fast-travel component clicks (interface 667):
    private static final int EVERLIGHT_HASH  = 43712523; // [667,11] Everlight entry (p2=1)
    private static final int FASTTRAVEL_HASH = 43712535; // [667,23] Fast travel   (p2=-1)

    // ── Runtime state ─────────────────────────────────────────────────────────
    private volatile BotState botState = BotState.IDLE;
    private final Random random = new Random();

    public final long scriptStartTime = System.currentTimeMillis();
    public volatile int oreMined = 0;
    private int lastOreCount = 0;

    /** Position in the banking route state machine. */
    private int bankStep = 0;
    private long lastLostLogMs = 0;
    /** Consecutive navPath failures; stop after MAX_NAV_FAILS to avoid endless spam. */
    private int navFailStreak = 0;
    private static final int MAX_NAV_FAILS = 5;
    /** Fast-travel attempts during recovery; stop after a few if it won't teleport. */
    private int fastTravelTries = 0;

    /** In-client Logs tab: thread-safe ring buffer of recent log lines. */
    private final java.util.ArrayDeque<String> logBuffer = new java.util.ArrayDeque<>();
    private static final int LOG_CAP = 300;
    private String lastStatusLog = "";

    // Stop conditions (set from the Play tab; 0 = off) + why we auto-stopped.
    public volatile int stopAtLevel = 0;
    public volatile int stopAfterMinutes = 0;
    public volatile String stopReason = "";
    /** Consecutive rock clicks that never started the mining animation (pickaxe/level?). */
    private int noAnimStreak = 0;

    public EverlightMinerScript(String name, ScriptConfig scriptConfig, ScriptDefinition definition) {
        super(name, scriptConfig, definition);
    }

    @Override
    public boolean initialize() {
        this.sgc = new EverlightMinerGraphicsContext(getConsole(), this);
        this.loopDelay = 300;
        log("[INIT] Everlight Porcelain Miner initialized — press Start.");
        return super.initialize();
    }

    @Override
    public void onLoop() {
        LocalPlayer player = Client.getLocalPlayer();
        if (player == null || Client.getGameState() != Client.GameState.LOGGED_IN) {
            Execution.delay(1000);
            return;
        }
        updateOreCount();
        logVerbose(player);
        if (checkStopConditions()) { Execution.delay(600); return; }
        switch (botState) {
            case IDLE -> Execution.delay(600);
            case MINING -> handleMining(player);
            case BANKING -> handleBanking(player);
        }
    }

    // ── Mining ────────────────────────────────────────────────────────────────
    private void handleMining(LocalPlayer player) {
        if (Backpack.isFull()) {
            log("[MINE] Backpack full — banking.");
            bankStep = 0;
            botState = BotState.BANKING;
            return;
        }
        // Already mining (animating) or walking — let it run.
        if (player.getAnimationId() != -1 || player.isMoving()) {
            Execution.delay(randomLong(600, 1200));
            return;
        }
        SceneObject rock = SceneObjectQuery.newQuery().name(ROCK_NAME).option(MINE_OPTION).results().nearest();
        if (rock == null) {
            boolean inCave = player.getCoordinate() != null
                    && player.getCoordinate().getRegionId() == CAVE_REGION;
            if (!inCave) {
                int r = regionOf();
                boolean atEverlight = r == EVERLIGHT_BANK_REGION || r == EVERLIGHT_EXCAV_REGION;
                if (System.currentTimeMillis() - lastLostLogMs > 8_000) {
                    lastLostLogMs = System.currentTimeMillis();
                    log("[MINE] Not in the mining cave (region " + r + ") — "
                            + (atEverlight ? "return legs" : "teleport chain") + " to get back.");
                }
                // At Everlight -> just the return legs; stranded elsewhere -> teleport chain.
                bankStep = atEverlight ? 3 : 10;
                botState = BotState.BANKING;
            } else if (chebyshev(player, ROCK_X, ROCK_Y) > 1) {
                navTo(ROCK_X, ROCK_Y, ROCK_Z);          // in the cave but no rock loaded
            } else {
                Execution.delay(randomLong(1500, 2500)); // at the rocks but none available
            }
            return;
        }
        if (rock.interact(MINE_OPTION)) {
            boolean started = Execution.delayUntil(3000, () -> {
                LocalPlayer me = Client.getLocalPlayer();
                return me != null && me.getAnimationId() != -1;
            });
            if (started) {
                noAnimStreak = 0;
            } else if (++noAnimStreak == 5) {
                log("[MINE] clicked a rock 5x but never started mining — check pickaxe / Mining level / access.");
            } else if (noAnimStreak >= 15) {
                autoStop("not mining after 15 tries (pickaxe / level?)");
                return;
            }
        }
        Execution.delay(randomLong(600, 1200));
        maybeBreak();
    }

    // ── Banking route (surface): leave cave -> Skip over scaffold -> Bank chest ->
    //    deposit -> scaffold back -> cave entrance -> walk to rocks. The "lost"
    //    recovery enters at step 3 (return legs) or step 10 (teleport chain). ──────
    private void handleBanking(LocalPlayer player) {
        int region = (player.getCoordinate() != null) ? player.getCoordinate().getRegionId() : -1;
        boolean inCave = region == CAVE_REGION;

        switch (bankStep) {
            case 0 -> {   // leave the cave to the surface
                if (!inCave) { bankStep = 1; return; }
                if (travelStep(CAVE_EXIT_NAME, CAVE_EXIT_OPTION, CAVE_EXIT_X, CAVE_EXIT_Y, CAVE_EXIT_Z)) {
                    log("[BANK] leaving cave");
                    Execution.delayUntil(8000, () -> regionOf() != CAVE_REGION);
                }
            }
            case 1 -> {   // cross the scaffold to the bank side
                if (region == EVERLIGHT_BANK_REGION) { bankStep = 2; return; }
                if (travelStep(SCAFFOLD_NAME, SCAFFOLD_OPTION, SCAFFOLD_X, SCAFFOLD_Y, SCAFFOLD_Z)) {
                    log("[BANK] scaffold -> bank side");
                    Execution.delayUntil(8000, () -> regionOf() == EVERLIGHT_BANK_REGION);
                }
            }
            case 2 -> {   // Load Last Preset from the Bank chest: deposits + reloads in one click
                if (backpackOreCount() == 0) { bankStep = 3; return; }   // porcelain gone -> deposited
                if (travelStep(BANK_NAME, BANK_PRESET_OPTION, BANK_X, BANK_Y, BANK_Z)) {
                    log("[BANK] load last preset");
                    Execution.delayUntil(5000, () -> backpackOreCount() == 0);
                }
            }
            case 3 -> {   // cross the scaffold back toward the cave (recovery entry point)
                if (inCave || region == EVERLIGHT_EXCAV_REGION) { bankStep = 4; return; }
                if (travelStep(SCAFFOLD_NAME, SCAFFOLD_OPTION, SCAFFOLD_X, SCAFFOLD_Y, SCAFFOLD_Z)) {
                    log("[BANK] scaffold -> cave side");
                    Execution.delayUntil(8000, () -> regionOf() == EVERLIGHT_EXCAV_REGION);
                }
            }
            case 4 -> {   // go back down into the cave
                if (inCave) { bankStep = 5; return; }
                if (travelStep(CAVE_ENTER_NAME, CAVE_ENTER_OPTION, CAVE_ENTER_X, CAVE_ENTER_Y, CAVE_ENTER_Z)) {
                    log("[BANK] entering cave");
                    Execution.delayUntil(8000, () -> regionOf() == CAVE_REGION);
                }
            }
            case 5 -> {   // walk back to the rocks, then resume mining
                if (Backpack.isFull()) {
                    log("[BANK] still full at the rocks — re-banking.");
                    bankStep = 0;
                    return;
                }
                if (chebyshev(player, ROCK_X, ROCK_Y) > 4) navTo(ROCK_X, ROCK_Y, ROCK_Z);
                log("[BANK] route complete — back to MINING.");
                bankStep = 0;
                botState = BotState.MINING;
            }

            // ── Stranded-recovery teleport chain (10-12): journal -> Guild -> map -> key
            case 10 -> {   // get to the Guild via the journal item
                if (region == GUILD_REGION_A || region == GUILD_REGION_B) { bankStep = 11; return; }
                boolean ok = Backpack.interact(JOURNAL_NAME, JOURNAL_TELE);
                log("[BANK] journal '" + JOURNAL_TELE + "' -> " + ok);
                Execution.delayUntil(8000, () -> {
                    int rr = regionOf();
                    return rr == GUILD_REGION_A || rr == GUILD_REGION_B;
                });
            }
            case 11 -> {   // open the Dig sites map at the Guild
                if (travelStep(MAP_NAME, MAP_OPTION, MAP_X, MAP_Y, 0)) {
                    log("[BANK] opened Dig sites map");
                    Execution.delay(randomLong(1500, 2200));   // let the map render
                    bankStep = 12;
                }
            }
            case 12 -> {   // click Everlight + Fast travel (captured DoActions), wait to arrive
                log("[BANK] map: Everlight + fast travel");
                MiniMenu.interact(14, 1, 1, EVERLIGHT_HASH);     // [667,11] select Everlight
                Execution.delay(randomLong(700, 1100));
                MiniMenu.interact(14, 1, -1, FASTTRAVEL_HASH);   // [667,23] fast travel
                boolean arrived = Execution.delayUntil(12000, () -> regionOf() == EVERLIGHT_BANK_REGION);
                if (arrived) {
                    fastTravelTries = 0;
                    bankStep = 3;                                // hand off to the surface return legs
                } else if (++fastTravelTries >= 4) {
                    fastTravelTries = 0;
                    autoStop("fast travel to Everlight failed");
                } else {
                    bankStep = 11;                               // reopen the map and retry
                }
            }
        }
        Execution.delay(randomLong(400, 700));
    }

    // ── Navigation ────────────────────────────────────────────────────────────
    /** One hop toward a named object: click it if loaded, else path toward its tile. */
    private boolean travelStep(String name, String option, int tx, int ty, int tz) {
        SceneObject obj = SceneObjectQuery.newQuery().name(name).option(option).results().nearest();
        if (obj != null) return obj.interact(option);
        navTo(tx, ty, tz);
        return false;
    }

    /** Path to a tile with navPathTraverse (which in this client is a bresenham walk,
     *  NOT a teleport-aware pathfinder — it only handles short, walkable hops). If it
     *  fails to find a path repeatedly (e.g. stranded far from the route), STOP
     *  instead of spamming "No path found" forever. */
    private boolean navTo(int x, int y, int z) {
        // navPathTraverse returns true just for issuing a walk click (even when it
        // logs "No path found"), so its return value is useless for detecting arrival.
        // Judge success by ACTUAL position progress toward the target instead.
        LocalPlayer me = Client.getLocalPlayer();
        int before = chebyshev(me, x, y);
        try {
            net.botwithus.api.game.world.Traverse.navPathTraverse(new Coordinate(x, y, z));
        } catch (Throwable t) {
            log("[NAV][ERR] navPathTraverse " + x + "," + y + "," + z + ": " + t);
        }
        me = Client.getLocalPlayer();
        int after = chebyshev(me, x, y);

        if (after <= 3) {                         // arrived
            navFailStreak = 0;
            log("[NAV] " + x + "," + y + "," + z + " reached");
            return true;
        }
        if (after < before) {                     // still making progress — keep going
            navFailStreak = 0;
            log("[NAV] " + x + "," + y + "," + z + " dist " + before + "->" + after);
            return false;
        }
        // no progress this attempt
        if (++navFailStreak >= MAX_NAV_FAILS) {
            navFailStreak = 0;
            autoStop("nav stuck at " + x + "," + y + " — move the character to the rocks and Start");
        } else {
            log("[NAV] no progress to " + x + "," + y + "," + z + " (dist " + after + ") "
                    + navFailStreak + "/" + MAX_NAV_FAILS);
        }
        return false;
    }

    private int chebyshev(LocalPlayer p, int x, int y) {
        if (p == null || p.getCoordinate() == null) return Integer.MAX_VALUE;
        return Math.max(Math.abs(p.getCoordinate().getX() - x), Math.abs(p.getCoordinate().getY() - y));
    }

    private int regionOf() {
        LocalPlayer me = Client.getLocalPlayer();
        return (me != null && me.getCoordinate() != null) ? me.getCoordinate().getRegionId() : -1;
    }

    // ── Counters ──────────────────────────────────────────────────────────────
    /** Track porcelain mined by watching the backpack count rise (resets on bank). */
    private void updateOreCount() {
        int cur;
        try { cur = Backpack.getCount(ORE_NAME); } catch (Throwable t) { return; }
        if (cur > lastOreCount) oreMined += (cur - lastOreCount);
        lastOreCount = cur;
    }

    private long randomLong(int minInclusive, int maxInclusive) {
        return minInclusive + random.nextInt(Math.max(1, maxInclusive - minInclusive + 1));
    }

    // ── Logging (streamed to the GUI Logs tab + the console) ────────────────────
    /** Append a line to the in-client Logs tab ring buffer and the script console. */
    public void log(String s) {
        long secs = (System.currentTimeMillis() - scriptStartTime) / 1000;
        String line = String.format("[%02d:%02d:%02d] %s", secs / 3600, (secs % 3600) / 60, secs % 60, s);
        synchronized (logBuffer) {
            logBuffer.addLast(line);
            while (logBuffer.size() > LOG_CAP) logBuffer.removeFirst();
        }
        try { println(s); } catch (Throwable ignored) {}
    }

    /** Snapshot of the log ring buffer for the GUI (oldest first). */
    public java.util.List<String> recentLog() {
        synchronized (logBuffer) { return new java.util.ArrayList<>(logBuffer); }
    }

    public void clearLog() { synchronized (logBuffer) { logBuffer.clear(); } }

    /** Emit a status line only when the meaningful state changes (state/step/region),
     *  so it doesn't spam a line per ore mined. free/mined are shown but don't trigger. */
    private void logVerbose(LocalPlayer player) {
        String key = "state=" + botState + (botState == BotState.BANKING ? " step=" + bankStep : "")
                + " region=" + regionOf();
        if (key.equals(lastStatusLog)) return;
        lastStatusLog = key;
        int free = -1;
        try { free = Backpack.countFreeSlots(); } catch (Throwable ignored) {}
        log("[STATUS] " + key + " free=" + free + " mined=" + oreMined);
    }

    // ── Stop conditions / anti-ban ──────────────────────────────────────────────
    /** Stop when a target Mining level or runtime is reached. Returns true if stopped. */
    private boolean checkStopConditions() {
        if (botState == BotState.IDLE) return false;
        if (stopAtLevel > 0) {
            int lvl = 0;
            try { lvl = Skills.MINING.getSkill().getLevel(); } catch (Throwable ignored) {}
            if (lvl >= stopAtLevel) { autoStop("reached Mining level " + lvl); return true; }
        }
        if (stopAfterMinutes > 0
                && (System.currentTimeMillis() - scriptStartTime) / 60000 >= stopAfterMinutes) {
            autoStop("ran for " + stopAfterMinutes + " min");
            return true;
        }
        return false;
    }

    /** Occasionally pause like a human — a short break most of the time, a rare longer
     *  AFK. Only called from the mining loop, never mid-bank/nav. */
    private void maybeBreak() {
        int roll = random.nextInt(100);
        if (roll < 3) {                                  // ~3%: longer AFK
            long ms = randomLong(20_000, 60_000);
            log("[BREAK] afk " + (ms / 1000) + "s");
            Execution.delay(ms);
        } else if (roll < 18) {                          // ~15%: short pause
            Execution.delay(randomLong(2000, 6000));
        }
    }

    /** Stop the bot and record why (surfaced on the Play tab + logged prominently). */
    private void autoStop(String reason) {
        stopReason = reason;
        bankStep = 0;
        botState = BotState.IDLE;
        log("[STOP] " + reason);
    }

    private int backpackOreCount() {
        try { return Backpack.getCount(ORE_NAME); } catch (Throwable t) { return 0; }
    }

    // ── GUI accessors ─────────────────────────────────────────────────────────
    public BotState getBotState() { return botState; }
    public void setBotState(BotState s) {
        if (s == BotState.MINING) { stopReason = ""; noAnimStreak = 0; }  // fresh Start
        this.botState = s;
    }
}

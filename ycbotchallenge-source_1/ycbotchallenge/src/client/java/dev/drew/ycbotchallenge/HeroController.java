package dev.drew.ycbotchallenge;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.MinecraftClient;

/**
 * 0.9.48: /heroes. The hero (Drew's Archer Queen) is a summoned ally that shoots the mobs
 * in the zone; it lives on an HP pool that drains ~6.9/min while it is out (86 -> gone in
 * 12.5 min on 2026-09-06 00:57-01:10) and refills ~1.95/min while it is down (16 at
 * +8.3 min, 20 at +10.2 min after the despawn line), max 100, and the server refuses a
 * spawn under 25 ("Your hero needs 25 health before you can spawn it!"). Drew: "in /heroes
 * click beacon then close menu" - the hero's own item is the button, and the menu stays
 * open afterwards.
 *
 * <p>Timing is a distribution, not a clock: each cycle draws a target HP in
 * [heroSpawnHpMin, heroSpawnHpMax], the regen model says when the pool reaches it, and
 * the visit waits that long with +-heroCheckLeewayPct of jitter, then for a post-kill lull.
 * The menu opens once per cycle, at the estimate, and the click goes whenever the pool
 * is over the server's floor (0.9.49, Drew: "too many menus too often, just off our
 * estimate spawn it in, learn over time and vary"); the HP read there feeds the model
 * (hero_regen) for the next estimate. The nameplate tracker ({@link HeroTracker})
 * supplies the decay rate and the alive/gone state.
 */
public class HeroController {
    private enum Phase { IDLE, TYPE, MENU_WAIT, LOOK, CLICK, CONFIRM, CLOSE, DONE }

    private final YCBotChallengeConfig cfg;
    private final StatsTracker stats;
    private final UpgradeController upgrades;
    private final HeroTracker tracker;
    private final ChatTyper typer;
    private final Pattern plateRe;
    private final Pattern loreRe;
    private EventLogger logger;

    private Phase phase = Phase.IDLE;
    private long phaseUntil;
    private long visitStartedAt;
    private long clickAt;
    private long nextCheckAt = 0;
    private double targetHp = -1;
    private String nextVia = "start";
    private int heroSlot = -1;
    private String heroName;
    private Double menuHp;
    private Double menuMax;
    private int consecutiveAborts;
    private boolean suspended;
    private long lastSkipLogAt;
    private int spawnsThisSession;
    private long lastGateSeen;
    /** The drawn target holds for the whole cycle; a fresh draw only after a spawn (or a first plan). */
    private boolean cycleSpawned = true;

    public HeroController(YCBotChallengeConfig cfg, StatsTracker stats, UpgradeController upgrades, HeroTracker tracker) {
        this.cfg = cfg;
        this.stats = stats;
        this.upgrades = upgrades;
        this.tracker = tracker;
        this.typer = new ChatTyper(cfg);
        this.plateRe = HeroTracker.compile(cfg.heroPlatePattern);
        this.loreRe = HeroTracker.compile(cfg.heroLorePattern);
    }

    public void setLogger(EventLogger logger) { this.logger = logger; }
    private void log(String type, Object... kv) { if (logger != null) logger.log(type, kv); }

    public boolean isBusy() { return phase != Phase.IDLE; }
    public boolean isSuspended() { return suspended; }

    public boolean isOurGui(MinecraftClient client) {
        return Economy.isServerMenu(GuiHuman.title(client), List.of(cfg.heroMenuTitle));
    }

    public void onEnable(long now, int kills) {
        suspended = false;
        consecutiveAborts = 0;
        if (nextCheckAt == 0) schedule(now, "enable");
    }

    public void reset(MinecraftClient client) {
        if (client != null && phase != Phase.IDLE && isOurGui(client)) EnchantScreens.closeGui(client);
        typer.cancel(client);
        phase = Phase.IDLE;
    }

    public String hudLine() {
        if (!cfg.heroSpawnEnabled) return null;
        long now = System.currentTimeMillis();
        if (phase != Phase.IDLE) return "hero: " + phase.name().toLowerCase(Locale.ROOT) + (menuHp != null ? " \u2764" + Math.round(menuHp) : "");
        if (suspended) return "hero: suspended after repeated aborts (toggle to reset)";
        if (stats.heroAlive(now)) return "hero: out " + (now - stats.heroSpawnedAt) / 1000 + "s · " + spawnsThisSession + " spawned";
        Double hp = stats.heroPredictedHp(now, cfg.heroMaxHp);
        String t = targetHp > 0 ? " target " + Math.round(targetHp) : "";
        String in = nextCheckAt > now ? " · check in " + (nextCheckAt - now + 59_999) / 60_000 + " min" : " · check at next lull";
        return "hero: ~" + (hp != null ? Math.round(hp) : "?") + "/" + cfg.heroMaxHp + t + in
            + " · regen " + fmt(stats.heroRegenPerMin != null ? stats.heroRegenPerMin : cfg.heroRegenPerMin) + "/min";
    }

    private static String fmt(double v) { return String.valueOf(Math.round(v * 100.0) / 100.0); }

    /** The next visit: a fresh target HP and the wait the regen model implies, with leeway. */
    private void schedule(long now, String via) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        boolean redraw = targetHp <= 0 || cycleSpawned;
        if (redraw) {
            // A re-check under the target keeps the target: redrawing on every plan (the 07:37
            // read: 87 -> 67) would walk it down to the bottom of the range.
            targetHp = Economy.heroPickTarget(rng.nextDouble(), cfg.heroSpawnHpMin, cfg.heroSpawnHpMax, cfg.heroMaxHp);
            cycleSpawned = false;
        }
        Double hp = stats.heroPredictedHp(now, cfg.heroMaxHp);
        double regen = stats.heroRegenPerMin != null ? stats.heroRegenPerMin : cfg.heroRegenPerMin;
        double minutes = hp == null ? 0 : Economy.heroMinutesToTarget(hp, targetHp, regen);
        long waitMs = Economy.heroWaitMs(minutes, cfg.heroCheckLeewayPct, rng.nextDouble(), cfg.heroMinRecheckMs);
        if (stats.heroAlive(now)) {
            // Out now: the pool is spent when it lands, so the wait counts from the predicted despawn.
            double decay = stats.heroDecayPerMin != null ? stats.heroDecayPerMin : cfg.heroDecayPerMin;
            double hpAtSpawn = stats.heroHpAtSpawn != null ? stats.heroHpAtSpawn : cfg.heroMaxHp;
            long lifeLeft = Math.max(0, Math.round(hpAtSpawn / Math.max(0.1, decay) * 60_000.0) - (now - stats.heroSpawnedAt));
            minutes = Economy.heroMinutesToTarget(0, targetHp, regen);
            waitMs = lifeLeft + Economy.heroWaitMs(minutes, cfg.heroCheckLeewayPct, rng.nextDouble(), cfg.heroMinRecheckMs);
        }
        nextCheckAt = now + waitMs;
        nextVia = via;
        log("hero_plan", "via", via, "targetHp", Math.round(targetHp), "redrawn", redraw, "predictedHp", hp != null ? Math.round(hp * 10.0) / 10.0 : null,
            "regenPerMin", Math.round(regen * 100.0) / 100.0, "waitMs", waitMs, "alive", stats.heroAlive(now));
    }

    /** @return true if combat should yield this tick. */
    public boolean tick(MinecraftClient client, CombatController combat) {
        if (!cfg.heroSpawnEnabled || client.player == null || client.world == null) { if (phase != Phase.IDLE) reset(client); return false; }
        long now = System.currentTimeMillis();
        if (phase == Phase.IDLE) return maybeStart(client, combat, now);
        combat.releaseKeys(client);
        if (now - visitStartedAt > 30_000) { abort(client, "visit-timeout"); return false; }
        switch (phase) {
            case TYPE -> {
                if (client.currentScreen != null && !typer.running()) {
                    if (isOurGui(client)) { phase = Phase.MENU_WAIT; phaseUntil = now + cfg.heroOpenTimeoutMs; return true; }
                    abort(client, "screen-open");
                    return false;
                }
                if (!typer.running()) { typer.begin(client, cfg.heroCommand, now); return true; }
                ChatTyper.State s = typer.tick(client, now);
                if (s == ChatTyper.State.FAILED) { abort(client, typer.failReason()); return false; }
                if (s != ChatTyper.State.DONE) return true;
                log("hero_send", "command", cfg.heroCommand, "typos", typer.typos());
                phase = Phase.MENU_WAIT;
                phaseUntil = now + cfg.heroOpenTimeoutMs;
            }
            case MENU_WAIT -> {
                if (isOurGui(client) && !GuiHuman.items(client).isEmpty()) {
                    phase = Phase.LOOK;
                    phaseUntil = now + GuiHuman.lookDelayMs(cfg, "hero");
                } else if (now >= phaseUntil) {
                    abort(client, "no-menu");
                    return false;
                }
            }
            case LOOK -> {
                if (!isOurGui(client)) { abort(client, "menu-closed"); return false; }
                if (now < phaseUntil) return true;
                List<GuiHuman.Item> items = GuiHuman.items(client);
                readMenu(items, now);
                if (heroSlot < 0) {
                    log("hero_menu_unparsed", "items", GuiHuman.describe(items));
                    abort(client, "no-hero-item");
                    return false;
                }
                // 0.9.49 (Drew: "too many menus too often, just off our estimate spawn it in"): the
                // menu is opened once per cycle, when the model says the pool is at the target,
                // and the click goes whenever the server would take it. The read only feeds the model.
                boolean go = menuHp != null && menuHp >= cfg.heroSpawnFloorHp && !tracker.isAlive();
                log("hero_menu", "name", heroName, "slot", heroSlot, "hp", menuHp, "max", menuMax, "targetHp", Math.round(targetHp),
                    "spawn", go, "underTarget", menuHp != null && menuHp < targetHp,
                    "sinceDespawnMs", stats.heroDespawnedAt != 0 ? now - stats.heroDespawnedAt : null);
                if (!go) { phase = Phase.CLOSE; phaseUntil = now + GuiHuman.closeDelayMs(cfg); return true; }
                phase = Phase.CLICK;
                phaseUntil = now + GuiHuman.clickDelayMs(cfg);
            }
            case CLICK -> {
                if (!isOurGui(client)) { abort(client, "menu-closed"); return false; }
                if (now < phaseUntil) return true;
                GuiHuman.click(client, heroSlot, "hero", "spawn:" + heroName, logger);
                clickAt = now;
                phase = Phase.CONFIRM;
                phaseUntil = now + cfg.heroConfirmMs;
            }
            case CONFIRM -> {
                if (stats.heroSpawnedAt >= clickAt) {
                    spawnsThisSession++;
                    cycleSpawned = true;
                    stats.noteHeroSpawned(menuHp, now);
                    log("hero_spawn", "name", heroName, "hp", menuHp, "spawns", spawnsThisSession, "confirmMs", now - clickAt);
                    phase = Phase.CLOSE;
                    phaseUntil = now + GuiHuman.closeDelayMs(cfg);
                } else if (stats.heroNeedsAt >= clickAt) {
                    log("hero_spawn_refused", "needs", stats.heroNeedsHp, "hp", menuHp);
                    phase = Phase.CLOSE;
                    phaseUntil = now + GuiHuman.closeDelayMs(cfg);
                } else if (!isOurGui(client) && now - clickAt > 1500) {
                    // The menu went with no line either way: read the plate for the answer later.
                    log("hero_spawn_unconfirmed", "hp", menuHp);
                    finish(client, now, "unconfirmed");
                    return false;
                } else if (now >= phaseUntil) {
                    log("hero_spawn_unconfirmed", "hp", menuHp);
                    phase = Phase.CLOSE;
                    phaseUntil = now + GuiHuman.closeDelayMs(cfg);
                }
            }
            case CLOSE -> {
                if (now < phaseUntil) return true;
                if (isOurGui(client)) GuiHuman.close(client, "hero", logger);
                finish(client, now, "done");
                return false;
            }
            default -> { }
        }
        return true;
    }

    private void readMenu(List<GuiHuman.Item> items, long now) {
        heroSlot = -1; heroName = null; menuHp = null; menuMax = null;
        for (GuiHuman.Item it : items) {
            String[] p = HeroTracker.parsePlate(plateRe, it.name());
            if (p == null) continue;
            heroSlot = it.slot();
            heroName = p[0];
            menuHp = Amounts.parse(p[1]);
            if (loreRe != null) {
                for (String line : it.lore()) {
                    Matcher m = loreRe.matcher(line);
                    if (m.find()) {
                        try {
                            menuHp = Double.parseDouble(m.group("hp").replace(",", ""));
                            menuMax = Double.parseDouble(m.group("max").replace(",", ""));
                        } catch (RuntimeException ignored) { }
                        break;
                    }
                }
            }
            break;
        }
        // A live hero shows its draining HP in the menu too (13:30: read 2, 14:52: read 7) - not a pool anchor.
        if (menuHp != null && !tracker.isAlive() && !stats.heroAlive(now)) stats.noteHeroHp(menuHp, menuMax, now, "menu");
    }

    private boolean maybeStart(MinecraftClient client, CombatController combat, long now) {
        if (suspended || combat.isOnBreak() || client.currentScreen != null) return false;
        if (nextCheckAt == 0) schedule(now, "start");
        if (now < nextCheckAt) return false;
        if (stats.heroAlive(now) || tracker.isAlive()) {
            if (now - lastSkipLogAt > 60_000) { lastSkipLogAt = now; log("hero_skip", "reason", "alive", "plate", tracker.isAlive(), "sinceSpawnMs", now - stats.heroSpawnedAt); }
            return false;
        }
        if (upgrades != null && (upgrades.isBusy() || upgrades.hasPendingDecision())) return false;
        if (combat.isCooking()) return false; // the lull after a kill, never mid-cook
        Double hp = stats.heroPredictedHp(now, cfg.heroMaxHp);
        if (hp != null && hp < cfg.heroSpawnFloorHp && now - stats.heroLastHpAt < 20 * 60_000L) {
            // The model says the server would refuse: wait for the floor, no menu open.
            schedule(now, "under-floor");
            return false;
        }
        visitStartedAt = now;
        phase = Phase.TYPE;
        phaseUntil = now;
        heroSlot = -1; menuHp = null; menuMax = null;
        log("hero_visit_start", "via", nextVia, "targetHp", Math.round(targetHp), "predictedHp", hp != null ? Math.round(hp * 10.0) / 10.0 : null,
            "sinceDespawnMs", stats.heroDespawnedAt != 0 ? now - stats.heroDespawnedAt : null);
        return true;
    }

    private void finish(MinecraftClient client, long now, String reason) {
        consecutiveAborts = 0;
        log("hero_visit_end", "reason", reason, "hp", menuHp, "durationMs", now - visitStartedAt);
        phase = Phase.IDLE;
        schedule(now, reason);
    }

    private void abort(MinecraftClient client, String why) {
        log("hero_abort", "reason", why, "phase", phase.name().toLowerCase(Locale.ROOT));
        if (isOurGui(client)) EnchantScreens.closeGui(client);
        typer.cancel(client);
        phase = Phase.IDLE;
        if (++consecutiveAborts >= Math.max(1, cfg.heroMaxConsecutiveAborts)) {
            suspended = true;
            log("hero_suspended", "aborts", consecutiveAborts, "lastReason", why);
        }
        long now = System.currentTimeMillis();
        nextCheckAt = now + cfg.heroMinRecheckMs;
        nextVia = "abort";
    }
}

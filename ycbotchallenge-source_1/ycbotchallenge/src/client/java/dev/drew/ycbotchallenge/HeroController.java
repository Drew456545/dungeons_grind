package dev.drew.ycbotchallenge;

import java.util.List;
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
 *
 * <p>0.9.62: the visit is a {@link GuiFlow} script - type, menu_wait, look, click, confirm,
 * close - the first controller on the shared driver; the abort and suspension bookkeeping
 * is {@link GuiFlow.Aborts}. Every event and field is as before.
 */
public class HeroController extends BotModule implements Module {
    @Override public String name() { return "hero"; }

    private final YCBotChallengeConfig cfg;
    private final StatsTracker stats;
    private final UpgradeController upgrades;
    private final HeroTracker tracker;
    private final ChatTyper typer;
    private final Pattern plateRe;
    private final Pattern loreRe;
    private final GuiFlow.Aborts aborts;
    private final GuiFlow flow;
    private GuiFlow.Step stepType;

    private long visitStartedAt;
    private long clickAt;
    private long nextCheckAt = 0;
    private double targetHp = -1;
    private String nextVia = "start";
    private int heroSlot = -1;
    private String heroName;
    private Double menuHp;
    private Double menuMax;
    private int spawnsThisSession;
    private long lastGateSeen;
    /** The drawn target holds for the whole cycle; a fresh draw only after a spawn (or a first plan). */
    private boolean cycleSpawned = true;
    /** 0.9.54: the farm phase seen last, and the hold log's beat. */
    private int farmSeqSeen = -1;
    private long lastHoldLogAt = 0;

    public HeroController(YCBotChallengeConfig cfg, StatsTracker stats, UpgradeController upgrades, HeroTracker tracker) {
        this.cfg = cfg;
        this.stats = stats;
        this.upgrades = upgrades;
        this.tracker = tracker;
        this.typer = new ChatTyper(cfg);
        this.plateRe = HeroTracker.compile(cfg.heroPlatePattern);
        this.loreRe = HeroTracker.compile(cfg.heroLorePattern);
        this.aborts = new GuiFlow.Aborts("hero", () -> cfg.heroMaxConsecutiveAborts, () -> 0L);
        this.flow = new GuiFlow(aborts, () -> 30_000L, "visit-timeout");
        this.flow.onAbort((client, why) -> {
            if (isOurGui(client)) EnchantScreens.closeGui(client);
            typer.cancel(client);
            nextCheckAt = System.currentTimeMillis() + cfg.heroMinRecheckMs;
            nextVia = "abort";
        });
        buildSteps();
    }

    /** The visit script, last step first so every reference points backwards. */
    private void buildSteps() {
        GuiFlow.Step finish = GuiFlow.custom("close", c -> {
            finish(c.client, c.now, "done");
            return GuiFlow.DONE;
        });
        GuiFlow.Step close = GuiFlow.close("close", c -> isOurGui(c.client), () -> GuiHuman.closeDelayMs(cfg), "hero", finish);
        GuiFlow.Step confirm = GuiFlow.custom("confirm", c -> {
            if (!c.armed()) c.arm(cfg.heroConfirmMs);
            if (stats.hero.spawnedAt >= clickAt) {
                spawnsThisSession++;
                cycleSpawned = true;
                stats.hero.noteSpawned(menuHp, c.now);
                log("hero_spawn", "name", heroName, "hp", menuHp, "spawns", spawnsThisSession, "confirmMs", c.now - clickAt);
                return close;
            } else if (stats.hero.needsAt >= clickAt) {
                log("hero_spawn_refused", "needs", stats.hero.needsHp, "hp", menuHp);
                return close;
            } else if (!isOurGui(c.client) && c.now - clickAt > 1500) {
                // The menu went with no line either way: read the plate for the answer later.
                log("hero_spawn_unconfirmed", "hp", menuHp);
                finish(c.client, c.now, "unconfirmed");
                return GuiFlow.DONE;
            } else if (c.due()) {
                log("hero_spawn_unconfirmed", "hp", menuHp);
                return close;
            }
            return null;
        });
        GuiFlow.Step click = GuiFlow.click("click", c -> isOurGui(c.client), () -> GuiHuman.clickDelayMs(cfg),
            () -> heroSlot, "hero", () -> "spawn:" + heroName, c -> clickAt = c.now, confirm);
        GuiFlow.Step look = GuiFlow.look("look", c -> isOurGui(c.client), () -> GuiHuman.lookDelayMs(cfg, "hero"), "menu-closed", c -> {
            List<GuiHuman.Item> items = GuiHuman.items(c.client);
            readMenu(items, c.now);
            if (heroSlot < 0) {
                log("hero_menu_unparsed", "items", GuiHuman.describe(items));
                c.abort("no-hero-item");
                return null;
            }
            // 0.9.49 (Drew: "too many menus too often, just off our estimate spawn it in"): the
            // menu is opened once per cycle, when the model says the pool is at the target,
            // and the click goes whenever the server would take it. The read only feeds the model.
            boolean go = menuHp != null && menuHp >= cfg.heroSpawnFloorHp && !tracker.isAlive();
            log("hero_menu", "name", heroName, "slot", heroSlot, "hp", menuHp, "max", menuMax, "targetHp", Math.round(targetHp),
                "spawn", go, "underTarget", menuHp != null && menuHp < targetHp,
                "sinceDespawnMs", stats.hero.despawnedAt != 0 ? c.now - stats.hero.despawnedAt : null);
            return go ? click : close;
        });
        GuiFlow.Step menuWait = GuiFlow.waitFor("menu_wait", c -> isOurGui(c.client) && !GuiHuman.items(c.client).isEmpty(),
            () -> cfg.heroOpenTimeoutMs, "no-menu", look);
        stepType = GuiFlow.type("type", typer, () -> cfg.heroCommand, c -> isOurGui(c.client),
            c -> log("hero_send", "command", cfg.heroCommand, "typos", typer.typos()), menuWait);
    }

    public boolean isBusy() { return flow.isBusy(); }
    public boolean isSuspended() { return aborts.suspended(); }

    public boolean isOurGui(MinecraftClient client) {
        return Economy.isServerMenu(GuiHuman.title(client), List.of(cfg.heroMenuTitle));
    }

    public void onEnable(long now, int kills) {
        aborts.onEnable();
        if (nextCheckAt == 0) schedule(now, "enable");
    }

    public void reset(MinecraftClient client) {
        if (client != null && flow.isBusy() && isOurGui(client)) EnchantScreens.closeGui(client);
        typer.cancel(client);
        flow.cancel();
    }

    public String hudLine() {
        if (!cfg.heroSpawnEnabled) return null;
        long now = System.currentTimeMillis();
        if (flow.isBusy()) return "hero: " + flow.phaseName() + (menuHp != null ? " ❤" + Math.round(menuHp) : "");
        if (aborts.suspended()) return "hero: suspended after repeated aborts (toggle to reset)";
        if (stats.hero.alive(now)) return "hero: out " + (now - stats.hero.spawnedAt) / 1000 + "s · " + spawnsThisSession + " spawned";
        Double hp = stats.hero.predictedHp(now, cfg.heroMaxHp);
        if (cfg.heroFarmPhaseOnly && !stats.farmPhase() && (hp == null || hp < cfg.heroMaxHp - 0.5)) {
            return "hero: ~" + (hp != null ? Math.round(hp) : "?") + "/" + cfg.heroMaxHp + " · holding for the farm phase (stage "
                + stats.confirmedZoneLevel() + " of ~" + stats.expectedTopStage() + ")";
        }
        String t = targetHp > 0 ? " target " + Math.round(targetHp) : "";
        String in = nextCheckAt > now ? " · check in " + (nextCheckAt - now + 59_999) / 60_000 + " min" : " · check at next lull";
        return "hero: ~" + (hp != null ? Math.round(hp) : "?") + "/" + cfg.heroMaxHp + t + in
            + " · regen " + fmt(stats.hero.regenPerMin != null ? stats.hero.regenPerMin : cfg.heroRegenPerMin) + "/min";
    }

    private static String fmt(double v) { return String.valueOf(Num.r2(v)); }

    /** The next visit: a fresh target HP and the wait the regen model implies, with leeway. */
    private void schedule(long now, String via) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        boolean redraw = targetHp <= 0 || cycleSpawned;
        if (redraw) {
            // 0.9.54: the target is the floor plus a margin when the farm phase decides the
            // moment (heroFarmPhaseOnly); the old random draw in [heroSpawnHpMin, heroSpawnHpMax]
            // only when it does not.
            targetHp = cfg.heroFarmPhaseOnly ? Math.min(cfg.heroMaxHp, cfg.heroSpawnFloorHp + cfg.heroSpawnFloorMargin)
                : Economy.heroPickTarget(rng.nextDouble(), cfg.heroSpawnHpMin, cfg.heroSpawnHpMax, cfg.heroMaxHp);
            cycleSpawned = false;
        }
        Double hp = stats.hero.predictedHp(now, cfg.heroMaxHp);
        double regen = stats.hero.regenPerMin != null ? stats.hero.regenPerMin : cfg.heroRegenPerMin;
        double minutes = hp == null ? 0 : Economy.heroMinutesToTarget(hp, targetHp, regen);
        long waitMs = Economy.heroWaitMs(minutes, cfg.heroCheckLeewayPct, rng.nextDouble(), cfg.heroMinRecheckMs);
        if (stats.hero.alive(now)) {
            // Out now: the pool is spent when it lands, so the wait counts from the predicted despawn.
            double decay = stats.hero.decayPerMin != null ? stats.hero.decayPerMin : cfg.heroDecayPerMin;
            double hpAtSpawn = stats.hero.hpAtSpawn != null ? stats.hero.hpAtSpawn : cfg.heroMaxHp;
            long lifeLeft = Math.max(0, Math.round(hpAtSpawn / Math.max(0.1, decay) * 60_000.0) - (now - stats.hero.spawnedAt));
            minutes = Economy.heroMinutesToTarget(0, targetHp, regen);
            waitMs = lifeLeft + Economy.heroWaitMs(minutes, cfg.heroCheckLeewayPct, rng.nextDouble(), cfg.heroMinRecheckMs);
        }
        nextCheckAt = now + waitMs;
        nextVia = via;
        log("hero_plan", "via", via, "targetHp", Math.round(targetHp), "redrawn", redraw, "predictedHp", hp != null ? Num.r1(hp) : null,
            "regenPerMin", Num.r2(regen), "waitMs", waitMs, "alive", stats.hero.alive(now));
    }

    /** @return true if combat should yield this tick. */
    public boolean tick(MinecraftClient client, CombatController combat) {
        if (!cfg.heroSpawnEnabled || client.player == null || client.world == null) { if (flow.isBusy()) reset(client); return false; }
        long now = System.currentTimeMillis();
        if (!flow.isBusy()) return maybeStart(client, combat, now);
        combat.releaseKeys(client);
        return flow.tick(client, combat, now, logger);
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
        if (menuHp != null && !tracker.isAlive() && !stats.hero.alive(now)) stats.hero.noteHp(menuHp, menuMax, now, "menu");
    }

    private boolean maybeStart(MinecraftClient client, CombatController combat, long now) {
        if (aborts.suspended() || combat.isOnBreak() || client.currentScreen != null) return false;
        if (nextCheckAt == 0) schedule(now, "start");
        if (stats.farmPhaseSeq != farmSeqSeen) {
            // 0.9.54: the climb just ended - the menu is due after a short beat, not at the
            // pool model's far date (a player finishes the mob in hand, then summons).
            farmSeqSeen = stats.farmPhaseSeq;
            if (!stats.hero.alive(now) && !tracker.isAlive()) {
                nextCheckAt = Math.min(nextCheckAt, now + HumanTiming.logNormalMs(20_000, 90_000));
                nextVia = "farm";
            }
        }
        if (now < nextCheckAt) return false;
        if (stats.hero.alive(now) || tracker.isAlive()) {
            logThrottled("hero_skip:alive", 60_000, "hero_skip", "reason", "alive", "plate", tracker.isAlive(), "sinceSpawnMs", now - stats.hero.spawnedAt);
            return false;
        }
        if (upgrades != null && (upgrades.isBusy() || upgrades.hasPendingDecision())) return false;
        if (combat.isCooking()) return false; // the lull after a kill, never mid-cook
        Double hp = stats.hero.predictedHp(now, cfg.heroMaxHp);
        // 0.9.54: the hero halves the time to kill at the top stage and adds nothing to the
        // climb (stage 40: 4.3 s up vs 11.7 s down over 84 kills; the farm phase covered in
        // 4 of 18 cycles) - the pool is held for the farm phase unless it is full.
        String gate = Economy.heroSpawnGate(cfg.heroFarmPhaseOnly, stats.farmPhase(), hp, cfg.heroSpawnFloorHp, cfg.heroSpawnFloorMargin, cfg.heroMaxHp);
        if ("hold-farm".equals(gate)) {
            if (now - lastHoldLogAt > 5 * 60_000L) {
                lastHoldLogAt = now;
                log("hero_hold", "reason", "climb", "predictedHp", hp != null ? Num.r1(hp) : null,
                    "stage", stats.confirmedZoneLevel(), "expectedTop", stats.expectedTopStage());
            }
            nextCheckAt = now + 30_000;
            return false;
        }
        if ("hold-pool".equals(gate) && now - stats.hero.lastHpAt < 20 * 60_000L) {
            // The model says the server would refuse: wait for the floor, no menu open.
            schedule(now, "under-floor");
            return false;
        }
        visitStartedAt = now;
        heroSlot = -1; menuHp = null; menuMax = null;
        log("hero_visit_start", "via", nextVia, "targetHp", Math.round(targetHp), "predictedHp", hp != null ? Num.r1(hp) : null,
            "sinceDespawnMs", stats.hero.despawnedAt != 0 ? now - stats.hero.despawnedAt : null);
        flow.start(stepType, now);
        return true;
    }

    private void finish(MinecraftClient client, long now, String reason) {
        aborts.finish();
        log("hero_visit_end", "reason", reason, "hp", menuHp, "durationMs", now - visitStartedAt);
        flow.cancel();
        schedule(now, reason);
    }
}

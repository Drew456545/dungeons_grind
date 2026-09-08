package dev.drew.ycbotchallenge;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;

/**
 * Spends rebirth points (0.9.17). Each rebirth grants points that buy permanent
 * upgrades through the Rebirth GUI's nether star ("REBIRTH UPGRADES", lore
 * "Current Points: N") → "Upgrades" menu. Drew's order: enchant proc, damage,
 * essence, souls, each maxed before the next.
 *
 * A visit is a typed {@code /rebirth} a while after a rebirth (or once per
 * session after some kills, for points left over), a look at the star, a click,
 * a scan of the menu, then one click per purchase with a settle and a re-read in
 * between. The menu's tooltips are not captured yet, so the first visit logs
 * every item (rebirth_upgrade_menu) and the click is judged by whether the
 * item's tooltip changed: unchanged = nothing bought, stop. A sub-menu opening
 * from the click is logged and closed, never clicked into blind.
 *
 * <p>0.9.62: the visit is a {@link GuiFlow} script (wait_still, pause, type, gui_wait,
 * look, star_click, menu_wait, scan, click, after, close, close_return) with the shared
 * abort bookkeeping; the menu-timeout rule (jump to close, never abort) is the driver's
 * timeout hook. The abort cap is its own knob now - it read the enchanter's.
 */
public class RebirthUpgradeController extends BotModule implements Module {
    @Override public String name() { return "rebirth_upgrade"; }

    private final YCBotChallengeConfig cfg;
    private final StatsTracker stats;
    private final RebirthLore lore;
    private final ChatTyper typer;
    private final GuiFlow.Aborts aborts;
    private final GuiFlow flow;
    private GuiFlow.Step stepWaitStill;
    private GuiFlow.Step stepClose;
    private GuiFlow.Step stepScan;
    /** The scan's first look is the menu look; a re-read after a purchase is the shorter read beat. */
    private long scanDelayMs;

    private long visitStartedAt;
    private String visitVia = "rebirth";
    private long plannedAt = 0;
    private String planVia = null;
    private long revisitAt = 0;
    private int revisits = 0;
    private long lastRebirthSeen = -1;
    private boolean enableCheckDone = true;
    private long enabledAt;
    private int killsAtEnable;
    private int enableKillsNeeded;
    private long enableDelayMs;
    private Integer points = null;
    private int starSlot = -1;
    private int clicks;
    private boolean menuLogged;
    private RebirthLore.Item chosen;
    private int chosenSlot = -1;
    private List<String> chosenLoreBefore = List.of();

    public RebirthUpgradeController(YCBotChallengeConfig cfg, StatsTracker stats) {
        this.cfg = cfg;
        this.stats = stats;
        this.lore = new RebirthLore(cfg);
        this.typer = new ChatTyper(cfg);
        this.aborts = new GuiFlow.Aborts("rebirth_upgrade", () -> cfg.rebirthUpgradeMaxConsecutiveAborts, () -> 0L);
        this.flow = new GuiFlow(aborts, () -> cfg.rebirthUpgradeMaxMenuMs, "menu-timeout");
        this.flow.abortFields(() -> new Object[] {"clicks", clicks});
        this.flow.onAbort((client, why) -> {
            typer.cancel(client);
            if (isOurGui(client)) EnchantScreens.closeGui(client);
        });
        this.flow.onTimeout(c -> {
            String p = flow.phaseName();
            if ("close".equals(p) || "close_return".equals(p)) return null;
            log("rebirth_upgrade_skip", "reason", "menu-timeout", "phase", p);
            return stepClose;
        });
        buildSteps();
    }

    /** The visit script, last step first so every reference points backwards (the scan loop goes through a field). */
    private void buildSteps() {
        GuiFlow.Step closeReturn = GuiFlow.custom("close_return", c -> {
            if (rebirthGuiOpen(c.client)) {
                if (!c.armed()) { c.arm(GuiHuman.closeDelayMs(cfg)); return null; }
                if (!c.due()) return null;
                GuiHuman.close(c.client, "rebirth-upgrade", logger);
                log("rebirth_upgrade_close_return", "closed", true);
            } else if (c.sinceStep() < GuiFlow.SUBMENU_BEAT_MS) {
                return null;
            }
            return endVisit(c.now);
        });
        stepClose = GuiFlow.custom("close", c -> {
            if (isOurGui(c.client)) {
                if (!c.armed()) { c.arm(GuiHuman.closeDelayMs(cfg)); return null; }
                if (!c.due()) return null;
                boolean submenu = !rebirthGuiOpen(c.client);
                GuiHuman.close(c.client, "rebirth-upgrade", logger);
                if (submenu) {
                    // 0.9.54: Esc on the Upgrades menu brings the Rebirth GUI back (13 stray closes
                    // of it on 2026-09-06, 8 s idle each) - wait for it and close it too.
                    return closeReturn;
                }
            }
            return endVisit(c.now);
        });
        GuiFlow.Step after = GuiFlow.custom("after", c -> {
            if (!c.armed()) c.arm(HumanTiming.logNormalMs(cfg.rebirthUpgradeSettleMinMs, cfg.rebirthUpgradeSettleMaxMs));
            if (!c.due()) return null;
            if (!menuOpen(c.client)) return onMenuGone(c.client, c.now);
            List<String> loreAfter = List.of();
            for (GuiHuman.Item e : GuiHuman.items(c.client)) if (e.slot() == chosenSlot) { loreAfter = e.lore(); break; }
            if (!loreAfter.equals(chosenLoreBefore)) {
                RebirthLore.Item nowItem = lore.parse(chosen != null ? chosen.name() : "", loreAfter);
                if (chosen != null && chosen.cost() != null && points != null) points = Math.max(0, points - chosen.cost());
                log("rebirth_upgrade_bought", "name", chosen != null ? chosen.name() : null, "clicks", clicks,
                    "before", chosenLoreBefore, "after", loreAfter, "level", nowItem.level(), "pointsLeft", points);
                scanDelayMs = GuiHuman.readDelayMs(cfg);
                return stepScan;
            }
            log("rebirth_upgrade_stop", "reason", "no-change", "name", chosen != null ? chosen.name() : null,
                "lore", loreAfter, "points", points);
            return stepClose;
        });
        GuiFlow.Step click = GuiFlow.custom("click", c -> {
            if (!menuOpen(c.client)) return onMenuGone(c.client, c.now);
            if (!c.armed()) c.arm(GuiHuman.clickDelayMs(cfg));
            if (!c.due()) return null;
            GuiHuman.click(c.client, chosenSlot, "rebirth-upgrade", "upgrade:" + (chosen != null ? chosen.name() : "?"), logger);
            clicks++;
            log("rebirth_upgrade_click", "name", chosen != null ? chosen.name() : null, "slot", chosenSlot, "clicks", clicks);
            return after;
        });
        stepScan = GuiFlow.custom("scan", c -> {
            if (!menuOpen(c.client)) return onMenuGone(c.client, c.now);
            if (!c.armed()) c.arm(scanDelayMs);
            if (!c.due()) return null;
            List<GuiHuman.Item> entries = GuiHuman.items(c.client);
            List<RebirthLore.Item> items = new ArrayList<>();
            List<Integer> slots = new ArrayList<>();
            for (GuiHuman.Item e : entries) { items.add(lore.parse(e.name(), e.lore())); slots.add(e.slot()); }
            if (!menuLogged) {
                menuLogged = true;
                log("rebirth_upgrade_menu", "title", GuiHuman.title(c.client), "items", GuiHuman.describe(entries));
            }
            List<String> summaries = new ArrayList<>();
            for (RebirthLore.Item it : items) summaries.add(it.summary());
            log("rebirth_upgrade_scan", "items", summaries, "points", points, "clicks", clicks);
            if (clicks >= cfg.rebirthUpgradeMaxClicks) {
                log("rebirth_upgrade_skip", "reason", "click-cap", "clicks", clicks);
                return stepClose;
            }
            RebirthLore.Item pick = lore.choose(items, points);
            if (pick == null) {
                log("rebirth_upgrade_skip", "reason", "nothing-eligible", "points", points, "order", lore.order());
                stats.noteNothingEligible();
                return stepClose;
            }
            chosen = pick;
            chosenSlot = slots.get(items.indexOf(pick));
            chosenLoreBefore = new ArrayList<>(pick.lore());
            log("rebirth_upgrade_pick", "name", pick.name(), "slot", chosenSlot, "level", pick.level(),
                "maxLevel", pick.maxLevel(), "cost", pick.cost(), "points", points, "orderIndex", lore.orderIndex(pick));
            return click;
        });
        GuiFlow.Step menuWait = GuiFlow.custom("menu_wait", c -> {
            if (menuOpen(c.client)) {
                scanDelayMs = HumanTiming.logNormalMs(cfg.rebirthLookMinMs, cfg.rebirthLookMaxMs);
                return stepScan;
            }
            if (!c.armed()) c.arm(cfg.rebirthUpgradeOpenTimeoutMs);
            if (!c.due()) return null;
            if (rebirthGuiOpen(c.client)) {
                log("rebirth_upgrade_skip", "reason", "no-menu", "title", GuiHuman.title(c.client));
                return stepClose;
            }
            c.abort("gui-closed");
            return null;
        });
        GuiFlow.Step starClick = GuiFlow.custom("star_click", c -> {
            if (!rebirthGuiOpen(c.client)) { c.abort("gui-closed"); return null; }
            if (!c.armed()) c.arm(GuiHuman.clickDelayMs(cfg));
            if (!c.due()) return null;
            GuiHuman.click(c.client, starSlot, "rebirth-upgrade", "star", logger);
            log("rebirth_upgrade_star_click", "slot", starSlot);
            return menuWait;
        });
        GuiFlow.Step look = GuiFlow.look("look", c -> rebirthGuiOpen(c.client),
            () -> HumanTiming.logNormalMs(cfg.rebirthLookMinMs, cfg.rebirthLookMaxMs), "gui-closed", c -> {
                GuiHuman.Item star = null;
                List<GuiHuman.Item> entries = GuiHuman.items(c.client);
                for (GuiHuman.Item e : entries) if (lore.isStar(e.name(), e.lore())) { star = e; break; }
                if (star == null) {
                    log("rebirth_upgrade_skip", "reason", "no-star", "menuItems", GuiHuman.describe(entries));
                    return stepClose;
                }
                starSlot = star.slot();
                points = lore.points(star.lore());
                log("rebirth_points", "points", points, "slot", starSlot, "lore", star.lore(), "via", visitVia);
                if (points != null && points <= 0) {
                    stats.notePointsChecked();
                    log("rebirth_upgrade_skip", "reason", "no-points", "rebirths", stats.rebirths);
                    return stepClose;
                }
                return starClick;
            });
        GuiFlow.Step guiWait = GuiFlow.waitFor("gui_wait", c -> rebirthGuiOpen(c.client), () -> cfg.rebirthUpgradeOpenTimeoutMs, "no-gui", look);
        GuiFlow.Step type = GuiFlow.type("type", typer, () -> cfg.rebirthCommand, c -> isOurGui(c.client),
            c -> log("rebirth_upgrade_send", "command", cfg.rebirthCommand, "typos", typer.typos(), "via", visitVia), guiWait);
        GuiFlow.Step pause = GuiFlow.pause("pause", () -> HumanTiming.logNormalMs(cfg.upgradeStopPauseMinMs, cfg.upgradeStopPauseMaxMs), false, type);
        stepWaitStill = GuiFlow.waitStill("wait_still", c -> c.combat.isStationary(c.client), () -> 5000L, pause);
    }

    public boolean isBusy() { return flow.isBusy(); }

    /** 0.9.30 HUD chip: suspended after repeated aborts (toggle to reset). */
    public boolean isSuspended() { return aborts.suspended(); }

    /** The Rebirth GUI and its Upgrades menu are ours (or hand-opened), never a captcha. */
    public boolean isOurGui(MinecraftClient client) {
        String title = GuiHuman.title(client);
        return title != null && (RebirthScreens.isRebirthGui(title) || lore.isMenuTitle(title));
    }

    public String hudLine() {
        if (!cfg.rebirthUpgradesEnabled) return null;
        if (!flow.isBusy()) {
            if (aborts.suspended()) return "rebirth upgrades: suspended after repeated aborts (toggle to reset)";
            if (plannedAt != 0) return "rebirth upgrades: visit in " + Math.max(0, (plannedAt - System.currentTimeMillis() + 999) / 1000) + "s";
            return null;
        }
        return "rebirth upgrades: " + flow.phaseName()
            + (points != null ? "  pts " + points : "") + (clicks > 0 ? "  bought " + clicks : "");
    }

    /** Bot enabled: roll the once-per-session leftover-points check. */
    public void onEnable(long now, int kills) {
        enabledAt = now;
        killsAtEnable = kills;
        enableCheckDone = !cfg.rebirthUpgradeCheckOnEnable;
        enableKillsNeeded = HumanTiming.ticks(cfg.rebirthUpgradeEnableMinKillsMin,
            Math.max(cfg.rebirthUpgradeEnableMinKillsMin, cfg.rebirthUpgradeEnableMinKillsMax));
        enableDelayMs = HumanTiming.logNormalMs(cfg.rebirthUpgradeEnableDelayMinMs,
            Math.max(cfg.rebirthUpgradeEnableDelayMinMs + 1, cfg.rebirthUpgradeEnableDelayMaxMs));
        lastRebirthSeen = stats.lastRebirthAt;
        aborts.onEnable();
    }

    public void reset(MinecraftClient client) {
        if (client != null && flow.isBusy() && isOurGui(client)) EnchantScreens.closeGui(client);
        typer.cancel(client);
        flow.cancel();
        plannedAt = 0;
        revisitAt = 0;
        chosen = null;
    }

    /** @return true if combat should yield this tick. */
    public boolean tick(MinecraftClient client, CombatController combat) {
        if (!cfg.rebirthUpgradesEnabled || client.player == null) return false;
        long now = System.currentTimeMillis();
        if (!flow.isBusy()) return maybeStart(client, combat, now);
        combat.releaseKeys(client);
        return flow.tick(client, combat, now, logger);
    }

    private GuiFlow.Step endVisit(long now) {
        log("rebirth_upgrade_close", "clicks", clicks, "points", points, "visitMs", now - visitStartedAt, "via", visitVia);
        return GuiFlow.DONE;
    }

    /** The Upgrades menu vanished mid-visit: a sub-menu (logged, closed) or a server close after a purchase. */
    private GuiFlow.Step onMenuGone(MinecraftClient client, long now) {
        String title = GuiHuman.title(client);
        if (title != null && !rebirthGuiOpen(client)) {
            log("rebirth_upgrade_submenu", "title", title, "items", GuiHuman.describe(GuiHuman.items(client)),
                "after", chosen != null ? chosen.name() : null);
            EnchantScreens.closeGui(client);
        } else {
            log("rebirth_upgrade_gui_closed", "title", title, "after", chosen != null ? chosen.name() : null, "clicks", clicks);
        }
        if (revisits < 2) {
            revisits++;
            revisitAt = now + HumanTiming.logNormalMs(20_000, 90_000);
        }
        return stepClose;
    }

    private boolean maybeStart(MinecraftClient client, CombatController combat, long now) {
        if (aborts.suspended()) return false;
        long rb = stats.lastRebirthAt;
        if (rb != lastRebirthSeen) {
            lastRebirthSeen = rb;
            if (rb > 0) {
                revisits = 0;
                long delay = cfg.postRebirthSettleMaxMs
                    + HumanTiming.logNormalMs(cfg.rebirthUpgradeDelayMinMs, Math.max(cfg.rebirthUpgradeDelayMinMs + 1, cfg.rebirthUpgradeDelayMaxMs));
                plannedAt = now + delay;
                planVia = "rebirth";
                log("rebirth_upgrade_plan", "via", "rebirth", "delayMs", delay);
            }
        }
        if (plannedAt == 0 && revisitAt != 0 && now >= revisitAt) {
            revisitAt = 0;
            // 0.9.37: the revisit after a purchase read "no-points" on every 2026-09-04 rebirth
            // (the one point a rebirth grants was already spent); only revisit with points left.
            if (stats.pointsCheckedThisRebirth()) {
                log("rebirth_upgrade_skip", "reason", "revisit-no-points", "rebirths", stats.rebirths);
            } else {
                plannedAt = now;
                planVia = "revisit";
            }
        }
        if (plannedAt == 0 && !enableCheckDone
            && Economy.probeDue(combat.kills - killsAtEnable, enableKillsNeeded, now - enabledAt, enableDelayMs)) {
            enableCheckDone = true;
            if (stats.pointsCheckedThisRebirth() || stats.eligibleCheckedThisRebirth()) {
                // 0.9.30: 12 enable visits across two logs all read zero points with the
                // rebirth counter unchanged between them — points only come with a rebirth.
                // 0.9.54: the same for a read that found nothing eligible (18 of 21 enable visits).
                log("rebirth_upgrade_skip", "reason", "checked-this-rebirth", "rebirths", stats.rebirths,
                    "why", stats.pointsCheckedThisRebirth() ? "no-points" : "nothing-eligible");
            } else {
                plannedAt = now;
                planVia = "enable";
                log("rebirth_upgrade_plan", "via", "enable", "killsSinceEnable", combat.kills - killsAtEnable);
            }
        }
        if (plannedAt == 0 || now < plannedAt) return false;
        // Between fights, like a person: never mid-cook, never over another screen, never on a break (0.9.33).
        if (combat.isCooking() || client.currentScreen != null || combat.isOnBreak()) return false;
        plannedAt = 0;
        visitVia = planVia;
        visitStartedAt = now;
        points = null;
        clicks = 0;
        menuLogged = false;
        chosen = null;
        chosenSlot = -1;
        starSlot = -1;
        log("rebirth_upgrade_visit", "via", visitVia);
        combat.releaseKeys(client);
        MouseDriver.INSTANCE.cancel();
        flow.start(stepWaitStill, now);
        return true;
    }

    // ---------------------------------------------------------------- screens

    private static boolean rebirthGuiOpen(MinecraftClient client) {
        String t = GuiHuman.title(client);
        return t != null && client.currentScreen instanceof HandledScreen && RebirthScreens.isRebirthGui(t);
    }

    private boolean menuOpen(MinecraftClient client) {
        String t = GuiHuman.title(client);
        return t != null && client.currentScreen instanceof HandledScreen && lore.isMenuTitle(t);
    }
}

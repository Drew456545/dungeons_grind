package dev.drew.ycbotchallenge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;

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
 */
public class RebirthUpgradeController {
    private enum Phase { IDLE, WAIT_STILL, PAUSE, TYPE, GUI_WAIT, LOOK, STAR_CLICK, MENU_WAIT, SCAN, CLICK, AFTER, CLOSE }

    private record Entry(int slot, String name, List<String> lore) {}

    private final YCBotChallengeConfig cfg;
    private final StatsTracker stats;
    private final RebirthLore lore;
    private final ChatTyper typer;
    private EventLogger logger;

    private Phase phase = Phase.IDLE;
    private long phaseUntil;
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
    private int consecutiveAborts;
    private boolean suspended;

    public RebirthUpgradeController(YCBotChallengeConfig cfg, StatsTracker stats) {
        this.cfg = cfg;
        this.stats = stats;
        this.lore = new RebirthLore(cfg);
        this.typer = new ChatTyper(cfg);
    }

    public void setLogger(EventLogger logger) { this.logger = logger; }

    public boolean isBusy() { return phase != Phase.IDLE; }

    /** The Rebirth GUI and its Upgrades menu are ours (or hand-opened), never a captcha. */
    public boolean isOurGui(MinecraftClient client) {
        String title = title(client);
        return title != null && (RebirthScreens.isRebirthGui(title) || lore.isMenuTitle(title));
    }

    public String hudLine() {
        if (!cfg.rebirthUpgradesEnabled) return null;
        if (phase == Phase.IDLE) {
            if (suspended) return "rebirth upgrades: suspended after repeated aborts (toggle to reset)";
            if (plannedAt != 0) return "rebirth upgrades: visit in " + Math.max(0, (plannedAt - System.currentTimeMillis() + 999) / 1000) + "s";
            return null;
        }
        return "rebirth upgrades: " + phase.name().toLowerCase(Locale.ROOT)
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
        suspended = false;
        consecutiveAborts = 0;
    }

    public void reset(MinecraftClient client) {
        if (client != null && phase != Phase.IDLE && isOurGui(client)) EnchantScreens.closeGui(client);
        typer.cancel(client);
        phase = Phase.IDLE;
        plannedAt = 0;
        revisitAt = 0;
        chosen = null;
    }

    private void log(String type, Object... kv) {
        if (logger != null) logger.log(type, kv);
    }

    /** @return true if combat should yield this tick. */
    public boolean tick(MinecraftClient client, CombatController combat) {
        if (!cfg.rebirthUpgradesEnabled || client.player == null) return false;
        long now = System.currentTimeMillis();
        if (phase == Phase.IDLE) return maybeStart(client, combat, now);

        combat.releaseKeys(client);
        if (phase != Phase.CLOSE && now - visitStartedAt > cfg.rebirthUpgradeMaxMenuMs) {
            log("rebirth_upgrade_skip", "reason", "menu-timeout", "phase", phase.name().toLowerCase(Locale.ROOT));
            phase = Phase.CLOSE;
        }
        switch (phase) {
            case WAIT_STILL -> {
                if (combat.isStationary(client)) {
                    phase = Phase.PAUSE;
                    phaseUntil = now + HumanTiming.logNormalMs(cfg.upgradeStopPauseMinMs, cfg.upgradeStopPauseMaxMs);
                } else if (now >= phaseUntil) {
                    abort(client, "not-still");
                    return false;
                }
            }
            case PAUSE -> {
                if (now < phaseUntil) return true;
                if (client.currentScreen != null) { abort(client, "screen-open"); return false; }
                typer.begin(client, cfg.rebirthCommand, now);
                phase = Phase.TYPE;
            }
            case TYPE -> {
                ChatTyper.State s = typer.tick(client, now);
                if (s == ChatTyper.State.FAILED) { abort(client, typer.failReason()); return false; }
                if (s != ChatTyper.State.DONE) return true;
                log("rebirth_upgrade_send", "command", cfg.rebirthCommand, "typos", typer.typos(), "via", visitVia);
                phase = Phase.GUI_WAIT;
                phaseUntil = now + cfg.rebirthUpgradeOpenTimeoutMs;
            }
            case GUI_WAIT -> {
                if (rebirthGuiOpen(client)) {
                    phase = Phase.LOOK;
                    phaseUntil = now + HumanTiming.logNormalMs(cfg.rebirthLookMinMs, cfg.rebirthLookMaxMs);
                } else if (now >= phaseUntil) {
                    abort(client, "no-gui");
                    return false;
                }
            }
            case LOOK -> {
                if (!rebirthGuiOpen(client)) { abort(client, "gui-closed"); return false; }
                if (now < phaseUntil) return true;
                Entry star = null;
                List<Entry> entries = containerItems(client);
                for (Entry e : entries) if (lore.isStar(e.name(), e.lore())) { star = e; break; }
                if (star == null) {
                    log("rebirth_upgrade_skip", "reason", "no-star", "menuItems", describe(entries));
                    phase = Phase.CLOSE;
                    return true;
                }
                starSlot = star.slot();
                points = lore.points(star.lore());
                log("rebirth_points", "points", points, "slot", starSlot, "lore", star.lore(), "via", visitVia);
                if (points != null && points <= 0) {
                    log("rebirth_upgrade_skip", "reason", "no-points");
                    phase = Phase.CLOSE;
                    return true;
                }
                phase = Phase.STAR_CLICK;
                phaseUntil = now + HumanTiming.logNormalMs(250, 700);
            }
            case STAR_CLICK -> {
                if (!rebirthGuiOpen(client)) { abort(client, "gui-closed"); return false; }
                if (now < phaseUntil) return true;
                EnchantScreens.click(client, handler(client), starSlot);
                log("rebirth_upgrade_star_click", "slot", starSlot);
                phase = Phase.MENU_WAIT;
                phaseUntil = now + cfg.rebirthUpgradeOpenTimeoutMs;
            }
            case MENU_WAIT -> {
                if (menuOpen(client)) {
                    phase = Phase.SCAN;
                    phaseUntil = now + HumanTiming.logNormalMs(cfg.rebirthLookMinMs, cfg.rebirthLookMaxMs);
                } else if (now >= phaseUntil) {
                    if (rebirthGuiOpen(client)) {
                        log("rebirth_upgrade_skip", "reason", "no-menu", "title", title(client));
                        phase = Phase.CLOSE;
                    } else {
                        abort(client, "gui-closed");
                        return false;
                    }
                }
            }
            case SCAN -> {
                if (!menuOpen(client)) { onMenuGone(client, now); return true; }
                if (now < phaseUntil) return true;
                List<Entry> entries = containerItems(client);
                List<RebirthLore.Item> items = new ArrayList<>();
                List<Integer> slots = new ArrayList<>();
                for (Entry e : entries) { items.add(lore.parse(e.name(), e.lore())); slots.add(e.slot()); }
                if (!menuLogged) {
                    menuLogged = true;
                    log("rebirth_upgrade_menu", "title", title(client), "items", describe(entries));
                }
                List<String> summaries = new ArrayList<>();
                for (RebirthLore.Item it : items) summaries.add(it.summary());
                log("rebirth_upgrade_scan", "items", summaries, "points", points, "clicks", clicks);
                if (clicks >= cfg.rebirthUpgradeMaxClicks) {
                    log("rebirth_upgrade_skip", "reason", "click-cap", "clicks", clicks);
                    phase = Phase.CLOSE;
                    return true;
                }
                RebirthLore.Item pick = lore.choose(items, points);
                if (pick == null) {
                    log("rebirth_upgrade_skip", "reason", "nothing-eligible", "points", points, "order", lore.order());
                    phase = Phase.CLOSE;
                    return true;
                }
                chosen = pick;
                chosenSlot = slots.get(items.indexOf(pick));
                chosenLoreBefore = new ArrayList<>(pick.lore());
                log("rebirth_upgrade_pick", "name", pick.name(), "slot", chosenSlot, "level", pick.level(),
                    "maxLevel", pick.maxLevel(), "cost", pick.cost(), "points", points, "orderIndex", lore.orderIndex(pick));
                phase = Phase.CLICK;
                phaseUntil = now + HumanTiming.logNormalMs(300, 900);
            }
            case CLICK -> {
                if (!menuOpen(client)) { onMenuGone(client, now); return true; }
                if (now < phaseUntil) return true;
                EnchantScreens.click(client, handler(client), chosenSlot);
                clicks++;
                log("rebirth_upgrade_click", "name", chosen != null ? chosen.name() : null, "slot", chosenSlot, "clicks", clicks);
                phase = Phase.AFTER;
                phaseUntil = now + HumanTiming.logNormalMs(cfg.rebirthUpgradeSettleMinMs, cfg.rebirthUpgradeSettleMaxMs);
            }
            case AFTER -> {
                if (now < phaseUntil) return true;
                if (!menuOpen(client)) { onMenuGone(client, now); return true; }
                List<String> after = List.of();
                for (Entry e : containerItems(client)) if (e.slot() == chosenSlot) { after = e.lore(); break; }
                if (!after.equals(chosenLoreBefore)) {
                    RebirthLore.Item nowItem = lore.parse(chosen != null ? chosen.name() : "", after);
                    if (chosen != null && chosen.cost() != null && points != null) points = Math.max(0, points - chosen.cost());
                    log("rebirth_upgrade_bought", "name", chosen != null ? chosen.name() : null, "clicks", clicks,
                        "before", chosenLoreBefore, "after", after, "level", nowItem.level(), "pointsLeft", points);
                    phase = Phase.SCAN;
                    phaseUntil = now + HumanTiming.logNormalMs(400, 1100);
                } else {
                    log("rebirth_upgrade_stop", "reason", "no-change", "name", chosen != null ? chosen.name() : null,
                        "lore", after, "points", points);
                    phase = Phase.CLOSE;
                }
            }
            case CLOSE -> {
                if (isOurGui(client)) EnchantScreens.closeGui(client);
                log("rebirth_upgrade_close", "clicks", clicks, "points", points, "visitMs", now - visitStartedAt, "via", visitVia);
                consecutiveAborts = 0;
                phase = Phase.IDLE;
                return false;
            }
            default -> { }
        }
        return true;
    }

    /** The Upgrades menu vanished mid-visit: a sub-menu (logged, closed) or a server close after a purchase. */
    private void onMenuGone(MinecraftClient client, long now) {
        String title = title(client);
        if (title != null && !rebirthGuiOpen(client)) {
            log("rebirth_upgrade_submenu", "title", title, "items", describe(containerItems(client)),
                "after", chosen != null ? chosen.name() : null);
            EnchantScreens.closeGui(client);
        } else {
            log("rebirth_upgrade_gui_closed", "title", title, "after", chosen != null ? chosen.name() : null, "clicks", clicks);
        }
        if (revisits < 2) {
            revisits++;
            revisitAt = now + HumanTiming.logNormalMs(20_000, 90_000);
        }
        phase = Phase.CLOSE;
    }

    private boolean maybeStart(MinecraftClient client, CombatController combat, long now) {
        if (suspended) return false;
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
            plannedAt = now;
            planVia = "revisit";
        }
        if (plannedAt == 0 && !enableCheckDone
            && Economy.probeDue(combat.kills - killsAtEnable, enableKillsNeeded, now - enabledAt, enableDelayMs)) {
            enableCheckDone = true;
            plannedAt = now;
            planVia = "enable";
            log("rebirth_upgrade_plan", "via", "enable", "killsSinceEnable", combat.kills - killsAtEnable);
        }
        if (plannedAt == 0 || now < plannedAt) return false;
        // Between fights, like a person: never mid-cook, never over another screen.
        if (combat.isCooking() || client.currentScreen != null) return false;
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
        phase = Phase.WAIT_STILL;
        phaseUntil = now + 5000;
        return true;
    }

    private void abort(MinecraftClient client, String why) {
        log("rebirth_upgrade_abort", "reason", why, "phase", phase.name().toLowerCase(Locale.ROOT), "clicks", clicks);
        typer.cancel(client);
        if (isOurGui(client)) EnchantScreens.closeGui(client);
        phase = Phase.IDLE;
        if (++consecutiveAborts >= Math.max(1, cfg.enchantMaxConsecutiveAborts)) {
            suspended = true;
            log("rebirth_upgrade_suspended", "aborts", consecutiveAborts);
        }
    }

    // ---------------------------------------------------------------- screens

    private static String title(MinecraftClient client) {
        if (client.currentScreen == null || client.currentScreen.getTitle() == null) return null;
        return client.currentScreen.getTitle().getString();
    }

    private static boolean rebirthGuiOpen(MinecraftClient client) {
        String t = title(client);
        return t != null && client.currentScreen instanceof HandledScreen && RebirthScreens.isRebirthGui(t);
    }

    private boolean menuOpen(MinecraftClient client) {
        String t = title(client);
        return t != null && client.currentScreen instanceof HandledScreen && lore.isMenuTitle(t);
    }

    private static ScreenHandler handler(MinecraftClient client) {
        return client.currentScreen instanceof HandledScreen<?> hs ? hs.getScreenHandler() : null;
    }

    /** Non-empty container slots (player inventory excluded), in slot order. */
    private static List<Entry> containerItems(MinecraftClient client) {
        List<Entry> out = new ArrayList<>();
        ScreenHandler h = handler(client);
        if (h == null || h.slots == null) return out;
        int chestEnd = Math.max(0, h.slots.size() - 36);
        for (int i = 0; i < chestEnd; i++) {
            Slot slot = h.slots.get(i);
            if (slot == null) continue;
            ItemStack stack = slot.getStack();
            if (stack == null || stack.isEmpty()) continue;
            out.add(new Entry(slot.id, EnchantScreens.name(stack), EnchantScreens.loreLines(stack)));
        }
        return out;
    }

    private static List<String> describe(List<Entry> entries) {
        List<String> out = new ArrayList<>();
        for (Entry e : entries) out.add(e.slot() + ":" + e.name() + (e.lore().isEmpty() ? "" : " | " + String.join(" | ", e.lore())));
        return out;
    }
}

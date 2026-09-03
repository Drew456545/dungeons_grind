package dev.drew.ycbotchallenge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.MinecraftClient;
import net.minecraft.screen.ScreenHandler;

/**
 * Upgrades sword enchants during LONG kills. The server auto-attacks a tagged
 * mob until it dies, so a fresh-stage 50–120s kill is idle time: right-click
 * with the sword, walk the SOULS / ESSENCE / SHARDS tabs, and for the first
 * unlocked, non-maxed, affordable enchant in slot order open its "<Name>
 * Upgrade" GUI and click Max Upgrade. Then the next one, then the next tab.
 * No optimisation by design.
 *
 * Realism: one visit per qualifying kill, spaced by a log-normal gap, with a
 * random skip roll, and only when a currency actually grew since the last
 * visit. Every step waits a humanized pause. If the mob dies mid-visit the
 * purchase in flight completes and the menu closes; nothing is ever left open
 * while combat should be running.
 */
public class EnchantController {
    private enum Phase {
        IDLE, OPEN_WAIT, LOOK, TAB_CLICK, TAB_WAIT, SCAN, ENCHANT_CLICK, UPGRADE_WAIT,
        MAX_READ, MAX_CLICK, SETTLE, RETURN_WAIT, CLOSE
    }

    private final YCBotChallengeConfig cfg;
    private final StatsTracker stats;
    private final EnchantLore lore;
    private EventLogger logger;

    private Phase phase = Phase.IDLE;
    private long phaseUntil;
    private long visitStartedAt;
    private long lastVisitAt = 0;
    private long nextVisitGapMs = 0;
    private long decidedCookAt = -1;
    private final Map<String, Double> balAtLastVisit = new HashMap<>();
    private int tabIndex;
    private String currentTab;
    private final Set<String> attempted = new HashSet<>();
    private int scansThisTab;
    private EnchantLore.Item picked;
    private int pickedSlot = -1;
    private int maxSlot = -1;
    private EnchantLore.Item maxItem;
    private int buys;
    private final Map<String, Double> spent = new HashMap<>();
    private boolean reopened;
    private boolean wrapUp;
    private boolean useHeld;
    private int consecutiveAborts;
    private boolean suspended;
    private boolean firstTabDecided;
    private boolean firstTabSkipped;

    public EnchantController(YCBotChallengeConfig cfg, StatsTracker stats) {
        this.cfg = cfg;
        this.stats = stats;
        this.lore = new EnchantLore(cfg);
    }

    public void setLogger(EventLogger logger) { this.logger = logger; }

    public boolean isBusy() { return phase != Phase.IDLE; }

    public EnchantLore lore() { return lore; }

    /** A hand-opened enchanter (or its upgrade sub-GUI) must never be mistaken for a captcha. */
    public boolean isOurGui(MinecraftClient client) {
        EnchantScreens.Kind k = EnchantScreens.classify(client, lore);
        return k == EnchantScreens.Kind.ENCHANTER || k == EnchantScreens.Kind.UPGRADE;
    }

    public String hudLine() {
        if (!cfg.enchantsEnabled) return null;
        if (phase == Phase.IDLE) return suspended ? "enchant: suspended after repeated aborts (toggle to reset)" : null;
        return "enchant: " + phase.name().toLowerCase(Locale.ROOT)
            + (currentTab != null ? " " + currentTab : "") + (buys > 0 ? "  bought " + buys : "");
    }

    public void reset(MinecraftClient client) {
        if (client != null && phase != Phase.IDLE && isOurGui(client)) EnchantScreens.closeGui(client);
        if (client != null && useHeld) EnchantScreens.releaseUse(client);
        useHeld = false;
        phase = Phase.IDLE;
        picked = null;
        maxItem = null;
        suspended = false;
        consecutiveAborts = 0;
    }

    /** @return true if combat should yield this tick. */
    public boolean tick(MinecraftClient client, CombatController combat) {
        if (!cfg.enchantsEnabled || client.player == null) return false;
        long now = System.currentTimeMillis();
        if (useHeld) {
            EnchantScreens.releaseUse(client);
            useHeld = false;
        }
        if (phase == Phase.IDLE) return maybeStart(client, combat, now);

        combat.releaseKeys(client);
        if (!wrapUp && killEnding(combat)) {
            wrapUp = true;
            log("enchant_wrap_up", "phase", phase.name().toLowerCase(Locale.ROOT), "buys", buys);
        }
        if (phase != Phase.CLOSE && now - visitStartedAt > cfg.enchantMaxMenuMs) {
            log("enchant_skip", "reason", "menu-timeout", "phase", phase.name().toLowerCase(Locale.ROOT));
            phase = Phase.CLOSE;
        }

        switch (phase) {
            case OPEN_WAIT -> {
                EnchantScreens.Kind k = EnchantScreens.classify(client, lore);
                if (k == EnchantScreens.Kind.ENCHANTER) {
                    ScreenHandler h = EnchantScreens.handler(client);
                    log("enchant_menu_open", "reopened", reopened,
                        "items", EnchantScreens.items(h, lore).size(),
                        "tabs", tabsPresent(h));
                    phase = Phase.LOOK;
                    phaseUntil = now + HumanTiming.logNormalMs(cfg.enchantLookMinMs, cfg.enchantLookMaxMs);
                } else if (k == EnchantScreens.Kind.OTHER || k == EnchantScreens.Kind.UPGRADE) {
                    abort(client, "wrong-gui", false);
                } else if (now >= phaseUntil) {
                    abort(client, "no-gui", false);
                }
            }
            case LOOK -> {
                if (now < phaseUntil) return true;
                if (wrapUp) { phase = Phase.CLOSE; return true; }
                phase = Phase.TAB_CLICK;
            }
            case TAB_CLICK -> {
                if (wrapUp || tabIndex >= lore.tabs().size()) { phase = Phase.CLOSE; return true; }
                if (EnchantScreens.classify(client, lore) != EnchantScreens.Kind.ENCHANTER) {
                    onEnchanterGone(client, now);
                    return true;
                }
                ScreenHandler h = EnchantScreens.handler(client);
                currentTab = lore.tabs().get(tabIndex);
                // The enchanter opens on the first tab already; clicking it every visit is a tell.
                if (tabIndex == 0 && !firstTabDecided) {
                    firstTabDecided = true;
                    if (ThreadLocalRandom.current().nextDouble() < cfg.enchantSkipFirstTabChance) {
                        firstTabSkipped = true;
                        attempted.clear();
                        scansThisTab = 0;
                        log("enchant_tab", "tab", currentTab, "clicked", false);
                        phase = Phase.SCAN;
                        return true;
                    }
                }
                Integer slot = EnchantScreens.tabSlot(h, currentTab, lore);
                if (slot == null) {
                    log("enchant_skip", "reason", "tab-missing", "tab", currentTab);
                    tabIndex++;
                    return true;
                }
                EnchantScreens.click(client, h, slot);
                Double bal = stats.currency(currentTab);
                log("enchant_tab", "tab", currentTab, "slot", slot, "balance", bal != null ? Amounts.format(bal) : null);
                attempted.clear();
                scansThisTab = 0;
                phase = Phase.TAB_WAIT;
                phaseUntil = now + HumanTiming.logNormalMs(cfg.enchantTabSettleMinMs, cfg.enchantTabSettleMaxMs);
            }
            case TAB_WAIT -> {
                if (now < phaseUntil) return true;
                phase = Phase.SCAN;
            }
            case SCAN -> {
                if (wrapUp || buys >= cfg.enchantMaxBuysPerVisit) { phase = Phase.CLOSE; return true; }
                if (EnchantScreens.classify(client, lore) != EnchantScreens.Kind.ENCHANTER) {
                    onEnchanterGone(client, now);
                    return true;
                }
                ScreenHandler h = EnchantScreens.handler(client);
                List<EnchantScreens.SlotItem> slots = EnchantScreens.enchantItems(h, lore);
                if (firstTabSkipped && slots.isEmpty()) {
                    // Not on the first tab after all — click it.
                    firstTabSkipped = false;
                    phase = Phase.TAB_CLICK;
                    return true;
                }
                List<EnchantLore.Item> items = new ArrayList<>();
                for (EnchantScreens.SlotItem si : slots) items.add(si.item());
                Double bal = stats.currency(currentTab);
                if (scansThisTab++ == 0) {
                    List<String> summary = new ArrayList<>();
                    for (EnchantLore.Item it : items) summary.add(it.summary());
                    log("enchant_scan", "tab", currentTab, "balance", bal != null ? Amounts.format(bal) : null,
                        "count", items.size(), "items", summary);
                }
                EnchantLore.Item choice = EnchantLore.chooseEnchant(items, bal, attempted);
                if (choice == null) {
                    log("enchant_skip", "reason", "none-affordable", "tab", currentTab,
                        "balance", bal != null ? Amounts.format(bal) : null);
                    tabIndex++;
                    phase = Phase.TAB_CLICK;
                    return true;
                }
                picked = choice;
                pickedSlot = -1;
                for (EnchantScreens.SlotItem si : slots) if (si.item() == choice) { pickedSlot = si.slot(); break; }
                attempted.add(choice.name());
                log("enchant_pick", "tab", currentTab, "name", choice.name(), "slot", pickedSlot,
                    "level", choice.level(), "maxLevel", choice.maxLevel(),
                    "price", choice.price() != null ? Amounts.format(choice.price()) : null,
                    "balance", bal != null ? Amounts.format(bal) : null);
                phase = Phase.ENCHANT_CLICK;
                phaseUntil = now + HumanTiming.logNormalMs(250, 600);
            }
            case ENCHANT_CLICK -> {
                if (now < phaseUntil) return true;
                if (EnchantScreens.classify(client, lore) != EnchantScreens.Kind.ENCHANTER) {
                    onEnchanterGone(client, now);
                    return true;
                }
                EnchantScreens.click(client, EnchantScreens.handler(client), pickedSlot);
                phase = Phase.UPGRADE_WAIT;
                phaseUntil = now + cfg.enchantOpenTimeoutMs;
            }
            case UPGRADE_WAIT -> {
                EnchantScreens.Kind k = EnchantScreens.classify(client, lore);
                if (k == EnchantScreens.Kind.UPGRADE) {
                    phase = Phase.MAX_READ;
                    phaseUntil = now + HumanTiming.logNormalMs(300, 800);
                } else if (now >= phaseUntil) {
                    if (k == EnchantScreens.Kind.ENCHANTER) {
                        log("enchant_skip", "reason", "no-upgrade-gui", "name", picked != null ? picked.name() : null);
                        phase = Phase.SCAN;
                    } else {
                        onEnchanterGone(client, now);
                    }
                }
            }
            case MAX_READ -> {
                if (now < phaseUntil) return true;
                if (EnchantScreens.classify(client, lore) != EnchantScreens.Kind.UPGRADE) {
                    phase = Phase.RETURN_WAIT;
                    phaseUntil = now + 1500;
                    return true;
                }
                ScreenHandler h = EnchantScreens.handler(client);
                EnchantScreens.SlotItem mi = EnchantScreens.maxUpgradeItem(h, lore);
                Double bal = stats.currency(currentTab);
                if (mi == null) {
                    log("enchant_skip", "reason", "no-max-item", "name", picked != null ? picked.name() : null);
                    EnchantScreens.closeGui(client);
                    phase = Phase.RETURN_WAIT;
                    phaseUntil = now + 1500;
                    return true;
                }
                maxItem = mi.item();
                maxSlot = mi.slot();
                Integer levels = maxItem.maxLevels();
                Double price = maxItem.maxPrice();
                boolean affordable = levels != null && levels >= 1
                    && (price == null || bal == null || price <= bal + 1e-6);
                if (!affordable) {
                    log("enchant_skip", "reason", "max-unaffordable", "name", picked != null ? picked.name() : null,
                        "levels", levels, "price", price != null ? Amounts.format(price) : null,
                        "balance", bal != null ? Amounts.format(bal) : null);
                    EnchantScreens.closeGui(client);
                    phase = Phase.RETURN_WAIT;
                    phaseUntil = now + 1500;
                    return true;
                }
                phase = Phase.MAX_CLICK;
                phaseUntil = now + HumanTiming.logNormalMs(200, 500);
            }
            case MAX_CLICK -> {
                if (now < phaseUntil) return true;
                if (EnchantScreens.classify(client, lore) != EnchantScreens.Kind.UPGRADE) {
                    phase = Phase.RETURN_WAIT;
                    phaseUntil = now + 1500;
                    return true;
                }
                EnchantScreens.click(client, EnchantScreens.handler(client), maxSlot);
                buys++;
                Double price = maxItem != null ? maxItem.maxPrice() : null;
                String cur = maxItem != null && maxItem.currency() != null ? maxItem.currency() : currentTab;
                if (price != null) spent.merge(cur, price, Double::sum);
                Double bal = stats.currency(currentTab);
                log("enchant_upgrade", "tab", currentTab, "name", picked != null ? picked.name() : null,
                    "fromLevel", picked != null ? picked.level() : null,
                    "levels", maxItem != null ? maxItem.maxLevels() : null,
                    "price", price != null ? Amounts.format(price) : null, "currency", cur,
                    "balanceBefore", bal != null ? Amounts.format(bal) : null);
                phase = Phase.SETTLE;
                phaseUntil = now + HumanTiming.logNormalMs(cfg.enchantBuySettleMinMs, cfg.enchantBuySettleMaxMs);
            }
            case SETTLE -> {
                if (now < phaseUntil) return true;
                EnchantScreens.Kind k = EnchantScreens.classify(client, lore);
                if (k == EnchantScreens.Kind.UPGRADE) {
                    EnchantScreens.closeGui(client);
                    phase = Phase.RETURN_WAIT;
                    phaseUntil = now + 1500;
                } else if (k == EnchantScreens.Kind.ENCHANTER) {
                    phase = (wrapUp || buys >= cfg.enchantMaxBuysPerVisit) ? Phase.CLOSE : Phase.SCAN;
                } else {
                    onEnchanterGone(client, now);
                }
            }
            case RETURN_WAIT -> {
                EnchantScreens.Kind k = EnchantScreens.classify(client, lore);
                if (k == EnchantScreens.Kind.ENCHANTER) {
                    phase = (wrapUp || buys >= cfg.enchantMaxBuysPerVisit) ? Phase.CLOSE : Phase.SCAN;
                } else if (now >= phaseUntil) {
                    onEnchanterGone(client, now);
                }
            }
            case CLOSE -> {
                if (isOurGui(client)) EnchantScreens.closeGui(client);
                finish(client, now, "done");
                return false;
            }
            default -> { }
        }
        return true;
    }

    private boolean maybeStart(MinecraftClient client, CombatController combat, long now) {
        if (suspended || combat.isOnBreak() || client.currentScreen != null) return false;
        if (!combat.isCooking()) return false;
        long cookAt = combat.cookStartMs();
        if (cookAt == decidedCookAt) return false;
        Double eta = combat.currentEtaMs;
        if (eta == null || eta < cfg.enchantMinEtaMs) return false;
        if (combat.cookElapsedMs() < cfg.enchantCookSettleMs) return false;
        decidedCookAt = cookAt; // one decision per cook, whatever it is
        if (lastVisitAt > 0 && now - lastVisitAt < nextVisitGapMs) {
            log("enchant_skip", "reason", "cadence", "etaMs", Math.round(eta),
                "sinceLastMs", now - lastVisitAt, "gapMs", nextVisitGapMs);
            return false;
        }
        if (cfg.enchantSkipChance > 0 && ThreadLocalRandom.current().nextDouble() < cfg.enchantSkipChance) {
            log("enchant_skip", "reason", "rolled-skip", "etaMs", Math.round(eta));
            return false;
        }
        if (!balanceGrew()) {
            log("enchant_skip", "reason", "no-growth", "etaMs", Math.round(eta));
            return false;
        }
        if (!EnchantScreens.swordInHand(client, lore)) {
            log("enchant_skip", "reason", "no-sword", "etaMs", Math.round(eta));
            return false;
        }
        begin(client, now, eta);
        return true;
    }

    private void begin(MinecraftClient client, long now, double eta) {
        visitStartedAt = now;
        tabIndex = 0;
        currentTab = null;
        attempted.clear();
        scansThisTab = 0;
        buys = 0;
        spent.clear();
        reopened = false;
        wrapUp = false;
        picked = null;
        maxItem = null;
        firstTabDecided = false;
        firstTabSkipped = false;
        log("sword_lore", "lines", EnchantScreens.mainHandLore(client));
        log("enchant_visit_start", "etaMs", Math.round(eta), "balances", balancesNow());
        openMenu(client, now);
    }

    private void openMenu(MinecraftClient client, long now) {
        EnchantScreens.pressUse(client, cfg.enchantOpenViaInteract);
        useHeld = false; // press counter only — the key is never held
        phase = Phase.OPEN_WAIT;
        phaseUntil = now + cfg.enchantOpenTimeoutMs;
    }

    /** The enchanter is no longer on screen: reopen once per visit if there is work left, else finish. */
    private void onEnchanterGone(MinecraftClient client, long now) {
        EnchantScreens.Kind k = EnchantScreens.classify(client, lore);
        if (k == EnchantScreens.Kind.OTHER) {
            abort(client, "wrong-gui", false);
            return;
        }
        if (k == EnchantScreens.Kind.UPGRADE) {
            EnchantScreens.closeGui(client);
            phase = Phase.RETURN_WAIT;
            phaseUntil = now + 1500;
            return;
        }
        boolean workLeft = !wrapUp && buys < cfg.enchantMaxBuysPerVisit && tabIndex < lore.tabs().size();
        if (!reopened && workLeft && now - visitStartedAt < cfg.enchantMaxMenuMs) {
            reopened = true;
            log("enchant_reopen", "buys", buys, "tab", currentTab);
            openMenu(client, now);
            return;
        }
        finish(client, now, "closed-by-server");
    }

    private boolean killEnding(CombatController combat) {
        if (!combat.isCooking()) return true;
        Double eta = combat.liveEtaMs();
        return eta != null && eta < cfg.enchantWrapUpEtaMs;
    }

    private boolean balanceGrew() {
        if (balAtLastVisit.isEmpty()) return true;
        double mult = 1.0 + Math.max(0, cfg.enchantMinBalanceGrowthPct);
        for (String tab : lore.tabs()) {
            Double cur = stats.currency(tab);
            if (cur == null || cur <= 0) continue;
            Double prev = balAtLastVisit.get(tab);
            if (prev == null || cur >= prev * mult) return true;
        }
        return false;
    }

    private Map<String, String> balancesNow() {
        Map<String, String> out = new HashMap<>();
        for (String tab : lore.tabs()) {
            Double v = stats.currency(tab);
            if (v != null) out.put(tab, Amounts.format(v));
        }
        return out;
    }

    private List<String> tabsPresent(ScreenHandler h) {
        List<String> out = new ArrayList<>();
        for (EnchantScreens.SlotItem si : EnchantScreens.items(h, lore)) if (si.item().tab() != null) out.add(si.item().tab());
        return out;
    }

    private void finish(MinecraftClient client, long now, String reason) {
        Map<String, String> spentFmt = new HashMap<>();
        spent.forEach((k, v) -> spentFmt.put(k, Amounts.format(v)));
        log("enchant_menu_close", "reason", reason, "buys", buys, "spent", spentFmt,
            "durationMs", now - visitStartedAt, "balances", balancesNow());
        consecutiveAborts = 0;
        endVisit(client, now);
    }

    private void abort(MinecraftClient client, String why, boolean closeGui) {
        log("enchant_abort", "reason", why, "phase", phase.name().toLowerCase(Locale.ROOT), "buys", buys);
        if (closeGui || isOurGui(client)) EnchantScreens.closeGui(client);
        // A menu that never opens or keeps vanishing is a server/layout change, not
        // bad luck: stop trying until the bot is toggled, rather than right-clicking forever.
        if (++consecutiveAborts >= Math.max(1, cfg.enchantMaxConsecutiveAborts)) {
            suspended = true;
            log("enchant_suspended", "aborts", consecutiveAborts, "lastReason", why);
        }
        endVisit(client, now());
    }

    private void endVisit(MinecraftClient client, long now) {
        lastVisitAt = now;
        nextVisitGapMs = HumanTiming.logNormalMs(cfg.enchantVisitGapMinMs,
            Math.max(cfg.enchantVisitGapMinMs + 1, cfg.enchantVisitGapMaxMs));
        balAtLastVisit.clear();
        for (String tab : lore.tabs()) {
            Double v = stats.currency(tab);
            if (v != null) balAtLastVisit.put(tab, v);
        }
        if (useHeld) EnchantScreens.releaseUse(client);
        useHeld = false;
        phase = Phase.IDLE;
        picked = null;
        maxItem = null;
    }

    private static long now() { return System.currentTimeMillis(); }

    private void log(String type, Object... kv) {
        if (logger != null) logger.log(type, kv);
    }
}

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
import net.minecraft.util.hit.HitResult;

/**
 * Upgrades sword enchants during LONG kills. The server auto-attacks a tagged
 * mob until it dies, so a fresh-stage 50–120s kill is idle time: right-click
 * with the sword, walk the SOULS / ESSENCE / SHARDS tabs, and for a randomly
 * chosen unlocked, non-maxed, affordable enchant (equal chance each, 0.9.30)
 * open its "<Name> Upgrade" GUI and click Max Upgrade. Then another, then the
 * next tab. No optimisation by design.
 *
 * Realism: one visit per qualifying kill, spaced by a log-normal gap, with a
 * random skip roll, and only when a currency actually grew since the last
 * visit. Every step waits a humanized pause. If the mob dies mid-visit the
 * purchase in flight completes and the menu closes; nothing is ever left open
 * while combat should be running.
 */
public class EnchantController {
    private enum Phase {
        IDLE, OPEN_CLEAR, OPEN_WAIT, LOOK, TAB_CLICK, TAB_PRESS, TAB_WAIT, SCAN, ENCHANT_CLICK, UPGRADE_WAIT,
        MAX_READ, MAX_CLICK, SETTLE, RETURN_WAIT, PRESTIGE_CLICK, PRESTIGE_SETTLE,
        SWORDS_CLICK, SWORDS_PRESS, SWORDS_WAIT, SWORDS_READ, CLOSE
    }

    private final YCBotChallengeConfig cfg;
    private final StatsTracker stats;
    private final EnchantLore lore;
    /** 0.9.33: the Sword Skins menu reached from the enchanter's "Swords" item. */
    private final SwordSkinLore skins;
    private int swordsSlot = -1;
    private EventLogger logger;

    private Phase phase = Phase.IDLE;
    private long phaseUntil;
    private long visitStartedAt;
    private long lastVisitAt = 0;
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
    /** Per-tab state: whether we looked at what is showing, whether we clicked the button, purchases so far. */
    private boolean tabChecked;
    private boolean tabClicked;
    private int buysThisTab;
    /** "lull" (between kills) or "cook" (mid-fight) — only cook visits end with the mob. */
    private String visitVia = "lull";
    // Hazard trigger state.
    private int lastKillSeen = -1;
    private int lastZoneSeqSeen = -1;
    private long quietUntil = 0;
    private long lastQuietSkipLogAt = 0;
    /** Cheapest upgradable price seen per tab on the last scan — the affordability pull. */
    private final Map<String, Double> cheapestByTab = new HashMap<>();

    public EnchantController(YCBotChallengeConfig cfg, StatsTracker stats) {
        this.cfg = cfg;
        this.stats = stats;
        this.lore = new EnchantLore(cfg);
        this.skins = new SwordSkinLore(cfg);
    }

    public void setLogger(EventLogger logger) { this.logger = logger; }

    /** 0.9.37: the upgrade controller, so a visit never opens the enchanter over a buy that is decided or in flight. */
    private UpgradeController upgrades;
    public void attachUpgrades(UpgradeController u) { upgrades = u; }
    /**
     * 0.9.37: tabs every enchant of which is maxed (souls: 28/28 MAX, 163.87T idle, probed every
     * visit). 0.9.43: when it was seen so, not for good - a prestige or a newly unlocked enchant
     * shows up on exactly such a tab (enchantMaxedTabRescanMs; a sword-level line clears it).
     */
    private final Map<String, Long> maxedTabAt = new HashMap<>();
    private long lastBuyPendingSkipAt = 0;
    // 0.9.43: enchant prestige.
    private boolean prestigeMode = false;
    private EnchantLore.Prestige prestigeItem = null;
    private int prestigesThisEnchant = 0;
    private int prestigeOpensThisVisit = 0;
    private int prestigesThisVisit = 0;
    private int prestigesSession = 0;
    private long prestigeClickAt = 0;
    private final Set<String> upgradeGuiLogged = new HashSet<>();
    private final Map<String, Set<String>> lockedByTab = new HashMap<>();
    private int swordLevelSeqSeen = -1;
    private boolean unlockHint = false;
    private String lastPrestigeLine = null;

    public boolean isBusy() { return phase != Phase.IDLE; }

    /** 0.9.30 HUD chip: suspended after repeated aborts (toggle to reset). */
    public boolean isSuspended() { return suspended; }

    public EnchantLore lore() { return lore; }

    /** A hand-opened enchanter (or its upgrade sub-GUI) must never be mistaken for a captcha. */
    public boolean isOurGui(MinecraftClient client) {
        EnchantScreens.Kind k = EnchantScreens.classify(client, lore);
        return k == EnchantScreens.Kind.ENCHANTER || k == EnchantScreens.Kind.UPGRADE || skinsOpen(client);
    }

    /** The Sword Skins menu is showing: by title, or by content (two or more SWORD SKIN items). */
    private boolean skinsOpen(MinecraftClient client) {
        if (GuiHuman.handler(client) == null) return false;
        if (skins.isSkinsTitle(GuiHuman.title(client))) return true;
        return SwordSkinLore.looksLikeSkins(parseSkins(client));
    }

    private List<SwordSkinLore.Skin> parseSkins(MinecraftClient client) {
        List<SwordSkinLore.Skin> out = new ArrayList<>();
        for (GuiHuman.Item it : GuiHuman.items(client)) {
            SwordSkinLore.Skin sk = skins.parse(it.slot(), it.name(), it.lore());
            if (sk != null) out.add(sk);
        }
        return out;
    }

    /** Whether this visit ends with a look at the Sword Skins menu. */
    private boolean swordMenuDue() {
        if (!cfg.swordMenuScoutEnabled) return false;
        if (stats.swordTarget == null || stats.swordTargetPredicted || stats.swordTier == null) return true;
        return ThreadLocalRandom.current().nextDouble() < cfg.swordMenuScoutChance;
    }

    public String hudLine() {
        if (!cfg.enchantsEnabled) return null;
        if (phase == Phase.IDLE) return suspended ? "enchant: suspended after repeated aborts (toggle to reset)" : null;
        return "enchant: " + phase.name().toLowerCase(Locale.ROOT)
            + (currentTab != null ? " " + currentTab : "") + (buys > 0 ? "  bought " + buys : "")
            + (prestigesThisVisit > 0 ? "  prestiged " + prestigesThisVisit : "");
    }

    /** 0.9.43: the Y-screen line under "Enchant prestige". */
    public String prestigeHudLine() {
        if (!cfg.enchantPrestigeEnabled) return "off";
        String next = null;
        double best = Double.MAX_VALUE;
        Map<String, Double> bals = balancesMap();
        for (Map.Entry<String, EnchantLore.PrestigeState> e : stats.enchantPrestigeStates().entrySet()) {
            EnchantLore.PrestigeState s = e.getValue();
            if (s.cost() == null || EnchantLore.prestigeBlock(s, stats.rebirths, bals) != null) continue;
            if (s.cost() < best) { best = s.cost(); next = e.getKey() + " " + Amounts.format(s.cost()) + " " + s.currency(); }
        }
        return "prestiged " + prestigesSession + " this session" + (next != null ? " · next " + next : lastPrestigeLine != null ? " · " + lastPrestigeLine : "");
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
    /** Close beat (0.9.33): when the CLOSE phase will send Esc; 0 until armed. */
    private long closeAt = 0;
    private int pendingTabSlot = -1;

    public boolean tick(MinecraftClient client, CombatController combat) {
        if (!cfg.enchantsEnabled || client.player == null) return false;
        long now = System.currentTimeMillis();
        if (useHeld) {
            EnchantScreens.releaseUse(client);
            useHeld = false;
        }
        if (phase == Phase.IDLE) return maybeStart(client, combat, now);

        combat.releaseKeys(client);
        // Only a mid-cook visit ends with the mob; a between-kills visit has nothing cooking.
        if (!wrapUp && "cook".equals(visitVia) && killEnding(combat)) {
            wrapUp = true;
            log("enchant_wrap_up", "phase", phase.name().toLowerCase(Locale.ROOT), "buys", buys);
        }
        if (phase != Phase.CLOSE && now - visitStartedAt > cfg.enchantMaxMenuMs) {
            log("enchant_skip", "reason", "menu-timeout", "phase", phase.name().toLowerCase(Locale.ROOT));
            phase = Phase.CLOSE;
        }

        switch (phase) {
            case OPEN_CLEAR -> {
                if (now < phaseUntil || MouseDriver.INSTANCE.isBusy()) return true;
                pressUse(client, now);
            }
            case OPEN_WAIT -> {
                EnchantScreens.Kind k = EnchantScreens.classify(client, lore);
                if (k == EnchantScreens.Kind.ENCHANTER) {
                    ScreenHandler h = EnchantScreens.handler(client);
                    log("enchant_menu_open", "reopened", reopened,
                        "items", EnchantScreens.items(h, lore).size(),
                        "tabs", EnchantScreens.tabsPresent(h, lore),
                        "menuItems", reopened ? null : EnchantScreens.menuItems(h, lore));
                    phase = Phase.LOOK;
                    phaseUntil = now + HumanTiming.logNormalMs(cfg.enchantLookMinMs, cfg.enchantLookMaxMs);
                } else if (k == EnchantScreens.Kind.UPGRADE) {
                    abort(client, "wrong-gui", true);
                } else if (k == EnchantScreens.Kind.OTHER) {
                    // The enchanter's slots arrive a tick or two after its screen (16:31
                    // log: aborted "wrong-gui" 0.2s after the click and left the menu open,
                    // idling the bot until toggled). Unrecognised = not yet, until the timeout.
                    if (now >= phaseUntil) abort(client, "wrong-gui", true);
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
                if (wrapUp) { phase = Phase.CLOSE; return true; }
                if (tabIndex >= lore.tabs().size()) {
                    // Every tab seen: a look at the Sword Skins menu on the way out (0.9.33), or close.
                    phase = swordMenuDue() ? Phase.SWORDS_CLICK : Phase.CLOSE;
                    return true;
                }
                if (EnchantScreens.classify(client, lore) != EnchantScreens.Kind.ENCHANTER) {
                    onEnchanterGone(client, now);
                    return true;
                }
                ScreenHandler h = EnchantScreens.handler(client);
                currentTab = lore.tabs().get(tabIndex);
                Long maxedAt = maxedTabAt.get(currentTab);
                if (maxedAt != null && now - maxedAt < cfg.enchantMaxedTabRescanMs && !prestigeWorthOnTab(currentTab)) {
                    log("enchant_skip", "reason", "tab-maxed", "tab", currentTab, "ageMs", now - maxedAt);
                    nextTab();
                    return true;
                }
                // The server remembers the last tab, so first look at what is showing: a
                // person does not click a tab that is already selected. SCAN detects the
                // tab from the items' price currency and comes back here to click if needed.
                if (!tabClicked && !tabChecked) {
                    tabChecked = true;
                    attempted.clear();
                    scansThisTab = 0;
                    phase = Phase.SCAN;
                    return true;
                }
                Integer slot = EnchantScreens.tabSlot(h, currentTab, lore);
                if (slot == null) {
                    log("enchant_skip", "reason", "tab-missing", "tab", currentTab,
                        "tabsPresent", EnchantScreens.tabsPresent(h, lore));
                    nextTab();
                    return true;
                }
                // Notice the tab, then click it (0.9.33: this used to click in the same tick).
                pendingTabSlot = slot;
                phase = Phase.TAB_PRESS;
                phaseUntil = now + GuiHuman.clickDelayMs(cfg);
            }
            case TAB_PRESS -> {
                if (now < phaseUntil) return true;
                if (EnchantScreens.classify(client, lore) != EnchantScreens.Kind.ENCHANTER) {
                    onEnchanterGone(client, now);
                    return true;
                }
                GuiHuman.click(client, pendingTabSlot, "enchant", "tab:" + currentTab, logger);
                tabClicked = true;
                Double bal = stats.currency(currentTab);
                log("enchant_tab", "tab", currentTab, "slot", pendingTabSlot, "balance", bal != null ? Amounts.format(bal) : null);
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
                if (wrapUp) { phase = Phase.CLOSE; return true; }
                if (EnchantScreens.classify(client, lore) != EnchantScreens.Kind.ENCHANTER) {
                    onEnchanterGone(client, now);
                    return true;
                }
                ScreenHandler h = EnchantScreens.handler(client);
                List<EnchantScreens.SlotItem> slots = EnchantScreens.enchantItems(h, lore);
                List<EnchantLore.Item> items = new ArrayList<>();
                for (EnchantScreens.SlotItem si : slots) items.add(si.item());
                // Which tab is actually showing? Trust the items, never the assumption.
                String showing = EnchantLore.majorityCurrency(items);
                if (showing == null || !showing.equals(currentTab)) {
                    if (tabClicked) {
                        log("enchant_skip", "reason", "tab-mismatch", "tab", currentTab, "showing", showing);
                        nextTab();
                    } else {
                        log("enchant_tab_showing", "want", currentTab, "showing", showing);
                        phase = Phase.TAB_CLICK; // tabChecked is set, so this click happens
                    }
                    return true;
                }
                Map<String, Double> balances = balancesMap();
                Double bal = balances.get(currentTab);
                Double cheapest = null;
                for (EnchantLore.Item it : items) {
                    if (it.upgradable() && (cheapest == null || it.price() < cheapest)) cheapest = it.price();
                }
                if (cheapest != null) cheapestByTab.put(currentTab, cheapest); else cheapestByTab.remove(currentTab);
                if (scansThisTab++ == 0) {
                    List<String> summary = new ArrayList<>();
                    Set<String> lockedNow = new HashSet<>();
                    for (EnchantLore.Item it : items) { summary.add(it.summary()); if (it.locked()) lockedNow.add(it.name()); }
                    // 0.9.43: an enchant LOCKED at the last scan of this tab and not now has unlocked.
                    Set<String> lockedBefore = lockedByTab.get(currentTab);
                    List<String> unlocked = new ArrayList<>();
                    if (lockedBefore != null) for (String n : lockedBefore) if (!lockedNow.contains(n)) unlocked.add(n);
                    lockedByTab.put(currentTab, lockedNow);
                    log("enchant_scan", "tab", currentTab, "clicked", tabClicked,
                        "balance", bal != null ? Amounts.format(bal) : null,
                        "count", items.size(), "locked", lockedNow.size(), "items", summary);
                    if (!unlocked.isEmpty()) log("enchant_unlocked", "tab", currentTab, "names", unlocked, "swordLevel", stats.swordLevel);
                }
                double roll = ThreadLocalRandom.current().nextDouble();
                List<EnchantLore.Item> candidates = EnchantLore.enchantCandidates(items, balances, currentTab, attempted);
                EnchantLore.Item choice = EnchantLore.chooseEnchant(items, balances, currentTab, attempted, roll, cfg.enchantLagBias);
                if (choice == null) {
                    // 0.9.43: level buys are done on this tab - a maxed enchant's beacon next
                    // (Drew: levels first, then prestige; cheapest next prestige first).
                    EnchantLore.Item pp = cfg.enchantPrestigeEnabled && !wrapUp && prestigeOpensThisVisit < cfg.enchantPrestigeOpensPerVisit
                        ? EnchantLore.prestigePick(items, stats.enchantPrestigeStates(), stats.rebirths, balances, attempted) : null;
                    if (pp != null) {
                        picked = pp;
                        prestigeMode = true;
                        prestigeOpensThisVisit++;
                        prestigesThisEnchant = 0;
                        pickedSlot = -1;
                        for (EnchantScreens.SlotItem si : slots) if (si.item() == pp) { pickedSlot = si.slot(); break; }
                        attempted.add(pp.name());
                        EnchantLore.PrestigeState known = stats.enchantPrestigeState(pp.name());
                        log("enchant_pick", "tab", currentTab, "via", "prestige", "name", pp.name(), "slot", pickedSlot,
                            "level", pp.level(), "maxLevel", pp.maxLevel(),
                            "known", known != null ? known.level() + "/" + known.max() : null,
                            "knownCost", known != null && known.cost() != null ? Amounts.format(known.cost()) : null,
                            "balance", bal != null ? Amounts.format(bal) : null, "rebirths", stats.rebirths);
                        phase = Phase.ENCHANT_CLICK;
                        phaseUntil = now + GuiHuman.clickDelayMs(cfg);
                        return true;
                    }
                    // 0.9.37: "nothing affordable" and "nothing left to buy" are different states.
                    long enchants = items.stream().filter(EnchantLore.Item::isEnchant).count();
                    boolean allMaxed = enchants > 0 && items.stream().filter(EnchantLore.Item::isEnchant).allMatch(EnchantLore.Item::maxed);
                    if (allMaxed) maxedTabAt.put(currentTab, now);
                    log("enchant_skip", "reason", allMaxed ? "all-maxed" : "none-affordable", "tab", currentTab,
                        "balance", bal != null ? Amounts.format(bal) : null, "buysThisTab", buysThisTab);
                    nextTab();
                    return true;
                }
                prestigeMode = false;
                picked = choice;
                pickedSlot = -1;
                for (EnchantScreens.SlotItem si : slots) if (si.item() == choice) { pickedSlot = si.slot(); break; }
                attempted.add(choice.name());
                List<String> candidateNames = new ArrayList<>();
                for (EnchantLore.Item it : candidates) candidateNames.add(it.name());
                log("enchant_pick", "tab", currentTab, "name", choice.name(), "slot", pickedSlot,
                    "level", choice.level(), "maxLevel", choice.maxLevel(),
                    "price", choice.price() != null ? Amounts.format(choice.price()) : null,
                    "balance", bal != null ? Amounts.format(bal) : null,
                    "candidates", candidateNames, "roll", Math.round(roll * 1000) / 1000.0,
                    "lagBias", cfg.enchantLagBias);
                phase = Phase.ENCHANT_CLICK;
                phaseUntil = now + GuiHuman.clickDelayMs(cfg);
            }
            case ENCHANT_CLICK -> {
                if (now < phaseUntil) return true;
                if (EnchantScreens.classify(client, lore) != EnchantScreens.Kind.ENCHANTER) {
                    onEnchanterGone(client, now);
                    return true;
                }
                GuiHuman.click(client, pickedSlot, "enchant", "enchant:" + (picked != null ? picked.name() : "?"), logger);
                phase = Phase.UPGRADE_WAIT;
                phaseUntil = now + cfg.enchantOpenTimeoutMs;
            }
            case UPGRADE_WAIT -> {
                EnchantScreens.Kind k = EnchantScreens.classify(client, lore);
                if (k == EnchantScreens.Kind.UPGRADE) {
                    phase = Phase.MAX_READ;
                    phaseUntil = now + GuiHuman.readDelayMs(cfg);
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
                // 0.9.43: the menu's contents, once per enchant per visit (never dumped before
                // this; the beacon's slot and lore are read from here), and the beacon itself.
                String pname = picked != null ? picked.name() : "?";
                List<GuiHuman.Item> guiItems = GuiHuman.items(client);
                if (upgradeGuiLogged.add(pname)) {
                    log("enchant_upgrade_gui", "name", pname, "title", GuiHuman.title(client), "items", GuiHuman.describe(guiItems));
                }
                prestigeItem = findPrestige(guiItems);
                if (prestigeMode) {
                    String block = EnchantLore.prestigeBlock(prestigeItem, stats.rebirths,
                        prestigeItem != null && prestigeItem.currency() != null ? stats.currency(prestigeItem.currency()) : null);
                    if (prestigeItem != null) stats.rememberEnchantPrestige(pname, currentTab, prestigeItem);
                    log("enchant_prestige_read", "name", pname, "beacon", prestigeItem != null ? prestigeItem.summary() : null,
                        "slot", prestigeItem != null ? prestigeItem.slot() : null,
                        "level", prestigeItem != null ? prestigeItem.level() : null, "max", prestigeItem != null ? prestigeItem.max() : null,
                        "cost", prestigeItem != null && prestigeItem.cost() != null ? Amounts.format(prestigeItem.cost()) : null,
                        "currency", prestigeItem != null ? prestigeItem.currency() : null,
                        "rebirthReq", prestigeItem != null ? prestigeItem.rebirthReq() : null,
                        "multiplier", prestigeItem != null ? prestigeItem.multiplier() : null,
                        "rebirths", stats.rebirths,
                        "balance", prestigeItem != null && prestigeItem.currency() != null && stats.currency(prestigeItem.currency()) != null
                            ? Amounts.format(stats.currency(prestigeItem.currency())) : null,
                        "eligible", block == null, "why", block);
                    if (block != null) {
                        GuiHuman.close(client, "enchant", logger);
                        phase = Phase.RETURN_WAIT;
                        phaseUntil = now + 1500;
                        return true;
                    }
                    phase = Phase.PRESTIGE_CLICK;
                    phaseUntil = now + GuiHuman.clickDelayMs(cfg);
                    return true;
                }
                EnchantScreens.SlotItem mi = EnchantScreens.maxUpgradeItem(h, lore);
                if (mi == null) {
                    log("enchant_skip", "reason", "no-max-item", "name", picked != null ? picked.name() : null);
                    GuiHuman.close(client, "enchant", logger);
                    phase = Phase.RETURN_WAIT;
                    phaseUntil = now + 1500;
                    return true;
                }
                maxItem = mi.item();
                maxSlot = mi.slot();
                Integer levels = maxItem.maxLevels();
                Double price = maxItem.maxPrice();
                // The hopper's own price line names the currency (0.9.11 spent essence while
                // checking the souls balance because it trusted the assumed tab).
                String cur = maxItem.currency() != null ? maxItem.currency()
                    : (picked != null && picked.currency() != null ? picked.currency() : currentTab);
                Double bal = stats.currency(cur);
                boolean affordable = levels != null && levels >= 1
                    && (price == null || bal == null || price <= bal + 1e-6);
                if (!affordable) {
                    log("enchant_skip", "reason", "max-unaffordable", "name", picked != null ? picked.name() : null,
                        "levels", levels, "price", price != null ? Amounts.format(price) : null,
                        "currency", cur, "balance", bal != null ? Amounts.format(bal) : null);
                    GuiHuman.close(client, "enchant", logger);
                    phase = Phase.RETURN_WAIT;
                    phaseUntil = now + 1500;
                    return true;
                }
                phase = Phase.MAX_CLICK;
                phaseUntil = now + GuiHuman.clickDelayMs(cfg);
            }
            case MAX_CLICK -> {
                if (now < phaseUntil) return true;
                if (EnchantScreens.classify(client, lore) != EnchantScreens.Kind.UPGRADE) {
                    phase = Phase.RETURN_WAIT;
                    phaseUntil = now + 1500;
                    return true;
                }
                GuiHuman.click(client, maxSlot, "enchant", "max-upgrade", logger);
                buys++;
                buysThisTab++;
                Double price = maxItem != null ? maxItem.maxPrice() : null;
                String cur = maxItem != null && maxItem.currency() != null ? maxItem.currency()
                    : (picked != null && picked.currency() != null ? picked.currency() : currentTab);
                if (price != null) spent.merge(cur, price, Double::sum);
                Double bal = stats.currency(cur);
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
                    GuiHuman.close(client, "enchant", logger);
                    phase = Phase.RETURN_WAIT;
                    phaseUntil = now + 1500;
                } else if (k == EnchantScreens.Kind.ENCHANTER) {
                    phase = wrapUp ? Phase.CLOSE : Phase.SCAN;
                } else {
                    onEnchanterGone(client, now);
                }
            }
            case RETURN_WAIT -> {
                EnchantScreens.Kind k = EnchantScreens.classify(client, lore);
                if (k == EnchantScreens.Kind.ENCHANTER) {
                    phase = wrapUp ? Phase.CLOSE : Phase.SCAN;
                } else if (now >= phaseUntil) {
                    onEnchanterGone(client, now);
                }
            }
            case PRESTIGE_CLICK -> {
                if (now < phaseUntil) return true;
                if (EnchantScreens.classify(client, lore) != EnchantScreens.Kind.UPGRADE || prestigeItem == null) {
                    phase = Phase.RETURN_WAIT;
                    phaseUntil = now + 1500;
                    return true;
                }
                GuiHuman.click(client, prestigeItem.slot(), "enchant", "prestige", logger);
                prestigeClickAt = now;
                log("enchant_prestige_click", "name", picked != null ? picked.name() : null, "slot", prestigeItem.slot(),
                    "from", prestigeItem.level(), "cost", prestigeItem.cost() != null ? Amounts.format(prestigeItem.cost()) : null,
                    "currency", prestigeItem.currency(), "n", prestigesThisEnchant + 1);
                phase = Phase.PRESTIGE_SETTLE;
                phaseUntil = now + HumanTiming.logNormalMs(cfg.enchantBuySettleMinMs, cfg.enchantBuySettleMaxMs);
            }
            case PRESTIGE_SETTLE -> {
                if (now < phaseUntil) return true;
                EnchantLore.Prestige before = prestigeItem;
                boolean chat = stats.lastEnchantPrestigeAt >= prestigeClickAt;
                EnchantScreens.Kind k = EnchantScreens.classify(client, lore);
                EnchantLore.Prestige after = k == EnchantScreens.Kind.UPGRADE ? findPrestige(GuiHuman.items(client)) : null;
                boolean rose = after != null && after.level() != null && before != null && before.level() != null && after.level() > before.level();
                String pname = picked != null ? picked.name() : null;
                if (rose || chat) {
                    prestigesThisEnchant++;
                    prestigesThisVisit++;
                    prestigesSession++;
                    Double cost = before != null ? before.cost() : null;
                    String cur = before != null ? before.currency() : null;
                    if (chat && stats.lastEnchantPrestigeCost != null) cost = stats.lastEnchantPrestigeCost;
                    if (cost != null && cur != null) spent.merge(cur, cost, Double::sum);
                    lastPrestigeLine = pname + " -> " + (after != null && after.level() != null ? after.level() : before != null && before.level() != null ? before.level() + 1 : "?");
                    log("enchant_prestige", "name", pname, "from", before != null ? before.level() : null,
                        "to", after != null ? after.level() : null, "cost", cost != null ? Amounts.format(cost) : null, "currency", cur,
                        "chat", chat, "chatName", chat ? stats.lastEnchantPrestigeName : null,
                        "next", after != null ? after.summary() : null, "n", prestigesThisEnchant);
                    if (after != null) stats.rememberEnchantPrestige(pname, currentTab, after);
                } else {
                    log("enchant_prestige_stop", "reason", after == null ? "gui-gone" : "no-change", "name", pname,
                        "level", after != null ? after.level() : null, "n", prestigesThisEnchant);
                    if (after != null) stats.rememberEnchantPrestige(pname, currentTab, after);
                }
                if (k != EnchantScreens.Kind.UPGRADE) {
                    phase = Phase.RETURN_WAIT;
                    phaseUntil = now + 1500;
                    return true;
                }
                String block = after == null ? "no-item" : EnchantLore.prestigeBlock(after, stats.rebirths,
                    after.currency() != null ? stats.currency(after.currency()) : null);
                if ((rose || chat) && block == null && prestigesThisEnchant < cfg.enchantPrestigeMaxPerVisit && !wrapUp) {
                    prestigeItem = after;
                    phase = Phase.PRESTIGE_CLICK;
                    phaseUntil = now + GuiHuman.clickDelayMs(cfg);
                    return true;
                }
                GuiHuman.close(client, "enchant", logger);
                phase = Phase.RETURN_WAIT;
                phaseUntil = now + 1500;
            }
            case SWORDS_CLICK -> {
                if (wrapUp) { phase = Phase.CLOSE; return true; }
                if (EnchantScreens.classify(client, lore) != EnchantScreens.Kind.ENCHANTER) { phase = Phase.CLOSE; return true; }
                swordsSlot = -1;
                List<GuiHuman.Item> items = GuiHuman.items(client);
                for (GuiHuman.Item it : items) {
                    if (skins.isSwordsButton(it.name(), it.lore())) { swordsSlot = it.slot(); break; }
                }
                if (swordsSlot < 0) {
                    log("sword_menu_skip", "reason", "no-button", "menuItems", GuiHuman.describe(items));
                    phase = Phase.CLOSE;
                    return true;
                }
                phase = Phase.SWORDS_PRESS;
                phaseUntil = now + GuiHuman.clickDelayMs(cfg);
            }
            case SWORDS_PRESS -> {
                if (now < phaseUntil) return true;
                if (EnchantScreens.classify(client, lore) != EnchantScreens.Kind.ENCHANTER) { phase = Phase.CLOSE; return true; }
                GuiHuman.click(client, swordsSlot, "enchant", "swords", logger);
                phase = Phase.SWORDS_WAIT;
                phaseUntil = now + cfg.enchantOpenTimeoutMs;
            }
            case SWORDS_WAIT -> {
                if (skinsOpen(client)) {
                    phase = Phase.SWORDS_READ;
                    phaseUntil = now + GuiHuman.lookDelayMs(cfg, "enchant");
                } else if (now >= phaseUntil) {
                    log("sword_menu_skip", "reason", "timeout", "title", GuiHuman.title(client),
                        "kind", EnchantScreens.classify(client, lore).name().toLowerCase(Locale.ROOT));
                    phase = Phase.CLOSE;
                }
            }
            case SWORDS_READ -> {
                if (now < phaseUntil) return true;
                if (!skinsOpen(client)) {
                    log("sword_menu_skip", "reason", "gone", "title", GuiHuman.title(client));
                    phase = Phase.CLOSE;
                    return true;
                }
                List<SwordSkinLore.Skin> all = parseSkins(client);
                SwordSkinLore.Skin eq = SwordSkinLore.equipped(all);
                SwordSkinLore.Skin next = SwordSkinLore.nextBuy(all);
                boolean promotion = next != null && eq != null && next != eq;
                log("sword_menu", "title", GuiHuman.title(client), "skins", all.size(),
                    "equipped", eq != null ? eq.name() : null, "tier", eq != null ? eq.tier() : null,
                    "tierMax", eq != null ? eq.tierMax() : null,
                    "damage", eq != null && eq.damage() != null ? Amounts.format(eq.damage()) : null,
                    "damageRaw", eq != null ? eq.damageRaw() : null,
                    "nextPrice", next != null && next.price() != null ? Amounts.format(next.price()) : null,
                    "nextPriceRaw", next != null ? next.priceRaw() : null, "nextSkin", next != null ? next.name() : null,
                    "promotion", promotion ? true : null, "items", SwordSkinLore.summaries(all));
                stats.onSwordMenu(next != null ? next.price() : null, next != null ? next.priceRaw() : null,
                    eq != null ? eq.tier() : null, eq != null ? eq.tierMax() : null, eq != null ? eq.name() : null, promotion, now);
                phase = Phase.CLOSE;
            }
            case CLOSE -> {
                // Done with the menu: a beat, then Esc (0.9.33; it used to close instantly).
                if (isOurGui(client)) {
                    if (closeAt == 0) { closeAt = now + GuiHuman.closeDelayMs(cfg); return true; }
                    if (now < closeAt) return true;
                    GuiHuman.close(client, "enchant", logger);
                }
                finish(client, now, "done");
                return false;
            }
            default -> { }
        }
        return true;
    }

    /**
     * Hazard trigger (0.9.11). Two moments a person opens the enchanter: the lull
     * right after a kill, and the free time mid-way through a long cook. Both roll
     * against the same hazard — a ramp over time since the last visit, pulled up
     * when the sidebar shows the cheapest thing seen last time is affordable,
     * with a bonus mid-cook. Visits are decorrelated from zone advances by a
     * random quiet window, and one in ten happens out of curiosity with nothing
     * to buy. Replaces the 0.9.9 "45s mob and 3 minutes" gate, whose visits only
     * ever clustered right after zone advances.
     */
    private boolean maybeStart(MinecraftClient client, CombatController combat, long now) {
        if (suspended || combat.isOnBreak() || client.currentScreen != null) return false;
        if (lastVisitAt == 0) lastVisitAt = now; // session start counts as a visit for the ramp
        // 0.9.37: a zone/sword buy is decided or typing - the enchanter would steal the chat
        // (2026-09-04 19:24:38: upgrade_abort chat-closed kind=zone inside the lvl4 leg).
        if (upgrades != null && (upgrades.isBusy() || upgrades.hasPendingDecision())) {
            if (now - lastBuyPendingSkipAt > 30_000) {
                lastBuyPendingSkipAt = now;
                log("enchant_skip", "reason", "buy-pending");
            }
            return false;
        }
        int zseq = stats.zoneChangeSeq();
        if (zseq != lastZoneSeqSeen) {
            lastZoneSeqSeen = zseq;
            quietUntil = now + HumanTiming.logNormalMs(cfg.enchantPostZoneQuietMinMs,
                Math.max(cfg.enchantPostZoneQuietMinMs + 1, cfg.enchantPostZoneQuietMaxMs));
        }
        String via;
        double bonus;
        Double eta = combat.currentEtaMs;
        if (combat.isCooking()) {
            long cookAt = combat.cookStartMs();
            if (cookAt == decidedCookAt) return false;
            if (eta == null || eta < cfg.enchantCookMinEtaMs) return false;
            if (combat.cookElapsedMs() < cfg.enchantCookSettleMs) return false;
            decidedCookAt = cookAt; // one roll per cook, whatever it is
            via = "cook";
            bonus = cfg.enchantHazardCookBonus;
        } else {
            if (combat.kills == lastKillSeen) return false;
            lastKillSeen = combat.kills; // one roll per lull
            via = "lull";
            bonus = 1.0;
        }
        if (now < quietUntil) {
            if (now - lastQuietSkipLogAt > 30_000) {
                lastQuietSkipLogAt = now;
                log("enchant_skip", "reason", "quiet-after-zone", "via", via, "quietLeftMs", quietUntil - now);
            }
            return false;
        }
        // 0.9.43: a sword level came in - an enchant may have unlocked: every tab is scanned
        // again on the next visit, and that visit does not wait for the hazard or the growth.
        if (stats.swordLevelSeq != swordLevelSeqSeen) {
            boolean first = swordLevelSeqSeen == -1;
            swordLevelSeqSeen = stats.swordLevelSeq;
            if (!first) { unlockHint = true; maxedTabAt.clear(); log("enchant_unlock_hint", "swordLevel", stats.swordLevel); }
        }
        double pull = affordPull();
        double hazard = Economy.visitHazard(now - lastVisitAt, cfg.enchantHazardRampStartMs, cfg.enchantHazardRampFullMs,
            cfg.enchantHazardFullChance, pull, bonus);
        if (unlockHint) { via = "unlock"; hazard = 1.0; }
        if (ThreadLocalRandom.current().nextDouble() >= hazard) return false;
        boolean curiosity = false;
        if (!unlockHint && !balanceGrew()) {
            if (ThreadLocalRandom.current().nextDouble() >= cfg.enchantCuriosityChance) {
                log("enchant_skip", "reason", "no-growth", "via", via, "hazard", Math.round(hazard * 1000) / 1000.0);
                return false;
            }
            curiosity = true;
        }
        if (!EnchantScreens.swordInHand(client, lore)) {
            log("enchant_skip", "reason", "no-sword", "via", via);
            return false;
        }
        begin(client, now, via, eta, hazard, pull, curiosity);
        return true;
    }

    /** Strongest pull across tabs: balance over the cheapest upgradable price seen on the last scan. */
    private double affordPull() {
        double best = 1.0;
        for (String tab : lore.tabs()) {
            double p = Economy.affordPull(stats.currency(tab), cheapestByTab.get(tab), cfg.enchantHazardPullMaxMult);
            if (p > best) best = p;
        }
        return best;
    }

    private void begin(MinecraftClient client, long now, String via, Double eta, double hazard, double pull, boolean curiosity) {
        visitStartedAt = now;
        closeAt = 0;
        pendingTabSlot = -1;
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
        visitVia = via;
        buysThisTab = 0;
        prestigeMode = false;
        prestigeItem = null;
        prestigesThisEnchant = 0;
        prestigeOpensThisVisit = 0;
        prestigesThisVisit = 0;
        upgradeGuiLogged.clear();
        unlockHint = false;
        log("sword_lore", "lines", EnchantScreens.mainHandLore(client));
        log("enchant_visit_start", "via", via, "etaMs", eta != null ? Math.round(eta) : null,
            "sinceLastVisitMs", now - lastVisitAt, "hazard", Math.round(hazard * 1000) / 1000.0,
            "pull", Math.round(pull * 100) / 100.0, "curiosity", curiosity,
            "balances", balancesNow());
        openMenu(client, now);
    }

    /**
     * 0.9.30: 7 of 22 visits aborted "no-gui" and every one began the tick a kill landed —
     * the dying mob was still under the crosshair for its death animation, so the use-key
     * interacted with it instead of the sword. An entity under the crosshair gets a glance
     * up first (enchantOpenClearPitchDeg); enchant_open records what was there either way.
     */
    private void openMenu(MinecraftClient client, long now) {
        HitResult hit = client.crosshairTarget;
        String target = hit == null || hit.getType() == HitResult.Type.MISS ? "none"
            : hit.getType() == HitResult.Type.ENTITY ? "entity" : "block";
        boolean clear = "entity".equals(target) && cfg.enchantOpenClearPitchDeg > 0 && client.player != null;
        log("enchant_open", "target", target, "cleared", clear, "reopen", reopened);
        if (clear) {
            float pitch = Math.max(-90f, client.player.getPitch() - cfg.enchantOpenClearPitchDeg);
            MouseDriver.INSTANCE.cancel();
            MouseDriver.INSTANCE.lookTo(client, client.player.getYaw(), pitch, "enchant-open-clear");
            phase = Phase.OPEN_CLEAR;
            phaseUntil = now + HumanTiming.logNormalMs(250, 550);
            return;
        }
        pressUse(client, now);
    }

    private void pressUse(MinecraftClient client, long now) {
        EnchantScreens.pressUse(client, cfg.enchantOpenViaInteract);
        useHeld = false; // press counter only — the key is never held
        tabChecked = false; // a (re)opened menu shows whatever tab was last used — look first
        tabClicked = false;
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
        boolean workLeft = !wrapUp && tabIndex < lore.tabs().size();
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

    private Map<String, Double> balancesMap() {
        Map<String, Double> out = new HashMap<>();
        for (String tab : lore.tabs()) {
            Double v = stats.currency(tab);
            if (v != null) out.put(tab, v);
        }
        return out;
    }

    /** Move on to the next tab: fresh per-tab state, no click yet. */
    private void nextTab() {
        tabIndex++;
        tabClicked = false;
        tabChecked = false;
        buysThisTab = 0;
        attempted.clear();
        scansThisTab = 0;
        phase = Phase.TAB_CLICK;
    }

    private void finish(MinecraftClient client, long now, String reason) {
        Map<String, String> spentFmt = new HashMap<>();
        spent.forEach((k, v) -> spentFmt.put(k, Amounts.format(v)));
        log("enchant_menu_close", "reason", reason, "buys", buys, "prestiges", prestigesThisVisit, "spent", spentFmt,
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

    /** 0.9.43: the beacon among an Upgrade menu's items, or null. */
    private EnchantLore.Prestige findPrestige(List<GuiHuman.Item> items) {
        for (GuiHuman.Item it : items) {
            EnchantLore.Prestige p = lore.parsePrestige(it.slot(), it.name(), it.lore());
            if (p != null) return p;
        }
        return null;
    }

    /**
     * 0.9.43: a maxed tab is still worth a scan when a remembered beacon on it could be
     * clicked now (the floor met, the cost covered) - or when nothing on it has been read yet.
     */
    private boolean prestigeWorthOnTab(String tab) {
        if (!cfg.enchantPrestigeEnabled) return false;
        Map<String, Double> bals = balancesMap();
        boolean anyOnTab = false;
        for (EnchantLore.PrestigeState s : stats.enchantPrestigeStates().values()) {
            if (s.tab() == null || !s.tab().equals(tab)) continue;
            anyOnTab = true;
            if (EnchantLore.prestigeBlock(s, stats.rebirths, bals) == null) return true;
        }
        return !anyOnTab;
    }

    private static long now() { return System.currentTimeMillis(); }

    private void log(String type, Object... kv) {
        if (logger != null) logger.log(type, kv);
    }
}

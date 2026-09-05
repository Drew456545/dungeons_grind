package dev.drew.ycbotchallenge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure parsing of the SWORD ENCHANTER item tooltips (lore) — no Minecraft types,
 * unit-tested in EconomyChecks against the captured lines.
 *
 * Every enchant is listed in the enchanter whether owned or not:
 * <pre>
 *   Rage Enchant            | Level: 0 / 100      | Price: 20,000,000 Souls   LOCKED (Requires Sword Level 50)
 *   Soul Magnet Enchant     | Level: 1,321 / 2,000| Price: 7,105,000 Souls    [CLICK HERE TO UPGRADE THIS ENCHANT]
 *   Essence Greed Enchant   | Level: 1,000 / 1,000                            (maxed)
 *   Max Upgrade (hopper, in the "<Name> Upgrade" sub-GUI)  * Levels: 1  * Price: 7,730,000 Souls
 * </pre>
 * The enchanter's own screen title is formatting-only (a font glyph draws the
 * words), so the GUI is recognised by its contents: any slot whose lore carries
 * the signature line ("ACTIVATION CHANCE: …"), or three or more level items.
 * All strings must be run through {@link SidebarParser#strip} first (§ codes and
 * unicode small caps) — callers do that; this class is case-insensitive.
 */
public final class EnchantLore {

    /** One container item, parsed. {@code lore} is the stripped tooltip. */
    public record Item(
            String name, List<String> lore,
            Integer level, Integer maxLevel, Double price, String currency,
            boolean locked, boolean signature, String tab,
            boolean maxUpgrade, Integer maxLevels, Double maxPrice) {

        public boolean isEnchant() { return level != null && maxLevel != null; }
        public boolean maxed() { return isEnchant() && level >= maxLevel; }
        /** Not locked, not maxed, price known — the only thing the bot ever clicks. */
        public boolean upgradable() { return isEnchant() && !locked && !maxed() && price != null; }

        /** Compact one-line form for the enchant_scan log. */
        public String summary() {
            StringBuilder sb = new StringBuilder(name);
            if (isEnchant()) sb.append(' ').append(level).append('/').append(maxLevel);
            if (price != null) sb.append(' ').append(Amounts.format(price)).append(' ').append(currency);
            if (locked) sb.append(" LOCKED");
            else if (maxed()) sb.append(" MAX");
            return sb.toString();
        }
    }

    /**
     * 0.9.43: the "Enchant Prestige" beacon of an Upgrade menu. {@code level}/{@code max} =
     * "Prestige: 6 / 10", {@code cost} in {@code currency} = "Cost: 2.5T Souls",
     * {@code rebirthReq} = "Rebirth: 21", {@code multiplier} = "Multiplier: 13.30x" (evidence).
     */
    public record Prestige(int slot, Integer level, Integer max, Double cost, String currency,
                           Integer rebirthReq, Double multiplier) {
        public boolean maxedOut() { return level != null && max != null && level >= max; }
        public String summary() {
            return "prestige " + level + "/" + max + (cost != null ? " " + Amounts.format(cost) + " " + currency : "")
                + (rebirthReq != null ? " rb>=" + rebirthReq : "") + (multiplier != null ? " " + multiplier + "x" : "");
        }
    }

    /** 0.9.43: what a visit remembers about an enchant's beacon (persisted), so the menu is not opened for nothing. */
    public record PrestigeState(Integer level, Integer max, Double cost, String currency, Integer rebirthReq, String tab) {}

    private final Pattern prestigeNameRe;
    private final Pattern prestigeLevelRe;
    private final Pattern prestigeCostRe;
    private final Pattern prestigeRebirthRe;
    private final Pattern prestigeMultRe;
    private final Pattern levelRe;
    private final Pattern priceRe;
    private final Pattern lockedRe;
    private final Pattern signatureRe;
    private final Pattern maxLevelsRe;
    private final Pattern upgradeTitleRe;
    private final Pattern swordRe;
    private final String maxUpgradeName;
    private final List<String> tabs;

    public EnchantLore(YCBotChallengeConfig cfg) {
        levelRe = compileLoose(cfg.enchantLevelPattern);
        priceRe = compileLoose(cfg.enchantPricePattern);
        lockedRe = compileLoose(cfg.enchantLockedPattern);
        signatureRe = compileLoose(cfg.enchantSignaturePattern);
        maxLevelsRe = compileLoose(cfg.enchantMaxLevelsPattern);
        upgradeTitleRe = compileLoose(cfg.enchantUpgradeTitlePattern);
        swordRe = compileLoose(cfg.enchantSwordPattern);
        prestigeNameRe = compileLoose(cfg.enchantPrestigeNamePattern);
        prestigeLevelRe = compileLoose(cfg.enchantPrestigeLevelPattern);
        prestigeCostRe = compileLoose(cfg.enchantPrestigeCostPattern);
        prestigeRebirthRe = compileLoose(cfg.enchantPrestigeRebirthPattern);
        prestigeMultRe = compileLoose(cfg.enchantPrestigeMultiplierPattern);
        maxUpgradeName = cfg.enchantMaxUpgradeName == null ? "max upgrade"
            : cfg.enchantMaxUpgradeName.trim().toLowerCase(Locale.ROOT);
        List<String> t = new ArrayList<>();
        if (cfg.enchantTabs != null) {
            for (String s : cfg.enchantTabs) if (s != null && !s.isBlank()) t.add(s.trim().toLowerCase(Locale.ROOT));
        }
        tabs = t;
    }

    public List<String> tabs() { return tabs; }

    /** 0.9.43: the beacon, parsed from its stripped name and lore; null for any other item. */
    public Prestige parsePrestige(int slot, String rawName, List<String> rawLore) {
        String name = rawName == null ? "" : SidebarParser.strip(rawName);
        List<String> lore = new ArrayList<>();
        if (rawLore != null) for (String l : rawLore) { String s = SidebarParser.strip(l); if (!s.isEmpty()) lore.add(s); }
        boolean isIt = prestigeNameRe.matcher(name).find();
        if (!isIt) for (String l : lore) if (prestigeNameRe.matcher(l).find()) { isIt = true; break; }
        if (!isIt) return null;
        Integer level = null, max = null, req = null;
        Double cost = null, mult = null;
        String currency = null;
        for (String line : lore) {
            Matcher m;
            if (level == null && (m = prestigeLevelRe.matcher(line)).find()) { level = intGroup(m, "cur"); max = intGroup(m, "max"); }
            if (cost == null && (m = prestigeCostRe.matcher(line)).find()) {
                cost = Amounts.parse(m.group("amount"));
                String c = m.group("currency");
                currency = c != null ? c.toLowerCase(Locale.ROOT) : null;
            }
            if (req == null && (m = prestigeRebirthRe.matcher(line)).find()) req = intGroup(m, "n");
            if (mult == null && (m = prestigeMultRe.matcher(line)).find()) mult = Amounts.parse(m.group("x"));
        }
        return new Prestige(slot, level, max, cost, currency, req, mult);
    }

    /**
     * 0.9.43: why a beacon cannot be clicked right now, or null when it can. An unknown
     * rebirth count or balance never clicks (the 0.9.11 rule: a person does not buy blind).
     */
    public static String prestigeBlock(Prestige p, Integer rebirths, Double balance) {
        if (p == null) return "no-item";
        if (p.maxedOut()) return "max";
        if (p.cost() == null) return "no-cost";
        if (p.rebirthReq() != null && rebirths == null) return "no-rebirths";
        if (p.rebirthReq() != null && rebirths < p.rebirthReq()) return "rebirth";
        if (balance == null) return "no-balance";
        if (p.cost() > balance + 1e-6) return "balance";
        return null;
    }

    /** The same gate on a remembered state (null state = unknown = worth opening). */
    public static String prestigeBlock(PrestigeState s, Integer rebirths, java.util.Map<String, Double> balances) {
        if (s == null) return null;
        if (s.level() != null && s.max() != null && s.level() >= s.max()) return "max";
        if (s.rebirthReq() != null && rebirths != null && rebirths < s.rebirthReq()) return "rebirth";
        if (s.cost() != null && s.currency() != null && balances != null) {
            Double bal = balances.get(s.currency());
            if (bal != null && s.cost() > bal + 1e-6) return "balance";
        }
        return null;
    }

    /**
     * 0.9.43: the maxed enchant whose Upgrade menu to open for its beacon: never one blocked
     * by what was remembered, unknown ones first (to learn them), then the cheapest known
     * next prestige (Drew: most prestiges per soul). Pure.
     */
    public static Item prestigePick(List<Item> inSlotOrder, java.util.Map<String, PrestigeState> remembered,
                                    Integer rebirths, java.util.Map<String, Double> balances, Set<String> skipNames) {
        Item best = null;
        double bestCost = Double.MAX_VALUE;
        boolean bestUnknown = false;
        if (inSlotOrder == null) return null;
        for (Item it : inSlotOrder) {
            if (!it.maxed() || it.locked()) continue;
            if (skipNames != null && skipNames.contains(it.name())) continue;
            PrestigeState s = remembered != null ? remembered.get(it.name()) : null;
            if (prestigeBlock(s, rebirths, balances) != null) continue;
            boolean unknown = s == null || s.cost() == null;
            double cost = unknown ? Double.MAX_VALUE : s.cost();
            if (best == null || (unknown && !bestUnknown) || (unknown == bestUnknown && cost < bestCost)) {
                best = it; bestCost = cost; bestUnknown = unknown;
            }
        }
        return best;
    }

    /** Parse one item from its stripped name and stripped lore lines. */
    public Item parse(String rawName, List<String> rawLore) {
        String name = rawName == null ? "" : SidebarParser.strip(rawName);
        List<String> lore = new ArrayList<>();
        if (rawLore != null) for (String l : rawLore) { String s = SidebarParser.strip(l); if (!s.isEmpty()) lore.add(s); }
        Integer level = null, maxLevel = null, maxLevels = null;
        Double price = null;
        String currency = null;
        boolean locked = false, signature = false;
        for (String line : lore) {
            Matcher m;
            if (level == null && (m = levelRe.matcher(line)).find()) {
                level = intGroup(m, "cur");
                maxLevel = intGroup(m, "max");
            }
            if (price == null && (m = priceRe.matcher(line)).find()) {
                price = Amounts.parse(m.group("amount"));
                String c = m.group("currency");
                currency = c != null ? c.toLowerCase(Locale.ROOT) : null;
            }
            if (!locked && lockedRe.matcher(line).find()) locked = true;
            if (!signature && signatureRe.matcher(line).find()) signature = true;
            if (maxLevels == null && (m = maxLevelsRe.matcher(line)).find()) maxLevels = intGroup(m, "n");
        }
        String lname = name.toLowerCase(Locale.ROOT);
        boolean maxUpgrade = lname.contains(maxUpgradeName);
        String tab = (level == null && price == null && !maxUpgrade) ? tabOfName(name) : null;
        return new Item(name, lore, level, maxLevel, price, currency, locked, signature, tab,
            maxUpgrade, maxLevels, maxUpgrade ? price : null);
    }

    /**
     * Which tab a button's name denotes: the tab word or its singular as a whole word
     * ("SOULS", "Soul Enchants", "ꜱʜᴀʀᴅꜱ"). Callers restrict this to the button row —
     * the 0.9.11 name-prefix rule matched essence-named icons elsewhere and missed
     * "Soul"/"Shard" singulars, so every button read as essence and shards was "missing".
     */
    public String tabOfName(String rawName) {
        if (rawName == null) return null;
        String lname = SidebarParser.strip(rawName).toLowerCase(Locale.ROOT);
        if (lname.isEmpty()) return null;
        for (String t : tabs) {
            String singular = t.endsWith("s") ? t.substring(0, t.length() - 1) : t;
            for (String w : new String[] {t, singular}) {
                if (w.isEmpty()) continue;
                if (Pattern.compile("(^|[^a-z])" + Pattern.quote(w) + "([^a-z]|$)").matcher(lname).find()) return t;
            }
        }
        return null;
    }

    /** The tab actually showing, from the scanned items' price currency (the server remembers your last tab). */
    public static String majorityCurrency(List<Item> items) {
        if (items == null) return null;
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (Item it : items) if (it.currency() != null) counts.merge(it.currency(), 1, Integer::sum);
        String best = null;
        int bestN = 0;
        for (java.util.Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestN) { best = e.getKey(); bestN = e.getValue(); }
        }
        return best;
    }

    /** First candidate in slot order (the 0.9.9 policy); kept for callers without a roll. */
    public static Item chooseEnchant(List<Item> inSlotOrder, java.util.Map<String, Double> balances,
                                     String fallbackCurrency, Set<String> skipNames) {
        return chooseEnchant(inSlotOrder, balances, fallbackCurrency, skipNames, 0.0, 0.0);
    }

    /**
     * Everything the bot could click right now: upgradable (unlocked, not maxed, priced),
     * not attempted this visit, and affordable by the ITEM's own price currency (never
     * the tab's). Slot order preserved. Pure.
     */
    public static List<Item> enchantCandidates(List<Item> inSlotOrder, java.util.Map<String, Double> balances,
                                               String fallbackCurrency, Set<String> skipNames) {
        List<Item> out = new java.util.ArrayList<>();
        if (inSlotOrder == null || balances == null) return out;
        for (Item it : inSlotOrder) {
            if (!it.upgradable()) continue;
            if (skipNames != null && skipNames.contains(it.name())) continue;
            String cur = it.currency() != null ? it.currency() : fallbackCurrency;
            Double balance = cur != null ? balances.get(cur) : null;
            if (balance != null && it.price() <= balance + 1e-6) out.add(it);
        }
        return out;
    }

    /** Weight of one candidate: 1 + lagBias × (1 − level/maxLevel). lagBias 0 = every candidate equal. */
    public static double enchantWeight(Item it, double lagBias) {
        double frac = it.maxLevel() != null && it.maxLevel() > 0 && it.level() != null
            ? Math.min(1.0, Math.max(0.0, it.level() / (double) it.maxLevel())) : 0.0;
        return 1.0 + Math.max(0.0, lagBias) * (1.0 - frac);
    }

    /**
     * The bot's whole enchant policy (0.9.30): a weighted roll over the candidates —
     * equal chance per non-maxed affordable enchant by default (Drew: Rocket took the
     * whole essence balance on ten visits in a row while Second Hand got the change and
     * Wizard on the shards tab was never bought), optionally tilted toward the enchant
     * furthest from max by {@code lagBias}. {@code roll} in [0,1) walks the cumulative
     * weights, so tests are deterministic and the controller passes a random.
     */
    public static Item chooseEnchant(List<Item> inSlotOrder, java.util.Map<String, Double> balances,
                                     String fallbackCurrency, Set<String> skipNames, double roll, double lagBias) {
        List<Item> c = enchantCandidates(inSlotOrder, balances, fallbackCurrency, skipNames);
        if (c.isEmpty()) return null;
        double sum = 0;
        double[] w = new double[c.size()];
        for (int i = 0; i < c.size(); i++) { w[i] = enchantWeight(c.get(i), lagBias); sum += w[i]; }
        double r = Math.min(0.999_999, Math.max(0.0, roll)) * sum;
        double acc = 0;
        for (int i = 0; i < c.size(); i++) {
            acc += w[i];
            if (r < acc) return c.get(i);
        }
        return c.get(c.size() - 1);
    }

    /** "<Name> Upgrade" — the sub-GUI's title is plain text (unlike the enchanter's). */
    public boolean isUpgradeTitle(String title) {
        if (title == null || title.isBlank()) return false;
        return upgradeTitleRe.matcher(SidebarParser.strip(title)).find();
    }

    /** The held item is the sword we can enchant (name says sword, or lore lists "Enchants:"). */
    public boolean isSword(String rawName, List<String> rawLore) {
        String name = rawName == null ? "" : SidebarParser.strip(rawName);
        if (swordRe.matcher(name).find()) return true;
        if (rawLore != null) for (String l : rawLore) if (swordRe.matcher(SidebarParser.strip(l)).find()) return true;
        return false;
    }

    private static Integer intGroup(Matcher m, String group) {
        try {
            String s = m.group(group);
            return s == null ? null : Integer.parseInt(s.replace(",", "").trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static Pattern compileLoose(String p) {
        if (p == null || p.isBlank()) return Pattern.compile("(?!)");
        if (p.startsWith("/") && p.endsWith("/") && p.length() > 2) {
            return Pattern.compile(p.substring(1, p.length() - 1), Pattern.CASE_INSENSITIVE);
        }
        return Pattern.compile(Pattern.quote(p), Pattern.CASE_INSENSITIVE);
    }
}

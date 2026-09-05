package dev.drew.ycbotchallenge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure parsing for the rebirth-upgrade menus (no Minecraft types; checked in
 * EconomyChecks). The Rebirth GUI's nether star reads:
 * <pre>
 *   REBIRTH UPGRADES | REBIRTHS | After you perform a rebirth, you'll use the rebirth
 *   points on these upgrades ... | BALANCES: | | Current Points: 0 |
 *   [CLICK HERE TO VIEW THE REBIRTH UPGRADES]
 * </pre>
 * Clicking it opens "Upgrades" with one item per upgrade (magma cream = essence,
 * purple dye = souls, enchanted book = enchant proc, red dye = damage). Their
 * tooltips are not captured yet, so level/cost/maxed are parsed with configurable
 * patterns and the first visit logs everything (rebirth_upgrade_menu) as the
 * fixture for tuning. Drew's order: enchant proc, damage, essence, souls.
 */
public final class RebirthLore {

    /** One upgrade item, parsed. Unknown level/cost stay null: the click decides. */
    public record Item(String name, List<String> lore, Integer level, Integer maxLevel, Integer cost, boolean maxed) {
        public boolean isMaxed() { return maxed || (level != null && maxLevel != null && level >= maxLevel); }

        public String summary() {
            StringBuilder sb = new StringBuilder(name);
            if (level != null && maxLevel != null) sb.append(' ').append(level).append('/').append(maxLevel);
            if (cost != null) sb.append(' ').append(cost).append("pts");
            if (isMaxed()) sb.append(" MAX");
            return sb.toString();
        }

        /** Everything the order keys are matched against. */
        public String haystack() {
            StringBuilder sb = new StringBuilder(name.toLowerCase(Locale.ROOT));
            for (String l : lore) sb.append(" | ").append(l.toLowerCase(Locale.ROOT));
            return sb.toString();
        }
    }

    /** 0.9.43: the diamond's lore - the rebirth cost and the permanent money multiplier now / after. */
    public record RebirthItem(Double required, Double multFrom, Double multTo) {}

    private final Pattern requiredRe;
    private final Pattern multiplierRe;
    private final Pattern starRe;
    private final Pattern pointsRe;
    private final Pattern menuTitleRe;
    private final Pattern levelRe;
    private final Pattern costRe;
    private final Pattern maxedRe;
    private final List<String> order;

    public RebirthLore(YCBotChallengeConfig cfg) {
        starRe = compileLoose(cfg.rebirthUpgradesItemPattern);
        pointsRe = compileLoose(cfg.rebirthPointsPattern);
        requiredRe = compileLoose(cfg.rebirthRequiredPattern);
        multiplierRe = compileLoose(cfg.rebirthMultiplierPattern);
        menuTitleRe = compileLoose(cfg.rebirthUpgradesTitlePattern);
        levelRe = compileLoose(cfg.rebirthUpgradeLevelPattern);
        costRe = compileLoose(cfg.rebirthUpgradeCostPattern);
        maxedRe = compileLoose(cfg.rebirthUpgradeMaxedPattern);
        List<String> o = new ArrayList<>();
        if (cfg.rebirthUpgradeOrder != null) {
            for (String s : cfg.rebirthUpgradeOrder) if (s != null && !s.isBlank()) o.add(s.toLowerCase(Locale.ROOT).trim());
        }
        order = o;
    }

    public List<String> order() { return order; }

    /** 0.9.43: "Required: 282.430T Money" and "Multiplier: 4.59Kx -> 6.43Kx" from the diamond's stripped lore. */
    public RebirthItem parseRebirthItem(List<String> lore) {
        Double required = null, from = null, to = null;
        if (lore != null) {
            for (String raw : lore) {
                if (raw == null) continue;
                String l = SidebarParser.strip(raw);
                if (required == null) {
                    Matcher m = requiredRe.matcher(l);
                    if (m.find()) required = Amounts.parse(groupOr(m, "amount", 1));
                }
                if (from == null) {
                    Matcher m = multiplierRe.matcher(l);
                    if (m.find()) { from = Amounts.parse(groupOr(m, "from", 1)); to = Amounts.parse(groupOr(m, "to", 2)); }
                }
            }
        }
        return new RebirthItem(required, from, to);
    }

    static Pattern compileLoose(String p) {
        if (p == null || p.isBlank()) return Pattern.compile("(?!)");
        if (p.length() > 2 && p.startsWith("/") && p.endsWith("/")) {
            return Pattern.compile(p.substring(1, p.length() - 1), Pattern.CASE_INSENSITIVE);
        }
        return Pattern.compile(Pattern.quote(p), Pattern.CASE_INSENSITIVE);
    }

    /** The nether star ("REBIRTH UPGRADES") by name or lore. */
    public boolean isStar(String name, List<String> lore) {
        if (name != null && starRe.matcher(name).find()) return true;
        if (lore != null) for (String l : lore) if (starRe.matcher(l).find()) return true;
        return false;
    }

    /** "| Current Points: 0" → 0; null when no line matches. */
    public Integer points(List<String> lore) {
        if (lore == null) return null;
        for (String l : lore) {
            Matcher m = pointsRe.matcher(l);
            if (m.find()) {
                try {
                    String g = groupOr(m, "n", 1);
                    if (g != null) return Integer.parseInt(g.replace(",", "").trim());
                } catch (Exception ignored) { }
            }
        }
        return null;
    }

    public boolean isMenuTitle(String title) {
        if (title == null) return false;
        return menuTitleRe.matcher(SidebarParser.strip(title).trim()).find();
    }

    public Item parse(String name, List<String> lore) {
        Integer level = null, max = null, cost = null;
        boolean maxed = false;
        List<String> lines = lore == null ? List.of() : lore;
        for (String l : lines) {
            if (level == null) {
                Matcher m = levelRe.matcher(l);
                if (m.find()) {
                    try {
                        level = Integer.parseInt(groupOr(m, "cur", 1).replace(",", "").trim());
                        max = Integer.parseInt(groupOr(m, "max", 2).replace(",", "").trim());
                    } catch (Exception ignored) { level = null; max = null; }
                }
            }
            if (cost == null) {
                Matcher m = costRe.matcher(l);
                if (m.find()) {
                    try { cost = Integer.parseInt(groupOr(m, "amount", 1).replace(",", "").trim()); } catch (Exception ignored) { }
                }
            }
            if (maxedRe.matcher(l).find()) maxed = true;
        }
        if (name != null && maxedRe.matcher(name).find()) maxed = true;
        return new Item(name == null ? "" : name, lines, level, max, cost, maxed);
    }

    /** Index in the order list of the first key found in the item, or -1. */
    public int orderIndex(Item item) {
        String hay = item.haystack();
        for (int i = 0; i < order.size(); i++) if (hay.contains(order.get(i))) return i;
        return -1;
    }

    /**
     * The upgrade to click: walk the order (enchant proc, damage, essence, souls);
     * the first one present, not maxed, and affordable when both its cost and the
     * points are known. Items outside the order are never clicked.
     */
    public Item choose(List<Item> items, Integer points) {
        for (String key : order) {
            for (Item it : items) {
                if (!it.haystack().contains(key)) continue;
                if (it.isMaxed()) continue;
                if (it.cost() != null && points != null && it.cost() > points) continue;
                return it;
            }
        }
        return null;
    }

    private static String groupOr(Matcher m, String named, int idx) {
        try {
            String g = m.group(named);
            if (g != null) return g;
        } catch (IllegalArgumentException ignored) { }
        return m.groupCount() >= idx ? m.group(idx) : null;
    }
}

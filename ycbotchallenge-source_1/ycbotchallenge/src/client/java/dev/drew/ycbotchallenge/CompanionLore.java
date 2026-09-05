package dev.drew.ycbotchallenge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure parsing and decisions for companions (0.9.28; no Minecraft types, checked in
 * EconomyChecks). Fixtures from Drew's screenshots, 2026-09-03:
 * <pre>
 *   hologram: Western Companion Egg | Unhatch a Dungeons Companion that boosts | the amount
 *             of money you gain! | | Price: $121.3300 Money | << Right Click to view. >>
 *   egg GUI:  OPEN: [3x COMPANION EGG] | Pressing this will open up 3x Companion Egg, all
 *             companions will go directly to your companion storage. | | Price: 363.9800 Money
 *             | | Discounted Openings Left: 0x | [CLICK THIS TO OPEN 3x COMPANION EGG]
 *   storage:  Cow Companion | COMPANION | Information: | | Rarity: Rare (NORMAL)
 *             | | Multiplier: 156.38x Money | [ZONE 1 STAGE 10] | << Click Here to un-equip ... >>
 * </pre>
 * The zone prefix of the egg ("Western", "Farm", …) is never matched — only the tail.
 */
public final class CompanionLore {
    /** One "OPEN: [Nx COMPANION EGG]" item. */
    public record OpenOption(int slot, String name, int count, Double price, String priceRaw) {
        public OpenOption(int slot, String name, int count, Double price) { this(slot, name, count, price, null); }
    }

    /** 0.9.31: a rung the Companion Eggs GUI taught us (see {@link #rungFromOptions}). */
    public record RungLesson(String suffix, Amounts.Learned learned, int count, double unit) {}

    /** A companion's zone/stage tag. */
    public record ZoneStage(int zone, int stage) {}

    /** A companion item as read from the Companions GUI. */
    public record Companion(int slot, String name, Integer zone, Integer stage, Double multiplier, String rarity) {
        public ZoneStage zoneStage() { return zone != null && stage != null ? new ZoneStage(zone, stage) : null; }

        public String summary() {
            StringBuilder sb = new StringBuilder(name);
            if (rarity != null) sb.append(' ').append(rarity);
            if (multiplier != null) sb.append(' ').append(multiplier).append('x');
            if (zone != null && stage != null) sb.append(" z").append(zone).append("s").append(stage);
            return sb.toString();
        }
    }

    private final Pattern eggRe;
    private final Pattern priceRe;
    private final Pattern excludeRe;
    private final Pattern openRe;
    private final Pattern zoneStageRe;
    private final Pattern multiplierRe;
    private final Pattern rarityRe;
    private final Pattern equipBestRe;
    private final Pattern fuseRe;
    private final Pattern fuseAllRe;
    private final Pattern unequipRe;
    private final Pattern eggsTitleRe;
    private final Pattern companionsTitleRe;
    private final Pattern fuseTitleRe;

    public CompanionLore(YCBotChallengeConfig cfg) {
        eggRe = RebirthLore.compileLoose(cfg.companionEggPattern);
        priceRe = RebirthLore.compileLoose(cfg.companionPricePattern);
        excludeRe = RebirthLore.compileLoose(cfg.companionEggExcludePattern);
        openRe = RebirthLore.compileLoose(cfg.companionOpenPattern);
        zoneStageRe = RebirthLore.compileLoose(cfg.companionZoneStagePattern);
        multiplierRe = RebirthLore.compileLoose(cfg.companionMultiplierPattern);
        rarityRe = RebirthLore.compileLoose(cfg.companionRarityPattern);
        equipBestRe = RebirthLore.compileLoose(cfg.companionEquipBestPattern);
        fuseRe = RebirthLore.compileLoose(cfg.companionFusePattern);
        fuseAllRe = RebirthLore.compileLoose(cfg.companionFuseAllPattern);
        unequipRe = RebirthLore.compileLoose(cfg.companionUnequipPattern);
        eggsTitleRe = RebirthLore.compileLoose(cfg.companionEggsTitlePattern);
        companionsTitleRe = RebirthLore.compileLoose(cfg.companionsTitlePattern);
        fuseTitleRe = RebirthLore.compileLoose(cfg.companionFuseTitlePattern);
    }

    private static boolean any(Pattern re, List<String> lines) {
        if (lines == null) return false;
        for (String l : lines) if (l != null && re.matcher(l).find()) return true;
        return false;
    }

    /** The money egg's hologram: a "Companion Egg" line, a money price, nothing about credits. */
    public boolean isEggHologram(List<String> lines) {
        return any(eggRe, lines) && eggPrice(lines) != null && !any(excludeRe, lines);
    }

    /** The raw amount token of the price line ("1.58SS"), parsed or not; null without a price line. */
    public String eggPriceRaw(List<String> lines) {
        if (lines == null) return null;
        for (String l : lines) {
            if (l == null) continue;
            Matcher m = priceRe.matcher(l);
            if (m.find()) {
                String a = group(m, "amount");
                if (a != null) return a.replace(" ", "").trim();
            }
        }
        return null;
    }

    /**
     * 0.9.31: the Companion Eggs GUI prices 1×, 3×, 10×, 50× and 250× openings, and the
     * 250× line reads "1.58SS" while the 1× line reads "6.34S" — 250 × 6.34S = 1585S, so
     * SS is exactly the 1000× rung above S. That is a crossing the sidebar may not show
     * for hours, proven by a count ratio instead: unit = the smallest parsed option's
     * price per egg; for an option whose suffix is unknown, scale = count × unit /
     * mantissa, accepted when within 5 % of 1000 × the highest known rung (the printed
     * mantissa is truncated to two decimals, ~0.3 % off). Pure; null when nothing to learn.
     */
    public static RungLesson rungFromOptions(List<OpenOption> options) {
        if (options == null) return null;
        OpenOption unitOpt = null;
        for (OpenOption o : options) {
            if (o == null || o.price() == null || o.price() <= 0 || o.count() <= 0) continue;
            if (unitOpt == null || o.count() < unitOpt.count()) unitOpt = o;
        }
        if (unitOpt == null) return null;
        double unit = unitOpt.price() / unitOpt.count();
        Amounts.Rung top = Amounts.highestKnown();
        double expect = top.scale() * 1000.0;
        for (OpenOption o : options) {
            if (o == null || o.price() != null || o.priceRaw() == null || o.count() <= 0) continue;
            String sfx = Amounts.suffixOf(o.priceRaw());
            Double m = Amounts.mantissaOf(o.priceRaw());
            if (sfx.isEmpty() || m == null || m <= 0 || Amounts.knownSuffix(sfx)) continue;
            double scale = o.count() * unit / m;
            if (Math.abs(scale - expect) / expect > 0.05) continue;
            Amounts.Learned l = new Amounts.Learned();
            l.scale = expect;
            l.confirmed = true;
            l.via = "gui-ratio";
            l.basis = top.suffix();
            l.raw = o.priceRaw();
            l.prevRaw = Amounts.format(unit) + " x" + o.count();
            l.at = System.currentTimeMillis();
            return new RungLesson(sfx.toUpperCase(Locale.ROOT), l, o.count(), unit);
        }
        return null;
    }

    /** The single-egg price on the hologram ("| Price: $121.3300 Money" → 121.33), or null. */
    public Double eggPrice(List<String> lines) {
        if (lines == null) return null;
        for (String l : lines) {
            if (l == null) continue;
            Matcher m = priceRe.matcher(l);
            if (m.find()) {
                Double v = Amounts.parse(group(m, "amount"));
                if (v != null) return v;
            }
        }
        return null;
    }

    /** "OPEN: [3x COMPANION EGG]" by name or lore, with its money price; null for any other item. */
    public OpenOption openOption(int slot, String name, List<String> lore) {
        Integer count = null;
        Matcher m = name != null ? openRe.matcher(name) : null;
        if (m != null && m.find()) count = parseInt(group(m, "n"));
        if (count == null && lore != null) {
            for (String l : lore) {
                if (l == null) continue;
                Matcher lm = openRe.matcher(l);
                if (lm.find()) { count = parseInt(group(lm, "n")); if (count != null) break; }
            }
        }
        if (count == null || count <= 0) return null;
        return new OpenOption(slot, name, count, eggPrice(lore), eggPriceRaw(lore));
    }

    /** A companion item (zone/stage tag or multiplier present); null for buttons and fillers. */
    public Companion companion(int slot, String name, List<String> lore) {
        Integer zone = null, stage = null;
        Double mult = null;
        String rarity = null;
        if (lore != null) {
            for (String l : lore) {
                if (l == null) continue;
                Matcher zm = zoneStageRe.matcher(l);
                if (zone == null && zm.find()) { zone = parseInt(group(zm, "zone")); stage = parseInt(group(zm, "stage")); }
                Matcher mm = multiplierRe.matcher(l);
                if (mult == null && mm.find()) mult = Amounts.parse(group(mm, "x"));
                Matcher rm = rarityRe.matcher(l);
                if (rarity == null && rm.find()) rarity = group(rm, "r");
            }
        }
        if (zone == null && mult == null) return null;
        return new Companion(slot, name, zone, stage, mult, rarity);
    }

    public boolean isEquipBest(String name, List<String> lore) {
        return (name != null && equipBestRe.matcher(name).find()) || any(equipBestRe, lore);
    }

    /** Equipped: the lore offers to un-equip it ("(( Click Here to un-equip your Cow Companion. ))"). */
    public boolean isEquipped(List<String> lore) {
        return any(unequipRe, lore);
    }

    public boolean isFuse(String name, List<String> lore) {
        return (name != null && fuseRe.matcher(name).find()) || any(fuseRe, lore);
    }

    /** 0.9.37: the "Fuse All" item of the fusion menu (slot 53 in every 2026-09-04 dump). */
    public boolean isFuseAll(String name, List<String> lore) {
        return (name != null && fuseAllRe.matcher(name).find()) || any(fuseAllRe, lore);
    }

    /** 0.9.37: a fusable group - five of the same companion (name, zone, stage, rarity). */
    public record FuseGroup(String name, Integer zone, Integer stage, String rarity, int count) {
        public String summary() {
            return name + " " + (rarity != null ? rarity : "?") + " z" + zone + "s" + stage + " x" + count;
        }
    }

    /**
     * 0.9.37: the groups in a companion list, largest first. Identity is the companion's
     * name, zone, stage and rarity - the fusion item says "fuse ALL 5 of the same
     * Companions", and the 23:46 dump held 7 Eagle Basic z2s3 and 6 Lizard Rare z2s3 at
     * 100 % odds that four visits walked past.
     */
    public static List<FuseGroup> fuseGroups(Collection<Companion> companions, int minGroup) {
        java.util.Map<String, int[]> counts = new java.util.LinkedHashMap<>();
        java.util.Map<String, Companion> first = new java.util.LinkedHashMap<>();
        if (companions != null) {
            for (Companion c : companions) {
                if (c == null || c.name() == null) continue;
                String key = c.name().toLowerCase(Locale.ROOT) + "|" + c.zone() + "|" + c.stage() + "|"
                    + (c.rarity() == null ? "" : c.rarity().toLowerCase(Locale.ROOT));
                counts.computeIfAbsent(key, k -> new int[1])[0]++;
                first.putIfAbsent(key, c);
            }
        }
        List<FuseGroup> out = new ArrayList<>();
        for (java.util.Map.Entry<String, int[]> e : counts.entrySet()) {
            if (e.getValue()[0] < Math.max(1, minGroup)) continue;
            Companion c = first.get(e.getKey());
            out.add(new FuseGroup(c.name(), c.zone(), c.stage(), c.rarity(), e.getValue()[0]));
        }
        out.sort((a, b) -> Integer.compare(b.count(), a.count()));
        return out;
    }

    public boolean isEggsTitle(String title) { return title != null && eggsTitleRe.matcher(title.trim()).find(); }

    /** The storage GUI, never the fusion menu (whose real title may also start "Companions ..."). */
    public boolean isCompanionsTitle(String title) {
        return title != null && companionsTitleRe.matcher(title.trim()).find() && !isFuseTitle(title);
    }

    public boolean isFuseTitle(String title) { return title != null && fuseTitleRe.matcher(title.trim()).find(); }

    /**
     * Minutes of income a price costs, or null when either is unknown. Evidence only since
     * 0.9.35 (companion_price / companion_open): the buy decision is made on rebirth ETAs in
     * {@link Economy#decideCompanion}, not on a flat minutes-of-income budget.
     */
    public static Double incomeMinutes(Double price, Double incomePerMin) {
        if (price == null || incomePerMin == null || incomePerMin <= 0) return null;
        return price / incomePerMin;
    }

    /**
     * The open to click: the largest option that fits the eggs still wanted, costs at most
     * {@code moneyBudget} and at most {@code maxBalancePct} of the balance. Unknown budget
     * or balance = nothing (a person does not buy blind). Ties: cheaper.
     *
     * <p>0.9.35: an absolute budget, carried from the decision that approved the visit. It
     * used to be "companionMaxIncomeMinutes of income" — the same knob that gated the
     * trigger — so once the economy stopped using that knob the walk would have reached the
     * egg and bought nothing.
     */
    public static OpenOption pickOpen(List<OpenOption> options, int eggsLeft, Double moneyBudget,
                                      Double balance, double maxBalancePct) {
        if (options == null || eggsLeft <= 0 || moneyBudget == null || moneyBudget <= 0 || balance == null) return null;
        double budget = Math.min(moneyBudget, balance * Math.max(0, maxBalancePct) / 100.0);
        OpenOption best = null;
        for (OpenOption o : options) {
            if (o == null || o.price() == null || o.price() <= 0 || o.count() <= 0) continue;
            if (o.count() > eggsLeft || o.price() > budget) continue;
            if (best == null || o.count() > best.count() || (o.count() == best.count() && o.price() < best.price())) best = o;
        }
        return best;
    }

    /**
     * Sliding window (Drew): keep the newest {@code keepZones} zones (the current one and the
     * one before it by default), delete the rest — never a pair an equipped companion holds.
     * Unknown current zone = delete nothing.
     */
    public static List<ZoneStage> deletePairs(Collection<ZoneStage> storage, Collection<ZoneStage> equipped,
                                              Integer currentZone, int keepZones) {
        List<ZoneStage> out = new ArrayList<>();
        if (storage == null || currentZone == null) return out;
        int maxZone = currentZone - Math.max(1, keepZones);
        Set<ZoneStage> seen = new LinkedHashSet<>();
        for (ZoneStage zs : storage) {
            if (zs == null || zs.zone() > maxZone) continue;
            if (equipped != null && equipped.contains(zs)) continue;
            seen.add(zs);
        }
        out.addAll(seen);
        out.sort((a, b) -> a.zone() != b.zone() ? Integer.compare(a.zone(), b.zone()) : Integer.compare(a.stage(), b.stage()));
        return out;
    }

    /** The command for one pair from the template ("/companion bulkdelete {zone} {stage}"). */
    public static String bulkDeleteCommand(String template, ZoneStage zs) {
        return template.replace("{zone}", Integer.toString(zs.zone())).replace("{stage}", Integer.toString(zs.stage()));
    }

    private static String group(Matcher m, String name) {
        try { return m.group(name); } catch (IllegalArgumentException e) { return null; }
    }

    private static Integer parseInt(String s) {
        if (s == null) return null;
        try { return Integer.parseInt(s.replace(",", "").trim()); } catch (NumberFormatException e) { return null; }
    }

    /** Lower-cased "name | lore" haystack for ad-hoc matching in logs. */
    public static String haystack(String name, List<String> lore) {
        StringBuilder sb = new StringBuilder(name == null ? "" : name.toLowerCase(Locale.ROOT));
        if (lore != null) for (String l : lore) if (l != null) sb.append(" | ").append(l.toLowerCase(Locale.ROOT));
        return sb.toString();
    }
}

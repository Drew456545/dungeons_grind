package dev.drew.ycbotchallenge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.9.33: pure parsing of the "Sword Skins" menu (opened from the SWORD ENCHANTER's
 * "Swords" item — "Click to view your swords"). Each skin's tooltip reads
 * <pre>
 *   Netherite Sword | SWORD SKIN | Damage: 727.75B DMG | Price: $139.88Q Money | Tier: 3/5 ★★★☆☆ | EQUIPPED | Click here to upgrade the tier of your sword
 *   Samurai Sword   | SWORD SKIN | Damage: 15.13T DMG  | Price: $600 Money     | Tier: 0/5 ☆☆☆☆☆ | LOCKED   | Click to buy this Sword Skin
 * </pre>
 * The equipped skin's price is the next tier's price (what the next /swordmax pays); a
 * locked skin's price is the skin itself. Every skin has five tiers and /swordmax climbs
 * them before promoting to the next skin, on the same ×3.5 ladder. No Minecraft types;
 * unit-tested in EconomyChecks. Strings are {@link SidebarParser#strip}ped by the caller.
 */
public final class SwordSkinLore {

    /** One skin as shown in the menu. */
    public record Skin(int slot, String name, Double damage, String damageRaw, Double price, String priceRaw,
                       Integer tier, Integer tierMax, boolean equipped, boolean locked) {
        public String summary() {
            StringBuilder sb = new StringBuilder(slot + ":" + name);
            if (tier != null) sb.append(' ').append(tier).append('/').append(tierMax);
            if (priceRaw != null) sb.append(" price=").append(priceRaw);
            if (damageRaw != null) sb.append(" dmg=").append(damageRaw);
            if (equipped) sb.append(" EQUIPPED");
            if (locked) sb.append(" LOCKED");
            return sb.toString();
        }
    }

    private final Pattern buttonNameRe;
    private final Pattern buttonLoreRe;
    private final Pattern titleRe;
    private final Pattern signatureRe;
    private final Pattern priceRe;
    private final Pattern tierRe;
    private final Pattern damageRe;
    private final Pattern equippedRe;
    private final Pattern lockedRe;

    public SwordSkinLore(YCBotChallengeConfig cfg) {
        buttonNameRe = RebirthLore.compileLoose(cfg.swordSkinsButtonPattern);
        buttonLoreRe = RebirthLore.compileLoose(cfg.swordSkinsButtonLorePattern);
        titleRe = RebirthLore.compileLoose(cfg.swordSkinsTitlePattern);
        signatureRe = RebirthLore.compileLoose(cfg.swordSkinSignaturePattern);
        priceRe = RebirthLore.compileLoose(cfg.swordSkinPricePattern);
        tierRe = RebirthLore.compileLoose(cfg.swordSkinTierPattern);
        damageRe = RebirthLore.compileLoose(cfg.swordSkinDamagePattern);
        equippedRe = RebirthLore.compileLoose(cfg.swordSkinEquippedPattern);
        lockedRe = RebirthLore.compileLoose(cfg.swordSkinLockedPattern);
    }

    /** The enchanter's "Swords" item: name matches, or any lore line does ("Click to view your swords"). */
    public boolean isSwordsButton(String name, List<String> lore) {
        if (name != null && buttonNameRe.matcher(SidebarParser.strip(name)).find()) return true;
        if (lore != null) for (String l : lore) if (l != null && buttonLoreRe.matcher(SidebarParser.strip(l)).find()) return true;
        return false;
    }

    public boolean isSkinsTitle(String title) {
        return title != null && titleRe.matcher(SidebarParser.strip(title)).find();
    }

    /** A skins menu recognised by content: two or more items carrying the SWORD SKIN signature. */
    public static boolean looksLikeSkins(List<Skin> skins) {
        return skins != null && skins.size() >= 2;
    }

    /** Parse one item; null unless a lore line carries the signature. */
    public Skin parse(int slot, String rawName, List<String> rawLore) {
        String name = rawName == null ? "" : SidebarParser.strip(rawName);
        List<String> lore = new ArrayList<>();
        if (rawLore != null) for (String l : rawLore) { String s = SidebarParser.strip(l); if (!s.isEmpty()) lore.add(s); }
        boolean signature = false;
        Double price = null, damage = null;
        String priceRaw = null, damageRaw = null;
        Integer tier = null, tierMax = null;
        boolean equipped = false, locked = false;
        for (String line : lore) {
            Matcher m;
            if (!signature && signatureRe.matcher(line).find()) signature = true;
            if (price == null && (m = priceRe.matcher(line)).find()) {
                priceRaw = m.group("amount").trim();
                price = Amounts.parse(priceRaw);
            }
            if (damage == null && (m = damageRe.matcher(line)).find()) {
                damageRaw = m.group("amount").trim();
                damage = Amounts.parse(damageRaw);
            }
            if (tier == null && (m = tierRe.matcher(line)).find()) {
                tier = intGroup(m, "cur");
                tierMax = intGroup(m, "max");
            }
            if (!equipped && equippedRe.matcher(line).find()) equipped = true;
            if (!locked && lockedRe.matcher(line).find()) locked = true;
        }
        if (!signature) return null;
        return new Skin(slot, name, damage, damageRaw, price, priceRaw, tier, tierMax, equipped, locked);
    }

    public static Skin equipped(List<Skin> skins) {
        if (skins == null) return null;
        for (Skin s : skins) if (s.equipped()) return s;
        return null;
    }

    /**
     * What the next /swordmax pays: the equipped skin's price while it has tiers left, else
     * the cheapest locked skin (the promotion). Null when neither is readable.
     */
    public static Skin nextBuy(List<Skin> skins) {
        Skin eq = equipped(skins);
        if (eq != null && eq.price() != null && (eq.tier() == null || eq.tierMax() == null || eq.tier() < eq.tierMax())) return eq;
        Skin best = null;
        if (skins != null) for (Skin s : skins) {
            if (!s.locked() || s.price() == null) continue;
            if (best == null || s.price() < best.price()) best = s;
        }
        return best;
    }

    public static Double nextPrice(List<Skin> skins) {
        Skin s = nextBuy(skins);
        return s != null ? s.price() : null;
    }

    /**
     * Whether a menu price may replace what the bot knows. The suffix in the tooltip may
     * be a font glyph the text component does not carry ("$139.880" for 139.88Q), so a
     * value that disagrees with the known target or the ladder by more than {@code bandPct}
     * is rejected: "match" (within band of the known/predicted target), "ladder-match"
     * (a ×growth step from the previous price), "no-reference" (nothing to check against),
     * "rejected".
     */
    public static String acceptMenuPrice(Double menu, Double reference, Double previous, double growth, double bandPct) {
        if (menu == null || menu <= 0) return "rejected";
        double band = Math.max(0, bandPct) / 100.0;
        if (reference != null && reference > 0) {
            return Math.abs(menu - reference) / reference <= band ? "match" : "rejected";
        }
        if (previous != null && previous > 0) {
            return Economy.growthAccepted(menu / previous, growth, bandPct) ? "ladder-match" : "rejected";
        }
        return "no-reference";
    }

    public static List<String> summaries(List<Skin> skins) {
        List<String> out = new ArrayList<>();
        if (skins != null) for (Skin s : skins) out.add(s.summary());
        return out;
    }

    private static Integer intGroup(Matcher m, String group) {
        try {
            String g = m.group(group);
            return g == null ? null : Integer.parseInt(g.replace(",", "").trim());
        } catch (Exception e) {
            return null;
        }
    }

    static String lower(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }
}

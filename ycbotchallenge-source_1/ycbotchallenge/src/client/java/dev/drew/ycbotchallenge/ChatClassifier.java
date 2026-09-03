package dev.drew.ycbotchallenge;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict, evidence-based chat classification for the upgrade economy. Every
 * pattern is anchored to the real EnchantedMC wording captured in session logs,
 * and callers gate on a short window after our own sends plus the broadcast
 * guard, so server noise (enchant procs, welcomes, player shops) can never be
 * mistaken for an upgrade response. Pure string work — unit-tested.
 */
public final class ChatClassifier {
    private ChatClassifier() {}

    /**
     * Player chat and server broadcasts carry a » separator or a [rank] prefix.
     * Our own command replies never do ("You don't have enough money...",
     * " - Money: (1.09T)").
     */
    public static boolean isPlayerOrBroadcast(String stripped) {
        if (stripped == null || stripped.isEmpty()) return true;
        return stripped.indexOf('\u00BB') >= 0 || stripped.charAt(0) == '[';
    }

    /** Strip §/& formatting and collapse whitespace (same rules as the sidebar). */
    public static String clean(String raw) {
        return SidebarParser.strip(raw);
    }

    /**
     * Heart HP from a mob boss-bar title. Low stages print plain numbers
     * ("[EPIC] LVL1 Chicken ❤346"); higher ones use amount suffixes
     * ("LVL5 Goat ❤82.04M"), which a digits-only parser read as 82 — and as
     * 999 → 8 across a K→M boundary, so the DPS slope went negative.
     */
    private static final Pattern BOSS_HP = Pattern.compile(
        "[❤♥]️?\\s*([\\d,]+(?:\\.\\d+)?\\s*[A-Za-z]{0,4})");

    public static Double bossBarHp(String title) {
        if (title == null || title.isEmpty()) return null;
        Matcher m = BOSS_HP.matcher(title);
        if (!m.find()) return null;
        return Amounts.parse(m.group(1));
    }

    /** The remaining-gap amount from a fail line: "You need 781.04B Money ...". */
    public static Double needAmount(String stripped, Pattern needAmountRe) {
        return amountGroup(stripped, needAmountRe);
    }

    /** Summary payout amount, e.g. " + 17.19B Money" (money lines only). */
    public static Double summaryMoney(String stripped, Pattern moneyRe) {
        return amountGroup(stripped, moneyRe);
    }

    /**
     * Amount paid from a success line, e.g. "You have unlocked a new sword level for
     * 1.24B!" → 1.24e9. Null when the matching pattern carries no amount (the zone
     * "You have purchased new stage(s)!" line).
     */
    public static Double successAmount(String stripped, List<Pattern> successRes) {
        if (stripped == null || successRes == null) return null;
        for (Pattern p : successRes) {
            Double v = amountGroup(stripped, p);
            if (v != null) return v;
        }
        return null;
    }

    private static Double amountGroup(String stripped, Pattern re) {
        if (stripped == null || re == null) return null;
        Matcher m = re.matcher(stripped);
        if (!m.find()) return null;
        try {
            String a = m.group("amount");
            return a != null ? Amounts.parse(a) : null;
        } catch (IllegalArgumentException e) {
            try {
                return m.groupCount() >= 1 ? Amounts.parse(m.group(1)) : null;
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    /** "rebirth" / "sword" / "zone" from the line's own wording, else the given fallback. */
    public static String kindOf(String stripped, String fallback) {
        if (stripped == null) return fallback;
        String l = stripped.toLowerCase(Locale.ROOT);
        if (l.contains("rebirth")) return "rebirth";
        if (l.contains("sword")) return "sword";
        if (l.contains("stage") || l.contains("zone")) return "zone";
        return fallback;
    }

    /** Rebirth container title (screenshot: "Rebirth GUI"). */
    public static boolean isRebirthGui(String title) {
        if (title == null || title.isBlank()) return false;
        return SidebarParser.strip(title).toLowerCase(Locale.ROOT).contains("rebirth");
    }

    /** Reward Summary header seconds, e.g. "Reward Summary: (60s)" → 60. */
    public static Integer summaryWindowSeconds(String stripped, Pattern headerRe) {
        if (stripped == null || headerRe == null) return null;
        Matcher m = headerRe.matcher(stripped);
        if (!m.find()) return null;
        try {
            String s = m.group("seconds");
            if (s != null) return Integer.parseInt(s);
        } catch (IllegalArgumentException ignored) {}
        try {
            return Integer.parseInt(m.group(1));
        } catch (Exception e) {
            return null;
        }
    }
}

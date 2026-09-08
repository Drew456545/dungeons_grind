package dev.drew.ycbotchallenge;

import java.util.regex.Pattern;

/**
 * 0.9.62: the one "loose pattern" compiler. A config pattern is either {@code /regex/}
 * (compiled case-insensitive) or a plain substring (quoted, case-insensitive); null or
 * blank matches nothing. Four byte-identical copies lived in EnchantLore, RebirthLore,
 * StatsTracker and EconomyChecks (plus CaptchaSolver's); they delegate here now.
 */
public final class Loose {
    private Loose() {}

    public static Pattern compile(String p) {
        if (p == null || p.isBlank()) return Pattern.compile("(?!)");
        if (p.length() > 2 && p.startsWith("/") && p.endsWith("/")) {
            return Pattern.compile(p.substring(1, p.length() - 1), Pattern.CASE_INSENSITIVE);
        }
        return Pattern.compile(Pattern.quote(p), Pattern.CASE_INSENSITIVE);
    }
}

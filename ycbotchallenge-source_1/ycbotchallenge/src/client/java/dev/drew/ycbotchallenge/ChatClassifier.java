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

    /**
     * A chat line may trigger the captcha path only when it is a server line: never
     * action-bar text, never our own [YCBotChallenge] messages, never player chat or
     * broadcasts. 2026-09-03: "» next time just captcha him" from another player
     * matched "captcha" and the bot typed a guess into public chat.
     */
    public static boolean captchaLineEligible(String text, boolean overlay) {
        if (text == null || text.isEmpty() || overlay) return false;
        if (text.startsWith("[YCBotChallenge]")) return false;
        return !isPlayerOrBroadcast(text);
    }

    /**
     * The prize line of a giveaway announcement packet: the first line that is not
     * the announcement itself and not the "Click to Enter!" call to action
     * (2026-09-03: "NEW GIVEAWAY (30s to enter)" / "Current Lootbox" / "Click to Enter!").
     */
    public static String giveawayPrize(List<String> lines, List<Pattern> announceRes) {
        if (lines == null) return null;
        for (String l : lines) {
            if (l == null || l.isBlank()) continue;
            String low = l.toLowerCase(Locale.ROOT);
            if (low.contains("click to enter") || low.contains("click here")) continue;
            boolean announce = false;
            if (announceRes != null) for (Pattern p : announceRes) if (p.matcher(l).find()) { announce = true; break; }
            if (announce) continue;
            return l.trim();
        }
        return null;
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

    /**
     * The raw amount token of a need line ("20.5QQ" from "You need $20.5QQ Money to
     * Rebirth."), whether or not its suffix is known — so an unknown suffix is logged
     * as evidence and the send still resolves as a fail instead of a timeout (0.9.24).
     */
    public static String needAmountToken(String stripped, Pattern needAmountRe) {
        if (stripped == null || needAmountRe == null) return null;
        Matcher m = needAmountRe.matcher(stripped);
        if (!m.find()) return null;
        try {
            String tok = m.group("amount");
            return tok == null ? null : tok.trim();
        } catch (IllegalArgumentException e) {
            return null;
        }
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

    /**
     * Boss-bar identity without its live parts: the heart HP of a mob bar
     * ("[EPIC] LVL4 Pig ❤8.48M" → "[EPIC] LVL4 Pig") and the countdown of an
     * event bar ("2x Essence Event: 12m 10s", "Soul Harvest 2x Souls (12m, 9s)").
     * Keying boosts on this stops every HP tick and timer tick logging a
     * boost_start/boost_end pair (1500 pairs in one 0.9.12 session).
     */
    private static final Pattern BAR_TIMER = Pattern.compile("(?i)\\s*:?\\s*(?:\\d+\\s*[hms]\\b[\\s,]*)+$");
    private static final Pattern BAR_PARENS = Pattern.compile("\\s*\\([^)]*\\)\\s*$");

    public static String bossBarKey(String title) {
        if (title == null) return "";
        String s = title;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 0x2764 || c == 0x2665) { s = s.substring(0, i); break; }
        }
        s = BAR_PARENS.matcher(s).replaceAll("");
        s = BAR_TIMER.matcher(s).replaceAll("");
        return s.replaceAll("\\s+", " ").trim();
    }

    /**
     * The captcha reading from a map-prompt reply: the JSON array after ANSWER:
     * (or the first array anywhere), one character per element, joined. Bench
     * 2026-09-03: asking for characters as an array is what stops Qwen3-VL-4B
     * turning "pnGe" into a word. Null when there is no array or it is empty.
     */
    private static final Pattern ANSWER_ARRAY = Pattern.compile("\\[([^\\]]*)\\]");
    private static final Pattern ARRAY_ITEM = Pattern.compile("\"([^\"]*)\"|'([^']*)'|([^,\\s\"']+)");

    public static String parseAnswerArray(String content, boolean preserveCase) {
        if (content == null) return null;
        int at = content.toUpperCase(Locale.ROOT).indexOf("ANSWER");
        Matcher m = ANSWER_ARRAY.matcher(content);
        boolean found = at >= 0 && m.find(at);
        if (!found) found = m.find(0);
        if (!found) return null;
        StringBuilder sb = new StringBuilder();
        Matcher it = ARRAY_ITEM.matcher(m.group(1));
        while (it.find()) {
            String s = it.group(1) != null ? it.group(1) : it.group(2) != null ? it.group(2) : it.group(3);
            if (s == null) continue;
            for (char c : s.toCharArray()) {
                if (!Character.isWhitespace(c)) sb.append(c);
            }
        }
        if (sb.length() == 0) return null;
        String out = sb.toString();
        return preserveCase ? out : out.toLowerCase(Locale.ROOT);
    }

    /**
     * Second guess for a case-sensitive captcha: flip the case of the first letter
     * whose upper and lower glyphs look alike ({@code ambiguous}), else the first
     * letter. Null when the answer has no letters.
     */
    public static String caseFlipAlt(String answer, String ambiguous) {
        if (answer == null || answer.isEmpty()) return null;
        String amb = ambiguous == null ? "" : ambiguous.toLowerCase(Locale.ROOT);
        int idx = -1;
        for (int i = 0; i < answer.length() && idx < 0; i++) {
            char c = answer.charAt(i);
            if (Character.isLetter(c) && amb.indexOf(Character.toLowerCase(c)) >= 0) idx = i;
        }
        for (int i = 0; i < answer.length() && idx < 0; i++) {
            if (Character.isLetter(answer.charAt(i))) idx = i;
        }
        if (idx < 0) return null;
        char c = answer.charAt(idx);
        char f = Character.isUpperCase(c) ? Character.toLowerCase(c) : Character.toUpperCase(c);
        return answer.substring(0, idx) + f + answer.substring(idx + 1);
    }

    /**
     * Second guess for a mixed letters/digits captcha (0.9.22): the server's alphabet
     * has digits (2026-09-03 17:38: the map read "pBb", the answer was "p8b"), so the
     * first character with a look-alike in {@code pairs} ("B8,O0,S5,Z2,I1,l1,G6,b6,g9,q9")
     * is swapped for its partner; with none, the case flip of {@link #caseFlipAlt}.
     */
    public static String lookalikeAlt(String answer, String pairs, String ambiguous) {
        if (answer == null || answer.isEmpty()) return null;
        if (pairs != null) {
            for (String pair : pairs.split("[,\\s]+")) {
                if (pair.length() != 2) continue;
                for (int i = 0; i < answer.length(); i++) {
                    char c = answer.charAt(i);
                    if (c == pair.charAt(0)) return answer.substring(0, i) + pair.charAt(1) + answer.substring(i + 1);
                    if (c == pair.charAt(1)) return answer.substring(0, i) + pair.charAt(0) + answer.substring(i + 1);
                }
            }
        }
        return caseFlipAlt(answer, ambiguous);
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

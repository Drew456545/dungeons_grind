package dev.drew.ycbotchallenge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns raw sidebar rows into structured currency amounts.
 *
 * EnchantedMC (and most dungeon boards) paint each row as a team prefix + optional
 * custom score name, so the visible string looks like {@code | 131.56B MONEY}.
 * Color codes, leading pipes, and label-first {@code MONEY: 75.1B} variants are
 * accepted. Parsing is pure string work — no Minecraft types — so it can be
 * unit-tested against captured board text.
 */
public final class SidebarParser {
    public record Hit(String currency, String rawAmount, double value, String line) {}

    private SidebarParser() {}

    /** Strip §/& formatting (including §x hex), collapse whitespace, drop leading bullets. */
    public static String strip(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String s = raw;
        s = s.replaceAll("(?i)§x(§[0-9a-f]){6}", "");
        s = s.replaceAll("§.", "");
        s = s.replaceAll("(?i)&[0-9a-fk-or]", "");
        s = s.replace('\u00A0', ' ').replace("\u200B", "").replace("\uFEFF", "");
        s = s.replaceAll("\\s+", " ").trim();
        s = s.replaceAll("^[|•>·➤·\\-]+\\s*", "");
        return s.trim();
    }

    /**
     * Parse every configured currency present in {@code lines}. First hit per
     * currency wins; later duplicate rows are ignored. Missing currencies are
     * simply absent from the result (callers keep their last known value).
     */
    public static Map<String, Hit> parseCurrencies(List<String> lines, Collection<String> currencies) {
        Map<String, Hit> out = new LinkedHashMap<>();
        if (lines == null || currencies == null || currencies.isEmpty()) return out;
        Pattern[] patterns = compile(currencies);
        List<String> names = normalizeNames(currencies);
        for (String line : lines) {
            String cleaned = strip(line);
            if (cleaned.isEmpty()) continue;
            Hit hit = match(cleaned, patterns, names);
            if (hit == null) continue;
            out.putIfAbsent(hit.currency, hit);
        }
        return out;
    }

    public static Hit parseLine(String line, Collection<String> currencies) {
        if (currencies == null || currencies.isEmpty()) return null;
        String cleaned = strip(line);
        if (cleaned.isEmpty()) return null;
        return match(cleaned, compile(currencies), normalizeNames(currencies));
    }

    private static Hit match(String cleaned, Pattern[] patterns, List<String> names) {
        for (Pattern p : patterns) {
            Matcher m = p.matcher(cleaned);
            if (!m.find()) continue;
            String name = m.group("name");
            String token = m.group("token");
            if (name == null || token == null) continue;
            String key = canonicalName(name, names);
            if (key == null) continue;
            Double v = Amounts.parse(token.trim());
            if (v == null) continue;
            return new Hit(key, token.trim(), v, cleaned);
        }
        return null;
    }

    /**
     * value-first ({@code 131.56B MONEY}) then label-first ({@code MONEY: 131.56B}).
     * Amount suffix is optional so {@code 235 SHARDS} still parses.
     */
    private static Pattern[] compile(Collection<String> currencies) {
        String alt = String.join("|", quoteNames(currencies));
        if (alt.isEmpty()) alt = "money";
        String token = "(?<token>[\\d,]+(?:\\.\\d+)?(?:\\s*[A-Za-z]{1,4})?)";
        return new Pattern[] {
            Pattern.compile("(?i)" + token + "\\s+(?<name>" + alt + ")\\b"),
            Pattern.compile("(?i)\\b(?<name>" + alt + ")\\s*:?\\s*" + token)
        };
    }

    private static List<String> quoteNames(Collection<String> currencies) {
        List<String> out = new ArrayList<>();
        for (String n : currencies) {
            if (n == null || n.isBlank()) continue;
            out.add(Pattern.quote(n.trim()));
        }
        out.sort((a, b) -> Integer.compare(b.length(), a.length()));
        return out;
    }

    private static List<String> normalizeNames(Collection<String> currencies) {
        List<String> out = new ArrayList<>();
        for (String n : currencies) {
            if (n == null || n.isBlank()) continue;
            out.add(n.trim().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static String canonicalName(String matched, List<String> names) {
        String m = matched.toLowerCase(Locale.ROOT);
        for (String n : names) {
            if (n.equals(m)) return n;
        }
        return m;
    }
}

package dev.drew.ycbotchallenge;

import java.util.regex.Matcher;

/**
 * 0.9.62: the matcher-group helpers five lore parsers each wrote for themselves. A named
 * group that the pattern lacks (a hand-edited config regex) is null, never an exception.
 */
public final class Groups {
    private Groups() {}

    /** The named group, or null when the pattern has no such group or it did not take part. */
    public static String group(Matcher m, String name) {
        try { return m.group(name); } catch (IllegalArgumentException | IllegalStateException e) { return null; }
    }

    /** The named group, else the indexed one, else null. */
    public static String groupOr(Matcher m, String name, int idx) {
        String g = group(m, name);
        if (g != null) return g;
        return m.groupCount() >= idx ? m.group(idx) : null;
    }

    /** The named group as an int ("1,234" allowed), or null. */
    public static Integer intGroup(Matcher m, String name) {
        return parseInt(group(m, name));
    }

    /** "1,234" -> 1234; null or unreadable -> null. */
    public static Integer parseInt(String s) {
        if (s == null) return null;
        try { return Integer.parseInt(s.replace(",", "").trim()); } catch (NumberFormatException e) { return null; }
    }
}

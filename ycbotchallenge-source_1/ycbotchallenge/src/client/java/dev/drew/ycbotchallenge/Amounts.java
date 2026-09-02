package dev.drew.ycbotchallenge;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses sidebar/chat amounts like {@code 1.25K}, {@code 14.5M}, {@code 58}. */
public final class Amounts {
    private static final Pattern TOKEN = Pattern.compile(
        "([\\d,]+(?:\\.\\d+)?)\\s*([KMBTQkmbtq]?)");

    private Amounts() {}

    public static Double parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim().replace("$", "").replace(",", "");
        Matcher m = TOKEN.matcher(s);
        if (!m.find()) return null;
        try {
            double n = Double.parseDouble(m.group(1));
            String suf = m.group(2);
            if (suf != null && !suf.isEmpty()) {
                n *= switch (suf.toUpperCase(Locale.ROOT)) {
                    case "K" -> 1e3;
                    case "M" -> 1e6;
                    case "B" -> 1e9;
                    case "T" -> 1e12;
                    case "Q" -> 1e15;
                    default -> 1.0;
                };
            }
            return n;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String format(double v) {
        double a = Math.abs(v);
        if (a >= 1e12) return trim(v / 1e12) + "T";
        if (a >= 1e9) return trim(v / 1e9) + "B";
        if (a >= 1e6) return trim(v / 1e6) + "M";
        if (a >= 1e3) return trim(v / 1e3) + "K";
        if (a >= 10) return String.format(Locale.ROOT, "%.0f", v);
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String trim(double v) {
        String s = String.format(Locale.ROOT, "%.2f", v);
        if (s.indexOf('.') >= 0) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return s;
    }
}

package dev.drew.ycbotchallenge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses sidebar/chat amounts like {@code 1.25K}, {@code 14.5M}, {@code 37.16UTG}, {@code 58}. */
public final class Amounts {
    /**
     * Number + optional short suffix, but do not swallow the following currency word.
     * {@code 235 SHARDS} is 235 (not suffix {@code SHAR}); {@code 131.56B} is billions.
     */
    private static final Pattern TOKEN = Pattern.compile(
        "([\\d,]+(?:\\.\\d+)?)(?:\\s*([A-Za-z]{1,4}))?(?![A-Za-z])");

    /**
     * Built-in suffix table (case-insensitive keys) in EnchantedMC's order:
     * K M B T Q Qa Qi Sx Sp Oc No Dc. "1.25Q" was a quadrillion in the 0.9.6 fail
     * line and Drew reports T → Q → Qa, so Qa is a quintillion (0.9.19: Qa used to
     * alias Q here, which would have read a Qa balance 1000× too small and tripped
     * the money-collapse rebirth detector). Override any of these under suffixScales.
     */
    private static final Map<String, Double> BUILTIN = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    static {
        BUILTIN.put("K", 1e3);
        BUILTIN.put("M", 1e6);
        BUILTIN.put("B", 1e9);
        BUILTIN.put("T", 1e12);
        BUILTIN.put("Q", 1e15);
        BUILTIN.put("QA", 1e18);
        BUILTIN.put("QI", 1e21);
        BUILTIN.put("SX", 1e24);
        BUILTIN.put("SP", 1e27);
        BUILTIN.put("OC", 1e30);
        BUILTIN.put("NO", 1e33);
        BUILTIN.put("DC", 1e36);
    }
    /** Config-provided overrides/additions (uppercase keys). */
    private static final Map<String, Double> EXTRA = new ConcurrentHashMap<>();
    private static final Set<String> warned = ConcurrentHashMap.newKeySet();

    private Amounts() {}

    /** Merge config-provided suffix overrides. Call after config load. */
    public static void configure(Map<String, Double> overrides) {
        EXTRA.clear();
        warned.clear();
        if (overrides == null) return;
        overrides.forEach((k, v) -> {
            if (k != null && v != null && v > 0) EXTRA.put(k.toUpperCase(Locale.ROOT), v);
        });
    }

    /** The suffix letters of an amount string ("1.25Qa" → "Qa", "58" → ""), for the evidence log. */
    public static String suffixOf(String raw) {
        if (raw == null) return "";
        Matcher m = TOKEN.matcher(raw.replace("$", "").trim());
        if (!m.find() || m.group(2) == null) return "";
        return m.group(2);
    }

    /** Scale for a suffix, or null if unknown (empty suffix => 1.0). */
    public static Double scaleFor(String suffix) {
        if (suffix == null || suffix.isEmpty()) return 1.0;
        Double v = EXTRA.get(suffix.toUpperCase(Locale.ROOT));
        if (v != null) return v;
        v = BUILTIN.get(suffix);
        if (v == null && warned.add(suffix.toUpperCase(Locale.ROOT))) {
            org.slf4j.LoggerFactory.getLogger("ycbotchallenge").warn(
                "Unknown amount suffix '{}' — add it under suffixScales in ycbotchallenge.json", suffix);
        }
        return v;
    }

    public static Double parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim().replace("$", "");
        Matcher m = TOKEN.matcher(s);
        if (!m.find()) return null;
        return tokenValue(m);
    }

    /** Every parseable amount token in the string, in order (bare numbers count — callers classify by keyword). */
    public static List<Double> parseAll(String raw) {
        List<Double> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        Matcher m = TOKEN.matcher(raw.replace("$", ""));
        while (m.find()) {
            Double v = tokenValue(m);
            if (v != null) out.add(v);
        }
        return out;
    }

    private static Double tokenValue(Matcher m) {
        try {
            double n = Double.parseDouble(m.group(1).replace(",", ""));
            String suf = m.group(2);
            Double scale = scaleFor(suf);
            if (scale == null) return null;
            return n * scale;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final double[] FORMAT_SCALES = {1e36, 1e33, 1e30, 1e27, 1e24, 1e21, 1e18, 1e15, 1e12, 1e9, 1e6, 1e3};
    private static final String[] FORMAT_LABELS = {"Dc", "No", "Oc", "Sp", "Sx", "Qi", "Qa", "Q", "T", "B", "M", "K"};

    public static String format(double v) {
        double a = Math.abs(v);
        for (int i = 0; i < FORMAT_SCALES.length; i++) {
            if (a >= FORMAT_SCALES[i]) return trim(v / FORMAT_SCALES[i]) + FORMAT_LABELS[i];
        }
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

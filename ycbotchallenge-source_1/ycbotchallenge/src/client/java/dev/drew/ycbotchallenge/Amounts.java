package dev.drew.ycbotchallenge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses sidebar/chat amounts like {@code 1.25K}, {@code 14.5M}, {@code 20.5QQ}, {@code 58},
 * and (0.9.25) learns the server's suffix ladder as it appears instead of guessing it.
 *
 * Three scale layers, highest precedence first: config {@code suffixScales} (manual),
 * learned entries (persisted in {@code config/ycbotchallenge-suffixes.json} with
 * provenance), and the built-in table of suffixes the server has actually printed.
 * A learned entry is {@code confirmed} when it came from a sidebar rung crossing whose
 * basis was itself confirmed ({@link #crossing}), and provisional when it is a rung
 * guess ({@link #rungGuess}) or chained off a provisional basis.
 */
public final class Amounts {
    /**
     * Number + optional short suffix, but do not swallow the following currency word.
     * {@code 235 SHARDS} is 235 (not suffix {@code SHAR}); {@code 131.56B} is billions.
     */
    private static final Pattern TOKEN = Pattern.compile(
        "([\\d,]+(?:\\.\\d+)?)(?:\\s*([A-Za-z]{1,4}))?(?![A-Za-z])");

    /**
     * Built-in suffix table (case-insensitive keys): only what EnchantedMC has printed,
     * K M B T Q QQ. "1.25Q" was a quadrillion in the 0.9.6 fail line; the quintillion is
     * written "QQ" (2026-09-03 18:43: "You need $20.xQQ Money to Rebirth." after a 2.66Q
     * balance — the unknown suffix parsed to nothing and the probe looped, 0.9.24).
     * Nothing above QQ is assumed: a guessed suffix can never fail to parse, so it could
     * never heal (0.9.19 guessed "Qa" for 1e18; the server says QQ). Rungs above QQ are
     * learned from the sidebar the moment the balance steps onto them (suffix_learned),
     * or provisionally from a chat line naming them first (suffix_guess), and corrected by
     * the next crossing (suffix_corrected). Manual overrides go under suffixScales.
     */
    private static final Map<String, Double> BUILTIN = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    static {
        BUILTIN.put("K", 1e3);
        BUILTIN.put("M", 1e6);
        BUILTIN.put("B", 1e9);
        BUILTIN.put("T", 1e12);
        BUILTIN.put("Q", 1e15);
        BUILTIN.put("QQ", 1e18);
    }
    /** Config-provided overrides/additions (uppercase keys). */
    private static final Map<String, Double> EXTRA = new ConcurrentHashMap<>();
    /** Learned from the server (uppercase keys), persisted by SuffixStore. */
    private static final Map<String, Learned> LEARNED = new ConcurrentHashMap<>();
    private static final Set<String> warned = ConcurrentHashMap.newKeySet();

    /** One learned suffix with its provenance. Public fields: Gson round-trips it. */
    public static final class Learned {
        public double scale;
        /** false = provisional (a rung guess, or a crossing chained off a provisional basis). */
        public boolean confirmed;
        /** "crossing" | "chained" | "rung". */
        public String via;
        /** The suffix the scale was derived from ("Q" for a Q→QQ crossing). */
        public String basis;
        /** The token that introduced it ("1.1QQ", "$20.5QQQ"). */
        public String raw;
        /** The row before the crossing ("903.74T"); null for a rung guess. */
        public String prevRaw;
        public long at;
    }

    /** A suffix and its scale. */
    public record Rung(String suffix, double scale) {}

    /** Verdict of {@link #crossing}: {@code learned} is null when rejected, {@code reason} says why. */
    public record Crossing(Learned learned, String reason, double ratio) {}

    private record FormatTable(double[] scales, String[] labels) {}
    private static volatile FormatTable formatTable;
    static {
        formatTable = buildFormatTable();
    }

    private Amounts() {}

    /** Merge config-provided suffix overrides. Call after config load. */
    public static void configure(Map<String, Double> overrides) {
        EXTRA.clear();
        warned.clear();
        if (overrides != null) {
            overrides.forEach((k, v) -> {
                if (k != null && v != null && v > 0) EXTRA.put(k.toUpperCase(Locale.ROOT), v);
            });
        }
        rebuildFormatTable();
    }

    /** Replace the learned layer (startup, from the suffix store). */
    public static void loadLearned(Map<String, Learned> entries) {
        LEARNED.clear();
        warned.clear();
        if (entries != null) {
            entries.forEach((k, v) -> {
                if (k != null && v != null && v.scale > 0) LEARNED.put(k.toUpperCase(Locale.ROOT), v);
            });
        }
        rebuildFormatTable();
    }

    /** Learn (or replace) one suffix; returns the previous learned entry, or null. */
    public static Learned learn(String suffix, Learned e) {
        if (suffix == null || suffix.isEmpty() || e == null || e.scale <= 0) return null;
        String key = suffix.toUpperCase(Locale.ROOT);
        Learned old = LEARNED.put(key, e);
        warned.remove(key);
        rebuildFormatTable();
        return old;
    }

    /** Forget one learned suffix; returns what was there, or null. */
    public static Learned forget(String suffix) {
        if (suffix == null || suffix.isEmpty()) return null;
        Learned old = LEARNED.remove(suffix.toUpperCase(Locale.ROOT));
        rebuildFormatTable();
        return old;
    }

    /** Copy of the learned layer, lowest scale first. */
    public static Map<String, Learned> learned() {
        List<Map.Entry<String, Learned>> es = new ArrayList<>(LEARNED.entrySet());
        es.sort((a, b) -> Double.compare(a.getValue().scale, b.getValue().scale));
        Map<String, Learned> out = new LinkedHashMap<>();
        for (Map.Entry<String, Learned> e : es) out.put(e.getKey(), e.getValue());
        return out;
    }

    /** Clear the learned layer (tests). */
    public static void resetLearned() {
        LEARNED.clear();
        rebuildFormatTable();
    }

    /**
     * Where a suffix's scale comes from: "config", "learned" (confirmed), "provisional",
     * "builtin" (also for a bare number), or null when unknown.
     */
    public static String confidence(String suffix) {
        if (suffix == null || suffix.isEmpty()) return "builtin";
        String key = suffix.toUpperCase(Locale.ROOT);
        if (EXTRA.containsKey(key)) return "config";
        Learned l = LEARNED.get(key);
        if (l != null) return l.confirmed ? "learned" : "provisional";
        if (BUILTIN.containsKey(suffix)) return "builtin";
        return null;
    }

    /** Known and not provisional. */
    public static boolean confirmed(String suffix) {
        String c = confidence(suffix);
        return c != null && !"provisional".equals(c);
    }

    public static boolean provisional(String suffix) {
        return "provisional".equals(confidence(suffix));
    }

    /** The suffix letters of an amount string ("1.25Qa" → "Qa", "58" → ""), for the evidence log. */
    public static String suffixOf(String raw) {
        if (raw == null) return "";
        Matcher m = TOKEN.matcher(raw.replace("$", "").trim());
        if (!m.find() || m.group(2) == null) return "";
        return m.group(2);
    }

    /** The numeric part of an amount string ("903.74T" → 903.74), or null. */
    public static Double mantissaOf(String raw) {
        if (raw == null) return null;
        Matcher m = TOKEN.matcher(raw.replace("$", "").trim());
        if (!m.find()) return null;
        try {
            return Double.parseDouble(m.group(1).replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** True when {@link #parse} would understand the suffix (empty = a bare number). */
    public static boolean knownSuffix(String suffix) {
        if (suffix == null || suffix.isEmpty()) return true;
        String key = suffix.toUpperCase(Locale.ROOT);
        return EXTRA.containsKey(key) || LEARNED.containsKey(key) || BUILTIN.containsKey(suffix);
    }

    /** Scale for a suffix, or null if unknown (empty suffix => 1.0). */
    public static Double scaleFor(String suffix) {
        if (suffix == null || suffix.isEmpty()) return 1.0;
        String key = suffix.toUpperCase(Locale.ROOT);
        Double v = EXTRA.get(key);
        if (v != null) return v;
        Learned l = LEARNED.get(key);
        if (l != null) return l.scale;
        v = BUILTIN.get(suffix);
        if (v == null && warned.add(key)) {
            org.slf4j.LoggerFactory.getLogger("ycbotchallenge").warn(
                "Unknown amount suffix '{}' — it will be learned from the sidebar, or add it under suffixScales", suffix);
        }
        return v;
    }

    /** The largest scale across all layers (ties: built-in, then learned, then config spelling). */
    public static Rung highestKnown() {
        String bestSfx = null;
        double best = 0;
        for (Map.Entry<String, Double> e : BUILTIN.entrySet()) {
            if (e.getValue() > best) { best = e.getValue(); bestSfx = e.getKey(); }
        }
        for (Map.Entry<String, Learned> e : LEARNED.entrySet()) {
            if (e.getValue().scale > best) { best = e.getValue().scale; bestSfx = labelOf(e.getKey(), e.getValue()); }
        }
        for (Map.Entry<String, Double> e : EXTRA.entrySet()) {
            if (e.getValue() > best) { best = e.getValue(); bestSfx = e.getKey(); }
        }
        return new Rung(bestSfx, best);
    }

    /**
     * The one provable moment for a new suffix: the money row moves from {@code prevRaw}
     * (value {@code prevValue}, suffix S1) to {@code raw} (suffix S2, mantissa m2) between
     * two one-second polls. If S2 is the next 1000× rung, m2 × scale(S1) × 1000 sits just
     * above prevValue. Accept iff S2 is a real, different suffix; the previous poll is at
     * most {@code maxGapMs} old (a stale prev after a lag fakes a jump); 1 ≤ m2 < 1000;
     * and ratio = m2 × candidate / prevValue is within [0.95, maxJump] (one kill lump can
     * land between polls; 18:43 log: 903.74T → 1.1Q, ratio 1.22). Adjacent rungs differ
     * by 1000× while the band spans ~20×, so a spend, a rebirth collapse or a two-rung skip
     * lands out of band. The result is confirmed only when the basis suffix was.
     */
    public static Crossing crossing(String prevRaw, Double prevValue, boolean prevConfirmed,
                                    String raw, long prevAgeMs, int maxGapMs, double maxJump) {
        String s2 = suffixOf(raw);
        Double m2 = mantissaOf(raw);
        if (s2.isEmpty() || m2 == null) return new Crossing(null, "no-suffix", 0);
        String s1 = suffixOf(prevRaw);
        Double m1 = mantissaOf(prevRaw);
        if (prevRaw == null || prevValue == null || prevValue <= 0 || m1 == null || m1 <= 0) {
            return new Crossing(null, "no-prev", 0);
        }
        if (s2.equalsIgnoreCase(s1)) return new Crossing(null, "same-suffix", 0);
        if (maxGapMs > 0 && prevAgeMs > maxGapMs) return new Crossing(null, "stale", 0);
        if (m2 < 1 || m2 >= 1000) return new Crossing(null, "mantissa", 0);
        Double s1Scale = s1.isEmpty() ? Double.valueOf(1.0) : (knownSuffix(s1) ? scaleFor(s1) : null);
        if (s1Scale == null || s1Scale <= 0) s1Scale = prevValue / m1;
        double candidate = s1Scale * 1000.0;
        double ratio = m2 * candidate / prevValue;
        if (ratio < 0.95 || ratio > Math.max(0.95, maxJump)) return new Crossing(null, "out-of-band", ratio);
        Learned l = new Learned();
        l.scale = candidate;
        l.confirmed = prevConfirmed;
        l.via = prevConfirmed ? "crossing" : "chained";
        l.basis = s1;
        l.raw = raw;
        l.prevRaw = prevRaw;
        l.at = System.currentTimeMillis();
        return new Crossing(l, "fit", ratio);
    }

    /**
     * A suffix named before the board ever showed it (a fail line quoting "$20.5QQQ" at a
     * 2.66Q balance; an enable with the row already on an unseen rung): assume the rung
     * above the highest known scale, provisionally. The balance has to climb through that
     * rung before it can afford anything quoted in it, and that crossing confirms or
     * corrects the guess.
     */
    public static Learned rungGuess(String suffix, String raw) {
        Rung top = highestKnown();
        Learned l = new Learned();
        l.scale = top.scale() * 1000.0;
        l.confirmed = false;
        l.via = "rung";
        l.basis = top.suffix();
        l.raw = raw;
        l.prevRaw = null;
        l.at = System.currentTimeMillis();
        return l;
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

    /** Server spelling for a learned key ("Sx" from its raw token) when it matches the key, else the key. */
    private static String labelOf(String key, Learned l) {
        String s = l != null ? suffixOf(l.raw) : "";
        return !s.isEmpty() && s.equalsIgnoreCase(key) ? s : key;
    }

    private static void rebuildFormatTable() {
        formatTable = buildFormatTable();
    }

    /** Merged label table, highest scale first; the earlier layer's spelling wins a tie. */
    private static FormatTable buildFormatTable() {
        List<Double> scales = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (Map.Entry<String, Double> e : BUILTIN.entrySet()) addLabel(scales, labels, e.getKey(), e.getValue());
        for (Map.Entry<String, Learned> e : LEARNED.entrySet()) {
            addLabel(scales, labels, labelOf(e.getKey(), e.getValue()), e.getValue().scale);
        }
        for (Map.Entry<String, Double> e : EXTRA.entrySet()) addLabel(scales, labels, e.getKey(), e.getValue());
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < scales.size(); i++) order.add(i);
        order.sort((a, b) -> Double.compare(scales.get(b), scales.get(a)));
        double[] s = new double[order.size()];
        String[] l = new String[order.size()];
        for (int i = 0; i < order.size(); i++) {
            s[i] = scales.get(order.get(i));
            l[i] = labels.get(order.get(i));
        }
        return new FormatTable(s, l);
    }

    private static void addLabel(List<Double> scales, List<String> labels, String label, double scale) {
        if (label == null || label.isEmpty() || scale <= 0) return;
        for (double s : scales) if (Math.abs(s - scale) <= scale * 1e-9) return;
        scales.add(scale);
        labels.add(label);
    }

    /** Human form on the current ladder; above the top rung the mantissa grows ("2500QQ" until a rung above QQ is known). */
    public static String format(double v) {
        FormatTable t = formatTable;
        double a = Math.abs(v);
        for (int i = 0; i < t.scales().length; i++) {
            if (a >= t.scales()[i]) return trim(v / t.scales()[i]) + t.labels()[i];
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

    /** Short ETA: "42s" under 90 s, "12m" under 90 min, else "1.5h" (shared by the HUD and the decision plan line). */
    public static String eta(double ms) {
        double s = ms / 1000.0;
        if (s < 90) return Math.round(s) + "s";
        double m = s / 60.0;
        if (m < 90) return Math.round(m) + "m";
        return String.format(Locale.ROOT, "%.1fh", m / 60.0);
    }
}

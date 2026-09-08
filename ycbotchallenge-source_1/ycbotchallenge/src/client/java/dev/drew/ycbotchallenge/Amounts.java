package dev.drew.ycbotchallenge;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
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
     * Number, then either its own exponent or a short suffix. The exponent branch comes
     * first so {@code 1.03235E93} cannot degrade to suffix {@code E} and leave the bare
     * {@code 93} to be read as the amount (0.9.61). The trailing lookahead stays outside
     * the group: it is what keeps {@code 235 SHARDS} at 235 (not suffix {@code SHAR}).
     */
    private static final Pattern TOKEN = Pattern.compile(
        "(?<num>[\\d,]+(?:\\.\\d+)?)"
      + "(?:[Ee](?<exp>[+-]?\\d{1,3})|\\s*(?<sfx>[A-Za-z]{1,4}))?(?![A-Za-z])");

    /**
     * The server's own ceiling: above this it stops using the suffix ladder and writes
     * the exponent instead ("1.03235E93"). Observed 2026-09-07 between 97.9NVG (9.79e91)
     * and 1.03615E92, with the two forms alternating as the balance crosses back.
     */
    public static final double DEFAULT_SCI_FROM = 1e92;

    /** Six significant figures, no {@code +} — the shape the server prints. */
    private static final String SCI_PATTERN = "0.#####E0";

    /** Value at or above which {@link #format} writes an exponent instead of a rung. */
    private static volatile double sciFrom = DEFAULT_SCI_FROM;

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
        configure(overrides, DEFAULT_SCI_FROM);
    }

    /** As {@link #configure(Map)}, also setting the value above which the server writes exponents. */
    public static void configure(Map<String, Double> overrides, double sciFromValue) {
        if (sciFromValue > 0) sciFrom = sciFromValue;
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

    /**
     * The suffix letters of an amount string ("1.25Qa" → "Qa", "58" → ""), for the
     * evidence log. An exponent token has none ("1.03235E93" → ""), which is the right
     * answer downstream: confidence("") is "builtin", so it is exactly known and never
     * provisional.
     */
    public static String suffixOf(String raw) {
        if (raw == null) return "";
        Matcher m = TOKEN.matcher(raw.replace("$", "").trim());
        if (!m.find() || m.group("sfx") == null) return "";
        return m.group("sfx");
    }

    /** The numeric part of an amount string ("903.74T" → 903.74, "1.03235E93" → 1.03235), or null. */
    public static Double mantissaOf(String raw) {
        if (raw == null) return null;
        Matcher m = TOKEN.matcher(raw.replace("$", "").trim());
        if (!m.find()) return null;
        try {
            return Double.parseDouble(m.group("num").replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The exponent of a self-describing amount ("1.03235E93" → 93), or null when the
     * token rides the suffix ladder instead. Needs no table: the scale is in the token,
     * so it can never be guessed wrong and never has to be learned.
     */
    public static Integer exponentOf(String raw) {
        if (raw == null) return null;
        Matcher m = TOKEN.matcher(raw.replace("$", "").trim());
        if (!m.find()) return null;
        String e = m.group("exp");
        if (e == null) return null;
        try {
            return Integer.valueOf(Integer.parseInt(e));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** True when the amount carries its own exponent - exact, and off the ladder. */
    public static boolean scientific(String raw) {
        return exponentOf(raw) != null;
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
        // Above the ceiling there are no new rungs to find; sciCrossing reads that pair instead.
        if (scientific(raw) || scientific(prevRaw)) return new Crossing(null, "scientific", 0);
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

    /**
     * The ladder's ceiling read against ground truth (0.9.61). The money row moves from a
     * suffix token ({@code prevRaw}, suffix S1, mantissa m1) to an exponent token
     * ({@code raw}) between two polls, because above {@link #DEFAULT_SCI_FROM} the server
     * stops using suffixes and writes the exponent itself. That new value is exact, so it
     * is a measurement of S1's scale rather than another inference from it.
     *
     * The verdict is judged against what the table already believes, never solved for
     * freely: m1 x believedScale should sit within the same [0.95, maxJump] band
     * {@link #crossing} uses. In band, the belief is proved and comes back confirmed - the
     * one proof a rung guess could never get, since a guessed suffix always parses and so
     * never fails its way to a correction ({@link #rungGuess}). A ratio near 1000x either
     * way says the belief is off by exactly one rung, which is the only way a chained guess
     * can be wrong, and that rung is corrected. Anything else is rejected out of band rather
     * than written: a free solve would happily confirm any suffix at all, and confidently.
     *
     * The entry is keyed on S1 - the suffix being proved, not the token that proved it -
     * and S1 is carried in {@code basis}.
     */
    public static Crossing sciCrossing(String prevRaw, String raw, long prevAgeMs,
                                       int maxGapMs, double maxJump) {
        Double v2 = scientific(raw) ? parse(raw) : null;
        if (v2 == null || v2 <= 0) return new Crossing(null, "not-scientific", 0);
        String s1 = suffixOf(prevRaw);
        Double m1 = mantissaOf(prevRaw);
        if (s1.isEmpty() || m1 == null || m1 <= 0) return new Crossing(null, "no-prev", 0);
        if (maxGapMs > 0 && prevAgeMs > maxGapMs) return new Crossing(null, "stale", 0);
        Double believed = scaleFor(s1);
        if (believed == null || believed <= 0) return new Crossing(null, "unknown-basis", 0);
        double hi = Math.max(0.95, maxJump);
        double ratio = v2 / (m1 * believed);
        double candidate = 0;
        if (inBand(ratio, hi)) candidate = believed;
        else if (inBand(ratio / 1000.0, hi)) candidate = believed * 1000.0;
        else if (inBand(ratio * 1000.0, hi)) candidate = believed / 1000.0;
        if (candidate <= 0) return new Crossing(null, "out-of-band", ratio);
        Learned l = new Learned();
        l.scale = candidate;
        l.confirmed = true;
        l.via = "sci-crossing";
        l.basis = s1;
        l.raw = raw;
        l.prevRaw = prevRaw;
        l.at = System.currentTimeMillis();
        return new Crossing(l, "fit", v2 / (m1 * candidate));
    }

    private static boolean inBand(double ratio, double hi) {
        return ratio >= 0.95 && ratio <= hi;
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
            String num = m.group("num").replace(",", "");
            String exp = m.group("exp");
            if (exp != null) {
                // Let parseDouble assemble it: n * Math.pow(10, e) rounds, the literal does not.
                double v = Double.parseDouble(num + "E" + exp);
                return Double.isFinite(v) ? Double.valueOf(v) : null;
            }
            Double scale = scaleFor(m.group("sfx"));
            if (scale == null) return null;
            return Double.parseDouble(num) * scale;
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

    /**
     * Human form on the current ladder ("2500QQ" while the mantissa still grows past the
     * top known rung), switching to the server's own exponent form at {@link #sciFrom} so
     * a formatted target and a chat line are the same string, character for character.
     */
    public static String format(double v) {
        FormatTable t = formatTable;
        double a = Math.abs(v);
        // DecimalFormat is not thread-safe and format() is called from the render and log
        // threads; it is not hot enough for the instance to be worth sharing.
        if (a >= sciFrom) {
            return new DecimalFormat(SCI_PATTERN, DecimalFormatSymbols.getInstance(Locale.ROOT)).format(v);
        }
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

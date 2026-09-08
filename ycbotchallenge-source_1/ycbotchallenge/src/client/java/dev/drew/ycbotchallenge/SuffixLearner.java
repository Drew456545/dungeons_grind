package dev.drew.ycbotchallenge;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 0.9.25: the money-suffix ladder learning, lifted out of StatsTracker in 0.9.62. Every
 * change of the sidebar money row's suffix is judged as a rung crossing, an exponent reading
 * proves the rung it just left (0.9.61), a GUI count ratio or a chat fail line can name a rung
 * first; the one place a scale is learned, confirmed or corrected persists it to the
 * {@link SuffixStore}. A corrected rung asks the owner to drop the prices learned on the old
 * scale ({@code dropPricesAbove}).
 */
public final class SuffixLearner extends BotModule {
    private final YCBotChallengeConfig cfg;
    private final Function<Double, List<String>> dropPricesAbove;

    public SuffixLearner(YCBotChallengeConfig cfg, Function<Double, List<String>> dropPricesAbove) {
        this.cfg = cfg;
        this.dropPricesAbove = dropPricesAbove;
    }

    private SuffixStore suffixStore;
    /** A rung crossing is judged against the previous poll only while it is this fresh. */
    private static final int SUFFIX_CROSSING_MAX_GAP_MS = 5000;
    /** Rungs an exponent reading has already settled; keeps a re-crossing from re-logging. */
    private final Set<String> sciCrossingSeen = new HashSet<>();

    /** Load the suffixes the sidebar taught us in earlier sessions (config overrides stay on top). */
    public void setSuffixStore(SuffixStore store) {
        this.suffixStore = store;
        Map<String, Amounts.Learned> all = store != null ? store.all() : Map.of();
        Amounts.loadLearned(all);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Amounts.Learned> e : all.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue().scale).append(e.getValue().confirmed ? "" : "/prov");
        }
        log("suffix_state_loaded", "count", all.size(), "suffixes", sb.length() > 0 ? sb.toString() : null);
    }

    /**
     * Every change of the money row's suffix is judged as a rung crossing
     * ({@link Amounts#crossing}) and logged (suffix_crossing) — on the known rungs too
     * (T→Q, Q→QQ once per rebirth cycle), so the rule proves itself on the live board.
     * Only an unconfirmed suffix is ever changed by it: a fit learns, confirms or
     * corrects; no fit on a suffix the table lacks takes the rung guess so money()
     * never goes stale.
     */
    public void noteMoneySuffix(String prevRaw, Double prevValue, long age, String raw) {
        // Above the server's ceiling the row carries its own exponent. That reading is exact,
        // so it proves the rung it just left instead of introducing one of its own (0.9.61).
        if (Amounts.scientific(raw)) {
            noteSciCrossing(prevRaw, raw, age);
            return;
        }
        String sfx = Amounts.suffixOf(raw);
        String prevSfx = Amounts.suffixOf(prevRaw);
        if (sfx.isEmpty() || sfx.equalsIgnoreCase(prevSfx)) return;
        Amounts.Crossing c = Amounts.crossing(prevRaw, prevValue, Amounts.confirmed(prevSfx),
            raw, age, SUFFIX_CROSSING_MAX_GAP_MS, cfg.suffixCrossingMaxJump);
        boolean fit = c.learned() != null;
        log("suffix_crossing", "from", prevSfx.isEmpty() ? null : prevSfx, "to", sfx, "raw", raw, "prevRaw", prevRaw,
            "verdict", fit ? "fit" : "rejected", "reason", c.reason(),
            "ratio", Num.r3(c.ratio()),
            "scale", fit ? c.learned().scale : null, "known", Amounts.confidence(sfx));
        if (!cfg.suffixLearningEnabled || Amounts.confirmed(sfx)) return;
        if (fit) {
            applyLearned(sfx, c.learned(), "sidebar", c.reason());
        } else if (!Amounts.knownSuffix(sfx)) {
            boolean stale = age > SUFFIX_CROSSING_MAX_GAP_MS;
            applyLearned(sfx, Amounts.rungGuess(sfx, raw), stale ? "sidebar-stale" : "sidebar", c.reason());
        }
    }

    /**
     * 0.9.31: a rung proven by a GUI count ratio (the Companion Eggs menu's 250× line) —
     * same learning path as a sidebar crossing, confirmed, persisted.
     */
    /**
     * 0.9.61: the money row stepped onto the server's exponent form ("1.03235E93"), which is
     * exact. That pins the scale of the suffix it just left - the one proof a rung guess could
     * never get from a crossing, since a guessed suffix always parses and so never fails its
     * way to a correction. Confirms or corrects the previous rung; never learns one of its own.
     */
    private void noteSciCrossing(String prevRaw, String raw, long age) {
        String s1 = Amounts.suffixOf(prevRaw);
        if (s1.isEmpty()) return;
        Amounts.Crossing c = Amounts.sciCrossing(prevRaw, raw, age, SUFFIX_CROSSING_MAX_GAP_MS,
            cfg.suffixCrossingMaxJump);
        boolean fit = c.learned() != null;
        Double known = Amounts.scaleFor(s1);
        boolean settled = fit && Amounts.confirmed(s1) && known != null
            && Math.abs(known - c.learned().scale) <= c.learned().scale * 1e-9;
        if (!settled || sciCrossingSeen.add(s1)) {
            log("suffix_crossing", "from", s1, "to", null, "via", "sci", "raw", raw, "prevRaw", prevRaw,
                "verdict", fit ? "fit" : "rejected", "reason", c.reason(),
                "ratio", Num.r3(c.ratio()),
                "scale", fit ? c.learned().scale : null, "known", Amounts.confidence(s1));
        }
        if (!cfg.suffixLearningEnabled || !fit || settled) return;
        applyLearned(s1, c.learned(), "sidebar-sci", c.reason());
    }

    public boolean learnSuffixFromGui(String sfx, Amounts.Learned e, String source) {
        if (!cfg.suffixLearningEnabled || sfx == null || e == null || Amounts.confirmed(sfx)) return false;
        applyLearned(sfx, e, source, "count-ratio");
        return true;
    }

    /** The one place a suffix scale is learned, confirmed or corrected; persists and logs. */
    public void applyLearned(String sfx, Amounts.Learned e, String source, String reason) {
        String key = sfx.toUpperCase(Locale.ROOT);
        Amounts.Learned old = Amounts.learn(key, e);
        if (old == null) {
            if ("rung".equals(e.via)) {
                log("suffix_guess", "suffix", key, "scale", e.scale, "via", e.via, "basis", e.basis,
                    "raw", e.raw, "source", source, "reason", reason);
            } else {
                log("suffix_learned", "suffix", key, "scale", e.scale, "via", e.via, "confirmed", e.confirmed,
                    "basis", e.basis, "raw", e.raw, "prevRaw", e.prevRaw);
            }
        } else if (Math.abs(old.scale - e.scale) <= e.scale * 1e-9) {
            if (!old.confirmed && e.confirmed) {
                log("suffix_confirmed", "suffix", key, "scale", e.scale, "raw", e.raw, "prevRaw", e.prevRaw);
            }
        } else {
            // The earlier scale was wrong: every provisional rung above it was built on it,
            // and every learned price expressed on it is wrong by the same factor. Forget
            // both; prices relearn from the next fail line. A sidebar crossing only ever
            // brings a guess here, but an exponent reading (0.9.61) can overrule a
            // confirmation too - it measures the rung rather than inferring it.
            List<String> forgotten = new ArrayList<>();
            for (Map.Entry<String, Amounts.Learned> le : Amounts.learned().entrySet()) {
                if (le.getKey().equals(key) || le.getValue().confirmed || le.getValue().scale <= old.scale) continue;
                Amounts.forget(le.getKey());
                if (suffixStore != null) suffixStore.remove(le.getKey());
                forgotten.add(le.getKey());
            }
            List<String> dropped = dropPricesAbove.apply(old.scale);
            log("suffix_corrected", "suffix", key, "oldScale", old.scale, "scale", e.scale, "via", e.via,
                "raw", e.raw, "prevRaw", e.prevRaw, "forgotten", forgotten, "dropped", dropped);
        }
        if (suffixStore != null) suffixStore.put(key, e);
    }

}

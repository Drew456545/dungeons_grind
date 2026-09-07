package dev.drew.ycbotchallenge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Running ballot for a captcha (0.9.26): every model reading of every render is a vote.
 * The first answer goes out once a few votes are in; voting continues in the background
 * so the second answer is the best-supported alternative rather than a coin flip.
 * 19:43 log: x4 read "Kra" (12/12, so temperature could not help) while x3 and x2 read
 * "KrA" — the ballot picks KrA; the old primary-render rule typed Kra. Pure, thread-safe,
 * no Minecraft types.
 *
 * <p>0.9.59: a vote carries the ORDER of the read that produced it (its launch index, not
 * its arrival), and equal counts rank by the lowest order. Two models fired together
 * disagree 1:1; the reader (order 0) is typed first whichever reply lands first, because
 * on the certified maps 3.6-flash is right more often (35/40) than 3.8-max (30/40).
 */
final class CaptchaBallot {
    record Vote(String reading, String render, double temperature, int order, String model) {}

    private final List<Vote> votes = new ArrayList<>();
    /** Reading -> count, in first-seen order. */
    private final Map<String, Integer> tally = new LinkedHashMap<>();
    /** Reading -> the lowest read order that produced it (the tie-break). */
    private final Map<String, Integer> firstOrder = new LinkedHashMap<>();

    /** A vote in arrival order (order = the number of votes before it). */
    synchronized void cast(String reading, String render, double temperature) {
        cast(reading, render, temperature, votes.size(), null);
    }

    /** A vote from the read launched {@code order}-th (0 = the reader's read A) by {@code model}. */
    synchronized void cast(String reading, String render, double temperature, int order, String model) {
        if (reading == null || reading.isBlank()) return;
        votes.add(new Vote(reading, render, temperature, order, model));
        tally.merge(reading, 1, Integer::sum);
        firstOrder.merge(reading, order, Math::min);
    }

    synchronized int reads() { return votes.size(); }

    synchronized int distinct() { return tally.size(); }

    /** Readings by votes, most first; equal counts rank by the earliest read order; {@code excluded} left out. */
    synchronized List<String> ranked(Collection<String> excluded) {
        List<Map.Entry<String, Integer>> es = new ArrayList<>(tally.entrySet());
        es.sort((a, b) -> {
            int byVotes = Integer.compare(b.getValue(), a.getValue());
            if (byVotes != 0) return byVotes;
            return Integer.compare(firstOrder.getOrDefault(a.getKey(), Integer.MAX_VALUE),
                                   firstOrder.getOrDefault(b.getKey(), Integer.MAX_VALUE));
        }); // stable: first-seen wins among equal orders
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : es) {
            if (excluded != null && excluded.contains(e.getKey())) continue;
            out.add(e.getKey());
        }
        return out;
    }

    synchronized String leader(Collection<String> excluded) {
        List<String> r = ranked(excluded);
        return r.isEmpty() ? null : r.get(0);
    }

    /** Copy of the tallies for the log ("KrA=2,Kra=1" ordering by votes). */
    synchronized Map<String, Integer> tallies() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String r : ranked(null)) out.put(r, tally.get(r));
        return out;
    }

    /** The render that first produced this reading, or null. */
    synchronized String renderOf(String reading) {
        if (reading == null) return null;
        for (Vote v : votes) if (reading.equals(v.reading())) return v.render();
        return null;
    }

    /** The model that first produced this reading, or null (0.9.59: the log names the second guess's model). */
    synchronized String modelOf(String reading) {
        if (reading == null) return null;
        for (Vote v : votes) if (reading.equals(v.reading())) return v.model();
        return null;
    }

    synchronized void clear() {
        votes.clear();
        tally.clear();
        firstOrder.clear();
    }
}

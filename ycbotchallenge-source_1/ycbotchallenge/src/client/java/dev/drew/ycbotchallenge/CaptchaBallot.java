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
 */
final class CaptchaBallot {
    record Vote(String reading, String render, double temperature) {}

    private final List<Vote> votes = new ArrayList<>();
    /** Reading -> count, in first-seen order (the tie-break). */
    private final Map<String, Integer> tally = new LinkedHashMap<>();

    synchronized void cast(String reading, String render, double temperature) {
        if (reading == null || reading.isBlank()) return;
        votes.add(new Vote(reading, render, temperature));
        tally.merge(reading, 1, Integer::sum);
    }

    synchronized int reads() { return votes.size(); }

    synchronized int distinct() { return tally.size(); }

    /** Readings by votes, most first; equal counts keep first-seen order; {@code excluded} left out. */
    synchronized List<String> ranked(Collection<String> excluded) {
        List<Map.Entry<String, Integer>> es = new ArrayList<>(tally.entrySet());
        es.sort((a, b) -> Integer.compare(b.getValue(), a.getValue())); // stable: first-seen wins ties
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

    synchronized void clear() {
        votes.clear();
        tally.clear();
    }
}

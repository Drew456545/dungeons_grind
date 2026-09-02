package dev.drew.ycbotchallenge;

/**
 * Pure economy rules: fail-chat targets, log-scaled zone readiness, pick among
 * affordable upgrades. No Minecraft types — unit-tested against captured strings.
 */
public final class Economy {
    private Economy() {}

    /**
     * Fail lines on this server report the REMAINING GAP, not the price:
     * "You need 781.04B Money to purchase the next sword upgrade" shrinks as you
     * earn (verified against session logs: 781.04B → 732.08B → 683.12B). The
     * absolute next-tier price is balance-at-fail + gap; null while the balance
     * is unknown (never guess).
     */
    public static Double priceFromFail(double gap, Double balAtFail) {
        return balAtFail != null ? balAtFail + gap : null;
    }

    /**
     * Unknown-price retry policy: after a successful buy the new tier's price is
     * unknown, and a human retries once their balance passes the OLD price again
     * ("it cost ~780B last time"). Never a poll, never an immediate reprobe.
     */
    public static boolean retryUnknownAllowed(Double lastPrice, Double bal, double growthPct) {
        return lastPrice != null && bal != null
            && bal >= lastPrice * (1.0 + Math.max(0.0, growthPct));
    }

    /** Milliseconds until the gap closes at the given earning rate, or null. */
    public static Double etaMs(Double need, Double ratePerMin) {
        if (need == null || need <= 0 || ratePerMin == null || ratePerMin <= 0) return null;
        return need / ratePerMin * 60_000.0;
    }

    /**
     * Log-lerp of median TTK from this zone's baseline down to {@code readyMs}.
     * 0 at a fresh ~40s stage, 1 at 2s; a high-sword enable whose baseline is
     * already near ready snaps to 1.
     */
    public static double zoneReadiness(Double medianTtkMs, Double baselineMs, double readyMs) {
        if (medianTtkMs == null || medianTtkMs <= 0) return 0;
        double ready = readyMs > 0 ? readyMs : 2000;
        if (medianTtkMs <= ready) return 1.0;
        double baseline = (baselineMs != null && baselineMs > 0) ? baselineMs : medianTtkMs;
        if (baseline <= ready * 1.2) return 1.0;
        if (medianTtkMs >= baseline) return 0;
        double den = Math.log(baseline) - Math.log(ready);
        if (den <= 1e-9) return 1.0;
        double r = (Math.log(baseline) - Math.log(medianTtkMs)) / den;
        if (r < 0) return 0;
        if (r > 1) return 1;
        return r;
    }

    /**
     * Among <em>currently affordable</em> kinds, pick by TTK weights
     * ({@code swordWeight = 1-R}, {@code zoneWeight = R}). Zone is not chosen
     * while {@code R < minR} even if it is the only affordable upgrade.
     *
     * @return {@code "sword"}, {@code "zone"}, or {@code null} to wait
     */
    public static String chooseBuyKind(
            boolean swordOpen, boolean zoneOpen,
            boolean swordAffordable, boolean zoneAffordable,
            double R, double minR) {
        if (!swordOpen && !zoneOpen) return null;
        boolean canSword = swordOpen && swordAffordable;
        boolean canZone = zoneOpen && zoneAffordable && R >= minR;
        if (canSword && canZone) return R >= 0.5 ? "zone" : "sword";
        if (canZone) return "zone";
        if (canSword) return "sword";
        return null;
    }

    /** What we're working toward (HUD), independent of affordability. */
    public static String preferredKind(boolean swordOpen, boolean zoneOpen, double R) {
        if (!swordOpen && !zoneOpen) return null;
        if (!zoneOpen) return swordOpen ? "sword" : null;
        if (!swordOpen) return "zone";
        return R >= 0.5 ? "zone" : "sword";
    }

    /** Snapshot covers the next-tier price. Unknown price is never "affordable". */
    public static boolean knownAffordable(Double need, Double bal) {
        return need != null && bal != null && bal + 1e-6 >= need;
    }

    /**
     * Hard cap between kill-driven buys of the same kind. A success follow-up
     * (immediate re-run to learn the next tier) always passes.
     */
    public static boolean cooldownElapsed(long nowMs, long lastSendAt, int minIntervalMs, boolean followUp) {
        if (followUp) return true;
        if (lastSendAt <= 0) return true;
        int min = Math.max(0, minIntervalMs);
        return nowMs - lastSendAt >= min;
    }

    /**
     * Wait {@code minExtra} further kills after the one that first made the
     * upgrade affordable (0 = buy on the crossing kill's eval).
     */
    public static boolean extraKillsReached(int killsNow, int affordableAtKill, int minExtra) {
        if (minExtra <= 0) return true;
        if (affordableAtKill < 0) return false;
        return killsNow - affordableAtKill >= minExtra;
    }

    /** True once {@code settleMs} have passed since a spend (or there was no spend). */
    public static boolean sidebarSettled(long nowMs, long lastSpendAt, int settleMs) {
        if (lastSpendAt <= 0) return true;
        return nowMs - lastSpendAt >= Math.max(0, settleMs);
    }
}

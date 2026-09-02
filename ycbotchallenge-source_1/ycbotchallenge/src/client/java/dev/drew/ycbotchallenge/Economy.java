package dev.drew.ycbotchallenge;

/**
 * Pure economy rules: fail-chat targets, sword-vs-zone by price ratio, pick among
 * affordable upgrades. No Minecraft types — unit-tested against captured strings.
 */
public final class Economy {
    private Economy() {}

    /**
     * Fail lines on this server report the REMAINING GAP, not the price:
     * "You need 781.04B Money to purchase the next sword upgrade" shrinks as you
     * earn (verified against session logs: 781.04B → 732.08B → 683.12B). Same for
     * rebirth: "You need $29.99T Money to Rebirth." at 8.04B ⇒ target ≈ 30T.
     * Absolute next-tier price is balance-at-fail + gap; null while the balance
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
     * Zone when the next sword costs more than {@code ratio} × the next zone
     * (default 1.25). Missing prices never prefer zone.
     */
    public static boolean preferZone(Double swordTarget, Double zoneTarget, double ratio) {
        if (swordTarget == null || zoneTarget == null || zoneTarget <= 0) return false;
        return swordTarget > Math.max(0.0, ratio) * zoneTarget;
    }

    /**
     * Log-lerp of median TTK from this zone's baseline down to {@code readyMs}.
     * HUD-only; buy choice uses {@link #preferZone}.
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
     * Among currently affordable kinds, pick by the 1.25× sword/zone price ratio.
     * Rebirth is chosen by the controller before this is consulted.
     *
     * @return {@code "sword"}, {@code "zone"}, or {@code null} to wait
     */
    public static String chooseBuyKind(
            boolean swordOpen, boolean zoneOpen,
            boolean swordAffordable, boolean zoneAffordable,
            Double swordTarget, Double zoneTarget, double zoneOverSwordRatio) {
        if (!swordOpen && !zoneOpen) return null;
        boolean canSword = swordOpen && swordAffordable;
        boolean canZone = zoneOpen && zoneAffordable;
        if (canSword && canZone) {
            return preferZone(swordTarget, zoneTarget, zoneOverSwordRatio) ? "zone" : "sword";
        }
        if (canZone) return "zone";
        if (canSword) return "sword";
        return null;
    }

    /** What we're working toward (HUD), independent of affordability. */
    public static String preferredKind(
            boolean swordOpen, boolean zoneOpen,
            Double swordTarget, Double zoneTarget, double zoneOverSwordRatio) {
        if (!swordOpen && !zoneOpen) return null;
        if (!zoneOpen) return swordOpen ? "sword" : null;
        if (!swordOpen) return "zone";
        return preferZone(swordTarget, zoneTarget, zoneOverSwordRatio) ? "zone" : "sword";
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

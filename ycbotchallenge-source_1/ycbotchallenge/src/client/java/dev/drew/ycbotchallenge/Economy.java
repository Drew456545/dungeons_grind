package dev.drew.ycbotchallenge;

/**
 * Pure economy rules: fail-chat targets, the hard TTK zone gate, sword-vs-zone by
 * price ratio, pick among affordable upgrades, cooldown relaxation. No Minecraft
 * types — unit-tested against captured strings.
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
     * Effective TTK for the zone gate: the DPS-predicted whole-mob TTK of the mob
     * being cooked when available (readable seconds into a fresh stage), else the
     * rolling kill median, else unknown.
     */
    public static Double effectiveTtkMs(Double predictedMs, Double medianMs) {
        if (predictedMs != null && predictedMs > 0) return predictedMs;
        if (medianMs != null && medianMs > 0) return medianMs;
        return null;
    }

    /**
     * Hard zone gate: zone is allowed only with a KNOWN effective TTK at or under
     * {@code maxTtkMs}, affordable or not. Unknown TTK refuses (wait for data);
     * {@code maxTtkMs <= 0} disables the gate. This is what keeps the sword ahead
     * of the stage — the 0.9.5 spiral went 0.25s → 90s TTK over three zone buys.
     */
    public static boolean zoneAllowed(Double ttkMs, int maxTtkMs) {
        if (maxTtkMs <= 0) return true;
        return ttkMs != null && ttkMs > 0 && ttkMs <= maxTtkMs;
    }

    /** HUD/log readiness: 1.0 while the gate is open, {@code max/ttk} above it, 0 while unknown. */
    public static double zoneReadiness(Double ttkMs, int maxTtkMs) {
        if (ttkMs == null || ttkMs <= 0) return 0;
        if (maxTtkMs <= 0 || ttkMs <= maxTtkMs) return 1.0;
        return maxTtkMs / ttkMs;
    }

    /**
     * Per-kind send cap: the backstop {@code capMs}, collapsed to {@code floorMs}
     * while the balance is at least {@code relaxMult} × the kind's last known price
     * (early-rebirth snowball: a 60s hold costs a 10× balance swing).
     */
    public static int effectiveCooldownMs(int capMs, int floorMs, Double bal, Double lastPrice, double relaxMult) {
        int cap = Math.max(0, capMs);
        int floor = Math.max(0, Math.min(floorMs, cap));
        if (relaxMult <= 0 || bal == null || lastPrice == null || lastPrice <= 0) return cap;
        return bal >= relaxMult * lastPrice ? floor : cap;
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

    /**
     * Re-aim threshold that grows with distance: a person walking at a mob 8 blocks
     * out does not micro-correct every 300ms; the aim only has to be right inside
     * {@code finalBlocks} of reach. Returns {@code base} there, up to
     * {@code base × farMult} at {@code farBlocks} beyond reach and further.
     */
    public static double reacquireThresholdDeg(double base, double dist, double reach,
                                               double farMult, double farBlocks, double finalBlocks) {
        double beyond = dist - reach;
        if (beyond <= finalBlocks) return base;
        double f = 1.0 + (Math.max(1.0, farMult) - 1.0) * Math.min(1.0, beyond / Math.max(0.1, farBlocks));
        return base * f;
    }

    /** Breaks are bimodal (a short stretch or a real walk-away), never always 1–4 minutes. */
    public static String breakKind(double roll, double shortChance) {
        return roll < shortChance ? "short" : "long";
    }

    /**
     * Buy hesitation only for long saves: the price must have been known for at least
     * {@code minSaveMs}, and the balance must not dwarf it (that is the post-rebirth
     * snowball, where every upgrade is cheap and waiting is a real loss).
     */
    public static boolean hesitationApplies(long priceSeenAt, long now, int minSaveMs,
                                            Double bal, Double price, double relaxMult) {
        if (priceSeenAt <= 0 || now - priceSeenAt < Math.max(0, minSaveMs)) return false;
        if (bal != null && price != null && price > 0 && relaxMult > 0 && bal >= relaxMult * price) return false;
        return true;
    }

    /** A deferred probe (/rebirth seed or re-probe) is due after both a kill count and a delay. */
    public static boolean probeDue(int killsSince, int minKills, long msSince, long minDelayMs) {
        return killsSince >= Math.max(0, minKills) && msSince >= Math.max(0, minDelayMs);
    }

    /** True once {@code settleMs} have passed since a spend (or there was no spend). */
    public static boolean sidebarSettled(long nowMs, long lastSpendAt, int settleMs) {
        if (lastSpendAt <= 0) return true;
        return nowMs - lastSpendAt >= Math.max(0, settleMs);
    }
}

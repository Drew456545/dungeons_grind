package dev.drew.ycbotchallenge;

import java.util.Locale;

/**
 * Pure economy rules: fail-chat targets, log-scaled zone readiness, pick among
 * affordable upgrades. No Minecraft types — unit-tested against captured strings.
 */
public final class Economy {
    private Economy() {}

    /**
     * {@code You need 96.56B more} is a remaining gap. {@code You need 277.81B
     * Money to purchase the next sword upgrade} is the absolute next-tier price.
     */
    public static boolean isGapNeed(String text) {
        if (text == null) return false;
        String l = text.toLowerCase(Locale.ROOT);
        return l.contains(" more") || l.contains("need more")
            || l.contains("remaining") || l.contains("left")
            || l.contains("short of");
    }

    /**
     * Next-tier price from a fail amount. Gap phrases add current balance;
     * purchase/buy/unlock phrasing is the total cost as written.
     */
    public static Double targetFromFail(double amount, Double balAtFail, String text) {
        if (isGapNeed(text)) {
            return balAtFail != null ? balAtFail + amount : null;
        }
        return amount;
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
}

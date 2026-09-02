package dev.drew.ycbotchallenge;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Log-normal delays — humans are not uniform between min and max. With ninja
 * humanization on, the hard clamp becomes a soft squash (no pile-up at exact
 * bounds), a rare heavy tail produces real outlier pauses, each session rolls
 * its own bounds multiplier, and means drift upward with uptime (fatigue).
 */
final class HumanTiming {
    private HumanTiming() {}

    private static boolean ninja = false;
    private static double sessionScale = 1.0;
    private static long sessionStartMs = System.currentTimeMillis();
    private static double fatigueRatePerHour = 0.0;
    private static double tailChance = 0.0;
    private static double softMarginPct = 0.0;

    /** Re-roll the session identity. Called when the bot is enabled. */
    static void beginSession(YCBotChallengeConfig cfg) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        ninja = cfg.ninja;
        sessionScale = ninja ? 1.0 + (rng.nextDouble() * 2 - 1) * cfg.sessionJitterPct : 1.0;
        fatigueRatePerHour = ninja ? Math.max(0, cfg.fatiguePerHour) : 0.0;
        tailChance = ninja ? Math.max(0, cfg.tailChancePerDelay) : 0.0;
        softMarginPct = ninja ? Math.max(0, cfg.softClampMarginPct) : 0.0;
        sessionStartMs = System.currentTimeMillis();
    }

    private static double drift() {
        if (!ninja) return 1.0;
        double hours = (System.currentTimeMillis() - sessionStartMs) / 3600_000.0;
        return sessionScale * (1.0 + fatigueRatePerHour * hours);
    }

    static long logNormalMs(int minMs, int maxMs) {
        if (maxMs <= minMs) return minMs;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double d = drift();
        double lo = Math.max(1, minMs) * d;
        double hi = Math.max(lo + 1, maxMs * d);
        double mean = Math.log(Math.sqrt(lo * hi));
        double sigma = Math.max(0.12, Math.log(hi / lo) / 4.0);
        double sample = Math.exp(mean + sigma * rng.nextGaussian());
        double range = hi - lo;
        if (tailChance > 0 && rng.nextDouble() < tailChance) {
            // heavy tail: a genuine outlier (zoned out, coughed, doorbell)
            sample = hi + range * (1.0 + 3.0 * rng.nextDouble());
        } else if (sample < lo) {
            sample = lo - (lo - sample) * softMarginPct * rng.nextDouble();
        } else if (sample > hi) {
            sample = hi + (sample - hi) * softMarginPct * rng.nextDouble();
        }
        return Math.round(Math.max(1, sample));
    }

    static int ticks(int minInclusive, int maxInclusive) {
        if (maxInclusive <= minInclusive) return minInclusive;
        return ThreadLocalRandom.current().nextInt(minInclusive, maxInclusive + 1);
    }
}

package dev.drew.ycbotchallenge;

import java.util.concurrent.ThreadLocalRandom;

/** Log-normal delays — humans are not uniform between min and max. */
final class HumanTiming {
    private HumanTiming() {}

    static long logNormalMs(int minMs, int maxMs) {
        if (maxMs <= minMs) return minMs;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double lo = Math.max(1, minMs);
        double hi = Math.max(lo + 1, maxMs);
        double mean = Math.log(Math.sqrt(lo * hi));
        double sigma = Math.max(0.12, Math.log(hi / lo) / 4.0);
        double sample = Math.exp(mean + sigma * rng.nextGaussian());
        return Math.round(Math.max(lo, Math.min(hi, sample)));
    }

    static int ticks(int minInclusive, int maxInclusive) {
        if (maxInclusive <= minInclusive) return minInclusive;
        return ThreadLocalRandom.current().nextInt(minInclusive, maxInclusive + 1);
    }
}

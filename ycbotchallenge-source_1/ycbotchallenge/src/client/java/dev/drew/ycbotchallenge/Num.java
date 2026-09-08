package dev.drew.ycbotchallenge;

/**
 * 0.9.62: rounding for the log that cannot saturate. {@code Math.round(x * 100.0) / 100.0}
 * clamps at {@code Long.MAX_VALUE} and prints {@code 9.223372036854776E16} for any ratio
 * past 9e16 - the zone_back_candidate ratio and the upgrade pct of 2026-09-08 read exactly
 * that while the real numbers were 1e52 and 1e30. Past {@link #SAFE} (or non-finite) the
 * value passes through untouched; six significant figures are plenty for a log row.
 */
public final class Num {
    private Num() {}

    /** |v| above this is left as it is: the rounding would saturate. */
    public static final double SAFE = 1e15;

    public static double round(double v, double scale) {
        if (Double.isNaN(v) || Double.isInfinite(v) || Math.abs(v) > SAFE) return v;
        return Math.round(v * scale) / scale;
    }

    /** One decimal. */
    public static double r1(double v) { return round(v, 10.0); }
    /** Two decimals. */
    public static double r2(double v) { return round(v, 100.0); }
    /** Three decimals. */
    public static double r3(double v) { return round(v, 1000.0); }

    public static Double r1(Double v) { return v == null ? null : r1(v.doubleValue()); }
    public static Double r2(Double v) { return v == null ? null : r2(v.doubleValue()); }
    public static Double r3(Double v) { return v == null ? null : r3(v.doubleValue()); }
}

package dev.drew.ycbotchallenge;

import java.util.ArrayDeque;

/**
 * The earning rate (0.9.62, lifted out of StatsTracker). Two estimators: the exact
 * "Reward Summary (60s)" money line, smoothed as an EMA (alpha 0.5) and preferred whenever
 * one has been seen; else the slope of the balance samples over the trailing five minutes.
 *
 * <p>Neither survives a rebirth: the summary EMA carried 1.39E100 into a cycle whose
 * balance was 0 (2026-09-08 05:39:30 reset, 05:40:03 summary of the old cycle's kills) and
 * every zone_back_candidate of the climb compared against it, halving once per "+ 0 Money"
 * window. {@link #reset} clears both, and a summary whose window spans the reset is
 * discarded. Pure, for the checks.
 */
public final class IncomeEstimator {
    private double summaryRatePerMs = 0;
    private long summaryWindowMs = 60_000;
    /** (timeMs, balance) samples from the sidebar row, for the slope. */
    private final ArrayDeque<double[]> samples = new ArrayDeque<>();
    private long resetAt = 0;

    /** The "Reward Summary: (60s)" header names the window the money line covers. */
    public void onSummaryWindow(int seconds) {
        if (seconds > 0) summaryWindowMs = seconds * 1000L;
    }

    public long summaryWindowMs() { return summaryWindowMs; }

    /**
     * A summary money line. Returns the per-ms sample it took, or null when the window spans
     * the last reset (the old cycle's kills) and the line was discarded.
     */
    public Double onSummaryMoney(double earned, long now) {
        if (resetAt != 0 && now - resetAt < summaryWindowMs) return null;
        double sample = earned / Math.max(1, summaryWindowMs);
        summaryRatePerMs = summaryRatePerMs <= 0 ? sample : 0.5 * summaryRatePerMs + 0.5 * sample;
        return sample;
    }

    /** A balance reading (deduped within 5 s, at most 100 kept). */
    public void onBalance(double bal, long now) {
        if (!samples.isEmpty()) {
            double[] last = samples.peekLast();
            if (Math.abs(last[1] - bal) < 1e-9 && now - last[0] < 5_000) return;
        }
        samples.addLast(new double[]{now, bal});
        while (samples.size() > 100) samples.removeFirst();
    }

    /** A rebirth (or any economy reset): both estimators belong to the old cycle. */
    public void reset(long now) {
        summaryRatePerMs = 0;
        samples.clear();
        resetAt = now;
    }

    public long resetAt() { return resetAt; }

    /** True once a full summary window has passed since the reset (or there was none). */
    public boolean settled(long now) {
        return resetAt == 0 || now - resetAt >= summaryWindowMs;
    }

    /** Earning rate per minute: the summary rate when seen, else the balance slope; null when unknown. */
    public Double perMinute(long now) {
        if (summaryRatePerMs > 0) return summaryRatePerMs * 60_000.0;
        return slopePerMinute(now);
    }

    /** Earning slope (money/min) over the trailing ~5 min of balance samples; null when unknown or falling. */
    public Double slopePerMinute(long now) {
        while (samples.size() > 2 && now - samples.peekFirst()[0] > 300_000) {
            samples.removeFirst();
        }
        if (samples.size() < 2) return null;
        double[] f = samples.peekFirst();
        double[] l = samples.peekLast();
        double dtMin = (l[0] - f[0]) / 60_000.0;
        if (dtMin < 0.5) return null;
        double slope = (l[1] - f[1]) / dtMin;
        // purchases create negative steps; report only the positive earning rate
        return slope > 0 ? slope : null;
    }
}

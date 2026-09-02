package dev.drew.ycbotchallenge;

/**
 * Chat-driven money book. Exact anchors only: {@code /bal} replies, 60s
 * reward-summary accrual, fail-implied balances (price − gap), and debits on
 * confirmed buys. Between anchors the live estimate grows at the trailing
 * summary rate, frozen after {@link #MAX_PROJECT_MS} without a fresh anchor
 * (no summaries arriving means no income anyway). No Minecraft types — unit-tested.
 */
public final class MoneyBook {
    /** Don't project the estimate further than this past the last exact anchor. */
    static final long MAX_PROJECT_MS = 90_000;

    private Double exact;
    private long exactAt;
    private double ratePerMs;
    private long summaryWindowMs = 60_000;

    /** First exact balance of the session (a /bal reply). */
    public void seed(double value, long now) {
        anchor(value, now);
    }

    /** Exact balance truth: a /bal reply, or price − gap from a fail line. */
    public void anchor(double value, long now) {
        exact = Math.max(0, value);
        exactAt = now;
    }

    /**
     * Accrue a reward-summary payout ("+ X Money" over the last windowMs). The
     * window overlaps time already covered by the last anchor, so only the fresh
     * fraction is added — re-anchoring never double-counts.
     */
    public void accrue(double earned, long windowMs, long now) {
        noteSummaryRate(earned, windowMs);
        if (exact == null) return;
        long w = windowMs > 0 ? windowMs : 60_000L;
        double frac = exactAt <= 0 ? 1.0 : Math.min(1.0, Math.max(0.0, (double) (now - exactAt) / w));
        anchor(exact + earned * frac, now);
    }

    /** Trailing income rate from summary windows (EMA-smoothed). */
    public void noteSummaryRate(double earned, long windowMs) {
        noteSummaryWindow(windowMs);
        double sample = earned / Math.max(1, summaryWindowMs);
        ratePerMs = ratePerMs <= 0 ? sample : 0.5 * ratePerMs + 0.5 * sample;
    }

    /** Record the summary window length without recording a rate sample. */
    public void noteSummaryWindow(long windowMs) {
        if (windowMs > 0) summaryWindowMs = windowMs;
    }

    /** Tentative debit after a confirmed purchase (a /bal re-seed makes it exact). */
    public void debit(double amount, long now) {
        if (exact == null) return;
        anchor(exact - amount, now);
    }

    /**
     * Live estimate: last exact anchor plus trailing-rate accrual, frozen after
     * {@link #MAX_PROJECT_MS}. Null while no anchor has ever landed.
     */
    public Double estimate(long now) {
        if (exact == null) return null;
        long elapsed = Math.max(0, Math.min(now - exactAt, MAX_PROJECT_MS));
        return Math.max(0, exact + ratePerMs * elapsed);
    }

    public Double exact() {
        return exact;
    }

    public double ratePerMs() {
        return ratePerMs;
    }

    public long summaryWindowMs() {
        return summaryWindowMs;
    }
}

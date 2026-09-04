package dev.drew.ycbotchallenge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One buy decision (0.9.33), produced by {@link Economy#decide} from plain facts and
 * consumed by the controller (behaviour), the JSONL log ({@link #kv}) and the HUD
 * ({@link #hudPlan}) — so the three can never disagree about what runs next and why.
 *
 * <p>{@code action}: {@code buy} (type the kind's command), {@code probe} (type it to
 * learn the price — it buys if affordable), {@code wait} (hold, {@code reason} says why),
 * {@code none} (nothing left to buy). {@code gate}: {@code open} / {@code hard} /
 * {@code unknown} (0.9.33 tri-state zone gate) with {@code gateVia} naming the evidence
 * ({@code median}, {@code cook}, {@code kill}, {@code predicted}, {@code none}).
 */
public record Decision(
        String action, String kind, String reason,
        String gate, String gateVia, Double ttkMs, Integer patienceMs, Integer stageKills,
        Double zoneGap, String zoneGapVia, Double swordPct,
        Double gain, String gainVia, Double waitMs, long at,
        String eggs) {

    /** The 0.9.33-0.9.35 shape: no egg annotation. */
    public Decision(String action, String kind, String reason,
                    String gate, String gateVia, Double ttkMs, Integer patienceMs, Integer stageKills,
                    Double zoneGap, String zoneGapVia, Double swordPct,
                    Double gain, String gainVia, Double waitMs, long at) {
        this(action, kind, reason, gate, gateVia, ttkMs, patienceMs, stageKills,
            zoneGap, zoneGapVia, swordPct, gain, gainVia, waitMs, at, null);
    }

    public static final String BUY = "buy";
    public static final String PROBE = "probe";
    public static final String WAIT = "wait";
    public static final String NONE = "none";

    /**
     * 0.9.35: the companion batch. A kind like any other to the economy, the log and the
     * HUD, but never a typed command - it is a walk to the egg and a GUI visit, so the
     * typed overlays (first kills, cooldown, hesitation) must not apply. See {@link #actsTyped}.
     */
    public static final String KIND_COMPANION = "companion";

    /** True when the controller should type {@code kind}'s command (buy or probe). */
    public boolean acts() {
        return kind != null && (BUY.equals(action) || PROBE.equals(action));
    }

    public boolean isBuy() { return BUY.equals(action); }

    /** True when the controller should TYPE this kind's command; a companion buy is a visit, not a command. */
    public boolean actsTyped() { return acts() && !KIND_COMPANION.equals(kind); }

    /** True when this decision hands the buy to the companion controller. */
    public boolean actsCompanion() { return acts() && KIND_COMPANION.equals(kind); }

    public boolean gateHard() { return "hard".equals(gate); }

    /** The same decision held back by a controller-side overlay (first kills, cooldown, hesitation). */
    public Decision hold(String holdReason, Double holdWaitMs) {
        return new Decision(WAIT, kind, holdReason, gate, gateVia, ttkMs, patienceMs, stageKills,
            zoneGap, zoneGapVia, swordPct, gain, gainVia, holdWaitMs, at, eggs);
    }

    /** A copy with the outcome fields set (used by {@link Economy#decide} on a base carrying the gate/gap facts). */
    public Decision with(String newAction, String newKind, String newReason, Double newGain, String newGainVia, Double newWaitMs) {
        return new Decision(newAction, newKind, newReason, gate, gateVia, ttkMs, patienceMs, stageKills,
            zoneGap, zoneGapVia, swordPct, newGain, newGainVia, newWaitMs, at, eggs);
    }

    /**
     * 0.9.36: the same decision annotated with why the egg post-pass declined ({@code blocked},
     * {@code no-price}, {@code below-owned}, {@code repeat}, {@code unaffordable}, {@code settle},
     * {@code no-income}, {@code no-rebirth-target}, {@code horizon}). The hold, its reason and
     * its HUD line are untouched: eggs never replace the primary economy's verdict, they ride
     * on it - logged as {@code eggs=} on every eval row and as a throttled companion_skip.
     */
    public Decision withEggs(String why) {
        return new Decision(action, kind, reason, gate, gateVia, ttkMs, patienceMs, stageKills,
            zoneGap, zoneGapVia, swordPct, gain, gainVia, waitMs, at, why);
    }

    /** Log fields (nulls are dropped by {@link EventLogger}). {@code zoneGate} keeps the old open/closed vocabulary for the analyzers. */
    public Object[] kv() {
        List<Object> out = new ArrayList<>();
        out.add("decision"); out.add(action);
        out.add("kind"); out.add(kind);
        out.add("reason"); out.add(reason);
        out.add("gate"); out.add(gate);
        out.add("gateVia"); out.add(gateVia);
        out.add("zoneGate"); out.add(gateHard() ? "closed" : "open");
        out.add("ttkMs"); out.add(ttkMs != null ? Math.round(ttkMs) : null);
        out.add("ttkVia"); out.add(ttkMs != null ? "median" : null);
        out.add("patienceMs"); out.add(patienceMs);
        out.add("stageKills"); out.add(stageKills);
        out.add("zoneGap"); out.add(zoneGap != null ? Amounts.format(zoneGap) : null);
        out.add("zoneGapVia"); out.add(zoneGapVia);
        out.add("swordPct"); out.add(swordPct != null ? Math.round(swordPct * 10.0) / 10.0 : null);
        out.add("gain"); out.add(gain != null ? Math.round(gain * 100.0) / 100.0 : null);
        out.add("gainVia"); out.add(gainVia);
        out.add("waitMs"); out.add(waitMs != null ? Math.round(waitMs) : null);
        out.add("eggs"); out.add(eggs);
        return out.toArray();
    }

    /** Short human line for the HUD plan row and the options screen (≤ 64 chars). */
    public String hudPlan(Double kindPrice, Double bal) {
        String price = kindPrice != null ? Amounts.format(kindPrice) : null;
        String pct = kindPrice != null && kindPrice > 0 && bal != null
            ? Math.min(999, Math.round(100.0 * bal / kindPrice)) + "%" : null;
        String ttk = ttkMs != null ? seconds(ttkMs) : null;
        String pat = patienceMs != null ? seconds(patienceMs.doubleValue()) : null;
        String eta = waitMs != null ? Amounts.eta(waitMs) : null;
        String r = reason == null ? "" : reason;
        String out;
        switch (action == null ? NONE : action) {
            case BUY -> out = switch (r) {
                case "zone-affordable" -> "buy zone" + sp(price) + " · stage " + (gate == null ? "?" : gate) + (ttk != null ? " " + ttk : "");
                case "sword-hard" -> "buy sword" + sp(price) + " · stage hard" + (ttk != null && pat != null ? " " + ttk + " > " + pat : "");
                case "sword-cheap" -> "buy sword" + sp(price) + " · cheap vs zone gap" + (swordPct != null ? " " + Math.round(swordPct) + "%" : "");
                case "rebirth-affordable" -> "buy rebirth" + sp(price);
                case "companion-sooner" -> "buy eggs" + sp(price) + " · sooner to stage/rebirth";
                case "companion-persist" -> "buy eggs" + sp(price) + " · pays past rebirth" + sp(eta != null ? "+" + eta : null);
                default -> "buy " + kind + sp(price) + " · " + r;
            };
            case PROBE -> out = "probe " + kind + " · price ?" + (r.endsWith("-hard") ? " · stage hard" : "");
            case WAIT -> out = switch (r) {
                case "saving-zone" -> "save for zone" + sp(price) + sp(pct) + sp(eta != null ? "~" + eta : null)
                    + (swordPct != null ? " · sword " + Math.round(swordPct) + "% of gap" : "");
                case "zone-stage-kills" -> "wait · new stage, " + (stageKills != null ? stageKills : 0) + " kill(s) so far";
                case "sword-instant" -> "wait · instant kills" + (ttk != null ? " " + ttk : "") + ", sword useless";
                case "sword-hard-unaffordable" -> "save for sword" + sp(price) + sp(pct) + sp(eta != null ? "~" + eta : null) + " · stage hard";
                case "rebirth-horizon" -> "wait · rebirth sooner than " + (kind == null ? "buy" : kind) + " pays off";
                case "unaffordable" -> "save" + (kind != null ? " for " + kind : "") + sp(price) + sp(pct) + sp(eta != null ? "~" + eta : null);
                case "first-kills" -> "wait · a few kills first";
                case "cooldown" -> "wait" + sp(eta) + " · cooldown" + (kind != null ? " " + kind : "");
                case "hesitate" -> "wait" + sp(eta) + " · hesitating on " + kind;
                default -> "wait · " + r + (kind != null ? " " + kind : "");
            };
            default -> out = "maxed".equals(r) ? "nothing left to buy" : "idle";
        }
        return out.length() > 64 ? out.substring(0, 64) : out;
    }

    private static String sp(String s) { return s == null || s.isEmpty() ? "" : " " + s; }

    private static String seconds(double ms) {
        return String.format(Locale.ROOT, ms < 100_000 ? "%.1fs" : "%.0fs", ms / 1000.0);
    }
}

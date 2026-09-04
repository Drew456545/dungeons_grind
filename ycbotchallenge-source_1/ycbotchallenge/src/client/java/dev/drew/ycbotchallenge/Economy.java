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
     * While saving for a known zone price, a sword is bought only when it cannot hurt:
     * never at or below the movement-floor TTK ({@code instantTtkMs}, where walking and
     * aiming dominate and a sharper sword changes nothing: 03-36 spent 525K at 0.73s),
     * and only when it costs at most {@code savingMaxPct} of the remaining zone gap.
     * Unknown prices leave the decision to exploration (true).
     */
    public static boolean swordWhileSaving(Double swordTarget, Double zoneTarget, Double bal, Double ttkMs,
                                           double savingMaxPct, int instantTtkMs) {
        if (ttkMs != null && instantTtkMs > 0 && ttkMs <= instantTtkMs) return false;
        if (swordTarget == null || zoneTarget == null || bal == null) return true;
        double gap = Math.max(0.0, zoneTarget - bal);
        return swordTarget <= gap * Math.max(0.0, savingMaxPct) / 100.0 + 1e-6;
    }

    /**
     * Effective TTK for the zone gate (0.9.23): the rolling kill median once three kills
     * of this stage have landed — what actually happened — and the DPS-predicted
     * whole-mob TTK of the mob being cooked only before that (the first mob of a fresh
     * stage, readable seconds in). The 17:57 log had it the other way round: the
     * prediction from the first slow chicken after the rebirth (11.5s) outlived it by
     * two minutes while the median fell to 0.3–0.8s, the gate stayed closed, and three
     * swords were bought on zone 1 instead of the first zone.
     */
    public static Double effectiveTtkMs(Double predictedMs, Double medianMs) {
        if (medianMs != null && medianMs > 0) return medianMs;
        if (predictedMs != null && predictedMs > 0) return predictedMs;
        return null;
    }

    /** Which number {@link #effectiveTtkMs} used: "median", "predicted", or null. */
    public static String ttkSource(Double predictedMs, Double medianMs) {
        if (medianMs != null && medianMs > 0) return "median";
        if (predictedMs != null && predictedMs > 0) return "predicted";
        return null;
    }

    /**
     * A DPS prediction describes the mob it was read from. It is refreshed every tick
     * while that mob is being cooked, so one older than {@code maxAgeMs} belongs to a
     * mob that is already dead (instant kills never produce a fresh one) and is dropped.
     * {@code maxAgeMs <= 0} keeps every prediction.
     */
    public static Double freshPrediction(Double predictedMs, long predictedAt, long now, int maxAgeMs) {
        if (predictedMs == null || predictedMs <= 0) return null;
        if (maxAgeMs <= 0) return predictedMs;
        if (predictedAt <= 0 || now - predictedAt > maxAgeMs) return null;
        return predictedMs;
    }

    /**
     * Zone patience (0.9.23): the kill time a player tolerates before wanting a sword
     * instead of the next stage is a mood, not a line. Bounds for the per-stage roll:
     * {@code baseMs × minMult .. baseMs × maxMult}, sanitized (a disabled gate stays
     * disabled, swapped or sub-zero multipliers collapse to the base).
     */
    public static int[] zonePatienceBounds(int baseMs, double minMult, double maxMult) {
        if (baseMs <= 0) return new int[]{0, 0};
        double lo = Math.max(0.05, Math.min(minMult, maxMult));
        double hi = Math.max(lo, Math.max(minMult, maxMult));
        return new int[]{(int) Math.round(baseMs * lo), (int) Math.round(baseMs * hi)};
    }

    /**
     * The persisted rebirth floor is stale once the balance sits on or above it with no
     * rebirth having happened (with /autorebirth the server would already have fired):
     * the 17:57 log ran at 155Q against a 900T floor from two rebirths ago, so the
     * horizon rule had no rebirth target all session and bought a 489Q sword two
     * minutes before the ~800Q rebirth. Stale ⇒ the one-per-session seed probe is due.
     */
    public static boolean rebirthFloorStale(Double floor, Double bal, double growthPct) {
        return retryUnknownAllowed(floor, bal, growthPct);
    }

    /**
     * A /rebirth probe that opened the GUI and got no answer it understood is not
     * re-typed (18:37 log: an unknown "QQ" suffix turned every probe into a timeout and
     * the abort path re-queued /rebirth five times in 80s, then read a closed GUI as a
     * rebirth and wiped the learned prices). Only a probe that never reached the GUI
     * ("no-gui": the typed command was eaten, "no-diamond": an unexpected layout) may
     * retry, and at most {@code maxRetries} times per session.
     */
    public static boolean rebirthProbeRetryAllowed(String abortReason, int retriesSoFar, int maxRetries) {
        if (abortReason == null) return false;
        if (!"no-gui".equals(abortReason) && !"no-diamond".equals(abortReason)) return false;
        return retriesSoFar < Math.max(0, maxRetries);
    }

    /**
     * A rebirth is only ever confirmed by the server's own signals (its chat line, the
     * sidebar counter, the money collapse — all of which land within ~5s of the click);
     * a GUI that merely closed is not one (18:37 log: "success via silence" on the
     * fifth probe with the balance still 2.66Q).
     */
    public static boolean rebirthConfirmed(long lastRebirthAt, long sendAt) {
        return sendAt > 0 && lastRebirthAt >= sendAt;
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
     * Buy order (0.9.16, from the 03-36 post-rebirth log): a zone multiplies per-kill
     * money x20 and costs about one kill of the next zone, so with the TTK gate open
     * the zone is always the buy and money is saved for it; a sword only helps while
     * the TTK is above the movement floor, and while saving it is bought only when it
     * is cheap against the remaining zone gap ({@link #swordWhileSaving}). Gate closed
     * (TTK above zoneMaxTtkMs, or zone maxed): the sword, the only thing that helps.
     * Rebirth is chosen by the controller before this is consulted.
     *
     * @return {@code "sword"}, {@code "zone"}, or {@code null} to wait
     */
    public static String chooseBuyKind(
            boolean swordOpen, boolean zoneOpen,
            boolean swordAffordable, boolean zoneAffordable,
            Double swordTarget, Double zoneTarget, Double bal, Double ttkMs,
            double savingMaxPct, int instantTtkMs) {
        if (!swordOpen && !zoneOpen) return null;
        if (zoneOpen && zoneAffordable) return "zone";
        if (!swordOpen || !swordAffordable) return null;
        if (!zoneOpen || zoneTarget == null) return "sword";
        return swordWhileSaving(swordTarget, zoneTarget, bal, ttkMs, savingMaxPct, instantTtkMs) ? "sword" : null;
    }

    /** What we're working toward (HUD, exploration order): the zone while the gate is open, else the sword. */
    public static String preferredKind(boolean swordOpen, boolean zoneOpen) {
        if (zoneOpen) return "zone";
        return swordOpen ? "sword" : null;
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

    /**
     * Per-lull hazard for an enchanter visit: nothing before {@code rampStartMs}
     * since the last visit, rising (squared, so the middle is gentle) to
     * {@code fullChance} at {@code rampFullMs}, scaled by the affordability pull
     * and a bonus for free time mid-cook. A renewal process with a wandering
     * interval — never "45s mob and 3 minutes".
     */
    public static double visitHazard(long sinceLastVisitMs, int rampStartMs, int rampFullMs,
                                     double fullChance, double pullMult, double bonus) {
        if (sinceLastVisitMs < Math.max(0, rampStartMs)) return 0;
        double span = Math.max(1, rampFullMs - rampStartMs);
        double r = Math.min(1.0, (sinceLastVisitMs - rampStartMs) / span);
        double h = Math.max(0, fullChance) * r * r * Math.max(0, pullMult) * Math.max(0, bonus);
        return Math.min(1.0, h);
    }

    /**
     * Affordability pull: players check the menu more once the sidebar shows they can
     * afford the cheapest thing they saw last time. 1 below that price, the balance
     * ratio above it, capped at {@code maxMult}.
     */
    public static double affordPull(Double balance, Double cheapestPrice, double maxMult) {
        if (balance == null || cheapestPrice == null || cheapestPrice <= 0) return 1.0;
        double ratio = balance / cheapestPrice;
        if (ratio < 1.0) return 1.0;
        return Math.min(Math.max(1.0, maxMult), ratio);
    }

    /** True once {@code settleMs} have passed since a spend (or there was no spend). */
    /**
     * A nameplate matching any ignore pattern is never a target: the zone's
     * "[AFKMOB] LVL7 Donkey ❤∞" is the same species as the real mobs and
     * stands still, so nothing but its tag (and its infinite HP) tells it apart.
     */
    public static boolean ignoredMob(String nameplate, java.util.List<java.util.regex.Pattern> ignoreRes) {
        if (nameplate == null || ignoreRes == null) return false;
        for (java.util.regex.Pattern p : ignoreRes) {
            if (p.matcher(nameplate).find()) return true;
        }
        return false;
    }

    /**
     * Rebirth horizon (0.9.15): a sword/zone bought before a rebirth is lost with it,
     * so it must pay for itself first. With income I per minute, price P, remaining gap
     * G = R - bal and an income multiplier g from the buy, staying rebirths in G/I
     * minutes and buying in (G + P)/(I*g); buy only when that is sooner, i.e.
     * P < G*(g - 1). Logs 2026-09-03: the 415T zone 8 against a 475T gap at
     * 109T/min delayed the rebirth ~2 min; the 7.55T zone 7 against a 22T gap ~5 min;
     * every early-snowball buy (K-B prices against a T gap) passes. Unknown numbers,
     * a covered rebirth or a gain of 1.0 = no opinion (true).
     */
    public static boolean rebirthHorizonAllows(Double price, Double bal, Double rebirthTarget,
                                               Double incomePerMin, double gain) {
        if (price == null || bal == null || rebirthTarget == null || incomePerMin == null) return true;
        if (gain <= 1.0 || incomePerMin <= 0 || price <= 0) return true;
        double gap = rebirthTarget - bal;
        if (gap <= 0) return true;
        double stayEta = gap / incomePerMin;
        double buyEta = (gap + price) / (incomePerMin * gain);
        return buyEta < stayEta;
    }

    /** Minutes to the rebirth at the current income; 0 when covered, null when unknown. */
    public static Double rebirthEtaMin(Double bal, Double rebirthTarget, Double incomePerMin) {
        if (bal == null || rebirthTarget == null) return null;
        if (bal >= rebirthTarget) return 0.0;
        if (incomePerMin == null || incomePerMin <= 0) return null;
        return (rebirthTarget - bal) / incomePerMin;
    }

    /** Minutes to the rebirth if the buy is made now and income multiplies by {@code gain}; null when unknown. */
    public static Double buyEtaMin(Double price, Double bal, Double rebirthTarget, Double incomePerMin, double gain) {
        if (price == null || bal == null || rebirthTarget == null || incomePerMin == null) return null;
        if (incomePerMin <= 0 || gain <= 0) return null;
        return Math.max(0, rebirthTarget - bal + price) / (incomePerMin * gain);
    }

    /**
     * A boss bar that vanished under barVanishMinCookMs with the entity still standing
     * (0.9.21). On zone 1 after a rebirth every chicken dies on the first click: the
     * bar lives one tick, the client entity is still in its death animation, and the
     * 17:12 log filed 36 of 39 tags as "tag didn't stick" while the money landed a
     * second after each. Verdict: "kill" once the entity is gone or the sidebar money
     * rose after the tag; "wait" while the confirm window is open; else "retag".
     */
    public static String vanishVerdict(long barGoneAt, long tagAt, long now, boolean entityGone,
                                       long lastMoneyUpAt, int confirmMs) {
        if (entityGone) return "kill";
        if (lastMoneyUpAt > tagAt) return "kill";
        if (now - barGoneAt < Math.max(0, confirmMs)) return "wait";
        return "retag";
    }

    /**
     * 0.9.26: on this server a mob's plate is not its entity name — it is a text display
     * riding the mob or floating above it (target_ignored never fired and no tag ever
     * carried a rarity in any log, so the 0.9.14 rule read a name that was never there).
     * Any plate line matching an ignore pattern ("[AFKMOB] LVL9 Mooshroom ❤∞",
     * "RIGHT CLICK TO UPGRADE") makes the mob untargetable.
     */
    public static boolean ignoredByLines(java.util.List<String> lines, java.util.List<java.util.regex.Pattern> ignoreRes) {
        if (lines == null) return false;
        for (String l : lines) if (ignoredMob(l, ignoreRes)) return true;
        return false;
    }

    /**
     * Backstop after the first hit: the AFK mob's own boss bar reads "[AFKMOB] LVL9
     * Mooshroom" (19:26 log, boost_start). The target is dropped only when every bar
     * mentioning the mob matches — a real Mooshroom cooking next to a lingering AFK bar
     * is not.
     */
    public static boolean bossBarIgnored(java.util.List<String> titles, java.util.List<java.util.regex.Pattern> ignoreRes) {
        if (titles == null || titles.isEmpty()) return false;
        for (String t : titles) if (!ignoredMob(t, ignoreRes)) return false;
        return true;
    }

    /** A floating plate belongs to the mob it hovers over: within {@code radius} horizontally, from half a block below to 3.5 above. */
    public static boolean hologramBelongs(double dx, double dz, double dy, double radius) {
        return Math.sqrt(dx * dx + dz * dz) <= Math.max(0, radius) && dy >= -0.5 && dy <= 3.5;
    }

    /** A manual mark (Ctrl+toggle) matches the same kind of mob within {@code radius} of where it was marked. */
    public static boolean manualMarkMatches(String type, String markType, double dist, double radius) {
        if (type == null || markType == null) return false;
        return type.equalsIgnoreCase(markType) && dist <= Math.max(0, radius);
    }

    /**
     * Stay in your zone (0.9.27): a mob is a candidate only when its plate level matches
     * the boss-bar-confirmed zone level. Either side unknown = no opinion (the dominant
     * pack filter carries those seconds, as before).
     */
    public static boolean sameZoneLevel(Integer plateLevel, Integer zoneLevel) {
        if (plateLevel == null || zoneLevel == null) return true;
        return plateLevel.intValue() == zoneLevel.intValue();
    }

    /** "lvl7" → 7, anything else → null. */
    public static Integer zoneLevelOf(String zone) {
        if (zone == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)lvl\\s*(\\d+)").matcher(zone.trim());
        if (!m.find()) return null;
        try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException e) { return null; }
    }

    /**
     * No typed /swordmax or /zone max before the first kills (0.9.29): a person who just
     * rebirthed, or just sat down, kills something first. Both counts must reach
     * {@code needed} (rolled 1–3 per enable and per rebirth). 00:19 log: upgrade_plan
     * 5 s after each enable with zero kills; 17:57 log: via=timer at 32 s before any kill.
     */
    public static boolean firstKillsReached(int killsSinceEnable, int killsSinceRebirth, int needed) {
        int n = Math.max(0, needed);
        return killsSinceEnable >= n && killsSinceRebirth >= n;
    }

    /**
     * 0.9.30: the income multiplier a sword tier is expected to bring, from the current
     * TTK. Income goes as 1/(ttk + floor) — the movement floor doubles as walk/aim
     * overhead — and a tier multiplies DPS by {@code dpsMult}, so the new TTK is
     * max(floor, ttk/dpsMult). Logs 2026-09-03: same-zone kill medians around sword buys
     * gave 1.3x at the floor and 2–8x on long kills (22.3s → 2.7s), while the fixed 1.25
     * assumption held a 132S sword back at a 5–8s TTK with ~29 min of rebirth left.
     * Floor 2s, mult 2: 1s → minGain, 5s → 1.56, 8s → 1.67, 20s → 1.83, 60s → 1.94.
     * Unknown TTK → minGain.
     */
    public static double swordGain(Double ttkMs, double dpsMult, int floorMs, double minGain) {
        double min = Math.max(1.0, minGain);
        if (ttkMs == null || ttkMs <= 0 || dpsMult <= 1.0) return min;
        double floor = Math.max(0, floorMs);
        double after = Math.max(floor, ttkMs / dpsMult);
        double gain = (ttkMs + floor) / (after + floor);
        return Math.max(min, gain);
    }

    /**
     * 0.9.30: a Transcend activation that arrived with no press of ours in the last
     * {@code graceMs} is the server's own (the 00:19 log: 37 activations at exactly
     * 190s spacing, two hours of them with the bot off; we pressed once).
     */
    public static boolean transcendServerDriven(long activatedAt, long lastPressAt, long graceMs) {
        if (lastPressAt <= 0) return true;
        return activatedAt - lastPressAt > Math.max(0, graceMs);
    }

    public static boolean sidebarSettled(long nowMs, long lastSpendAt, int settleMs) {
        if (lastSpendAt <= 0) return true;
        return nowMs - lastSpendAt >= Math.max(0, settleMs);
    }
}

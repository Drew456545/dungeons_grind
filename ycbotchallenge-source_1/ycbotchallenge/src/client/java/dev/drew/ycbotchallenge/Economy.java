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
     * 0.9.35: the rebirth horizon is the wrong veto for companions. A sword or a zone is
     * wiped by the rebirth, so it must pay for itself before it ({@link #rebirthHorizonAllows},
     * P &lt; G(g-1)); companions persist — the 2026-09-04 storage held z1s10 and z2s1..s5
     * companions across rebirth 11 — so a batch that delays this rebirth still pays on the
     * other side. Allowed when the buy reaches the rebirth sooner than standing still, or
     * delays it by at most {@code maxDelayMin} minutes AND {@code maxDelayPct} of what is
     * left. Unknown numbers = no opinion (true), same contract as the wiped-buy rule.
     */
    public static boolean companionHorizonAllows(Double price, Double bal, Double rebirthTarget,
                                                 Double incomePerMin, double gain,
                                                 double maxDelayMin, double maxDelayPct) {
        Double stay = rebirthEtaMin(bal, rebirthTarget, incomePerMin);
        Double buy = buyEtaMin(price, bal, rebirthTarget, incomePerMin, gain);
        if (stay == null || buy == null) return true;
        if (buy <= stay) return true;
        double delay = buy - stay;
        return delay <= Math.max(0, maxDelayMin) && delay <= stay * Math.max(0, maxDelayPct) / 100.0;
    }

    /**
     * The patience horizon (0.9.35): eggs only pre-empt when the stage — the thing that
     * actually advances this rebirth — is a long way off. A null ETA is FAR: there is no
     * price to wait for. {@code patienceMs <= 0} disables the gate.
     *
     * <p>The zone ETA only, deliberately. Gating on the sword too would reinstate the bug
     * this fixes (the sword sits on a x3.5 ladder, so its ETA was 0-20 min throughout the
     * lvl15 stall); gating on the rebirth ETA would refuse the near-rebirth batch, which is
     * the one Drew asks for. The sword is priced by the ETA comparison in {@link #decide},
     * the rebirth by {@link #companionHorizonAllows}.
     */
    public static boolean companionPatienceOk(Double zoneEtaMs, int patienceMs) {
        if (patienceMs <= 0) return true;
        return zoneEtaMs == null || zoneEtaMs >= patienceMs;
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

    /**
     * 0.9.31 price ladders. Every log agrees: a sword level costs ×3.5 the last (36 steps
     * from 150.06K to 472.7S, every ratio 3.49–3.50) and a zone stage ×55 the last (137.26B
     * → 7.55T → 415.21T → 22.83Q → 1.26QQ → 69.08QQ → 3.8S → 208.97S). So after a purchase
     * the next price is last × growth — known before the server says so.
     */
    public static Double predictNext(Double lastPrice, double growth) {
        if (lastPrice == null || lastPrice <= 0 || growth <= 1.0) return null;
        return lastPrice * growth;
    }

    /** A measured price ratio is a lesson only when it sits within bandPct of the expected growth (a /zone max that bought two stages is not). */
    public static boolean growthAccepted(double ratio, double expected, double bandPct) {
        if (ratio <= 1.0 || expected <= 1.0) return false;
        return Math.abs(ratio - expected) / expected <= Math.max(0, bandPct) / 100.0;
    }

    /** Blend a measured ratio into the learned growth (EMA, weight w). */
    public static double blendGrowth(Double learned, double ratio, double w) {
        if (learned == null) return ratio;
        double k = Math.max(0, Math.min(1, w));
        return learned * (1 - k) + ratio * k;
    }

    public static boolean sidebarSettled(long nowMs, long lastSpendAt, int settleMs) {
        if (lastSpendAt <= 0) return true;
        return nowMs - lastSpendAt >= Math.max(0, settleMs);
    }

    // ---- 0.9.33: tri-state zone gate and the zone-first decision ---------------------------

    /** Zone gate state (0.9.33): OPEN (stage measured killable), HARD (measured too slow), UNKNOWN (not measured yet). */
    public enum Gate { OPEN, HARD, UNKNOWN }

    public record GateResult(Gate gate, String via) {
        public String name() { return gate.name().toLowerCase(java.util.Locale.ROOT); }
        public boolean hard() { return gate == Gate.HARD; }
    }

    /** 1 + the rarity's HP scale (RARE 1.15, EPIC 1.30, LEGENDARY 1.40 by default); 1 for an untagged or unknown mob. */
    public static double rarityScale(String rarity, java.util.Map<String, Double> scales) {
        if (rarity == null || scales == null) return 1.0;
        Double s = scales.get(rarity.toUpperCase(java.util.Locale.ROOT));
        return s != null && s > -1.0 ? 1.0 + s : 1.0;
    }

    /**
     * Tri-state zone gate (0.9.33). The 0.9.23 gate read "unknown TTK" as closed, and every
     * /zone max teleport empties the kill window, so 11 of 12 zone buys in the 2026-09-04
     * logs were followed by a blind sword buy (15.98Q on lvl7 with a 4.4T zone floor and
     * 17.33Q in hand; 2.48T four seconds before the median opened the gate). Now:
     * <ol>
     * <li>patience 0: OPEN (gate disabled);</li>
     * <li>the mob being cooked has already taken longer than the patience (rarity-normalised
     *     elapsed time, real evidence, never the DPS prediction): HARD via "cook";</li>
     * <li>three or more stage kills: the median decides, via "median";</li>
     * <li>one or two stage kills and any of them above the patience: HARD via "kill"
     *     (the 17:57 chicken: 11.5 s, then 0.3-0.8 s; two kills of delay, not two minutes);</li>
     * <li>else, with a legacy prediction supplied ({@code gateUsesPrediction}), that decides;</li>
     * <li>else UNKNOWN: the zone stays allowed, the sword does not.</li>
     * </ol>
     */
    public static GateResult zoneGate(Double medianMs, int stageKills, Double stageMaxTtkMs,
                                      double cookElapsedMs, int patienceMs, Double predictedMs) {
        if (patienceMs <= 0) return new GateResult(Gate.OPEN, "none");
        if (cookElapsedMs > patienceMs) return new GateResult(Gate.HARD, "cook");
        if (stageKills >= 3 && medianMs != null && medianMs > 0) {
            return new GateResult(medianMs > patienceMs ? Gate.HARD : Gate.OPEN, "median");
        }
        if (stageKills >= 1 && stageMaxTtkMs != null && stageMaxTtkMs > patienceMs) {
            return new GateResult(Gate.HARD, "kill");
        }
        if (predictedMs != null && predictedMs > 0) {
            return new GateResult(predictedMs > patienceMs ? Gate.HARD : Gate.OPEN, "predicted");
        }
        return new GateResult(Gate.UNKNOWN, "none");
    }

    /**
     * Remaining money to the next stage: the known target, else the last zone price times
     * the ladder growth (a lower bound is better than no opinion: with {@code zoneTarget}
     * null the 0.9.32 rule skipped the saving guard and bought swords blind), else null.
     */
    public static Double zoneGapEstimate(Double zoneTarget, Double zoneFloor, double zoneGrowth, Double bal) {
        if (bal == null) return null;
        Double ref = zoneTarget != null ? zoneTarget
            : zoneFloor != null && zoneFloor > 0 ? zoneFloor * Math.max(1.0, zoneGrowth) : null;
        return ref == null ? null : Math.max(0.0, ref - bal);
    }

    /** "target" / "floor" / null: where {@link #zoneGapEstimate} got its number. */
    public static String zoneGapVia(Double zoneTarget, Double zoneFloor) {
        if (zoneTarget != null) return "target";
        return zoneFloor != null && zoneFloor > 0 ? "floor" : null;
    }

    /** {@link #swordWhileSaving} against an already-estimated gap; an unknown gap (nothing known about the zone) leaves it to exploration. */
    public static boolean swordWhileSavingGap(Double swordTarget, Double zoneGap, Double ttkMs,
                                              double savingMaxPct, int instantTtkMs) {
        if (ttkMs != null && instantTtkMs > 0 && ttkMs <= instantTtkMs) return false;
        if (swordTarget == null || zoneGap == null) return true;
        return swordTarget <= zoneGap * Math.max(0.0, savingMaxPct) / 100.0 + 1e-6;
    }

    /**
     * A toggle within {@code keepMs} on the same stage keeps the kill window and the patience
     * roll (2026-09-04 14:55: six toggles in 37 s emptied the window each time and the gate
     * never opened). A zone change or teleport in between always resets.
     */
    public static boolean keepTtkWindow(long offMs, int keepMs, boolean sameZone, boolean zoneChanged) {
        if (keepMs <= 0 || offMs < 0) return false;
        return offMs <= keepMs && sameZone && !zoneChanged;
    }

    /**
     * Target-score adjustment for a mob's rarity tag: while the stage has fewer than
     * {@code probeKills} kills the first target should be a common one so the stage is
     * measured quickly (rare tags cost {@code penaltyBlocks}); afterwards the rarer mob
     * is worth walking {@code bonusBlocks} further for, as before.
     */
    public static double rarityScoreAdjust(String rarity, double bonusBlocks, int stageKills, int probeKills, double penaltyBlocks) {
        if (rarity == null) return 0;
        if (stageKills < Math.max(0, probeKills)) return Math.max(0, penaltyBlocks);
        return -bonusBlocks;
    }

    /** One typed probe of an unknown price: the free seed, then only past the rolled retry floor, one unresolved at a time. */
    public static boolean probeAllowed(boolean seeded, boolean exploratorySent, Double lastPrice, Double bal, double retryGrowth) {
        if (!seeded) return true;
        if (exploratorySent) return false;
        return retryUnknownAllowed(lastPrice, bal, retryGrowth);
    }

    /** Plain facts for {@link #decide}; defaults are the config defaults so a test sets only what it means. */
    public static final class Inputs {
        public Double swordTarget, zoneTarget, rebirthTarget, swordFloor, zoneFloor, bal, incomePerMin;
        public double zoneGrowth = 55.0;
        public Double medianTtkMs, stageMaxTtkMs, predictedTtkMs;
        public int stageKills;
        public double cookElapsedMs;
        public int patienceMs = 10_000;
        public boolean swordMaxed, zoneMaxed;
        public boolean swordSeeded, swordExploratorySent, zoneSeeded, zoneExploratorySent;
        public double swordRetryGrowth = 0.5, zoneRetryGrowth = 0.5;
        public boolean serverAutoRebirth = true, rebirthAffordable, rebirthRetryDue;
        public double savingMaxPct = 25;
        public int instantTtkMs = 2000;
        public boolean horizonEnabled = true;
        public double zoneGain = 1.3, swordDpsMult = 2.0, swordGainFloor = 1.25;
        public int zoneMinStageKills = 1;
        public long now;
        // 0.9.35 companions. Defaults leave the branch inert (companionBatchPrice null), so
        // every pre-0.9.35 fixture decides exactly as it did before.
        public boolean companionsEnabled = true;
        /** Controller-side: an egg is known, the stage has settled, nothing suspended or aborting. */
        public boolean companionFeasible;
        /** companionEggsMin eggs at this stage's price (observed, else the x52.2 ladder). */
        public Double companionBatchPrice;
        public double companionGain = 1.5;
        public String companionGainVia = "config";
        public Integer companionStage;
        public int companionVisitsThisStage;
        public int companionMaxVisitsPerStage = 2;
        public double companionMaxBalancePct = 40;
        public int companionPatienceMs = 1_200_000;
        public double companionPersistCredit = 1.25;
        public double companionMaxRebirthDelayMin = 3.0;
        public double companionMaxRebirthDelayPct = 25;
        public double companionRebirthEtaMinMax = 12.0;
        /** Zone buys have stopped for this rebirth (horizon-blocked on the zone, or maxed). */
        public boolean companionZoneStopped;
    }

    /**
     * The buy decision (0.9.33), zone-first: rebirth when it is ours to buy; the zone
     * whenever the stage is not HARD and it is affordable (probed first while its price is
     * unknown), after {@code zoneMinStageKills} kills on the stage the last /zone max landed
     * on; the sword when the stage is HARD, or while it is cheap against the zone gap
     * ({@link #swordWhileSavingGap}) and the kills are above the movement floor; the
     * rebirth horizon ({@link #rebirthHorizonAllows}, sword gain from the kill median only)
     * vetoes any buy that would not pay off before the rebirth. Every outcome carries its
     * reason so the log, the HUD and the tests share one vocabulary.
     */
    public static Decision decide(Inputs in) {
        GateResult g = zoneGate(in.medianTtkMs, in.stageKills, in.stageMaxTtkMs, in.cookElapsedMs, in.patienceMs, in.predictedTtkMs);
        Double ttk = in.medianTtkMs != null && in.medianTtkMs > 0 ? in.medianTtkMs : null;
        Double zoneGap = in.zoneMaxed ? null : zoneGapEstimate(in.zoneTarget, in.zoneFloor, in.zoneGrowth, in.bal);
        String gapVia = in.zoneMaxed ? null : zoneGapVia(in.zoneTarget, in.zoneFloor);
        Double swordPct = in.swordTarget != null && zoneGap != null && zoneGap > 0 ? 100.0 * in.swordTarget / zoneGap : null;
        Decision base = new Decision(Decision.NONE, null, null, g.name(), g.via(), ttk,
            in.patienceMs > 0 ? in.patienceMs : null, in.stageKills, zoneGap, gapVia, swordPct, null, null, null, in.now);

        Decision d = decideUpgrades(in, base, g, ttk, zoneGap);
        if (d.acts()) return d;
        // 0.9.35: only ever converts a hold into an egg batch - a real zone/sword/rebirth buy
        // always wins, so the zone-first strategy is untouched.
        Decision c = decideCompanion(in, base, ttk, zoneGap);
        return c != null ? c : d;
    }

    /** The sword/zone/rebirth decision (0.9.33), unchanged; {@link #decide} adds the companion post-pass. */
    private static Decision decideUpgrades(Inputs in, Decision base, GateResult g, Double ttk, Double zoneGap) {
        if (!in.serverAutoRebirth) {
            if (in.rebirthAffordable) return base.with(Decision.BUY, "rebirth", "rebirth-affordable", null, null, null);
            if (in.rebirthRetryDue) return base.with(Decision.PROBE, "rebirth", "rebirth-probe", null, null, null);
        }
        if (in.swordMaxed && in.zoneMaxed) return base.with(Decision.NONE, null, "maxed", null, null, null);

        boolean zoneAff = knownAffordable(in.zoneTarget, in.bal);
        boolean swordAff = knownAffordable(in.swordTarget, in.bal);
        double zoneGain = in.zoneGain;
        double swordGain = swordGain(ttk, in.swordDpsMult, in.instantTtkMs, in.swordGainFloor);
        String swordGainVia = ttk != null ? "median" : "floor";
        boolean instant = ttk != null && in.instantTtkMs > 0 && ttk <= in.instantTtkMs;
        Double zoneEta = etaMs(zoneGap, in.incomePerMin);
        Double swordEta = in.swordTarget != null && in.bal != null ? etaMs(in.swordTarget - in.bal, in.incomePerMin) : null;

        // Zone branch: the zone is the buy whenever the stage is not measured too hard.
        if (!in.zoneMaxed && !g.hard()) {
            boolean zoneProbe = !zoneAff && in.zoneTarget == null
                && probeAllowed(in.zoneSeeded, in.zoneExploratorySent, in.zoneFloor, in.bal, in.zoneRetryGrowth);
            if (zoneAff || zoneProbe) {
                if (in.stageKills < Math.max(0, in.zoneMinStageKills)) {
                    return base.with(Decision.WAIT, "zone", "zone-stage-kills", zoneGain, "config", null);
                }
                if (zoneProbe) return base.with(Decision.PROBE, "zone", "zone-probe", zoneGain, "config", null);
                if (horizonAllows(in, in.zoneTarget, zoneGain)) {
                    return base.with(Decision.BUY, "zone", "zone-affordable", zoneGain, "config", null);
                }
                // The zone would not pay off before the rebirth; a sword that does may still go.
                if (!in.swordMaxed && swordAff && !instant && horizonAllows(in, in.swordTarget, swordGain)) {
                    return base.with(Decision.BUY, "sword", "sword-before-rebirth", swordGain, swordGainVia, null);
                }
                return base.with(Decision.WAIT, "zone", "rebirth-horizon", zoneGain, "config", zoneEta);
            }
        }

        // Sword branch.
        if (in.swordMaxed) {
            if (g.hard()) return base.with(Decision.WAIT, "zone", "hard-no-sword", null, null, null);
            return base.with(Decision.WAIT, in.zoneTarget != null ? "zone" : null,
                in.zoneTarget != null ? "unaffordable" : "no-prices", null, null, zoneEta);
        }
        if (g.hard()) {
            if (swordAff) {
                if (horizonAllows(in, in.swordTarget, swordGain)) {
                    return base.with(Decision.BUY, "sword", "sword-hard", swordGain, swordGainVia, null);
                }
                return base.with(Decision.WAIT, "sword", "rebirth-horizon", swordGain, swordGainVia, null);
            }
            if (in.swordTarget == null
                && probeAllowed(in.swordSeeded, in.swordExploratorySent, in.swordFloor, in.bal, in.swordRetryGrowth)) {
                return base.with(Decision.PROBE, "sword", "sword-probe-hard", swordGain, swordGainVia, null);
            }
            return base.with(Decision.WAIT, "sword", "sword-hard-unaffordable", swordGain, swordGainVia, swordEta);
        }
        // Gate OPEN or UNKNOWN: saving for the zone; a sword only while it cannot hurt.
        if (instant) {
            return base.with(Decision.WAIT, in.zoneMaxed ? null : "zone", "sword-instant", null, null, zoneEta);
        }
        if (swordAff) {
            if (swordWhileSavingGap(in.swordTarget, zoneGap, ttk, in.savingMaxPct, in.instantTtkMs)) {
                if (horizonAllows(in, in.swordTarget, swordGain)) {
                    return base.with(Decision.BUY, "sword", "sword-cheap", swordGain, swordGainVia, null);
                }
                return base.with(Decision.WAIT, "sword", "rebirth-horizon", swordGain, swordGainVia, null);
            }
            return base.with(Decision.WAIT, "zone", "saving-zone", zoneGain, "config", zoneEta);
        }
        if (in.swordTarget == null
            && probeAllowed(in.swordSeeded, in.swordExploratorySent, in.swordFloor, in.bal, in.swordRetryGrowth)
            && (zoneGap == null || in.bal == null || in.bal <= zoneGap * Math.max(0.0, in.savingMaxPct) / 100.0)) {
            // /swordmax buys every level it can afford: a blind probe may spend the whole balance,
            // so it is typed only while that balance is small against the zone gap.
            return base.with(Decision.PROBE, "sword", "sword-probe", swordGain, swordGainVia, null);
        }
        if (in.zoneTarget != null || zoneGap != null) {
            return base.with(Decision.WAIT, "zone", "unaffordable", zoneGain, "config", zoneEta);
        }
        if (in.swordTarget != null) {
            return base.with(Decision.WAIT, "sword", "unaffordable", swordGain, swordGainVia, swordEta);
        }
        return base.with(Decision.WAIT, null, "no-prices", null, null, null);
    }

    /**
     * 0.9.33: the zone success line carries no amount, so the price of a /zone max is the
     * sidebar drop right after it (balBefore - balAfter), null unless the balance fell. A
     * kill credit inside the window shrinks it, so it feeds the retry floor and the ladder
     * prediction (self-correcting on the next fail line), never the growth learning.
     */
    public static Double paidFromDelta(Double balBefore, Double balAfter) {
        if (balBefore == null || balAfter == null) return null;
        return balAfter < balBefore - 1e-6 ? balBefore - balAfter : null;
    }

    /** Persisted companion visits count for the current rebirth: 0 once the rebirth counter moved past the one they were made in. */
    public static int visitsThisRebirth(Integer visits, Integer atRebirths, Integer rebirths) {
        if (visits == null) return 0;
        if (rebirths != null && atRebirths != null && !rebirths.equals(atRebirths)) return 0;
        return Math.max(0, visits);
    }

    /**
     * The companion post-pass (0.9.35). Runs only when {@link #decideUpgrades} is holding,
     * so an egg batch can never delay a stage or a sword the bot was about to buy.
     *
     * <p>Why it exists: zone prices climb a flat x55 a stage while income growth per stage
     * fell to x24 at lvl14 and x18 at lvl15 in the 2026-09-04 logs — income is
     * money/kill x kills/min and kills/min collapses as the TTK runs 1.2s -> 96s, so the
     * x3.5 sword ladder cannot hold that line and the climb decelerates without bound
     * (lvl15: 41.7 bot-on minutes, thirteen sword buys, no advance). A companion batch is a
     * direct income multiplier that does not depend on the TTK at all — measured 2.20x
     * (7 eggs) and 1.76x (8 eggs) against a sword gain of 1.25-1.93 — and it survives the
     * rebirth, which is why it is worth buying late and high.
     *
     * <p>Order: affordable, not the whole wallet, not a repeat of this stage, the stage is
     * far away ({@link #companionPatienceOk}), then the batch must reach the rebirth sooner
     * than the zone would and no more than {@code companionPersistCredit} slower than the
     * sword (the sword is wiped at the rebirth, the eggs are not). Null = no companion
     * opinion; the caller keeps its own hold.
     */
    private static Decision decideCompanion(Inputs in, Decision base, Double ttk, Double zoneGap) {
        if (!in.companionsEnabled || !in.companionFeasible) return null;
        Double batch = in.companionBatchPrice;
        if (batch == null || batch <= 0 || in.bal == null) return null;
        if (!knownAffordable(batch, in.bal)) return null;
        // Variance control, not economics: the ETA maths is linear and would spend the lot.
        if (batch > in.bal * Math.max(0, in.companionMaxBalancePct) / 100.0) return null;
        if (in.companionMaxVisitsPerStage > 0 && in.companionVisitsThisStage >= in.companionMaxVisitsPerStage) {
            return base.with(Decision.WAIT, Decision.KIND_COMPANION, "companion-repeat",
                in.companionGain, in.companionGainVia, null);
        }
        Double zoneWaitEta = in.zoneMaxed ? null : etaMs(zoneGap, in.incomePerMin);
        if (!companionPatienceOk(zoneWaitEta, in.companionPatienceMs)) return null;

        double gc = in.companionGain;
        String via = in.companionGainVia;
        Double stay = rebirthEtaMin(in.bal, in.rebirthTarget, in.incomePerMin);
        Double cEta = buyEtaMin(batch, in.bal, in.rebirthTarget, in.incomePerMin, gc);
        if (stay == null || cEta == null) return null; // no income or no rebirth target: no opinion
        Double zEta = in.zoneMaxed ? null
            : buyEtaMin(in.zoneTarget, in.bal, in.rebirthTarget, in.incomePerMin, in.zoneGain);
        Double sEta = in.swordMaxed ? null
            : buyEtaMin(in.swordTarget, in.bal, in.rebirthTarget, in.incomePerMin,
                swordGain(ttk, in.swordDpsMult, in.instantTtkMs, in.swordGainFloor));
        if (!sooner(cEta, zEta)) {
            return base.with(Decision.WAIT, Decision.KIND_COMPANION, "companion-outbid", gc, via, null);
        }
        if (sEta != null && cEta > sEta * Math.max(1.0, in.companionPersistCredit)) {
            return base.with(Decision.WAIT, Decision.KIND_COMPANION, "companion-outbid", gc, via, null);
        }
        if (cEta < stay) {
            return base.with(Decision.BUY, Decision.KIND_COMPANION, "companion-sooner", gc, via, null);
        }
        // Slower to this rebirth, but the eggs keep paying past it: only near the rebirth, or
        // once the zone has stopped being bought at all, and only inside the delay budget.
        boolean near = stay <= in.companionRebirthEtaMinMax;
        boolean zoneStopped = in.zoneMaxed || in.companionZoneStopped;
        if ((near || zoneStopped)
            && companionHorizonAllows(batch, in.bal, in.rebirthTarget, in.incomePerMin, gc,
                in.companionMaxRebirthDelayMin, in.companionMaxRebirthDelayPct)) {
            return base.with(Decision.BUY, Decision.KIND_COMPANION,
                zoneStopped && !near ? "companion-end" : "companion-persist", gc, via, null);
        }
        return null;
    }

    /** a is strictly sooner than b; an unknown b never blocks. */
    private static boolean sooner(Double a, Double b) {
        return a != null && (b == null || a < b - 1e-9);
    }

    private static boolean horizonAllows(Inputs in, Double price, double gain) {
        if (!in.horizonEnabled) return true;
        return rebirthHorizonAllows(price, in.bal, in.rebirthTarget, in.incomePerMin, gain);
    }
}

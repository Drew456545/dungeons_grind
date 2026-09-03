package dev.drew.ycbotchallenge;

import dev.drew.ycbotchallenge.mixin.ChatScreenAccessor;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.Vec3d;

/**
 * Stationary, typed {@code /swordmax} / {@code /zone max} / {@code /rebirth}.
 *
 * Sidebar money is truth (no {@code /bal}). Rebirth is seeded from the chat gap
 * {@code You need $29.99T Money to Rebirth.} Evaluations fire on a kill, on a
 * sidebar money increase, or on a timer; if rebirth is covered, sword/zone are
 * skipped. Zone is refused outright while the effective TTK is above
 * {@code zoneMaxTtkMs}; among affordable kinds the 1.25× price ratio decides.
 */
public class UpgradeController {
    private enum Phase { IDLE, WAIT_STILL, PAUSE, TYPE, READ, SETTLE, GUI_WAIT, GUI_LOOK, GUI_CLICK, GUI_ESC }
    private enum Kind { SWORD, ZONE, REBIRTH, GIVEAWAY }

    private record PendingCmd(String text, Kind kind, long notBefore, boolean followUp) {}

    private final YCBotChallengeConfig cfg;
    private final StatsTracker stats;
    private EventLogger logger;

    private Phase phase = Phase.IDLE;
    private long phaseUntil;
    private String pending;
    /** Types the command through the chat screen like a person (shared with the captcha solver). */
    private final ChatTyper typer;
    /** Kind the rebirth horizon blocked at the last eval (HUD "saving"), null when none. */
    private String horizonBlocked = null;
    /** Last eval held an affordable sword back to save for the zone (upgrade_skip saving-zone, HUD). */
    private boolean savingZone = false;
    /** Giveaways: last announcement handled, and the deadline for the queued /giveaway. */
    private int lastGiveawaySeq = 0;
    private long giveawayDeadline = 0;
    /** Income at the moment of the last send; the upgrade_gain evidence compares it with the rate later. */
    private Double incomeAtSend = null;
    private String gainKind = null;
    private Double gainBefore = null;
    private long gainAt = 0;
    private int gainKillsAt = -1;
    private Kind pendingKind = Kind.SWORD;
    private boolean pendingFollowUp = false;
    private final ArrayDeque<PendingCmd> queue = new ArrayDeque<>();
    private int swordsSinceZone;
    private long lastSendAt;
    private long lastSwordSendAt;
    private long lastZoneSendAt;
    private long lastRebirthSendAt;
    private boolean startupProbed = false;
    // Lazy /rebirth knowledge: one seed per session for an unknown account, one
    // deferred re-probe after each rebirth — never the enable ritual.
    private long enabledAt = 0;
    private int killsAtEnable = 0;
    private int seedKillsNeeded = -1;
    private long seedDelayMs = 0;
    private boolean rebirthSeedSent = false;
    private long seenRebirthAt = 0;
    private int killsAtRebirth = 0;
    private int reprobeKillsNeeded = -1;
    private long reprobeDelayMs = 0;
    private boolean reprobeSent = false;
    // Buy hesitation on long saves.
    private long hesitatingUntil = 0;
    private String hesitateKind = null;
    private String lastHesitatedKind = null;
    private int lastKillCount = 0;
    private long evalAt = Long.MAX_VALUE;
    private String decision = null;
    private long decisionAt = 0;
    public String lastKind = null;
    private int lastZoneSeq = -1;
    private int swordAffordableAtKill = -1;
    private int zoneAffordableAtKill = -1;
    private long lastEvalAt = 0;
    private Double lastSeenMoney = null;
    private String evalReason = null;
    private Double evalTtkMs = null;

    public UpgradeController(YCBotChallengeConfig cfg, StatsTracker stats) {
        this.cfg = cfg;
        this.stats = stats;
        this.typer = new ChatTyper(cfg);
    }

    public void setLogger(EventLogger logger) { this.logger = logger; }

    public boolean isBusy() { return phase != Phase.IDLE; }

    public String hudLine() {
        if (!cfg.upgradesEnabled) return null;
        String kind = hudKind();
        Double bal = stats.money();
        Double rate = stats.incomePerMinute();
        StringBuilder sb = new StringBuilder("next ").append(kind == null ? "none" : kind);
        if (bal != null) sb.append("  bal ").append(Amounts.format(bal));
        if (rate != null) sb.append("  +").append(Amounts.format(rate)).append("/min");
        if (stats.rebirthTarget != null) {
            sb.append("  rb ").append(Amounts.format(stats.rebirthTarget));
            Double etaMin = Economy.rebirthEtaMin(bal, stats.rebirthTarget, rate);
            if (etaMin != null && etaMin > 0) sb.append("  eta ").append(formatEta(etaMin * 60_000.0));
            if (horizonBlocked != null) sb.append("  §7(saving; ").append(horizonBlocked).append(" waits)§r");
        }
        if (savingZone && phase == Phase.IDLE) sb.append("  §7(sword waits for zone)§r");
        if (phase != Phase.IDLE) {
            sb.append("  §e").append(phase.name().toLowerCase(Locale.ROOT));
        } else if (kind != null) {
            Double price = targetOf(kind);
            if (price != null && bal != null) {
                double need = Math.max(0, price - bal);
                sb.append("  need ").append(Amounts.format(need));
                Double eta = Economy.etaMs(need, rate);
                if (eta != null) sb.append("  ~").append(formatEta(eta));
            } else if (price == null) {
                Double last = stats.lastPrice(kind);
                sb.append(last != null ? "  price ? (retry ≥ " + Amounts.format(last) + ")" : "  price ?");
            }
            long last = lastSendFor(kind);
            int cap = capFor(kind);
            long remainMs = last <= 0 || cap <= 0 ? 0 : (last + cap) - System.currentTimeMillis();
            if (remainMs > 0) sb.append("  cd ").append((remainMs + 999) / 1000).append("s");
        }
        return sb.toString();
    }

    private static String formatEta(double ms) {
        double s = ms / 1000.0;
        if (s < 90) return Math.round(s) + "s";
        double m = s / 60.0;
        if (m < 90) return Math.round(m) + "m";
        return String.format(Locale.ROOT, "%.1fh", m / 60.0);
    }

    public void reset(MinecraftClient client) {
        closeOurChat(client);
        if (client != null && client.currentScreen instanceof HandledScreen
            && client.currentScreen.getTitle() != null
            && RebirthScreens.isRebirthGui(client.currentScreen.getTitle().getString())) {
            if (client.player != null) client.player.closeHandledScreen();
        }
        typer.cancel(client);
        phase = Phase.IDLE;
        pending = null;
        horizonBlocked = null;
        savingZone = false;
        gainAt = 0;
        queue.clear();
        startupProbed = false;
        lastKillCount = 0;
        evalAt = Long.MAX_VALUE;
        decision = null;
        decisionAt = 0;
        lastZoneSeq = -1;
        swordAffordableAtKill = -1;
        zoneAffordableAtKill = -1;
        lastEvalAt = 0;
        lastSeenMoney = null;
        evalReason = null;
        evalTtkMs = null;
    }

    /**
     * @return true if combat should yield this tick (we're typing or holding still for a command).
     */
    public boolean tick(MinecraftClient client, CombatController combat) {
        if (!cfg.upgradesEnabled || client.player == null) return false;
        long now = System.currentTimeMillis();

        if (phase == Phase.IDLE) {
            if (combat.isOnBreak()) return false;
            if (!startupProbed) {
                startupProbed = true;
                enabledAt = now;
                killsAtEnable = combat.kills;
                seedKillsNeeded = HumanTiming.ticks(cfg.rebirthSeedMinKillsMin, Math.max(cfg.rebirthSeedMinKillsMin, cfg.rebirthSeedMinKillsMax));
                seedDelayMs = HumanTiming.logNormalMs(cfg.rebirthSeedDelayMinMs, Math.max(cfg.rebirthSeedDelayMinMs + 1, cfg.rebirthSeedDelayMaxMs));
            }
            maybeQueueRebirthProbe(combat, now);
            maybeQueueGiveaway(now);
            if (gainAt != 0) {
                if (gainKillsAt < 0) gainKillsAt = combat.kills;
                if (now >= gainAt) {
                    Double after = stats.incomePerMinute();
                    if (logger != null) {
                        logger.log("upgrade_gain", "kind", gainKind,
                            "before", gainBefore != null ? Amounts.format(gainBefore) : null,
                            "after", after != null ? Amounts.format(after) : null,
                            "ratio", gainBefore != null && after != null && gainBefore > 0
                                ? Math.round(100.0 * after / gainBefore) / 100.0 : null,
                            "kills", combat.kills - gainKillsAt,
                            "windowMs", cfg.rebirthHorizonGainWindowMs);
                    }
                    gainAt = 0;
                }
            }
            if (!cfg.serverAutoRebirth && rebirthAffordable()) {
                dropNonRebirthQueue();
                if (decision != null && !"rebirth".equals(decision)) {
                    decision = "rebirth";
                    logPlan("rebirth");
                }
            }
            PendingCmd head = queue.peek();
            if (head != null && head.kind() == Kind.GIVEAWAY && now > giveawayDeadline) {
                queue.poll();
                if (logger != null) logger.log("giveaway_skip", "reason", "window", "lateMs", now - giveawayDeadline);
                head = queue.peek();
            }
            if (head != null) {
                if (now < head.notBefore()) return false;
                if (!commandReady(now)) return false;
                if (!combat.wantsUpgradeWindow && !combat.isStationary(client)) return false;
                begin(client, combat, now, queue.poll());
                return true;
            }
            int zoneSeq = stats.zoneChangeSeq();
            if (zoneSeq != lastZoneSeq) {
                lastZoneSeq = zoneSeq;
                swordsSinceZone = 0;
            }
            if (decision != null) {
                if (now < decisionAt) return false;
                String kind = decision;
                if (!commandReady(now)) return false;
                if (!Economy.cooldownElapsed(now, lastSendFor(kind), capFor(kind), false)) return false;
                if (!combat.wantsUpgradeWindow && !combat.isStationary(client)) {
                    return false;
                }
                decision = null;
                begin(client, combat, now, new PendingCmd(commandOf(kind), kindOf(kind), 0, false));
                return true;
            }
            // Eval triggers: a kill (its money lands ~1s later), a sidebar money increase
            // (the kill credit itself — also catches kills the client missed), and a timer
            // so a stalled stage with a fat balance is never left unevaluated (0.9.6 sat on
            // 8B→21B with a 1.24B sword for 108s because nothing died).
            if (lastEvalAt == 0) lastEvalAt = now;
            Double balNow = stats.money();
            boolean killed = combat.kills != lastKillCount;
            boolean moneyUp = cfg.evalOnMoneyIncrease && balNow != null && lastSeenMoney != null
                && balNow > lastSeenMoney + 1e-6;
            boolean stale = cfg.evalFallbackMs > 0 && now - lastEvalAt >= cfg.evalFallbackMs;
            if (balNow != null) lastSeenMoney = balNow;
            if (killed || moneyUp || stale) {
                lastKillCount = combat.kills;
                if (evalAt == Long.MAX_VALUE) {
                    evalAt = now + HumanTiming.logNormalMs(cfg.postKillEvalDelayMinMs, cfg.postKillEvalDelayMaxMs);
                    evalReason = killed ? "kill" : moneyUp ? "money" : "timer";
                }
            }
            if (evalAt == Long.MAX_VALUE || now < evalAt) return false;
            int settleMs = Math.max(cfg.upgradeSpendSettleMs, cfg.postKillEvalDelayMinMs);
            if (!stats.sidebarSettled(now, settleMs)) {
                evalAt = now + 500; // board still lagging a spend: re-check shortly, never drop the eval
                return false;
            }
            evalAt = Long.MAX_VALUE;
            lastEvalAt = now;

            stats.publishSnapshot(true);
            evalTtkMs = stats.effectiveTtkMs(combat.lastPredictedTtkMs);
            stats.lastEffectiveTtkMs = evalTtkMs;
            updateAffordableMarks(combat.kills);
            String kind = decideKind(combat.kills);
            if (kind == null) {
                if (horizonBlocked != null) {
                    skipEval("rebirth-horizon", horizonBlocked);
                    return false;
                }
                if (savingZone) {
                    skipEval("saving-zone", "sword");
                    return false;
                }
                boolean zoneGated = !Economy.zoneAllowed(evalTtkMs, cfg.zoneMaxTtkMs)
                    && !stats.zoneMaxed && (knownAffordable("zone") || stats.zoneTarget == null);
                skipEval(zoneGated ? "zone-gated" : "unaffordable", null);
                return false;
            }
            if (!Economy.cooldownElapsed(now, lastSendFor(kind), capFor(kind), false)) {
                skipEval("cooldown", kind);
                return false;
            }
            if (hesitate(kind, now)) {
                skipEval("hesitate", kind);
                return false;
            }
            decision = kind;
            decisionAt = now + HumanTiming.logNormalMs(cfg.buyNoticeDelayMinMs, cfg.buyNoticeDelayMaxMs);
            logPlan(kind);
            return false;
        }

        switch (phase) {
            case WAIT_STILL -> {
                combat.releaseKeys(client);
                if (combat.isStationary(client)) {
                    phase = Phase.PAUSE;
                    phaseUntil = now + HumanTiming.logNormalMs(cfg.upgradeStopPauseMinMs, cfg.upgradeStopPauseMaxMs);
                }
            }
            case PAUSE -> {
                combat.releaseKeys(client);
                if (now >= phaseUntil) {
                    phase = Phase.TYPE;
                    typer.begin(client, pending, now);
                }
            }
            case TYPE -> {
                combat.releaseKeys(client);
                ChatTyper.State ts = typer.tick(client, now);
                if (ts == ChatTyper.State.FAILED) {
                    abort(client, typer.failReason());
                    return false;
                }
                if (ts != ChatTyper.State.DONE) return true;
                if (pendingKind == Kind.GIVEAWAY) {
                    // Not an economy command: no price bookkeeping, no response window.
                    lastSendAt = now;
                    if (logger != null) {
                        logger.log("giveaway_join", "command", pending, "typos", typer.typos(),
                            "delayMs", now - stats.giveawaySeenAt, "prize", stats.giveawayPrize);
                    }
                    finish();
                    return false;
                }
                if (logger != null) {
                    logger.log("upgrade_send", "command", pending,
                        "kind", pendingKind.name().toLowerCase(Locale.ROOT),
                        "followUp", pendingFollowUp,
                        "swordsSinceZone", swordsSinceZone,
                        "typos", typer.typos());
                }
                lastSendAt = now;
                lastKind = pendingKind.name().toLowerCase(Locale.ROOT);
                if (pendingKind == Kind.SWORD) lastSwordSendAt = now;
                else if (pendingKind == Kind.ZONE) lastZoneSendAt = now;
                else lastRebirthSendAt = now;
                Double price = targetOf(lastKind);
                if (price == null) {
                    stats.noteSeeded(lastKind);
                    stats.noteExploratorySent(lastKind);
                }
                stats.noteUpgradeSend(lastKind);
                client.setScreen(null);
                if (pendingKind == Kind.REBIRTH) {
                    phase = Phase.GUI_WAIT;
                    phaseUntil = now + Math.max(2500, cfg.upgradeResponseWindowMs);
                    return true;
                }
                phase = Phase.READ;
                phaseUntil = now + HumanTiming.logNormalMs(cfg.upgradeReadPauseMinMs, cfg.upgradeReadPauseMaxMs);
            }
            case READ -> {
                combat.releaseKeys(client);
                if (now < phaseUntil) return true;
                phase = Phase.SETTLE;
                phaseUntil = lastSendAt + Math.max(cfg.successSilenceMs, 1500);
                return true;
            }
            case SETTLE -> {
                combat.releaseKeys(client);
                if (pendingKind == Kind.REBIRTH) {
                    return settleRebirth(client, now);
                }
                if (now < phaseUntil && !stats.lastSendSucceeded && !stats.failSince(kindName(), lastSendAt)) {
                    return true;
                }
                // A success line ends the wait, but /swordmax prints one line per level it
                // bought — give the rest of the burst a moment to land before moving on.
                if (stats.lastSendSucceeded && stats.lastSuccessAt > 0 && now - stats.lastSuccessAt < 600) {
                    return true;
                }
                String kind = kindName();
                if (stats.failSince(kind, lastSendAt)) {
                    finish();
                    return false;
                }
                boolean maxed = pendingKind == Kind.ZONE ? stats.zoneMaxed : stats.swordMaxed;
                if (!stats.lastSendSucceeded && !maxed) stats.onUpgradeSuccess(kind, now);
                // No follow-up re-send: the next tier's price is learned lazily when the
                // balance passes the rolled retry floor (0.9.x re-sent the same command
                // 3.5–4.9s after every success — the loop's clearest fingerprint).
                finish();
                return false;
            }
            case GUI_WAIT -> {
                combat.releaseKeys(client);
                if (rebirthGuiOpen(client)) {
                    phase = Phase.GUI_LOOK;
                    phaseUntil = now + HumanTiming.logNormalMs(cfg.rebirthLookMinMs, cfg.rebirthLookMaxMs);
                    return true;
                }
                if (now >= phaseUntil) {
                    abort(client, "no-gui");
                    return false;
                }
            }
            case GUI_LOOK -> {
                combat.releaseKeys(client);
                if (!rebirthGuiOpen(client)) {
                    if (stats.lastSendSucceeded || (lastSendAt > 0 && !stats.failSince("rebirth", lastSendAt)
                        && stats.rebirths != null)) {
                        if (!stats.lastSendSucceeded) stats.onUpgradeSuccess("rebirth", now);
                        finish();
                        return false;
                    }
                    abort(client, "gui-closed");
                    return false;
                }
                if (stats.failSince("rebirth", lastSendAt)) {
                    phase = Phase.GUI_ESC;
                    phaseUntil = now + HumanTiming.logNormalMs(200, 600);
                    return true;
                }
                if (now < phaseUntil) return true;
                if (rebirthAffordable()) {
                    phase = Phase.GUI_CLICK;
                    phaseUntil = now + HumanTiming.logNormalMs(200, 500);
                    return true;
                }
                if (stats.rebirthTarget != null) {
                    phase = Phase.GUI_ESC;
                    phaseUntil = now + HumanTiming.logNormalMs(200, 600);
                    return true;
                }
                // No gap yet — click once in case we already cover the unknown cost.
                phase = Phase.GUI_CLICK;
                phaseUntil = now + HumanTiming.logNormalMs(200, 500);
            }
            case GUI_CLICK -> {
                combat.releaseKeys(client);
                if (now < phaseUntil) return true;
                if (!clickDiamond(client)) {
                    abort(client, "no-diamond");
                    return false;
                }
                phase = Phase.SETTLE;
                phaseUntil = now + Math.max(cfg.successSilenceMs, 2000);
            }
            case GUI_ESC -> {
                combat.releaseKeys(client);
                if (now < phaseUntil) return true;
                closeRebirthGui(client);
                finish();
                return false;
            }
            default -> { }
        }
        return true;
    }

    private boolean settleRebirth(MinecraftClient client, long now) {
        if (stats.failSince("rebirth", lastSendAt)) {
            closeRebirthGui(client);
            finish();
            return false;
        }
        boolean guiGone = !rebirthGuiOpen(client);
        if (stats.lastSendSucceeded || guiGone) {
            if (!stats.lastSendSucceeded) stats.onUpgradeSuccess("rebirth", now);
            finish();
            return false;
        }
        if (now < phaseUntil) return true;
        abort(client, "rebirth-timeout");
        return false;
    }

    private boolean clickDiamond(MinecraftClient client) {
        if (!(client.currentScreen instanceof HandledScreen<?> hs)) return false;
        ScreenHandler handler = hs.getScreenHandler();
        Integer slot = RebirthScreens.diamondSlot(handler);
        if (slot == null || client.interactionManager == null || client.player == null) return false;
        client.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.PICKUP, client.player);
        stats.armTeleport(cfg.expectedTeleportAfterRebirthMs);
        if (logger != null) logger.log("rebirth_click", "slot", slot);
        return true;
    }

    private static boolean rebirthGuiOpen(MinecraftClient client) {
        if (client.currentScreen == null || client.currentScreen.getTitle() == null) return false;
        return RebirthScreens.isRebirthGui(client.currentScreen.getTitle().getString());
    }

    private static void closeRebirthGui(MinecraftClient client) {
        if (client.player != null && rebirthGuiOpen(client)) {
            client.player.closeHandledScreen();
        } else if (rebirthGuiOpen(client)) {
            client.setScreen(null);
        }
    }

    private void dropNonRebirthQueue() {
        queue.removeIf(c -> c.kind() != Kind.REBIRTH);
    }

    private boolean rebirthQueued() {
        for (PendingCmd c : queue) if (c.kind() == Kind.REBIRTH) return true;
        return false;
    }

    /**
     * Lazy /rebirth knowledge. Unknown account (nothing persisted): one seed per
     * session, only after a rolled number of kills AND minutes of grinding. After a
     * rebirth: one deferred re-probe on the same kind of schedule, so the next goal
     * is learned "at some point" and never seconds after rebirthing. Either way the
     * send is a normal post-kill lull command.
     */
    private void maybeQueueRebirthProbe(CombatController combat, long now) {
        if (stats.rebirthTarget != null || rebirthQueued()) return;
        long rb = stats.lastRebirthAt;
        if (rb != seenRebirthAt) {
            seenRebirthAt = rb;
            killsAtRebirth = combat.kills;
            reprobeKillsNeeded = HumanTiming.ticks(cfg.rebirthReprobeMinKillsMin, Math.max(cfg.rebirthReprobeMinKillsMin, cfg.rebirthReprobeMinKillsMax));
            reprobeDelayMs = HumanTiming.logNormalMs(cfg.rebirthReprobeDelayMinMs, Math.max(cfg.rebirthReprobeDelayMinMs + 1, cfg.rebirthReprobeDelayMaxMs));
            reprobeSent = false;
        }
        if (rb > 0) {
            if (reprobeSent) return;
            if (!Economy.probeDue(combat.kills - killsAtRebirth, reprobeKillsNeeded, now - rb, reprobeDelayMs)) return;
            reprobeSent = true;
            queue.add(new PendingCmd(cfg.rebirthCommand, Kind.REBIRTH, now + HumanTiming.logNormalMs(800, 2000), false));
            if (logger != null) logger.log("upgrade_plan", "kind", "rebirth", "via", "reprobe",
                "killsSinceRebirth", combat.kills - killsAtRebirth, "msSinceRebirth", now - rb);
            return;
        }
        if (stats.lastPrice("rebirth") != null || rebirthSeedSent) return;
        if (!Economy.probeDue(combat.kills - killsAtEnable, seedKillsNeeded, now - enabledAt, seedDelayMs)) return;
        rebirthSeedSent = true;
        queue.add(new PendingCmd(cfg.rebirthCommand, Kind.REBIRTH, now + HumanTiming.logNormalMs(800, 2000), false));
        if (logger != null) logger.log("upgrade_plan", "kind", "rebirth", "via", "seed",
            "killsSinceEnable", combat.kills - killsAtEnable, "msSinceEnable", now - enabledAt);
    }

    /**
     * Giveaways (0.9.17): on a new announcement roll the join chance, then queue the
     * typed /giveaway after a reading delay; it goes out through the same still-and-type
     * path as every command and is dropped if the window closes first.
     */
    private void maybeQueueGiveaway(long now) {
        int seq = stats.giveawaySeq;
        if (seq == lastGiveawaySeq) return;
        lastGiveawaySeq = seq;
        if (!cfg.giveawaysEnabled) {
            if (logger != null) logger.log("giveaway_skip", "reason", "disabled", "prize", stats.giveawayPrize);
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() >= cfg.giveawayJoinChance) {
            if (logger != null) logger.log("giveaway_skip", "reason", "chance", "prize", stats.giveawayPrize);
            return;
        }
        for (PendingCmd c : queue) if (c.kind() == Kind.GIVEAWAY) return;
        long delay = HumanTiming.logNormalMs(cfg.giveawayJoinDelayMinMs, Math.max(cfg.giveawayJoinDelayMinMs + 1, cfg.giveawayJoinDelayMaxMs));
        giveawayDeadline = stats.giveawaySeenAt + cfg.giveawayWindowMs;
        queue.add(new PendingCmd(cfg.giveawayCommand, Kind.GIVEAWAY, now + delay, false));
        if (logger != null) logger.log("giveaway_plan", "prize", stats.giveawayPrize, "delayMs", delay, "seq", seq);
    }

    /** Rebirth cost only grows: once the balance passes the old price (plus a rolled margin), try the GUI. */
    private boolean rebirthRetryDue() {
        if (stats.rebirthTarget != null) return false;
        return Economy.retryUnknownAllowed(stats.lastPrice("rebirth"), stats.money(), stats.retryGrowth("rebirth"));
    }

    /**
     * Long saves only: a person who watched a price for minutes does not always buy
     * the second it is affordable. Never in the post-rebirth snowball (balance dwarfs
     * the price), never for rebirth, never twice in a row for the same kind.
     */
    private boolean hesitate(String kind, long now) {
        if ("rebirth".equals(kind) || cfg.buyHesitationChance <= 0) return false;
        if (hesitatingUntil > now) {
            if (kind.equals(hesitateKind)) return true;
            return false;
        }
        if (hesitateKind != null) { lastHesitatedKind = hesitateKind; hesitateKind = null; }
        if (kind.equals(lastHesitatedKind)) { lastHesitatedKind = null; return false; }
        if (!Economy.hesitationApplies(stats.priceSeenAt(kind), now, cfg.buyHesitationMinSaveMs,
            stats.money(), targetOf(kind), cfg.cooldownRelaxBalanceMult)) return false;
        if (ThreadLocalRandom.current().nextDouble() >= cfg.buyHesitationChance) return false;
        hesitatingUntil = now + HumanTiming.logNormalMs(cfg.buyHesitationMinMs, Math.max(cfg.buyHesitationMinMs + 1, cfg.buyHesitationMaxMs));
        hesitateKind = kind;
        if (logger != null) logger.log("upgrade_hesitate", "kind", kind, "holdMs", hesitatingUntil - now,
            "priceSeenMs", now - stats.priceSeenAt(kind));
        return true;
    }

    private void logPlan(String kind) {
        if (logger == null) return;
        Double price = targetOf(kind);
        Double bal = stats.money();
        logger.log("upgrade_plan", "kind", kind,
            "via", evalReason,
            "priority", hudKind(),
            "target", price != null ? Amounts.format(price) : null,
            "bal", bal != null ? Amounts.format(bal) : null,
            "pct", price != null && price > 0 && bal != null
                ? Math.round(1000.0 * bal / price) / 10.0 : null,
            "incomePerMin", stats.incomePerMinute() != null ? Amounts.format(stats.incomePerMinute()) : null,
            "ttkMs", evalTtkMs != null ? Math.round(evalTtkMs) : null,
            "zoneGate", Economy.zoneAllowed(evalTtkMs, cfg.zoneMaxTtkMs) ? "open" : "closed",
            "swordTarget", stats.swordTarget != null ? Amounts.format(stats.swordTarget) : null,
            "zoneTarget", stats.zoneTarget != null ? Amounts.format(stats.zoneTarget) : null,
            "swordFloor", stats.lastPrice("sword") != null ? Amounts.format(stats.lastPrice("sword")) : null,
            "zoneFloor", stats.lastPrice("zone") != null ? Amounts.format(stats.lastPrice("zone")) : null);
    }

    private boolean zoneOpen() {
        return !stats.zoneMaxed && Economy.zoneAllowed(evalTtkMs, cfg.zoneMaxTtkMs);
    }

    private String hudKind() {
        if (stats.rebirthTarget != null) return "rebirth";
        return Economy.preferredKind(!stats.swordMaxed, zoneOpen());
    }

    /**
     * Rebirth when covered. Gate closed (TTK above zoneMaxTtkMs): the sword, the only
     * thing that helps. Gate open: the zone: bought when affordable, probed first when
     * its price is unknown, saved for otherwise, with a sword allowed only while it is
     * cheap against the zone gap and the TTK is above the movement floor (0.9.16; the
     * 03-36 log spent 675K on swords at a 0.73s TTK next to a 145K zone).
     */
    private String decideKind(int kills) {
        savingZone = false;
        // With /autorebirth the server rebirths the moment the cost is covered; the
        // GUI is only ever probed to learn that cost for the horizon rule.
        if (!cfg.serverAutoRebirth && (rebirthAffordable() || rebirthRetryDue())) return "rebirth";
        boolean zoneOpen = zoneOpen();
        if (zoneOpen && stats.zoneTarget == null) {
            // Learn the zone price before spending on a sword (one typed /zone max).
            String probe = exploreKind("zone", true);
            if (probe != null) return probe;
        }
        String buy = Economy.chooseBuyKind(
            !stats.swordMaxed, zoneOpen,
            knownAffordable("sword"), knownAffordable("zone"),
            stats.swordTarget, stats.zoneTarget, stats.money(), evalTtkMs,
            cfg.swordWhileSavingMaxPct, cfg.zoneInstantTtkMs);
        if (buy != null) {
            if (!horizonAllows(buy)) {
                // The pricier kind failed the horizon; the cheaper one may still pay off.
                String other = "zone".equals(buy) ? "sword" : "zone";
                boolean otherOpen = "zone".equals(other) ? zoneOpen : !stats.swordMaxed;
                if (otherOpen && knownAffordable(other) && horizonAllows(other)) {
                    return extraKillsOk(other, kills) ? other : null;
                }
                horizonBlocked = buy;
                return null;
            }
            return extraKillsOk(buy, kills) ? buy : null;
        }
        if (zoneOpen && stats.zoneTarget != null && !knownAffordable("zone") && knownAffordable("sword")) {
            savingZone = true;
            return null;
        }
        String pref = Economy.preferredKind(!stats.swordMaxed, zoneOpen);
        String explore = exploreKind(pref, zoneOpen);
        if (explore == null && pref != null) explore = exploreKind("zone".equals(pref) ? "sword" : "zone", zoneOpen);
        return explore;
    }

    private String exploreKind(String kind, boolean zoneOpen) {
        if (kind == null || "rebirth".equals(kind)) return null;
        boolean zone = "zone".equals(kind);
        if (zone ? !zoneOpen : stats.swordMaxed) return null;
        Double price = zone ? stats.zoneTarget : stats.swordTarget;
        if (price != null) return null;
        if (!stats.seeded(kind)) return kind;
        if (stats.exploratorySent(kind)) return null;
        if (Economy.retryUnknownAllowed(stats.lastPrice(kind), stats.money(), stats.retryGrowth(kind))) return kind;
        return null;
    }

    private boolean rebirthAffordable() {
        return Economy.knownAffordable(stats.rebirthTarget, stats.money());
    }

    private double horizonGain(String kind) {
        return "zone".equals(kind) ? cfg.rebirthHorizonZoneGain : cfg.rebirthHorizonSwordGain;
    }

    /**
     * Rebirth horizon: a known-price sword/zone is bought only when it pays for itself
     * before the rebirth ({@link Economy#rebirthHorizonAllows}). Clears the blocked mark;
     * the caller sets it when nothing else can be bought.
     */
    private boolean horizonAllows(String kind) {
        horizonBlocked = null;
        if (!cfg.rebirthHorizonEnabled || kind == null || "rebirth".equals(kind)) return true;
        return Economy.rebirthHorizonAllows(targetOf(kind), stats.money(), stats.rebirthTarget,
            stats.incomePerMinute(), horizonGain(kind));
    }

    private boolean knownAffordable(String kind) {
        return Economy.knownAffordable(targetOf(kind), stats.money());
    }

    private Double targetOf(String kind) {
        if ("zone".equals(kind)) return stats.zoneTarget;
        if ("rebirth".equals(kind)) return stats.rebirthTarget;
        return stats.swordTarget;
    }

    private String commandOf(String kind) {
        if ("zone".equals(kind)) return cfg.zoneCommand;
        if ("rebirth".equals(kind)) return cfg.rebirthCommand;
        return cfg.swordCommand;
    }

    private static Kind kindOf(String kind) {
        if ("zone".equals(kind)) return Kind.ZONE;
        if ("rebirth".equals(kind)) return Kind.REBIRTH;
        return Kind.SWORD;
    }

    private String kindName() {
        return pendingKind.name().toLowerCase(Locale.ROOT);
    }

    private void updateAffordableMarks(int kills) {
        if (knownAffordable("sword")) {
            if (swordAffordableAtKill < 0) swordAffordableAtKill = kills;
        } else {
            swordAffordableAtKill = -1;
        }
        if (knownAffordable("zone")) {
            if (zoneAffordableAtKill < 0) zoneAffordableAtKill = kills;
        } else {
            zoneAffordableAtKill = -1;
        }
    }

    private boolean extraKillsOk(String kind, int kills) {
        if ("rebirth".equals(kind)) return true;
        int at = "zone".equals(kind) ? zoneAffordableAtKill : swordAffordableAtKill;
        return Economy.extraKillsReached(kills, at, cfg.minKillsAfterAffordable);
    }

    private long lastSendFor(String kind) {
        if (kind == null) return Math.max(lastSwordSendAt, lastZoneSendAt);
        if ("zone".equals(kind)) return lastZoneSendAt;
        if ("rebirth".equals(kind)) return lastRebirthSendAt;
        return lastSwordSendAt;
    }

    /**
     * Per-kind send cap: none for rebirth; the 60s backstop otherwise, collapsed to
     * the command cooldown while the balance dwarfs the kind's last known price
     * (the early-rebirth snowball, where a 60s hold on /zone max cost a 10× swing).
     */
    private int capFor(String kind) {
        if ("rebirth".equals(kind)) return 0;
        Double ref = targetOf(kind) != null ? targetOf(kind) : stats.lastPrice(kind);
        return Economy.effectiveCooldownMs(cfg.upgradeMinIntervalMs, cfg.commandCooldownMs,
            stats.money(), ref, cfg.cooldownRelaxBalanceMult);
    }

    private boolean commandReady(long now) {
        int cd = Math.max(0, cfg.commandCooldownMs);
        return lastSendAt <= 0 || now - lastSendAt >= cd;
    }

    private void skipEval(String reason, String kind) {
        evalAt = Long.MAX_VALUE;
        if (logger == null) return;
        Double remaining = kind == null ? null : targetOf(kind);
        Double bal = stats.money();
        logger.log("upgrade_skip", "reason", reason,
            "kind", kind,
            "via", evalReason,
            "target", remaining != null ? Amounts.format(remaining) : null,
            "bal", bal != null ? Amounts.format(bal) : null,
            "ttkMs", evalTtkMs != null ? Math.round(evalTtkMs) : null,
            "zoneGate", Economy.zoneAllowed(evalTtkMs, cfg.zoneMaxTtkMs) ? "open" : "closed",
            "swordTarget", stats.swordTarget != null ? Amounts.format(stats.swordTarget) : null,
            "zoneTarget", stats.zoneTarget != null ? Amounts.format(stats.zoneTarget) : null,
            "swordFloor", stats.lastPrice("sword") != null ? Amounts.format(stats.lastPrice("sword")) : null,
            "zoneFloor", stats.lastPrice("zone") != null ? Amounts.format(stats.lastPrice("zone")) : null,
            "rebirthTarget", stats.rebirthTarget != null ? Amounts.format(stats.rebirthTarget) : null,
            "incomePerMin", stats.incomePerMinute() != null ? Amounts.format(stats.incomePerMinute()) : null,
            "rebirthEtaMin", tenth(Economy.rebirthEtaMin(bal, stats.rebirthTarget, stats.incomePerMinute())),
            "buyEtaMin", kind == null ? null
                : tenth(Economy.buyEtaMin(remaining, bal, stats.rebirthTarget, stats.incomePerMinute(), horizonGain(kind))),
            "gain", kind == null ? null : horizonGain(kind),
            "gapPct", kind != null && remaining != null && bal != null && stats.rebirthTarget != null
                && stats.rebirthTarget - bal > 0
                ? Math.round(1000.0 * remaining / (stats.rebirthTarget - bal)) / 10.0 : null,
            "zoneGap", stats.zoneTarget != null && bal != null ? Amounts.format(Math.max(0, stats.zoneTarget - bal)) : null,
            "swordPct", stats.swordTarget != null && stats.zoneTarget != null && bal != null && stats.zoneTarget - bal > 0
                ? Math.round(1000.0 * stats.swordTarget / (stats.zoneTarget - bal)) / 10.0 : null);
    }

    private static Double tenth(Double v) {
        return v == null ? null : Math.round(v * 10.0) / 10.0;
    }

    private void begin(MinecraftClient client, CombatController combat, long now, PendingCmd cmd) {
        pendingKind = cmd.kind();
        pending = cmd.text();
        incomeAtSend = stats.incomePerMinute();
        pendingFollowUp = cmd.followUp();
        combat.releaseKeys(client);
        combat.wantsUpgradeWindow = false;
        MouseDriver.INSTANCE.cancel();
        if (combat.isStationary(client)) {
            phase = Phase.PAUSE;
            phaseUntil = now + HumanTiming.logNormalMs(cfg.upgradeStopPauseMinMs, cfg.upgradeStopPauseMaxMs);
        } else {
            phase = Phase.WAIT_STILL;
        }
    }

    private void finish() {
        if (pendingKind == Kind.SWORD) {
            swordsSinceZone++;
        } else if (pendingKind == Kind.ZONE) {
            swordsSinceZone = 0;
        }
        if (stats.lastSendSucceeded && (pendingKind == Kind.SWORD || pendingKind == Kind.ZONE)
            && cfg.rebirthHorizonGainWindowMs > 0) {
            // Evidence for the horizon gain knobs: income now vs. a few minutes from now.
            gainKind = pendingKind.name().toLowerCase(Locale.ROOT);
            gainBefore = incomeAtSend;
            gainAt = System.currentTimeMillis() + cfg.rebirthHorizonGainWindowMs;
            gainKillsAt = -1;
        }
        phase = Phase.IDLE;
        pending = null;
        pendingFollowUp = false;
        typer.cancel(null);
    }

    private void abort(MinecraftClient client, String why) {
        if (logger != null) logger.log("upgrade_abort", "reason", why,
            "kind", pendingKind != null ? pendingKind.name().toLowerCase(Locale.ROOT) : null);
        closeOurChat(client);
        closeRebirthGui(client);
        if (pendingKind == Kind.REBIRTH && stats.rebirthTarget == null) {
            stats.unseed("rebirth");
            queue.add(new PendingCmd(cfg.rebirthCommand, Kind.REBIRTH,
                System.currentTimeMillis() + HumanTiming.logNormalMs(8_000, 20_000), false));
        }
        phase = Phase.IDLE;
        pending = null;
        pendingFollowUp = false;
        typer.cancel(null);
    }

    private static void closeOurChat(MinecraftClient client) {
        if (client != null && client.currentScreen instanceof ChatScreen) {
            client.setScreen(null);
        }
    }

    static boolean playerStill(MinecraftClient client) {
        if (client.player == null) return false;
        Vec3d v = client.player.getVelocity();
        double h = Math.sqrt(v.x * v.x + v.z * v.z);
        return h < 0.03 && client.player.isOnGround();
    }
}

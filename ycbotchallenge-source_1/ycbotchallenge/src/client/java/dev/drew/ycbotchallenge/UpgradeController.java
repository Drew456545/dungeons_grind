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
 * skipped. Since 0.9.33 every eval is one {@link Decision} from {@link Economy#decide}:
 * the zone is the buy whenever the stage is not measured HARD (kill median, a slow first
 * kill, or the mob being cooked already past the patience), the sword only when it is,
 * or while it is cheap against the zone gap; the same object feeds the log and the HUD.
 */
public class UpgradeController {
    private enum Phase { IDLE, WAIT_STILL, PAUSE, TYPE, READ, SETTLE, GUI_WAIT, GUI_LOOK, GUI_CLICK, GUI_ESC }
    private enum Kind { SWORD, ZONE, REBIRTH, GIVEAWAY, CHAT }

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
    /** Win reply: last win handled, and the deadline for the queued chat line. */
    private int lastWonSeq = 0;
    private long chatDeadline = 0;
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
    /** Kills needed since enable and since the last rebirth before any typed upgrade (rolled). */
    private int firstKillsNeeded = 1;
    private long lastFirstKillsLogAt = 0;
    /** /rebirth probe re-types this session after an abort that never reached the GUI (capped). */
    private int rebirthProbeRetries = 0;
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
    /** Where evalTtkMs came from ("median" / "predicted" / null) — logged with every plan and skip. */
    private String evalTtkVia = null;
    /** Fresh DPS prediction at the last eval (log-only since 0.9.33 unless gateUsesPrediction). */
    private Double evalPredictedMs = null;
    /** The last eval's decision (0.9.33): behaviour, log and HUD all read this one object. */
    private Decision lastDecision = null;
    /** Tag time of the cook whose "already past the patience" verdict was logged (zone_gate_hard). */
    private long lastCookHardLogged = -1;

    public UpgradeController(YCBotChallengeConfig cfg, StatsTracker stats) {
        this.cfg = cfg;
        this.stats = stats;
        this.typer = new ChatTyper(cfg);
    }

    public void setLogger(EventLogger logger) { this.logger = logger; }

    public boolean isBusy() { return phase != Phase.IDLE; }

    /** The kind the rebirth horizon held back at the last eval ("zone"/"sword"), or null (0.9.28: the companion trigger reads it). */
    public String horizonBlockedKind() { return horizonBlocked; }

    /** The last eval's decision, or null before the first one (HUD plan row, companion trigger). */
    public Decision lastDecision() { return lastDecision; }

    /** When the last eval ran (ms), 0 before the first. */
    public long lastEvalAt() { return lastEvalAt; }

    /**
     * 0.9.33 HUD: one short row per kind — "472.7S  26%  ~9m" (a "~" before a ladder-
     * predicted price), "? ≥ 4.4T" while the price is unknown but its floor is, the typing
     * phase while a send of that kind is in flight. What happens next lives on the plan
     * row ({@link #hudPlanLine}). Null when upgrades are off.
     */
    public String hudKindLine(String kind) {
        if (!cfg.upgradesEnabled || kind == null) return null;
        boolean zone = "zone".equals(kind);
        if (zone ? stats.zoneMaxed : stats.swordMaxed) return "§8maxed§r";
        Double bal = stats.money();
        Double rate = stats.incomePerMinute();
        StringBuilder sb = new StringBuilder();
        Double price = targetOf(kind);
        if (price != null) {
            if (stats.targetPredicted(kind)) sb.append("§7~§r");
            sb.append(Amounts.format(price));
            if (bal != null) {
                int pct = (int) Math.min(999, Math.round(100.0 * bal / Math.max(1e-9, price)));
                sb.append("  §7").append(pct).append('%');
                double need = Math.max(0, price - bal);
                Double eta = Economy.etaMs(need, rate);
                if (need > 0 && eta != null) sb.append("  ~").append(formatEta(eta));
                sb.append("§r");
            }
        } else {
            Double last = stats.lastPrice(kind);
            sb.append(last != null ? "§7? ≥ " + Amounts.format(last) + "§r" : "§7?§r");
        }
        if (!zone && stats.swordTierLine() != null) sb.append("  §8").append(stats.swordTierLine()).append("§r");
        if (phase != Phase.IDLE && pendingKind != null && pendingKind.name().equalsIgnoreCase(kind)) {
            sb.append("  §e").append(phase.name().toLowerCase(Locale.ROOT)).append("§r");
        }
        return sb.toString();
    }

    /**
     * 0.9.33 HUD plan row: what the bot does next and why, from the last eval's
     * {@link Decision} — "buy zone 4.4T · stage open 1.2s", "save for zone 11.49SS 64% ~3m ·
     * sword 142% of gap", "wait · new stage, 0 kill(s) so far" — or the send in flight
     * ("typing /zone max", "→ /swordmax in 4s"). The eval is at most evalFallbackMs old; its
     * age is shown past 10 s so a stale line never reads as live.
     */
    public String hudPlanLine() {
        if (!cfg.upgradesEnabled) return "§8upgrades off§r";
        long now = System.currentTimeMillis();
        if (phase != Phase.IDLE && pending != null) {
            return "§e" + phase.name().toLowerCase(Locale.ROOT) + " " + pending + "§r";
        }
        if (decision != null) {
            long in = decisionAt - now;
            return "§a→ " + commandOf(decision) + (in > 0 ? " in " + ((in + 999) / 1000) + "s" : " now") + "§r";
        }
        if (lastDecision == null) return "§8waiting for the first eval§r";
        Decision d = lastDecision;
        String text = d.hudPlan(d.kind() != null ? targetOf(d.kind()) : null, stats.money());
        String color = d.acts() ? "§a" : Decision.NONE.equals(d.action()) ? "§8" : "§7";
        long age = now - d.at();
        String suffix = age > 10_000 ? "  §8" + formatEta(age) + " ago§r" : "";
        return color + text + "§r" + suffix;
    }

    /** 0.9.30 HUD: "656.09S  eta 27m", or null without a known rebirth target. */
    public String hudRebirthLine() {
        if (stats.rebirthTarget == null) return null;
        Double bal = stats.money();
        Double rate = stats.incomePerMinute();
        StringBuilder sb = new StringBuilder(Amounts.format(stats.rebirthTarget));
        Double etaMin = Economy.rebirthEtaMin(bal, stats.rebirthTarget, rate);
        if (etaMin != null && etaMin > 0) sb.append("  §7eta ").append(formatEta(etaMin * 60_000.0)).append("§r");
        else if (etaMin != null) sb.append("  §acovered§r");
        return sb.toString();
    }

    private static String formatEta(double ms) {
        return Amounts.eta(ms);
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
        lastDecision = null;
        lastCookHardLogged = -1;
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
                // A quick re-enable on the same stage keeps the kill window (0.9.33) and with
                // it the first-kills roll; otherwise both start over.
                boolean kept = stats.lastEnableKeptWindow && killsAtEnable > 0 && killsAtEnable <= combat.kills;
                if (!kept) {
                    killsAtEnable = combat.kills;
                    firstKillsNeeded = HumanTiming.ticks(cfg.upgradeFirstKillsMin, Math.max(cfg.upgradeFirstKillsMin, cfg.upgradeFirstKillsMax));
                }
                // The kill counter carries over a toggle: without this the first eval after an
                // enable read as "a kill happened" and typed /swordmax 5 s in (00:19 log).
                lastKillCount = combat.kills;
                seedKillsNeeded = HumanTiming.ticks(cfg.rebirthSeedMinKillsMin, Math.max(cfg.rebirthSeedMinKillsMin, cfg.rebirthSeedMinKillsMax));
                seedDelayMs = HumanTiming.logNormalMs(cfg.rebirthSeedDelayMinMs, Math.max(cfg.rebirthSeedDelayMinMs + 1, cfg.rebirthSeedDelayMaxMs));
            }
            maybeQueueRebirthProbe(combat, now);
            maybeQueueGiveaway(now);
            maybeQueueWinReply(now);
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
                    lastDecision = decide(combat, now, null);
                    logPlan(lastDecision);
                }
            }
            PendingCmd head = queue.peek();
            if (head != null && head.kind() == Kind.GIVEAWAY && now > giveawayDeadline) {
                queue.poll();
                if (logger != null) logger.log("giveaway_skip", "reason", "window", "lateMs", now - giveawayDeadline);
                head = queue.peek();
            }
            if (head != null && head.kind() == Kind.CHAT && now > chatDeadline) {
                queue.poll();
                if (logger != null) logger.log("giveaway_reply_skip", "reason", "window", "text", head.text());
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
            Double predicted = Economy.freshPrediction(combat.lastPredictedTtkMs, combat.lastPredictedAt,
                now, cfg.predictedTtkMaxAgeMs);
            evalPredictedMs = predicted;
            // 0.9.33: the gate reads the kill median only; the prediction is logged (and used
            // only behind the legacy gateUsesPrediction switch).
            evalTtkMs = cfg.gateUsesPrediction ? stats.effectiveTtkMs(predicted) : stats.medianTtkMs();
            evalTtkVia = cfg.gateUsesPrediction ? Economy.ttkSource(predicted, stats.medianTtkMs())
                : evalTtkMs != null ? "median" : null;
            stats.lastEffectiveTtkMs = evalTtkMs;
            updateAffordableMarks(combat.kills);
            Decision d = decide(combat, now, predicted);
            horizonBlocked = "rebirth-horizon".equals(d.reason()) ? d.kind() : null;
            savingZone = "saving-zone".equals(d.reason());
            if (d.acts() && !Economy.firstKillsReached(combat.kills - killsAtEnable, combat.kills - killsAtRebirth, firstKillsNeeded)) {
                lastDecision = d.hold("first-kills", null);
                evalAt = Long.MAX_VALUE;
                if (logger != null && now - lastFirstKillsLogAt > 20_000) {
                    lastFirstKillsLogAt = now;
                    logger.log("upgrade_skip", evalFields(lastDecision,
                        "killsSinceEnable", combat.kills - killsAtEnable, "killsSinceRebirth", combat.kills - killsAtRebirth,
                        "needed", firstKillsNeeded));
                }
                return false;
            }
            if (!d.acts()) {
                lastDecision = d;
                skipEval(d);
                return false;
            }
            String kind = d.kind();
            if (!extraKillsOk(kind, combat.kills)) {
                lastDecision = d.hold("extra-kills", null);
                skipEval(lastDecision);
                return false;
            }
            if (!Economy.cooldownElapsed(now, lastSendFor(kind), capFor(kind), false)) {
                lastDecision = d.hold("cooldown", (double) Math.max(0, lastSendFor(kind) + capFor(kind) - now));
                skipEval(lastDecision);
                return false;
            }
            if (hesitate(kind, now)) {
                lastDecision = d.hold("hesitate", (double) Math.max(0, hesitatingUntil - now));
                skipEval(lastDecision);
                return false;
            }
            lastDecision = d;
            decision = kind;
            decisionAt = now + HumanTiming.logNormalMs(cfg.buyNoticeDelayMinMs, cfg.buyNoticeDelayMaxMs);
            logPlan(d);
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
                if (pendingKind == Kind.CHAT) {
                    lastSendAt = now;
                    if (logger != null) logger.log("giveaway_reply", "text", pending, "typos", typer.typos());
                    finish();
                    return false;
                }
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
                        "typos", typer.typos(),
                        "typedMismatch", typer.typedMismatch() ? true : null);
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
                    if (Economy.rebirthConfirmed(stats.lastRebirthAt, lastSendAt)) {
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
                phaseUntil = now + Math.max(cfg.rebirthSignalWaitMs, Math.max(cfg.successSilenceMs, 2000));
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

    /**
     * After the diamond click: the server's fail line ends it (price learned, or an
     * unknown amount logged), the server's own rebirth signal ends it (the reset
     * already ran from that signal), and nothing else does — a closed GUI is waited
     * out for rebirthSignalWaitMs and then aborted without touching the economy.
     */
    private boolean settleRebirth(MinecraftClient client, long now) {
        if (stats.failSince("rebirth", lastSendAt)) {
            closeRebirthGui(client);
            finish();
            return false;
        }
        if (Economy.rebirthConfirmed(stats.lastRebirthAt, lastSendAt)) {
            finish();
            return false;
        }
        if (now < phaseUntil) return true;
        abort(client, rebirthGuiOpen(client) ? "rebirth-timeout" : "no-signal");
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
            firstKillsNeeded = HumanTiming.ticks(cfg.upgradeFirstKillsMin, Math.max(cfg.upgradeFirstKillsMin, cfg.upgradeFirstKillsMax));
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
        if (rebirthSeedSent) return;
        // A persisted floor is a lower bound from an earlier rebirth. Once the balance sits on
        // it with no rebirth having fired, it is stale (0.9.23: 155Q against a 900T floor) and
        // the seed probe is due on the same lazy schedule as for an unknown account.
        Double floor = stats.lastPrice("rebirth");
        boolean stale = floor != null && Economy.rebirthFloorStale(floor, stats.money(), stats.retryGrowth("rebirth"));
        if (floor != null && !stale) return;
        if (!Economy.probeDue(combat.kills - killsAtEnable, seedKillsNeeded, now - enabledAt, seedDelayMs)) return;
        rebirthSeedSent = true;
        queue.add(new PendingCmd(cfg.rebirthCommand, Kind.REBIRTH, now + HumanTiming.logNormalMs(800, 2000), false));
        if (logger != null) logger.log("upgrade_plan", "kind", "rebirth", "via", stale ? "stale-floor" : "seed",
            "floor", floor != null ? Amounts.format(floor) : null,
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

    /** We won ("<name> has won the giveaway for"): say one of the configured lines after a beat. */
    private void maybeQueueWinReply(long now) {
        int seq = stats.giveawayWonSeq;
        if (seq == lastWonSeq) return;
        lastWonSeq = seq;
        if (!cfg.giveawayWinReplyEnabled || cfg.giveawayWinMessages == null || cfg.giveawayWinMessages.isEmpty()) return;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        if (rng.nextDouble() >= cfg.giveawayWinReplyChance) {
            if (logger != null) logger.log("giveaway_reply_skip", "reason", "chance");
            return;
        }
        String msg = cfg.giveawayWinMessages.get(rng.nextInt(cfg.giveawayWinMessages.size()));
        if (msg == null || msg.isBlank()) return;
        long delay = HumanTiming.logNormalMs(cfg.giveawayWinReplyDelayMinMs, Math.max(cfg.giveawayWinReplyDelayMinMs + 1, cfg.giveawayWinReplyDelayMaxMs));
        chatDeadline = now + 30_000;
        queue.add(new PendingCmd(msg.trim(), Kind.CHAT, now + delay, false));
        if (logger != null) logger.log("giveaway_reply_plan", "text", msg.trim(), "delayMs", delay, "won", stats.giveawaysWon);
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

    private void logPlan(Decision d) {
        if (logger == null) return;
        logger.log("upgrade_plan", evalFields(d));
    }

    /**
     * Every fact behind the decision (0.9.33): the {@link Decision#kv()} vocabulary plus the
     * prices, floors, rebirth horizon numbers and the log-only DPS prediction.
     */
    private Object[] evalFields(Decision d, Object... extra) {
        String kind = d.kind();
        Double price = kind == null ? null : targetOf(kind);
        Double bal = stats.money();
        Double income = stats.incomePerMinute();
        double gain = d.gain() != null ? d.gain() : 1.0;
        java.util.List<Object> out = new java.util.ArrayList<>(java.util.Arrays.asList(d.kv()));
        java.util.Collections.addAll(out,
            "via", evalReason,
            "priority", kind,
            "target", price != null ? Amounts.format(price) : null,
            "bal", bal != null ? Amounts.format(bal) : null,
            "pct", price != null && price > 0 && bal != null ? Math.round(1000.0 * bal / price) / 10.0 : null,
            "incomePerMin", income != null ? Amounts.format(income) : null,
            "predictedMs", evalPredictedMs != null ? Math.round(evalPredictedMs) : null,
            "stageMaxTtkMs", stats.stageMaxTtkMs() != null ? Math.round(stats.stageMaxTtkMs()) : null,
            "swordTarget", stats.swordTarget != null ? Amounts.format(stats.swordTarget) : null,
            "swordVia", stats.swordTarget == null ? null : stats.swordTargetPredicted ? "predicted" : "server",
            "zoneTarget", stats.zoneTarget != null ? Amounts.format(stats.zoneTarget) : null,
            "zoneVia", stats.zoneTarget == null ? null : stats.zoneTargetPredicted ? "predicted" : "server",
            "swordFloor", stats.lastPrice("sword") != null ? Amounts.format(stats.lastPrice("sword")) : null,
            "zoneFloor", stats.lastPrice("zone") != null ? Amounts.format(stats.lastPrice("zone")) : null,
            "rebirthTarget", stats.rebirthTarget != null ? Amounts.format(stats.rebirthTarget) : null,
            "rebirthEtaMin", tenth(Economy.rebirthEtaMin(bal, stats.rebirthTarget, income)),
            "buyEtaMin", price == null ? null : tenth(Economy.buyEtaMin(price, bal, stats.rebirthTarget, income, gain)),
            "gapPct", price != null && bal != null && stats.rebirthTarget != null && stats.rebirthTarget - bal > 0
                ? Math.round(1000.0 * price / (stats.rebirthTarget - bal)) / 10.0 : null);
        java.util.Collections.addAll(out, extra);
        return out.toArray();
    }

    /** Kind of the last decision (HUD "next" marker); null before the first eval. */
    private String hudKind() {
        return lastDecision != null ? lastDecision.kind() : null;
    }

    /**
     * The eval (0.9.33): plain facts in, one {@link Decision} out ({@link Economy#decide}).
     * The cook-elapsed HARD verdict is logged once per cook (zone_gate_hard) so a stage
     * going hard mid-fight is visible between evals.
     */
    private Decision decide(CombatController combat, long now, Double predicted) {
        Economy.Inputs in = new Economy.Inputs();
        in.swordTarget = stats.swordTarget;
        in.zoneTarget = stats.zoneTarget;
        in.rebirthTarget = stats.rebirthTarget;
        in.swordFloor = stats.lastPrice("sword");
        in.zoneFloor = stats.lastPrice("zone");
        in.zoneGrowth = stats.priceGrowth("zone");
        in.bal = stats.money();
        in.incomePerMin = stats.incomePerMinute();
        in.medianTtkMs = stats.medianTtkMs();
        in.stageKills = stats.stageKills();
        in.stageMaxTtkMs = stats.stageMaxTtkMs();
        in.predictedTtkMs = cfg.gateUsesPrediction ? predicted : null;
        in.cookElapsedMs = combat.isCooking()
            ? combat.cookElapsedMs() / Economy.rarityScale(combat.targetRarity(), cfg.rarityHpScale) : 0;
        in.patienceMs = stats.zoneTtkToleranceMs();
        in.swordMaxed = stats.swordMaxed;
        in.zoneMaxed = stats.zoneMaxed;
        in.swordSeeded = stats.seeded("sword");
        in.swordExploratorySent = stats.exploratorySent("sword");
        in.swordRetryGrowth = stats.retryGrowth("sword");
        in.zoneSeeded = stats.seeded("zone");
        in.zoneExploratorySent = stats.exploratorySent("zone");
        in.zoneRetryGrowth = stats.retryGrowth("zone");
        in.serverAutoRebirth = cfg.serverAutoRebirth;
        in.rebirthAffordable = rebirthAffordable();
        in.rebirthRetryDue = rebirthRetryDue();
        in.savingMaxPct = cfg.swordWhileSavingMaxPct;
        in.instantTtkMs = cfg.zoneInstantTtkMs;
        in.horizonEnabled = cfg.rebirthHorizonEnabled;
        in.zoneGain = cfg.rebirthHorizonZoneGain;
        in.swordDpsMult = cfg.rebirthHorizonSwordDpsMult;
        in.swordGainFloor = cfg.rebirthHorizonSwordGain;
        in.zoneMinStageKills = cfg.zoneMinStageKills;
        in.now = now;
        Decision d = Economy.decide(in);
        if ("cook".equals(d.gateVia()) && logger != null && combat.cookStartMs() != lastCookHardLogged) {
            lastCookHardLogged = combat.cookStartMs();
            logger.log("zone_gate_hard", "via", "cook", "elapsedMs", combat.cookElapsedMs(),
                "normalizedMs", Math.round(in.cookElapsedMs), "patienceMs", in.patienceMs,
                "rarity", combat.targetRarity(), "stageKills", in.stageKills);
        }
        return d;
    }

    private boolean rebirthAffordable() {
        return Economy.knownAffordable(stats.rebirthTarget, stats.money());
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

    private void skipEval(Decision d) {
        evalAt = Long.MAX_VALUE;
        if (logger == null) return;
        logger.log("upgrade_skip", evalFields(d));
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
            if (Economy.rebirthProbeRetryAllowed(why, rebirthProbeRetries, cfg.rebirthProbeMaxRetries)) {
                rebirthProbeRetries++;
                stats.unseed("rebirth");
                queue.add(new PendingCmd(cfg.rebirthCommand, Kind.REBIRTH,
                    System.currentTimeMillis() + HumanTiming.logNormalMs(8_000, 20_000), false));
                if (logger != null) logger.log("rebirth_probe_retry", "reason", why, "retry", rebirthProbeRetries);
            } else if (logger != null) {
                // Unresolved: the next look at /rebirth is the deferred re-probe after the
                // next rebirth, or the next session's seed — never a re-type in seconds.
                logger.log("rebirth_probe_unresolved", "reason", why, "retries", rebirthProbeRetries);
            }
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

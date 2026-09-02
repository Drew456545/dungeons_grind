package dev.drew.ycbotchallenge;

import dev.drew.ycbotchallenge.mixin.ChatScreenAccessor;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.util.math.Vec3d;

/**
 * Stationary, typed {@code /swordmax} / {@code /zone max} (+ a startup {@code /bal} seed).
 *
 * Zero-spam, event-driven buys: the chat-driven money book (bal seed + reward-summary
 * accrual + fail-gap anchors) is evaluated after each kill, and a command is typed
 * only when the book covers a known price — after a humanized "notice" delay. Fail
 * lines teach the absolute price (bal + gap); silence after a send means the buy
 * went through, so the price resets and the next attempt waits until the balance
 * passes the OLD price again. No polling, no reprobe chains.
 */
public class UpgradeController {
    private enum Phase { IDLE, WAIT_STILL, PAUSE, OPEN, TYPE, SEND, READ, SETTLE }
    private enum Kind { SWORD, ZONE, PROBE }

    private record PendingCmd(String text, Kind kind, long notBefore, boolean followUp) {}

    private final YCBotChallengeConfig cfg;
    private final StatsTracker stats;
    private EventLogger logger;

    private Phase phase = Phase.IDLE;
    private long phaseUntil;
    private String pending;
    private String typed = "";
    private int typedChars;
    private int typoAt = -1;
    private Kind pendingKind = Kind.SWORD;
    private final ArrayDeque<PendingCmd> queue = new ArrayDeque<>();
    private int swordsSinceZone;
    private long lastSendAt;
    private long lastSwordSendAt;
    private long lastZoneSendAt;
    private boolean startupProbed = false;
    /** Kill-driven economy: one affordability evaluation per kill, after the sidebar settles. */
    private int lastKillCount = 0;
    /** {@link Long#MAX_VALUE} = no eval pending. Must not start at 0 (that is immediately due). */
    private long evalAt = Long.MAX_VALUE;
    /** A decided buy awaiting its humanized notice delay before typing. */
    private String decision = null;
    private long decisionAt = 0;
    public String lastKind = null;
    private int lastZoneSeq = -1;
    private int swordAffordableAtKill = -1;
    private int zoneAffordableAtKill = -1;

    public UpgradeController(YCBotChallengeConfig cfg, StatsTracker stats) {
        this.cfg = cfg;
        this.stats = stats;
    }

    public void setLogger(EventLogger logger) { this.logger = logger; }

    public boolean isBusy() { return phase != Phase.IDLE; }

    public String hudLine() {
        if (!cfg.upgradesEnabled) return null;
        String kind = hudKind();
        Double bal = stats.money();
        Double rate = stats.incomePerMinute();
        Double ttk = stats.medianTtkMs();
        double R = stats.zoneReadiness();
        StringBuilder sb = new StringBuilder("next ").append(kind == null ? "none (maxed)" : kind);
        if (bal != null) sb.append("  bal ").append(Amounts.format(bal));
        if (rate != null) sb.append("  +").append(Amounts.format(rate)).append("/min");
        if (ttk != null) {
            sb.append("  ttk ").append(String.format(Locale.ROOT, "%.1fs", ttk / 1000.0));
            sb.append("  zone ").append(Math.round(100.0 * R)).append("%");
            Double base = stats.zoneBaselineTtkMs();
            if (base != null && base > 0) {
                sb.append(" §8(").append(String.format(Locale.ROOT, "%+.0f%%", 100.0 * (ttk - base) / base)).append(")§7");
            }
        }
        if (phase != Phase.IDLE) {
            sb.append("  §e").append(phase.name().toLowerCase(Locale.ROOT));
        } else if (kind != null) {
            Double price = "zone".equals(kind) ? stats.zoneTarget : stats.swordTarget;
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
            long remainMs = last <= 0 ? 0 : (last + Math.max(0, cfg.upgradeMinIntervalMs)) - System.currentTimeMillis();
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
        phase = Phase.IDLE;
        pending = null;
        typed = "";
        typedChars = 0;
        typoAt = -1;
        queue.clear();
        startupProbed = false;
        lastKillCount = 0;
        evalAt = Long.MAX_VALUE;
        decision = null;
        decisionAt = 0;
        lastZoneSeq = -1;
        swordAffordableAtKill = -1;
        zoneAffordableAtKill = -1;
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
                if (cfg.startupProbes) {
                    // a player checks their balance when they sit down to grind
                    queue.add(new PendingCmd(cfg.balCommand, Kind.PROBE,
                        now + HumanTiming.logNormalMs(800, 2000), false));
                }
            }
            if (stats.consumeBalReseed()) {
                // a fail arrived while the balance was unknown — re-seed once
                queue.add(new PendingCmd(cfg.balCommand, Kind.PROBE,
                    now + HumanTiming.logNormalMs(1200, 3000), false));
            }
            PendingCmd head = queue.peek();
            if (head != null) {
                if (now < head.notBefore()) return false;
                if (!combat.wantsUpgradeWindow && !combat.isStationary(client)) return false;
                begin(client, combat, now, queue.poll());
                return true;
            }
            int zoneSeq = stats.zoneChangeSeq();
            if (zoneSeq != lastZoneSeq) {
                lastZoneSeq = zoneSeq;
                swordsSinceZone = 0;
            }
            // A decided buy whose humanized notice delay elapsed fires without waiting for another kill.
            if (decision != null) {
                if (now < decisionAt) return false;
                String kind = decision;
                decision = null;
                if (!Economy.cooldownElapsed(now, lastSendFor(kind), cfg.upgradeMinIntervalMs, false)) return false;
                if (!combat.wantsUpgradeWindow && !combat.isStationary(client)) {
                    decision = kind; // still pending — try again next tick
                    return false;
                }
                begin(client, combat, now, new PendingCmd(
                    "zone".equals(kind) ? cfg.zoneCommand : cfg.swordCommand,
                    "zone".equals(kind) ? Kind.ZONE : Kind.SWORD, 0, false));
                return true;
            }
            // Kill-driven: one evaluation per kill, after the sidebar balance settles.
            if (combat.kills != lastKillCount) {
                lastKillCount = combat.kills;
                evalAt = now + HumanTiming.logNormalMs(cfg.postKillEvalDelayMinMs, cfg.postKillEvalDelayMaxMs);
            }
            if (evalAt == Long.MAX_VALUE || now < evalAt) return false;
            evalAt = Long.MAX_VALUE;
            int settleMs = Math.max(cfg.upgradeSpendSettleMs, cfg.postKillEvalDelayMinMs);
            if (!stats.sidebarSettled(now, settleMs)) return false;

            stats.publishSnapshot(true);
            updateAffordableMarks(combat.kills);
            String kind = decideKind(combat.kills);
            if (kind == null) {
                skipEval("unaffordable", null);
                return false;
            }
            if (!Economy.cooldownElapsed(now, lastSendFor(kind), cfg.upgradeMinIntervalMs, false)) {
                skipEval("cooldown", kind);
                return false;
            }
            // Humanized "notice" delay: a player finishes the mob, glances at their bal, then types.
            decision = kind;
            decisionAt = now + HumanTiming.logNormalMs(cfg.buyNoticeDelayMinMs, cfg.buyNoticeDelayMaxMs);
            Double price = "zone".equals(kind) ? stats.zoneTarget : stats.swordTarget;
            Double bal = stats.money();
            if (logger != null) {
                logger.log("upgrade_plan", "kind", kind,
                    "target", price != null ? Amounts.format(price) : null,
                    "bal", bal != null ? Amounts.format(bal) : null,
                    "pct", price != null && price > 0 && bal != null
                        ? Math.round(1000.0 * bal / price) / 10.0 : null,
                    "zoneReady", Math.round(1000.0 * stats.zoneReadiness()) / 10.0,
                    "incomePerMin", stats.incomePerMinute() != null ? Amounts.format(stats.incomePerMinute()) : null,
                    "ttkMs", stats.medianTtkMs());
            }
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
                    phase = Phase.OPEN;
                    client.setScreen(new ChatScreen("", false));
                    phaseUntil = now + 80; // let init() create the field
                }
            }
            case OPEN -> {
                combat.releaseKeys(client);
                if (now < phaseUntil) return true;
                if (!(client.currentScreen instanceof ChatScreen)) {
                    abort(client, "chat-closed");
                    return false;
                }
                phase = Phase.TYPE;
                typed = "";
                typedChars = 0;
                typoAt = -1;
                phaseUntil = now + HumanTiming.logNormalMs(cfg.typeKeyMinMs, cfg.typeKeyMaxMs);
            }
            case TYPE -> {
                combat.releaseKeys(client);
                if (!(client.currentScreen instanceof ChatScreen cs)) {
                    abort(client, "chat-closed");
                    return false;
                }
                if (now < phaseUntil) return true;
                TextFieldWidget field = ((ChatScreenAccessor) cs).ycBotChallenge$getChatField();
                if (typoAt >= 0) {
                    // noticed the wrong char: backspace it, then retype on the next step
                    typed = typed.substring(0, typed.length() - 1);
                    if (field != null) field.setText(typed);
                    typoAt = -1;
                    phaseUntil = now + HumanTiming.logNormalMs(cfg.typeKeyMinMs, cfg.typeKeyMaxMs + 120);
                    return true;
                }
                if (typedChars < pending.length()) {
                    ThreadLocalRandom rng = ThreadLocalRandom.current();
                    char c = pending.charAt(typedChars);
                    boolean typo = cfg.ninja && typedChars > 1 && Character.isLetterOrDigit(c)
                        && rng.nextDouble() < cfg.typoChancePerChar;
                    if (typo) {
                        typed += (char) ('a' + rng.nextInt(26));
                        typoAt = typedChars;
                    } else {
                        typed += c;
                    }
                    typedChars++;
                    if (field != null) field.setText(typed);
                    else cs.insertText(String.valueOf(typed.charAt(typed.length() - 1)), false);
                    phaseUntil = now + HumanTiming.logNormalMs(cfg.typeKeyMinMs, cfg.typeKeyMaxMs);
                } else {
                    phase = Phase.SEND;
                    phaseUntil = now + HumanTiming.logNormalMs(80, 220);
                }
            }
            case SEND -> {
                combat.releaseKeys(client);
                if (now < phaseUntil) return true;
                if (!(client.currentScreen instanceof ChatScreen cs)) {
                    abort(client, "chat-closed");
                    return false;
                }
                if (logger != null) {
                    logger.log("upgrade_send", "command", pending,
                        "kind", pendingKind.name().toLowerCase(Locale.ROOT),
                        "swordsSinceZone", swordsSinceZone);
                }
                cs.sendMessage(pending, true);
                lastSendAt = now;
                if (pendingKind != Kind.PROBE) {
                    lastKind = pendingKind == Kind.SWORD ? "sword" : "zone";
                    if (pendingKind == Kind.SWORD) lastSwordSendAt = now;
                    else lastZoneSendAt = now;
                    Double price = pendingKind == Kind.SWORD ? stats.swordTarget : stats.zoneTarget;
                    if (price == null) {
                        // unknown-price send: seed or old-price retry — resolves on the response
                        stats.noteSeeded(lastKind);
                        stats.noteExploratorySent(lastKind);
                    }
                    stats.noteUpgradeSend(pendingKind == Kind.SWORD);
                } else {
                    stats.noteProbeSend();
                }
                client.setScreen(null);
                phase = Phase.READ;
                phaseUntil = now + HumanTiming.logNormalMs(cfg.upgradeReadPauseMinMs, cfg.upgradeReadPauseMaxMs);
            }
            case READ -> {
                combat.releaseKeys(client);
                if (now < phaseUntil) return true;
                if (pendingKind == Kind.SWORD || pendingKind == Kind.ZONE) {
                    // Wait out the response window: a fail line resolves the price,
                    // silence means the purchase went through.
                    phase = Phase.SETTLE;
                    phaseUntil = lastSendAt + Math.max(cfg.successSilenceMs, 1500);
                    return true;
                }
                finish();
                return false;
            }
            case SETTLE -> {
                combat.releaseKeys(client);
                if (now < phaseUntil) return true;
                String kind = pendingKind == Kind.ZONE ? "zone" : "sword";
                if (!stats.failSince(kind, lastSendAt)) {
                    stats.onUpgradeSuccess(kind, now);
                    // a human checks their balance after a big purchase
                    queue.add(new PendingCmd(cfg.balCommand, Kind.PROBE,
                        now + HumanTiming.logNormalMs(1500, 4000), false));
                }
                finish();
                return false;
            }
            default -> { }
        }
        return true;
    }

    /** HUD: what we're working toward, independent of whether we can afford it yet. */
    private String hudKind() {
        return Economy.preferredKind(!stats.swordMaxed, !stats.zoneMaxed, stats.zoneReadiness());
    }

    /**
     * What to buy, or null to wait. Known-affordable kinds first; otherwise one
     * unknown-price exploration per kind — seeded once per session, then retried
     * only once the balance passes the OLD price again (never a poll, never an
     * immediate reprobe).
     */
    private String decideKind(int kills) {
        Double bal = stats.money();
        double R = stats.zoneReadiness();
        String buy = Economy.chooseBuyKind(
            !stats.swordMaxed, !stats.zoneMaxed,
            knownAffordable("sword"), knownAffordable("zone"),
            R, cfg.zoneMinReadiness);
        if (buy != null) {
            return extraKillsOk(buy, kills) ? buy : null;
        }
        String pref = Economy.preferredKind(!stats.swordMaxed, !stats.zoneMaxed, R);
        if ("zone".equals(pref) && R < cfg.zoneMinReadiness) pref = stats.swordMaxed ? null : "sword";
        String explore = exploreKind(pref);
        if (explore == null && pref != null) explore = exploreKind("zone".equals(pref) ? "sword" : "zone");
        return explore;
    }

    private String exploreKind(String kind) {
        if (kind == null) return null;
        boolean zone = "zone".equals(kind);
        if (zone ? stats.zoneMaxed : stats.swordMaxed) return null;
        Double price = zone ? stats.zoneTarget : stats.swordTarget;
        if (price != null) return null; // known price is handled by the affordable path
        if (!stats.seeded(kind)) return kind; // first-ever discovery send
        if (stats.exploratorySent(kind)) return null; // one in flight / unresolved
        if (Economy.retryUnknownAllowed(stats.lastPrice(kind), stats.money(), cfg.retryPriceGrowthPct)) return kind;
        return null;
    }

    private boolean knownAffordable(String kind) {
        Double need = "zone".equals(kind) ? stats.zoneTarget : stats.swordTarget;
        return Economy.knownAffordable(need, stats.money());
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
        int at = "zone".equals(kind) ? zoneAffordableAtKill : swordAffordableAtKill;
        return Economy.extraKillsReached(kills, at, cfg.minKillsAfterAffordable);
    }

    private long lastSendFor(String kind) {
        if (kind == null) return Math.max(lastSwordSendAt, lastZoneSendAt);
        return "zone".equals(kind) ? lastZoneSendAt : lastSwordSendAt;
    }

    private void skipEval(String reason, String kind) {
        evalAt = Long.MAX_VALUE;
        if (logger == null) return;
        Double remaining = kind == null ? null
            : ("zone".equals(kind) ? stats.zoneTarget : stats.swordTarget);
        Double bal = stats.money();
        logger.log("upgrade_skip", "reason", reason,
            "kind", kind,
            "target", remaining != null ? Amounts.format(remaining) : null,
            "bal", bal != null ? Amounts.format(bal) : null,
            "swordTarget", stats.swordTarget != null ? Amounts.format(stats.swordTarget) : null,
            "zoneTarget", stats.zoneTarget != null ? Amounts.format(stats.zoneTarget) : null);
    }

    private void begin(MinecraftClient client, CombatController combat, long now, PendingCmd cmd) {
        pendingKind = cmd.kind();
        pending = cmd.text();
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
        phase = Phase.IDLE;
        pending = null;
        typoAt = -1;
    }

    private void abort(MinecraftClient client, String why) {
        if (logger != null) logger.log("upgrade_abort", "reason", why);
        closeOurChat(client);
        phase = Phase.IDLE;
        pending = null;
        typoAt = -1;
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

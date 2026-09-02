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
 * Economy-driven, buy-or-learn: on enable we type /bal then /swordmax once, which seeds
 * balance and the next-tier price. From then on we only type a buy when the scoreboard
 * snapshot covers that absolute target. Zone vs sword is a log-scaled TTK readiness
 * (fresh stage ~40s → 2s farmed-out), not a sword-count quota.
 */
public class UpgradeController {
    private enum Phase { IDLE, WAIT_STILL, PAUSE, OPEN, TYPE, SEND, READ }
    private enum Kind { SWORD, ZONE, PROBE }

    private record PendingCmd(String text, Kind kind, long notBefore) {}

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
    private boolean startupProbed = false;
    /** Kill-driven economy: one affordability evaluation per kill, after the sidebar settles. */
    private int lastKillCount = 0;
    private long evalAt = 0;
    public String lastKind = null;
    private boolean zoneProbedThisZone = false;
    private int lastZoneSeq = -1;

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
        Double rate = stats.moneyPerMinute();
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
            sb.append("  §e").append(phase.name().toLowerCase());
        } else if (kind != null) {
            Double remaining = "zone".equals(kind) ? stats.zoneTarget : stats.swordTarget;
            Double bal2 = stats.money();
            if (remaining != null && remaining > 0 && bal2 != null) {
                sb.append("  ").append(Math.min(999, Math.round(100.0 * bal2 / remaining))).append("%");
            }
        }
        return sb.toString();
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
        evalAt = 0;
        zoneProbedThisZone = false;
        lastZoneSeq = -1;
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
                    queue.add(new PendingCmd(cfg.balCommand, Kind.PROBE, 0));
                    queue.add(new PendingCmd(cfg.swordCommand, Kind.SWORD, 0));
                }
            }
            String reprobe = stats.consumeReprobe();
            if (reprobe != null) {
                // one learn-probe after a successful buy: the fail line teaches the next absolute price
                queue.add(new PendingCmd(
                    "zone".equals(reprobe) ? cfg.zoneCommand : cfg.swordCommand,
                    "zone".equals(reprobe) ? Kind.ZONE : Kind.SWORD,
                    now + HumanTiming.logNormalMs(1200, 3000)));
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
                zoneProbedThisZone = false;
                swordsSinceZone = 0;
            }
            // Kill-driven: one evaluation per kill, after the sidebar balance settles.
            if (combat.kills != lastKillCount) {
                lastKillCount = combat.kills;
                evalAt = now + HumanTiming.logNormalMs(cfg.postKillEvalDelayMinMs, cfg.postKillEvalDelayMaxMs);
            }
            if (now < evalAt) return false;
            if (lastSendAt != 0 && !stats.lastSendClassified() && now - lastSendAt < cfg.probeMinIntervalMs) {
                return false;
            }
            stats.publishSnapshot(true);
            String kind = buyKind();
            boolean probe = false;
            if (kind == null) {
                // learn-probe zone once this stage when TTK readiness is already high but we have no price
                double R = stats.zoneReadiness();
                if (!stats.zoneMaxed && stats.zoneTarget == null && R >= cfg.zoneMinReadiness
                    && !zoneProbedThisZone && now - lastSendAt >= cfg.probeMinIntervalMs) {
                    kind = "zone";
                    probe = true;
                    zoneProbedThisZone = true;
                } else {
                    return false;
                }
            }
            if (!combat.wantsUpgradeWindow && !combat.isStationary(client)) return false;
            Double remaining = "zone".equals(kind) ? stats.zoneTarget : stats.swordTarget;
            Double bal = stats.money();
            if (logger != null) {
                logger.log("upgrade_plan", "kind", kind,
                    "target", remaining != null ? Amounts.format(remaining) : null,
                    "bal", bal != null ? Amounts.format(bal) : null,
                    "pct", remaining != null && remaining > 0 && bal != null
                        ? Math.round(1000.0 * bal / remaining) / 10.0 : null,
                    "zoneReady", Math.round(1000.0 * stats.zoneReadiness()) / 10.0,
                    "incomePerMin", stats.moneyPerMinute() != null ? Amounts.format(stats.moneyPerMinute()) : null,
                    "ttkMs", stats.medianTtkMs(),
                    "probe", probe);
            }
            begin(client, combat, now, new PendingCmd(
                "zone".equals(kind) ? cfg.zoneCommand : cfg.swordCommand,
                "zone".equals(kind) ? Kind.ZONE : Kind.SWORD, 0));
            return true;
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

    /** Send this buy now, or null to wait. Only returns a kind that is known-affordable (or null). */
    private String buyKind() {
        return Economy.chooseBuyKind(
            !stats.swordMaxed, !stats.zoneMaxed,
            knownAffordable("sword"), knownAffordable("zone"),
            stats.zoneReadiness(), cfg.zoneMinReadiness);
    }

    private boolean knownAffordable(String kind) {
        Double need = "zone".equals(kind) ? stats.zoneTarget : stats.swordTarget;
        Double bal = stats.money();
        return need != null && bal != null && bal + 1e-6 >= need;
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

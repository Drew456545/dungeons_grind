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
 * balance and remaining cost. From then on the preferred command IS the probe — a fail
 * response parses to the exact remaining gap, and with a measured income rate the next
 * attempt is scheduled at now + gap/rate. Zone max becomes the priority buy once median
 * time-to-kill drops under {@code zoneReadyTtkMs} (fast kills = ready to move up).
 */
public class UpgradeController {
    private enum Phase { IDLE, WAIT_STILL, PAUSE, OPEN, TYPE, SEND, READ }
    private enum Kind { SWORD, ZONE, PROBE }

    private record PendingCmd(String text, Kind kind) {}

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
    private int zoneEvery;
    private long lastSendAt;
    private boolean startupProbed = false;
    /** Kill count when we first observed we could afford the next upgrade; -1 = not yet affordable. */
    private int affordableSinceKills = -1;
    public String lastKind = null;

    public UpgradeController(YCBotChallengeConfig cfg, StatsTracker stats) {
        this.cfg = cfg;
        this.stats = stats;
        rollZoneEvery();
    }

    public void setLogger(EventLogger logger) { this.logger = logger; }

    public boolean isBusy() { return phase != Phase.IDLE; }

    public String hudLine() {
        if (!cfg.upgradesEnabled) return null;
        long now = System.currentTimeMillis();
        String kind = chooseKind();
        Double bal = stats.money();
        Double rate = stats.moneyPerMinute();
        Double ttk = stats.medianTtkMs();
        StringBuilder sb = new StringBuilder("next ").append(kind == null ? "none (maxed)" : kind);
        if (bal != null) sb.append("  bal ").append(Amounts.format(bal));
        if (rate != null) sb.append("  +").append(Amounts.format(rate)).append("/min");
        if (ttk != null) {
            sb.append("  ttk ").append(String.format(Locale.ROOT, "%.1fs", ttk / 1000.0));
            Double base = stats.zoneBaselineTtkMs();
            if (base != null && base > 0) {
                sb.append(" §8(").append(String.format(Locale.ROOT, "%+.0f%%", 100.0 * (ttk - base) / base)).append(")§7");
            }
        }
        if (phase != Phase.IDLE) {
            sb.append("  §e").append(phase.name().toLowerCase());
        } else if (kind != null) {
            long dueIn = planNext(kind, now) - now;
            if (dueIn > 1_000) {
                sb.append("  buy in ").append(dueIn < 60_000 ? (dueIn / 1000) + "s" : (dueIn / 60_000) + "m");
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
        affordableSinceKills = -1;
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
                    queue.add(new PendingCmd(cfg.balCommand, Kind.PROBE));
                    queue.add(new PendingCmd(cfg.swordCommand, Kind.SWORD));
                }
            }
            PendingCmd head = queue.peek();
            if (head != null) {
                if (now - lastSendAt < cfg.probeMinIntervalMs) return false;
                if (!combat.wantsUpgradeWindow && !combat.isStationary(client)) return false;
                begin(client, combat, now, queue.poll());
                return true;
            }
            String kind = chooseKind();
            if (kind == null) return false;
            if (now < planNext(kind, now)) return false;
            if (!knownAffordable(kind) && now - lastSendAt < cfg.probeMinIntervalMs) return false;
            // Known-affordable: finish the current mob first, buy in the lull.
            if (knownAffordable(kind)) {
                if (affordableSinceKills < 0) affordableSinceKills = combat.kills;
                if (combat.kills - affordableSinceKills < cfg.minKillsAfterAffordable) return false;
            }
            if (!combat.wantsUpgradeWindow && !combat.isStationary(client)) return false;
            if (logger != null) {
                Double remaining = "zone".equals(kind) ? stats.zoneRemaining : stats.swordRemaining;
                logger.log("upgrade_plan", "kind", kind,
                    "remaining", remaining,
                    "bal", stats.money(),
                    "incomePerMin", stats.moneyPerMinute(),
                    "ttkMs", stats.medianTtkMs(),
                    "knownAffordable", knownAffordable(kind));
            }
            begin(client, combat, now, new PendingCmd(
                "zone".equals(kind) ? cfg.zoneCommand : cfg.swordCommand,
                "zone".equals(kind) ? Kind.ZONE : Kind.SWORD));
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

    /** The next kind to buy, or null when everything is maxed. */
    private String chooseKind() {
        boolean swordOpen = !stats.swordMaxed;
        boolean zoneOpen = !stats.zoneMaxed;
        if (!swordOpen && !zoneOpen) return null;
        Double ttk = stats.medianTtkMs();
        boolean zoneHot = zoneOpen && ttk != null && ttk <= cfg.zoneReadyTtkMs;
        boolean ratioDue = swordsSinceZone >= zoneEvery;
        if (zoneOpen && (zoneHot || ratioDue)) {
            // zone is the prize; if it's known-unaffordable but the sword is known-affordable, sword first
            if (stats.zoneRemaining != null) {
                Double bal = stats.money();
                if (bal != null && stats.zoneRemaining > bal && swordOpen && knownAffordable("sword")) {
                    return "sword";
                }
            }
            return "zone";
        }
        return swordOpen ? "sword" : "zone";
    }

    /** When the next attempt for {@code kind} should fire: now + remaining/income-rate, politeness-clamped. */
    private long planNext(String kind, long now) {
        long earliest = Math.max(now, lastSendAt + cfg.probeMinIntervalMs);
        Double remaining = "zone".equals(kind) ? stats.zoneRemaining : stats.swordRemaining;
        Double bal = stats.money();
        if (remaining == null) return earliest;              // unknown: probe-by-buying
        double gap = bal != null ? Math.max(0, remaining - bal) : remaining;
        if (gap <= 0) return earliest;                       // affordable: buy as soon as polite
        Double rate = stats.moneyPerMinute();
        if (rate == null || rate <= 0) return now + cfg.upgradePeriodMaxMs; // no income signal: slow poll
        long etaMs = (long) (gap / (rate / 60_000.0));
        return earliest + Math.max(0, Math.min((long) cfg.upgradePeriodMaxMs, etaMs));
    }

    private boolean knownAffordable(String kind) {
        Double need = "zone".equals(kind) ? stats.zoneRemaining : stats.swordRemaining;
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
            rollZoneEvery();
        }
        stats.becameAffordable = false;
        phase = Phase.IDLE;
        pending = null;
        typoAt = -1;
        affordableSinceKills = -1;
    }

    private void abort(MinecraftClient client, String why) {
        if (logger != null) logger.log("upgrade_abort", "reason", why);
        closeOurChat(client);
        phase = Phase.IDLE;
        pending = null;
        typoAt = -1;
        affordableSinceKills = -1;
    }

    private static void closeOurChat(MinecraftClient client) {
        if (client != null && client.currentScreen instanceof ChatScreen) {
            client.setScreen(null);
        }
    }

    private void rollZoneEvery() {
        zoneEvery = HumanTiming.ticks(cfg.zoneEverySwordsMin, cfg.zoneEverySwordsMax);
    }

    static boolean playerStill(MinecraftClient client) {
        if (client.player == null) return false;
        Vec3d v = client.player.getVelocity();
        double h = Math.sqrt(v.x * v.x + v.z * v.z);
        return h < 0.03 && client.player.isOnGround();
    }
}

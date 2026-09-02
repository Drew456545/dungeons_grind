package dev.drew.ycbotchallenge;

import dev.drew.ycbotchallenge.mixin.ChatScreenAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.util.math.Vec3d;

/**
 * Stationary, typed {@code /swordmax} / {@code /zone max}. Sword first; a zone
 * buy every 5–6 successful swords. Never fires while walking.
 */
public class UpgradeController {
    private enum Phase { IDLE, WAIT_STILL, PAUSE, OPEN, TYPE, SEND, READ }

    private final YCBotChallengeConfig cfg;
    private final StatsTracker stats;
    private EventLogger logger;

    private Phase phase = Phase.IDLE;
    private long nextDueAt;
    private long phaseUntil;
    private String pending;
    private String typed = "";
    private int typedChars;
    private boolean pendingIsSword;
    private int swordsSinceZone;
    private int zoneEvery;
    private long lastSendAt;
    /** Kill count when we first observed we could afford the next upgrade; -1 = not yet affordable. */
    private int affordableSinceKills = -1;
    public String lastKind = null;

    public UpgradeController(YCBotChallengeConfig cfg, StatsTracker stats) {
        this.cfg = cfg;
        this.stats = stats;
        rollNextDue(System.currentTimeMillis(), true);
        rollZoneEvery();
    }

    public void setLogger(EventLogger logger) { this.logger = logger; }

    public boolean isBusy() { return phase != Phase.IDLE; }

    public String hudLine() {
        if (!cfg.upgradesEnabled) return null;
        String next = nextKind();
        String rem = "sword";
        Double sr = stats.swordRemaining;
        Double zr = stats.zoneRemaining;
        Double bal = stats.money();
        StringBuilder sb = new StringBuilder("next ");
        sb.append(next);
        if (bal != null) sb.append("  bal ").append(Amounts.format(bal));
        if ("zone".equals(next) && zr != null) sb.append("  need ").append(Amounts.format(zr));
        else if (sr != null) sb.append("  need ").append(Amounts.format(sr));
        sb.append("  swords ").append(swordsSinceZone).append("/").append(zoneEvery);
        if (phase != Phase.IDLE) sb.append("  §e").append(phase.name().toLowerCase());
        return sb.toString();
    }

    public void reset(MinecraftClient client) {
        closeOurChat(client);
        phase = Phase.IDLE;
        pending = null;
        typed = "";
        typedChars = 0;
        affordableSinceKills = -1;
    }

    /**
     * @return true if combat should yield this tick (we're typing or holding still for a command).
     */
    public boolean tick(MinecraftClient client, CombatController combat) {
        if (!cfg.upgradesEnabled || client.player == null) return false;
        long now = System.currentTimeMillis();

        if (phase == Phase.IDLE) {
            if (!due(now)) return false;
            boolean known = knownAffordable();
            if (!canAffordPreferred()) {
                // Not affordable (or still unknown): reset the affordable-since marker and
                // retry on a shorter jitter once money might have moved.
                affordableSinceKills = -1;
                if (now - lastSendAt > 20_000) rollNextDue(now, false);
                return false;
            }
            // Affordable. Once we KNOW we can afford (cost + balance both parsed),
            // wait for at least one more kill before interrupting combat to upgrade —
            // feels like a player finishing a mob, then doing the buy in the lull.
            if (known) {
                if (affordableSinceKills < 0) affordableSinceKills = combat.kills;
                int killsSince = combat.kills - affordableSinceKills;
                if (killsSince < cfg.minKillsAfterAffordable) return false;
            }
            if (!combat.wantsUpgradeWindow && !combat.isStationary(client)) return false;
            begin(client, combat, now);
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
                phaseUntil = now + HumanTiming.logNormalMs(cfg.typeKeyMinMs, cfg.typeKeyMaxMs);
            }
            case TYPE -> {
                combat.releaseKeys(client);
                if (!(client.currentScreen instanceof ChatScreen cs)) {
                    abort(client, "chat-closed");
                    return false;
                }
                if (now < phaseUntil) return true;
                if (typedChars < pending.length()) {
                    typed += pending.charAt(typedChars);
                    typedChars++;
                    TextFieldWidget field = ((ChatScreenAccessor) cs).ycBotChallenge$getChatField();
                    if (field != null) field.setText(typed);
                    else cs.insertText(String.valueOf(pending.charAt(typedChars - 1)), false);
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
                    logger.log("upgrade_send", "command", pending, "kind", pendingIsSword ? "sword" : "zone",
                        "swordsSinceZone", swordsSinceZone);
                }
                cs.sendMessage(pending, true);
                lastSendAt = now;
                lastKind = pendingIsSword ? "sword" : "zone";
                stats.noteUpgradeSend(pendingIsSword);
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

    private void begin(MinecraftClient client, CombatController combat, long now) {
        pendingIsSword = !"zone".equals(nextKind());
        pending = pendingIsSword ? cfg.swordCommand : cfg.zoneCommand;
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
        if (pendingIsSword) {
            swordsSinceZone++;
            if (swordsSinceZone >= zoneEvery) {
                // next window prefers zone
            }
        } else {
            swordsSinceZone = 0;
            rollZoneEvery();
        }
        phase = Phase.IDLE;
        pending = null;
        affordableSinceKills = -1;
        rollNextDue(System.currentTimeMillis(), true);
    }

    private void abort(MinecraftClient client, String why) {
        if (logger != null) logger.log("upgrade_abort", "reason", why);
        closeOurChat(client);
        phase = Phase.IDLE;
        pending = null;
        affordableSinceKills = -1;
        rollNextDue(System.currentTimeMillis(), false);
    }

    private static void closeOurChat(MinecraftClient client) {
        if (client != null && client.currentScreen instanceof ChatScreen) {
            client.setScreen(null);
        }
    }

    private boolean due(long now) {
        if (now >= nextDueAt) return true;
        // Newly affordable after we learned a remaining cost.
        return stats.becameAffordable && now - lastSendAt > 8_000;
    }

    private boolean canAffordPreferred() {
        String kind = nextKind();
        Double need = "zone".equals(kind) ? stats.zoneRemaining : stats.swordRemaining;
        Double bal = stats.money();
        if (need == null || bal == null) return true; // unknown: try and learn
        return bal + 1e-6 >= need;
    }

    /** True only when we have actually parsed both the cost and the balance and can afford it. */
    private boolean knownAffordable() {
        String kind = nextKind();
        Double need = "zone".equals(kind) ? stats.zoneRemaining : stats.swordRemaining;
        Double bal = stats.money();
        return need != null && bal != null && bal + 1e-6 >= need;
    }

    public String nextKind() {
        if (swordsSinceZone >= zoneEvery) {
            Double bal = stats.money();
            if (bal != null && stats.zoneRemaining != null && bal + 1e-6 < stats.zoneRemaining) {
                return "sword";
            }
            return "zone";
        }
        return "sword";
    }

    private void rollNextDue(long now, boolean fullPeriod) {
        int min = fullPeriod ? cfg.upgradePeriodMinMs : Math.min(45_000, cfg.upgradePeriodMinMs);
        int max = fullPeriod ? cfg.upgradePeriodMaxMs : Math.max(min + 1, 75_000);
        nextDueAt = now + HumanTiming.logNormalMs(min, max);
        stats.becameAffordable = false;
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

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
 * {@code You need $29.99T Money to Rebirth.} Buys use the same post-kill human
 * window as the other upgrades; if rebirth is covered, sword/zone are skipped.
 * Sword vs zone is a 1.25× price ratio, not TTK.
 */
public class UpgradeController {
    private enum Phase { IDLE, WAIT_STILL, PAUSE, OPEN, TYPE, SEND, READ, SETTLE, GUI_WAIT, GUI_LOOK, GUI_CLICK, GUI_ESC }
    private enum Kind { SWORD, ZONE, REBIRTH }

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
    private boolean pendingFollowUp = false;
    private final ArrayDeque<PendingCmd> queue = new ArrayDeque<>();
    private int swordsSinceZone;
    private long lastSendAt;
    private long lastSwordSendAt;
    private long lastZoneSendAt;
    private long lastRebirthSendAt;
    private boolean startupProbed = false;
    private int lastKillCount = 0;
    private long evalAt = Long.MAX_VALUE;
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
        StringBuilder sb = new StringBuilder("next ").append(kind == null ? "none" : kind);
        if (bal != null) sb.append("  bal ").append(Amounts.format(bal));
        if (rate != null) sb.append("  +").append(Amounts.format(rate)).append("/min");
        if (stats.rebirthTarget != null) {
            sb.append("  rb ").append(Amounts.format(stats.rebirthTarget));
        }
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
            int cap = "rebirth".equals(kind) ? 0 : Math.max(0, cfg.upgradeMinIntervalMs);
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
                if (!stats.seeded("rebirth")) {
                    queue.add(new PendingCmd(cfg.rebirthCommand, Kind.REBIRTH,
                        now + HumanTiming.logNormalMs(800, 2000), false));
                }
            }
            if (rebirthAffordable()) {
                dropNonRebirthQueue();
                if (decision != null && !"rebirth".equals(decision)) {
                    decision = "rebirth";
                    logPlan("rebirth");
                }
            }
            PendingCmd head = queue.peek();
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
                int cap = "rebirth".equals(kind) ? 0 : cfg.upgradeMinIntervalMs;
                if (!Economy.cooldownElapsed(now, lastSendFor(kind), cap, false)) return false;
                if (!combat.wantsUpgradeWindow && !combat.isStationary(client)) {
                    return false;
                }
                decision = null;
                begin(client, combat, now, new PendingCmd(commandOf(kind), kindOf(kind), 0, false));
                return true;
            }
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
            int cap = "rebirth".equals(kind) ? 0 : cfg.upgradeMinIntervalMs;
            if (!Economy.cooldownElapsed(now, lastSendFor(kind), cap, false)) {
                skipEval("cooldown", kind);
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
                    phase = Phase.OPEN;
                    client.setScreen(new ChatScreen("", false));
                    phaseUntil = now + 80;
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
                        "followUp", pendingFollowUp,
                        "swordsSinceZone", swordsSinceZone);
                }
                cs.sendMessage(pending, true);
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
                String kind = kindName();
                if (stats.failSince(kind, lastSendAt)) {
                    finish();
                    return false;
                }
                boolean maxed = pendingKind == Kind.ZONE ? stats.zoneMaxed : stats.swordMaxed;
                if (!stats.lastSendSucceeded && !maxed) stats.onUpgradeSuccess(kind, now);
                if (!maxed) queueFollowUp(now);
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
                        queueRebirthSeed(now);
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
            queueRebirthSeed(now);
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

    private void queueFollowUp(long now) {
        if (rebirthAffordable()) return;
        long wait = pendingKind == Kind.ZONE
            ? HumanTiming.logNormalMs(800, 2000)
            : HumanTiming.logNormalMs(400, 1200);
        wait = Math.max(wait, Math.max(400, cfg.commandCooldownMs));
        queue.add(new PendingCmd(pending, pendingKind, now + wait, true));
    }

    private void dropNonRebirthQueue() {
        queue.removeIf(c -> c.kind() != Kind.REBIRTH);
    }

    private void queueRebirthSeed(long now) {
        long wait = HumanTiming.logNormalMs(1500, 4000);
        wait = Math.max(wait, Math.max(cfg.commandCooldownMs, cfg.expectedTeleportAfterRebirthMs / 4));
        queue.add(new PendingCmd(cfg.rebirthCommand, Kind.REBIRTH, now + wait, true));
    }

    private void logPlan(String kind) {
        if (logger == null) return;
        Double price = targetOf(kind);
        Double bal = stats.money();
        logger.log("upgrade_plan", "kind", kind,
            "target", price != null ? Amounts.format(price) : null,
            "bal", bal != null ? Amounts.format(bal) : null,
            "pct", price != null && price > 0 && bal != null
                ? Math.round(1000.0 * bal / price) / 10.0 : null,
            "incomePerMin", stats.incomePerMinute() != null ? Amounts.format(stats.incomePerMinute()) : null,
            "swordTarget", stats.swordTarget != null ? Amounts.format(stats.swordTarget) : null,
            "zoneTarget", stats.zoneTarget != null ? Amounts.format(stats.zoneTarget) : null);
    }

    private String hudKind() {
        if (stats.rebirthTarget != null) return "rebirth";
        return Economy.preferredKind(!stats.swordMaxed, !stats.zoneMaxed,
            stats.swordTarget, stats.zoneTarget, cfg.zoneOverSwordRatio);
    }

    private String decideKind(int kills) {
        if (rebirthAffordable()) return "rebirth";
        String buy = Economy.chooseBuyKind(
            !stats.swordMaxed, !stats.zoneMaxed,
            knownAffordable("sword"), knownAffordable("zone"),
            stats.swordTarget, stats.zoneTarget, cfg.zoneOverSwordRatio);
        if (buy != null) {
            return extraKillsOk(buy, kills) ? buy : null;
        }
        String pref = Economy.preferredKind(!stats.swordMaxed, !stats.zoneMaxed,
            stats.swordTarget, stats.zoneTarget, cfg.zoneOverSwordRatio);
        String explore = exploreKind(pref);
        if (explore == null && pref != null) explore = exploreKind("zone".equals(pref) ? "sword" : "zone");
        return explore;
    }

    private String exploreKind(String kind) {
        if (kind == null || "rebirth".equals(kind)) return null;
        boolean zone = "zone".equals(kind);
        if (zone ? stats.zoneMaxed : stats.swordMaxed) return null;
        Double price = zone ? stats.zoneTarget : stats.swordTarget;
        if (price != null) return null;
        if (!stats.seeded(kind)) return kind;
        if (stats.exploratorySent(kind)) return null;
        if (Economy.retryUnknownAllowed(stats.lastPrice(kind), stats.money(), cfg.retryPriceGrowthPct)) return kind;
        return null;
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
            "target", remaining != null ? Amounts.format(remaining) : null,
            "bal", bal != null ? Amounts.format(bal) : null,
            "swordTarget", stats.swordTarget != null ? Amounts.format(stats.swordTarget) : null,
            "zoneTarget", stats.zoneTarget != null ? Amounts.format(stats.zoneTarget) : null,
            "rebirthTarget", stats.rebirthTarget != null ? Amounts.format(stats.rebirthTarget) : null);
    }

    private void begin(MinecraftClient client, CombatController combat, long now, PendingCmd cmd) {
        pendingKind = cmd.kind();
        pending = cmd.text();
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
        phase = Phase.IDLE;
        pending = null;
        pendingFollowUp = false;
        typoAt = -1;
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

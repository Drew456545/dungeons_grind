package dev.drew.ycbotchallenge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * The grind loop, matched to the server's mechanics: one hit TAGS a mob and
 * the server auto-attacks it until it dies — one mob at a time. Per cycle:
 * pick nearest mob -> walk into reach -> click until the boss bar confirms a
 * connect (5-8 cps, misses are OK) -> stop spamming, let it cook -> as it
 * nears death (ETA from boss-bar HP / DPS), pre-aim/walk to the next -> on
 * death, the handoff is already done.
 */
public class CombatController {
    private static final Pattern NAMEPLATE = Pattern.compile(
        "^\\[(?<rarity>[^\\]]+)\\]\\s*(?:\\[?(?:Level|Lvl?\\.?)\\s*(?<level>\\d+)\\]?)?\\s*(?<mob>.+?)(?:\\s*[♥❤].*)?$",
        Pattern.CASE_INSENSITIVE);

    private final YCBotChallengeConfig cfg;
    private final StatsTracker stats;
    private EventLogger logger;

    private LivingEntity target = null;
    /** Connected = a click landed (boss bar for this mob appeared). The server now cooks it. */
    private boolean connected = false;
    private long tagAt = 0;
    private long targetPickedAt = 0;
    /** While the current mob cooks: the mob we'll go for next (pre-aimed so the handoff is instant). */
    private LivingEntity nextTarget = null;
    private long nextPickedAt = 0;
    /** How far we may drift from the cooking mob this kill (rolled per connect). */
    private double cookLeash = 3.0;
    public String nextTargetDesc = null;
    private long nextActionAt = 0;   // humanized reaction / idle gate
    private long breakUntil = 0;     // break scheduler: inert while now < breakUntil
    private long nextFocusEndAt = 0;
    private int lastZoneSeq = 0;
    private long lastClickAt = 0;
    /** Set to request the client stop the bot (teleport / player radar); consumed by the main tick. */
    public String stopRequest = null;
    private Vec3d lastTickPos = null;
    private long lastRadarAt = 0;
    /** Once a teleport happens, the player radar arms for the rest of the session. */
    private boolean teleportSeen = false;
    /** Radar memory: entity id -> {x, z, lastMoveMs, firstSeenMs, lastSeenMs}. */
    private final Map<Integer, double[]> radarMotion = new HashMap<>();
    private int clicksThisTarget = 0;
    public int kills = 0;
    public String lastTargetDesc = null;
    public String dominantDesc = null;
    private EntityType<?> dominantType = null;
    private int dominantCount = 0;

    /** Upgrade controller may claim the post-kill stillness window. */
    public boolean wantsUpgradeWindow = true;

    /** Live DPS / ETA read from the boss bar (null until we have samples). */
    public Double currentHp = null;
    public Double currentDps = null;
    public Double currentEtaMs = null;

    private float approachYawOffset = 0f;
    /** Signed yaw error to the movement target (deg, + = target to the right). */
    private float lastYawErrSigned = 0f;
    private int prevOct = 0;
    private int octStaggerTicks = 0;
    private int pendingOct = 0;
    private int sprintTapTicks = 0;

    private enum TrackStyle { FLICK_NEXT, WATCH, SCAN, HESITATE }
    private TrackStyle trackStyle = TrackStyle.WATCH;
    private float aimHeightFrac = 0.58f;
    private int lookEntityId = Integer.MIN_VALUE;
    private boolean lookIssued = false;
    private int scanStep = 0;
    private int scanCount = 0;
    private float[] scanYaw;
    private float[] scanPitch;
    private long hesitateUntil = 0;

    // ghost filter: per-entity motion tracking
    private static final class Motion {
        Vec3d lastPos;
        double moved = 0;
        int ticks = 0;
        long lastSeen = 0;
        long stillSince = 0; // for ghost redemption: when it last stopped moving
    }
    private final Map<Integer, Motion> motion = new HashMap<>();
    private final java.util.Set<Integer> ghosts = new java.util.HashSet<>();
    public int ghostsIgnored = 0;
    private String targetRarity = null;
    private Integer targetLevel = null;
    private String targetMob = null;

    public CombatController(YCBotChallengeConfig cfg, StatsTracker stats) {
        this.cfg = cfg;
        this.stats = stats;
    }

    public void setLogger(EventLogger logger) { this.logger = logger; }

    public String stateDescription() {
        if (target == null) return "searching";
        String phase = connected ? "cooking " : (clicksThisTarget > 0 ? "clicking " : "approaching ");
        String s = phase + (lastTargetDesc == null ? "?" : lastTargetDesc);
        if (connected && nextTargetDesc != null) s += "  §8→ next: " + nextTargetDesc;
        if (connected) s += "  §8" + trackStyle.name().toLowerCase().replace('_', '-');
        return s;
    }

    public boolean isStationary(MinecraftClient client) {
        if (client.player == null) return false;
        return UpgradeController.playerStill(client);
    }

    public boolean isOnBreak() {
        return System.currentTimeMillis() < breakUntil;
    }

    /** Match any whitelist entry against the full name or any single line of it (NPCs use multi-line plates). */
    private boolean radarWhitelisted(String name) {
        // SidebarParser.strip normalizes unicode small caps (NPC plates on this
        // server render as small caps) in addition to § codes.
        String clean = SidebarParser.strip(name);
        for (String w : cfg.playerRadarWhitelist) {
            if (w == null) continue;
            if (w.equalsIgnoreCase(clean)) return true;
            for (String line : clean.split("\\n")) {
                if (w.equalsIgnoreCase(line.trim())) return true;
            }
        }
        return false;
    }

    /** Spawn NPCs / AFKers: ignore players who haven't moved within the configured window. */
    private boolean radarIgnoredAsStationary(net.minecraft.entity.player.PlayerEntity p, long now, double[] rec) {
        if (cfg.playerRadarIgnoreStationaryMs <= 0) return false;
        Vec3d pos = p.getEntityPos();
        if (Double.isNaN(rec[0]) || Math.hypot(pos.x - rec[0], pos.z - rec[1]) > 0.5) {
            rec[0] = pos.x;
            rec[1] = pos.z;
            rec[2] = now;
            return false; // just moved (or just seen) — counts as active
        }
        return now - (long) rec[2] >= cfg.playerRadarIgnoreStationaryMs;
    }

    private long focusMs() {
        return HumanTiming.logNormalMs(
            cfg.focusMinutesMin * 60_000, Math.max(cfg.focusMinutesMin * 60_000 + 1, cfg.focusMinutesMax * 60_000));
    }

    /** movingTargetPolicy "sometimes": a human would swing at the twitching mob anyway. */
    private boolean mayAttackMoving() {
        return cfg.ninja && "sometimes".equalsIgnoreCase(cfg.movingTargetPolicy)
            && ThreadLocalRandom.current().nextDouble() < cfg.movingTargetAttackChance;
    }

    public void reset(MinecraftClient client) {
        target = null;
        nextTarget = null;
        connected = false;
        clicksThisTarget = 0;
        prevOct = 0;
        octStaggerTicks = 0;
        lookIssued = false;
        wantsUpgradeWindow = false;
        currentHp = null;
        currentDps = null;
        currentEtaMs = null;
        ghosts.clear();
        motion.clear();
        radarMotion.clear();
        lastTickPos = null;
        MouseDriver.INSTANCE.cancel();
        releaseKeys(client);
    }

    /** Zone switched: full targeting reset plus the dominant-type cache. */
    private void retargetForZone(MinecraftClient client) {
        reset(client);
        dominantType = null;
        dominantCount = 0;
        dominantDesc = null;
        if (logger != null) logger.log("zone_retarget", "zone", stats.zone);
    }

    public void releaseKeys(MinecraftClient client) {
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
        // never hold the attack key; we fire discrete presses via timesPressed
        client.options.attackKey.setPressed(false);
    }

    public void tick(MinecraftClient client) {
        if (client.player == null || client.world == null || client.interactionManager == null) return;
        long now = System.currentTimeMillis();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // Zone switched (e.g. /zone max succeeded): new mobs spawn, so all targeting
        // state and the ghost blacklist from the old zone are meaningless.
        int zseq = stats.zoneChangeSeq();
        if (zseq != lastZoneSeq) {
            lastZoneSeq = zseq;
            retargetForZone(client);
        }

        // Stop protocol: teleport = pulled for a check; another player in range = staff
        // spectating (this gamemode is solo while grinding, so anyone else is a red flag).
        if (cfg.stopProtocolEnabled) {
            Vec3d pos = client.player.getEntityPos();
            if (lastTickPos != null) {
                double jumped = pos.distanceTo(lastTickPos);
                if (jumped > cfg.teleportThresholdBlocks) {
                    if (stats.isTeleportExpected(now)) {
                        // Our own /zone max advance — not a staff pull, and it must NOT
                        // arm the radar (zones legitimately contain stationary NPCs).
                        stats.clearTeleportExpected();
                        if (logger != null) logger.log("zone_teleport", "blocks", Math.round(jumped));
                        lastTickPos = null;
                        releaseKeys(client);
                        return;
                    }
                    teleportSeen = true; // unexpected teleport: arms the player radar for the rest of the session
                    stopRequest = "teleport (" + Math.round(jumped) + " blocks)";
                    lastTickPos = null;
                    releaseKeys(client);
                    return;
                }
            }
            lastTickPos = pos;
            if ((!cfg.playerRadarArmAfterTeleport || teleportSeen) && now - lastRadarAt >= 200) {
                lastRadarAt = now;
                java.util.Set<Integer> seenNow = new java.util.HashSet<>();
                for (var p : client.world.getPlayers()) {
                    if (p == client.player) continue;
                    if (client.player.distanceTo(p) > cfg.playerRadarRadius) continue;
                    String name = p.getName().getString();
                    if (radarWhitelisted(name)) continue;
                    seenNow.add(p.getId());
                    double[] rec = radarMotion.get(p.getId());
                    if (rec == null) {
                        rec = new double[]{Double.NaN, Double.NaN, 0, now, now};
                        radarMotion.put(p.getId(), rec);
                        if (logger != null) logger.log("radar_seen", "name", name);
                    }
                    rec[4] = now;
                    if (radarIgnoredAsStationary(p, now, rec)) continue;
                    long present = now - (long) rec[3];
                    if (present >= cfg.playerRadarDwellMs) {
                        stopRequest = "player nearby for " + (present / 1000) + "s: " + name;
                        releaseKeys(client);
                        return;
                    }
                }
                // left range or despawned = dwell resets
                radarMotion.keySet().removeIf(id -> !seenNow.contains(id));
            }
        }

        updateMotion(client);

        // current target started moving -> it's a ghost; drop it and rescan next tick
        if (target != null && cfg.stationaryOnly && ghosts.contains(target.getId())) {
            if (logger != null) {
                logger.log("target_abandoned", "reason", "moving-ghost", "mob", targetMob, "rarity", targetRarity);
            }
            target = null;
            connected = false;
            clicksThisTarget = 0;
            lookIssued = false;
        }

        // break scheduler: human-length breaks between focus blocks
        if (cfg.ninja && cfg.breaksEnabled) {
            if (now < breakUntil) { releaseKeys(client); return; }
            if (nextFocusEndAt == 0) nextFocusEndAt = now + focusMs();
            if (now >= nextFocusEndAt) {
                breakUntil = now + HumanTiming.logNormalMs(
                    cfg.breakMinutesMin * 60_000, Math.max(cfg.breakMinutesMin * 60_000 + 1, cfg.breakMinutesMax * 60_000));
                nextFocusEndAt = breakUntil + focusMs();
                if (logger != null) logger.log("break_start", "durationMs", breakUntil - now);
                releaseKeys(client);
                return;
            }
        }

        // occasional human-ish idle
        if (now < nextActionAt) { releaseKeys(client); return; }
        if (rng.nextDouble() < cfg.idleChancePerMinute / (60.0 * 20.0)) { // per tick
            nextActionAt = now + rng.nextLong(cfg.idleMinMs, cfg.idleMaxMs + 1);
            releaseKeys(client);
            return;
        }

        // rare long distraction — the heavy tail idleChance can't produce
        if (cfg.ninja && rng.nextDouble() < cfg.distractionChancePerMinute / (60.0 * 20.0)) {
            nextActionAt = now + HumanTiming.logNormalMs(cfg.distractionMinMs, cfg.distractionMaxMs);
            if (logger != null) logger.log("distracted", "pauseMs", nextActionAt - now);
            releaseKeys(client);
            return;
        }

        // Authoritative kill signal: the cooking mob's boss bar vanished (server-side death),
        // even when the client keeps a ghost entity around. TTK measures connect -> bar gone.
        // A quick vanish with a still-living entity = the tag didn't stick (retag below).
        if (target != null && connected && !stats.bossBarMatches(targetMob)) {
            boolean entityGone = target.isRemoved() || target.isDead() || !target.isAlive();
            long cookMs = now - tagAt;
            if (entityGone || cookMs >= cfg.barVanishMinCookMs) {
                kills++;
                stats.recordKill();
                stats.recordKillDuration(cookMs, targetRarity);
                wantsUpgradeWindow = true;
                if (logger != null) {
                    logger.log("kill",
                        "mob", targetMob, "rarity", targetRarity, "level", targetLevel,
                        "timeToKillMs", cookMs, "kills", kills,
                        "via", entityGone ? "death+bar" : "bossbar-gone",
                        "clicks", clicksThisTarget);
                }
                target = null;
                connected = false;
                clicksThisTarget = 0;
                lookIssued = false;
                nextTarget = null;
                nextTargetDesc = null;
                nextActionAt = now + HumanTiming.logNormalMs(cfg.reactionDelayMinMs, cfg.reactionDelayMaxMs);
                return;
            }
        }

        // current target dead? -> kill credit (only if we actually connected)
        if (target != null && (target.isRemoved() || target.isDead() || !target.isAlive())) {
            if (connected) {
                kills++;
                stats.recordKill();
                stats.recordKillDuration(now - tagAt, targetRarity);
                wantsUpgradeWindow = true;
                if (logger != null) {
                    logger.log("kill",
                        "mob", targetMob, "rarity", targetRarity, "level", targetLevel,
                        "timeToKillMs", now - tagAt, "kills", kills, "via", "death",
                        "clicks", clicksThisTarget);
                }
            }
            target = null;
            connected = false;
            clicksThisTarget = 0;
            lookIssued = false;
            nextTarget = null;
            nextTargetDesc = null;
            nextActionAt = now + HumanTiming.logNormalMs(cfg.reactionDelayMinMs, cfg.reactionDelayMaxMs);
            return;
        }

        // connected mob that never dies = client-side ghost or unkillable — abandon it
        if (target != null && connected && now - tagAt > cfg.maxCookMs) {
            if (logger != null) {
                logger.log("target_abandoned", "reason", "cook-timeout",
                    "mob", targetMob, "rarity", targetRarity, "afterMs", now - tagAt);
            }
            target = null;
            connected = false;
            clicksThisTarget = 0;
            lookIssued = false;
        }

        // stale target we never managed to connect on
        if (target != null && !connected && now - targetPickedAt > 12_000) {
            target = null;
            clicksThisTarget = 0;
            lookIssued = false;
        }

        // stage changed under us: current (unconnected) target is no longer the dominant mob type
        if (target != null && !connected && cfg.targetDominant && dominantType != null
            && target.getType() != dominantType && dominantCount >= cfg.minDominantPack) {
            target = null;
            clicksThisTarget = 0;
            lookIssued = false;
        }

        if (target == null) {
            if (nextTarget != null && validMob(client, nextTarget)) {
                target = nextTarget;
            } else {
                target = pickTarget(client, null);
                if (target == null) { nextTarget = null; releaseKeys(client); return; }
            }
            nextTarget = null;
            nextTargetDesc = null;
            targetPickedAt = now;
            clicksThisTarget = 0;
            rollAimPoint(client);
            lookIssued = false;
            readNameplate(target);
            maybeLook(client, target, "approach");
            if (logger != null) {
                logger.log("tag_intent", "mob", targetMob, "rarity", targetRarity, "level", targetLevel);
            }
        }

        lastYawErrSigned = MouseDriver.signedYawError(client, connected && nextTarget != null ? nextTarget : target);

        // Re-tag safety: if a connect was recorded but the boss bar has since
        // vanished (tag didn't stick / ghost), drop it and resume clicking.
        if (connected && target != null && !stats.bossBarMatches(targetMob)) {
            if (logger != null) logger.log("retag", "reason", "bossbar-vanished", "mob", targetMob);
            connected = false;
            clicksThisTarget = 0;
            lookIssued = false;
        }

        if (connected) {
            tickCook(client, now);
            return;
        }

        // --- not connected: approach + click until the boss bar confirms a hit ---

        // Connect detection: a click has landed once the mob's boss bar is showing.
        if (clicksThisTarget >= 1 && stats.bossBarMatches(targetMob)) {
            connected = true;
            tagAt = now;
            cookLeash = rng.nextDouble(cfg.cookLeashMinBlocks, Math.max(cfg.cookLeashMinBlocks + 0.01, cfg.cookLeashMaxBlocks));
            rollTrackStyle(client);
            lookIssued = false;
            stats.resetDps();
            if (logger != null) {
                logger.log("tag", "mob", targetMob, "rarity", targetRarity, "level", targetLevel,
                    "trackStyle", trackStyle.name(), "clicks", clicksThisTarget, "via", "connect");
            }
            return;
        }

        maybeLook(client, target, "approach");
        reacquireIfNeeded(client, target, "approach-correct");

        double dist = client.player.distanceTo(target);

        // Anticipatory swing spam on the way in: 2-3 cps with a wandering vigor.
        // Mostly whiffs at air — that's exactly what a player closing in looks like.
        if (cfg.approachClickCpsMax > 0 && dist <= cfg.approachClickMaxDist
            && now - lastClickAt >= approachIntervalMs()) {
            pressAttack(client);
            lastClickAt = now;
            clicksThisTarget++; // a whiff counts; a lucky early land is how connects happen
        }

        if (dist > cfg.reach) {
            if (cfg.movement) moveToward(client, dist, true);
            return;
        }

        releaseKeys(client);
        // Arrived while sprinting: drop the sprint *key* and wait a tick so the
        // hit isn't a knockback sprint-hit (shoves the mob, trips ghost filter).
        // Ninja: with small probability we skip the discipline and sprint-hit anyway.
        if (client.player.isSprinting() || sprintTapTicks > 0) {
            if (cfg.ninja && sprintTapTicks <= 0 && rng.nextDouble() < cfg.sprintHitChance) {
                if (logger != null) logger.log("sprint_hit_slip");
            } else {
                tapSprint(client, false);
                return;
            }
        }

        // Tight final servo: reacquireIfNeeded (threshold = lookReacquireDeg)
        // already re-flicks when the camera drifts off the hitbox. Here we only
        // fire a click once the actual camera is within tolerance and the vanilla
        // attack cooldown is ready. Missing is realistic and expected — we keep
        // clicking at 5-8 cps until one connects (boss bar appears, handled above).
        double aimErr = MouseDriver.aimErrorDeg(client, target, aimHeightFrac);
        double tapTol = cfg.aimTapMaxErrorDeg;
        if (cfg.ninja && aimErr > tapTol && rng.nextDouble() < cfg.misclickChance) {
            tapTol *= 2.5; // sloppy click — mostly still misses, which is the point
            if (logger != null) logger.log("misclick", "aimErr", Math.round(aimErr * 10.0) / 10.0);
        }
        if (aimErr <= tapTol
            && !MouseDriver.INSTANCE.isBusy()
            && now - lastClickAt >= clickIntervalMs()
            && vanillaAttackReady(client)) {
            pressAttack(client);
            lastClickAt = now;
            clicksThisTarget++;
            if (logger != null && clicksThisTarget == 1) {
                logger.log("click_start", "mob", targetMob, "aimErr", Math.round(aimErr * 10.0) / 10.0);
            }
        }
    }

    private void tickCook(MinecraftClient client, long now) {
        // Refresh the DPS estimate from the boss bar every tick (cheap).
        currentHp = stats.currentHpFor(targetMob);
        stats.sampleDpsFor(targetMob);
        currentDps = stats.dps();
        if (currentHp != null && currentDps != null && currentDps > 0) {
            currentEtaMs = currentHp / currentDps * 1000.0;
        } else {
            currentEtaMs = null;
        }

        boolean handoffDue = currentEtaMs != null && currentEtaMs <= cfg.handoffLeadMs;
        // Fallback: if we've been cooking a while with no DPS signal at all, start
        // looking for the next mob anyway so we never stall on a boss-bar-less mob.
        boolean fallbackDue = currentDps == null && (now - tagAt) > cfg.handoffFallbackMs;

        // Stay in range of the cooking mob; camera is a one-shot intent, not a lock.
        // Only acquire/pre-aim the next target once the handoff is due (or fallback).
        if (handoffDue || fallbackDue) {
            if (nextTarget == null || !validMob(client, nextTarget) || now - nextPickedAt > cfg.nextTargetRescanMs) {
                LivingEntity n = pickTarget(client, target);
                if (n != nextTarget) {
                    nextTarget = n;
                    nextTargetDesc = n != null ? describe(n) : null;
                    if (logger != null && n != null) {
                        logger.log("next_picked", "mob", describe(n),
                            "etaMs", currentEtaMs, "via", handoffDue ? "eta" : "fallback");
                    }
                }
                nextPickedAt = now;
            }
        }

        switch (trackStyle) {
            case FLICK_NEXT -> {
                if (cfg.preAimNext && nextTarget != null) maybeLook(client, nextTarget, "flick-next");
            }
            case WATCH -> { /* leave the camera; idle tremor only */ }
            case HESITATE -> {
                if (now >= hesitateUntil && nextTarget != null) maybeLook(client, nextTarget, "hesitate");
            }
            case SCAN -> tickScan(client);
        }

        if (nextTarget == null) { releaseKeys(client); return; }
        double leash = client.player.distanceTo(target);
        double toNext = client.player.distanceTo(nextTarget);
        boolean roomOnLeash = leash + coastDistance(client) < cookLeash - 0.25;
        boolean throughTarget = leash < toNext
            && Math.abs(MathHelper.wrapDegrees(bearingTo(client, nextTarget) - bearingTo(client, target))) < 35f;
        lastYawErrSigned = MouseDriver.signedYawError(client, nextTarget);
        // Only pre-walk once the handoff is due, so we don't drift off a mob that's
        // still far from dying.
        if ((handoffDue || fallbackDue) && cfg.movement && toNext > cfg.reach && roomOnLeash && !throughTarget) {
            moveToward(client, toNext, false);
        } else {
            releaseKeys(client);
        }
    }

    private void tickScan(MinecraftClient client) {
        if (MouseDriver.INSTANCE.isBusy()) return;
        if (scanStep < scanCount && scanYaw != null) {
            MouseDriver.INSTANCE.lookTo(client, scanYaw[scanStep], scanPitch[scanStep], "scan");
            scanStep++;
            return;
        }
        if (nextTarget != null) maybeLook(client, nextTarget, "scan-settle");
    }

    /**
     * 8-way approach. The signed bearing to the target (relative to the camera)
     * is rounded to the nearest 45 deg octant and mapped onto the W/A/S/D combo
     * that travels in that direction:
     *   0 = W, ±1 = W+D / W+A, ±2 = D / A, ±3 = S+D / S+A, 4 = S.
     * So we always move along the heading nearest the mob while the momentum
     * camera catches up — a 15 deg error runs straight (not 45 deg wide), a mob
     * behind us gets back-pedalled toward, and there is never a stand-and-turn.
     */
    /** Movement-speed multiplier vs. an unbuffed player (Speed X etc.): 1.0 normally, ~3.0 at Speed 10. */
    private static double speedFactor(MinecraftClient client) {
        double v = client.player.getAttributeValue(EntityAttributes.MOVEMENT_SPEED);
        return MathHelper.clamp(v / 0.1, 0.5, 10.0);
    }

    /** Blocks we'd slide if we let go of the keys right now (horizontal speed x coastFactor). */
    private double coastDistance(MinecraftClient client) {
        Vec3d v = client.player.getVelocity();
        return Math.sqrt(v.x * v.x + v.z * v.z) * cfg.coastFactor;
    }

    private static float bearingTo(MinecraftClient client, Entity e) {
        Vec3d rel = e.getEntityPos().subtract(client.player.getEntityPos());
        return (float) (Math.toDegrees(Math.atan2(rel.z, rel.x)) - 90.0);
    }

    private void moveToward(MinecraftClient client, double dist, boolean allowSprint) {
        // Coast in: let go once we'd slide into reach anyway. If we come up a
        // little short the next tick just nudges W again — a normal-looking step.
        if (dist - cfg.reach <= coastDistance(client)) { releaseKeys(client); return; }
        float err = lastYawErrSigned;
        int oct = Math.round(err / 45f);
        if (oct == -4) oct = 4;
        if (oct != prevOct
            && Math.abs(MathHelper.wrapDegrees(err - prevOct * 45f)) < 22.5f + cfg.moveHysteresisDeg) {
            oct = prevOct;
        }
        if (oct != prevOct) {
            pendingOct = oct;
            if (octStaggerTicks <= 0) octStaggerTicks = HumanTiming.ticks(1, 2);
        }
        if (octStaggerTicks > 0) {
            octStaggerTicks--;
            oct = prevOct;
            if (octStaggerTicks == 0) prevOct = pendingOct;
        } else {
            prevOct = oct;
        }

        int a = Math.abs(oct);
        boolean forward = a <= 1;
        boolean back = a >= 3;
        boolean right = oct > 0 && a < 4;
        boolean left = oct < 0 && a < 4;
        client.options.forwardKey.setPressed(forward);
        client.options.backKey.setPressed(back);
        client.options.leftKey.setPressed(left);
        client.options.rightKey.setPressed(right);

        boolean aligned = Math.abs(err) < cfg.sprintAlignMaxDeg;
        double toGo = dist - cfg.reach;
        boolean wantSprint = allowSprint && cfg.sprint && forward && aligned && toGo > cfg.sprintMinDistance;
        tapSprint(client, wantSprint);
        boolean hopping = allowSprint && cfg.sprintJump && client.player.isSprinting() && aligned && toGo > cfg.sprintJumpMinDistance;
        client.options.jumpKey.setPressed(client.player.horizontalCollision || hopping);
    }

    private void tapSprint(MinecraftClient client, boolean wantSprint) {
        boolean toggled = false;
        try {
            toggled = client.options.getSprintToggled().getValue();
        } catch (Throwable ignored) {}
        if (!toggled) {
            client.options.sprintKey.setPressed(wantSprint && canStartSprint(client));
            return;
        }
        // Sprint: Toggle — tap the key once to change state, don't hold it.
        if (sprintTapTicks > 0) {
            sprintTapTicks--;
            client.options.sprintKey.setPressed(true);
            return;
        }
        boolean running = client.player.isSprinting();
        if (wantSprint && !running && canStartSprint(client)) sprintTapTicks = 1;
        else if (!wantSprint && running) sprintTapTicks = 1;
        client.options.sprintKey.setPressed(sprintTapTicks > 0);
    }

    /**
     * Fire one real vanilla attack key-press by incrementing {@code timesPressed}.
     * Vanilla's own {@code handleInputEvents} -> {@code doAttack()} consumes it on
     * the next tick (ray-trace + swing + cooldown). We never hold the key.
     */
    private void pressAttack(MinecraftClient client) {
        var attack = client.options.attackKey;
        int cur = ((dev.drew.ycbotchallenge.mixin.KeyBindingAccessor) attack).ycbotchallenge$getTimesPressed();
        ((dev.drew.ycbotchallenge.mixin.KeyBindingAccessor) attack).ycbotchallenge$setTimesPressed(cur + 1);
    }

    /** Inter-click delay for the 5-8 cps spam, jittered log-normal. */
    private long clickIntervalMs() {
        int minMs = (int) Math.round(1000.0 / Math.max(1, cfg.clickCpsMax));
        int maxMs = (int) Math.round(1000.0 / Math.max(1, cfg.clickCpsMin));
        if (maxMs <= minMs) maxMs = minMs + 1;
        return HumanTiming.logNormalMs(minMs, maxMs);
    }

    private long approachVigorUntil = 0;
    private double approachVigor = 1.0;

    /** Approach-spam interval: log-normal around 2-3 cps, with the vigor re-rolled every few seconds. */
    private long approachIntervalMs() {
        long now = System.currentTimeMillis();
        if (now >= approachVigorUntil) {
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            approachVigor = 0.8 + rng.nextDouble() * 0.5; // 0.8x .. 1.3x
            approachVigorUntil = now + 1500 + rng.nextInt(2500);
        }
        int minMs = (int) Math.round(1000.0 / Math.max(0.5, cfg.approachClickCpsMax));
        int maxMs = (int) Math.round(1000.0 / Math.max(0.5, cfg.approachClickCpsMin));
        if (maxMs <= minMs) maxMs = minMs + 1;
        return Math.round(HumanTiming.logNormalMs(minMs, maxMs) * approachVigor);
    }

    /** True when the vanilla attack cooldown is ready (never faster than vanilla allows). */
    private boolean vanillaAttackReady(MinecraftClient client) {
        if (!cfg.respectVanillaAttackCooldown) return true;
        try {
            return client.player.getAttackCooldownProgress(0.0f) >= 1.0f;
        } catch (Throwable t) {
            return true;
        }
    }

    private void rollAimPoint(MinecraftClient client) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        aimHeightFrac = (float) (0.42 + rng.nextDouble() * 0.32);
        approachYawOffset = (float) ((rng.nextDouble() * 2 - 1) * cfg.approachYawOffsetMaxDeg / speedFactor(client));
        lookEntityId = Integer.MIN_VALUE;
        lookIssued = false;
    }

    private void rollTrackStyle(MinecraftClient client) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double a = Math.max(0, cfg.trackStyleFlickNext);
        double b = Math.max(0, cfg.trackStyleWatchThenFind);
        double c = Math.max(0, cfg.trackStyleScan);
        double d = Math.max(0, cfg.trackStyleHesitate);
        double sum = a + b + c + d;
        if (sum <= 0) sum = 1;
        double r = rng.nextDouble() * sum;
        if (r < a) trackStyle = TrackStyle.FLICK_NEXT;
        else if (r < a + b) trackStyle = TrackStyle.WATCH;
        else if (r < a + b + c) trackStyle = TrackStyle.SCAN;
        else trackStyle = TrackStyle.HESITATE;

        hesitateUntil = System.currentTimeMillis() + HumanTiming.logNormalMs(180, 700);
        scanStep = 0;
        scanCount = 0;
        if (trackStyle == TrackStyle.SCAN) {
            scanCount = HumanTiming.ticks(2, 4);
            scanYaw = new float[scanCount];
            scanPitch = new float[scanCount];
            float yaw = client.player.getYaw();
            float pitch = client.player.getPitch();
            for (int i = 0; i < scanCount; i++) {
                scanYaw[i] = yaw + (float) ((rng.nextDouble() * 2 - 1) * 40.0);
                scanPitch[i] = MathHelper.clamp(pitch + (float) ((rng.nextDouble() * 2 - 1) * 8.0), -30f, 40f);
            }
        }
    }

    private void maybeLook(MinecraftClient client, Entity e, String reason) {
        if (e == null) return;
        // Ninja chaining: mid-path corrections blend into the flight; other intents wait.
        if (MouseDriver.INSTANCE.isBusy()
            && !(cfg.ninja && cfg.mouseChaining && reason.endsWith("correct"))) return;
        if (lookIssued && lookEntityId == e.getId()) return;
        float lead = 0f;
        if ("approach".equals(reason) && approachYawOffset != 0f) {
            double distXZ = client.player.distanceTo(e);
            double t = MathHelper.clamp((distXZ - cfg.reach) / 8.0, 0.0, 1.0);
            lead = approachYawOffset * (float) t;
        }
        MouseDriver.INSTANCE.lookAtEntity(client, e, aimHeightFrac, lead, reason);
        lookIssued = true;
        lookEntityId = e.getId();
    }

    private void reacquireIfNeeded(MinecraftClient client, Entity e, String reason) {
        if (e == null || !lookIssued) return;
        if (MouseDriver.INSTANCE.isBusy() && !(cfg.ninja && cfg.mouseChaining)) return;
        double err = MouseDriver.aimErrorDeg(client, e, aimHeightFrac);
        if (err > cfg.lookReacquireDeg) {
            lookIssued = false;
            maybeLook(client, e, reason);
        }
    }

    /** Mirrors ClientPlayerEntity's own sprint-start gates so a forced sprint sticks. */
    private static boolean canStartSprint(MinecraftClient client) {
        var p = client.player;
        boolean fed = p.getHungerManager().getFoodLevel() > 6 || p.getAbilities().allowFlying;
        return (p.isOnGround() || p.isSubmergedInWater()) && fed
            && !p.isUsingItem() && !p.isSneaking() && !p.isGliding()
            && !p.hasStatusEffect(StatusEffects.BLINDNESS)
            && !(p.horizontalCollision && !p.collidedSoftly);
    }

    private boolean inZone(Vec3d pos) {
        if (cfg.zoneMin == null || cfg.zoneMax == null) return true;
        return pos.x >= cfg.zoneMin[0] && pos.x <= cfg.zoneMax[0]
            && pos.y >= cfg.zoneMin[1] && pos.y <= cfg.zoneMax[1]
            && pos.z >= cfg.zoneMin[2] && pos.z <= cfg.zoneMax[2];
    }

    private boolean validMob(MinecraftClient client, Entity e) {
        if (!(e instanceof LivingEntity le)) return false;
        if (e == client.player || e instanceof PlayerEntity) return false;
        if (e instanceof ArmorStandEntity || e instanceof DisplayEntity) return false;
        if (!le.isAlive() || le.isRemoved()) return false;
        if (!inZone(e.getEntityPos())) return false;
        if (cfg.stationaryOnly) {
            if (ghosts.contains(e.getId()) && !mayAttackMoving()) return false;
            Motion m = motion.get(e.getId());
            // must have been observed standing still before it's targetable
            if (m == null || m.ticks < cfg.minObservationTicks) return false;
        }
        return client.player.distanceTo(e) <= cfg.targetRange;
    }

    /**
     * Per-tick motion bookkeeping for the ghost filter. Real dungeon mobs
     * never move once landed; a client-side ghost follows/orbits the player.
     * Cumulative horizontal drift past the threshold blacklists an entity —
     * but drift is NOT counted while it's freshly spawned or airborne (stage
     * respawns drop mobs in from above), and a blacklisted entity that stands
     * still for ghostRedemptionSeconds is un-blacklisted (real ghosts never
     * stop moving).
     */
    private void updateMotion(MinecraftClient client) {
        if (!cfg.stationaryOnly) return;
        long now = System.currentTimeMillis();
        long redemptionMs = (long) (cfg.ghostRedemptionSeconds * 1000);
        for (Entity e : client.world.getEntities()) {
            if (!(e instanceof LivingEntity) || e == client.player || e instanceof PlayerEntity) continue;
            if (e instanceof ArmorStandEntity || e instanceof DisplayEntity) continue;
            if (client.player.distanceTo(e) > cfg.targetRange * 1.5) continue;
            int id = e.getId();
            Vec3d pos = e.getEntityPos();
            Motion m = motion.computeIfAbsent(id, k -> new Motion());
            double drift = 0;
            if (m.lastPos != null) {
                double dx = pos.x - m.lastPos.x;
                double dz = pos.z - m.lastPos.z;
                drift = Math.sqrt(dx * dx + dz * dz);
            }
            m.lastPos = pos;
            m.ticks++;
            m.lastSeen = now;

            if (ghosts.contains(id)) {
                // Redemption: a "ghost" that stays put was a misfire (spawn fall,
                // sync hiccup). Real ghosts keep following the player.
                if (redemptionMs <= 0) continue;
                if (drift > 0.03) {
                    m.stillSince = 0; // still moving — not redeemable
                } else {
                    if (m.stillSince == 0) m.stillSince = now;
                    if (now - m.stillSince >= redemptionMs) {
                        ghosts.remove(id);
                        m.moved = 0;
                        m.stillSince = 0;
                        if (logger != null) logger.log("ghost_redeemed", "entityId", id);
                    }
                }
                continue;
            }

            // Spawn grace: newly-seen or airborne entities get position
            // interpolation and fall movement we must not count.
            if (m.ticks <= cfg.spawnGraceTicks || !e.isOnGround()) continue;

            m.moved += drift;
            if (m.moved > cfg.ghostMotionBlocks) {
                ghosts.add(id);
                ghostsIgnored++;
                m.stillSince = 0;
            }
        }
        if (motion.size() > 512) motion.values().removeIf(m -> now - m.lastSeen > 10_000);
        if (ghosts.size() > 4096) ghosts.clear(); // safety valve
    }

    private LivingEntity pickTarget(MinecraftClient client, LivingEntity exclude) {
        List<LivingEntity> candidates = new ArrayList<>();
        Map<EntityType<?>, Integer> counts = new HashMap<>();
        for (Entity e : client.world.getEntities()) {
            if (e == exclude || !validMob(client, e)) continue;
            LivingEntity le = (LivingEntity) e;
            candidates.add(le);
            counts.merge(le.getType(), 1, Integer::sum);
        }
        if (candidates.isEmpty()) {
            dominantType = null;
            dominantCount = 0;
            dominantDesc = null;
            return null;
        }

        // dominant mob type = the majority population in range (the current stage's spawn)
        dominantType = null;
        dominantCount = 0;
        for (Map.Entry<EntityType<?>, Integer> en : counts.entrySet()) {
            if (en.getValue() > dominantCount) {
                dominantCount = en.getValue();
                dominantType = en.getKey();
            }
        }
        dominantDesc = dominantType != null
            ? dominantType.getName().getString() + " ×" + dominantCount : null;

        boolean filterToDominant = cfg.targetDominant && dominantCount >= cfg.minDominantPack;
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        float curYaw = client.player.getYaw();
        double turnCost = cfg.turnCostBlocks * speedFactor(client);   // same turn TIME, more ground covered
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (LivingEntity le : candidates) {
            if (filterToDominant && le.getType() != dominantType) continue;
            double d = client.player.distanceTo(le);
            // estimated travel cost in blocks: distance + a fixed price for the turn.
            // Additive, because a turn takes the same time whether the mob is 5 or
            // 25 blocks away — nearest-wins alone whips side to side, a multiplicative
            // bias ignores anything behind you even when it's the obvious next hop.
            Vec3d rel = le.getEntityPos().subtract(client.player.getEntityPos());
            float yawTo = (float) (Math.toDegrees(Math.atan2(rel.z, rel.x)) - 90.0);
            double angleErr = Math.abs(MathHelper.wrapDegrees(yawTo - curYaw));
            double score = d + turnCost * (angleErr / 180.0);
            // rarer mobs pay more: worth walking rarityBonusBlocks further for
            score -= rarityBonus(parseRarity(le));
            if (cfg.targetCostJitter > 0) {
                score *= 1.0 + (rng.nextDouble() * 2 - 1) * cfg.targetCostJitter;  // vary the lap
            }
            if (score < bestScore) { best = le; bestScore = score; }
        }
        // Ninja: occasionally pick a random in-range mob instead of the optimal one.
        if (cfg.ninja && best != null && candidates.size() > 1 && rng.nextDouble() < cfg.wrongTargetChance) {
            LivingEntity oops = candidates.get(rng.nextInt(candidates.size()));
            if (logger != null) logger.log("target_mispick", "mob", describe(oops));
            return oops;
        }
        return best;
    }

    private static String parseRarity(LivingEntity e) {
        Text custom = e.getCustomName();
        if (custom == null) return null;
        Matcher m = NAMEPLATE.matcher(custom.getString().trim());
        return m.matches() ? m.group("rarity").trim().toUpperCase() : null;
    }

    private double rarityBonus(String rarity) {
        if (rarity == null || cfg.rarityBonusBlocks == null) return 0;
        for (Map.Entry<String, Double> en : cfg.rarityBonusBlocks.entrySet()) {
            if (en.getKey().equalsIgnoreCase(rarity) && en.getValue() != null) return en.getValue();
        }
        return 0;
    }

    private static String describe(LivingEntity e) {
        Text custom = e.getCustomName();
        if (custom == null) return e.getType().getName().getString();
        Matcher m = NAMEPLATE.matcher(custom.getString().trim());
        return m.matches() ? "[" + m.group("rarity") + "] " + m.group("mob") : custom.getString();
    }

    private void readNameplate(LivingEntity e) {
        targetRarity = null;
        targetLevel = null;
        targetMob = e.getType().getName().getString();
        Text custom = e.getCustomName();
        if (custom != null) {
            Matcher m = NAMEPLATE.matcher(custom.getString().trim());
            if (m.matches()) {
                targetRarity = m.group("rarity");
                try { targetLevel = Integer.parseInt(m.group("level")); } catch (NumberFormatException ignored) {}
                targetMob = m.group("mob");
            } else {
                targetMob = custom.getString();
            }
        }
        lastTargetDesc = (targetRarity != null ? "[" + targetRarity + "] " : "") + targetMob;
    }
}

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
 * the server auto-attacks it until it dies — one mob at a time. So per cycle:
 * pick nearest mob -> walk into reach -> tap once -> wait for death -> next.
 */
public class CombatController {
    private static final Pattern NAMEPLATE = Pattern.compile(
        "^\\[(?<rarity>[^\\]]+)\\]\\s*(?:\\[?(?:Level|Lvl?\\.?)\\s*(?<level>\\d+)\\]?)?\\s*(?<mob>.+?)(?:\\s*[♥❤].*)?$",
        Pattern.CASE_INSENSITIVE);

    private final YCBotChallengeConfig cfg;
    private final StatsTracker stats;
    private EventLogger logger;

    private LivingEntity target = null;
    private boolean tagged = false;
    private long tagAt = 0;
    private long targetPickedAt = 0;
    /** While the current mob cooks: the mob we'll go for next (pre-aimed so the handoff is instant). */
    private LivingEntity nextTarget = null;
    private long nextPickedAt = 0;
    /** How far we may drift from the cooking mob this kill (rolled per tag). */
    private double cookLeash = 3.0;
    public String nextTargetDesc = null;
    private long nextActionAt = 0;   // humanized reaction / idle gate
    private long lastTapAt = 0;
    public int kills = 0;
    public String lastTargetDesc = null;
    public String dominantDesc = null;
    private EntityType<?> dominantType = null;
    private int dominantCount = 0;

    /** Upgrade controller may claim the post-kill stillness window. */
    public boolean wantsUpgradeWindow = false;

    private float approachYawOffset = 0f;
    /** Signed yaw error to the movement target (deg, + = target to the right). */
    private float lastYawErrSigned = 0f;
    private int prevOct = 0;
    private int octStaggerTicks = 0;
    private int pendingOct = 0;
    private int attackHoldTicks = 0;
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
        String s = (tagged ? "cooking " : "approaching ") + (lastTargetDesc == null ? "?" : lastTargetDesc);
        if (tagged && nextTargetDesc != null) s += "  §8→ next: " + nextTargetDesc;
        if (tagged) s += "  §8" + trackStyle.name().toLowerCase().replace('_', '-');
        return s;
    }

    public boolean isStationary(MinecraftClient client) {
        if (client.player == null) return false;
        return UpgradeController.playerStill(client);
    }

    public void reset(MinecraftClient client) {
        target = null;
        nextTarget = null;
        tagged = false;
        prevOct = 0;
        octStaggerTicks = 0;
        lookIssued = false;
        wantsUpgradeWindow = false;
        MouseDriver.INSTANCE.cancel();
        releaseKeys(client);
    }

    public void releaseKeys(MinecraftClient client) {
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
        if (attackHoldTicks <= 0) client.options.attackKey.setPressed(false);
    }

    public void tick(MinecraftClient client) {
        if (client.player == null || client.world == null || client.interactionManager == null) return;
        long now = System.currentTimeMillis();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        tickAttackHold(client);

        updateMotion(client);

        // current target started moving -> it's a ghost; drop it and rescan next tick
        if (target != null && cfg.stationaryOnly && ghosts.contains(target.getId())) {
            if (logger != null) {
                logger.log("target_abandoned", "reason", "moving-ghost", "mob", targetMob, "rarity", targetRarity);
            }
            target = null;
            tagged = false;
            lookIssued = false;
        }

        // occasional human-ish idle
        if (now < nextActionAt) { releaseKeys(client); return; }
        if (rng.nextDouble() < cfg.idleChancePerMinute / (60.0 * 20.0)) { // per tick
            nextActionAt = now + rng.nextLong(cfg.idleMinMs, cfg.idleMaxMs + 1);
            releaseKeys(client);
            return;
        }

        // current target dead? -> kill credit
        if (target != null && (target.isRemoved() || target.isDead() || !target.isAlive())) {
            if (tagged) {
                kills++;
                stats.recordKill();
                wantsUpgradeWindow = true;
                if (logger != null) {
                    logger.log("kill",
                        "mob", targetMob, "rarity", targetRarity, "level", targetLevel,
                        "timeToKillMs", now - tagAt, "kills", kills, "via", "death");
                }
            }
            target = null;
            tagged = false;
            lookIssued = false;
            nextActionAt = now + HumanTiming.logNormalMs(cfg.reactionDelayMinMs, cfg.reactionDelayMaxMs);
            return;
        }

        // tagged mob that never dies = client-side ghost or unkillable — abandon it
        if (target != null && tagged && now - tagAt > cfg.maxCookMs) {
            if (logger != null) {
                logger.log("target_abandoned", "reason", "cook-timeout",
                    "mob", targetMob, "rarity", targetRarity, "afterMs", now - tagAt);
            }
            target = null;
            tagged = false;
            lookIssued = false;
        }

        // stale un-killable target
        if (target != null && !tagged && now - targetPickedAt > 12_000) {
            target = null;
            lookIssued = false;
        }

        // stage changed under us: current (untagged) target is no longer the dominant mob type
        if (target != null && !tagged && cfg.targetDominant && dominantType != null
            && target.getType() != dominantType && dominantCount >= cfg.minDominantPack) {
            target = null;
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
            rollAimPoint(client);
            lookIssued = false;
            readNameplate(target);
            maybeLook(client, target, "approach");
            if (logger != null) {
                logger.log("tag_intent", "mob", targetMob, "rarity", targetRarity, "level", targetLevel);
            }
        }

        lastYawErrSigned = MouseDriver.signedYawError(client, tagged && nextTarget != null ? nextTarget : target);

        if (tagged) {
            tickCook(client, now);
            return;
        }

        maybeLook(client, target, "approach");
        reacquireIfNeeded(client, target, "approach-correct");

        double dist = client.player.distanceTo(target);
        if (dist > cfg.reach) {
            if (cfg.movement) moveToward(client, dist, true);
            return;
        }

        releaseKeys(client);
        // Arrived while sprinting: drop the sprint *key* and wait a tick so the
        // hit isn't a knockback sprint-hit (shoves the mob, trips ghost filter).
        if (client.player.isSprinting() || sprintTapTicks > 0) {
            tapSprint(client, false);
            return;
        }
        double aimErr = MouseDriver.aimErrorDeg(client, target, aimHeightFrac);
        if (!tagged && now - lastTapAt >= cfg.tapCooldownMs && aimErr <= cfg.aimTapMaxErrorDeg
            && !MouseDriver.INSTANCE.isBusy()) {
            pressAttack(client);
            lastTapAt = now;
            tagAt = now;
            tagged = true;
            cookLeash = rng.nextDouble(cfg.cookLeashMinBlocks, Math.max(cfg.cookLeashMinBlocks + 0.01, cfg.cookLeashMaxBlocks));
            rollTrackStyle(client);
            lookIssued = false;
            if (logger != null) {
                logger.log("tag", "mob", targetMob, "rarity", targetRarity, "level", targetLevel,
                    "trackStyle", trackStyle.name());
            }
        }
    }

    private void tickCook(MinecraftClient client, long now) {
        // Stay in range of the cooking mob; camera is a one-shot intent, not a lock.
        if (nextTarget == null || !validMob(client, nextTarget) || now - nextPickedAt > cfg.nextTargetRescanMs) {
            LivingEntity n = pickTarget(client, target);
            if (n != nextTarget) {
                nextTarget = n;
                nextTargetDesc = n != null ? describe(n) : null;
            }
            nextPickedAt = now;
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
        if (cfg.movement && toNext > cfg.reach && roomOnLeash && !throughTarget) moveToward(client, toNext, false);
        else releaseKeys(client);
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

    private void pressAttack(MinecraftClient client) {
        client.options.attackKey.setPressed(true);
        attackHoldTicks = HumanTiming.ticks(1, 2);
    }

    private void tickAttackHold(MinecraftClient client) {
        if (attackHoldTicks <= 0) return;
        attackHoldTicks--;
        if (attackHoldTicks <= 0) client.options.attackKey.setPressed(false);
        else client.options.attackKey.setPressed(true);
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
        if (e == null || MouseDriver.INSTANCE.isBusy()) return;
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
        if (e == null || MouseDriver.INSTANCE.isBusy() || !lookIssued) return;
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
            if (ghosts.contains(e.getId())) return false;
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

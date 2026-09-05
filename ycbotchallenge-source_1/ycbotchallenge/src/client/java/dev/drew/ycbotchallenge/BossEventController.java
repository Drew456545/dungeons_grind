package dev.drew.ycbotchallenge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * The zone boss (0.9.38). EnchantedMC spawns a personal boss in the player's zone about
 * every 89 minutes ("the boss in your zone has just spawned, you will have 5 minutes to
 * kill it before it despawns"): a large block-shaped body with its own bar ("Rotten Boss
 * 300", one count per hit), a title overlay and a small target marker on the body that
 * relocates about every ten hits. In 36 hours of logs the bot never landed a hit - the
 * bar sat at 300 for four and a half minutes of grinding next to it (2026-09-04 18:03:04,
 * {@code boost_end durationMs 277001}) until Drew toggled the bot off and killed it by
 * hand in 99 s. A kill pays a Tier-2 Totem Box, skin boxes, sword perk rolls and weak
 * 15-minute boosters; no money.
 *
 * <p>The marker's entity type was unknown when this was written (the mod had no entity
 * scan), so the module is built to learn it: the first thing it does is dump every
 * non-player entity near the boss ({@code boss_scan}), it ranks candidates by what vanilla
 * can actually hit (an {@code interaction} entity first, an armor stand second, a display
 * never - the attack key would mine the block behind it), and it only ever clicks while the
 * crosshair raycast is on the entity it chose. A wrong guess is a logged abort with the scan
 * attached, never a wasted five minutes or a mined block.
 *
 * <p>Shape: {@link CompanionController} - phases, a walk to a stand point outside the body
 * facing the marker (recomputed every tick, so the marker moving re-plans the walk), the
 * offset aim sweep, the combat tap loop, one {@code finish} that resets combat so the walk
 * is never read as a teleport by the stop protocol.
 */
public class BossEventController {
    private enum Phase { IDLE, SCAN, WALK, AIM, HIT, DONE }

    private record Candidate(Entity e, String type, int rank, double dBody, double volume, String plate) {}

    private final YCBotChallengeConfig cfg;
    private final StatsTracker stats;
    private final UpgradeController upgrades;
    private EventLogger logger;

    private Phase phase = Phase.IDLE;
    private long eventStartedAt;
    private int seqSeen = -1;
    private long startPendingSince = 0;
    private long lastSkipLogAt = 0;
    private String startVia = null;

    // the fight
    private Entity body;
    private Vec3d bodyPos;
    private Entity marker;
    private String markerType;
    private Vec3d markerPosAtTarget;
    private long targetAt;
    private Integer countAtTarget;
    private int targets;
    private int rescans;
    private int rescansWithoutProgress;
    private int hits;
    private long lastClickAt;
    private long lastProgressAt;
    private Integer lastCountSeen;
    private Integer lastTargetsHit;
    private long lastScanAt;
    private long lastRayOkAt;
    private long screenOpenSince;
    private int killedSeqSeen;
    private float aimHeightFrac = 0.5f;

    // walk / aim
    private long walkStartAt;
    private double bestDist;
    private long lastWalkProgressAt;
    private long lastLookAt;
    private int sidestepTicks;
    private int sidestepSign = 1;
    private int walkTimeouts;
    private int aimTry;
    private long aimIssuedAt;
    private int aimSweeps;
    private static final float[][] AIM_OFFSETS = {
        {0f, 0f}, {4f, 0f}, {-4f, 0f}, {0f, 4f}, {0f, -4f}, {8f, 0f}, {-8f, 0f}, {4f, 4f}, {4f, -4f}, {-4f, 4f}, {-4f, -4f}, {12f, 0f}};

    private int consecutiveAborts;
    private boolean suspended;
    // 0.9.42: retries inside one bar window, the walk to the body when no marker is in reach.
    private boolean retryPending = false;
    private long retryAt = 0;
    private long windowStartedAt = 0;
    private int windowHits = 0;
    private int windowRetries = 0;
    private boolean approaching = false;
    private boolean approachTried = false;

    public BossEventController(YCBotChallengeConfig cfg, StatsTracker stats, UpgradeController upgrades) {
        this.cfg = cfg;
        this.stats = stats;
        this.upgrades = upgrades;
    }

    public void setLogger(EventLogger logger) { this.logger = logger; }

    public boolean isBusy() { return phase != Phase.IDLE; }

    public boolean isSuspended() { return suspended; }

    public String hudLine() {
        if (!cfg.bossEventEnabled) return null;
        if (phase == Phase.IDLE) {
            if (suspended) return "boss: suspended after repeated aborts (toggle to reset)";
            return null;
        }
        Integer c = stats.bossEventCount;
        return "boss " + (stats.bossEventBarTitle != null ? stats.bossEventBarTitle : "?")
            + (c != null ? " · " + c + " left" : "") + " · hits " + hits + " · target " + targets
            + " · " + phase.name().toLowerCase(Locale.ROOT);
    }

    public void onEnable(long now, int kills) {
        suspended = false;
        consecutiveAborts = 0;
        retryPending = false;
        seqSeen = stats.bossEventSeq;
        killedSeqSeen = stats.bossKilledUsSeq;
        startPendingSince = 0;
    }

    public void reset(MinecraftClient client) {
        if (client != null) releaseWalkKeys(client);
        phase = Phase.IDLE;
        startPendingSince = 0;
    }

    private void log(String type, Object... kv) {
        if (logger != null) logger.log(type, kv);
    }

    /** @return true if combat should yield this tick. */
    public boolean tick(MinecraftClient client, CombatController combat) {
        if (!cfg.bossEventEnabled || client.player == null || client.world == null) return false;
        long now = System.currentTimeMillis();
        if (phase == Phase.IDLE) return maybeStart(client, combat, now);

        if (phase != Phase.WALK) combat.releaseKeys(client);
        if (now - eventStartedAt > cfg.bossEventMaxMs) { abort(client, combat, "event-timeout"); return false; }
        if (client.currentScreen != null) {
            if (screenOpenSince == 0) screenOpenSince = now;
            if (now - screenOpenSince > 5_000) { abort(client, combat, "screen-open"); return false; }
            releaseWalkKeys(client);
            return true;
        }
        screenOpenSince = 0;

        // Progress: the bar count falling, or the title counter rising, is the truth.
        Integer count = stats.bossEventCount;
        if (count != null && (lastCountSeen == null || count < lastCountSeen)) {
            lastCountSeen = count;
            lastProgressAt = now;
            rescansWithoutProgress = 0;
        }
        Integer th = stats.bossTargetsHit;
        if (th != null && !th.equals(lastTargetsHit)) {
            if (lastTargetsHit != null && th > lastTargetsHit) rescansWithoutProgress = 0;
            lastTargetsHit = th;
            lastProgressAt = now;
        }
        if (stats.bossKilledUsSeq != killedSeqSeen) { done(client, combat, "killed"); return false; }
        if (!stats.bossEventBarPresent && now - eventStartedAt > 3_000) { done(client, combat, "bar-gone"); return false; }

        switch (phase) {
            case SCAN -> {
                if (now - lastScanAt < 500) return true;
                if (!scan(client, combat, now)) return false;
                if (!approaching) beginWalk(now);
            }
            case WALK -> {
                if (approaching) {
                    if (body == null || body.isRemoved()) { approaching = false; phase = Phase.SCAN; return true; }
                } else if (marker == null || marker.isRemoved()) { phase = Phase.SCAN; return true; }
                Vec3d stand = approaching ? approachPoint(client) : standPoint(client);
                Vec3d p = client.player.getEntityPos();
                double dx = stand.x - p.x, dz = stand.z - p.z;
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist <= cfg.bossStandTolerance) {
                    releaseWalkKeys(client);
                    log("boss_walk", "blocks", Math.round((bestDist - dist) * 10.0) / 10.0, "ms", now - walkStartAt,
                        "left", Math.round(dist * 10.0) / 10.0, "target", targets, "approach", approaching);
                    if (approaching) {
                        // 0.9.42: at the body now - the marker only shows at close range.
                        approaching = false;
                        approachTried = true;
                        phase = Phase.SCAN;
                        lastScanAt = 0;
                        return true;
                    }
                    beginAim(now);
                    return true;
                }
                if (now - walkStartAt > cfg.bossWalkTimeoutMs) {
                    releaseWalkKeys(client);
                    if (++walkTimeouts >= 2) { abort(client, combat, "walk-timeout"); return false; }
                    log("boss_walk_timeout", "dist", Math.round(dist * 10.0) / 10.0, "rescan", true);
                    phase = Phase.SCAN;
                    return true;
                }
                float yawTo = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
                float err = MathHelper.wrapDegrees(yawTo - client.player.getYaw());
                if (Math.abs(err) > 6f && now - lastLookAt > 300 && !MouseDriver.INSTANCE.isBusy()) {
                    lastLookAt = now;
                    MouseDriver.INSTANCE.lookTo(client, yawTo, dist > 8 ? 5f : 15f, "boss-walk");
                }
                boolean forward = Math.abs(err) < 60f;
                if (dist < bestDist - 0.3) { bestDist = dist; lastWalkProgressAt = now; }
                boolean stuck = now - lastWalkProgressAt > 3000;
                if (stuck && sidestepTicks == 0) {
                    sidestepTicks = 14;
                    sidestepSign = -sidestepSign;
                    lastWalkProgressAt = now;
                    log("boss_walk_stuck", "dist", Math.round(dist * 10.0) / 10.0, "side", sidestepSign);
                }
                boolean side = sidestepTicks > 0;
                if (side) sidestepTicks--;
                client.options.forwardKey.setPressed(forward && !side);
                client.options.leftKey.setPressed(side && sidestepSign > 0);
                client.options.rightKey.setPressed(side && sidestepSign < 0);
                client.options.backKey.setPressed(false);
                combat.tapSprint(client, forward && dist > 6 && Math.abs(err) < 20f);
                client.options.jumpKey.setPressed(client.player.horizontalCollision || side);
                return true;
            }
            case AIM -> {
                if (marker == null || marker.isRemoved()) { phase = Phase.SCAN; return true; }
                if (aimIssuedAt == 0) {
                    if (aimTry >= AIM_OFFSETS.length) {
                        if (++aimSweeps >= 2) { abort(client, combat, "no-aim"); return false; }
                        log("boss_aim_sweep_failed", "sweeps", aimSweeps, "crosshair", crosshairDesc(client));
                        phase = Phase.SCAN;
                        return true;
                    }
                    float[] yp = anglesTo(client, markerAim());
                    MouseDriver.INSTANCE.cancel();
                    MouseDriver.INSTANCE.lookTo(client, yp[0] + AIM_OFFSETS[aimTry][1],
                        MathHelper.clamp(yp[1] + AIM_OFFSETS[aimTry][0], -89f, 89f), "boss-aim");
                    aimIssuedAt = now;
                    return true;
                }
                if (now - aimIssuedAt < 400) return true;
                if (rayOnMarker(client)) {
                    log("boss_aim", "try", aimTry, "hit", crosshairDesc(client), "target", targets);
                    phase = Phase.HIT;
                    lastRayOkAt = now;
                    lastProgressAt = now;
                    return true;
                }
                log("boss_aim_miss", "try", aimTry, "hit", crosshairDesc(client),
                    "pitchOff", AIM_OFFSETS[aimTry][0], "yawOff", AIM_OFFSETS[aimTry][1], "aimHeight", aimHeightFrac);
                // 0.9.42: a companion plate stand or a display in the ray (the 0.9.40 combat
                // rule, never ported here): aim a step lower on the marker's box and look again.
                Entity inRay = client.crosshairTarget instanceof EntityHitResult ehr ? ehr.getEntity() : null;
                if (inRay != null && inRay != marker && (inRay instanceof ArmorStandEntity || inRay instanceof DisplayEntity)
                    && aimHeightFrac > 0.2f) {
                    float from = aimHeightFrac;
                    aimHeightFrac = Math.max(0.2f, aimHeightFrac - 0.15f);
                    log("boss_aim_lowered", "from", Math.round(from * 100.0) / 100.0, "to", Math.round(aimHeightFrac * 100.0) / 100.0,
                        "hit", crosshairDesc(client));
                }
                aimTry++;
                aimIssuedAt = 0;
                return true;
            }
            case HIT -> {
                if (marker == null || marker.isRemoved()) {
                    log("boss_marker_gone", "hits", hits, "target", targets);
                    phase = Phase.SCAN;
                    return true;
                }
                Vec3d mp = marker.getEntityPos();
                if (markerPosAtTarget != null && mp.distanceTo(markerPosAtTarget) > cfg.bossMarkerMoveBlocks) {
                    log("boss_marker_moved", "blocks", Math.round(mp.distanceTo(markerPosAtTarget) * 10.0) / 10.0, "hits", hits);
                    phase = Phase.SCAN;
                    return true;
                }
                if (countAtTarget != null && count != null && countAtTarget - count >= cfg.bossMarkerMoveHits) {
                    log("boss_marker_due", "countDrop", countAtTarget - count, "hits", hits);
                    countAtTarget = count;
                    phase = Phase.SCAN;
                    return true;
                }
                if (now - lastProgressAt > cfg.bossNoProgressMs) {
                    rescans++;
                    if (++rescansWithoutProgress > cfg.bossMaxRescans) { abort(client, combat, "no-progress"); return false; }
                    log("boss_no_progress", "sinceMs", now - lastProgressAt, "hits", hits, "rescans", rescans,
                        "crosshair", crosshairDesc(client));
                    lastProgressAt = now;
                    phase = Phase.SCAN;
                    return true;
                }
                boolean rayOk = rayOnMarker(client);
                if (rayOk) lastRayOkAt = now;
                else if (now - lastRayOkAt > 1_500 && !MouseDriver.INSTANCE.isBusy()) {
                    // The camera drifted (or the marker slid along the face): sweep again.
                    log("boss_reaim", "hits", hits, "crosshair", crosshairDesc(client));
                    beginAim(now);
                    return true;
                }
                if (rayOk && !MouseDriver.INSTANCE.isBusy() && now - lastClickAt >= clickIntervalMs()
                    && !combat.swingHeld(now)
                    && (!cfg.bossRespectVanillaCooldown || combat.vanillaAttackReady(client))) {
                    combat.pressAttack(client);
                    lastClickAt = now;
                    hits++;
                    windowHits++;
                    if (hits == 1 || hits % Math.max(1, cfg.bossHitLogEvery) == 0) {
                        log("boss_hit", "n", hits, "count", count, "targetsHit", th, "target", targets,
                            "sinceTargetMs", now - targetAt, "dist", Math.round(client.player.distanceTo(marker) * 100.0) / 100.0);
                    }
                }
                return true;
            }
            case DONE -> { finish(client, combat); return false; }
            default -> { }
        }
        return true;
    }

    // ---------------------------------------------------------------- trigger

    private boolean maybeStart(MinecraftClient client, CombatController combat, long now) {
        if (suspended) return false;
        int seq = stats.bossEventSeq;
        boolean fresh = seq != seqSeen;
        boolean live = stats.bossEventBarPresent || (stats.bossTitleStartAt != 0 && now - stats.bossTitleStartAt < 15_000);
        if (!live) {
            // 0.9.42: the bar went away with a retry pending - that window is over.
            if (retryPending) endWindow("bar-gone");
            seqSeen = seq;
            return false;
        }
        boolean retry = retryPending && now >= retryAt;
        if (!fresh && !retry) return false;
        if (startPendingSince == 0) startPendingSince = now;
        String blocked = null;
        if (combat.isOnBreak()) blocked = "break";
        else if (client.currentScreen != null) blocked = "screen";
        else if (upgrades != null && upgrades.isBusy()) blocked = "upgrade-typing";
        else if (combat.isCooking() && now - startPendingSince < cfg.bossEventStartGraceMs) blocked = "cooking";
        if (blocked != null) {
            if (now - lastSkipLogAt > 10_000) {
                lastSkipLogAt = now;
                log("boss_skip", "reason", blocked, "pendingMs", now - startPendingSince, "count", stats.bossEventCount);
            }
            return false;
        }
        seqSeen = seq;
        startPendingSince = 0;
        startVia = stats.bossEventBarPresent ? "bar" : "title";
        if (retry) {
            retryPending = false;
            eventStartedAt = windowStartedAt; // the five minutes bound the whole window
        } else {
            windowStartedAt = now;
            windowHits = 0;
            windowRetries = 0;
            eventStartedAt = now;
        }
        approaching = false;
        approachTried = false;
        hits = 0; targets = 0; rescans = 0; rescansWithoutProgress = 0; walkTimeouts = 0; aimSweeps = 0;
        lastCountSeen = stats.bossEventCount;
        countAtTarget = lastCountSeen;
        lastTargetsHit = stats.bossTargetsHit;
        lastProgressAt = now;
        lastClickAt = 0;
        killedSeqSeen = stats.bossKilledUsSeq;
        body = null; bodyPos = null; marker = null; markerType = null; markerPosAtTarget = null;
        screenOpenSince = 0;
        log("boss_seen", "via", startVia, "barTitle", stats.bossEventBarTitle, "count", stats.bossEventCount,
            "targetsHit", stats.bossTargetsHit, "cooking", combat.isCooking(), "kills", combat.kills,
            "sinceBarMs", stats.bossEventSeenAt != 0 ? now - stats.bossEventSeenAt : null,
            "retry", retry ? windowRetries : null, "windowMs", retry ? now - windowStartedAt : null);
        combat.releaseKeys(client);
        MouseDriver.INSTANCE.cancel();
        phase = Phase.SCAN;
        return true;
    }

    // ------------------------------------------------------------------ scan

    /** Every non-player entity near us, ranked by what vanilla can hit; the evidence net for the marker's type. */
    private boolean scan(MinecraftClient client, CombatController combat, long now) {
        lastScanAt = now;
        Vec3d me = client.player.getEntityPos();
        List<Entity> near = new ArrayList<>();
        for (Entity e : client.world.getEntities()) {
            if (e == client.player || e instanceof PlayerEntity || e.isRemoved()) continue;
            if (e.getEntityPos().distanceTo(me) > cfg.bossScanRadius) continue;
            near.add(e);
        }
        near.sort((a, b) -> Double.compare(a.getEntityPos().distanceTo(me), b.getEntityPos().distanceTo(me)));

        // The body: the visible cube is a display when the server built it that way, else the
        // largest box around; the marker sits on its surface. 0.9.42: the cube is a cluster of
        // block displays (Drew's screenshots), so the block display with the most block
        // displays within 6 blocks of it is the body, nearest first on a tie; then any
        // display; then the largest non-living box.
        Entity bestBody = null;
        double bestVol = -1;
        Entity nearestDisplay = null;
        Entity clusterBody = null;
        int clusterBest = -1;
        List<Entity> blockDisplays = new ArrayList<>();
        for (Entity e : near) if (typeId(e).endsWith("block_display")) blockDisplays.add(e);
        for (Entity e : blockDisplays) {
            int n = 0;
            for (Entity o : blockDisplays) if (o != e && centre(o).distanceTo(centre(e)) <= 6.0) n++;
            if (n > clusterBest) { clusterBest = n; clusterBody = e; }
        }
        for (Entity e : near) {
            String type = typeId(e);
            if (nearestDisplay == null && (type.endsWith("block_display") || type.endsWith("item_display"))) nearestDisplay = e;
            double vol = volume(e);
            if (!(e instanceof LivingEntity) && vol > bestVol) { bestVol = vol; bestBody = e; }
        }
        body = clusterBody != null ? clusterBody : nearestDisplay != null ? nearestDisplay : bestBody;
        bodyPos = body != null ? centre(body) : (bodyPos != null ? bodyPos : me);
        boolean bodyKnown = body != null;

        List<Candidate> cands = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Entity e : near) {
            String type = typeId(e);
            String plate = String.join(" | ", CombatController.plateTextLines(e));
            // 0.9.42: an armor stand is a LivingEntity to vanilla; the rank must not see it as a mob.
            boolean livingMob = e instanceof LivingEntity && !(e instanceof ArmorStandEntity);
            int rank = Economy.markerRank(type, livingMob, !plate.isBlank(),
                !plate.isBlank() && stats.bossTargetNameRe != null && stats.bossTargetNameRe.matcher(plate).find());
            double dBody = e == body ? 0 : centre(e).distanceTo(bodyPos);
            if (rows.size() < 60) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("type", type);
                r.put("id", e.getId());
                Vec3d p = e.getEntityPos();
                r.put("x", Math.round(p.x * 10.0) / 10.0); r.put("y", Math.round(p.y * 10.0) / 10.0); r.put("z", Math.round(p.z * 10.0) / 10.0);
                r.put("dist", Math.round(p.distanceTo(me) * 10.0) / 10.0);
                r.put("dBody", Math.round(dBody * 10.0) / 10.0);
                r.put("w", Math.round(e.getWidth() * 100.0) / 100.0);
                r.put("h", Math.round(e.getHeight() * 100.0) / 100.0);
                r.put("living", e instanceof LivingEntity);
                r.put("name", e.getName() != null ? e.getName().getString() : null);
                r.put("custom", e.getCustomName() != null ? e.getCustomName().getString() : null);
                r.put("plate", plate.isBlank() ? null : plate);
                r.put("rank", rank);
                rows.add(r);
            }
            // 0.9.42: the marker sits on the body - a stand across the zone (our companions,
            // a damage number) is not it.
            if (e != body && rank < 9 && (!bodyKnown || dBody <= cfg.bossMarkerBodyRadius)) {
                cands.add(new Candidate(e, type, rank, dBody, volume(e), plate));
            }
        }
        // Best rank first; inside a rank the smallest box (the marker is small, a body-sized
        // interaction box is not), then the one closest to the body.
        cands.sort((a, b) -> {
            if (a.rank() != b.rank()) return Integer.compare(a.rank(), b.rank());
            if (a.volume() != b.volume()) return Double.compare(a.volume(), b.volume());
            return Double.compare(a.dBody(), b.dBody());
        });
        Candidate chosen = cands.isEmpty() ? null : cands.get(0);
        String via = chosen == null ? "none" : "rank" + chosen.rank();
        if (chosen != null && chosen.rank() >= 2) {
            // A display is the picture, not the hitbox: something hittable must sit on it.
            Candidate hittable = null;
            for (Candidate c : cands) {
                if (c.rank() <= 1 && centre(c.e()).distanceTo(centre(chosen.e())) <= cfg.bossMarkerBodyRadius) { hittable = c; break; }
            }
            if (hittable != null) { chosen = hittable; via = "near-display"; }
            else chosen = null;
        }
        // 0.9.42: nothing hittable seen from here but the body is - the target's own entity
        // only shows once we stand at the body (a 48-block scan lists what the client has
        // loaded, not what it can hit). Walk there once, then look again.
        double bodyDist = bodyKnown ? bodyPos.distanceTo(me) : -1;
        if (chosen == null && bodyKnown && !approachTried && bodyDist > cfg.reach + 1.5) {
            log("boss_scan", "count", near.size(), "radius", cfg.bossScanRadius, "entities", rows,
                "bodyPos", fmt(bodyPos), "bodyType", typeId(body), "bodyDist", Math.round(bodyDist * 10.0) / 10.0,
                "chosen", null, "chosenVia", "approach", "candidates", cands.size());
            marker = null;
            approaching = true;
            beginWalk(now);
            return true;
        }
        log("boss_scan", "count", near.size(), "radius", cfg.bossScanRadius, "entities", rows,
            "bodyPos", fmt(bodyPos), "bodyType", body != null ? typeId(body) : null,
            "bodyDist", bodyKnown ? Math.round(bodyDist * 10.0) / 10.0 : null,
            "chosen", chosen != null ? chosen.type() : null, "chosenVia", approachTried ? via + "/near-body" : via,
            "candidates", cands.size());
        if (chosen == null) { abort(client, combat, bodyKnown ? "marker-not-hittable" : "no-marker"); return false; }
        approachTried = false;
        boolean same = marker != null && marker.getId() == chosen.e().getId();
        marker = chosen.e();
        markerType = chosen.type();
        markerPosAtTarget = marker.getEntityPos();
        targetAt = now;
        countAtTarget = stats.bossEventCount;
        if (!same) targets++;
        aimHeightFrac = 0.5f;
        Vec3d stand = standPoint(client);
        log("boss_target", "entityId", marker.getId(), "type", markerType, "same", same,
            "x", Math.round(markerPosAtTarget.x * 10.0) / 10.0, "y", Math.round(markerPosAtTarget.y * 10.0) / 10.0,
            "z", Math.round(markerPosAtTarget.z * 10.0) / 10.0, "dBody", Math.round(chosen.dBody() * 10.0) / 10.0,
            "face", faceDesc, "stand", fmt(stand), "walkBlocks", Math.round(stand.distanceTo(me) * 10.0) / 10.0,
            "via", via, "target", targets, "plate", chosen.plate().isBlank() ? null : chosen.plate());
        return true;
    }

    private void beginWalk(long now) {
        phase = Phase.WALK;
        walkStartAt = now;
        bestDist = Double.MAX_VALUE;
        lastWalkProgressAt = now;
        lastLookAt = 0;
        sidestepTicks = 0;
    }

    private void beginAim(long now) {
        phase = Phase.AIM;
        aimTry = 0;
        aimIssuedAt = 0;
        MouseDriver.INSTANCE.cancel();
    }

    private String faceDesc = "side";

    /** Where to stand: outside the body, facing the marker, inside vanilla's entity reach. */
    private Vec3d standPoint(MinecraftClient client) {
        Vec3d m = markerAim();
        Vec3d p = client.player.getEntityPos();
        double[] out = Economy.bossStandPoint(new double[]{bodyPos.x, bodyPos.y, bodyPos.z},
            new double[]{m.x, m.y, m.z}, cfg.reach, new double[]{p.x, p.y, p.z});
        faceDesc = out[3] == 0 ? "side" : out[3] == 1 ? "top" : "degenerate";
        return new Vec3d(out[0], out[1], out[2]);
    }

    /** The aim point on the marker: its box at aimHeightFrac (0.9.42: lowered when a stand sits in the ray). */
    private Vec3d markerAim() {
        if (marker == null) return bodyPos;
        Box b = marker.getBoundingBox();
        if (b == null || volume(marker) <= 1e-6) return centre(marker);
        return new Vec3d(b.getCenter().x, b.minY + (b.maxY - b.minY) * aimHeightFrac, b.getCenter().z);
    }

    /** 0.9.42: where to stand to look at the body when no marker is known yet: reach + 1 block from it, on our side. */
    private Vec3d approachPoint(MinecraftClient client) {
        Vec3d p = client.player.getEntityPos();
        double dx = p.x - bodyPos.x, dz = p.z - bodyPos.z;
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d < 1e-3) { dx = 1; dz = 0; d = 1; }
        double r = Math.max(1.5, cfg.reach + 1.0);
        return new Vec3d(bodyPos.x + dx / d * r, p.y, bodyPos.z + dz / d * r);
    }

    private boolean rayOnMarker(MinecraftClient client) {
        return marker != null && client.crosshairTarget instanceof EntityHitResult ehr && ehr.getEntity() == marker;
    }

    private String crosshairDesc(MinecraftClient client) {
        HitResult hr = client.crosshairTarget;
        if (hr == null) return "none";
        if (hr.getType() == HitResult.Type.ENTITY) return "entity:" + typeId(((EntityHitResult) hr).getEntity());
        if (hr.getType() == HitResult.Type.BLOCK) return "block";
        return "miss";
    }

    private long clickIntervalMs() {
        int minMs = (int) Math.round(1000.0 / Math.max(0.5, cfg.bossClickCpsMax));
        int maxMs = (int) Math.round(1000.0 / Math.max(0.5, cfg.bossClickCpsMin));
        if (maxMs <= minMs) maxMs = minMs + 1;
        return HumanTiming.logNormalMs(minMs, maxMs);
    }

    private static String typeId(Entity e) {
        try {
            return Registries.ENTITY_TYPE.getId(e.getType()).toString();
        } catch (Throwable t) {
            return e.getType().getName().getString();
        }
    }

    private static double volume(Entity e) {
        Box b = e.getBoundingBox();
        return b == null ? 0 : Math.max(0, b.getLengthX()) * Math.max(0, b.getLengthY()) * Math.max(0, b.getLengthZ());
    }

    private static Vec3d centre(Entity e) {
        Box b = e.getBoundingBox();
        if (b != null && volume(e) > 1e-6) return b.getCenter();
        return e.getEntityPos().add(0, e.getHeight() * 0.5, 0);
    }

    private static String fmt(Vec3d v) {
        return v == null ? null : Math.round(v.x * 10.0) / 10.0 + "," + Math.round(v.y * 10.0) / 10.0 + "," + Math.round(v.z * 10.0) / 10.0;
    }

    private static float[] anglesTo(MinecraftClient client, Vec3d aim) {
        Vec3d eye = client.player.getEyePos();
        double dx = aim.x - eye.x, dy = aim.y - eye.y, dz = aim.z - eye.z;
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        float wantYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float wantPitch = (float) -Math.toDegrees(Math.atan2(dy, distXZ));
        return new float[] { wantYaw, MathHelper.clamp(wantPitch, -89f, 89f) };
    }

    private static void releaseWalkKeys(MinecraftClient client) {
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
    }

    // ----------------------------------------------------------------- ends

    private void done(MinecraftClient client, CombatController combat, String via) {
        long now = System.currentTimeMillis();
        Integer count = stats.bossEventCount;
        boolean complete = "killed".equals(via) || (count != null && count <= 5);
        log("boss_done", "via", via, "hits", hits, "count", count, "targetsHit", stats.bossTargetsHit,
            "targets", targets, "rescans", rescans, "eventMs", now - eventStartedAt, "complete", complete,
            "markerType", markerType);
        consecutiveAborts = 0;
        retryPending = false;
        finish(client, combat);
    }

    private void abort(MinecraftClient client, CombatController combat, String why) {
        long now = System.currentTimeMillis();
        // 0.9.42: the bar is still up and the window has time left - look again in a moment
        // instead of giving the boss up (the 07:58 window: one scan, then 290 s of nothing).
        long windowMs = now - windowStartedAt;
        boolean canRetry = stats.bossEventBarPresent && !"event-timeout".equals(why)
            && windowMs + cfg.bossRescanMs + 10_000 < cfg.bossEventMaxMs
            && windowRetries < cfg.bossMaxWindowRetries;
        log("boss_abort", "reason", why, "phase", phase.name().toLowerCase(Locale.ROOT), "hits", hits,
            "count", stats.bossEventCount, "targetsHit", stats.bossTargetsHit, "targets", targets, "rescans", rescans,
            "eventMs", now - eventStartedAt, "windowMs", windowMs, "markerType", markerType,
            "crosshair", client != null ? crosshairDesc(client) : null,
            "retryInMs", canRetry ? cfg.bossRescanMs : null, "windowHits", windowHits);
        if (canRetry) {
            retryPending = true;
            retryAt = now + cfg.bossRescanMs;
            windowRetries++;
        } else {
            endWindow(why);
        }
        finish(client, combat);
    }

    /** 0.9.42: a bar window is over without a kill: one abort when nothing in it landed a hit. */
    private void endWindow(String why) {
        retryPending = false;
        boolean counted = windowHits == 0;
        if (counted && ++consecutiveAborts >= Math.max(1, cfg.bossMaxConsecutiveAborts)) {
            suspended = true;
            log("boss_suspended", "aborts", consecutiveAborts);
        }
        log("boss_window_end", "reason", why, "hits", windowHits, "retries", windowRetries,
            "windowMs", System.currentTimeMillis() - windowStartedAt, "counted", counted, "aborts", consecutiveAborts);
    }

    private void finish(MinecraftClient client, CombatController combat) {
        if (client != null) releaseWalkKeys(client);
        MouseDriver.INSTANCE.cancel();
        // We walked while combat did not tick: its last position is stale and the stop protocol
        // would read the gap as a teleport. A fresh start clears it (the companion rule).
        if (combat != null && client != null) combat.reset(client);
        phase = Phase.IDLE;
        marker = null;
        body = null;
    }
}

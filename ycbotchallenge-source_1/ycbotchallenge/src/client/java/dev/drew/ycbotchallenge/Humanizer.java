package dev.drew.ycbotchallenge;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Body language layered on top of the grind loop. CombatController still
 * decides WHAT to tag; this decides HOW the camera and hands get there —
 * wandering gaze, never-still mouse, hesitation before a tap, delayed key
 * changes, and a per-session personality so two runs don't look identical.
 *
 * Designed to look like a tired-but-competent player in a 50x50 dungeon pad,
 * not like a state machine that snaps to the nearest hitbox.
 */
public class Humanizer {
    public enum Gaze {
        TARGET,   // the mob we're walking to tag
        COOK,     // the tagged mob that's dying
        NEXT,     // the one we might go for after
        WANDER    // a world point / random nearby entity / sky / floor
    }

    /** How this kill's wait time is spent. Rolled on every tag. */
    public enum CookStyle {
        EFFICIENT, // glance at next, walk toward it (old behaviour, mixed in)
        WATCH,     // mostly watch the tagged mob, occasional look-away
        FIDGET     // look around the pad, tiny sidesteps
    }

    private final YCBotChallengeConfig cfg;
    private final int sessionSeed;

    /** Multiplies reaction/hesitation windows for this session (≈0.85–1.3). */
    public final double tempo;
    /** Extra camera wander amplitude for this session. */
    public final double wanderAmp;
    /** Aim-agility offset for this session, already clamped into 0.05–1. */
    public final double agility;

    public Gaze gaze = Gaze.WANDER;
    public CookStyle cookStyle = CookStyle.WATCH;
    public String hudHint = "looking around";

    private Vec3d wanderPoint = null;
    private Entity wanderEntity = null;
    private long gazeUntil = 0;
    private long noticeUntil = 0;
    private long arrivedAt = 0;
    private long tapHesitationMs = 0;
    private long microPauseUntil = 0;
    private long fidgetUntil = 0;
    private int fidgetDir = 0; // -1 left, +1 right, 0 none
    private int fidgetTicksLeft = 0;
    private float aimHeightFrac = 0.62f;
    private float heightVel = 0f;
    private int pendingOct = 0;
    private int octDelayTicks = 0;
    private int sprintWarmup = 0;
    private int sprintWarmupNeed = 4;
    private float noiseT = 0f;
    private final float nYawSlow;
    private final float nYawFast;
    private final float nPitchSlow;
    private final float nPitchFast;
    private final float nYawPhase;
    private final float nPitchPhase;

    public Humanizer(YCBotChallengeConfig cfg) {
        this.cfg = cfg;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        this.sessionSeed = rng.nextInt();
        this.tempo = 0.85 + rng.nextDouble() * 0.45;
        this.wanderAmp = 0.7 + rng.nextDouble() * 0.8;
        this.agility = MathHelper.clamp(cfg.aimAgility + (rng.nextDouble() * 2 - 1) * 0.08, 0.08, 0.95);
        this.nYawSlow = 0.35f + rng.nextFloat() * 0.25f;
        this.nYawFast = 2.4f + rng.nextFloat() * 1.6f;
        this.nPitchSlow = 0.28f + rng.nextFloat() * 0.22f;
        this.nPitchFast = 1.8f + rng.nextFloat() * 1.2f;
        this.nYawPhase = rng.nextFloat() * 40f;
        this.nPitchPhase = rng.nextFloat() * 40f;
    }

    public void reset() {
        gaze = Gaze.WANDER;
        wanderPoint = null;
        wanderEntity = null;
        gazeUntil = 0;
        noticeUntil = 0;
        arrivedAt = 0;
        tapHesitationMs = 0;
        microPauseUntil = 0;
        fidgetUntil = 0;
        fidgetDir = 0;
        fidgetTicksLeft = 0;
        pendingOct = 0;
        octDelayTicks = 0;
        sprintWarmup = 0;
        hudHint = "looking around";
    }

    /** Call when a new current target is acquired (not yet tagged). */
    public void onNewTarget(long now, boolean alreadyLinedUp) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        gaze = Gaze.TARGET;
        // If we already walked onto this mob during the last cook, the "notice"
        // beat would just stall a kill we were already set up for.
        noticeUntil = alreadyLinedUp
            ? now
            : now + scaled(rng.nextLong(cfg.noticeDelayMinMs, cfg.noticeDelayMaxMs + 1));
        arrivedAt = 0;
        tapHesitationMs = 0;
        sprintWarmup = 0;
        sprintWarmupNeed = 2 + rng.nextInt(7);
        aimHeightFrac = alreadyLinedUp ? 0.62f : 0.95f; // nametag glance only on a fresh spot
        heightVel = alreadyLinedUp ? 0f : -0.012f;
        hudHint = alreadyLinedUp ? "closing in" : "noticed a mob";
    }

    /** Call the tick a mob is tagged. Picks how we'll spend the cook. */
    public void onTagged(long now) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double r = rng.nextDouble();
        if (r < cfg.cookEfficientChance) cookStyle = CookStyle.EFFICIENT;
        else if (r < cfg.cookEfficientChance + cfg.cookWatchChance) cookStyle = CookStyle.WATCH;
        else cookStyle = CookStyle.FIDGET;
        arrivedAt = 0;
        gazeUntil = now + scaled(rng.nextLong(180, 700));
        gaze = Gaze.COOK; // always glance at the thing we just hit for a beat
        fidgetUntil = now + scaled(rng.nextLong(400, 1600));
        hudHint = switch (cookStyle) {
            case EFFICIENT -> "lining up next";
            case WATCH -> "watching it die";
            case FIDGET -> "looking around";
        };
    }

    public boolean noticing(long now) {
        if (!cfg.humanize) return false;
        return now < noticeUntil;
    }

    /**
     * In-reach hesitation: first tick we enter reach we roll a delay, then
     * wait it out. Humans don't left-click the frame they stop walking.
     */
    public boolean waitingToTap(long now) {
        if (!cfg.humanize) return false;
        if (arrivedAt == 0) {
            arrivedAt = now;
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            tapHesitationMs = scaled(rng.nextLong(cfg.tapHesitationMinMs, cfg.tapHesitationMaxMs + 1));
            // rare "huh, is this the right one?" extra beat
            if (rng.nextDouble() < cfg.tapHesitationLongChance) {
                tapHesitationMs += scaled(rng.nextLong(220, 700));
            }
            hudHint = "winding up";
        }
        return now < arrivedAt + tapHesitationMs;
    }

    public void leftReach() {
        arrivedAt = 0;
        tapHesitationMs = 0;
    }

    /**
     * Short hitch mid-approach. Returns true while keys should be released.
     */
    public boolean microPausing(double dist, long now) {
        if (!cfg.humanize) return false;
        if (now < microPauseUntil) return true;
        if (dist < cfg.reach + 2.5) return false;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        if (rng.nextDouble() < cfg.microPauseChancePerMinute / (60.0 * 20.0)) {
            microPauseUntil = now + scaled(rng.nextLong(cfg.microPauseMinMs, cfg.microPauseMaxMs + 1));
            hudHint = "hesitating";
            return true;
        }
        return false;
    }

    /**
     * Lognormal-ish reaction after a kill, clamped to the configured window
     * (uniform windows look metronomic).
     */
    public long reactionDelay() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double mid = (cfg.reactionDelayMinMs + cfg.reactionDelayMaxMs) / 2.0;
        double sigma = 0.32;
        long v = Math.round(Math.exp(Math.log(Math.max(1.0, mid * tempo)) + rng.nextGaussian() * sigma));
        long lo = cfg.reactionDelayMinMs;
        long hi = Math.max(lo + 1, (long) (cfg.reactionDelayMaxMs * 1.6 * tempo));
        return Math.max(lo, Math.min(hi, v));
    }

    public long idleHold() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        // Mix: mostly short "wait, where next" pauses; rare longer look-around.
        if (rng.nextDouble() < 0.72) {
            return scaled(rng.nextLong(cfg.idleMinMs, Math.min(cfg.idleMaxMs, cfg.idleMinMs + 900) + 1));
        }
        return scaled(rng.nextLong(cfg.idleMinMs, cfg.idleMaxMs + 1));
    }

    /** Aim point on a living entity: drifting chest/head, never a locked 0.6. */
    public Vec3d aimPoint(LivingEntity e) {
        float h = MathHelper.clamp(aimHeightFrac, 0.28f, 1.08f);
        return e.getEntityPos().add(0, e.getHeight() * h, 0);
    }

    public void tickAimHeight() {
        // nametag glance settles toward the torso, then wanders a little
        float want = 0.58f + 0.10f * (float) Math.sin(noiseT * 0.07 + nYawPhase);
        heightVel += (want - aimHeightFrac) * 0.04f;
        heightVel *= 0.86f;
        aimHeightFrac += heightVel;
    }

    /**
     * Pick where the eyes should go this tick. During cook we do NOT lock onto
     * the next mob for the whole wait — we hold a look, then switch.
     */
    public Vec3d gazePoint(MinecraftClient client, LivingEntity current, LivingEntity next, boolean tagged, long now) {
        if (!cfg.humanize) {
            LivingEntity look = tagged ? (next != null ? next : current) : current;
            return look != null
                ? look.getEntityPos().add(0, look.getHeight() * 0.6, 0)
                : fallbackLook(client);
        }
        if (now >= gazeUntil || !gazeStillValid(current, next, tagged)) {
            pickGaze(client, current, next, tagged, now);
        }
        return resolveGaze(client, current, next);
    }

    public boolean wantsPreWalk() {
        return !cfg.humanize || cookStyle == CookStyle.EFFICIENT;
    }

    /**
     * Tiny sidestep during cook fidget. Returns -1 (A), +1 (D), or 0.
     * Caller must still respect the cook leash.
     */
    public int cookFidgetStrafe(long now) {
        if (!cfg.humanize || cookStyle != CookStyle.FIDGET) {
            fidgetTicksLeft = 0;
            fidgetDir = 0;
            return 0;
        }
        if (fidgetTicksLeft > 0) {
            fidgetTicksLeft--;
            return fidgetDir;
        }
        fidgetDir = 0;
        if (now < fidgetUntil) return 0;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        fidgetUntil = now + scaled(rng.nextLong(700, 2400));
        if (rng.nextDouble() > 0.55) return 0;
        fidgetDir = rng.nextBoolean() ? -1 : 1;
        fidgetTicksLeft = 3 + rng.nextInt(8);
        hudHint = fidgetDir < 0 ? "sidestep L" : "sidestep R";
        return fidgetDir;
    }

    /**
     * Delay WASD octant changes by 0–3 ticks so A and W don't land on the
     * same packet as a perfect 45° strafe every time.
     */
    public int delayOctant(int desiredOct, int prevOct) {
        if (!cfg.humanize) return desiredOct;
        if (desiredOct == prevOct) {
            pendingOct = desiredOct;
            octDelayTicks = 0;
            return desiredOct;
        }
        if (desiredOct != pendingOct) {
            pendingOct = desiredOct;
            octDelayTicks = ThreadLocalRandom.current().nextInt(0, cfg.keyTransitionMaxTicks + 1);
        }
        if (octDelayTicks > 0) {
            octDelayTicks--;
            return prevOct;
        }
        return desiredOct;
    }

    /** Humans don't sprint the instant W goes down. */
    public boolean sprintWarmedUp(boolean wantSprint) {
        if (!cfg.humanize) return wantSprint;
        if (!wantSprint) {
            sprintWarmup = 0;
            return false;
        }
        if (sprintWarmup < sprintWarmupNeed) {
            sprintWarmup++;
            return false;
        }
        return true;
    }

    /**
     * Slow drift + fast tremor, in degrees, applied to the *desired* look
     * so the momentum camera orbits a living point instead of a pixel.
     */
    public float yawNoise() {
        noiseT += 0.05f;
        double slow = Math.sin(noiseT * nYawSlow + nYawPhase) * 1.15;
        double fast = Math.sin(noiseT * nYawFast + sessionSeed) * 0.18;
        double walk = valueNoise(noiseT * 0.11 + (sessionSeed & 255)) * 0.55;
        return (float) ((slow + fast + walk) * wanderAmp * cfg.cameraNoiseScale);
    }

    public float pitchNoise() {
        double slow = Math.sin(noiseT * nPitchSlow + nPitchPhase) * 0.55;
        double fast = Math.sin(noiseT * nPitchFast * 1.3 + 2.1) * 0.10;
        double walk = valueNoise(noiseT * 0.09 + 40 + (sessionSeed & 255)) * 0.28;
        return (float) ((slow + fast + walk) * wanderAmp * cfg.cameraNoiseScale);
    }

    /** Idle / no-target: keep the camera breathing instead of freezing. */
    public void idleLook(MinecraftClient client, long now) {
        if (client.player == null) return;
        if (now >= gazeUntil) {
            pickWander(client, now, 900, 2800);
            hudHint = "looking around";
        }
    }

    // ------------------------------------------------------------------ gaze

    private void pickGaze(MinecraftClient client, LivingEntity current, LivingEntity next, boolean tagged, long now) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        if (!tagged) {
            gaze = Gaze.TARGET;
            gazeUntil = now + scaled(rng.nextLong(400, 1400));
            hudHint = "tracking";
            return;
        }
        double r = rng.nextDouble();
        switch (cookStyle) {
            case EFFICIENT -> {
                if (next != null && r < 0.78) setGaze(Gaze.NEXT, now, 500, 1600, "lining up next");
                else if (r < 0.90) setGaze(Gaze.COOK, now, 250, 700, "checking the tag");
                else pickWander(client, now, 400, 1100);
            }
            case WATCH -> {
                if (r < 0.52) setGaze(Gaze.COOK, now, 700, 2400, "watching it die");
                else if (next != null && r < 0.78) setGaze(Gaze.NEXT, now, 400, 1300, "checking next");
                else pickWander(client, now, 500, 1800);
            }
            case FIDGET -> {
                if (r < 0.22 && current != null) setGaze(Gaze.COOK, now, 300, 900, "glance at tag");
                else if (next != null && r < 0.42) setGaze(Gaze.NEXT, now, 350, 1000, "checking next");
                else pickWander(client, now, 600, 2200);
            }
        }
    }

    private void setGaze(Gaze g, long now, int minMs, int maxMs, String hint) {
        gaze = g;
        gazeUntil = now + scaled(ThreadLocalRandom.current().nextLong(minMs, maxMs + 1));
        hudHint = hint;
        wanderPoint = null;
        wanderEntity = null;
    }

    private void pickWander(MinecraftClient client, long now, int minMs, int maxMs) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        gaze = Gaze.WANDER;
        gazeUntil = now + scaled(rng.nextLong(minMs, maxMs + 1));
        wanderEntity = randomNearby(client);
        if (wanderEntity != null && rng.nextDouble() < 0.55) {
            hudHint = "looking around";
            wanderPoint = null;
            return;
        }
        wanderEntity = null;
        Vec3d eye = client.player.getEyePos();
        float yaw = client.player.getYaw() + (rng.nextFloat() * 2f - 1f) * 70f;
        float pitch = (rng.nextFloat() * 2f - 1f) * 28f;
        // occasional sky / floor glance
        double kind = rng.nextDouble();
        if (kind < 0.18) pitch = -25f - rng.nextFloat() * 20f;
        else if (kind < 0.30) pitch = 18f + rng.nextFloat() * 22f;
        Vec3d dir = lookDir(pitch, yaw);
        wanderPoint = eye.add(dir.multiply(6.0 + rng.nextDouble() * 14.0));
        hudHint = "looking around";
    }

    /** Same convention as vanilla camera: pitch/yaw in degrees → unit vector. */
    private static Vec3d lookDir(float pitch, float yaw) {
        float p = pitch * ((float) Math.PI / 180f);
        float y = -yaw * ((float) Math.PI / 180f);
        float cy = MathHelper.cos(y);
        float sy = MathHelper.sin(y);
        float cp = MathHelper.cos(p);
        float sp = MathHelper.sin(p);
        return new Vec3d(sy * cp, -sp, cy * cp);
    }

    private Vec3d resolveGaze(MinecraftClient client, LivingEntity current, LivingEntity next) {
        return switch (gaze) {
            case TARGET, COOK -> current != null
                ? aimPoint(current)
                : fallbackLook(client);
            case NEXT -> next != null ? aimPoint(next) : fallbackLook(client);
            case WANDER -> {
                if (wanderEntity != null && wanderEntity.isAlive() && !wanderEntity.isRemoved()) {
                    yield wanderEntity.getEntityPos().add(0, wanderEntity.getHeight() * 0.7, 0);
                }
                yield wanderPoint != null ? wanderPoint : fallbackLook(client);
            }
        };
    }

    private static Vec3d fallbackLook(MinecraftClient client) {
        return client.player.getEyePos().add(lookDir(client.player.getPitch(), client.player.getYaw()).multiply(8));
    }

    private boolean gazeStillValid(LivingEntity current, LivingEntity next, boolean tagged) {
        return switch (gaze) {
            case TARGET -> !tagged && current != null && current.isAlive();
            case COOK -> tagged && current != null && current.isAlive();
            case NEXT -> next != null && next.isAlive();
            case WANDER -> {
                if (wanderEntity != null) {
                    yield wanderEntity.isAlive() && !wanderEntity.isRemoved();
                }
                yield wanderPoint != null;
            }
        };
    }

    private LivingEntity randomNearby(MinecraftClient client) {
        List<LivingEntity> found = new ArrayList<>();
        for (Entity e : client.world.getEntities()) {
            if (!(e instanceof LivingEntity le) || e == client.player) continue;
            if (e instanceof PlayerEntity || e instanceof ArmorStandEntity || e instanceof DisplayEntity) continue;
            if (!le.isAlive()) continue;
            if (client.player.distanceTo(e) > 18) continue;
            found.add(le);
        }
        if (found.isEmpty()) return null;
        return found.get(ThreadLocalRandom.current().nextInt(found.size()));
    }

    private long scaled(long ms) {
        return Math.max(40, Math.round(ms * tempo));
    }

    /** Smooth 1D value noise in [-1, 1]. */
    static double valueNoise(double t) {
        int i = (int) Math.floor(t);
        double f = t - i;
        double u = f * f * (3.0 - 2.0 * f);
        return hash(i) * (1.0 - u) + hash(i + 1) * u;
    }

    private static double hash(int n) {
        n = (n << 13) ^ n;
        int v = (n * (n * n * 15731 + 789221) + 1376312589);
        return 1.0 - (double) (v & 0x7fffffff) / 1073741824.0;
    }
}

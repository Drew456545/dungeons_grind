package dev.drew.ycbotchallenge;

import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * One-shot human mouse paths. Combat publishes a look intent; this class turns
 * that into cursor deltas that vanilla {@code Mouse.updateMouse} consumes.
 * Never writes yaw/pitch.
 *
 * Ninja humanization: new intents arriving mid-path blend in with velocity
 * continuity (chaining) instead of being dropped, a continuous OU tremor rides
 * on top of every path (and idle), and flick speed rotates through agility
 * regimes so no single duration-distance law fits a session.
 */
public final class MouseDriver {
    public static final MouseDriver INSTANCE = new MouseDriver();

    private YCBotChallengeConfig cfg;
    private EventLogger logger;

    private boolean pathActive;
    private long pathStartMs;
    private long pathDurationMs;
    private float y0, p0, y1, p1, y2, p2, y3, p3;
    private float lastYaw;
    private float lastPitch;
    private boolean lastSet;
    private long lastChainAt;

    private long lastTremorMs;
    // OU tremor state (absolute offsets; increments are emitted)
    private double tremorYaw, tremorPitch;
    private double prevTremorYaw, prevTremorPitch;
    private long lastTremorAtMs;
    // agility regime
    private double regimeMult = 1.0;
    private long regimeUntil;

    public void configure(YCBotChallengeConfig cfg, EventLogger logger) {
        this.cfg = cfg;
        this.logger = logger;
    }

    public boolean isBusy() { return pathActive; }

    public void cancel() {
        pathActive = false;
        lastSet = false;
    }

    /**
     * Start a flick from the current camera to {@code (wantYaw, wantPitch)}.
     * With chaining on, a path already in flight is re-targeted smoothly
     * (velocity-continuous) instead of ignoring the new intent.
     */
    public void lookTo(MinecraftClient client, float wantYaw, float wantPitch, String reason) {
        if (client.player == null || cfg == null) return;
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        float curYaw, curPitch;
        double velYaw = 0, velPitch = 0;
        boolean chaining = false;
        if (pathActive) {
            if (!(cfg.ninja && cfg.mouseChaining)) return;
            long nowC = System.currentTimeMillis();
            if (nowC - lastChainAt < 120) return; // don't re-target every tick
            lastChainAt = nowC;
            double t = pathDurationMs <= 0 ? 1.0 : Math.min(1.0, (nowC - pathStartMs) / (double) pathDurationMs);
            double u = t * t * (3.0 - 2.0 * t);
            float[] pos = bezier(u);
            float[] dB = bezierDeriv(u);
            double easeDeriv = 6.0 * t * (1.0 - t);
            double denom = Math.max(1, pathDurationMs);
            velYaw = dB[0] * easeDeriv / denom;   // deg/ms, in eased-path space
            velPitch = dB[1] * easeDeriv / denom;
            curYaw = pos[0];
            curPitch = pos[1];
            chaining = true;
        } else {
            curYaw = client.player.getYaw();
            curPitch = client.player.getPitch();
        }

        float dy = MathHelper.wrapDegrees(wantYaw - curYaw);
        float dp = MathHelper.clamp(wantPitch, -89f, 89f) - curPitch;
        double dist = Math.sqrt(dy * dy + dp * dp);
        if (dist < 0.35) return; // already there — don't twitch

        // Occasional overshoot: land past the point, a later intent can correct.
        if (dist > 8.0 && rng.nextDouble() < 0.28) {
            double extra = 0.06 + rng.nextDouble() * 0.10;
            dy += (float) (dy * extra);
            dp += (float) (dp * extra * 0.6);
        }

        float endYaw = curYaw + dy;
        float endPitch = MathHelper.clamp(curPitch + dp, -89f, 89f);

        float nx = dist > 1e-3 ? (float) (-dp / dist) : 0f;
        float ny = dist > 1e-3 ? (float) (dy / dist) : 0f;
        float amp = (float) (dist * (0.08 + rng.nextDouble() * 0.18) * (rng.nextBoolean() ? 1 : -1));

        if (cfg.ninja && cfg.agilityRegimes) {
            long nowR = System.currentTimeMillis();
            if (nowR >= regimeUntil) {
                double[] regimes = {0.75, 1.0, 1.3};
                regimeMult = regimes[rng.nextInt(regimes.length)];
                regimeUntil = nowR + HumanTiming.logNormalMs(cfg.regimeDwellMinMs, cfg.regimeDwellMaxMs);
                if (logger != null) logger.log("aim_regime", "mult", regimeMult);
            }
        }
        double agility = MathHelper.clamp(cfg.aimAgility, 0.05, 1.0)
            * (cfg.ninja && cfg.agilityRegimes ? regimeMult : 1.0);
        // Fitts-ish: bigger flicks take longer; agility shortens them. AFK-slow is OK.
        double duration = (160.0 + 340.0 * Math.log(dist / 6.0 + 1.0) / Math.log(2)) / agility;
        duration *= 0.85 + rng.nextDouble() * 0.40;
        pathDurationMs = Math.round(MathHelper.clamp(duration, 140, 1400));

        y0 = curYaw; p0 = curPitch;
        y3 = endYaw; p3 = endPitch;
        if (chaining) {
            // velocity-continuous entry: B'(0) = 3(P1-P0)/D must equal the incoming velocity
            y1 = (float) (curYaw + velYaw * pathDurationMs / 3.0);
            p1 = (float) (curPitch + velPitch * pathDurationMs / 3.0);
        } else {
            y1 = curYaw + dy * 0.30f + nx * amp;
            p1 = curPitch + dp * 0.30f + ny * amp * 0.5f;
        }
        y2 = curYaw + dy * 0.72f + nx * amp * -0.45f;
        p2 = curPitch + dp * 0.72f + ny * amp * -0.25f;

        pathStartMs = System.currentTimeMillis();
        lastYaw = curYaw;
        lastPitch = curPitch;
        lastSet = true;
        pathActive = true;
        if (logger != null) {
            logger.log("mouse_flick",
                "reason", reason,
                "distDeg", Math.round(dist * 10.0) / 10.0,
                "durationMs", pathDurationMs,
                "chained", chaining);
        }
    }

    public void lookAtEntity(MinecraftClient client, Entity target, float heightFrac, float yawLeadDeg, String reason) {
        if (client.player == null || target == null) return;
        float[] yp = anglesTo(client, target, heightFrac, yawLeadDeg);
        lookTo(client, yp[0], yp[1], reason);
    }

    /** Remaining error from the actual (vanilla) camera to the entity aim point. */
    public static double aimErrorDeg(MinecraftClient client, Entity target, float heightFrac) {
        if (client.player == null || target == null) return 180;
        float[] yp = anglesTo(client, target, heightFrac, 0f);
        float dy = MathHelper.wrapDegrees(yp[0] - client.player.getYaw());
        float dp = yp[1] - client.player.getPitch();
        return Math.sqrt(dy * dy + dp * dp);
    }

    public static float signedYawError(MinecraftClient client, Entity target) {
        if (client.player == null || target == null) return 0f;
        Vec3d rel = target.getEntityPos().subtract(client.player.getEntityPos());
        float yawTo = (float) (Math.toDegrees(Math.atan2(rel.z, rel.x)) - 90.0);
        return MathHelper.wrapDegrees(yawTo - client.player.getYaw());
    }

    public static float[] anglesTo(MinecraftClient client, Entity target, float heightFrac, float yawLeadDeg) {
        Vec3d eye = client.player.getEyePos();
        Vec3d aim = target.getEntityPos().add(0, target.getHeight() * heightFrac, 0);
        double dx = aim.x - eye.x;
        double dy = aim.y - eye.y;
        double dz = aim.z - eye.z;
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        float wantYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0) + yawLeadDeg;
        float wantPitch = (float) -Math.toDegrees(Math.atan2(dy, distXZ));
        return new float[] { wantYaw, MathHelper.clamp(wantPitch, -89f, 89f) };
    }

    /**
     * Called from the Mouse mixin at the start of {@code updateMouse}. Returns
     * extra {@code (cursorDeltaX, cursorDeltaY)} to add, or null.
     */
    public double[] pollCursorDelta(double timeDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.mouse == null || !client.mouse.isCursorLocked()) {
            return null;
        }
        if (client.currentScreen != null) return null;

        double scale = cursorScale(client);
        if (scale <= 1e-9) return null;

        boolean ninjaTremor = cfg != null && cfg.ninja && cfg.mouseIdleTremor;
        double[] tremor = ninjaTremor ? tremorStep() : null;
        double tremorGain = pathActive ? 1.0 + Math.max(0, cfg.tremorSpeedScaling) : 1.0;

        if (pathActive) {
            long now = System.currentTimeMillis();
            double t = pathDurationMs <= 0 ? 1.0 : (now - pathStartMs) / (double) pathDurationMs;
            double dYaw, dPitch;
            if (t >= 1.0) {
                float[] end = bezier(1.0);
                dYaw = end[0] - lastYaw;
                dPitch = end[1] - lastPitch;
                pathActive = false;
                lastSet = false;
            } else {
                t = t * t * (3.0 - 2.0 * t); // smoothstep
                float[] pos = bezier(t);
                dYaw = MathHelper.wrapDegrees(pos[0] - lastYaw);
                dPitch = pos[1] - lastPitch;
                lastYaw = pos[0];
                lastPitch = pos[1];
            }
            if (tremor != null) {
                dYaw += tremor[0] * tremorGain;
                dPitch += tremor[1] * tremorGain;
            }
            return toCursor(dYaw, dPitch, scale);
        }

        // Ninja: continuous OU tremor, idle included (increments sum to ~0 over time).
        if (tremor != null) {
            return toCursor(tremor[0], tremor[1], scale);
        }

        // Legacy: sparse idle tremor — not a tracking loop.
        if (cfg != null && cfg.mouseIdleTremor && timeDelta > 0) {
            long now = System.currentTimeMillis();
            if (now - lastTremorMs > 40) {
                lastTremorMs = now;
                ThreadLocalRandom rng = ThreadLocalRandom.current();
                if (rng.nextDouble() < cfg.idleTremorChancePerSecond * timeDelta) {
                    double mag = 0.04 + rng.nextDouble() * 0.12;
                    return toCursor(
                        (rng.nextDouble() * 2 - 1) * mag,
                        (rng.nextDouble() * 2 - 1) * mag * 0.45,
                        scale);
                }
            }
        }
        return null;
    }

    /** One Ornstein-Uhlenbeck step; returns the (yaw, pitch) increment since the last call. */
    private double[] tremorStep() {
        long now = System.currentTimeMillis();
        double dt = lastTremorAtMs == 0 ? 0.02 : Math.min(0.25, (now - lastTremorAtMs) / 1000.0);
        lastTremorAtMs = now;
        if (dt <= 0) return null;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double theta = Math.max(1.0, cfg.tremorMeanReversionPerSec);
        double amp = Math.max(0.0, cfg.tremorAmplitudeDeg);
        double sigma = amp * Math.sqrt(2.0 * theta);
        double sq = Math.sqrt(dt);
        tremorYaw += -theta * tremorYaw * dt + sigma * sq * rng.nextGaussian();
        tremorPitch += -theta * tremorPitch * dt + sigma * sq * rng.nextGaussian() * 0.45;
        double dy = tremorYaw - prevTremorYaw;
        double dp = tremorPitch - prevTremorPitch;
        prevTremorYaw = tremorYaw;
        prevTremorPitch = tremorPitch;
        if (Math.abs(dy) < 1e-6 && Math.abs(dp) < 1e-6) return null;
        return new double[]{dy, dp};
    }

    private float[] bezier(double t) {
        double u = 1.0 - t;
        double b0 = u * u * u;
        double b1 = 3 * u * u * t;
        double b2 = 3 * u * t * t;
        double b3 = t * t * t;
        float y = (float) (b0 * y0 + b1 * y1 + b2 * y2 + b3 * y3);
        float p = (float) (b0 * p0 + b1 * p1 + b2 * p2 + b3 * p3);
        return new float[] { y, p };
    }

    /** dB/dt of the cubic control polygon (before duration scaling). */
    private float[] bezierDeriv(double t) {
        double u = 1.0 - t;
        float y = (float) (3 * u * u * (y1 - y0) + 6 * u * t * (y2 - y1) + 3 * t * t * (y3 - y2));
        float p = (float) (3 * u * u * (p1 - p0) + 6 * u * t * (p2 - p1) + 3 * t * t * (p3 - p2));
        return new float[] { y, p };
    }

    /** Vanilla: {@code yawDeg = cursorDeltaX * f * 0.15} where {@code f = (s*0.6+0.2)^3 * 8}. */
    static double cursorScale(MinecraftClient client) {
        double s = client.options.getMouseSensitivity().getValue();
        double d = s * 0.6 + 0.2;
        return d * d * d * 8.0 * 0.15;
    }

    private static double[] toCursor(double yawDeg, double pitchDeg, double scale) {
        return new double[] { yawDeg / scale, pitchDeg / scale };
    }
}

package dev.drew.ycbotchallenge;

import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * One-shot human mouse paths. Combat publishes a look intent; this class turns
 * that into cursor deltas that vanilla {@code Mouse.updateMouse} consumes.
 * Never writes yaw/pitch. Never servos onto a target after the path ends.
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

    private long lastTremorMs;

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
     * Start a one-shot flick from the current camera to {@code (wantYaw, wantPitch)}.
     * If a path is already playing, this is ignored (call {@link #cancel} to replace).
     */
    public void lookTo(MinecraftClient client, float wantYaw, float wantPitch, String reason) {
        if (client.player == null || cfg == null) return;
        if (pathActive) return;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        float curYaw = client.player.getYaw();
        float curPitch = client.player.getPitch();
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

        // Cubic bezier with a perpendicular bump so the path isn't a straight line.
        float nx = dist > 1e-3 ? (float) (-dp / dist) : 0f;
        float ny = dist > 1e-3 ? (float) (dy / dist) : 0f;
        float amp = (float) (dist * (0.08 + rng.nextDouble() * 0.18) * (rng.nextBoolean() ? 1 : -1));
        y0 = curYaw; p0 = curPitch;
        y3 = endYaw; p3 = endPitch;
        y1 = curYaw + dy * 0.30f + nx * amp;
        p1 = curPitch + dp * 0.30f + ny * amp * 0.5f;
        y2 = curYaw + dy * 0.72f + nx * amp * -0.45f;
        p2 = curPitch + dp * 0.72f + ny * amp * -0.25f;

        double agility = MathHelper.clamp(cfg.aimAgility, 0.05, 1.0);
        // Fitts-ish: bigger flicks take longer; agility shortens them. AFK-slow is OK.
        double duration = (160.0 + 340.0 * Math.log(dist / 6.0 + 1.0) / Math.log(2)) / agility;
        duration *= 0.85 + rng.nextDouble() * 0.40;
        pathDurationMs = Math.round(MathHelper.clamp(duration, 140, 1400));
        pathStartMs = System.currentTimeMillis();
        lastYaw = curYaw;
        lastPitch = curPitch;
        lastSet = true;
        pathActive = true;
        if (logger != null) {
            logger.log("mouse_flick",
                "reason", reason,
                "distDeg", Math.round(dist * 10.0) / 10.0,
                "durationMs", pathDurationMs);
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

        if (pathActive) {
            long now = System.currentTimeMillis();
            double t = pathDurationMs <= 0 ? 1.0 : (now - pathStartMs) / (double) pathDurationMs;
            if (t >= 1.0) {
                float[] end = bezier(1.0);
                double[] out = toCursor(end[0] - lastYaw, end[1] - lastPitch, scale);
                pathActive = false;
                lastSet = false;
                return out;
            }
            t = t * t * (3.0 - 2.0 * t); // smoothstep
            float[] pos = bezier(t);
            float dYaw = MathHelper.wrapDegrees(pos[0] - lastYaw);
            float dPitch = pos[1] - lastPitch;
            lastYaw = pos[0];
            lastPitch = pos[1];
            return toCursor(dYaw, dPitch, scale);
        }

        // Sparse idle tremor — not a tracking loop.
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

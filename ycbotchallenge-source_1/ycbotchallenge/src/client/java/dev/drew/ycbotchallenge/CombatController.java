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
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
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
    /**
     * Plate shapes on this server: "[RARE] LVL9 Mooshroom ❤2.3B", "LVL7 Donkey ❤69B" (no
     * rarity — the common case; 0.9.27 made the tag optional so the level parses for every
     * mob), "[AFKMOB] LVL9 Mooshroom ❤∞".
     */
    private static final Pattern NAMEPLATE = Pattern.compile(
        "^(?:\\[(?<rarity>[^\\]]+)\\]\\s*)?(?:\\[?(?:Level|Lvl?\\.?)\\s*(?<level>\\d+)\\]?)?\\s*(?<mob>.+?)(?:\\s*[♥❤].*)?$",
        Pattern.CASE_INSENSITIVE);

    /** Parsed plate: rarity (null when untagged), level (null when absent), mob name. */
    record Plate(String rarity, Integer level, String mob) {}

    /** Pure plate parse for one line, or null when the line is not a nameplate at all. */
    static Plate parsePlate(String line) {
        if (line == null) return null;
        Matcher m = NAMEPLATE.matcher(line.trim());
        if (!m.matches()) return null;
        String rarity = m.group("rarity");
        Integer level = null;
        if (m.group("level") != null) {
            try { level = Integer.parseInt(m.group("level")); } catch (NumberFormatException ignored) {}
        }
        String mob = m.group("mob") != null ? m.group("mob").trim() : null;
        if (mob == null || mob.isEmpty()) return null;
        return new Plate(rarity != null ? rarity.trim() : null, level, mob);
    }

    private final YCBotChallengeConfig cfg;
    private final StatsTracker stats;
    private EventLogger logger;

    private LivingEntity target = null;
    /** Connected = a click landed (boss bar for this mob appeared). The server now cooks it. */
    private boolean connected = false;
    private long tagAt = 0;
    /** When the cooking mob's bar vanished early (0 = still showing); see instantKillConfirmMs. */
    private long barGoneAt = 0;
    /** First click on the current target (0 = none yet): money landing after it credits a kill the bar never showed. */
    private long firstClickAt = 0;
    private long targetPickedAt = 0;
    /** 0.9.37: no-connect runs on one entity (the 12.000 s re-pick loop), and the no_ray evidence counter. */
    private int noConnectStreak = 0;
    private int noConnectEntityId = Integer.MIN_VALUE;
    private long lastNoRayLogAt = 0;
    private int noRayCount = 0;
    /** While the current mob cooks: the mob we'll go for next (pre-aimed so the handoff is instant). */
    private LivingEntity nextTarget = null;
    private long nextPickedAt = 0;
    /** How far we may drift from the cooking mob this kill (rolled per connect). */
    private double cookLeash = 3.0;
    public String nextTargetDesc = null;
    private long nextActionAt = 0;   // humanized reaction / idle gate
    private long breakUntil = 0;     // break scheduler: inert while now < breakUntil
    /**
     * Active-time budget until the next break; -1 = roll a fresh focus block. Counted
     * from ticks the bot actually runs, never wall clock: the 0.9.7 scheduler kept a
     * wall-clock deadline, so 49 minutes of the bot being OFF counted as "focus" and
     * every re-enable landed straight in a break the toggle could not clear.
     */
    private long focusRemainingMs = -1;
    private long lastFocusTickAt = 0;
    private int lastZoneSeq = 0;
    private long lastClickAt = 0;
    /** Set to request the client stop the bot (teleport / player radar); consumed by the main tick. */
    public String stopRequest = null;
    private Vec3d lastTickPos = null;
    private long lastRadarAt = 0;
    /** Once a teleport happens, the player radar arms for the rest of the session. */
    private boolean teleportSeen = false;
    /** An unexplained teleport waiting for a rebirth signal (0 = none); see teleportExplainGraceMs. */
    private long pendingTeleportAt = 0;
    private double pendingTeleportBlocks = 0;
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
    /** Boss-bar HP when the tag connected; with the DPS slope this predicts the whole-mob TTK. */
    private Double tagHp = null;
    /** Predicted TTK (tag HP / DPS) of the most recent cook — survives the kill for the upgrade eval. */
    public Double lastPredictedTtkMs = null;
    /** When {@link #lastPredictedTtkMs} was last refreshed (it only describes the mob being cooked). */
    public long lastPredictedAt = 0;
    private Double lastHpSeen = null;
    private long lastHpDropAt = 0;

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

    /** Nameplates never targeted (ignoreMobPatterns); entity ids already logged as ignored. */
    private final java.util.List<Pattern> ignoreRes = new java.util.ArrayList<>();
    private final java.util.Set<Integer> ignoredLogged = new java.util.HashSet<>();
    /** Entity ids ignored for the session by evidence that arrives after the pick (its boss bar) or by hand (Ctrl+toggle). */
    private final java.util.Set<Integer> ignoredIds = new java.util.HashSet<>();
    /** Entity ids already logged as another zone's mob (target_offzone). */
    private final java.util.Set<Integer> offzoneLogged = new java.util.HashSet<>();
    /** 0.9.40: distinct entities refused per plate level - the vote that can move the zone level when no bar will. */
    private final java.util.Map<Integer, java.util.Set<Integer>> offzoneVotes = new java.util.HashMap<>();
    private long offzoneVotesAt = 0;
    private long lastPlateAdoptAt = 0;
    /** 0.9.40: the last time the aim point was dropped under a nameplate stand in the ray. */
    private long lastAimDropAt = 0;
    /** Manual marks (kind + position), persisted. */
    private IgnoreStore ignoreStore;
    /** Plate entities (text displays, named armor stands) near the player, refreshed at most every 250ms. */
    private final List<Entity> plateCache = new ArrayList<>();
    private long plateCacheAt = 0;

    public CombatController(YCBotChallengeConfig cfg, StatsTracker stats) {
        this.cfg = cfg;
        this.stats = stats;
        if (cfg.ignoreMobPatterns != null) {
            for (String p : cfg.ignoreMobPatterns) {
                if (p == null || p.isBlank()) continue;
                boolean re = p.length() > 2 && p.startsWith("/") && p.endsWith("/");
                ignoreRes.add(Pattern.compile(re ? p.substring(1, p.length() - 1) : Pattern.quote(p), Pattern.CASE_INSENSITIVE));
            }
        }
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

    /** Milliseconds left on the current break (0 when not on one) — shown on the HUD. */
    public long breakRemainingMs() {
        return Math.max(0, breakUntil - System.currentTimeMillis());
    }

    /**
     * A tag has connected and the mob's boss bar is still up — the server is
     * auto-attacking it. Checked against the live bar (not the cached flag) so a
     * controller that pauses combat still sees the kill land.
     */
    public boolean isCooking() {
        return connected && target != null && stats.bossBarMatches(targetMob);
    }

    /** Start of the current cook (tag time), or -1 when not cooking. */
    public long cookStartMs() {
        return connected ? tagAt : -1;
    }

    public long cookElapsedMs() {
        return connected ? System.currentTimeMillis() - tagAt : 0;
    }

    /** Rarity tag of the current target ("RARE", "EPIC", ...), null when untagged or none. */
    public String targetRarity() {
        return targetRarity;
    }

    /** Whether the last pick passed over a rarer mob for a common one (0.9.33 stage probe); cleared by the next pick. */
    public boolean lastPickCommonFirst = false;
    private int commonFirstLoggedSeq = -1;

    // --- post-teleport settle ---
    private long settleUntil = 0;
    private String settleReason = null;
    private int settleLooksLeft = 0;
    private long nextSettleLookAt = 0;
    private boolean settlePendingRetarget = false;

    private void beginSettle(long now, String reason) {
        boolean rebirth = "rebirth".equals(reason);
        long dur = rebirth
            ? HumanTiming.logNormalMs(cfg.postRebirthSettleMinMs, Math.max(cfg.postRebirthSettleMinMs + 1, cfg.postRebirthSettleMaxMs))
            : HumanTiming.logNormalMs(cfg.postZoneSettleMinMs, Math.max(cfg.postZoneSettleMinMs + 1, cfg.postZoneSettleMaxMs));
        settleUntil = now + dur;
        settleReason = reason;
        settleLooksLeft = ThreadLocalRandom.current().nextDouble() < cfg.postTeleportLookChance ? HumanTiming.ticks(1, 2) : 0;
        nextSettleLookAt = now + HumanTiming.logNormalMs(500, 1500);
        settlePendingRetarget = true;
        if (logger != null) logger.log("settle_start", "reason", reason, "durationMs", dur, "looks", settleLooksLeft);
    }

    public boolean isSettling() {
        return System.currentTimeMillis() < settleUntil;
    }

    /** Remaining time on the current mob from the live boss-bar HP and the measured DPS, or null. */
    public Double liveEtaMs() {
        if (!connected || targetMob == null) return null;
        Double hp = stats.currentHpFor(targetMob);
        if (hp == null || currentDps == null || currentDps <= 0) return null;
        return hp / currentDps * 1000.0;
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
        barGoneAt = 0;
        firstClickAt = 0;
        prevOct = 0;
        octStaggerTicks = 0;
        lookIssued = false;
        wantsUpgradeWindow = false;
        currentHp = null;
        currentDps = null;
        currentEtaMs = null;
        ghosts.clear();
        pendingTeleportAt = 0;
        motion.clear();
        radarMotion.clear();
        lastTickPos = null;
        tagHp = null;
        lastHpSeen = null;
        lastHpDropAt = 0;
        // A toggle, stop or captcha is a human intervention: the "break" is over and a
        // fresh focus block starts on the next enable (breaksResetOnToggle).
        if (cfg.breaksResetOnToggle) {
            breakUntil = 0;
            focusRemainingMs = -1;
            lastFocusTickAt = 0;
        }
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
            offzoneVotes.clear();
            retargetForZone(client);
        }

        // Stop protocol: teleport = pulled for a check; another player in range = staff
        // spectating (this gamemode is solo while grinding, so anyone else is a red flag).
        if (cfg.stopProtocolEnabled && pendingTeleportAt != 0) {
            // Holding still after an unexplained teleport. The server's auto-rebirth
            // teleports us with no command of ours to arm it (17:03 log: 28.23Q → teleport
            // 32 blocks → money 0 at +0.4s → counter 4 at +4.4s, and the stop protocol had
            // already fired). A rebirth signal inside the grace explains it; nothing does not.
            releaseKeys(client);
            boolean armed = stats.isTeleportExpected(now);
            boolean rebirthSignal = stats.lastRebirthAt != 0 && stats.lastRebirthAt >= pendingTeleportAt - 3000;
            if (armed || rebirthSignal) {
                String why = armed ? stats.consumeTeleportReason() : "rebirth";
                stats.clearTeleportExpected();
                if (logger != null) {
                    logger.log("teleport_explained", "blocks", Math.round(pendingTeleportBlocks), "reason", why,
                        "afterMs", now - pendingTeleportAt, "via", armed ? "armed" : "rebirth-signal");
                }
                pendingTeleportAt = 0;
                stats.onZoneAdvance("teleport");
                lastPredictedTtkMs = null;
                beginSettle(now, why);
                lastTickPos = null;
                return;
            }
            if (now - pendingTeleportAt >= cfg.teleportExplainGraceMs) {
                pendingTeleportAt = 0;
                teleportSeen = true; // unexpected teleport: arms the player radar for the rest of the session
                stopRequest = "teleport (" + Math.round(pendingTeleportBlocks) + " blocks)";
                return;
            }
            lastTickPos = null;
            return;
        }
        if (cfg.stopProtocolEnabled) {
            Vec3d pos = client.player.getEntityPos();
            if (lastTickPos != null) {
                double jumped = pos.distanceTo(lastTickPos);
                if (jumped > cfg.teleportThresholdBlocks) {
                    if (stats.isTeleportExpected(now)) {
                        // Our own /zone max advance — not a staff pull, and it must NOT
                        // arm the radar (zones legitimately contain stationary NPCs).
                        stats.clearTeleportExpected();
                        String why = stats.consumeTeleportReason();
                        if (logger != null) logger.log("zone_teleport", "blocks", Math.round(jumped), "reason", why);
                        stats.onZoneAdvance("teleport");
                        lastPredictedTtkMs = null;
                        beginSettle(now, why);
                        lastTickPos = null;
                        releaseKeys(client);
                        return;
                    }
                    if (cfg.teleportExplainGraceMs > 0) {
                        pendingTeleportAt = now;
                        pendingTeleportBlocks = jumped;
                        lastTickPos = null;
                        releaseKeys(client);
                        if (logger != null) logger.log("teleport_pending", "blocks", Math.round(jumped), "graceMs", cfg.teleportExplainGraceMs);
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

        // Post-teleport settle: after a rebirth or zone advance a person stands for a
        // few seconds and looks around before picking a mob. 0.9.9 tagged a stale
        // Donkey one tick after the rebirth teleport, before the new zone had loaded.
        if (now < settleUntil) {
            releaseKeys(client);
            if (settleLooksLeft > 0 && now >= nextSettleLookAt && !MouseDriver.INSTANCE.isBusy()) {
                float yaw = client.player.getYaw() + (float) ((rng.nextDouble() * 2 - 1) * 40.0);
                float pitch = MathHelper.clamp(client.player.getPitch() + (float) ((rng.nextDouble() * 2 - 1) * 8.0), -30f, 40f);
                MouseDriver.INSTANCE.lookTo(client, yaw, pitch, "settle-look");
                settleLooksLeft--;
                nextSettleLookAt = now + HumanTiming.logNormalMs(700, 1800);
            }
            return;
        }
        if (settlePendingRetarget) {
            settlePendingRetarget = false;
            retargetForZone(client);
            if (logger != null) logger.log("settle_end", "reason", settleReason);
        }

        // break scheduler: human-length breaks between focus blocks
        if (cfg.ninja && cfg.breaksEnabled) {
            if (now < breakUntil) { releaseKeys(client); return; }
            if (focusRemainingMs < 0) {
                focusRemainingMs = focusMs();
                if (logger != null) logger.log("focus_start", "focusMs", focusRemainingMs);
            }
            // Only time the bot actually runs counts as focus (gaps over 1s = it was off).
            long dt = lastFocusTickAt == 0 ? 0 : Math.min(1000, now - lastFocusTickAt);
            lastFocusTickAt = now;
            focusRemainingMs -= dt;
            if (focusRemainingMs <= 0) {
                // Bimodal: mostly a short stretch, sometimes a real walk-away — never always 1–4 min.
                String kind = Economy.breakKind(rng.nextDouble(), cfg.breakShortChance);
                long dur = "short".equals(kind)
                    ? HumanTiming.logNormalMs(cfg.breakShortMinMs, Math.max(cfg.breakShortMinMs + 1, cfg.breakShortMaxMs))
                    : HumanTiming.logNormalMs(cfg.breakLongMinutesMin * 60_000,
                        Math.max(cfg.breakLongMinutesMin * 60_000 + 1, cfg.breakLongMinutesMax * 60_000));
                breakUntil = now + dur;
                focusRemainingMs = focusMs();
                lastFocusTickAt = 0;
                if (logger != null) logger.log("break_start", "kind", kind, "durationMs", dur, "nextFocusMs", focusRemainingMs);
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
            // Instant kill (0.9.21): the bar lived a tick and the entity is still in its
            // death animation. Wait for the entity to vanish or the money to land before
            // calling it a missed tag; the TTK is the bar's lifetime.
            if (barGoneAt == 0) barGoneAt = now;
            String verdict = Economy.vanishVerdict(barGoneAt, tagAt, now, entityGone, stats.lastMoneyUpAt, cfg.instantKillConfirmMs);
            if ("kill".equals(verdict)) {
                long ttk = Math.max(1, barGoneAt - tagAt);
                kills++;
                stats.recordKill();
                stats.recordKillDuration(ttk, targetRarity);
                wantsUpgradeWindow = true;
                if (logger != null) {
                    logger.log("kill",
                        "mob", targetMob, "rarity", targetRarity, "level", targetLevel,
                        "timeToKillMs", ttk, "kills", kills, "via", "instant",
                        "confirm", entityGone ? "entity" : "money", "confirmMs", now - barGoneAt,
                        "clicks", clicksThisTarget);
                }
                target = null;
                connected = false;
                clicksThisTarget = 0;
                barGoneAt = 0;
                firstClickAt = 0;
                lookIssued = false;
                nextTarget = null;
                nextTargetDesc = null;
                nextActionAt = now + HumanTiming.logNormalMs(cfg.reactionDelayMinMs, cfg.reactionDelayMaxMs);
                return;
            }
            if ("wait".equals(verdict)) {
                releaseKeys(client);
                return;
            }
            // "retag": the re-tag safety block below drops the connect and clicks again.
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
            } else if (firstClickAt > 0 && stats.lastMoneyUpAt > firstClickAt) {
                // The bar never rendered, but the mob is gone and the money landed after
                // our first click: a kill the boss bar was too quick to show.
                kills++;
                stats.recordKill();
                stats.recordKillDuration(Math.max(1, now - firstClickAt), targetRarity);
                wantsUpgradeWindow = true;
                if (logger != null) {
                    logger.log("kill",
                        "mob", targetMob, "rarity", targetRarity, "level", targetLevel,
                        "timeToKillMs", now - firstClickAt, "kills", kills, "via", "death+money",
                        "clicks", clicksThisTarget);
                }
            }
            barGoneAt = 0;
            firstClickAt = 0;
            target = null;
            connected = false;
            clicksThisTarget = 0;
            lookIssued = false;
            nextTarget = null;
            nextTargetDesc = null;
            nextActionAt = now + HumanTiming.logNormalMs(cfg.reactionDelayMinMs, cfg.reactionDelayMaxMs);
            return;
        }

        // connected mob that never dies = client-side ghost or unkillable — abandon it.
        // A mob whose boss-bar HP is still dropping is neither: the 90s Goat in the logs
        // was abandoned 1s before it died, forfeiting the kill credit and its 6.48B.
        boolean hpStalled = lastHpDropAt == 0 || now - lastHpDropAt > cfg.cookStallMs;
        if (target != null && connected && now - tagAt > cfg.maxCookMs && hpStalled) {
            if (logger != null) {
                logger.log("target_abandoned", "reason", "cook-timeout",
                    "mob", targetMob, "rarity", targetRarity, "afterMs", now - tagAt,
                    "sinceHpDropMs", lastHpDropAt == 0 ? null : now - lastHpDropAt);
            }
            target = null;
            connected = false;
            clicksThisTarget = 0;
            lookIssued = false;
        }

        // stale target we never managed to connect on (0.9.37: a knob, logged, and never the
        // same mob again after noConnectIgnoreAfter runs - the 05:55 log re-picked one Horse
        // six times at exactly 12.000 s each, 72 s of clicks, until Drew toggled the bot).
        if (target != null && !connected && now - targetPickedAt > Math.max(500, cfg.noConnectTimeoutMs)) {
            int id = target.getId();
            noConnectStreak = id == noConnectEntityId ? noConnectStreak + 1 : 1;
            noConnectEntityId = id;
            boolean ignore = noConnectStreak >= Math.max(1, cfg.noConnectIgnoreAfter);
            if (ignore) ignoredIds.add(id);
            if (logger != null) {
                logger.log("target_abandoned", "reason", "no-connect", "mob", targetMob, "rarity", targetRarity,
                    "level", targetLevel, "afterMs", now - targetPickedAt, "clicks", clicksThisTarget,
                    "streak", noConnectStreak, "ignored", ignore, "reach", Math.round(effectiveReach() * 100.0) / 100.0,
                    "dist", client.player != null ? Math.round(client.player.distanceTo(target) * 100.0) / 100.0 : null,
                    "aimBusy", MouseDriver.INSTANCE.isBusy());
            }
            // Stand closer next time: reach is vanilla's exact range with no margin.
            targetReach = Math.max(1.6, effectiveReach() - 0.5);
            target = null;
            clicksThisTarget = 0;
            lookIssued = false;
            MouseDriver.INSTANCE.cancel();
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
            barGoneAt = 0;
            firstClickAt = 0;
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
            // Backstop (0.9.26): the bar the hit raised names the mob for us. The AFK
            // mob's reads "[AFKMOB] LVL9 Mooshroom" — drop it for the session instead of
            // cooking an infinite-HP mob until the cook timeout.
            if (cfg.ignoreByBossBar && !ignoreRes.isEmpty()) {
                List<String> titles = stats.bossBarTitlesFor(targetMob);
                if (Economy.bossBarIgnored(titles, ignoreRes)) {
                    ignoredIds.add(target.getId());
                    if (logger != null) {
                        logger.log("target_ignored", "via", "bossbar", "nameplate", String.join(" | ", titles),
                            "entityId", target.getId(), "mob", targetMob);
                    }
                    target = null;
                    clicksThisTarget = 0;
                    lookIssued = false;
                    firstClickAt = 0;
                    nextActionAt = now + HumanTiming.logNormalMs(cfg.reactionDelayMinMs, cfg.reactionDelayMaxMs);
                    return;
                }
            }
            connected = true;
            tagAt = now;
            barGoneAt = 0;
            tagHp = stats.currentHpFor(targetMob);
            lastHpSeen = tagHp;
            lastHpDropAt = now;
            nextGlanceAt = 0;
            glanceOut = false;
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
        // 0.9.37: on the way in only, and only while roughly facing the mob - standing inside
        // reach with the camera 65 degrees off it was 72 s of visible spam.
        if (cfg.approachClickCpsMax > 0 && dist <= cfg.approachClickMaxDist
            && Economy.approachClickAllowed(dist, effectiveReach(),
                MouseDriver.aimErrorDeg(client, target, aimHeightFrac), cfg.approachClickMaxAimDeg)
            && now - lastClickAt >= approachIntervalMs()) {
            pressAttack(client);
            lastClickAt = now;
            clicksThisTarget++; // a whiff counts; a lucky early land is how connects happen
            if (firstClickAt == 0) firstClickAt = now;
        }

        if (dist > effectiveReach()) {
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
        // 0.9.37: the tap is vanilla's own raycast, so the crosshair must actually be on the
        // mob - the aim-point error can read 0.4 degrees while the ray hits a text display,
        // the ground or air (a Goat: 20 clicks in 31 s with zero misclick rows). When the
        // aim is in tolerance and the ray disagrees, no_ray says what it hit instead.
        boolean rayOk = client.crosshairTarget instanceof EntityHitResult ehr && ehr.getEntity() == target;
        if (aimErr <= tapTol && !rayOk && !MouseDriver.INSTANCE.isBusy()) {
            noRayCount++;
            if (logger != null && now - lastNoRayLogAt > 5_000) {
                lastNoRayLogAt = now;
                HitResult hr = client.crosshairTarget;
                String hit = hr == null ? "none"
                    : hr.getType() == HitResult.Type.ENTITY ? "entity:" + typeName(((EntityHitResult) hr).getEntity())
                    : hr.getType() == HitResult.Type.BLOCK ? "block" : "miss";
                logger.log("no_ray", "mob", targetMob, "aimErr", Math.round(aimErr * 10.0) / 10.0, "hit", hit,
                    "dist", Math.round(dist * 100.0) / 100.0, "reach", Math.round(effectiveReach() * 100.0) / 100.0,
                    "count", noRayCount, "clicks", clicksThisTarget);
            }
            // 0.9.40: the mob's nameplate stand sits in the ray when the aim point is high on
            // the body (no_ray hit=entity:Armor Stand at aimErr 0.0, 54 and 62 times in the
            // 03:19 log): drop the aim a step and look again, never under the floor.
            HitResult hr2 = client.crosshairTarget;
            Entity rayEntity = hr2 instanceof EntityHitResult eh2 ? eh2.getEntity() : null;
            if ((rayEntity instanceof ArmorStandEntity || rayEntity instanceof DisplayEntity) && now - lastAimDropAt > 700) {
                double from = aimHeightFrac;
                aimHeightFrac = (float) Economy.loweredAim(aimHeightFrac, 0.15, 0.2);
                lastAimDropAt = now;
                lookIssued = false;
                if (logger != null && aimHeightFrac < from) {
                    logger.log("aim_lowered", "from", Math.round(from * 100.0) / 100.0, "to", Math.round(aimHeightFrac * 100.0) / 100.0,
                        "hit", typeName(rayEntity), "mob", targetMob);
                }
            }
        }
        if (aimErr <= tapTol
            && rayOk
            && !MouseDriver.INSTANCE.isBusy()
            && now - lastClickAt >= clickIntervalMs()
            && vanillaAttackReady(client)) {
            pressAttack(client);
            lastClickAt = now;
            clicksThisTarget++;
            if (firstClickAt == 0) firstClickAt = now;
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
        // Whole-mob TTK prediction (HP at tag / DPS): the zone gate reads it a couple of
        // seconds into the first mob of a new stage instead of waiting for three kills.
        if (tagHp != null && currentDps != null && currentDps > 0) {
            lastPredictedTtkMs = tagHp / currentDps * 1000.0;
            lastPredictedAt = now;
        }
        if (currentHp != null) {
            if (lastHpSeen == null || currentHp < lastHpSeen - 1e-9) lastHpDropAt = now;
            lastHpSeen = currentHp;
        }

        if (trackStyle == TrackStyle.WATCH || trackStyle == TrackStyle.HESITATE) tickGlance(client, now);

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
                            "etaMs", currentEtaMs, "via", handoffDue ? "eta" : "fallback",
                            "commonFirst", lastPickCommonFirst ? true : null);
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

    // --- idle glances during long cooks ---
    private long nextGlanceAt = 0;
    private boolean glanceOut = false;
    private long glanceBackAt = 0;

    /**
     * A person standing through a 30–120s kill looks around now and then: a small
     * yaw/pitch glance, then back to the mob. Never during handoffs (a next target
     * is being pre-aimed) and only once the cook has run a while.
     */
    private void tickGlance(MinecraftClient client, long now) {
        if (nextTarget != null || target == null) return;
        if (now - tagAt < cfg.cookGlanceAfterMs) return;
        if (MouseDriver.INSTANCE.isBusy()) return;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        if (glanceOut) {
            if (now < glanceBackAt) return;
            lookIssued = false;
            MouseDriver.INSTANCE.lookAtEntity(client, target, aimHeightFrac, 0f, "glance-back");
            lookIssued = true;
            lookEntityId = target.getId();
            glanceOut = false;
            nextGlanceAt = now + HumanTiming.logNormalMs(cfg.cookGlanceMinMs, Math.max(cfg.cookGlanceMinMs + 1, cfg.cookGlanceMaxMs));
            return;
        }
        if (nextGlanceAt == 0) {
            nextGlanceAt = now + HumanTiming.logNormalMs(cfg.cookGlanceMinMs, Math.max(cfg.cookGlanceMinMs + 1, cfg.cookGlanceMaxMs));
            return;
        }
        if (now < nextGlanceAt) return;
        float sign = rng.nextBoolean() ? 1f : -1f;
        float yaw = client.player.getYaw() + sign * (float) (5.0 + rng.nextDouble() * 15.0);
        float pitch = MathHelper.clamp(client.player.getPitch() + (float) ((rng.nextDouble() * 2 - 1) * (3.0 + rng.nextDouble() * 5.0)), -30f, 40f);
        MouseDriver.INSTANCE.lookTo(client, yaw, pitch, "glance");
        glanceOut = true;
        glanceBackAt = now + HumanTiming.logNormalMs(600, 1500);
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
        // Occasionally we hold W a tick too long and overshoot, as people do.
        if (dist - effectiveReach() <= coastDistance(client)) {
            if (overshootTicks > 0) {
                overshootTicks--;
            } else {
                releaseKeys(client);
                return;
            }
        }
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
        double toGo = dist - effectiveReach();
        boolean wantSprint = allowSprint && cfg.sprint && forward && aligned && toGo > cfg.sprintMinDistance;
        tapSprint(client, wantSprint);
        boolean hopping = allowSprint && cfg.sprintJump && client.player.isSprinting() && aligned && toGo > cfg.sprintJumpMinDistance;
        client.options.jumpKey.setPressed(client.player.horizontalCollision || hopping);
    }

    /** Sprint key handling for both "Hold" and "Toggle" modes; shared with the companion walk (0.9.28). */
    void tapSprint(MinecraftClient client, boolean wantSprint) {
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
    /** One real attack-key edge; package-private since 0.9.38 (the boss module taps the same way). */
    void pressAttack(MinecraftClient client) {
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
    boolean vanillaAttackReady(MinecraftClient client) {
        if (!cfg.respectVanillaAttackCooldown) return true;
        try {
            return client.player.getAttackCooldownProgress(0.0f) >= 1.0f;
        } catch (Throwable t) {
            return true;
        }
    }

    /** Per-target reach: people do not stop at exactly the same distance every time. */
    private double targetReach = -1;
    private int overshootTicks = 0;

    private double effectiveReach() {
        return targetReach > 0 ? targetReach : cfg.reach;
    }

    private void rollAimPoint(MinecraftClient client) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        aimHeightFrac = (float) (0.42 + rng.nextDouble() * 0.32);
        double jitter = MathHelper.clamp(cfg.reachJitterPct, 0.0, 0.9);
        targetReach = cfg.reach * (1.0 - rng.nextDouble() * jitter);
        overshootTicks = rng.nextDouble() < cfg.overshootChance ? 1 : 0;
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
            double t = MathHelper.clamp((distXZ - effectiveReach()) / 8.0, 0.0, 1.0);
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
        // Far out, a person tolerates a lot of drift and fixes the aim near arrival —
        // the 0.9.x fixed 3° threshold produced 2–4 corrections at 300ms spacing per approach.
        double threshold = Economy.reacquireThresholdDeg(cfg.lookReacquireDeg, client.player.distanceTo(e),
            effectiveReach(), cfg.reacquireFarMult, cfg.reacquireFarBlocks, cfg.reacquireFinalBlocks);
        if (err > threshold) {
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
        if (ignoredIds.contains(e.getId())) return false;
        String why = ignoreReason(client, le);
        if (why != null) {
            if (ignoredLogged.add(e.getId()) && logger != null) {
                logger.log("target_ignored", "via", why, "nameplate", plateSummary(client, le),
                    "entityId", e.getId(), "mob", typeName(le));
            }
            if (ignoredLogged.size() > 4096) ignoredLogged.clear();
            return false;
        }
        // Stay in your zone (0.9.27): the plate carries the stage ("LVL7 Donkey"); a mob whose
        // level differs from the boss-bar-confirmed zone level is a neighbour's, however
        // close it stands. 20:35 log: a Chicken picked in zone 7 right after a respawn
        // broadcast, and a stray neighbour species in every zone of the session.
        if (cfg.targetZoneLevelOnly) {
            Integer zoneLevel = stats.confirmedZoneLevel();
            Integer plateLevel = plateLevel(client, le);
            if (!Economy.sameZoneLevel(plateLevel, zoneLevel)) {
                if (offzoneLogged.add(e.getId()) && logger != null) {
                    logger.log("target_offzone", "mob", typeName(le), "plateLevel", plateLevel, "zoneLevel", zoneLevel,
                        "entityId", e.getId());
                }
                if (offzoneLogged.size() > 4096) offzoneLogged.clear();
                if (plateLevel != null) {
                    long nowV = System.currentTimeMillis();
                    if (nowV - offzoneVotesAt > 15_000) { offzoneVotes.clear(); offzoneVotesAt = nowV; }
                    offzoneVotes.computeIfAbsent(plateLevel, k -> new java.util.HashSet<>()).add(e.getId());
                }
                return false;
            }
        }
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
            // 0.9.40: nothing in sight is ours, but the plates agree on a level - adopt it.
            java.util.Map<Integer, Integer> counts2 = new java.util.HashMap<>();
            for (java.util.Map.Entry<Integer, java.util.Set<Integer>> v : offzoneVotes.entrySet()) counts2.put(v.getKey(), v.getValue().size());
            Integer adopt = Economy.plateMajority(counts2, stats.confirmedZoneLevel(), cfg.plateMajorityMin);
            long nowMs = System.currentTimeMillis();
            if (adopt != null && nowMs - lastPlateAdoptAt > 10_000) {
                lastPlateAdoptAt = nowMs;
                int voters = counts2.getOrDefault(adopt, 0);
                offzoneVotes.clear();
                offzoneLogged.clear();
                stats.adoptZoneLevel(adopt, "plates", voters);
            }
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
        int stageKillsNow = stats.stageKills();
        boolean sawRarity = false;
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
            // rarer mobs pay more: worth walking rarityBonusBlocks further for — except for the
            // first kills of a fresh stage, which measure it (0.9.33: a common mob first).
            String rarity = parseRarity(le);
            if (rarity != null) sawRarity = true;
            score += Economy.rarityScoreAdjust(rarity, rarityBonus(rarity), stageKillsNow,
                cfg.stageProbeCommonKills, cfg.stageProbeRarityPenaltyBlocks);
            if (cfg.targetCostJitter > 0) {
                score *= 1.0 + (rng.nextDouble() * 2 - 1) * cfg.targetCostJitter;  // vary the lap
            }
            if (score < bestScore) { best = le; bestScore = score; }
        }
        lastPickCommonFirst = best != null && stageKillsNow < cfg.stageProbeCommonKills && sawRarity
            && parseRarity(best) == null;
        if (lastPickCommonFirst && logger != null && commonFirstLoggedSeq != stats.zoneChangeSeq()) {
            commonFirstLoggedSeq = stats.zoneChangeSeq();
            logger.log("target_common_first", "stageKills", stageKillsNow, "mob", describe(best),
                "probeKills", cfg.stageProbeCommonKills);
        }
        // Ninja: occasionally pick a random in-range mob instead of the optimal one.
        if (cfg.ninja && best != null && candidates.size() > 1 && rng.nextDouble() < cfg.wrongTargetChance) {
            LivingEntity oops = candidates.get(rng.nextInt(candidates.size()));
            if (logger != null) logger.log("target_mispick", "mob", describe(oops));
            return oops;
        }
        return best;
    }

    private String parseRarity(LivingEntity e) {
        Plate p = parsePlate(plateName(MinecraftClient.getInstance(), e));
        return p != null && p.rarity() != null ? p.rarity().toUpperCase(java.util.Locale.ROOT) : null;
    }

    /** The stage printed on the mob's plate ("LVL7 Donkey" → 7), or null. */
    private Integer plateLevel(MinecraftClient client, LivingEntity e) {
        Plate p = parsePlate(plateName(client, e));
        return p != null ? p.level() : null;
    }

    private double rarityBonus(String rarity) {
        if (rarity == null || cfg.rarityBonusBlocks == null) return 0;
        for (Map.Entry<String, Double> en : cfg.rarityBonusBlocks.entrySet()) {
            if (en.getKey().equalsIgnoreCase(rarity) && en.getValue() != null) return en.getValue();
        }
        return 0;
    }

    private String describe(LivingEntity e) {
        String plate = plateName(MinecraftClient.getInstance(), e);
        if (plate == null) return typeName(e);
        Plate p = parsePlate(plate);
        if (p == null) return plate;
        return (p.rarity() != null ? "[" + p.rarity() + "] " : "") + p.mob();
    }

    private static String typeName(Entity e) {
        return e.getType().getName().getString();
    }

    /**
     * The mob's plate text: its entity name when it has one, else the first hologram
     * line shaped like a nameplate ("[RARE] LVL9 Mooshroom ❤2.3B"), else the first line.
     */
    private String plateName(MinecraftClient client, LivingEntity e) {
        Text custom = e.getCustomName();
        if (custom != null) return custom.getString();
        List<String> lines = plateLines(client, e);
        for (String l : lines) {
            Plate p = parsePlate(l);
            if (p != null && (p.level() != null || p.rarity() != null)) return l;
        }
        // 0.9.37: the fallback refuses the server's floating damage numbers ("✧293.89QQ✧
        // Critical", "+4.77T Money") that share the mob's box - a name no boss bar matches.
        String first = lines.isEmpty() ? null : lines.get(0);
        return Economy.hologramNameUsable(first) ? first : null;
    }

    /** Every plate line, joined, for logs and notices; null when the mob has none. */
    private String plateSummary(MinecraftClient client, LivingEntity e) {
        Text custom = e.getCustomName();
        List<String> lines = plateLines(client, e);
        if (custom != null) lines.add(0, custom.getString());
        return lines.isEmpty() ? null : String.join(" | ", lines);
    }

    /**
     * Nameplate lines of a mob on this server (0.9.26). EnchantedMC does not name the
     * mob entity: the plate is a text display riding the mob or floating just above it
     * (the AFK mob carries three: "⟡332.12B⟡", "[AFKMOB] LVL9 Mooshroom ❤∞", "RIGHT CLICK
     * TO UPGRADE"). Passengers first; else every plate entity within
     * nameplateHologramRadiusBlocks horizontally and 3.5 blocks above.
     */
    private List<String> plateLines(MinecraftClient client, LivingEntity e) {
        List<String> lines = new ArrayList<>();
        for (Entity p : e.getPassengerList()) addPlateText(p, lines);
        if (!lines.isEmpty()) return lines;
        refreshPlateCache(client);
        Vec3d pos = e.getEntityPos();
        for (Entity p : plateCache) {
            if (p.isRemoved()) continue;
            Vec3d pp = p.getEntityPos();
            if (!Economy.hologramBelongs(pp.x - pos.x, pp.z - pos.z, pp.y - pos.y, cfg.nameplateHologramRadiusBlocks)) continue;
            addPlateText(p, lines);
        }
        return lines;
    }

    private void refreshPlateCache(MinecraftClient client) {
        long now = System.currentTimeMillis();
        if (now - plateCacheAt < 250 || client == null || client.world == null || client.player == null) return;
        plateCacheAt = now;
        plateCache.clear();
        double reach = cfg.targetRange * 1.5 + 4;
        for (Entity p : client.world.getEntities()) {
            if (!(p instanceof DisplayEntity.TextDisplayEntity) && !(p instanceof ArmorStandEntity)) continue;
            if (client.player.distanceTo(p) > reach) continue;
            plateCache.add(p);
        }
    }

    /** Plate entities (text displays, named armor stands) within {@code radius} of the player (0.9.28: the companion egg's hologram). */
    public List<Entity> nearbyPlates(MinecraftClient client, double radius) {
        List<Entity> out = new ArrayList<>();
        if (client == null || client.world == null || client.player == null) return out;
        for (Entity p : client.world.getEntities()) {
            if (!(p instanceof DisplayEntity.TextDisplayEntity) && !(p instanceof ArmorStandEntity)) continue;
            if (client.player.distanceTo(p) > radius) continue;
            out.add(p);
        }
        return out;
    }

    /** A plate entity's text lines (display text or armor-stand name), trimmed; empty when it has none. */
    static List<String> plateTextLines(Entity p) {
        List<String> out = new ArrayList<>();
        addPlateText(p, out);
        return out;
    }

    private static void addPlateText(Entity p, List<String> out) {
        String text = null;
        if (p instanceof DisplayEntity.TextDisplayEntity td) {
            DisplayEntity.TextDisplayEntity.Data d = td.getData();
            if (d != null && d.text() != null) text = d.text().getString();
        } else if (p instanceof ArmorStandEntity as) {
            Text n = as.getCustomName();
            if (n != null) text = n.getString();
        }
        if (text == null) return;
        for (String line : text.split("\\n")) {
            String t = line.trim();
            if (!t.isEmpty()) out.add(t);
        }
    }

    /** Why a mob is untargetable ("name", "hologram", "manual"), or null when it is fair game. */
    private String ignoreReason(MinecraftClient client, LivingEntity le) {
        if (!ignoreRes.isEmpty()) {
            Text custom = le.getCustomName();
            if (custom != null && Economy.ignoredMob(custom.getString(), ignoreRes)) return "name";
            if (Economy.ignoredByLines(plateLines(client, le), ignoreRes)) return "hologram";
        }
        if (ignoreStore != null && ignoreStore.size() > 0) {
            Vec3d p = le.getEntityPos();
            if (ignoreStore.findNear(typeName(le), p.x, p.y, p.z, cfg.manualIgnoreRadiusBlocks) != null) return "manual";
        }
        return null;
    }

    public void setIgnoreStore(IgnoreStore store) { this.ignoreStore = store; }

    /**
     * Ctrl + toggle key (0.9.26): ignore the mob under the crosshair — or, when it is
     * already marked, stop ignoring it. The mark is the mob's kind and position, so it
     * outlives the entity id (each zone's AFK mob stands on the same block forever).
     * Returns the chat notice, or null when nothing is being looked at.
     */
    public String toggleManualIgnore(MinecraftClient client) {
        LivingEntity le = lookedAtMob(client);
        if (le == null) return null;
        Vec3d p = le.getEntityPos();
        String type = typeName(le);
        String label = plateSummary(client, le);
        String shown = label != null ? label : type;
        String at = Math.round(p.x) + ", " + Math.round(p.y) + ", " + Math.round(p.z);
        IgnoreStore.Mark old = ignoreStore != null
            ? ignoreStore.removeNear(type, p.x, p.y, p.z, cfg.manualIgnoreRadiusBlocks) : null;
        if (old != null) {
            ignoredIds.remove(le.getId());
            ignoredLogged.remove(le.getId());
            if (logger != null) {
                logger.log("target_unignored", "via", "manual", "nameplate", label, "entityId", le.getId(),
                    "mob", type, "x", Math.round(p.x), "y", Math.round(p.y), "z", Math.round(p.z));
            }
            return "no longer ignoring " + shown + " (" + type + " at " + at + ").";
        }
        if (ignoreStore != null) {
            IgnoreStore.Mark m = new IgnoreStore.Mark();
            m.type = type;
            m.x = p.x;
            m.y = p.y;
            m.z = p.z;
            m.label = label;
            m.at = System.currentTimeMillis();
            ignoreStore.add(m);
        }
        ignoredIds.add(le.getId());
        if (target == le) {
            target = null;
            connected = false;
            clicksThisTarget = 0;
            lookIssued = false;
            firstClickAt = 0;
        }
        if (nextTarget == le) {
            nextTarget = null;
            nextTargetDesc = null;
        }
        if (logger != null) {
            logger.log("target_ignored", "via", "manual", "nameplate", label, "entityId", le.getId(),
                "mob", type, "x", Math.round(p.x), "y", Math.round(p.y), "z", Math.round(p.z));
        }
        return "ignoring " + shown + " (" + type + " at " + at + "). Ctrl+toggle on it again to undo.";
    }

    /** The mob under the crosshair: the game's own pick within reach, else the nearest mob within manualIgnoreAimDeg of the look line. */
    private LivingEntity lookedAtMob(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) return null;
        if (client.targetedEntity instanceof LivingEntity hit && !(hit instanceof PlayerEntity)
            && !(hit instanceof ArmorStandEntity)) {
            return hit;
        }
        Vec3d eye = client.player.getEyePos();
        Vec3d look = client.player.getRotationVec(1.0f);
        LivingEntity best = null;
        double bestAng = Math.max(0.5, cfg.manualIgnoreAimDeg);
        for (Entity e : client.world.getEntities()) {
            if (!(e instanceof LivingEntity le) || e == client.player || e instanceof PlayerEntity) continue;
            if (e instanceof ArmorStandEntity || e instanceof DisplayEntity) continue;
            Vec3d c = e.getEntityPos().add(0, e.getHeight() * 0.5, 0);
            Vec3d rel = c.subtract(eye);
            double dist = rel.length();
            if (dist < 0.3 || dist > 12) continue;
            double cos = rel.normalize().dotProduct(look);
            double ang = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, cos))));
            if (ang < bestAng) {
                bestAng = ang;
                best = le;
            }
        }
        return best;
    }

    private void readNameplate(LivingEntity e) {
        targetRarity = null;
        targetLevel = null;
        targetMob = typeName(e);
        String plate = plateName(MinecraftClient.getInstance(), e);
        if (plate != null) {
            Plate p = parsePlate(plate);
            if (p != null) {
                targetRarity = p.rarity();
                targetLevel = p.level();
                targetMob = p.mob();
            } else if (e.getCustomName() != null) {
                targetMob = plate;
            }
        }
        lastTargetDesc = (targetRarity != null ? "[" + targetRarity + "] " : "") + targetMob;
    }
}

package dev.drew.ycbotchallenge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ClientBossBar;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * The grind loop, matched to the server's mechanics: one hit TAGS a mob,
 * a boss health bar appears while the server auto-attacks it, and the cook
 * is over when that bar expires — then (and only then) we pick the next mob.
 * One fight at a time. Entity despawn is not the signal; corpses linger.
 */
public class CombatController {
    private static final Pattern NAMEPLATE = Pattern.compile(
        "^\\[(?<rarity>[^\\]]+)\\]\\s*(?:\\[?(?:Level|Lvl?\\.?)\\s*(?<level>\\d+)\\]?)?\\s*(?<mob>.+?)(?:\\s*[♥❤].*)?$",
        Pattern.CASE_INSENSITIVE);

    private final YCBotChallengeConfig cfg;
    private final StatsTracker stats;
    private Humanizer human;
    private EventLogger logger;

    private LivingEntity target = null;
    private boolean tagged = false;
    private long tagAt = 0;
    private long targetPickedAt = 0;
    /** Only filled after the cook bar expires — we do not select during a fight. */
    private LivingEntity nextTarget = null;
    /** How far we may drift from the cooking mob this kill (rolled per tag). */
    private double cookLeash = 3.0;
    public String nextTargetDesc = null;

    /** Boss bars present at the moment we tapped; any new non-boost bar is the cook HP. */
    private Set<UUID> barsAtTag = Set.of();
    private UUID cookBarId = null;
    private boolean cookBarSeen = false;
    private boolean cookBarGone = false;
    private float cookBarPercent = -1f;
    private String cookBarTitle = null;
    private int cookBarZeroTicks = 0;
    private float cookBarPercentAtLatch = 1f;
    private long cookBarLatchedAt = 0;
    /** Event bars we already logged this cook so we don't spam. */
    private final Set<UUID> ignoredEventBars = new HashSet<>();
    private long nextActionAt = 0;   // humanized reaction / idle gate
    private long lastTapAt = 0;
    public int kills = 0;
    public String lastTargetDesc = null;
    public String dominantDesc = null;
    private EntityType<?> dominantType = null;
    private int dominantCount = 0;

    // rotation momentum state (degrees per tick)
    private float yawVel = 0f;
    private float pitchVel = 0f;
    /** Per-target random camera lead (deg) — varies the approach path. */
    private float approachYawOffset = 0f;
    /** Signed yaw error to the target from the last aim tick (deg, + = target to the right). */
    private float lastYawErrSigned = 0f;
    /** Last 8-way movement octant (see moveToward); held across ticks for hysteresis. */
    private int prevOct = 0;

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
        this.human = new Humanizer(cfg);
    }

    public void setLogger(EventLogger logger) { this.logger = logger; }

    public Humanizer humanizer() { return human; }

    public String stateDescription() {
        String hint = human != null ? human.hudHint : null;
        if (target == null) {
            return hint != null && !hint.isBlank() ? "searching — " + hint : "searching";
        }
        String s = (tagged ? "cooking " : "approaching ") + (lastTargetDesc == null ? "?" : lastTargetDesc);
        if (tagged && cookBarSeen && cookBarPercent >= 0f) {
            s += "  §c" + Math.round(cookBarPercent * 100f) + "%";
        } else if (tagged && !cookBarSeen) {
            s += "  §8waiting for bar";
        } else if (hint != null && !hint.isBlank()) {
            s += "  §8" + hint;
        }
        return s;
    }

    public void reset(MinecraftClient client) {
        target = null;
        nextTarget = null;
        tagged = false;
        clearCookBar();
        yawVel = 0f;
        pitchVel = 0f;
        prevOct = 0;
        human = new Humanizer(cfg); // new session personality each toggle
        human.reset();
        releaseKeys(client);
    }

    public void releaseKeys(MinecraftClient client) {
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
    }

    public void tick(MinecraftClient client) {
        if (client.player == null || client.world == null || client.interactionManager == null) return;
        long now = System.currentTimeMillis();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        updateMotion(client);

        // current target started moving -> it's a ghost leftover. If a cook bar
        // is still up the fight isn't over — drop the entity, keep waiting.
        if (target != null && cfg.stationaryOnly && ghosts.contains(target.getId())) {
            if (logger != null) {
                logger.log("target_abandoned", "reason", "moving-ghost", "mob", targetMob, "rarity", targetRarity);
            }
            target = null;
            if (!tagged || !cookBarSeen || cookBarGone) {
                tagged = false;
                clearCookBar();
            }
        }

        // Cook first: the boss HP bar is the done-signal, not entity death.
        // Must run before idle so a pause never misses the bar expiring.
        if (tagged) {
            updateCookBar(client);
            if (cookComplete(now)) {
                finishCook(now, cookBarSeen && cookBarGone ? "boss-bar" : "death");
                releaseKeys(client);
                lookAround(client, now);
                return;
            }
            if (now - tagAt > cfg.maxCookMs) {
                if (logger != null) {
                    logger.log("target_abandoned", "reason", "cook-timeout",
                        "mob", targetMob, "rarity", targetRarity, "afterMs", now - tagAt);
                }
                target = null;
                tagged = false;
                clearCookBar();
            }
        }

        // occasional human-ish idle — camera keeps wandering instead of freezing
        if (now < nextActionAt) {
            releaseKeys(client);
            lookAround(client, now);
            return;
        }
        if (rng.nextDouble() < cfg.idleChancePerMinute / (60.0 * 20.0)) { // per tick
            nextActionAt = now + human.idleHold();
            releaseKeys(client);
            lookAround(client, now);
            return;
        }

        // Entity despawned. That is NOT a kill unless we aren't using the bar,
        // or no bar ever showed. The corpse/ghost can vanish while the bar is
        // still the fight.
        if (target != null && entityGone(target)) {
            if (!tagged) {
                target = null;
            } else if (!cfg.cookDoneOnBossBar || (!cookBarSeen && now - tagAt > cfg.cookBarAppearMs)) {
                finishCook(now, "death");
                lookAround(client, now);
                return;
            } else {
                target = null; // keep tagged; wait for the bar
            }
        }

        // stale un-killable target (never got a tap off)
        if (target != null && !tagged && now - targetPickedAt > 12_000) {
            target = null;
        }

        // stage changed under us: current (untagged) target is no longer the dominant mob type
        if (target != null && !tagged && cfg.targetDominant && dominantType != null
            && target.getType() != dominantType && dominantCount >= cfg.minDominantPack) {
            target = null;
        }

        // Next selection starts only after the previous cook is fully over.
        if (target == null && !tagged) {
            boolean linedUp = nextTarget != null && validMob(client, nextTarget);
            if (linedUp) {
                target = nextTarget;
            } else {
                target = pickTarget(client, null);
                if (target == null) { nextTarget = null; releaseKeys(client); lookAround(client, now); return; }
                approachYawOffset = (float) ((rng.nextDouble() * 2 - 1) * cfg.approachYawOffsetMaxDeg / speedFactor(client));
            }
            nextTarget = null;
            nextTargetDesc = null;
            targetPickedAt = now;
            readNameplate(target);
            human.onNewTarget(now, linedUp);
            if (logger != null) {
                logger.log("tag_intent", "mob", targetMob, "rarity", targetRarity, "level", targetLevel);
            }
        }

        if (tagged) {
            cookTick(client, now);
            return;
        }

        // spotted a mob: look at it for a beat before the legs start (a person
        // notices, then commits — they don't strafe the same tick they acquire)
        if (human.noticing(now)) {
            releaseKeys(client);
            aimAt(client, human.gazePoint(client, target, null, false, now), true);
            return;
        }

        double dist = client.player.distanceTo(target);
        if (human.microPausing(dist, now)) {
            releaseKeys(client);
            aimAt(client, human.gazePoint(client, target, null, false, now), true);
            return;
        }

        double aimErr = aimAt(client, human.gazePoint(client, target, null, false, now), true);

        if (dist > cfg.reach) {
            human.leftReach();
            if (cfg.movement) moveToward(client, dist, true);
            return;
        }

        releaseKeys(client);
        // Arrived while sprinting: a sprint-hit is a knockback hit (shoves the mob
        // ~a block, which the ghost filter reads as "it moved") and it also kills
        // our own momentum. Drop sprint and let the STOP packet go out first —
        // the tap happens next tick, 50 ms later.
        if (client.player.isSprinting()) { client.player.setSprinting(false); return; }
        if (human.waitingToTap(now)) return;
        if (!tagged && now - lastTapAt >= cfg.tapCooldownMs && aimErr <= cfg.aimTapMaxErrorDeg) {
            client.interactionManager.attackEntity(client.player, target);
            client.player.swingHand(Hand.MAIN_HAND);
            lastTapAt = now;
            tagAt = now;
            tagged = true;
            barsAtTag = new HashSet<>(BossBars.ids(client));
            cookBarId = null;
            cookBarSeen = false;
            cookBarGone = false;
            cookBarPercent = -1f;
            cookBarTitle = null;
            cookBarZeroTicks = 0;
            cookBarPercentAtLatch = 1f;
            cookBarLatchedAt = 0;
            ignoredEventBars.clear();
            cookLeash = rng.nextDouble(cfg.cookLeashMinBlocks, Math.max(cfg.cookLeashMinBlocks + 0.01, cfg.cookLeashMaxBlocks));
            human.onTagged(now);
            if (logger != null) {
                logger.log("tag", "mob", targetMob, "rarity", targetRarity, "level", targetLevel,
                    "cookStyle", human.cookStyle.name());
            }
        }
    }

    /**
     * Fight in progress: stay near the tagged mob so the server keeps
     * auto-attacking, look around like a person. Do NOT pick the next mob
     * until the boss bar expires.
     */
    private void cookTick(MinecraftClient client, long now) {
        aimAt(client, human.gazePoint(client, target, null, true, now), false);

        if (target == null) {
            releaseKeys(client);
            return;
        }

        double leash = client.player.distanceTo(target);
        boolean roomOnLeash = leash + coastDistance(client) < cookLeash - 0.25;

        int fidget = human.cookFidgetStrafe(now);
        if (cfg.movement && fidget != 0 && roomOnLeash && leash > 0.9) {
            client.options.forwardKey.setPressed(false);
            client.options.backKey.setPressed(false);
            client.options.jumpKey.setPressed(false);
            client.options.leftKey.setPressed(fidget < 0);
            client.options.rightKey.setPressed(fidget > 0);
            client.options.sprintKey.setPressed(false);
            return;
        }
        releaseKeys(client);
    }

    private static boolean entityGone(LivingEntity e) {
        return e.isRemoved() || e.isDead() || !e.isAlive();
    }

    private void updateCookBar(MinecraftClient client) {
        if (!cfg.cookDoneOnBossBar) return;
        Map<UUID, ClientBossBar> bars = BossBars.current(client);
        long now = System.currentTimeMillis();

        // An event popup during the fight must never be treated as the cook
        // finishing — and if we latched one by mistake, drop it and keep looking.
        if (cookBarId != null) {
            ClientBossBar held = bars.get(cookBarId);
            if (held != null && BossBars.ignoredForCook(held.getName().getString(), cfg.cookBarIgnorePatterns)) {
                if (logger != null) {
                    logger.log("cook_bar_unlatched", "reason", "event",
                        "title", held.getName().getString());
                }
                rememberIgnored(cookBarId, held.getName().getString(), held.getPercent());
                clearLatchOnly();
            } else if (held != null && stalledEventDisguise(held, now)) {
                if (logger != null) {
                    logger.log("cook_bar_unlatched", "reason", "stalled-event",
                        "title", held.getName().getString(), "percent", held.getPercent());
                }
                rememberIgnored(cookBarId, held.getName().getString(), held.getPercent());
                clearLatchOnly();
            }
        }

        if (cookBarId == null) {
            latchBestCookBar(bars, now);
            return;
        }

        // Prefer a bar that actually names this mob if we latched a generic one
        // and the real HP bar showed up a tick later.
        if (!BossBars.looksLikeCookBar(cookBarTitle, targetMob, targetRarity)) {
            UUID better = bestCookCandidate(bars);
            if (better != null && !better.equals(cookBarId)) {
                ClientBossBar b = bars.get(better);
                if (b != null && BossBars.looksLikeCookBar(b.getName().getString(), targetMob, targetRarity)) {
                    latch(better, b, now);
                }
            }
        }

        ClientBossBar bar = bars.get(cookBarId);
        if (bar == null) {
            cookBarGone = true;
            return;
        }
        cookBarPercent = bar.getPercent();
        cookBarTitle = bar.getName().getString();
        if (cookBarPercent <= 0.005f) {
            cookBarZeroTicks++;
            if (cookBarZeroTicks >= 3) cookBarGone = true;
        } else {
            cookBarZeroTicks = 0;
        }
    }

    private void latchBestCookBar(Map<UUID, ClientBossBar> bars, long now) {
        UUID id = bestCookCandidate(bars);
        if (id == null) return;
        ClientBossBar bar = bars.get(id);
        if (bar == null) return;
        latch(id, bar, now);
    }

    private UUID bestCookCandidate(Map<UUID, ClientBossBar> bars) {
        UUID bestId = null;
        int bestScore = Integer.MIN_VALUE;
        for (var e : bars.entrySet()) {
            if (barsAtTag.contains(e.getKey()) || ignoredEventBars.contains(e.getKey())) continue;
            String title = e.getValue().getName().getString();
            if (BossBars.ignoredForCook(title, cfg.cookBarIgnorePatterns)) {
                rememberIgnored(e.getKey(), title, e.getValue().getPercent());
                continue;
            }
            int score = BossBars.cookScore(title, e.getValue().getPercent(), targetMob, targetRarity);
            if (score > bestScore) {
                bestScore = score;
                bestId = e.getKey();
            }
        }
        return bestId;
    }

    private void latch(UUID id, ClientBossBar bar, long now) {
        cookBarId = id;
        cookBarSeen = true;
        cookBarGone = false;
        cookBarTitle = bar.getName().getString();
        cookBarPercent = bar.getPercent();
        cookBarPercentAtLatch = cookBarPercent;
        cookBarLatchedAt = now;
        cookBarZeroTicks = 0;
        if (logger != null) {
            logger.log("cook_bar", "title", cookBarTitle, "percent", cookBarPercent,
                "cookLike", BossBars.looksLikeCookBar(cookBarTitle, targetMob, targetRarity));
        }
    }

    /** Event bars drain over minutes; cook HP drops in seconds. No nameplate match + no drain → not the fight. */
    private boolean stalledEventDisguise(ClientBossBar bar, long now) {
        if (now - cookBarLatchedAt < 4000) return false;
        if (BossBars.looksLikeCookBar(bar.getName().getString(), targetMob, targetRarity)) return false;
        return bar.getPercent() > cookBarPercentAtLatch - 0.01f;
    }

    private void rememberIgnored(UUID id, String title, float percent) {
        if (!ignoredEventBars.add(id)) return;
        if (logger != null) logger.log("event_bar_ignored", "title", title, "percent", percent);
    }

    private void clearLatchOnly() {
        cookBarId = null;
        cookBarSeen = false;
        cookBarGone = false;
        cookBarPercent = -1f;
        cookBarTitle = null;
        cookBarZeroTicks = 0;
        cookBarLatchedAt = 0;
    }

    /** True when this fight is over and we may start the next selection. */
    private boolean cookComplete(long now) {
        if (cookBarSeen && cookBarGone) return true;
        if (!cfg.cookDoneOnBossBar) {
            return target == null || entityGone(target);
        }
        // No fight bar showed up — fall back to the entity disappearing.
        if (!cookBarSeen && now - tagAt > cfg.cookBarAppearMs) {
            return target == null || entityGone(target);
        }
        return false;
    }

    private void finishCook(long now, String via) {
        kills++;
        stats.recordKill();
        if (logger != null) {
            logger.log("kill",
                "mob", targetMob, "rarity", targetRarity, "level", targetLevel,
                "timeToKillMs", now - tagAt, "kills", kills, "via", via,
                "bar", cookBarTitle, "barPct", cookBarPercent);
        }
        // Corpse that outlived the bar is a ghost — don't tap it again.
        if (target != null && !entityGone(target) && cfg.stationaryOnly) {
            ghosts.add(target.getId());
            ghostsIgnored++;
        }
        target = null;
        nextTarget = null;
        nextTargetDesc = null;
        tagged = false;
        clearCookBar();
        nextActionAt = now + human.reactionDelay();
    }

    private void clearCookBar() {
        barsAtTag = Set.of();
        cookBarId = null;
        cookBarSeen = false;
        cookBarGone = false;
        cookBarPercent = -1f;
        cookBarTitle = null;
        cookBarZeroTicks = 0;
        cookBarPercentAtLatch = 1f;
        cookBarLatchedAt = 0;
        ignoredEventBars.clear();
    }

    /** Idle / waiting: keep the eyes moving so the mouse never parks. */
    private void lookAround(MinecraftClient client, long now) {
        human.idleLook(client, now);
        Vec3d pt = human.gazePoint(client, target, nextTarget, tagged, now);
        aimAt(client, pt, false);
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

    private void moveToward(MinecraftClient client, double dist, boolean allowSprint) {
        // Coast in: let go once we'd slide into reach anyway. If we come up a
        // little short the next tick just nudges W again — a normal-looking step.
        if (dist - cfg.reach <= coastDistance(client)) { releaseKeys(client); return; }
        float err = lastYawErrSigned;
        int oct = Math.round(err / 45f);
        if (oct == -4) oct = 4;
        // hysteresis: keep the current combo until the bearing is clearly past the boundary
        if (oct != prevOct
            && Math.abs(MathHelper.wrapDegrees(err - prevOct * 45f)) < 22.5f + cfg.moveHysteresisDeg) {
            oct = prevOct;
        }
        oct = human.delayOctant(oct, prevOct);
        prevOct = oct;

        int a = Math.abs(oct);
        boolean forward = a <= 1;
        boolean back = a >= 3;
        boolean right = oct > 0 && a < 4;   // + = target to our right -> D
        boolean left = oct < 0 && a < 4;    // - = target to our left  -> A
        client.options.forwardKey.setPressed(forward);
        client.options.backKey.setPressed(back);
        client.options.leftKey.setPressed(left);
        client.options.rightKey.setPressed(right);

        // Sprint: assert it on the player directly rather than through the sprint
        // keybind (a StickyKeyBinding under "Sprint: Toggle", where setPressed(true)
        // every tick flips it on/off). Vanilla keeps it going while W is held and
        // clears it on its own rules; we only re-assert when those rules allow it,
        // so there's no start/stop packet flicker.
        boolean aligned = Math.abs(err) < cfg.sprintAlignMaxDeg;
        double toGo = dist - cfg.reach;
        boolean wantSprint = allowSprint && cfg.sprint && forward && aligned && toGo > cfg.sprintMinDistance;
        if (human.sprintWarmedUp(wantSprint) && !client.player.isSprinting() && canStartSprint(client)) {
            client.player.setSprinting(true);
        }
        boolean hop = allowSprint && cfg.sprintJump && client.player.isSprinting() && aligned && toGo > cfg.sprintJumpMinDistance;
        client.options.jumpKey.setPressed(client.player.horizontalCollision || hop);
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

    /**
     * Momentum-based aiming at a world point. The camera has angular velocity
     * and an acceleration cap: every turn ramps up, coasts, and eases out —
     * nothing ever snaps. Returns the remaining aim error in degrees.
     *
     * Feel is driven by the session's agility (config ± a little personality):
     *   turn speed cap:  120..420 deg/s
     *   acceleration:    250..1600 deg/s²
     */
    private double aimAt(MinecraftClient client, Vec3d aim, boolean approachLead) {
        human.tickAimHeight();
        Vec3d eye = client.player.getEyePos();
        double dx = aim.x - eye.x;
        double dy = aim.y - eye.y;
        double dz = aim.z - eye.z;
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        float wantYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float wantPitch = (float) -Math.toDegrees(Math.atan2(dy, distXZ));

        // Curved-approach lead: bias the aim off-center while far away, decaying
        // to 0 within reach so the tap still lands dead-on. Different sign/size
        // per target -> a different arc every time instead of straight lines.
        if (approachLead && approachYawOffset != 0f) {
            double lead = (distXZ - cfg.reach) / 8.0;      // full offset ~8 blocks out
            lead = MathHelper.clamp(lead, 0.0, 1.0);
            wantYaw += approachYawOffset * (float) lead;
        }

        if (cfg.humanize) {
            wantYaw += human.yawNoise();
            wantPitch += human.pitchNoise();
        }

        float yawErr = MathHelper.wrapDegrees(wantYaw - client.player.getYaw());
        float pitchErr = wantPitch - client.player.getPitch();
        lastYawErrSigned = yawErr;
        double err = Math.sqrt(yawErr * yawErr + pitchErr * pitchErr);

        double a = MathHelper.clamp(human.agility, 0.05, 1.0);
        float maxSpeed = (float) ((120.0 + 300.0 * a) / 20.0);   // deg per tick
        float accel = (float) ((250.0 + 1350.0 * a) / 400.0);    // deg per tick per tick

        if (err < cfg.aimDeadzoneDeg) {
            // close enough — bleed off momentum instead of pixel-tracking, but
            // keep a whisper of noise so the crosshair never parks on a pixel
            yawVel *= 0.7f;
            pitchVel *= 0.7f;
            if (cfg.humanize) {
                yawVel += human.yawNoise() * 0.12f;
                pitchVel += human.pitchNoise() * 0.12f;
            }
        } else {
            yawVel = approachVel(yawVel, yawErr, maxSpeed, accel);
            pitchVel = approachVel(pitchVel, pitchErr, maxSpeed * 0.6f, accel * 0.8f);
            // faint hand tremor, proportional to how fast we're turning
            float tremor = Math.abs(yawVel) * 0.05f;
            if (tremor > 0.01f) {
                ThreadLocalRandom r = ThreadLocalRandom.current();
                yawVel += (r.nextFloat() - 0.5f) * tremor;
                pitchVel += (r.nextFloat() - 0.5f) * tremor * 0.6f;
            }
        }

        client.player.setYaw(client.player.getYaw() + yawVel);
        client.player.setPitch(MathHelper.clamp(client.player.getPitch() + pitchVel, -89f, 89f));
        return err;
    }

    /**
     * Velocity chases a proportional target speed under an acceleration cap:
     * far away -> speeds up toward the cap; close -> desired speed shrinks,
     * so it brakes into the target instead of overshooting or snapping.
     */
    private static float approachVel(float vel, float error, float maxSpeed, float accel) {
        float desired = MathHelper.clamp(error * 0.25f, -maxSpeed, maxSpeed);
        float dv = MathHelper.clamp(desired - vel, -accel, accel);
        return vel + dv;
    }
}

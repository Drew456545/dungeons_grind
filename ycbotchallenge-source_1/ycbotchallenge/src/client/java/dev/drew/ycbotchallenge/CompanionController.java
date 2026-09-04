package dev.drew.ycbotchallenge;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Companions (0.9.28): walk to the zone's Companion Egg (a dragon-egg block under a
 * hologram "<Zone> Companion Egg / … / | Price: $… Money"), right-click it, open eggs
 * while an open is cheap against income, then /companion → Equip Best, a look at the
 * Fuse Companions GUI (logged only), and a sliding-window bulk delete of old zones.
 *
 * 0.9.35: the trigger is no longer here. Economy.decideCompanion prices an egg
 * batch against the sword, the zone and the rebirth on one set of ETAs and publishes a
 * Decision of kind "companion"; this class contributes {@link #canVisitNow} (physical
 * feasibility and the blast-radius caps) and then executes. What it replaced was a second,
 * weaker economy whose gates all failed closed on the stage the bot could not leave.
 * Ctrl+Shift+toggle still runs a visit by hand.
 *
 * Every GUI is dumped verbatim (companion_gui) — the fixture net for the next version.
 */
public class CompanionController {
    private enum Phase { IDLE, WALK, AIM, OPEN_WAIT, EGG_LOOK, BUY, BUY_CLICK, BUY_SETTLE, CLOSE_EGG, TYPE_COMPANION, COMP_WAIT,
        COMP_LOOK, EQUIP, EQUIP_SETTLE, FUSE_CLICK, FUSE_WAIT, FUSE_LOG, DELETE, DELETE_TYPE, DONE }

    private record Entry(int slot, String name, List<String> lore) {}
    private record EggHit(Vec3d aim, List<String> lines, Double price, double dist, String via) {}

    private final YCBotChallengeConfig cfg;
    private final StatsTracker stats;
    private final UpgradeController upgrades;
    private final CompanionLore lore;
    private final ChatTyper typer;
    private EventLogger logger;

    private Phase phase = Phase.IDLE;
    private long phaseUntil;
    private long visitStartedAt;
    private String visitVia = "cheap";
    private long plannedAt = 0;
    private String planVia = null;
    private boolean manualRequested = false;

    // trigger bookkeeping
    private long lastRebirthSeen = -1;
    // visitsThisRebirth / endFallbackDone / lastBoughtStage live in StatsTracker since 0.9.33 (persisted per user).
    private Integer lastPriceLogStage = null;
    /** Zone-gap facts of the last decide() for the skip log. */
    // 0.9.35: the measured batch gain, the abort cooldown, and combat.kills mirrored so
    // canVisitNow() can answer without a CombatController in hand (the economy asks first).
    private Double gainBefore = null;
    private long gainAt = 0;
    private int gainEggs = 0;
    private Integer gainStage = null;
    private long blockedUntil = 0;
    private int lastCombatKills = 0;
    private int zoneSeqSeen = -1;
    private int killsAtStage = 0;
    /** 0.9.36: when the current stage started (or the bot was enabled on it) - the time half of the settle rule. */
    private long stageEnteredAt = 0;
    private long lastEggScanAt = 0;
    private long lastDecisionAt = 0;
    private long lastSkipLogAt = 0;
    private EggHit lastEgg = null;
    private long lastEggAt = 0;
    private long lastBlockScanAt = 0;
    private EggStore eggStore;
    /** A spotlighted egg (Ctrl+Shift+toggle on a block) waiting for the next visit. */
    private Vec3d pickedAim = null;
    private boolean pickedAimEntity = false;
    /** Location the remembered egg points (pickedAim / lastEgg) were taken in. */
    private Integer eggLocation = null;
    /** The visit's aim is an entity (a spotlighted armor stand), so a block-identity check cannot apply. */
    private boolean eggAimEntity = false;

    // walk / aim
    private Vec3d eggAim = null;
    private long walkStartAt;
    private double bestDist;
    private long lastProgressAt;
    private long lastLookAt;
    private int sidestepTicks;
    private int aimTry;
    private long aimIssuedAt;
    /** {pitch, yaw} offsets swept until the crosshair is on the egg block itself (0.9.31: yaw too). */
    private static final float[][] AIM_OFFSETS = {
        {0f, 0f}, {6f, 0f}, {-6f, 0f}, {0f, 4f}, {0f, -4f}, {12f, 0f}, {6f, 4f}, {6f, -4f}, {18f, 0f}, {24f, 0f}};

    // buy
    private int eggsTarget;
    private int eggsOpened;
    private int opensClicked;
    private double spent;
    private Double balanceBefore;
    private CompanionLore.OpenOption pendingOption;
    private Double pendingMinutes;
    private Double observedEggPrice;
    private boolean eggsGuiLogged;
    private boolean unparsedLogged;
    private boolean rungLessonTried;

    // companions
    private List<CompanionLore.Companion> equippedBefore = List.of();
    private List<CompanionLore.Companion> equippedAfter = List.of();
    private List<CompanionLore.Companion> storage = List.of();
    private int equipSlot = -1;
    private int fuseSlot = -1;
    private List<CompanionLore.ZoneStage> deletes = List.of();
    private int deleteIdx;
    private Integer currentZone;
    private Integer visitStage;

    private int consecutiveAborts;
    private boolean suspended;

    public CompanionController(YCBotChallengeConfig cfg, StatsTracker stats, UpgradeController upgrades) {
        this.cfg = cfg;
        this.stats = stats;
        this.upgrades = upgrades;
        this.lore = new CompanionLore(cfg);
        this.typer = new ChatTyper(cfg);
    }

    public void setLogger(EventLogger logger) { this.logger = logger; }

    public boolean isBusy() { return phase != Phase.IDLE; }

    /** 0.9.30 HUD chip: suspended after repeated aborts (toggle to reset). */
    public boolean isSuspended() { return suspended; }

    /** The egg GUI, the Companions GUI and the fuse GUI are ours (or hand-opened), never a captcha. */
    public boolean isOurGui(MinecraftClient client) {
        String t = title(client);
        return t != null && (lore.isEggsTitle(t) || lore.isCompanionsTitle(t) || lore.isFuseTitle(t));
    }

    /** Ctrl+Shift+toggle with the bot on: run the visit now (testing, or a hand-timed purchase). */
    public void runNow() { manualRequested = true; }

    public void setEggStore(EggStore store) { this.eggStore = store; }

    /**
     * Ctrl+Shift+toggle, bot on or off (0.9.29): the block (or entity) under the crosshair
     * within 6 blocks is the egg. Saved for the current stage label so automatic visits
     * find it when the block scan does not; the next visit (bot on) walks to it.
     * Returns the chat notice, or null when nothing is being looked at.
     */
    public String spotlight(MinecraftClient client, String stage) {
        if (client == null || client.player == null) return null;
        HitResult hit = client.crosshairTarget;
        Vec3d aim = null;
        String what = null;
        boolean entity = false;
        if (hit != null && hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult bh) {
            aim = eggAimPoint(bh.getBlockPos());
            what = client.world != null ? Registries.BLOCK.getId(client.world.getBlockState(bh.getBlockPos()).getBlock()).toString() : "block";
        } else if (hit != null && hit.getType() == HitResult.Type.ENTITY && hit instanceof EntityHitResult eh) {
            aim = eh.getEntity().getEntityPos();
            what = "entity:" + eh.getEntity().getType().getName().getString();
            entity = true;
        }
        if (aim == null || client.player.getEyePos().distanceTo(aim) > 6.5) return null;
        pickedAim = aim;
        pickedAimEntity = entity;
        eggLocation = locationNow();
        // 0.9.31: keyed by location (Farm = stages 1–10, Western = 11–20, …): one egg serves
        // ten stages, and the ten-stage teleport lands at a new one.
        String key = EggStore.key(stage, cfg.companionStagesPerLocation);
        if (eggStore != null) {
            EggStore.Egg e = new EggStore.Egg();
            e.x = aim.x; e.y = aim.y; e.z = aim.z;
            e.label = what;
            e.at = System.currentTimeMillis();
            eggStore.put(key, e);
        }
        log("companion_egg_spotlight", "stage", stage, "key", key, "location", eggLocation, "what", what,
            "x", Math.round(aim.x), "y", Math.round(aim.y), "z", Math.round(aim.z));
        Integer loc = eggLocation;
        String where = loc != null
            ? "location " + loc + " (stages " + ((loc - 1) * cfg.companionStagesPerLocation + 1) + "–" + (loc * cfg.companionStagesPerLocation) + ")"
            : key;
        return "egg for " + where + " saved at " + Math.round(aim.x) + ", " + Math.round(aim.y) + ", " + Math.round(aim.z) + " (" + what + ").";
    }

    public String hudLine() {
        if (!cfg.companionsEnabled) return null;
        if (phase == Phase.IDLE) {
            if (suspended) return "companions: suspended after repeated aborts (toggle to reset)";
            if (plannedAt != 0) return "companions: visit in " + Math.max(0, (plannedAt - System.currentTimeMillis() + 999) / 1000) + "s (" + planVia + ")";
            return eggsLine();
        }
        return "companions: " + phase.name().toLowerCase(Locale.ROOT)
            + (eggsOpened > 0 ? "  eggs " + eggsOpened + "/" + eggsTarget : "")
            + (deletes.isEmpty() ? "" : "  del " + deleteIdx + "/" + deletes.size());
    }

    public void onEnable(long now, int kills) {
        suspended = false;
        consecutiveAborts = 0;
        lastRebirthSeen = stats.lastRebirthAt;
        killsAtStage = kills;
        stageEnteredAt = now;
        zoneSeqSeen = stats.zoneChangeSeq();
        manualRequested = false;
    }

    /**
     * 0.9.36: why the eggs are not being bought, for the HUD busy row and the Y screen -
     * the last eval's annotation ({@link Decision#eggs}) with the batch and how far the
     * balance is from it. Null when the economy has nothing to say (a buy is planned, or
     * companions are off).
     */
    public String eggsLine() {
        Decision d = upgrades != null ? upgrades.lastDecision() : null;
        if (d == null || d.eggs() == null) return null;
        Integer stage = stats.confirmedZoneLevel();
        Double batch = stats.companionBatchPrice(stage);
        Double bal = stats.money();
        String price = batch != null ? " " + Amounts.format(batch) : "";
        String pct = batch != null && batch > 0 && bal != null ? " " + Math.min(999, Math.round(100.0 * bal / batch)) + "%" : "";
        String why = "blocked".equals(d.eggs()) ? "blocked: " + blockedReason(System.currentTimeMillis()) : d.eggs();
        return "eggs" + price + pct + " · " + why;
    }

    public void reset(MinecraftClient client) {
        if (client != null && phase != Phase.IDLE) {
            if (isOurGui(client)) EnchantScreens.closeGui(client);
            releaseWalkKeys(client);
        }
        typer.cancel(client);
        phase = Phase.IDLE;
        plannedAt = 0;
        manualRequested = false;
    }

    private void log(String type, Object... kv) {
        if (logger != null) logger.log(type, kv);
    }

    /** @return true if combat should yield this tick. */
    public boolean tick(MinecraftClient client, CombatController combat) {
        if (!cfg.companionsEnabled || client.player == null || client.world == null) return false;
        long now = System.currentTimeMillis();
        lastCombatKills = combat.kills;
        tickGain(now);
        if (phase == Phase.IDLE) return maybeStart(client, combat, now);

        if (phase != Phase.WALK) combat.releaseKeys(client);
        if (phase != Phase.DONE && now - visitStartedAt > cfg.companionMaxVisitMs) {
            log("companion_skip", "reason", "visit-timeout", "phase", phase.name().toLowerCase(Locale.ROOT));
            abort(client, combat, "visit-timeout");
            return false;
        }
        switch (phase) {
            case WALK -> {
                if (eggAim == null) { abort(client, combat, "no-aim"); return false; }
                Vec3d p = client.player.getEntityPos();
                double dx = eggAim.x - p.x, dz = eggAim.z - p.z;
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist <= cfg.companionEggReach) {
                    releaseWalkKeys(client);
                    log("companion_walk", "blocks", Math.round(bestDistStart - dist), "ms", now - walkStartAt, "left", Math.round(dist * 10.0) / 10.0);
                    phase = Phase.AIM;
                    aimTry = 0;
                    aimIssuedAt = 0;
                    MouseDriver.INSTANCE.cancel();
                    return true;
                }
                if (now - walkStartAt > cfg.companionWalkTimeoutMs) { abort(client, combat, "walk-timeout"); return false; }
                float yawTo = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
                float err = MathHelper.wrapDegrees(yawTo - client.player.getYaw());
                if (Math.abs(err) > 6f && now - lastLookAt > 300 && !MouseDriver.INSTANCE.isBusy()) {
                    lastLookAt = now;
                    MouseDriver.INSTANCE.lookTo(client, yawTo, walkPitch(client, dist), "companion-walk");
                }
                boolean forward = Math.abs(err) < 60f;
                if (dist < bestDist - 0.3) { bestDist = dist; lastProgressAt = now; }
                boolean stuck = now - lastProgressAt > 4000;
                if (stuck && sidestepTicks == 0) {
                    sidestepTicks = 12;
                    lastProgressAt = now;
                    log("companion_walk_stuck", "dist", Math.round(dist * 10.0) / 10.0);
                }
                boolean side = sidestepTicks > 0;
                if (side) sidestepTicks--;
                client.options.forwardKey.setPressed(forward);
                client.options.leftKey.setPressed(side && (walkStartAt / 1000) % 2 == 0);
                client.options.rightKey.setPressed(side && (walkStartAt / 1000) % 2 == 1);
                client.options.backKey.setPressed(false);
                combat.tapSprint(client, forward && dist > 6 && Math.abs(err) < 20f);
                client.options.jumpKey.setPressed(client.player.horizontalCollision || side);
                return true;
            }
            case AIM -> {
                // 0.9.31: the click goes out only with the crosshair on a dragon-egg block (or,
                // for a spotlighted entity, within companionEggHitRadius of it). The 03:34 log
                // right-clicked an armor stand and then the pedestal stairs because any surface
                // near the aim point used to pass.
                if (aimIssuedAt == 0) {
                    if (aimTry >= AIM_OFFSETS.length) { abort(client, combat, "no-egg-block"); return false; }
                    float[] yp = anglesTo(client, eggAim);
                    MouseDriver.INSTANCE.cancel();
                    MouseDriver.INSTANCE.lookTo(client, yp[0] + AIM_OFFSETS[aimTry][1],
                        MathHelper.clamp(yp[1] + AIM_OFFSETS[aimTry][0], -89f, 89f), "companion-aim");
                    aimIssuedAt = now;
                    return true;
                }
                if (now - aimIssuedAt < 450) return true;
                HitResult hit = client.crosshairTarget;
                String what = null;
                double hitDist = -1;
                boolean onEgg = false;
                if (hit != null && hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult bh) {
                    hitDist = hit.getPos().distanceTo(eggAim);
                    what = Registries.BLOCK.getId(client.world.getBlockState(bh.getBlockPos()).getBlock()).toString();
                    onEgg = client.world.getBlockState(bh.getBlockPos()).isOf(Blocks.DRAGON_EGG)
                        || (eggAimEntity && hitDist <= cfg.companionEggHitRadius);
                } else if (hit != null && hit.getType() == HitResult.Type.ENTITY && hit instanceof EntityHitResult eh) {
                    hitDist = hit.getPos().distanceTo(eggAim);
                    what = "entity:" + eh.getEntity().getType().getName().getString();
                    onEgg = eggAimEntity && hitDist <= cfg.companionEggHitRadius;
                }
                if (onEgg) {
                    log("companion_aim", "hit", what, "hitDist", Math.round(hitDist * 100.0) / 100.0, "try", aimTry);
                    EnchantScreens.pressUse(client, false);
                    phase = Phase.OPEN_WAIT;
                    phaseUntil = now + cfg.companionOpenTimeoutMs;
                    return true;
                }
                log("companion_aim_miss", "try", aimTry, "hit", what, "hitDist", hitDist < 0 ? null : Math.round(hitDist * 100.0) / 100.0,
                    "pitchOff", AIM_OFFSETS[aimTry][0], "yawOff", AIM_OFFSETS[aimTry][1]);
                aimTry++;
                aimIssuedAt = 0;
                return true;
            }
            case OPEN_WAIT -> {
                if (eggsGuiOpen(client)) {
                    phase = Phase.EGG_LOOK;
                    phaseUntil = now + GuiHuman.lookDelayMs(cfg, "companion");
                } else if (now >= phaseUntil) {
                    if (client.currentScreen != null) {
                        log("companion_skip", "reason", "other-gui", "title", title(client), "items", describe(containerItems(client)));
                        EnchantScreens.closeGui(client);
                    }
                    abort(client, combat, "no-egg-gui");
                    return false;
                }
            }
            case EGG_LOOK -> {
                if (!eggsGuiOpen(client)) { abort(client, combat, "egg-gui-closed"); return false; }
                if (now < phaseUntil) return true;
                if (!eggsGuiLogged) {
                    eggsGuiLogged = true;
                    log("companion_gui", "which", "eggs", "title", title(client), "items", describe(containerItems(client)));
                }
                phase = Phase.BUY;
            }
            case BUY -> {
                if (!eggsGuiOpen(client)) { onEggsGuiGone(client, now); return true; }
                List<CompanionLore.OpenOption> options = new ArrayList<>();
                List<CompanionLore.OpenOption> rawOptions = new ArrayList<>();
                for (Entry e : containerItems(client)) {
                    CompanionLore.OpenOption o = lore.openOption(e.slot(), e.name(), e.lore());
                    if (o == null) continue;
                    rawOptions.add(o);
                    // The first open is the probe: the hologram's "$121.3300" format is unverified,
                    // so once a balance delta has been seen, the observed per-egg price rules.
                    // 0.9.31: an explicit Double — the old mixed ternary unboxed a null price
                    // ("1.58SS", a rung the ladder had not seen) and crashed the client twice.
                    Double price = o.price();
                    if (observedEggPrice != null) price = observedEggPrice * o.count();
                    if (o.price() == null && o.priceRaw() != null && !unparsedLogged) {
                        unparsedLogged = true;
                        log("companion_option_unparsed", "slot", o.slot(), "count", o.count(), "raw", o.priceRaw());
                    }
                    options.add(new CompanionLore.OpenOption(o.slot(), o.name(), o.count(), price, o.priceRaw()));
                }
                if (!rungLessonTried) {
                    rungLessonTried = true;
                    CompanionLore.RungLesson lesson = CompanionLore.rungFromOptions(rawOptions);
                    if (lesson != null && stats.learnSuffixFromGui(lesson.suffix(), lesson.learned(), "companion-gui")) {
                        log("companion_rung_lesson", "suffix", lesson.suffix(), "scale", lesson.learned().scale,
                            "count", lesson.count(), "unit", Amounts.format(lesson.unit()), "raw", lesson.learned().raw);
                        return true; // re-read the options: the 250x line parses now
                    }
                }
                int eggsLeft = eggsTarget - eggsOpened;
                Double income = stats.incomePerMinute();
                Double bal = stats.money();
                // 0.9.35: spend what the decision approved, not a minutes-of-income budget.
                // The batch the economy priced is companionEggsMin eggs; the rolled eggsTarget
                // only humanises the count, it may not enlarge the bill.
                Double budget = visitBudget();
                CompanionLore.OpenOption pick = CompanionLore.pickOpen(options, eggsLeft, budget, bal, cfg.companionMaxBalancePct);
                if (pick == null || opensClicked >= cfg.companionMaxOpensPerVisit) {
                    log("companion_skip", "reason", pick == null ? (options.isEmpty() ? "no-open-item" : "budget") : "open-cap",
                        "eggsLeft", eggsLeft, "opens", opensClicked, "options", options.size(),
                        "incomePerMin", income != null ? Amounts.format(income) : null, "balance", bal != null ? Amounts.format(bal) : null,
                        "budget", budget != null ? Amounts.format(budget) : null, "spent", spent > 0 ? Amounts.format(spent) : null);
                    phase = Phase.CLOSE_EGG;
                    phaseUntil = now + GuiHuman.closeDelayMs(cfg);
                    return true;
                }
                pendingOption = pick;
                pendingMinutes = CompanionLore.incomeMinutes(pick.price(), income);
                // Notice the option, then click it (0.9.33: this used to click in the same tick).
                phase = Phase.BUY_CLICK;
                phaseUntil = now + GuiHuman.clickDelayMs(cfg);
            }
            case BUY_CLICK -> {
                if (!eggsGuiOpen(client)) { onEggsGuiGone(client, now); return true; }
                if (now < phaseUntil) return true;
                CompanionLore.OpenOption pick = pendingOption;
                if (pick == null) { phase = Phase.BUY; return true; }
                balanceBefore = stats.money();
                GuiHuman.click(client, pick.slot(), "companion", "open:" + pick.count(), logger);
                opensClicked++;
                log("companion_open_click", "slot", pick.slot(), "count", pick.count(),
                    "price", pick.price() != null ? Amounts.format(pick.price()) : null,
                    "minutes", tenth(pendingMinutes), "opens", opensClicked);
                phase = Phase.BUY_SETTLE;
                phaseUntil = now + HumanTiming.logNormalMs(cfg.companionSettleMinMs, Math.max(cfg.companionSettleMinMs + 1, cfg.companionSettleMaxMs));
            }
            case BUY_SETTLE -> {
                if (now < phaseUntil) return true;
                Double balNow = stats.money();
                double delta = balanceBefore != null && balNow != null ? balanceBefore - balNow : 0;
                boolean bought = delta > 0;
                if (bought && pendingOption != null) {
                    eggsOpened += pendingOption.count();
                    spent += delta;
                    observedEggPrice = delta / pendingOption.count();
                    stats.noteCompanionEggPrice(visitStage, observedEggPrice);
                }
                log("companion_open", "count", pendingOption != null ? pendingOption.count() : null, "bought", bought,
                    "paid", bought ? Amounts.format(delta) : null, "perEgg", bought ? Amounts.format(observedEggPrice) : null,
                    "listed", pendingOption != null && pendingOption.price() != null ? Amounts.format(pendingOption.price()) : null,
                    "eggs", eggsOpened, "target", eggsTarget, "guiOpen", eggsGuiOpen(client));
                pendingOption = null;
                if (!eggsGuiOpen(client)) { onEggsGuiGone(client, now); return true; }
                if (!bought || eggsOpened >= eggsTarget || opensClicked >= cfg.companionMaxOpensPerVisit) {
                    phase = Phase.CLOSE_EGG;
                    phaseUntil = now + GuiHuman.closeDelayMs(cfg);
                } else {
                    phase = Phase.BUY;
                }
            }
            case CLOSE_EGG -> {
                if (now < phaseUntil) return true;
                if (isOurGui(client)) GuiHuman.close(client, "companion", logger);
                phase = Phase.TYPE_COMPANION;
                phaseUntil = now + GuiHuman.betweenDelayMs(cfg);
            }
            case TYPE_COMPANION -> {
                if (now < phaseUntil) return true;
                if (client.currentScreen != null) {
                    if (typer.running()) {
                        ChatTyper.State s = typer.tick(client, now);
                        if (s == ChatTyper.State.FAILED) { abort(client, combat, typer.failReason()); return false; }
                        if (s != ChatTyper.State.DONE) return true;
                        log("companion_send", "command", cfg.companionCommand, "typos", typer.typos());
                        phase = Phase.COMP_WAIT;
                        phaseUntil = now + cfg.companionOpenTimeoutMs;
                        return true;
                    }
                    abort(client, combat, "screen-open");
                    return false;
                }
                if (!typer.running()) {
                    typer.begin(client, cfg.companionCommand, now);
                    return true;
                }
                ChatTyper.State s = typer.tick(client, now);
                if (s == ChatTyper.State.FAILED) { abort(client, combat, typer.failReason()); return false; }
                if (s != ChatTyper.State.DONE) return true;
                log("companion_send", "command", cfg.companionCommand, "typos", typer.typos());
                phase = Phase.COMP_WAIT;
                phaseUntil = now + cfg.companionOpenTimeoutMs;
            }
            case COMP_WAIT -> {
                if (companionsGuiOpen(client)) {
                    phase = Phase.COMP_LOOK;
                    phaseUntil = now + GuiHuman.lookDelayMs(cfg, "companion");
                } else if (now >= phaseUntil) {
                    abort(client, combat, "no-companions-gui");
                    return false;
                }
            }
            case COMP_LOOK -> {
                if (!companionsGuiOpen(client)) { abort(client, combat, "companions-gui-closed"); return false; }
                if (now < phaseUntil) return true;
                List<Entry> entries = containerItems(client);
                log("companion_gui", "which", "companions", "title", title(client), "items", describe(entries));
                readCompanions(entries, true);
                equipSlot = -1;
                fuseSlot = -1;
                for (Entry e : entries) {
                    if (equipSlot < 0 && lore.isEquipBest(e.name(), e.lore())) equipSlot = e.slot();
                    if (fuseSlot < 0 && lore.isFuse(e.name(), e.lore())) fuseSlot = e.slot();
                }
                if (equipSlot < 0) {
                    log("companion_skip", "reason", "no-equip-best");
                    phase = Phase.FUSE_CLICK;
                    phaseUntil = now + GuiHuman.closeDelayMs(cfg);
                    return true;
                }
                phase = Phase.EQUIP;
                phaseUntil = now + GuiHuman.clickDelayMs(cfg);
            }
            case EQUIP -> {
                if (!companionsGuiOpen(client)) { abort(client, combat, "companions-gui-closed"); return false; }
                if (now < phaseUntil) return true;
                GuiHuman.click(client, equipSlot, "companion", "equip-best", logger);
                log("companion_equip_click", "slot", equipSlot);
                phase = Phase.EQUIP_SETTLE;
                phaseUntil = now + HumanTiming.logNormalMs(cfg.companionSettleMinMs, Math.max(cfg.companionSettleMinMs + 1, cfg.companionSettleMaxMs));
            }
            case EQUIP_SETTLE -> {
                if (now < phaseUntil) return true;
                if (!companionsGuiOpen(client)) {
                    log("companion_equip", "guiOpen", false, "before", summaries(equippedBefore));
                    phase = Phase.DELETE;
                    deleteIdx = 0;
                    prepareDeletes();
                    phaseUntil = now + GuiHuman.betweenDelayMs(cfg);
                    return true;
                }
                List<Entry> entries = containerItems(client);
                readCompanions(entries, false);
                log("companion_equip", "guiOpen", true, "before", summaries(equippedBefore), "after", summaries(equippedAfter),
                    "storage", storage.size());
                phase = Phase.FUSE_CLICK;
                phaseUntil = now + GuiHuman.clickDelayMs(cfg);
            }
            case FUSE_CLICK -> {
                if (now < phaseUntil) return true;
                if (!companionsGuiOpen(client) || fuseSlot < 0) {
                    if (fuseSlot < 0) log("companion_skip", "reason", "no-fuse-item");
                    prepareDeletes();
                    phase = Phase.DELETE;
                    deleteIdx = 0;
                    if (isOurGui(client)) EnchantScreens.closeGui(client);
                    phaseUntil = now + GuiHuman.betweenDelayMs(cfg);
                    return true;
                }
                GuiHuman.click(client, fuseSlot, "companion", "fuse", logger);
                log("companion_fuse_click", "slot", fuseSlot);
                phase = Phase.FUSE_WAIT;
                phaseUntil = now + cfg.companionOpenTimeoutMs;
            }
            case FUSE_WAIT -> {
                if (fuseGuiOpen(client)) {
                    phase = Phase.FUSE_LOG;
                    phaseUntil = now + GuiHuman.lookDelayMs(cfg, "companion");
                } else if (now >= phaseUntil) {
                    log("companion_skip", "reason", "no-fuse-gui", "title", title(client));
                    prepareDeletes();
                    phase = Phase.DELETE;
                    deleteIdx = 0;
                    if (isOurGui(client)) EnchantScreens.closeGui(client);
                    phaseUntil = now + GuiHuman.betweenDelayMs(cfg);
                }
            }
            case FUSE_LOG -> {
                if (now < phaseUntil) return true;
                // Fusing is not automated yet (Drew): record the layout, close.
                log("companion_gui", "which", "fuse", "title", title(client), "items", describe(containerItems(client)));
                if (isOurGui(client)) EnchantScreens.closeGui(client);
                prepareDeletes();
                phase = Phase.DELETE;
                deleteIdx = 0;
                phaseUntil = now + GuiHuman.betweenDelayMs(cfg);
            }
            case DELETE -> {
                if (now < phaseUntil) return true;
                if (client.currentScreen != null) { EnchantScreens.closeGui(client); return true; }
                if (deleteIdx >= deletes.size()) { phase = Phase.DONE; return true; }
                String cmd = CompanionLore.bulkDeleteCommand(cfg.companionBulkDeleteCommand, deletes.get(deleteIdx));
                typer.begin(client, cmd, now);
                phase = Phase.DELETE_TYPE;
            }
            case DELETE_TYPE -> {
                ChatTyper.State s = typer.tick(client, now);
                if (s == ChatTyper.State.FAILED) { abort(client, combat, typer.failReason()); return false; }
                if (s != ChatTyper.State.DONE) return true;
                CompanionLore.ZoneStage zs = deletes.get(deleteIdx);
                log("companion_bulk_delete", "zone", zs.zone(), "stage", zs.stage(), "index", deleteIdx + 1, "of", deletes.size(),
                    "typos", typer.typos(), "currentZone", currentZone);
                deleteIdx++;
                phase = Phase.DELETE;
                phaseUntil = now + HumanTiming.logNormalMs(3000, 8000);
            }
            case DONE -> {
                if (isOurGui(client)) EnchantScreens.closeGui(client);
                stats.noteCompanionVisit(visitStage, eggsOpened > 0);
                log("companion_visit_done", "via", visitVia, "eggs", eggsOpened, "opens", opensClicked,
                    "spent", spent > 0 ? Amounts.format(spent) : null,
                    "perEgg", observedEggPrice != null ? Amounts.format(observedEggPrice) : null,
                    "stage", visitStage, "before", summaries(equippedBefore), "after", summaries(equippedAfter),
                    "deletes", deletes.size(), "visitMs", now - visitStartedAt,
                    "visitsThisRebirth", stats.companionVisitsThisRebirth(), "persisted", true);
                consecutiveAborts = 0;
                if (eggsOpened > 0 && gainBefore != null) {
                    gainEggs = eggsOpened;
                    gainAt = now + cfg.companionGainWindowMs;
                } else {
                    gainBefore = null;
                }
                finish(client, combat);
                return false;
            }
            default -> { }
        }
        return true;
    }

    private double bestDistStart;

    // ---------------------------------------------------------------- trigger

    private boolean maybeStart(MinecraftClient client, CombatController combat, long now) {
        if (suspended) return false;
        long rb = stats.lastRebirthAt;
        if (rb != lastRebirthSeen) {
            boolean first = lastRebirthSeen == -1;
            lastRebirthSeen = rb;
            if (!first) stats.companionRebirthRollover();
            plannedAt = 0;
            if (!first) forgetEgg("rebirth");
        }
        int zseq = stats.zoneChangeSeq();
        if (zseq != zoneSeqSeen) {
            zoneSeqSeen = zseq;
            killsAtStage = combat.kills;
            stageEnteredAt = now;
        }
        Integer locNow = locationNow();
        if (eggLocation != null && locNow != null && !locNow.equals(eggLocation)) forgetEgg("location");
        // Price evidence for free: the egg's hologram shows the single-egg price in every zone.
        if (now - lastEggScanAt > 10_000) {
            lastEggScanAt = now;
            EggHit hit = findEggByHologram(client, combat);
            if (hit != null) {
                lastEgg = hit;
                lastEggAt = now;
                Integer stage = stats.confirmedZoneLevel();
                if (stage != null && !stage.equals(lastPriceLogStage)) {
                    lastPriceLogStage = stage;
                    Double income = stats.incomePerMinute();
                    log("companion_price", "stage", stage, "price", hit.price() != null ? Amounts.format(hit.price()) : null,
                        "incomePerMin", income != null ? Amounts.format(income) : null,
                        "minutesPerEgg", tenth(CompanionLore.incomeMinutes(hit.price(), income)),
                        "dist", Math.round(hit.dist()), "lines", hit.lines());
                }
            }
        }
        if (manualRequested) {
            manualRequested = false;
            plannedAt = now;
            planVia = "manual";
            log("companion_plan", "via", "manual", "stage", stats.confirmedZoneLevel());
        } else if (plannedAt == 0 && now - lastDecisionAt > 15_000) {
            lastDecisionAt = now;
            String via = decide(now);
            if (via != null) {
                long delay = HumanTiming.logNormalMs(cfg.companionDelayMinMs, Math.max(cfg.companionDelayMinMs + 1, cfg.companionDelayMaxMs));
                plannedAt = now + delay;
                planVia = via;
                Integer stage = stats.confirmedZoneLevel();
                Double batch = stats.companionBatchPrice(stage);
                log("companion_plan", "via", via, "delayMs", delay, "stage", stage,
                    "batch", batch != null ? Amounts.format(batch) : null,
                    "perEgg", stats.companionEggPriceEstimate(stage) != null ? Amounts.format(stats.companionEggPriceEstimate(stage)) : null,
                    "gain", Math.round(stats.companionGain() * 100.0) / 100.0, "gainVia", stats.companionGainVia(),
                    "incomePerMin", stats.incomePerMinute() != null ? Amounts.format(stats.incomePerMinute()) : null,
                    "visitsThisStage", stats.companionVisitsThisStage(stage),
                    "visitsThisRebirth", stats.companionVisitsThisRebirth(), "lastBoughtStage", stats.companionLastBoughtStage);
            }
        }
        if (plannedAt == 0 || now < plannedAt) return false;
        // Between fights, like a person: never mid-cook, never over another screen, never on a break.
        if (combat.isCooking() || client.currentScreen != null || combat.isOnBreak()) return false;
        EggHit hit = resolveEgg(client, combat, now);
        if (hit == null) {
            log("companion_skip", "reason", "no-egg", "via", planVia, "scanRadius", cfg.companionEggScanRadius,
                "hologramRadius", cfg.companionEggSearchRadius, "stage", stats.zone);
            dumpPlates(client, combat);
            plannedAt = 0;
            if ("manual".equals(planVia)) return false;
            blockedUntil = now + cfg.companionRetryAfterAbortMs;
            stats.noteCompanionVisit(null, false); // do not retry every 15s
            return false;
        }
        plannedAt = 0;
        visitVia = planVia;
        visitStartedAt = now;
        visitStage = stats.confirmedZoneLevel();
        // Armed before the first spend: this visit's income multiplier is the only evidence
        // the prior is ever replaced by. A visit that starts while an earlier measurement is
        // still open would corrupt it, so that one is abandoned rather than mixed.
        if (gainAt != 0) { gainAt = 0; gainBefore = null; }
        gainBefore = stats.incomePerMinute();
        gainStage = visitStage;
        gainEggs = 0;
        eggAim = hit.aim();
        eggsTarget = HumanTiming.ticks(cfg.companionEggsMin, Math.max(cfg.companionEggsMin, cfg.companionEggsMax));
        eggsOpened = 0;
        opensClicked = 0;
        spent = 0;
        observedEggPrice = null;
        pendingOption = null;
        eggsGuiLogged = false;
        unparsedLogged = false;
        rungLessonTried = false;
        equippedBefore = List.of();
        equippedAfter = List.of();
        storage = List.of();
        deletes = List.of();
        deleteIdx = 0;
        currentZone = null;
        log("companion_visit", "via", visitVia, "stage", visitStage, "eggsTarget", eggsTarget,
            "dist", Math.round(hit.dist() * 10.0) / 10.0, "price", hit.price() != null ? Amounts.format(hit.price()) : null,
            "eggVia", hit.via(), "x", Math.round(hit.aim().x), "y", Math.round(hit.aim().y), "z", Math.round(hit.aim().z),
            "lines", hit.lines());
        combat.releaseKeys(client);
        MouseDriver.INSTANCE.cancel();
        walkStartAt = now;
        bestDist = hit.dist();
        bestDistStart = hit.dist();
        lastProgressAt = now;
        lastLookAt = 0;
        sidestepTicks = 0;
        phase = Phase.WALK;
        return true;
    }

    /**
     * 0.9.35: the economy decides, this decides whether a visit can run. Until now this
     * method was a second, weaker economy — it reached for the zone gap, the last decision
     * and the horizon through back-channels and could disagree with the real one, and its
     * "a batch costs at most companionMaxIncomeMinutes of income, two stages above the last
     * buy, twice a rebirth, never while a zone is affordable" refused every batch the
     * 2026-09-04 lvl15 stall wanted. Economy.decideCompanion prices it now; what is
     * left here is physical feasibility, which the economy cannot see.
     *
     * @return the decision's own reason ("companion-sooner" / "companion-persist"), or null
     *         when nothing is due.
     */
    private String decide(long now) {
        Decision d = upgrades.lastDecision();
        if (d == null || !d.actsCompanion()) return null;
        String blocked = blockedReason(now);
        if (blocked != null) {
            // The economy wants a batch and the visit cannot run: say so, or it is a silent refusal.
            skip(blocked, stats.confirmedZoneLevel());
            return null;
        }
        return d.reason();
    }

    /**
     * Can a visit actually run? Fed back into Economy.Inputs.companionBlocked so the
     * decision never live-locks on a buy that cannot execute. Everything here is physical or
     * a blast-radius cap — never economics. 0.9.36: the settle and the income are no longer
     * feasibility - the economy's sooner rule needs neither, and {@link #incomeSettled} gates
     * only its persist rule.
     */
    public boolean canVisitNow(long now) { return blockedReason(now) == null; }

    /** Why a visit cannot run right now, or null when it can. */
    public String blockedReason(long now) {
        if (!cfg.companionsEnabled) return "disabled";
        if (suspended) return "suspended";
        if (now < blockedUntil) return "abort-cooldown";
        if (phase != Phase.IDLE || plannedAt != 0) return "busy";
        // 0.9.36: the previous batch's income window is still open - a second visit inside it
        // would abandon that measurement, and the gain estimate is the only evidence there is.
        if (gainAt != 0 && now < gainAt) return "gain-window";
        Integer stage = stats.confirmedZoneLevel();
        if (stage == null) return "no-stage";
        if (stats.money() == null) return "no-balance";
        if (stats.companionBatchPrice(stage) == null) return "no-egg-price";
        // Runaway guard only; the real budget is per stage, inside the economy.
        if (cfg.companionMaxVisitsPerRebirth > 0
            && stats.companionVisitsThisRebirth() >= cfg.companionMaxVisitsPerRebirth) return "visits";
        return null;
    }

    /**
     * 0.9.36: the income figure is this stage's own - enough kills on it, or enough time on
     * it (the 60 s reward summary makes the rate honest within two of its windows). Either.
     */
    public boolean incomeSettled(long now) {
        if (lastCombatKills - killsAtStage >= Math.max(0, cfg.companionStageSettleKills)) return true;
        return stageEnteredAt != 0 && now - stageEnteredAt >= Math.max(0, cfg.companionStageSettleMs);
    }

    /**
     * What this visit may still spend: the batch the economy priced, less what has gone
     * already. A manual visit (Ctrl+Shift+toggle) is Drew asking for it, so it gets the
     * balance cap alone.
     */
    private Double visitBudget() {
        if ("manual".equals(visitVia)) {
            Double bal = stats.money();
            return bal == null ? null : bal * Math.max(0, cfg.companionMaxBalancePct) / 100.0;
        }
        Double batch = stats.companionBatchPrice(visitStage);
        if (batch == null) return null;
        return Math.max(0.0, batch - spent);
    }

    /**
     * The income multiplier this batch brought: armed before the first spend, read once the
     * rate has refilled (companionGainWindowMs). This is the whole evidence base for the
     * prior — measured 2.20x on 7 eggs and 1.76x on 8 in the 2026-09-04 logs.
     */
    private void tickGain(long now) {
        if (gainAt == 0 || now < gainAt) return;
        long at = gainAt;
        gainAt = 0;
        Double after = stats.incomePerMinute();
        Double before = gainBefore;
        gainBefore = null;
        if (before == null || before <= 0 || after == null || after <= 0) return;
        double ratio = after / before;
        log("companion_gain", "before", Amounts.format(before), "after", Amounts.format(after),
            "ratio", Math.round(ratio * 100.0) / 100.0, "eggs", gainEggs, "stage", gainStage,
            "windowMs", cfg.companionGainWindowMs, "at", at,
            "equippedMultBefore", multSum(equippedBefore), "equippedMultAfter", multSum(equippedAfter),
            "equippedMaxBefore", multMax(equippedBefore), "equippedMaxAfter", multMax(equippedAfter));
        stats.noteCompanionGain(ratio, gainEggs, gainStage);
    }

    private static Double multSum(List<CompanionLore.Companion> cs) {
        double t = 0;
        boolean any = false;
        for (CompanionLore.Companion c : cs) if (c.multiplier() != null) { t += c.multiplier(); any = true; }
        return any ? t : null;
    }

    private static Double multMax(List<CompanionLore.Companion> cs) {
        Double best = null;
        for (CompanionLore.Companion c : cs) if (c.multiplier() != null && (best == null || c.multiplier() > best)) best = c.multiplier();
        return best;
    }

    private void skip(String reason, Integer stage) {
        long now = System.currentTimeMillis();
        if (now - lastSkipLogAt > 120_000) {
            lastSkipLogAt = now;
            log("companion_skip", "reason", reason, "stage", stage,
                "visitsThisRebirth", stats.companionVisitsThisRebirth(),
                "visitsThisStage", stats.companionVisitsThisStage(stage),
                "lastBoughtStage", stats.companionLastBoughtStage);
        }
    }

    // ------------------------------------------------------------------ egg

    /**
     * The money egg's hologram within companionEggSearchRadius: text displays whose lines
     * (grouped with any plate within a block of them — the server may split the lines over
     * several displays) name a Companion Egg with a money price and no credits. The aim
     * point is the pedestal under the lowest line.
     */
    /**
     * Where the egg is, in order of trust (0.9.29): the block Drew just spotlighted; the
     * dragon-egg block scan paired with its hologram (the Credit egg's says credits); the
     * point saved for this stage; the hologram alone. Null = nothing found.
     */
    private EggHit resolveEgg(MinecraftClient client, CombatController combat, long now) {
        eggAimEntity = false;
        if (pickedAim != null) {
            Vec3d aim = pickedAim;
            pickedAim = null;
            eggAimEntity = pickedAimEntity;
            List<String> lines = hologramNear(client, combat, aim);
            return new EggHit(aim, lines, lore.eggPrice(lines), client.player.getEntityPos().distanceTo(aim), "crosshair");
        }
        EggHit block = findEggByBlock(client, combat);
        if (block != null) return block;
        if (eggStore != null) {
            EggStore.Egg saved = eggStore.get(EggStore.key(stats.zone, cfg.companionStagesPerLocation));
            if (saved != null) {
                Vec3d aim = new Vec3d(saved.x, saved.y, saved.z);
                eggAimEntity = saved.label != null && saved.label.startsWith("entity:");
                List<String> lines = hologramNear(client, combat, aim);
                return new EggHit(aim, lines, lore.eggPrice(lines), client.player.getEntityPos().distanceTo(aim), "saved");
            }
        }
        EggHit holo = lastEgg != null && now - lastEggAt < 30_000 ? lastEgg : findEggByHologram(client, combat);
        return holo;
    }

    /** The point to aim at on an egg block: its centre, a quarter block up (the egg is a cone; its upper half is the clean hit). */
    private static Vec3d eggAimPoint(BlockPos bp) {
        return Vec3d.ofCenter(bp).add(0, 0.25, 0);
    }

    /** Which location (Farm = stages 1–10, Western = 11–20, …) the bot is in, or null. */
    private Integer locationNow() {
        Integer stage = Economy.zoneLevelOf(stats.zone);
        return stage == null ? null : EggStore.locationOf(stage, cfg.companionStagesPerLocation);
    }

    /** 0.9.31: every remembered egg point belongs to one location and one life; drop them when either changes. */
    private void forgetEgg(String reason) {
        boolean had = pickedAim != null || lastEgg != null;
        pickedAim = null;
        pickedAimEntity = false;
        lastEgg = null;
        lastEggAt = 0;
        lastBlockScanAt = 0;
        if (had) log("companion_egg_forget", "reason", reason, "location", eggLocation);
        eggLocation = null;
    }

    /** Plate lines hovering within companionEggHologramReach of a point (companionEggHologramBelow under to companionEggHologramAbove over it). */
    private List<String> hologramNear(MinecraftClient client, CombatController combat, Vec3d at) {
        List<String> lines = new ArrayList<>();
        for (Entity p : combat.nearbyPlates(client, cfg.companionEggSearchRadius)) {
            Vec3d pp = p.getEntityPos();
            double dx = pp.x - at.x, dz = pp.z - at.z, dy = pp.y - at.y;
            if (Math.sqrt(dx * dx + dz * dz) > cfg.companionEggHologramReach
                || dy < -cfg.companionEggHologramBelow || dy > cfg.companionEggHologramAbove) continue;
            lines.addAll(CombatController.plateTextLines(p));
        }
        return lines;
    }

    /**
     * Every minecraft:dragon_egg within the scan box, paired with its hologram. Ours is the
     * one whose hologram names a Companion Egg with a money price (or, with no readable
     * hologram anywhere, the only egg). Runs at most once per 30 s.
     */
    private EggHit findEggByBlock(MinecraftClient client, CombatController combat) {
        long now = System.currentTimeMillis();
        if (now - lastBlockScanAt < 30_000 && lastEgg != null && "block".equals(lastEgg.via())) return lastEgg;
        lastBlockScanAt = now;
        BlockPos me = client.player.getBlockPos();
        int r = Math.max(4, cfg.companionEggScanRadius);
        int v = Math.max(1, cfg.companionEggScanVertical);
        List<BlockPos> eggs = new ArrayList<>();
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -v; dy <= v; dy++) {
                    cursor.set(me.getX() + dx, me.getY() + dy, me.getZ() + dz);
                    if (client.world.getBlockState(cursor).isOf(Blocks.DRAGON_EGG)) eggs.add(cursor.toImmutable());
                }
            }
        }
        if (eggs.isEmpty()) {
            log("companion_egg_scan", "eggs", 0, "radius", r, "vertical", v);
            return null;
        }
        EggHit best = null;
        List<String> seen = new ArrayList<>();
        int unreadable = 0;
        for (BlockPos bp : eggs) {
            Vec3d aim = eggAimPoint(bp);
            List<String> lines = hologramNear(client, combat, aim);
            boolean money = lore.isEggHologram(lines);
            boolean credit = !lines.isEmpty() && !money;
            if (lines.isEmpty()) unreadable++;
            seen.add(bp.getX() + "," + bp.getY() + "," + bp.getZ() + (money ? " money" : credit ? " other" : " no-hologram"));
            if (!money) continue;
            double dist = client.player.getEntityPos().distanceTo(aim);
            if (best == null || dist < best.dist()) best = new EggHit(aim, lines, lore.eggPrice(lines), dist, "block");
        }
        if (best == null) {
            // 0.9.31: no egg paired with a money hologram, but a money hologram exists —
            // the egg block nearest to it (03:34 log: both eggs read "no-hologram"/"other"
            // and the visit right-clicked an armor stand two blocks under the real egg).
            EggHit holo = findEggByHologram(client, combat);
            if (holo != null) {
                double bestD = Double.MAX_VALUE;
                BlockPos bestBp = null;
                for (BlockPos bp : eggs) {
                    double ddx = bp.getX() + 0.5 - holo.aim().x, ddz = bp.getZ() + 0.5 - holo.aim().z;
                    double d = Math.sqrt(ddx * ddx + ddz * ddz);
                    if (d <= cfg.companionEggHologramReach * 2 && d < bestD) { bestD = d; bestBp = bp; }
                }
                if (bestBp != null) {
                    Vec3d aim = eggAimPoint(bestBp);
                    best = new EggHit(aim, holo.lines(), holo.price(), client.player.getEntityPos().distanceTo(aim), "block-near-hologram");
                }
            }
        }
        if (best == null && eggs.size() == 1 && unreadable == 1) {
            Vec3d aim = eggAimPoint(eggs.get(0));
            best = new EggHit(aim, List.of(), null, client.player.getEntityPos().distanceTo(aim), "block-only");
        }
        log("companion_egg_scan", "eggs", eggs.size(), "radius", r, "vertical", v, "found", seen,
            "chosen", best != null ? best.via() : null);
        if (best != null) { lastEgg = best; lastEggAt = now; eggLocation = locationNow(); }
        // 0.9.33: an egg found by its block and hologram is worth remembering (only the manual
        // spotlight wrote the egg file before), so the next visit at this location walks straight to it.
        if (best != null && eggStore != null && stats.zone != null
            && ("block".equals(best.via()) || "block-near-hologram".equals(best.via()))) {
            String key = EggStore.key(stats.zone, cfg.companionStagesPerLocation);
            EggStore.Egg saved = eggStore.get(key);
            Vec3d aim = best.aim();
            boolean moved = saved == null || Math.abs(saved.x - aim.x) > 1.0 || Math.abs(saved.y - aim.y) > 1.0 || Math.abs(saved.z - aim.z) > 1.0;
            if (moved) {
                EggStore.Egg e = new EggStore.Egg();
                e.x = aim.x; e.y = aim.y; e.z = aim.z;
                e.label = "scan:" + best.via();
                e.at = now;
                eggStore.put(key, e);
                log("companion_egg_saved", "via", "scan", "key", key, "x", Math.round(aim.x), "y", Math.round(aim.y),
                    "z", Math.round(aim.z), "replaced", saved != null);
            }
        }
        return best;
    }

    /** The egg by its hologram alone (secondary; also the price evidence as the bot passes). */
    private EggHit findEggByHologram(MinecraftClient client, CombatController combat) {
        List<Entity> plates = combat.nearbyPlates(client, cfg.companionEggSearchRadius);
        EggHit best = null;
        Set<Integer> used = new HashSet<>();
        for (Entity p : plates) {
            if (used.contains(p.getId())) continue;
            Vec3d pp = p.getEntityPos();
            List<String> lines = new ArrayList<>();
            double lowestY = pp.y;
            List<Integer> group = new ArrayList<>();
            for (Entity q : plates) {
                Vec3d qp = q.getEntityPos();
                if (Math.abs(qp.x - pp.x) > 1.5 || Math.abs(qp.z - pp.z) > 1.5 || Math.abs(qp.y - pp.y) > 4.0) continue;
                lines.addAll(CombatController.plateTextLines(q));
                lowestY = Math.min(lowestY, qp.y);
                group.add(q.getId());
            }
            if (!lore.isEggHologram(lines)) continue;
            used.addAll(group);
            Vec3d aim = new Vec3d(pp.x, lowestY - cfg.companionEggAimDrop, pp.z);
            double dist = client.player.getEntityPos().distanceTo(aim);
            if (best == null || dist < best.dist()) best = new EggHit(aim, lines, lore.eggPrice(lines), dist, "hologram");
        }
        return best;
    }

    /** Evidence when nothing was found: the nearest plates, their kinds and lines. */
    private void dumpPlates(MinecraftClient client, CombatController combat) {
        List<Entity> plates = combat.nearbyPlates(client, cfg.companionEggSearchRadius);
        Vec3d me = client.player.getEntityPos();
        plates.sort((a, b) -> Double.compare(a.getEntityPos().distanceTo(me), b.getEntityPos().distanceTo(me)));
        List<String> out = new ArrayList<>();
        for (Entity p : plates) {
            if (out.size() >= 25) break;
            List<String> lines = CombatController.plateTextLines(p);
            out.add(p.getType().getName().getString() + "@" + Math.round(p.getEntityPos().distanceTo(me)) + ": " + String.join(" | ", lines));
        }
        log("companion_plates", "count", plates.size(), "nearest", out);
    }

    private static float[] anglesTo(MinecraftClient client, Vec3d aim) {
        Vec3d eye = client.player.getEyePos();
        double dx = aim.x - eye.x, dy = aim.y - eye.y, dz = aim.z - eye.z;
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        float wantYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float wantPitch = (float) -Math.toDegrees(Math.atan2(dy, distXZ));
        return new float[] { wantYaw, MathHelper.clamp(wantPitch, -89f, 89f) };
    }

    /** Walking: look roughly level, a little down when close. */
    private static float walkPitch(MinecraftClient client, double dist) {
        return dist > 8 ? 5f : 15f;
    }

    private static void releaseWalkKeys(MinecraftClient client) {
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
    }

    // ------------------------------------------------------------- companions

    /**
     * 0.9.35: equipped means the lore offers to un-equip it. The configured slot list said
     * {0,1,2,3} while the real GUI equips 1, 2, 3 and 5 (4 is Equip Best, 6-7 are locked,
     * 0 is empty), so this saw three of four and filed the fourth as storage — which let
     * prepareDeletes plan a bulk delete over a pair an equipped companion held. The slot
     * list is the fallback for a layout with no un-equip line.
     */
    private void readCompanions(List<Entry> entries, boolean before) {
        List<CompanionLore.Companion> eq = new ArrayList<>();
        List<CompanionLore.Companion> st = new ArrayList<>();
        Set<Integer> equipSlots = new HashSet<>();
        if (cfg.companionEquipSlots != null) equipSlots.addAll(cfg.companionEquipSlots);
        boolean anyUnequip = false;
        for (Entry e : entries) if (lore.isEquipped(e.lore())) { anyUnequip = true; break; }
        Integer maxZone = null;
        for (Entry e : entries) {
            CompanionLore.Companion c = lore.companion(e.slot(), e.name(), e.lore());
            if (c == null) continue;
            boolean equipped = anyUnequip ? lore.isEquipped(e.lore()) : equipSlots.contains(e.slot());
            if (equipped) eq.add(c); else st.add(c);
            if (c.zone() != null && (maxZone == null || c.zone() > maxZone)) maxZone = c.zone();
        }
        if (before) equippedBefore = eq; else equippedAfter = eq;
        storage = st;
        if (currentZone == null) currentZone = maxZone;
    }

    private void prepareDeletes() {
        deletes = List.of();
        if (!cfg.companionBulkDeleteEnabled) return;
        List<CompanionLore.ZoneStage> st = new ArrayList<>();
        for (CompanionLore.Companion c : storage) if (c.zoneStage() != null) st.add(c.zoneStage());
        List<CompanionLore.ZoneStage> eq = new ArrayList<>();
        for (CompanionLore.Companion c : equippedAfter.isEmpty() ? equippedBefore : equippedAfter) if (c.zoneStage() != null) eq.add(c.zoneStage());
        List<CompanionLore.ZoneStage> all = CompanionLore.deletePairs(st, eq, currentZone, cfg.companionKeepZones);
        if (all.size() > cfg.companionMaxBulkDeletes) all = new ArrayList<>(all.subList(0, Math.max(0, cfg.companionMaxBulkDeletes)));
        deletes = all;
        log("companion_delete_plan", "currentZone", currentZone, "keepZones", cfg.companionKeepZones,
            "storage", st.size(), "equipped", eq.size(), "pairs", describePairs(deletes));
    }

    private static List<String> describePairs(List<CompanionLore.ZoneStage> pairs) {
        List<String> out = new ArrayList<>();
        for (CompanionLore.ZoneStage zs : pairs) out.add(zs.zone() + ":" + zs.stage());
        return out;
    }

    private static List<String> summaries(List<CompanionLore.Companion> cs) {
        List<String> out = new ArrayList<>();
        for (CompanionLore.Companion c : cs) out.add(c.summary());
        return out;
    }

    private void onEggsGuiGone(MinecraftClient client, long now) {
        log("companion_egg_gui_gone", "title", title(client), "eggs", eggsOpened, "opens", opensClicked);
        if (client.currentScreen != null && !isOurGui(client)) EnchantScreens.closeGui(client);
        phase = Phase.CLOSE_EGG;
        phaseUntil = now + GuiHuman.closeDelayMs(cfg);
    }

    private void finish(MinecraftClient client, CombatController combat) {
        releaseWalkKeys(client);
        typer.cancel(client);
        MouseDriver.INSTANCE.cancel();
        // We walked while combat did not tick: its last position is stale, and the stop
        // protocol would read the gap as a teleport. A fresh start clears it.
        combat.reset(client);
        phase = Phase.IDLE;
    }

    private void abort(MinecraftClient client, CombatController combat, String why) {
        log("companion_abort", "reason", why, "phase", phase.name().toLowerCase(Locale.ROOT), "eggs", eggsOpened,
            "opens", opensClicked, "visitMs", System.currentTimeMillis() - visitStartedAt);
        if (isOurGui(client)) EnchantScreens.closeGui(client);
        finish(client, combat);
        // The economy keeps asking every eval; without this it live-locks on a buy that
        // cannot run (and canVisitNow would keep saying yes).
        blockedUntil = System.currentTimeMillis() + cfg.companionRetryAfterAbortMs;
        if (++consecutiveAborts >= Math.max(1, cfg.companionMaxConsecutiveAborts)) {
            suspended = true;
            log("companion_suspended", "aborts", consecutiveAborts);
        }
    }

    // ---------------------------------------------------------------- screens

    private static String title(MinecraftClient client) {
        if (client.currentScreen == null || client.currentScreen.getTitle() == null) return null;
        return client.currentScreen.getTitle().getString();
    }

    private boolean eggsGuiOpen(MinecraftClient client) {
        return client.currentScreen instanceof HandledScreen && lore.isEggsTitle(title(client));
    }

    private boolean companionsGuiOpen(MinecraftClient client) {
        return client.currentScreen instanceof HandledScreen && lore.isCompanionsTitle(title(client));
    }

    private boolean fuseGuiOpen(MinecraftClient client) {
        return client.currentScreen instanceof HandledScreen && lore.isFuseTitle(title(client));
    }

    private static ScreenHandler handler(MinecraftClient client) {
        return client.currentScreen instanceof HandledScreen<?> hs ? hs.getScreenHandler() : null;
    }

    /** Non-empty container slots (player inventory excluded), in slot order. */
    private static List<Entry> containerItems(MinecraftClient client) {
        List<Entry> out = new ArrayList<>();
        for (GuiHuman.Item it : GuiHuman.items(client)) out.add(new Entry(it.slot(), it.name(), it.lore()));
        return out;
    }

    private static List<String> describe(List<Entry> entries) {
        List<String> out = new ArrayList<>();
        for (Entry e : entries) out.add(e.slot() + ":" + e.name() + (e.lore().isEmpty() ? "" : " | " + String.join(" | ", e.lore())));
        return out;
    }

    private static Double tenth(Double v) {
        return v == null ? null : Math.round(v * 10.0) / 10.0;
    }
}

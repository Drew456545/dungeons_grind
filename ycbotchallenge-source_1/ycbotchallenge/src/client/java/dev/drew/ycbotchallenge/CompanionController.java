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
 * Trigger is a sliding window on price vs income (Drew: at the top stage a batch costs
 * ~1/4 of the rebirth): once the stage's income has settled, buy when an open is at
 * most companionMaxIncomeMinutes of income, the stage is companionMinStageGain above
 * the last purchase and the rebirth has fewer than companionMaxVisitsPerRebirth visits;
 * when zone buys stop with no visit this rebirth, one visit within
 * companionEndOfRebirthMaxIncomeMinutes. Ctrl+Shift+toggle runs it by hand.
 *
 * Every GUI is dumped verbatim (companion_gui) — the fixture net for the next version.
 */
public class CompanionController {
    private enum Phase { IDLE, WALK, AIM, OPEN_WAIT, EGG_LOOK, BUY, BUY_SETTLE, CLOSE_EGG, TYPE_COMPANION, COMP_WAIT,
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
    private int visitsThisRebirth = 0;
    private boolean endFallbackDone = false;
    private Integer lastBoughtStage = null;
    private Integer lastPriceLogStage = null;
    private int zoneSeqSeen = -1;
    private int killsAtStage = 0;
    private long lastEggScanAt = 0;
    private long lastDecisionAt = 0;
    private long lastSkipLogAt = 0;
    private EggHit lastEgg = null;
    private long lastEggAt = 0;
    private long lastBlockScanAt = 0;
    private EggStore eggStore;
    /** A spotlighted egg (Ctrl+Shift+toggle on a block) waiting for the next visit. */
    private Vec3d pickedAim = null;

    // walk / aim
    private Vec3d eggAim = null;
    private long walkStartAt;
    private double bestDist;
    private long lastProgressAt;
    private long lastLookAt;
    private int sidestepTicks;
    private int aimTry;
    private long aimIssuedAt;
    private static final float[] AIM_PITCH_OFFSETS = {0f, 6f, 12f, -6f, 18f, 24f, 30f};

    // buy
    private int eggsTarget;
    private int eggsOpened;
    private int opensClicked;
    private double spent;
    private Double balanceBefore;
    private CompanionLore.OpenOption pendingOption;
    private Double observedEggPrice;
    private boolean eggsGuiLogged;

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
        if (hit != null && hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult bh) {
            aim = Vec3d.ofCenter(bh.getBlockPos());
            what = client.world != null ? Registries.BLOCK.getId(client.world.getBlockState(bh.getBlockPos()).getBlock()).toString() : "block";
        } else if (hit != null && hit.getType() == HitResult.Type.ENTITY && hit instanceof EntityHitResult eh) {
            aim = eh.getEntity().getEntityPos();
            what = "entity:" + eh.getEntity().getType().getName().getString();
        }
        if (aim == null || client.player.getEyePos().distanceTo(aim) > 6.5) return null;
        pickedAim = aim;
        String key = EggStore.key(stage);
        if (eggStore != null) {
            EggStore.Egg e = new EggStore.Egg();
            e.x = aim.x; e.y = aim.y; e.z = aim.z;
            e.label = what;
            e.at = System.currentTimeMillis();
            eggStore.put(key, e);
        }
        log("companion_egg_spotlight", "stage", key, "what", what, "x", Math.round(aim.x), "y", Math.round(aim.y), "z", Math.round(aim.z));
        return "egg for " + key + " saved at " + Math.round(aim.x) + ", " + Math.round(aim.y) + ", " + Math.round(aim.z) + " (" + what + ").";
    }

    public String hudLine() {
        if (!cfg.companionsEnabled) return null;
        if (phase == Phase.IDLE) {
            if (suspended) return "companions: suspended after repeated aborts (toggle to reset)";
            if (plannedAt != 0) return "companions: visit in " + Math.max(0, (plannedAt - System.currentTimeMillis() + 999) / 1000) + "s (" + planVia + ")";
            return null;
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
        zoneSeqSeen = stats.zoneChangeSeq();
        manualRequested = false;
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
                if (aimIssuedAt == 0) {
                    if (aimTry >= AIM_PITCH_OFFSETS.length) { abort(client, combat, "no-egg-block"); return false; }
                    float[] yp = anglesTo(client, eggAim);
                    MouseDriver.INSTANCE.cancel();
                    MouseDriver.INSTANCE.lookTo(client, yp[0], MathHelper.clamp(yp[1] + AIM_PITCH_OFFSETS[aimTry], -89f, 89f), "companion-aim");
                    aimIssuedAt = now;
                    return true;
                }
                if (now - aimIssuedAt < 450) return true;
                HitResult hit = client.crosshairTarget;
                String what = null;
                double hitDist = -1;
                if (hit != null && hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult bh) {
                    hitDist = hit.getPos().distanceTo(eggAim);
                    what = Registries.BLOCK.getId(client.world.getBlockState(bh.getBlockPos()).getBlock()).toString();
                } else if (hit != null && hit.getType() == HitResult.Type.ENTITY && hit instanceof EntityHitResult eh) {
                    hitDist = hit.getPos().distanceTo(eggAim);
                    what = "entity:" + eh.getEntity().getType().getName().getString();
                }
                if (what != null && hitDist <= cfg.companionEggHitRadius) {
                    log("companion_aim", "hit", what, "hitDist", Math.round(hitDist * 100.0) / 100.0, "try", aimTry);
                    EnchantScreens.pressUse(client, false);
                    phase = Phase.OPEN_WAIT;
                    phaseUntil = now + cfg.companionOpenTimeoutMs;
                    return true;
                }
                aimTry++;
                aimIssuedAt = 0;
                return true;
            }
            case OPEN_WAIT -> {
                if (eggsGuiOpen(client)) {
                    phase = Phase.EGG_LOOK;
                    phaseUntil = now + HumanTiming.logNormalMs(cfg.rebirthLookMinMs, cfg.rebirthLookMaxMs);
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
                for (Entry e : containerItems(client)) {
                    CompanionLore.OpenOption o = lore.openOption(e.slot(), e.name(), e.lore());
                    if (o == null) continue;
                    // The first open is the probe: the hologram's "$121.3300" format is unverified,
                    // so once a balance delta has been seen, the observed per-egg price rules.
                    Double price = observedEggPrice != null ? observedEggPrice * o.count() : o.price();
                    options.add(new CompanionLore.OpenOption(o.slot(), o.name(), o.count(), price));
                }
                int eggsLeft = eggsTarget - eggsOpened;
                Double income = stats.incomePerMinute();
                Double bal = stats.money();
                double maxMinutes = "end-of-rebirth".equals(visitVia) ? cfg.companionEndOfRebirthMaxIncomeMinutes : cfg.companionMaxIncomeMinutes;
                if ("manual".equals(visitVia)) maxMinutes = Math.max(maxMinutes, cfg.companionEndOfRebirthMaxIncomeMinutes);
                CompanionLore.OpenOption pick = CompanionLore.pickOpen(options, eggsLeft, income, maxMinutes, bal, cfg.companionMaxBalancePct);
                if (pick == null || opensClicked >= cfg.companionMaxOpensPerVisit) {
                    log("companion_skip", "reason", pick == null ? (options.isEmpty() ? "no-open-item" : "price") : "open-cap",
                        "eggsLeft", eggsLeft, "opens", opensClicked, "options", options.size(),
                        "incomePerMin", income != null ? Amounts.format(income) : null, "balance", bal != null ? Amounts.format(bal) : null,
                        "maxMinutes", maxMinutes);
                    phase = Phase.CLOSE_EGG;
                    phaseUntil = now + HumanTiming.logNormalMs(400, 1200);
                    return true;
                }
                pendingOption = pick;
                balanceBefore = bal;
                EnchantScreens.click(client, handler(client), pick.slot());
                opensClicked++;
                log("companion_open_click", "slot", pick.slot(), "count", pick.count(),
                    "price", pick.price() != null ? Amounts.format(pick.price()) : null,
                    "minutes", tenth(CompanionLore.incomeMinutes(pick.price(), income)), "opens", opensClicked);
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
                }
                log("companion_open", "count", pendingOption != null ? pendingOption.count() : null, "bought", bought,
                    "paid", bought ? Amounts.format(delta) : null, "perEgg", bought ? Amounts.format(observedEggPrice) : null,
                    "listed", pendingOption != null && pendingOption.price() != null ? Amounts.format(pendingOption.price()) : null,
                    "eggs", eggsOpened, "target", eggsTarget, "guiOpen", eggsGuiOpen(client));
                pendingOption = null;
                if (!eggsGuiOpen(client)) { onEggsGuiGone(client, now); return true; }
                if (!bought || eggsOpened >= eggsTarget || opensClicked >= cfg.companionMaxOpensPerVisit) {
                    phase = Phase.CLOSE_EGG;
                    phaseUntil = now + HumanTiming.logNormalMs(400, 1200);
                } else {
                    phase = Phase.BUY;
                }
            }
            case CLOSE_EGG -> {
                if (now < phaseUntil) return true;
                if (isOurGui(client)) EnchantScreens.closeGui(client);
                phase = Phase.TYPE_COMPANION;
                phaseUntil = now + HumanTiming.logNormalMs(1500, 3500);
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
                    phaseUntil = now + HumanTiming.logNormalMs(cfg.rebirthLookMinMs, cfg.rebirthLookMaxMs);
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
                    phaseUntil = now + HumanTiming.logNormalMs(400, 1200);
                    return true;
                }
                phase = Phase.EQUIP;
                phaseUntil = now + HumanTiming.logNormalMs(300, 900);
            }
            case EQUIP -> {
                if (!companionsGuiOpen(client)) { abort(client, combat, "companions-gui-closed"); return false; }
                if (now < phaseUntil) return true;
                EnchantScreens.click(client, handler(client), equipSlot);
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
                    phaseUntil = now + HumanTiming.logNormalMs(1500, 3500);
                    return true;
                }
                List<Entry> entries = containerItems(client);
                readCompanions(entries, false);
                log("companion_equip", "guiOpen", true, "before", summaries(equippedBefore), "after", summaries(equippedAfter),
                    "storage", storage.size());
                phase = Phase.FUSE_CLICK;
                phaseUntil = now + HumanTiming.logNormalMs(600, 1800);
            }
            case FUSE_CLICK -> {
                if (now < phaseUntil) return true;
                if (!companionsGuiOpen(client) || fuseSlot < 0) {
                    if (fuseSlot < 0) log("companion_skip", "reason", "no-fuse-item");
                    prepareDeletes();
                    phase = Phase.DELETE;
                    deleteIdx = 0;
                    if (isOurGui(client)) EnchantScreens.closeGui(client);
                    phaseUntil = now + HumanTiming.logNormalMs(1500, 3500);
                    return true;
                }
                EnchantScreens.click(client, handler(client), fuseSlot);
                log("companion_fuse_click", "slot", fuseSlot);
                phase = Phase.FUSE_WAIT;
                phaseUntil = now + cfg.companionOpenTimeoutMs;
            }
            case FUSE_WAIT -> {
                if (fuseGuiOpen(client)) {
                    phase = Phase.FUSE_LOG;
                    phaseUntil = now + HumanTiming.logNormalMs(cfg.rebirthLookMinMs, cfg.rebirthLookMaxMs);
                } else if (now >= phaseUntil) {
                    log("companion_skip", "reason", "no-fuse-gui", "title", title(client));
                    prepareDeletes();
                    phase = Phase.DELETE;
                    deleteIdx = 0;
                    if (isOurGui(client)) EnchantScreens.closeGui(client);
                    phaseUntil = now + HumanTiming.logNormalMs(1500, 3500);
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
                phaseUntil = now + HumanTiming.logNormalMs(1500, 3500);
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
                visitsThisRebirth++;
                if (eggsOpened > 0 && visitStage != null) lastBoughtStage = visitStage;
                log("companion_visit_done", "via", visitVia, "eggs", eggsOpened, "opens", opensClicked,
                    "spent", spent > 0 ? Amounts.format(spent) : null,
                    "perEgg", observedEggPrice != null ? Amounts.format(observedEggPrice) : null,
                    "stage", visitStage, "before", summaries(equippedBefore), "after", summaries(equippedAfter),
                    "deletes", deletes.size(), "visitMs", now - visitStartedAt, "visitsThisRebirth", visitsThisRebirth);
                consecutiveAborts = 0;
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
            lastRebirthSeen = rb;
            visitsThisRebirth = 0;
            endFallbackDone = false;
            plannedAt = 0;
        }
        int zseq = stats.zoneChangeSeq();
        if (zseq != zoneSeqSeen) {
            zoneSeqSeen = zseq;
            killsAtStage = combat.kills;
        }
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
            String via = decide(combat, now);
            if (via != null) {
                long delay = HumanTiming.logNormalMs(cfg.companionDelayMinMs, Math.max(cfg.companionDelayMinMs + 1, cfg.companionDelayMaxMs));
                plannedAt = now + delay;
                planVia = via;
                log("companion_plan", "via", via, "delayMs", delay, "stage", stats.confirmedZoneLevel(),
                    "price", lastEgg != null && lastEgg.price() != null ? Amounts.format(lastEgg.price()) : null,
                    "incomePerMin", stats.incomePerMinute() != null ? Amounts.format(stats.incomePerMinute()) : null,
                    "visitsThisRebirth", visitsThisRebirth, "lastBoughtStage", lastBoughtStage);
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
            visitsThisRebirth++; // do not retry every 15s; the next rebirth gets another look
            return false;
        }
        plannedAt = 0;
        visitVia = planVia;
        visitStartedAt = now;
        visitStage = stats.confirmedZoneLevel();
        eggAim = hit.aim();
        eggsTarget = HumanTiming.ticks(cfg.companionEggsMin, Math.max(cfg.companionEggsMin, cfg.companionEggsMax));
        eggsOpened = 0;
        opensClicked = 0;
        spent = 0;
        observedEggPrice = null;
        pendingOption = null;
        eggsGuiLogged = false;
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

    /** "cheap" / "end-of-rebirth" when a visit is due now, else null (skips rate-limited to the log). */
    private String decide(CombatController combat, long now) {
        Integer stage = stats.confirmedZoneLevel();
        if (stage == null || lastEgg == null || lastEgg.price() == null) return null;
        if (combat.kills - killsAtStage < cfg.companionStageSettleKills) return null;
        Double income = stats.incomePerMinute();
        Double bal = stats.money();
        if (income == null || income <= 0 || bal == null) return skip("no-income", stage, null);
        double batch = lastEgg.price() * Math.max(1, cfg.companionEggsMin);
        double minutes = batch / income;
        boolean underBalance = batch <= bal * Math.max(0, cfg.companionMaxBalancePct) / 100.0;
        if (visitsThisRebirth >= cfg.companionMaxVisitsPerRebirth) return skip("visits", stage, minutes);
        boolean stageOk = lastBoughtStage == null || stage >= lastBoughtStage + cfg.companionMinStageGain;
        if (minutes <= cfg.companionMaxIncomeMinutes && underBalance) {
            if (stageOk) return "cheap";
            return skip("stage-gain", stage, minutes);
        }
        Double eta = Economy.rebirthEtaMin(bal, stats.rebirthTarget, income);
        boolean zoneStopped = "zone".equals(upgrades.horizonBlockedKind()) || stats.zoneMaxed
            || (eta != null && eta <= cfg.companionRebirthEtaMinMax);
        if (zoneStopped && visitsThisRebirth == 0 && !endFallbackDone
            && minutes <= cfg.companionEndOfRebirthMaxIncomeMinutes && underBalance) {
            endFallbackDone = true;
            return "end-of-rebirth";
        }
        return skip(underBalance ? "price" : "balance", stage, minutes);
    }

    private String skip(String reason, Integer stage, Double minutes) {
        long now = System.currentTimeMillis();
        if (now - lastSkipLogAt > 120_000) {
            lastSkipLogAt = now;
            log("companion_skip", "reason", reason, "stage", stage, "minutesPerBatch", tenth(minutes),
                "visitsThisRebirth", visitsThisRebirth, "lastBoughtStage", lastBoughtStage);
        }
        return null;
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
        if (pickedAim != null) {
            Vec3d aim = pickedAim;
            pickedAim = null;
            List<String> lines = hologramNear(client, combat, aim);
            return new EggHit(aim, lines, lore.eggPrice(lines), client.player.getEntityPos().distanceTo(aim), "crosshair");
        }
        EggHit block = findEggByBlock(client, combat);
        if (block != null) return block;
        if (eggStore != null) {
            EggStore.Egg saved = eggStore.get(stats.zone);
            if (saved != null) {
                Vec3d aim = new Vec3d(saved.x, saved.y, saved.z);
                List<String> lines = hologramNear(client, combat, aim);
                return new EggHit(aim, lines, lore.eggPrice(lines), client.player.getEntityPos().distanceTo(aim), "saved");
            }
        }
        EggHit holo = lastEgg != null && now - lastEggAt < 30_000 ? lastEgg : findEggByHologram(client, combat);
        return holo;
    }

    /** Plate lines hovering within companionEggHologramReach of a point (0–4 blocks above it). */
    private List<String> hologramNear(MinecraftClient client, CombatController combat, Vec3d at) {
        List<String> lines = new ArrayList<>();
        for (Entity p : combat.nearbyPlates(client, cfg.companionEggSearchRadius)) {
            Vec3d pp = p.getEntityPos();
            double dx = pp.x - at.x, dz = pp.z - at.z, dy = pp.y - at.y;
            if (Math.sqrt(dx * dx + dz * dz) > cfg.companionEggHologramReach || dy < -0.5 || dy > 4.5) continue;
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
            Vec3d aim = Vec3d.ofCenter(bp);
            List<String> lines = hologramNear(client, combat, aim);
            boolean money = lore.isEggHologram(lines);
            boolean credit = !lines.isEmpty() && !money;
            if (lines.isEmpty()) unreadable++;
            seen.add(bp.getX() + "," + bp.getY() + "," + bp.getZ() + (money ? " money" : credit ? " other" : " no-hologram"));
            if (!money) continue;
            double dist = client.player.getEntityPos().distanceTo(aim);
            if (best == null || dist < best.dist()) best = new EggHit(aim, lines, lore.eggPrice(lines), dist, "block");
        }
        if (best == null && eggs.size() == 1 && unreadable == 1) {
            Vec3d aim = Vec3d.ofCenter(eggs.get(0));
            best = new EggHit(aim, List.of(), null, client.player.getEntityPos().distanceTo(aim), "block-only");
        }
        log("companion_egg_scan", "eggs", eggs.size(), "radius", r, "vertical", v, "found", seen,
            "chosen", best != null ? best.via() : null);
        if (best != null) { lastEgg = best; lastEggAt = now; }
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

    private void readCompanions(List<Entry> entries, boolean before) {
        List<CompanionLore.Companion> eq = new ArrayList<>();
        List<CompanionLore.Companion> st = new ArrayList<>();
        Set<Integer> equipSlots = new HashSet<>();
        if (cfg.companionEquipSlots != null) equipSlots.addAll(cfg.companionEquipSlots);
        Integer maxZone = null;
        for (Entry e : entries) {
            CompanionLore.Companion c = lore.companion(e.slot(), e.name(), e.lore());
            if (c == null) continue;
            if (equipSlots.contains(e.slot())) eq.add(c); else st.add(c);
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
        phaseUntil = now + HumanTiming.logNormalMs(400, 1200);
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
        ScreenHandler h = handler(client);
        if (h == null || h.slots == null) return out;
        int chestEnd = Math.max(0, h.slots.size() - 36);
        for (int i = 0; i < chestEnd; i++) {
            Slot slot = h.slots.get(i);
            if (slot == null) continue;
            ItemStack stack = slot.getStack();
            if (stack == null || stack.isEmpty()) continue;
            out.add(new Entry(slot.id, EnchantScreens.name(stack), EnchantScreens.loreLines(stack)));
        }
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

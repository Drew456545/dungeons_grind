package dev.drew.ycbotchallenge;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class YCBotChallengeClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("ycbotchallenge");
    public static boolean enabled = false;
    /** Why the bot last auto-paused (e.g. "captcha"); null after a manual re-enable. */
    public static String pausedReason = null;
    private static long lastOnAt = 0;
    private static long lastOffAt = 0;

    /** The per-event bot flag (0.9.33): "on", "off", or "paused:<reason>" (captcha, stopped). */
    public static String botFlag() {
        if (enabled) return "on";
        return pausedReason != null ? "paused:" + pausedReason : "off";
    }

    private YCBotChallengeConfig config;
    private Path configPath;
    private StatsTracker stats;
    private CombatController combat;
    private UpgradeController upgrades;
    private EnchantController enchants;
    private RebirthUpgradeController rebirthUpgrades;
    private CompanionController companions;
    private TranscendController transcend;
    private BossEventController bossEvent;
    /** 0.9.38: the title overlay, handed over by InGameHudMixin and read on the client tick. */
    private static volatile String titleText = null;
    private static volatile String subtitleText = null;

    public static void onTitle(String text) { titleText = text; }

    public static void onSubtitle(String text) { subtitleText = text; }
    private CaptchaSolver captchaSolver;
    private CaptchaDetector captchaDetector;
    private EventLogger logger;
    private KeyBinding toggleKey;
    private KeyBinding optionsKey;
    private int tickCounter = 0;
    private long lastStatusAt = 0;
    private long guiRetryBlockUntil = 0;
    /** When the current container screen first appeared (0 = none open); see guiRecognizeGraceMs. */
    private long guiSeenAt = 0;

    @Override
    public void onInitializeClient() {
        configPath = FabricLoader.getInstance().getConfigDir().resolve("ycbotchallenge.json");
        config = YCBotChallengeConfig.load(configPath);
        Amounts.configure(config.suffixScales);
        stats = new StatsTracker(config);
        stats.setStateStore(new StateStore(FabricLoader.getInstance().getConfigDir().resolve("ycbotchallenge-state.json")));
        stats.setSuffixStore(new SuffixStore(FabricLoader.getInstance().getConfigDir().resolve("ycbotchallenge-suffixes.json")));
        combat = new CombatController(config, stats);
        combat.setIgnoreStore(new IgnoreStore(FabricLoader.getInstance().getConfigDir().resolve("ycbotchallenge-ignored.json")));
        upgrades = new UpgradeController(config, stats);
        enchants = new EnchantController(config, stats);
        rebirthUpgrades = new RebirthUpgradeController(config, stats);
        companions = new CompanionController(config, stats, upgrades);
        // 0.9.35: each needs the other — the economy prices the batch, the controller says
        // whether a visit can actually run.
        upgrades.attachCompanions(companions);
        enchants.attachUpgrades(upgrades);
        companions.setEggStore(new EggStore(FabricLoader.getInstance().getConfigDir().resolve("ycbotchallenge-eggs.json")));
        transcend = new TranscendController(config, enchants.lore());
        bossEvent = new BossEventController(config, stats, upgrades);
        stats.bossEventBusy = () -> bossEvent.isBusy();
        MouseDriver.INSTANCE.configure(config, null);
        captchaSolver = new CaptchaSolver(config, new CaptchaSolver.Callbacks() {
            @Override public void onSolved(MinecraftClient client) {
                // stay enabled; combat resumes on the next tick. Drop any captcha
                // chat line that arrived mid-solve (e.g. the server re-prompting)
                // so we don't immediately re-trigger on the one we just solved.
                stats.captchaMessage = null;
                guiRetryBlockUntil = 0;
            }
            @Override public void onFailed(MinecraftClient client, String stage, String why) {
                pauseForCaptcha(client, "autosolve", stage, why);
            }
        }, FabricLoader.getInstance().getGameDir().resolve("ycbotchallenge-logs"));
        captchaDetector = new CaptchaDetector(config, stats);

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.ycbotchallenge.toggle",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            KeyBinding.Category.MISC));

        optionsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.ycbotchallenge.options",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_Y,
            KeyBinding.Category.MISC));

        HudElementRegistry.addLast(
            Identifier.of("ycbotchallenge", "hud"),
            (context, tickCounter) -> new HudOverlay(config, stats, combat, captchaSolver, upgrades, enchants, rebirthUpgrades, companions, transcend, bossEvent).render(context));

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            stats.onGameMessage(message, overlay);
            if (!overlay) {
                captchaSolver.onGameMessage(message.getString());
                String all = message.getString();
                if (all != null) for (String line : all.split("\\R")) transcend.onChatLine(ChatClassifier.clean(line));
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            setEnabled(client, false, true);
            transcend.reset();
            closeLogger("disconnect");
        });

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);

        LOGGER.info("YCBotChallenge loaded — press G in game to toggle, Shift+G to toggle sprint, Ctrl+G to ignore the mob you look at, Y for the options screen.");
    }

    private void onTick(MinecraftClient client) {
        while (optionsKey.wasPressed()) {
            if (client.currentScreen == null && client.player != null) client.setScreen(newOptionsScreen());
        }
        while (toggleKey.wasPressed()) {
            if (client.currentScreen instanceof BotOptionsScreen) continue; // buttons, not hotkeys, on that screen
            // Ctrl + Shift + toggle = run the companion visit now (0.9.28);
            // Ctrl + toggle = ignore/unignore the mob under the crosshair (0.9.26);
            // Shift + toggle = flip sprinting (persisted to the config); plain = bot on/off
            if (ctrlHeld(client) && shiftHeld(client)) runCompanions(client);
            else if (ctrlHeld(client)) markIgnored(client);
            else if (shiftHeld(client)) toggleSprint(client);
            else setEnabled(client, !enabled, false);
        }
        if (client.player == null || client.world == null) return;

        tickCounter++;
        if (tickCounter % 20 == 0) stats.poll(client);
        stats.onTitle(titleText, subtitleText);

        if (!enabled) return;

        // 0.9.30: our own options screen is open — hands off the keys, nothing else runs.
        if (client.currentScreen instanceof BotOptionsScreen) {
            combat.releaseKeys(client);
            return;
        }

        // Captcha solving in progress: keep the player inert and drive the solver.
        if (captchaSolver.isActive()) {
            combat.releaseKeys(client);
            captchaSolver.tick(client);
            return;
        }

        // Captcha detected: auto-solve with the local model, or pause for the human.
        String captchaChat = stats.captchaMessage;
        if (captchaChat != null) {
            stats.captchaMessage = null;
            beginCaptcha(client, "chat", captchaChat);
            return;
        }
        // The map captcha: a filled map the server just put in our hand/hotbar.
        long nowMs = System.currentTimeMillis();
        CaptchaDetector.Hit mapHit = captchaDetector.tick(client, nowMs);
        if (mapHit != null) {
            beginCaptcha(client, mapHit.source(), mapHit.detail());
            return;
        }
        captchaSolver.tickIdle(nowMs);
        // 0.9.38: a live zone boss outranks every buy - it is gone in five minutes.
        if (bossEvent.isBusy()) {
            bossEvent.tick(client, combat);
            if (combat.stopRequest != null) {
                String reason = combat.stopRequest;
                combat.stopRequest = null;
                emergencyStop(client, reason);
            }
            return;
        }
        if (upgrades.isBusy()) {
            upgrades.tick(client, combat);
            return;
        }
        if (enchants.isBusy()) {
            enchants.tick(client, combat);
            return;
        }
        if (rebirthUpgrades.isBusy()) {
            rebirthUpgrades.tick(client, combat);
            return;
        }
        if (companions.isBusy()) {
            companions.tick(client, combat);
            if (combat.stopRequest != null) {
                String reason = combat.stopRequest;
                combat.stopRequest = null;
                emergencyStop(client, reason);
            }
            return;
        }
        // The SWORD ENCHANTER's title is formatting-only (font glyph), so it is
        // recognised by its contents — which arrive a tick after the screen opens, hence
        // the grace. Our own menus (enchanter, Rebirth GUI, Upgrades) are never a captcha;
        // but one nobody is driving (an aborted visit left it open, or it was opened by
        // hand while the bot runs) is closed after strayGuiCloseMs instead of idling the
        // bot forever (16:31 log: 90s on the enchanter until Drew toggled).
        boolean handled = client.currentScreen instanceof HandledScreen;
        long nowGui = System.currentTimeMillis();
        if (!handled) guiSeenAt = 0;
        else if (guiSeenAt == 0) guiSeenAt = nowGui;
        String screenTitle = client.currentScreen != null && client.currentScreen.getTitle() != null
            ? client.currentScreen.getTitle().getString() : "";
        boolean ownGui = handled && (RebirthScreens.isRebirthGui(screenTitle) || enchants.isOurGui(client)
            || rebirthUpgrades.isOurGui(client) || companions.isOurGui(client));
        if (ownGui && config.strayGuiCloseMs > 0 && nowGui - guiSeenAt >= config.strayGuiCloseMs) {
            // 0.9.37: name the screen's items so the log says which menu was left open, and
            // drop any aim path - one issued as the screen opened never completes and used to
            // latch the camera for the rest of the target (72 s of clicks at a Horse).
            java.util.List<String> names = new java.util.ArrayList<>();
            for (GuiHuman.Item it : GuiHuman.items(client)) if (names.size() < 12 && it.name() != null && !it.name().isBlank()) names.add(it.name());
            if (logger != null) logger.log("stray_gui_close", "title", screenTitle, "ageMs", nowGui - guiSeenAt, "items", names);
            EnchantScreens.closeGui(client);
            guiSeenAt = 0;
            combat.releaseKeys(client);
            MouseDriver.INSTANCE.cancel();
            return;
        }
        if (ownGui || (handled && nowGui - guiSeenAt < config.guiRecognizeGraceMs)) {
            combat.releaseKeys(client);
            return;
        }
        if (client.currentScreen != null
            && (config.pauseOnAnyScreen
                || (config.pauseOnContainerScreen && client.currentScreen instanceof HandledScreen))) {
            String title = client.currentScreen.getTitle() != null
                ? client.currentScreen.getTitle().getString() : "";
            // a screen that survives (or reappears right after) an auto-solve isn't
            // the captcha — don't loop on it, hand it to the human
            if (System.currentTimeMillis() < guiRetryBlockUntil) {
                pauseForCaptcha(client, "gui", "gui-persisted", title);
            } else {
                beginCaptcha(client, "gui", title);
            }
            return;
        }

        if (bossEvent.tick(client, combat)) {
            return;
        }
        if (upgrades.tick(client, combat)) {
            return;
        }
        if (enchants.tick(client, combat)) {
            return;
        }
        if (rebirthUpgrades.tick(client, combat)) {
            return;
        }
        if (companions.tick(client, combat)) {
            return;
        }
        transcend.tick(client, combat);

        combat.tick(client);

        if (combat.stopRequest != null) {
            String reason = combat.stopRequest;
            combat.stopRequest = null;
            emergencyStop(client, reason);
            return;
        }

        long now = System.currentTimeMillis();
        if (logger != null && now - lastStatusAt >= config.statusIntervalSeconds * 1000L) {
            lastStatusAt = now;
            logger.log("status",
                "rebirths", stats.rebirths,
                "ascensions", stats.ascensions,
                "rebirthProgressPct", stats.rebirthProgressPct,
                "kills", combat.kills,
                "killsPerMin", Math.round(stats.killsPerMinute(60_000) * 10.0) / 10.0,
                "multiplier", stats.multiplier,
                "bals", stats.formattedBalances(),
                "zoneReady", Math.round(1000.0 * stats.zoneReadiness()) / 10.0);
        }
    }

    /** 0.9.30: the in-game options screen — one ON/OFF button per feature, saved to the config file at once. */
    private BotOptionsScreen newOptionsScreen() {
        List<BotOptionsScreen.Option> opts = new ArrayList<>();
        // 0.9.33: each toggle shows what its module is doing right now (the HUD module row is off by default).
        opts.add(new BotOptionsScreen.Option("serverAutoRebirth", "Server auto-rebirth", () -> config.serverAutoRebirth, v -> config.serverAutoRebirth = v,
            () -> { String r = upgrades.hudRebirthLine(); return (r != null ? "rebirth " + r : "rebirth cost unknown") + " · " + stats.cycleHistoryLine(); }));
        opts.add(new BotOptionsScreen.Option("upgradesEnabled", "Sword / zone buys", () -> config.upgradesEnabled, v -> config.upgradesEnabled = v,
            () -> upgrades.hudPlanLine()));
        opts.add(new BotOptionsScreen.Option("rebirthHorizonEnabled", "Rebirth horizon rule", () -> config.rebirthHorizonEnabled, v -> config.rebirthHorizonEnabled = v,
            () -> upgrades.horizonBlockedKind() != null ? "holding " + upgrades.horizonBlockedKind() + " (rebirth sooner)" : "not limiting"));
        opts.add(new BotOptionsScreen.Option("enchantsEnabled", "Enchant visits", () -> config.enchantsEnabled, v -> config.enchantsEnabled = v,
            () -> moduleStatus(enchants.hudLine(), enchants.isBusy(), enchants.isSuspended())));
        opts.add(new BotOptionsScreen.Option("rebirthUpgradesEnabled", "Rebirth upgrades", () -> config.rebirthUpgradesEnabled, v -> config.rebirthUpgradesEnabled = v,
            () -> moduleStatus(rebirthUpgrades.hudLine(), rebirthUpgrades.isBusy(), rebirthUpgrades.isSuspended())));
        opts.add(new BotOptionsScreen.Option("companionsEnabled", "Companions", () -> config.companionsEnabled, v -> config.companionsEnabled = v,
            () -> moduleStatus(companions.hudLine(), companions.isBusy(), companions.isSuspended())));
        opts.add(new BotOptionsScreen.Option("companionBulkDeleteEnabled", "Companion bulk delete", () -> config.companionBulkDeleteEnabled, v -> config.companionBulkDeleteEnabled = v));
        opts.add(new BotOptionsScreen.Option("bossEventEnabled", "Zone boss", () -> config.bossEventEnabled, v -> config.bossEventEnabled = v,
            () -> moduleStatus(bossEvent.hudLine(), bossEvent.isBusy(), bossEvent.isSuspended())));
        opts.add(new BotOptionsScreen.Option("transcendEnabled", "Transcend (Q)", () -> config.transcendEnabled, v -> config.transcendEnabled = v,
            () -> { String t = transcend.hudState(); return t != null ? t : "idle"; }));
        opts.add(new BotOptionsScreen.Option("giveawaysEnabled", "Join giveaways", () -> config.giveawaysEnabled, v -> config.giveawaysEnabled = v,
            () -> "joined " + stats.giveawaysJoined + " · won " + stats.giveawaysWon));
        opts.add(new BotOptionsScreen.Option("giveawayWinReplyEnabled", "Giveaway win reply", () -> config.giveawayWinReplyEnabled, v -> config.giveawayWinReplyEnabled = v));
        opts.add(new BotOptionsScreen.Option("ggEnabled", "GG replies", () -> config.ggEnabled, v -> config.ggEnabled = v,
            () -> stats.ggSeq == 0 ? "no wave seen yet" : "last " + stats.ggKind + (stats.ggWho != null ? " · " + stats.ggWho : "")));
        opts.add(new BotOptionsScreen.Option("ggPerkEnabled", "GG on perk pulls", () -> config.ggPerkEnabled, v -> config.ggPerkEnabled = v,
            () -> !config.ggEnabled ? "off with GG replies" : "Universal Perk 5, half of them"));
        opts.add(new BotOptionsScreen.Option("breaksEnabled", "Breaks", () -> config.breaksEnabled, v -> config.breaksEnabled = v,
            () -> combat.isOnBreak() ? "on break · " + (combat.breakRemainingMs() + 999) / 1000 + "s left" : "focused"));
        opts.add(new BotOptionsScreen.Option("stopProtocolEnabled", "Stop protocol", () -> config.stopProtocolEnabled, v -> config.stopProtocolEnabled = v,
            () -> pausedReason != null ? "paused: " + pausedReason : "armed"));
        opts.add(new BotOptionsScreen.Option("captchaAutoSolve", "Captcha auto-solve", () -> config.captchaAutoSolve, v -> config.captchaAutoSolve = v,
            // Mid-solve show the solve; otherwise the reader's reachability and which host
            // it actually is (0.9.34 — the old HUD named vLLM whatever was configured).
            () -> {
                String c = captchaSolver.hudLine();
                if (c != null) return c;
                String h = captchaSolver.vlmHudLine();
                return h != null ? h : "reader ok · " + captchaSolver.readerHost();
            }));
        opts.add(new BotOptionsScreen.Option("learnObservedUpgrades", "Learn manual buys", () -> config.learnObservedUpgrades, v -> config.learnObservedUpgrades = v));
        opts.add(new BotOptionsScreen.Option("swordMenuScoutEnabled", "Sword Skins price scouting", () -> config.swordMenuScoutEnabled, v -> config.swordMenuScoutEnabled = v,
            () -> { String t = stats.swordTierLine(); return t != null ? t + (stats.swordSkin != null ? " · " + stats.swordSkin : "") : "menu not read yet"; }));
        opts.add(new BotOptionsScreen.Option("gateUsesPrediction", "Legacy: prediction fills gate", () -> config.gateUsesPrediction, v -> config.gateUsesPrediction = v));
        opts.add(new BotOptionsScreen.Option("pricePredictionEnabled", "Price ladder prediction", () -> config.pricePredictionEnabled, v -> config.pricePredictionEnabled = v));
        opts.add(new BotOptionsScreen.Option("sprint", "Sprint", () -> config.sprint, v -> config.sprint = v));
        opts.add(new BotOptionsScreen.Option("hud", "HUD", () -> config.hud, v -> config.hud = v));
        opts.add(new BotOptionsScreen.Option("hudShowPlan", "HUD plan row", () -> config.hudShowPlan, v -> config.hudShowPlan = v));
        opts.add(new BotOptionsScreen.Option("hudShowModules", "HUD module row", () -> config.hudShowModules, v -> config.hudShowModules = v));
        opts.add(new BotOptionsScreen.Option("hudShowBalances", "HUD balances row", () -> config.hudShowBalances, v -> config.hudShowBalances = v));
        return new BotOptionsScreen(opts, (key, value) -> {
            config.save(configPath);
            if (logger != null) logger.log("option_toggle", "name", key, "value", value);
            LOGGER.info("option {} = {}", key, value);
        });
    }

    /** Status line for a visiting module: what it is doing, else suspended, else idle. */
    private static String moduleStatus(String line, boolean busy, boolean suspended) {
        if (line != null && !line.isBlank()) return line;
        if (suspended) return "suspended until the next toggle";
        return busy ? "busy" : "idle";
    }

    private static boolean ctrlHeld(MinecraftClient client) {
        var w = client.getWindow();
        return InputUtil.isKeyPressed(w, GLFW.GLFW_KEY_LEFT_CONTROL) || InputUtil.isKeyPressed(w, GLFW.GLFW_KEY_RIGHT_CONTROL);
    }

    /**
     * Ctrl+Shift+toggle (0.9.29): looking at the egg saves it for this stage, bot on or off;
     * with the bot on the visit is queued as well (it walks to the spotlighted block, else
     * to whatever the scans find).
     */
    private void runCompanions(MinecraftClient client) {
        String saved = companions.spotlight(client, stats.zone);
        if (client.player == null) return;
        if (!enabled) {
            client.player.sendMessage(Text.literal(saved != null
                ? "§e[YCBotChallenge] " + saved + " Turn the bot on and press Ctrl+Shift+toggle again to visit it."
                : "§e[YCBotChallenge] look at the companion egg (within 6 blocks) and press Ctrl+Shift+toggle to save it; with the bot on that also starts the visit."), false);
            return;
        }
        companions.runNow();
        client.player.sendMessage(Text.literal("§e[YCBotChallenge] " + (saved != null ? saved + " " : "")
            + "companion visit queued — after this kill."), false);
    }

    private void markIgnored(MinecraftClient client) {
        String msg = combat.toggleManualIgnore(client);
        if (client.player == null) return;
        client.player.sendMessage(Text.literal(msg != null
            ? "§e[YCBotChallenge] " + msg
            : "§e[YCBotChallenge] no mob under the crosshair — look at the one to ignore and press Ctrl+toggle."), false);
    }

    private static boolean shiftHeld(MinecraftClient client) {
        var w = client.getWindow();
        return InputUtil.isKeyPressed(w, GLFW.GLFW_KEY_LEFT_SHIFT) || InputUtil.isKeyPressed(w, GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    private void toggleSprint(MinecraftClient client) {
        config.sprint = !config.sprint;
        config.save(configPath);
        if (logger != null) logger.log("sprint_toggle", "sprint", config.sprint);
        if (client.player != null) {
            client.player.sendMessage(
                Text.literal(config.sprint ? "§a[YCBotChallenge] sprint ON" : "§c[YCBotChallenge] sprint OFF"), true);
        }
    }

    private void beginCaptcha(MinecraftClient client, String source, String detail) {
        if (!config.captchaAutoSolve) {
            pauseForCaptcha(client, source, "autosolve-off", detail);
            return;
        }
        combat.reset(client);
        upgrades.reset(client);
        enchants.reset(client);
        rebirthUpgrades.reset(client);
        companions.reset(client);
        bossEvent.reset(client);
        MouseDriver.INSTANCE.cancel();
        if ("gui".equals(source)) {
            // if this screen (or a sibling) is still around after the solve, next
            // detection within the window falls back to a manual pause
            guiRetryBlockUntil = System.currentTimeMillis() + 20_000;
        }
        captchaSolver.begin(client, source, detail);
    }

    /** Teleport or nearby player while grinding: full stop, right now, human takes over. */
    private void emergencyStop(MinecraftClient client, String reason) {
        if (logger != null) logger.log("stop_protocol", "reason", reason);
        setEnabled(client, false, true);
        pausedReason = "stopped";
        if (client.player != null) {
            client.player.sendMessage(Text.literal(
                "§c[YCBotChallenge] STOPPED — " + reason + ". Press the toggle key to resume."), false);
        }
        LOGGER.info("stop protocol fired: {}", reason);
    }

    private void pauseForCaptcha(MinecraftClient client, String source, String reason, String detail) {
        if (logger != null) logger.log("captcha_pause", "source", source, "reason", reason, "detail", detail);
        setEnabled(client, false, true);
        pausedReason = "captcha";
        if (client.player != null) {
            String hint = switch (reason) {
                // Name the host actually configured: before 0.9.34 this said "the model
                // server", which sent Drew looking for a vLLM the mod had not used since
                // 0.9.32 (2026-09-04 16:07:13).
                case "vlm-offline" -> " Every read failed to reach " + captchaSolver.readerHost() + ".";
                case "budget" -> " No answer in time — type it yourself before the window closes.";
                default -> "";
            };
            client.player.sendMessage(Text.literal(
                "§e[YCBotChallenge] paused — captcha (" + source + ", " + reason + ")." + hint
                    + " Solve it, then press the toggle key."), false);
        }
        LOGGER.info("YCBotChallenge paused for captcha ({}, {}): {}", source, reason, detail);
    }

    private void setEnabled(MinecraftClient client, boolean on, boolean silent) {
        if (enabled == on) return;
        enabled = on;
        if (on) {
            pausedReason = null;
            stats.captchaMessage = null;
            guiRetryBlockUntil = 0;
            HumanTiming.beginSession(config);
            // Fresh start: after /spawn, a manual zone hop or an AFK gap, old kill
            // samples describe a different stage — the zone gate re-measures.
            if (config.resetTtkOnEnable) {
                stats.onEnable();
                combat.lastPredictedTtkMs = null;
            }
        }
        if (on && client != null && client.getSession() != null) {
            // Learned prices are per account (Drew runs an alt): load this user's, so no
            // /rebirth or /swordmax probe is needed to relearn what the server already said.
            stats.attachUser(client.getSession().getUsername());
        }
        if (on) {
            if (logger == null) {
                logger = new EventLogger(
                    FabricLoader.getInstance().getGameDir().resolve("ycbotchallenge-logs"),
                    config.runLabel, () -> stats.context(), YCBotChallengeClient::botFlag);
                stats.setLogger(logger);
                combat.setLogger(logger);
                upgrades.setLogger(logger);
                enchants.setLogger(logger);
                rebirthUpgrades.setLogger(logger);
                companions.setLogger(logger);
                bossEvent.setLogger(logger);
                transcend.setLogger(logger);
                captchaSolver.setLogger(logger);
                captchaDetector.setLogger(logger);
                MouseDriver.INSTANCE.configure(config, logger);
                logger.log("session_start",
                    "username", client.getSession() != null ? client.getSession().getUsername() : null);
                LOGGER.info("Logging to {}", logger.getFile());
            }
            stats.botActive = () -> enabled;
            logger.log("bot_on", "offMs", lastOffAt > 0 ? System.currentTimeMillis() - lastOffAt : null);
            lastOnAt = System.currentTimeMillis();
            // Maps already in the hotbar are not a captcha; only a new one is.
            captchaDetector.onEnable(client);
            captchaSolver.checkHealth(System.currentTimeMillis());
            rebirthUpgrades.onEnable(System.currentTimeMillis(), combat.kills);
            companions.onEnable(System.currentTimeMillis(), combat.kills);
            bossEvent.onEnable(System.currentTimeMillis(), combat.kills);
            transcend.onEnable(System.currentTimeMillis(), combat.kills);
        } else {
            if (logger != null) logger.log("bot_off", "onMs", lastOnAt > 0 ? System.currentTimeMillis() - lastOnAt : null);
            lastOffAt = System.currentTimeMillis();
            stats.onDisable();
            if (client != null) {
                combat.reset(client);
                upgrades.reset(client);
                enchants.reset(client);
                rebirthUpgrades.reset(client);
                companions.reset(client);
                bossEvent.reset(client);
            }
            MouseDriver.INSTANCE.cancel();
            captchaSolver.cancel();
        }
        if (!silent && client != null && client.player != null) {
            client.player.sendMessage(
                Text.literal(on ? "§a[YCBotChallenge] ON" : "§c[YCBotChallenge] OFF"), true);
        }
    }

    private void closeLogger(String reason) {
        if (logger != null) {
            logger.log("session_end", "reason", reason);
            logger.close();
            logger = null;
            stats.setLogger(null);
            combat.setLogger(null);
            upgrades.setLogger(null);
            enchants.setLogger(null);
            rebirthUpgrades.setLogger(null);
            companions.setLogger(null);
            bossEvent.setLogger(null);
            transcend.setLogger(null);
            captchaSolver.setLogger(null);
            captchaDetector.setLogger(null);
            MouseDriver.INSTANCE.configure(config, null);
        }
    }
}

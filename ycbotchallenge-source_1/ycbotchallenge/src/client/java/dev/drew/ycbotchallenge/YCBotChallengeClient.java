package dev.drew.ycbotchallenge;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import java.nio.file.Path;
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

    private YCBotChallengeConfig config;
    private Path configPath;
    private StatsTracker stats;
    private CombatController combat;
    private UpgradeController upgrades;
    private EnchantController enchants;
    private RebirthUpgradeController rebirthUpgrades;
    private CaptchaSolver captchaSolver;
    private CaptchaDetector captchaDetector;
    private EventLogger logger;
    private KeyBinding toggleKey;
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
        combat = new CombatController(config, stats);
        upgrades = new UpgradeController(config, stats);
        enchants = new EnchantController(config, stats);
        rebirthUpgrades = new RebirthUpgradeController(config, stats);
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

        HudElementRegistry.addLast(
            Identifier.of("ycbotchallenge", "hud"),
            (context, tickCounter) -> new HudOverlay(config, stats, combat, captchaSolver, upgrades, enchants, rebirthUpgrades).render(context));

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            stats.onGameMessage(message, overlay);
            if (!overlay) captchaSolver.onGameMessage(message.getString());
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            setEnabled(client, false, true);
            closeLogger("disconnect");
        });

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);

        LOGGER.info("YCBotChallenge loaded — press G in game to toggle, Shift+G to toggle sprint.");
    }

    private void onTick(MinecraftClient client) {
        while (toggleKey.wasPressed()) {
            // Shift + toggle key = flip sprinting (persisted to the config); plain = bot on/off
            if (shiftHeld(client)) toggleSprint(client);
            else setEnabled(client, !enabled, false);
        }
        if (client.player == null || client.world == null) return;

        tickCounter++;
        if (tickCounter % 20 == 0) stats.poll(client);

        if (!enabled) return;

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
        String screenTitle = client.currentScreen != null && client.currentScreen.getTitle() != null
            ? client.currentScreen.getTitle().getString() : "";
        if (RebirthScreens.isRebirthGui(screenTitle)) {
            combat.releaseKeys(client);
            return;
        }
        // The SWORD ENCHANTER's title is formatting-only (font glyph), so it is
        // recognised by its contents — which arrive a tick after the screen opens, hence
        // the grace. Opened by hand it is never a captcha: idle until it is closed.
        boolean handled = client.currentScreen instanceof HandledScreen;
        long nowGui = System.currentTimeMillis();
        if (!handled) guiSeenAt = 0;
        else if (guiSeenAt == 0) guiSeenAt = nowGui;
        if (handled && (nowGui - guiSeenAt < config.guiRecognizeGraceMs || enchants.isOurGui(client)
            || rebirthUpgrades.isOurGui(client))) {
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

        if (upgrades.tick(client, combat)) {
            return;
        }
        if (enchants.tick(client, combat)) {
            return;
        }
        if (rebirthUpgrades.tick(client, combat)) {
            return;
        }

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
            String hint = "vlm-offline".equals(reason) ? " The model server is offline." : "";
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
                    config.runLabel, () -> stats.context());
                stats.setLogger(logger);
                combat.setLogger(logger);
                upgrades.setLogger(logger);
                enchants.setLogger(logger);
                rebirthUpgrades.setLogger(logger);
                captchaSolver.setLogger(logger);
                captchaDetector.setLogger(logger);
                MouseDriver.INSTANCE.configure(config, logger);
                logger.log("session_start",
                    "username", client.getSession() != null ? client.getSession().getUsername() : null);
                LOGGER.info("Logging to {}", logger.getFile());
            }
            logger.log("bot_on");
            // Maps already in the hotbar are not a captcha; only a new one is.
            captchaDetector.onEnable(client);
            captchaSolver.checkHealth(System.currentTimeMillis());
            rebirthUpgrades.onEnable(System.currentTimeMillis(), combat.kills);
        } else {
            if (logger != null) logger.log("bot_off");
            if (client != null) {
                combat.reset(client);
                upgrades.reset(client);
                enchants.reset(client);
                rebirthUpgrades.reset(client);
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
            captchaSolver.setLogger(null);
            captchaDetector.setLogger(null);
            MouseDriver.INSTANCE.configure(config, null);
        }
    }
}

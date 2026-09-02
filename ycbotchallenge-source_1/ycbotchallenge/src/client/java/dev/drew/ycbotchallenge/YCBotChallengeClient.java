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
    private CaptchaSolver captchaSolver;
    private EventLogger logger;
    private KeyBinding toggleKey;
    private int tickCounter = 0;
    private long lastStatusAt = 0;
    private long guiRetryBlockUntil = 0;

    @Override
    public void onInitializeClient() {
        configPath = FabricLoader.getInstance().getConfigDir().resolve("ycbotchallenge.json");
        config = YCBotChallengeConfig.load(configPath);
        Amounts.configure(config.suffixScales);
        stats = new StatsTracker(config);
        combat = new CombatController(config, stats);
        upgrades = new UpgradeController(config, stats);
        MouseDriver.INSTANCE.configure(config, null);
        captchaSolver = new CaptchaSolver(config, new CaptchaSolver.Callbacks() {
            @Override public void onSolved(MinecraftClient client) {
                // stay enabled; combat resumes on the next tick. Drop any captcha
                // chat line that arrived mid-solve (e.g. the server re-prompting)
                // so we don't immediately re-trigger on the one we just solved.
                stats.captchaMessage = null;
            }
            @Override public void onFailed(MinecraftClient client, String stage, String why) {
                pauseForCaptcha(client, "autosolve-failed:" + stage, why);
            }
        });

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.ycbotchallenge.toggle",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            KeyBinding.Category.MISC));

        HudElementRegistry.addLast(
            Identifier.of("ycbotchallenge", "hud"),
            (context, tickCounter) -> new HudOverlay(config, stats, combat, captchaSolver, upgrades).render(context));

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
        if (upgrades.isBusy()) {
            upgrades.tick(client, combat);
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
                pauseForCaptcha(client, "gui", title);
            } else {
                beginCaptcha(client, "gui", title);
            }
            return;
        }

        if (upgrades.tick(client, combat)) {
            return;
        }

        combat.tick(client);

        long now = System.currentTimeMillis();
        if (logger != null && now - lastStatusAt >= config.statusIntervalSeconds * 1000L) {
            lastStatusAt = now;
            logger.log("status",
                "rebirths", stats.rebirths,
                "ascensions", stats.ascensions,
                "rebirthProgressPct", stats.rebirthProgressPct,
                "kills", combat.kills,
                "killsPerMin", Math.round(stats.killsPerMinute(60_000) * 10.0) / 10.0,
                "multiplier", stats.multiplier);
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
            pauseForCaptcha(client, source, detail);
            return;
        }
        combat.reset(client);
        upgrades.reset(client);
        MouseDriver.INSTANCE.cancel();
        if ("gui".equals(source)) {
            // if this screen (or a sibling) is still around after the solve, next
            // detection within the window falls back to a manual pause
            guiRetryBlockUntil = System.currentTimeMillis() + 20_000;
        }
        captchaSolver.begin(client, source, detail);
    }

    private void pauseForCaptcha(MinecraftClient client, String source, String detail) {
        if (logger != null) logger.log("captcha_pause", "source", source, "detail", detail);
        setEnabled(client, false, true);
        pausedReason = "captcha";
        if (client.player != null) {
            client.player.sendMessage(Text.literal(
                "§e[YCBotChallenge] paused — captcha detected (" + source + "). Solve it, then press the toggle key."), false);
        }
        LOGGER.info("YCBotChallenge paused for captcha ({}): {}", source, detail);
    }

    private void setEnabled(MinecraftClient client, boolean on, boolean silent) {
        if (enabled == on) return;
        enabled = on;
        if (on) {
            pausedReason = null;
            stats.captchaMessage = null;
            HumanTiming.beginSession(config);
        }
        if (on) {
            if (logger == null) {
                logger = new EventLogger(
                    FabricLoader.getInstance().getGameDir().resolve("ycbotchallenge-logs"),
                    config.runLabel, () -> stats.context());
                stats.setLogger(logger);
                combat.setLogger(logger);
                upgrades.setLogger(logger);
                captchaSolver.setLogger(logger);
                MouseDriver.INSTANCE.configure(config, logger);
                logger.log("session_start",
                    "username", client.getSession() != null ? client.getSession().getUsername() : null);
                LOGGER.info("Logging to {}", logger.getFile());
            }
            logger.log("bot_on");
        } else {
            if (logger != null) logger.log("bot_off");
            if (client != null) {
                combat.reset(client);
                upgrades.reset(client);
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
            captchaSolver.setLogger(null);
            MouseDriver.INSTANCE.configure(config, null);
        }
    }
}

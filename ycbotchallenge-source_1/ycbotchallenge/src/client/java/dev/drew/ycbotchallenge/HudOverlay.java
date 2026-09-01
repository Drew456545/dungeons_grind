package dev.drew.ycbotchallenge;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/** Small stats panel drawn while the bot is enabled (and briefly after toggle). */
public class HudOverlay {
    private final YCBotChallengeConfig cfg;
    private final StatsTracker stats;
    private final CombatController combat;
    private final CaptchaSolver captcha;

    public HudOverlay(YCBotChallengeConfig cfg, StatsTracker stats, CombatController combat, CaptchaSolver captcha) {
        this.cfg = cfg;
        this.stats = stats;
        this.combat = combat;
        this.captcha = captcha;
    }

    public void render(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!cfg.hud || client.player == null || client.getDebugHud().shouldShowDebugHud()) return;
        boolean on = YCBotChallengeClient.enabled;

        List<String> lines = new ArrayList<>();
        lines.add("YCBotChallenge " + (on ? "§aON§r" : "§cOFF§r")
            + "  §7sprint " + (cfg.sprint ? "§aon" : "§8off") + "§r");
        if (!on && "captcha".equals(YCBotChallengeClient.pausedReason)) {
            lines.add("§eCAPTCHA — solve it, then press the toggle key§r");
        }
        if (on) {
            String solving = captcha != null ? captcha.hudLine() : null;
            if (solving != null) lines.add(solving);
            else lines.add("§7" + combat.stateDescription() + "§r");
            if (combat.dominantDesc != null) lines.add("§7pack: " + combat.dominantDesc + "§r");
            if (combat.ghostsIgnored > 0) lines.add("§8ghosts ignored: " + combat.ghostsIgnored + "§r");
        }
        lines.add("kills " + combat.kills + "  §7(" + String.format("%.1f", stats.killsPerMinute(60_000)) + "/min)§r");

        int y = cfg.hudY;
        int width = 0;
        for (String l : lines) width = Math.max(width, client.textRenderer.getWidth(l));
        context.fill(cfg.hudX - 2, y - 2, cfg.hudX + width + 4, y + lines.size() * 10 + 2, 0x90000000);
        for (String l : lines) {
            context.drawTextWithShadow(client.textRenderer, l, cfg.hudX + 1, y, 0xFFFFFFFF);
            y += 10;
        }
    }
}

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
    private final UpgradeController upgrades;

    public HudOverlay(YCBotChallengeConfig cfg, StatsTracker stats, CombatController combat, CaptchaSolver captcha, UpgradeController upgrades) {
        this.cfg = cfg;
        this.stats = stats;
        this.combat = combat;
        this.captcha = captcha;
        this.upgrades = upgrades;
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
        if (!on && "stopped".equals(YCBotChallengeClient.pausedReason)) {
            lines.add("§cSTOPPED (stop protocol) — press the toggle key to resume§r");
        }
        if (on) {
            String solving = captcha != null ? captcha.hudLine() : null;
            if (solving != null) lines.add(solving);
            else if (combat.isOnBreak()) lines.add("§8on break§r");
            else lines.add("§7" + combat.stateDescription() + "§r");
            if (combat.dominantDesc != null) lines.add("§7pack: " + combat.dominantDesc + "§r");
            if (combat.ghostsIgnored > 0) lines.add("§8ghosts ignored: " + combat.ghostsIgnored + "§r");
            if (combat.currentDps != null) {
                String eta = combat.currentEtaMs != null
                    ? (combat.currentEtaMs < 1000 ? Math.round(combat.currentEtaMs) + "ms"
                        : String.format("%.1fs", combat.currentEtaMs / 1000.0))
                    : "?";
                lines.add("§7dps " + String.format("%.1f", combat.currentDps) + "  eta " + eta + "§r");
            }
            String up = upgrades != null ? upgrades.hudLine() : null;
            if (up != null) lines.add("§7" + up + "§r");
        }
        lines.add("kills " + combat.kills + "  §7(" + String.format("%.1f", stats.killsPerMinute(60_000)) + "/min · "
            + String.format("%.2f", stats.killsPerSecond(30_000)) + "/s)§r");

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

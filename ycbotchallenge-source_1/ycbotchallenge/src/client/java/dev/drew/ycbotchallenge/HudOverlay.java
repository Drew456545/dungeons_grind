package dev.drew.ycbotchallenge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * The stats panel (0.9.30 layout). Two aligned columns — dim labels, plain values, one
 * accent colour per meaning (green good, yellow busy, red stopped, grey off) — with a
 * row per thing worth knowing and nothing else: header + alerts, what combat is doing,
 * money and income, the next sword and zone prices (server-quoted or ladder-predicted,
 * 0.9.31), the rebirth, the one module that is busy, a chip row of every module's state,
 * optionally the other balances, kills. Rows with nothing to say are
 * omitted, so the panel is short while the bot idles.
 */
public class HudOverlay {
    private record Row(String label, String value) {}

    private static final int LINE_H = 10;
    private static final int PAD = 4;
    private static final int LABEL_GAP = 6;

    private final YCBotChallengeConfig cfg;
    private final StatsTracker stats;
    private final CombatController combat;
    private final CaptchaSolver captcha;
    private final UpgradeController upgrades;
    private final EnchantController enchants;
    private final RebirthUpgradeController rebirthUpgrades;
    private final CompanionController companions;
    private final TranscendController transcend;

    public HudOverlay(YCBotChallengeConfig cfg, StatsTracker stats, CombatController combat, CaptchaSolver captcha,
                      UpgradeController upgrades, EnchantController enchants, RebirthUpgradeController rebirthUpgrades,
                      CompanionController companions, TranscendController transcend) {
        this.cfg = cfg;
        this.stats = stats;
        this.combat = combat;
        this.captcha = captcha;
        this.upgrades = upgrades;
        this.enchants = enchants;
        this.rebirthUpgrades = rebirthUpgrades;
        this.companions = companions;
        this.transcend = transcend;
    }

    public void render(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!cfg.hud || client.player == null || client.getDebugHud().shouldShowDebugHud()) return;
        List<Row> rows = rows();

        int x = cfg.hudX + PAD;
        int y = cfg.hudY + PAD;
        int labelW = 0;
        int width = 0;
        for (Row r : rows) {
            if (!r.label().isEmpty()) labelW = Math.max(labelW, client.textRenderer.getWidth("§7" + r.label()));
        }
        for (Row r : rows) {
            int w = client.textRenderer.getWidth(r.value());
            if (!r.label().isEmpty()) w += labelW + LABEL_GAP;
            width = Math.max(width, w);
        }
        int alpha = (int) Math.round(Math.max(0, Math.min(1, cfg.hudAlpha)) * 255);
        int bg = (alpha << 24);
        context.fill(cfg.hudX, cfg.hudY, x + width + PAD, y + rows.size() * LINE_H + PAD - 2, bg);
        // A thin accent bar on the left: green running, yellow paused for the human, grey off.
        int accent = YCBotChallengeClient.enabled ? 0xFF55FF55
            : YCBotChallengeClient.pausedReason != null ? 0xFFFFAA00 : 0xFF666666;
        context.fill(cfg.hudX, cfg.hudY, cfg.hudX + 1, y + rows.size() * LINE_H + PAD - 2, accent);
        for (Row r : rows) {
            if (r.label().isEmpty()) {
                context.drawTextWithShadow(client.textRenderer, r.value(), x, y, 0xFFFFFFFF);
            } else {
                context.drawTextWithShadow(client.textRenderer, "§7" + r.label(), x, y, 0xFFFFFFFF);
                context.drawTextWithShadow(client.textRenderer, r.value(), x + labelW + LABEL_GAP, y, 0xFFFFFFFF);
            }
            y += LINE_H;
        }
    }

    private List<Row> rows() {
        boolean on = YCBotChallengeClient.enabled;
        List<Row> rows = new ArrayList<>();

        rows.add(new Row("", "§lYCBot§r " + (on ? "§a● ON§r" : "§c○ OFF§r")
            + "  §8sprint " + (cfg.sprint ? "§aon" : "§8off") + "§r"));
        if (!on && "captcha".equals(YCBotChallengeClient.pausedReason)) {
            rows.add(new Row("", "§eCAPTCHA · solve it, then press G§r"));
        }
        if (!on && "stopped".equals(YCBotChallengeClient.pausedReason)) {
            rows.add(new Row("", "§cSTOPPED · press G to resume§r"));
        }
        if (on && captcha != null && captcha.vlmHudLine() != null) rows.add(new Row("", captcha.vlmHudLine()));

        if (on) {
            String solving = captcha != null ? captcha.hudLine() : null;
            if (solving != null) {
                rows.add(new Row("state", solving));
            } else if (combat.isOnBreak()) {
                rows.add(new Row("state", "§8on break · " + (combat.breakRemainingMs() + 999) / 1000 + "s left (G to skip)§r"));
            } else {
                StringBuilder st = new StringBuilder(combat.stateDescription());
                if (combat.dominantDesc != null) st.append("  §8pack ").append(combat.dominantDesc);
                if (combat.ghostsIgnored > 0) st.append("  §8ghosts ").append(combat.ghostsIgnored);
                rows.add(new Row("state", st.append("§r").toString()));
                if (combat.currentDps != null) {
                    String eta = combat.currentEtaMs != null
                        ? (combat.currentEtaMs < 1000 ? Math.round(combat.currentEtaMs) + "ms"
                            : String.format(Locale.ROOT, "%.1fs", combat.currentEtaMs / 1000.0))
                        : "?";
                    rows.add(new Row("dps", String.format(Locale.ROOT, "%.1f", combat.currentDps) + "  §7eta " + eta + "§r"));
                }
            }
        }

        Double money = stats.money();
        Double rate = stats.incomePerMinute();
        if (money != null) {
            rows.add(new Row("money", "§a" + Amounts.format(money) + "§r"
                + (rate != null ? "  §7+" + Amounts.format(rate) + "/min§r" : "")));
        }
        if (on) {
            String sw = upgrades != null ? upgrades.hudKindLine("sword") : null;
            if (sw != null) rows.add(new Row("sword", sw));
            String zn = upgrades != null ? upgrades.hudKindLine("zone") : null;
            if (zn != null) rows.add(new Row("zone", zn));
            String rb = upgrades != null ? upgrades.hudRebirthLine() : null;
            if (rb != null) rows.add(new Row("rebirth", rb));
            String act = activity();
            if (act != null) rows.add(new Row("busy", act));
            if (cfg.hudShowModules) rows.add(new Row("mods", modules()));
        }
        if (cfg.hudShowBalances) {
            String bals = otherBalances();
            if (bals != null) rows.add(new Row("bals", bals));
        }
        rows.add(new Row("kills", combat.kills + "  §7" + String.format(Locale.ROOT, "%.1f", stats.killsPerMinute(60_000))
            + "/min · " + String.format(Locale.ROOT, "%.2f", stats.killsPerSecond(30_000)) + "/s§r"));
        return rows;
    }

    /** The one module with something going on (a visit, a countdown to one, a suspension), coloured by module. */
    private String activity() {
        String en = enchants != null ? enchants.hudLine() : null;
        if (en != null) return "§d" + en + "§r";
        String ru = rebirthUpgrades != null ? rebirthUpgrades.hudLine() : null;
        if (ru != null) return "§b" + ru + "§r";
        String co = companions != null ? companions.hudLine() : null;
        if (co != null) return "§6" + co + "§r";
        return null;
    }

    /** One chip per module: green enabled, yellow busy, red suspended, grey off. */
    private String modules() {
        StringBuilder sb = new StringBuilder();
        chip(sb, "upg", cfg.upgradesEnabled, upgrades != null && upgrades.isBusy(), false);
        chip(sb, "ench", cfg.enchantsEnabled, enchants != null && enchants.isBusy(), enchants != null && enchants.isSuspended());
        chip(sb, "rbup", cfg.rebirthUpgradesEnabled, rebirthUpgrades != null && rebirthUpgrades.isBusy(),
            rebirthUpgrades != null && rebirthUpgrades.isSuspended());
        chip(sb, "comp", cfg.companionsEnabled, companions != null && companions.isBusy(), companions != null && companions.isSuspended());
        String tr = transcend != null ? transcend.hudState() : null;
        chip(sb, "transc" + (tr != null ? " " + tr : ""), cfg.transcendEnabled, false, false);
        chip(sb, "give", cfg.giveawaysEnabled, false, false);
        chip(sb, "brk", cfg.ninja && cfg.breaksEnabled, false, false);
        chip(sb, "stop", cfg.stopProtocolEnabled, false, false);
        chip(sb, "auto-rb", cfg.serverAutoRebirth, false, false);
        return sb.toString();
    }

    private static void chip(StringBuilder sb, String name, boolean enabled, boolean busy, boolean suspended) {
        if (sb.length() > 0) sb.append(' ');
        String color = !enabled ? "§8" : suspended ? "§c" : busy ? "§e" : "§a";
        sb.append(color).append(name).append("§r");
    }

    /** Every sidebar currency but money, dim. */
    private String otherBalances() {
        List<String> names = cfg.sidebarCurrencies != null && !cfg.sidebarCurrencies.isEmpty()
            ? cfg.sidebarCurrencies : List.of("money", "souls", "essence", "shards", "credits");
        String moneyKey = cfg.moneyCurrency != null ? cfg.moneyCurrency.toLowerCase(Locale.ROOT) : "money";
        StringBuilder sb = new StringBuilder();
        for (String name : names) {
            String key = name.toLowerCase(Locale.ROOT);
            if (key.equals(moneyKey)) continue;
            Double v = stats.currency(key);
            if (v == null) continue;
            if (sb.length() > 0) sb.append("  ");
            sb.append("§7").append(Amounts.format(v)).append(' ').append(key).append("§r");
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}

package dev.drew.ycbotchallenge;

import dev.drew.ycbotchallenge.mixin.BossBarHudAccessor;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ClientBossBar;

/** Client boss-bar snapshot. Combat uses the fight HP bar; stats uses boost timers. */
public final class BossBars {
    private BossBars() {}

    public static Map<UUID, ClientBossBar> current(MinecraftClient client) {
        if (client == null || client.inGameHud == null) return Map.of();
        try {
            Map<UUID, ClientBossBar> bars =
                ((BossBarHudAccessor) client.inGameHud.getBossBarHud()).ycBotChallenge$getBossBars();
            return bars != null ? bars : Map.of();
        } catch (Throwable t) {
            return Map.of();
        }
    }

    /**
     * Timed events / 2x harvest / etc. — not the per-mob cook HP bar.
     * Combat ignores these when latching onto "the bar that appeared after I tapped".
     */
    public static boolean looksLikeBoost(String title) {
        if (title == null || title.isBlank()) return false;
        String t = title.toLowerCase(Locale.ROOT);
        if (t.contains("2x") || t.contains("3x") || t.contains("boost")) return true;
        if (t.contains("harvest") || t.contains("multiplier") || t.contains("event")) return true;
        if (t.contains("coupon") || t.contains("sale")) return true;
        // "Soul Harvest 2x Souls (12m, 9s)"
        return t.matches(".*\\(\\s*\\d+\\s*m\\b.*");
    }

    public static boolean ignoredForCook(String title, List<String> extra) {
        if (looksLikeBoost(title)) return true;
        if (extra == null) return false;
        String t = title.toLowerCase(Locale.ROOT);
        for (String p : extra) {
            if (p != null && !p.isBlank() && t.contains(p.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    public static java.util.Set<UUID> ids(MinecraftClient client) {
        Map<UUID, ClientBossBar> bars = current(client);
        return bars.isEmpty() ? Collections.emptySet() : Set.copyOf(bars.keySet());
    }
}

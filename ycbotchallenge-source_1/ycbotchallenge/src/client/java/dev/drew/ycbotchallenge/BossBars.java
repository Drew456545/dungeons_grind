package dev.drew.ycbotchallenge;

import dev.drew.ycbotchallenge.mixin.BossBarHudAccessor;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ClientBossBar;

/**
 * Client boss-bar snapshot plus a classifier: dungeon servers put BOTH the
 * per-mob cook HP bar AND timed events (2x souls, vote party, flash sale)
 * on the boss-bar HUD. Combat must wait on the HP bar and ignore events.
 */
public final class BossBars {
    private BossBars() {}

    /** "(12m, 9s)", "(45s)", "(1h)", "3:20 left", "expires in …" */
    private static final Pattern TIMER = Pattern.compile(
        "\\(\\s*\\d+\\s*[smh]\\b"
            + "|\\b\\d+\\s*[smh]\\s*(?:left|remain)"
            + "|\\b\\d{1,2}:\\d{2}\\b"
            + "|\\bexpires?\\b|\\bremaining\\b|\\btime\\s*left\\b",
        Pattern.CASE_INSENSITIVE);

    /** "2x Souls", "3x", "double xp", "triple" */
    private static final Pattern MULTIPLIER = Pattern.compile(
        "\\b\\d+x\\b|\\bdouble\\b|\\btriple\\b|\\bxp\\s*boost\\b",
        Pattern.CASE_INSENSITIVE);

    /** Word-ish tokens that mean a server event, not a mob fight. */
    private static final Pattern EVENT_WORD = Pattern.compile(
        "\\b(?:boost(?:er|s)?|harvest|event|sale|vote|coupon|announcement|"
            + "party|weekend|flash|giveaway|crate|reward|multiplier|"
            + "tournament|koth|maintenance|restart|store|discord|"
            + "motd|purchase|rank\\s*sale|outpost)\\b",
        Pattern.CASE_INSENSITIVE);

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

    /** Timed events / 2x harvest / vote party — not the per-mob cook HP bar. */
    public static boolean looksLikeEvent(String title) {
        if (title == null || title.isBlank()) return false;
        String t = title.toLowerCase(Locale.ROOT);
        if (TIMER.matcher(t).find()) return true;
        if (MULTIPLIER.matcher(t).find()) return true;
        if (EVENT_WORD.matcher(t).find()) return true;
        return false;
    }

    /** Alias used by the boost tracker (events ARE boosts for stats). */
    public static boolean looksLikeBoost(String title) {
        return looksLikeEvent(title);
    }

    public static boolean ignoredForCook(String title, List<String> extra) {
        if (looksLikeEvent(title)) return true;
        if (extra == null) return false;
        String t = title.toLowerCase(Locale.ROOT);
        for (String p : extra) {
            if (p != null && !p.isBlank() && t.contains(p.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /**
     * Positive evidence this bar is the fight we just tagged: nameplate,
     * rarity tag, hearts, or an HP fraction. Event titles never qualify.
     */
    public static boolean looksLikeCookBar(String title, String mob, String rarity) {
        if (title == null || title.isBlank() || looksLikeEvent(title)) return false;
        String t = title.toLowerCase(Locale.ROOT);
        if (mob != null && !mob.isBlank() && t.contains(mob.trim().toLowerCase(Locale.ROOT))) return true;
        if (rarity != null && !rarity.isBlank() && t.contains(rarity.trim().toLowerCase(Locale.ROOT))) return true;
        if (t.contains("health") || t.contains("hp") || title.indexOf('♥') >= 0 || title.indexOf('❤') >= 0) return true;
        return t.matches(".*\\d+\\s*/\\s*\\d+.*");
    }

    /**
     * Higher is more likely the cook HP bar. Event titles should be filtered
     * out before calling this; they score 0 anyway.
     */
    public static int cookScore(String title, float percent, String mob, String rarity) {
        if (title == null || looksLikeEvent(title)) return Integer.MIN_VALUE;
        int score = 1;
        if (looksLikeCookBar(title, mob, rarity)) score += 20;
        if (percent < 0.995f) score += 3; // already taking damage
        return score;
    }

    public static Set<UUID> ids(MinecraftClient client) {
        Map<UUID, ClientBossBar> bars = current(client);
        return bars.isEmpty() ? Collections.emptySet() : Set.copyOf(bars.keySet());
    }
}

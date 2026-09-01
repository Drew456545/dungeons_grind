package dev.drew.ycbotchallenge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.drew.ycbotchallenge.mixin.BossBarHudAccessor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ClientBossBar;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
import net.minecraft.text.Text;

/**
 * Reads what the server already shows the player: sidebar (rebirths, zone,
 * multiplier, balances), boss bars (boosts), action bar (rebirth progress %),
 * chat (ascension/prestige broadcasts). Emits log events on change.
 */
public class StatsTracker {
    private final YCBotChallengeConfig cfg;
    private EventLogger logger;

    public Integer rebirths = null;
    public int ascensions = 0;
    public int prestiges = 0;
    public String zone = null;
    public String multiplier = null;
    public Double rebirthProgressPct = null;
    public final Map<String, String> balances = new HashMap<>();
    public final Set<String> activeBoosts = new HashSet<>();
    private final Map<String, Long> boostSince = new HashMap<>();

    public final ArrayDeque<Long> killTimes = new ArrayDeque<>();
    public final ArrayDeque<Long> rebirthTimes = new ArrayDeque<>();

    private final Pattern rebirthsRe;
    private final Pattern zoneRe;
    private final Pattern multiplierRe;
    private final Pattern actionBarRe;
    private final List<String> balanceNames = new ArrayList<>();
    private final List<Pattern> balanceRes = new ArrayList<>();
    private final List<Pattern> ascensionRes = new ArrayList<>();
    private final List<Pattern> prestigeRes = new ArrayList<>();
    private final List<Pattern> captchaRes = new ArrayList<>();

    /** Set when a captcha chat line is seen; consumed (and cleared) by the main tick. */
    public volatile String captchaMessage = null;

    public StatsTracker(YCBotChallengeConfig cfg) {
        this.cfg = cfg;
        this.rebirthsRe = Pattern.compile(cfg.rebirthsPattern, Pattern.CASE_INSENSITIVE);
        this.zoneRe = Pattern.compile(cfg.zonePattern, Pattern.CASE_INSENSITIVE);
        this.multiplierRe = Pattern.compile(cfg.multiplierPattern, Pattern.CASE_INSENSITIVE);
        this.actionBarRe = Pattern.compile(cfg.actionBarPattern, Pattern.CASE_INSENSITIVE);
        for (String spec : cfg.balancePatterns) {
            int i = spec.indexOf('|');
            balanceNames.add(spec.substring(0, i));
            balanceRes.add(Pattern.compile(spec.substring(i + 1), Pattern.CASE_INSENSITIVE));
        }
        for (String p : cfg.ascensionChatPatterns) ascensionRes.add(compileLoose(p));
        for (String p : cfg.prestigeChatPatterns) prestigeRes.add(compileLoose(p));
        for (String p : cfg.captchaChatPatterns) captchaRes.add(compileLoose(p));
    }

    private static Pattern compileLoose(String p) {
        if (p.startsWith("/") && p.endsWith("/") && p.length() > 2) {
            return Pattern.compile(p.substring(1, p.length() - 1), Pattern.CASE_INSENSITIVE);
        }
        return Pattern.compile(Pattern.quote(p), Pattern.CASE_INSENSITIVE);
    }

    public void setLogger(EventLogger logger) { this.logger = logger; }

    public JsonObject context() {
        JsonObject ctx = new JsonObject();
        JsonArray boosts = new JsonArray();
        activeBoosts.forEach(boosts::add);
        ctx.add("boosts", boosts);
        if (rebirths != null) ctx.addProperty("rebirths", rebirths);
        ctx.addProperty("ascensions", ascensions);
        if (zone != null) ctx.addProperty("zone", zone);
        if (multiplier != null) ctx.addProperty("multiplier", multiplier);
        return ctx;
    }

    private void log(String type, Object... kv) {
        if (logger != null) logger.log(type, kv);
    }

    /** Call every ~20 ticks. */
    public void poll(MinecraftClient client) {
        if (client.world == null || client.player == null) return;
        pollSidebar(client);
        pollBossBars(client);
    }

    private void pollSidebar(MinecraftClient client) {
        Scoreboard sb = client.world.getScoreboard();
        ScoreboardObjective obj = sb.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
        if (obj == null) return;
        for (ScoreboardEntry entry : sb.getScoreboardEntries(obj)) {
            String owner = entry.owner();
            Team team = sb.getScoreHolderTeam(owner);
            String line = Team.decorateName(team, Text.literal(owner)).getString();
            if (line.isBlank()) continue;
            handleSidebarLine(line.trim());
        }
    }

    private void handleSidebarLine(String line) {
        Matcher m;
        if ((m = zoneRe.matcher(line)).find()) zone = m.group(1).trim();
        if ((m = multiplierRe.matcher(line)).find()) multiplier = m.group(1).trim();
        if ((m = rebirthsRe.matcher(line)).find()) {
            try {
                onRebirthValue(Integer.parseInt(m.group(1).replace(",", "")));
            } catch (NumberFormatException ignored) {}
        }
        for (int i = 0; i < balanceRes.size(); i++) {
            Matcher bm = balanceRes.get(i).matcher(line);
            if (bm.find()) {
                String name = balanceNames.get(i);
                String raw = bm.group(1).trim();
                String prev = balances.put(name, raw);
                if (prev != null && !prev.equals(raw)) {
                    log("balance", "currency", name, "raw", raw);
                }
            }
        }
    }

    private void onRebirthValue(int n) {
        Integer prev = rebirths;
        if (prev != null && prev == n) return;
        rebirths = n;
        if (prev == null) return; // first read
        if (n > prev) {
            rebirthTimes.addLast(System.currentTimeMillis());
            while (rebirthTimes.size() > 500) rebirthTimes.removeFirst();
            log("rebirth", "rebirths", n, "prev", prev, "delta", n - prev);
        } else {
            ascensions++;
            log("ascension", "via", "rebirth-reset", "rebirthsBefore", prev, "rebirthsAfter", n, "ascensions", ascensions);
        }
    }

    private void pollBossBars(MinecraftClient client) {
        Map<?, ClientBossBar> bars;
        try {
            bars = ((BossBarHudAccessor) client.inGameHud.getBossBarHud()).ycBotChallenge$getBossBars();
        } catch (Throwable t) {
            return;
        }
        Set<String> current = new HashSet<>();
        for (ClientBossBar bar : bars.values()) {
            String title = bar.getName().getString();
            // "Soul Harvest 2x Souls (12m, 9s)" -> key off text before the timer parens
            int paren = title.lastIndexOf('(');
            String key = (paren > 0 ? title.substring(0, paren) : title).trim();
            if (!key.isEmpty()) current.add(key);
        }
        for (String key : current) {
            if (activeBoosts.add(key)) {
                boostSince.put(key, System.currentTimeMillis());
                log("boost_start", "boost", key);
            }
        }
        activeBoosts.removeIf(key -> {
            if (!current.contains(key)) {
                Long since = boostSince.remove(key);
                log("boost_end", "boost", key,
                    "durationMs", since != null ? System.currentTimeMillis() - since : null);
                return true;
            }
            return false;
        });
    }

    /** Wire to ClientReceiveMessageEvents.GAME. */
    public void onGameMessage(Text message, boolean overlay) {
        String text = message.getString();
        if (text == null || text.isBlank()) return;
        for (Pattern p : captchaRes) {
            if (p.matcher(text).find()) {
                captchaMessage = text;
                break;
            }
        }
        if (overlay) {
            Matcher m = actionBarRe.matcher(text);
            if (m.find()) {
                try { rebirthProgressPct = Double.parseDouble(m.group(1)); } catch (NumberFormatException ignored) {}
            }
            return;
        }
        for (Pattern p : ascensionRes) {
            if (p.matcher(text).find()) {
                ascensions++;
                log("ascension", "via", "chat", "message", text, "ascensions", ascensions);
                return;
            }
        }
        for (Pattern p : prestigeRes) {
            if (p.matcher(text).find()) {
                prestiges++;
                log("prestige", "via", "chat", "message", text, "prestiges", prestiges);
                return;
            }
        }
    }

    public void recordKill() {
        killTimes.addLast(System.currentTimeMillis());
        while (killTimes.size() > 1000) killTimes.removeFirst();
    }

    public double killsPerMinute(long windowMs) {
        long now = System.currentTimeMillis();
        long count = killTimes.stream().filter(t -> now - t <= windowMs).count();
        if (count == 0) return 0;
        long span = Math.max(10_000, Math.min(windowMs, now - killTimes.peekFirst()));
        return count / (span / 60_000.0);
    }

    public Double rebirthsPerHour() {
        long now = System.currentTimeMillis();
        List<Long> recent = rebirthTimes.stream().filter(t -> now - t <= 30 * 60_000L).toList();
        if (recent.size() < 2) return null;
        long span = now - recent.get(0);
        return recent.size() / (span / 3600_000.0);
    }

    public String etaNextAscension() {
        Double rate = rebirthsPerHour();
        if (rate == null || rate <= 0 || rebirths == null) return null;
        int target = 50 * (ascensions + 1);
        int remaining = target - rebirths;
        if (remaining <= 0) return "imminent";
        double hours = remaining / rate;
        return hours < 1 ? Math.round(hours * 60) + "m" : String.format("%.1fh", hours);
    }
}

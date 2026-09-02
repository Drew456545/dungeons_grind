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
    public Double swordRemaining = null;
    public Double zoneRemaining = null;
    public boolean becameAffordable = false;
    public String lastUpgradeKind = null;
    public final Set<String> activeBoosts = new HashSet<>();
    private final Map<String, Long> boostSince = new HashMap<>();

    /** DPS estimate from the cooking mob's boss-bar HP slope. */
    private static final Pattern BOSS_HP = Pattern.compile("[\u2764\u2665]\uFE0F?\\s*([0-9]+)");
    private final ArrayDeque<long[]> dpsSamples = new ArrayDeque<>(); // {timeMs, hp}
    private long lastDpsSampleAt = 0;

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
    private final List<Pattern> swordRemainingRes = new ArrayList<>();
    private final List<Pattern> zoneRemainingRes = new ArrayList<>();
    private final List<Pattern> upgradeSuccessRes = new ArrayList<>();
    private final List<Pattern> upgradeFailRes = new ArrayList<>();

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
        if (cfg.swordRemainingPatterns != null)
            for (String p : cfg.swordRemainingPatterns) swordRemainingRes.add(compileLoose(p));
        if (cfg.zoneRemainingPatterns != null)
            for (String p : cfg.zoneRemainingPatterns) zoneRemainingRes.add(compileLoose(p));
        if (cfg.upgradeSuccessPatterns != null)
            for (String p : cfg.upgradeSuccessPatterns) upgradeSuccessRes.add(compileLoose(p));
        if (cfg.upgradeFailPatterns != null)
            for (String p : cfg.upgradeFailPatterns) upgradeFailRes.add(compileLoose(p));
    }

    public Double money() {
        String key = cfg.moneyCurrency == null ? "chicken" : cfg.moneyCurrency.toLowerCase();
        String raw = balances.get(key);
        if (raw == null) raw = balances.get("money");
        if (raw == null && !balances.isEmpty()) {
            raw = balances.values().iterator().next();
        }
        return Amounts.parse(raw);
    }

    public void noteUpgradeSend(boolean sword) {
        lastUpgradeKind = sword ? "sword" : "zone";
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
        Double bal = money();
        if (bal != null) ctx.addProperty("money", bal);
        if (swordRemaining != null) ctx.addProperty("swordRemaining", swordRemaining);
        if (zoneRemaining != null) ctx.addProperty("zoneRemaining", zoneRemaining);
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
                    checkBecameAffordable();
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

    /** Live boss bars (never null). Safe to call every tick. */
    private Map<?, ClientBossBar> bossBars(MinecraftClient client) {
        try {
            return ((BossBarHudAccessor) client.inGameHud.getBossBarHud()).ycBotChallenge$getBossBars();
        } catch (Throwable t) {
            return java.util.Collections.emptyMap();
        }
    }

    /** Text before the heart on a boss bar title, trimmed (e.g. "LVL1 Chicken"). */
    private static String bossBarPrefix(String title) {
        int i = indexOfHeart(title);
        return (i > 0 ? title.substring(0, i) : title).trim();
    }

    private static int indexOfHeart(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u2764' || c == '\u2665') return i;
        }
        return -1;
    }

    /** True if any boss bar's title mentions {@code mobName} (e.g. "Chicken" in "LVL1 Chicken ❤ 78"). */
    public boolean bossBarMatches(String mobName) {
        if (mobName == null || mobName.isBlank()) return false;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.inGameHud == null) return false;
        for (ClientBossBar bar : bossBars(client).values()) {
            String prefix = bossBarPrefix(bar.getName().getString());
            if (prefix.toLowerCase().contains(mobName.toLowerCase())) return true;
        }
        return false;
    }

    /** Current heart HP of the boss bar whose title mentions {@code mobName}, or null. */
    public Double currentHpFor(String mobName) {
        if (mobName == null || mobName.isBlank()) return null;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.inGameHud == null) return null;
        Double best = null;
        for (ClientBossBar bar : bossBars(client).values()) {
            String title = bar.getName().getString();
            if (!bossBarPrefix(title).toLowerCase().contains(mobName.toLowerCase())) continue;
            Matcher m = BOSS_HP.matcher(title);
            if (m.find()) {
                try {
                    double hp = Double.parseDouble(m.group(1));
                    // if multiple match, take the lowest (most-damaged = the one cooking)
                    if (best == null || hp < best) best = hp;
                } catch (NumberFormatException ignored) {}
            }
        }
        return best;
    }

    /** Record a (now, hp) sample for the DPS slope. Call every tick while cooking. */
    public void sampleDpsFor(String mobName) {
        Double hp = currentHpFor(mobName);
        if (hp == null) return;
        long now = System.currentTimeMillis();
        dpsSamples.addLast(new long[]{now, (long) Math.round(hp)});
        long cutoff = now - cfg.dpsWindowMs;
        while (!dpsSamples.isEmpty() && dpsSamples.peekFirst()[0] < cutoff) dpsSamples.removeFirst();
        lastDpsSampleAt = now;
    }

    /** Clear the DPS window (call on connect so it measures only this mob's cook). */
    public void resetDps() {
        dpsSamples.clear();
        lastDpsSampleAt = 0;
    }

    /** Effective damage/sec from the boss-bar HP slope (positive), or null if too few samples. */
    public Double dps() {
        if (dpsSamples.size() < cfg.dpsMinSamples) return null;
        long[] first = dpsSamples.peekFirst();
        long[] last = dpsSamples.peekLast();
        double dt = (last[0] - first[0]) / 1000.0;
        if (dt <= 0) return null;
        double dh = first[1] - last[1]; // hp dropped => positive
        return dh / dt;
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
        parseUpgradeChat(text);
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

    private void parseUpgradeChat(String text) {
        boolean fail = anyMatch(upgradeFailRes, text);
        boolean success = anyMatch(upgradeSuccessRes, text);
        Double swordAmt = firstAmount(swordRemainingRes, text);
        Double zoneAmt = firstAmount(zoneRemainingRes, text);
        if (swordAmt == null && zoneAmt == null && lastUpgradeKind != null) {
            // generic "need 1.2M more" after we just sent a command
            Double any = Amounts.parse(text);
            if (any != null && (fail || text.toLowerCase().contains("need") || text.toLowerCase().contains("remain"))) {
                if ("zone".equals(lastUpgradeKind)) zoneAmt = any;
                else swordAmt = any;
            }
        }
        if (swordAmt != null) {
            swordRemaining = swordAmt;
            log("upgrade_chat", "kind", "sword", "remaining", swordAmt, "raw", text);
        }
        if (zoneAmt != null) {
            zoneRemaining = zoneAmt;
            log("upgrade_chat", "kind", "zone", "remaining", zoneAmt, "raw", text);
        }
        if (success || fail) {
            log("upgrade_result",
                "kind", lastUpgradeKind,
                "success", success && !fail,
                "fail", fail,
                "message", text);
        }
        if (swordAmt != null || zoneAmt != null || success || fail) {
            checkBecameAffordable();
        }
    }

    private void checkBecameAffordable() {
        Double bal = money();
        if (bal == null) return;
        if (swordRemaining != null && bal >= swordRemaining) becameAffordable = true;
        if (zoneRemaining != null && bal >= zoneRemaining) becameAffordable = true;
    }

    private static boolean anyMatch(List<Pattern> res, String text) {
        for (Pattern p : res) if (p.matcher(text).find()) return true;
        return false;
    }

    private static Double firstAmount(List<Pattern> res, String text) {
        for (Pattern p : res) {
            Matcher m = p.matcher(text);
            if (m.find()) {
                Double v = amountFrom(m);
                if (v != null) return v;
            }
        }
        return null;
    }

    private static Double amountFrom(Matcher m) {
        try {
            String named = m.group("amount");
            if (named != null) return Amounts.parse(named);
        } catch (IllegalArgumentException ignored) {}
        if (m.groupCount() >= 1) {
            try { return Amounts.parse(m.group(1)); } catch (Exception ignored) {}
        }
        return Amounts.parse(m.group());
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

package dev.drew.ycbotchallenge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.drew.ycbotchallenge.mixin.BossBarHudAccessor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    public final Map<String, String> balances = new LinkedHashMap<>();
    /** Total price of the next tier — absolute "You need X to purchase", or bal+gap when the line says more/left. */
    public Double swordTarget = null;
    public Double zoneTarget = null;
    private Double swordGap = null;
    private Double zoneGap = null;
    /** Live parsed sidebar amounts (updated every poll). Canonical snapshot is published on an interval. */
    private final Map<String, Double> liveBals = new LinkedHashMap<>();
    private final Map<String, String> liveRaw = new LinkedHashMap<>();
    private final Map<String, Double> snapshotBals = new LinkedHashMap<>();
    private final Map<String, String> snapshotRaw = new LinkedHashMap<>();
    private long lastSnapshotAt = 0;
    private String upgradeChatFrag = null;
    private long upgradeChatFragAt = 0;
    public String lastUpgradeKind = null;
    private long lastUpgradeSendAt = 0;
    private long lastSpendAt = 0;
    private long lastBalSendAt = 0;
    private long lastClassifiedAt = 0;
    /** One-shot unknown-price seed per kind; not reset on bot toggle (avoids re-spam). */
    public boolean swordSeeded = false;
    public boolean zoneSeeded = false;
    private int zoneChangeSeq = 0;
    private String reprobeKind = null;
    private int swordBuysThisZone = 0;
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
    private final Pattern sidebarMoneyRe;
    private final Set<String> seenSidebarLines = new HashSet<>();
    private final List<String> balanceNames = new ArrayList<>();
    private final List<Pattern> balanceRes = new ArrayList<>();
    private final List<Pattern> ascensionRes = new ArrayList<>();
    private final List<Pattern> prestigeRes = new ArrayList<>();
    private final List<Pattern> captchaRes = new ArrayList<>();
    private final List<Pattern> swordRemainingRes = new ArrayList<>();
    private final List<Pattern> zoneRemainingRes = new ArrayList<>();
    private final List<Pattern> upgradeSuccessRes = new ArrayList<>();
    private final List<Pattern> upgradeFailRes = new ArrayList<>();
    private final List<Pattern> upgradeMaxedRes = new ArrayList<>();
    private final List<Pattern> balRes = new ArrayList<>();

    /** Set when a captcha chat line is seen; consumed (and cleared) by the main tick. */
    public volatile String captchaMessage = null;

    /** Rolling per-kill durations (tag -> death), ms. */
    public final ArrayDeque<Long> killDurations = new ArrayDeque<>();
    public volatile boolean swordMaxed = false;
    public volatile boolean zoneMaxed = false;
    /** Median TTK snapshot taken a few kills into a new zone; TTK declines as the sword improves. */
    private Double zoneBaselineTtkMs = null;
    private int zoneKills = 0;
    private Double lastBenchmarkLogged = null;
    private long lastBenchmarkLogAt = 0;
    private long lastIncomeLogAt = 0;
    /** (timeMs, balance) samples from /bal replies and sidebar changes, for the income rate. */
    private final ArrayDeque<double[]> incomeSamples = new ArrayDeque<>();

    public StatsTracker(YCBotChallengeConfig cfg) {
        this.cfg = cfg;
        this.rebirthsRe = Pattern.compile(cfg.rebirthsPattern, Pattern.CASE_INSENSITIVE);
        this.zoneRe = Pattern.compile(cfg.zonePattern, Pattern.CASE_INSENSITIVE);
        this.multiplierRe = Pattern.compile(cfg.multiplierPattern, Pattern.CASE_INSENSITIVE);
        this.actionBarRe = Pattern.compile(cfg.actionBarPattern, Pattern.CASE_INSENSITIVE);
        this.sidebarMoneyRe = compileLoose(cfg.sidebarMoneyPattern);
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
        if (cfg.upgradeMaxedPatterns != null)
            for (String p : cfg.upgradeMaxedPatterns) upgradeMaxedRes.add(compileLoose(p));
        if (cfg.balPatterns != null)
            for (String p : cfg.balPatterns) balRes.add(compileLoose(p));
    }

    public Double money() {
        String key = moneyKey();
        Double v = snapshotBals.get(key);
        if (v != null) return v;
        return liveBals.get(key);
    }

    public double zoneReadiness() {
        return Economy.zoneReadiness(medianTtkMs(), zoneBaselineTtkMs, cfg.zoneReadyTtkMs);
    }

    /** Copy live bals into the canonical snapshot (HUD / logs / buy eval). */
    public void publishSnapshot(boolean force) {
        long now = System.currentTimeMillis();
        int interval = Math.max(500, cfg.scoreboardSnapshotMs);
        if (!force && lastSnapshotAt != 0 && now - lastSnapshotAt < interval) return;
        if (liveBals.isEmpty() && !force) return;
        snapshotBals.clear();
        snapshotBals.putAll(liveBals);
        snapshotRaw.clear();
        snapshotRaw.putAll(liveRaw);
        lastSnapshotAt = now;
        if (logger != null) {
            log("scoreboard_snapshot",
                "bals", formattedBalances(snapshotBals),
                "lines", cfg.debugSidebar ? List.copyOf(liveRaw.values()) : null);
        }
    }

    public Map<String, String> formattedBalances() {
        Map<String, Double> src = snapshotBals.isEmpty() ? liveBals : snapshotBals;
        return formattedBalances(src);
    }

    private static Map<String, String> formattedBalances(Map<String, Double> src) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : src.entrySet()) {
            if (e.getValue() != null) out.put(e.getKey(), Amounts.format(e.getValue()));
        }
        return out;
    }

    public String hudBalancesLine() {
        Map<String, Double> src = snapshotBals.isEmpty() ? liveBals : snapshotBals;
        if (src.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        List<String> names = cfg.sidebarCurrencies != null && !cfg.sidebarCurrencies.isEmpty()
            ? cfg.sidebarCurrencies : List.of("money", "souls", "essence", "shards", "credits");
        for (String name : names) {
            String key = name.toLowerCase(Locale.ROOT);
            Double v = src.get(key);
            if (v == null) continue;
            if (sb.length() > 0) sb.append("  ");
            sb.append(hudColor(key)).append(Amounts.format(v)).append(" ").append(key).append("§r");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String hudColor(String name) {
        return switch (name) {
            case "money" -> "§a";
            case "souls" -> "§c";
            case "essence" -> "§d";
            case "shards" -> "§b";
            case "credits" -> "§7";
            default -> "§f";
        };
    }

    private String moneyKey() {
        String k = cfg.moneyCurrency;
        return (k == null || k.isBlank()) ? "money" : k.toLowerCase(Locale.ROOT);
    }

    public void noteUpgradeSend(boolean sword) {
        lastUpgradeKind = sword ? "sword" : "zone";
        long now = System.currentTimeMillis();
        lastUpgradeSendAt = now;
        lastSpendAt = now;
    }

    public boolean seeded(String kind) {
        return "zone".equals(kind) ? zoneSeeded : swordSeeded;
    }

    public void noteSeeded(String kind) {
        if ("zone".equals(kind)) zoneSeeded = true;
        else swordSeeded = true;
    }

    public boolean sidebarSettled(long nowMs, int settleMs) {
        return Economy.sidebarSettled(nowMs, lastSpendAt, settleMs);
    }

    public void noteProbeSend() {
        lastBalSendAt = System.currentTimeMillis();
    }

    /** False when the most recent upgrade command never produced a classified response (patterns blind). */
    public boolean lastSendClassified() {
        if (lastUpgradeSendAt == 0) return true;
        return lastClassifiedAt >= lastUpgradeSendAt;
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
        if (bal != null) ctx.addProperty("money", Amounts.format(bal));
        Map<String, String> bals = formattedBalances();
        if (!bals.isEmpty()) {
            JsonObject o = new JsonObject();
            bals.forEach(o::addProperty);
            ctx.add("bals", o);
        }
        if (swordTarget != null) ctx.addProperty("swordTarget", Amounts.format(swordTarget));
        if (zoneTarget != null) ctx.addProperty("zoneTarget", Amounts.format(zoneTarget));
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
        Double ttk = medianTtkMs();
        if (zone != null && ttk != null) {
            long now = System.currentTimeMillis();
            boolean moved = lastBenchmarkLogged == null
                || Math.abs(ttk - lastBenchmarkLogged) / lastBenchmarkLogged > 0.05;
            if ((moved && now - lastBenchmarkLogAt > 5_000) || now - lastBenchmarkLogAt > 30_000) {
                lastBenchmarkLogAt = now;
                lastBenchmarkLogged = ttk;
                log("zone_benchmark", "zone", zone, "medianTtkMs", Math.round(ttk),
                    "baselineTtkMs", zoneBaselineTtkMs != null ? Math.round(zoneBaselineTtkMs) : null,
                    "zoneKills", zoneKills);
            }
        }
        Double rate = moneyPerMinute();
        long nowMs = System.currentTimeMillis();
        if (rate != null && nowMs - lastIncomeLogAt > 15_000) {
            lastIncomeLogAt = nowMs;
            Double bal = money();
            log("income", "moneyPerMin", Amounts.format(rate), "balance", bal != null ? Amounts.format(bal) : null);
        }
        publishSnapshot(false);
    }

    private void pollSidebar(MinecraftClient client) {
        Scoreboard sb = client.world.getScoreboard();
        ScoreboardObjective obj = sb.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
        if (obj == null) return;
        List<String> lines = new ArrayList<>();
        for (ScoreboardEntry entry : sb.getScoreboardEntries(obj)) {
            if (entry.hidden()) continue;
            Team team = sb.getScoreHolderTeam(entry.owner());
            String line = Team.decorateName(team, entry.name()).getString();
            line = SidebarParser.strip(line);
            if (line.isBlank()) continue;
            lines.add(line);
            handleSidebarProgress(line);
        }
        Map<String, SidebarParser.Hit> hits = SidebarParser.parseCurrencies(lines, cfg.sidebarCurrencies);
        for (SidebarParser.Hit hit : hits.values()) applyCurrency(hit.currency(), hit.rawAmount(), hit.value(), hit.line());
        if (!liveBals.containsKey(moneyKey()) && sidebarMoneyRe != null) {
            for (String line : lines) {
                Matcher mm = sidebarMoneyRe.matcher(line);
                if (!mm.find()) continue;
                String g1 = mm.group(1);
                String g2 = mm.groupCount() >= 2 ? mm.group(2) : null;
                Double v = Amounts.parse(g1 != null ? g1 : g2);
                if (v == null) continue;
                applyCurrency(moneyKey(), g1 != null ? g1 : g2, v, line);
                break;
            }
        }
        for (String line : lines) applyBalancePatterns(line);
    }

    private void handleSidebarProgress(String line) {
        if (cfg.debugSidebar) {
            if (seenSidebarLines.size() > 500) seenSidebarLines.clear();
            if (seenSidebarLines.add(line)) log("sidebar_raw", "line", line);
        }
        Matcher m;
        if ((m = zoneRe.matcher(line)).find()) {
            String z = m.group(1).trim();
            if (!z.equals(zone)) {
                zone = z;
                zoneChangeSeq++;
                zoneBaselineTtkMs = null;
                zoneKills = 0;
                swordBuysThisZone = 0;
                lastBenchmarkLogged = null;
                log("zone_change", "zone", z);
            }
        }
        if ((m = multiplierRe.matcher(line)).find()) multiplier = m.group(1).trim();
        if ((m = rebirthsRe.matcher(line)).find()) {
            try {
                onRebirthValue(Integer.parseInt(m.group(1).replace(",", "")));
            } catch (NumberFormatException ignored) {}
        }
    }

    private void applyCurrency(String name, String raw, double value, String line) {
        String key = name.toLowerCase(Locale.ROOT);
        Double prev = liveBals.put(key, value);
        liveRaw.put(key, raw);
        balances.put(key, raw);
        boolean changed = prev == null || Math.abs(prev - value) > 1e-6;
        if (changed) {
            if (key.equals(moneyKey())) noteBalance(value);
            log("balance", "currency", key, "raw", raw, "parsed", Amounts.format(value), "line", line);
        }
    }

    private void applyBalancePatterns(String line) {
        for (int i = 0; i < balanceRes.size(); i++) {
            String name = balanceNames.get(i).toLowerCase(Locale.ROOT);
            if (liveBals.containsKey(name)) continue;
            Matcher bm = balanceRes.get(i).matcher(line);
            if (!bm.find()) continue;
            String g1 = bm.group(1);
            String g2 = bm.groupCount() >= 2 ? bm.group(2) : null;
            String raw = g1 != null ? g1 : g2;
            if (raw == null) continue;
            raw = raw.trim();
            Double v = Amounts.parse(raw);
            if (v == null) continue;
            applyCurrency(name, raw, v, line);
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
        boolean known = false;
        if (!overlay) known = parseBalReply(text);
        // Upgrade fail/success can land as overlay (system/action-bar) or chat.
        if (parseUpgradeChat(text)) known = true;
        if (overlay) {
            Matcher m = actionBarRe.matcher(text);
            if (m.find()) {
                try { rebirthProgressPct = Double.parseDouble(m.group(1)); } catch (NumberFormatException ignored) {}
            }
            if (!known && lastUpgradeSendAt != 0
                && System.currentTimeMillis() - lastUpgradeSendAt <= 6_000) {
                log("upgrade_response_raw", "raw", text, "overlay", true);
            }
            return;
        }
        for (Pattern p : ascensionRes) {
            if (p.matcher(text).find()) {
                ascensions++;
                log("ascension", "via", "chat", "message", text, "ascensions", ascensions);
                known = true;
                break;
            }
        }
        for (Pattern p : prestigeRes) {
            if (p.matcher(text).find()) {
                prestiges++;
                log("prestige", "via", "chat", "message", text, "prestiges", prestiges);
                known = true;
                break;
            }
        }
        // Unrecognized line right after a command send: capture the server's actual wording
        // so the patterns can be tuned from evidence instead of guessing.
        if (!known && lastUpgradeSendAt != 0
            && System.currentTimeMillis() - lastUpgradeSendAt <= 6_000) {
            log("upgrade_response_raw", "raw", text);
        }
    }

    /** A balCommand reply: authoritative current balance + income-rate sample. Only plausible right after we asked. */
    private boolean parseBalReply(String text) {
        if (lastBalSendAt == 0 || System.currentTimeMillis() - lastBalSendAt > 8_000) return false;
        for (Pattern p : balRes) {
            Matcher m = p.matcher(text);
            if (!m.find()) continue;
            Double v = amountFrom(m);
            if (v == null) return true; // pattern matched, amount unreadable — still a known line
            String key = moneyKey();
            liveBals.put(key, v);
            liveRaw.put(key, Amounts.format(v));
            balances.put(key, Amounts.format(v));
            snapshotBals.put(key, v);
            snapshotRaw.put(key, Amounts.format(v));
            noteBalance(v);
            publishSnapshot(true);
            log("balance_probe", "balance", Amounts.format(v), "raw", text);
            return true;
        }
        return false;
    }

    private boolean parseUpgradeChat(String text) {
        long now = System.currentTimeMillis();
        if (upgradeChatFrag != null && now - upgradeChatFragAt <= 2000) {
            boolean stitched = parseUpgradeChatOnce(upgradeChatFrag + " " + text);
            if (stitched) {
                upgradeChatFrag = null;
                return true;
            }
        }
        return parseUpgradeChatOnce(text);
    }

    private boolean parseUpgradeChatOnce(String text) {
        boolean fail = anyMatch(upgradeFailRes, text);
        boolean success = anyMatch(upgradeSuccessRes, text);
        boolean maxed = anyMatch(upgradeMaxedRes, text);
        long sinceSend = lastUpgradeSendAt == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - lastUpgradeSendAt;
        if (!fail && !success && !maxed && lastUpgradeKind != null && sinceSend <= 15_000) {
            String l = text.toLowerCase(Locale.ROOT);
            if (l.contains("need") || l.contains("remain") || l.contains("left")
                || l.contains("afford") || l.contains("cost") || l.contains("enough")) {
                if (!Amounts.parseAll(text).isEmpty()) fail = true;
            }
        }
        if (!fail && !success && !maxed) {
            // first half of a split fail ("You don't have enough...") — wait for the amount line
            if (sinceSend <= 6_000) {
                String l = text.toLowerCase(Locale.ROOT);
                if (l.contains("enough") || l.contains("need") || l.contains("afford")
                    || l.contains("purchase") || l.contains("cost")) {
                    upgradeChatFrag = text;
                    upgradeChatFragAt = System.currentTimeMillis();
                }
            }
            return false;
        }
        if ((success || maxed) && !fail && sinceSend > 15_000 && !text.toLowerCase(Locale.ROOT).contains("you")) {
            return false;
        }

        String lower = text.toLowerCase(Locale.ROOT);
        String kind = lower.contains("sword") ? "sword"
            : (lower.contains("zone") || lower.contains("stage")) ? "zone"
            : lastUpgradeKind;
        if (kind == null) return false;

        Double amt = firstAmount("zone".equals(kind) ? zoneRemainingRes : swordRemainingRes, text);
        if (amt == null) {
            java.util.List<Double> all = Amounts.parseAll(text);
            if (!all.isEmpty()) amt = all.get(0);
        }

        if (maxed) {
            if ("zone".equals(kind)) { zoneMaxed = true; zoneTarget = null; zoneGap = null; }
            else { swordMaxed = true; swordTarget = null; swordGap = null; }
            lastClassifiedAt = System.currentTimeMillis();
            upgradeChatFrag = null;
            log("upgrade_maxed", "kind", kind, "raw", text);
            return true;
        }
        if (success && !fail) {
            Double knownPrice = "zone".equals(kind) ? zoneTarget : swordTarget;
            if ("zone".equals(kind)) { zoneTarget = null; zoneGap = null; }
            else { swordTarget = null; swordGap = null; swordBuysThisZone++; }
            reprobeKind = kind;
            lastClassifiedAt = System.currentTimeMillis();
            upgradeChatFrag = null;
            Double paid = amt != null ? amt : knownPrice;
            if (paid != null) {
                debitMoney(paid);
                log("upgrade_result", "kind", kind, "success", true, "fail", false,
                    "paid", Amounts.format(paid), "message", text);
            } else {
                log("upgrade_result", "kind", kind, "success", true, "fail", false, "message", text);
            }
            return true;
        }
        if (fail) {
            lastClassifiedAt = System.currentTimeMillis();
            if (amt == null) {
                upgradeChatFrag = text;
                upgradeChatFragAt = System.currentTimeMillis();
                log("upgrade_result", "kind", kind, "success", false, "fail", true, "message", text);
                return true;
            }
            upgradeChatFrag = null;
            Double bal = money();
            Double target = Economy.targetFromFail(amt, bal, text);
            boolean gap = Economy.isGapNeed(text);
            if ("zone".equals(kind)) {
                zoneGap = gap ? amt : null;
                zoneTarget = target;
            } else {
                swordGap = gap ? amt : null;
                swordTarget = target;
            }
            log("upgrade_chat", "kind", kind,
                "gap", gap ? Amounts.format(amt) : null,
                "target", target != null ? Amounts.format(target) : null,
                "absolute", !gap, "raw", text);
            log("upgrade_result", "kind", kind, "success", false, "fail", true, "message", text);
        }
        return true;
    }

    /** Subtract a known purchase cost from the local money balance. */
    private void debitMoney(double paid) {
        String key = moneyKey();
        Double cur = liveBals.get(key);
        if (cur == null) cur = snapshotBals.get(key);
        if (cur == null) return;
        double next = Math.max(0, cur - paid);
        liveBals.put(key, next);
        liveRaw.put(key, Amounts.format(next));
        snapshotBals.put(key, next);
        snapshotRaw.put(key, Amounts.format(next));
        balances.put(key, Amounts.format(next));
        noteBalance(next);
    }

    private void checkBecameAffordable() {
        // superseded by the kill-driven eval comparing money() against swordTarget/zoneTarget
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

    /**
     * Connect-to-boss-bar-gone duration for one kill, normalized by the rarity HP scale
     * (a 1.40x legendary shouldn't read as slower farming). Feeds the median TTK and the
     * per-zone baseline.
     */
    public void recordKillDuration(long ms, String rarity) {
        double scale = 1.0;
        if (rarity != null && cfg.rarityHpScale != null) {
            Double s = cfg.rarityHpScale.get(rarity.toUpperCase(java.util.Locale.ROOT));
            if (s != null) scale += s;
        }
        killDurations.addLast(Math.round(ms / scale));
        while (killDurations.size() > 200) killDurations.removeFirst();
        zoneKills++;
        if (zoneBaselineTtkMs == null && zoneKills >= 3) zoneBaselineTtkMs = medianTtkMs();
    }

    /** Median of the last {@code ttkWindowKills} kill durations, or null with < 3 samples. */
    public Double medianTtkMs() {
        int n = Math.min(Math.max(3, cfg.ttkWindowKills), killDurations.size());
        if (n < 3) return null;
        java.util.List<Long> tail = new ArrayList<>();
        int skip = killDurations.size() - n;
        int i = 0;
        for (Long d : killDurations) {
            if (i++ >= skip) tail.add(d);
        }
        tail.sort(null);
        return (double) tail.get(tail.size() / 2);
    }

    public Double zoneBaselineTtkMs() { return zoneBaselineTtkMs; }

    /** Increments on every sidebar zone change; CombatController diffs it to trigger a retarget. */
    public int zoneChangeSeq() { return zoneChangeSeq; }

    /** Kind whose successful purchase should be re-sent once (up-arrow style) to learn the next tier's gap. */
    public String consumeReprobe() {
        String k = reprobeKind;
        reprobeKind = null;
        return k;
    }

    /** Successful sword purchases since the current zone started. */
    public int swordBuysThisZone() { return swordBuysThisZone; }

    public double killsPerSecond(long windowMs) {
        return killsPerMinute(windowMs) / 60.0;
    }

    /** Earning slope (money/min) over the trailing ~5 min of balance samples; null when unknown. */
    public Double moneyPerMinute() {
        long now = System.currentTimeMillis();
        while (incomeSamples.size() > 2 && now - incomeSamples.peekFirst()[0] > 300_000) {
            incomeSamples.removeFirst();
        }
        if (incomeSamples.size() < 2) return null;
        double[] f = incomeSamples.peekFirst();
        double[] l = incomeSamples.peekLast();
        double dtMin = (l[0] - f[0]) / 60_000.0;
        if (dtMin < 0.5) return null;
        double slope = (l[1] - f[1]) / dtMin;
        // purchases create negative steps; report only the positive earning rate
        return slope > 0 ? slope : null;
    }

    private void noteBalance(double bal) {
        long now = System.currentTimeMillis();
        if (!incomeSamples.isEmpty()) {
            double[] last = incomeSamples.peekLast();
            if (Math.abs(last[1] - bal) < 1e-9 && now - last[0] < 5_000) return;
        }
        incomeSamples.addLast(new double[]{now, bal});
        while (incomeSamples.size() > 100) incomeSamples.removeFirst();
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

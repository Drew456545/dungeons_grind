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
    /** Absolute next-tier price (bal at fail + gap), null while unknown. */
    public Double swordTarget = null;
    public Double zoneTarget = null;
    /** Last seen remaining gap ("You need X Money") per kind — HUD/logging. */
    private Double swordGap = null;
    private Double zoneGap = null;
    /** Price of the most recent known tier per kind — unknown-price retries fire past this. */
    private Double swordLastPrice = null;
    private Double zoneLastPrice = null;
    /** Chat-driven money book: /bal seed + reward-summary accrual + fail-implied anchors. */
    private final MoneyBook book = new MoneyBook();
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
    /** Last fail-line timestamps per kind — silence after a send is the success signal. */
    private volatile long lastSwordFailAt = 0;
    private volatile long lastZoneFailAt = 0;
    /** One-shot unknown-price seed per kind; not reset on bot toggle (avoids re-spam). */
    public boolean swordSeeded = false;
    public boolean zoneSeeded = false;
    /** An unknown-price exploratory send is in flight / unresolved per kind. */
    private boolean swordExploratorySent = false;
    private boolean zoneExploratorySent = false;
    /** Our own /zone max teleports us — the stop protocol ignores displacements until then. */
    private long expectTeleportUntil = 0;
    /** Set when a fail arrives with an unknown balance; the controller re-seeds /bal once. */
    private boolean balReseedWanted = false;
    private long lastBalReseedAt = 0;
    private int zoneChangeSeq = 0;
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
    private final List<Pattern> upgradeFailRes = new ArrayList<>();
    private final List<Pattern> upgradeMaxedRes = new ArrayList<>();
    private final List<Pattern> balRes = new ArrayList<>();
    private final Pattern needAmountRe;
    private final Pattern summaryHeaderRe;
    private final Pattern summaryMoneyRe;

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
        if (cfg.upgradeFailPatterns != null)
            for (String p : cfg.upgradeFailPatterns) upgradeFailRes.add(compileLoose(p));
        if (cfg.upgradeMaxedPatterns != null)
            for (String p : cfg.upgradeMaxedPatterns) upgradeMaxedRes.add(compileLoose(p));
        if (cfg.balPatterns != null)
            for (String p : cfg.balPatterns) balRes.add(compileLoose(p));
        needAmountRe = compileLoose(cfg.upgradeNeedAmountPattern);
        summaryHeaderRe = compileLoose(cfg.summaryHeaderPattern);
        summaryMoneyRe = compileLoose(cfg.summaryMoneyPattern);
    }

    /** Last time the sidebar money row parsed (board is live). */
    private long lastSidebarMoneyAt = 0;

    /**
     * Best current money estimate: the live sidebar row when fresh (it is the
     * board's own truth), else the chat-driven book (exact anchor + trailing
     * summary rate, frozen 90s past the last anchor), else the last sidebar read.
     */
    public Double money() {
        long now = System.currentTimeMillis();
        String key = moneyKey();
        Double side = liveBals.get(key);
        if (side != null && now - lastSidebarMoneyAt <= 3_000) return side;
        Double est = book.estimate(now);
        if (est != null) return est;
        Double v = snapshotBals.get(key);
        return v != null ? v : side;
    }

    /** Earning rate: exact summary-window rate when seen, else the balance-delta slope. */
    public Double incomePerMinute() {
        if (book.ratePerMs() > 0) return book.ratePerMs() * 60_000.0;
        return moneyPerMinute();
    }

    /** Write the book's exact value through to the sidebar books so HUD/logs stay consistent. */
    private void writeBookThrough() {
        Double est = book.exact();
        if (est == null) return;
        String key = moneyKey();
        liveBals.put(key, est);
        liveRaw.put(key, Amounts.format(est));
        balances.put(key, Amounts.format(est));
        snapshotBals.put(key, est);
        snapshotRaw.put(key, Amounts.format(est));
        noteBalance(est);
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
        // Advancing a zone teleports the player — exempt our own send from the stop protocol.
        if (!sword) expectTeleportUntil = now + Math.max(0, cfg.expectedTeleportAfterZoneMs);
    }

    public boolean isTeleportExpected(long now) {
        return now < expectTeleportUntil;
    }

    public void clearTeleportExpected() {
        expectTeleportUntil = 0;
    }

    public boolean seeded(String kind) {
        return "zone".equals(kind) ? zoneSeeded : swordSeeded;
    }

    public void noteSeeded(String kind) {
        if ("zone".equals(kind)) zoneSeeded = true;
        else swordSeeded = true;
    }

    public boolean exploratorySent(String kind) {
        return "zone".equals(kind) ? zoneExploratorySent : swordExploratorySent;
    }

    public void noteExploratorySent(String kind) {
        if ("zone".equals(kind)) zoneExploratorySent = true;
        else swordExploratorySent = true;
    }

    /** Price of the most recent known tier (retry threshold while the price is unknown). */
    public Double lastPrice(String kind) {
        return "zone".equals(kind) ? zoneLastPrice : swordLastPrice;
    }

    /** Last remaining gap seen in a fail line (HUD/logging). */
    public Double lastGap(String kind) {
        return "zone".equals(kind) ? zoneGap : swordGap;
    }

    /** No fail line within the response window after our send = the purchase succeeded. */
    public boolean failSince(String kind, long sendAt) {
        long at = "zone".equals(kind) ? lastZoneFailAt : lastSwordFailAt;
        return sendAt > 0 && at >= sendAt;
    }

    /** A fail arrived while the balance was unknown — the controller re-seeds /bal once. */
    public boolean consumeBalReseed() {
        boolean w = balReseedWanted;
        balReseedWanted = false;
        return w;
    }

    public boolean sidebarSettled(long nowMs, int settleMs) {
        return Economy.sidebarSettled(nowMs, lastSpendAt, settleMs);
    }

    public void noteProbeSend() {
        lastBalSendAt = System.currentTimeMillis();
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
        Double rate = incomePerMinute();
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
        if (sidebarMoneyRe != null) {
            for (String line : lines) {
                Matcher mm = sidebarMoneyRe.matcher(line);
                if (!mm.find()) continue;
                String raw = null;
                for (int g = 1; g <= mm.groupCount(); g++) {
                    if (mm.group(g) != null) { raw = mm.group(g); break; }
                }
                if (raw == null) continue;
                Double v = Amounts.parse(raw);
                if (v == null) continue;
                applyCurrency(moneyKey(), raw, v, line);
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
        if (key.equals(moneyKey())) {
            long nowMs = System.currentTimeMillis();
            lastSidebarMoneyAt = nowMs;
            // Money collapsing to ~zero while we didn't just buy anything = a rebirth
            // wiped the balance (the sidebar rebirth counter is the primary signal;
            // this covers boards where that row isn't always rendered).
            if (prev != null && prev >= 1e9 && value <= Math.max(1e6, prev * 0.01)
                && nowMs - lastSpendAt > 10_000) {
                rebirthReset("money-collapse");
            }
        }
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
            rebirthReset("sidebar-counter");
        } else {
            ascensions++;
            log("ascension", "via", "rebirth-reset", "rebirthsBefore", prev, "rebirthsAfter", n, "ascensions", ascensions);
            rebirthReset("ascension");
        }
    }

    /**
     * A rebirth zeroes money and resets sword/zone progression — every learned
     * price, maxed flag, and seed flag is stale the moment it happens. Wipe the
     * economy so the loop re-discovers post-rebirth prices from scratch.
     */
    private void rebirthReset(String via) {
        swordTarget = null;
        zoneTarget = null;
        swordGap = null;
        zoneGap = null;
        swordLastPrice = null;
        zoneLastPrice = null;
        swordMaxed = false;
        zoneMaxed = false;
        swordSeeded = false;
        zoneSeeded = false;
        swordExploratorySent = false;
        zoneExploratorySent = false;
        swordBuysThisZone = 0;
        upgradeChatFrag = null;
        log("economy_reset", "via", via);
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

    /** Wire to ClientReceiveMessageEvents.GAME. Multi-line packets are split and classified per line. */
    public void onGameMessage(Text message, boolean overlay) {
        String rawAll = message.getString();
        if (rawAll == null || rawAll.isBlank()) return;
        for (String line : rawAll.split("\\R")) {
            onChatLine(ChatClassifier.clean(line), overlay);
        }
    }

    private void onChatLine(String text, boolean overlay) {
        if (text.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Pattern p : captchaRes) {
            if (p.matcher(text).find()) {
                captchaMessage = text;
                break;
            }
        }
        boolean known = false;
        // Income summary lines are exact earnings — anchored, prefix-free, parse anytime.
        Integer windowS = ChatClassifier.summaryWindowSeconds(text, summaryHeaderRe);
        if (windowS != null) {
            book.noteSummaryWindow(windowS * 1000L);
            known = true;
        }
        Double earned = ChatClassifier.summaryMoney(text, summaryMoneyRe);
        if (earned != null) {
            book.accrue(earned, book.summaryWindowMs(), now);
            writeBookThrough();
            log("income_summary", "earned", Amounts.format(earned),
                "windowS", book.summaryWindowMs() / 1000,
                "balance", book.exact() != null ? Amounts.format(book.exact()) : null);
            known = true;
        }
        // Upgrade responses: strict gate — only within the window after our own
        // send, never on player/broadcast lines, anchored patterns only.
        if (parseUpgradeResponse(text, now)) known = true;
        // /bal replies: window-gated and anchored; classified AFTER upgrade lines so
        // a fail line can never be eaten as a balance (the 0.8.x corruption bug).
        if (!overlay && parseBalReply(text, now)) known = true;
        // Evidence net: raw-log unrecognized lines after our own sends — upgrade
        // commands (6s) AND /bal probes (8s), so reply formats are always captured.
        boolean nearSend = (lastUpgradeSendAt != 0 && now - lastUpgradeSendAt <= 6_000)
            || (lastBalSendAt != 0 && now - lastBalSendAt <= 8_000);
        if (overlay) {
            Matcher m = actionBarRe.matcher(text);
            if (m.find()) {
                try { rebirthProgressPct = Double.parseDouble(m.group(1)); } catch (NumberFormatException ignored) {}
            }
            if (!known && nearSend) {
                log("upgrade_response_raw", "raw", text, "overlay", true);
            }
            return;
        }
        // "All mobs have been respawned in your zone." — a zone advance happened.
        if (text.toLowerCase(Locale.ROOT).contains("mobs have been respawned in your zone")) {
            zoneChangeSeq++;
            zoneBaselineTtkMs = null;
            zoneKills = 0;
            swordBuysThisZone = 0;
            lastBenchmarkLogged = null;
            log("zone_change", "via", "respawn-broadcast");
            known = true;
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
        if (!known && nearSend) {
            log("upgrade_response_raw", "raw", text);
        }
    }

    /**
     * A balCommand reply: exact balance anchor. Only plausible right after we asked,
     * never from broadcast lines, anchored formats only (" - Money: (1.09T)").
     */
    private boolean parseBalReply(String text, long now) {
        if (lastBalSendAt == 0 || now - lastBalSendAt > 8_000) return false;
        if (ChatClassifier.isPlayerOrBroadcast(text)) return false;
        Double v = ChatClassifier.balReply(text, balRes);
        if (v == null) return false;
        book.anchor(v, now);
        writeBookThrough();
        publishSnapshot(true);
        log("balance_probe", "balance", Amounts.format(v), "raw", text);
        return true;
    }

    /**
     * Strictly-gated upgrade responses. Fail lines carry the REMAINING GAP
     * ("You need 781.04B Money..."), which both teaches the absolute price
     * (bal + gap) and — when the price is already known — re-anchors the book
     * exactly (price − gap). Success is never parsed from wording: no fail line
     * within the window after our send means the purchase went through (see
     * {@link #onUpgradeSuccess}, driven by the controller's settle phase).
     */
    private boolean parseUpgradeResponse(String text, long now) {
        if (lastUpgradeSendAt == 0 || now - lastUpgradeSendAt > Math.max(500, cfg.upgradeResponseWindowMs)) {
            upgradeChatFrag = null;
            return false;
        }
        if (ChatClassifier.isPlayerOrBroadcast(text)) return false;

        // Stitched split fail: first half held from the previous line.
        if (upgradeChatFrag != null && now - upgradeChatFragAt <= 2000) {
            String joined = upgradeChatFrag + " " + text;
            Double g = ChatClassifier.needAmount(joined, needAmountRe);
            String kind = ChatClassifier.kindOf(joined, lastUpgradeKind);
            if (g != null && kind != null) {
                upgradeChatFrag = null;
                onFail(kind, g, joined, now);
                return true;
            }
        }

        if (anyMatch(upgradeMaxedRes, text)) {
            String kind = ChatClassifier.kindOf(text, lastUpgradeKind);
            if (kind == null) return false;
            if ("zone".equals(kind)) { zoneMaxed = true; zoneTarget = null; zoneGap = null; }
            else { swordMaxed = true; swordTarget = null; swordGap = null; }
            upgradeChatFrag = null;
            log("upgrade_maxed", "kind", kind, "raw", text);
            return true;
        }

        boolean failShape = anyMatch(upgradeFailRes, text);
        Double gap = ChatClassifier.needAmount(text, needAmountRe);
        if (failShape && gap == null) {
            // First half of a split fail ("You don't have enough...") — wait for the amount line.
            upgradeChatFrag = text;
            upgradeChatFragAt = now;
            return true;
        }
        if (!failShape && gap == null) return false;
        String kind = ChatClassifier.kindOf(text, lastUpgradeKind);
        if (kind == null) return false;
        onFail(kind, gap, text, now);
        return true;
    }

    private void onFail(String kind, double gap, String raw, long now) {
        boolean zone = "zone".equals(kind);
        Double knownPrice = zone ? zoneTarget : swordTarget;
        Double price;
        if (knownPrice != null) {
            // Server truth: we are `gap` short of a known price → exact balance anchor.
            book.anchor(Math.max(0, knownPrice - gap), now);
            writeBookThrough();
            price = knownPrice;
        } else {
            price = Economy.priceFromFail(gap, money());
            if (price == null && lastBalReseedAt < now - 60_000) {
                // Can't solve price without a balance — one human-plausible /bal re-seed.
                lastBalReseedAt = now;
                balReseedWanted = true;
            }
        }
        if (zone) {
            zoneGap = gap;
            if (price != null) { zoneTarget = price; zoneExploratorySent = false; }
            lastZoneFailAt = now;
        } else {
            swordGap = gap;
            if (price != null) { swordTarget = price; swordExploratorySent = false; }
            lastSwordFailAt = now;
        }
        log("upgrade_chat", "kind", kind,
            "gap", Amounts.format(gap),
            "target", price != null ? Amounts.format(price) : null,
            "balance", book.exact() != null ? Amounts.format(book.exact()) : null,
            "raw", raw);
        log("upgrade_result", "kind", kind, "success", false, "fail", true, "message", raw);
    }

    /**
     * Silence-success bookkeeping, driven by the controller after the response
     * window elapses with no fail line: tentative debit of the known price (the
     * post-buy /bal re-seed makes it exact), price reset, old price remembered
     * as the unknown-price retry threshold.
     */
    public void onUpgradeSuccess(String kind, long now) {
        boolean zone = "zone".equals(kind);
        Double price = zone ? zoneTarget : swordTarget;
        if (price != null) {
            book.debit(price, now);
            writeBookThrough();
        }
        if (zone) {
            if (zoneTarget != null) zoneLastPrice = zoneTarget;
            zoneTarget = null;
            zoneGap = null;
            zoneExploratorySent = false;
        } else {
            if (swordTarget != null) swordLastPrice = swordTarget;
            swordTarget = null;
            swordGap = null;
            swordExploratorySent = false;
            swordBuysThisZone++;
        }
        lastSpendAt = now;
        log("upgrade_result", "kind", kind, "success", true, "fail", false,
            "paid", price != null ? Amounts.format(price) : null, "via", "silence");
    }

    private static boolean anyMatch(List<Pattern> res, String text) {
        for (Pattern p : res) if (p.matcher(text).find()) return true;
        return false;
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

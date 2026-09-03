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
    public Double rebirthTarget = null;
    /** Last seen remaining gap ("You need X Money") per kind — HUD/logging. */
    private Double swordGap = null;
    private Double zoneGap = null;
    private Double rebirthGap = null;
    /** Price of the most recent known tier per kind — unknown-price retries fire past this. */
    private Double swordLastPrice = null;
    private Double zoneLastPrice = null;
    private Double rebirthLastPrice = null;
    /** When each kind's current price was first learned — hesitation applies only to long saves. */
    private long swordPriceSeenAt = 0;
    private long zonePriceSeenAt = 0;
    private long rebirthPriceSeenAt = 0;
    /** Per-success rolled growth before an unknown-price retry, so the margin is never the same twice. */
    private double swordRetryGrowth = 0;
    private double zoneRetryGrowth = 0;
    private double rebirthRetryGrowth = 0;
    /** Last rebirth seen by any signal; the controller schedules the deferred /rebirth re-probe from it. */
    public volatile long lastRebirthAt = 0;
    /** Learned prices persisted per username (see StateStore). */
    private StateStore stateStore;
    private String stateUser;
    private long stateDirtyAt = 0;
    /** Why the next expected teleport happens ("zone" advance or "rebirth") — picks the settle length. */
    private String expectTeleportReason = "zone";
    /** Trailing income rate from "Reward Summary" money lines (EMA, per ms) and the window they cover. */
    private double summaryRatePerMs = 0;
    private long summaryWindowMs = 60_000;
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
    /** Sidebar balance at the moment of our last upgrade send (retry floor after a seed buy with no success amount). */
    private Double lastSendBal = null;
    /** Last success line (chat) attributed to our send — the controller holds SETTLE briefly after it for more lines. */
    public volatile long lastSuccessAt = 0;
    /** Last fail-line timestamps per kind — silence after a send is the success signal. */
    private volatile long lastSwordFailAt = 0;
    private volatile long lastZoneFailAt = 0;
    private volatile long lastRebirthFailAt = 0;
    /** One-shot unknown-price seed per kind; not reset on bot toggle (avoids re-spam). */
    public boolean swordSeeded = false;
    public boolean zoneSeeded = false;
    public boolean rebirthSeeded = false;
    /** An unknown-price exploratory send is in flight / unresolved per kind. */
    private boolean swordExploratorySent = false;
    private boolean zoneExploratorySent = false;
    private boolean rebirthExploratorySent = false;
    /** Our own /zone max teleports us — the stop protocol ignores displacements until then. */
    private long expectTeleportUntil = 0;
    private int zoneChangeSeq = 0;
    private long lastZoneChangeAt = 0;
    private int swordBuysThisZone = 0;
    /** Zone proxy: the LVLn prefix on mob boss bars (the EnchantedMC sidebar has no Zone row). */
    private static final Pattern BOSS_LEVEL = Pattern.compile("(?i)\\bLVL\\.?\\s*(\\d+)");
    private Integer bossLevel = null;
    private Integer pendingBossLevel = null;
    private int pendingBossLevelPolls = 0;
    private boolean zoneFromSidebar = false;
    public final Set<String> activeBoosts = new HashSet<>();
    private final Map<String, Long> boostSince = new HashMap<>();

    /** DPS estimate from the cooking mob's boss-bar HP slope (HP parsed with suffixes via ChatClassifier.bossBarHp). */
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
    private final List<Pattern> upgradeSuccessRes = new ArrayList<>();
    private final Pattern needAmountRe;
    private final Pattern summaryHeaderRe;
    private final Pattern summaryMoneyRe;

    /** Set when a captcha chat line is seen; consumed (and cleared) by the main tick. */
    public volatile String captchaMessage = null;
    /** Last soft captcha hint line (captchaChatHintPatterns); the map detector confirms at once within 10s of it. */
    public volatile long captchaHintAt = 0;
    private final List<Pattern> captchaHintRes = new ArrayList<>();
    /** Evidence net for unclassified server lines (chat_raw). */
    private final RawChatNet rawNet;
    /** Amount suffixes seen on the sidebar so far (amount_suffix logged on first sight, with the previous row). */
    private final Set<String> suffixesSeen = new HashSet<>();
    // Giveaways (0.9.17): announcement seq for the controller, prize, outcome counters.
    private final List<Pattern> giveawayAnnounceRes = new ArrayList<>();
    private final List<Pattern> giveawayJoinedRes = new ArrayList<>();
    private final List<Pattern> giveawayWonRes = new ArrayList<>();
    /** "[!] You have successfully rebirthed." / "Rebirth Milestone Completed" (15:23 log). */
    private final List<Pattern> rebirthChatRes = new ArrayList<>();
    public volatile int giveawaySeq = 0;
    public volatile long giveawaySeenAt = 0;
    public volatile String giveawayPrize = null;
    public volatile int giveawaysJoined = 0;
    public volatile int giveawaysWon = 0;
    /** Bumped on each of our own wins; the controller types a reply on the seq. */
    public volatile int giveawayWonSeq = 0;

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
        if (cfg.captchaChatHintPatterns != null) {
            for (String p : cfg.captchaChatHintPatterns) captchaHintRes.add(compileLoose(p));
        }
        rawNet = new RawChatNet(cfg.chatRawPerMinute);
        if (cfg.giveawayAnnouncePatterns != null) for (String p : cfg.giveawayAnnouncePatterns) giveawayAnnounceRes.add(compileLoose(p));
        if (cfg.giveawayJoinedPatterns != null) for (String p : cfg.giveawayJoinedPatterns) giveawayJoinedRes.add(compileLoose(p));
        if (cfg.giveawayWonPatterns != null) for (String p : cfg.giveawayWonPatterns) giveawayWonRes.add(compileLoose(p));
        if (cfg.rebirthChatPatterns != null) for (String p : cfg.rebirthChatPatterns) rebirthChatRes.add(compileLoose(p));
        if (cfg.upgradeFailPatterns != null)
            for (String p : cfg.upgradeFailPatterns) upgradeFailRes.add(compileLoose(p));
        if (cfg.upgradeMaxedPatterns != null)
            for (String p : cfg.upgradeMaxedPatterns) upgradeMaxedRes.add(compileLoose(p));
        if (cfg.upgradeSuccessPatterns != null)
            for (String p : cfg.upgradeSuccessPatterns) upgradeSuccessRes.add(compileLoose(p));
        needAmountRe = compileLoose(cfg.upgradeNeedAmountPattern);
        summaryHeaderRe = compileLoose(cfg.summaryHeaderPattern);
        summaryMoneyRe = compileLoose(cfg.summaryMoneyPattern);
    }

    /** Last time the sidebar money row parsed (board is live). */
    private long lastSidebarMoneyAt = 0;

    /**
     * Current money: the live sidebar row (the board's own truth, reread every
     * second and credited ~1s after each kill), else the last snapshot. Null
     * until the row has parsed once. There is no chat-side estimate any more —
     * the 0.9.x money book projected income between anchors and wrote its own
     * estimate back over the live row.
     */
    public Double money() {
        return currency(moneyKey());
    }

    /** Any sidebar currency by name ("souls", "essence", "shards", …): live row first, else the last snapshot. */
    public Double currency(String name) {
        if (name == null || name.isBlank()) return null;
        String key = name.toLowerCase(Locale.ROOT);
        Double side = liveBals.get(key);
        if (side != null) return side;
        return snapshotBals.get(key);
    }

    /** Earning rate: exact summary-window rate when seen, else the balance-delta slope. */
    public Double incomePerMinute() {
        if (summaryRatePerMs > 0) return summaryRatePerMs * 60_000.0;
        return moneyPerMinute();
    }

    /** Effective TTK for the zone gate: DPS-predicted for the mob being cooked when available, else the kill median. */
    public Double effectiveTtkMs(Double predictedMs) {
        return Economy.effectiveTtkMs(predictedMs, medianTtkMs());
    }

    /** Last effective TTK the controller evaluated (HUD/status share this one number). */
    public volatile Double lastEffectiveTtkMs = null;

    /** HUD/log readiness: 1.0 while the zone gate is open, scaled down above zoneMaxTtkMs, 0 while unknown. */
    public double zoneReadiness() {
        return Economy.zoneReadiness(lastEffectiveTtkMs, cfg.zoneMaxTtkMs);
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

    public void noteUpgradeSend(String kind) {
        lastUpgradeKind = kind;
        long now = System.currentTimeMillis();
        lastUpgradeSendAt = now;
        lastSendBal = money();
        lastSendSucceeded = false;
        // Advancing a zone teleports the player. Rebirth teleport is armed on the diamond click,
        // not on the /rebirth seed (Esc after the gap line does not move you).
        if ("zone".equals(kind)) {
            expectTeleportUntil = now + Math.max(0, cfg.expectedTeleportAfterZoneMs);
            expectTeleportReason = "zone";
        }
    }

    /** Rebirth diamond click: the teleport that follows is a rebirth, not a zone advance. */
    public void armTeleport(int ms) {
        expectTeleportUntil = System.currentTimeMillis() + Math.max(0, ms);
        expectTeleportReason = "rebirth";
    }

    /** Reason of the teleport just consumed by the stop protocol; resets to "zone". */
    public String consumeTeleportReason() {
        String r = expectTeleportReason;
        expectTeleportReason = "zone";
        return r;
    }

    // --- persisted per-user state ---

    public void setStateStore(StateStore store) { this.stateStore = store; }

    /** Bot enabled as this username: load what the server already taught us for this account. */
    public void attachUser(String username) {
        stateUser = username;
        if (stateStore == null || username == null) return;
        StateStore.Entry e = stateStore.get(username);
        if (e == null) { log("state_loaded", "user", username, "found", false); return; }
        if (swordTarget == null) swordTarget = e.swordTarget;
        if (zoneTarget == null) zoneTarget = e.zoneTarget;
        if (rebirthTarget == null) rebirthTarget = e.rebirthTarget;
        if (swordLastPrice == null) swordLastPrice = e.swordLastPrice;
        if (zoneLastPrice == null) zoneLastPrice = e.zoneLastPrice;
        if (rebirthLastPrice == null) rebirthLastPrice = e.rebirthLastPrice;
        long now = System.currentTimeMillis();
        if (swordTarget != null && swordPriceSeenAt == 0) swordPriceSeenAt = now;
        if (zoneTarget != null && zonePriceSeenAt == 0) zonePriceSeenAt = now;
        if (rebirthTarget != null && rebirthPriceSeenAt == 0) rebirthPriceSeenAt = now;
        log("state_loaded", "user", username, "found", true, "savedAt", e.savedAt,
            "swordTarget", fmt(swordTarget), "zoneTarget", fmt(zoneTarget), "rebirthTarget", fmt(rebirthTarget),
            "swordFloor", fmt(swordLastPrice), "zoneFloor", fmt(zoneLastPrice), "rebirthFloor", fmt(rebirthLastPrice));
    }

    private static String fmt(Double v) { return v != null ? Amounts.format(v) : null; }

    private void markStateDirty() {
        if (stateDirtyAt == 0) stateDirtyAt = System.currentTimeMillis();
    }

    /** Debounced write-through of the learned prices (called from poll). */
    private void flushState(long now) {
        if (stateDirtyAt == 0 || now - stateDirtyAt < 2000) return;
        stateDirtyAt = 0;
        if (stateStore == null || stateUser == null) return;
        StateStore.Entry e = new StateStore.Entry();
        e.swordTarget = swordTarget;
        e.zoneTarget = zoneTarget;
        e.rebirthTarget = rebirthTarget;
        e.swordLastPrice = swordLastPrice;
        e.zoneLastPrice = zoneLastPrice;
        e.rebirthLastPrice = rebirthLastPrice;
        e.rebirths = rebirths;
        stateStore.put(stateUser, e);
        log("state_saved", "user", stateUser);
    }

    /** When the current price of a kind was learned (0 = unknown). */
    public long priceSeenAt(String kind) {
        if ("zone".equals(kind)) return zonePriceSeenAt;
        if ("rebirth".equals(kind)) return rebirthPriceSeenAt;
        return swordPriceSeenAt;
    }

    /** Rolled growth margin for the unknown-price retry of a kind. */
    public double retryGrowth(String kind) {
        if ("zone".equals(kind)) return zoneRetryGrowth;
        if ("rebirth".equals(kind)) return rebirthRetryGrowth;
        return swordRetryGrowth;
    }

    private double rollGrowth(double minPct, double maxPct) {
        double lo = Math.max(0, minPct);
        double hi = Math.max(lo, maxPct);
        return lo + java.util.concurrent.ThreadLocalRandom.current().nextDouble() * (hi - lo);
    }

    public boolean lastSendSucceeded = false;

    public boolean isTeleportExpected(long now) {
        return now < expectTeleportUntil;
    }

    public void clearTeleportExpected() {
        expectTeleportUntil = 0;
    }

    public boolean seeded(String kind) {
        if ("zone".equals(kind)) return zoneSeeded;
        if ("rebirth".equals(kind)) return rebirthSeeded;
        return swordSeeded;
    }

    public void noteSeeded(String kind) {
        if ("zone".equals(kind)) zoneSeeded = true;
        else if ("rebirth".equals(kind)) rebirthSeeded = true;
        else swordSeeded = true;
    }

    public boolean exploratorySent(String kind) {
        if ("zone".equals(kind)) return zoneExploratorySent;
        if ("rebirth".equals(kind)) return rebirthExploratorySent;
        return swordExploratorySent;
    }

    public void noteExploratorySent(String kind) {
        if ("zone".equals(kind)) zoneExploratorySent = true;
        else if ("rebirth".equals(kind)) rebirthExploratorySent = true;
        else swordExploratorySent = true;
    }

    public void unseed(String kind) {
        if ("zone".equals(kind)) { zoneSeeded = false; zoneExploratorySent = false; }
        else if ("rebirth".equals(kind)) { rebirthSeeded = false; rebirthExploratorySent = false; }
        else { swordSeeded = false; swordExploratorySent = false; }
    }

    /** Price of the most recent known tier (retry threshold while the price is unknown). */
    public Double lastPrice(String kind) {
        if ("zone".equals(kind)) return zoneLastPrice;
        if ("rebirth".equals(kind)) return rebirthLastPrice;
        return swordLastPrice;
    }

    /** Last remaining gap seen in a fail line (HUD/logging). */
    public Double lastGap(String kind) {
        if ("zone".equals(kind)) return zoneGap;
        if ("rebirth".equals(kind)) return rebirthGap;
        return swordGap;
    }

    /** No fail line within the response window after our send = the purchase succeeded. */
    public boolean failSince(String kind, long sendAt) {
        long at = "zone".equals(kind) ? lastZoneFailAt
            : "rebirth".equals(kind) ? lastRebirthFailAt
            : lastSwordFailAt;
        return sendAt > 0 && at >= sendAt;
    }

    public boolean sidebarSettled(long nowMs, int settleMs) {
        return Economy.sidebarSettled(nowMs, lastSpendAt, settleMs);
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
        if (rebirthTarget != null) ctx.addProperty("rebirthTarget", Amounts.format(rebirthTarget));
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
        flushState(nowMs);
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
            zoneFromSidebar = true;
            if (!z.equals(zone)) {
                zone = z;
                onZoneChange("sidebar-row");
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
        String prevRaw = liveRaw.put(key, raw);
        balances.put(key, raw);
        // Suffix evidence: the first "Qa" (or anything new) is logged next to the row it
        // followed, so a wrong scale in the table shows up as a 1000x jump in the log.
        String sfx = Amounts.suffixOf(raw).toUpperCase(Locale.ROOT);
        if (!sfx.isEmpty() && suffixesSeen.add(sfx)) {
            log("amount_suffix", "currency", key, "suffix", sfx, "raw", raw, "parsed", Amounts.format(value),
                "prevRaw", prevRaw, "prevParsed", prev != null ? Amounts.format(prev) : null,
                "scale", Amounts.scaleFor(sfx));
        }
        if (key.equals(moneyKey())) {
            long nowMs = System.currentTimeMillis();
            lastSidebarMoneyAt = nowMs;
            // Money collapsing to ~zero while we didn't just buy anything = a rebirth
            // wiped the balance (the sidebar rebirth counter is the primary signal;
            // this covers boards where that row isn't always rendered). A collapse
            // lands near zero: a value still above moneyCollapseMaxValue is a suffix
            // read 1000x too small, not a rebirth.
            if (prev != null && prev >= 1e9 && value <= Math.max(1e6, prev * 0.01)
                && value < cfg.moneyCollapseMaxValue && nowMs - lastSpendAt > 10_000) {
                rebirthReset("money-collapse");
            }
            // Server auto-rebirth: the balance reaching the known cost means the teleport
            // that follows is a rebirth, not a staff pull.
            if (cfg.serverAutoRebirth && rebirthTarget != null && value >= rebirthTarget) {
                armTeleport(cfg.expectedTeleportAfterRebirthMs);
            } else if (prev != null && prev >= 1e9 && value <= prev * 0.01 && value >= cfg.moneyCollapseMaxValue) {
                log("suffix_scale_suspect", "raw", raw, "prevRaw", prevRaw, "parsed", Amounts.format(value),
                    "prevParsed", Amounts.format(prev));
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
        // One rebirth arrives as up to three signals (chat line, money collapse, sidebar
        // counter) within seconds; the first one does the reset.
        long nowMs = System.currentTimeMillis();
        if (lastRebirthAt != 0 && nowMs - lastRebirthAt < 15_000) {
            log("economy_reset_dedup", "via", via, "sinceMs", nowMs - lastRebirthAt);
            return;
        }
        // The rebirth cost only grows, so the price we just paid (or last learned) is a
        // floor for the next one — kept so the controller retries the GUI at a sane point
        // instead of re-probing /rebirth seconds after rebirthing.
        Double rebirthFloor = rebirthTarget != null ? rebirthTarget : rebirthLastPrice;
        swordTarget = null;
        zoneTarget = null;
        rebirthTarget = null;
        swordGap = null;
        zoneGap = null;
        rebirthGap = null;
        swordLastPrice = null;
        zoneLastPrice = null;
        rebirthLastPrice = rebirthFloor;
        rebirthRetryGrowth = rollGrowth(0, cfg.rebirthRetryFloorGrowthMaxPct);
        swordPriceSeenAt = 0;
        zonePriceSeenAt = 0;
        rebirthPriceSeenAt = 0;
        lastRebirthAt = System.currentTimeMillis();
        markStateDirty();
        swordMaxed = false;
        zoneMaxed = false;
        swordSeeded = false;
        zoneSeeded = false;
        rebirthSeeded = false;
        swordExploratorySent = false;
        zoneExploratorySent = false;
        rebirthExploratorySent = false;
        swordBuysThisZone = 0;
        upgradeChatFrag = null;
        // Back to stage 1: every TTK sample belongs to the old progression.
        killDurations.clear();
        zoneBaselineTtkMs = null;
        zoneKills = 0;
        lastBenchmarkLogged = null;
        lastEffectiveTtkMs = null;
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
        Integer levelSeen = null;
        for (ClientBossBar bar : bars.values()) {
            String title = bar.getName().getString();
            // Identity only: no HP ("LVL4 Pig ❤8.48M") and no countdown ("Event: 12m 10s",
            // "(12m, 9s)"), else every tick starts and ends a "boost".
            String key = ChatClassifier.bossBarKey(title);
            if (!key.isEmpty()) current.add(key);
            Matcher lm = BOSS_LEVEL.matcher(bossBarPrefix(title));
            if (lm.find()) {
                try {
                    int lvl = Integer.parseInt(lm.group(1));
                    if (levelSeen == null || lvl > levelSeen) levelSeen = lvl;
                } catch (NumberFormatException ignored) {}
            }
        }
        noteBossLevel(levelSeen);
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

    /**
     * Mob level from the boss bar is the zone proxy (logs: LVL2 Rabbit → LVL5 Goat,
     * one step per stage, while the sidebar never showed a Zone row). Debounced
     * over two polls so a stale leftover from the previous stage can't flip it.
     */
    private void noteBossLevel(Integer lvl) {
        if (lvl == null) return;
        if (bossLevel == null) {
            bossLevel = lvl;
            if (!zoneFromSidebar) zone = "lvl" + lvl;
            return;
        }
        if (lvl.equals(bossLevel)) {
            pendingBossLevel = null;
            pendingBossLevelPolls = 0;
            return;
        }
        if (!lvl.equals(pendingBossLevel)) {
            pendingBossLevel = lvl;
            pendingBossLevelPolls = 0;
        }
        if (++pendingBossLevelPolls < 2) return;
        int prev = bossLevel;
        bossLevel = lvl;
        pendingBossLevel = null;
        pendingBossLevelPolls = 0;
        if (!zoneFromSidebar) zone = "lvl" + lvl;
        log("boss_level", "level", lvl, "prev", prev);
        onZoneChange("bossbar-level");
    }

    /** Our own zone advance confirmed by the teleport — same reset as any other zone signal. */
    public void onZoneAdvance(String via) {
        onZoneChange(via);
    }

    /**
     * Bot enabled: assume nothing about where we are. After /spawn, a manual zone
     * change or an AFK gap the old kill samples describe a different stage (or a
     * different sword), so the TTK window restarts and the zone gate waits for
     * fresh evidence. Prices are kept — they are server state, not position state.
     */
    public void onEnable() {
        resetTtkWindow("enable");
    }

    private void resetTtkWindow(String via) {
        zoneBaselineTtkMs = null;
        zoneKills = 0;
        lastBenchmarkLogged = null;
        killDurations.clear();
        lastEffectiveTtkMs = null;
        log("ttk_reset", "via", via);
    }

    /**
     * A zone change invalidates every TTK sample: the median window is cleared so
     * the zone gate cannot be fooled by the previous stage's fast kills, and the
     * benchmark baseline restarts. Duplicate signals for the same advance
     * (teleport + boss level) within 10s collapse into one.
     */
    private void onZoneChange(String via) {
        long now = System.currentTimeMillis();
        if (lastZoneChangeAt != 0 && now - lastZoneChangeAt < 10_000) {
            log("zone_change", "via", via, "zone", zone, "dedup", true);
            return;
        }
        lastZoneChangeAt = now;
        zoneChangeSeq++;
        swordBuysThisZone = 0;
        resetTtkWindow(via);
        log("zone_change", "via", via, "zone", zone);
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
            Double hp = ChatClassifier.bossBarHp(title);
            // if multiple match, take the lowest (most-damaged = the one cooking)
            if (hp != null && (best == null || hp < best)) best = hp;
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
        List<String> lines = new ArrayList<>();
        for (String line : rawAll.split("\\R")) {
            String clean = ChatClassifier.clean(line);
            lines.add(clean);
            onChatLine(clean, overlay);
        }
        if (!overlay) onGiveawayPacket(lines);
    }

    /**
     * A giveaway announcement arrives as one multi-line packet ("NEW GIVEAWAY (30s to
     * enter)" / prize / "Click to Enter!"); the controller types /giveaway on the seq.
     */
    private void onGiveawayPacket(List<String> lines) {
        if (giveawayAnnounceRes.isEmpty()) return;
        boolean announce = false;
        for (String l : lines) {
            if (l.isEmpty() || ChatClassifier.isPlayerOrBroadcast(l)) continue;
            for (Pattern p : giveawayAnnounceRes) if (p.matcher(l).find()) { announce = true; break; }
            if (announce) break;
        }
        if (!announce) return;
        long now = System.currentTimeMillis();
        if (now - giveawaySeenAt < 20_000) return; // countdown repeats never re-fire
        giveawaySeenAt = now;
        giveawayPrize = ChatClassifier.giveawayPrize(lines, giveawayAnnounceRes);
        giveawaySeq++;
        log("giveaway_seen", "seq", giveawaySeq, "prize", giveawayPrize, "raw", lines);
    }

    private void onChatLine(String text, boolean overlay) {
        if (text.isEmpty()) return;
        long now = System.currentTimeMillis();
        boolean ours = text.startsWith("[YCBotChallenge]");
        // Action-bar text is never the captcha prompt (and repeats every tick).
        if (!overlay && !ours) {
            boolean eligible = ChatClassifier.captchaLineEligible(text, overlay);
            for (Pattern p : captchaRes) {
                if (p.matcher(text).find()) {
                    if (eligible) captchaMessage = text;
                    else log("captcha_chat_ignored", "raw", text, "why", "player-or-broadcast");
                    break;
                }
            }
            if (!ChatClassifier.isPlayerOrBroadcast(text)) {
                for (Pattern p : captchaHintRes) {
                    if (p.matcher(text).find()) {
                        captchaHintAt = now;
                        log("captcha_hint", "raw", text);
                        break;
                    }
                }
            }
        }
        boolean known = false;
        // Income summary lines are exact earnings — anchored, prefix-free, parse anytime.
        // They feed only the income RATE; the balance itself is always the sidebar row.
        Integer windowS = ChatClassifier.summaryWindowSeconds(text, summaryHeaderRe);
        if (windowS != null) {
            if (windowS > 0) summaryWindowMs = windowS * 1000L;
            known = true;
        }
        Double earned = ChatClassifier.summaryMoney(text, summaryMoneyRe);
        if (earned != null) {
            double sample = earned / Math.max(1, summaryWindowMs);
            summaryRatePerMs = summaryRatePerMs <= 0 ? sample : 0.5 * summaryRatePerMs + 0.5 * sample;
            Double bal = money();
            log("income_summary", "earned", Amounts.format(earned),
                "windowS", summaryWindowMs / 1000,
                "balance", bal != null ? Amounts.format(bal) : null);
            known = true;
        }
        // Upgrade responses: strict gate — only within the window after our own
        // send, never on player/broadcast lines, anchored patterns only.
        if (parseUpgradeResponse(text, now)) known = true;
        // Evidence net: raw-log unrecognized lines after our own sends (6s) so the
        // server's actual wording is always captured for pattern tuning.
        boolean nearSend = lastUpgradeSendAt != 0 && now - lastUpgradeSendAt <= 6_000;
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
        // The server's own rebirth line ("[!] You have successfully rebirthed." carries a
        // [!] prefix, so only the » player separator is excluded). It arms the teleport
        // exemption and resets the economy (deduped against the money collapse / counter).
        if (text.indexOf('\u00BB') < 0) {
            for (Pattern p : rebirthChatRes) {
                if (p.matcher(text).find()) {
                    log("rebirth_chat", "raw", text);
                    armTeleport(cfg.expectedTeleportAfterRebirthMs);
                    rebirthReset("chat");
                    known = true;
                    break;
                }
            }
        }
        // Giveaway outcomes (server lines only).
        if (!ChatClassifier.isPlayerOrBroadcast(text)) {
            for (Pattern p : giveawayJoinedRes) {
                if (p.matcher(text).find()) {
                    giveawaysJoined++;
                    log("giveaway_joined", "raw", text, "joined", giveawaysJoined);
                    known = true;
                    break;
                }
            }
            for (Pattern p : giveawayWonRes) {
                if (p.matcher(text).find()) {
                    boolean us = stateUser != null && text.toLowerCase(Locale.ROOT).contains(stateUser.toLowerCase(Locale.ROOT));
                    if (us) { giveawaysWon++; giveawayWonSeq++; }
                    log("giveaway_result", "raw", text, "us", us, "won", giveawaysWon);
                    known = true;
                    break;
                }
            }
        }
        // "All mobs have been respawned in your zone." — a zone advance happened.
        if (text.toLowerCase(Locale.ROOT).contains("mobs have been respawned in your zone")) {
            onZoneChange("respawn-broadcast");
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
        } else if (!known && !ours && !ChatClassifier.isPlayerOrBroadcast(text) && rawNet.admit(text, now)) {
            // Evidence net: server lines nothing classified (a captcha prompt, a warning,
            // a new reward line) are kept, rate-limited, so the next unknown has a fixture.
            log("chat_raw", "raw", text);
        }
    }

    /**
     * Strictly-gated upgrade responses. Fail lines carry the REMAINING GAP
     * ("You need 781.04B Money..."), which teaches the absolute price (sidebar
     * bal + gap). Success lines are parsed when the server prints them (sword:
     * exact amount per level; zone: "purchased new stage(s)"); otherwise no fail
     * line within the window after our send means the purchase went through (see
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

        if (anyMatch(upgradeSuccessRes, text)) {
            String kind = ChatClassifier.kindOf(text, lastUpgradeKind);
            if (kind == null) return false;
            upgradeChatFrag = null;
            // "You have unlocked a new sword level for 1.24B!" carries the exact price;
            // "You have purchased new stage(s)!" does not (and may cover several stages).
            onUpgradeSuccess(kind, now, "chat", ChatClassifier.successAmount(text, upgradeSuccessRes));
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
        // The gap is measured against the server's balance right now; sidebar bal + gap
        // is the absolute price and self-corrects on every fail, so always re-derive it.
        Double price = Economy.priceFromFail(gap, money());
        // Never leave a kind latched on an unknown balance: with no price the gap
        // itself becomes the retry floor (the price is at least the gap).
        if ("zone".equals(kind)) {
            zoneGap = gap;
            zoneExploratorySent = false;
            if (price != null) { if (zoneTarget == null) zonePriceSeenAt = now; zoneTarget = price; }
            else zoneLastPrice = zoneLastPrice == null ? gap : Math.max(zoneLastPrice, gap);
            lastZoneFailAt = now;
        } else if ("rebirth".equals(kind)) {
            rebirthGap = gap;
            rebirthExploratorySent = false;
            if (price != null) { if (rebirthTarget == null) rebirthPriceSeenAt = now; rebirthTarget = price; }
            else rebirthLastPrice = rebirthLastPrice == null ? gap : Math.max(rebirthLastPrice, gap);
            lastRebirthFailAt = now;
        } else {
            swordGap = gap;
            swordExploratorySent = false;
            if (price != null) { if (swordTarget == null) swordPriceSeenAt = now; swordTarget = price; }
            else swordLastPrice = swordLastPrice == null ? gap : Math.max(swordLastPrice, gap);
            lastSwordFailAt = now;
        }
        markStateDirty();
        log("upgrade_chat", "kind", kind,
            "gap", Amounts.format(gap),
            "target", price != null ? Amounts.format(price) : null,
            "balance", money() != null ? Amounts.format(money()) : null,
            "raw", raw);
        log("upgrade_result", "kind", kind, "success", false, "fail", true, "message", raw);
    }

    /**
     * Success bookkeeping, from the success line when the server prints one
     * (sword: exact amount per level; zone: "purchased new stage(s)") or from
     * silence after the response window. The sidebar row is the balance, so
     * there is no debit — only the settle delay before the next eval. The retry
     * floor for the now-unknown next tier is the amount paid (the highest line of
     * a multi-level /swordmax), else the known price, else the balance at send.
     */
    public void onUpgradeSuccess(String kind, long now) {
        onUpgradeSuccess(kind, now, "silence", null);
    }

    public void onUpgradeSuccess(String kind, long now, String via, Double paid) {
        if ("chat".equals(via)) lastSuccessAt = now;
        boolean zone = "zone".equals(kind);
        if (lastSendSucceeded) {
            // Further levels from the same /swordmax: the retry floor is the last price paid.
            if (paid != null && !"rebirth".equals(kind)) {
                if (zone) zoneLastPrice = zoneLastPrice == null ? paid : Math.max(zoneLastPrice, paid);
                else swordLastPrice = swordLastPrice == null ? paid : Math.max(swordLastPrice, paid);
                lastSpendAt = now;
                log("upgrade_result", "kind", kind, "success", true, "fail", false,
                    "paid", Amounts.format(paid), "via", via, "extraLevel", true);
            }
            return;
        }
        lastSendSucceeded = true;
        lastSpendAt = now;
        if ("rebirth".equals(kind)) {
            Double cost = rebirthTarget != null ? rebirthTarget : lastSendBal;
            log("upgrade_result", "kind", kind, "success", true, "fail", false,
                "paid", cost != null ? Amounts.format(cost) : null, "via", via);
            rebirthReset("upgrade-success");
            return;
        }
        Double price = zone ? zoneTarget : swordTarget;
        Double retryFloor = paid != null ? paid : (price != null ? price : lastSendBal);
        double growth = rollGrowth(cfg.retryPriceGrowthMinPct, cfg.retryPriceGrowthMaxPct);
        if (zone) {
            zoneLastPrice = retryFloor != null ? retryFloor : zoneLastPrice;
            zoneRetryGrowth = growth;
            zoneTarget = null;
            zoneGap = null;
            zonePriceSeenAt = 0;
            zoneExploratorySent = false;
        } else {
            swordLastPrice = retryFloor != null ? retryFloor : swordLastPrice;
            swordRetryGrowth = growth;
            swordTarget = null;
            swordGap = null;
            swordPriceSeenAt = 0;
            swordExploratorySent = false;
            swordBuysThisZone++;
        }
        markStateDirty();
        Double floor = zone ? zoneLastPrice : swordLastPrice;
        log("upgrade_result", "kind", kind, "success", true, "fail", false,
            "paid", paid != null ? Amounts.format(paid) : (price != null ? Amounts.format(price) : null),
            "via", via,
            "retryAt", floor != null ? Amounts.format(floor * (1.0 + growth)) : null);
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

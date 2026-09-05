package dev.drew.ycbotchallenge;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Standalone checks for the chat-driven upgrade economy. Every fixture is a real
 * line captured from live EnchantedMC session logs (events-baseline-2026-09-02):
 * must-parse formats (fail gaps, success lines, reward summary) and must-NOT-classify
 * server noise (enchant procs, welcomes, player shops, soul-enchant purchases), plus
 * the pure decision rules (zone TTK gate, cooldown relaxation, retry floors).
 */
public final class EconomyChecks {
    private EconomyChecks() {}

    // --- Real captured lines (verbatim from the JSONL logs) ---
    private static final String SWORD_FAIL =
        "You don't have enough money to purchase any sword upgrades! "
            + "You need 781.04B Money to purchase the next sword upgrade.";
    private static final String ZONE_FAIL =
        "You do not have enough money to purchase the next stage. You need 1.25Q Money.";
    private static final String SUMMARY_HEADER = "Reward Summary: (60s)";
    private static final String SUMMARY_MONEY = " + 17.19B Money";
    private static final String SUMMARY_SOULS = " + 2.69M Souls";
    // Server noise that the old loose patterns misclassified:
    private static final String ENCHANT_PROC =
        "EnchantedMC » You gained 408.75 Sword Experience from ᴇɴʜᴀɴᴄᴇʀ ᴇɴᴄʜᴀɴᴛ!";
    private static final String WELCOME = "[ENCHANTEDMC] Welcome pimpek5 to the Dungeons Realm!";
    private static final String SOUL_PURCHASE =
        "§f\ue0cd§r You have successflly purchased 50x Soul Magnet Enchant for 277.88M Souls.";
    private static final String PLAYER_SHOP =
        "[✧R178✧] [⚒iʙᴀɴʜᴀᴍᴍᴇʀi⚒] Cammyprof » selling perks 1k=100c, currency 2Q/200T/10m=100c";
    private static final String REBIRTH_NEED = "You need $29.99T Money to Rebirth.";
    private static final String REBIRTH_NEED_ICON = "\ue04e You need $29.99T Money to Rebirth.";
    private static final String SWORD_UNLOCK = "You have unlocked a new sword level for 6.43M!";
    private static final String SWORD_UNLOCK_REAL = "You have unlocked a new sword level for 1.24B!";
    private static final String ZONE_UNLOCK = "You have purchased new stage(s)!";

    private static final YCBotChallengeConfig CFG = new YCBotChallengeConfig();

    public static void main(String[] args) {
        int n = 0;
        n += amounts();
        n += sidebar();
        n += failLines();
        n += successLines();
        n += summaryLines();
        n += noiseRejection();
        n += zoneGate();
        n += choose();
        n += gates();
        n += rebirth();
        n += realism();
        n += stateStore();
        n += captcha();
        n += ignoredMobs();
        n += rebirthHorizon();
        n += rebirthUpgrades();
        n += instantKills();
        n += patience();
        n += rebirthProbe();
        n += suffixLearning();
        n += typer();
        n += ballot();
        n += zoneLevel();
        n += companions();
        n += transcend();
        n += firstKills();
        n += audit0930();
        n += companions0931();
        n += priceLadders();
        n += audit0933Decision();
        n += logging0933();
        n += hudPlan0933();
        n += companions0933();
        n += gui0933();
        n += swordSkins0933();
        n += captchaHedge0934();
        n += companions0935();
        n += companions0936();
        n += companions0937();
        n += gg0937();
        n += climb0937();
        n += targeting0937();
        n += progress0937();
        if (n > 0) {
            System.err.println(n + " failed");
            System.exit(1);
        }
        System.out.println("EconomyChecks ok");
    }

    private static Pattern loose(String spec) {
        if (spec.startsWith("/") && spec.endsWith("/") && spec.length() > 2) {
            return Pattern.compile(spec.substring(1, spec.length() - 1), Pattern.CASE_INSENSITIVE);
        }
        return Pattern.compile(Pattern.quote(spec), Pattern.CASE_INSENSITIVE);
    }

    private static List<Pattern> looseAll(List<String> specs) {
        return specs.stream().map(EconomyChecks::loose).toList();
    }

    private static int amounts() {
        int n = 0;
        n += eq("131.56B", Amounts.parse("131.56B"), 131.56e9, 1e3);
        n += eq("781.04B", Amounts.parse("781.04B"), 781.04e9, 1e3);
        n += eq("1.25Q", Amounts.parse("1.25Q"), 1.25e15, 1e9);
        n += eq("6.78T", Amounts.parse("6.78T"), 6.78e12, 1e6);
        n += eq("$29.99T", Amounts.parse("$29.99T"), 29.99e12, 1e6);
        n += eq("17.19B", Amounts.parse("17.19B"), 17.19e9, 1e3);
        n += eq("parens (1.09T)", Amounts.parse("(1.09T)"), 1.09e12, 1e6);
        n += eq("235 SHARDS not suffix", Amounts.parse("235 SHARDS"), 235.0, 1e-6);
        n += eq("format B", Amounts.format(131.56e9), "131.56B");
        // Server suffix order K M B T Q QQ: the server's quintillion is "QQ" (2026-09-03 18:43,
        // rebirth GUI: "You need $20.xQQ Money to Rebirth." after a 2.66Q balance). 0.9.25:
        // nothing above QQ is built in — Qa/Qi/Sx are unknown until the sidebar teaches them.
        n += eq("2Q", Amounts.parse("2Q"), 2e15, 1e6);
        n += eq("20.5QQ", Amounts.parse("20.5QQ"), 20.5e18, 1e9);
        n += eq("$20.5QQ", Amounts.parse("$20.5QQ"), 20.5e18, 1e9);
        n += eq("Qa not built in", Amounts.knownSuffix("Qa"), false);
        n += eq("Qi not built in", Amounts.knownSuffix("Qi"), false);
        n += eq("Sx not built in", Amounts.knownSuffix("Sx"), false);
        n += eq("format Q", Amounts.format(1.25e15), "1.25Q");
        n += eq("format QQ", Amounts.format(1.5e18), "1.5QQ");
        n += eq("format above top rung", Amounts.format(2.5e21), "2500QQ");
        n += eq("mantissa of 903.74T", Amounts.mantissaOf("903.74T"), 903.74, 1e-9);
        n += eq("mantissa of $20.5QQ", Amounts.mantissaOf("$20.5QQ"), 20.5, 1e-9);
        n += eq("mantissa of none", Amounts.mantissaOf("Zone Boss") == null, true);
        n += eq("suffix of 1.25Qa", Amounts.suffixOf("1.25Qa"), "Qa");
        n += eq("suffix of 20.5QQ", Amounts.suffixOf("20.5QQ"), "QQ");
        n += eq("QQ known", Amounts.knownSuffix("QQ"), true);
        n += eq("QQQ unknown", Amounts.knownSuffix("QQQ"), false);
        n += eq("bare number known", Amounts.knownSuffix(""), true);
        n += eq("suffix of $29.99T", Amounts.suffixOf("$29.99T"), "T");
        n += eq("suffix of 58", Amounts.suffixOf("58"), "");

        // Boss-bar HP (verbatim titles from the 20:52 / 22:02 logs). Higher stages use
        // suffixes; the old digits-only parser read "82.04M" as 82 and broke the DPS slope.
        n += eq("boss hp plain", ChatClassifier.bossBarHp("[EPIC] LVL1 Chicken ❤346"), 346.0, 1e-9);
        n += eq("boss hp M suffix", ChatClassifier.bossBarHp("LVL5 Goat ❤82.04M"), 82.04e6, 1);
        n += eq("boss hp rare pig", ChatClassifier.bossBarHp("[EPIC] LVL4 Pig ❤8.48M"), 8.48e6, 1);
        n += eq("boss hp K suffix", ChatClassifier.bossBarHp("[EPIC] LVL4 Pig ❤320.48K"), 320.48e3, 1);
        n += eq("boss hp none", ChatClassifier.bossBarHp("Soul Harvest 2x Souls (12m, 9s)") == null, true);
        return n;
    }

    private static int sidebar() {
        int n = 0;
        List<String> currencies = List.of("money", "souls", "essence", "shards", "credits");
        List<String> lines = List.of(
            "§c| §a131.56B §2MONEY",
            "| 204.88M SOULS",
            "| 235 SHARDS",
            "§x§f§f§a§a§0§0| 75.1B MONEY"
        );
        var hits = SidebarParser.parseCurrencies(lines, currencies);
        n += eq("money parsed", hits.get("money").value(), 131.56e9, 1e3);
        n += eq("souls parsed", hits.get("souls").value(), 204.88e6, 1);
        n += eq("shards parsed", hits.get("shards").value(), 235.0, 1e-6);

        // Real server sidebar rows: colon-less "Zone 1" must parse; old pattern required "Zone:".
        Pattern zoneRe = Pattern.compile(CFG.zonePattern, Pattern.CASE_INSENSITIVE);
        var m1 = zoneRe.matcher(SidebarParser.strip("Zone 1"));
        boolean found1 = m1.find();
        n += eq("zone row matches", found1, true);
        n += eq("zone row value", found1 ? m1.group(1) : null, "1");
        var m2 = zoneRe.matcher(SidebarParser.strip("Zone: 3"));
        n += eq("legacy colon zone", m2.find() ? m2.group(1) : null, "3");

        // Sidebar money rows: value-first, label-first, and the real "Your Balance" row.
        Pattern moneyRe = Pattern.compile(
            CFG.sidebarMoneyPattern.substring(1, CFG.sidebarMoneyPattern.length() - 1),
            Pattern.CASE_INSENSITIVE);
        n += eq("sidebar your-balance row", firstGroup(moneyRe, "Your Balance 2.35T"), 2.35e12, 1e6);
        n += eq("sidebar your-balance parens", firstGroup(moneyRe, "Your Balance: (1.09T)"), 1.09e12, 1e6);
        n += eq("sidebar value-first money", firstGroup(moneyRe, "75.1B MONEY"), 75.1e9, 1e3);
        n += eq("sidebar label-first money", firstGroup(moneyRe, "MONEY: 75.1B"), 75.1e9, 1e3);
        n += eq("zone row is not money", firstGroup(moneyRe, "Zone 1") == null, true);
        n += eq("time row is not money", firstGroup(moneyRe, "Time Left 05:00") == null, true);

        // The REAL EnchantedMC sidebar (0.9.1 debugSidebar capture): small-caps
        // labels and a box-drawing bullet — "│ 5.62T ᴍᴏɴᴇʏ".
        n += eq("small-caps strip", SidebarParser.strip("│ 5.62T ᴍᴏɴᴇʏ"), "5.62T money");
        n += eq("small-caps money row", firstGroup(moneyRe, "│ 5.62T ᴍᴏɴᴇʏ"), 5.62e12, 1e9);
        n += eq("whole-trillion amount", firstGroup(moneyRe, "│ 6T ᴍᴏɴᴇʏ"), 6e12, 1e6);
        var realHits = SidebarParser.parseCurrencies(List.of(
                "│ 5.62T ᴍᴏɴᴇʏ",
                "│ 485.27M ꜱᴏᴜʟꜱ",
                "│ 31.23M ᴇꜱꜱᴇɴᴄᴇ",
                "│ 91 ꜱʜᴀʀᴅꜱ",
                "│ ꜱᴡɪɴɢꜱ: 63.31K"
        ), CFG.sidebarCurrencies);
        n += eq("real money parsed", realHits.get("money") != null ? realHits.get("money").value() : null, 5.62e12, 1e9);
        n += eq("real souls parsed", realHits.get("souls") != null ? realHits.get("souls").value() : null, 485.27e6, 1);
        n += eq("real essence parsed", realHits.get("essence") != null ? realHits.get("essence").value() : null, 31.23e6, 1);
        n += eq("real shards parsed", realHits.get("shards") != null ? realHits.get("shards").value() : null, 91.0, 1e-6);
        n += eq("real swings parsed", realHits.get("swings") != null ? realHits.get("swings").value() : null, 63.31e3, 1);

        // Rebirth counter row (real capture: "│ ʀᴇʙɪʀᴛʜ: 1" — singular, small caps).
        Pattern rebirthRe = Pattern.compile(CFG.rebirthsPattern, Pattern.CASE_INSENSITIVE);
        n += eq("rebirth row value", firstGroupStr(rebirthRe, "│ ʀᴇʙɪʀᴛʜ: 1"), "1");
        n += eq("rebirth zero", firstGroupStr(rebirthRe, "│ ʀᴇʙɪʀᴛʜ: 0"), "0");
        n += eq("legacy plural rebirths", firstGroupStr(rebirthRe, "Rebirths: 5"), "5");
        n += eq("swing-rate row is not rebirth",
            rebirthRe.matcher(SidebarParser.strip("│ ꜱᴡɪɴɢ ʀᴀᴛᴇ: 4/s")).find(), false);

        // NPC plate normalization for the radar whitelist.
        n += eq("small-caps plate", SidebarParser.strip("ᴢᴏɴᴇ ᴠɪꜱɪʙɪʟɪᴛʏ"), "zone visibility");
        n += eq("whitelist match after normalization",
            "zone visibility".equalsIgnoreCase(SidebarParser.strip("ᴢᴏɴᴇ ᴠɪꜱɪʙɪʟɪᴛʏ")), true);
        return n;
    }

    /** First regex group as a string (matcher.find once). */
    private static String firstGroupStr(Pattern re, String line) {
        var m = re.matcher(SidebarParser.strip(line));
        return m.find() ? m.group(1) : null;
    }

    /** Mirror of the group-scan in StatsTracker.pollSidebar: first non-null group, parsed. */
    private static Double firstGroup(Pattern re, String line) {
        var m = re.matcher(SidebarParser.strip(line));
        if (!m.find()) return null;
        for (int g = 1; g <= m.groupCount(); g++) {
            if (m.group(g) != null) return Amounts.parse(m.group(g));
        }
        return null;
    }

    private static int failLines() {
        int n = 0;
        Pattern need = loose(CFG.upgradeNeedAmountPattern);
        List<Pattern> failRes = looseAll(CFG.upgradeFailPatterns);

        n += eq("sword fail shape", failRes.stream().anyMatch(p -> p.matcher(SWORD_FAIL).find()), true);
        n += eq("sword fail gap", ChatClassifier.needAmount(SWORD_FAIL, need), 781.04e9, 1e3);
        n += eq("sword fail kind", ChatClassifier.kindOf(SWORD_FAIL, null), "sword");

        n += eq("zone fail shape", failRes.stream().anyMatch(p -> p.matcher(ZONE_FAIL).find()), true);
        n += eq("zone fail gap", ChatClassifier.needAmount(ZONE_FAIL, need), 1.25e15, 1e9);
        n += eq("zone fail kind via 'stage'", ChatClassifier.kindOf(ZONE_FAIL, null), "zone");

        n += eq("rebirth fail shape", failRes.stream().anyMatch(p -> p.matcher(REBIRTH_NEED).find()), true);
        n += eq("rebirth fail gap", ChatClassifier.needAmount(REBIRTH_NEED, need), 29.99e12, 1e6);
        n += eq("rebirth fail with icon", ChatClassifier.needAmount(REBIRTH_NEED_ICON, need), 29.99e12, 1e6);
        n += eq("rebirth kind", ChatClassifier.kindOf(REBIRTH_NEED, null), "rebirth");
        n += eq("rebirth target = bal + gap",
            Economy.priceFromFail(29.99e12, 8.04e9), 8.04e9 + 29.99e12, 1e6);

        n += eq("sword unlock kind", ChatClassifier.kindOf(SWORD_UNLOCK, null), "sword");
        n += eq("sword unlock is not a fail",
            failRes.stream().anyMatch(p -> p.matcher(SWORD_UNLOCK).find()), false);
        n += enchantLore();

        // Gap semantics: price = balance at fail + gap (the amount shrinks as you earn).
        n += eq("price = bal + gap", Economy.priceFromFail(781.04e9, 1.09e12), 1.87104e12, 1e6);
        n += eq("no bal → no price", Economy.priceFromFail(781.04e9, null) == null, true);

        // Gap dynamics from the real log: 781.04B → 732.08B → 683.12B while earning
        // implies a constant price when the balance grows by the gap delta.
        double b1 = 1.09e12;
        double p1 = Economy.priceFromFail(781.04e9, b1);
        double b2 = b1 + (781.04e9 - 732.08e9);
        double p2 = Economy.priceFromFail(732.08e9, b2);
        n += eq("shrinking gap ⇒ constant price", p2, p1, 1e3);
        return n;
    }

    private static int successLines() {
        int n = 0;
        List<Pattern> okRes = looseAll(CFG.upgradeSuccessPatterns);
        List<Pattern> failRes = looseAll(CFG.upgradeFailPatterns);
        // Sword: one line per level bought, exact price → retry floor for the next tier.
        n += eq("sword unlock matches", okRes.stream().anyMatch(p -> p.matcher(SWORD_UNLOCK).find()), true);
        n += eq("sword unlock amount", ChatClassifier.successAmount(SWORD_UNLOCK, okRes), 6.43e6, 1);
        n += eq("sword unlock 1.24B amount", ChatClassifier.successAmount(SWORD_UNLOCK_REAL, okRes), 1.24e9, 1e3);
        // Zone: "purchased new stage(s)" — no amount, may cover several stages.
        n += eq("zone unlock matches", okRes.stream().anyMatch(p -> p.matcher(ZONE_UNLOCK).find()), true);
        n += eq("zone unlock kind via 'stage'", ChatClassifier.kindOf(ZONE_UNLOCK, null), "zone");
        n += eq("zone unlock has no amount", ChatClassifier.successAmount(ZONE_UNLOCK, okRes) == null, true);
        n += eq("zone unlock is not a fail", failRes.stream().anyMatch(p -> p.matcher(ZONE_UNLOCK).find()), false);
        // Nothing else reads as a success.
        n += eq("fail line is not a success", okRes.stream().anyMatch(p -> p.matcher(SWORD_FAIL).find()), false);
        n += eq("summary money is not a success", ChatClassifier.successAmount(SUMMARY_MONEY, okRes) == null, true);
        n += eq("soul purchase is not a success",
            okRes.stream().anyMatch(p -> p.matcher(ChatClassifier.clean(SOUL_PURCHASE)).find()), false);
        return n;
    }

    private static int summaryLines() {
        int n = 0;
        Pattern header = loose(CFG.summaryHeaderPattern);
        Pattern money = loose(CFG.summaryMoneyPattern);
        n += eq("summary window", ChatClassifier.summaryWindowSeconds(SUMMARY_HEADER, header), 60);
        n += eq("summary money", ChatClassifier.summaryMoney(SUMMARY_MONEY, money), 17.19e9, 1e3);
        n += eq("summary souls not money", ChatClassifier.summaryMoney(SUMMARY_SOULS, money) == null, true);
        n += eq("fail line is NOT summary money", ChatClassifier.summaryMoney(SWORD_FAIL, money) == null, true);
        return n;
    }

    private static int noiseRejection() {
        int n = 0;
        Pattern need = loose(CFG.upgradeNeedAmountPattern);
        List<Pattern> failRes = looseAll(CFG.upgradeFailPatterns);

        // Broadcast/player lines are refused before pattern matching.
        n += eq("enchant proc is broadcast", ChatClassifier.isPlayerOrBroadcast(ChatClassifier.clean(ENCHANT_PROC)), true);
        n += eq("welcome is broadcast", ChatClassifier.isPlayerOrBroadcast(WELCOME), true);
        n += eq("player shop is broadcast", ChatClassifier.isPlayerOrBroadcast(PLAYER_SHOP), true);
        n += eq("sword fail is not broadcast", ChatClassifier.isPlayerOrBroadcast(SWORD_FAIL), false);
        n += eq("sword unlock is not broadcast", ChatClassifier.isPlayerOrBroadcast(SWORD_UNLOCK), false);
        n += eq("zone unlock is not broadcast", ChatClassifier.isPlayerOrBroadcast(ZONE_UNLOCK), false);

        // Even without the broadcast guard, none of these match the strict patterns.
        for (String noise : new String[] {
            ChatClassifier.clean(ENCHANT_PROC), WELCOME, ChatClassifier.clean(SOUL_PURCHASE), PLAYER_SHOP,
            "EnchantedMC » All mobs have been respawned in your zone.",
            "You have recieved 1 Rusty Key keys.",
            "You have landed on Green winning: 3x Enchanted Keys"
        }) {
            boolean failMatch = failRes.stream().anyMatch(p -> p.matcher(noise).find());
            n += eq("noise not a fail: " + noise.substring(0, Math.min(30, noise.length())), failMatch, false);
            n += eq("noise has no need-amount", ChatClassifier.needAmount(noise, need) == null, true);
        }
        return n;
    }

    private static int zoneGate() {
        int n = 0;
        // Effective TTK (0.9.23): the kill median once it exists, the DPS prediction before, else unknown.
        n += eq("median wins once kills landed", Economy.effectiveTtkMs(11_502.0, 769.0), 769.0, 1e-9);
        n += eq("prediction fills a fresh stage", Economy.effectiveTtkMs(4_000.0, null), 4_000.0, 1e-9);
        n += eq("unknown ttk", Economy.effectiveTtkMs(null, null) == null, true);
        n += eq("ttk source median", Economy.ttkSource(11_502.0, 769.0), "median");
        n += eq("ttk source predicted", Economy.ttkSource(4_000.0, null), "predicted");
        n += eq("ttk source none", Economy.ttkSource(null, null) == null, true);

        // The 0.9.5 spiral (events-baseline 20:52): Rabbit 0.25s → Sheep 7.2s → Pig 75s → Goat 90s.
        // With the 10s gate the third zone buy never happens.
        int gate = CFG.zoneMaxTtkMs;
        n += eq("rabbit 0.25s open", Economy.zoneAllowed(249.0, gate), true);
        n += eq("sheep 7.2s open", Economy.zoneAllowed(7_202.0, gate), true);
        n += eq("pig 75s closed", Economy.zoneAllowed(74_963.0, gate), false);
        n += eq("goat 90s closed", Economy.zoneAllowed(89_823.0, gate), false);
        n += eq("unknown TTK closed", Economy.zoneAllowed(null, gate), false);
        n += eq("gate disabled", Economy.zoneAllowed(null, 0), true);

        // Closed gate ⇒ zone is never chosen, even as the only affordable kind (zoneOpen=false).
        n += eq("closed gate: zone-only affordable → wait",
            Economy.chooseBuyKind(true, false, false, true, null, 2.5e9, 3e9, 60_000.0, 25, 2000), null);
        n += eq("closed gate: sword affordable → sword",
            Economy.chooseBuyKind(true, false, true, true, 1.24e9, 2.5e9, 3e9, 60_000.0, 25, 2000), "sword");
        n += eq("closed gate: HUD prefers sword",
            Economy.preferredKind(true, false), "sword");

        // Readiness for HUD/status.
        n += eq("ready at gate", Economy.zoneReadiness(10_000.0, 10_000), 1.0, 1e-9);
        n += eq("half at 2x", Economy.zoneReadiness(20_000.0, 10_000), 0.5, 1e-9);
        n += eq("unknown 0", Economy.zoneReadiness(null, 10_000), 0.0, 1e-9);

        // Cooldown relaxation (events-baseline 20:52 — zone skipped for "cooldown" from 8M to 220M):
        // the 60s cap collapses to the command cooldown once bal ≥ 3× the last known price.
        n += eq("cap holds", Economy.effectiveCooldownMs(60_000, 1100, 24.55e6, 30e6, 3.0), 60_000);
        n += eq("cap relaxed", Economy.effectiveCooldownMs(60_000, 1100, 220e6, 30e6, 3.0), 1100);
        n += eq("no price keeps cap", Economy.effectiveCooldownMs(60_000, 1100, 220e6, null, 3.0), 60_000);
        n += eq("relax disabled", Economy.effectiveCooldownMs(60_000, 1100, 220e6, 30e6, 0), 60_000);
        return n;
    }

    /** 0.9.16 zone-first buy order; numbers from the 03-36 post-rebirth log. */
    private static int choose() {
        int n = 0;
        // Gate open + zone affordable: the zone, whatever the sword costs.
        n += eq("zone affordable -> zone", Economy.chooseBuyKind(true, true, true, true, 10e9, 10e9, 20e9, 3000.0, 25, 2000), "zone");
        n += eq("zone 1: 145.7K zone beats 525K sword", Economy.chooseBuyKind(true, true, true, true, 525.22e3, 145.7e3, 553.5e3, 725.0, 25, 2000), "zone");
        // Zone 3: bal 31.91M, zone ~252M (gap 220M), sword 22.52M = 10% -> sword while saving.
        n += eq("cheap sword while saving", Economy.chooseBuyKind(true, true, true, false, 22.52e6, 252e6, 31.91e6, 4742.0, 25, 2000), "sword");
        // bal 79.14M, sword 78.82M = 45% of the 173M gap -> save for the zone.
        n += eq("pricey sword while saving -> wait", Economy.chooseBuyKind(true, true, true, false, 78.82e6, 252e6, 79.14e6, 2678.0, 25, 2000), null);
        // TTK at the movement floor: no sword at all while saving.
        n += eq("instant ttk -> wait", Economy.chooseBuyKind(true, true, true, false, 1.84e6, 252e6, 200e6, 480.0, 25, 2000), null);
        // Gate closed (zone 6 on arrival, TTK 42s): the sword.
        n += eq("gate closed -> sword", Economy.chooseBuyKind(true, false, true, true, 507.09e9, 137.26e9, 568.87e9, 44399.0, 25, 2000), "sword");
        n += eq("gate closed, sword unaffordable -> wait", Economy.chooseBuyKind(true, false, false, true, 507.09e9, 137.26e9, 385.47e9, 42783.0, 25, 2000), null);
        n += eq("zone price unknown -> sword", Economy.chooseBuyKind(true, true, true, false, 22.52e6, null, 31.91e6, 4742.0, 25, 2000), "sword");
        n += eq("neither -> wait", Economy.chooseBuyKind(true, true, false, false, 20e9, 10e9, 1e9, 3000.0, 25, 2000), null);
        n += eq("sword maxed, zone unaffordable -> wait", Economy.chooseBuyKind(false, true, true, false, 20e9, 10e9, 1e9, 3000.0, 25, 2000), null);
        n += eq("both closed -> wait", Economy.chooseBuyKind(false, false, true, true, 20e9, 10e9, 50e9, 3000.0, 25, 2000), null);
        // Saving rule boundaries.
        n += eq("saving: exactly 25%", Economy.swordWhileSaving(55e6, 252e6, 32e6, 4000.0, 25, 2000), true);
        n += eq("saving: 25.1%", Economy.swordWhileSaving(55.22e6, 252e6, 32e6, 4000.0, 25, 2000), false);
        n += eq("saving: ttk unknown uses price only", Economy.swordWhileSaving(22e6, 252e6, 32e6, null, 25, 2000), true);
        n += eq("saving: at floor", Economy.swordWhileSaving(1e6, 252e6, 32e6, 2000.0, 25, 2000), false);
        n += eq("saving: floor off", Economy.swordWhileSaving(1e6, 252e6, 32e6, 500.0, 25, 0), true);
        n += eq("preferred: gate open -> zone", Economy.preferredKind(true, true), "zone");
        n += eq("preferred: gate closed -> sword", Economy.preferredKind(true, false), "sword");
        n += eq("preferred: nothing", Economy.preferredKind(false, false) == null, true);
        // Captcha capture is map-only by default; a v19 "auto" config migrates, "screen" is kept.
        n += eq("fresh capture mode", CFG.captchaCaptureMode, "map");
        n += eq("fresh map scale is native x1 (0.9.32: the cloud reader wants the raw map)", CFG.captchaMapScale, 1);
        n += eq("re-prompt line is a retry signal", CFG.captchaRetryPatterns.contains("please enter the captcha on the map"), true);
        try {
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("ycbot-cfg", ".json");
            java.nio.file.Files.writeString(tmp, "{\"configVersion\":19,\"captchaCaptureMode\":\"auto\"}");
            n += eq("auto migrates to map", YCBotChallengeConfig.load(tmp).captchaCaptureMode, "map");
            java.nio.file.Files.writeString(tmp, "{\"configVersion\":19,\"captchaCaptureMode\":\"screen\"}");
            n += eq("screen opt-in kept", YCBotChallengeConfig.load(tmp).captchaCaptureMode, "screen");
            java.nio.file.Files.deleteIfExists(tmp);
        } catch (Exception ex) {
            System.err.println("FAIL capture mode migration: " + ex);
            n++;
        }
        return n;
    }

    private static int rebirth() {
        int n = 0;
        n += eq("gui title", ChatClassifier.isRebirthGui("Rebirth GUI"), true);
        n += eq("gui title case", ChatClassifier.isRebirthGui("REBIRTH gui"), true);
        n += eq("not gui", ChatClassifier.isRebirthGui("Captcha"), false);
        n += eq("null title", ChatClassifier.isRebirthGui(null), false);
        n += eq("rebirth covers", Economy.knownAffordable(8.04e9 + 29.99e12, 30.1e12), true);
        n += eq("rebirth short", Economy.knownAffordable(8.04e9 + 29.99e12, 8.04e9), false);
        n += eq("rebirth interval 0 is immediate",
            Economy.cooldownElapsed(100, 99, 0, false), true);
        // Sword/zone choice is unused while rebirth is covered (controller short-circuits).
        n += eq("chooser still answers when asked",
            Economy.chooseBuyKind(true, true, true, true, 10e9, 10e9, 20e9, 3000.0, 25, 2000), "zone");
        return n;
    }

    private static int gates() {
        int n = 0;
        n += eq("unknown price not affordable", Economy.knownAffordable(null, 1.09e12), false);
        n += eq("null bal not affordable", Economy.knownAffordable(1.8e12, null), false);
        n += eq("covered is affordable", Economy.knownAffordable(1.8e12, 1.9e12), true);

        // Unknown-price retry: only once the balance passes the OLD price again.
        n += eq("retry below old price refused",
            Economy.retryUnknownAllowed(780e9, 700e9, 0.0), false);
        n += eq("retry at old price allowed",
            Economy.retryUnknownAllowed(780e9, 781e9, 0.0), true);
        n += eq("retry with growth margin",
            Economy.retryUnknownAllowed(780e9, 800e9, 0.05), false);
        n += eq("retry past growth margin",
            Economy.retryUnknownAllowed(780e9, 820e9, 0.05), true);
        n += eq("no last price → no retry", Economy.retryUnknownAllowed(null, 9e15, 0.0), false);

        // ETA.
        n += eq("eta 60B at 60B/min = 60s", Economy.etaMs(60e9, 60e9), 60_000.0, 1e-6);
        n += eq("eta null without rate", Economy.etaMs(60e9, null) == null, true);
        n += eq("eta null when covered", Economy.etaMs(0.0, 60e9) == null, true);

        n += eq("follow-up bypasses cooldown",
            Economy.cooldownElapsed(10_000, 9_500, 60_000, true), true);
        n += eq("kill-driven blocked by 60s",
            Economy.cooldownElapsed(30_000, 1_000, 60_000, false), false);
        n += eq("kill-driven allowed after 60s",
            Economy.cooldownElapsed(61_000, 1_000, 60_000, false), true);

        n += eq("crossing kill with min 1 waits",
            Economy.extraKillsReached(10, 10, 1), false);
        n += eq("next kill with min 1",
            Economy.extraKillsReached(11, 10, 1), true);

        n += eq("no spend is settled", Economy.sidebarSettled(5_000, 0, 2500), true);
        n += eq("spend still lagging", Economy.sidebarSettled(3_000, 2_000, 2500), false);
        n += eq("spend settled", Economy.sidebarSettled(5_000, 2_000, 2500), true);
        return n;
    }

    /**
     * SWORD ENCHANTER tooltips, verbatim from the 2026-09-03 screenshots. Every
     * enchant is listed whether owned or not; LOCKED and maxed ones are never clicked.
     */
    private static int enchantLore() {
        int n = 0;
        EnchantLore lore = new EnchantLore(CFG);
        List<String> rage = List.of(
            "ACTIVATION CHANCE: 0.000%", "Description:", "| Chance to deal a large amount of",
            "| damage while attacking a mob.", "|", "| Damage: 5.00x", "| Type: Damage",
            "Information:", "| Level: 0 / 100", "| Price: 20,000,000 Souls",
            "LOCKED (Requires Sword Level 50)");
        List<String> magnet = List.of(
            "ACTIVATION CHANCE: 0.361%", "Description:", "| Chance to multiply souls",
            "| gained from soul greed.", "|", "| Type: Souls", "Information:",
            "| Level: 1,321 / 2,000", "| Price: 7,105,000 Souls", "[CLICK HERE TO UPGRADE THIS ENCHANT]");
        List<String> greed = List.of(
            "ACTIVATION CHANCE: 100.000%", "Description:", "| Gain a large amount of essence",
            "| while swinging your sword.", "|", "| Amount: 2,200", "| Type: Essence",
            "Information:", "| Level: 1,000 / 1,000", "| Price: 5,100,000 Souls",
            "[CLICK HERE TO UPGRADE THIS ENCHANT]");
        List<String> maxUp = List.of(
            "Click here to purchase the", "max amount of levels you can.", "", "* Levels: 1",
            "* Price: 7,730,000 Souls", "", "Click to upgrade enchant.");

        EnchantLore.Item r = lore.parse("Rage Enchant", rage);
        n += eq("rage is enchant", r.isEnchant(), true);
        n += eq("rage level", r.level(), 0);
        n += eq("rage max", r.maxLevel(), 100);
        n += eq("rage price", r.price(), 20e6, 1);
        n += eq("rage currency", r.currency(), "souls");
        n += eq("rage locked", r.locked(), true);
        n += eq("rage not upgradable", r.upgradable(), false);
        n += eq("rage signature", r.signature(), true);

        EnchantLore.Item m = lore.parse("Soul Magnet Enchant", magnet);
        n += eq("magnet level", m.level(), 1321);
        n += eq("magnet max", m.maxLevel(), 2000);
        n += eq("magnet price", m.price(), 7.105e6, 1);
        n += eq("magnet upgradable", m.upgradable(), true);
        n += eq("magnet not locked", m.locked(), false);

        EnchantLore.Item g = lore.parse("Essence Greed Enchant", greed);
        n += eq("greed maxed", g.maxed(), true);
        n += eq("greed not upgradable", g.upgradable(), false);

        // Same tooltip once the sword reaches level 50: LOCKED line gone ⇒ level-0 enchant is buyable.
        EnchantLore.Item r50 = lore.parse("Rage Enchant", rage.subList(0, rage.size() - 1));
        n += eq("rage unlocked at 50 is upgradable", r50.upgradable(), true);

        // Policy (0.9.30): a weighted roll over the affordable non-maxed candidates; roll 0 = first in slot order.
        // maxed/locked skipped; attempted skipped. Affordability uses the ITEM's price currency
        // (0.9.11 spent essence against the souls balance).
        List<EnchantLore.Item> grid = List.of(g, r, m);
        java.util.Map<String, Double> rich = java.util.Map.of("souls", 8e6, "essence", 100.0);
        java.util.Map<String, Double> poor = java.util.Map.of("souls", 5e6, "essence", 1e12);
        n += eq("choose magnet at 8M souls", EnchantLore.chooseEnchant(grid, rich, "souls", java.util.Set.of(), 0.0, 0.0) == m, true);
        n += eq("choose none at 5M souls (essence irrelevant)",
            EnchantLore.chooseEnchant(grid, poor, "souls", java.util.Set.of(), 0.0, 0.0) == null, true);
        n += eq("choose skips attempted",
            EnchantLore.chooseEnchant(grid, rich, "souls", java.util.Set.of("Soul Magnet Enchant"), 0.0, 0.0) == null, true);
        n += eq("choose rage once unlocked",
            EnchantLore.chooseEnchant(List.of(g, r50, m), java.util.Map.of("souls", 25e6), "souls", java.util.Set.of(), 0.0, 0.0) == r50, true);
        // 0.9.30 weighted pick: 10 essence visits in the 20:35/00:19 logs put the whole balance into
        // Rocket (slot order) while Second Hand got the change and Wizard was never bought.
        List<EnchantLore.Item> two = List.of(m, r50); // magnet 7.105M and rage 20M, both souls
        java.util.Map<String, Double> both = java.util.Map.of("souls", 25e6);
        java.util.Map<String, Double> oneOnly = java.util.Map.of("souls", 8e6);
        n += eq("two candidates at 25M", EnchantLore.enchantCandidates(two, both, "souls", java.util.Set.of()).size(), 2);
        n += eq("one candidate at 8M", EnchantLore.enchantCandidates(two, oneOnly, "souls", java.util.Set.of()).size(), 1);
        n += eq("equal weights, roll 0.0 → first", EnchantLore.chooseEnchant(two, both, "souls", java.util.Set.of(), 0.0, 0.0) == m, true);
        n += eq("equal weights, roll 0.49 → first", EnchantLore.chooseEnchant(two, both, "souls", java.util.Set.of(), 0.49, 0.0) == m, true);
        n += eq("equal weights, roll 0.51 → second", EnchantLore.chooseEnchant(two, both, "souls", java.util.Set.of(), 0.51, 0.0) == r50, true);
        n += eq("equal weights, roll 0.99 → second", EnchantLore.chooseEnchant(two, both, "souls", java.util.Set.of(), 0.99, 0.0) == r50, true);
        n += eq("roll 1.0 stays in range", EnchantLore.chooseEnchant(two, both, "souls", java.util.Set.of(), 1.0, 0.0) == r50, true);
        for (double roll : new double[] {0.0, 0.5, 0.99}) {
            n += eq("unaffordable never chosen (roll " + roll + ")",
                EnchantLore.chooseEnchant(two, oneOnly, "souls", java.util.Set.of(), roll, 0.0) == m, true);
        }
        n += eq("weight equal", EnchantLore.enchantWeight(m, 0.0), 1.0, 1e-9);
        n += eq("weight lag: level 0/100 at bias 2 → 3", EnchantLore.enchantWeight(r50, 2.0), 3.0, 1e-9);
        n += eq("weight lag: 1321/2000 at bias 2 → 1.68", EnchantLore.enchantWeight(m, 2.0), 1.679, 1e-3);
        n += eq("lag bias 2 favours the level-0 enchant at roll 0.4",
            EnchantLore.chooseEnchant(two, both, "souls", java.util.Set.of(), 0.4, 2.0) == r50, true);
        n += eq("equal weights keep slot order at roll 0.4",
            EnchantLore.chooseEnchant(two, both, "souls", java.util.Set.of(), 0.4, 0.0) == m, true);
        // Essence-priced item on whatever tab: judged against essence.
        EnchantLore.Item rocket = lore.parse("Rocket Enchant", List.of("ACTIVATION CHANCE: 100.000%",
            "| Level: 2,977 / 5,000", "| Price: 307,700 Essence", "[CLICK HERE TO UPGRADE THIS ENCHANT]"));
        n += eq("rocket currency", rocket.currency(), "essence");
        n += eq("rocket unaffordable with 187K essence even with 589M souls",
            EnchantLore.chooseEnchant(List.of(rocket), java.util.Map.of("souls", 589e6, "essence", 187e3), "souls", java.util.Set.of()) == null, true);
        n += eq("rocket affordable with 29M essence",
            EnchantLore.chooseEnchant(List.of(rocket), java.util.Map.of("souls", 0.0, "essence", 29e6), "souls", java.util.Set.of()) == rocket, true);
        // The showing tab comes from the items' price currency (the server remembers the last tab).
        n += eq("showing essence", EnchantLore.majorityCurrency(List.of(rocket, rocket, m)), "essence");
        n += eq("showing souls", EnchantLore.majorityCurrency(List.of(m, g, r)), "souls");
        n += eq("showing unknown", EnchantLore.majorityCurrency(List.of()) == null, true);

        EnchantLore.Item mu = lore.parse("Max Upgrade", maxUp);
        n += eq("max upgrade item", mu.maxUpgrade(), true);
        n += eq("max upgrade levels", mu.maxLevels(), 1);
        n += eq("max upgrade price", mu.maxPrice(), 7.73e6, 1);
        n += eq("max upgrade not enchant", mu.isEnchant(), false);

        // Tabs and screens.
        n += eq("souls tab", lore.parse("SOULS", List.of()).tab(), "souls");
        n += eq("essence tab small caps", lore.parse("ᴇꜱꜱᴇɴᴄᴇ", List.of()).tab(), "essence");
        n += eq("enchant is not a tab", m.tab() == null, true);
        n += eq("tab name singular", lore.tabOfName("Soul Enchants"), "souls");
        n += eq("tab name shards", lore.tabOfName("§b§lSHARDS"), "shards");
        n += eq("tab name shard singular", lore.tabOfName("Shard Upgrades"), "shards");
        n += eq("tab name essence colored", lore.tabOfName("§dEssence"), "essence");
        n += eq("tab name: enchant name is not a tab word", lore.tabOfName("Essence Greed Enchant") == null, false);
        n += eq("tab name: icon", lore.tabOfName("Max Upgrade") == null, true);
        n += eq("upgrade title", lore.isUpgradeTitle("Soul Magnet Upgrade"), true);
        n += eq("upgrade title colored", lore.isUpgradeTitle("§aSoul Magnet Upgrade"), true);
        n += eq("enchanter title (glyph, formatting only) is not upgrade", lore.isUpgradeTitle("§f§r§f§r"), false);
        n += eq("null title", lore.isUpgradeTitle(null), false);
        n += eq("small-caps level line",
            lore.parse("x", List.of("| ʟᴇᴠᴇʟ: 5 / 100", "| ᴘʀɪᴄᴇ: 1,000 ꜱᴏᴜʟꜱ")).upgradable(), true);
        n += eq("sword by name", lore.isSword("Golden Sword", List.of()), true);
        n += eq("sword by lore", lore.isSword("Thing", List.of("Enchants: (9)", "| Speed MAX")), true);
        n += eq("not a sword", lore.isSword("Rusty Key", List.of("Open a crate")), false);
        return n;
    }

    /** 0.9.10 de-fingerprinting helpers. */
    private static int realism() {
        int n = 0;
        // Re-aim threshold: base inside the final 1.5 blocks, ×3 four blocks out, capped.
        n += eq("reacquire at reach", Economy.reacquireThresholdDeg(3.0, 3.0, 3.0, 3.0, 4.0, 1.5), 3.0, 1e-9);
        n += eq("reacquire at reach+1", Economy.reacquireThresholdDeg(3.0, 4.0, 3.0, 3.0, 4.0, 1.5), 3.0, 1e-9);
        n += eq("reacquire at reach+2", Economy.reacquireThresholdDeg(3.0, 5.0, 3.0, 3.0, 4.0, 1.5), 6.0, 1e-9);
        n += eq("reacquire at reach+4", Economy.reacquireThresholdDeg(3.0, 7.0, 3.0, 3.0, 4.0, 1.5), 9.0, 1e-9);
        n += eq("reacquire far capped", Economy.reacquireThresholdDeg(3.0, 20.0, 3.0, 3.0, 4.0, 1.5), 9.0, 1e-9);
        n += eq("reacquire mult<1 is base", Economy.reacquireThresholdDeg(3.0, 20.0, 3.0, 0.5, 4.0, 1.5), 3.0, 1e-9);
        // Bimodal breaks.
        n += eq("break short", Economy.breakKind(0.1, 0.7), "short");
        n += eq("break long", Economy.breakKind(0.9, 0.7), "long");
        // Hesitation only on long saves, never in the snowball.
        n += eq("hesitate: price just learned", Economy.hesitationApplies(100_000, 130_000, 120_000, 1e12, 0.9e12, 3.0), false);
        n += eq("hesitate: long save", Economy.hesitationApplies(0 + 1, 400_000, 120_000, 1e12, 0.9e12, 3.0), true);
        n += eq("hesitate: snowball (bal 5x price)", Economy.hesitationApplies(1, 400_000, 120_000, 5e12, 0.9e12, 3.0), false);
        n += eq("hesitate: unknown seen-at", Economy.hesitationApplies(0, 400_000, 120_000, 1e12, 0.9e12, 3.0), false);
        // Deferred probes need both the kills and the delay.
        n += eq("probe: kills only", Economy.probeDue(20, 15, 60_000, 300_000), false);
        n += eq("probe: delay only", Economy.probeDue(3, 15, 900_000, 300_000), false);
        n += eq("probe: both", Economy.probeDue(20, 15, 900_000, 300_000), true);
        // Unknown-price retry with a rolled growth (replaces the follow-up re-send).
        n += eq("retry below floor×1.2", Economy.retryUnknownAllowed(1.0e12, 1.1e12, 0.2), false);
        n += eq("retry at floor×1.2", Economy.retryUnknownAllowed(1.0e12, 1.2e12, 0.2), true);

        // Enchanter visit hazard (0.9.11): ramp 2→12 min, 8% at full, squared in between.
        n += eq("hazard before ramp", Economy.visitHazard(60_000, 120_000, 720_000, 0.08, 1.0, 1.0), 0.0, 1e-12);
        n += eq("hazard at ramp start", Economy.visitHazard(120_000, 120_000, 720_000, 0.08, 1.0, 1.0), 0.0, 1e-12);
        n += eq("hazard mid ramp (7 min)", Economy.visitHazard(420_000, 120_000, 720_000, 0.08, 1.0, 1.0), 0.02, 1e-9);
        n += eq("hazard full", Economy.visitHazard(720_000, 120_000, 720_000, 0.08, 1.0, 1.0), 0.08, 1e-9);
        n += eq("hazard past full stays", Economy.visitHazard(3_600_000, 120_000, 720_000, 0.08, 1.0, 1.0), 0.08, 1e-9);
        n += eq("hazard cook bonus", Economy.visitHazard(720_000, 120_000, 720_000, 0.08, 1.0, 2.0), 0.16, 1e-9);
        n += eq("hazard pull", Economy.visitHazard(720_000, 120_000, 720_000, 0.08, 3.0, 1.0), 0.24, 1e-9);
        n += eq("hazard capped", Economy.visitHazard(720_000, 120_000, 720_000, 0.8, 3.0, 2.0), 1.0, 1e-9);
        n += eq("pull below price", Economy.affordPull(5e6, 7.1e6, 3.0), 1.0, 1e-9);
        n += eq("pull at 2x", Economy.affordPull(14.2e6, 7.1e6, 3.0), 2.0, 1e-9);
        n += eq("pull capped", Economy.affordPull(100e6, 7.1e6, 3.0), 3.0, 1e-9);
        n += eq("pull unknown price", Economy.affordPull(100e6, null, 3.0), 1.0, 1e-9);
        return n;
    }

    /** Learned prices persist per username (Drew runs an alt) and survive a restart. */
    private static int stateStore() {
        int n = 0;
        try {
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("ycbot-state", ".json");
            java.nio.file.Files.deleteIfExists(tmp);
            StateStore s = new StateStore(tmp);
            n += eq("empty store", s.get("Ihazekids69420") == null, true);
            StateStore.Entry e = new StateStore.Entry();
            e.swordTarget = 6.21e12;
            e.rebirthLastPrice = 30e12;
            e.rebirths = 2;
            s.put("Ihazekids69420", e);
            StateStore.Entry alt = new StateStore.Entry();
            alt.swordTarget = 41.4e9;
            s.put("AltAccount", alt);
            // Reload from disk: per-user isolation and values intact.
            StateStore r = new StateStore(tmp);
            n += eq("main sword", r.get("ihazekids69420").swordTarget, 6.21e12, 1e6);
            n += eq("main rebirth floor", r.get("IHAZEKIDS69420").rebirthLastPrice, 30e12, 1e6);
            n += eq("main rebirths", r.get("Ihazekids69420").rebirths, 2);
            n += eq("alt sword", r.get("altaccount").swordTarget, 41.4e9, 1e3);
            n += eq("alt has no rebirth floor", r.get("altaccount").rebirthLastPrice == null, true);
            r.remove("Ihazekids69420");
            StateStore r2 = new StateStore(tmp);
            n += eq("main removed", r2.get("Ihazekids69420") == null, true);
            n += eq("alt survives removal", r2.get("AltAccount") != null, true);
            n += eq("null user", r2.get(null) == null, true);
            java.nio.file.Files.deleteIfExists(tmp);
        } catch (Exception ex) {
            System.err.println("FAIL stateStore: " + ex);
            n++;
        }
        return n;
    }

    /** 0.9.21: a bar that vanished under barVanishMinCookMs (timings from the 17:12 chicken log). */
    private static int instantKills() {
        int n = 0;
        long tag = 100_000;
        // bar gone at +150ms, money landed at +1000ms, judged at +1100ms -> kill
        n += eq("instant: money after tag", Economy.vanishVerdict(tag + 150, tag, tag + 1100, false, tag + 1000, 1500), "kill");
        // nothing yet at +800ms -> wait
        n += eq("instant: still waiting", Economy.vanishVerdict(tag + 150, tag, tag + 800, false, 0, 1500), "wait");
        // window over (+1800ms), no money, entity alive -> the old verdict
        n += eq("instant: window over", Economy.vanishVerdict(tag + 150, tag, tag + 1800, false, 0, 1500), "retag");
        // entity gone at any time -> kill
        n += eq("instant: entity gone", Economy.vanishVerdict(tag + 150, tag, tag + 200, true, 0, 1500), "kill");
        // money that rose before the tag is someone else's credit
        n += eq("instant: stale money", Economy.vanishVerdict(tag + 150, tag, tag + 1800, false, tag - 500, 1500), "retag");
        n += eq("instant: stale money still waits", Economy.vanishVerdict(tag + 150, tag, tag + 500, false, tag - 500, 1500), "wait");
        return n;
    }

    /** 0.9.17: rebirth-upgrade menus (star lore verbatim from the 2026-09-03 screenshot) and giveaway packets. */
    private static int rebirthUpgrades() {
        int n = 0;
        RebirthLore rl = new RebirthLore(CFG);
        List<String> star = List.of("REBIRTH UPGRADES", "REBIRTHS",
            "After you perform a rebirth, you'll use the rebirth points on these upgrades to help boost your progression even further.",
            "BALANCES:", "| Current Points: 0", "[CLICK HERE TO VIEW THE REBIRTH UPGRADES]");
        n += eq("star by lore", rl.isStar("Nether Star", star), true);
        n += eq("star by name", rl.isStar("REBIRTH UPGRADES", List.of()), true);
        n += eq("diamond is not the star", rl.isStar("Rebirth", List.of("Click to rebirth")), false);
        n += eq("points 0", rl.points(star), 0);
        n += eq("points 1,250", rl.points(List.of("BALANCES:", "| Current Points: 1,250")), 1250);
        n += eq("points absent", rl.points(List.of("nothing")) == null, true);
        n += eq("menu title", rl.isMenuTitle("Upgrades"), true);
        n += eq("rebirth gui is not the menu", rl.isMenuTitle("Rebirth GUI"), false);
        RebirthLore.Item ench = rl.parse("Enchant Proc Upgrade", List.of("Level: 2 / 10", "Cost: 3 Rebirth Points"));
        n += eq("item level", ench.level(), 2);
        n += eq("item max", ench.maxLevel(), 10);
        n += eq("item cost", ench.cost(), 3);
        n += eq("item not maxed", ench.isMaxed(), false);
        RebirthLore.Item maxed = rl.parse("Damage Upgrade", List.of("Level: 10 / 10", "MAXED"));
        n += eq("maxed by level", maxed.isMaxed(), true);
        n += eq("maxed by word", rl.parse("Damage Upgrade", List.of("Maxed out!")).isMaxed(), true);
        RebirthLore.Item unknown = rl.parse("Souls Upgrade", List.of("Boosts soul drops"));
        n += eq("unknown level stays null", unknown.level() == null && unknown.cost() == null, true);
        // Drew's order: enchant proc, damage, essence, souls; maxed and unaffordable ones are skipped.
        RebirthLore.Item essence = rl.parse("Essence Upgrade", List.of("Level: 0 / 10", "Cost: 2 Rebirth Points"));
        RebirthLore.Item souls = rl.parse("Souls Upgrade", List.of("Level: 0 / 10", "Cost: 2 Rebirth Points"));
        RebirthLore.Item damage = rl.parse("Damage Upgrade", List.of("Level: 0 / 10", "Cost: 10 Rebirth Points"));
        List<RebirthLore.Item> menu = List.of(essence, souls, ench, damage);
        n += eq("enchant first", rl.choose(menu, 5).name(), "Enchant Proc Upgrade");
        n += eq("enchant maxed -> damage if affordable", rl.choose(List.of(essence, souls, maxed, damage), 10).name(), "Damage Upgrade");
        n += eq("damage too dear -> essence", rl.choose(List.of(essence, souls, maxed, damage), 5).name(), "Essence Upgrade");
        n += eq("all maxed -> none", rl.choose(List.of(maxed), 5) == null, true);
        n += eq("unknown cost is clicked", rl.choose(List.of(unknown), 1).name(), "Souls Upgrade");
        n += eq("unknown points buys anyway", rl.choose(menu, null).name(), "Enchant Proc Upgrade");
        n += eq("order index", rl.orderIndex(damage), 1);
        n += eq("outside order never clicked", rl.choose(List.of(rl.parse("Mystery Box", List.of("?"))), 9) == null, true);
        // Rebirth chat lines (verbatim 15:23 log) match the default patterns; player chat about rebirths does not.
        boolean hit = false;
        for (String p : CFG.rebirthChatPatterns) if (Pattern.compile(Pattern.quote(p), Pattern.CASE_INSENSITIVE).matcher("[!] You have successfully rebirthed.").find()) hit = true;
        n += eq("rebirth chat line matches", hit, true);
        hit = false;
        for (String p : CFG.rebirthChatPatterns) if (Pattern.compile(Pattern.quote(p), Pattern.CASE_INSENSITIVE).matcher("Rebirth Milestone Completed").find()) hit = true;
        n += eq("milestone line matches", hit, true);
        hit = false;
        for (String p : CFG.rebirthChatPatterns) if (Pattern.compile(Pattern.quote(p), Pattern.CASE_INSENSITIVE).matcher("[✧R193✧] snusie » when u rebirth next").find()) hit = true;
        n += eq("player rebirth talk does not", hit, false);
        // Giveaway packet (verbatim 2026-09-03 15:52 log).
        List<Pattern> ann = List.of(Pattern.compile(Pattern.quote("new giveaway"), Pattern.CASE_INSENSITIVE));
        n += eq("giveaway prize", ChatClassifier.giveawayPrize(List.of("NEW GIVEAWAY (30s to enter)", "Current Lootbox", "Click to Enter!"), ann), "Current Lootbox");
        n += eq("giveaway no prize", ChatClassifier.giveawayPrize(List.of("NEW GIVEAWAY (30s to enter)", "Click to Enter!"), ann) == null, true);
        return n;
    }

    /** 0.9.15 rebirth horizon: numbers verbatim from the 2026-09-03 upgrade_plan lines. */
    private static int rebirthHorizon() {
        int n = 0;
        // 14-52: zone 8 for 415.21T at bal 425.19T, rebirth 900T, 108.79T/min: payback ~10 min vs 4.4 min to rebirth.
        n += eq("horizon: zone 8 before 900T rebirth", Economy.rebirthHorizonAllows(415.21e12, 425.19e12, 900e12, 108.79e12, 1.3), false);
        // 14-52: sword 266.33T at bal 272.53T, 102.51T/min: same shape.
        n += eq("horizon: sword before 900T rebirth", Economy.rebirthHorizonAllows(266.33e12, 272.53e12, 900e12, 102.51e12, 1.25), false);
        // 02-23: zone 7 for 7.55T at bal 7.71T, rebirth 30T, 2.39T/min: the rebirth landed 14 min later, staying was 9.
        n += eq("horizon: zone 7 before 30T rebirth", Economy.rebirthHorizonAllows(7.55e12, 7.71e12, 30e12, 2.39e12, 1.3), false);
        // 03-36: zone 6 for 137.26B at bal 137.5B with the 900T rebirth far away: fine.
        n += eq("horizon: zone 6 far from rebirth", Economy.rebirthHorizonAllows(137.26e9, 137.5e9, 900e12, 75.44e9, 1.3), true);
        // 03-36: early snowball, zone for 145.7K against a 30T gap.
        n += eq("horizon: early snowball", Economy.rebirthHorizonAllows(145.7e3, 145.7e3, 30e12, 410.95e3, 1.3), true);
        n += eq("horizon: unknown income", Economy.rebirthHorizonAllows(415e12, 425e12, 900e12, null, 1.3), true);
        n += eq("horizon: unknown rebirth", Economy.rebirthHorizonAllows(415e12, 425e12, null, 108e12, 1.3), true);
        n += eq("horizon: unknown price", Economy.rebirthHorizonAllows(null, 425e12, 900e12, 108e12, 1.3), true);
        n += eq("horizon: gain 1.0 is off", Economy.rebirthHorizonAllows(415e12, 425e12, 900e12, 108e12, 1.0), true);
        n += eq("horizon: rebirth covered", Economy.rebirthHorizonAllows(415e12, 950e12, 900e12, 108e12, 1.3), true);
        // Break-even: P < G*(g-1); gap 350T, g 1.4 -> 140T is not sooner, 139T is.
        n += eq("horizon: at break-even", Economy.rebirthHorizonAllows(140e12, 0.0, 350e12, 10e12, 1.4), false);
        n += eq("horizon: just under", Economy.rebirthHorizonAllows(139e12, 0.0, 350e12, 10e12, 1.4), true);
        n += eq("rebirth eta", Economy.rebirthEtaMin(425.19e12, 900e12, 108.79e12), 4.3645, 0.001);
        n += eq("rebirth eta covered", Economy.rebirthEtaMin(950e12, 900e12, 108.79e12), 0.0, 1e-9);
        n += eq("rebirth eta unknown", Economy.rebirthEtaMin(425e12, 900e12, null) == null, true);
        n += eq("buy eta", Economy.buyEtaMin(415.21e12, 425.19e12, 900e12, 108.79e12, 1.3), 6.293, 0.001);
        // Chat captcha guard: the player line that made the bot type "qwe" into public chat.
        n += eq("captcha: player line ignored",
            ChatClassifier.captchaLineEligible("[\u2727R193\u2727] [\u2629nightmare\u2629]    snusie  \u00bb next time just captcha him", false), false);
        n += eq("captcha: server line eligible", ChatClassifier.captchaLineEligible("Please enter the text in chat to verify", false), true);
        n += eq("captcha: overlay ignored", ChatClassifier.captchaLineEligible("enter the text in chat", true), false);
        n += eq("captcha: own line ignored", ChatClassifier.captchaLineEligible("[YCBotChallenge] captcha detected", false), false);
        return n;
    }

    /** 0.9.14: the zone's AFK upgrade mob is never a target (nameplate from the 2026-09-03 screenshot). */
    private static int ignoredMobs() {
        int n = 0;
        List<Pattern> res = new java.util.ArrayList<>();
        for (String p : CFG.ignoreMobPatterns) res.add(Pattern.compile(Pattern.quote(p), Pattern.CASE_INSENSITIVE));
        n += eq("afk mob ignored", Economy.ignoredMob("[AFKMOB] LVL7 Donkey ❤∞", res), true);
        n += eq("afk tag alone", Economy.ignoredMob("[AfkMob] Donkey", res), true);
        n += eq("infinite hp alone", Economy.ignoredMob("[EPIC] LVL7 Donkey ❤∞", res), true);
        n += eq("real mob targeted", Economy.ignoredMob("[EPIC] LVL4 Pig ❤8.48M", res), false);
        n += eq("rare mob targeted", Economy.ignoredMob("[RARE] LVL6 Cow ❤41.4M", res), false);
        n += eq("no nameplate", Economy.ignoredMob(null, res), false);
        n += eq("no patterns", Economy.ignoredMob("[AFKMOB] LVL7 Donkey", List.of()), false);

        // 0.9.26: the plate is a hologram (19:26 log + screenshot: three text-display lines
        // above the AFK Mooshroom), and the hit raises a bar titled "[AFKMOB] LVL9 Mooshroom".
        n += eq("hologram lines ignored", Economy.ignoredByLines(
            List.of("⟡332.12B⟡", "[AFKMOB] LVL9 Mooshroom ❤∞", "RIGHT CLICK TO UPGRADE"), res), true);
        n += eq("real plate lines targeted", Economy.ignoredByLines(List.of("[RARE] LVL9 Mooshroom ❤2.3B"), res), false);
        n += eq("no lines", Economy.ignoredByLines(List.of(), res), false);
        n += eq("null lines", Economy.ignoredByLines(null, res), false);
        n += eq("afk bar title", Economy.bossBarIgnored(List.of("[AFKMOB] LVL9 Mooshroom"), res), true);
        n += eq("afk bar next to a real bar", Economy.bossBarIgnored(List.of("[AFKMOB] LVL9 Mooshroom", "LVL9 Mooshroom"), res), false);
        n += eq("real bar", Economy.bossBarIgnored(List.of("[RARE] LVL9 Mooshroom"), res), false);
        n += eq("no bars", Economy.bossBarIgnored(List.of(), res), false);
        n += eq("plate above the mob", Economy.hologramBelongs(0.2, 0.1, 1.9, 0.9), true);
        n += eq("plate at head height", Economy.hologramBelongs(0.0, 0.0, 0.0, 0.9), true);
        n += eq("plate of the neighbour", Economy.hologramBelongs(2.5, 0.0, 1.9, 0.9), false);
        n += eq("plate below", Economy.hologramBelongs(0.0, 0.0, -1.0, 0.9), false);
        n += eq("plate too high", Economy.hologramBelongs(0.0, 0.0, 4.0, 0.9), false);
        n += eq("manual mark same kind", Economy.manualMarkMatches("Mooshroom", "mooshroom", 0.4, 1.5), true);
        n += eq("manual mark other kind", Economy.manualMarkMatches("Cow", "Mooshroom", 0.4, 1.5), false);
        n += eq("manual mark far", Economy.manualMarkMatches("Mooshroom", "Mooshroom", 3.0, 1.5), false);
        n += eq("manual mark null", Economy.manualMarkMatches(null, "Mooshroom", 0.0, 1.5), false);
        // Manual marks persist by kind and position.
        try {
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("ycbot-ignored", ".json");
            java.nio.file.Files.deleteIfExists(tmp);
            IgnoreStore s = new IgnoreStore(tmp);
            n += eq("empty ignore store", s.size(), 0);
            IgnoreStore.Mark m = new IgnoreStore.Mark();
            m.type = "Mooshroom";
            m.x = 12.3; m.y = 64.0; m.z = -30.2;
            m.label = "[AFKMOB] LVL9 Mooshroom ❤∞ | RIGHT CLICK TO UPGRADE";
            m.at = 1_788_470_000_000L;
            s.add(m);
            IgnoreStore r = new IgnoreStore(tmp);
            n += eq("mark persisted", r.size(), 1);
            n += eq("mark found nearby", r.findNear("Mooshroom", 12.6, 64.0, -30.0, 1.5) != null, true);
            n += eq("mark label kept", r.findNear("mooshroom", 12.3, 64.0, -30.2, 1.5).label, m.label);
            n += eq("mark other kind", r.findNear("Cow", 12.3, 64.0, -30.2, 1.5) == null, true);
            n += eq("mark far away", r.findNear("Mooshroom", 20.0, 64.0, -30.2, 1.5) == null, true);
            n += eq("mark removed", r.removeNear("Mooshroom", 12.3, 64.0, -30.2, 1.5) != null, true);
            n += eq("removal persisted", new IgnoreStore(tmp).size(), 0);
            java.nio.file.Files.deleteIfExists(tmp);
        } catch (Exception ex) {
            System.err.println("FAIL ignoreStore: " + ex);
            n++;
        }
        return n;
    }

    /** 0.9.13 held-map captcha: pure parsing, second guess, boss-bar identity, evidence net, slot diff, image cap. */
    private static int captcha() {
        int n = 0;
        // Map-prompt reply (bench 2026-09-03): the JSON array after ANSWER:, case kept.
        n += eq("answer array", ChatClassifier.parseAnswerArray("ANSWER: [\"p\",\"n\",\"G\",\"e\"]", true), "pnGe");
        n += eq("answer array lowercased", ChatClassifier.parseAnswerArray("ANSWER: [\"p\",\"n\",\"G\",\"e\"]", false), "pnge");
        n += eq("bare array", ChatClassifier.parseAnswerArray("[\"p\",\"n\",\"G\",\"e\"]", true), "pnGe");
        n += eq("array after prose", ChatClassifier.parseAnswerArray(
            "The letters are:\nANSWER: [\"D\", \"o\", \"m\"]\nALT: [\"D\",\"O\",\"m\"]", true), "Dom");
        n += eq("unquoted items", ChatClassifier.parseAnswerArray("ANSWER: [p, n, G, e]", true), "pnGe");
        n += eq("no array", ChatClassifier.parseAnswerArray("ANSWER: pnGe", true) == null, true);
        n += eq("empty array", ChatClassifier.parseAnswerArray("ANSWER: []", true) == null, true);
        n += eq("null reply", ChatClassifier.parseAnswerArray(null, true) == null, true);
        // Second guess: flip the first look-alike letter, else the first letter.
        n += eq("alt flips ambiguous", ChatClassifier.caseFlipAlt("abcd", "cosuvwxz"), "abCd");
        n += eq("alt flips first letter", ChatClassifier.caseFlipAlt("pnGe", "cosuvwxz"), "PnGe");
        n += eq("alt lowers", ChatClassifier.caseFlipAlt("SnGe", "cosuvwxz"), "snGe");
        n += eq("alt digits only", ChatClassifier.caseFlipAlt("1234", "cosuvwxz") == null, true);
        // Look-alike second guess (0.9.22): the 17:38 map read "pBb", the answer was "p8b".
        n += eq("lookalike B->8", ChatClassifier.lookalikeAlt("pBb", "B8,O0,S5,Z2,I1,l1,G6,b6,g9,q9", "cosuvwxz"), "p8b");
        n += eq("lookalike 8->B", ChatClassifier.lookalikeAlt("p8b", "B8,O0,S5,Z2,I1,l1,G6,b6,g9,q9", "cosuvwxz"), "pBb");
        n += eq("lookalike G->6", ChatClassifier.lookalikeAlt("pnGe", "B8,O0,S5,Z2,I1,l1,G6,b6,g9,q9", "cosuvwxz"), "pn6e");
        n += eq("lookalike none -> case flip", ChatClassifier.lookalikeAlt("aef", "B8,O0", "cosuvwxz"), "Aef");
        n += eq("lookalike null pairs", ChatClassifier.lookalikeAlt("abcd", null, "cosuvwxz"), "abCd");
        n += eq("lookalike empty", ChatClassifier.lookalikeAlt("", "B8", "c") == null, true);
        // Boss-bar identity without HP / timers (titles as logged in the 0.9.12 session).
        n += eq("bar key: mob hp", ChatClassifier.bossBarKey("[EPIC] LVL4 Pig ❤8.48M"), "[EPIC] LVL4 Pig");
        n += eq("bar key: timer colon", ChatClassifier.bossBarKey("2x Essence Event: 12m 10s"), "2x Essence Event");
        n += eq("bar key: timer parens", ChatClassifier.bossBarKey("Soul Harvest 2x Souls (12m, 9s)"), "Soul Harvest 2x Souls");
        n += eq("bar key: seconds only", ChatClassifier.bossBarKey("2x Essence Event: 59s"), "2x Essence Event");
        n += eq("bar key: plain", ChatClassifier.bossBarKey("[RARE] LVL6 Cow"), "[RARE] LVL6 Cow");
        // Raw chat net: 3/min, repeats dropped.
        RawChatNet net = new RawChatNet(3);
        n += eq("net admits", net.admit("a", 0), true);
        n += eq("net dedups", net.admit("a", 1000), false);
        n += eq("net admits b", net.admit("b", 1000), true);
        n += eq("net admits c", net.admit("c", 1000), true);
        n += eq("net caps", net.admit("d", 1000), false);
        n += eq("net new minute", net.admit("d", 61_000), true);
        n += eq("net off", new RawChatNet(0).admit("x", 0), false);
        // Map-slot diff: the first newly-mapped slot; hand slots always, hotbar only with anySlot.
        boolean[] known = new boolean[11];
        int[] cur = new int[11];
        java.util.Arrays.fill(cur, -1);
        n += eq("no maps", CaptchaDetector.newMapSlot(known, cur, null, true), -1);
        cur[8] = 42;
        n += eq("hotbar 9 new", CaptchaDetector.newMapSlot(known, cur, null, true), 8);
        n += eq("hotbar ignored without anySlot", CaptchaDetector.newMapSlot(known, cur, null, false), -1);
        cur[9] = 42;
        n += eq("held wins without anySlot", CaptchaDetector.newMapSlot(known, cur, null, false), 9);
        n += eq("muted id skipped", CaptchaDetector.newMapSlot(known, cur, 42, true), -1);
        known[8] = true;
        n += eq("known slot skipped", CaptchaDetector.newMapSlot(known, cur, null, true), 9);
        // Screenshot cap: 2000 px wide -> 1024 (model hallucinates above ~1024); small images untouched.
        try {
            java.awt.image.BufferedImage big = new java.awt.image.BufferedImage(2000, 1000, java.awt.image.BufferedImage.TYPE_INT_RGB);
            byte[] png = CaptchaImages.encodePng(big);
            n += eq("downscaled width", CaptchaImages.pngWidth(CaptchaImages.downscalePng(png, 1024)), 1024);
            n += eq("small untouched", CaptchaImages.downscalePng(png, 4096) == png, true);
            n += eq("map x2 nearest", CaptchaImages.scale(new java.awt.image.BufferedImage(128, 128, java.awt.image.BufferedImage.TYPE_INT_RGB), 256, false).getWidth(), 256);
        } catch (Exception ex) {
            System.err.println("FAIL images: " + ex);
            n++;
        }
        return n;
    }

    /**
     * 0.9.23, from events-baseline-2026-09-03T17-57-06: after the 4→5 rebirth the first
     * chicken's prediction (11502 ms) sat in every eval for two minutes ("ttkMs":11502,
     * "zoneGate":"closed") while zone_benchmark medians read 1206 → 301 ms, and the bot
     * bought 2.57M + 6.43M + 22.52M of swords on zone 1 with no zone probe at all.
     */
    private static int patience() {
        int n = 0;
        long t0 = 1_788_459_200_000L;
        // The stale prediction is dropped once it is older than the freshness window…
        n += eq("stale prediction dropped", Economy.freshPrediction(11_502.0, t0, t0 + 120_000, 4000) == null, true);
        // …a live one (refreshed every tick while cooking) survives…
        n += eq("fresh prediction kept", Economy.freshPrediction(11_502.0, t0, t0 + 900, 4000), 11_502.0, 1e-9);
        n += eq("never stamped -> none", Economy.freshPrediction(11_502.0, 0, t0, 4000) == null, true);
        n += eq("age check off", Economy.freshPrediction(11_502.0, t0, t0 + 120_000, 0), 11_502.0, 1e-9);
        // …and the gate then reads the zone-1 median, which opens it.
        Double eff = Economy.effectiveTtkMs(Economy.freshPrediction(11_502.0, t0, t0 + 120_000, 4000), 769.0);
        n += eq("zone 1 after rebirth: 769ms median opens the gate", Economy.zoneAllowed(eff, CFG.zoneMaxTtkMs), true);
        // With no median yet (first two kills of a stage) the live prediction still decides.
        n += eq("fresh stage: 25s prediction closes", Economy.zoneAllowed(
            Economy.effectiveTtkMs(Economy.freshPrediction(25_059.0, t0, t0 + 500, 4000), null), CFG.zoneMaxTtkMs), false);

        // Patience bounds: 10s base rolls between 6s and 16s; a disabled gate stays disabled;
        // swapped or broken multipliers collapse sanely.
        int[] b = Economy.zonePatienceBounds(10_000, CFG.zonePatienceMinMult, CFG.zonePatienceMaxMult);
        n += eq("patience lo", b[0], 6000);
        n += eq("patience hi", b[1], 16_000);
        int[] off = Economy.zonePatienceBounds(0, 0.6, 1.6);
        n += eq("patience disabled lo", off[0], 0);
        n += eq("patience disabled hi", off[1], 0);
        int[] swapped = Economy.zonePatienceBounds(10_000, 1.6, 0.6);
        n += eq("patience swapped lo", swapped[0], 6000);
        n += eq("patience swapped hi", swapped[1], 16_000);
        int[] fixed = Economy.zonePatienceBounds(10_000, 1.0, 1.0);
        n += eq("patience fixed line", fixed[0] == 10_000 && fixed[1] == 10_000, true);
        // The 03-36 sheep (7.2s) is inside the roll band: open for a patient stage, closed for an impatient one.
        n += eq("7.2s sheep vs 6s patience closed", Economy.zoneAllowed(7_202.0, 6000), false);
        n += eq("7.2s sheep vs 16s patience open", Economy.zoneAllowed(7_202.0, 16_000), true);

        // Stale rebirth floor: 155.44Q balance on a 900T floor from two rebirths ago => probe.
        n += eq("stale floor -> probe", Economy.rebirthFloorStale(900e12, 155.44e15, 0.0), true);
        n += eq("fresh floor -> no probe", Economy.rebirthFloorStale(900e12, 585.71e12, 0.0), false);
        n += eq("no floor -> unknown account path", Economy.rebirthFloorStale(null, 155.44e15, 0.0), false);
        n += eq("margin respected", Economy.rebirthFloorStale(900e12, 1000e12, 0.5), false);
        return n;
    }

    /**
     * 0.9.24, from events-baseline-2026-09-03T18-37-16 + latest.log 12:43:03 ("Unknown
     * amount suffix 'QQ'"): the stale-floor probe clicked the diamond, the server answered
     * with a QQ gap the parser could not scale, so no fail was recorded, the probe timed
     * out, the abort path re-typed /rebirth five times in 80s, and the fifth read a closed
     * GUI as a rebirth (economy_reset via upgrade-success at 2.66Q). The QQ line below is
     * reconstructed from Drew's report ("you need 20.x QQ"), not a verbatim capture.
     */
    private static int rebirthProbe() {
        int n = 0;
        Pattern need = loose(CFG.upgradeNeedAmountPattern);
        String qq = " You need $20.5QQ Money to Rebirth.";
        n += eq("QQ line is a fail shape", looseAll(CFG.upgradeFailPatterns).stream().anyMatch(p -> p.matcher(qq).find()), true);
        n += eq("QQ token extracted", ChatClassifier.needAmountToken(qq, need), "20.5QQ");
        n += eq("QQ gap parses now", ChatClassifier.needAmount(qq, need), 20.5e18, 1e9);
        n += eq("QQ line is rebirth", ChatClassifier.kindOf(qq, null), "rebirth");
        n += eq("token on a still-unknown suffix", ChatClassifier.needAmountToken(" You need $3.1QQQ Money to Rebirth.", need), "3.1QQQ");
        n += eq("no token on noise", ChatClassifier.needAmountToken("Zone Boss has been Defeated", need) == null, true);
        n += eq("verbatim T line still parses", ChatClassifier.needAmount(REBIRTH_NEED, need), 29.99e12, 1e6);

        // The probe never loops on an unanswered GUI; only a probe that never got there retries, once.
        n += eq("timeout: no retry", Economy.rebirthProbeRetryAllowed("rebirth-timeout", 0, 1), false);
        n += eq("no-signal: no retry", Economy.rebirthProbeRetryAllowed("no-signal", 0, 1), false);
        n += eq("gui-closed: no retry", Economy.rebirthProbeRetryAllowed("gui-closed", 0, 1), false);
        n += eq("no-gui: one retry", Economy.rebirthProbeRetryAllowed("no-gui", 0, 1), true);
        n += eq("no-gui: second retry refused", Economy.rebirthProbeRetryAllowed("no-gui", 1, 1), false);
        n += eq("no-diamond: retry", Economy.rebirthProbeRetryAllowed("no-diamond", 0, 1), true);
        n += eq("retries disabled", Economy.rebirthProbeRetryAllowed("no-gui", 0, 0), false);

        // A rebirth is confirmed only by the server's own signal after our send.
        long send = 1_788_461_000_000L;
        n += eq("signal after send confirms", Economy.rebirthConfirmed(send + 1200, send), true);
        n += eq("old signal does not", Economy.rebirthConfirmed(send - 60_000, send), false);
        n += eq("no signal does not", Economy.rebirthConfirmed(0, send), false);
        n += eq("no send does not", Economy.rebirthConfirmed(send + 1200, 0), false);
        return n;
    }

    /**
     * 0.9.25 self-healing suffixes. Numbers from the logs: the 18:43 crossing 903.74T → 1.1Q
     * (one poll apart, income ~1.27Q/min), the 2.66Q balance against the "$20.xQQ" rebirth
     * gap, the rebirth collapse 2.66Q → 0.00, and the 1.1Q → 903.74T purchase drop.
     */
    private static int suffixLearning() {
        int n = 0;
        Amounts.resetLearned();
        Amounts.configure(Map.of());
        int gap = 5000;
        double jump = 20.0;

        // The real crossing: the next rung, confirmed because T is.
        Amounts.Crossing c = Amounts.crossing("903.74T", 903.74e12, true, "1.1Q", 1000, gap, jump);
        n += eq("T->Q fit", c.reason(), "fit");
        n += eq("T->Q scale", c.learned() != null ? Double.valueOf(c.learned().scale) : null, 1e15, 1e6);
        n += eq("T->Q confirmed", c.learned() != null && c.learned().confirmed, true);
        n += eq("T->Q via", c.learned() != null ? c.learned().via : null, "crossing");
        n += eq("T->Q basis", c.learned() != null ? c.learned().basis : null, "T");
        n += eq("T->Q ratio", c.ratio(), 1.1e15 / 903.74e12, 1e-6);
        // The same shape on a label nobody has seen.
        c = Amounts.crossing("903.74QQ", 903.74e18, true, "1.1QQQ", 1000, gap, jump);
        n += eq("QQ->QQQ fit", c.reason(), "fit");
        n += eq("QQ->QQQ scale", c.learned() != null ? Double.valueOf(c.learned().scale) : null, 1e21, 1e12);
        // Not crossings: a rebirth collapse, a purchase drop, the same rung.
        n += eq("collapse to 0.00", Amounts.crossing("2.66Q", 2.66e15, true, "0.00", 1000, gap, jump).reason(), "no-suffix");
        n += eq("collapse to 12.5K", Amounts.crossing("2.66Q", 2.66e15, true, "12.5K", 1000, gap, jump).reason(), "out-of-band");
        n += eq("purchase drop", Amounts.crossing("1.1Q", 1.1e15, true, "903.74T", 1000, gap, jump).reason(), "out-of-band");
        n += eq("same suffix", Amounts.crossing("2.66Q", 2.66e15, true, "2.70Q", 1000, gap, jump).reason(), "same-suffix");
        // Guards: mantissa, two-rung skip, stale previous poll, no previous poll.
        n += eq("mantissa 1500", Amounts.crossing("903.74T", 903.74e12, true, "1500Q", 1000, gap, jump).reason(), "mantissa");
        n += eq("mantissa 0.5", Amounts.crossing("903.74T", 903.74e12, true, "0.5Q", 1000, gap, jump).reason(), "mantissa");
        n += eq("two rungs", Amounts.crossing("903.74T", 903.74e12, true, "25Q", 1000, gap, jump).reason(), "out-of-band");
        n += eq("stale prev", Amounts.crossing("903.74T", 903.74e12, true, "1.1Q", 60_000, gap, jump).reason(), "stale");
        n += eq("no prev", Amounts.crossing(null, null, true, "1.1Q", 1000, gap, jump).reason(), "no-prev");
        // Chained off a provisional basis: still a fit, still provisional.
        c = Amounts.crossing("903.74QQ", 903.74e18, false, "1.1QQQ", 1000, gap, jump);
        n += eq("chained fit", c.reason(), "fit");
        n += eq("chained not confirmed", c.learned() != null && !c.learned().confirmed, true);
        n += eq("chained via", c.learned() != null ? c.learned().via : null, "chained");

        // Rung guess from a fail line at a 2.66Q balance: the rung above QQ, provisional.
        Amounts.Learned g = Amounts.rungGuess("QQQ", "$20.5QQQ");
        n += eq("rung scale", Double.valueOf(g.scale), 1e21, 1e12);
        n += eq("rung basis", g.basis, "QQ");
        n += eq("rung provisional", g.confirmed, false);
        n += eq("rung via", g.via, "rung");
        n += eq("QQQ unknown before learn", Amounts.knownSuffix("QQQ"), false);
        n += eq("learn returns no old", Amounts.learn("QQQ", g) == null, true);
        n += eq("parse $20.5QQQ", Amounts.parse("$20.5QQQ"), 20.5e21, 1e12);
        n += eq("QQQ provisional", Amounts.provisional("QQQ"), true);
        n += eq("QQQ confidence", Amounts.confidence("QQQ"), "provisional");
        n += eq("QQQ not confirmed", Amounts.confirmed("QQQ"), false);
        n += eq("QQ still confirmed", Amounts.confirmed("QQ"), true);
        n += eq("format 20.5QQQ", Amounts.format(20.5e21), "20.5QQQ");
        n += eq("format QQ unchanged", Amounts.format(1.5e18), "1.5QQ");
        n += eq("highest is QQQ", Amounts.highestKnown().suffix(), "QQQ");
        // The balance later steps onto QQQ: the crossing confirms the guess (same scale).
        c = Amounts.crossing("999.5QQ", 999.5e18, true, "1.02QQQ", 1200, gap, jump);
        n += eq("confirm fit", c.reason(), "fit");
        Amounts.Learned old = Amounts.learn("QQQ", c.learned());
        n += eq("confirm: old was provisional", old != null && !old.confirmed, true);
        n += eq("confirm: same scale", old != null ? Double.valueOf(old.scale) : null, c.learned().scale, 1e9);
        n += eq("QQQ learned now", Amounts.confidence("QQQ"), "learned");

        // Correction: "Sx" named first (guessed one rung above QQQ = 1e24 here), but the
        // board later shows QQQ → Sx as 1e24 ... vs a guess made when only QQ was known (1e21).
        Amounts.resetLearned();
        Amounts.Learned sxGuess = Amounts.rungGuess("Sx", "$4.2Sx");
        n += eq("Sx guessed above QQ", Double.valueOf(sxGuess.scale), 1e21, 1e9);
        Amounts.learn("Sx", sxGuess);
        n += eq("Sx parses on the guess", Amounts.parse("1.1Sx"), 1.1e21, 1e9);
        c = Amounts.crossing("999.9QQ", 999.9e18, true, "1.05QQQ", 900, gap, jump);
        Amounts.learn("QQQ", c.learned());
        c = Amounts.crossing("980QQQ", 980e21, true, "1.1Sx", 900, gap, jump);
        n += eq("QQQ->Sx fit", c.reason(), "fit");
        n += eq("QQQ->Sx scale", c.learned() != null ? Double.valueOf(c.learned().scale) : null, 1e24, 1e15);
        old = Amounts.learn("Sx", c.learned());
        n += eq("correction detected", old != null && Math.abs(old.scale - c.learned().scale) > 1e12, true);
        n += eq("Sx learned", Amounts.confidence("Sx"), "learned");
        n += eq("Sx keeps the server spelling", Amounts.format(1.1e24), "1.1Sx");
        n += eq("QQQ label", Amounts.format(2.2e21), "2.2QQQ");
        n += eq("highest is Sx", Amounts.highestKnown().suffix(), "Sx");

        // Config wins over everything and is never provisional.
        Amounts.configure(Map.of("QQQ", 5e20));
        n += eq("config scale", Amounts.scaleFor("QQQ"), 5e20, 1e9);
        n += eq("config confidence", Amounts.confidence("QQQ"), "config");
        n += eq("config confirmed", Amounts.confirmed("QQQ"), true);
        Amounts.configure(Map.of());
        Amounts.resetLearned();
        n += eq("reset: QQQ unknown", Amounts.knownSuffix("QQQ"), false);
        n += eq("reset: highest QQ", Amounts.highestKnown().suffix(), "QQ");

        // Persistence round trip on a temp file.
        try {
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("ycbot-suffixes", ".json");
            java.nio.file.Files.deleteIfExists(tmp);
            SuffixStore s = new SuffixStore(tmp);
            n += eq("empty suffix store", s.all().isEmpty(), true);
            s.put("qqq", Amounts.rungGuess("QQQ", "$20.5QQQ"));
            Amounts.Learned sx = Amounts.crossing("980QQQ", 980e21, true, "1.1Sx", 900, gap, jump).learned();
            s.put("Sx", sx);
            SuffixStore r = new SuffixStore(tmp);
            n += eq("QQQ persisted", r.get("QQQ") != null, true);
            n += eq("QQQ scale", r.get("qqq") != null ? Double.valueOf(r.get("qqq").scale) : null, 1e21, 1e12);
            n += eq("QQQ provisional", r.get("QQQ") != null && !r.get("QQQ").confirmed, true);
            n += eq("QQQ via", r.get("QQQ") != null ? r.get("QQQ").via : null, "rung");
            n += eq("Sx confirmed", r.get("SX") != null && r.get("SX").confirmed, true);
            n += eq("Sx basis", r.get("SX") != null ? r.get("SX").basis : null, "QQQ");
            n += eq("Sx raw", r.get("SX") != null ? r.get("SX").raw : null, "1.1Sx");
            r.remove("QQQ");
            SuffixStore r2 = new SuffixStore(tmp);
            n += eq("QQQ removed", r2.get("QQQ") == null, true);
            n += eq("Sx survives", r2.get("Sx") != null, true);
            java.nio.file.Files.deleteIfExists(tmp);
        } catch (Exception ex) {
            System.err.println("FAIL suffixStore: " + ex);
            n++;
        }
        Amounts.resetLearned();
        return n;
    }

    /**
     * 0.9.26 keystrokes: the old rule advanced past the intended character on a typo, so
     * "/zone max" with a slip at the 'o' ended as "/zne max" in the field (19:43 log) while
     * the original string was sent. The pure step keeps the index until the slip is fixed.
     */
    private static int typer() {
        int n = 0;
        String cmd = "/zone max";
        ChatTyper.Keys k = ChatTyper.Keys.start();
        k = ChatTyper.step(k, cmd, false, 'x');           // '/'
        k = ChatTyper.step(k, cmd, false, 'x');           // 'z'
        n += eq("two chars", k.typed(), "/z");
        k = ChatTyper.step(k, cmd, true, 'n');            // slip: 'n' instead of 'o'
        n += eq("slip shows", k.typed(), "/zn");
        n += eq("slip pending", k.typoAt(), 2);
        n += eq("index held", k.next(), 2);
        k = ChatTyper.step(k, cmd, false, 'x');           // backspace
        n += eq("backspaced", k.typed(), "/z");
        n += eq("slip cleared", k.typoAt(), -1);
        k = ChatTyper.step(k, cmd, false, 'x');           // the intended 'o'
        n += eq("intended char typed", k.typed(), "/zo");
        while (!ChatTyper.done(k, cmd)) k = ChatTyper.step(k, cmd, false, 'x');
        n += eq("field equals the command", k.typed(), cmd);
        // Two slips, one right at the end: still exact.
        k = ChatTyper.Keys.start();
        int i = 0;
        java.util.Set<Integer> slipped = new java.util.HashSet<>();
        while (!ChatTyper.done(k, cmd)) {
            boolean slip = k.typoAt() < 0 && (k.next() == 3 || k.next() == cmd.length() - 1) && slipped.add(k.next());
            k = ChatTyper.step(k, cmd, slip, 'q');
            if (++i > 100) break;
        }
        n += eq("two slips happened", slipped.size(), 2);
        n += eq("two slips, exact", k.typed(), cmd);
        n += eq("no slip path", ChatTyper.step(ChatTyper.Keys.start(), "ab", false, 'x').typed(), "a");
        n += eq("past the end is a no-op", ChatTyper.step(new ChatTyper.Keys("ab", 2, -1), "ab", false, 'x').typed(), "ab");
        n += eq("done", ChatTyper.done(new ChatTyper.Keys("ab", 2, -1), "ab"), true);
        n += eq("not done with a slip pending", ChatTyper.done(new ChatTyper.Keys("abq", 2, 2), "ab"), false);
        return n;
    }

    /**
     * 0.9.26 ballot, from the bench on the certified captures (greedy readings per render):
     * KrA live PNG x4 Kra / x3 KrA / x2 KrA; KrA fixture KrA / KrA / Kra; p8b p8b / p8b / pBb;
     * pnGe all pnGe. The leader is right on all four; the old primary-render rule was wrong
     * on the first.
     */
    /**
     * 0.9.34 hedged reads and the health split. Evidence: 2026-09-04 16:07:13, one
     * transient HTTP 503 on the QwenCloud /v1/models probe marked the reader offline for
     * 97 s, and the pre-flight gate would have handed the bot over without capturing the
     * map — on a route a solve never calls. A 503 is now DEGRADED, not death, and reads
     * are hedged so no single call can sink a captcha.
     */
    private static int captchaHedge0934() {
        int n = 0;

        // --- health classification: the probe must agree with the solve path ---
        n += eq("200 is online", CaptchaSolver.classify(200, null), CaptchaSolver.Reach.ONLINE);
        // The 16:07:13 line itself: answered, so reachable.
        n += eq("503 is degraded, not dead", CaptchaSolver.classify(503, null), CaptchaSolver.Reach.DEGRADED);
        n += eq("500 is degraded", CaptchaSolver.classify(500, null), CaptchaSolver.Reach.DEGRADED);
        n += eq("429 is degraded", CaptchaSolver.classify(429, null), CaptchaSolver.Reach.DEGRADED);
        n += eq("401 is degraded", CaptchaSolver.classify(401, null), CaptchaSolver.Reach.DEGRADED);
        n += eq("connect refused is unreachable",
            CaptchaSolver.classify(0, new java.net.ConnectException("Connection refused")),
            CaptchaSolver.Reach.UNREACHABLE);
        n += eq("connect timeout is unreachable",
            CaptchaSolver.classify(0, new java.net.http.HttpConnectTimeoutException("timed out")),
            CaptchaSolver.Reach.UNREACHABLE);
        // The pre-0.9.32 local-vLLM failure, which SHOULD still read as a dead port.
        n += eq("closed channel is unreachable",
            CaptchaSolver.classify(0, new java.io.IOException(new java.nio.channels.ClosedChannelException())),
            CaptchaSolver.Reach.UNREACHABLE);
        // A read timeout means it answered the connect: reachable but slow.
        n += eq("read timeout is degraded",
            CaptchaSolver.classify(0, new java.net.http.HttpTimeoutException("request timed out")),
            CaptchaSolver.Reach.DEGRADED);

        // --- hedge schedule ---
        long budget = 25_000, perRead = 8_000, tail = 2_500 + CaptchaSolver.TYPING_ESTIMATE_MS;
        // Read A always goes, immediately.
        n += eq("read A fires at once",
            CaptchaSolver.shouldHedge(0, budget, 0, 3, 0, 0, perRead, tail), true);
        // Read B goes without waiting to learn whether A failed — the whole point.
        n += eq("read B fires with A still in flight",
            CaptchaSolver.shouldHedge(4_300, budget, 1, 3, 0, 0, perRead, tail), true);
        // Read C only earns its place on a failure or a split ballot.
        n += eq("no read C when A and B agree",
            CaptchaSolver.shouldHedge(7_300, budget, 2, 3, 1, 0, perRead, tail), false);
        n += eq("read C on a failure",
            CaptchaSolver.shouldHedge(7_300, budget, 2, 3, 1, 1, perRead, tail), true);
        n += eq("read C on a split ballot",
            CaptchaSolver.shouldHedge(7_300, budget, 2, 3, 2, 0, perRead, tail), true);
        n += eq("stops at captchaHedgeMax",
            CaptchaSolver.shouldHedge(7_300, budget, 3, 3, 2, 1, perRead, tail), false);

        // --- the budget guard: never start a read that cannot finish and still be typed ---
        // 8s read + 2.5s answer delay + 2.5s typing = 13s of tail; past 12s there is no room.
        n += eq("room at 11s", CaptchaSolver.shouldHedge(11_000, budget, 1, 3, 0, 1, perRead, tail), true);
        n += eq("no room at 13s", CaptchaSolver.shouldHedge(13_000, budget, 1, 3, 0, 1, perRead, tail), false);
        n += eq("no room at 20s", CaptchaSolver.shouldHedge(20_000, budget, 1, 3, 1, 1, perRead, tail), false);
        // Read A is exempt: it is the only chance there is, so it goes out regardless.
        n += eq("read A ignores the budget guard",
            CaptchaSolver.shouldHedge(20_000, budget, 0, 3, 0, 0, perRead, tail), true);
        // A one-hedge config degenerates to the old single-read behaviour.
        n += eq("hedgeMax 1 fires once",
            CaptchaSolver.shouldHedge(0, budget, 0, 1, 0, 0, perRead, tail), true);
        n += eq("hedgeMax 1 never twice",
            CaptchaSolver.shouldHedge(3_000, budget, 1, 1, 0, 1, perRead, tail), false);

        // --- two hedges must be able to satisfy the send rule (v38 migrated 3 -> 2) ---
        YCBotChallengeConfig c = new YCBotChallengeConfig();
        n += eq("minReads reachable by the hedge schedule",
            c.captchaVoteMinReads <= c.captchaHedgeMax, true);
        n += eq("timeout leaves room for a hedge",
            c.captchaTimeoutMs + c.captchaHedgeMs < c.captchaBudgetMs, true);
        n += eq("budget leaves the human room in a ~30s window", c.captchaBudgetMs <= 25_000, true);
        // The hedge must land inside the reading pause, or it is not free.
        n += eq("hedge hides inside the answer delay",
            c.captchaHedgeMs <= c.captchaAnswerDelayMaxMs, true);

        // --- the v38 migration moves a 0.9.33 config onto the hedged defaults ---
        try {
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("ycbot-cfg34", ".json");
            java.nio.file.Files.writeString(tmp,
                "{\"configVersion\":37,\"captchaTimeoutMs\":20000,\"captchaVoteMinReads\":3}");
            YCBotChallengeConfig c37 = YCBotChallengeConfig.load(tmp);
            n += eq("v37 migrates to current", c37.configVersion, YCBotChallengeConfig.CURRENT_CONFIG_VERSION);
            n += eq("v38 takes the 8s read timeout", c37.captchaTimeoutMs, 8000);
            n += eq("v38 drops minReads to the hedge count", c37.captchaVoteMinReads, 2);
            n += eq("v38 gains the budget", c37.captchaBudgetMs, 25_000);
            n += eq("v38 gains the hedge stagger", c37.captchaHedgeMs, 3000);
            java.nio.file.Files.deleteIfExists(tmp);
        } catch (Exception ex) {
            System.err.println("FAIL v38 migration: " + ex);
            n++;
        }
        // A hand-set timeout is the user's choice and must survive the migration.
        try {
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("ycbot-cfg34b", ".json");
            java.nio.file.Files.writeString(tmp, "{\"configVersion\":37,\"captchaTimeoutMs\":15000}");
            YCBotChallengeConfig hand = YCBotChallengeConfig.load(tmp);
            n += eq("hand-set timeout survives v38", hand.captchaTimeoutMs, 15000);
            java.nio.file.Files.deleteIfExists(tmp);
        } catch (Exception ex) {
            System.err.println("FAIL v38 hand-set timeout: " + ex);
            n++;
        }

        // --- a split ballot still ranks the alternative for the rejection (0.9.26 KrA) ---
        CaptchaBallot h = new CaptchaBallot();
        h.cast("Kra", "x1", 0.0);   // read A
        h.cast("KrA", "x1", 0.0);   // read B disagrees
        n += eq("hedges disagreeing keeps both", h.distinct(), 2);
        n += eq("rejection has an alternative ready", h.ranked(List.of("Kra")), List.of("KrA"));
        return n;
    }

    private static int ballot() {
        int n = 0;
        CaptchaBallot b = new CaptchaBallot();
        n += eq("empty leader", b.leader(List.of()) == null, true);
        n += eq("empty reads", b.reads(), 0);
        b.cast("Kra", "x4bil", 0.0);
        n += eq("first read leads", b.leader(List.of()), "Kra");
        b.cast("KrA", "x3bil", 0.0);
        n += eq("tie keeps first-seen", b.leader(List.of()), "Kra");
        b.cast("KrA", "x2near", 0.0);
        n += eq("live case: KrA wins 2-1", b.leader(List.of()), "KrA");
        n += eq("ranked", b.ranked(List.of()), List.of("KrA", "Kra"));
        n += eq("reads", b.reads(), 3);
        n += eq("distinct", b.distinct(), 2);
        n += eq("render of KrA", b.renderOf("KrA"), "x3bil");
        n += eq("tallies", b.tallies().get("KrA"), 2);
        n += eq("rejected leader excluded", b.leader(List.of("KrA")), "Kra");
        n += eq("all excluded", b.leader(List.of("KrA", "Kra")) == null, true);
        b.cast(null, "x5bil", 0.0);
        b.cast("", "x5bil", 0.0);
        n += eq("nulls skipped", b.reads(), 3);

        CaptchaBallot f = new CaptchaBallot();
        f.cast("KrA", "x4bil", 0.0); f.cast("KrA", "x3bil", 0.0); f.cast("Kra", "x2near", 0.0);
        n += eq("fixture case", f.leader(List.of()), "KrA");
        CaptchaBallot p = new CaptchaBallot();
        p.cast("p8b", "x4bil", 0.0); p.cast("p8b", "x3bil", 0.0); p.cast("pBb", "x2near", 0.0);
        n += eq("p8b case", p.leader(List.of()), "p8b");
        n += eq("p8b runner-up", p.ranked(List.of("p8b")), List.of("pBb"));
        CaptchaBallot g = new CaptchaBallot();
        g.cast("pnGe", "x4bil", 0.0); g.cast("pnGe", "x3bil", 0.0); g.cast("pnGe", "x2near", 0.0);
        n += eq("all agree", g.distinct(), 1);
        n += eq("all agree leader", g.leader(List.of()), "pnGe");
        g.clear();
        n += eq("cleared", g.reads(), 0);
        return n;
    }

    /**
     * 0.9.27 stay-in-your-zone, from the 20:35 log: plates "LVL7 Donkey ❤69B" (no rarity —
     * the common shape, which the old regex did not parse at all), "[RARE] LVL9 Mooshroom
     * ❤2.3B", "[AFKMOB] LVL9 Mooshroom ❤∞"; a Chicken (level 1) picked in zone 7.
     */
    private static int zoneLevel() {
        int n = 0;
        CombatController.Plate p = CombatController.parsePlate("LVL7 Donkey ❤69B");
        n += eq("plain plate parses", p != null, true);
        n += eq("plain plate level", p != null ? p.level() : null, 7);
        n += eq("plain plate mob", p != null ? p.mob() : null, "Donkey");
        n += eq("plain plate no rarity", p != null && p.rarity() == null, true);
        p = CombatController.parsePlate("[RARE] LVL9 Mooshroom ❤2.3B");
        n += eq("tagged plate rarity", p != null ? p.rarity() : null, "RARE");
        n += eq("tagged plate level", p != null ? p.level() : null, 9);
        n += eq("tagged plate mob", p != null ? p.mob() : null, "Mooshroom");
        p = CombatController.parsePlate("[AFKMOB] LVL9 Mooshroom ❤∞");
        n += eq("afk plate rarity", p != null ? p.rarity() : null, "AFKMOB");
        n += eq("afk plate level", p != null ? p.level() : null, 9);
        p = CombatController.parsePlate("[LEGENDARY] Chicken");
        n += eq("no level", p != null && p.level() == null, true);
        n += eq("no level mob", p != null ? p.mob() : null, "Chicken");
        p = CombatController.parsePlate("LVL1 Chicken ❤116");
        n += eq("chicken level 1", p != null ? p.level() : null, 1);
        n += eq("upgrade line has no level", CombatController.parsePlate("RIGHT CLICK TO UPGRADE").level() == null, true);
        n += eq("null line", CombatController.parsePlate(null) == null, true);

        n += eq("chicken in zone 7 rejected", Economy.sameZoneLevel(1, 7), false);
        n += eq("donkey in zone 7 ok", Economy.sameZoneLevel(7, 7), true);
        n += eq("unknown plate level: no opinion", Economy.sameZoneLevel(null, 7), true);
        n += eq("unknown zone level: no opinion", Economy.sameZoneLevel(7, null), true);
        n += eq("zone level of lvl10", Economy.zoneLevelOf("lvl10"), 10);
        n += eq("zone level of LVL7", Economy.zoneLevelOf("LVL7"), 7);
        n += eq("zone level of null", Economy.zoneLevelOf(null) == null, true);
        n += eq("zone level of junk", Economy.zoneLevelOf("Dungeons") == null, true);
        return n;
    }

    /** 0.9.28 companions: fixtures verbatim from Drew's screenshots (2026-09-03). */
    private static int companions() {
        int n = 0;
        CompanionLore cl = new CompanionLore(CFG);
        List<String> egg = List.of("Western Companion Egg", "Unhatch a Dungeons Companion that boosts",
            "the amount of money you gain!", "| Price: $121.3300 Money", "<< Right Click to view. >>");
        List<String> credit = List.of("Western Credit Egg", "Unhatch a Dungeons Companion that boosts",
            "the amount of money you gain!.", "| Price: 100 Credits", "<< Right Click to view. >>");
        n += eq("money egg hologram", cl.isEggHologram(egg), true);
        n += eq("credit egg excluded", cl.isEggHologram(credit), false);
        n += eq("prefix is not matched", cl.isEggHologram(List.of("Farm Companion Egg", "| Price: $5.10 Money")), true);
        n += eq("egg price", cl.eggPrice(egg), 121.33, 1e-6);
        n += eq("plain mob plate is no egg", cl.isEggHologram(List.of("LVL7 Donkey ❤69B")), false);

        List<String> openLore = List.of("Pressing this will open up 3x Companion Egg, all",
            "companions will go directly to your companion storage.", "| Price: 363.9800 Money",
            "| Discounted Openings Left: 0x", "[CLICK THIS TO OPEN 3x COMPANION EGG]");
        CompanionLore.OpenOption o3 = cl.openOption(36, "OPEN: [3x COMPANION EGG]", openLore);
        n += eq("open option parses", o3 != null, true);
        n += eq("open count", o3 != null ? o3.count() : null, 3);
        n += eq("open price", o3 != null ? o3.price() : null, 363.98, 1e-6);
        n += eq("open by lore only", cl.openOption(37, "Egg", List.of("open: [10x companion egg]", "| Price: 1213.3 Money")) != null, true);
        n += eq("filler is no option", cl.openOption(1, "Gray Glass", List.of()) == null, true);

        List<String> cow = List.of("COMPANION", "Information:", "| Rarity: Rare (NORMAL)", "| Multiplier: 156.38x Money",
            "[ZONE 1 STAGE 10]", "<< Click Here to un-equip your Cow Companion. >>");
        CompanionLore.Companion c = cl.companion(1, "Cow Companion", cow);
        n += eq("companion parses", c != null, true);
        n += eq("companion zone", c != null ? c.zone() : null, 1);
        n += eq("companion stage", c != null ? c.stage() : null, 10);
        n += eq("companion multiplier", c != null ? c.multiplier() : null, 156.38, 1e-6);
        n += eq("companion rarity", c != null ? c.rarity() : null, "Rare");
        n += eq("equip best by name", cl.isEquipBest("Equip Best", List.of("Equip Companions")), true);
        n += eq("equip best is no companion", cl.companion(4, "Equip Best", List.of("Equip Companions",
            "The new Equip Best option will automatically equip your companions with the highest multiplier.")) == null, true);
        n += eq("fuse by name", cl.isFuse("Fuse Companions", List.of()), true);
        n += eq("eggs title", cl.isEggsTitle("Companion Eggs"), true);
        n += eq("companions title", cl.isCompanionsTitle("Companions"), true);
        n += eq("companions title is not eggs", cl.isEggsTitle("Companions"), false);
        n += eq("fuse title", cl.isFuseTitle("Fuse Companions"), true);
        n += eq("fuse title is not companions", cl.isCompanionsTitle("Fuse Companions"), false);

        // Pick the open: largest that fits the eggs left, the money budget the decision approved
        // (0.9.35 - it used to be minutes of income, the same knob that gated the trigger) and
        // the balance cap.
        List<CompanionLore.OpenOption> opts = List.of(
            new CompanionLore.OpenOption(36, "1x", 1, 121.33), new CompanionLore.OpenOption(37, "3x", 3, 363.98),
            new CompanionLore.OpenOption(38, "10x", 10, 1213.3));
        CompanionLore.OpenOption p = CompanionLore.pickOpen(opts, 7, 200.0, 100_000.0, 40);
        n += eq("tight income -> 1x", p != null ? p.count() : null, 1);
        p = CompanionLore.pickOpen(opts, 7, 2000.0, 100_000.0, 40);
        n += eq("room for 3x, 10x over eggs left", p != null ? p.count() : null, 3);
        p = CompanionLore.pickOpen(opts, 10, 2000.0, 100_000.0, 40);
        n += eq("10x fits", p != null ? p.count() : null, 10);
        p = CompanionLore.pickOpen(opts, 10, 2000.0, 500.0, 40);
        n += eq("balance cap -> 1x", p != null ? p.count() : null, 1);
        n += eq("no budget -> nothing", CompanionLore.pickOpen(opts, 7, null, 100_000.0, 40) == null, true);
        n += eq("no eggs left -> nothing", CompanionLore.pickOpen(opts, 0, 2000.0, 100_000.0, 40) == null, true);
        n += eq("too poor -> nothing", CompanionLore.pickOpen(opts, 7, 20.0, 100_000.0, 40) == null, true);
        n += eq("income minutes", CompanionLore.incomeMinutes(363.98, 100.0), 3.6398, 1e-6);
        n += eq("income minutes unknown", CompanionLore.incomeMinutes(363.98, null) == null, true);

        // Sliding-window delete: keep the current and previous zone, never an equipped pair.
        List<CompanionLore.ZoneStage> st = List.of(zs(1, 8), zs(1, 9), zs(1, 9), zs(2, 3), zs(3, 1), zs(3, 5));
        List<CompanionLore.ZoneStage> del = CompanionLore.deletePairs(st, List.of(zs(1, 9)), 3, 2);
        n += eq("window of 2 deletes zone 1 only", del, List.of(zs(1, 8)));
        n += eq("window of 1 deletes zones 1-2", CompanionLore.deletePairs(st, List.of(), 3, 1), List.of(zs(1, 8), zs(1, 9), zs(2, 3)));
        n += eq("unknown zone deletes nothing", CompanionLore.deletePairs(st, List.of(), null, 2).isEmpty(), true);
        n += eq("current zone 1 deletes nothing", CompanionLore.deletePairs(st, List.of(), 1, 2).isEmpty(), true);
        n += eq("bulk delete command", CompanionLore.bulkDeleteCommand(CFG.companionBulkDeleteCommand, zs(1, 8)), "/companion bulkdelete 1 8");
        return n;
    }

    private static CompanionLore.ZoneStage zs(int z, int s) { return new CompanionLore.ZoneStage(z, s); }

    /** 0.9.28 Transcend: lines verbatim from the 20:35 log. */
    private static int transcend() {
        int n = 0;
        Pattern cd = RebirthLore.compileLoose(CFG.transcendCooldownPattern);
        n += eq("cooldown 180", TranscendController.cooldownSecondsOf("Your Transcend Ability has been activated (180s Cooldown)", cd), 180);
        n += eq("cooldown on end line", TranscendController.cooldownSecondsOf("Your Transcend Ability has ended (180s Cooldown)", cd), 180);
        n += eq("no cooldown", TranscendController.cooldownSecondsOf("Your Transcend Ability has ended", cd) == null, true);
        n += eq("active line", RebirthLore.compileLoose(CFG.transcendActivePattern).matcher("Your Transcend Ability has been activated (180s Cooldown)").find(), true);
        n += eq("end line", RebirthLore.compileLoose(CFG.transcendEndPattern).matcher("Your Transcend Ability has ended (180s Cooldown)").find(), true);
        long t0 = 1_788_480_000_000L;
        n += eq("never pressed: ready after first delay", TranscendController.ready(0, 0, 180_000, t0 + 30_000, t0 + 31_000), true);
        n += eq("not before first delay", TranscendController.ready(0, 0, 180_000, t0 + 30_000, t0 + 1000), false);
        n += eq("cooling", TranscendController.ready(t0, 0, 180_000, 0, t0 + 100_000), false);
        n += eq("cooled", TranscendController.ready(t0, 0, 180_000, 0, t0 + 181_000), true);
        n += eq("our press counts too", TranscendController.ready(0, t0, 180_000, 0, t0 + 100_000), false);
        n += eq("hazard zero at ready", Economy.visitHazard(0, 0, 90_000, 0.3, 1.0, 1.0), 0.0, 1e-9);
        n += eq("hazard full after ramp", Economy.visitHazard(90_000, 0, 90_000, 0.3, 1.0, 1.0), 0.3, 1e-9);
        return n;
    }

    /** 0.9.29: no typed upgrade before the first kills (00:19 log: sends 5 s after enable with zero kills); egg store. */
    private static int firstKills() {
        int n = 0;
        n += eq("fresh enable, no kills", Economy.firstKillsReached(0, 0, 1), false);
        n += eq("kills before but none since the rebirth", Economy.firstKillsReached(5, 0, 1), false);
        n += eq("one kill both ways", Economy.firstKillsReached(1, 1, 1), true);
        n += eq("needs three, has two", Economy.firstKillsReached(2, 2, 3), false);
        n += eq("needs three, has three", Economy.firstKillsReached(3, 7, 3), true);
        n += eq("needed 0 is off", Economy.firstKillsReached(0, 0, 0), true);
        try {
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("ycbot-eggs", ".json");
            java.nio.file.Files.deleteIfExists(tmp);
            EggStore s = new EggStore(tmp);
            n += eq("empty egg store", s.size(), 0);
            EggStore.Egg e = new EggStore.Egg();
            e.x = 12.5; e.y = 64.5; e.z = -30.5; e.label = "minecraft:dragon_egg"; e.at = 1_788_480_000_000L;
            s.put("lvl12", e);
            EggStore r = new EggStore(tmp);
            n += eq("egg persisted", r.get("LVL12") != null, true);
            n += eq("egg x", r.get("lvl12") != null ? Double.valueOf(r.get("lvl12").x) : null, 12.5, 1e-9);
            n += eq("egg label", r.get("lvl12") != null ? r.get("lvl12").label : null, "minecraft:dragon_egg");
            n += eq("other location missing", r.get("lvl3") == null, true);
            EggStore.Egg e2 = new EggStore.Egg();
            e2.x = 1; e2.y = 2; e2.z = 3;
            r.put("lvl12", e2);
            n += eq("overwrite", Double.valueOf(new EggStore(tmp).get("lvl12").x), 1.0, 1e-9);
            n += eq("null stage keys as unknown", EggStore.key(null), "unknown");
            java.nio.file.Files.deleteIfExists(tmp);
        } catch (Exception ex) {
            System.err.println("FAIL eggStore: " + ex);
            n++;
        }
        return n;
    }

    /**
     * 0.9.30 log audit: the sword gain follows the TTK (kill medians around sword buys:
     * 1.3x at the floor, 2–8x on long kills), Transcend activations without our press are
     * the server's (37 at 190s spacing, bot off), and a zero-points /rebirth read is not
     * repeated until the rebirth counter moves (12 empty visits across two logs).
     */
    private static int audit0930() {
        int n = 0;
        n += eq("sword gain at the floor", Economy.swordGain(1000.0, 2.0, 2000, 1.25), 1.25, 1e-9);
        n += eq("sword gain 5s", Economy.swordGain(5000.0, 2.0, 2000, 1.25), 7000.0 / 4500.0, 1e-9);
        n += eq("sword gain 8s", Economy.swordGain(8000.0, 2.0, 2000, 1.25), 10000.0 / 6000.0, 1e-9);
        n += eq("sword gain 20s", Economy.swordGain(20000.0, 2.0, 2000, 1.25), 22000.0 / 12000.0, 1e-9);
        n += eq("sword gain 60s", Economy.swordGain(60000.0, 2.0, 2000, 1.25), 62000.0 / 32000.0, 1e-9);
        n += eq("unknown ttk → floor", Economy.swordGain(null, 2.0, 2000, 1.25), 1.25, 1e-9);
        n += eq("dps mult 1 → floor", Economy.swordGain(20000.0, 1.0, 2000, 1.25), 1.25, 1e-9);
        n += eq("no movement floor, mult 2 → 2", Economy.swordGain(6000.0, 2.0, 0, 1.25), 2.0, 1e-9);
        // 00:19 log, 1744 s: 132S sword, 156S balance, 656S rebirth, ~17S/min, 5–8s kills.
        double g = Economy.swordGain(6000.0, 2.0, 2000, 1.25);
        n += eq("132S sword at a 6s ttk passes the horizon", Economy.rebirthHorizonAllows(132e21, 156e21, 656e21, 17e21, g), true);
        n += eq("the same sword was refused at the fixed 1.25", Economy.rebirthHorizonAllows(132e21, 156e21, 656e21, 17e21, 1.25), false);
        long t0 = 1_788_480_000_000L;
        n += eq("activation with no press is the server's", Economy.transcendServerDriven(t0, 0, 2000), true);
        n += eq("activation 1s after our press is ours", Economy.transcendServerDriven(t0 + 1000, t0, 2000), false);
        n += eq("activation 190s after our press is the server's", Economy.transcendServerDriven(t0 + 190_000, t0, 2000), true);
        try {
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("ycbot-state30", ".json");
            java.nio.file.Files.deleteIfExists(tmp);
            StateStore s = new StateStore(tmp);
            StateStore.Entry e = new StateStore.Entry();
            e.rebirths = 8;
            e.pointsCheckedAtRebirths = 8;
            s.put("Ihazekids69420", e);
            StateStore.Entry alt = new StateStore.Entry();
            alt.rebirths = 2;
            s.put("AltAccount", alt);
            StateStore r = new StateStore(tmp);
            n += eq("points check persisted", r.get("ihazekids69420").pointsCheckedAtRebirths, 8);
            n += eq("alt never checked", r.get("altaccount").pointsCheckedAtRebirths == null, true);
            java.nio.file.Files.deleteIfExists(tmp);
        } catch (Exception ex) {
            System.err.println("FAIL stateStore 0.9.30: " + ex);
            n++;
        }
        return n;
    }

    /**
     * 0.9.31 companions: the Companion Eggs GUI (verbatim, 03:34 log) teaches SS = 1000 × S
     * by its count ratio (250 × 6.34S printed as 1.58SS — the null price crashed the client
     * twice), and the egg store is keyed by location (Farm 1–10, Western 11–20).
     */
    private static int companions0931() {
        int n = 0;
        // The ladder must know S for the lesson to have a basis (it is learned live, not built in).
        Amounts.Learned s = new Amounts.Learned();
        s.scale = 1e21; s.confirmed = true; s.via = "crossing"; s.basis = "QQ"; s.raw = "1.1S"; s.prevRaw = "980QQ"; s.at = 1;
        Amounts.learn("S", s);
        try {
            List<CompanionLore.OpenOption> opts = List.of(
                new CompanionLore.OpenOption(38, "open: [1x companion egg]", 1, 6.34e21, "6.34S"),
                new CompanionLore.OpenOption(39, "open: [3x companion egg]", 3, 19.02e21, "19.02S"),
                new CompanionLore.OpenOption(40, "open: [10x companion egg]", 10, 63.39e21, "63.39S"),
                new CompanionLore.OpenOption(41, "open: [50x companion egg]", 50, 316.96e21, "316.96S"),
                new CompanionLore.OpenOption(42, "open: [250x companion egg]", 250, null, "1.58SS"));
            CompanionLore.RungLesson lesson = CompanionLore.rungFromOptions(opts);
            n += eq("SS lesson found", lesson != null, true);
            if (lesson != null) {
                n += eq("SS suffix", lesson.suffix(), "SS");
                n += eq("SS scale", lesson.learned().scale, 1e24, 1e12);
                n += eq("SS confirmed", lesson.learned().confirmed, true);
                n += eq("SS basis", lesson.learned().basis, "S");
                n += eq("lesson count", lesson.count(), 250);
            }
            n += eq("pickOpen skips the unparsed option",
                CompanionLore.pickOpen(opts, 250, 1e25, 1e25, 100).count(), 50);
            List<CompanionLore.OpenOption> wrong = List.of(
                new CompanionLore.OpenOption(38, "open: [1x companion egg]", 1, 2e21, "2S"),
                new CompanionLore.OpenOption(42, "open: [250x companion egg]", 250, null, "1.58SS"));
            n += eq("mantissa that does not fit the rung → nothing learned", CompanionLore.rungFromOptions(wrong) == null, true);
            n += eq("nothing unparsed → nothing learned", CompanionLore.rungFromOptions(opts.subList(0, 4)) == null, true);
            n += eq("no parsed unit → nothing learned", CompanionLore.rungFromOptions(List.of(opts.get(4))) == null, true);
        } finally {
            Amounts.forget("S");
        }
        n += eq("lvl12 → loc2", EggStore.key("lvl12"), "loc2");
        n += eq("lvl10 → loc1", EggStore.key("lvl10"), "loc1");
        n += eq("lvl11 → loc2", EggStore.key("lvl11"), "loc2");
        n += eq("lvl1 → loc1", EggStore.key("lvl1"), "loc1");
        n += eq("lvl21 → loc3", EggStore.key("lvl21"), "loc3");
        n += eq("LVL 12 spaced → loc2", EggStore.key("LVL 12"), "loc2");
        n += eq("other label kept", EggStore.key("Hub"), "hub");
        n += eq("location of 20 at 10 per", EggStore.locationOf(20, 10), 2);
        n += eq("location of 21 at 10 per", EggStore.locationOf(21, 10), 3);
        try {
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("ycbot-eggs31", ".json");
            java.nio.file.Files.writeString(tmp, "{\"lvl12\": {\"x\": 314.5, \"y\": 67.75, \"z\": -41.5, \"label\": \"minecraft:dragon_egg\", \"at\": 5},"
                + " \"lvl11\": {\"x\": 1, \"y\": 2, \"z\": 3, \"label\": \"old\", \"at\": 1}}");
            EggStore legacy = new EggStore(tmp);
            n += eq("legacy per-stage entries fold into the location", legacy.size(), 1);
            n += eq("newest legacy entry wins", legacy.get("lvl15") != null ? Double.valueOf(legacy.get("lvl15").x) : null, 314.5, 1e-9);
            n += eq("location 1 empty", legacy.get("lvl3") == null, true);
            java.nio.file.Files.deleteIfExists(tmp);
        } catch (Exception ex) {
            System.err.println("FAIL eggStore 0.9.31: " + ex);
            n++;
        }
        return n;
    }

    /**
     * 0.9.31 price ladders, verbatim from every log: the sword price steps ×3.5 per level
     * and the zone price ×55 per stage, so the next price is predictable after a purchase.
     */
    private static int priceLadders() {
        int n = 0;
        String[] sword = {"150.06K", "525.22K", "1.84M", "6.43M", "22.52M", "78.82M", "275.85M", "965.49M", "3.38B",
            "11.83B", "41.4B", "144.89B", "507.09B", "1.77T", "6.21T", "21.74T", "76.1T", "266.34T", "932.17T",
            "3.26Q", "11.42Q", "39.97Q", "139.88Q", "489.6Q", "1.71QQ", "6QQ", "21QQ", "73.47QQ", "257.14QQ", "900.01QQ"};
        for (int i = 1; i < sword.length; i++) {
            double a = Amounts.parse(sword[i - 1]), b = Amounts.parse(sword[i]);
            n += eq("sword ladder " + sword[i - 1] + " → " + sword[i], b / a, 3.5, 0.035);
        }
        String[] zone = {"137.26B", "7.55T", "415.21T", "22.83Q", "1.26QQ", "69.08QQ"};
        for (int i = 1; i < zone.length; i++) {
            double a = Amounts.parse(zone[i - 1]), b = Amounts.parse(zone[i]);
            n += eq("zone ladder " + zone[i - 1] + " → " + zone[i], b / a, 55.0, 1.2);
        }
        n += eq("predict sword after 135.06S", Economy.predictNext(135.06e21, 3.5), 472.71e21, 1e19);
        n += eq("predict zone after 3.8S", Economy.predictNext(3.8e21, 55.0), 209e21, 1e19);
        n += eq("no last price → no prediction", Economy.predictNext(null, 3.5) == null, true);
        n += eq("growth 1 → no prediction", Economy.predictNext(1e9, 1.0) == null, true);
        n += eq("ratio 3.45 accepted", Economy.growthAccepted(3.45, 3.5, 30), true);
        n += eq("two-level jump 12.25 rejected", Economy.growthAccepted(12.25, 3.5, 30), false);
        n += eq("zone 55.2 accepted", Economy.growthAccepted(55.2, 55.0, 30), true);
        n += eq("ratio below 1 rejected", Economy.growthAccepted(0.9, 3.5, 30), false);
        n += eq("blend from nothing", Economy.blendGrowth(null, 3.48, 0.3), 3.48, 1e-9);
        n += eq("blend ema", Economy.blendGrowth(3.5, 3.4, 0.3), 3.47, 1e-9);
        return n;
    }

    /**
     * 0.9.33 tri-state gate and zone-first decision, from the 2026-09-04 audit (logs
     * events-baseline 03-48 / 05-39 / 05-55): every /zone max teleport emptied the kill
     * window, "unknown" read as closed, and 11 of 12 zone buys were followed by a blind
     * sword buy; a slow RARE mob's DPS prediction bought a 5.79SS sword 12.3SS short of
     * the 19.68SS rebirth.
     */
    private static int audit0933Decision() {
        int n = 0;
        // --- the gate
        n += eq("fresh stage: unknown", Economy.zoneGate(null, 0, null, 0, 9600, null).name(), "unknown");
        n += eq("cook 12.0s past a 9.6s patience: hard via cook", Economy.zoneGate(null, 0, null, 12_000, 9600, null).via(), "cook");
        n += eq("legendary scale 1.4", Economy.rarityScale("LEGENDARY", CFG.rarityHpScale), 1.4, 1e-9);
        n += eq("rare scale 1.15", Economy.rarityScale("rare", CFG.rarityHpScale), 1.15, 1e-9);
        n += eq("untagged scale 1", Economy.rarityScale(null, CFG.rarityHpScale), 1.0, 1e-9);
        n += eq("cook 12.0s on a LEGENDARY (8.6s normalised): still unknown",
            Economy.zoneGate(null, 0, null, 12_000 / 1.4, 9600, null).name(), "unknown");
        n += eq("17:57 chicken 11.5s as the first kill: hard via kill", Economy.zoneGate(null, 1, 11_502.0, 0, 9600, null).via(), "kill");
        n += eq("median 0.77s over three kills opens it despite the 11.5s first kill",
            Economy.zoneGate(769.0, 3, 11_502.0, 0, 9600, null).name(), "open");
        n += eq("median 14.1s: hard via median", Economy.zoneGate(14_100.0, 5, 20_000.0, 0, 9600, null).via(), "median");
        n += eq("gate disabled: open", Economy.zoneGate(null, 0, null, 0, 0, null).name(), "open");
        n += eq("legacy prediction fills unknown", Economy.zoneGate(null, 0, null, 0, 9600, 35_447.0).via(), "predicted");
        n += eq("prediction never beats a median", Economy.zoneGate(5_500.0, 3, 7_000.0, 0, 9600, 35_447.0).name(), "open");
        // --- common first target on a fresh stage
        n += eq("rare penalised before the first kill", Economy.rarityScoreAdjust("RARE", 4.0, 0, 1, 30), 30.0, 1e-9);
        n += eq("rare bonus after it", Economy.rarityScoreAdjust("RARE", 4.0, 1, 1, 30), -4.0, 1e-9);
        n += eq("untagged unaffected", Economy.rarityScoreAdjust(null, 4.0, 0, 1, 30), 0.0, 1e-9);
        n += eq("probe kills 0 = old behaviour", Economy.rarityScoreAdjust("EPIC", 8.0, 0, 0, 30), -8.0, 1e-9);
        // --- keep the window across a quick toggle (14:55: six toggles in 37 s)
        n += eq("37s off, same stage: keep", Economy.keepTtkWindow(37_000, 60_000, true, false), true);
        n += eq("5 min off: reset", Economy.keepTtkWindow(300_000, 60_000, true, false), false);
        n += eq("zone label changed: reset", Economy.keepTtkWindow(37_000, 60_000, false, false), false);
        n += eq("teleported meanwhile: reset", Economy.keepTtkWindow(37_000, 60_000, true, true), false);
        n += eq("keep disabled", Economy.keepTtkWindow(37_000, 0, true, false), false);
        // --- the zone gap estimate replaces the zoneTarget==null bypass
        n += eq("gap from target", Economy.zoneGapEstimate(11.49e24, null, 55, 7.42e24), 4.07e24, 1e21);
        n += eq("gap from floor x growth is already covered at 17.33Q", Economy.zoneGapEstimate(null, 4.4e12, 55, 17.33e15), 0.0, 1e-9);
        n += eq("nothing known: null", Economy.zoneGapEstimate(null, null, 55, 1.0) == null, true);
        n += eq("gap via target", Economy.zoneGapVia(11.49e24, 4.4e12), "target");
        n += eq("gap via floor", Economy.zoneGapVia(null, 4.4e12), "floor");
        n += eq("15.98Q sword against a covered zone gap: not cheap", Economy.swordWhileSavingGap(15.98e15, 0.0, null, 25, 2000), false);
        n += eq("fresh account, nothing known: exploration", Economy.swordWhileSavingGap(15.98e15, null, null, 25, 2000), true);

        // --- 14:59:30 lvl7: bal 17.33Q, sword 15.98Q predicted, zone floor 4.4T, window just reset.
        Economy.Inputs a = new Economy.Inputs();
        a.bal = 17.33e15; a.incomePerMin = 2.62e21; a.swordTarget = 15.98e15; a.swordFloor = 5.79e15;
        a.zoneFloor = 4.4e12; a.zoneSeeded = true; a.patienceMs = 13_318; a.stageKills = 0;
        Decision d = Economy.decide(a);
        n += eq("14:59:30 with no stage kill yet: wait for one", d.reason(), "zone-stage-kills");
        n += eq("14:59:30 gate unknown", d.gate(), "unknown");
        a.stageKills = 1; a.stageMaxTtkMs = 1251.0;
        d = Economy.decide(a);
        n += eq("14:59:30 after one kill: probe the zone, not the 15.98Q sword", d.action() + " " + d.kind(), "probe zone");
        a.zoneExploratorySent = true;
        d = Economy.decide(a);
        n += eq("14:59:30 probe in flight: hold the sword for the zone", d.reason(), "saving-zone");
        n += eq("14:59:30 never buys the sword", d.isBuy(), false);
        a.zoneMinStageKills = 0; a.stageKills = 0; a.zoneExploratorySent = false;
        n += eq("zoneMinStageKills 0 chain-probes on the teleport", Economy.decide(a).action(), "probe");

        // --- 04:02:58 lvl4: 2.48T sword bought four seconds before the median opened the gate.
        Economy.Inputs b = new Economy.Inputs();
        b.bal = 2.6e12; b.incomePerMin = 23.24e21; b.swordTarget = 2.48e12; b.swordFloor = 386.2e6;
        b.zoneFloor = 578.84e6; b.zoneSeeded = true; b.patienceMs = 16_605; b.stageKills = 2; b.stageMaxTtkMs = 7704.0;
        d = Economy.decide(b);
        n += eq("04:02:58 two fast kills: probe zone", d.action() + " " + d.kind(), "probe zone");
        b.medianTtkMs = 8825.0; b.stageKills = 3;
        n += eq("04:02:58 median 8.8s < 16.6s: still zone", Economy.decide(b).kind(), "zone");

        // --- 05:56:13 lvl13: 5.79SS sword, 11.49SS zone target, 19.68SS rebirth, 1.21SS/min.
        Economy.Inputs c = new Economy.Inputs();
        c.bal = 7.42e24; c.incomePerMin = 1.21e24; c.swordTarget = 5.79e24; c.zoneTarget = 11.49e24;
        c.rebirthTarget = 19.68e24; c.patienceMs = 9901; c.stageKills = 1; c.stageMaxTtkMs = 7003.0;
        d = Economy.decide(c);
        n += eq("05:56:13 holds the 5.79SS sword for the zone", d.reason(), "saving-zone");
        n += eq("05:56:13 sword is 142% of the gap", d.swordPct(), 142.3, 0.5);
        n += eq("05:56:13 the predicted 35s gain let the buy through (the bug)",
            Economy.rebirthHorizonAllows(5.79e24, 7.42e24, 19.68e24, 1.21e24, Economy.swordGain(35_447.0, 2.0, 2000, 1.25)), true);
        n += eq("05:56:13 the floor gain blocks it", Economy.rebirthHorizonAllows(5.79e24, 7.42e24, 19.68e24, 1.21e24, Economy.swordGain(null, 2.0, 2000, 1.25)), false);
        c.zoneTarget = null; c.zoneFloor = 208.97e21; c.zoneSeeded = true; c.zoneExploratorySent = true;
        d = Economy.decide(c);
        n += eq("05:56:13 with only the zone floor known: still saving", d.reason(), "saving-zone");
        n += eq("05:56:13 gap via floor", d.zoneGapVia(), "floor");

        // --- the overshoot: /zone max landed on a stage the sword cannot handle (15:06:22).
        Economy.Inputs o = new Economy.Inputs();
        o.bal = 6.26e18; o.incomePerMin = 166.81e18; o.swordTarget = 1.71e18; o.swordFloor = 489.6e15;
        o.zoneFloor = 7.52e18; o.zoneSeeded = true; o.patienceMs = 14_451; o.stageKills = 0; o.cookElapsedMs = 51_056;
        d = Economy.decide(o);
        n += eq("overshoot: hard via cook", d.gateVia(), "cook");
        n += eq("overshoot: buy the sword mid-fight", d.action() + " " + d.kind() + " " + d.reason(), "buy sword sword-hard");
        o.cookElapsedMs = 0; o.stageKills = 3; o.medianTtkMs = 3175.0; o.bal = 72.79e18;
        d = Economy.decide(o);
        n += eq("overshoot fixed, 72.79QQ in hand: probe the zone", d.action() + " " + d.kind(), "probe zone");
        n += eq("overshoot fixed: gate open via median", d.gate() + "/" + d.gateVia(), "open/median");

        // --- instant kills: the sword is useless, save for the known zone (15:05).
        Economy.Inputs i = new Economy.Inputs();
        i.bal = 1.09e18; i.incomePerMin = 327.76e18; i.swordTarget = 489.6e15; i.zoneTarget = 1.17e18;
        i.patienceMs = 10_000; i.stageKills = 5; i.medianTtkMs = 1200.0;
        d = Economy.decide(i);
        n += eq("instant kills: wait for the zone", d.reason(), "sword-instant");
        n += eq("instant kills: waiting on the zone", d.kind(), "zone");
        n += eq("instant kills: eta known", d.waitMs() != null, true);
        i.bal = 1.2e18;
        n += eq("zone affordable: buy it", Economy.decide(i).reason(), "zone-affordable");

        // --- cheap sword while saving (zone 3 numbers from 0.9.16): 22.52M sword, 252M zone, 31.91M bal, 4.7s kills.
        Economy.Inputs ch = new Economy.Inputs();
        ch.bal = 31.91e6; ch.incomePerMin = 10e6; ch.swordTarget = 22.52e6; ch.zoneTarget = 252e6;
        ch.stageKills = 4; ch.medianTtkMs = 4742.0;
        n += eq("cheap sword while saving", Economy.decide(ch).reason(), "sword-cheap");
        ch.swordTarget = 78.82e6; ch.bal = 79.14e6;
        n += eq("pricey sword while saving", Economy.decide(ch).reason(), "saving-zone");

        // --- rebirth is ours to buy only without /autorebirth.
        Economy.Inputs r = new Economy.Inputs();
        r.serverAutoRebirth = false; r.rebirthAffordable = true; r.bal = 1e12;
        n += eq("rebirth affordable, ours to buy", Economy.decide(r).kind(), "rebirth");
        r.serverAutoRebirth = true; r.stageKills = 1;
        n += eq("server auto-rebirth, fresh account: probe the zone first", Economy.decide(r).reason(), "zone-probe");
        r.zoneSeeded = true; r.zoneExploratorySent = true; r.swordSeeded = true; r.swordExploratorySent = true;
        n += eq("both probes in flight, nothing known: no prices", Economy.decide(r).reason(), "no-prices");
        Economy.Inputs m = new Economy.Inputs();
        m.swordMaxed = true; m.zoneMaxed = true; m.bal = 1e12;
        n += eq("both maxed", Economy.decide(m).reason(), "maxed");

        // --- the decision is the log and the HUD line.
        Decision held = d.hold("cooldown", 41_000.0);
        n += eq("hold keeps the kind", held.kind(), "zone");
        n += eq("hold is a wait", held.action(), "wait");
        n += eq("kv carries the gate", java.util.Arrays.asList(held.kv()).contains("gate"), true);
        n += eq("closed only when hard", java.util.Arrays.asList(Economy.decide(o).kv()).indexOf("closed") < 0, true);
        String plan = Economy.decide(i).hudPlan(1.17e18, 1.2e18);
        n += eq("plan line: buy zone", plan.startsWith("buy zone 1.17QQ"), true);
        n += eq("plan line short", plan.length() <= 64, true);
        String hold = held.hudPlan(1.17e18, 1.09e18);
        n += eq("plan line: cooldown", hold, "wait 41s · cooldown zone");
        Economy.Inputs sv = new Economy.Inputs();
        sv.bal = 79.14e6; sv.incomePerMin = 10e6; sv.swordTarget = 78.82e6; sv.zoneTarget = 252e6; sv.stageKills = 4; sv.medianTtkMs = 2678.0;
        String save = Economy.decide(sv).hudPlan(252e6, 79.14e6);
        n += eq("plan line: saving", save.startsWith("save for zone 252M 31% ~17m"), true);

        // --- config
        n += eq("fresh zoneMinStageKills", CFG.zoneMinStageKills, 1);
        n += eq("fresh ttkKeepOnReenableMs", CFG.ttkKeepOnReenableMs, 60_000);
        n += eq("fresh gateUsesPrediction off", CFG.gateUsesPrediction, false);
        n += eq("fresh stageProbeCommonKills", CFG.stageProbeCommonKills, 1);
        n += eq("config version 41", YCBotChallengeConfig.CURRENT_CONFIG_VERSION, 41);
        try {
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("ycbot-cfg", ".json");
            java.nio.file.Files.writeString(tmp, "{\"configVersion\":36,\"gateUsesPrediction\":true,\"zoneMinStageKills\":-3}");
            YCBotChallengeConfig c36 = YCBotChallengeConfig.load(tmp);
            n += eq("v36 migrates to current", c36.configVersion, YCBotChallengeConfig.CURRENT_CONFIG_VERSION);
            n += eq("v36 prediction gate forced off", c36.gateUsesPrediction, false);
            n += eq("negative stage kills normalised", c36.zoneMinStageKills, 0);
            java.nio.file.Files.deleteIfExists(tmp);
        } catch (Exception ex) {
            System.err.println("FAIL v37 migration: " + ex);
            n++;
        }
        return n;
    }

    /**
     * 0.9.33 logging: observed (manual) upgrade lines are classified from the same verbatim
     * fixtures as our own responses, the zone price is the sidebar drop after a silent
     * success, and every event row carries the bot flag.
     */
    private static int logging0933() {
        int n = 0;
        List<Pattern> fail = looseAll(CFG.upgradeFailPatterns);
        List<Pattern> success = looseAll(CFG.upgradeSuccessPatterns);
        List<Pattern> maxed = looseAll(CFG.upgradeMaxedPatterns);
        Pattern need = loose(CFG.upgradeNeedAmountPattern);
        ChatClassifier.UpgradeLine u = ChatClassifier.classifyUpgradeLine(SWORD_FAIL, fail, success, maxed, need);
        n += eq("observed sword fail", u != null ? u.kind() + "/" + u.outcome() : null, "sword/fail");
        n += eq("observed sword gap 781.04B", u != null ? u.amount() : null, 781.04e9, 1e3);
        u = ChatClassifier.classifyUpgradeLine(ZONE_FAIL, fail, success, maxed, need);
        n += eq("observed zone fail", u != null ? u.kind() + "/" + u.outcome() : null, "zone/fail");
        n += eq("observed zone gap 1.25Q", u != null ? u.amount() : null, 1.25e15, 1e9);
        u = ChatClassifier.classifyUpgradeLine(SWORD_UNLOCK_REAL, fail, success, maxed, need);
        n += eq("observed sword success", u != null ? u.kind() + "/" + u.outcome() : null, "sword/success");
        n += eq("observed sword paid 1.24B", u != null ? u.amount() : null, 1.24e9, 1e3);
        u = ChatClassifier.classifyUpgradeLine(ZONE_UNLOCK, fail, success, maxed, need);
        n += eq("observed zone success", u != null ? u.kind() + "/" + u.outcome() : null, "zone/success");
        n += eq("observed zone success has no amount", u != null && u.amount() == null, true);
        u = ChatClassifier.classifyUpgradeLine(REBIRTH_NEED_ICON, fail, success, maxed, need);
        n += eq("observed rebirth fail", u != null ? u.kind() + "/" + u.outcome() : null, "rebirth/fail");
        n += eq("observed rebirth gap 29.99T", u != null ? u.amount() : null, 29.99e12, 1e6);
        n += eq("enchant proc is not an upgrade line", ChatClassifier.classifyUpgradeLine(ENCHANT_PROC, fail, success, maxed, need) == null, true);
        n += eq("player shop is not an upgrade line", ChatClassifier.classifyUpgradeLine(PLAYER_SHOP, fail, success, maxed, need) == null, true);
        n += eq("soul purchase is not an upgrade line", ChatClassifier.classifyUpgradeLine(SOUL_PURCHASE, fail, success, maxed, need) == null, true);
        n += eq("welcome is not an upgrade line", ChatClassifier.classifyUpgradeLine(WELCOME, fail, success, maxed, need) == null, true);
        // Zone paid from the sidebar drop (14:59: 17.33Q before, 15.98Q sword; the shape is the same for a zone).
        n += eq("paid from delta", Economy.paidFromDelta(17.33e15, 1.35e15), 15.98e15, 1e12);
        n += eq("a rise is not a spend", Economy.paidFromDelta(1.0e15, 1.2e15) == null, true);
        n += eq("unknown before", Economy.paidFromDelta(null, 1.2e15) == null, true);
        n += eq("fresh learnObservedUpgrades", CFG.learnObservedUpgrades, true);
        n += eq("fresh offBotLogIntervalMs", CFG.offBotLogIntervalMs, 30_000);
        // Every row carries the bot flag.
        try {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("ycbot-log");
            EventLogger lg = new EventLogger(dir, "test", com.google.gson.JsonObject::new, () -> "paused:captcha");
            lg.log("probe", "kind", "zone");
            lg.close();
            String row = java.nio.file.Files.readString(lg.getFile());
            n += eq("row carries bot=paused:captcha", row.contains("\"bot\":\"paused:captcha\""), true);
            n += eq("row carries the type", row.contains("\"type\":\"probe\""), true);
            java.nio.file.Files.deleteIfExists(lg.getFile());
            java.nio.file.Files.deleteIfExists(dir);
        } catch (Exception ex) {
            System.err.println("FAIL event logger bot flag: " + ex);
            n++;
        }
        return n;
    }

    /** 0.9.33 HUD: the plan row is the last Decision, one short line per reason; module row off by default. */
    private static int hudPlan0933() {
        int n = 0;
        n += eq("fresh hudShowPlan", CFG.hudShowPlan, true);
        n += eq("fresh hudShowModules off", CFG.hudShowModules, false);
        try {
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("ycbot-cfg", ".json");
            java.nio.file.Files.writeString(tmp, "{\"configVersion\":36,\"hudShowModules\":true}");
            n += eq("v36 module row migrates off", YCBotChallengeConfig.load(tmp).hudShowModules, false);
            java.nio.file.Files.deleteIfExists(tmp);
        } catch (Exception ex) {
            System.err.println("FAIL hud migration: " + ex);
            n++;
        }
        long at = 1_000_000L;
        Decision base = new Decision(Decision.WAIT, "zone", "zone-stage-kills", "unknown", "none", null, 9600, 0,
            null, null, null, null, null, null, at);
        n += eq("plan: new stage", base.hudPlan(null, null), "wait · new stage, 0 kill(s) so far");
        Decision hard = new Decision(Decision.BUY, "sword", "sword-hard", "hard", "cook", 14_100.0, 9600, 1,
            null, null, null, 1.8, "median", null, at);
        n += eq("plan: hard stage", hard.hudPlan(2.48e12, 2.6e12), "buy sword 2.48T · stage hard 14.1s > 9.6s");
        Decision probe = new Decision(Decision.PROBE, "zone", "zone-probe", "unknown", "none", null, 9600, 1,
            0.0, "floor", null, 1.3, "config", null, at);
        n += eq("plan: probe", probe.hudPlan(null, 17.33e15), "probe zone · price ?");
        Decision inst = new Decision(Decision.WAIT, "zone", "sword-instant", "open", "median", 1200.0, 9600, 5,
            80e15, "target", null, null, null, 15_000.0, at);
        n += eq("plan: instant", inst.hudPlan(1.17e18, 1.09e18), "wait · instant kills 1.2s, sword useless");
        Decision hz = new Decision(Decision.WAIT, "zone", "rebirth-horizon", "open", "median", 3000.0, 9600, 5,
            400e21, "target", null, 1.3, "config", null, at);
        n += eq("plan: horizon", hz.hudPlan(415e21, 100e21), "wait · rebirth sooner than zone pays off");
        Decision none = new Decision(Decision.NONE, null, "maxed", "open", "none", null, null, 0,
            null, null, null, null, null, null, at);
        n += eq("plan: maxed", none.hudPlan(null, null), "nothing left to buy");
        for (Decision d : new Decision[]{base, hard, probe, inst, hz, none}) {
            n += eq("plan length " + d.reason(), d.hudPlan(1e12, 1e12).length() <= 64, true);
        }
        return n;
    }


    /** Real amounts from the 2026-09-04 logs: O is the rung above SS. */
    private static final double SS = 1e24, O = 1e27, N = 1e30;

    /**
     * The lvl15 stall: 2026-09-04 17:57:13 verbatim, 36 minutes in, where the log shows
     * "upgrade_skip ... saving-zone" — the zone 30 min out, the sword affordable but not cheap
     * against the gap, so nothing happens. Zone prices climb x55 a stage while income growth
     * per stage had fallen to x18 by lvl15, which is why the climb stops here at all. An egg
     * batch is the buy: a direct income multiplier that ignores the TTK, measured 2.20x
     * (7 eggs) and 1.76x (8), and unlike the sword and the zone it survives the rebirth.
     */
    private static Economy.Inputs lvl15() {
        Economy.Inputs in = new Economy.Inputs();
        in.bal = 11.51 * O;
        in.rebirthTarget = 17.72 * O;
        in.incomePerMin = 0.767 * O;      // rebirthEtaMin 8.1, as logged
        in.zoneTarget = 34.7 * O;
        in.zoneFloor = 631.12 * SS;
        in.swordTarget = 10.65 * O;
        in.swordFloor = 3.04 * O;
        in.medianTtkMs = 5603.0;
        in.stageMaxTtkMs = 167344.0;      // the stage the sword never fixed
        in.stageKills = 74;
        in.patienceMs = 13977;
        in.companionStage = 15;
        in.companionBatchPrice = 3 * 904.27 * SS;   // companionEggsMin eggs at the s15 price
        in.companionGain = 1.5;                     // the prior, before anything is learned
        in.now = 1_000L;
        return in;
    }

    /** 0.9.35: companions priced by the one economy — the asymmetry, the patience horizon, the caps. */
    private static int companions0935() {
        int n = 0;

        // The asymmetry (0.9.35), now in 0.9.36 terms. The same numbers: a buy the rebirth
        // wipes is refused by the horizon; a batch that survives it is bought because the
        // rebirth comes sooner WITH the eggs (P < G(g-1) on the rebirth gap).
        n += eq("horizon vetoes a wiped buy",
            Economy.rebirthHorizonAllows(3.31 * SS, 7.0 * SS, 10.0 * SS, 1.21 * SS, 1.8), false);
        n += eq("a batch over the gap's share is not sooner",
            Economy.companionSoonerTo(3.31 * SS, 3.0 * SS, 1.8), false);
        n += eq("sooner to a 10SS gap at g 1.8", Economy.companionSoonerTo(3.31 * SS, 10.0 * SS, 1.8), true);
        n += eq("not sooner when the batch exceeds the gap's share", Economy.companionSoonerTo(30.0 * SS, 10.0 * SS, 1.8), false);
        n += eq("no gain, no opinion", Economy.companionSoonerTo(1.0, 10.0, 1.0), false);
        n += eq("unknown gap: not sooner", Economy.companionSoonerTo(1.0, null, 1.5), false);

        // The persistent payback budget: what the next cycle saves, floored - never a share of
        // what is left to this rebirth (0.9.35's 25 % of a two-minute rebirth was 30 s).
        n += eq("budget: g1.5 on a 45 min cycle, half kept", Economy.companionPersistBudgetMin(1.5, 45, 0.5, 3.0), 7.5, 1e-9);
        n += eq("budget floor before a cycle is measured", Economy.companionPersistBudgetMin(1.5, 0, 0.5, 3.0), 3.0, 1e-9);
        n += eq("budget floor wins over a tiny cycle", Economy.companionPersistBudgetMin(1.5, 6, 0.5, 3.0), 3.0, 1e-9);
        n += eq("a 2-minute delay fits", Economy.companionPersistAllows(2.0, 1.5, 45, 0.5, 3.0), true);
        n += eq("a 10-minute delay does not", Economy.companionPersistAllows(10.0, 1.5, 45, 0.5, 3.0), false);
        n += eq("... unless the cycle is long", Economy.companionPersistAllows(10.0, 1.5, 90, 0.5, 3.0), true);
        // 19:19:47: 141.75O batch, 402.66O in hand, 531.44O rebirth at 65.04O/min - 0.9.35
        // refused it eight times; the delay is 0.8 min and the 3-minute floor alone takes it.
        Double d1919 = Economy.companionDelayMin(141.75 * O, 402.66 * O, 531.44 * O, 65.04 * O, 1.5);
        n += eq("19:19:47 delay", d1919, 0.8, 0.1);
        n += eq("19:19:47 allowed by the floor", Economy.companionPersistAllows(d1919, 1.5, 45, 0.5, 3.0), true);
        n += eq("unknown numbers: no delay", Economy.companionDelayMin(3.31 * SS, null, 10.0 * SS, 1.21 * SS, 1.8) == null, true);

        // The stall itself: what the log holds on, and what the batch turns it into.
        Economy.Inputs baseInputs = lvl15();
        baseInputs.companionBlocked = "busy";
        Decision held = Economy.decide(baseInputs);
        n += eq("baseline is the hold the log shows",
            held.action() + " " + held.kind() + " " + held.reason(), "wait zone saving-zone");
        n += eq("... annotated with why the eggs wait", held.eggs(), "blocked");

        Decision d = Economy.decide(lvl15());
        n += eq("companions convert it", d.action() + " " + d.kind() + " " + d.reason(),
            "buy companion companion-sooner");
        n += eq("the batch is not a typed command", d.actsTyped(), false);
        n += eq("it is handed to the companion controller", d.actsCompanion(), true);
        n += eq("a buy carries no annotation", d.eggs() == null, true);

        // Slower to every milestone than standing still (zone buys stopped, the batch over the
        // rebirth gap's share), but the eggs keep paying past the rebirth: the delay fits.
        Economy.Inputs persist = lvl15();
        persist.bal = 16.5 * O;                  // 1.22O to the rebirth
        persist.companionZoneStopped = true;
        persist.companionBatchPrice = 1.0 * O;   // delays the rebirth 0.34 min against a 7.5 min budget
        Decision dp = Economy.decide(persist);
        n += eq("bought for what it keeps", dp.reason(), "companion-persist");
        n += eq("the delay rides on the decision", dp.waitMs() != null && dp.waitMs() > 10_000 && dp.waitMs() < 40_000, true);

        // The further the rebirth, the MORE an income multiplier is worth - the opposite of a
        // sword or a zone, which the rebirth wipes.
        Economy.Inputs far = lvl15();
        far.rebirthTarget = 400.0 * O;
        n += eq("a distant rebirth makes it worth more", Economy.decide(far).reason(), "companion-sooner");

        // ... and a batch that pushes the rebirth out past the payback budget is refused, the
        // hold standing with the reason on it.
        Economy.Inputs late = lvl15();
        late.bal = 16.5 * O;
        late.companionZoneStopped = true;
        late.companionBatchPrice = 12.0 * O;     // delay 9.9 min > 7.5
        Decision dl = Economy.decide(late);
        n += eq("past the budget: refused", dl.actsCompanion(), false);
        n += eq("... and says so", dl.eggs(), "horizon");
        n += eq("... on the hold the economy already had", dl.reason(), "saving-zone");
        late.companionCycleMin = 90;             // a longer cycle pays more back
        n += eq("a longer cycle takes it", Economy.decide(late).reason(), "companion-persist");
        late.companionIncomeSettled = false;
        n += eq("the persist rule waits for the stage's own income", Economy.decide(late).eggs(), "settle");
        late.companionIncomeSettled = true;
        late.incomePerMin = null;
        n += eq("no income: no delay to judge", Economy.decide(late).eggs(), "no-income");

        // The two 0.9.33 log cases. 0.9.35 refused them for being minutes from the stage; now
        // they are bought: the batch is under half the rebirth gap, so the rebirth comes sooner
        // with the eggs, and Drew buys at every new stage on the way up.
        Economy.Inputs t1422 = lvl15();          // 3.31SS on eggs with 1.58SS left to the zone
        t1422.bal = 8.33 * SS;
        t1422.rebirthTarget = 30.0 * SS;
        t1422.incomePerMin = 2.09 * SS;
        t1422.zoneTarget = 9.91 * SS;
        t1422.swordTarget = 7.47 * SS;
        t1422.companionBatchPrice = 3.31 * SS;
        t1422.companionStage = 13;
        t1422.medianTtkMs = 8294.0;
        t1422.zoneFloor = null;
        n += eq("14:22: bought - sooner to the 30SS rebirth", Economy.decide(t1422).reason(), "companion-sooner");

        Economy.Inputs t0545 = lvl15();          // 2.32SS against an 8.81SS gap
        t0545.bal = 2.68 * SS;
        t0545.rebirthTarget = 30.0 * SS;
        t0545.incomePerMin = 0.94 * SS;
        t0545.zoneTarget = 11.49 * SS;
        t0545.swordTarget = 7.47 * SS;
        t0545.companionBatchPrice = 2.32 * SS;
        t0545.companionStage = 13;
        t0545.medianTtkMs = 8294.0;
        t0545.zoneFloor = null;
        n += eq("05:45: same - sooner to the 11.49SS stage", Economy.decide(t0545).reason(), "companion-sooner");

        // Caps and safety.
        Economy.Inputs repeat = lvl15();
        repeat.companionVisitsThisStage = 2;
        Decision dRep = Economy.decide(repeat);
        n += eq("the per-stage budget is spent", dRep.eggs(), "repeat");
        n += eq("and nothing is bought", dRep.acts(), false);
        n += eq("the hold is the economy's own", dRep.reason(), "saving-zone");

        // 0.9.36: the 40 %-of-balance cap is gone from the decision (at lvl17 it demanded
        // 18.53N in hand for a 7.41N batch while the whole rebirth cost 15.94N).
        Economy.Inputs rich = lvl15();
        rich.companionBatchPrice = 0.6 * rich.bal;   // 60% of the wallet
        n += eq("most of the balance is fine when it pays", Economy.decide(rich).actsCompanion(), true);

        Economy.Inputs blocked = lvl15();
        blocked.companionBlocked = "abort-cooldown";
        Decision dB = Economy.decide(blocked);
        n += eq("an infeasible visit leaves the hold alone", dB.reason(), "saving-zone");
        n += eq("... and says the visit is blocked", dB.eggs(), "blocked");

        Economy.Inputs noPrice = lvl15();
        noPrice.companionBatchPrice = null;
        n += eq("no egg price yet", Economy.decide(noPrice).eggs(), "no-price");

        Economy.Inputs off = lvl15();
        off.companionsEnabled = false;
        Decision dOff = Economy.decide(off);
        n += eq("the toggle really turns it off", dOff.actsCompanion(), false);
        n += eq("... with no annotation at all", dOff.eggs() == null, true);

        // Regression: a fixture that says nothing about companions decides as it always did.
        n += eq("companions are inert by default", new Economy.Inputs().companionBatchPrice == null, true);

        // The egg ladder, measured x52.2 a stage over seven consecutive steps.
        n += eq("s9 -> s10 is a ladder step", Economy.growthAccepted(2.32e21 / 44.44e18, 52.2, 30), true);
        n += eq("s13 -> s14 too", Economy.growthAccepted(17.31e24 / 331.23e21, 52.2, 30), true);
        n += eq("a x2 step is not", Economy.growthAccepted(2.0, 52.2, 30), false);
        n += eq("s13 predicts s14 within 5%", Economy.predictNext(331.23e21, 52.2), 17.29e24, 0.9e24);

        // Learning the gain from the two measured batches, in order.
        n += eq("first sample is the estimate", Economy.blendGrowth(null, 2.2, 0.3), 2.2, 1e-9);
        n += eq("second blends in", Economy.blendGrowth(2.2, 1.76, 0.3), 2.068, 1e-3);

        // The plan lines stay inside the HUD row.
        for (String r : List.of("companion-sooner", "companion-persist")) {
            Decision p = new Decision(Decision.BUY, Decision.KIND_COMPANION, r, "hard", "median", 36073.0, 13977, 23,
                23.71 * O, "target", null, 1.76, "learned", 116_400.0, 0L);
            n += eq("plan length " + r, p.hudPlan(2.71 * O, 3.41 * O).length() <= 64, true);
        }
        return n;
    }

    /**
     * The lvl17 stall, 2026-09-04 20:40 verbatim: the newest log's terminal stage. Ocelots at a
     * 23 s median after two sword buys (1.6N, 5.59N), gate HARD, the 19.56N sword out of reach,
     * the 105.17N zone 91N away, the 15.94N rebirth 1.66N away at 2.26N/min - and a 3-egg
     * batch at 2.47N each that 0.9.35 refused on the 40 % cap every single eval.
     */
    private static Economy.Inputs lvl17() {
        Economy.Inputs in = new Economy.Inputs();
        in.bal = 14.28 * N;
        in.rebirthTarget = 15.94 * N;
        in.incomePerMin = 2.26 * N;
        in.zoneTarget = 105.17 * N;
        in.zoneFloor = 1.91 * N;
        in.swordTarget = 19.56 * N;
        in.swordFloor = 5.59 * N;
        in.medianTtkMs = 23_200.0;
        in.stageMaxTtkMs = 247_800.0;
        in.stageKills = 20;
        in.patienceMs = 11_812;
        in.companionStage = 17;
        in.companionLastBoughtStage = 15;
        in.companionBatchPrice = 3 * 2.47 * N;
        in.companionGain = 1.5;
        in.companionCycleMin = 45;
        in.now = 1_000L;
        return in;
    }

    /** 0.9.36: eggs that get bought, every decline named, the cycle clock, the retreat measured, config v40. */
    private static int companions0936() {
        int n = 0;

        // --- 20:40 lvl17: the hold the log shows, and the batch 0.9.35 never bought.
        Economy.Inputs held = lvl17();
        held.companionBlocked = "busy";
        Decision h = Economy.decide(held);
        n += eq("20:40 hold", h.action() + " " + h.kind() + " " + h.reason(), "wait sword sword-hard-unaffordable");
        n += eq("20:40 gate", h.gate() + " " + h.gateVia(), "hard median");
        Decision d17 = Economy.decide(lvl17());
        // 0.9.37: the rebirth (1.66N away) is the nearer milestone, so the payback rule decides - and buys.
        n += eq("20:40 the batch is the buy", d17.action() + " " + d17.kind() + " " + d17.reason(), "buy companion companion-persist");
        // Zone buys stopped: the rebirth gap (1.66N) is too small for "sooner", the persist rule takes it.
        Economy.Inputs stopped = lvl17();
        stopped.companionZoneStopped = true;
        Decision ds = Economy.decide(stopped);
        n += eq("20:40 zone stopped: still bought, for what it keeps", ds.reason(), "companion-persist");
        n += eq("20:40 delay ~1.9 min", ds.waitMs() != null ? ds.waitMs() / 60_000.0 : null, 1.94, 0.1);
        // Not yet affordable: the hold stands and says so.
        Economy.Inputs poor = lvl17();
        poor.bal = 5.0 * N;
        Decision dpoor = Economy.decide(poor);
        n += eq("5N in hand: hold", dpoor.reason(), "sword-hard-unaffordable");
        n += eq("5N in hand: eggs unaffordable", dpoor.eggs(), "unaffordable");
        // A fresh hard stage before its income is its own: the sooner rule needs no income at all
        // (0.9.37: on a stage whose zone is the nearer milestone), the persist rule waits.
        Economy.Inputs fresh = lvl17();
        fresh.rebirthTarget = 400.0 * N;         // the zone (91N) is the nearer milestone
        fresh.companionIncomeSettled = false;
        fresh.incomePerMin = null;
        n += eq("fresh stage, no income: sooner still buys", Economy.decide(fresh).reason(), "companion-sooner");
        fresh.companionZoneStopped = true;
        n += eq("... the persist rule waits", Economy.decide(fresh).eggs(), "settle");
        fresh.companionIncomeSettled = true;
        n += eq("... and with no income it cannot judge", Economy.decide(fresh).eggs(), "no-income");

        // --- 19:19:47 lvl16: eight refusals in 0.9.35 on the percentage rule; the stage gap takes it now.
        Economy.Inputs l16 = new Economy.Inputs();
        l16.bal = 402.66 * O;
        l16.rebirthTarget = 531.44 * O;
        l16.incomePerMin = 65.04 * O;
        l16.zoneTarget = 1911.08 * O;
        l16.zoneFloor = 34.76 * O;
        l16.swordTarget = 456.99 * O;
        l16.swordFloor = 130.4 * O;
        l16.stageKills = 2;
        l16.stageMaxTtkMs = 15_040.0;
        l16.patienceMs = 9737;
        l16.companionStage = 16;
        l16.companionLastBoughtStage = 15;
        l16.companionBatchPrice = 141.75 * O;
        l16.now = 1_000L;
        Decision d16 = Economy.decide(l16);
        n += eq("19:19:47 bought", d16.reason(), "companion-sooner");
        n += eq("19:19:47 gate from the first slow kill", d16.gate() + " " + d16.gateVia(), "hard kill");

        // --- Late and high: a stage below the last one bought is never bought again.
        Economy.Inputs low = lvl17();
        low.companionStage = 12;
        Decision dlow = Economy.decide(low);
        n += eq("stage 12 with eggs from 15: refused", dlow.actsCompanion(), false);
        n += eq("... as below-owned", dlow.eggs(), "below-owned");
        low.companionStage = 15;
        n += eq("the last bought stage itself is fine", Economy.decide(low).actsCompanion(), true);
        low.companionLastBoughtStage = null;
        low.companionStage = 3;
        n += eq("nothing bought yet: any stage", Economy.decide(low).actsCompanion(), true);

        // --- Zone-first is untouched: a real buy always wins and carries no annotation.
        Economy.Inputs zone = lvl15();
        zone.bal = 40.0 * O;
        Decision dz = Economy.decide(zone);
        n += eq("an affordable zone beats the eggs", dz.action() + " " + dz.kind() + " " + dz.reason(), "buy zone zone-affordable");
        n += eq("... and is not annotated", dz.eggs() == null, true);

        // --- The annotation rides on controller holds and reaches the log.
        Decision hold = Economy.decide(held).hold("first-kills", null);
        n += eq("a controller hold keeps the annotation", hold.eggs(), "blocked");
        Object[] kv = hold.kv();
        boolean found = false;
        for (int i = 0; i + 1 < kv.length; i += 2) if ("eggs".equals(kv[i]) && "blocked".equals(kv[i + 1])) found = true;
        n += eq("kv emits eggs", found, true);
        n += eq("a plain fixture is annotated no-price", Economy.decide(new Economy.Inputs()).eggs(), "no-price");

        // --- The retreat, measured: 2026-09-04 lvl16 -> 17. 770O a kill at 185 s against
        // lvl16's 356O/min peak: a x27 money step against a x29 time step, no retreat.
        Economy.ZoneBack zb = Economy.zoneBackCandidate(770.0 * O, 185_000.0, 2000, 356.0 * O, 1.5);
        n += eq("lvl17 earns ~250O/min at first", zb.herePerMin() / O, 249.7, 1.0);
        n += eq("lvl16 was 1.43x that", zb.ratio(), 1.43, 0.01);
        n += eq("under the margin: no retreat", zb.wouldRetreat(), false);
        n += eq("ten times slower kills: retreat", Economy.zoneBackCandidate(770.0 * O, 1_850_000.0, 2000, 356.0 * O, 1.5).wouldRetreat(), true);
        n += eq("instant kills sit on the movement floor", Economy.zoneBackCandidate(1.0e6, 500.0, 2000, null, 1.5).herePerMin(), 30.0e6, 1.0);
        n += eq("no previous stage: nothing to retreat to", Economy.zoneBackCandidate(770.0 * O, 185_000.0, 2000, null, 1.5).wouldRetreat(), false);
        n += eq("no kill priced yet: null", Economy.zoneBackCandidate(null, 185_000.0, 2000, 356.0 * O, 1.5) == null, true);

        // --- Config v40: the 0.9.35 race knobs are gone, two defaults moved, the live fusion pattern is fixed.
        for (String dead : new String[]{"companionPatienceMinutes", "companionPersistCredit", "companionMaxRebirthDelayPct", "companionRebirthEtaMinMax"}) {
            boolean gone;
            try { YCBotChallengeConfig.class.getDeclaredField(dead); gone = false; } catch (NoSuchFieldException e) { gone = true; }
            n += eq("dead knob removed: " + dead, gone, true);
        }
        n += eq("fresh settle kills", CFG.companionStageSettleKills, 3);
        n += eq("fresh settle ms", CFG.companionStageSettleMs, 120_000);
        n += eq("fresh gain floor", CFG.companionGainMin, 0.8, 1e-9);
        n += eq("fresh cycle prior", CFG.companionCyclePriorMin, 45.0, 1e-9);
        n += eq("fresh payback fraction", CFG.companionPaybackFraction, 0.5, 1e-9);
        n += eq("fresh zone-back measurement on", CFG.zoneBackMeasureEnabled, true);
        try {
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("ycbot-cfg", ".json");
            java.nio.file.Files.writeString(tmp, "{\"configVersion\":39,\"companionStageSettleKills\":10,\"companionGainMin\":1.2,"
                + "\"companionFusePattern\":\"/fuse companions/\",\"companionPatienceMinutes\":20.0}");
            YCBotChallengeConfig c39 = YCBotChallengeConfig.load(tmp);
            n += eq("v39 migrates to current", c39.configVersion, YCBotChallengeConfig.CURRENT_CONFIG_VERSION);
            n += eq("v39 settle kills 10 -> 3", c39.companionStageSettleKills, 3);
            n += eq("v39 gain floor 1.2 -> 0.8", c39.companionGainMin, 0.8, 1e-9);
            n += eq("v39 stale fusion pattern replaced", c39.companionFusePattern, CFG.companionFusePattern);
            java.nio.file.Files.writeString(tmp, "{\"configVersion\":39,\"companionStageSettleKills\":5,\"companionGainMin\":1.1,"
                + "\"companionFusePattern\":\"/my fusion/\"}");
            YCBotChallengeConfig hand = YCBotChallengeConfig.load(tmp);
            n += eq("hand-set settle survives", hand.companionStageSettleKills, 5);
            n += eq("hand-set gain floor survives", hand.companionGainMin, 1.1, 1e-9);
            n += eq("hand-set fusion pattern survives", hand.companionFusePattern, "/my fusion/");
            java.nio.file.Files.deleteIfExists(tmp);
        } catch (Exception ex) {
            System.err.println("FAIL v40 migration: " + ex);
            n++;
        }

        // --- The cycle clock round-trips through the state file.
        try {
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("ycbot-state", ".json");
            java.nio.file.Files.deleteIfExists(tmp);
            StateStore s = new StateStore(tmp);
            StateStore.Entry e = new StateStore.Entry();
            e.lastCycleOnMin = 58.3;
            e.cycleOnMs = 120_000L;
            e.cycleAtRebirths = 14;
            s.put("Ihazekids69420", e);
            StateStore.Entry r = new StateStore(tmp).get("ihazekids69420");
            n += eq("last cycle minutes", r.lastCycleOnMin, 58.3, 1e-9);
            n += eq("running cycle ms", r.cycleOnMs, 120_000L);
            n += eq("cycle rebirth", r.cycleAtRebirths, 14);
            java.nio.file.Files.deleteIfExists(tmp);
        } catch (Exception ex) {
            System.err.println("FAIL cycle state: " + ex);
            n++;
        }
        return n;
    }

    /** 0.9.33 companions: eggs never delay a stage within reach; visits, last stage and egg prices survive a restart. */
    private static int companions0933() {
        int n = 0;
        // The 0.9.33 "batch within 25% of the zone gap" proxy is gone; the same two log cases
        // are asserted against the real decision in companions0935().
        n += eq("visits this rebirth", Economy.visitsThisRebirth(2, 9, 9), 2);
        n += eq("visits reset once the counter moved", Economy.visitsThisRebirth(2, 9, 10), 0);
        n += eq("visits kept while the counter is unknown", Economy.visitsThisRebirth(2, 9, null), 2);
        n += eq("no visits recorded", Economy.visitsThisRebirth(null, null, 9), 0);
        try {
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("ycbot-state", ".json");
            java.nio.file.Files.deleteIfExists(tmp);
            StateStore s = new StateStore(tmp);
            StateStore.Entry e = new StateStore.Entry();
            e.companionLastBoughtStage = 12;
            e.companionVisitsThisRebirth = 1;
            e.companionVisitsAtRebirths = 9;
            e.companionEggPriceByStage = new java.util.LinkedHashMap<>(Map.of("12", 330e21, "13", 331.23e21));
            e.companionGainLearned = 1.76;
            e.companionVisitsByStage = new java.util.LinkedHashMap<>(Map.of("15", 2));
            e.companionVisitsStageRebirths = 11;
            s.put("Ihazekids69420", e);
            StateStore.Entry alt = new StateStore.Entry();
            alt.swordTarget = 41.4e9;
            s.put("AltAccount", alt);
            StateStore r = new StateStore(tmp);
            StateStore.Entry m = r.get("ihazekids69420");
            n += eq("companion stage persisted", m.companionLastBoughtStage, 12);
            n += eq("companion visits persisted", m.companionVisitsThisRebirth, 1);
            n += eq("companion visits rebirth persisted", m.companionVisitsAtRebirths, 9);
            n += eq("companion egg price persisted", m.companionEggPriceByStage.get("13"), 331.23e21, 1e18);
            n += eq("companion gain persisted", m.companionGainLearned, 1.76, 1e-9);
            n += eq("companion stage visits persisted", m.companionVisitsByStage.get("15"), 2);
            n += eq("alt has no companion facts", r.get("altaccount").companionLastBoughtStage == null, true);
            java.nio.file.Files.deleteIfExists(tmp);
        } catch (Exception ex) {
            System.err.println("FAIL companion state: " + ex);
            n++;
        }
        return n;
    }

    /** 0.9.33 menu manners: one timing policy for every container flow; the dead pre-0.9.11 knobs are gone. */
    private static int gui0933() {
        int n = 0;
        n += eq("fresh guiClickMinMs", CFG.guiClickMinMs, 250);
        n += eq("fresh guiClickMaxMs", CFG.guiClickMaxMs, 900);
        n += eq("fresh guiCloseMinMs", CFG.guiCloseMinMs, 400);
        n += eq("fresh guiBetweenMaxMs", CFG.guiBetweenMaxMs, 3500);
        n += eq("fresh companionLookMaxMs", CFG.companionLookMaxMs, 3500);
        for (String dead : new String[]{"enchantSkipChance", "enchantVisitGapMinMs", "upgradePeriodMinMs", "zoneEverySwordsMin",
            "zoneOverSwordRatio", "zoneMinReadiness", "retryPriceGrowthPct", "enchantMaxBuysPerVisit"}) {
            boolean gone;
            try { YCBotChallengeConfig.class.getDeclaredField(dead); gone = false; } catch (NoSuchFieldException e) { gone = true; }
            n += eq("dead knob removed: " + dead, gone, true);
        }
        try {
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("ycbot-cfg", ".json");
            java.nio.file.Files.writeString(tmp, "{\"configVersion\":36,\"enchantSkipChance\":0.5,\"guiClickMinMs\":900,\"guiClickMaxMs\":100}");
            YCBotChallengeConfig c = YCBotChallengeConfig.load(tmp);
            n += eq("old file with a dead knob still loads", c.configVersion,
                YCBotChallengeConfig.CURRENT_CONFIG_VERSION);
            n += eq("swapped click range normalises", c.guiClickMaxMs >= c.guiClickMinMs, true);
            java.nio.file.Files.deleteIfExists(tmp);
        } catch (Exception ex) {
            System.err.println("FAIL gui config: " + ex);
            n++;
        }
        return n;
    }

    /**
     * 0.9.33 Sword Skins scouting. Fixtures are from Drew's screenshots (2026-09-04); replace
     * them with the first live sword_menu line once it lands — the suffix of "$139.880" in
     * particular may be a font glyph the text component does not carry.
     */
    private static int swordSkins0933() {
        int n = 0;
        SwordSkinLore sl = new SwordSkinLore(CFG);
        List<String> netherite = List.of("SWORD SKIN", "Custom Netherite Sword that will provide the wielder to do more damage against monsters.",
            "Information:", "* Damage: 727.75B DMG", "* Price: $139.880 Money", "* Tier: 3/5 ★★★☆☆", "EQUIPPED",
            "Click here to upgrade the tier of your sword", "When in Main Hand:", "8 Attack Damage", "1.6 Attack Speed");
        SwordSkinLore.Skin eq = sl.parse(12, "Netherite Sword", netherite);
        n += eq("netherite parsed", eq != null, true);
        n += eq("netherite equipped", eq != null && eq.equipped(), true);
        n += eq("netherite tier 3", eq != null ? eq.tier() : null, 3);
        n += eq("netherite tier max 5", eq != null ? eq.tierMax() : null, 5);
        n += eq("netherite damage 727.75B", eq != null ? eq.damage() : null, 727.75e9, 1e6);
        n += eq("glyph price reads as 139.88", eq != null ? eq.price() : null, 139.88, 1e-6);
        List<String> netheriteQ = List.of("SWORD SKIN", "* Damage: 727.75B DMG", "* Price: $139.88Q Money", "* Tier: 3/5", "EQUIPPED");
        n += eq("real suffix reads as 139.88Q", sl.parse(12, "Netherite Sword", netheriteQ).price(), 139.88e15, 1e12);
        List<String> samurai = List.of("SWORD SKIN", "* Damage: 15.13T DMG", "* Price: $600 Money", "* Tier: 0/5 ★★★★★", "LOCKED", "Click to buy this Sword Skin");
        SwordSkinLore.Skin lk = sl.parse(13, "Samurai Sword", samurai);
        n += eq("samurai locked", lk != null && lk.locked() && !lk.equipped(), true);
        n += eq("samurai tier 0/5", lk != null ? lk.tier() + "/" + lk.tierMax() : null, "0/5");
        n += eq("samurai price 600", lk != null ? lk.price() : null, 600.0, 1e-9);
        n += eq("not a skin", sl.parse(1, "Rage Enchant", List.of("Level: 0 / 100", "Price: 20,000,000 Souls")) == null, true);
        n += eq("swords button by lore", sl.isSwordsButton("Swords", List.of("Click to view your swords")), true);
        n += eq("swords button by name", sl.isSwordsButton("Swords", List.of()), true);
        n += eq("tab is not the button", sl.isSwordsButton("SOULS", List.of("Soul enchants")), false);
        n += eq("skins title", sl.isSkinsTitle("Sword Skins"), true);
        n += eq("enchanter glyph title is not skins", sl.isSkinsTitle("\u00a7f\u00a7r"), false);
        List<SwordSkinLore.Skin> all = List.of(eq, lk);
        n += eq("looks like skins", SwordSkinLore.looksLikeSkins(all), true);
        n += eq("next buy is the equipped skin's tier", SwordSkinLore.nextBuy(all).name(), "Netherite Sword");
        SwordSkinLore.Skin maxed = sl.parse(12, "Netherite Sword", List.of("SWORD SKIN", "* Price: $1Q Money", "* Tier: 5/5", "EQUIPPED"));
        n += eq("maxed skin: next buy is the cheapest locked skin", SwordSkinLore.nextBuy(List.of(maxed, lk)).name(), "Samurai Sword");
        // The band: a glyph-mangled 139.88 can never override the 139.88Q ladder.
        n += eq("glyph price rejected against the target", SwordSkinLore.acceptMenuPrice(139.88, 139.88e15, 39.97e15, 3.5, 30), "rejected");
        n += eq("menu agrees with the target", SwordSkinLore.acceptMenuPrice(139.88e15, 139.88e15, null, 3.5, 30), "match");
        n += eq("menu is a ladder step from the last price", SwordSkinLore.acceptMenuPrice(139.88e15, null, 39.97e15, 3.5, 30), "ladder-match");
        n += eq("menu two steps up is rejected", SwordSkinLore.acceptMenuPrice(489.6e15, null, 39.97e15, 3.5, 30), "rejected");
        n += eq("nothing to check against", SwordSkinLore.acceptMenuPrice(1e9, null, null, 3.5, 30), "no-reference");
        n += eq("null price rejected", SwordSkinLore.acceptMenuPrice(null, 1e9, null, 3.5, 30), "rejected");
        n += eq("fresh swordMenuScoutEnabled", CFG.swordMenuScoutEnabled, true);
        n += eq("fresh swordMenuScoutChance", CFG.swordMenuScoutChance, 0.35, 1e-9);
        return n;
    }

    /** 0.9.37: eggs against the nearer milestone, saturation, hand purchases, fusable groups, one record per stage, config v41. */
    private static int companions0937() {
        int n = 0;

        // --- 2026-09-05 00:05:21 lvl18: a 387.18N batch, 92 % of the balance, passed 0.9.36's sooner rule on a
        // stage-18 zone gap of ~5,800N that was never going to come before the 478.3N rebirth 63 min away.
        Economy.Inputs s18 = new Economy.Inputs();
        s18.bal = 422.58 * N;
        s18.rebirthTarget = 478.3 * N;
        s18.incomePerMin = 17.0 * N;
        s18.zoneTarget = 5784.0 * N;
        s18.zoneFloor = 105.17 * N;
        s18.swordTarget = 232.73 * N;
        s18.swordFloor = 66.5 * N;
        s18.medianTtkMs = 85_495.0;
        s18.stageMaxTtkMs = 348_431.0;
        s18.stageKills = 5;
        s18.patienceMs = 9_800;
        s18.companionStage = 18;
        s18.companionLastBoughtStage = 17;
        s18.companionBatchPrice = 387.18 * N;
        s18.companionGain = 1.89;
        s18.companionCycleMin = 45;
        s18.now = 1_000L;
        Decision d18 = Economy.decide(s18);
        n += eq("00:05:21 not sooner on an unreachable zone", "companion-sooner".equals(d18.reason()), false);
        n += eq("00:05:21 the rebirth is the milestone",
            Economy.companionMilestone(5784.0 * N - 422.58 * N, 478.3 * N - 422.58 * N, false), "rebirth");
        Double delay18 = Economy.companionDelayMin(387.18 * N, 422.58 * N, 478.3 * N, 17.0 * N, 1.89);
        n += eq("00:05:21 delay ~10.5 min", delay18, 10.5, 0.3);
        n += eq("00:05:21 the payback rule takes it (Drew: keep the rule)", d18.reason(), "companion-persist");
        s18.incomePerMin = 12.0 * N;
        n += eq("00:05:21 at 12N/min it is past the budget", Economy.decide(s18).eggs(), "horizon");

        // The zone is the milestone while its gap is the smaller one.
        n += eq("zone nearer", Economy.companionMilestone(10.0, 50.0, false), "zone");
        n += eq("zone stopped: rebirth", Economy.companionMilestone(10.0, 50.0, true), "rebirth");
        n += eq("rebirth unknown: zone", Economy.companionMilestone(10.0, null, false), "zone");
        n += eq("rebirth covered: zone", Economy.companionMilestone(10.0, 0.0, false), "zone");
        n += eq("nothing known", Economy.companionMilestone(null, null, false) == null, true);
        // 20:40 lvl17: the rebirth (1.66N away) is nearer than the zone (91N) - the batch is sooner to it? No:
        // 7.41N > 1.66N x 0.5, so it is the persist rule that buys, and it still buys.
        Decision d17 = Economy.decide(lvl17());
        n += eq("20:40 still bought", d17.actsCompanion(), true);
        n += eq("20:40 via the payback rule now", d17.reason(), "companion-persist");

        // --- Saturation: the last bought visit changed nothing on this stage.
        Economy.Inputs sat = lvl17();
        sat.companionStageSaturated = true;
        Decision ds = Economy.decide(sat);
        n += eq("saturated stage: refused", ds.actsCompanion(), false);
        n += eq("... and says so", ds.eggs(), "saturated");
        n += eq("... on the economy's own hold", ds.reason(), "sword-hard-unaffordable");

        // --- Hand purchases: 00:06:13, 422.58N -> 35.61N at 128.99N an egg.
        n += eq("3 eggs by hand", Economy.companionObservedCount(386.97 * N, 128.99 * N, 3.0), 3);
        n += eq("1 egg by hand", Economy.companionObservedCount(47.25 * O, 47.25 * O, 3.0), 1);
        n += eq("10 eggs by hand", Economy.companionObservedCount(1289.9 * N, 128.99 * N, 3.0), 10);
        n += eq("not an egg multiple", Economy.companionObservedCount(100.0 * O, 47.25 * O, 3.0) == null, true);
        n += eq("2 eggs is not an option", Economy.companionObservedCount(94.5 * O, 47.25 * O, 3.0) == null, true);
        n += eq("outside the tolerance", Economy.companionObservedCount(50.0 * O, 47.25 * O, 3.0) == null, true);
        n += eq("no price: nothing", Economy.companionObservedCount(50.0 * O, null, 3.0) == null, true);

        // --- Fusable groups from the 23:46:53 fusion dump: 7 Eagle Basic z2s3, 6 Lizard Rare z2s3, 4 + 4 others.
        List<CompanionLore.Companion> dump = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) dump.add(new CompanionLore.Companion(38 + i, "Eagle Companion", 2, 3, 618.18, "Basic"));
        for (int i = 0; i < 6; i++) dump.add(new CompanionLore.Companion(27 + i, "Lizard Companion", 2, 3, 927.27, "Rare"));
        for (int i = 0; i < 4; i++) dump.add(new CompanionLore.Companion(12 + i, "Lizard Companion", 2, 5, 3040.0, "Rare"));
        for (int i = 0; i < 4; i++) dump.add(new CompanionLore.Companion(16 + i, "Eagle Companion", 2, 5, 2030.0, "Basic"));
        dump.add(new CompanionLore.Companion(24, "Donkey Companion", 2, 2, 1020.0, "Legendary"));
        List<CompanionLore.FuseGroup> groups = CompanionLore.fuseGroups(dump, 5);
        n += eq("two groups at 5+", groups.size(), 2);
        n += eq("largest first", groups.get(0).name() + " " + groups.get(0).count(), "Eagle Companion 7");
        n += eq("second", groups.get(1).name() + " " + groups.get(1).count(), "Lizard Companion 6");
        n += eq("stage separates identity", CompanionLore.fuseGroups(dump, 4).size(), 4);
        n += eq("rarity separates identity",
            CompanionLore.fuseGroups(List.of(
                new CompanionLore.Companion(1, "Snake Companion", 2, 3, 1240.0, "Epic"),
                new CompanionLore.Companion(2, "Snake Companion", 2, 3, 1240.0, "Epic"),
                new CompanionLore.Companion(3, "Snake Companion", 2, 3, 683.0, "Basic")), 2).size(), 1);
        n += eq("empty list", CompanionLore.fuseGroups(List.of(), 5).isEmpty(), true);

        // --- One stage record per stage.
        n += eq("respawn on the same stage keeps the record", Economy.stageRecordRolls("respawn-broadcast", 16, 16), false);
        n += eq("respawn with the stage unknown keeps it", Economy.stageRecordRolls("respawn-broadcast", null, 16), false);
        n += eq("respawn on a new level rolls", Economy.stageRecordRolls("respawn-broadcast", 16, 17), true);
        n += eq("teleport rolls", Economy.stageRecordRolls("teleport", 16, null), true);
        n += eq("boss level moved rolls", Economy.stageRecordRolls("bossbar-level", 16, 17), true);
        n += eq("boss level unchanged keeps it", Economy.stageRecordRolls("bossbar-level", 16, 16), false);
        n += eq("sidebar row rolls", Economy.stageRecordRolls("sidebar-row", 16, 16), true);

        // --- Config v41: the multiplier pattern reaches a live config at last; hand-set values survive.
        n += eq("fresh fuse on", CFG.companionFuseEnabled, true);
        n += eq("fresh fuse group", CFG.companionFuseMinGroup, 5);
        n += eq("fresh multiplier pattern reads a suffixed multiplier",
            loose(CFG.companionMultiplierPattern).matcher("Multiplier: 3.04Kx Money").find(), true);
        try {
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("ycbot-cfg", ".json");
            java.nio.file.Files.writeString(tmp, "{\"configVersion\":40,\"companionMultiplierPattern\":\"/multiplier:\\\\s*(?<x>[\\\\d,.]+)\\\\s*x/\","
                + "\"companionFuseTitlePattern\":\"/^fuse companions\\\\b/\"}");
            YCBotChallengeConfig c40 = YCBotChallengeConfig.load(tmp);
            n += eq("v40 migrates to current", c40.configVersion, YCBotChallengeConfig.CURRENT_CONFIG_VERSION);
            n += eq("v40 stale multiplier pattern replaced", c40.companionMultiplierPattern, CFG.companionMultiplierPattern);
            n += eq("v40 stale fuse title replaced", c40.companionFuseTitlePattern, CFG.companionFuseTitlePattern);
            java.nio.file.Files.writeString(tmp, "{\"configVersion\":40,\"companionMultiplierPattern\":\"/my mult/\"}");
            n += eq("hand-set multiplier pattern survives", YCBotChallengeConfig.load(tmp).companionMultiplierPattern, "/my mult/");
            java.nio.file.Files.deleteIfExists(tmp);
        } catch (Exception ex) {
            System.err.println("FAIL v41 migration: " + ex);
            n++;
        }
        try {
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("ycbot-state", ".json");
            java.nio.file.Files.deleteIfExists(tmp);
            StateStore s = new StateStore(tmp);
            StateStore.Entry e = new StateStore.Entry();
            e.companionSaturatedStage = 16;
            s.put("Ihazekids69420", e);
            n += eq("saturated stage round-trips", new StateStore(tmp).get("ihazekids69420").companionSaturatedStage, 16);
            java.nio.file.Files.deleteIfExists(tmp);
        } catch (Exception ex) {
            System.err.println("FAIL saturated state: " + ex);
            n++;
        }
        return n;
    }

    /** 0.9.37: GG waves and perk pulls - the parse, and what must never match. */
    private static int gg0937() {
        int n = 0;
        n += eq("wave who", ChatClassifier.ggWho("\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588 Thank you BoostedWalrus for"), "BoostedWalrus");
        n += eq("wave what", ChatClassifier.ggWhat("\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588 They purchased 5,500 Credits."), "5,500 Credits");
        n += eq("wave what bundle", ChatClassifier.ggWhat("\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588 They purchased Monthly Plus Sub."), "Monthly Plus Sub");
        n += eq("no who on the header", ChatClassifier.ggWho("\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588 GG WAVE ACTIVATED!") == null, true);
        n += eq("no what on a reply", ChatClassifier.ggWhat("CopingOpossum61: !GG!") == null, true);
        String perk = "EnchantedMC \u00bb thla_ has just pulled Universal Perk 5 on their Sword! (7235 total rolls)";
        n += eq("perk who", ChatClassifier.perkWho(perk), "thla_");
        n += eq("perk what", ChatClassifier.perkWhat(perk), "Universal Perk 5");
        Pattern wave = loose(CFG.ggWavePatterns.get(0));
        Pattern perkRe = loose(CFG.ggPerkPatterns.get(0));
        n += eq("wave header matches", wave.matcher("\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588 GG WAVE ACTIVATED!").find(), true);
        n += eq("wave header is a server line", ChatClassifier.isPlayerOrBroadcast("\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588 GG WAVE ACTIVATED!"), false);
        n += eq("perk line matches", perkRe.matcher(perk).find(), true);
        n += eq("perk 3 does not", perkRe.matcher("EnchantedMC \u00bb thla_ has just pulled Universal Perk 3 on their Sword! (6825 total rolls)").find(), false);
        n += eq("master perk does not", perkRe.matcher("EnchantedMC \u00bb JackkPowell has just pulled Master Perk 4 on their Sword! (5369 total rolls)").find(), false);
        n += eq("a player's gg is neither", wave.matcher("LiterallyWorst: !GG!").find() || perkRe.matcher("LiterallyWorst: !GG!").find(), false);
        n += eq("a player talking about it is neither",
            perkRe.matcher("[\u2727R46\u2727] [\u2623danger\u2623] KeinLanMehr \u00bb bro got uni 5 and rolled it gg").find(), false);
        n += eq("a player quoting the header is a player line",
            ChatClassifier.isPlayerOrBroadcast("[\u2727R46\u2727] KeinLanMehr \u00bb gg wave activated"), true);
        n += eq("fresh gg on", CFG.ggEnabled, true);
        n += eq("fresh wave chance", CFG.ggWaveChance, 0.85, 1e-9);
        n += eq("fresh perk chance", CFG.ggPerkChance, 0.5, 1e-9);
        n += eq("fresh gap", CFG.ggMinGapMs, 60_000);
        return n;
    }

    /** 0.9.37: the climb - the fresh-stage prediction, the server-quoted cap, the snowball notice. */
    private static int climb0937() {
        int n = 0;
        // Climb 4, lvl11 leg (19:26:51): patience 7.2 s, the first Llama predicted at 74 s two seconds
        // after the tag. 0.9.36 read the gate UNKNOWN until the 30 s fallback eval.
        Economy.GateResult g = Economy.zoneGate(null, 0, null, 2_000, 7_210, null, 74_000.0, 3.0);
        n += eq("fresh stage, fresh 74 s prediction: HARD", g.name() + " " + g.via(), "hard predicted-fresh");
        n += eq("under the margin: unknown", Economy.zoneGate(null, 0, null, 2_000, 7_210, null, 15_000.0, 3.0).name(), "unknown");
        n += eq("after a kill the prediction arm is off", Economy.zoneGate(null, 1, 5_000.0, 2_000, 7_210, null, 74_000.0, 3.0).name(), "unknown");
        n += eq("mult 0 disables it", Economy.zoneGate(null, 0, null, 2_000, 7_210, null, 74_000.0, 0).name(), "unknown");
        n += eq("the six-arg gate is unchanged", Economy.zoneGate(null, 0, null, 2_000, 7_210, null).name(), "unknown");
        n += eq("cook past the patience still wins", Economy.zoneGate(null, 0, null, 8_000, 7_210, null, 74_000.0, 3.0).via(), "cook");
        Economy.Inputs fresh = new Economy.Inputs();
        fresh.bal = 5.0e12; fresh.swordTarget = 1.0e12; fresh.swordFloor = 0.3e12; fresh.zoneFloor = 0.1e12; fresh.zoneSeeded = true;
        fresh.patienceMs = 7_210; fresh.stageKills = 0; fresh.cookElapsedMs = 2_000; fresh.freshPredictedTtkMs = 74_000.0;
        fresh.rebirthTarget = 1.0e30; fresh.incomePerMin = 1.0e12; fresh.now = 1_000L;
        Decision d = Economy.decide(fresh);
        n += eq("... so the sword goes out two seconds in", d.action() + " " + d.kind() + " " + d.reason(), "buy sword sword-hard");

        // A server-quoted price collapses the 60 s cap; a prediction does not.
        n += eq("quoted: floor", Economy.effectiveCooldownMs(60_000, 1_100, 5.0, 10.0, 3.0, true), 1_100);
        n += eq("not quoted, not rich: cap", Economy.effectiveCooldownMs(60_000, 1_100, 5.0, 10.0, 3.0, false), 60_000);
        n += eq("rich: floor as before", Economy.effectiveCooldownMs(60_000, 1_100, 50.0, 10.0, 3.0, false), 1_100);
        n += eq("snowball", Economy.snowball(50.0, 10.0, 3.0), true);
        n += eq("not a snowball", Economy.snowball(20.0, 10.0, 3.0), false);
        n += eq("unknown price: no snowball", Economy.snowball(20.0, null, 3.0), false);
        n += eq("fresh snowball notice", CFG.buyNoticeSnowballMinMs + "-" + CFG.buyNoticeSnowballMaxMs, "500-3000");
        n += eq("fresh quote relax", CFG.serverQuoteRelaxMs, 300_000);
        n += eq("fresh fresh-stage mult", CFG.stageProbePredictedMult, 3.0, 1e-9);
        return n;
    }

    /** 0.9.37: the no-hit click spam - hologram names, the approach gate, the aim-path expiry. */
    private static int targeting0937() {
        int n = 0;
        n += eq("damage number is not a name", Economy.hologramNameUsable("\u2727293.89QQ\u2727 Critical"), false);
        n += eq("money drop is not a name", Economy.hologramNameUsable("+4.77T Money"), false);
        n += eq("afk marker is not a name", Economy.hologramNameUsable("\u27E1332.12B\u27E1"), false);
        n += eq("a plate is a name", Economy.hologramNameUsable("LVL7 Donkey \u2764" + "69B"), true);
        n += eq("a bracketed plate is a name", Economy.hologramNameUsable("[AFKMOB] LVL9 Mooshroom \u2764\u221e"), true);
        n += eq("a companion label is a name", Economy.hologramNameUsable("Lizard Companion ()"), true);
        n += eq("blank is not", Economy.hologramNameUsable("  "), false);
        n += eq("null is not", Economy.hologramNameUsable(null), false);

        n += eq("closing in, facing it: swing", Economy.approachClickAllowed(4.5, 2.8, 10.0, 25.0), true);
        n += eq("inside reach: no swing", Economy.approachClickAllowed(2.5, 2.8, 10.0, 25.0), false);
        n += eq("facing away: no swing", Economy.approachClickAllowed(4.5, 2.8, 65.0, 25.0), false);
        n += eq("aim gate 0 = off", Economy.approachClickAllowed(4.5, 2.8, 65.0, 0), true);

        n += eq("a path inside its duration is alive", Economy.pathExpired(1_000, 400, 1_300, 500), false);
        n += eq("a path past duration + grace is dead", Economy.pathExpired(1_000, 400, 2_000, 500), true);
        n += eq("no path", Economy.pathExpired(0, 400, 2_000, 500), false);
        n += eq("fresh no-connect timeout", CFG.noConnectTimeoutMs, 4000);
        n += eq("fresh ignore-after", CFG.noConnectIgnoreAfter, 2);
        n += eq("fresh approach aim gate", CFG.approachClickMaxAimDeg, 25.0, 1e-9);
        return n;
    }

    /** 0.9.37: the progress record - minutes to lvl14, the cycle history round-trip. */
    private static int progress0937() {
        int n = 0;
        // Climb 4: lvl1 0.5, lvl4 2.0, lvl7 0.8, lvl9 1.0, lvl11 1.8, lvl12 1.5, lvl13 1.7, lvl14 5.1, lvl15 22.7 ...
        int[] st = {1, 4, 7, 9, 11, 12, 13, 14, 15};
        double[] mn = {0.5, 2.0, 0.8, 1.0, 1.8, 1.5, 1.7, 5.1, 22.7};
        n += eq("minutes to lvl14", Economy.minutesToStage(st, mn, 14), 9.3, 1e-9);
        n += eq("minutes to lvl15", Economy.minutesToStage(st, mn, 15), 14.4, 1e-9);
        n += eq("never reached", Economy.minutesToStage(st, mn, 18) == null, true);
        n += eq("mismatched arrays", Economy.minutesToStage(new int[]{1}, new double[]{1, 2}, 14) == null, true);
        try {
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("ycbot-state", ".json");
            java.nio.file.Files.deleteIfExists(tmp);
            StateStore s = new StateStore(tmp);
            StateStore.Entry e = new StateStore.Entry();
            StateStore.StageEntry se = new StateStore.StageEntry();
            se.stage = 17; se.onMin = 23.1; se.wallMin = 23.1; se.kills = 56; se.swordBuys = 3; se.moneyEarned = 138.66 * N; se.peakPerMin = 17.01 * N;
            StateStore.CycleEntry c = new StateStore.CycleEntry();
            c.rebirths = 13; c.endedAt = 1_788_556_000_000L; c.onMin = 60.3; c.wallMin = 62.1; c.toLvl14OnMin = 9.5; c.topStage = 17;
            c.stages = new java.util.ArrayList<>(List.of(se));
            e.cycleHistory = new java.util.ArrayList<>(List.of(c));
            e.cycleStages = new java.util.ArrayList<>(List.of(se));
            s.put("Ihazekids69420", e);
            StateStore.Entry r = new StateStore(tmp).get("ihazekids69420");
            n += eq("history round-trips", r.cycleHistory.size(), 1);
            n += eq("cycle minutes", r.cycleHistory.get(0).onMin, 60.3, 1e-9);
            n += eq("cycle to lvl14", r.cycleHistory.get(0).toLvl14OnMin, 9.5, 1e-9);
            n += eq("cycle stages", r.cycleHistory.get(0).stages.get(0).stage, 17);
            n += eq("stage money", r.cycleHistory.get(0).stages.get(0).moneyEarned, 138.66 * N, 1e27);
            n += eq("in-progress stages round-trip", r.cycleStages.get(0).kills, 56);
            java.nio.file.Files.deleteIfExists(tmp);
        } catch (Exception ex) {
            System.err.println("FAIL cycle history: " + ex);
            n++;
        }
        return n;
    }

    private static int eq(String name, double a, double b, double eps) {
        if (Math.abs(a - b) > eps) {
            System.err.println("FAIL " + name + ": " + a + " != " + b);
            return 1;
        }
        return 0;
    }

    private static int eq(String name, Double a, double b, double eps) {
        if (a == null || Math.abs(a - b) > eps) {
            System.err.println("FAIL " + name + ": " + a + " != " + b);
            return 1;
        }
        return 0;
    }

    private static int eq(String name, Object a, Object b) {
        if (a == null ? b != null : !a.equals(b)) {
            System.err.println("FAIL " + name + ": " + a + " != " + b);
            return 1;
        }
        return 0;
    }
}

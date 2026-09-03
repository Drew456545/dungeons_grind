package dev.drew.ycbotchallenge;

import java.util.List;
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
        // Server suffix order K M B T Q Qa Qi (Drew: T -> Q -> Qa); 0.9.19 fixes Qa = 1e18.
        n += eq("2Q", Amounts.parse("2Q"), 2e15, 1e6);
        n += eq("1.5Qa", Amounts.parse("1.5Qa"), 1.5e18, 1e9);
        n += eq("3Qi", Amounts.parse("3Qi"), 3e21, 1e12);
        n += eq("4Sx", Amounts.parse("4Sx"), 4e24, 1e15);
        n += eq("format Q", Amounts.format(1.25e15), "1.25Q");
        n += eq("format Qa", Amounts.format(1.5e18), "1.5Qa");
        n += eq("suffix of 1.25Qa", Amounts.suffixOf("1.25Qa"), "Qa");
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
        // Effective TTK: the DPS prediction wins, then the kill median, else unknown.
        n += eq("predicted wins", Economy.effectiveTtkMs(4_000.0, 12_000.0), 4_000.0, 1e-9);
        n += eq("median fallback", Economy.effectiveTtkMs(null, 12_000.0), 12_000.0, 1e-9);
        n += eq("unknown ttk", Economy.effectiveTtkMs(null, null) == null, true);

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

        // Policy: first upgradable affordable in slot order; maxed/locked skipped; attempted skipped.
        // Affordability uses the ITEM's price currency (0.9.11 spent essence against the souls balance).
        List<EnchantLore.Item> grid = List.of(g, r, m);
        java.util.Map<String, Double> rich = java.util.Map.of("souls", 8e6, "essence", 100.0);
        java.util.Map<String, Double> poor = java.util.Map.of("souls", 5e6, "essence", 1e12);
        n += eq("choose magnet at 8M souls", EnchantLore.chooseEnchant(grid, rich, "souls", java.util.Set.of()) == m, true);
        n += eq("choose none at 5M souls (essence irrelevant)",
            EnchantLore.chooseEnchant(grid, poor, "souls", java.util.Set.of()) == null, true);
        n += eq("choose skips attempted",
            EnchantLore.chooseEnchant(grid, rich, "souls", java.util.Set.of("Soul Magnet Enchant")) == null, true);
        n += eq("choose rage once unlocked",
            EnchantLore.chooseEnchant(List.of(g, r50, m), java.util.Map.of("souls", 25e6), "souls", java.util.Set.of()) == r50, true);
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

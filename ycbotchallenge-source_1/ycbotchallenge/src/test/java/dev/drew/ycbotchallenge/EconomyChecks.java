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
            Economy.chooseBuyKind(true, false, false, true, null, 2.5e9, 1.25), null);
        n += eq("closed gate: sword affordable → sword",
            Economy.chooseBuyKind(true, false, true, true, 1.24e9, 2.5e9, 1.25), "sword");
        n += eq("closed gate: HUD prefers sword",
            Economy.preferredKind(true, false, null, null, 1.25), "sword");

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

    private static int choose() {
        int n = 0;
        n += eq("both affordable sword cheaper → sword",
            Economy.chooseBuyKind(true, true, true, true, 10e9, 10e9, 1.25), "sword");
        n += eq("both affordable sword 2x zone → zone",
            Economy.chooseBuyKind(true, true, true, true, 20e9, 10e9, 1.25), "zone");
        n += eq("sword just over 1.25x → zone",
            Economy.chooseBuyKind(true, true, true, true, 12.6e9, 10e9, 1.25), "zone");
        n += eq("sword exactly 1.25x → sword",
            Economy.chooseBuyKind(true, true, true, true, 12.5e9, 10e9, 1.25), "sword");
        n += eq("zone unaffordable → sword",
            Economy.chooseBuyKind(true, true, true, false, 20e9, 10e9, 1.25), "sword");
        n += eq("only zone affordable → zone",
            Economy.chooseBuyKind(true, true, false, true, 20e9, 10e9, 1.25), "zone");
        n += eq("neither → wait",
            Economy.chooseBuyKind(true, true, false, false, 20e9, 10e9, 1.25), null);
        n += eq("preferZone true", Economy.preferZone(20e9, 10e9, 1.25), true);
        n += eq("preferZone false", Economy.preferZone(10e9, 10e9, 1.25), false);
        n += eq("preferZone nulls", Economy.preferZone(null, 10e9, 1.25), false);
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
        n += eq("chooser still sword when asked",
            Economy.chooseBuyKind(true, true, true, true, 10e9, 10e9, 1.25), "sword");
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

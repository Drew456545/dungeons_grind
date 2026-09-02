package dev.drew.ycbotchallenge;

import java.util.List;
import java.util.Map;

/** Standalone checks for sidebar bals, fail-chat targets, and TTK readiness. */
public final class EconomyChecks {
    private EconomyChecks() {}

    public static void main(String[] args) {
        int n = 0;
        n += amounts();
        n += sidebar();
        n += targets();
        n += readiness();
        n += choose();
        if (n > 0) {
            System.err.println(n + " failed");
            System.exit(1);
        }
        System.out.println("EconomyChecks ok");
    }

    private static int amounts() {
        int n = 0;
        n += eq("131.56B", Amounts.parse("131.56B"), 131.56e9, 1e3);
        n += eq("204.88M", Amounts.parse("204.88M"), 204.88e6, 1);
        n += eq("235 SHARDS not suffix", Amounts.parse("235 SHARDS"), 235.0, 1e-6);
        n += eq("52 CREDITS", Amounts.parse("52 CREDITS"), 52.0, 1e-6);
        n += eq("format B", Amounts.format(131.56e9), "131.56B");
        return n;
    }

    private static int sidebar() {
        int n = 0;
        List<String> currencies = List.of("money", "souls", "essence", "shards", "credits");
        List<String> lines = List.of(
            "§c| §a131.56B §2MONEY",
            "| 204.88M SOULS",
            "| 35.78M ESSENCE",
            "| 235 SHARDS",
            "| 52 CREDITS",
            "§x§f§f§a§a§0§0| 75.1B MONEY"
        );
        Map<String, SidebarParser.Hit> hits = SidebarParser.parseCurrencies(lines, currencies);
        n += eq("money parsed", hits.get("money").value(), 131.56e9, 1e3);
        n += eq("souls parsed", hits.get("souls").value(), 204.88e6, 1);
        n += eq("essence parsed", hits.get("essence").value(), 35.78e6, 1);
        n += eq("shards parsed", hits.get("shards").value(), 235.0, 1e-6);
        n += eq("credits parsed", hits.get("credits").value(), 52.0, 1e-6);
        n += eq("hex color money",
            SidebarParser.parseLine("§x§f§f§a§a§0§0| 75.1B MONEY", currencies).value(), 75.1e9, 1e3);

        SidebarParser.Hit label = SidebarParser.parseLine("MONEY: 75.1B", currencies);
        n += eq("label-first", label != null ? label.value() : -1, 75.1e9, 1e3);
        return n;
    }

    private static int targets() {
        int n = 0;
        String purchase = "You don't have enough money to purchase any sword upgrades! "
            + "You need 277.81B Money to purchase the next sword upgrade.";
        n += eq("purchase is not gap", Economy.isGapNeed(purchase), false);
        Double abs = Economy.targetFromFail(277.81e9, 131.56e9, purchase);
        n += eq("absolute target", abs, 277.81e9, 1e3);
        n += eq("not bal+amt", abs.equals(131.56e9 + 277.81e9), false);

        String gap = "You need 96.56B more Money for the next sword upgrade.";
        n += eq("more is gap", Economy.isGapNeed(gap), true);
        n += eq("gap target", Economy.targetFromFail(96.56e9, 131.56e9, gap), 228.12e9, 1e3);
        return n;
    }

    private static int readiness() {
        int n = 0;
        n += eq("no ttk", Economy.zoneReadiness(null, 40_000.0, 2000), 0.0, 1e-9);
        n += eq("fresh 40s", Economy.zoneReadiness(40_000.0, 40_000.0, 2000), 0.0, 1e-9);
        n += eq("ready 2s", Economy.zoneReadiness(2_000.0, 40_000.0, 2000), 1.0, 1e-9);
        double mid = Economy.zoneReadiness(8_944.0, 40_000.0, 2000); // ~sqrt(40s*2s) ≈ 8.94s → R≈0.5
        n += eq("log midpoint ~9s", mid, 0.5, 0.03);
        n += eq("high-sword baseline", Economy.zoneReadiness(2_100.0, 2_200.0, 2000), 1.0, 1e-9);
        n += eq("4s on 40s", Economy.zoneReadiness(4_000.0, 40_000.0, 2000) > 0.7, true);
        return n;
    }

    private static int choose() {
        int n = 0;
        n += eq("fresh both affordable → sword",
            Economy.chooseBuyKind(true, true, true, true, 0.1, 0.5), "sword");
        n += eq("late both affordable → zone",
            Economy.chooseBuyKind(true, true, true, true, 0.8, 0.5), "zone");
        n += eq("late zone unaffordable → sword",
            Economy.chooseBuyKind(true, true, true, false, 0.8, 0.5), "sword");
        n += eq("early only-zone affordable → wait",
            Economy.chooseBuyKind(true, true, false, true, 0.2, 0.5), null);
        n += eq("high-sword only-zone → zone",
            Economy.chooseBuyKind(true, true, false, true, 1.0, 0.5), "zone");
        n += eq("neither → wait",
            Economy.chooseBuyKind(true, true, false, false, 0.9, 0.5), null);
        n += eq("prefer late zone",
            Economy.preferredKind(true, true, 0.8), "zone");
        n += eq("prefer early sword",
            Economy.preferredKind(true, true, 0.2), "sword");
        return n;
    }

    private static int eq(String name, double a, double b, double eps) {
        if (Math.abs(a - b) > eps) {
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

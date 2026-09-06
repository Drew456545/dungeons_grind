package dev.drew.ycbotchallenge;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;

/**
 * 0.9.47: the evidence net for /heroes. A hero (Drew's Archer Queen, from the credit
 * shop) is a summoned ally that kills mobs for money; the server says only "Your hero
 * has been spawned." and shows the rest on a nameplate: "Archer Queen [heart]86" in the menu
 * at spawn, "Archer Queen [heart]23" a few minutes later in the zone (2026-09-06 00:57 local).
 * Nothing is clicked here: every few seconds the nearest plate matching
 * {@code heroPlatePattern} is logged when it appears, when its HP changes and when it
 * goes, so the decay rate, the lifetime and the cooldown can be read off the log before
 * a controller is written.
 */
public class HeroTracker {
    private final YCBotChallengeConfig cfg;
    private final StatsTracker stats;
    private EventLogger logger;
    private final Pattern plateRe;

    private String seenName;
    private String lastHp;
    private Double lastHpValue;
    private long firstSeenAt;
    private long lastHpAt;
    private long lastSeenAt;
    private int hpChanges;

    public HeroTracker(YCBotChallengeConfig cfg, StatsTracker stats) {
        this.cfg = cfg;
        this.stats = stats;
        this.plateRe = compile(cfg.heroPlatePattern);
    }

    public void setLogger(EventLogger logger) { this.logger = logger; }

    private void log(String type, Object... kv) { if (logger != null) logger.log(type, kv); }

    static Pattern compile(String p) {
        if (p == null || p.isBlank()) return null;
        String body = p.length() > 1 && p.startsWith("/") && p.endsWith("/") ? p.substring(1, p.length() - 1) : p;
        try {
            return Pattern.compile(body, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** name/hp from one plate line, or null. */
    static String[] parsePlate(Pattern re, String line) {
        if (re == null || line == null) return null;
        Matcher m = re.matcher(line.trim());
        if (!m.find()) return null;
        return new String[] { m.group("name").trim(), m.group("hp").trim() };
    }

    public String hudLine() {
        if (seenName == null) return null;
        return "hero " + seenName + " \u2764" + lastHp + " · " + (System.currentTimeMillis() - firstSeenAt) / 1000 + "s";
    }

    /** Called every few seconds, bot on or off. */
    public void tick(MinecraftClient client, long now) {
        if (!cfg.heroTrackEnabled || plateRe == null || client.player == null || client.world == null) return;
        String name = null, hp = null, type = null;
        double best = Double.MAX_VALUE;
        for (Entity e : client.world.getEntities()) {
            if (e == client.player) continue;
            double d = e.distanceTo(client.player);
            if (d > cfg.heroScanRadius || d >= best) continue;
            List<String> lines = CombatController.plateTextLines(e);
            if (e.getCustomName() != null) lines.add(e.getCustomName().getString());
            for (String line : lines) {
                String[] p = parsePlate(plateRe, line);
                if (p == null) continue;
                name = p[0]; hp = p[1]; best = d;
                try { type = Registries.ENTITY_TYPE.getId(e.getType()).toString(); } catch (Throwable t) { type = null; }
                break;
            }
        }
        if (name == null) {
            if (seenName != null && now - lastSeenAt >= cfg.heroGoneAfterMs) {
                log("hero_gone", "name", seenName, "lastHp", lastHp, "lifetimeMs", lastSeenAt - firstSeenAt,
                    "hpChanges", hpChanges, "sinceSpawnMs", stats.heroSpawnedAt != 0 ? now - stats.heroSpawnedAt : null);
                seenName = null; lastHp = null; lastHpValue = null; hpChanges = 0;
            }
            return;
        }
        lastSeenAt = now;
        Double v = Amounts.parse(hp);
        if (seenName == null || !seenName.equals(name)) {
            seenName = name; firstSeenAt = now; lastHp = hp; lastHpValue = v; lastHpAt = now; hpChanges = 0;
            log("hero_seen", "name", name, "hp", hp, "hpValue", v, "dist", Math.round(best * 10.0) / 10.0, "entityType", type,
                "sinceSpawnMs", stats.heroSpawnedAt != 0 ? now - stats.heroSpawnedAt : null);
            return;
        }
        if (!hp.equals(lastHp)) {
            Double perMin = v != null && lastHpValue != null && now > lastHpAt ? (lastHpValue - v) * 60_000.0 / (now - lastHpAt) : null;
            hpChanges++;
            log("hero_hp", "name", name, "hp", hp, "hpValue", v, "from", lastHp, "dropPerMin", perMin != null ? Math.round(perMin * 100.0) / 100.0 : null,
                "sinceSeenMs", now - firstSeenAt, "dist", Math.round(best * 10.0) / 10.0);
            lastHp = hp; lastHpValue = v; lastHpAt = now;
        }
    }
}

package dev.drew.ycbotchallenge;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.9.48: the hero pool model, lifted out of StatsTracker in 0.9.62 (only the hero spawner
 * and the plate tracker read it). The spawn / despawn / "needs N health" chat lines, the last
 * HP read and when, the learned regen and decay rates, and what they imply: whether the hero
 * is out, and where the pool stands now.
 */
public final class HeroPool extends BotModule {
    /** 0.9.47: "Your hero has been spawned." and any other server line about a hero (evidence). */
    public volatile long spawnedAt = 0;
    public volatile long despawnedAt = 0;
    public volatile long needsAt = 0;
    public volatile Integer needsHp = null;
    public volatile Double lastHp = null;
    public volatile long lastHpAt = 0;
    public volatile Double hpAtSpawn = null;
    public volatile Double regenPerMin = null;
    public volatile Double decayPerMin = null;
    private int regenSamples = 0;
    private final Pattern spawnRe;
    private final Pattern chatRe;
    private final Pattern despawnRe;
    private final Pattern needsRe;

    public HeroPool(YCBotChallengeConfig cfg) {
        spawnRe = Loose.compile(cfg.heroSpawnPattern);
        chatRe = Loose.compile(cfg.heroChatPattern);
        despawnRe = Loose.compile(cfg.heroDespawnPattern);
        needsRe = Loose.compile(cfg.heroNeedsPattern);
    }

    /** The hero is out: spawned after it last despawned, and not longer ago than its pool could last. */
    public boolean alive(long now) {
        if (spawnedAt == 0 || spawnedAt <= despawnedAt) return false;
        double decay = decayPerMin != null ? decayPerMin : 6.9;
        double hp = hpAtSpawn != null ? hpAtSpawn : 100;
        return now - spawnedAt < hp / Math.max(0.1, decay) * 60_000.0 + 60_000;
    }

    /** The pool now, from the last read and the regen rate (null before any read); 0 while the hero is out. */
    public Double predictedHp(long now, double maxHp) {
        if (alive(now)) return 0.0;
        if (lastHp == null || lastHpAt == 0) return null;
        double regen = regenPerMin != null ? regenPerMin : 1.95;
        return Economy.heroPredictedHp(lastHp, (now - lastHpAt) / 60_000.0, regen, maxHp);
    }

    /** A menu read of the pool while the hero is down: the model's anchor, and a regen sample against the previous anchor. */
    public void noteHp(double hp, Double max, long at, String via) {
        if (lastHp != null && lastHpAt != 0 && spawnedAt < lastHpAt && at - lastHpAt >= 60_000 && hp > lastHp) {
            double rate = (hp - lastHp) / ((at - lastHpAt) / 60_000.0);
            if (rate > 0 && rate < 30) {
                regenPerMin = regenPerMin == null ? rate : 0.5 * regenPerMin + 0.5 * rate;
                regenSamples++;
                log("hero_regen", "perMin", Num.r2(rate), "ema", Num.r2(regenPerMin),
                    "samples", regenSamples, "from", lastHp, "to", hp, "overMs", at - lastHpAt, "via", via);
            }
        }
        lastHp = hp;
        lastHpAt = at;
    }

    /** The hero lines: spawn, despawn (the pool at 0), the needs-N refusal, anything else with "hero". */
    public boolean onLine(String text, long now) {
        if (spawnRe != null && spawnRe.matcher(text).find()) {
            spawnedAt = now;
            log("hero_spawned", "raw", text, "sinceDespawnMs", despawnedAt != 0 ? now - despawnedAt : null);
            return true;
        }
        if (despawnRe != null && despawnRe.matcher(text).find()) {
            // 0.9.48: the pool is empty now - the model's best anchor.
            despawnedAt = now;
            lastHp = 0.0;
            lastHpAt = now;
            log("hero_despawned", "raw", text, "lifetimeMs", spawnedAt != 0 ? now - spawnedAt : null, "hpAtSpawn", hpAtSpawn);
            return true;
        }
        if (needsRe != null) {
            Matcher hm = needsRe.matcher(text);
            if (hm.find()) {
                needsAt = now;
                try { needsHp = Integer.parseInt(hm.group("n").replace(",", "")); } catch (RuntimeException ignored) { }
                log("hero_needs", "hp", needsHp, "raw", text);
                return true;
            }
        }
        if (chatRe != null && chatRe.matcher(text).find()) {
            log("hero_chat", "raw", text);
            return true;
        }
        return false;
    }

    public void noteSpawned(Double hp, long at) {
        if (hp != null) hpAtSpawn = hp;
        if (spawnedAt < at) spawnedAt = at;
    }

    public void noteDecay(double perMin, long at) {
        decayPerMin = decayPerMin == null ? perMin : 0.7 * decayPerMin + 0.3 * perMin;
    }
}

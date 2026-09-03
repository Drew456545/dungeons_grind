package dev.drew.ycbotchallenge;

import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;

/**
 * The Transcend ability (0.9.28): Q — the vanilla drop key — while the sword is held
 * activates it; the server prints "Your Transcend Ability has been activated (180s
 * Cooldown)" and "... has ended (180s Cooldown)" (20:35 log). Drew: use it
 * periodically, non-deterministically, must be holding the sword.
 *
 * The cooldown is read from the server's own line (fallback transcendCooldownMs).
 * Once it has passed, every kill rolls a hazard that ramps from zero over
 * transcendRampMs to transcendFullChance, so the press lands somewhere after the
 * cooldown, never on the dot. The press is one real key edge (the timesPressed
 * trick the attack and use keys use), only with the sword in hand and no screen
 * open. If the sword is not in the hand half a second later the press dropped it:
 * transcend_drop_suspect and an emergency stop so Drew can pick it up.
 */
public class TranscendController {
    private final YCBotChallengeConfig cfg;
    private final EnchantLore swordLore;
    private EventLogger logger;
    private final Pattern activeRe;
    private final Pattern endRe;
    private final Pattern cooldownRe;

    private volatile long activatedAt = 0;
    private volatile long endedAt = 0;
    private volatile Integer cooldownS = null;
    private long lastPressAt = 0;
    private long notBefore = 0;
    private long pendingCheckAt = 0;
    private int killsSeen = -1;
    private long lastSkipLogAt = 0;

    public TranscendController(YCBotChallengeConfig cfg, EnchantLore swordLore) {
        this.cfg = cfg;
        this.swordLore = swordLore;
        this.activeRe = RebirthLore.compileLoose(cfg.transcendActivePattern);
        this.endRe = RebirthLore.compileLoose(cfg.transcendEndPattern);
        this.cooldownRe = RebirthLore.compileLoose(cfg.transcendCooldownPattern);
    }

    public void setLogger(EventLogger logger) { this.logger = logger; }

    private void log(String type, Object... kv) {
        if (logger != null) logger.log(type, kv);
    }

    /** Bot enabled: a first press somewhere in the next minutes, not at once. */
    public void onEnable(long now, int kills) {
        notBefore = now + HumanTiming.logNormalMs(cfg.transcendFirstDelayMinMs,
            Math.max(cfg.transcendFirstDelayMinMs + 1, cfg.transcendFirstDelayMaxMs));
        killsSeen = kills;
        pendingCheckAt = 0;
    }

    /** "(180s Cooldown)" → 180, or null. Pure, tested. */
    public static Integer cooldownSecondsOf(String line, Pattern cooldownRe) {
        if (line == null) return null;
        Matcher m = cooldownRe.matcher(line);
        if (!m.find()) return null;
        try { return Integer.parseInt(m.group("s")); } catch (Exception e) { return null; }
    }

    /** Ready once the cooldown has elapsed since the later of the activation and our last press. Pure, tested. */
    public static boolean ready(long activatedAt, long lastPressAt, long cooldownMs, long notBefore, long now) {
        long since = Math.max(activatedAt, lastPressAt);
        return now >= notBefore && (since == 0 || now - since >= Math.max(0, cooldownMs));
    }

    /** Server chat (any line). */
    public void onChatLine(String clean) {
        if (clean == null || clean.isBlank()) return;
        if (activeRe.matcher(clean).find()) {
            activatedAt = System.currentTimeMillis();
            Integer s = cooldownSecondsOf(clean, cooldownRe);
            if (s != null) cooldownS = s;
            log("transcend_active", "cooldownS", s, "raw", clean);
        } else if (endRe.matcher(clean).find()) {
            endedAt = System.currentTimeMillis();
            Integer s = cooldownSecondsOf(clean, cooldownRe);
            if (s != null) cooldownS = s;
            log("transcend_end", "cooldownS", s, "raw", clean);
        }
    }

    private long cooldownMs() {
        Integer s = cooldownS;
        return s != null ? s * 1000L : Math.max(0, cfg.transcendCooldownMs);
    }

    /** Every tick while the bot runs (never yields). */
    public void tick(MinecraftClient client, CombatController combat) {
        if (client.player == null) return;
        long now = System.currentTimeMillis();
        if (pendingCheckAt != 0 && now >= pendingCheckAt) {
            pendingCheckAt = 0;
            if (!EnchantScreens.swordInHand(client, swordLore)) {
                log("transcend_drop_suspect", "afterMs", now - lastPressAt);
                combat.stopRequest = "transcend press left the hand empty — pick the sword up";
                return;
            }
        }
        if (!cfg.transcendEnabled) return;
        if (client.currentScreen != null) return;
        if (combat.kills == killsSeen) return; // one roll per kill
        killsSeen = combat.kills;
        long cd = cooldownMs();
        if (!ready(activatedAt, lastPressAt, cd, notBefore, now)) return;
        long readyAt = Math.max(Math.max(activatedAt, lastPressAt) + cd, notBefore);
        double hazard = Economy.visitHazard(now - readyAt, 0, Math.max(1000, cfg.transcendRampMs),
            cfg.transcendFullChance, 1.0, 1.0);
        if (ThreadLocalRandom.current().nextDouble() >= hazard) return;
        if (!EnchantScreens.swordInHand(client, swordLore)) {
            if (now - lastSkipLogAt > 60_000) {
                lastSkipLogAt = now;
                log("transcend_skip", "reason", "no-sword");
            }
            return;
        }
        KeyBinding drop = client.options.dropKey;
        dev.drew.ycbotchallenge.mixin.KeyBindingAccessor acc = (dev.drew.ycbotchallenge.mixin.KeyBindingAccessor) drop;
        acc.ycbotchallenge$setTimesPressed(acc.ycbotchallenge$getTimesPressed() + 1);
        lastPressAt = now;
        pendingCheckAt = now + 500;
        log("transcend_press", "sinceReadyMs", now - readyAt, "cooldownMs", cd, "hazard", Math.round(hazard * 1000.0) / 1000.0);
    }

    public String hudLine() {
        if (!cfg.transcendEnabled) return null;
        long since = Math.max(activatedAt, lastPressAt);
        if (since == 0) return null;
        long left = since + cooldownMs() - System.currentTimeMillis();
        return left > 0 ? "transcend: " + (left + 999) / 1000 + "s" : "transcend: ready";
    }
}

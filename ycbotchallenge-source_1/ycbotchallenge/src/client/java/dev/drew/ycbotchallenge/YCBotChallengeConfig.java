package dev.drew.ycbotchallenge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** JSON config at config/ycbotchallenge.json — created with defaults on first run. */
public class YCBotChallengeConfig {
    public double reach = 3.0;
    public double targetRange = 50.0;
    public int tapCooldownMs = 300;
    public int reactionDelayMinMs = 160;
    public int reactionDelayMaxMs = 480;
    public double idleChancePerMinute = 1.1;
    public int idleMinMs = 800;
    public int idleMaxMs = 3000;

    /**
     * Body language. When true the grind loop still tags one stationary mob
     * at a time, but the camera, keys, and timing stop looking like a state
     * machine: gaze wanders during cook, the mouse never parks, taps wait a
     * beat after arriving, WASD changes lag a tick, sprint doesn't start the
     * instant W is held. Disable to get the old metronomic loop back.
     */
    public boolean humanize = true;
    /** Scales the always-on camera drift/tremor (1.0 = default; 0 = off). */
    public double cameraNoiseScale = 1.0;
    /** Look-at-the-mob pause before the legs start, on a freshly spotted target. */
    public int noticeDelayMinMs = 90;
    public int noticeDelayMaxMs = 280;
    /** Extra wait after arriving in reach, before the tap. */
    public int tapHesitationMinMs = 70;
    public int tapHesitationMaxMs = 220;
    /** Chance of a longer "is this the one?" beat on top of tap hesitation. */
    public double tapHesitationLongChance = 0.08;
    /** Short hitches mid-walk (per minute). Keep modest or KPM tanks. */
    public double microPauseChancePerMinute = 2.2;
    public int microPauseMinMs = 120;
    public int microPauseMaxMs = 380;
    /**
     * How a cook wait is spent (remainder after efficient+watch = fidget).
     * Efficient = old pre-walk-to-next. Watch = stare at the tagged mob with
     * glances away. Fidget = look around the pad and occasional sidesteps.
     */
    public double cookEfficientChance = 0.42;
    public double cookWatchChance = 0.38;
    /** Max extra ticks before a WASD octant change is applied (0–this, rolled). */
    public int keyTransitionMaxTicks = 3;
    public boolean movement = true;
    /**
     * Sprint whenever we're running forward at the target and still more than
     * sprintMinDistance blocks OUTSIDE reach. Sprint is asserted directly on the
     * player (not via the sprint keybind, which misbehaves with "Sprint: Toggle")
     * and is dropped one tick before the tap so the hit is a normal, non-knockback
     * hit — a sprint-hit shoves the mob and trips the ghost filter.
     * Toggle in game with Shift + the bot key (Shift+G); the change is saved here.
     */
    public boolean sprint = true;
    public double sprintMinDistance = 1.0;
    /** Also hold jump while sprinting on long, aligned approaches (sprint-jumping is ~20% faster). Off by default. */
    public boolean sprintJump = false;
    public double sprintJumpMinDistance = 6.0;
    /**
     * Path variation: each new target gets a random camera lead of up to this
     * many degrees off-center (decays to 0 as you arrive so the tap lands).
     * Combined with strafe assist this produces a different curved approach
     * every time instead of identical straight lines. Scaled down with speed
     * effects (a 20 deg arc over a 0.6 s dash reads as jitter). 0 disables.
     */
    public double approachYawOffsetMaxDeg = 20.0;
    /**
     * 8-way approach: every tick the bot presses the W/A/S/D combo whose travel
     * heading (0, ±45, ±90, ±135, 180 deg off the camera) is nearest the target's
     * bearing, so it is always closing distance while the smooth camera swings
     * around — never standing still to turn, never running 45 deg wide of a mob
     * that is only 15 deg off-axis. The combo is held until the bearing is this
     * many degrees past the next boundary (hysteresis against key flicker).
     */
    public double moveHysteresisDeg = 5.0;
    /**
     * Coast into reach instead of braking on the line: W is released once the
     * remaining distance is under (current speed × this), which is roughly how
     * far you slide on the ground after letting go. Matters a lot with speed
     * effects — a Speed 10 player who stops dead on the 3-block line looks
     * like a bot; one who lets go early and rolls to a stop doesn't.
     */
    public double coastFactor = 1.2;
    /** Only sprint when within this many degrees of facing the target (sprint needs W held and alignment). */
    public double sprintAlignMaxDeg = 45.0;

    /**
     * Aim feel — one slider, 0.0 to 1.0.
     * Low (0.2) = heavy/lazy: slow turns, lots of momentum, eases in and out.
     * High (0.9) = agile: quick turns, still momentum-limited, never snaps.
     * Turn rate and acceleration both scale from this; there is no instant
     * rotation anywhere — every turn ramps up and coasts down.
     */
    public double aimAgility = 0.4;
    /** Stop micro-adjusting once within this many degrees of the target (humans don't pixel-track). */
    public double aimDeadzoneDeg = 3.2;
    /** Only tap the mob when actually looking at it — aim error must be under this. */
    public double aimTapMaxErrorDeg = 22.0;
    /**
     * Target choice = lowest estimated travel cost, in blocks:
     *   cost = distance + turnCostBlocks * (angle off camera / 180).
     * A turn costs a fixed amount of time regardless of how far the mob is, so
     * the penalty is ADDITIVE (the old multiplicative bias made a far mob behind
     * you look absurdly expensive and a near one absurdly cheap). 4 ≈ the blocks
     * you'd sprint in the time a full 180 takes at the default aimAgility.
     * This is at NORMAL walking speed — it scales automatically with the
     * player's movement-speed attribute (Speed X potions etc.), because with
     * Speed 10 you cover three times as much ground during the same turn.
     * 0 = pure nearest-mob.
     */
    public double turnCostBlocks = 4.0;
    /**
     * Random ±fraction applied to each candidate's cost when picking a target,
     * so near-ties resolve differently run to run instead of always walking the
     * same lap around the arena. 0 disables.
     */
    public double targetCostJitter = 0.15;
    /**
     * Rarity priority, in blocks: a mob of this rarity is worth walking this many
     * extra blocks for (subtracted from its cost). Matched case-insensitively
     * against the [RARITY] tag on the nameplate. Rarer mobs pay more but take
     * longer, so keep these modest — a LEGENDARY is worth ~12 blocks, not the arena.
     */
    public Map<String, Double> rarityBonusBlocks = Map.of(
        "UNCOMMON", 1.5, "RARE", 4.0, "EPIC", 8.0, "LEGENDARY", 12.0);
    /**
     * A tap starts a fight: the server puts up a boss health bar and
     * auto-attacks. The cook is over when THAT BAR expires — not when the
     * client entity despawns (corpses linger as ghosts). Next selection
     * starts only after the bar is gone. If no bar appears within
     * cookBarAppearMs we fall back to entity despawn.
     */
    public boolean cookDoneOnBossBar = true;
    public int cookBarAppearMs = 1500;
    /**
     * Extra title fragments that mean "event/boost bar, not cook HP".
     * Built-in filter already skips timers ((12m, 9s)), multipliers (2x),
     * and words like boost/harvest/event/vote/sale/party. Add server-specific
     * names here if a new event bar still gets grabbed.
     */
    public List<String> cookBarIgnorePatterns = List.of();
    /**
     * Stay this close to the tagged mob while the bar is up so the server
     * keeps auto-attacking. Rolled per tag. We do not walk off to the next
     * mob until the bar expires.
     */
    public boolean preAimNext = false;
    public int nextTargetRescanMs = 750;
    public double cookLeashMinBlocks = 2.0;
    public double cookLeashMaxBlocks = 4.0;

    /**
     * Ghost filter. Real dungeon mobs are ALWAYS stationary; client-side ghost
     * leftovers follow/orbit the player. Any mob that moves more than
     * ghostMotionBlocks (horizontal, cumulative) is blacklisted for the session
     * and never targeted; a current target that starts moving is dropped
     * immediately and a rescan happens on the next tick.
     */
    public boolean stationaryOnly = true;
    public double ghostMotionBlocks = 0.5;
    /** Watch a mob stand still for this many ticks before it becomes targetable. */
    public int minObservationTicks = 3;
    /**
     * Ignore an entity's motion for its first N ticks in view, and whenever it
     * is airborne. Stage respawns drop mobs in from above — spawn interpolation
     * and the fall itself would otherwise trip the ghost filter.
     */
    public int spawnGraceTicks = 20;
    /**
     * A blacklisted "ghost" that stays horizontally still this long is
     * un-blacklisted (real ghosts orbit/follow the player and never stop).
     * Set 0 to make ghosting permanent like before.
     */
    public double ghostRedemptionSeconds = 5.0;

    /**
     * Captcha auto-pause (no bypassing — the bot stops so you solve it).
     * A chat line matching captchaChatPatterns, or a container GUI opening
     * (the bot never opens GUIs itself, so one appearing mid-run means
     * the server put it there), disables grinding until you press the
     * toggle key again.
     */
    // Sonar's captcha prompt never says "captcha" — match its actual message.
    public List<String> captchaChatPatterns = List.of("enter the text in chat", "captcha");
    public boolean pauseOnContainerScreen = true;

    /**
     * Captcha auto-solve via a local Qwen3-VL served by vLLM (the sanctioned
     * hackathon hurdle). When enabled, a detected captcha pauses grinding,
     * captures the map (held map pixels > nearest item-frame map > full
     * screenshot), asks the local model, sends the answer to chat, and resumes.
     * On repeated failure it falls back to the old pause-for-human behavior.
     */
    public boolean captchaAutoSolve = true;
    public String captchaVlmEndpoint = "http://127.0.0.1:8000/v1/chat/completions";
    public String captchaVlmModel = "Qwen/Qwen3-VL-4B-Instruct-FP8";
    public String captchaPrompt =
        "This is a Sonar anti-bot map captcha from Minecraft: 3 or 4 lowercase "
        + "letters (a-z) drawn as thick, colored, distorted glyphs on a dark noisy "
        + "background with random curved lines crossing it. Ignore the curves and "
        + "noise; read the glyphs left to right. Watch for merged or overlapping "
        + "letters: adjacent glyphs can touch, and a doubled letter (like 'rr' or "
        + "'oo') can look like one wide glyph - always consider whether a wide or "
        + "odd glyph is actually the same letter twice. Give your best reading, "
        + "then ONE alternative reading. The ALT must NOT be the same string as "
        + "ANSWER — if you are fully confident, make the ALT the doubled/undoubled "
        + "variant of your reading (e.g. ANSWER 'rzx' -> ALT 'rrzx') or swap the "
        + "most ambiguous letter for its look-alike. "
        + "End with exactly these two lines:\n"
        + "ANSWER: <best guess>\nALT: <different second guess>\n"
        + "Each guess is 3-4 lowercase letters, no spaces.";
    /**
     * Appended to captchaPrompt on the SECOND model call, after the server
     * rejects a guess. {rejected} is replaced with the rejected guesses,
     * comma-separated. This is the "reconsider" prompt — tune it freely.
     */
    public String captchaRetryPrompt =
        "\nIMPORTANT: these guesses were already REJECTED as wrong: {rejected}. "
        + "Do NOT repeat them. The most common mistake is the letter count: if a "
        + "rejected guess has 3 letters, one glyph was probably a doubled letter - "
        + "give a 4-letter reading; if it has 4, two of them may be one wide glyph - "
        + "give a 3-letter reading. Also consider swapping the most ambiguous letter "
        + "for a look-alike. Your ANSWER and ALT must both differ from every "
        + "rejected guess.";
    /** "auto" (map if found, else screenshot), "map" (map only, retries if absent), or "screen" (always screenshot). */
    public String captchaCaptureMode = "auto";
    /** How the answer is sent: "{answer}" as plain chat, or e.g. "/captcha {answer}". */
    public String captchaAnswerTemplate = "{answer}";
    /** Wait for the map/screen to actually render before capturing. (Sonar's total budget is ~30s.) */
    public int captchaSettleMs = 1000;
    /** HTTP timeout for the local model call. */
    public int captchaTimeoutMs = 20000;
    /**
     * Max solve cycles for NON-answer failures only (capture failed, model
     * timeout — nothing was sent to chat). Chat answers are capped separately
     * and harder: primary + ALT, then hard stop. We never spam guesses.
     */
    public int captchaMaxAttempts = 3;
    /**
     * Hard cap on answers actually SENT TO CHAT per captcha. After this many
     * rejections the bot stops and pauses for the human — it never spams guesses.
     */
    public int captchaMaxAnswers = 2;
    /**
     * Human-ish pause between receiving a guess from the model and typing it
     * into chat (a person needs a moment to read the map and type). Applied to
     * every answer sent, jittered between min and max.
     */
    public int captchaAnswerDelayMinMs = 1200;
    public int captchaAnswerDelayMaxMs = 1900;
    /**
     * After answering: resume if no rejection message arrives within this window.
     * Sonar sends NOTHING on success (it silently transfers you), so silence = solved.
     */
    public int captchaVerifyWaitMs = 5000;
    /** Upscale factor for the 128x128 map image sent to the model. */
    public int captchaMapScale = 4;
    /** How far to look for an item-frame map holding the captcha. */
    public double captchaMapSearchRadius = 10.0;
    /** Chat lines meaning the captcha was accepted / rejected (plain substrings or /regex/). */
    public List<String> captchaSolvedPatterns = List.of("correct", "verified", "success", "thank");
    public List<String> captchaRetryPatterns = List.of("wrong answer", "incorrect", "try again", "invalid");
    /** Stricter: pause on ANY screen opening (including chat/inventory you open yourself). */
    public boolean pauseOnAnyScreen = false;
    /** Target the majority mob type in range — skips stale leftovers from the previous stage. */
    public boolean targetDominant = true;
    /** Dominant filtering only kicks in when the top mob type has at least this many alive in range. */
    public int minDominantPack = 3;
    /** Abandon a tagged fight if the boss bar hasn't expired after this long. Set ~2x your average time-to-kill. */
    public int maxCookMs = 90000;
    public boolean hud = true;
    public int hudX = 4;
    public int hudY = 4;
    public String runLabel = "baseline";
    public int statusIntervalSeconds = 5;

    // Optional zone bounding box; leave null for no constraint.
    public double[] zoneMin = null; // [x, y, z]
    public double[] zoneMax = null;

    // Parsing knobs (match the Node analyzer's expectations)
    public String rebirthsPattern = "Rebirths:\\s*([\\d,]+)";
    public String zonePattern = "Zone:\\s*(.+)";
    public String multiplierPattern = "Multiplier:\\s*(\\S+)";
    public String actionBarPattern = "Rebirth Progress:.*?\\(([\\d.]+)%\\)";
    public List<String> balancePatterns = List.of(
        "money|Money:\\s*\\$?(\\S+)",
        "souls|Souls:\\s*.?\\s*(\\S+)",
        "essence|Essence:\\s*.?\\s*(\\S+)",
        "shards|Shards:\\s*.?\\s*(\\S+)",
        "credits|Credits:\\s*.?\\s*(\\S+)"
    );
    public List<String> ascensionChatPatterns = List.of("ascend", "ascension");
    public List<String> prestigeChatPatterns = List.of("prestige");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static YCBotChallengeConfig load(Path file) {
        try {
            if (Files.exists(file)) {
                JsonObject raw = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                // Gson may skip field initializers; fill any keys added in a newer
                // build so humanize (etc.) doesn't silently come through as false/0.
                JsonObject def = GSON.toJsonTree(new YCBotChallengeConfig()).getAsJsonObject();
                boolean missing = false;
                for (var e : def.entrySet()) {
                    if (!raw.has(e.getKey())) {
                        raw.add(e.getKey(), e.getValue());
                        missing = true;
                    }
                }
                YCBotChallengeConfig cfg = GSON.fromJson(raw, YCBotChallengeConfig.class);
                if (missing) cfg.save(file);
                return cfg;
            }
        } catch (Exception e) {
            YCBotChallengeClient.LOGGER.warn("Failed to read config, using defaults: {}", e.toString());
        }
        YCBotChallengeConfig cfg = new YCBotChallengeConfig();
        cfg.save(file);
        return cfg;
    }

    public void save(Path file) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this));
        } catch (IOException e) {
            YCBotChallengeClient.LOGGER.warn("Failed to save config: {}", e.toString());
        }
    }
}

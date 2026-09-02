package dev.drew.ycbotchallenge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
    public int reactionDelayMinMs = 120;
    public int reactionDelayMaxMs = 350;
    public double idleChancePerMinute = 0.5;
    public int idleMinMs = 800;
    public int idleMaxMs = 3000;
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
     * Aim feel — one slider, 0.0 to 1.0. Scales one-shot mouse-path duration only.
     * The camera is never locked or written directly. 1.0 = a 30° snap in ~350ms.
     */
    public double aimAgility = 1.0;
    /** After a flick lands, only start a NEW correction flick if error exceeds this. */
    public double lookReacquireDeg = 3.0;
    /** Only click the mob when the actual camera is this close to the aim point (chicken hitbox is only a few deg). */
    public double aimTapMaxErrorDeg = 3.0;
    /** Sparse idle mouse noise while standing (not a tracking loop). */
    public boolean mouseIdleTremor = true;
    public double idleTremorChancePerSecond = 0.35;
    /**
     * Per-kill look style weights (normalized). Flick-next glances at the next
     * mob once; watch waits until death; scan pans; hesitate idles then commits.
     */
    public double trackStyleFlickNext = 0.45;
    public double trackStyleWatchThenFind = 0.30;
    public double trackStyleScan = 0.15;
    public double trackStyleHesitate = 0.10;
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
     * While a tagged mob cooks we can't tag another. Movement toward the next
     * mob is still leash-limited; the camera is a one-shot look intent (see
     * track-style weights), not a lock on nextTarget.
     */
    public boolean preAimNext = true;
    public int nextTargetRescanMs = 750;
    public double cookLeashMinBlocks = 2.0;
    public double cookLeashMaxBlocks = 4.0;

    /**
     * DPS-driven handoff. While a mob cooks we read its boss-bar HP, compute an
     * effective DPS from the HP slope, and only start looking for / walking to
     * the next mob once ETA = HP / DPS drops below this lead time (reaction +
     * flick + short walk). Before that we stay put and watch — no wandering off
     * a mob that's still far from dying.
     */
    public int handoffLeadMs = 700;
    /** If we've been cooking this long with NO boss-bar DPS signal at all, look for the next mob anyway (never stall). */
    public int handoffFallbackMs = 4000;
    /** Minimum boss-bar HP samples before trusting a DPS number. */
    public int dpsMinSamples = 3;
    /** DPS slope window (ms); older HP samples are dropped. */
    public int dpsWindowMs = 10000;

    /**
     * Click-to-connect. Once in reach and aimed, click at 5-8 cps until the
     * mob's boss bar appears (a hit landed). Missing is realistic, so we keep
     * clicking rather than widening the aim tolerance. Never faster than the
     * vanilla attack cooldown (see respectVanillaAttackCooldown).
     */
    public int clickCpsMin = 5;
    public int clickCpsMax = 8;
    /** Anticipatory swing spam while closing in on a target (mostly whiffs — that's the point). Set max 0 to disable. */
    public double approachClickCpsMin = 2;
    public double approachClickCpsMax = 3;
    /** Only start approach spam within this many blocks of the target. */
    public double approachClickMaxDist = 6.0;
    /** Hard ceiling: skip a click if the vanilla attack cooldown isn't ready. */
    public boolean respectVanillaAttackCooldown = true;

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
    /** Abandon a tagged mob that still hasn't died after this long (client-side ghost / unkillable). Set ~2x your average time-to-kill. */
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
    public String rebirthsPattern = "rebirths?\\s*:?\\s*([\\d,]+)";
    public String zonePattern = "Zone\\s*:?\\s*(.+)";
    public String multiplierPattern = "Multiplier:\\s*(\\S+)";
    public String actionBarPattern = "Rebirth Progress:.*?\\(([\\d.]+)%\\)";
    /**
     * Sidebar currency names (case-insensitive) parsed from rows like
     * {@code | 131.56B MONEY} / {@code | 235 SHARDS}.
     */
    public List<String> sidebarCurrencies = List.of("money", "souls", "essence", "shards", "credits", "swings");
    /** How often the canonical balance snapshot (HUD / logs / buy eval) is published. */
    public int scoreboardSnapshotMs = 5000;
    public List<String> balancePatterns = List.of(
        "souls|(?i)([\\d,.]+\\s*[A-Za-z]{0,4})\\s*SOULS\\b|SOULS\\s*:?\\s*.?\\s*([\\d,.]+\\s*[A-Za-z]{0,4})",
        "essence|(?i)([\\d,.]+\\s*[A-Za-z]{0,4})\\s*ESSENCE\\b|ESSENCE\\s*:?\\s*.?\\s*([\\d,.]+\\s*[A-Za-z]{0,4})",
        "shards|(?i)([\\d,.]+\\s*[A-Za-z]{0,4})\\s*SHARDS\\b|SHARDS\\s*:?\\s*.?\\s*([\\d,.]+\\s*[A-Za-z]{0,4})",
        "credits|(?i)([\\d,.]+\\s*[A-Za-z]{0,4})\\s*CREDITS\\b|CREDITS\\s*:?\\s*([\\d,.]+\\s*[A-Za-z]{0,4})"
    );
    public List<String> ascensionChatPatterns = List.of("ascend", "ascension");
    public List<String> prestigeChatPatterns = List.of("prestige");

    /** AFK upgrades: typed chat, only while standing still. */
    public boolean upgradesEnabled = true;
    public String swordCommand = "/swordmax";
    public String zoneCommand = "/zone max";
    /** Legacy: inert since the kill-driven scheduler replaced the fixed buy timer. */
    public int upgradePeriodMinMs = 132_000;
    public int upgradePeriodMaxMs = 240_000;
    /** Legacy; no longer used as a zone trigger (TTK readiness is the signal). */
    public int zoneEverySwordsMin = 5;
    public int zoneEverySwordsMax = 6;
    public int upgradeStopPauseMinMs = 200;
    public int upgradeStopPauseMaxMs = 800;
    public int typeKeyMinMs = 80;
    public int typeKeyMaxMs = 180;
    public int upgradeReadPauseMinMs = 400;
    public int upgradeReadPauseMaxMs = 900;
    /**
     * Once the money balance is known to cover the next upgrade cost, wait for at
     * least this many more kills before interrupting combat to type the command —
     * a player finishes a mob, then does the buy in the lull rather than snapping
     * to chat the instant money crosses the threshold.
     */
    public int minKillsAfterAffordable = 1;
    /** Sidebar key used for afford checks (chicken / money / souls...). */
    public String moneyCurrency = "money";
    /**
     * Strict, anchored upgrade fail lines (verified against live EnchantedMC logs).
     * The amount in "You need X Money" is the REMAINING GAP — it shrinks as you earn.
     * Classification is additionally gated: only within {@link #upgradeResponseWindowMs}
     * of our own send, and never on lines with a player/broadcast prefix (» or [rank]).
     */
    public List<String> upgradeFailPatterns = List.of(
        "/^you (?:don'?t|do not) have enough money\\b/"
    );
    /** Extracts the gap amount from a fail line ("You need 781.04B Money ..."). */
    public String upgradeNeedAmountPattern =
        "/(?i)you need\\s+\\(?(?<amount>[\\d,.]+\\s*[A-Za-z]{0,4})\\)?\\s*money\\b/";
    /** Response lines meaning the kind is fully upgraded (window-gated, anchored). */
    public List<String> upgradeMaxedPatterns = List.of(
        "/^you\\b.*(?:already maxed|max level|fully upgraded)/"
    );
    /** Income summary header: "Reward Summary: (60s)" — exact earnings window. */
    public String summaryHeaderPattern = "/(?i)^\\s*reward summary:\\s*\\((?<seconds>\\d+)\\s*s\\)/";
    /** Summary money line: " + 17.19B Money" — exact earnings for that window. */
    public String summaryMoneyPattern = "/(?i)^\\s*\\+\\s*(?<amount>[\\d,.]+\\s*[A-Za-z]{0,4})\\s*money\\s*$/";
    /** How long after our own send a reply is still attributable to it. */
    public int upgradeResponseWindowMs = 4000;
    /** No fail line this long after a send = the purchase succeeded (silence-success). */
    public int successSilenceMs = 3000;
    /**
     * After a successful buy the new price is unknown; retry once the balance
     * passes the old price × (1 + this). 0 = retry at the old price.
     */
    public double retryPriceGrowthPct = 0.0;
    /** Humanized "notice" delay between becoming affordable and typing the buy. */
    public int buyNoticeDelayMinMs = 2000;
    public int buyNoticeDelayMaxMs = 8000;
    /** Stop-protocol exemption window after our own /zone max (advancing teleports you). */
    public int expectedTeleportAfterZoneMs = 8000;

    // --- Economy: /bal seed + income-driven scheduling ---

    /** On bot enable, type balCommand once to seed balance. Upgrade commands are kill-gated and never polled. */
    public boolean startupProbes = true;
    public String balCommand = "/bal";
    /**
     * Reply lines for balCommand, anchored to the real formats (verified in logs):
     * the "Your Balances:" block renders " - Money: (1.09T)" — parenthesized amount.
     * Only accepted within 8s of a /bal send, and never from broadcast lines.
     */
    public List<String> balPatterns = List.of(
        "/(?i)^\\s*-?\\s*money\\s*:\\s*\\(\\s*\\$?(?<amount>[\\d,.]+\\s*[A-Za-z]{0,4})\\s*\\)/",
        "/(?i)^\\s*-?\\s*money\\s*:\\s*\\$?(?<amount>[\\d,.]+\\s*[A-Za-z]{0,4})\\s*$/",
        "/(?i)^\\s*(?:your\\s+)?balance\\s*:?\\s*\\$?\\(?(?<amount>[\\d,.]+\\s*[A-Za-z]{0,4})\\)?\\s*$/"
    );
    /**
     * Sidebar money line: "75.1B Money" (value-first), "MONEY: 75.1B" (label-first),
     * or the real EnchantedMC row "Your Balance 2.35T". First non-null group wins.
     */
    public String sidebarMoneyPattern =
        "/(?i)([\\d,.]+\\s*[A-Za-z]{0,4})\\s*MONEY\\b|MONEY\\s*:?\\s*([\\d,.]+\\s*[A-Za-z]{0,4})|YOUR\\s+BALANCE\\s*:?\\s*\\(?\\$?([\\d,.]+\\s*[A-Za-z]{0,4})\\)?/";
    /** Log every new/changed raw sidebar line (debug the scoreboard parse from the JSONL). Default on while we tune parsers from live evidence. */
    public boolean debugSidebar = true;
    /**
     * Hard cap between kill-driven /swordmax or /zone max sends of the same kind.
     * Success follow-ups (immediate re-run to learn the next tier) bypass this.
     * This is a ceiling, not a heartbeat: unaffordable evals do not send.
     */
    public int upgradeMinIntervalMs = 60_000;
    /** After a buy, wait this long before trusting the sidebar (it lags ~1–2s after spends). */
    public int upgradeSpendSettleMs = 2500;
    /** After a kill, wait this long for the sidebar balance to update before evaluating affordability. */
    public int postKillEvalDelayMinMs = 1500;
    public int postKillEvalDelayMaxMs = 3000;
    /** Legacy; TTK readiness no longer requires a sword buy this zone. */
    public int zoneMinSwordBuysThisZone = 0;
    /** Median TTK at this value => zone readiness R=1 (log-lerped from the per-zone baseline). */
    public int zoneReadyTtkMs = 2000;
    /**
     * Minimum zone readiness before /zone max is allowed, even if it is the only
     * affordable upgrade. 0.5 ≈ the log-lerp midpoint (~9s on a 40s baseline).
     */
    public double zoneMinReadiness = 0.5;
    /** Rolling window (kills) for the median time-to-kill. */
    public int ttkWindowKills = 8;
    /** Extra amount suffixes merged over the built-in K..Dc table, e.g. {"UTG": 1e36}. */
    public Map<String, Double> suffixScales = Map.of();

    // --- Ninja humanization (single behavior set; ninja=false restores the old mechanical one) ---

    public boolean ninja = true;
    /** Per-session multiplier on all delay bounds: 1 ± this, rolled at enable. */
    public double sessionJitterPct = 0.12;
    /** Delay means drift up by this fraction per hour of uptime (fatigue). */
    public double fatiguePerHour = 0.04;
    /** Chance any sampled delay becomes a real outlier pause (heavy tail). */
    public double tailChancePerDelay = 0.004;
    /** Out-of-bounds samples are squashed to this fraction of the excursion instead of clamped. */
    public double softClampMarginPct = 0.25;
    /** Rare long distractions, per tick rate derived from this per-minute chance. */
    public double distractionChancePerMinute = 0.4;
    public int distractionMinMs = 1500;
    public int distractionMaxMs = 12_000;
    /** Blend a new look intent into the in-flight path (velocity-continuous). Off by default: mid-path re-targets read as servo ticking. */
    public boolean mouseChaining = false;
    /** Perpendicular curve bump as a fraction of flick distance (random side per flick). The swoop is the human part. */
    public double curveBumpMinPct = 0.07;
    public double curveBumpMaxPct = 0.22;
    /** Subtle flick-tempo rotation (±~10%) so no single Fitts regression fits a session. */
    public boolean agilityRegimes = true;
    public int regimeDwellMinMs = 45_000;
    public int regimeDwellMaxMs = 90_000;
    /** Chance a click fires at up to 2.5x the normal aim tolerance (a sloppy click). */
    public double misclickChance = 0.02;
    /** Chance target selection picks a random in-range mob instead of the optimal one. */
    public double wrongTargetChance = 0.02;
    /** Chance the sprint-drop tick is skipped, producing a knockback sprint-hit. */
    public double sprintHitChance = 0.01;
    /** Per-char chance of a typo + backspace correction while typing commands. */
    public double typoChancePerChar = 0.01;
    /** Periodic human-length breaks. */
    public boolean breaksEnabled = true;
    public int focusMinutesMin = 45;
    public int focusMinutesMax = 90;
    public int breakMinutesMin = 1;
    public int breakMinutesMax = 4;
    /** "ignore" = ghost filter untouched; "sometimes" = attack movers with movingTargetAttackChance. */
    public String movingTargetPolicy = "ignore";
    public double movingTargetAttackChance = 0.15;

    // --- Stop protocol: teleport = pulled for a check; another player = staff spectating (solo gamemode) ---

    public boolean stopProtocolEnabled = true;
    /** Player radar stays dormant until a teleport has been seen this session (NPCs never matter mid-grind). */
    public boolean playerRadarArmAfterTeleport = true;
    /** Single-tick displacement past this many blocks counts as a teleport. */
    public double teleportThresholdBlocks = 12.0;
    /** Another player within this radius trips the stop protocol... */
    public double playerRadarRadius = 48.0;
    /** ...but only after being continuously in range this long (NPCs standing in the grind area never qualify if excluded below). */
    public long playerRadarDwellMs = 5000;
    /** Ignore players who haven't moved for this long — spawn NPCs / AFKers (0 = everyone trips it). */
    public long playerRadarIgnoreStationaryMs = 0;
    /** Names that never trip the radar (case-insensitive, small-caps-normalized, matches any line of multi-line NPC plates). */
    public List<String> playerRadarWhitelist = List.of("ZONE VISIBILITY", "CLICK HERE", "Barn");

    // --- TTK measurement ---

    /** Boss-bar-vanish kill credit requires the entity gone OR this much cook time (below it = tag didn't stick). */
    public int barVanishMinCookMs = 1200;
    /** Rarity HP scaling: TTK is divided by (1 + scale) so the zone benchmark compares across rarities. */
    public Map<String, Double> rarityHpScale = Map.of("RARE", 0.15, "EPIC", 0.30, "LEGENDARY", 0.40);

    /**
     * Bump when shipping new default patterns; loaded configs below this get pattern
     * lists replaced. The field default is 0 ON PURPOSE: Gson runs field initializers
     * before overlaying JSON, so a config file that lacks this key would otherwise
     * "look" current and skip every migration. save() always writes the current version.
     */
    public static final int CURRENT_CONFIG_VERSION = 9;
    public int configVersion = 0;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static YCBotChallengeConfig load(Path file) {
        try {
            if (Files.exists(file)) {
                YCBotChallengeConfig cfg = GSON.fromJson(Files.readString(file), YCBotChallengeConfig.class);
                if (cfg != null) {
                    boolean migrated = cfg.migrate();
                    cfg.normalize();
                    if (migrated) cfg.save(file);
                    return cfg;
                }
            }
        } catch (Exception e) {
            YCBotChallengeClient.LOGGER.warn("Failed to read config, using defaults: {}", e.toString());
        }
        YCBotChallengeConfig cfg = new YCBotChallengeConfig();
        cfg.save(file);
        return cfg;
    }

    /** Old configs keep stale server-specific patterns forever; replace them wholesale on version bumps. */
    private boolean migrate() {
        if (configVersion >= CURRENT_CONFIG_VERSION) return false;
        boolean changed = false;
        YCBotChallengeConfig fresh = new YCBotChallengeConfig();
        if (configVersion < 4) {
            balancePatterns = fresh.balancePatterns;
            sidebarCurrencies = fresh.sidebarCurrencies;
            scoreboardSnapshotMs = fresh.scoreboardSnapshotMs;
            sidebarMoneyPattern = fresh.sidebarMoneyPattern;
            playerRadarWhitelist = fresh.playerRadarWhitelist;
            zoneMinSwordBuysThisZone = 0;
            zoneMinReadiness = fresh.zoneMinReadiness;
            if (moneyCurrency != null && moneyCurrency.equalsIgnoreCase("chicken")) moneyCurrency = "money";
            changed = true;
        }
        if (configVersion < 5) {
            upgradeMinIntervalMs = fresh.upgradeMinIntervalMs;
            upgradeSpendSettleMs = fresh.upgradeSpendSettleMs;
            if (postKillEvalDelayMaxMs < 3000) postKillEvalDelayMaxMs = 3000;
            changed = true;
        }
        if (configVersion < 6) {
            // v6: evidence-based strict patterns replace the loose guessed ones
            // (the old ones matched "EnchantedMC »" broadcasts and ate fail lines
            // as /bal replies — see the 0.9.0 README economy section).
            balPatterns = fresh.balPatterns;
            upgradeFailPatterns = fresh.upgradeFailPatterns;
            upgradeMaxedPatterns = fresh.upgradeMaxedPatterns;
            zonePattern = fresh.zonePattern;
            changed = true;
        }
        if (configVersion < 7) {
            // v7: parse the real "Your Balance 2.35T" sidebar row; sidebar raw
            // logging on by default while parsers are tuned from live evidence.
            sidebarMoneyPattern = fresh.sidebarMoneyPattern;
            debugSidebar = true;
            changed = true;
        }
        if (configVersion < 8) {
            // v8: force every evidence-based economy pattern — covers configs whose
            // configVersion was missing from the JSON (Gson field initializers made
            // it look current, silently skipping the v6/v7 migrations).
            balPatterns = fresh.balPatterns;
            sidebarMoneyPattern = fresh.sidebarMoneyPattern;
            upgradeFailPatterns = fresh.upgradeFailPatterns;
            upgradeMaxedPatterns = fresh.upgradeMaxedPatterns;
            upgradeNeedAmountPattern = fresh.upgradeNeedAmountPattern;
            summaryHeaderPattern = fresh.summaryHeaderPattern;
            summaryMoneyPattern = fresh.summaryMoneyPattern;
            zonePattern = fresh.zonePattern;
            sidebarCurrencies = fresh.sidebarCurrencies;
            rebirthsPattern = fresh.rebirthsPattern;
            debugSidebar = true;
            changed = true;
        }
        if (configVersion < 9) {
            // v9: whitelist the "Barn" zone NPC fixture (radar false-stop evidence).
            playerRadarWhitelist = fresh.playerRadarWhitelist;
            changed = true;
        }
        configVersion = CURRENT_CONFIG_VERSION;
        return changed;
    }

    /** Fill nulls when an older config json is missing new fields. */
    public void normalize() {
        if (balancePatterns == null) balancePatterns = List.of();
        if (ascensionChatPatterns == null) ascensionChatPatterns = List.of();
        if (prestigeChatPatterns == null) prestigeChatPatterns = List.of();
        if (captchaChatPatterns == null) captchaChatPatterns = List.of();
        YCBotChallengeConfig fresh = new YCBotChallengeConfig();
        if (upgradeFailPatterns == null) upgradeFailPatterns = fresh.upgradeFailPatterns;
        if (upgradeMaxedPatterns == null) upgradeMaxedPatterns = fresh.upgradeMaxedPatterns;
        if (balPatterns == null) balPatterns = fresh.balPatterns;
        if (upgradeNeedAmountPattern == null || upgradeNeedAmountPattern.isBlank()) {
            upgradeNeedAmountPattern = fresh.upgradeNeedAmountPattern;
        }
        if (summaryHeaderPattern == null || summaryHeaderPattern.isBlank()) {
            summaryHeaderPattern = fresh.summaryHeaderPattern;
        }
        if (summaryMoneyPattern == null || summaryMoneyPattern.isBlank()) {
            summaryMoneyPattern = fresh.summaryMoneyPattern;
        }
        if (upgradeResponseWindowMs < 500) upgradeResponseWindowMs = 4000;
        if (successSilenceMs < 500) successSilenceMs = 3000;
        if (buyNoticeDelayMaxMs < buyNoticeDelayMinMs) buyNoticeDelayMaxMs = buyNoticeDelayMinMs;
        if (expectedTeleportAfterZoneMs < 0) expectedTeleportAfterZoneMs = 8000;
        if (suffixScales == null) suffixScales = Map.of();
        if (rarityHpScale == null) rarityHpScale = Map.of("RARE", 0.15, "EPIC", 0.30, "LEGENDARY", 0.40);
        // migrate: 0.7.5 shipped an empty whitelist; fill it with the zone NPC's plate lines
        if (playerRadarWhitelist == null || playerRadarWhitelist.isEmpty()) {
            playerRadarWhitelist = List.of("ZONE VISIBILITY", "CLICK HERE");
        }
        if (movingTargetPolicy == null) movingTargetPolicy = "ignore";
        if (balCommand == null || balCommand.isBlank()) balCommand = "/bal";
        if (sidebarMoneyPattern == null || sidebarMoneyPattern.isBlank()) {
            sidebarMoneyPattern = "/(?i)([\\d,.]+\\s*[A-Za-z]{0,4})\\s*MONEY\\b|MONEY\\s*:?\\s*([\\d,.]+\\s*[A-Za-z]{0,4})/";
        }
        // 0.6.x shipped aimAgility 0.4 with a much slower duration law; migrate the exact
        // old default to the new one so existing configs get the faster flicks.
        if (aimAgility == 0.4) aimAgility = 1.0;
        if (swordCommand == null || swordCommand.isBlank()) swordCommand = "/swordmax";
        if (zoneCommand == null || zoneCommand.isBlank()) zoneCommand = "/zone max";
        if (moneyCurrency == null || moneyCurrency.isBlank() || moneyCurrency.equalsIgnoreCase("chicken")) {
            moneyCurrency = "money";
        }
        if (sidebarCurrencies == null || sidebarCurrencies.isEmpty()) {
            sidebarCurrencies = List.of("money", "souls", "essence", "shards", "credits");
        }
        if (scoreboardSnapshotMs < 500) scoreboardSnapshotMs = 5000;
        if (zoneMinReadiness < 0 || zoneMinReadiness > 1) zoneMinReadiness = 0.5;
        if (upgradeMinIntervalMs < 0) upgradeMinIntervalMs = 60_000;
        if (upgradeSpendSettleMs < 0) upgradeSpendSettleMs = 2500;
        if (postKillEvalDelayMaxMs < postKillEvalDelayMinMs) {
            postKillEvalDelayMaxMs = Math.max(postKillEvalDelayMinMs, 3000);
        }
    }

    public void save(Path file) {
        configVersion = CURRENT_CONFIG_VERSION;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this));
        } catch (IOException e) {
            YCBotChallengeClient.LOGGER.warn("Failed to save config: {}", e.toString());
        }
    }
}

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
    /** Reaction to a kill before the next target: 0.9.x floored at 120ms, which is faster than people notice a bar vanish. */
    public int reactionDelayMinMs = 200;
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
     * 0.9.33: the first kills of a new stage measure it (they open or close the zone gate),
     * so while the stage has fewer than this many kills a tagged mob is penalised by
     * stageProbeRarityPenaltyBlocks instead of earning its rarity bonus, and a common one
     * is picked unless none is in range (Drew: rarity scales HP at a fixed rate; the first
     * mob on a fresh stage should be common, purely for time). 0 = never.
     */
    public int stageProbeCommonKills = 1;
    public double stageProbeRarityPenaltyBlocks = 30.0;
    /**
     * 0.9.37: on a fresh stage (no kill yet) a fresh DPS prediction over patience x this
     * reads the stage HARD at once (zone_gate_hard via=predicted-fresh) instead of when the
     * cook outruns the patience; the sword is one tier short on every fresh stage of every
     * 2026-09-04 climb and the verdict used to wait for the 30 s fallback eval. 0 = off.
     */
    public double stageProbePredictedMult = 3.0;
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
    /**
     * 0.9.37: the approach swings are for closing in only - never inside reach, never while
     * the camera is more than this far off the mob. The 2026-09-04 05:55 log has 72 s of
     * 2-3 cps clicks at a Horse from 2.5 blocks with the aim frozen 65 degrees away.
     */
    public double approachClickMaxAimDeg = 25.0;
    /**
     * 0.9.37: a target no click has connected on is dropped after this (was a hard-coded
     * 12 s that re-picked the same mob: six 12.000 s runs in a row on one Horse), the next
     * pick stands half a block closer, and after noConnectIgnoreAfter such runs on the same
     * entity it is ignored for the session (target_abandoned reason=no-connect).
     */
    public int noConnectTimeoutMs = 4000;
    public int noConnectIgnoreAfter = 2;
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
    /**
     * Nameplates that are never targeted (plain substrings or /regex/, case-insensitive).
     * The zone's AFK mob ("[AFKMOB] LVL7 Donkey ❤∞", infinite HP, right-click to
     * upgrade) is the same species as the real mobs and stands still, so only its
     * tag tells it apart; locking onto it wasted whole cook cycles (0.9.14).
     */
    public List<String> ignoreMobPatterns = List.of("[afkmob]", "❤∞");
    /**
     * 0.9.26: on this server a mob's plate is a text display riding the mob or floating
     * above it, not the entity's name (target_ignored never fired in any log). A plate
     * entity within this many blocks horizontally of a mob, from half a block below to
     * 3.5 above, is read as its nameplate for ignoreMobPatterns, rarity and level.
     */
    public double nameplateHologramRadiusBlocks = 0.9;
    /**
     * Backstop after the first hit: when every boss bar mentioning the mob matches
     * ignoreMobPatterns ("[AFKMOB] LVL9 Mooshroom"), the target is dropped for the session.
     */
    public boolean ignoreByBossBar = true;
    /**
     * Ctrl + toggle key marks the mob under the crosshair ignored (or unmarks it):
     * persisted by kind and position in config/ycbotchallenge-ignored.json and matched
     * within this radius; when nothing is in reach, the nearest mob within this many
     * degrees of the look line is taken.
     */
    public double manualIgnoreRadiusBlocks = 1.5;
    public double manualIgnoreAimDeg = 4.0;
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
     * Captcha auto-solve via QwenCloud's qwen3.6-flash since 0.9.32 (a local Qwen3-VL
     * behind vLLM stays available — see captchaVlmEndpoint). When enabled, a detected
     * captcha pauses grinding, captures the map (held map pixels > nearest item-frame
     * map > full screenshot), asks the reader, sends the answer to chat, and resumes.
     * On repeated failure it falls back to the old pause-for-human behavior.
     */
    public boolean captchaAutoSolve = true;
    /**
     * 0.9.32: the reader is QwenCloud's qwen3.6-flash (OpenAI-compatible token-plan endpoint,
     * key from env YCBOT_VLM_KEY or the one-line file ~/.ycbot_vlm_key), sent the NATIVE 128 px
     * map, one greedy read, no ballot. Bench 2026-09-04 on the four certified captures: the
     * native map reads 4/4 (164 input tokens, 3-5 s); every upscaled render lost udWn (read as
     * uaWn/ualWn) on every model, local 4B/8B or cloud. For the local vLLM reader set the
     * endpoint back to http://127.0.0.1:8000/v1/chat/completions, the model to
     * Qwen/Qwen3-VL-4B-Instruct-FP8, captchaMapScale 4, captchaVoteRenders to the x4bil… list
     * and captchaVoteTemperature 0.6 (the 0.9.26 ballot).
     */
    public String captchaVlmEndpoint = "https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1/chat/completions";
    public String captchaVlmModel = "qwen3.6-flash";
    /** Thinking models (qwen3.8-*) spend reasoning tokens on a four-letter read unless told not to; false sends enable_thinking=false. */
    public boolean captchaVlmThinking = false;
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
    /**
     * "map" (default since 0.9.16): read the captcha from a filled map's own pixels and
     * pause for the human when there is none: a chat/GUI trigger without a map is not
     * something the model can read (2026-09-03: it screenshotted the arena and typed
     * "qwe" into public chat). "auto" falls back to a screenshot, "screen" always
     * screenshots; both are opt-in.
     */
    public String captchaCaptureMode = "map";
    /** How the answer is sent: "{answer}" as plain chat, or e.g. "/captcha {answer}". */
    public String captchaAnswerTemplate = "{answer}";
    /** Wait for the map/screen to actually render before capturing. (Sonar's total budget is ~30s.) */
    public int captchaSettleMs = 1000;
    /**
     * HTTP timeout for one model read. 0.9.34: 8s, not the old 20s — a single read is
     * benched at 3-5s, and at 20s one slow call ate two thirds of the server's ~30s
     * window with nothing left to recover. The hedges below are the resilience now.
     */
    public int captchaTimeoutMs = 8000;
    /**
     * Hard hand-over deadline for the whole solve, measured from detection (0.9.34).
     * At this point the bot stops and pauses for the human (captcha_pause reason=budget)
     * rather than typing a guess that will land after the server's ~30s window closes —
     * the remainder is the human's room to type it themselves.
     */
    public int captchaBudgetMs = 25_000;
    /**
     * Hedged reads (0.9.34): read A fires as soon as the map is captured, read B this
     * long after it WITHOUT waiting for A to fail, both voting into the same ballot.
     * 3s lands read B inside the 2.5-5s captchaAnswerDelay reading pause, so the
     * cross-check costs no end-to-end time. Fixes both tail latency (a hung or 503'd
     * call no longer sinks the captcha) and the 0.9.26 blind spot: the x4 render read
     * "Kra" 12/12 when the answer was "KrA" — one read that is confidently wrong is
     * undetectable, two that disagree hand the alternative to the ballot for free.
     */
    public int captchaHedgeMs = 3000;
    /** Most reads fired for one captcha. A and B always go; C only if one failed or the ballot split. */
    public int captchaHedgeMax = 3;
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
    public int captchaAnswerDelayMinMs = 2500;
    public int captchaAnswerDelayMaxMs = 5000;
    /**
     * After answering: resume if no rejection message arrives within this window.
     * Sonar sends NOTHING on success (it silently transfers you), so silence = solved.
     */
    public int captchaVerifyWaitMs = 12_000;
    /**
     * Upscale factor for the 128x128 map image sent to the model, bilinear-smoothed
     * above x2 (captchaMapSmooth). tools/captcha_bench.py on the two certified
     * captchas (pnGe, p8b), 12 samples each, 2026-09-03: x4 smoothed reads both
     * greedily (23/24 sampled); x2 nearest (the 0.9.13 default) reads p8b as pBb every
     * time; x4 nearest and x6 are garbage. Re-run the bench before changing this.
     */
    public int captchaMapScale = 1;
    /** How far to look for an item-frame map holding the captcha. */
    public double captchaMapSearchRadius = 10.0;
    /** Chat lines meaning the captcha was accepted / rejected (plain substrings or /regex/). */
    public List<String> captchaSolvedPatterns = List.of("correct", "verified", "success", "thank");
    public List<String> captchaRetryPatterns = List.of("wrong answer", "incorrect", "try again", "invalid",
        "please enter the captcha on the map");

    // ---- 0.9.13 held-map captcha. EnchantedMC hands the player a filled map whose
    // picture is the code (typed in chat, case-sensitive). No chat line comes with
    // it, so the map appearing in the hands/hotbar is the trigger.
    /** Also watch the non-selected hotbar slots (the 2026-09-02 map landed in hotbar slot 9). */
    public boolean captchaMapAnySlot = true;
    /** A new map must persist this long before it counts as a captcha. */
    public int captchaSignalConfirmMs = 300;
    /** The map's pixels arrive in a later packet than the item: wait up to this for them. */
    public int captchaMapDataWaitMs = 1500;
    /** Draw the map bilinear-smoothed when captchaMapScale > 2 (nearest x4 misread, smoothed x4 read right). */
    public boolean captchaMapSmooth = true;
    /** Legacy since 0.9.26 (the ballot below reads every render); still used by the single-read paths. */
    public int captchaSecondScale = 3;
    /**
     * Running ballot (0.9.26). The map is rendered once at every scale here ("x<scale>bil"
     * smoothed, "x<scale>near" nearest; smoothing only applies above x2) and one background
     * reader reads them in turn — the schedule at temperature 0, then again at
     * captchaVoteTemperature — until the captcha is resolved or captchaVoteMaxReads. Every
     * reading is a vote; the first answer is the leader once the reading pause has passed
     * and captchaVoteMinReads votes are in (or captchaVoteMaxWaitMs later with at least
     * one); voting continues while the answer is verified, so a rejection sends the
     * best-supported alternative. 19:43 log + bench: x4 read "Kra" 12/12 on the live image
     * while x3 and x2 read "KrA" 8/8 — a vote picks KrA, the old primary render typed Kra.
     * Certified captures: x4/x3/x2 vote right on KrA, p8b and pnGe (x6 was a coin flip on
     * KrA and misread p8b; x4 nearest is garbage — keep those out of the schedule).
     */
    public List<String> captchaVoteRenders = List.of("x1");
    public double captchaVoteTemperature = 0;
    public int captchaVoteMaxReads = 12;
    /** Votes to hold for before typing. 0.9.34: 2 — the hedge schedule fires two reads, not three. */
    public int captchaVoteMinReads = 2;
    public int captchaVoteMaxWaitMs = 3000;
    /**
     * The server says nothing after an answer, right or wrong (latest.log 13:43); on a right
     * one the map leaves the hand. A map still held this long after the answer is the
     * rejection and the next candidate goes out (the captchaMaxAnswers cap still holds).
     * 0 = off (silence = solved, the 0.9.22 rule).
     */
    public int captchaMapHeldRejectMs = 7000;
    /**
     * Look-alike pairs for the second guess when both renders agree: the alphabet mixes
     * letters and digits (17:38: read "pBb", answer "p8b"). First matching character is
     * swapped for its partner; with none, the case flip (captchaCaseAmbiguous).
     */
    public String captchaLookalikes = "B8,O0,S5,Z2,I1,l1,G6,b6,g9,q9";
    /** The screenshot fallback is downscaled to this width first (the native 1605 px shot hallucinated a letter). */
    public int captchaScreenMaxPx = 1024;
    /** Hide the HUD (hotbar, boss bar, our overlay) for the screenshot fallback. */
    public boolean captchaScreenHideHud = true;
    /** Dump every captured captcha image to ycbotchallenge-logs/captcha-<time>-<mode>.png for prompt tuning. */
    public boolean captchaDebugPng = true;
    /**
     * Prompt for map captchas. Bench 2026-09-03 on the "pnGe" capture: telling the
     * model the string is NOT a word and asking for a JSON array of single
     * characters read it right on every input up to 1024 px (6/6 samples at
     * temperature 0.7); word-shaped prompts returned "pinc"/"pInGe", and a
     * per-letter colour enumeration inserted a phantom "i" every time.
     */
    public String captchaMapPrompt =
        "The image is a Minecraft map captcha: a short random string of large colored "
        + "letters over a dark background with small colored noise specks. It is NOT a "
        + "word, so do not autocorrect. Ignore the specks. Read the large letters left to "
        + "right, keeping exact case. Reply with exactly one line:\n"
        + "ANSWER: <the letters as a JSON array of single characters, e.g. [\"a\",\"B\"]>";
    /** Appended to captchaMapPrompt after a rejection; {rejected} = the rejected readings. */
    public String captchaMapRetryPrompt =
        "\nIMPORTANT: these readings were already REJECTED as wrong: {rejected}. Look again, "
        + "check the case of every letter and whether two letters touch, and give a different reading.";
    /** Keep the model's letter case for map captchas (the server is case-sensitive). */
    public boolean captchaPreserveCase = true;
    /** Letters whose upper and lower glyphs look alike: the second guess flips the first of these (else the first letter). */
    public String captchaCaseAmbiguous = "cosuvwxz";
    /**
     * Soft hints: a non-player server line containing one of these shortens the map
     * confirm window to 0 but never triggers alone (Chat Games prints "Type the
     * answer in chat to win!" for trivia). Logged as captcha_hint.
     */
    public List<String> captchaChatHintPatterns = List.of("type the", "verify", "prove you", "bot check", "captcha");
    /** Unclassified server lines are raw-logged (chat_raw) so new wording is captured; at most this many per minute, 0 = off. */
    public int chatRawPerMinute = 30;
    /**
     * Reader health: GET this on enable and every interval. Informational since 0.9.34 —
     * a captcha is never gated on it, because this route (/v1/models) is not the route a
     * solve uses (/chat/completions), and on 2026-09-04 16:07:13 one transient HTTP 503
     * here marked the reader dead for 97s. Only a genuine connect failure now counts as
     * unreachable; any answered request, 5xx included, is merely degraded.
     */
    public String captchaVlmHealthUrl = "https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1/models";
    public int captchaVlmHealthIntervalMs = 300_000;
    public int captchaVlmHealthTimeoutMs = 3000;
    /** When the server serves exactly one model under a different id, use that id. */
    public boolean captchaVlmModelAuto = true;
    /** Stricter: pause on ANY screen opening (including chat/inventory you open yourself). */
    public boolean pauseOnAnyScreen = false;
    /**
     * Stay in your zone (0.9.27): a mob whose plate level ("LVL7 Donkey") differs from the
     * boss-bar-confirmed zone level is never a candidate — targetRange (50) reaches the
     * neighbouring zones and the dominant filter drops out while the local pack respawns
     * (20:35 log: a Chicken picked in zone 7 right after the respawn broadcast, and a
     * stray neighbour species in every zone). Off until the first hit of a new stage
     * confirms its level. Rejections log target_offzone once per mob.
     */
    public boolean targetZoneLevelOnly = true;
    /** Target the majority mob type in range — skips stale leftovers from the previous stage. */
    public boolean targetDominant = true;
    /** Dominant filtering only kicks in when the top mob type has at least this many alive in range. */
    public int minDominantPack = 3;
    /**
     * Abandon a tagged mob that still hasn't died after this long — but only once its
     * boss-bar HP has also stopped dropping for {@link #cookStallMs}. A slow, legit kill
     * on a fresh stage keeps cooking (the 90s Goat); a ghost / unkillable mob does not.
     */
    public int maxCookMs = 90000;
    public int cookStallMs = 15000;
    public boolean hud = true;
    public int hudX = 4;
    public int hudY = 4;
    /** 0.9.30 HUD: backdrop opacity (0–1) and whether the module chip row is drawn. */
    public double hudAlpha = 0.55;
    public boolean hudShowModules = false;
    /** 0.9.33: the plan row — what runs next and why, from the last eval's Decision (age shown past 10 s). */
    public boolean hudShowPlan = true;
    /** 0.9.31: the souls/essence/shards/credits row (off: the sword and zone price rows carry what matters). */
    public boolean hudShowBalances = false;
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
    public String rebirthCommand = "/rebirth";
    /**
     * Zone-first economy (0.9.16). A zone multiplies per-kill money x20 and costs about
     * one kill of the next zone (03-36 log: 137B for zone 6 whose kills pay 183B), so
     * with the TTK gate open the zone is the buy and money is saved for it. A sword is
     * bought while saving only when it costs at most this percent of the remaining zone
     * gap (zone 3: the 22.5M sword at 10% of a 220M gap yes, the 78.8M one at 45% no).
     */
    public double swordWhileSavingMaxPct = 25;
    /**
     * At or below this TTK a sword cannot help (walking and aiming dominate the kill:
     * 03-36 bought a 525K sword at 0.73s and the TTK stayed 0.73s), so none is bought
     * while saving for the zone. 0 disables the floor.
     */
    public int zoneInstantTtkMs = 2000;
    /**
     * Rebirth horizon (0.9.15): a sword/zone is lost at the rebirth, so it is bought
     * only if it pays for itself first: price < remaining gap x (gain - 1), where gain
     * is the income multiplier expected from the buy. Logs 2026-09-03: the 415T zone 8
     * against a 475T gap at 109T/min delayed the rebirth ~2 min; zone 7 for 7.55T
     * against a 22T gap ~5 min. Early-snowball buys (K-B against a T gap) always pass.
     */
    public boolean rebirthHorizonEnabled = true;
    /** Income multiplier assumed for the next zone right after the buy (lvl6->7 and lvl7->8 measured 1.2-1.4). */
    public double rebirthHorizonZoneGain = 1.3;
    /**
     * Sword gain floor: the multiplier assumed at the movement floor (TTK 4.0s -> 3.1s
     * measured ~1.3). Since 0.9.30 the sword gain is TTK-aware: a tier multiplies DPS by
     * rebirthHorizonSwordDpsMult, income goes as 1/(ttk + zoneInstantTtkMs), and the
     * result is never below this floor (logs 2026-09-03: 1.3x at the floor, 2–8x on
     * long kills; the fixed 1.25 held a 132S sword back at a 5–8s TTK).
     */
    public double rebirthHorizonSwordGain = 1.25;
    public double rebirthHorizonSwordDpsMult = 2.0;
    /** After a sword/zone success, log the observed income ratio (upgrade_gain) once this long later; 0 = off. */
    public int rebirthHorizonGainWindowMs = 180_000;
    /** Minimum gap between any two typed commands (server: "again in less than 1 second"). */
    public int commandCooldownMs = 1100;
    /**
     * 0.9.29: no typed /swordmax or /zone max before this many kills (rolled) since the
     * enable AND since the last rebirth — a person kills something first (00:19 log:
     * upgrade_plan 5 s after each enable with zero kills).
     */
    public int upgradeFirstKillsMin = 1;
    public int upgradeFirstKillsMax = 3;
    /**
     * 0.9.31 price ladders: every log agrees a sword level costs ×3.5 the last (36 steps,
     * ratios 3.49–3.50) and a zone stage ×55 (137.26B → 7.55T → 415.21T → 22.83Q → 1.26QQ →
     * 69.08QQ → 3.8S → 208.97S). After a purchase the next price is predicted as last ×
     * growth and used as the target (HUD "predicted"); the next fail line checks it
     * (price_check) and a measured ratio within priceGrowthLearnBandPct of the config value
     * is blended into the per-account growth (price_ratio, state file).
     */
    public boolean pricePredictionEnabled = true;
    public double swordPriceGrowth = 3.5;
    public double zonePriceGrowth = 55.0;
    public double priceGrowthLearnBandPct = 30;

    // ---- 0.9.17: server auto-rebirth, giveaways, rebirth upgrades
    /**
     * /autorebirth is unlocked on Drew's account (chat: "Auto rebirth has been enabled."),
     * so the server rebirths the moment the cost is covered. The bot then never types
     * /rebirth to rebirth; it still probes the GUI to learn the next cost for the
     * rebirth-horizon rule. Set false on an account without it.
     */
    public boolean serverAutoRebirth = true;
    /**
     * /rebirth probe hygiene (0.9.24). After the diamond click the bot waits this long
     * for the server's own answer — its fail line, or a rebirth signal (chat line,
     * sidebar counter, money collapse; all within ~5s) — and a GUI that merely closed
     * is never read as a rebirth. A probe that never reached the GUI may be re-typed
     * at most this many times per session; one that did and went unanswered is left
     * alone (18:37 log: five /rebirth in 80s on an unknown "QQ" suffix).
     */
    public int rebirthSignalWaitMs = 6000;
    public int rebirthProbeMaxRetries = 1;
    /**
     * Giveaways: the server announces "NEW GIVEAWAY (30s to enter)" / prize / "Click to
     * Enter!" and typing /giveaway joins ("You have joined the giveaway for Current
     * Lootbox!", 2026-09-03; Drew won a Monster Lootbox). Toggle: joining every one
     * within seconds is its own fingerprint, so a join chance and a read delay apply.
     */
    public boolean giveawaysEnabled = true;
    public String giveawayCommand = "/giveaway";
    /** Chance to join a given giveaway at all (people miss some). */
    public double giveawayJoinChance = 0.85;
    /** Log-normal pause between the announcement and typing the command (reading it, finishing the swing). */
    public int giveawayJoinDelayMinMs = 2500;
    public int giveawayJoinDelayMaxMs = 12_000;
    /** Give up when the command could not go out this long after the announcement (the roll is at 30s). */
    public int giveawayWindowMs = 25_000;
    public List<String> giveawayAnnouncePatterns = List.of("new giveaway");
    public List<String> giveawayJoinedPatterns = List.of("you have joined the giveaway");
    public List<String> giveawayWonPatterns = List.of("has won the giveaway");
    /**
     * After our own win ("Ihazekids69420 has won the giveaway for", 2026-09-03) say
     * something, like anyone would: one of these, picked at random, typed after a
     * short delay. Off, or a one-entry list, if a fixed phrase is preferred.
     */
    public boolean giveawayWinReplyEnabled = true;
    public double giveawayWinReplyChance = 0.9;
    public List<String> giveawayWinMessages = List.of("lfg", "gg", "LFG", "ggs", "lets gooo", "W", "ty ty", "lfgggg", "gg ez");
    public int giveawayWinReplyDelayMinMs = 1500;
    public int giveawayWinReplyDelayMaxMs = 6000;
    /**
     * 0.9.37: gg like everyone else. A store purchase prints a seven-line "GG WAVE
     * ACTIVATED!" block and a median 17 players answer "gg" 0.5-2.0 s later (p10/p50/p90
     * over 26 waves: 0.50/1.30/2.00 s); a rare perk pull prints "EnchantedMC » NAME has
     * just pulled Universal Perk 5 on their Sword!" and gets free-form congratulations. The
     * bot had never typed a free-text line. Waves: most of them (ggWaveChance), the plain
     * word, a short roll before the typing pipeline's own second or two (gg_reply logs
     * sinceMs so the band can be tuned); perk pulls: half of them, a phrase from
     * ggPerkMessages after a longer read. Never twice within ggMinGapMs, dropped past the
     * window, never off a player's own line (the wave block has no » and the perk pattern
     * carries the server's prefix).
     */
    public boolean ggEnabled = true;
    public List<String> ggWavePatterns = List.of("gg wave activated");
    public double ggWaveChance = 0.85;
    public List<String> ggWaveMessages = List.of("gg");
    public int ggWaveDelayMinMs = 300;
    public int ggWaveDelayMaxMs = 1500;
    public int ggWaveWindowMs = 6000;
    public int ggWaveBlockMs = 2000;
    public List<String> ggPerkPatterns = List.of("/^enchantedmc \u00bb .* has just pulled universal perk 5 on their sword/");
    public double ggPerkChance = 0.5;
    public List<String> ggPerkMessages = List.of("gg", "W", "lfg", "gg wp", "huge", "ggs");
    public int ggPerkDelayMinMs = 2000;
    public int ggPerkDelayMaxMs = 8000;
    public int ggPerkWindowMs = 20_000;
    public int ggMinGapMs = 60_000;
    /**
     * Rebirth upgrades: each rebirth grants points spent in /rebirth → nether star
     * ("REBIRTH UPGRADES", lore "| Current Points: N") → "Upgrades" menu. Order is
     * Drew's: enchant proc (enchanted book), damage (red dye), essence (magma cream),
     * souls (purple dye); each key is matched against the item name and tooltip.
     */
    public boolean rebirthUpgradesEnabled = true;
    public List<String> rebirthUpgradeOrder = List.of("enchant", "damage", "essence", "soul");
    /** The star, by name or a lore line. */
    public String rebirthUpgradesItemPattern = "/rebirth upgrades/";
    /** "| Current Points: 0" → 0. */
    public String rebirthPointsPattern = "/current points:?\\s*(?<n>[\\d,]+)/";
    /** Title of the menu the star opens (screenshot: "Upgrades"). */
    public String rebirthUpgradesTitlePattern = "/^upgrades\\b/";
    /** Tooltip patterns for the upgrade items (unverified until the first rebirth_upgrade_menu log; tune from it). */
    public String rebirthUpgradeLevelPattern = "/\\blevel:?\\s*(?<cur>[\\d,]+)\\s*\\/\\s*(?<max>[\\d,]+)/";
    public String rebirthUpgradeCostPattern = "/(?:cost|price):?\\s*(?<amount>[\\d,]+)\\s*(?:rebirth\\s*)?points?/";
    public String rebirthUpgradeMaxedPattern = "/\\bmaxed\\b|\\bmax level\\b|\\bmaxed out\\b/";
    /** After a rebirth (past the teleport settle), the visit waits this log-normal delay: nobody spends points the second they land. */
    public int rebirthUpgradeDelayMinMs = 20_000;
    public int rebirthUpgradeDelayMaxMs = 120_000;
    /** Once per session, after a rolled kill count and delay, check for leftover points. */
    public boolean rebirthUpgradeCheckOnEnable = true;
    public int rebirthUpgradeEnableMinKillsMin = 5;
    public int rebirthUpgradeEnableMinKillsMax = 15;
    public int rebirthUpgradeEnableDelayMinMs = 60_000;
    public int rebirthUpgradeEnableDelayMaxMs = 180_000;
    /** Purchases per visit at most; each click waits a settle and is confirmed by the tooltip changing. */
    public int rebirthUpgradeMaxClicks = 20;
    public int rebirthUpgradeSettleMinMs = 400;
    public int rebirthUpgradeSettleMaxMs = 1200;
    public int rebirthUpgradeOpenTimeoutMs = 4000;
    public int rebirthUpgradeMaxMenuMs = 60_000;
    /** Look-at-menu pause after Rebirth GUI opens, before Esc or diamond click. */
    public int rebirthLookMinMs = 600;
    public int rebirthLookMaxMs = 3500;

    // ---- 0.9.28: companions. Walk to the zone's Companion Egg (dragon egg under a hologram
    // "<Zone> Companion Egg / … / | Price: $121.3300 Money"; the Credit Egg is never touched),
    // right-click, open eggs while an open is cheap against income, /companion → Equip Best,
    // a look at Fuse Companions (logged, not automated yet), then a sliding-window bulk delete.
    public boolean companionsEnabled = true;
    /** The egg's hologram: matched on the tail only ("Western Companion Egg", "Farm Companion Egg"). */
    public String companionEggPattern = "/\\bcompanion egg\\b/";
    /** "| Price: $121.3300 Money" / "| Price: 363.9800 Money" → the amount (the first open is the probe for its scale). */
    public String companionPricePattern = "/price:\\s*\\$?(?<amount>[\\d,.]+\\s*[A-Za-z]{0,4})\\s*money/";
    /** A hologram with this is the other egg. */
    public String companionEggExcludePattern = "/credits/";
    /** "OPEN: [3x COMPANION EGG]" (name or lore) → 3. */
    public String companionOpenPattern = "/open:?\\s*\\[\\s*(?<n>\\d+)\\s*x\\s*companion egg\\s*\\]/";
    /** "[ZONE 1 STAGE 10]" on a companion. */
    public String companionZoneStagePattern = "/\\[\\s*zone\\s*(?<zone>\\d+)\\s*stage\\s*(?<stage>\\d+)\\s*\\]/";
    /** "| Multiplier: 156.38x Money". */
    /**
     * 0.9.35: the multiplier carries a magnitude suffix once it passes 1000 ("Multiplier:
     * 1.02Kx Money"), and the digits-only group stopped at "1.02" and then failed on the
     * "K" - so every companion the account owns read as multiplier-less and companion_equip
     * logged none. Anchored on "money" so the trailing x is unambiguous; Amounts.parse
     * already handles "1.02K".
     */
    public String companionMultiplierPattern = "/multiplier:\\s*(?<x>[\\d,.]+\\s*[A-Za-z]{0,3})x\\s*money/";
    /** "| Rarity: Rare (NORMAL)". */
    public String companionRarityPattern = "/rarity:\\s*(?<r>[A-Za-z]+)/";
    public String companionEquipBestPattern = "/equip best/";
    /**
     * The item is called "Companions Fusion" ("Click here to visit the Companion Fusion
     * Menu"), never "Fuse Companions" — the old pattern matched nothing, so in 33 logs the
     * fuse menu was never opened once and every visit logged a misleading no-fuse-item.
     * Fusing itself is still not automated (0.9.35); this only reaches the menu so its
     * layout is dumped as companion_gui which=fuse.
     */
    public String companionFusePattern = "/fuse companions|companions?\\s+fusion|companion\\s+fusion\\s+menu/";
    public String companionEggsTitlePattern = "/^companion eggs\\b/";
    public String companionsTitlePattern = "/^companions\\b/";
    public String companionFuseTitlePattern = "/^fuse companions\\b|companions?\\s*fusion/";
    public String companionCommand = "/companion";
    /** Slots of the four equip positions in the Companions GUI (screenshot: the row left of the nether star). */
    /**
     * Fallback only since 0.9.35: the equipped companions are the ones whose lore offers to
     * un-equip them. The real GUI holds them at slots 1, 2, 3 and 5 (slot 4 is Equip Best,
     * 6-7 are "Slot #N Locked", 0 is empty), so this list saw three of four and filed the
     * fourth as storage — which let the bulk delete plan a pair an equipped companion held.
     */
    public List<Integer> companionEquipSlots = List.of(0, 1, 2, 3);
    public String companionUnequipPattern = "/click here to un-?equip/";
    /**
     * Sliding window (Drew): keep the newest companionKeepZones zones (the current and the
     * previous one), bulk-delete older zone/stage pairs never held by an equipped companion,
     * at most companionMaxBulkDeletes commands per visit.
     */
    public boolean companionBulkDeleteEnabled = true;
    public int companionKeepZones = 2;
    public int companionMaxBulkDeletes = 5;
    public String companionBulkDeleteCommand = "/companion bulkdelete {zone} {stage}";
    /**
     * 0.9.29: the egg is a real dragon-egg block on a pedestal (Drew's screenshot), so the
     * primary locator scans the blocks around the player for minecraft:dragon_egg (±scan
     * radius horizontally, ±vertical), once per 30 s while idle and once per visit; the
     * hologram within companionEggHologramReach of an egg tells the Money egg from the
     * Credit egg. companionEggSearchRadius bounds the hologram scan (secondary source).
     */
    public int companionEggScanRadius = 64;
    public int companionEggScanVertical = 12;
    public double companionEggHologramReach = 2.5;
    /**
     * 0.9.31: how far under / over an egg block's centre its hologram lines may hang and
     * still count as its own (the Western egg's plate group spans about a block under to
     * a block over the egg; the old 0.5-under window lost the price line and paired nothing).
     */
    public double companionEggHologramBelow = 3.0;
    public double companionEggHologramAbove = 5.0;
    /** Stages per location (Farm 1–10, Western 11–20, …): one egg per location, saved and forgotten per location. */
    public int companionStagesPerLocation = 10;
    /** Where to look for the egg's hologram, how close to walk, how far under the lowest line the egg block sits, and how far a crosshair hit may be from that point. */
    public double companionEggSearchRadius = 80.0;
    public double companionEggReach = 2.5;
    public double companionEggAimDrop = 1.2;
    public double companionEggHitRadius = 1.8;
    public int companionWalkTimeoutMs = 45_000;
    /** Eggs per visit (rolled), and the click cap on open items per visit. */
    public int companionEggsMin = 3;
    public int companionEggsMax = 10;
    public int companionMaxOpensPerVisit = 6;
    /**
     * Manual visits only since 0.9.36 (Ctrl+Shift+toggle): the share of the wallet such a
     * visit may spend. An economy visit spends the priced batch (companionEggsMin eggs) and
     * nothing more. It used to cap the decision too, and at lvl17 that demanded 18.53N in
     * hand for a 7.41N batch while the whole rebirth cost 15.94N - eggs were unreachable
     * exactly where they were needed.
     */
    public double companionMaxBalancePct = 40;
    public int companionMinStageGain = 0;
    /** Runaway backstop only; the real cap is companionMaxVisitsPerStage (0.9.35). */
    public int companionMaxVisitsPerRebirth = 8;

    // ---- 0.9.35: companions are priced by the one economy (Economy.decideCompanion)
    /**
     * Zone prices climb a flat x55 a stage, but income growth per stage fell to x24 at
     * lvl14 and x18 at lvl15 in the 2026-09-04 logs: income is money/kill x kills/min and
     * kills/min collapses as the TTK runs 1.2s -> 96s, so the x3.5 sword ladder cannot
     * hold that line (lvl15: 41.7 bot-on minutes, thirteen sword buys, no advance). A
     * companion batch is a direct income multiplier that ignores the TTK — measured 2.20x
     * (7 eggs) and 1.76x (8 eggs) — and it survives the rebirth, so it is worth buying
     * late and high. The old trigger (a batch under companionMaxIncomeMinutes of income,
     * two stages above the last buy, twice a rebirth, never while a zone was "affordable")
     * was a second economy that refused all of that; it is gone.
     *
     * 0.9.36: the 0.9.35 ETA race (patience on the stage, a credit against the sword, a delay
     * budget that was a percentage of what was left to the rebirth) bought nothing at all —
     * the 19:14 log has zero companion events — and is gone. Two rules remain
     * (Economy.decideCompanion): the batch reaches the gap being saved for sooner with the
     * eggs than without, or the minutes it delays the rebirth fit the persistent payback
     * budget: max(companionMaxRebirthDelayMin, (1 - 1/gain) x last cycle's bot-on minutes x
     * companionPaybackFraction). companionCyclePriorMin stands in for the cycle length until
     * one has been measured (rebirth 13's cycle ran ~60 bot-on minutes).
     */
    public double companionMaxRebirthDelayMin = 3.0;
    public double companionCyclePriorMin = 45.0;
    public double companionPaybackFraction = 0.5;
    public int companionMaxVisitsPerStage = 2;
    /**
     * 0.9.37: fuse first, then Equip Best (Drew). With a group of companionFuseMinGroup
     * identical companions (name, zone, stage, rarity) in the storage the visit opens the
     * fusion menu and clicks its Fuse All item, dumps whatever the server shows next
     * (companion_gui which=fuse-after - a confirmation is never clicked blind), then
     * re-opens /companion for Equip Best. Four 0.9.36 visits opened the menu and walked
     * past a 7-of-a-kind and a 6-of-a-kind at 100 % odds.
     */
    public boolean companionFuseEnabled = true;
    public int companionFuseMinGroup = 5;
    public String companionFuseAllPattern = "/fuse all/";
    /** 0.9.37: a sidebar drop of 1, 3 or 10 eggs at this stage's price with no visit running is a hand purchase (companion_observed). */
    public double companionObservedTolerancePct = 3.0;
    /** The egg ladder, measured x52.2 a stage over seven consecutive steps (44.44Q@s9 -> 904.27SS@s15). */
    public double companionPriceGrowth = 52.2;
    /**
     * The income multiplier one batch brings: a prior, replaced by the per-account value
     * learned from the income before/after each visit (companion_gain / companion_ratio).
     * 1.5 sits deliberately under the measured 1.76x and 2.20x.
     */
    public double companionGainPrior = 1.5;
    /** 0.9.36: 0.8, so a dud batch can lower the estimate (under 1.2 every one was "recorded and dropped"). */
    public double companionGainMin = 0.8;
    public double companionGainMax = 3.0;
    public int companionGainWindowMs = 180_000;
    /** After an aborted visit the decision stops asking for a while (it cannot execute). */
    public int companionRetryAfterAbortMs = 600_000;

    // ---- 0.9.33: menu manners, one policy for every container flow (GuiHuman)
    /**
     * A server sees only click/close packets and their spacing, so every menu click is
     * preceded by a notice beat (this range), every close by guiClose*, two menus in a row
     * by guiBetween*, a sub-menu read by guiRead*. Before 0.9.33 the companion egg opens
     * and the enchanter tab clicked in the same tick as the decision and each flow closed
     * on its own schedule.
     */
    public int guiClickMinMs = 250;
    public int guiClickMaxMs = 900;
    public int guiCloseMinMs = 400;
    public int guiCloseMaxMs = 1500;
    public int guiBetweenMinMs = 1500;
    public int guiBetweenMaxMs = 3500;
    public int guiReadMinMs = 300;
    public int guiReadMaxMs = 900;
    /** First look at the Companion Eggs / Companions / Fuse menus (they borrowed rebirthLook* before 0.9.33). */
    public int companionLookMinMs = 600;
    public int companionLookMaxMs = 3500;

    // ---- 0.9.33: Sword Skins scouting (price + tier from the enchanter's "Swords" menu)
    /**
     * At the end of an enchanter visit the bot clicks the "Swords" item ("Click to view your
     * swords"), reads the Sword Skins menu and closes it: the equipped skin's Price is what
     * the next /swordmax pays, its Tier n/5 is the HUD tier, a LOCKED skin's price is the
     * promotion. Done whenever the sword price is unknown or only ladder-predicted, else
     * with swordMenuScoutChance per visit. A menu price is trusted only within
     * swordMenuPriceBandPct of the known target or of a ladder step from the last price
     * (sword_menu_price verdict): the tooltip's suffix may be a font glyph the text does not
     * carry ("$139.880" for 139.88Q), and a mis-read must never override the ladder.
     */
    public boolean swordMenuScoutEnabled = true;
    public double swordMenuScoutChance = 0.35;
    public double swordMenuPriceBandPct = 30;
    public String swordSkinsButtonPattern = "/^swords?\\b/";
    public String swordSkinsButtonLorePattern = "/click to view your swords/";
    public String swordSkinsTitlePattern = "/^sword skins\\b/";
    public String swordSkinSignaturePattern = "/^sword skin$/";
    public String swordSkinPricePattern = "/price:\\s*\\$?(?<amount>[\\d,.]+\\s*[A-Za-z]{0,4})\\s*money/";
    public String swordSkinTierPattern = "/tier:\\s*(?<cur>\\d+)\\s*\\/\\s*(?<max>\\d+)/";
    public String swordSkinDamagePattern = "/damage:\\s*(?<amount>[\\d,.]+\\s*[A-Za-z]{0,4})\\s*dmg/";
    public String swordSkinEquippedPattern = "/^equipped\\b/";
    public String swordSkinLockedPattern = "/^locked\\b/";
    /**
     * The income figure is this stage's own after this many kills on it OR this long on it
     * (0.9.36: either). Ten kills at a 185 s first kill was 16+ minutes; the 60 s reward
     * summary makes the rate honest within two of its windows. Gates only the persist rule -
     * the sooner rule is income-free.
     */
    public int companionStageSettleKills = 3;
    public int companionStageSettleMs = 120_000;
    /** "Finish this kill, then go buy pets": delay between the decision and the walk. */
    public int companionDelayMinMs = 10_000;
    public int companionDelayMaxMs = 60_000;
    /** Settle after a click before reading the sidebar / GUI again. */
    public int companionSettleMinMs = 1500;
    public int companionSettleMaxMs = 3000;
    public int companionOpenTimeoutMs = 4000;
    public int companionMaxVisitMs = 180_000;
    public int companionMaxConsecutiveAborts = 3;

    // ---- 0.9.38: the zone boss. "the boss in your zone has just spawned, you will have 5
    // minutes to kill it before it despawns" about every 89 min; its bar ("Rotten Boss 300",
    // no heart, one count per hit), the title overlay ("Hit the targets to kill the boss and
    // recieve the rewards!" / "Targets Hit - N") and a small target marker on the body that
    // moves every ~10 hits. In 36 h of logs the bot never hit it once (the bar sat at 300
    // for 277 s of grinding beside it). A kill: Tier-2 Totem Box, skin boxes, sword perk
    // rolls, weak 15-min boosters - no money. The marker's entity type is learned from the
    // first boss_scan; a display is never attacked (the attack key would mine the block).
    public boolean bossEventEnabled = true;
    public String bossEventBarPattern = "/\\bboss\\b/";
    public String bossEventCountPattern = "/(?<n>\\d+)\\s*$/";
    public String bossEventStartPattern = "/hit the targets to kill the boss/";
    public String bossEventProgressPattern = "/targets?\\s*hit\\s*[-\u2013:]\\s*(?<n>\\d+)/";
    public String bossSpawnPattern = "/boss in your zone has just spawned/";
    public String bossDespawnPattern = "/boss in your zone has just despawned/";
    public String bossKilledPattern = "/(?<who>\\S+) has killed the (?<boss>.+?) in (?:his|her|their) zone/";
    public String bossRewardPattern = "/^boss reward:/";
    /** An armor stand whose plate matches this is the marker; one with no plate at all is a candidate too. */
    public String bossTargetNamePattern = "/target/";
    public double bossScanRadius = 12.0;
    public double bossStandTolerance = 0.8;
    public int bossWalkTimeoutMs = 30_000;
    /** A cook in progress is finished first, unless the boss has been waiting this long. */
    public int bossEventStartGraceMs = 8_000;
    public int bossEventMaxMs = 300_000;
    public int bossNoProgressMs = 4_000;
    public int bossMaxRescans = 3;
    public int bossMarkerMoveHits = 10;
    public double bossMarkerMoveBlocks = 1.5;
    /** Drew's own pace on the 2026-09-04 kill: 293 hits in 99 s. The server counts hits, not damage. */
    public double bossClickCpsMin = 2.5;
    public double bossClickCpsMax = 3.5;
    public boolean bossRespectVanillaCooldown = false;
    public int bossHitLogEvery = 10;
    public int bossMaxConsecutiveAborts = 3;

    // ---- 0.9.28: the Transcend ability (Q with the sword held; "Your Transcend Ability has
    // been activated (180s Cooldown)" / "… has ended"). Periodic, never on the dot.
    public boolean transcendEnabled = true;
    /** Used until the server's own "(Ns Cooldown)" has been read. */
    public int transcendCooldownMs = 300_000;
    public String transcendActivePattern = "/transcend ability has been activated/";
    public String transcendEndPattern = "/transcend ability has ended/";
    public String transcendCooldownPattern = "/\\((?<s>\\d+)\\s*s\\s*cooldown\\)/";
    /** After the cooldown, per-kill chance ramps from 0 to transcendFullChance over transcendRampMs. */
    public int transcendRampMs = 90_000;
    public double transcendFullChance = 0.3;
    /** First press after enabling: not at once. */
    public int transcendFirstDelayMinMs = 20_000;
    public int transcendFirstDelayMaxMs = 120_000;
    /**
     * 0.9.30: an activation with no press of ours in the last transcendPressGraceMs is
     * the server's; this many in a row and the bot stops pressing for the session
     * (transcend_auto). The 00:19 log: 37 activations at exactly 190s spacing, bot off.
     */
    public int transcendAutoDetectCount = 2;
    public int transcendPressGraceMs = 2000;

    // --- 0.9.10: rebirth settle, lazy /rebirth knowledge, hesitation, de-fingerprinting ---

    /** Stand and look around after our own teleport before targeting: rebirth (zone 1 reload) vs zone advance. */
    public int postRebirthSettleMinMs = 4_000;
    public int postRebirthSettleMaxMs = 9_000;
    public int postZoneSettleMinMs = 2_000;
    public int postZoneSettleMaxMs = 5_000;
    public double postTeleportLookChance = 0.8;
    /**
     * Unknown-price retry after a success: try again once the balance passes the last
     * paid price × (1 + a growth rolled in this range per success) — replaces the
     * fixed retryPriceGrowthPct and the 3.5–4.9s follow-up re-send.
     */
    public double retryPriceGrowthMinPct = 0.20;
    public double retryPriceGrowthMaxPct = 0.80;
    /** After a rebirth the old cost is a floor; retry the GUI once money passes it × (1 + roll in [0, this]). */
    public double rebirthRetryFloorGrowthMaxPct = 0.5;
    /** Unknown account: the one /rebirth seed per session waits for both a rolled kill count and a delay. */
    public int rebirthSeedMinKillsMin = 5;
    public int rebirthSeedMinKillsMax = 20;
    public int rebirthSeedDelayMinMs = 120_000;
    public int rebirthSeedDelayMaxMs = 600_000;
    /** After a rebirth: one deferred /rebirth re-probe learns the next goal ("at some point"). */
    public int rebirthReprobeMinKillsMin = 15;
    public int rebirthReprobeMinKillsMax = 40;
    public int rebirthReprobeDelayMinMs = 300_000;
    public int rebirthReprobeDelayMaxMs = 900_000;
    /**
     * Buy hesitation on long saves: with this chance an affordable buy is held for a
     * random 30s–3min. Only when the price has been known for buyHesitationMinSaveMs and
     * the balance is under cooldownRelaxBalanceMult × price (never in the post-rebirth snowball).
     */
    public double buyHesitationChance = 0.30;
    public int buyHesitationMinMs = 30_000;
    public int buyHesitationMaxMs = 180_000;
    public int buyHesitationMinSaveMs = 120_000;

    // --- Enchants during long kills (SWORD ENCHANTER, right-click with the sword) ---

    /** Visit the enchanter during long kills and Max-Upgrade a randomly chosen unlocked, non-maxed, affordable enchant per tab. */
    public boolean enchantsEnabled = true;
    /**
     * 0.9.30 pick weight: 1 + enchantLagBias × (1 − level/maxLevel). 0 = every affordable
     * non-maxed enchant on the tab has the same chance (Drew's ask); 1–2 tilts toward the
     * enchant furthest from max.
     */
    public double enchantLagBias = 0.0;
    /** 0.9.30: an entity under the crosshair when the enchanter is opened gets this glance up first (0 = off). */
    public int enchantOpenClearPitchDeg = 15;
    /** Cook this long before opening the menu mid-cook (the DPS/ETA read needs a few samples). */
    public int enchantCookSettleMs = 3_000;
    /**
     * Hazard trigger (0.9.11): every post-kill lull (and once per long cook) rolls
     * against a chance that is 0 until enchantHazardRampStartMs since the last visit,
     * rising (squared) to enchantHazardFullChance at enchantHazardRampFullMs. With
     * ~5s kills the mean spacing is ~6 min with a long tail — no fixed cadence.
     */
    public int enchantHazardRampStartMs = 120_000;
    public int enchantHazardRampFullMs = 720_000;
    public double enchantHazardFullChance = 0.08;
    /** Multiply the hazard by up to this once a tab balance passes the cheapest upgradable price seen on the last scan. */
    public double enchantHazardPullMaxMult = 3.0;
    /** Mid-cook the time is free: hazard bonus, evaluated once per cook with at least this much left. */
    public double enchantHazardCookBonus = 2.0;
    public int enchantCookMinEtaMs = 20_000;
    /** One visit in ~ten happens with nothing to buy (open, scan, close). */
    public double enchantCuriosityChance = 0.10;
    /** No visit rolls for a random window after a zone advance, so visits never land a fixed beat after arriving. */
    public int enchantPostZoneQuietMinMs = 30_000;
    public int enchantPostZoneQuietMaxMs = 90_000;
    /** A visit needs at least one tab currency to have grown this much since the last visit. */
    public double enchantMinBalanceGrowthPct = 0.10;
    /** Wrap the visit up (finish the click in flight, close) once the mob has this little time left. */
    public int enchantWrapUpEtaMs = 5_000;
    /** Time allowed for the GUI to appear after the right-click / an enchant click. */
    public int enchantOpenTimeoutMs = 2_500;
    /** Human pauses: look at the menu, after a tab click, after a purchase. */
    public int enchantLookMinMs = 800;
    public int enchantLookMaxMs = 2_000;
    public int enchantTabSettleMinMs = 400;
    public int enchantTabSettleMaxMs = 900;
    public int enchantBuySettleMinMs = 1_200;
    public int enchantBuySettleMaxMs = 2_500;
    /** Safety cap on one visit; the menu is closed when it elapses. A full three-tab visit with several buys takes 30–90s. */
    public int enchantMaxMenuMs = 180_000;
    /** Open the menu via the interaction manager instead of a synthetic use-key press (fallback). */
    public boolean enchantOpenViaInteract = false;
    /** After this many aborted visits in a row (menu never opens, GUI keeps vanishing) stop trying until the next toggle. */
    public int enchantMaxConsecutiveAborts = 3;
    /**
     * A container's slot contents arrive a tick after its screen opens. Any container
     * younger than this is left alone (neither enchanter nor captcha) so a hand-opened
     * enchanter is never classified while still empty.
     */
    public int guiRecognizeGraceMs = 300;
    /**
     * One of our own menus (enchanter, Rebirth GUI, Upgrades) open this long with no
     * controller driving it is closed (stray_gui_close) instead of idling the bot
     * until the toggle key; 0 = never. 16:31 log: an enchanter left open by an early
     * abort parked the bot for 90s.
     */
    public int strayGuiCloseMs = 8000;
    /**
     * A sidebar money drop of 99%+ counts as a rebirth (money-collapse) only when the
     * new value is below this; a bigger "collapse" is a suffix read on the wrong scale
     * (T → Q → Qa on this server) and is logged suffix_scale_suspect instead.
     */
    public double moneyCollapseMaxValue = 1e12;
    /** Tab button names, in the order they are visited; each is also the sidebar currency it spends. */
    public List<String> enchantTabs = List.of("souls", "essence", "shards");
    /** Lore line that identifies the enchanter GUI by content (its title is formatting-only). */
    public String enchantSignaturePattern = "/activation chance/";
    /** "Level: 1,321 / 2,000" → current / max. */
    public String enchantLevelPattern = "/\\blevel:\\s*(?<cur>[\\d,]+)\\s*\\/\\s*(?<max>[\\d,]+)/";
    /** "Price: 7,105,000 Souls" → amount + currency (the currency word is never swallowed as a suffix). */
    public String enchantPricePattern =
        "/\\bprice:\\s*(?<amount>[\\d,.]+(?:\\s*(?!souls|essence|shards)[A-Za-z]{1,4})?)\\s*(?<currency>souls|essence|shards)/";
    /** "LOCKED (Requires Sword Level 50)" — never clicked. */
    public String enchantLockedPattern = "/^\\W*locked\\b/";
    /** Max Upgrade hopper lore "* Levels: 1" → levels the click would buy (0 = unaffordable). */
    public String enchantMaxLevelsPattern = "/\\blevels:\\s*(?<n>[\\d,]+)/";
    public String enchantMaxUpgradeName = "max upgrade";
    /** The sub-GUI title ("Soul Magnet Upgrade") — that one IS plain text. */
    public String enchantUpgradeTitlePattern = "/\\bupgrade\\s*$/";
    /** The held item counts as the sword when its name or lore matches. */
    public String enchantSwordPattern = "/\\bsword\\b|enchants:/";
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
     * to chat the instant money crosses the threshold. Default 0 since 0.9.7: the
     * post-kill window already provides the lull, and at a 90s TTK one extra kill
     * held 2.75B against a 2.5B stage for a minute and a half.
     */
    public int minKillsAfterAffordable = 0;
    /** Sidebar key used for afford checks (chicken / money / souls...). */
    public String moneyCurrency = "money";
    /**
     * Strict, anchored upgrade fail lines (verified against live EnchantedMC logs).
     * The amount in "You need X Money" is the REMAINING GAP — it shrinks as you earn.
     * Classification is additionally gated: only within {@link #upgradeResponseWindowMs}
     * of our own send, and never on lines with a player/broadcast prefix (» or [rank]).
     */
    public List<String> upgradeFailPatterns = List.of(
        "/^you (?:don'?t|do not) have enough money\\b/",
        "/(?i)you need\\s+\\$?.+\\s+money\\s+to\\s+rebirth/"
    );
    /** Extracts the gap amount from a fail line ("You need 781.04B Money ..." / "$29.99T"). */
    public String upgradeNeedAmountPattern =
        "/(?i)you need\\s+\\$?\\(?(?<amount>[\\d,.]+\\s*[A-Za-z]{0,4})\\)?\\s*money\\b/";
    /**
     * Success chat, verified in logs. Sword prints one line PER LEVEL bought with the
     * exact price ("You have unlocked a new sword level for 6.43M!"); the amount group
     * feeds the retry floor. Zone prints "You have purchased new stage(s)!" (no amount,
     * possibly several stages). Silence after the response window remains the fallback.
     */
    public List<String> upgradeSuccessPatterns = List.of(
        "/(?i)you have unlocked a new sword level for\\s+(?<amount>[\\d,.]+\\s*[A-Za-z]{0,4})/",
        "/(?i)^you have purchased new stage/"
    );
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
    /** Humanized "notice" delay between becoming affordable and typing the buy. */
    public int buyNoticeDelayMinMs = 2000;
    public int buyNoticeDelayMaxMs = 15000;
    /** 0.9.37: the notice roll while the balance dwarfs the price (cooldownRelaxBalanceMult) - the post-rebirth snowball. */
    public int buyNoticeSnowballMinMs = 500;
    public int buyNoticeSnowballMaxMs = 3000;
    /** 0.9.37: a price the server quoted this recently collapses the per-kind send cap once affordable. */
    public int serverQuoteRelaxMs = 300_000;
    /** Stop-protocol exemption window after our own /zone max (advancing teleports you). */
    public int expectedTeleportAfterZoneMs = 8000;
    /** Same exemption after a successful rebirth diamond click (GUI closes and teleports). */
    public int expectedTeleportAfterRebirthMs = 8000;
    /**
     * An unexplained teleport holds still this long waiting for a rebirth signal before
     * the stop protocol fires (0.9.20). The server's auto-rebirth teleports with no
     * command of ours to arm it; its chat line, the money collapse (+0.4s) and the
     * sidebar counter (+4.4s) all land inside this window. 0 = stop instantly as before.
     */
    public int teleportExplainGraceMs = 6000;
    /** Server lines that mean a rebirth just happened (verbatim 15:23 log; "[!]" prefixed, so matched before the broadcast guard). */
    public List<String> rebirthChatPatterns = List.of("you have successfully rebirthed", "rebirth milestone completed");

    // --- Economy: sidebar balance + event-driven scheduling (the /bal probe was removed in 0.9.7) ---

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
    /**
     * The 60s cap collapses to commandCooldownMs while the balance is at least this
     * many times the kind's last known price: right after a rebirth the balance grows
     * 10× a minute and a 60s hold on /zone max is a real loss (logs: 8M→220M while
     * "cooldown"). 0 disables the relaxation.
     */
    public double cooldownRelaxBalanceMult = 3.0;
    /** After a buy, wait this long before trusting the sidebar (it lags ~1–2s after spends). */
    public int upgradeSpendSettleMs = 2500;
    /** After a kill, wait this long for the sidebar balance to update before evaluating affordability. */
    public int postKillEvalDelayMinMs = 1500;
    public int postKillEvalDelayMaxMs = 3000;
    /**
     * Upgrade evaluation is event-driven: a kill, a sidebar money increase
     * (evalOnMoneyIncrease — the kill credit lands ~1s after the boss bar vanishes and
     * also catches kills the client missed), and this timer as the backstop so a stalled
     * stage with a fat balance is never left unevaluated. 0 disables the timer.
     */
    public int evalFallbackMs = 30_000;
    public boolean evalOnMoneyIncrease = true;
    /**
     * Enabling the bot clears the TTK window (kill median + DPS prediction) so the
     * zone gate re-measures where you actually are — after /spawn, a manual zone
     * hop, or an AFK gap. Learned prices are kept (server state, not position).
     */
    public boolean resetTtkOnEnable = true;
    /**
     * Hard zone gate: /zone max is refused — affordable or not — while the effective
     * TTK (DPS-predicted for the mob being cooked, else the rolling kill median) is
     * above this, or unknown. Keeps the sword ahead of the stage: the 0.9.5 spiral went
     * 0.25s → 90s TTK across three zone buys and then starved. 0 disables the gate.
     */
    public int zoneMaxTtkMs = 10_000;
    /**
     * 0.9.33 tri-state gate: OPEN when the stage's kill median (three or more kills) sits
     * under the patience, HARD when it is above it, when one of the first two kills was,
     * or when the mob being cooked has already taken longer (rarity-normalised elapsed
     * time), and UNKNOWN before any of that. UNKNOWN allows the zone (the stage has not
     * been shown to be too hard) and refuses the sword — the 0.9.32 gate read UNKNOWN as
     * closed and, since every /zone max teleport empties the window, followed 11 of 12
     * zone buys with a blind sword buy (15.98Q on lvl7 with 17.33Q in hand and a 4.4T zone
     * floor; 2.48T four seconds before the median opened the gate). This is the number of
     * kills on the stage a /zone max landed on before the next zone buy (a person looks at
     * the new stage first); 0 = chain-buy on the teleport.
     */
    public int zoneMinStageKills = 1;
    /**
     * 0.9.36: the retreat question, measured only. On a fresh stage the first time the gate
     * reads HARD, and after every sword buy on it, log zone_back_candidate: what this stage
     * earns per minute right now (money per kill over the kill time) against the previous
     * stage's best rate, and whether /zone previous would have paid (there > here x margin).
     * No command is sent. 2026-09-04 lvl16 -> 17: the x27 money-per-kill step cancelled the
     * x29 kill-time step, so the answer was "no" - the measurement says when that flips.
     * zoneMoneyGrowthPrior prices this stage's kills off the previous stage's before the
     * first kill lands (the logs measured x27-x81 a stage; 20 is the conservative end).
     */
    public boolean zoneBackMeasureEnabled = true;
    public double zoneBackMargin = 1.5;
    public double zoneMoneyGrowthPrior = 20.0;
    /**
     * A bot toggle within this many ms on the same stage (no zone change or teleport in
     * between) keeps the kill window and the patience roll instead of clearing them
     * (2026-09-04 14:55: six toggles in 37 s emptied the window each time and the gate
     * never opened). 0 = always clear (0.9.32 behaviour).
     */
    public int ttkKeepOnReenableMs = 60_000;
    /**
     * Legacy escape hatch: let the DPS prediction of the mob being cooked fill the gate
     * while the stage is UNKNOWN (the 0.9.23-0.9.32 rule). Off since 0.9.33: one slow RARE
     * mob's prediction (35 s at 05:56:13) closed the gate and bought a 5.79SS sword
     * 12.3SS short of the rebirth while the median three seconds later was 5.5 s.
     */
    public boolean gateUsesPrediction = false;
    /**
     * 0.9.33: upgrade responses that were not answers to our own typed command (Drew
     * playing by hand: "You need 8.81SS Money", "You have unlocked a new sword level for
     * 1.24B!", "You have purchased new stage(s)!") are logged as upgrade_observed and
     * learned like ours (price, retry floor, ladder). Off = 0.9.32 (ignored).
     */
    public boolean learnObservedUpgrades = true;
    /**
     * While the bot is off the sidebar keeps being read (the economy log stays complete)
     * but balance/income rows are written at most this often per currency (a suffix change
     * or a drop of half or more always logs). 80% of a 38 MB 2026-09-04 log was bot-off
     * balance rows. 0 = every change, as before.
     */
    public int offBotLogIntervalMs = 30_000;
    /**
     * Zone patience (0.9.23): the TTK a player tolerates before wanting a sword instead
     * of the next stage is a mood, not a line. Every zone change and enable rolls this
     * stage's tolerance log-normally between zoneMaxTtkMs x min and x max (zone_patience),
     * so the same 8s median opens the gate one stage and closes it the next. Both 1 = the
     * fixed line.
     */
    public double zonePatienceMinMult = 0.6;
    public double zonePatienceMaxMult = 1.6;
    /**
     * A DPS prediction (HP at tag / boss-bar DPS) only describes the mob it was read from
     * and is refreshed every tick while that mob is cooked; one older than this is dropped
     * (17:57 log: the first slow chicken's 11.5s prediction gated zone 1 for two minutes of
     * 0.3–0.8s kills). The kill median takes over once three kills of the stage have landed.
     * 0 keeps every prediction.
     */
    public int predictedTtkMaxAgeMs = 4000;
    /** Rolling window (kills) for the median time-to-kill. */
    public int ttkWindowKills = 8;
    /**
     * Manual amount suffixes, e.g. {"Sx": 1e21}. They win over everything the bot learned
     * and over the built-in K M B T Q QQ. Since 0.9.25 nothing above QQ is built in: the
     * ladder is learned from the sidebar (see suffixLearningEnabled). List the conventional
     * short scale here (Qi 1e21, Sx 1e24, Sp 1e27, Oc 1e30, No 1e33, Dc 1e36) only if you
     * would rather guess it than learn it.
     */
    public Map<String, Double> suffixScales = Map.of();
    /**
     * Self-healing suffixes (0.9.25). The money row is read every second, so the moment
     * it steps from a known suffix onto a new one (903.74T → 1.1Q) the new scale is
     * provable: the next 1000x rung, if the value sits within [1x, suffixCrossingMaxJump]
     * of the previous poll (one kill lump can land between polls). Learned rungs persist
     * in config/ycbotchallenge-suffixes.json (delete it to relearn). A suffix a chat line
     * names before the board showed it ("$20.5QQQ" at a 2.66Q balance) gets the rung
     * above the highest known scale, provisionally, until the balance crosses into it.
     * false = 0.9.24 behavior (an unknown suffix resolves the send as a fail, nothing learned).
     */
    public boolean suffixLearningEnabled = true;
    /** A rung crossing is accepted only when the new value is at most this many times the previous poll. */
    public double suffixCrossingMaxJump = 20.0;

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
    /** Periodic human-length breaks. Focus time counts only ticks the bot actually runs. */
    public boolean breaksEnabled = true;
    /**
     * A toggle, stop-protocol or captcha pause ends any running break and starts a
     * fresh focus block on the next enable. Without this (0.9.7) a re-enable after a
     * long AFK landed straight in a 3-minute break that toggling could not clear.
     */
    public boolean breaksResetOnToggle = true;
    public int focusMinutesMin = 45;
    public int focusMinutesMax = 90;
    /** Legacy (pre-0.9.10 uniform 1–4 min breaks); inert. */
    public int breakMinutesMin = 1;
    public int breakMinutesMax = 4;
    /** Breaks are bimodal: a short stretch (breakShortChance) or a real walk-away of several minutes. */
    public double breakShortChance = 0.7;
    public int breakShortMinMs = 20_000;
    public int breakShortMaxMs = 60_000;
    public int breakLongMinutesMin = 5;
    public int breakLongMinutesMax = 15;
    /** Re-aim threshold grows with distance (× reacquireFarMult at reacquireFarBlocks beyond reach); base inside reacquireFinalBlocks. */
    public double reacquireFarMult = 3.0;
    public double reacquireFarBlocks = 4.0;
    public double reacquireFinalBlocks = 1.5;
    /** Turns over this many degrees are two movements: a coarse swing landing 8–15% short, then a settle flick. */
    public double bigTurnDeg = 60.0;
    public double bigTurnShortMinPct = 0.08;
    public double bigTurnShortMaxPct = 0.15;
    /** Ceiling on one flick's duration (0.9.x capped every big turn at exactly 700ms). */
    public int flickMaxDurationMs = 1100;
    /** Per-target reach is reach × (1 − U(0, this)); occasionally hold W a tick too long (overshootChance). */
    public double reachJitterPct = 0.2;
    public double overshootChance = 0.1;
    /** During long cooks (WATCH/HESITATE styles) glance away and back every 10–25s once the cook has run this long. */
    public int cookGlanceAfterMs = 10_000;
    public int cookGlanceMinMs = 10_000;
    public int cookGlanceMaxMs = 25_000;
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
    /**
     * A bar gone under barVanishMinCookMs with the entity still standing waits this long
     * for the entity to vanish or the sidebar money to rise before it counts as a missed
     * tag (0.9.21). Instant kills (zone 1 after a rebirth: one click, bar alive one tick,
     * money a second later) are credited this way with the bar's lifetime as TTK, which
     * is what opens the zone gate; the 17:12 log filed 36 of 39 as retags instead.
     */
    public int instantKillConfirmMs = 1500;
    /** Rarity HP scaling: TTK is divided by (1 + scale) so the zone benchmark compares across rarities. */
    public Map<String, Double> rarityHpScale = Map.of("RARE", 0.15, "EPIC", 0.30, "LEGENDARY", 0.40);

    /**
     * Bump when shipping new default patterns; loaded configs below this get pattern
     * lists replaced. The field default is 0 ON PURPOSE: Gson runs field initializers
     * before overlaying JSON, so a config file that lacks this key would otherwise
     * "look" current and skip every migration. save() always writes the current version.
     */
    public static final int CURRENT_CONFIG_VERSION = 42;
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
        if (configVersion < 10) {
            // v10: rebirth gap seed, $ amounts, 1.25× sword/zone, no /bal.
            upgradeNeedAmountPattern = fresh.upgradeNeedAmountPattern;
            upgradeFailPatterns = fresh.upgradeFailPatterns;
            upgradeSuccessPatterns = fresh.upgradeSuccessPatterns;
            rebirthCommand = fresh.rebirthCommand;
            commandCooldownMs = fresh.commandCooldownMs;
            changed = true;
        }
        if (configVersion < 11) {
            // v11: hard TTK zone gate, event-driven eval (money / timer), stall-aware
            // cook timeout, success-line bookkeeping (zone "purchased new stage(s)"),
            // relaxed cooldown, no extra-kill wait; the /bal machinery is gone.
            upgradeSuccessPatterns = fresh.upgradeSuccessPatterns;
            zoneMaxTtkMs = fresh.zoneMaxTtkMs;
            evalFallbackMs = fresh.evalFallbackMs;
            evalOnMoneyIncrease = fresh.evalOnMoneyIncrease;
            cooldownRelaxBalanceMult = fresh.cooldownRelaxBalanceMult;
            cookStallMs = fresh.cookStallMs;
            minKillsAfterAffordable = fresh.minKillsAfterAffordable;
            changed = true;
        }
        if (configVersion < 12) {
            // v12: breaks reset on toggle and count active time only; enable resets the TTK window.
            breaksResetOnToggle = fresh.breaksResetOnToggle;
            resetTtkOnEnable = fresh.resetTtkOnEnable;
            changed = true;
        }
        if (configVersion < 13) {
            // v13: enchant upgrades during long kills — evidence-based lore patterns.
            enchantTabs = fresh.enchantTabs;
            enchantSignaturePattern = fresh.enchantSignaturePattern;
            enchantLevelPattern = fresh.enchantLevelPattern;
            enchantPricePattern = fresh.enchantPricePattern;
            enchantLockedPattern = fresh.enchantLockedPattern;
            enchantMaxLevelsPattern = fresh.enchantMaxLevelsPattern;
            enchantMaxUpgradeName = fresh.enchantMaxUpgradeName;
            enchantUpgradeTitlePattern = fresh.enchantUpgradeTitlePattern;
            enchantSwordPattern = fresh.enchantSwordPattern;
            changed = true;
        }
        if (configVersion < 14) {
            // v14: rebirth settle, lazy persisted /rebirth knowledge, no follow-up re-send,
            // buy hesitation, reaction floor, bimodal breaks, aim/movement realism.
            if (reactionDelayMinMs == 120) reactionDelayMinMs = fresh.reactionDelayMinMs;
            if (buyNoticeDelayMaxMs == 8000) buyNoticeDelayMaxMs = fresh.buyNoticeDelayMaxMs;
            retryPriceGrowthMaxPct = Math.max(retryPriceGrowthMinPct, fresh.retryPriceGrowthMaxPct);
            rebirthLookMinMs = fresh.rebirthLookMinMs;
            rebirthLookMaxMs = fresh.rebirthLookMaxMs;
            breakShortChance = fresh.breakShortChance;
            breakShortMinMs = fresh.breakShortMinMs;
            breakShortMaxMs = fresh.breakShortMaxMs;
            breakLongMinutesMin = fresh.breakLongMinutesMin;
            breakLongMinutesMax = fresh.breakLongMinutesMax;
            flickMaxDurationMs = fresh.flickMaxDurationMs;
            changed = true;
        }
        if (configVersion < 15) {
            // v15: hazard-based enchanter visits replace the "45s mob + 3 min" gate.
            enchantHazardRampStartMs = fresh.enchantHazardRampStartMs;
            enchantHazardRampFullMs = fresh.enchantHazardRampFullMs;
            enchantHazardFullChance = fresh.enchantHazardFullChance;
            enchantHazardPullMaxMult = fresh.enchantHazardPullMaxMult;
            enchantHazardCookBonus = fresh.enchantHazardCookBonus;
            enchantCookMinEtaMs = fresh.enchantCookMinEtaMs;
            enchantCuriosityChance = fresh.enchantCuriosityChance;
            changed = true;
        }
        if (configVersion < 16) {
            // v16: no purchase caps — all three tabs, every affordable enchant; longer safety cap.
            if (enchantMaxMenuMs == 40_000) enchantMaxMenuMs = fresh.enchantMaxMenuMs;
            changed = true;
        }
        if (configVersion < 17) {
            // v17: held-map captcha — JSON-array prompt, 256 px map render, hint patterns.
            captchaMapPrompt = fresh.captchaMapPrompt;
            captchaMapRetryPrompt = fresh.captchaMapRetryPrompt;
            captchaChatHintPatterns = fresh.captchaChatHintPatterns;
            if (captchaMapScale == 4) captchaMapScale = fresh.captchaMapScale;
            changed = true;
        }
        if (configVersion < 18) {
            // v18: never target the zone's [AFKMOB] upgrade mob.
            ignoreMobPatterns = fresh.ignoreMobPatterns;
            changed = true;
        }
        if (configVersion < 19) {
            // v19: rebirth horizon (knobs default from the initializers; version bump records the rule).
            changed = true;
        }
        if (configVersion < 20) {
            // v20: zone-first buy order; captcha capture is map-only unless opted out.
            if (captchaCaptureMode == null || "auto".equals(captchaCaptureMode)) captchaCaptureMode = fresh.captchaCaptureMode;
            changed = true;
        }
        if (configVersion < 21) {
            // v21: giveaways, rebirth upgrades, server auto-rebirth (patterns from the 2026-09-03 log).
            giveawayAnnouncePatterns = fresh.giveawayAnnouncePatterns;
            giveawayJoinedPatterns = fresh.giveawayJoinedPatterns;
            giveawayWonPatterns = fresh.giveawayWonPatterns;
            rebirthUpgradeOrder = fresh.rebirthUpgradeOrder;
            rebirthUpgradesItemPattern = fresh.rebirthUpgradesItemPattern;
            rebirthPointsPattern = fresh.rebirthPointsPattern;
            rebirthUpgradesTitlePattern = fresh.rebirthUpgradesTitlePattern;
            rebirthUpgradeLevelPattern = fresh.rebirthUpgradeLevelPattern;
            rebirthUpgradeCostPattern = fresh.rebirthUpgradeCostPattern;
            rebirthUpgradeMaxedPattern = fresh.rebirthUpgradeMaxedPattern;
            changed = true;
        }
        if (configVersion < 22) {
            // v22: a typed reply after winning a giveaway.
            giveawayWinMessages = fresh.giveawayWinMessages;
            changed = true;
        }
        if (configVersion < 23) {
            // v23: Qa = 1e18 (server order K M B T Q Qa Qi ...), stray own-GUI closer.
            changed = true;
        }
        if (configVersion < 24) {
            // v24: auto-rebirth teleports explained by the rebirth signals instead of stopping.
            rebirthChatPatterns = fresh.rebirthChatPatterns;
            changed = true;
        }
        if (configVersion < 25) {
            // v25: instant kills credited via entity-gone / money-landed (instantKillConfirmMs).
            changed = true;
        }
        if (configVersion < 26) {
            // v26: captcha bench on the certified fixtures — x4 smoothed render, second-render
            // cross-check, look-alike second guess, the server's re-prompt line as a retry
            // signal, a longer verify window and a human reading pause.
            if (captchaMapScale == 2) captchaMapScale = fresh.captchaMapScale;
            captchaRetryPatterns = fresh.captchaRetryPatterns;
            if (captchaVerifyWaitMs == 5000) captchaVerifyWaitMs = fresh.captchaVerifyWaitMs;
            if (captchaAnswerDelayMinMs == 1200 && captchaAnswerDelayMaxMs == 1900) {
                captchaAnswerDelayMinMs = fresh.captchaAnswerDelayMinMs;
                captchaAnswerDelayMaxMs = fresh.captchaAnswerDelayMaxMs;
            }
            changed = true;
        }
        if (configVersion < 27) {
            // v27: the zone gate reads real kills (median first, DPS prediction only while
            // fresh) and rolls a per-stage patience around zoneMaxTtkMs; stale rebirth
            // floors trigger the seed probe. New knobs take their defaults.
            changed = true;
        }
        if (configVersion < 28) {
            // v28: QQ = 1e18 built in; /rebirth probe never loops and a closed GUI is not
            // a rebirth (rebirthSignalWaitMs, rebirthProbeMaxRetries take their defaults).
            changed = true;
        }
        if (configVersion < 29) {
            // v29: the suffix ladder is learned from sidebar rung crossings
            // (suffixLearningEnabled, suffixCrossingMaxJump take their defaults);
            // Qa/Qi/Sx/Sp/Oc/No/Dc left the built-in table.
            changed = true;
        }
        if (configVersion < 30) {
            // v30: nameplates are read from hologram text displays, the boss bar backstops
            // the AFK mob, Ctrl+toggle marks a mob ignored; captcha answers come from a
            // running ballot over several renders and a map still held after the answer
            // is the rejection (new knobs take their defaults).
            changed = true;
        }
        if (configVersion < 31) {
            // v31: targetZoneLevelOnly — the plate level keeps the bot on its own stage's mobs.
            changed = true;
        }
        if (configVersion < 32) {
            // v32: companions (egg visits, equip best, sliding-window delete) and the
            // Transcend ability presser; every new knob takes its default.
            changed = true;
        }
        if (configVersion < 33) {
            // v33: first kills before any typed upgrade; the egg is located as a dragon-egg
            // block (scan knobs), hologram radius 80, Ctrl+Shift+toggle spotlights an egg.
            if (companionEggSearchRadius == 60.0) companionEggSearchRadius = fresh.companionEggSearchRadius;
            changed = true;
        }
        if (configVersion < 34) {
            // v34: weighted enchant pick (enchantLagBias), TTK-aware sword gain
            // (rebirthHorizonSwordDpsMult), transcend auto-detection, enchanter open
            // glance (enchantOpenClearPitchDeg), HUD alpha/modules, in-game options
            // screen (Y). Every new knob takes its default.
            changed = true;
        }
        if (configVersion < 35) {
            // v35: egg hologram pairing window, per-location egg store, identity aim; price
            // ladders (sword x3.5, zone x55) predict the next target; HUD sword/zone rows,
            // balances row off. Every new knob takes its default.
            changed = true;
        }
        if (configVersion < 36) {
            // v36: the captcha reader is QwenCloud qwen3.6-flash on the native 128 px map, one
            // read (bench 2026-09-04: 4/4 native; every upscale lost udWn). A config still on
            // the local defaults moves over; a hand-set endpoint is left alone.
            if ("http://127.0.0.1:8000/v1/chat/completions".equals(captchaVlmEndpoint)) {
                captchaVlmEndpoint = fresh.captchaVlmEndpoint;
                captchaVlmModel = fresh.captchaVlmModel;
                captchaVlmHealthUrl = fresh.captchaVlmHealthUrl;
                captchaMapScale = fresh.captchaMapScale;
                captchaVoteRenders = fresh.captchaVoteRenders;
                captchaVoteTemperature = fresh.captchaVoteTemperature;
            }
            changed = true;
        }
        if (configVersion < 37) {
            // v37 (0.9.33): tri-state zone gate (zoneMinStageKills, ttkKeepOnReenableMs,
            // gateUsesPrediction off — the 2026-09-04 logs: 11 of 12 zone buys followed by a
            // blind sword, 15.98Q against a 4.4T zone floor), common first target on a fresh
            // stage (stageProbeCommonKills). Every new knob takes its default.
            gateUsesPrediction = fresh.gateUsesPrediction;
            // Companions: visits/last stage/egg prices persisted per user (state file), auto-found
            // eggs saved (companion_egg_saved), cheap visits capped by companionMaxZoneGapPct.
            // Sword Skins scouting (swordMenuScout*, swordSkin* patterns): price + tier from the
            // enchanter's "Swords" menu, band-checked against the ladder.
            // Menus: one timing policy (gui* knobs); the dead pre-0.9.11 knobs (enchantVisitGap*,
            // enchantSkipChance, enchantMaxBuysPerVisit, enchantMinEtaMs, enchantMaxBuysBetweenKills,
            // enchantSkipFirstTabChance, upgradePeriod*, zoneEverySwords*, zoneOverSwordRatio,
            // zoneMinSwordBuysThisZone, zoneReadyTtkMs, zoneMinReadiness, retryPriceGrowthPct)
            // are gone; Gson ignores them in an old file.
            // HUD: plan row on, module chip row off (its state lives on the Y screen now).
            hudShowModules = fresh.hudShowModules;
            hudShowPlan = fresh.hudShowPlan;
            // Logging: bot flag on every row, observed (manual) upgrade lines learned,
            // bot-off balance rows throttled (offBotLogIntervalMs), zone paid from the
            // sidebar delta (upgrade_paid).
            changed = true;
        }
        if (configVersion < 38) {
            // v38 (0.9.34): hedged reads. Evidence — 2026-09-04 16:07:13, one transient
            // HTTP 503 on the QwenCloud /v1/models probe flipped the reader "offline" for
            // 97 s; in that window begin() bailed before even capturing the map and paused
            // the bot for a human, on evidence from a route a solve never calls. Now two
            // reads are fired 3 s apart into the 0.9.26 ballot (the second lands inside the
            // 2.5-5 s reading pause, so it costs nothing end to end), a solve is never gated
            // on the health probe, and the whole attempt is bounded by captchaBudgetMs so
            // the hand-over leaves the human ~5 s of the server's ~30 s window.
            captchaBudgetMs = fresh.captchaBudgetMs;
            captchaHedgeMs = fresh.captchaHedgeMs;
            captchaHedgeMax = fresh.captchaHedgeMax;
            // 20s per read left no room to recover; a read benches at 3-5s.
            if (captchaTimeoutMs == 20000) captchaTimeoutMs = fresh.captchaTimeoutMs;
            // Two hedges can never satisfy a three-vote minimum.
            if (captchaVoteMinReads == 3) captchaVoteMinReads = fresh.captchaVoteMinReads;
            changed = true;
        }
        if (configVersion < 39) {
            // v39 (0.9.35): companions are priced by Economy.decideCompanion. The old trigger
            // knobs (companionMaxIncomeMinutes, companionEndOfRebirthMaxIncomeMinutes,
            // companionMaxZoneGapPct) are gone — Gson simply ignores them in an old file.
            // The per-rebirth cap becomes a backstop and the real one is per stage: the
            // 2026-09-04 17:21 log spent both rebirth visits on lvl15 and then refused every
            // later batch on the stage it never left.
            companionMaxRebirthDelayMin = fresh.companionMaxRebirthDelayMin;
            companionMaxVisitsPerStage = fresh.companionMaxVisitsPerStage;
            companionPriceGrowth = fresh.companionPriceGrowth;
            companionGainPrior = fresh.companionGainPrior;
            companionGainMin = fresh.companionGainMin;
            companionGainMax = fresh.companionGainMax;
            companionGainWindowMs = fresh.companionGainWindowMs;
            companionRetryAfterAbortMs = fresh.companionRetryAfterAbortMs;
            // "two stages above the last buy" was permanent on a stage you never leave.
            if (companionMinStageGain == 2) companionMinStageGain = fresh.companionMinStageGain;
            if (companionMaxVisitsPerRebirth == 2) companionMaxVisitsPerRebirth = fresh.companionMaxVisitsPerRebirth;
            changed = true;
        }
        if (configVersion < 40) {
            // v40 (0.9.36): the companion post-pass is two rules and every decline is logged.
            // The 0.9.35 race knobs (companionPatienceMinutes, companionPersistCredit,
            // companionMaxRebirthDelayPct, companionRebirthEtaMinMax) are gone - Gson ignores
            // them in an old file. New knobs (companionCyclePriorMin, companionPaybackFraction,
            // companionStageSettleMs, zoneBack*) take their defaults. Two defaults moved:
            // the settle (10 kills was 16+ minutes at a 185 s first kill) and the gain floor
            // (1.2 could never learn a dud batch). Hand-set values survive.
            if (companionStageSettleKills == 10) companionStageSettleKills = fresh.companionStageSettleKills;
            if (companionGainMin == 1.2) companionGainMin = fresh.companionGainMin;
            // The 0.9.35 fusion-menu pattern fix only filled a blank pattern, so the live
            // config kept the old "/fuse companions/" and the menu was still never found.
            if ("/fuse companions/".equals(companionFusePattern)) companionFusePattern = fresh.companionFusePattern;
            changed = true;
        }
        if (configVersion < 41) {
            // v41 (0.9.37): the 0.9.35 multiplier-pattern fix never reached a live config
            // either (v39 only filled a blank), so every companion read as multiplier-less
            // through 0.9.36; same for the fusion menu title. New knobs (fusion, observed
            // buys, gg replies, the fresh-stage prediction, the snowball notice, the
            // no-connect timeout) take their defaults.
            replaceIfOld("companionMultiplierPattern", "/multiplier:\\s*(?<x>[\\d,.]+)\\s*x/");
            replaceIfOld("companionFuseTitlePattern", "/^fuse companions\\b/");
            changed = true;
        }
        if (configVersion < 42) {
            // v42 (0.9.38): the zone boss module. Every knob is new and takes its default.
            changed = true;
        }
        configVersion = CURRENT_CONFIG_VERSION;
        return changed;
    }

    /**
     * 0.9.37: a pattern that still holds an older version's default takes the current one;
     * a hand-set value survives. The v39 and v40 migrations each missed a live pattern by
     * only filling blanks - this is the one way to ship a pattern fix.
     */
    private void replaceIfOld(String field, String oldDefault) {
        try {
            java.lang.reflect.Field f = YCBotChallengeConfig.class.getDeclaredField(field);
            Object cur = f.get(this);
            if (cur == null || oldDefault.equals(cur)) f.set(this, f.get(new YCBotChallengeConfig()));
        } catch (ReflectiveOperationException ignored) {
        }
    }

    /** Fill nulls when an older config json is missing new fields. */
    public void normalize() {
        if (balancePatterns == null) balancePatterns = List.of();
        if (ascensionChatPatterns == null) ascensionChatPatterns = List.of();
        if (prestigeChatPatterns == null) prestigeChatPatterns = List.of();
        if (captchaChatPatterns == null) captchaChatPatterns = List.of();
        YCBotChallengeConfig fresh = new YCBotChallengeConfig();
        // Captcha strings/lists: an older json (or a hand-edited one) may lack them.
        if (captchaPrompt == null || captchaPrompt.isBlank()) captchaPrompt = fresh.captchaPrompt;
        if (captchaRetryPrompt == null) captchaRetryPrompt = fresh.captchaRetryPrompt;
        if (captchaMapPrompt == null || captchaMapPrompt.isBlank()) captchaMapPrompt = fresh.captchaMapPrompt;
        if (captchaMapRetryPrompt == null) captchaMapRetryPrompt = fresh.captchaMapRetryPrompt;
        if (captchaVlmEndpoint == null || captchaVlmEndpoint.isBlank()) captchaVlmEndpoint = fresh.captchaVlmEndpoint;
        if (captchaVlmModel == null || captchaVlmModel.isBlank()) captchaVlmModel = fresh.captchaVlmModel;
        if (captchaVlmHealthUrl == null) captchaVlmHealthUrl = fresh.captchaVlmHealthUrl;
        if (captchaCaptureMode == null || captchaCaptureMode.isBlank()) captchaCaptureMode = fresh.captchaCaptureMode;
        if (captchaAnswerTemplate == null || !captchaAnswerTemplate.contains("{answer}")) {
            captchaAnswerTemplate = fresh.captchaAnswerTemplate;
        }
        if (captchaCaseAmbiguous == null) captchaCaseAmbiguous = fresh.captchaCaseAmbiguous;
        if (captchaLookalikes == null) captchaLookalikes = fresh.captchaLookalikes;
        if (captchaSecondScale < 0) captchaSecondScale = 0;
        if (captchaSecondScale > 8) captchaSecondScale = 8;
        if (captchaVoteRenders == null || captchaVoteRenders.isEmpty()) captchaVoteRenders = fresh.captchaVoteRenders;
        if (captchaVoteTemperature < 0 || captchaVoteTemperature > 1.5) captchaVoteTemperature = 0.6;
        if (captchaVoteMaxReads < 1) captchaVoteMaxReads = 1;
        if (captchaVoteMinReads < 1) captchaVoteMinReads = 1;
        if (captchaVoteMinReads > captchaVoteMaxReads) captchaVoteMinReads = captchaVoteMaxReads;
        if (captchaVoteMaxWaitMs < 0) captchaVoteMaxWaitMs = 0;
        if (captchaMapHeldRejectMs < 0) captchaMapHeldRejectMs = 0;
        if (captchaSolvedPatterns == null) captchaSolvedPatterns = fresh.captchaSolvedPatterns;
        if (captchaRetryPatterns == null) captchaRetryPatterns = fresh.captchaRetryPatterns;
        if (captchaChatHintPatterns == null) captchaChatHintPatterns = fresh.captchaChatHintPatterns;
        if (ignoreMobPatterns == null) ignoreMobPatterns = fresh.ignoreMobPatterns;
        if (nameplateHologramRadiusBlocks <= 0) nameplateHologramRadiusBlocks = 0.9;
        if (manualIgnoreRadiusBlocks <= 0) manualIgnoreRadiusBlocks = 1.5;
        if (manualIgnoreAimDeg <= 0) manualIgnoreAimDeg = 4.0;
        if (captchaMapScale < 1) captchaMapScale = fresh.captchaMapScale;
        if (captchaMapScale > 8) captchaMapScale = 8;
        if (captchaScreenMaxPx < 256) captchaScreenMaxPx = fresh.captchaScreenMaxPx;
        if (captchaScreenMaxPx > 4096) captchaScreenMaxPx = 4096;
        if (captchaSignalConfirmMs < 0) captchaSignalConfirmMs = fresh.captchaSignalConfirmMs;
        if (captchaMapDataWaitMs < 0) captchaMapDataWaitMs = fresh.captchaMapDataWaitMs;
        if (captchaSettleMs < 0) captchaSettleMs = fresh.captchaSettleMs;
        if (captchaTimeoutMs < 1000) captchaTimeoutMs = fresh.captchaTimeoutMs;
        if (captchaBudgetMs < 10_000) captchaBudgetMs = 10_000;
        if (captchaBudgetMs > 60_000) captchaBudgetMs = 60_000;
        if (captchaHedgeMs < 500) captchaHedgeMs = 500;
        if (captchaHedgeMs > 15_000) captchaHedgeMs = 15_000;
        if (captchaHedgeMax < 1) captchaHedgeMax = 1;
        if (captchaHedgeMax > 5) captchaHedgeMax = 5;
        if (captchaMaxAttempts < 1) captchaMaxAttempts = fresh.captchaMaxAttempts;
        if (captchaMaxAnswers < 1) captchaMaxAnswers = fresh.captchaMaxAnswers;
        if (captchaAnswerDelayMaxMs < captchaAnswerDelayMinMs) captchaAnswerDelayMaxMs = captchaAnswerDelayMinMs;
        if (captchaVerifyWaitMs < 0) captchaVerifyWaitMs = fresh.captchaVerifyWaitMs;
        if (chatRawPerMinute < 0) chatRawPerMinute = 0;
        if (captchaVlmHealthIntervalMs < 10_000) captchaVlmHealthIntervalMs = fresh.captchaVlmHealthIntervalMs;
        if (captchaVlmHealthTimeoutMs < 500) captchaVlmHealthTimeoutMs = fresh.captchaVlmHealthTimeoutMs;
        if (upgradeFailPatterns == null) upgradeFailPatterns = fresh.upgradeFailPatterns;
        if (upgradeSuccessPatterns == null) upgradeSuccessPatterns = fresh.upgradeSuccessPatterns;
        if (upgradeMaxedPatterns == null) upgradeMaxedPatterns = fresh.upgradeMaxedPatterns;
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
        if (suffixCrossingMaxJump < 1.05) suffixCrossingMaxJump = 20.0;
        if (rarityHpScale == null) rarityHpScale = Map.of("RARE", 0.15, "EPIC", 0.30, "LEGENDARY", 0.40);
        // migrate: 0.7.5 shipped an empty whitelist; fill it with the zone NPC's plate lines
        if (playerRadarWhitelist == null || playerRadarWhitelist.isEmpty()) {
            playerRadarWhitelist = List.of("ZONE VISIBILITY", "CLICK HERE");
        }
        if (movingTargetPolicy == null) movingTargetPolicy = "ignore";
        if (sidebarMoneyPattern == null || sidebarMoneyPattern.isBlank()) {
            sidebarMoneyPattern = "/(?i)([\\d,.]+\\s*[A-Za-z]{0,4})\\s*MONEY\\b|MONEY\\s*:?\\s*([\\d,.]+\\s*[A-Za-z]{0,4})/";
        }
        // 0.6.x shipped aimAgility 0.4 with a much slower duration law; migrate the exact
        // old default to the new one so existing configs get the faster flicks.
        if (aimAgility == 0.4) aimAgility = 1.0;
        if (swordCommand == null || swordCommand.isBlank()) swordCommand = "/swordmax";
        if (zoneCommand == null || zoneCommand.isBlank()) zoneCommand = "/zone max";
        if (rebirthCommand == null || rebirthCommand.isBlank()) rebirthCommand = "/rebirth";
        if (swordWhileSavingMaxPct < 0) swordWhileSavingMaxPct = 0;
        if (giveawayCommand == null || giveawayCommand.isBlank()) giveawayCommand = fresh.giveawayCommand;
        if (giveawayJoinChance < 0 || giveawayJoinChance > 1) giveawayJoinChance = fresh.giveawayJoinChance;
        if (giveawayJoinDelayMinMs < 0) giveawayJoinDelayMinMs = 0;
        if (giveawayJoinDelayMaxMs < giveawayJoinDelayMinMs) giveawayJoinDelayMaxMs = giveawayJoinDelayMinMs;
        if (giveawayWindowMs < 1000) giveawayWindowMs = fresh.giveawayWindowMs;
        if (giveawayAnnouncePatterns == null) giveawayAnnouncePatterns = fresh.giveawayAnnouncePatterns;
        if (giveawayJoinedPatterns == null) giveawayJoinedPatterns = fresh.giveawayJoinedPatterns;
        if (giveawayWonPatterns == null) giveawayWonPatterns = fresh.giveawayWonPatterns;
        if (giveawayWinMessages == null) giveawayWinMessages = fresh.giveawayWinMessages;
        if (giveawayWinReplyChance < 0 || giveawayWinReplyChance > 1) giveawayWinReplyChance = fresh.giveawayWinReplyChance;
        if (giveawayWinReplyDelayMinMs < 0) giveawayWinReplyDelayMinMs = 0;
        if (giveawayWinReplyDelayMaxMs < giveawayWinReplyDelayMinMs) giveawayWinReplyDelayMaxMs = giveawayWinReplyDelayMinMs;
        if (rebirthUpgradeOrder == null || rebirthUpgradeOrder.isEmpty()) rebirthUpgradeOrder = fresh.rebirthUpgradeOrder;
        if (rebirthUpgradesItemPattern == null || rebirthUpgradesItemPattern.isBlank()) rebirthUpgradesItemPattern = fresh.rebirthUpgradesItemPattern;
        if (rebirthPointsPattern == null || rebirthPointsPattern.isBlank()) rebirthPointsPattern = fresh.rebirthPointsPattern;
        if (rebirthUpgradesTitlePattern == null || rebirthUpgradesTitlePattern.isBlank()) rebirthUpgradesTitlePattern = fresh.rebirthUpgradesTitlePattern;
        if (rebirthUpgradeLevelPattern == null) rebirthUpgradeLevelPattern = fresh.rebirthUpgradeLevelPattern;
        if (rebirthUpgradeCostPattern == null) rebirthUpgradeCostPattern = fresh.rebirthUpgradeCostPattern;
        if (rebirthUpgradeMaxedPattern == null) rebirthUpgradeMaxedPattern = fresh.rebirthUpgradeMaxedPattern;
        if (rebirthUpgradeDelayMinMs < 0) rebirthUpgradeDelayMinMs = 0;
        if (rebirthUpgradeDelayMaxMs < rebirthUpgradeDelayMinMs) rebirthUpgradeDelayMaxMs = rebirthUpgradeDelayMinMs;
        if (rebirthUpgradeEnableMinKillsMin < 0) rebirthUpgradeEnableMinKillsMin = 0;
        if (rebirthUpgradeEnableMinKillsMax < rebirthUpgradeEnableMinKillsMin) rebirthUpgradeEnableMinKillsMax = rebirthUpgradeEnableMinKillsMin;
        if (rebirthUpgradeEnableDelayMinMs < 0) rebirthUpgradeEnableDelayMinMs = 0;
        if (rebirthUpgradeEnableDelayMaxMs < rebirthUpgradeEnableDelayMinMs) rebirthUpgradeEnableDelayMaxMs = rebirthUpgradeEnableDelayMinMs;
        if (rebirthUpgradeMaxClicks < 1) rebirthUpgradeMaxClicks = 1;
        if (rebirthUpgradeSettleMinMs < 0) rebirthUpgradeSettleMinMs = 0;
        if (rebirthUpgradeSettleMaxMs < rebirthUpgradeSettleMinMs) rebirthUpgradeSettleMaxMs = rebirthUpgradeSettleMinMs;
        if (rebirthUpgradeOpenTimeoutMs < 500) rebirthUpgradeOpenTimeoutMs = 4000;
        if (rebirthUpgradeMaxMenuMs < 5000) rebirthUpgradeMaxMenuMs = 60_000;
        if (companionEggPattern == null || companionEggPattern.isBlank()) companionEggPattern = fresh.companionEggPattern;
        if (companionPricePattern == null || companionPricePattern.isBlank()) companionPricePattern = fresh.companionPricePattern;
        if (companionEggExcludePattern == null) companionEggExcludePattern = fresh.companionEggExcludePattern;
        if (companionOpenPattern == null || companionOpenPattern.isBlank()) companionOpenPattern = fresh.companionOpenPattern;
        if (companionZoneStagePattern == null || companionZoneStagePattern.isBlank()) companionZoneStagePattern = fresh.companionZoneStagePattern;
        if (companionMultiplierPattern == null || companionMultiplierPattern.isBlank()) companionMultiplierPattern = fresh.companionMultiplierPattern;
        if (companionRarityPattern == null || companionRarityPattern.isBlank()) companionRarityPattern = fresh.companionRarityPattern;
        if (companionEquipBestPattern == null || companionEquipBestPattern.isBlank()) companionEquipBestPattern = fresh.companionEquipBestPattern;
        if (companionFusePattern == null || companionFusePattern.isBlank()) companionFusePattern = fresh.companionFusePattern;
        if (companionEggsTitlePattern == null || companionEggsTitlePattern.isBlank()) companionEggsTitlePattern = fresh.companionEggsTitlePattern;
        if (companionsTitlePattern == null || companionsTitlePattern.isBlank()) companionsTitlePattern = fresh.companionsTitlePattern;
        if (companionFuseTitlePattern == null || companionFuseTitlePattern.isBlank()) companionFuseTitlePattern = fresh.companionFuseTitlePattern;
        if (companionCommand == null || companionCommand.isBlank()) companionCommand = fresh.companionCommand;
        if (companionEquipSlots == null) companionEquipSlots = fresh.companionEquipSlots;
        if (companionBulkDeleteCommand == null || !companionBulkDeleteCommand.contains("{zone}")) companionBulkDeleteCommand = fresh.companionBulkDeleteCommand;
        if (companionKeepZones < 1) companionKeepZones = 1;
        if (companionMaxBulkDeletes < 0) companionMaxBulkDeletes = 0;
        if (companionEggSearchRadius < 5) companionEggSearchRadius = 80.0;
        if (companionEggScanRadius < 4) companionEggScanRadius = 4;
        if (companionEggScanRadius > 128) companionEggScanRadius = 128;
        if (companionEggScanVertical < 1) companionEggScanVertical = 1;
        if (companionEggScanVertical > 64) companionEggScanVertical = 64;
        if (companionEggHologramReach < 0.5) companionEggHologramReach = 2.5;
        if (companionEggHologramBelow < 0) companionEggHologramBelow = 3.0;
        if (companionEggHologramAbove < 0.5) companionEggHologramAbove = 5.0;
        if (companionStagesPerLocation < 1) companionStagesPerLocation = 10;
        if (swordPriceGrowth < 1.01) swordPriceGrowth = 3.5;
        if (zonePriceGrowth < 1.01) zonePriceGrowth = 55.0;
        if (priceGrowthLearnBandPct < 0) priceGrowthLearnBandPct = 0;
        if (priceGrowthLearnBandPct > 90) priceGrowthLearnBandPct = 90;
        if (upgradeFirstKillsMin < 0) upgradeFirstKillsMin = 0;
        if (upgradeFirstKillsMax < upgradeFirstKillsMin) upgradeFirstKillsMax = upgradeFirstKillsMin;
        if (companionEggReach < 1) companionEggReach = 2.5;
        if (companionEggAimDrop < 0) companionEggAimDrop = 1.2;
        if (companionEggHitRadius < 0.5) companionEggHitRadius = 1.8;
        if (companionWalkTimeoutMs < 5000) companionWalkTimeoutMs = 45_000;
        if (companionEggsMin < 1) companionEggsMin = 1;
        if (companionEggsMax < companionEggsMin) companionEggsMax = companionEggsMin;
        if (companionMaxOpensPerVisit < 1) companionMaxOpensPerVisit = 1;
        if (companionMaxBalancePct < 0) companionMaxBalancePct = 0;
        if (companionMaxBalancePct > 100) companionMaxBalancePct = 100;
        if (companionMinStageGain < 0) companionMinStageGain = 0;
        if (companionMaxVisitsPerRebirth < 0) companionMaxVisitsPerRebirth = 0;
        if (companionMaxVisitsPerStage < 0) companionMaxVisitsPerStage = 0;
        if (companionMaxRebirthDelayMin < 0) companionMaxRebirthDelayMin = 0;
        if (companionCyclePriorMin < 1.0) companionCyclePriorMin = 1.0;
        if (companionPaybackFraction < 0) companionPaybackFraction = 0;
        if (companionPaybackFraction > 1) companionPaybackFraction = 1;
        if (companionPriceGrowth <= 1.0) companionPriceGrowth = 52.2;
        if (companionGainPrior < 1.0) companionGainPrior = 1.0;
        if (companionGainMin < 0.1) companionGainMin = 0.1;
        if (companionGainMax < companionGainMin) companionGainMax = companionGainMin;
        if (companionGainWindowMs < 1000) companionGainWindowMs = 1000;
        if (companionRetryAfterAbortMs < 0) companionRetryAfterAbortMs = 0;
        if (companionStageSettleKills < 0) companionStageSettleKills = 0;
        if (companionStageSettleMs < 0) companionStageSettleMs = 0;
        if (zoneBackMargin < 1.0) zoneBackMargin = 1.0;
        if (zoneMoneyGrowthPrior < 1.0) zoneMoneyGrowthPrior = 1.0;
        if (companionFuseMinGroup < 2) companionFuseMinGroup = 2;
        if (companionFuseAllPattern == null || companionFuseAllPattern.isBlank()) companionFuseAllPattern = fresh.companionFuseAllPattern;
        if (companionObservedTolerancePct < 0) companionObservedTolerancePct = 0;
        if (ggWavePatterns == null) ggWavePatterns = fresh.ggWavePatterns;
        if (ggPerkPatterns == null) ggPerkPatterns = fresh.ggPerkPatterns;
        if (ggWaveMessages == null || ggWaveMessages.isEmpty()) ggWaveMessages = fresh.ggWaveMessages;
        if (ggPerkMessages == null || ggPerkMessages.isEmpty()) ggPerkMessages = fresh.ggPerkMessages;
        if (ggWaveChance < 0 || ggWaveChance > 1) ggWaveChance = fresh.ggWaveChance;
        if (ggPerkChance < 0 || ggPerkChance > 1) ggPerkChance = fresh.ggPerkChance;
        if (ggWaveDelayMinMs < 0) ggWaveDelayMinMs = 0;
        if (ggWaveDelayMaxMs < ggWaveDelayMinMs) ggWaveDelayMaxMs = ggWaveDelayMinMs;
        if (ggPerkDelayMinMs < 0) ggPerkDelayMinMs = 0;
        if (ggPerkDelayMaxMs < ggPerkDelayMinMs) ggPerkDelayMaxMs = ggPerkDelayMinMs;
        if (ggWaveWindowMs < 1000) ggWaveWindowMs = 1000;
        if (ggPerkWindowMs < 1000) ggPerkWindowMs = 1000;
        if (ggWaveBlockMs < 0) ggWaveBlockMs = 0;
        if (ggMinGapMs < 0) ggMinGapMs = 0;
        if (buyNoticeSnowballMinMs < 0) buyNoticeSnowballMinMs = 0;
        if (buyNoticeSnowballMaxMs < buyNoticeSnowballMinMs) buyNoticeSnowballMaxMs = buyNoticeSnowballMinMs;
        if (serverQuoteRelaxMs < 0) serverQuoteRelaxMs = 0;
        if (stageProbePredictedMult < 0) stageProbePredictedMult = 0;
        if (approachClickMaxAimDeg < 0) approachClickMaxAimDeg = 0;
        if (noConnectTimeoutMs < 500) noConnectTimeoutMs = 500;
        if (noConnectIgnoreAfter < 1) noConnectIgnoreAfter = 1;
        for (String f : new String[]{"bossEventBarPattern", "bossEventCountPattern", "bossEventStartPattern", "bossEventProgressPattern",
            "bossSpawnPattern", "bossDespawnPattern", "bossKilledPattern", "bossRewardPattern", "bossTargetNamePattern"}) {
            replaceIfOld(f, "");
        }
        if (bossScanRadius < 3) bossScanRadius = 3;
        if (bossStandTolerance < 0.3) bossStandTolerance = 0.3;
        if (bossWalkTimeoutMs < 5000) bossWalkTimeoutMs = 5000;
        if (bossEventStartGraceMs < 0) bossEventStartGraceMs = 0;
        if (bossEventMaxMs < 30_000) bossEventMaxMs = 30_000;
        if (bossNoProgressMs < 1000) bossNoProgressMs = 1000;
        if (bossMaxRescans < 1) bossMaxRescans = 1;
        if (bossMarkerMoveHits < 1) bossMarkerMoveHits = 1;
        if (bossMarkerMoveBlocks < 0.3) bossMarkerMoveBlocks = 0.3;
        if (bossClickCpsMin < 0.5) bossClickCpsMin = 0.5;
        if (bossClickCpsMax < bossClickCpsMin) bossClickCpsMax = bossClickCpsMin;
        if (bossHitLogEvery < 1) bossHitLogEvery = 1;
        if (bossMaxConsecutiveAborts < 1) bossMaxConsecutiveAborts = 1;
        if (companionDelayMinMs < 0) companionDelayMinMs = 0;
        if (companionDelayMaxMs < companionDelayMinMs) companionDelayMaxMs = companionDelayMinMs;
        if (companionSettleMinMs < 200) companionSettleMinMs = 200;
        if (companionSettleMaxMs < companionSettleMinMs) companionSettleMaxMs = companionSettleMinMs;
        if (companionOpenTimeoutMs < 500) companionOpenTimeoutMs = 4000;
        if (companionMaxVisitMs < 10_000) companionMaxVisitMs = 180_000;
        if (companionMaxConsecutiveAborts < 1) companionMaxConsecutiveAborts = 1;
        if (transcendCooldownMs < 0) transcendCooldownMs = 0;
        if (transcendActivePattern == null || transcendActivePattern.isBlank()) transcendActivePattern = fresh.transcendActivePattern;
        if (transcendEndPattern == null || transcendEndPattern.isBlank()) transcendEndPattern = fresh.transcendEndPattern;
        if (transcendCooldownPattern == null || transcendCooldownPattern.isBlank()) transcendCooldownPattern = fresh.transcendCooldownPattern;
        if (transcendRampMs < 1000) transcendRampMs = 1000;
        if (transcendFullChance < 0 || transcendFullChance > 1) transcendFullChance = 0.3;
        if (transcendFirstDelayMinMs < 0) transcendFirstDelayMinMs = 0;
        if (transcendFirstDelayMaxMs < transcendFirstDelayMinMs) transcendFirstDelayMaxMs = transcendFirstDelayMinMs;
        if (swordWhileSavingMaxPct > 100) swordWhileSavingMaxPct = 100;
        if (zoneInstantTtkMs < 0) zoneInstantTtkMs = 0;
        if (rebirthHorizonZoneGain < 1.0) rebirthHorizonZoneGain = 1.3;
        if (rebirthHorizonSwordGain < 1.0) rebirthHorizonSwordGain = 1.25;
        if (rebirthHorizonSwordDpsMult < 1.0) rebirthHorizonSwordDpsMult = 1.0;
        if (rebirthHorizonSwordDpsMult > 4.0) rebirthHorizonSwordDpsMult = 4.0;
        if (hudAlpha < 0 || hudAlpha > 1) hudAlpha = 0.55;
        if (transcendAutoDetectCount < 1) transcendAutoDetectCount = 1;
        if (transcendPressGraceMs < 0) transcendPressGraceMs = 2000;
        if (enchantLagBias < 0) enchantLagBias = 0;
        if (enchantLagBias > 3) enchantLagBias = 3;
        if (enchantOpenClearPitchDeg < 0) enchantOpenClearPitchDeg = 0;
        if (enchantOpenClearPitchDeg > 60) enchantOpenClearPitchDeg = 60;
        if (rebirthHorizonGainWindowMs < 0) rebirthHorizonGainWindowMs = 0;
        if (rebirthHorizonGainWindowMs > 0 && rebirthHorizonGainWindowMs < 30_000) rebirthHorizonGainWindowMs = 30_000;
        if (commandCooldownMs < 0) commandCooldownMs = 1100;
        if (rebirthLookMaxMs < rebirthLookMinMs) rebirthLookMaxMs = rebirthLookMinMs;
        if (postRebirthSettleMinMs < 0) postRebirthSettleMinMs = 4_000;
        if (postRebirthSettleMaxMs < postRebirthSettleMinMs) postRebirthSettleMaxMs = postRebirthSettleMinMs;
        if (postZoneSettleMinMs < 0) postZoneSettleMinMs = 2_000;
        if (postZoneSettleMaxMs < postZoneSettleMinMs) postZoneSettleMaxMs = postZoneSettleMinMs;
        if (postTeleportLookChance < 0 || postTeleportLookChance > 1) postTeleportLookChance = 0.8;
        if (retryPriceGrowthMinPct < 0) retryPriceGrowthMinPct = 0;
        if (retryPriceGrowthMaxPct < retryPriceGrowthMinPct) retryPriceGrowthMaxPct = retryPriceGrowthMinPct;
        if (rebirthRetryFloorGrowthMaxPct < 0) rebirthRetryFloorGrowthMaxPct = 0;
        if (rebirthSeedMinKillsMin < 0) rebirthSeedMinKillsMin = 0;
        if (rebirthSeedMinKillsMax < rebirthSeedMinKillsMin) rebirthSeedMinKillsMax = rebirthSeedMinKillsMin;
        if (rebirthSeedDelayMinMs < 0) rebirthSeedDelayMinMs = 0;
        if (rebirthSeedDelayMaxMs < rebirthSeedDelayMinMs) rebirthSeedDelayMaxMs = rebirthSeedDelayMinMs;
        if (rebirthReprobeMinKillsMin < 0) rebirthReprobeMinKillsMin = 0;
        if (rebirthReprobeMinKillsMax < rebirthReprobeMinKillsMin) rebirthReprobeMinKillsMax = rebirthReprobeMinKillsMin;
        if (rebirthReprobeDelayMinMs < 0) rebirthReprobeDelayMinMs = 0;
        if (rebirthReprobeDelayMaxMs < rebirthReprobeDelayMinMs) rebirthReprobeDelayMaxMs = rebirthReprobeDelayMinMs;
        if (buyHesitationChance < 0 || buyHesitationChance > 1) buyHesitationChance = 0.30;
        if (buyHesitationMinMs < 0) buyHesitationMinMs = 0;
        if (buyHesitationMaxMs < buyHesitationMinMs) buyHesitationMaxMs = buyHesitationMinMs;
        if (buyHesitationMinSaveMs < 0) buyHesitationMinSaveMs = 0;
        if (breakShortChance < 0 || breakShortChance > 1) breakShortChance = 0.7;
        if (breakShortMinMs < 1000) breakShortMinMs = 20_000;
        if (breakShortMaxMs < breakShortMinMs) breakShortMaxMs = breakShortMinMs;
        if (breakLongMinutesMin < 1) breakLongMinutesMin = 5;
        if (breakLongMinutesMax < breakLongMinutesMin) breakLongMinutesMax = breakLongMinutesMin;
        if (reacquireFarMult < 1) reacquireFarMult = 1;
        if (reacquireFarBlocks <= 0) reacquireFarBlocks = 4.0;
        if (reacquireFinalBlocks < 0) reacquireFinalBlocks = 0;
        if (bigTurnDeg <= 0) bigTurnDeg = 60.0;
        if (bigTurnShortMinPct < 0) bigTurnShortMinPct = 0;
        if (bigTurnShortMaxPct < bigTurnShortMinPct) bigTurnShortMaxPct = bigTurnShortMinPct;
        if (flickMaxDurationMs < 200) flickMaxDurationMs = 1100;
        if (reachJitterPct < 0 || reachJitterPct > 0.9) reachJitterPct = 0.2;
        if (overshootChance < 0 || overshootChance > 1) overshootChance = 0.1;
        if (cookGlanceAfterMs < 0) cookGlanceAfterMs = 10_000;
        if (cookGlanceMinMs < 1000) cookGlanceMinMs = 10_000;
        if (cookGlanceMaxMs < cookGlanceMinMs) cookGlanceMaxMs = cookGlanceMinMs;
        YCBotChallengeConfig freshEnchant = new YCBotChallengeConfig();
        if (enchantTabs == null || enchantTabs.isEmpty()) enchantTabs = freshEnchant.enchantTabs;
        if (enchantSignaturePattern == null || enchantSignaturePattern.isBlank()) enchantSignaturePattern = freshEnchant.enchantSignaturePattern;
        if (enchantLevelPattern == null || enchantLevelPattern.isBlank()) enchantLevelPattern = freshEnchant.enchantLevelPattern;
        if (enchantPricePattern == null || enchantPricePattern.isBlank()) enchantPricePattern = freshEnchant.enchantPricePattern;
        if (enchantLockedPattern == null || enchantLockedPattern.isBlank()) enchantLockedPattern = freshEnchant.enchantLockedPattern;
        if (enchantMaxLevelsPattern == null || enchantMaxLevelsPattern.isBlank()) enchantMaxLevelsPattern = freshEnchant.enchantMaxLevelsPattern;
        if (enchantMaxUpgradeName == null || enchantMaxUpgradeName.isBlank()) enchantMaxUpgradeName = freshEnchant.enchantMaxUpgradeName;
        if (enchantUpgradeTitlePattern == null || enchantUpgradeTitlePattern.isBlank()) enchantUpgradeTitlePattern = freshEnchant.enchantUpgradeTitlePattern;
        if (enchantSwordPattern == null || enchantSwordPattern.isBlank()) enchantSwordPattern = freshEnchant.enchantSwordPattern;
        if (enchantCookSettleMs < 0) enchantCookSettleMs = 3_000;
        if (enchantHazardRampStartMs < 0) enchantHazardRampStartMs = 120_000;
        if (enchantHazardRampFullMs <= enchantHazardRampStartMs) enchantHazardRampFullMs = enchantHazardRampStartMs + 600_000;
        if (enchantHazardFullChance < 0 || enchantHazardFullChance > 1) enchantHazardFullChance = 0.08;
        if (enchantHazardPullMaxMult < 1) enchantHazardPullMaxMult = 1;
        if (enchantHazardCookBonus < 0) enchantHazardCookBonus = 1;
        if (enchantCookMinEtaMs < 0) enchantCookMinEtaMs = 20_000;
        if (enchantCuriosityChance < 0 || enchantCuriosityChance > 1) enchantCuriosityChance = 0.10;
        if (enchantPostZoneQuietMinMs < 0) enchantPostZoneQuietMinMs = 0;
        if (enchantPostZoneQuietMaxMs < enchantPostZoneQuietMinMs) enchantPostZoneQuietMaxMs = enchantPostZoneQuietMinMs;
        if (enchantMinBalanceGrowthPct < 0) enchantMinBalanceGrowthPct = 0;
        if (enchantWrapUpEtaMs < 0) enchantWrapUpEtaMs = 5_000;
        if (enchantOpenTimeoutMs < 500) enchantOpenTimeoutMs = 2_500;
        if (enchantLookMaxMs < enchantLookMinMs) enchantLookMaxMs = enchantLookMinMs;
        if (enchantTabSettleMaxMs < enchantTabSettleMinMs) enchantTabSettleMaxMs = enchantTabSettleMinMs;
        if (enchantBuySettleMaxMs < enchantBuySettleMinMs) enchantBuySettleMaxMs = enchantBuySettleMinMs;
        if (enchantMaxMenuMs < 5_000) enchantMaxMenuMs = 180_000;
        if (enchantMaxConsecutiveAborts < 1) enchantMaxConsecutiveAborts = 3;
        if (guiRecognizeGraceMs < 0) guiRecognizeGraceMs = 300;
        if (strayGuiCloseMs < 0) strayGuiCloseMs = 0;
        if (strayGuiCloseMs > 0 && strayGuiCloseMs < 1000) strayGuiCloseMs = 1000;
        if (moneyCollapseMaxValue <= 0) moneyCollapseMaxValue = 1e12;
        if (expectedTeleportAfterRebirthMs < 0) expectedTeleportAfterRebirthMs = 8000;
        if (teleportExplainGraceMs < 0) teleportExplainGraceMs = 0;
        if (instantKillConfirmMs < 200) instantKillConfirmMs = 200;
        if (rebirthChatPatterns == null) rebirthChatPatterns = fresh.rebirthChatPatterns;
        if (moneyCurrency == null || moneyCurrency.isBlank() || moneyCurrency.equalsIgnoreCase("chicken")) {
            moneyCurrency = "money";
        }
        if (sidebarCurrencies == null || sidebarCurrencies.isEmpty()) {
            sidebarCurrencies = List.of("money", "souls", "essence", "shards", "credits");
        }
        if (scoreboardSnapshotMs < 500) scoreboardSnapshotMs = 5000;
        if (upgradeMinIntervalMs < 0) upgradeMinIntervalMs = 60_000;
        if (cooldownRelaxBalanceMult < 0) cooldownRelaxBalanceMult = 0;
        if (zoneMaxTtkMs < 0) zoneMaxTtkMs = 10_000;
        if (zoneMinStageKills < 0) zoneMinStageKills = 0;
        if (ttkKeepOnReenableMs < 0) ttkKeepOnReenableMs = 0;
        if (offBotLogIntervalMs < 0) offBotLogIntervalMs = 0;
        if (guiClickMinMs < 0) guiClickMinMs = 0;
        if (guiClickMaxMs < guiClickMinMs) guiClickMaxMs = guiClickMinMs;
        if (guiCloseMinMs < 0) guiCloseMinMs = 0;
        if (guiCloseMaxMs < guiCloseMinMs) guiCloseMaxMs = guiCloseMinMs;
        if (guiBetweenMinMs < 0) guiBetweenMinMs = 0;
        if (guiBetweenMaxMs < guiBetweenMinMs) guiBetweenMaxMs = guiBetweenMinMs;
        if (guiReadMinMs < 0) guiReadMinMs = 0;
        if (guiReadMaxMs < guiReadMinMs) guiReadMaxMs = guiReadMinMs;
        if (companionLookMinMs < 0) companionLookMinMs = 0;
        if (companionLookMaxMs < companionLookMinMs) companionLookMaxMs = companionLookMinMs;
        if (swordMenuScoutChance < 0 || swordMenuScoutChance > 1) swordMenuScoutChance = fresh.swordMenuScoutChance;
        if (swordMenuPriceBandPct < 0) swordMenuPriceBandPct = 0;
        if (swordSkinsButtonPattern == null || swordSkinsButtonPattern.isBlank()) swordSkinsButtonPattern = fresh.swordSkinsButtonPattern;
        if (swordSkinsButtonLorePattern == null || swordSkinsButtonLorePattern.isBlank()) swordSkinsButtonLorePattern = fresh.swordSkinsButtonLorePattern;
        if (swordSkinsTitlePattern == null || swordSkinsTitlePattern.isBlank()) swordSkinsTitlePattern = fresh.swordSkinsTitlePattern;
        if (swordSkinSignaturePattern == null || swordSkinSignaturePattern.isBlank()) swordSkinSignaturePattern = fresh.swordSkinSignaturePattern;
        if (swordSkinPricePattern == null || swordSkinPricePattern.isBlank()) swordSkinPricePattern = fresh.swordSkinPricePattern;
        if (swordSkinTierPattern == null || swordSkinTierPattern.isBlank()) swordSkinTierPattern = fresh.swordSkinTierPattern;
        if (swordSkinDamagePattern == null || swordSkinDamagePattern.isBlank()) swordSkinDamagePattern = fresh.swordSkinDamagePattern;
        if (swordSkinEquippedPattern == null || swordSkinEquippedPattern.isBlank()) swordSkinEquippedPattern = fresh.swordSkinEquippedPattern;
        if (swordSkinLockedPattern == null || swordSkinLockedPattern.isBlank()) swordSkinLockedPattern = fresh.swordSkinLockedPattern;
        if (stageProbeCommonKills < 0) stageProbeCommonKills = 0;
        if (stageProbeRarityPenaltyBlocks < 0) stageProbeRarityPenaltyBlocks = 0;
        if (zonePatienceMinMult <= 0) zonePatienceMinMult = 0.6;
        if (zonePatienceMaxMult <= 0) zonePatienceMaxMult = 1.6;
        if (zonePatienceMaxMult < zonePatienceMinMult) {
            double t = zonePatienceMinMult; zonePatienceMinMult = zonePatienceMaxMult; zonePatienceMaxMult = t;
        }
        if (predictedTtkMaxAgeMs < 0) predictedTtkMaxAgeMs = 4000;
        if (rebirthSignalWaitMs < 1000) rebirthSignalWaitMs = 6000;
        if (rebirthProbeMaxRetries < 0) rebirthProbeMaxRetries = 0;
        if (evalFallbackMs < 0) evalFallbackMs = 30_000;
        if (cookStallMs < 0) cookStallMs = 15_000;
        if (minKillsAfterAffordable < 0) minKillsAfterAffordable = 0;
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

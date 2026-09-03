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
     * Zone when the next sword costs more than this × the next zone.
     * TTK is HUD-only; this ratio is the buy switch.
     */
    public double zoneOverSwordRatio = 1.25;
    /** Minimum gap between any two typed commands (server: "again in less than 1 second"). */
    public int commandCooldownMs = 1100;
    /** Look-at-menu pause after Rebirth GUI opens, before Esc or diamond click. */
    public int rebirthLookMinMs = 600;
    public int rebirthLookMaxMs = 3500;

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

    /** Visit the enchanter during long kills and Max-Upgrade the first unlocked, non-maxed, affordable enchant per tab. */
    public boolean enchantsEnabled = true;
    /** Legacy (0.9.9 "45s mob" gate); inert since the 0.9.11 hazard trigger. */
    public int enchantMinEtaMs = 45_000;
    /** Cook this long before opening the menu mid-cook (the DPS/ETA read needs a few samples). */
    public int enchantCookSettleMs = 3_000;
    /** Legacy (0.9.9 visit gap); inert. */
    public int enchantVisitGapMinMs = 150_000;
    public int enchantVisitGapMaxMs = 330_000;
    /** Legacy (0.9.9 skip roll); inert — the hazard supplies the randomness. */
    public double enchantSkipChance = 0.0;
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
    /** Legacy (0.9.11); inert. */
    public int enchantMaxBuysBetweenKills = 2;
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
    /** Legacy (0.9.9–0.9.11 purchase caps); inert — every affordable enchant on every tab is bought, at human pace. */
    public int enchantMaxBuysPerVisit = 6;
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
    /**
     * After a successful buy the new price is unknown; retry once the balance
     * passes the old price × (1 + this). 0 = retry at the old price.
     */
    public double retryPriceGrowthPct = 0.0;
    /** Humanized "notice" delay between becoming affordable and typing the buy. */
    public int buyNoticeDelayMinMs = 2000;
    public int buyNoticeDelayMaxMs = 15000;
    /** Stop-protocol exemption window after our own /zone max (advancing teleports you). */
    public int expectedTeleportAfterZoneMs = 8000;
    /** Same exemption after a successful rebirth diamond click (GUI closes and teleports). */
    public int expectedTeleportAfterRebirthMs = 8000;

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
    /** Legacy; TTK readiness no longer requires a sword buy this zone. */
    public int zoneMinSwordBuysThisZone = 0;
    /**
     * Hard zone gate: /zone max is refused — affordable or not — while the effective
     * TTK (DPS-predicted for the mob being cooked, else the rolling kill median) is
     * above this, or unknown. Keeps the sword ahead of the stage: the 0.9.5 spiral went
     * 0.25s → 90s TTK across three zone buys and then starved. 0 disables the gate.
     */
    public int zoneMaxTtkMs = 10_000;
    /** Legacy (pre-0.9.7 log-lerp readiness); inert. */
    public int zoneReadyTtkMs = 2000;
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
    /** Legacy (0.9.10); inert — the showing tab is now detected from the items and never clicked when already selected. */
    public double enchantSkipFirstTabChance = 0.5;
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
    public static final int CURRENT_CONFIG_VERSION = 16;
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
            zoneOverSwordRatio = fresh.zoneOverSwordRatio;
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
            retryPriceGrowthMinPct = Math.max(fresh.retryPriceGrowthMinPct, retryPriceGrowthPct);
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
            enchantSkipChance = fresh.enchantSkipChance;
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
        if (zoneOverSwordRatio <= 0) zoneOverSwordRatio = 1.25;
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
        if (enchantSkipFirstTabChance < 0 || enchantSkipFirstTabChance > 1) enchantSkipFirstTabChance = 0.5;
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
        if (enchantMinEtaMs < 0) enchantMinEtaMs = 45_000;
        if (enchantCookSettleMs < 0) enchantCookSettleMs = 3_000;
        if (enchantVisitGapMinMs < 0) enchantVisitGapMinMs = 150_000;
        if (enchantVisitGapMaxMs < enchantVisitGapMinMs) enchantVisitGapMaxMs = enchantVisitGapMinMs;
        if (enchantSkipChance < 0 || enchantSkipChance > 1) enchantSkipChance = 0;
        if (enchantHazardRampStartMs < 0) enchantHazardRampStartMs = 120_000;
        if (enchantHazardRampFullMs <= enchantHazardRampStartMs) enchantHazardRampFullMs = enchantHazardRampStartMs + 600_000;
        if (enchantHazardFullChance < 0 || enchantHazardFullChance > 1) enchantHazardFullChance = 0.08;
        if (enchantHazardPullMaxMult < 1) enchantHazardPullMaxMult = 1;
        if (enchantHazardCookBonus < 0) enchantHazardCookBonus = 1;
        if (enchantCookMinEtaMs < 0) enchantCookMinEtaMs = 20_000;
        if (enchantCuriosityChance < 0 || enchantCuriosityChance > 1) enchantCuriosityChance = 0.10;
        if (enchantPostZoneQuietMinMs < 0) enchantPostZoneQuietMinMs = 0;
        if (enchantPostZoneQuietMaxMs < enchantPostZoneQuietMinMs) enchantPostZoneQuietMaxMs = enchantPostZoneQuietMinMs;
        if (enchantMaxBuysBetweenKills < 1) enchantMaxBuysBetweenKills = 2;
        if (enchantMinBalanceGrowthPct < 0) enchantMinBalanceGrowthPct = 0;
        if (enchantWrapUpEtaMs < 0) enchantWrapUpEtaMs = 5_000;
        if (enchantOpenTimeoutMs < 500) enchantOpenTimeoutMs = 2_500;
        if (enchantLookMaxMs < enchantLookMinMs) enchantLookMaxMs = enchantLookMinMs;
        if (enchantTabSettleMaxMs < enchantTabSettleMinMs) enchantTabSettleMaxMs = enchantTabSettleMinMs;
        if (enchantBuySettleMaxMs < enchantBuySettleMinMs) enchantBuySettleMaxMs = enchantBuySettleMinMs;
        if (enchantMaxMenuMs < 5_000) enchantMaxMenuMs = 180_000;
        if (enchantMaxBuysPerVisit < 1) enchantMaxBuysPerVisit = 6;
        if (enchantMaxConsecutiveAborts < 1) enchantMaxConsecutiveAborts = 3;
        if (guiRecognizeGraceMs < 0) guiRecognizeGraceMs = 300;
        if (expectedTeleportAfterRebirthMs < 0) expectedTeleportAfterRebirthMs = 8000;
        if (moneyCurrency == null || moneyCurrency.isBlank() || moneyCurrency.equalsIgnoreCase("chicken")) {
            moneyCurrency = "money";
        }
        if (sidebarCurrencies == null || sidebarCurrencies.isEmpty()) {
            sidebarCurrencies = List.of("money", "souls", "essence", "shards", "credits");
        }
        if (scoreboardSnapshotMs < 500) scoreboardSnapshotMs = 5000;
        if (zoneMinReadiness < 0 || zoneMinReadiness > 1) zoneMinReadiness = 0.5;
        if (upgradeMinIntervalMs < 0) upgradeMinIntervalMs = 60_000;
        if (cooldownRelaxBalanceMult < 0) cooldownRelaxBalanceMult = 0;
        if (zoneMaxTtkMs < 0) zoneMaxTtkMs = 10_000;
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

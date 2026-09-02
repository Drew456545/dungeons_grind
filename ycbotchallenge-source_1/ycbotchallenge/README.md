# YCBotChallenge (Fabric client mod, MC 1.21.11)

You play on your own client, press **G**, and the bot runs: walk to the nearest mob, tap it once (the server's auto-attack does the rest), wait for the kill, move to the next. Press **G** again to stop. A HUD shows live pacing; everything is logged to JSONL for the analyze/sim tools.

Built and compiled against Minecraft 1.21.11 / yarn 1.21.11+build.6 / Fabric API 0.141.6.

## Install

Drop `ycbotchallenge-0.5.1.jar` into your `mods/` folder alongside Fabric Loader (>= 0.16) and Fabric API for 1.21.11. Client-side only — nothing needed on the server.

## Use

- **G** toggles the bot (rebind in Controls → Misc). **Shift+G** toggles sprinting on/off (saved to the config; shown on the HUD).
- HUD (top-left) shows on/off, current action, kills + kills/min, rebirths + rebirths/hr, ascensions + ETA to the next one, rebirth progress %, and any active boosts read from the boss bars.
- Logs land in `.minecraft/ycbotchallenge-logs/events-<label>-<timestamp>.jsonl` — same schema as the mineflayer bot, so `analyze.js` and `simulate.js` from the bot project work on them unchanged (note: the toggle events are now named `bot_on` / `bot_off`).

## Config

`config/ycbotchallenge.json` (created with defaults on first run):

- `reach`, `targetRange` — tap distance and how far it will walk for a mob.
- `tapCooldownMs`, `reactionDelayMinMs/MaxMs`, `idleChancePerMinute` — humanization.
- `approachClickCpsMin/Max` (2–3), `approachClickMaxDist` (6) — anticipatory swing spam while closing in, with a vigor multiplier re-rolled every few seconds so the tempo wanders. Mostly whiffs at air, like a real player running up. Set max 0 to disable.
- `movement: false` — stand still and only tag what wanders into reach.
- `zoneMin`/`zoneMax` — `[x, y, z]` arrays bounding the farming zone (null = anywhere).
- `runLabel` — tag for the log file (`"baseline"`, `"asc6-geared"`, `"2x-souls"`, ...). Change it between experiment runs.
- `rebirthsPattern`, `balancePatterns`, `ascensionChatPatterns`, etc. — the sidebar/chat regexes, in case your display format changes.

### Economy (upgrade loop)

Buy-or-learn, kill-driven. There is no buy timer: every kill schedules one affordability evaluation a second or two later (`postKillEvalDelayMin/MaxMs`, default 1.5–2.5 s — the couple of seconds the scoreboard balance needs to update). At eval: if `bal >= remaining` for the preferred kind, buy; if not, the next kill re-evaluates; if cost/balance is unknown, a probe fires at most once per `probeMinIntervalMs` (75 s hard cap — never more than ~2 in 2–3 min). On enable the bot types `/bal` then `/swordmax` once (`startupProbes`) to seed both numbers; the sidebar money line keeps the balance fresh from then on.

Zone becomes the preferred buy when median TTK drops under `zoneReadyTtkMs` AND you've landed at least `zoneMinSwordBuysThisZone` (default 1) sword upgrades in the current zone — fresh-zone mobs are giga slow, so the sword catches up before you move up.

A *successful* buy re-sends the same command a second or two later — up-arrow + enter, like a player checking the next price. Since a success maxes the tier, the re-send's fail line teaches the next gap. A `maxed` response retires that kind for the session.

Zone changes force a full targeting reset: new zone = new mobs, so the target/lock state *and* the ghost blacklist are dropped (`zone_retarget` event). The same reset happens on every enable.

### Stop protocol + TTK

- **Stop protocol** (`stopProtocolEnabled`): teleport first, radar second. A single-tick displacement over `teleportThresholdBlocks` (12) stops instantly and *arms* the player radar for the rest of the session (`playerRadarArmAfterTeleport`, default on — dormant during normal grinding, so the zone NPC can never trip it). Once armed, a non-whitelisted player continuously within `playerRadarRadius` (48) for `playerRadarDwellMs` (5 s) stops the bot — a lingering stranger is staff. `playerRadarWhitelist` (matches any line of multi-line plates, §-codes stripped) and `playerRadarIgnoreStationaryMs` remain as second-line defenses; first sightings log as `radar_seen`. Fires a `stop_protocol` event and a red chat notice.
- **TTK** now measures connect → boss-bar-gone (server-authoritative death) instead of client-side entity removal, which ghosts were glitching. A bar that vanishes under `barVanishMinCookMs` with the entity still alive counts as a tag that didn't stick, not a kill.
- **Rarity HP scaling**: kill durations are normalized by `rarityHpScale` (default RARE +15%, EPIC +30%, LEGENDARY +40%) before entering the TTK window, so a tanky legendary doesn't read as slow farming. Mobs with no rarity tag get 1.0 (the nameplate parser can't see a tag that isn't there).

- `zoneReadyTtkMs` (default 2000) — median time-to-kill at/under this flips priority to `/zone max`: fast kills mean the zone is farmed out.
- `ttkWindowKills` (8) — rolling window for the median TTK. A per-zone baseline is snapshotted a few kills after each zone change and logged via `zone_benchmark` events.
- `probeMinIntervalMs` (25s) — min gap between typed commands (server spam/cooldown politeness).
- `balCommand` / `balPatterns` — the balance probe command and its reply patterns.
- `suffixScales` — extra amount suffixes merged over the built-in `K…Dc` table, e.g. `{"UTG": 1e36}`. Unknown suffixes warn once in the log.
- `upgradeMaxedPatterns` — response lines meaning a kind is fully upgraded (it is then never sent again).
- `upgradePeriodMinMs/MaxMs` — legacy; now only clamp bounds for the computed schedule.

### Ninja humanization

`ninja: true` (default) enables the realism layer; `false` restores the old mechanical behavior. All rates are tunable:

- Timing: soft bounds instead of hard clamps (`softClampMarginPct`), rare heavy-tail pauses (`tailChancePerDelay`), per-session bounds jitter (`sessionJitterPct`), fatigue drift (`fatiguePerHour`), and long distractions (`distractionChancePerMinute`, 2–30 s by default).
- Mouse: fast one-shot flicks with a big swoopy curve (~350 ms for a 30° snap at `aimAgility` 1.0 — the old default `0.4` is auto-migrated). Bump size is tunable via `curveBumpMinPct/MaxPct` (7–22% of flick distance by default), sparse idle tremor only, and small flick-tempo regimes (`agilityRegimes`) so no single Fitts regression fits. `mouseChaining` (mid-path re-targets) exists but is off by default — it reads as servo ticking.
- Mistakes: `misclickChance`, `wrongTargetChance`, `sprintHitChance`, `typoChancePerChar` (typo + backspace while typing commands).
- Session theater: `breaksEnabled` with `focusMinutesMin/Max` and `breakMinutesMin/Max`.
- `movingTargetPolicy`: `ignore` (default, ghost filter untouched) or `sometimes` (`movingTargetAttackChance`).

## Captcha auto-solve (local Qwen3-VL)

When a captcha is detected (chat line matching `captchaChatPatterns`, or a server-opened container GUI), the bot pauses combat and — with `captchaAutoSolve: true` (default) — solves it with the local model instead of waiting for you:

1. waits `captchaSettleMs` for the map to render,
2. captures it: a filled map in either hand → pixel-perfect 128×128 render from map data (upscaled ×`captchaMapScale`); else the nearest item-frame map within `captchaMapSearchRadius`; else a full framebuffer screenshot (`captchaCaptureMode` forces `"map"` or `"screen"`),
3. POSTs it to `captchaVlmEndpoint` (vLLM serving `Qwen/Qwen3-VL-4B-Instruct-FP8` — see the vllm-captcha setup scripts; from Windows, WSL2's `localhost:8000` just works),
4. asks the model for a **ranked top-3** reading (best guess + two alternatives, warned about merged/doubled glyphs like `rr`),
5. sends the best guess to chat via `captchaAnswerTemplate` (`"{answer}"`; use `"/captcha {answer}"` if the server wants a command),
6. on a `captchaRetryPatterns` rejection it fires the **next candidate instantly** (no re-capture — Sonar reuses the same image and gives 3 tries, so the top-3 maps onto them one-for-one); resumes when a `captchaSolvedPatterns` line arrives or after `captchaVerifyWaitMs` of silence; only when all candidates are exhausted does it re-capture, retry up to `captchaMaxAttempts`, then fall back to the pause-for-human behavior.

Everything is logged (`captcha_detected`, `captcha_captured`, `captcha_answer`, `captcha_solved`/`captcha_failed`), so downtime-per-captcha shows up in the analyzer. Set `captchaAutoSolve: false` to get the old hard pause back.

Defaults are tuned to Sonar (what the hackathon runs): detection matches its "enter the text in chat" prompt (it never says "captcha"); the answer is 3–4 lowercase chars from `abcdefhjkmnoprstuxyz`, sent as plain chat; a wrong try prints "wrong answer" (matched for retry — and since Sonar re-uses the same image per try, retries feed rejected guesses back to the model with a bit of temperature); success sends *no* message, so silence for `captchaVerifyWaitMs` counts as solved. Sonar's budget is ~30s and 3 tries — the defaults fit comfortably. Note Sonar verifies in a login limbo: the mid-session case (re-verification while grinding) is what the auto-solver handles; the login-time captcha happens before you'd toggle the bot anyway.

## How it detects progression

Rebirths: sidebar counter going up = rebirth; counter dropping = reset = ascension (plus chat-message patterns as a second signal, same for prestige). Boosts: boss bar titles, tracked as start/end and stamped into every event's context. Balances: raw sidebar strings (`37.16UTG` etc.) logged on change — multi-letter suffixes parse in-mod via the `suffixScales` table, so `/bal` replies and income rates work endogenously. New log events: `balance_probe`, `income`, `upgrade_plan`, `upgrade_maxed`, `zone_benchmark` (plus ninja noise: `misclick`, `target_mispick`, `sprint_hit_slip`, `distracted`, `break_start`, `aim_regime`).

## Building from source

```bash
gradle build          # Gradle 8.14+, JDK 21
# jar lands in build/libs/
```

Note: `fabric-loom` is pinned to `1.13-SNAPSHOT` because newer loom (1.14+) requires Gradle 9. If you're on Gradle 9+, you can bump loom freely.

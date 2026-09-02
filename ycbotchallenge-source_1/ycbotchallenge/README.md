# YCBotChallenge (Fabric client mod, MC 1.21.11)

You play on your own client, press **G**, and the bot runs: walk to the nearest mob, tap it once (the server's auto-attack does the rest), wait for the kill, move to the next. Press **G** again to stop. A HUD shows live pacing; everything is logged to JSONL for the analyze/sim tools.

Built and compiled against Minecraft 1.21.11 / yarn 1.21.11+build.6 / Fabric API 0.141.6.

## Install

Drop `ycbotchallenge-0.8.1.jar` into your `mods/` folder alongside Fabric Loader (>= 0.16) and Fabric API for 1.21.11. Client-side only — nothing needed on the server.

## Use

- **G** toggles the bot (rebind in Controls → Misc). **Shift+G** toggles sprinting on/off (saved to the config; shown on the HUD).
- HUD (top-left) shows on/off, current action, live sidebar bals (money/souls/essence/shards/credits), TTK + zone readiness %, kills + kills/min, and upgrade `bal/target`.
- Logs land in `.minecraft/ycbotchallenge-logs/events-<label>-<timestamp>.jsonl` — same schema as the mineflayer bot, so `analyze.js` and `simulate.js` from the bot project work on them unchanged (note: the toggle events are now named `bot_on` / `bot_off`).

## Config

`config/ycbotchallenge.json` (created with defaults on first run):

- `reach`, `targetRange` — tap distance and how far it will walk for a mob.
- `tapCooldownMs`, `reactionDelayMinMs/MaxMs`, `idleChancePerMinute` — humanization.
- `approachClickCpsMin/Max` (2–3), `approachClickMaxDist` (6) — anticipatory swing spam while closing in, with a vigor multiplier re-rolled every few seconds so the tempo wanders. Mostly whiffs at air, like a real player running up. Set max 0 to disable.
- `movement: false` — stand still and only tag what wanders into reach.
- `zoneMin`/`zoneMax` — `[x, y, z]` arrays bounding the farming zone (null = anywhere).
- `runLabel` — tag for the log file (`"baseline"`, `"asc6-geared"`, `"2x-souls"`, ...). Change it between experiment runs.
- `rebirthsPattern`, `sidebarCurrencies`, `ascensionChatPatterns`, etc. — the sidebar/chat parse knobs, in case your display format changes.

### Economy (upgrade loop)

Kill-gated buy-or-wait. `/swordmax` and `/zone max` are **not** polled. Every kill schedules one affordability evaluation after the sidebar settles (`postKillEvalDelayMin/MaxMs`, ~1.5–3s). That eval is consumed — it does not re-fire every tick. We send **only if** the settled scoreboard covers the last fail-chat price for the chosen kind, and at most once per `upgradeMinIntervalMs` (60s) per kind.

The 60s figure is a **ceiling**, not a heartbeat. Unaffordable evals re-read the scoreboard and wait; they do not type. Startup may type `/bal` once (balance seed). Unknown prices get **one** seed send per kind per session so fail-chat can teach the absolute cost — after that, never again until `book >= target`.

A *successful* buy immediately re-runs the same command (1.2–3s later, cooldown bypassed) to learn the next tier — up-arrow + enter. A fail line like `You need 536.24B Money to purchase the next sword upgrade` is the **absolute** next-tier price. A `maxed` response retires that kind for the session.

The sidebar is reread every second (vanilla `ScoreboardEntry.name()` + team prefix/suffix, § codes stripped). All of `sidebarCurrencies` (`money`, `souls`, `essence`, `shards`, `credits`) parse from rows like `| 131.56B MONEY` / `| 235 SHARDS`. A canonical snapshot is published every `scoreboardSnapshotMs` (5s) for HUD, logs, and other calcs, and again immediately before a buy so we don't type on stale money. After a spend we wait `upgradeSpendSettleMs` (2.5s) because the board lags. `/bal` replies (within 8s of a send) overwrite money in that book. A successful buy debits the paid amount (or the known target if the success line has no number) locally so a lagging high board cannot re-arm a send.

Chat is the source of truth for upgrade outcomes. Fail lines are the **absolute** next-tier price, not a remaining gap. Only phrases with `more` / `remaining` / `left` add current bal. Split zone/sword fails are stitched across ~2s (including overlay/system lines). Affordability is `snapshot.money >= swordTarget` (or `zoneTarget`). EnchantedMC formats work out of the box. Money values in the JSONL are suffixed strings (`75.1B`), not scientific notation. Config pattern lists auto-migrate (`configVersion`).

Sword vs zone is a **log-scaled TTK readiness** `R`, not a sword-count quota. Fresh-zone mobs take ~40s; each `/swordmax` drops that. `R` lerps in log-space from this zone's TTK baseline down to `zoneReadyTtkMs` (2s). On a 40s start that crosses 50/50 around ~9s and reaches 1.0 at 2s. Weights: `swordWeight = 1-R`, `zoneWeight = R`. We only pick among **affordable** kinds; zone is refused while `R < zoneMinReadiness` (0.5) even if you already have the money. A high-sword enable whose first kills are already ~2s snaps `R` to 1 and zones as soon as it is affordable. HUD prints `ttk 8.2s  zone 48%` and a `cd 41s` remainder when the 60s cap is holding.

Zone changes force a full targeting reset: new zone = new mobs, so the target/lock state *and* the ghost blacklist are dropped (`zone_retarget` event). The same reset happens on every enable. TTK baseline resets, so `R` goes back to 0 on the new ~40s curve.

### Stop protocol + TTK

- **Stop protocol** (`stopProtocolEnabled`): teleport first, radar second. A single-tick displacement over `teleportThresholdBlocks` (12) stops instantly and *arms* the player radar for the rest of the session (`playerRadarArmAfterTeleport`, default on — dormant during normal grinding, so the zone NPC can never trip it). Once armed, a non-whitelisted player continuously within `playerRadarRadius` (48) for `playerRadarDwellMs` (5 s) stops the bot — a lingering stranger is staff. `playerRadarWhitelist` (matches any line of multi-line plates, §-codes stripped) and `playerRadarIgnoreStationaryMs` remain as second-line defenses; first sightings log as `radar_seen`. Fires a `stop_protocol` event and a red chat notice.
- **TTK** now measures connect → boss-bar-gone (server-authoritative death) instead of client-side entity removal, which ghosts were glitching. A bar that vanishes under `barVanishMinCookMs` with the entity still alive counts as a tag that didn't stick, not a kill.
- **Rarity HP scaling**: kill durations are normalized by `rarityHpScale` (default RARE +15%, EPIC +30%, LEGENDARY +40%) before entering the TTK window, so a tanky legendary doesn't read as slow farming. Mobs with no rarity tag get 1.0 (the nameplate parser can't see a tag that isn't there).

- `zoneReadyTtkMs` (default 2000) — TTK at which zone readiness `R` = 1 (log-lerped from the per-zone baseline).
- `zoneMinReadiness` (0.5) — zone is not bought below this even if it is the only affordable upgrade.
- `scoreboardSnapshotMs` (5000) — how often the canonical multi-currency snapshot is published.
- `sidebarCurrencies` — names parsed from the sidebar (`money`, `souls`, `essence`, `shards`, `credits`).
- `ttkWindowKills` (8) — rolling window for the median TTK. A per-zone baseline is snapshotted a few kills after each zone change and logged via `zone_benchmark` events.
- `probeMinIntervalMs` (75s) — safety net only for unclassified upgrade replies. Unknown cost is not polled.
- `upgradeMinIntervalMs` (60s) — hard cap between kill-driven sends of the same kind. Success follow-ups bypass this. Unaffordable evals do not send.
- `upgradeSpendSettleMs` (2500) — ignore the sidebar this long after a buy (board lags after spending).
- `minKillsAfterAffordable` (1) — extra kills after the balance first covers the target before typing.
- `balCommand` / `balPatterns` — the balance probe command and its reply patterns.
- `suffixScales` — extra amount suffixes merged over the built-in `K…Dc` table, e.g. `{"UTG": 1e36}`. Unknown suffixes warn once in the log.
- `upgradeMaxedPatterns` — response lines meaning a kind is fully upgraded (it is then never sent again).
- `upgradePeriodMinMs/MaxMs`, `zoneEverySwordsMin/Max` — legacy; inert.

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

Rebirths: sidebar counter going up = rebirth; counter dropping = reset = ascension (plus chat-message patterns as a second signal, same for prestige). Boosts: boss bar titles, tracked as start/end and stamped into every event's context. Balances: all configured sidebar currencies parsed to numbers (`131.56B MONEY`, `235 SHARDS`) and snapshotted every 5s (`scoreboard_snapshot`). New log events: `scoreboard_snapshot`, `balance_probe`, `income`, `upgrade_plan`, `upgrade_maxed`, `zone_benchmark` (plus ninja noise: `misclick`, `target_mispick`, `sprint_hit_slip`, `distracted`, `break_start`, `aim_regime`).

## Building from source

```bash
gradle build          # Gradle 8.14+, JDK 21
# jar lands in build/libs/
```

Note: `fabric-loom` is pinned to `1.13-SNAPSHOT` because newer loom (1.14+) requires Gradle 9. If you're on Gradle 9+, you can bump loom freely.

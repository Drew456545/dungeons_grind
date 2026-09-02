# YCBotChallenge (Fabric client mod, MC 1.21.11)

You play on your own client, press **G**, and the bot runs: walk to the nearest mob, tap it once (the server's auto-attack does the rest), wait for the kill, move to the next. Press **G** again to stop. A HUD shows live pacing; everything is logged to JSONL for the analyze/sim tools.

Built and compiled against Minecraft 1.21.11 / yarn 1.21.11+build.6 / Fabric API 0.141.6.

## Install

Drop `ycbotchallenge-0.9.2.jar` into your `mods/` folder alongside Fabric Loader (>= 0.16) and Fabric API for 1.21.11. Client-side only — nothing needed on the server.

## Use

- **G** toggles the bot (rebind in Controls → Misc). **Shift+G** toggles sprinting on/off (saved to the config; shown on the HUD).
- HUD (top-left) shows on/off, current action, live sidebar bals (money/souls/essence/shards/credits), TTK + zone readiness %, kills + kills/min, and the upgrade line: `bal`, `+/min` income, `need` remaining and `~ETA` to the next tier.
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

Chat-driven, zero-spam, buy-or-wait. `/swordmax` and `/zone max` are **never polled** — every send is event-driven with a plausible human reason, and each goes through the humanized typing pipeline (kill-lull gating, log-normal pauses, typos).

**The money book.** Exact anchors, verified against live EnchantedMC logs:

- The sidebar `Your Balance 2.35T` row feeds the live balance every second (fresh board truth wins while it's parsing).
- `/bal` once on enable seeds the book as a backup (real reply: `Your Balances:` / ` - Money: (1.09T)`; multi-line packets are split per line).
- `Reward Summary: (60s)` → ` + 17.19B Money` accrues exact earnings every minute (overlap-clamped so re-anchoring never double-counts). Between anchors the estimate grows at the trailing summary rate, frozen 90s after the last anchor — no summaries means no income anyway.
- A fail line while the price is known re-anchors the book exactly: balance = price − gap.

**Fail lines are gaps, not prices.** `You need 781.04B Money to purchase the next sword upgrade` is how far short you are — it shrinks as you earn (logs: 781.04B → 732.08B → 683.12B). The absolute next-tier price is `balance at fail + gap`, self-correcting on every fail.

**The buy loop.** After each kill (post-settle, `postKillEvalDelayMin/MaxMs`), if the book covers a known price, the bot waits a humanized notice delay (`buyNoticeDelayMin/MaxMs`, 2–8s) and types the command. A fail line updates the price and it goes quiet. **Silence within `successSilenceMs` (3s) means the purchase succeeded** — no reliance on success wording — then the price resets to unknown, a human-plausible `/bal` re-seed follows (1.5–4s), and the next attempt waits until the balance passes the **old** price again (`retryPriceGrowthPct` margin). Unknown prices get one seed send per kind per session. `upgradeMinIntervalMs` (60s) per kind remains as a backstop ceiling, not a heartbeat.

**Strict chat gate.** Upgrade lines classify only within `upgradeResponseWindowMs` (4s) of our own send, never on player/broadcast lines (`»` or `[rank]` prefix), and only via anchored patterns. This kills the 0.8.x failure mode where `EnchantedMC »` broadcasts matched the loose `enchanted` success pattern and fail lines were eaten as `/bal` replies. Success/maxed wording is never trusted; unrecognized lines are raw-logged (`upgrade_response_raw`) after both upgrade sends (6s) and `/bal` probes (8s), so reply formats are always captured for evidence-based tuning.

**Zone.** Same model for `/zone max` (fail gap → `zoneTarget = bal + gap`), plus the TTK readiness gate below. A successful zone advance teleports you, so the stop protocol exempts displacements for `expectedTeleportAfterZoneMs` (8s) after our own zone send. Zone changes are detected from the sidebar `Zone 1` row (colon-less) or the `All mobs have been respawned in your zone.` broadcast, and force a full targeting reset (`zone_retarget`).

Sword vs zone is a **log-scaled TTK readiness** `R`, not a sword-count quota. Fresh-zone mobs take ~40s; each `/swordmax` drops that. `R` lerps in log-space from this zone's TTK baseline down to `zoneReadyTtkMs` (2s). On a 40s start that crosses 50/50 around ~9s and reaches 1.0 at 2s. Weights: `swordWeight = 1-R`, `zoneWeight = R`. We only pick among **affordable** kinds; zone is refused while `R < zoneMinReadiness` (0.5) even if you already have the money. A high-sword enable whose first kills are already ~2s snaps `R` to 1 and zones as soon as it is affordable. HUD prints `ttk 8.2s  zone 48%`, the live `need` and `~ETA` to the next tier from the summary rate, and a `cd 41s` remainder when the 60s cap is holding. TTK baseline resets on zone changes, so `R` goes back to 0 on the new ~40s curve.

The sidebar is reread every second: the `Your Balance` money row feeds the buy path directly, other currencies (`sidebarCurrencies`) are logged, and snapshots publish every `scoreboardSnapshotMs` (5s). `debugSidebar` defaults to **true** while parsers are tuned from live evidence — every new sidebar row lands in the JSONL as `sidebar_raw`. Money values in the JSONL are suffixed strings (`75.1B`), not scientific notation. Config pattern lists auto-migrate (`configVersion` 7 replaces economy patterns with the strict evidence-based defaults and flips `debugSidebar` on).

### Stop protocol + TTK

- **Stop protocol** (`stopProtocolEnabled`): teleport first, radar second. A single-tick displacement over `teleportThresholdBlocks` (12) stops instantly and *arms* the player radar for the rest of the session (`playerRadarArmAfterTeleport`, default on — dormant during normal grinding, so the zone NPC can never trip it). Once armed, a non-whitelisted player continuously within `playerRadarRadius` (48) for `playerRadarDwellMs` (5 s) stops the bot — a lingering stranger is staff. `playerRadarWhitelist` (matches any line of multi-line plates, §-codes stripped) and `playerRadarIgnoreStationaryMs` remain as second-line defenses; first sightings log as `radar_seen`. Fires a `stop_protocol` event and a red chat notice.
- **TTK** now measures connect → boss-bar-gone (server-authoritative death) instead of client-side entity removal, which ghosts were glitching. A bar that vanishes under `barVanishMinCookMs` with the entity still alive counts as a tag that didn't stick, not a kill.
- **Rarity HP scaling**: kill durations are normalized by `rarityHpScale` (default RARE +15%, EPIC +30%, LEGENDARY +40%) before entering the TTK window, so a tanky legendary doesn't read as slow farming. Mobs with no rarity tag get 1.0 (the nameplate parser can't see a tag that isn't there).

- `zoneReadyTtkMs` (default 2000) — TTK at which zone readiness `R` = 1 (log-lerped from the per-zone baseline).
- `zoneMinReadiness` (0.5) — zone is not bought below this even if it is the only affordable upgrade.
- `scoreboardSnapshotMs` (5000) — how often the canonical multi-currency snapshot is published.
- `sidebarCurrencies` — names parsed from the sidebar (`money`, `souls`, `essence`, `shards`, `credits`).
- `ttkWindowKills` (8) — rolling window for the median TTK. A per-zone baseline is snapshotted a few kills after each zone change and logged via `zone_benchmark` events.
- `upgradeMinIntervalMs` (60s) — backstop ceiling between sends of the same kind. Unaffordable evals do not send.
- `buyNoticeDelayMinMs/MaxMs` (2–8s) — humanized delay between becoming affordable and typing.
- `successSilenceMs` (3000) — no fail line this long after a send = purchase succeeded.
- `retryPriceGrowthPct` (0) — unknown-price retry fires when the balance passes old price × (1 + this).
- `upgradeResponseWindowMs` (4000) — replies are only attributable to our send within this window.
- `expectedTeleportAfterZoneMs` (8000) — stop-protocol exemption after our own `/zone max`.
- `upgradeSpendSettleMs` (2500) — ignore the sidebar this long after a buy (board lags after spending).
- `minKillsAfterAffordable` (1) — extra kills after the balance first covers the target before typing.
- `balCommand` / `balPatterns` — the balance probe command and its anchored reply patterns (` - Money: (1.09T)`).
- `summaryHeaderPattern` / `summaryMoneyPattern` — the `Reward Summary: (60s)` / ` + 17.19B Money` income lines.
- `upgradeFailPatterns` / `upgradeNeedAmountPattern` — anchored fail lines and the gap-amount extractor.
- `suffixScales` — extra amount suffixes merged over the built-in `K…Dc` table, e.g. `{"UTG": 1e36}`. Unknown suffixes warn once in the log.
- `upgradeMaxedPatterns` — anchored response lines meaning a kind is fully upgraded (it is then never sent again).
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

Rebirths: sidebar counter going up = rebirth; counter dropping = reset = ascension (plus chat-message patterns as a second signal, same for prestige). Boosts: boss bar titles, tracked as start/end and stamped into every event's context. Balances: the chat-driven money book (`/bal` seed, reward-summary accrual, fail-implied anchors) plus configured sidebar currencies snapshotted every 5s (`scoreboard_snapshot`). Log events: `scoreboard_snapshot`, `balance_probe`, `income`, `income_summary`, `upgrade_plan`, `upgrade_send`, `upgrade_chat`, `upgrade_result`, `upgrade_skip`, `upgrade_maxed`, `upgrade_response_raw`, `zone_benchmark`, `zone_teleport` (plus ninja noise: `misclick`, `target_mispick`, `sprint_hit_slip`, `distracted`, `break_start`, `aim_regime`).

## Building from source

```bash
gradle build          # Gradle 8.14+, JDK 21
# jar lands in build/libs/
```

Note: `fabric-loom` is pinned to `1.13-SNAPSHOT` because newer loom (1.14+) requires Gradle 9. If you're on Gradle 9+, you can bump loom freely.

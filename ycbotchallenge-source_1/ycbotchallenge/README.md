# YCBotChallenge (Fabric client mod, MC 1.21.11)

You play on your own client, press **G**, and the bot runs: walk to the nearest mob, tap it once (the server's auto-attack does the rest), wait for the kill, move to the next. Press **G** again to stop. A HUD shows live pacing; everything is logged to JSONL for the analyze/sim tools.

Built and compiled against Minecraft 1.21.11 / yarn 1.21.11+build.6 / Fabric API 0.141.6.

## Install

Drop `ycbotchallenge-0.9.11.jar` into your `mods/` folder alongside Fabric Loader (>= 0.16) and Fabric API for 1.21.11. Client-side only — nothing needed on the server.

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

Chat-driven, zero-spam, buy-or-wait. `/swordmax`, `/zone max` and `/rebirth` are **never polled** — every send is event-driven with a plausible human reason, and each goes through the humanized typing pipeline (kill-lull gating, log-normal pauses, typos).

**The balance is the sidebar.** The `5.62T ᴍᴏɴᴇʏ` row is reread every second and is the only money figure the bot uses. Money is credited per kill about a second after the boss bar vanishes (logs: kill 20:56:44.6 → row 20:56:45.5). `Reward Summary: (60s)` → ` + 17.19B Money` feeds only the income *rate* (HUD ETA). The 0.9.x money book (`/bal` seed, accrual, projection) is gone — it invented money between anchors and wrote its estimate back over the live row.

**Fail lines are gaps, not prices.** `You need 781.04B Money to purchase the next sword upgrade` is how far short you are — it shrinks as you earn (logs: 781.04B → 732.08B → 683.12B). The absolute next-tier price is `sidebar balance at fail + gap`, self-correcting on every fail. If the balance is somehow unknown at fail time the gap itself becomes the retry floor, so a kind can never latch shut.

**Evaluation triggers.** An evaluation is armed by a kill, by a sidebar money **increase** (`evalOnMoneyIncrease` — the kill credit itself, which also covers kills the client missed), and by a timer (`evalFallbackMs`, 30s) as the backstop. 0.9.6 was kill-only and sat on 8B→21B with a 1.24B sword for 108s because nothing died. Each eval waits for the board to settle (`postKillEvalDelayMin/MaxMs`, `upgradeSpendSettleMs`).

**The buy loop.** If the balance covers a known price, the bot waits a humanized notice delay (`buyNoticeDelayMin/MaxMs`, 2–8s) and types the command. A fail line updates the price and it goes quiet. Success is read from the server's own line when there is one — `You have unlocked a new sword level for 1.24B!` (one per level; `/swordmax` buys every level it can, so the **last** amount is the retry floor for the next tier) and `You have purchased new stage(s)!` — else **silence within `successSilenceMs` (3s)** counts as success. After a success the price resets to unknown and the next attempt waits until the balance passes the old price again (`retryPriceGrowthPct` margin). Unknown prices get one exploratory send per kind, **sword first** — an unknown sword price beats an affordable zone. `upgradeMinIntervalMs` (60s) per kind is a backstop ceiling, collapsed to `commandCooldownMs` while the balance is `cooldownRelaxBalanceMult` (3×) over the kind's last price (right after a rebirth the balance grows 10× a minute; logs show zone skipped for "cooldown" from 8M to 220M).

**Strict chat gate.** Upgrade lines classify only within `upgradeResponseWindowMs` (4s) of our own send, never on player/broadcast lines (`»` or `[rank]` prefix), and only via anchored patterns. This kills the 0.8.x failure mode where `EnchantedMC »` broadcasts matched the loose `enchanted` success pattern. Unrecognized lines are raw-logged (`upgrade_response_raw`) for 6s after each send, so reply formats are always captured for evidence-based tuning.

**Zone.** Same model for `/zone max` (fail gap → `zoneTarget = bal + gap`), behind the hard TTK gate below. A successful zone advance teleports you, so the stop protocol exempts displacements for `expectedTeleportAfterZoneMs` (8s) after our own zone send. EnchantedMC's sidebar has **no Zone row**, so zone changes come from our own teleport (`zone_teleport`), the mob level prefix on boss bars (`LVL2 Rabbit` → `LVL5 Goat`, logged as `boss_level`, debounced over two polls), the `All mobs have been respawned in your zone.` broadcast, or a `Zone N` row where a server has one. Any of them fires `zone_change`, clears the TTK window and forces a full targeting reset (`zone_retarget`).

**Rebirth reset.** A rebirth zeroes money and resets sword/zone progression, so every learned price is stale the moment it happens. Detected two ways: the sidebar `rebirth: N` counter incrementing (or resetting, for ascensions), and a money collapse (≥1B → ~0 while we didn't just buy something). Either wipes all learned prices, gaps, maxed flags, and seed flags (`economy_reset` event) — the loop re-discovers post-rebirth prices with one fresh seed send per kind.

**Sword vs zone: a hard TTK gate.** `/zone max` is refused — affordable or not — while the *effective TTK* is above `zoneMaxTtkMs` (10s) or unknown. Effective TTK is the DPS-predicted whole-mob time (`HP at tag / boss-bar DPS`, readable a couple of seconds into the first mob of a new stage) when available, else the rolling kill median (`ttkWindowKills`). The window is cleared on every zone change, so the previous stage's fast kills can never open the gate for the next stage. This is what stops the 0.9.5 spiral, where a readiness value pinned at 100% bought three zones against three swords and TTK went 0.25s → 3.8s → 7.2s → 75s → 90s until nothing died. Among **affordable** kinds with the gate open, zone wins when the next sword costs more than `zoneOverSwordRatio` (1.25×) the next zone. Rebirth, once its gap is known and covered, pre-empts both. HUD prints the live `need` and `~ETA` to the next tier from the summary rate, and a `cd 41s` remainder when a cap is holding; `status` events carry `zoneReady` (1.0 with the gate open, `zoneMaxTtkMs / ttk` above it).

**Cook timeout.** `maxCookMs` (90s) abandons a tagged mob only once its boss-bar HP has also stopped dropping for `cookStallMs` (15s). Fresh-stage mobs can legitimately take that long (the Goat in the logs died at 89.8s; 0.9.6 abandoned the next one at 90.0s, one second before it died, and lost the kill credit and its 6.48B).

The sidebar is reread every second: the money row feeds the buy path directly, other currencies (`sidebarCurrencies`) are logged, and snapshots publish every `scoreboardSnapshotMs` (5s). `debugSidebar` defaults to **true** while parsers are tuned from live evidence — every new sidebar row lands in the JSONL as `sidebar_raw`. Money values in the JSONL are suffixed strings (`75.1B`), not scientific notation. Config knobs auto-migrate (`configVersion` 11 forces the 0.9.7 defaults: success patterns, zone gate, eval triggers, cooldown relaxation, stall-aware cook timeout, `minKillsAfterAffordable` 0).

### 0.9.10: de-fingerprinting the loop

A timing pass over every 2026-09-02/03 log found server-visible regularities; each has a fix and a knob:

- **No success follow-up.** 0.9.x re-sent the same command 3.5–4.9s after every success to learn the next price (8/8, spread 10%). Now the next tier stays unknown until the balance passes the last paid price × (1 + a growth rolled per success in `retryPriceGrowthMinPct/MaxPct`, 0.2–0.8), logged as `retryAt` on `upgrade_result`.
- **No enable ritual.** Learned prices persist per **username** in `config/ycbotchallenge-state.json` (`state_loaded` / `state_saved`; wiped to the rebirth floor on a rebirth), so a restart never probes. An unknown account gets one `/rebirth` seed per session only after `rebirthSeedMinKillsMin/Max` (5–20) kills **and** `rebirthSeedDelayMin/MaxMs` (2–10 min). After a rebirth the old cost is a floor: one deferred re-probe learns the next goal after `rebirthReprobeMinKillsMin/Max` (15–40) kills and `rebirthReprobeDelayMin/MaxMs` (5–15 min) (`upgrade_plan via=reprobe`), or earlier if money passes the floor × (1 + roll ≤ `rebirthRetryFloorGrowthMaxPct`) (`via=retry-floor`).
- **Rebirth settle.** After our own teleport the bot stands `postRebirthSettleMin/MaxMs` (4–9s; zone advances `postZoneSettleMin/MaxMs` 2–5s), glances around (`postTeleportLookChance`, `mouse_flick reason=settle-look`), and only then retargets (`settle_start` / `settle_end`). 0.9.9 tagged a stale mob one tick after the rebirth teleport.
- **Buy hesitation on long saves.** With `buyHesitationChance` (0.3) an affordable buy is held `buyHesitationMin/MaxMs` (30s–3min) — only when the price has been known for `buyHesitationMinSaveMs` (2 min) and the balance is under `cooldownRelaxBalanceMult` × price. Never in the post-rebirth snowball (back to zone 1, `/swordmax` levels lost, enchants kept, balance 10×/min), never for rebirth, never twice in a row (`upgrade_hesitate`, `upgrade_skip reason=hesitate`). `buyNoticeDelayMaxMs` is 15s.
- **Reaction floor** `reactionDelayMinMs` 200 (was 120). **Bimodal breaks:** `breakShortChance` (0.7) of `breakShortMin/MaxMs` (20–60s), else `breakLongMinutesMin/Max` (5–15 min); `break_start kind=short|long`.
- **Aim and movement.** The re-aim threshold scales with distance (`reacquireFarMult` ×3 at `reacquireFarBlocks` beyond reach, base inside `reacquireFinalBlocks`) — no more 300ms correction chains while walking. Turns over `bigTurnDeg` (60°) land `bigTurnShortMin/MaxPct` (8–15%) short and get a settle flick (`mouse_flick short=true` then `settle=true`); `flickMaxDurationMs` 1100 replaces the 700ms wall. Per-target reach is jittered (`reachJitterPct` 0.2) with an occasional overshoot (`overshootChance`). Long cooks get glances (`cookGlanceAfterMs`, every `cookGlanceMin/MaxMs`; `reason=glance` / `glance-back`). The enchanter's first tab is skipped `enchantSkipFirstTabChance` of the time.

### Enchants during long kills

The server auto-attacks a tagged mob until it dies, so a fresh-stage 50–120s kill is idle time. When the mob's predicted remaining time is at least `enchantMinEtaMs` (45s), the bot right-clicks with the sword to open the **SWORD ENCHANTER**, walks the `enchantTabs` (souls → essence → shards, each spending its own sidebar currency), and for the **first** enchant in slot order that is not `LOCKED`, not maxed, and affordable, opens its `<Name> Upgrade` GUI and clicks **Max Upgrade** (buys level 1 upward for an unowned enchant). Then the next enchant, then the next tab. No optimisation by design.

Evidence-based details: the enchanter's title is formatting-only (a font glyph draws the words), so the GUI is recognised by its item tooltips (`enchantSignaturePattern`, "ACTIVATION CHANCE") — and a hand-opened enchanter is therefore never mistaken for a captcha (0.9.8 paused on every manual open). Enchant state comes from the tooltips: `Level: 1,321 / 2,000`, `Price: 7,105,000 Souls`, `LOCKED (Requires Sword Level 50)`; the Max Upgrade hopper's `* Levels: 1` / `* Price: …` decides the final click. The sword's own tooltip (only owned enchants) is logged once per visit as `sword_lore`, never used for decisions.

**When it opens (0.9.11 hazard).** The 0.9.9 "45s mob and 3 minutes" gate only ever opened the menu while a fresh-stage mob was being fought, so visits clustered right after zone advances — a fingerprint. Now every post-kill lull (`via=lull`) and, once per long cook with `enchantCookMinEtaMs` (20s) left (`via=cook`), rolls against a hazard: zero until `enchantHazardRampStartMs` (2 min) since the last visit, rising squared to `enchantHazardFullChance` (8%) at `enchantHazardRampFullMs` (12 min), multiplied by the affordability pull (balance over the cheapest upgradable price seen on the last scan, up to `enchantHazardPullMaxMult` 3×) and by `enchantHazardCookBonus` (2×) mid-cook. With ~5s kills the mean spacing is about 6 minutes with a long tail. No rolls for `enchantPostZoneQuietMin/MaxMs` (30–90s) after a zone advance. Growth since the last visit (`enchantMinBalanceGrowthPct`, 10%) is a soft condition: `enchantCuriosityChance` (10%) of visits happen anyway, scan, and close. Between-kills visits buy at most `enchantMaxBuysBetweenKills` (2); mid-cook visits `enchantMaxBuysPerVisit` (6). `enchant_visit_start` logs `via`, `hazard`, `pull`, `curiosity`. Every step waits a humanized pause (`enchantLook…`, `enchantTabSettle…`, `enchantBuySettle…`). If the mob is about to die (`enchantWrapUpEtaMs`) the purchase in flight completes and the menu closes; `enchantMaxMenuMs` (40s) and `enchantMaxBuysPerVisit` (6) cap a visit. Events: `enchant_visit_start`, `enchant_menu_open`, `enchant_tab`, `enchant_scan` (the grid with levels/prices), `enchant_pick`, `enchant_upgrade`, `enchant_skip` (cadence / rolled-skip / no-growth / no-sword / none-affordable / max-unaffordable / tab-missing), `enchant_wrap_up`, `enchant_reopen`, `enchant_abort`, `enchant_menu_close`. `enchantsEnabled: false` turns it off; `enchantOpenViaInteract` swaps the synthetic use-key press for a direct interact call if a server build ignores the key.

### Stop protocol + TTK

- **Stop protocol** (`stopProtocolEnabled`): teleport first, radar second. A single-tick displacement over `teleportThresholdBlocks` (12) stops instantly and *arms* the player radar for the rest of the session (`playerRadarArmAfterTeleport`, default on). Our own `/zone max` advances are exempt — they neither stop the bot nor arm the radar, so zone NPCs and AFK fixtures can never trip it mid-grind. Once armed, a non-whitelisted player continuously within `playerRadarRadius` (48) for `playerRadarDwellMs` (5 s) stops the bot — a lingering stranger is staff. `playerRadarWhitelist` (matches any line of multi-line plates, §-codes and small caps normalized) and `playerRadarIgnoreStationaryMs` remain as second-line defenses; first sightings log as `radar_seen`. Fires a `stop_protocol` event and a red chat notice.
- **TTK** now measures connect → boss-bar-gone (server-authoritative death) instead of client-side entity removal, which ghosts were glitching. A bar that vanishes under `barVanishMinCookMs` with the entity still alive counts as a tag that didn't stick, not a kill.
- **Rarity HP scaling**: kill durations are normalized by `rarityHpScale` (default RARE +15%, EPIC +30%, LEGENDARY +40%) before entering the TTK window, so a tanky legendary doesn't read as slow farming. Mobs with no rarity tag get 1.0 (the nameplate parser can't see a tag that isn't there).

- `zoneMaxTtkMs` (10000) — hard zone gate: no `/zone max` while the effective TTK is above this or unknown. 0 disables.
- `evalFallbackMs` (30000) / `evalOnMoneyIncrease` (true) — upgrade evaluation triggers beyond kills: a timer backstop and any sidebar money increase.
- `cooldownRelaxBalanceMult` (3.0) — the 60s per-kind cap collapses to `commandCooldownMs` while balance ≥ this × the kind's last price. 0 disables.
- `cookStallMs` (15000) — `maxCookMs` only abandons a mob whose boss-bar HP has not dropped for this long.
- `resetTtkOnEnable` (true) — enabling the bot clears the TTK window (`ttk_reset`) so the zone gate re-measures wherever you are: after `/spawn`, a manual zone hop, or an AFK gap. Learned prices are kept.
- `zoneReadyTtkMs`, `zoneMinReadiness` — legacy (pre-0.9.7 readiness curve); inert.
- `scoreboardSnapshotMs` (5000) — how often the canonical multi-currency snapshot is published.
- `sidebarCurrencies` — names parsed from the sidebar (`money`, `souls`, `essence`, `shards`, `credits`).
- `ttkWindowKills` (8) — rolling window for the median TTK. A per-zone baseline is snapshotted a few kills after each zone change and logged via `zone_benchmark` events.
- `upgradeMinIntervalMs` (60s) — backstop ceiling between sends of the same kind. Unaffordable evals do not send.
- `buyNoticeDelayMinMs/MaxMs` (2–8s) — humanized delay between becoming affordable and typing.
- `successSilenceMs` (3000) — no fail line this long after a send = purchase succeeded.
- `retryPriceGrowthMinPct/MaxPct` (0.2–0.8) — unknown-price retry fires when the balance passes old price × (1 + a growth rolled per success). `retryPriceGrowthPct` is legacy.
- `upgradeResponseWindowMs` (4000) — replies are only attributable to our send within this window.
- `expectedTeleportAfterZoneMs` (8000) — stop-protocol exemption after our own `/zone max`.
- `upgradeSpendSettleMs` (2500) — ignore the sidebar this long after a buy (board lags after spending).
- `minKillsAfterAffordable` (0) — extra kills after the balance first covers the target before typing (at a 90s TTK, 1 held 2.75B against a 2.5B stage for a minute and a half).
- `summaryHeaderPattern` / `summaryMoneyPattern` — the `Reward Summary: (60s)` / ` + 17.19B Money` income-rate lines.
- `upgradeFailPatterns` / `upgradeNeedAmountPattern` — anchored fail lines and the gap-amount extractor.
- `upgradeSuccessPatterns` — anchored success lines; the sword pattern's `amount` group is the exact price paid.
- `suffixScales` — extra amount suffixes merged over the built-in `K…Dc` table, e.g. `{"UTG": 1e36}`. Unknown suffixes warn once in the log.
- `upgradeMaxedPatterns` — anchored response lines meaning a kind is fully upgraded (it is then never sent again).
- `upgradePeriodMinMs/MaxMs`, `zoneEverySwordsMin/Max` — legacy; inert.

### Ninja humanization

`ninja: true` (default) enables the realism layer; `false` restores the old mechanical behavior. All rates are tunable:

- Timing: soft bounds instead of hard clamps (`softClampMarginPct`), rare heavy-tail pauses (`tailChancePerDelay`), per-session bounds jitter (`sessionJitterPct`), fatigue drift (`fatiguePerHour`), and long distractions (`distractionChancePerMinute`, 2–30 s by default).
- Mouse: fast one-shot flicks with a big swoopy curve (~350 ms for a 30° snap at `aimAgility` 1.0 — the old default `0.4` is auto-migrated). Bump size is tunable via `curveBumpMinPct/MaxPct` (7–22% of flick distance by default), sparse idle tremor only, and small flick-tempo regimes (`agilityRegimes`) so no single Fitts regression fits. `mouseChaining` (mid-path re-targets) exists but is off by default — it reads as servo ticking.
- Mistakes: `misclickChance`, `wrongTargetChance`, `sprintHitChance`, `typoChancePerChar` (typo + backspace while typing commands).
- Session theater: `breaksEnabled` with `focusMinutesMin/Max` and `breakMinutesMin/Max`. Focus counts only time the bot is actually running (never wall clock while it is off), and `breaksResetOnToggle` (default on) makes any toggle, stop or captcha pause end the break and start a fresh focus block — the HUD shows `on break — 142s left (toggle to skip)`. Logged as `focus_start` / `break_start`.
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

Rebirths: sidebar counter going up = rebirth; counter dropping = reset = ascension (plus chat-message patterns as a second signal, same for prestige). Boosts: boss bar titles, tracked as start/end and stamped into every event's context. Balances: the sidebar rows (`balance` on change, `scoreboard_snapshot` every 5s); the reward summary feeds only the income rate. Zone: own teleport, boss-bar mob level, respawn broadcast or a sidebar Zone row. Log events: `scoreboard_snapshot`, `balance`, `income`, `income_summary`, `upgrade_plan` / `upgrade_skip` (with `via` = kill/money/timer, `ttkMs`, `zoneGate`, `swordFloor`/`zoneFloor`), `upgrade_send`, `upgrade_chat`, `upgrade_result` (`paid`, `extraLevel` for multi-level `/swordmax`), `upgrade_maxed`, `upgrade_response_raw`, `zone_benchmark`, `zone_teleport`, `zone_change`, `boss_level`, `ttk_reset`, `economy_reset`, `target_abandoned` (`sinceHpDropMs`), `focus_start`, `enchant_*` (see Enchants during long kills) (plus ninja noise: `misclick`, `target_mispick`, `sprint_hit_slip`, `distracted`, `break_start`, `aim_regime`).

## Building from source

```bash
gradle build          # Gradle 8.14+, JDK 21
# jar lands in build/libs/
```

Note: `fabric-loom` is pinned to `1.13-SNAPSHOT` because newer loom (1.14+) requires Gradle 9. If you're on Gradle 9+, you can bump loom freely.

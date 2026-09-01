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
- `movement: false` — stand still and only tag what wanders into reach.
- `zoneMin`/`zoneMax` — `[x, y, z]` arrays bounding the farming zone (null = anywhere).
- `runLabel` — tag for the log file (`"baseline"`, `"asc6-geared"`, `"2x-souls"`, ...). Change it between experiment runs.
- `rebirthsPattern`, `balancePatterns`, `ascensionChatPatterns`, etc. — the sidebar/chat regexes, in case your display format changes.

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

Rebirths: sidebar counter going up = rebirth; counter dropping = reset = ascension (plus chat-message patterns as a second signal, same for prestige). Boosts: boss bar titles, tracked as start/end and stamped into every event's context. Balances: raw sidebar strings (`37.16UTG` etc.) logged on change — the Node tooling owns suffix parsing.

## Building from source

```bash
gradle build          # Gradle 8.14+, JDK 21
# jar lands in build/libs/
```

Note: `fabric-loom` is pinned to `1.13-SNAPSHOT` because newer loom (1.14+) requires Gradle 9. If you're on Gradle 9+, you can bump loom freely.

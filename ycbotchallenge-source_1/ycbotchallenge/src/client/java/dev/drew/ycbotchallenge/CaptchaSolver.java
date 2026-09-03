package dev.drew.ycbotchallenge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.block.MapColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.map.MapState;
import net.minecraft.text.Text;

/**
 * Auto-solves the map captcha with a local Qwen3-VL served by vLLM.
 *
 * Flow: detect (held/hotbar map, chat line or server GUI) -> settle -> capture
 * (the map's own 128x128 pixels from any hand/hotbar slot or a nearby item
 * frame; else a HUD-less, downscaled screenshot) -> POST to the local
 * OpenAI-compatible endpoint -> parse the answer -> type it in chat like a
 * person -> watch for a success/retry message -> resume the grind (a second,
 * case-flipped guess on rejection; then hand over to the human).
 *
 * All Minecraft state is touched on the client tick thread; only the HTTP
 * round-trips and PNG encoding happen off-thread.
 */
public class CaptchaSolver {
    private enum Phase { IDLE, SETTLING, HUD_HIDE, CAPTURING, SOLVING, TYPING, TYPING_RUN, AWAITING_RESULT }

    /** What the client should do when solving ends. */
    public interface Callbacks {
        void onSolved(MinecraftClient client);
        void onFailed(MinecraftClient client, String reason, String detail);
    }

    /** Sonar path: "ANSWER: xxx" and "ALT: yyy" lines. */
    private static final Pattern ANSWER_RE =
        Pattern.compile("(?:ANSWER|ALT)\\s*\\d*\\s*:\\s*([^\\s]+)", Pattern.CASE_INSENSITIVE);
    private static final Gson GSON = new Gson();

    private record MapHit(MapState state, boolean stackFound, String where, int mapId) {}
    private record Health(boolean online, List<String> models, long latencyMs, String error) {}

    private final YCBotChallengeConfig cfg;
    private final Callbacks callbacks;
    private final HttpClient http;
    private final List<Pattern> solvedRes = new ArrayList<>();
    private final List<Pattern> retryRes = new ArrayList<>();
    private final ChatTyper typer;
    private final Path debugDir;
    private EventLogger logger;

    private Phase phase = Phase.IDLE;
    private long phaseDeadline = 0;
    private long settleStart = 0;
    private int attempt = 0;
    /** Answers actually sent to chat for the current captcha: hard-capped, never spam. */
    private int answersSent = 0;
    private String source = null;
    /** "map" (rendered from map data) or "screen" (framebuffer). */
    private String captureMode = null;
    private String captureWhere = null;
    /** Whether the last model call used the map prompt (JSON letters, case kept). */
    private boolean mapPromptUsed = false;
    /** HUD visibility to restore after a screenshot (null = nothing to restore). */
    private Boolean hudRestore = null;

    // written by background threads / message handler, consumed on the tick thread
    private final AtomicReference<byte[]> capturedPng = new AtomicReference<>();
    private final AtomicReference<String> captureError = new AtomicReference<>();
    private final AtomicReference<List<String>> vlmCandidates = new AtomicReference<>();
    private final AtomicReference<String> vlmRaw = new AtomicReference<>();
    private final AtomicReference<String> vlmError = new AtomicReference<>();
    private final AtomicBoolean vlmConnectFailed = new AtomicBoolean(false);
    private final AtomicReference<Health> healthResult = new AtomicReference<>();
    /** Ranked guesses from the last model call; rejected ones are consumed in order. */
    private final List<String> candidates = new ArrayList<>();
    /** The captcha image we're working on, kept so a rejection can re-prompt without re-capturing. */
    private byte[] lastPng = null;
    /** Second render of the same map (captchaSecondScale) read as a cross-check; null when off. */
    private byte[] secondPng = null;
    // 0.9.26 running ballot: the map rendered at several scales once, one background
    // worker reading them in turn, every reading a vote (see CaptchaBallot).
    private record NamedPng(String name, byte[] png) {}
    private final CaptchaBallot ballot = new CaptchaBallot();
    private final List<NamedPng> renders = new ArrayList<>();
    private volatile int voteGeneration = 0;
    private volatile boolean votingDone = false;
    private final AtomicReference<String> voteError = new AtomicReference<>();
    private boolean ballotActive = false;
    private long answerSentAt = 0;
    private final AtomicReference<String> vlmSecond = new AtomicReference<>();
    /** The map we last answered from, its readings and how many answers went out for it: a server
     *  re-prompt for the same map continues with the next guess instead of re-reading. */
    private int solvedMapId = -1;
    private final List<String> mapCandidates = new ArrayList<>();
    private int mapAnswersSent = 0;
    private int captureMapId = -1;
    private volatile String feedback = null; // "solved" | "retry"
    private String lastSentAnswer = null;
    /** Guess waiting out the human-ish reading pause before being typed. */
    private String pendingAnswer = null;
    private String pendingOut = null;
    /** The server re-uses the same image across tries: remember rejects and feed them back. */
    private final List<String> wrongAnswers = new ArrayList<>();

    // VLM health
    private volatile boolean vlmOnline = true;
    private boolean healthInFlight = false;
    private long lastHealthAt = 0;

    public CaptchaSolver(YCBotChallengeConfig cfg, Callbacks callbacks, Path debugDir) {
        this.cfg = cfg;
        this.callbacks = callbacks;
        this.debugDir = debugDir;
        this.typer = new ChatTyper(cfg);
        this.http = HttpClient.newBuilder()
            // uvicorn (vLLM's server) rejects Java's default h2c upgrade attempt
            // with 400 "Unsupported upgrade request" — force plain HTTP/1.1
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        for (String p : cfg.captchaSolvedPatterns) solvedRes.add(compileLoose(p));
        for (String p : cfg.captchaRetryPatterns) retryRes.add(compileLoose(p));
    }

    private static Pattern compileLoose(String p) {
        if (p.startsWith("/") && p.endsWith("/") && p.length() > 2) {
            return Pattern.compile(p.substring(1, p.length() - 1), Pattern.CASE_INSENSITIVE);
        }
        return Pattern.compile(Pattern.quote(p), Pattern.CASE_INSENSITIVE);
    }

    public void setLogger(EventLogger logger) { this.logger = logger; }

    public boolean isActive() { return phase != Phase.IDLE; }

    public boolean vlmOnline() { return vlmOnline; }

    public String hudLine() {
        if (phase == Phase.IDLE) return null;
        String what = switch (phase) {
            case SETTLING -> "waiting for captcha to render";
            case HUD_HIDE, CAPTURING -> "capturing";
            case SOLVING -> ballotActive ? "asking Qwen (" + ballot.reads() + " reads)" : "asking Qwen";
            case TYPING -> "reading it...";
            case TYPING_RUN -> "typing answer...";
            case AWAITING_RESULT -> lastSentAnswer != null
                ? "tried '" + lastSentAnswer + "' (" + answersSent + "/" + cfg.captchaMaxAnswers + "), verifying"
                : "answer sent, verifying";
            default -> "";
        };
        return "§bcaptcha: " + what + "§r";
    }

    /** Red HUD line while the model server is unreachable (null when fine). */
    public String vlmHudLine() {
        return vlmOnline ? null : "§ccaptcha VLM: offline — start vLLM§r";
    }

    private void log(String type, Object... kv) {
        if (logger != null) logger.log(type, kv);
    }

    /** Kick off a solve. Call only when isActive() is false. */
    public void begin(MinecraftClient client, String detectSource, String detail) {
        log("captcha_detected", "source", detectSource, "detail", detail, "autoSolve", true, "vlmOnline", vlmOnline);
        int heldId = heldMapId(client);
        if (heldId >= 0 && heldId == solvedMapId && !mapCandidates.isEmpty()) {
            // The map we already answered from is still the captcha (a "Please enter the
            // captcha on the map." re-prompt, or any re-detection of it): the picture has
            // not changed, so re-reading it would type the same guess again (17:38 log).
            // Continue with the next reading instead — whatever the trigger was (0.9.26).
            source = detectSource;
            attempt = 1;
            answersSent = mapAnswersSent;
            feedback = null;
            pendingAnswer = null;
            pendingOut = null;
            captureMode = "map";
            captureWhere = "reprompt";
            mapPromptUsed = true;
            candidates.clear();
            candidates.addAll(mapCandidates);
            // The re-prompt is the server's "no": the guess that went out is spent.
            if (lastSentAnswer != null && !wrongAnswers.contains(lastSentAnswer)) wrongAnswers.add(lastSentAnswer);
            log("captcha_reprompted", "mapId", heldId, "answersSent", answersSent, "candidates", candidates, "wrong", wrongAnswers);
            if (answersSent >= cfg.captchaMaxAnswers) {
                phase = Phase.SETTLING;
                fail(client, "answers-exhausted", "server re-prompted after " + answersSent + " answer(s) for map " + heldId);
                return;
            }
            phase = Phase.SOLVING;
            submitNextCandidate(client, System.currentTimeMillis());
            return;
        }
        source = detectSource;
        attempt = 1;
        answersSent = 0;
        feedback = null;
        lastSentAnswer = null;
        captureMode = null;
        captureWhere = null;
        mapPromptUsed = false;
        wrongAnswers.clear();
        candidates.clear();
        lastPng = null;
        pendingAnswer = null;
        pendingOut = null;
        capturedPng.set(null);
        captureError.set(null);
        vlmCandidates.set(null);
        vlmRaw.set(null);
        vlmError.set(null);
        vlmConnectFailed.set(false);
        secondPng = null;
        vlmSecond.set(null);
        captureMapId = -1;
        stopVoting();
        ballot.clear();
        renders.clear();
        ballotActive = false;
        answerSentAt = 0;
        voteError.set(null);
        if (heldId != solvedMapId) { solvedMapId = -1; mapCandidates.clear(); mapAnswersSent = 0; }
        if (!vlmOnline) {
            // No 3x20s of retries against a dead port: hand over right away.
            phase = Phase.SETTLING;
            fail(client, "vlm-offline", "model server unreachable at " + cfg.captchaVlmHealthUrl);
            return;
        }
        phase = Phase.SETTLING;
        settleStart = System.currentTimeMillis();
        phaseDeadline = settleStart + cfg.captchaSettleMs;
        say(client, "§e[YCBotChallenge] captcha detected — solving with local Qwen...");
    }

    public void cancel() {
        MinecraftClient client = MinecraftClient.getInstance();
        typer.cancel(client);
        restoreHud(client);
        stopVoting();
        phase = Phase.IDLE;
    }

    /** Invalidate the background reader: a worker whose generation is stale casts no more votes. */
    private void stopVoting() {
        voteGeneration++;
    }

    /** Wire to ClientReceiveMessageEvents.GAME (any thread). */
    public void onGameMessage(String text) {
        if (phase != Phase.AWAITING_RESULT || text == null || text.isBlank()) return;
        for (Pattern p : retryRes) {
            if (p.matcher(text).find()) { feedback = "retry"; return; }
        }
        for (Pattern p : solvedRes) {
            if (p.matcher(text).find()) { feedback = "solved"; return; }
        }
    }

    // ---------------------------------------------------------------- health

    /** Call every tick while the bot is enabled and no solve is running. */
    public void tickIdle(long now) {
        pollHealth();
        if (now - lastHealthAt >= Math.max(10_000, cfg.captchaVlmHealthIntervalMs)) checkHealth(now);
    }

    /** Async GET of the models list; result lands in {@link #pollHealth()}. */
    public void checkHealth(long now) {
        if (healthInFlight) return;
        lastHealthAt = now;
        if (cfg.captchaVlmHealthUrl == null || cfg.captchaVlmHealthUrl.isBlank()) return;
        healthInFlight = true;
        long t0 = System.currentTimeMillis();
        HttpRequest req;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(cfg.captchaVlmHealthUrl))
                .timeout(Duration.ofMillis(Math.max(500, cfg.captchaVlmHealthTimeoutMs)))
                .GET();
            String key = apiKey();
            if (key != null) b.header("Authorization", "Bearer " + key);
            req = b.build();
        } catch (Exception e) {
            healthResult.set(new Health(false, List.of(), 0, "bad url: " + e.getMessage()));
            return;
        }
        http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
            long dt = System.currentTimeMillis() - t0;
            if (err != null) {
                healthResult.set(new Health(false, List.of(), dt, rootMessage(err)));
                return;
            }
            if (resp.statusCode() != 200) {
                healthResult.set(new Health(false, List.of(), dt, "HTTP " + resp.statusCode()));
                return;
            }
            List<String> models = new ArrayList<>();
            try {
                JsonElement data = JsonParser.parseString(resp.body()).getAsJsonObject().get("data");
                if (data != null && data.isJsonArray()) {
                    for (JsonElement e : data.getAsJsonArray()) {
                        JsonElement id = e.getAsJsonObject().get("id");
                        if (id != null) models.add(id.getAsString());
                    }
                }
            } catch (Exception ignored) { }
            healthResult.set(new Health(true, models, dt, null));
        });
    }

    private void pollHealth() {
        Health h = healthResult.getAndSet(null);
        if (h == null) return;
        healthInFlight = false;
        boolean was = vlmOnline;
        vlmOnline = h.online;
        log("vlm_health", "online", h.online, "models", h.models, "latencyMs", h.latencyMs,
            "error", h.error, "url", cfg.captchaVlmHealthUrl, "changed", was != h.online);
        if (h.online && cfg.captchaVlmModelAuto && h.models.size() == 1
            && !h.models.get(0).equals(cfg.captchaVlmModel)) {
            log("vlm_model_auto", "from", cfg.captchaVlmModel, "to", h.models.get(0));
            cfg.captchaVlmModel = h.models.get(0);
        }
    }

    private String apiKey() {
        String env = System.getenv("YCBOT_VLM_KEY");
        if (env != null && !env.isBlank()) return env.trim();
        return null;
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        return c.getClass().getSimpleName() + (c.getMessage() != null ? ": " + c.getMessage() : "");
    }

    private static boolean isConnectFailure(Throwable t) {
        Throwable c = t;
        while (c != null) {
            if (c instanceof java.net.ConnectException || c instanceof java.net.http.HttpConnectTimeoutException) return true;
            c = c.getCause() == c ? null : c.getCause();
        }
        return false;
    }

    // ------------------------------------------------------------------ tick

    /** Call every client tick while active. Keeps the player inert; drives the state machine. */
    public void tick(MinecraftClient client) {
        if (client.player == null || client.world == null) { cancel(); return; }
        long now = System.currentTimeMillis();
        pollHealth();
        switch (phase) {
            case SETTLING -> {
                if (now >= phaseDeadline) startCapture(client, now);
            }
            case HUD_HIDE -> {
                if (now >= phaseDeadline) takeScreenshot(client, now);
            }
            case CAPTURING -> {
                String err = captureError.getAndSet(null);
                if (err != null) { restoreHud(client); fail(client, "capture", err); return; }
                byte[] png = capturedPng.getAndSet(null);
                if (png != null) {
                    restoreHud(client);
                    lastPng = png;
                    String dumped = dumpPng(png);
                    log("captcha_captured", "mode", captureMode, "where", captureWhere, "bytes", png.length,
                        "px", CaptchaImages.pngWidth(png), "png", dumped, "attempt", attempt,
                        "renders", renders.size());
                    if ("map".equals(captureMode) && !renders.isEmpty() && attempt <= 1 && useMapPrompt()) startVoting();
                    else startSolve(png);
                } else if (now >= phaseDeadline) {
                    restoreHud(client);
                    fail(client, "capture", "screenshot never arrived");
                }
            }
            case SOLVING -> {
                if (vlmConnectFailed.getAndSet(false)) {
                    vlmOnline = false;
                    String err = vlmError.getAndSet(null);
                    log("vlm_health", "online", false, "error", err, "via", "solve", "changed", true);
                    fail(client, "vlm-offline", err != null ? err : "connection refused");
                    return;
                }
                if (ballotActive) {
                    String ve = voteError.getAndSet(null);
                    if (ve != null) log("captcha_vote_error", "error", ve, "reads", ballot.reads());
                    if (ballot.reads() > 0) {
                        refreshCandidates();
                        log("captcha_candidates", "candidates", candidates, "reads", ballot.reads(),
                            "tallies", ballot.tallies(), "prompt", "map", "preserveCase", cfg.captchaPreserveCase,
                            "attempt", attempt, "via", "ballot");
                        if (captureMapId >= 0) {
                            solvedMapId = captureMapId;
                            mapCandidates.clear();
                            mapCandidates.addAll(candidates);
                            mapAnswersSent = answersSent;
                        }
                        submitNextCandidate(client, now);
                    } else if (votingDone) {
                        String err = vlmError.getAndSet(null);
                        retryOrFail(client, "vlm", err != null ? err : "no reading from any render");
                    } else if (now >= phaseDeadline) {
                        retryOrFail(client, "vlm", "timed out waiting for the model");
                    }
                    return;
                }
                String err = vlmError.getAndSet(null);
                if (err != null) { retryOrFail(client, "vlm", err); return; }
                List<String> got = vlmCandidates.getAndSet(null);
                if (got != null) {
                    candidates.clear();
                    candidates.addAll(got); // ranked, de-duped, best first
                    log("captcha_candidates", "candidates", candidates, "raw", vlmRaw.getAndSet(null),
                        "second", vlmSecond.getAndSet(null), "secondScale", secondPng != null ? cfg.captchaSecondScale : null,
                        "prompt", mapPromptUsed ? "map" : "sonar",
                        "preserveCase", mapPromptUsed && cfg.captchaPreserveCase, "attempt", attempt);
                    if ("map".equals(captureMode) && captureMapId >= 0) {
                        solvedMapId = captureMapId;
                        mapCandidates.clear();
                        mapCandidates.addAll(candidates);
                        mapAnswersSent = answersSent;
                    }
                    submitNextCandidate(client, now);
                } else if (now >= phaseDeadline) {
                    retryOrFail(client, "vlm", "timed out waiting for the model");
                }
            }
            case TYPING -> {
                if (now >= phaseDeadline && pendingAnswer != null) {
                    // The reading pause has passed; with the ballot running, hold a little
                    // longer until a few votes are in (a person re-reads the map anyway).
                    if (ballotActive && !votingDone && ballot.reads() < Math.max(1, cfg.captchaVoteMinReads)
                        && now < phaseDeadline + Math.max(0, cfg.captchaVoteMaxWaitMs)) {
                        return;
                    }
                    String answer = pendingAnswer;
                    if (ballotActive) {
                        refreshCandidates();
                        if (!candidates.isEmpty()) answer = candidates.get(0);
                        log("captcha_vote", "at", "send", "reads", ballot.reads(), "tallies", ballot.tallies(),
                            "leader", answer, "render", ballot.renderOf(answer), "wrong", wrongAnswers,
                            "votingDone", votingDone);
                    }
                    pendingAnswer = null;
                    String out = cfg.captchaAnswerTemplate.replace("{answer}", answer);
                    pendingOut = out;
                    if (typedSend(client)) {
                        typer.begin(client, out, now);
                        phase = Phase.TYPING_RUN;
                    } else {
                        sendDirect(client, answer, out, "gui-open");
                    }
                }
            }
            case TYPING_RUN -> {
                ChatTyper.State s = typer.tick(client, now);
                if (s == ChatTyper.State.DONE) {
                    afterSend(client, now, lastCandidate(), pendingOut, true, typer.typos(), null);
                } else if (s == ChatTyper.State.FAILED) {
                    log("captcha_type_failed", "reason", typer.failReason(), "attempt", attempt);
                    sendDirect(client, lastCandidate(), pendingOut, typer.failReason());
                }
            }
            case AWAITING_RESULT -> {
                // The server says nothing either way (19:43 log); on a right answer the map
                // leaves the hand (Drew), so a map still held this long after the answer is
                // the rejection (0.9.26).
                if (feedback == null && "map".equals(captureMode) && captureMapId >= 0 && answerSentAt > 0
                    && cfg.captchaMapHeldRejectMs > 0 && now - answerSentAt >= cfg.captchaMapHeldRejectMs
                    && heldMapId(client) == captureMapId) {
                    log("captcha_map_persists", "mapId", captureMapId, "afterMs", now - answerSentAt,
                        "answer", lastSentAnswer, "answersSent", answersSent);
                    feedback = "retry";
                }
                String fb = feedback;
                if ("retry".equals(fb)) {
                    if (lastSentAnswer != null && !wrongAnswers.contains(lastSentAnswer)) {
                        wrongAnswers.add(lastSentAnswer);
                    }
                    if (ballotActive) {
                        // The rest of the ballot decides the second guess — by now it has
                        // had the whole verify window to keep reading.
                        refreshCandidates();
                        log("captcha_vote", "at", "rejection", "reads", ballot.reads(), "tallies", ballot.tallies(),
                            "leader", candidates.isEmpty() ? null : candidates.get(0), "wrong", wrongAnswers,
                            "votingDone", votingDone);
                        mapCandidates.clear();
                        mapCandidates.addAll(candidates);
                    }
                    candidates.remove(lastSentAnswer);
                    boolean altLeft = candidates.stream().anyMatch(c -> !wrongAnswers.contains(c));
                    if (answersSent >= cfg.captchaMaxAnswers) {
                        // Hard cap: STOP. Never spam answers; hand over to the human.
                        fail(client, "answers-exhausted", answersSent + " guess(es) rejected — stopping, no spam");
                    } else if (mapPromptUsed && altLeft) {
                        // The map read is unchanged; the case-flipped second guess goes next.
                        submitNextCandidate(client, now);
                    } else if (lastPng != null) {
                        // Re-prompt the model on the same image with the rejection as
                        // feedback. Prefill is cached, so this is fast.
                        attempt++;
                        log("captcha_reprompt", "rejected", wrongAnswers, "attempt", attempt);
                        startSolve(lastPng);
                    } else if (altLeft) {
                        submitNextCandidate(client, now);
                    } else {
                        fail(client, "server", "rejected with nothing left to try");
                    }
                } else if ("solved".equals(fb) || now >= phaseDeadline) {
                    stopVoting();
                    phase = Phase.IDLE;
                    log("captcha_solved", "attempt", attempt, "answer", lastSentAnswer,
                        "confirmed", "solved".equals(fb), "mode", captureMode, "source", source,
                        "answersSent", answersSent);
                    say(client, "§a[YCBotChallenge] captcha solved — resuming.");
                    callbacks.onSolved(client);
                }
            }
            default -> { }
        }
    }

    private String lastCandidate() {
        return pendingOut == null ? null : candidateFor(pendingOut);
    }

    private String candidateFor(String out) {
        for (String c : candidates) {
            if (cfg.captchaAnswerTemplate.replace("{answer}", c).equals(out)) return c;
        }
        return out;
    }

    /** Send the top remaining candidate, respecting the hard answer cap. */
    private void submitNextCandidate(MinecraftClient client, long now) {
        if (answersSent >= cfg.captchaMaxAnswers) {
            fail(client, "answers-exhausted", "answer cap (" + cfg.captchaMaxAnswers + ") reached — stopping, no spam");
            return;
        }
        String next = null;
        for (String c : candidates) {
            if (!wrongAnswers.contains(c)) { next = c; break; }
        }
        if (next == null) {
            // Model gave nothing new. If we haven't sent anything yet, a fresh
            // solve cycle is safe (nothing hit chat); otherwise stop.
            if (answersSent == 0) retryOrFail(client, "vlm", "no usable candidate");
            else fail(client, "server", "no candidates left after rejection — stopping, no spam");
            return;
        }
        // A person needs a moment to read the map before typing.
        pendingAnswer = next;
        phase = Phase.TYPING;
        int min = Math.max(0, cfg.captchaAnswerDelayMinMs);
        int max = Math.max(min + 1, cfg.captchaAnswerDelayMaxMs);
        phaseDeadline = now + HumanTiming.logNormalMs(min, max);
    }

    private void retryOrFail(MinecraftClient client, String stage, String why) {
        if ("server".equals(stage) && lastSentAnswer != null && !wrongAnswers.contains(lastSentAnswer)) {
            wrongAnswers.add(lastSentAnswer);
        }
        if (attempt >= cfg.captchaMaxAttempts) { fail(client, stage, why); return; }
        attempt++;
        log("captcha_retry", "stage", stage, "why", why, "attempt", attempt);
        candidates.clear();
        capturedPng.set(null);
        captureError.set(null);
        vlmCandidates.set(null);
        vlmError.set(null);
        phase = Phase.SETTLING;
        settleStart = System.currentTimeMillis();
        phaseDeadline = settleStart + cfg.captchaSettleMs;
    }

    private void fail(MinecraftClient client, String stage, String why) {
        typer.cancel(client);
        restoreHud(client);
        stopVoting();
        phase = Phase.IDLE;
        log("captcha_failed", "stage", stage, "why", why, "attempts", attempt, "answersSent", answersSent,
            "source", source, "mode", captureMode);
        callbacks.onFailed(client, stage, why);
    }

    // ---------------------------------------------------------------- capture

    private void startCapture(MinecraftClient client, long now) {
        String mode = cfg.captchaCaptureMode;
        if (!"screen".equals(mode)) {
            MapHit hit = findCaptchaMap(client);
            if (hit.state() != null) {
                try {
                    // The ballot's renders (0.9.26): the same map at every scale of the
                    // schedule, rendered once; the first is the one dumped and re-prompted.
                    renders.clear();
                    byte[] mapPng = null;
                    if (cfg.captchaVoteRenders != null) {
                        for (String spec : cfg.captchaVoteRenders) {
                            int[] rs = parseRenderSpec(spec);
                            if (rs == null) continue;
                            byte[] p = renderMapPng(hit.state(), rs[0], rs[1] == 1);
                            if (mapPng == null) mapPng = p;
                            renders.add(new NamedPng(spec.trim().toLowerCase(java.util.Locale.ROOT), p));
                        }
                    }
                    if (mapPng == null) mapPng = renderMapPng(hit.state(), cfg.captchaMapScale, cfg.captchaMapSmooth);
                    // Legacy cross-check render, used only by the single-read path (screen captures, re-prompts).
                    secondPng = renders.isEmpty() && cfg.captchaSecondScale > 0 && cfg.captchaSecondScale != cfg.captchaMapScale
                        ? renderMapPng(hit.state(), cfg.captchaSecondScale, cfg.captchaMapSmooth) : null;
                    captureMode = "map";
                    captureWhere = hit.where();
                    captureMapId = hit.mapId();
                    capturedPng.set(mapPng);
                    phase = Phase.CAPTURING;
                    phaseDeadline = now + 5000;
                    return;
                } catch (Exception e) {
                    YCBotChallengeClient.LOGGER.warn("Map render failed, falling back to screenshot: {}", e.toString());
                }
            } else if (hit.stackFound()) {
                // The item is here but its pixels travel in a later packet.
                if (now < settleStart + cfg.captchaSettleMs + cfg.captchaMapDataWaitMs) return;
                log("captcha_map_data_timeout", "where", hit.where(), "waitedMs", now - settleStart);
            }
        }
        if ("map".equals(mode)) {
            // Map-only (default since 0.9.16): a chat/GUI trigger with no map is not a
            // captcha we can read. Hand over at once rather than screenshot the arena
            // and type a guess into public chat (the "qwe" incident, 2026-09-03).
            fail(client, "capture", "no-map: no filled map in hands, hotbar or nearby item frames");
            return;
        }
        captureMode = "screen";
        captureWhere = "framebuffer";
        if (cfg.captchaScreenHideHud && !client.options.hudHidden) {
            hudRestore = Boolean.FALSE;
            client.options.hudHidden = true;
            phase = Phase.HUD_HIDE;
            phaseDeadline = now + 120; // let a HUD-less frame render first
            return;
        }
        takeScreenshot(client, now);
    }

    private void takeScreenshot(MinecraftClient client, long now) {
        phase = Phase.CAPTURING;
        phaseDeadline = now + 5000;
        int maxPx = cfg.captchaScreenMaxPx;
        ScreenshotRecorder.takeScreenshot(client.getFramebuffer(), image -> {
            try (image) {
                Path tmp = Files.createTempFile("ycbot-captcha", ".png");
                image.writeTo(tmp);
                byte[] bytes = Files.readAllBytes(tmp);
                Files.deleteIfExists(tmp);
                capturedPng.set(CaptchaImages.downscalePng(bytes, maxPx));
            } catch (Exception e) {
                captureError.set("screenshot: " + e);
            }
        });
    }

    private void restoreHud(MinecraftClient client) {
        if (hudRestore != null && client != null && client.options != null) {
            client.options.hudHidden = hudRestore;
        }
        hudRestore = null;
    }

    /** Main hand, off hand, any hotbar slot, then the nearest item-frame map. */
    private MapHit findCaptchaMap(MinecraftClient client) {
        ItemStack main = client.player.getMainHandStack();
        if (CaptchaDetector.mapId(main) >= 0) return new MapHit(mapFromStack(client, main), true, "main", CaptchaDetector.mapId(main));
        ItemStack off = client.player.getOffHandStack();
        if (CaptchaDetector.mapId(off) >= 0) return new MapHit(mapFromStack(client, off), true, "off", CaptchaDetector.mapId(off));
        if (cfg.captchaMapAnySlot) {
            for (int i = 0; i < 9; i++) {
                ItemStack s = client.player.getInventory().getStack(i);
                if (CaptchaDetector.mapId(s) >= 0) return new MapHit(mapFromStack(client, s), true, "hotbar" + (i + 1), CaptchaDetector.mapId(s));
            }
        }
        ItemFrameEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (Entity e : client.world.getEntities()) {
            if (!(e instanceof ItemFrameEntity frame) || !frame.containsMap()) continue;
            double d = client.player.distanceTo(frame);
            if (d <= cfg.captchaMapSearchRadius && d < best) { best = d; nearest = frame; }
        }
        if (nearest != null) {
            ItemStack fs = nearest.getHeldItemStack();
            return new MapHit(mapFromStack(client, fs), true, "frame", CaptchaDetector.mapId(fs));
        }
        return new MapHit(null, false, null, -1);
    }

    /** Map id in either hand or (captchaMapAnySlot) the hotbar, -1 when none. */
    private int heldMapId(MinecraftClient client) {
        if (client.player == null) return -1;
        int id = CaptchaDetector.mapId(client.player.getMainHandStack());
        if (id < 0) id = CaptchaDetector.mapId(client.player.getOffHandStack());
        if (id < 0 && cfg.captchaMapAnySlot) {
            for (int i = 0; i < 9 && id < 0; i++) id = CaptchaDetector.mapId(client.player.getInventory().getStack(i));
        }
        return id;
    }

    private MapState mapFromStack(MinecraftClient client, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        MapIdComponent id = stack.get(DataComponentTypes.MAP_ID);
        if (id == null) return null;
        return FilledMapItem.getMapState(id, client.world);
    }

    /**
     * 128x128 map colors -> PNG at {@code scale}x. Nearest neighbour up to x2;
     * beyond that bilinear when {@code smooth} (bench 2026-09-03: nearest x4 made
     * the model read "pnGe" as "prGe", x2 and smoothed x4 read it right).
     */
    static byte[] renderMapPng(MapState state, int scale, boolean smooth) throws Exception {
        BufferedImage base = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
        for (int z = 0; z < 128; z++) {
            for (int x = 0; x < 128; x++) {
                int packed = state.colors[x + z * 128] & 0xFF;
                int abgr = MapColor.getRenderColor(packed); // vanilla packs this ABGR
                int rgb = ((abgr & 0xFF) << 16) | (abgr & 0xFF00) | ((abgr >> 16) & 0xFF);
                base.setRGB(x, z, rgb);
            }
        }
        return CaptchaImages.encodePng(CaptchaImages.scale(base, 128 * Math.max(1, scale), smooth && scale > 2));
    }

    /** "x4bil" -> {4, 1}, "x2near" -> {2, 0}; null for anything else. */
    static int[] parseRenderSpec(String spec) {
        if (spec == null) return null;
        Matcher m = RENDER_SPEC.matcher(spec.trim().toLowerCase(java.util.Locale.ROOT));
        if (!m.matches()) return null;
        int scale = Integer.parseInt(m.group(1));
        if (scale < 1 || scale > 8) return null;
        return new int[]{scale, "bil".equals(m.group(2)) ? 1 : 0};
    }

    private static final Pattern RENDER_SPEC = Pattern.compile("x(\\d{1,2})(bil|near)");

    /**
     * One background reader for the whole captcha: the schedule at temperature 0, then
     * again at captchaVoteTemperature, one request at a time, each parsed reading a vote,
     * until the ballot is stopped (solved, failed, cancelled) or captchaVoteMaxReads.
     * Errors are logged on the tick thread; a connection failure before any vote is the
     * usual vlm-offline hand-over.
     */
    private void startVoting() {
        phase = Phase.SOLVING;
        phaseDeadline = System.currentTimeMillis() + cfg.captchaTimeoutMs + 2000;
        mapPromptUsed = true;
        ballotActive = true;
        votingDone = false;
        final int gen = ++voteGeneration;
        final String promptText = cfg.captchaMapPrompt;
        final List<NamedPng> snapshot = new ArrayList<>(renders);
        final int maxReads = Math.max(1, cfg.captchaVoteMaxReads);
        final double heat = cfg.captchaVoteTemperature;
        Thread t = new Thread(() -> {
            int reads = 0;
            double[] temps = heat > 0 ? new double[]{0.0, heat} : new double[]{0.0};
            outer:
            for (double temp : temps) {
                for (NamedPng r : snapshot) {
                    if (gen != voteGeneration || reads >= maxReads) break outer;
                    try {
                        HttpResponse<String> resp = http.send(buildRequest(r.png(), promptText, temp, 64),
                            HttpResponse.BodyHandlers.ofString());
                        if (resp.statusCode() != 200) {
                            voteError.set("HTTP " + resp.statusCode() + " on " + r.name());
                            continue;
                        }
                        String content = contentOf(resp.body());
                        String reading = ChatClassifier.parseAnswerArray(content, cfg.captchaPreserveCase);
                        if (reading == null) {
                            voteError.set("no answer on " + r.name() + ": " + truncate(content, 120));
                            continue;
                        }
                        if (gen != voteGeneration) break outer;
                        ballot.cast(reading, r.name(), temp);
                        reads++;
                    } catch (Exception e) {
                        if (isConnectFailure(e) && ballot.reads() == 0) {
                            vlmError.set("request failed: " + rootMessage(e));
                            vlmConnectFailed.set(true);
                            break outer;
                        }
                        voteError.set(r.name() + ": " + rootMessage(e));
                    }
                }
            }
            if (gen == voteGeneration) votingDone = true;
        }, "ycbot-captcha-vote");
        t.setDaemon(true);
        t.start();
    }

    /** Candidates = the ballot minus rejected readings; an all-agree ballot still gets the look-alike second guess. */
    private void refreshCandidates() {
        candidates.clear();
        candidates.addAll(ballot.ranked(wrongAnswers));
        if (ballot.distinct() == 1) {
            String only = ballot.leader(List.of());
            String alt = ChatClassifier.lookalikeAlt(only, cfg.captchaLookalikes, cfg.captchaCaseAmbiguous);
            if (alt != null && !alt.equals(only) && !wrongAnswers.contains(alt) && !candidates.contains(alt)) {
                candidates.add(alt);
            }
        }
    }

    private String dumpPng(byte[] png) {
        if (!cfg.captchaDebugPng || debugDir == null) return null;
        try {
            Files.createDirectories(debugDir);
            String stamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(":", "-").replace(".", "-");
            Path f = debugDir.resolve("captcha-" + stamp + "-" + captureMode + ".png");
            Files.write(f, png);
            return f.getFileName().toString();
        } catch (Exception e) {
            YCBotChallengeClient.LOGGER.warn("captcha png dump failed: {}", e.toString());
            return null;
        }
    }

    // ------------------------------------------------------------------ solve

    private boolean useMapPrompt() {
        // Map data, or a screenshot of a held map: the JSON letter prompt. Only a
        // chat/GUI-triggered captcha with no map at all keeps the Sonar prompt.
        return "map".equals(captureMode) || !("chat".equals(source) || "gui".equals(source));
    }

    private HttpRequest buildRequest(byte[] png, String prompt, double temperature, int maxTokens) {
        JsonObject imagePart = new JsonObject();
        imagePart.addProperty("type", "image_url");
        JsonObject imageUrl = new JsonObject();
        imageUrl.addProperty("url", "data:image/png;base64," + Base64.getEncoder().encodeToString(png));
        imagePart.add("image_url", imageUrl);
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", prompt);
        JsonArray content = new JsonArray();
        content.add(imagePart);
        content.add(textPart);
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        msg.add("content", content);
        JsonArray messages = new JsonArray();
        messages.add(msg);
        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.captchaVlmModel);
        body.addProperty("temperature", temperature);
        body.addProperty("max_tokens", maxTokens);
        body.add("messages", messages);
        HttpRequest.Builder b = HttpRequest.newBuilder()
            .uri(URI.create(cfg.captchaVlmEndpoint))
            .timeout(Duration.ofMillis(cfg.captchaTimeoutMs))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)));
        String key = apiKey();
        if (key != null) b.header("Authorization", "Bearer " + key);
        return b.build();
    }

    private static String contentOf(String responseBody) {
        return JsonParser.parseString(responseBody).getAsJsonObject()
            .getAsJsonArray("choices").get(0).getAsJsonObject()
            .getAsJsonObject("message").get("content").getAsString();
    }

    private void startSolve(byte[] png) {
        phase = Phase.SOLVING;
        phaseDeadline = System.currentTimeMillis() + cfg.captchaTimeoutMs + 2000;
        final boolean mapPrompt = useMapPrompt();
        mapPromptUsed = mapPrompt;

        String prompt = mapPrompt ? cfg.captchaMapPrompt : cfg.captchaPrompt;
        if (!wrongAnswers.isEmpty()) {
            String retry = mapPrompt ? cfg.captchaMapRetryPrompt : cfg.captchaRetryPrompt;
            prompt += retry.replace("{rejected}", String.join(", ", wrongAnswers));
        }
        final String promptText = prompt;
        // deterministic first try; a little heat on retries so the same image
        // doesn't produce the same rejected guess again
        HttpRequest req = buildRequest(png, promptText, attempt <= 1 ? 0.0 : 0.5, mapPrompt ? 64 : 256);
        final byte[] second = mapPrompt && attempt <= 1 ? secondPng : null;
        http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
            if (err != null) {
                vlmError.set("request failed: " + rootMessage(err));
                if (isConnectFailure(err)) vlmConnectFailed.set(true);
                return;
            }
            if (resp.statusCode() != 200) {
                vlmError.set("HTTP " + resp.statusCode() + ": " + truncate(resp.body(), 200));
                return;
            }
            try {
                String content2 = contentOf(resp.body());
                vlmRaw.set(truncate(content2, 300));
                List<String> ranked = new ArrayList<>();
                if (mapPrompt) {
                    String answer = ChatClassifier.parseAnswerArray(content2, cfg.captchaPreserveCase);
                    if (answer != null) {
                        ranked.add(answer);
                        // Second opinion from the other render (bench 2026-09-03: x4 bilinear
                        // read p8b where x2 read pBb); a disagreement is the second guess.
                        if (second != null) {
                            String other = null;
                            try {
                                HttpResponse<String> r2 = http.send(buildRequest(second, promptText, 0.0, 64), HttpResponse.BodyHandlers.ofString());
                                if (r2.statusCode() == 200) other = ChatClassifier.parseAnswerArray(contentOf(r2.body()), cfg.captchaPreserveCase);
                            } catch (Exception e) {
                                other = null;
                            }
                            vlmSecond.set(other);
                            if (other != null && !other.equals(answer)) ranked.add(other);
                        }
                        if (ranked.size() < 2) {
                            String alt = ChatClassifier.lookalikeAlt(answer, cfg.captchaLookalikes, cfg.captchaCaseAmbiguous);
                            if (alt != null && !alt.equals(answer)) ranked.add(alt);
                        }
                    }
                } else {
                    // Sonar path: ranked ANSWER/ALT lines, lowercase, de-duped.
                    Matcher m = ANSWER_RE.matcher(content2);
                    while (m.find()) {
                        String g = m.group(1).trim().toLowerCase();
                        if (!g.isEmpty() && !ranked.contains(g)) ranked.add(g);
                    }
                }
                if (!ranked.isEmpty()) {
                    vlmCandidates.set(ranked);
                } else {
                    vlmError.set("no answer in: " + truncate(content2, 200));
                }
            } catch (Exception e) {
                vlmError.set("bad response json: " + e);
            }
        });
    }

    private static String truncate(String s, int n) {
        if (s == null) return null;
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    // ------------------------------------------------------------------- send

    /** Typed through the chat screen unless a GUI is up (opening chat over a container desyncs it). */
    private boolean typedSend(MinecraftClient client) {
        return !"gui".equals(source) && client.currentScreen == null;
    }

    private void sendDirect(MinecraftClient client, String answer, String out, String why) {
        if (client.getNetworkHandler() == null) { fail(client, "send", "no network handler"); return; }
        if (out.startsWith("/")) {
            client.getNetworkHandler().sendChatCommand(out.substring(1));
        } else {
            client.getNetworkHandler().sendChatMessage(out);
        }
        afterSend(client, System.currentTimeMillis(), answer, out, false, 0, why);
    }

    private void afterSend(MinecraftClient client, long now, String answer, String out, boolean typed, int typos, String directWhy) {
        lastSentAnswer = answer;
        answersSent++;
        answerSentAt = now;
        if ("map".equals(captureMode)) mapAnswersSent = answersSent;
        pendingOut = null;
        log("captcha_answer", "answer", answer, "sent", out, "typed", typed, "typos", typos,
            "typedMismatch", typed && typer.typedMismatch() ? true : null,
            "directWhy", directWhy, "answersSent", answersSent, "attempt", attempt, "source", source,
            "mode", captureMode);
        feedback = null;
        phase = Phase.AWAITING_RESULT;
        phaseDeadline = now + cfg.captchaVerifyWaitMs;
    }

    private static void say(MinecraftClient client, String msg) {
        if (client.player != null) client.player.sendMessage(Text.literal(msg), false);
    }
}

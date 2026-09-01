package dev.drew.ycbotchallenge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import net.minecraft.block.MapColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
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
 * Auto-solves the organizers' map captcha with a local Qwen3-VL served by vLLM.
 *
 * Flow: detect (chat/gui, from the existing pause path) -> settle -> capture
 * (held map pixels > item-frame map pixels > framebuffer screenshot) -> POST to
 * the local OpenAI-compatible endpoint -> parse "ANSWER: xxx" -> send to chat
 * -> watch for a success/retry message -> resume the grind (or retry, then
 * fall back to the old pause-for-human behavior).
 *
 * All Minecraft state is touched on the client tick thread; only the HTTP
 * round-trip and PNG encoding happen off-thread.
 */
public class CaptchaSolver {
    private enum Phase { IDLE, SETTLING, CAPTURING, SOLVING, TYPING, AWAITING_RESULT }

    /** What the client should do when solving ends. */
    public interface Callbacks {
        void onSolved(MinecraftClient client);
        void onFailed(MinecraftClient client, String reason, String detail);
    }

    /** Matches "ANSWER: xxx" and "ALT: yyy" lines — the model returns a ranked top-3. */
    private static final Pattern ANSWER_RE =
        Pattern.compile("(?:ANSWER|ALT)\\s*\\d*\\s*:\\s*([^\\s]+)", Pattern.CASE_INSENSITIVE);
    private static final Gson GSON = new Gson();

    private final YCBotChallengeConfig cfg;
    private final Callbacks callbacks;
    private final HttpClient http;
    private final List<Pattern> solvedRes = new ArrayList<>();
    private final List<Pattern> retryRes = new ArrayList<>();
    private EventLogger logger;

    private Phase phase = Phase.IDLE;
    private long phaseDeadline = 0;
    private int attempt = 0;
    /** Answers actually sent to chat for the current captcha — hard-capped, never spam. */
    private int answersSent = 0;
    private String source = null;

    // written by background threads / message handler, consumed on the tick thread
    private final AtomicReference<byte[]> capturedPng = new AtomicReference<>();
    private final AtomicReference<String> captureError = new AtomicReference<>();
    private final AtomicReference<List<String>> vlmCandidates = new AtomicReference<>();
    private final AtomicReference<String> vlmError = new AtomicReference<>();
    /** Ranked guesses from the last model call; rejected ones are consumed in order. */
    private final List<String> candidates = new ArrayList<>();
    /** The captcha image we're working on — kept so a rejection can re-prompt
     *  the model on the same image instantly (prefix-cached, ~1s) without
     *  re-capturing. */
    private byte[] lastPng = null;
    private volatile String feedback = null; // "solved" | "retry"
    private String lastSentAnswer = null;
    /** Guess waiting out the human-ish typing delay before being sent. */
    private String pendingAnswer = null;
    /** Sonar re-uses the same image across tries, so a deterministic retry would
     *  repeat the identical wrong guess — remember rejects and feed them back. */
    private final List<String> wrongAnswers = new ArrayList<>();

    public CaptchaSolver(YCBotChallengeConfig cfg, Callbacks callbacks) {
        this.cfg = cfg;
        this.callbacks = callbacks;
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

    public String hudLine() {
        if (phase == Phase.IDLE) return null;
        String what = switch (phase) {
            case SETTLING -> "waiting for captcha to render";
            case CAPTURING -> "capturing";
            case SOLVING -> "asking Qwen";
            case TYPING -> "typing answer...";
            case AWAITING_RESULT -> lastSentAnswer != null
                ? "tried '" + lastSentAnswer + "' (" + answersSent + "/" + cfg.captchaMaxAnswers + "), verifying"
                : "answer sent, verifying";
            default -> "";
        };
        return "§bcaptcha: " + what + "§r";
    }

    private void log(String type, Object... kv) {
        if (logger != null) logger.log(type, kv);
    }

    /** Kick off a solve. Call only when isActive() is false. */
    public void begin(MinecraftClient client, String detectSource, String detail) {
        source = detectSource;
        attempt = 1;
        answersSent = 0;
        feedback = null;
        lastSentAnswer = null;
        wrongAnswers.clear();
        candidates.clear();
        lastPng = null;
        capturedPng.set(null);
        captureError.set(null);
        vlmCandidates.set(null);
        vlmError.set(null);
        phase = Phase.SETTLING;
        phaseDeadline = System.currentTimeMillis() + cfg.captchaSettleMs;
        log("captcha_detected", "source", detectSource, "detail", detail, "autoSolve", true);
        say(client, "§e[YCBotChallenge] captcha detected — solving with local Qwen...");
    }

    public void cancel() {
        phase = Phase.IDLE;
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

    /** Call every client tick while active. Keeps the player inert; drives the state machine. */
    public void tick(MinecraftClient client) {
        if (client.player == null || client.world == null) { cancel(); return; }
        long now = System.currentTimeMillis();
        switch (phase) {
            case SETTLING -> {
                if (now >= phaseDeadline) startCapture(client);
            }
            case CAPTURING -> {
                String err = captureError.getAndSet(null);
                if (err != null) { fail(client, "capture", err); return; }
                byte[] png = capturedPng.getAndSet(null);
                if (png != null) {
                    lastPng = png;
                    log("captcha_captured", "bytes", png.length, "attempt", attempt);
                    startSolve(png);
                } else if (now >= phaseDeadline) {
                    fail(client, "capture", "screenshot never arrived");
                }
            }
            case SOLVING -> {
                String err = vlmError.getAndSet(null);
                if (err != null) { retryOrFail(client, "vlm", err); return; }
                List<String> got = vlmCandidates.getAndSet(null);
                if (got != null) {
                    candidates.clear();
                    candidates.addAll(got); // ranked, de-duped, best first
                    log("captcha_candidates", "candidates", candidates, "attempt", attempt);
                    submitNextCandidate(client, now);
                } else if (now >= phaseDeadline) {
                    retryOrFail(client, "vlm", "timed out waiting for the model");
                }
            }
            case TYPING -> {
                if (now >= phaseDeadline && pendingAnswer != null) {
                    String toSend = pendingAnswer;
                    pendingAnswer = null;
                    sendAnswer(client, toSend);
                    feedback = null;
                    phase = Phase.AWAITING_RESULT;
                    phaseDeadline = now + cfg.captchaVerifyWaitMs;
                }
            }
            case AWAITING_RESULT -> {
                String fb = feedback;
                if ("retry".equals(fb)) {
                    if (lastSentAnswer != null && !wrongAnswers.contains(lastSentAnswer)) {
                        wrongAnswers.add(lastSentAnswer);
                    }
                    candidates.remove(lastSentAnswer);
                    if (answersSent >= cfg.captchaMaxAnswers) {
                        // Hard cap — STOP. Never spam answers; hand over to the human.
                        fail(client, "server", answersSent + " guess(es) rejected — stopping, no spam");
                    } else if (lastPng != null) {
                        // Re-prompt the model on the same image with the rejection
                        // as feedback (flip 3<->4 letters, etc). Prefill is cached,
                        // so this is ~1s — smarter than a pre-committed ALT.
                        attempt++;
                        log("captcha_reprompt", "rejected", wrongAnswers, "attempt", attempt);
                        startSolve(lastPng);
                    } else if (!candidates.isEmpty()) {
                        submitNextCandidate(client, now);
                    } else {
                        fail(client, "server", "rejected with nothing left to try");
                    }
                } else if ("solved".equals(fb) || now >= phaseDeadline) {
                    phase = Phase.IDLE;
                    log("captcha_solved", "attempt", attempt, "answer", lastSentAnswer,
                        "confirmed", "solved".equals(fb));
                    say(client, "§a[YCBotChallenge] captcha solved — resuming.");
                    callbacks.onSolved(client);
                }
            }
            default -> { }
        }
    }

    /** Send the top remaining candidate, respecting the hard answer cap. */
    private void submitNextCandidate(MinecraftClient client, long now) {
        if (answersSent >= cfg.captchaMaxAnswers) {
            fail(client, "server", "answer cap (" + cfg.captchaMaxAnswers + ") reached — stopping, no spam");
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
        // Human-ish pause before "typing" the answer.
        pendingAnswer = next;
        phase = Phase.TYPING;
        long min = Math.max(0, cfg.captchaAnswerDelayMinMs);
        long max = Math.max(min + 1, cfg.captchaAnswerDelayMaxMs);
        phaseDeadline = now + java.util.concurrent.ThreadLocalRandom.current().nextLong(min, max);
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
        phaseDeadline = System.currentTimeMillis() + cfg.captchaSettleMs;
    }

    private void fail(MinecraftClient client, String stage, String why) {
        phase = Phase.IDLE;
        log("captcha_failed", "stage", stage, "why", why, "attempts", attempt);
        callbacks.onFailed(client, stage, why);
    }

    // ---------------------------------------------------------------- capture

    private void startCapture(MinecraftClient client) {
        String mode = cfg.captchaCaptureMode;
        byte[] mapPng = null;
        if (!"screen".equals(mode)) {
            MapState map = findCaptchaMap(client);
            if (map != null) {
                try {
                    mapPng = renderMapPng(map, cfg.captchaMapScale);
                } catch (Exception e) {
                    YCBotChallengeClient.LOGGER.warn("Map render failed, falling back to screenshot: {}", e.toString());
                }
            }
        }
        if (mapPng != null) {
            capturedPng.set(mapPng);
            phase = Phase.CAPTURING;
            phaseDeadline = System.currentTimeMillis() + 5000;
            return;
        }
        if ("map".equals(mode)) {
            // explicitly map-only and no map found — treat as retryable (map may still be loading)
            retryOrFail(client, "capture", "no filled map in hands or nearby item frames");
            return;
        }
        // framebuffer screenshot; consumer runs later on the render thread
        phase = Phase.CAPTURING;
        phaseDeadline = System.currentTimeMillis() + 5000;
        ScreenshotRecorder.takeScreenshot(client.getFramebuffer(), image -> {
            try (image) {
                Path tmp = Files.createTempFile("ycbot-captcha", ".png");
                image.writeTo(tmp);
                byte[] bytes = Files.readAllBytes(tmp);
                Files.deleteIfExists(tmp);
                capturedPng.set(bytes);
            } catch (Exception e) {
                captureError.set("screenshot: " + e);
            }
        });
    }

    /** Held filled map (either hand) first, then the nearest item-frame map. */
    private MapState findCaptchaMap(MinecraftClient client) {
        MapState held = mapFromStack(client, client.player.getMainHandStack());
        if (held == null) held = mapFromStack(client, client.player.getOffHandStack());
        if (held != null) return held;

        ItemFrameEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (Entity e : client.world.getEntities()) {
            if (!(e instanceof ItemFrameEntity frame) || !frame.containsMap()) continue;
            double d = client.player.distanceTo(frame);
            if (d <= cfg.captchaMapSearchRadius && d < best) { best = d; nearest = frame; }
        }
        return nearest != null ? mapFromStack(client, nearest.getHeldItemStack()) : null;
    }

    private MapState mapFromStack(MinecraftClient client, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        MapIdComponent id = stack.get(DataComponentTypes.MAP_ID);
        if (id == null) return null;
        return FilledMapItem.getMapState(id, client.world);
    }

    /** 128x128 map colors -> upscaled PNG (nearest neighbor keeps glyph edges crisp). */
    static byte[] renderMapPng(MapState state, int scale) throws Exception {
        int s = Math.max(1, scale);
        BufferedImage img = new BufferedImage(128 * s, 128 * s, BufferedImage.TYPE_INT_RGB);
        for (int z = 0; z < 128; z++) {
            for (int x = 0; x < 128; x++) {
                int packed = state.colors[x + z * 128] & 0xFF;
                int abgr = MapColor.getRenderColor(packed); // vanilla packs this ABGR
                int rgb = ((abgr & 0xFF) << 16) | (abgr & 0xFF00) | ((abgr >> 16) & 0xFF);
                for (int dz = 0; dz < s; dz++) {
                    for (int dx = 0; dx < s; dx++) {
                        img.setRGB(x * s + dx, z * s + dz, rgb);
                    }
                }
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ solve

    private void startSolve(byte[] png) {
        phase = Phase.SOLVING;
        phaseDeadline = System.currentTimeMillis() + cfg.captchaTimeoutMs + 2000;

        JsonObject imagePart = new JsonObject();
        imagePart.addProperty("type", "image_url");
        JsonObject imageUrl = new JsonObject();
        imageUrl.addProperty("url", "data:image/png;base64," + Base64.getEncoder().encodeToString(png));
        imagePart.add("image_url", imageUrl);
        String prompt = cfg.captchaPrompt;
        if (!wrongAnswers.isEmpty()) {
            prompt += cfg.captchaRetryPrompt.replace("{rejected}", String.join(", ", wrongAnswers));
        }
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
        // deterministic first try; a little heat on retries so the same image
        // doesn't produce the same rejected guess again
        body.addProperty("temperature", attempt <= 1 ? 0.0 : 0.5);
        body.addProperty("max_tokens", 256);
        body.add("messages", messages);

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(cfg.captchaVlmEndpoint))
            .timeout(Duration.ofMillis(cfg.captchaTimeoutMs))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
            .build();

        http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
            if (err != null) { vlmError.set("request failed: " + err.getMessage()); return; }
            if (resp.statusCode() != 200) {
                vlmError.set("HTTP " + resp.statusCode() + ": " + truncate(resp.body(), 200));
                return;
            }
            try {
                String content2 = JsonParser.parseString(resp.body()).getAsJsonObject()
                    .getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString();
                // Collect the ranked ANSWER/ALT guesses, de-duped, order preserved.
                List<String> ranked = new ArrayList<>();
                Matcher m = ANSWER_RE.matcher(content2);
                while (m.find()) {
                    String g = m.group(1).trim().toLowerCase();
                    if (!g.isEmpty() && !ranked.contains(g)) ranked.add(g);
                }
                if (!ranked.isEmpty()) {
                    vlmCandidates.set(ranked);
                } else {
                    vlmError.set("no ANSWER line in: " + truncate(content2, 200));
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

    private void sendAnswer(MinecraftClient client, String answer) {
        lastSentAnswer = answer;
        answersSent++;
        String out = cfg.captchaAnswerTemplate.replace("{answer}", answer);
        log("captcha_answer", "answer", answer, "sent", out,
            "answersSent", answersSent, "attempt", attempt, "source", source);
        if (client.getNetworkHandler() == null) { fail(client, "send", "no network handler"); return; }
        if (out.startsWith("/")) {
            client.getNetworkHandler().sendChatCommand(out.substring(1));
        } else {
            client.getNetworkHandler().sendChatMessage(out);
        }
    }

    private static void say(MinecraftClient client, String msg) {
        if (client.player != null) client.player.sendMessage(Text.literal(msg), false);
    }
}

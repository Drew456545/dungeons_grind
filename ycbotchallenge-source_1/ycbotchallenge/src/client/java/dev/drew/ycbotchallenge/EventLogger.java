package dev.drew.ycbotchallenge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Append-only JSONL event log, schema-compatible with the Node analyze/sim
 * tools (t, iso, uptimeS, run, type, ..., ctx{boosts, rebirths, ascensions}).
 */
public class EventLogger {
    private static final Gson GSON = new Gson();
    private final Path file;
    private final String runLabel;
    private final long startedAt = System.currentTimeMillis();
    private final Supplier<JsonObject> context;
    /** "on" / "off" / "paused:<reason>" on every row (0.9.33): the economy keeps logging while the bot is off. */
    private final Supplier<String> botFlag;
    private BufferedWriter writer;

    public EventLogger(Path dir, String runLabel, Supplier<JsonObject> context) {
        this(dir, runLabel, context, null);
    }

    public EventLogger(Path dir, String runLabel, Supplier<JsonObject> context, Supplier<String> botFlag) {
        this.runLabel = runLabel;
        this.context = context;
        this.botFlag = botFlag;
        String stamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(":", "-").replace(".", "-");
        this.file = dir.resolve("events-" + runLabel + "-" + stamp + ".jsonl");
        try {
            Files.createDirectories(dir);
            this.writer = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            YCBotChallengeClient.LOGGER.error("Cannot open event log {}: {}", file, e.toString());
        }
    }

    public Path getFile() { return file; }

    private long rows = 0;

    /** Rows written so far (0.9.40: the perf row reports the log rate). */
    public synchronized long rowsWritten() { return rows; }

    public synchronized void log(String type, Object... kv) {
        if (writer == null) return;
        rows++;
        JsonObject row = new JsonObject();
        long now = System.currentTimeMillis();
        row.addProperty("t", now);
        row.addProperty("iso", Instant.ofEpochMilli(now).toString());
        row.addProperty("uptimeS", Math.round((now - startedAt) / 100.0) / 10.0);
        row.addProperty("run", runLabel);
        row.addProperty("type", type);
        if (botFlag != null) {
            String bot = botFlag.get();
            if (bot != null) row.addProperty("bot", bot);
        }
        for (int i = 0; i + 1 < kv.length; i += 2) {
            String key = String.valueOf(kv[i]);
            Object val = kv[i + 1];
            if (val == null) continue;
            switch (val) {
                case Number n -> row.addProperty(key, n);
                case Boolean b -> row.addProperty(key, b);
                case List<?> list -> row.add(key, GSON.toJsonTree(list));
                case Map<?, ?> map -> row.add(key, GSON.toJsonTree(map));
                case com.google.gson.JsonElement el -> row.add(key, el);
                default -> row.addProperty(key, String.valueOf(val));
            }
        }
        row.add("ctx", context.get());
        try {
            writer.write(GSON.toJson(row));
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            YCBotChallengeClient.LOGGER.warn("Event log write failed: {}", e.toString());
        }
    }

    public synchronized void close() {
        try { if (writer != null) writer.close(); } catch (IOException ignored) {}
        writer = null;
    }
}

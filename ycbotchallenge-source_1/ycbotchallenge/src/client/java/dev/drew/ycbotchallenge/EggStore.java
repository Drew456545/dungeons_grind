package dev.drew.ycbotchallenge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Companion eggs Drew spotlighted by hand (0.9.29): Ctrl+Shift+toggle while looking at the
 * egg saves its position for the current stage label ("lvl12"), bot on or off, in
 * {@code config/ycbotchallenge-eggs.json}. A visit on that stage uses it when the
 * dragon-egg block scan finds nothing. Pure Gson — unit-tested on a temp file.
 */
public final class EggStore {
    public static final class Egg {
        public double x;
        public double y;
        public double z;
        public String label;
        public long at;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Egg>>() {}.getType();

    private final Path file;
    private Map<String, Egg> eggs = new LinkedHashMap<>();

    public EggStore(Path file) {
        this.file = file;
        load();
    }

    public void load() {
        try {
            if (file != null && Files.exists(file)) {
                Map<String, Egg> m = GSON.fromJson(Files.readString(file), MAP_TYPE);
                if (m != null) {
                    Map<String, Egg> ok = new LinkedHashMap<>();
                    m.forEach((k, v) -> { if (k != null && v != null) ok.put(key(k), v); });
                    eggs = ok;
                }
            }
        } catch (Exception e) {
            YCBotChallengeClient.LOGGER.warn("Failed to read egg file {}: {}", file, e.toString());
        }
    }

    public static String key(String stage) {
        return stage == null || stage.isBlank() ? "unknown" : stage.trim().toLowerCase(Locale.ROOT);
    }

    public int size() { return eggs.size(); }

    public Egg get(String stage) { return eggs.get(key(stage)); }

    public void put(String stage, Egg egg) {
        if (egg == null) return;
        eggs.put(key(stage), egg);
        save();
    }

    private void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(eggs, MAP_TYPE));
        } catch (IOException e) {
            YCBotChallengeClient.LOGGER.warn("Failed to write egg file {}: {}", file, e.toString());
        }
    }
}

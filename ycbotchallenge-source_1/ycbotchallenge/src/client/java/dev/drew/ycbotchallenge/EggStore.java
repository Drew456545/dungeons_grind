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
                    // Legacy per-stage keys ("lvl12") fold into their location ("loc2"); the newest wins.
                    Map<String, Egg> ok = new LinkedHashMap<>();
                    m.forEach((k, v) -> {
                        if (k == null || v == null) return;
                        String nk = key(k);
                        Egg prev = ok.get(nk);
                        if (prev == null || v.at >= prev.at) ok.put(nk, v);
                    });
                    eggs = ok;
                }
            }
        } catch (Exception e) {
            YCBotChallengeClient.LOGGER.warn("Failed to read egg file {}: {}", file, e.toString());
        }
    }

    /** Stages per location on this server (Farm 1–10, Western 11–20, …). */
    public static final int DEFAULT_STAGES_PER_LOCATION = 10;

    /** 1-based location index of a stage: stages 1–10 → 1, 11–20 → 2. */
    public static int locationOf(int stage, int perLocation) {
        int per = Math.max(1, perLocation);
        return (Math.max(1, stage) - 1) / per + 1;
    }

    /**
     * 0.9.31: the key is the location, not the stage — "lvl12" → "loc2" — because one egg
     * serves ten stages and the ten-stage teleport lands at a new one. Labels that are not
     * "lvl<n>" keep their lowercase text; blank → "unknown".
     */
    public static String key(String stage, int perLocation) {
        if (stage == null || stage.isBlank()) return "unknown";
        Integer lvl = Economy.zoneLevelOf(stage);
        if (lvl != null) return "loc" + locationOf(lvl, perLocation);
        return stage.trim().toLowerCase(Locale.ROOT);
    }

    public static String key(String stage) {
        return key(stage, DEFAULT_STAGES_PER_LOCATION);
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

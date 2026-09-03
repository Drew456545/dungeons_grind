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
 * Learned money suffixes (0.9.25), persisted in {@code config/ycbotchallenge-suffixes.json}
 * so a rung the sidebar taught us survives a restart. Per server, not per user, so it is
 * not part of the per-username state file. Each entry carries its provenance
 * ({@link Amounts.Learned}: scale, confirmed, via, basis, raw, prevRaw, at). Deleting the
 * file relearns everything from the next rebirth cycle. Pure Gson — unit-tested in
 * EconomyChecks on a temp file.
 */
public final class SuffixStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Amounts.Learned>>() {}.getType();

    private final Path file;
    private Map<String, Amounts.Learned> entries = new LinkedHashMap<>();

    /** {@code file} may be null for an in-memory store (tests). */
    public SuffixStore(Path file) {
        this.file = file;
        load();
    }

    public Path file() { return file; }

    public void load() {
        try {
            if (file != null && Files.exists(file)) {
                Map<String, Amounts.Learned> m = GSON.fromJson(Files.readString(file), MAP_TYPE);
                if (m != null) {
                    Map<String, Amounts.Learned> up = new LinkedHashMap<>();
                    m.forEach((k, v) -> { if (k != null && v != null) up.put(key(k), v); });
                    entries = up;
                }
            }
        } catch (Exception e) {
            YCBotChallengeClient.LOGGER.warn("Failed to read suffix file {}: {}", file, e.toString());
        }
    }

    public static String key(String suffix) {
        return suffix == null ? null : suffix.trim().toUpperCase(Locale.ROOT);
    }

    /** Copy of every learned suffix, uppercase keys. */
    public Map<String, Amounts.Learned> all() {
        return new LinkedHashMap<>(entries);
    }

    public Amounts.Learned get(String suffix) {
        String k = key(suffix);
        return k == null ? null : entries.get(k);
    }

    /** Learned suffixes are rare events: every put writes through. */
    public void put(String suffix, Amounts.Learned e) {
        String k = key(suffix);
        if (k == null || k.isEmpty() || e == null) return;
        entries.put(k, e);
        save();
    }

    public void remove(String suffix) {
        String k = key(suffix);
        if (k != null && entries.remove(k) != null) save();
    }

    private void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(entries, MAP_TYPE));
        } catch (IOException e) {
            YCBotChallengeClient.LOGGER.warn("Failed to write suffix file {}: {}", file, e.toString());
        }
    }
}

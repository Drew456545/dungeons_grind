package dev.drew.ycbotchallenge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Mobs marked ignored by hand (0.9.26): Ctrl + toggle key while looking at one. Entity
 * ids change every join, so a mark is the mob's kind and position (each zone's AFK mob
 * stands on the same block forever) and matches any entity of that kind within
 * {@code manualIgnoreRadiusBlocks} of it. Persisted in {@code config/ycbotchallenge-ignored.json};
 * Ctrl + toggle on a marked mob removes the mark. Pure Gson — unit-tested on a temp file.
 */
public final class IgnoreStore {
    /** One mark. {@code label} is the plate text at marking time, for the log and the chat notice. */
    public static final class Mark {
        public String type;
        public double x;
        public double y;
        public double z;
        public String label;
        public long at;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<Mark>>() {}.getType();

    private final Path file;
    private List<Mark> marks = new ArrayList<>();

    /** {@code file} may be null for an in-memory store (tests). */
    public IgnoreStore(Path file) {
        this.file = file;
        load();
    }

    public Path file() { return file; }

    public void load() {
        try {
            if (file != null && Files.exists(file)) {
                List<Mark> m = GSON.fromJson(Files.readString(file), LIST_TYPE);
                if (m != null) {
                    List<Mark> ok = new ArrayList<>();
                    for (Mark k : m) if (k != null && k.type != null) ok.add(k);
                    marks = ok;
                }
            }
        } catch (Exception e) {
            YCBotChallengeClient.LOGGER.warn("Failed to read ignore file {}: {}", file, e.toString());
        }
    }

    public int size() { return marks.size(); }

    public List<Mark> all() { return new ArrayList<>(marks); }

    /** The mark of this kind nearest to the point within {@code radius}, or null. */
    public Mark findNear(String type, double x, double y, double z, double radius) {
        Mark best = null;
        double bestDist = Double.MAX_VALUE;
        for (Mark m : marks) {
            double dx = m.x - x, dy = m.y - y, dz = m.z - z;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (Economy.manualMarkMatches(type, m.type, dist, radius) && dist < bestDist) {
                best = m;
                bestDist = dist;
            }
        }
        return best;
    }

    public void add(Mark m) {
        if (m == null || m.type == null) return;
        marks.add(m);
        save();
    }

    /** Remove the nearest matching mark; returns it, or null when there was none. */
    public Mark removeNear(String type, double x, double y, double z, double radius) {
        Mark m = findNear(type, x, y, z, radius);
        if (m != null) {
            marks.remove(m);
            save();
        }
        return m;
    }

    private void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(marks, LIST_TYPE));
        } catch (IOException e) {
            YCBotChallengeClient.LOGGER.warn("Failed to write ignore file {}: {}", file, e.toString());
        }
    }
}

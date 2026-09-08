package dev.drew.ycbotchallenge;

import com.google.gson.Gson;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 0.9.62: the file half of every Gson-backed store (state, suffixes, eggs, ignores, the
 * config). Four copies of the same read and write lived in the stores; the write is atomic
 * now - the JSON lands in a sibling ".tmp" and is moved over the file, so a crash mid-write
 * leaves the old file rather than a truncated one.
 */
public final class JsonStore {
    private JsonStore() {}

    /** The parsed file, or null when there is none or it will not parse (logged). */
    public static <T> T read(Path file, Type type, Gson gson, String what) {
        try {
            if (file != null && Files.exists(file)) return gson.fromJson(Files.readString(file), type);
        } catch (Exception e) {
            YCBotChallengeClient.LOGGER.warn("Failed to read {} file {}: {}", what, file, e.toString());
        }
        return null;
    }

    /** Write the JSON atomically (tmp + move); failures are logged, never thrown. */
    public static void write(Path file, String json, String what) {
        if (file == null) return;
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException | UnsupportedOperationException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            YCBotChallengeClient.LOGGER.warn("Failed to write {} file {}: {}", what, file, e.toString());
        }
    }
}

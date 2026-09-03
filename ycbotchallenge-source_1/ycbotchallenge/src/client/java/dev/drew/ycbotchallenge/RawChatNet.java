package dev.drew.ycbotchallenge;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Evidence net for server lines nothing classified: admits at most
 * {@code perMinute} distinct lines per minute (repeats within 10 minutes are
 * dropped) so the JSONL captures the wording of a captcha prompt, a warning or
 * a new reward line the next time it appears, without flooding the log with
 * player chat. Pure; unit-checked.
 */
final class RawChatNet {
    private final int perMinute;
    private final Map<String, Long> seen = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Long> e) { return size() > 512; }
    };
    private long windowStart;
    private int inWindow;

    RawChatNet(int perMinute) {
        this.perMinute = perMinute;
    }

    /** True when {@code text} should be logged now. */
    boolean admit(String text, long now) {
        if (perMinute <= 0 || text == null || text.isBlank()) return false;
        Long last = seen.get(text);
        if (last != null && now - last < 600_000) return false;
        if (now - windowStart >= 60_000) { windowStart = now; inWindow = 0; }
        if (inWindow >= perMinute) return false;
        inWindow++;
        seen.put(text, now);
        return true;
    }
}
